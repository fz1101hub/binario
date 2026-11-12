#!/usr/bin/env python3
"""Offline regression tests for tools/build_gtfs_packs.py."""
from __future__ import annotations

import csv
import hashlib
import importlib.util
import io
import json
import multiprocessing
import shutil
import sys
import tempfile
import unittest
import zipfile
from datetime import date
from pathlib import Path
from typing import Mapping
from unittest import mock

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


def write_fixture_zip_with_agency(target: Path, agency_name: str) -> None:
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(FIXTURE.iterdir()):
            if file.name == "agency.txt":
                archive.writestr(
                    "agency.txt",
                    file.read_text(encoding="utf-8").replace("AMT,AMT,", f"AMT,{agency_name},"),
                )
            else:
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


def rewrite_zip_with_different_deflate_bytes(source: Path, destination: Path) -> None:
    """Model a rebuild whose compressor differs while every member is unchanged."""
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_STORED) as rebuilt:
        for entry in original.infolist():
            if not entry.is_dir():
                rebuilt.writestr(entry.filename, original.read(entry.filename))


def publish_worker(staging: str, target: str, start: multiprocessing.synchronize.Event, results: multiprocessing.queues.Queue) -> None:
    start.wait()
    try:
        builder.atomic_replace_directory(Path(staging), Path(target))
        results.put("")
    except BaseException as error:
        results.put(repr(error))


def build_worker(
    input_directory: str,
    output_directory: str,
    source_id: str,
    override: str,
    start: multiprocessing.synchronize.Event,
    results: multiprocessing.queues.Queue,
) -> None:
    start.wait()
    try:
        builder.build_packs(
            Path(input_directory),
            Path(output_directory),
            input_overrides={source_id: Path(override)},
        )
        results.put("")
    except BaseException as error:
        results.put(repr(error))


class FakeResponse:
    def __init__(self, payload: bytes, length: str | None = None, final_url: str = "https://example.test/feed.zip") -> None:
        self._stream = io.BytesIO(payload)
        self.headers = {} if length is None else {"Content-Length": length}
        self._final_url = final_url

    def read(self, size: int = -1) -> bytes:
        return self._stream.read(size)

    def __enter__(self) -> "FakeResponse":
        return self

    def geturl(self) -> str:
        return self._final_url

    def __exit__(self, *_: object) -> None:
        return None


