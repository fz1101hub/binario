#!/usr/bin/env python3
"""Offline regression tests for tools/build_gtfs_packs.py."""
from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import shutil
import sys
import tempfile
import unittest
import zipfile
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/build_gtfs_packs.py"
SPEC = importlib.util.spec_from_file_location("build_gtfs_packs", SCRIPT)
assert SPEC and SPEC.loader
builder = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = builder
SPEC.loader.exec_module(builder)
FIXTURE = ROOT / "tests/fixtures/gtfs"


def write_fixture_zip(target: Path) -> None:
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(FIXTURE.iterdir()):
            archive.write(file, file.name)


def write_manifest(directory: Path, *, last_service_date: str = "2027-01-15") -> None:
    payload = {
        "schemaVersion": 2,
        "generatedAt": "2026-01-01",
        "purpose": "test fixture",
        "packs": [
            {
                "sourceId": "amt-genova-casella-gtfs",
                "asset": "gtfs/amt-genova-casella-gtfs.zip",
                "sourceUrl": "http://example.test/amt.zip",
                "accessMode": "GATEWAY_ONLY",
                "attribution": "fixture",
                "sha256": "0" * 64,
                "lastServiceDate": last_service_date,
                "notes": "fixture",
            }
        ],
    }
    (directory / "manifest.json").write_text(json.dumps(payload), encoding="utf-8")


class FakeResponse:
    def __init__(self, payload: bytes, length: str | None = None) -> None:
        self._stream = io.BytesIO(payload)
        self.headers = {} if length is None else {"Content-Length": length}

    def read(self, size: int = -1) -> bytes:
        return self._stream.read(size)

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, *_: object) -> None:
        return None


class BuildGtfsPacksTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="gtfs-pipeline-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)

    def make_input(self) -> Path:
        input_dir = self.temp / "input"
        input_dir.mkdir()
        write_fixture_zip(input_dir / "amt-genova-casella-gtfs.zip")
        write_manifest(input_dir)
        return input_dir

    def test_amt_casella_filter_keeps_only_reviewed_fgc_route(self) -> None:
        input_dir = self.make_input()
        output_dir = self.temp / "output"
        manifest = builder.build_packs(input_dir, output_dir, generated_at="2026-01-01")

        self.assertEqual("2027-01-15", manifest["packs"][0]["lastServiceDate"])
        self.assertEqual(["FGC-164"], manifest["packs"][0]["filter"]["routeIds"])
        with zipfile.ZipFile(output_dir / "amt-genova-casella-gtfs.zip") as archive:
            routes = builder.read_table(archive, "routes.txt", required=True)
            trips = builder.read_table(archive, "trips.txt", required=True)
            stops = builder.read_table(archive, "stops.txt", required=True)
            frequencies = builder.read_table(archive, "frequencies.txt", required=True)
            assert routes and trips and stops and frequencies
            self.assertEqual(["FGC-164"], [row["route_id"] for row in routes.rows])
            self.assertEqual(["rail-trip"], [row["trip_id"] for row in trips.rows])
            self.assertEqual({"PARENT", "A"}, {row["stop_id"] for row in stops.rows})
            self.assertEqual(["rail-trip"], [row["trip_id"] for row in frequencies.rows])
            filter_manifest = json.loads(archive.read("binario_filter_manifest.json"))
            self.assertEqual("explicit-route-ids", filter_manifest["policy"]["kind"])

    def test_build_is_deterministic_and_publishes_complete_metadata(self) -> None:
        input_dir = self.make_input()
        output_one = self.temp / "one"
        output_two = self.temp / "two"
        builder.build_packs(input_dir, output_one, generated_at="2026-01-01")
        builder.build_packs(input_dir, output_two, generated_at="2026-01-01")

        self.assertEqual(
            (output_one / "amt-genova-casella-gtfs.zip").read_bytes(),
            (output_two / "amt-genova-casella-gtfs.zip").read_bytes(),
        )
        manifest = json.loads((output_one / "manifest.json").read_text(encoding="utf-8"))
        zip_hash = hashlib.sha256((output_one / "amt-genova-casella-gtfs.zip").read_bytes()).hexdigest()
        self.assertEqual(zip_hash, manifest["packs"][0]["sha256"])
        self.assertEqual(
            f"{zip_hash}  amt-genova-casella-gtfs.zip\n",
            (output_one / "SHA256SUMS").read_text(encoding="utf-8"),
        )
        builder.verify_assets(output_one, as_of=date(2026, 1, 1), min_valid_days=30)

    def test_verification_rejects_tampered_hash_and_near_expiry_pack(self) -> None:
        input_dir = self.make_input()
        output_dir = self.temp / "output"
        builder.build_packs(input_dir, output_dir, generated_at="2026-01-01")
        sums = output_dir / "SHA256SUMS"
        sums.write_text("0" * 64 + "  amt-genova-casella-gtfs.zip\n", encoding="utf-8")
        with self.assertRaisesRegex(builder.BuildError, "hash SHA256SUMS incoerente"):
            builder.verify_assets(output_dir, as_of=date(2026, 1, 1), min_valid_days=30)

        builder.build_packs(input_dir, output_dir, generated_at="2026-01-01")
        with self.assertRaisesRegex(builder.BuildError, "scaduto o in scadenza"):
            builder.verify_assets(output_dir, as_of=date(2026, 12, 20), min_valid_days=30)

    def test_failed_build_does_not_replace_existing_asset_directory(self) -> None:
        input_dir = self.make_input()
        target = self.temp / "published"
        target.mkdir()
        (target / "keep.txt").write_text("previous release", encoding="utf-8")
        (input_dir / "amt-genova-casella-gtfs.zip").write_bytes(b"not a zip")

        with self.assertRaises(builder.BuildError):
            builder.build_packs(input_dir, target, generated_at="2026-01-01")
        self.assertEqual("previous release", (target / "keep.txt").read_text(encoding="utf-8"))

    def test_bounded_download_rejects_declared_and_streamed_oversize_without_output(self) -> None:
        destination = self.temp / "download.zip"
        with self.assertRaisesRegex(builder.DownloadError, "oltre limite"):
            builder.download_to_file(
                "https://example.test/feed.zip",
                destination,
                max_bytes=4,
                urlopen=lambda *_args, **_kwargs: FakeResponse(b"12345", length="5"),
            )
        self.assertFalse(destination.exists())

        with self.assertRaisesRegex(builder.DownloadError, "streaming"):
            builder.download_to_file(
                "https://example.test/feed.zip",
                destination,
                max_bytes=4,
                urlopen=lambda *_args, **_kwargs: FakeResponse(b"12345"),
            )
        self.assertFalse(destination.exists())

    def test_download_refuses_http_without_opening_network(self) -> None:
        called = False

        def forbidden(*_args: object, **_kwargs: object) -> FakeResponse:
            nonlocal called
            called = True
            return FakeResponse(b"")

        with self.assertRaisesRegex(builder.DownloadError, "HTTPS"):
            builder.download_to_file("http://example.test/feed.zip", self.temp / "no.zip", urlopen=forbidden)
        self.assertFalse(called)


if __name__ == "__main__":
    unittest.main()