class BuildGtfsPacksTest(unittest.TestCase):
    def test_empty_optional_translation_file_does_not_block_timetable_refresh(self) -> None:
        source = self.temp / "empty-translations.zip"
        output = self.temp / "timetable.zip"
        with zipfile.ZipFile(source, "w") as archive:
            for file in sorted(FIXTURE.iterdir()):
                if file.name != "translations.txt":
                    archive.write(file, file.name)
            archive.writestr("translations.txt", b"")
        builder.filter_archive(source, output, "amt-genova-casella-gtfs")
        with zipfile.ZipFile(output) as archive:
            self.assertNotIn("translations.txt", archive.namelist())
            self.assertIn("stop_times.txt", archive.namelist())

    def test_empty_required_table_is_still_rejected(self) -> None:
        source = self.temp / "empty-routes.zip"
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("routes.txt", b"")
        with zipfile.ZipFile(source) as archive:
            with self.assertRaisesRegex(builder.BuildError, "tabella vuota"):
                builder.read_table(archive, "routes.txt", required=True)

    def test_large_unused_shapes_are_not_opened_and_references_are_removed(self) -> None:
        source = self.temp / "with-shapes.zip"
        output = self.temp / "timetable.zip"
        with zipfile.ZipFile(source, "w", compression=zipfile.ZIP_STORED) as archive:
            for file in sorted(FIXTURE.iterdir()):
                if file.name != "shapes.txt":
                    archive.writestr(file.name, file.read_text())
            archive.writestr("shapes.txt", b"unused geometry" * 200)
        with mock.patch.object(builder, "MAX_ARCHIVE_MEMBER_BYTES", 512):
            with zipfile.ZipFile(source) as archive:
                with self.assertRaisesRegex(builder.BuildError, "membro GTFS oltre limite"):
                    builder.validate_archive_limits(archive)
            with mock.patch.object(builder, "read_table", wraps=builder.read_table) as reader:
                builder.filter_archive(source, output, "amt-genova-casella-gtfs")
                self.assertFalse(any(call.args[1] == "shapes.txt" for call in reader.call_args_list))
        with zipfile.ZipFile(output) as archive:
            self.assertNotIn("shapes.txt", archive.namelist())
            self.assertNotIn("shape_id", archive.read("trips.txt").decode().splitlines()[0])
            metadata = json.loads(archive.read(builder.FILTER_MANIFEST_MEMBER))
            self.assertEqual(metadata["sourceArchiveSha256"], builder.sha256_file(source))

    def test_omitted_geometry_still_obeys_total_archive_limit(self) -> None:
        source = self.temp / "large-geometry.zip"
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("shapes.txt", b"geometry" * 200)
        with zipfile.ZipFile(source) as archive, mock.patch.object(builder, "MAX_ARCHIVE_UNCOMPRESSED_BYTES", 512):
            with self.assertRaisesRegex(builder.BuildError, "decompresso oltre limite"):
                builder.validate_archive_limits(archive, omit_geometry=True)

    def test_omitted_geometry_still_obeys_compression_ratio_limit(self) -> None:
        source = self.temp / "geometry-bomb.zip"
        with zipfile.ZipFile(source, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("shapes.txt", b"A" * 1_000_000)
        with zipfile.ZipFile(source) as archive:
            with self.assertRaisesRegex(builder.BuildError, "rapporto di compressione"):
                builder.validate_archive_limits(archive, omit_geometry=True)

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

        # The lone RAIL exception on 2027-01-15 does not extend the coverage past the calendar year.
        self.assertEqual("2026-12-31", manifest["packs"][0]["lastServiceDate"])
        self.assertEqual(["FGC-191"], manifest["packs"][0]["filter"]["routeIds"])
        with zipfile.ZipFile(output_dir / "amt-genova-casella-gtfs.zip") as archive:
            routes = builder.read_table(archive, "routes.txt", required=True)
            trips = builder.read_table(archive, "trips.txt", required=True)
            stops = builder.read_table(archive, "stops.txt", required=True)
            stop_times = builder.read_table(archive, "stop_times.txt", required=True)
            assert routes and trips and stops and stop_times
            self.assertEqual(["FGC-191"], [row["route_id"] for row in routes.rows])
            # Audit2 android-planner-8: the trip runs every 30 minutes from 08:00 to 09:00 (frequencies.txt, which no
            # reader of the packs knows): two trips, the second shifted by 30 minutes, and no frequencies.txt left.
            self.assertEqual(["rail-trip@080000", "rail-trip@083000"], [row["trip_id"] for row in trips.rows])
            self.assertEqual(
                [("rail-trip@080000", "08:00:00", "PARENT"), ("rail-trip@080000", "08:30:00", "A"),
                 ("rail-trip@083000", "08:30:00", "PARENT"), ("rail-trip@083000", "09:00:00", "A")],
                [(row["trip_id"], row["departure_time"], row["stop_id"]) for row in stop_times.rows],
            )
            self.assertEqual({"PARENT", "A"}, {row["stop_id"] for row in stops.rows})
            self.assertNotIn("frequencies.txt", archive.namelist())
            filter_manifest = json.loads(archive.read("binario_filter_manifest.json"))
            self.assertNotIn("tripRules", filter_manifest)
            self.assertEqual("explicit-route-ids", filter_manifest["policy"]["kind"])
            self.assertEqual(
                builder.canonical_member_digests(archive, "binario_filter_manifest.json"),
                filter_manifest["memberSha256"],
            )

    def test_only_rebuilds_from_the_input_directory_not_from_the_published_pack(self) -> None:
        # Audit data-tools-10: a manual "--only X --input-dir B" rebuilt X from the filtered pack already in the output
        # directory and reported success, never opening the new archive in B.
        first = self.make_input()
        output_dir = self.temp / "output"
        builder.build_packs(first, output_dir, generated_at="2026-01-01")
        second = self.temp / "second"
        second.mkdir()
        write_fixture_zip_with_agency(second / "amt-genova-casella-gtfs.zip", "AMT Nuova")
        write_manifest(second)
        manifest = builder.build_packs(second, output_dir, generated_at="2026-01-02", only=["amt-genova-casella-gtfs"])
        pack = manifest["packs"][0]
        self.assertEqual(hashlib.sha256((second / "amt-genova-casella-gtfs.zip").read_bytes()).hexdigest(),
                         pack["sourceArchiveSha256"])
        with zipfile.ZipFile(output_dir / "amt-genova-casella-gtfs.zip") as archive:
            self.assertIn("AMT Nuova", archive.read("agency.txt").decode("utf-8"))
        # Without the archive in the input directory the rebuild fails instead of reusing the published pack.
        (second / "amt-genova-casella-gtfs.zip").unlink()
        published = (output_dir / "amt-genova-casella-gtfs.zip").read_bytes()
        with self.assertRaises((builder.BuildError, OSError)):
            builder.build_packs(second, output_dir, generated_at="2026-01-03", only=["amt-genova-casella-gtfs"])
        self.assertEqual(published, (output_dir / "amt-genova-casella-gtfs.zip").read_bytes())

    def test_transit_directory_keeps_its_asset_prefix(self) -> None:
        input_dir = self.make_input()
        manifest = json.loads((input_dir / "manifest.json").read_text(encoding="utf-8"))
        manifest["packs"][0]["asset"] = "gtfs-transit/amt-genova-casella-gtfs.zip"
        (input_dir / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        output_dir = self.temp / "publish" / "gtfs-transit"
        built = builder.build_packs(input_dir, output_dir, generated_at="2026-01-01")

        self.assertEqual("gtfs-transit/amt-genova-casella-gtfs.zip", built["packs"][0]["asset"])
        published = json.loads((output_dir / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("gtfs-transit/amt-genova-casella-gtfs.zip", published["packs"][0]["asset"])
        builder.verify_assets(output_dir, as_of=None, min_valid_days=None)

    def test_urban_policy_keeps_metro_and_drops_buses_with_stop_modes(self) -> None:
        input_dir = self.temp / "urban-input"
        input_dir.mkdir()
        with zipfile.ZipFile(input_dir / "genova-amt-gtfs.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(FIXTURE.iterdir()):
                text = file.read_text(encoding="utf-8")
                if file.name == "routes.txt":
                    text = text.replace("Manin - Casella,2", "Brin - Brignole,1")
                archive.writestr(file.name, text)
        write_manifest(input_dir)
        manifest = json.loads((input_dir / "manifest.json").read_text(encoding="utf-8"))
        manifest["packs"][0].update({"sourceId": "genova-amt-gtfs", "asset": "gtfs-transit/genova-amt-gtfs.zip"})
        (input_dir / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        output_dir = self.temp / "gtfs-transit"
        built = builder.build_packs(input_dir, output_dir, generated_at="2026-01-01")

        self.assertEqual("gtfs-urban-route-types", built["packs"][0]["filter"]["kind"])
        with zipfile.ZipFile(output_dir / "genova-amt-gtfs.zip") as archive:
            routes = builder.read_table(archive, "routes.txt", required=True)
            modes = builder.read_table(archive, builder.STOP_MODES_MEMBER, required=True)
            assert routes and modes
            self.assertEqual(["FGC-191"], [row["route_id"] for row in routes.rows])
            self.assertNotIn("shapes.txt", archive.namelist())
            # The metro stop and its parent station carry "M"; the bus terminal is gone.
            self.assertEqual({"A": "M", "PARENT": "M"}, {row["stop_id"]: row["modes"] for row in modes.rows})

    def test_last_service_date_is_the_end_of_the_last_real_timetable_period(self) -> None:
        header = ("service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "start_date", "end_date")

        def calendar(*rows: tuple[str, ...]) -> builder.CsvTable:
            return builder.CsvTable(header, tuple(dict(zip(header, row)) for row in rows))

        def exceptions(*rows: tuple[str, str, str]) -> builder.CsvTable:
            return builder.CsvTable(("service_id", "date", "exception_type"), tuple(dict(zip(("service_id", "date", "exception_type"), row)) for row in rows))

        daily = ("DAILY", "1", "1", "1", "1", "1", "1", "1", "20260101", "20261231")
        # A single exception date months after the timetable (eav-gtfs) is not coverage.
        self.assertEqual(date(2026, 12, 31), builder.last_service_date(calendar(daily), exceptions(("EXTRA", "20270513", "1")), "eav"))
        # A last row ending on a Sunday the service never runs on (hzpp-gtfs) ends on its Saturday.
        until_december = ("YEAR", "1", "1", "1", "1", "1", "1", "1", "20260101", "20261206")
        last_week = ("WEEK", "1", "1", "1", "1", "1", "1", "0", "20261207", "20261213")
        self.assertEqual(date(2026, 12, 12), builder.last_service_date(calendar(until_december, last_week), builder.empty_calendar_dates(), "hzpp"))
        # A removal exception on the last day moves the end back; an added day after a short gap extends it.
        self.assertEqual(date(2026, 12, 30), builder.last_service_date(calendar(daily), exceptions(("DAILY", "20261231", "2")), "removal"))
        self.assertEqual(date(2027, 1, 6), builder.last_service_date(calendar(daily), exceptions(("DAILY", "20270106", "1")), "bridge"))
        # Weekend gaps and works closures do not end the coverage when a real period follows.
        mon_fri = ("MF", "1", "1", "1", "1", "1", "0", "0", "20260601", "20260731")
        autumn = ("AUT", "1", "1", "1", "1", "1", "1", "1", "20260914", "20261212")
        self.assertEqual(date(2026, 12, 12), builder.last_service_date(calendar(mon_fri, autumn), builder.empty_calendar_dates(), "works"))
        # Without any period of a week the last service day still counts; no dates at all is an error.
        self.assertEqual(date(2026, 3, 1), builder.last_service_date(builder.empty_calendar(), exceptions(("A", "20260101", "1"), ("A", "20260301", "1")), "sparse"))
        with self.assertRaisesRegex(builder.BuildError, "impossibile derivare lastServiceDate"):
            builder.last_service_date(builder.empty_calendar(), builder.empty_calendar_dates(), "empty")
        # The unfiltered fixture archive: the BUS row runs through 2027-01-01, its lone 2028-01-01 exception does not count.
        self.assertEqual(
            date(2027, 1, 1),
            builder.service_end_from_asset(self.make_input() / "amt-genova-casella-gtfs.zip", "amt-genova-casella-gtfs"),
        )

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
        self.assertEqual(4, manifest["schemaVersion"])
        self.assertEqual("complete ZIP byte stream (distribution integrity)", manifest["integrity"]["zipSha256"]["scope"])
        builder.verify_assets(output_one, as_of=date(2026, 1, 1), min_valid_days=30)

    def test_byte_different_rebuild_has_identical_canonical_content_digests(self) -> None:
        input_dir = self.make_input()
        output_dir = self.temp / "output"
        builder.build_packs(input_dir, output_dir, generated_at="2026-01-01")
        name = "amt-genova-casella-gtfs.zip"
        original = output_dir / name
        alternate = self.temp / "alternate.zip"
        rewrite_zip_with_different_deflate_bytes(original, alternate)

        self.assertNotEqual(original.read_bytes(), alternate.read_bytes())
        with zipfile.ZipFile(original) as first, zipfile.ZipFile(alternate) as second:
            first_metadata = json.loads(first.read("binario_filter_manifest.json"))
            second_metadata = json.loads(second.read("binario_filter_manifest.json"))
            self.assertEqual(first_metadata["memberSha256"], second_metadata["memberSha256"])
            self.assertEqual(
                builder.canonical_member_digests(first, "binario_filter_manifest.json"),
                builder.canonical_member_digests(second, "binario_filter_manifest.json"),
            )

        original_content_hash = builder.canonical_content_sha256(first_metadata["memberSha256"])
        self.assertEqual(original_content_hash, builder.canonical_content_sha256(second_metadata["memberSha256"]))
        self.assertNotEqual(builder.sha256_file(original), builder.sha256_file(alternate))

        # ZIP SHA-256 remains a distribution-integrity promise: release it with
        # the alternate container hash, while retaining the content attestation.
        alternate_hash = builder.sha256_file(alternate)
        original.replace(self.temp / "original.zip")
        alternate.replace(original)
        manifest_path = output_dir / "manifest.json"
        release_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        release_manifest["packs"][0]["sha256"] = alternate_hash
        self.assertEqual(original_content_hash, release_manifest["packs"][0]["contentSha256"])
        manifest_path.write_text(json.dumps(release_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (output_dir / "SHA256SUMS").write_text(f"{alternate_hash}  {name}\n", encoding="utf-8")

        builder.verify_assets(output_dir, as_of=date(2026, 1, 1), min_valid_days=30)

    def test_verification_rejects_table_changed_after_distribution_hash_is_released(self) -> None:
        input_dir = self.make_input()
        output_dir = self.temp / "output"
        builder.build_packs(input_dir, output_dir, generated_at="2026-01-01")
        name = "amt-genova-casella-gtfs.zip"
        original = output_dir / name
        changed = self.temp / "changed.zip"
        with zipfile.ZipFile(original) as source, zipfile.ZipFile(changed, "w", compression=zipfile.ZIP_STORED) as target:
            for entry in source.infolist():
                if entry.is_dir():
                    continue
                payload = source.read(entry.filename)
                if entry.filename == "routes.txt":
                    payload = payload.replace(b"FGC-191", b"FGC-192")
                target.writestr(entry.filename, payload)
        changed.replace(original)

        changed_hash = builder.sha256_file(original)
        release_manifest_path = output_dir / "manifest.json"
        release_manifest = json.loads(release_manifest_path.read_text(encoding="utf-8"))
        release_manifest["packs"][0]["sha256"] = changed_hash
        release_manifest_path.write_text(json.dumps(release_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (output_dir / "SHA256SUMS").write_text(f"{changed_hash}  {name}\n", encoding="utf-8")

        with self.assertRaisesRegex(builder.BuildError, "digest canonici member incoerenti: routes.txt"):
            builder.verify_assets(output_dir, as_of=date(2026, 1, 1), min_valid_days=30)

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

    def test_download_refuses_https_downgrade_redirects(self) -> None:
        destination = self.temp / "downgraded.zip"
        with self.assertRaisesRegex(builder.DownloadError, "URL finale"):
            builder.download_to_file(
                "https://example.test/feed.zip",
                destination,
                urlopen=lambda *_args, **_kwargs: FakeResponse(
                    b"not consumed",
                    final_url="http://mirror.example.test/feed.zip",
                ),
            )
        self.assertFalse(destination.exists())

        handler = builder.HttpsOnlyRedirectHandler()
        request = builder.urllib.request.Request("https://example.test/feed.zip")
        with self.assertRaisesRegex(builder.DownloadError, "redirect"):
            handler.redirect_request(
                request,
                io.BytesIO(),
                302,
                "Found",
                {},
                "http://mirror.example.test/feed.zip",
            )

    def test_refresh_accepts_only_explicit_open_direct_sources(self) -> None:
        for access_mode in ("AGREEMENT_REQUIRED", "PUBLIC_BEST_EFFORT", None, "UNKNOWN"):
            manifest = {
                "packs": [
                    {
                        "sourceId": "test-source",
                        "asset": "gtfs/test.zip",
                        "sourceUrl": "https://example.test/feed.zip",
                        **({"accessMode": access_mode} if access_mode is not None else {}),
                    }
                ]
            }
            with self.subTest(access_mode=access_mode):
                with self.assertRaisesRegex(builder.DownloadError, "non scaricabile direttamente"):
                    builder.refresh_overrides(manifest, set(), max_download_bytes=1024)

    def test_probe_does_not_contact_non_open_sources_and_contains_bad_urls(self) -> None:
        opener = mock.Mock()
        manifest = {
            "packs": [
                {
                    "sourceId": "licensed",
                    "sourceUrl": "https://licensed.example/feed.zip",
                    "accessMode": "AGREEMENT_REQUIRED",
                },
                {
                    "sourceId": "malformed",
                    "sourceUrl": "https://[invalid",
                    "accessMode": "OPEN_DIRECT",
                },
                {
                    "sourceId": "bad-port",
                    "sourceUrl": "https://example.test:not-a-port/feed.zip",
                    "accessMode": "OPEN_DIRECT",
                },
            ]
        }
        with mock.patch.object(builder.urllib.request, "build_opener", return_value=opener):
            rows = builder.probe_sources(manifest)

        self.assertEqual(["BLOCKED", "ERROR", "ERROR"], [row["status"] for row in rows])
        opener.open.assert_not_called()

    def test_archive_limits_reject_high_expansion_member(self) -> None:
        archive_path = self.temp / "expansion.zip"
        with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("translations.txt", b"A" * 1_000_000)

        with zipfile.ZipFile(archive_path) as archive:
            with self.assertRaisesRegex(builder.BuildError, "rapporto di compressione"):
                builder.validate_archive_limits(archive)

    def test_archive_entry_limit_counts_directory_records(self) -> None:
        archive_path = self.temp / "directories.zip"
        with zipfile.ZipFile(archive_path, "w") as archive:
            for index in range(builder.MAX_ARCHIVE_ENTRIES + 1):
                archive.writestr(f"directory-{index}/", b"")
        with zipfile.ZipFile(archive_path) as archive:
            with self.assertRaisesRegex(builder.BuildError, "troppe entry"):
                builder.validate_archive_limits(archive)

    def test_csv_rejects_duplicate_headers_and_oversized_fields(self) -> None:
        archive_path = self.temp / "invalid-csv.zip"
        with zipfile.ZipFile(archive_path, "w") as archive:
            archive.writestr("routes.txt", "route_id,route_type,route_type\nBUS,3,2\n")
            archive.writestr("stops.txt", "stop_id\n" + "A" * 200_000 + "\n")
        with zipfile.ZipFile(archive_path) as archive:
            with self.assertRaisesRegex(builder.BuildError, "colonne duplicate"):
                builder.read_table(archive, "routes.txt", required=True)
            with self.assertRaisesRegex(builder.BuildError, "ZIP/CSV non valida"):
                builder.read_table(archive, "stops.txt", required=True)

    def test_csv_rejects_multiline_fields_but_keeps_valid_quotes(self) -> None:
        archive_path = self.temp / "multiline-csv.zip"
        with zipfile.ZipFile(archive_path, "w") as archive:
            archive.writestr(
                "stops.txt",
                'stop_id,stop_name,location_type\nPARENT,"Genova\nPiazza Manin",1\n',
            )
            archive.writestr(
                "routes.txt",
                'route_id,route_short_name,route_type\nA,"Regionale, ""Veloce""",2\n',
            )
        with zipfile.ZipFile(archive_path) as archive:
            with self.assertRaisesRegex(builder.BuildError, "campo CSV multilinea"):
                builder.read_table(archive, "stops.txt", required=True)
            routes = builder.read_table(archive, "routes.txt", required=True)

        self.assertIsNotNone(routes)
        assert routes is not None
        self.assertEqual('Regionale, "Veloce"', routes.rows[0]["route_short_name"])

        crlf_path = self.temp / "multiline-crlf-csv.zip"
        with zipfile.ZipFile(crlf_path, "w") as archive:
            archive.writestr(
                "stops.txt",
                'stop_id,stop_name,location_type\r\nPARENT,"Genova\r\nPiazza Manin",1\r\n',
            )
        with zipfile.ZipFile(crlf_path) as archive:
            with self.assertRaisesRegex(builder.BuildError, "campo CSV multilinea"):
                builder.read_table(archive, "stops.txt", required=True)

    def test_build_retention_budget_fails_before_unbounded_materialization(self) -> None:
        archive_path = self.temp / "retained-budget.zip"
        with zipfile.ZipFile(archive_path, "w") as archive:
            archive.writestr("routes.txt", "route_id,route_type\nA,2\nB,2\nC,2\n")
        with zipfile.ZipFile(archive_path) as archive:
            with mock.patch.object(builder, "MAX_RETAINED_BUILD_ROWS", 2):
                with self.assertRaisesRegex(builder.BuildError, "budget righe trattenute"):
                    builder.read_table(
                        archive,
                        "routes.txt",
                        required=True,
                        budget=builder.RetentionBudget(),
                    )

        wide_path = self.temp / "wide-table.zip"
        header = ",".join(f"column_{index}" for index in range(builder.MAX_CSV_COLUMNS + 1))
        values = ",".join("x" for _ in range(builder.MAX_CSV_COLUMNS + 1))
        with zipfile.ZipFile(wide_path, "w") as archive:
            archive.writestr("routes.txt", f"{header}\n{values}\n")
        with zipfile.ZipFile(wide_path) as archive:
            with self.assertRaisesRegex(builder.BuildError, "troppe colonne"):
                builder.read_table(archive, "routes.txt", required=True)

        bytes_path = self.temp / "retained-bytes.zip"
        with zipfile.ZipFile(bytes_path, "w") as archive:
            archive.writestr("routes.txt", "route_id,route_type\nABCDE,2\n")
        with zipfile.ZipFile(bytes_path) as archive:
            with mock.patch.object(builder, "MAX_RETAINED_BUILD_UTF8_BYTES", 4):
                with self.assertRaisesRegex(builder.BuildError, "budget byte UTF-8"):
                    builder.read_table(
                        archive,
                        "routes.txt",
                        required=True,
                        budget=builder.RetentionBudget(),
                    )

        extra_fields_path = self.temp / "extra-fields.zip"
        with zipfile.ZipFile(extra_fields_path, "w") as archive:
            archive.writestr("routes.txt", "route_id,route_type\nA,2,extra,extra\n")
        with zipfile.ZipFile(extra_fields_path) as archive:
            with self.assertRaisesRegex(builder.BuildError, "campi oltre l'header"):
                builder.read_table(archive, "routes.txt", required=True)

        long_record_path = self.temp / "long-record.zip"
        with zipfile.ZipFile(long_record_path, "w") as archive:
            archive.writestr("routes.txt", "route_id,route_type\n" + ("A" * 64) + ",2\n")
        with zipfile.ZipFile(long_record_path) as archive:
            with mock.patch.object(builder, "MAX_CSV_PHYSICAL_LINE_UTF8_BYTES", 32):
                with self.assertRaisesRegex(builder.BuildError, "riga fisica CSV oltre limite"):
                    builder.read_table(archive, "routes.txt", required=True)

    def test_cleanup_failure_after_atomic_publish_is_non_fatal(self) -> None:
        target = self.temp / "published"
        staging = self.temp / "staging"
        target.mkdir()
        staging.mkdir()
        (target / "version.txt").write_text("old", encoding="utf-8")
        (staging / "version.txt").write_text("new", encoding="utf-8")

        with mock.patch.object(builder.shutil, "rmtree", side_effect=OSError("busy")):
            builder.atomic_replace_directory(staging, target)

        self.assertEqual("new", (target / "version.txt").read_text(encoding="utf-8"))
        self.assertTrue(any(self.temp.glob(".published.previous-*")))

    def test_concurrent_publishers_are_serialized_without_losing_a_release(self) -> None:
        context = multiprocessing.get_context("fork")
        target = self.temp / "concurrent-published"
        target.mkdir()
        (target / "version.txt").write_text("old", encoding="utf-8")
        staging_paths = []
        for version in ("first", "second"):
            staging = self.temp / f"staging-{version}"
            staging.mkdir()
            (staging / "version.txt").write_text(version, encoding="utf-8")
            staging_paths.append(staging)
        start = context.Event()
        results = context.Queue()
        processes = [
            context.Process(target=publish_worker, args=(str(staging), str(target), start, results))
            for staging in staging_paths
        ]
        for process in processes:
            process.start()
        start.set()
        for process in processes:
            process.join(10)

        self.assertEqual(["", ""], sorted(results.get(timeout=1) for _ in processes))
        self.assertTrue(all(process.exitcode == 0 for process in processes))
        self.assertIn((target / "version.txt").read_text(encoding="utf-8"), {"first", "second"})
        self.assertFalse(any(self.temp.glob(".concurrent-published.previous-*")))

    def test_concurrent_partial_builds_preserve_both_updates(self) -> None:
        context = multiprocessing.get_context("fork")
        input_directory = self.temp / "raw-input"
        output_directory = self.temp / "release"
        input_directory.mkdir()
        source_a = input_directory / "source-a.zip"
        source_b = input_directory / "source-b.zip"
        write_fixture_zip(source_a)
        write_fixture_zip(source_b)
        manifest = {
            "schemaVersion": 2,
            "generatedAt": "2026-01-01",
            "purpose": "concurrent fixture",
            "packs": [
                {
                    "sourceId": source_id,
                    "asset": f"gtfs/{source_id}.zip",
                    "sourceUrl": f"https://example.test/{source_id}.zip",
                    "accessMode": "OPEN_DIRECT",
                    "attribution": "fixture",
                    "lastServiceDate": "2027-01-15",
                }
                for source_id in ("source-a", "source-b")
            ],
        }
        (input_directory / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        builder.build_packs(input_directory, output_directory)

        override_a = self.temp / "override-a.zip"
        override_b = self.temp / "override-b.zip"
        write_fixture_zip_with_agency(override_a, "Agency A")
        write_fixture_zip_with_agency(override_b, "Agency B")
        start = context.Event()
        results = context.Queue()
        processes = [
            context.Process(
                target=build_worker,
                args=(str(input_directory), str(output_directory), source_id, str(override), start, results),
            )
            for source_id, override in (("source-a", override_a), ("source-b", override_b))
        ]
        for process in processes:
            process.start()
        start.set()
        for process in processes:
            process.join(20)

        self.assertEqual(["", ""], sorted(results.get(timeout=1) for _ in processes))
        final_manifest = json.loads((output_directory / "manifest.json").read_text(encoding="utf-8"))
        final_hashes = {pack["sourceId"]: pack["sourceArchiveSha256"] for pack in final_manifest["packs"]}
        self.assertEqual(builder.sha256_file(override_a), final_hashes["source-a"])
        self.assertEqual(builder.sha256_file(override_b), final_hashes["source-b"])



# Small extracts of the Trenitalia and Italo feeds committed for the app tests; zipped here as an upstream-like
# archive with an unused shapes.txt, so the full upstream archives are never needed.
EXTRACTS = ROOT / "app/src/test/resources/gtfs-extracts"
ASSETS = ROOT / "app/src/main/assets/gtfs"


def upstream_zip(source_id: str, target: Path) -> str:
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for file in sorted((EXTRACTS / source_id).glob("*.txt")):
            archive.write(file, file.name)
        archive.writestr("shapes.txt", "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\nS,45,9,1\n")
    return hashlib.sha256(target.read_bytes()).hexdigest()


def registry_entry(source_id: str, archive_sha256: str, commit: str = "a" * 40) -> dict[str, object]:
    repository = f"deryclem/{source_id}"
    return {
        "sourceId": source_id,
        "asset": f"gtfs/{source_id}.zip",
        "sourceUrl": f"https://raw.githubusercontent.com/{repository}/{commit}/gtfs.zip",
        "accessMode": "OPEN_DIRECT",
        "attribution": "fixture",
        "sha256": "0" * 64,
        "lastServiceDate": "2026-01-01",
        "notes": "fixture",
        "upstream": {
            "kind": "github-commit",
            "repository": f"https://github.com/{repository}",
            "commit": commit,
            "path": "gtfs.zip",
            "archiveSha256": archive_sha256,
            "licence": "CC BY 4.0",
            "officialDataset": "https://example.test/dataset",
        },
    }


def write_forged_archive(target: Path, member: str, provenance: Mapping[str, str]) -> None:
    """The archive of the audit proof of concept (A7-01): any valid GTFS plus a filter manifest it chose itself."""
    tables = {
        "agency.txt": "agency_id,agency_name,agency_url,agency_timezone\nA,Attacker Rail,https://x.invalid,Europe/Rome\n",
        "stops.txt": "stop_id,stop_name,stop_lat,stop_lon\nS1,Fake One,45.0,9.0\nS2,Fake Two,45.1,9.1\n",
        "routes.txt": "route_id,agency_id,route_short_name,route_type\nR1,A,X,2\n",
        "trips.txt": "route_id,service_id,trip_id\nR1,SV,T1\n",
        "stop_times.txt": "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n"
        "T1,08:00:00,08:00:00,S1,1\nT1,09:00:00,09:00:00,S2,2\n",
        "calendar.txt": "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
        "SV,1,1,1,1,1,1,1,20261101,20271231\n",
        "calendar_dates.txt": "service_id,date,exception_type\n",
        member: json.dumps(dict(provenance)),
    }
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, text in tables.items():
            archive.writestr(name, text)


def table(archive: zipfile.ZipFile, name: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO(archive.read(name).decode("utf-8"))))


class NationalPackTest(unittest.TestCase):
    """The pinned Trenitalia and Italo packs: coach policy, upstream pin, incremental build, reproducibility."""

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="gtfs-national-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)

    def build(self, source_ids: tuple[str, ...] = ("trenitalia-gtfs", "italo-gtfs")) -> tuple[Path, dict[str, Path]]:
        inputs = self.temp / "inputs"
        inputs.mkdir(exist_ok=True)
        archives = {source_id: inputs / f"upstream-{source_id}.zip" for source_id in source_ids}
        entries = [registry_entry(source_id, upstream_zip(source_id, path)) for source_id, path in archives.items()]
        registry = self.temp / "registry.json"
        registry.write_text(json.dumps({"generatedAt": "2026-11-02", "purpose": "test", "packs": entries}), encoding="utf-8")
        return registry, archives

    def test_trains_and_operator_coaches_are_kept_and_geometry_is_dropped(self) -> None:
        registry, archives = self.build()
        output = self.temp / "out"
        manifest = builder.build_packs(self.temp, output, manifest_path=registry, generated_at="2026-11-02", input_overrides=archives)
        policy = {pack["sourceId"]: pack["filter"] for pack in manifest["packs"]}
        self.assertEqual("gtfs-rail-and-operator-coach-route-types", policy["trenitalia-gtfs"]["kind"])
        self.assertIn("3", policy["italo-gtfs"]["routeTypes"])
        for source_id in ("trenitalia-gtfs", "italo-gtfs"):
            with zipfile.ZipFile(output / f"{source_id}.zip") as archive:
                self.assertNotIn("shapes.txt", archive.namelist())
                self.assertNotIn("shape_id", archive.read("trips.txt").decode("utf-8").splitlines()[0])
                route_types = {row["route_type"] for row in table(archive, "routes.txt")}
                self.assertEqual({"2", "3"}, route_types)
                # The upstream attribution travels with the pack.
                self.assertIn("attributions.txt", archive.namelist())
        with zipfile.ZipFile(output / "trenitalia-gtfs.zip") as archive:
            numbers = {row["trip_short_name"] for row in table(archive, "trips.txt")}
            self.assertTrue({"AO042", "PZ101", "9721", "41"} <= numbers)
        builder.verify_assets(output, as_of=None, min_valid_days=None)

    def test_rail_only_sources_still_drop_bus_routes(self) -> None:
        source = self.temp / "upstream.zip"
        upstream_zip("italo-gtfs", source)
        output = self.temp / "rail-only.zip"
        builder.filter_archive(source, output, "some-rail-gtfs")
        with zipfile.ZipFile(output) as archive:
            self.assertEqual({"2"}, {row["route_type"] for row in table(archive, "routes.txt")})
            self.assertNotIn("1060", {row["trip_short_name"] for row in table(archive, "trips.txt")})

    def test_build_is_reproducible_from_the_pinned_archive(self) -> None:
        registry, archives = self.build()
        first = builder.build_packs(self.temp, self.temp / "one", manifest_path=registry, generated_at="2026-11-02", input_overrides=archives)
        second = builder.build_packs(self.temp, self.temp / "two", manifest_path=registry, generated_at="2026-11-02", input_overrides=archives)
        self.assertEqual(first, second)
        for name in ("trenitalia-gtfs.zip", "italo-gtfs.zip", "manifest.json", "SHA256SUMS"):
            self.assertEqual((self.temp / "one" / name).read_bytes(), (self.temp / "two" / name).read_bytes())
        for pack in first["packs"]:
            self.assertEqual(pack["upstream"]["archiveSha256"], pack["sourceArchiveSha256"])

    def test_an_archive_other_than_the_pinned_one_is_rejected(self) -> None:
        registry, archives = self.build(("italo-gtfs",))
        payload = json.loads(registry.read_text(encoding="utf-8"))
        payload["packs"][0]["upstream"]["archiveSha256"] = "f" * 64
        registry.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaisesRegex(builder.BuildError, "diverso da quello fissato"):
            builder.build_packs(self.temp, self.temp / "out", manifest_path=registry, generated_at="2026-11-02", input_overrides=archives)
        self.assertFalse((self.temp / "out").exists())

    def test_an_archive_cannot_forge_the_pinned_provenance(self) -> None:
        # Audit A7-01 proof of concept: a fabricated feed carrying a nested binario_filter_manifest.json that names the
        # pinned digest. The pin is checked against the bytes on disk, so the forged archive is refused.
        registry, archives = self.build(("italo-gtfs",))
        output = self.temp / "out"
        builder.build_packs(self.temp, output, manifest_path=registry, generated_at="2026-11-02", input_overrides=archives)
        before = {path.name: path.read_bytes() for path in output.iterdir()}
        pinned = json.loads(registry.read_text(encoding="utf-8"))["packs"][0]["upstream"]["archiveSha256"]
        for member in ("nested/binario_filter_manifest.json", "binario_filter_manifest.json"):
            with self.subTest(member=member):
                forged = self.temp / "forged.zip"
                write_forged_archive(forged, member, {"sourceId": "italo-gtfs", "sourceArchiveSha256": pinned})
                with self.assertRaisesRegex(builder.BuildError, "diverso da quello fissato"):
                    builder.build_packs(
                        self.temp, output, manifest_path=registry, generated_at="2026-11-03",
                        input_overrides={"italo-gtfs": forged}, only=["italo-gtfs"],
                    )
                self.assertEqual(before, {path.name: path.read_bytes() for path in output.iterdir()})
                # Outside a pinned source the forged digest is not recorded either: the input's own hash is.
                filtered = self.temp / "forged-filtered.zip"
                result = builder.filter_archive(forged, filtered, "italo-gtfs")
                self.assertEqual(builder.sha256_file(forged), result.source_archive_sha256)

    def test_feed_text_in_an_error_never_starts_a_log_line(self) -> None:
        # Audit A7-02 proof of concept: a stops.txt header field with newlines, workflow commands and BINARIO_* lines.
        injected = ("x\n::warning title=injected::workflow command from feed data\nBINARIO_FILE gtfs-transit/manifest.json 2 "
                    + "4" * 64 + "\nBINARIO_B64 gtfs-transit/manifest.json e30=\nBINARIO_END gtfs-transit/manifest.json\ny")
        inputs = self.temp / "inputs"
        inputs.mkdir()
        with zipfile.ZipFile(inputs / "rail-gtfs.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("agency.txt", "agency_id,agency_name,agency_url,agency_timezone\nA,X,https://x.invalid,Europe/Rome\n")
            archive.writestr("stops.txt", 'stop_id,stop_name,stop_lat,stop_lon,"' + injected + '"\nS1,A,45,9,"a\nb"\nS2,B,45,9,c\n')
            archive.writestr("routes.txt", "route_id,agency_id,route_short_name,route_type\nR1,A,X,2\n")
            archive.writestr("trips.txt", "route_id,service_id,trip_id\nR1,SV,T1\n")
            archive.writestr("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n"
                             "T1,08:00:00,08:00:00,S1,1\nT1,09:00:00,09:00:00,S2,2\n")
            archive.writestr("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
                             "SV,1,1,1,1,1,1,1,20261101,20271231\n")
        registry = self.temp / "registry.json"
        registry.write_text(json.dumps({"generatedAt": "2026-11-03", "packs": [{
            "sourceId": "rail-gtfs", "asset": "gtfs/rail-gtfs.zip", "sourceUrl": "https://example.test/rail.zip",
            "accessMode": "OPEN_DIRECT"}]}), encoding="utf-8")
        stderr = io.StringIO()
        with mock.patch.object(sys, "stderr", stderr):
            code = builder.main(["--input-dir", str(inputs), "--manifest", str(registry), "--output-dir", str(self.temp / "out")])
        self.assertEqual(1, code)
        lines = stderr.getvalue().splitlines()
        self.assertEqual(1, len(lines))
        self.assertTrue(lines[0].startswith("ERRORE GTFS: campo CSV multilinea"))
        self.assertIn("\\n::warning", lines[0])

    def test_a_published_pack_rebuilt_in_place_keeps_its_recorded_provenance(self) -> None:
        registry, archives = self.build(("italo-gtfs",))
        output = self.temp / "out"
        built = builder.build_packs(self.temp, output, manifest_path=registry, generated_at="2026-11-02", input_overrides=archives)
        recorded = built["packs"][0]["sourceArchiveSha256"]
        rebuilt = builder.build_packs(output, output, generated_at="2026-11-03")
        self.assertEqual(recorded, rebuilt["packs"][0]["sourceArchiveSha256"])
        builder.verify_assets(output, as_of=None, min_valid_days=None)

    def test_the_source_url_must_be_the_pinned_commit(self) -> None:
        registry, _ = self.build(("italo-gtfs",))
        payload = json.loads(registry.read_text(encoding="utf-8"))
        payload["packs"][0]["sourceUrl"] = "https://raw.githubusercontent.com/deryclem/italo-gtfs/main/gtfs.zip"
        registry.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaisesRegex(builder.BuildError, "commit upstream fissato"):
            builder.read_manifest(registry)
        payload["packs"][0]["upstream"]["commit"] = "main"
        registry.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaisesRegex(builder.BuildError, "commit upstream non valido"):
            builder.read_manifest(registry)

    def test_only_rebuilds_the_named_sources_and_keeps_the_others_byte_for_byte(self) -> None:
        registry, archives = self.build()
        output = self.temp / "out"
        builder.build_packs(self.temp, output, manifest_path=registry, generated_at="2026-11-01", input_overrides=archives)
        italo_before = (output / "italo-gtfs.zip").read_bytes()
        italo_entry = next(p for p in json.loads((output / "manifest.json").read_text(encoding="utf-8"))["packs"] if p["sourceId"] == "italo-gtfs")
        (output / "trenitalia-gtfs.zip").unlink()
        (output / "trenitalia-gtfs.zip").write_bytes(b"broken")  # rebuilt from the pinned archive below
        manifest = builder.build_packs(
            self.temp, output, manifest_path=registry, generated_at="2026-11-02",
            input_overrides={"trenitalia-gtfs": archives["trenitalia-gtfs"]}, only=["trenitalia-gtfs"],
        )
        self.assertEqual("2026-11-02", manifest["generatedAt"])
        self.assertEqual(italo_before, (output / "italo-gtfs.zip").read_bytes())
        self.assertEqual(italo_entry, next(p for p in manifest["packs"] if p["sourceId"] == "italo-gtfs"))
        builder.verify_assets(output, as_of=None, min_valid_days=None)
        with self.assertRaisesRegex(builder.BuildError, "non registrato"):
            builder.build_packs(self.temp, output, manifest_path=registry, only=["missing-gtfs"])

    def test_shipped_national_packs_are_pinned_and_current(self) -> None:
        # The pins move with each reviewed refresh (tools/apply_ci_gtfs_refresh.py); what must hold is that each
        # names one commit and one archive of the reviewed repository and dataset, and that the manifest, the pin and
        # the pack's own filter manifest declare the same source archive.  These are declarations: proving that the
        # pack's content was filtered from that archive needs the archive (a network rebuild, audit data-tools-8).
        manifest = builder.read_manifest(ASSETS / "manifest.json")
        packs = {pack["sourceId"]: pack for pack in manifest["packs"]}
        expected = {
            "trenitalia-gtfs": ("https://github.com/deryclem/trenitalia-gtfs", "gtfs-trenitalia.zip", "1077621"),
            "italo-gtfs": ("https://github.com/deryclem/italo-gtfs", "gtfs-italo.zip", "1814124"),
        }
        for source_id, (repository, path, dataset) in expected.items():
            pack = packs[source_id]
            upstream = pack["upstream"]
            self.assertEqual((repository, path), (upstream["repository"], upstream["path"]))
            self.assertEqual(f"https://www.cciss.it/nap/mmtis/public/en/catalog/Dataset/{dataset}", upstream["officialDataset"])
            self.assertRegex(upstream["commit"], r"^[0-9a-f]{40}$")
            self.assertEqual(upstream["archiveSha256"], pack["sourceArchiveSha256"])
            self.assertEqual(f"https://raw.githubusercontent.com/{repository.removeprefix('https://github.com/')}/{upstream['commit']}/{path}", pack["sourceUrl"])
            self.assertEqual("gtfs-rail-and-operator-coach-route-types", pack["filter"]["kind"])
            with zipfile.ZipFile(ASSETS / f"{source_id}.zip") as archive:
                self.assertNotIn("shapes.txt", archive.namelist())
                metadata = json.loads(archive.read(builder.FILTER_MANIFEST_MEMBER))
            self.assertEqual(pack["sourceArchiveSha256"], metadata["sourceArchiveSha256"])
            self.assertEqual(pack["lastServiceDate"], metadata["lastServiceDate"])

CALENDAR_HEADER = "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"


def write_trip_feed(
    target: Path,
    trips: list[tuple[str, str, str, list[tuple[str, str, str]]]],
    *,
    calendar: str = "WEEK,1,1,1,1,1,1,1,20261102,20261213\nMON,1,0,0,0,0,0,0,20261102,20261213\n",
) -> None:
    """A small railway feed: ``trips`` are (trip_id, number, service_id, [(stop, arrival, departure), ...])."""
    stops = sorted({stop for _, _, _, calls in trips for stop, _, _ in calls})
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("agency.txt", "agency_id,agency_name,agency_url,agency_timezone\nI,Rail,https://x.invalid,Europe/Rome\n")
        archive.writestr("stops.txt", "stop_id,stop_name,stop_lat,stop_lon\n" + "".join(
            f"{stop},{stop},45.{index},9.{index}\n" for index, stop in enumerate(stops)))
        archive.writestr("routes.txt", "route_id,agency_id,route_short_name,route_type\nR,I,IT,2\nB,I,BUS,3\n")
        archive.writestr("trips.txt", "route_id,service_id,trip_id,trip_short_name\n" + "".join(
            f"{'B' if trip_id.endswith('_bus') else 'R'},{service},{trip_id},{number}\n" for trip_id, number, service, _ in trips))
        archive.writestr("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" + "".join(
            f"{trip_id},{arrival}:00,{departure}:00,{stop},{sequence}\n"
            for trip_id, _, _, calls in trips for sequence, (stop, arrival, departure) in enumerate(calls, 1)))
        archive.writestr("calendar.txt", CALENDAR_HEADER + calendar)
        archive.writestr("calendar_dates.txt", "service_id,date,exception_type\n")


def filtered(source: Path, output: Path, source_id: str) -> tuple[dict[str, list[dict[str, str]]], dict[str, object]]:
    builder.filter_archive(source, output, source_id)
    with zipfile.ZipFile(output) as archive:
        times: dict[str, list[dict[str, str]]] = {}
        for row in table(archive, "stop_times.txt"):
            times.setdefault(row["trip_id"], []).append(row)
        return times, json.loads(archive.read(builder.FILTER_MANIFEST_MEMBER))


class ItineraryCopiesTest(unittest.TestCase):
    """Audit2 tools-data-1 / android-planner-1: Italo's combined itineraries published as trips."""

    # Train 8987 Alfa → Delta; its own trip lost the dwell at Beta (arrival = departure), which the copies keep.
    TRAIN = ("IT::ServiceJourney:8987-1-9-1", "8987", "WEEK",
             [("ALFA", "14:35", "14:35"), ("BETA", "15:10", "15:10"), ("GAMMA", "15:47", "15:49"), ("DELTA", "16:15", "16:15")])
    # Train 9939 Omega → Alfa, then its passengers change to 8987: the itinerary's second leg runs 8987 under 9939.
    FEEDER = ("IT::ServiceJourney:9939-1-1-1", "9939", "WEEK", [("OMEGA", "13:00", "13:00"), ("ALFA", "14:20", "14:20")])
    LEG_ONE = ("IT::ServiceJourney:9939-8987-1-12-1_rail1", "9939", "WEEK", [("OMEGA", "13:00", "13:00"), ("ALFA", "14:20", "14:20")])
    LEG_TWO = ("IT::ServiceJourney:9939-8987-1-12-1_rail2", "9939", "WEEK",
               [("ALFA", "14:35", "14:35"), ("BETA", "15:10", "15:17"), ("GAMMA", "15:47", "15:49"), ("DELTA", "16:15", "16:15")])
    # An itinerary boarding 8987 at Beta, under the number of train 9920 (which runs elsewhere earlier that day).
    PARTIAL = ("IT::ServiceJourney:9920-8987-1-1-1_rail2", "9920", "WEEK", [("BETA", "15:10", "15:17"), ("GAMMA", "15:47", "15:49")])
    OTHER = ("IT::ServiceJourney:9920-1-1-1", "9920", "WEEK", [("SIGMA", "12:00", "12:00"), ("BETA", "14:50", "14:50")])
    # The whole itinerary 9939 + 8987 in one trip: the change at Alfa keeps the feeder's time.
    WHOLE = ("IT::ServiceJourney:9939-8987-1-1-1", "9939", "WEEK",
             [("OMEGA", "13:00", "13:00"), ("ALFA", "14:20", "14:20"), ("GAMMA", "15:47", "15:49"), ("DELTA", "16:15", "16:15")])

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="gtfs-copies-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)

    def build(self, trips: list[tuple[str, str, str, list[tuple[str, str, str]]]], source_id: str = "italo-gtfs", **feed: str):
        source = self.temp / f"{source_id}-source.zip"
        write_trip_feed(source, trips, **feed)
        return filtered(source, self.temp / f"{source_id}.zip", source_id)

    def test_copies_and_itineraries_go_and_the_train_keeps_its_number_and_the_dwell(self) -> None:
        times, metadata = self.build([self.LEG_ONE, self.LEG_TWO, self.PARTIAL, self.WHOLE, self.TRAIN, self.FEEDER, self.OTHER])
        self.assertEqual({self.TRAIN[0], self.FEEDER[0], self.OTHER[0]}, set(times))
        # The real train leaves Beta at 15:17, as the itineraries boarding it there say.
        self.assertEqual(["14:35:00", "15:17:00", "15:49:00", "16:15:00"], [row["departure_time"] for row in times[self.TRAIN[0]]])
        self.assertEqual(["14:35:00", "15:10:00", "15:47:00", "16:15:00"], [row["arrival_time"] for row in times[self.TRAIN[0]]])
        self.assertEqual(["itinerary-copies"], metadata["tripRules"])
        stderr = io.StringIO()
        with mock.patch.object(sys, "stderr", stderr):
            self.build([self.LEG_ONE, self.LEG_TWO, self.PARTIAL, self.WHOLE, self.TRAIN, self.FEEDER, self.OTHER])
        self.assertIn('{"departuresTaken": 1, "droppedCopies": 3, "droppedItineraries": 1, "partialCopiesKept": 0}', stderr.getvalue())
        # Filtering the filtered pack again changes nothing, byte for byte.
        again = self.temp / "again.zip"
        builder.filter_archive(self.temp / "italo-gtfs.zip", again, "italo-gtfs")
        self.assertEqual(json.loads(zipfile.ZipFile(again).read(builder.FILTER_MANIFEST_MEMBER))["memberSha256"], metadata["memberSha256"])

    def test_an_itinerary_found_only_in_the_data_goes_and_the_train_inside_it_stays(self) -> None:
        # One trip under 9939 runs the whole of 9939 and then the whole of 8987; nothing in its id says so.
        itinerary = ("X", "9939", "WEEK", self.FEEDER[3] + self.TRAIN[3][1:])
        times, _ = self.build([itinerary, self.FEEDER, self.TRAIN])
        self.assertEqual({self.TRAIN[0], self.FEEDER[0]}, set(times))

    def test_a_copy_that_also_runs_on_days_without_its_train_stays(self) -> None:
        # 8987 runs on Mondays only; the copy every day: on the other days it is the only record of the run.
        train = (self.TRAIN[0], self.TRAIN[1], "MON", self.TRAIN[3])
        times, _ = self.build([self.LEG_TWO, train, self.FEEDER])
        self.assertEqual({self.LEG_TWO[0], self.TRAIN[0], self.FEEDER[0]}, set(times))

    def test_coupled_trains_and_other_packs_are_left_alone(self) -> None:
        # Two trains starting their own numbers at the same minutes are coupled portions, never merged.
        coupled = ("IT::ServiceJourney:8990-1-1-1", "8990", "WEEK", self.TRAIN[3])
        times, _ = self.build([self.TRAIN, coupled])
        self.assertEqual({self.TRAIN[0], coupled[0]}, set(times))
        # The rule is Italo's: the same trips in another pack are kept as published.
        times, metadata = self.build([self.LEG_TWO, self.PARTIAL, self.WHOLE, self.TRAIN, self.FEEDER], "lombardia-trenord-gtfs")
        self.assertEqual(5, len(times))
        self.assertNotIn("tripRules", metadata)

    def test_the_check_refuses_an_italo_pack_with_copies_left(self) -> None:
        registry, archives = self.temp / "registry.json", {"italo-gtfs": self.temp / "upstream.zip"}
        write_trip_feed(archives["italo-gtfs"], [self.LEG_TWO, self.TRAIN, self.FEEDER])
        entry = registry_entry("italo-gtfs", builder.sha256_file(archives["italo-gtfs"]))
        registry.write_text(json.dumps({"generatedAt": "2026-11-02", "packs": [entry]}), encoding="utf-8")
        output = self.temp / "out"
        with mock.patch.object(builder, "ITINERARY_COPY_SOURCES", frozenset()):
            builder.build_packs(self.temp, output, manifest_path=registry, generated_at="2026-11-02", input_overrides=archives)
        with self.assertRaisesRegex(builder.BuildError, "1 trip ripetono la corsa di un altro treno"):
            builder.verify_assets(output, as_of=None, min_valid_days=None)
        builder.build_packs(self.temp, output, manifest_path=registry, generated_at="2026-11-02", input_overrides=archives)
        builder.verify_assets(output, as_of=None, min_valid_days=None)

    def test_named_trains_reads_the_converter_ids(self) -> None:
        self.assertEqual(["9928"], builder.named_trains("IT::ServiceJourney:9928-1-1-1"))
        self.assertEqual(["9907", "8111"], builder.named_trains("IT::ServiceJourney:9907-8111--3-1-7-1"))
        self.assertEqual(["8158", "9962"], builder.named_trains("IT::ServiceJourney:8158-9962--1-1-5-1_rail2"))
        self.assertTrue(builder.is_combined_itinerary("IT::ServiceJourney:9928-8918-1-1-1", "9928"))
        self.assertFalse(builder.is_combined_itinerary("IT::ServiceJourney:9928-8918-1-1-1_rail1", "9928"))
        self.assertFalse(builder.is_combined_itinerary("IT::ServiceJourney:1077-9939-1-1-1", "9939"))

    def test_the_shipped_italo_pack_has_one_trip_per_run(self) -> None:
        with zipfile.ZipFile(ASSETS / "italo-gtfs.zip") as archive:
            metadata = json.loads(archive.read(builder.FILTER_MANIFEST_MEMBER))
            trips = table(archive, "trips.txt")
        self.assertIn("itinerary-copies", metadata["tripRules"])
        self.assertFalse([trip["trip_id"] for trip in trips if builder.is_combined_itinerary(trip["trip_id"], trip["trip_short_name"])])



class RouteTypeOverrideTest(unittest.TestCase):
    """Audit2 android-planner-4: TFT's railway lines are published as buses (route_type 3)."""

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="gtfs-route-type-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)

    def write_tft(self, target: Path) -> None:
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("agency.txt", "agency_id,agency_name,agency_url,agency_timezone\nT,TFT,https://x.invalid,Europe/Rome\n")
            archive.writestr("stops.txt", "stop_id,stop_name,stop_lat,stop_lon\nA,Arezzo,43.4,11.8\nS,Stia,43.8,11.7\n")
            archive.writestr("routes.txt", "route_id,agency_id,route_short_name,route_type\n"
                             "1028056267,T,Arezzo-Stia,3\n2272465657,T,Arezzo-Sinalunga,3\nBUS,T,Bus,3\n")
            archive.writestr("trips.txt", "route_id,service_id,trip_id\n1028056267,S,T1\n2272465657,S,T2\nBUS,S,T3\n")
            archive.writestr("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" + "".join(
                f"{trip},08:00:00,08:00:00,A,1\n{trip},09:00:00,09:00:00,S,2\n" for trip in ("T1", "T2", "T3")))
            archive.writestr("calendar.txt", CALENDAR_HEADER + "S,1,1,1,1,1,1,1,20261102,20261213\n")

    def build(self, output: Path) -> None:
        source = self.temp / "tft-source.zip"
        self.write_tft(source)
        registry = self.temp / "registry.json"
        registry.write_text(json.dumps({"generatedAt": "2026-11-02", "packs": [{
            "sourceId": "toscana-tft-gtfs", "asset": "gtfs/toscana-tft-gtfs.zip", "sourceUrl": "https://example.test/tft.zip",
            "accessMode": "OPEN_DIRECT"}]}), encoding="utf-8")
        builder.build_packs(self.temp, output, manifest_path=registry, generated_at="2026-11-02",
                            input_overrides={"toscana-tft-gtfs": source})

    def test_tft_railway_lines_are_published_as_trains_and_the_check_holds_it(self) -> None:
        output = self.temp / "out"
        self.build(output)
        with zipfile.ZipFile(output / "toscana-tft-gtfs.zip") as archive:
            routes = {row["route_id"]: row["route_type"] for row in table(archive, "routes.txt")}
            self.assertEqual(["T1", "T2"], sorted(row["trip_id"] for row in table(archive, "trips.txt")))
        self.assertEqual({"1028056267": "2", "2272465657": "2"}, routes)
        manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual({"1028056267": "2", "2272465657": "2"}, manifest["packs"][0]["filter"]["routeTypeOverrides"])
        builder.verify_assets(output, as_of=None, min_valid_days=None)
        # A pack built without the correction (an older build) fails the check.
        stale = self.temp / "stale"
        with mock.patch.object(builder, "ROUTE_TYPE_OVERRIDES", {}):
            self.build(stale)
        with self.assertRaisesRegex(builder.BuildError, "route o criterio filtro incoerente"):
            builder.verify_assets(stale, as_of=None, min_valid_days=None)

    def test_the_shipped_tft_pack_runs_trains(self) -> None:
        with zipfile.ZipFile(ASSETS / "toscana-tft-gtfs.zip") as archive:
            self.assertEqual({"2"}, {row["route_type"] for row in table(archive, "routes.txt")})


class FrequenciesTest(unittest.TestCase):
    """Audit2 android-planner-8: frequency-based trips (Genova's lift) become one trip per run."""

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="gtfs-frequencies-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)

    def test_each_run_is_a_trip_without_block_id_also_past_midnight(self) -> None:
        trips = [{"trip_id": "LIFT", "route_id": "R", "service_id": "S", "block_id": "8"}, {"trip_id": "X", "route_id": "R", "block_id": "9"}]
        stop_times = [
            {"trip_id": "LIFT", "arrival_time": "23:59:00", "departure_time": "23:59:30", "stop_id": "A", "stop_sequence": "1"},
            {"trip_id": "LIFT", "arrival_time": "24:00:30", "departure_time": "24:00:30", "stop_id": "B", "stop_sequence": "2"},
            {"trip_id": "X", "arrival_time": "10:00:00", "departure_time": "10:00:00", "stop_id": "A", "stop_sequence": "1"},
        ]
        frequencies = [{"trip_id": "LIFT", "start_time": "23:58:00", "end_time": "24:01:00", "headway_secs": "60"}]
        kept_trips, kept_times, runs = builder.expand_frequencies(trips, stop_times, frequencies, "fixture")
        self.assertEqual(3, runs)
        self.assertEqual(["LIFT@235800", "LIFT@235900", "LIFT@240000", "X"], [row["trip_id"] for row in kept_trips])
        self.assertEqual(["", "", "", "9"], [row["block_id"] for row in kept_trips])
        self.assertEqual(
            # The first departure of each run is its start; the times keep the template's spacing.
            [("23:57:30", "23:58:00"), ("23:59:00", "23:59:00"), ("23:58:30", "23:59:00"), ("24:00:00", "24:00:00"),
             ("23:59:30", "24:00:00"), ("24:01:00", "24:01:00"), ("10:00:00", "10:00:00")],
            [(row["arrival_time"], row["departure_time"]) for row in kept_times],
        )
        with self.assertRaisesRegex(builder.BuildError, "oltre"), mock.patch.object(builder, "MAX_FREQUENCY_RUNS", 2):
            builder.expand_frequencies(trips, stop_times, frequencies, "fixture")
        with self.assertRaisesRegex(builder.BuildError, "frequencies.txt non valido"):
            builder.expand_frequencies(trips, stop_times, [dict(frequencies[0], headway_secs="0")], "fixture")

    def test_the_shipped_packs_carry_no_frequency_table_with_runs(self) -> None:
        for directory in (ASSETS, ROOT / "app/src/main/assets/gtfs-transit", ROOT / "app/src/personal/assets/gtfs-personal"):
            for path in sorted(directory.glob("*.zip")):
                with self.subTest(pack=path.name), zipfile.ZipFile(path) as archive:
                    if "frequencies.txt" in archive.namelist():
                        self.assertEqual([], table(archive, "frequencies.txt"))


class FullServiceTest(unittest.TestCase):
    """Audit2 tools-data-2: a pack whose calendar runs on with a fraction of its trains is not current."""

    HEADER = ("service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "start_date", "end_date")

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="gtfs-full-service-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)

    def calendar(self, *rows: tuple[str, str, str]) -> builder.CsvTable:
        return builder.CsvTable(self.HEADER, tuple(dict(zip(self.HEADER, (service, *"1111111", start, end))) for service, start, end in rows))

    def trips(self, **services: int) -> list[dict[str, str]]:
        return [{"trip_id": f"{service}{index}", "service_id": service} for service, count in services.items() for index in range(count)]

    def test_the_end_of_full_coverage_is_the_last_day_with_most_of_the_trains(self) -> None:
        # Ten trains a day until the timetable change of 12 December, then six of them for a year (Trenitalia's 2027).
        calendar = self.calendar(("OLD", "20261102", "20261212"), ("NEW", "20261102", "20271211"))
        exceptions = builder.empty_calendar_dates()
        trips = self.trips(OLD=4, NEW=6)
        last = builder.last_service_date(calendar, exceptions, "fixture")
        self.assertEqual(date(2027, 12, 11), last)
        self.assertEqual(date(2026, 12, 12), builder.full_service_until(trips, calendar, exceptions, "fixture", last))
        # A holiday with a thinner service is not the end; on the last day it is.
        holiday = builder.CsvTable(("service_id", "date", "exception_type"), (
            {"service_id": "OLD", "date": "20261208", "exception_type": "2"},
            {"service_id": "OLD", "date": "20261212", "exception_type": "2"},
        ))
        self.assertEqual(date(2026, 12, 11), builder.full_service_until(trips, calendar, holiday, "fixture", last))
        # A pack running the same trains to its end is fully covered to its last day.
        self.assertEqual(last, builder.full_service_until(self.trips(NEW=6), calendar, exceptions, "fixture", last))

    def test_the_check_publishes_and_applies_it(self) -> None:
        source = self.temp / "rail-source.zip"
        write_trip_feed(source, [
            ("A", "1", "OLD", [("X", "08:00", "08:00"), ("Y", "09:00", "09:00")]),
            ("B", "2", "OLD", [("X", "10:00", "10:00"), ("Y", "11:00", "11:00")]),
            ("C", "3", "NEW", [("X", "12:00", "12:00"), ("Y", "13:00", "13:00")]),
        ], calendar="OLD,1,1,1,1,1,1,1,20261102,20261212\nNEW,1,1,1,1,1,1,1,20261102,20271211\n")
        registry = self.temp / "registry.json"
        registry.write_text(json.dumps({"generatedAt": "2026-11-02", "packs": [{
            "sourceId": "rail-gtfs", "asset": "gtfs/rail-gtfs.zip", "sourceUrl": "https://example.test/rail.zip",
            "accessMode": "OPEN_DIRECT", "sha256": "0" * 64, "lastServiceDate": "2026-01-01", "notes": "fixture"}]}), encoding="utf-8")
        output = self.temp / "out"
        manifest = builder.build_packs(self.temp, output, manifest_path=registry, generated_at="2026-11-02",
                                       input_overrides={"rail-gtfs": source})
        pack = manifest["packs"][0]
        self.assertEqual(("2027-12-11", "2026-12-12"), (pack["lastServiceDate"], pack["fullServiceUntil"]))
        self.assertEqual(["lastServiceDate", "fullServiceUntil"], [key for key in pack if key in ("lastServiceDate", "fullServiceUntil")])
        self.assertEqual(date(2026, 12, 12), builder.release_end(pack))
        builder.verify_assets(output, as_of=date(2026, 11, 12), min_valid_days=30)
        with self.assertRaisesRegex(builder.BuildError, "copertura piena fino al 2026-12-12"):
            builder.verify_assets(output, as_of=date(2026, 11, 13), min_valid_days=30)
        # A manifest that drops or moves the date is refused.
        published = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
        for value in (None, "2027-12-11"):
            entry = dict(published["packs"][0])
            if value is None:
                del entry["fullServiceUntil"]
            else:
                entry["fullServiceUntil"] = value
            (output / "manifest.json").write_text(json.dumps(dict(published, packs=[entry])), encoding="utf-8")
            with self.subTest(value=value), self.assertRaisesRegex(builder.BuildError, "fullServiceUntil incoerente"):
                builder.verify_assets(output, as_of=None, min_valid_days=None)

    def test_the_shipped_packs_declare_it(self) -> None:
        manifest = builder.read_manifest(ASSETS / "manifest.json")
        packs = {pack["sourceId"]: pack for pack in manifest["packs"]}
        self.assertTrue(all("fullServiceUntil" in pack for pack in packs.values()))
        # Trenitalia's calendar runs to December 2027; its full timetable ends with the change of December 2026.
        self.assertEqual("2026-12-12", packs["trenitalia-gtfs"]["fullServiceUntil"])

if __name__ == "__main__":
    unittest.main()
