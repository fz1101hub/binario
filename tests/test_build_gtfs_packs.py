#!/usr/bin/env python3
"""Offline regression tests for tools/build_gtfs_packs.py."""
from __future__ import annotations

import contextlib
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
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Mapping
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

    def test_total_archive_limit_names_the_largest_members_on_one_line(self) -> None:
        source = self.temp / "large-feed.zip"
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("stop_times.txt", b"s" * 400)
            archive.writestr("shapes.txt", b"g" * 300)
            archive.writestr("stops\n::warning::x.txt", b"t" * 200)
            for index in range(3):
                archive.writestr(f"extra{index}.txt", b"e" * (10 + index))
        with zipfile.ZipFile(source) as archive, mock.patch.object(builder, "MAX_ARCHIVE_UNCOMPRESSED_BYTES", 512):
            with self.assertRaises(builder.BuildError) as raised:
                builder.validate_archive_limits(archive, omit_geometry=True)
        message = str(raised.exception)
        self.assertIn("membri più grandi: stop_times.txt 400, shapes.txt 300, stops\\n::warning::x.txt 200, extra2.txt 12)", message)
        self.assertNotIn("\n", message)
        self.assertNotIn("extra1.txt", message)

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
    calendar_dates: str = "",
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
        archive.writestr("calendar_dates.txt", "service_id,date,exception_type\n" + calendar_dates)


def filtered(source: Path, output: Path, source_id: str) -> tuple[dict[str, list[dict[str, str]]], dict[str, object]]:
    builder.filter_archive(source, output, source_id)
    with zipfile.ZipFile(output) as archive:
        times: dict[str, list[dict[str, str]]] = {}
        for row in table(archive, "stop_times.txt"):
            times.setdefault(row["trip_id"], []).append(row)
        return times, json.loads(archive.read(builder.FILTER_MANIFEST_MEMBER))


def write_france_feed(
    target: Path,
    trips: list[tuple[str, str, str, list[tuple[str, str, str]]]],
    calendar_dates: dict[str, list[str]],
    *,
    timezone: str = "UTC",
    with_short_names: dict[str, str] | None = None,
) -> None:
    """A feed shaped like Trenitalia France's: one route per train whose route_id is the number, no trip_short_name,
    times in ``timezone``; ``trips`` are (trip_id, route_id, service_id, [(stop, arrival, departure), ...]), and
    ``calendar_dates`` the days of each service."""
    stops = sorted({stop for _, _, _, calls in trips for stop, _, _ in calls})
    routes = sorted({route for _, route, _, _ in trips})
    short = with_short_names or {}
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("agency.txt", f"agency_id,agency_name,agency_url,agency_timezone\nTI,Trenitalia,https://x.invalid,{timezone}\n")
        archive.writestr("stops.txt", "stop_id,stop_name,stop_lat,stop_lon\n" + "".join(
            f"{stop},{stop},45.{index},5.{index}\n" for index, stop in enumerate(stops)))
        archive.writestr("routes.txt", "route_id,agency_id,route_short_name,route_long_name,route_type\n" + "".join(
            f"{route},TI,Paris/Lyon,Paris/Lyon,2\n" for route in routes))
        if short:
            archive.writestr("trips.txt", "route_id,service_id,trip_id,trip_short_name\n" + "".join(
                f"{route},{service},{trip_id},{short.get(trip_id, '')}\n" for trip_id, route, service, _ in trips))
        else:
            archive.writestr("trips.txt", "route_id,service_id,trip_id\n" + "".join(
                f"{route},{service},{trip_id}\n" for trip_id, route, service, _ in trips))
        archive.writestr("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" + "".join(
            f"{trip_id},{arrival},{departure},{stop},{sequence * 8}\n"
            for trip_id, _, _, calls in trips for sequence, (stop, arrival, departure) in enumerate(calls)))
        archive.writestr("calendar_dates.txt", "service_id,date,exception_type\n" + "".join(
            f"{service},{day},1\n" for service, days in calendar_dates.items() for day in days))


class LocalTimesTest(unittest.TestCase):
    """Trenitalia France publishes UTC times and the train number only as route_id: the build moves the times into
    Europe/Rome per service day and copies the number into trip_short_name; --check refuses a pack in another offset."""

    SOURCE = "trenitalia-france-gtfs"
    LYON = [("PERRACHE", "16:08:00", "16:08:00"), ("PARTDIEU", "16:16:00", "16:22:00"), ("PARIS", "18:18:00", "18:18:00")]

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="gtfs-local-times-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)

    def build(self, trips, days, source_id: str = SOURCE, **feed):
        source = self.temp / f"{source_id}-source.zip"
        write_france_feed(source, trips, days, **feed)
        output = self.temp / f"{source_id}.zip"
        with mock.patch.object(sys, "stderr", io.StringIO()):
            builder.filter_archive(source, output, source_id)
        with zipfile.ZipFile(output) as archive:
            times: dict[str, list[dict[str, str]]] = {}
            for row in table(archive, "stop_times.txt"):
                times.setdefault(row["trip_id"], []).append(row)
            return output, times, table(archive, "trips.txt"), table(archive, "agency.txt"), json.loads(
                archive.read(builder.FILTER_MANIFEST_MEMBER))

    def test_times_move_by_the_offset_of_each_trip_service_day(self) -> None:
        trips = [
            ("70458", "6656", "NOV", self.LYON),                              # 24 Nov 2026: CET, +60
            ("70300", "6656", "OCT", self.LYON),                              # 20 Oct 2026: CEST, +120
            ("70310", "6656", "DST", self.LYON),                              # 25 Oct 2026, the change: noon is CET, +60
            ("70900", "6662", "DEC", self.LYON),                              # 12-14 Dec 2026, the timetable change: +60
            ("70950", "9283", "NOV", [("PARIS", "22:30:00", "22:30:00"), ("TORINO", "23:59:00", "24:05:00"), ("MILANO", "25:00:00", "25:00:00")]),
        ]
        days = {"NOV": ["20261124"], "OCT": ["20261020"], "DST": ["20261025"], "DEC": ["20261212", "20261213", "20261214"]}
        output, times, trip_rows, agency, metadata = self.build(trips, days)
        departures = {trip: [row["departure_time"] for row in rows] for trip, rows in times.items()}
        self.assertEqual(["17:08:00", "17:22:00", "19:18:00"], departures["70458"])
        self.assertEqual(["18:08:00", "18:22:00", "20:18:00"], departures["70300"])
        self.assertEqual(["17:08:00", "17:22:00", "19:18:00"], departures["70310"])
        self.assertEqual(["17:08:00", "17:22:00", "19:18:00"], departures["70900"])
        # After midnight the times go on past 24:00 on the same service day, as GTFS counts them.
        self.assertEqual(["23:30:00", "25:05:00", "26:00:00"], departures["70950"])
        self.assertEqual("17:16:00", times["70458"][1]["arrival_time"])
        self.assertEqual([builder.APP_TIMEZONE], [row["agency_timezone"] for row in agency])
        self.assertEqual({"70458": "6656", "70300": "6656", "70310": "6656", "70900": "6662", "70950": "9283"},
                         {row["trip_id"]: row["trip_short_name"] for row in trip_rows})
        self.assertEqual([builder.OUT_OF_ORDER_RULE, builder.LOCAL_TIMES_RULE, builder.TRAIN_NUMBERS_RULE, builder.ROUTE_CATEGORY_RULE],
                         metadata["tripRules"])
        self.assertEqual("UTC", metadata[builder.LOCAL_TIMES_FROM_FIELD])
        builder.verify_filter_from_asset(output, self.SOURCE)

        # Filtering the pack again (now in Europe/Rome) changes no member, and the published pack keeps where it came from.
        again = self.temp / "again.zip"
        with mock.patch.object(sys, "stderr", io.StringIO()):
            builder.filter_archive(output, again, self.SOURCE, published_entries=[
                {"sha256": builder.sha256_file(output), "sourceArchiveSha256": metadata["sourceArchiveSha256"]}])
        with zipfile.ZipFile(again) as archive:
            refiltered = json.loads(archive.read(builder.FILTER_MANIFEST_MEMBER))
        self.assertEqual(metadata["memberSha256"], refiltered["memberSha256"])
        self.assertEqual("UTC", refiltered[builder.LOCAL_TIMES_FROM_FIELD])
        self.assertEqual(output.read_bytes(), again.read_bytes())

    def test_a_trip_across_a_change_of_summer_time_is_refused(self) -> None:
        with self.assertRaisesRegex(builder.BuildError, r"scarti diversi.*\+60 min, \+120 min"):
            self.build([("70458", "6656", "BOTH", self.LYON)], {"BOTH": ["20261024", "20261026"]})

    def test_a_time_that_would_leave_the_service_day_is_refused(self) -> None:
        late = [("PARIS", "46:30:00", "46:30:00"), ("MILANO", "47:10:00", "47:10:00")]
        with self.assertRaisesRegex(builder.BuildError, r"uscirebbe dal giorno di servizio"):
            self.build([("70999", "9283", "NOV", late)], {"NOV": ["20261124"]})
        # A zone ahead of Italy would move a time before midnight of the service day.
        early = [("PARIS", "00:30:00", "00:30:00"), ("LYON", "02:00:00", "02:00:00")]
        with self.assertRaisesRegex(builder.BuildError, r"uscirebbe dal giorno di servizio"):
            self.build([("70998", "9283", "NOV", early)], {"NOV": ["20261124"]}, timezone="Europe/Athens")

    def test_a_pack_without_agency_timezone_is_refused(self) -> None:
        with self.assertRaisesRegex(builder.BuildError, r"agency_timezone mancante"):
            self.build([("70458", "6656", "NOV", self.LYON)], {"NOV": ["20261124"]}, timezone="")

    def test_only_a_plain_number_becomes_the_train_number(self) -> None:
        trips = [("A", "6656", "NOV", self.LYON), ("B", "R-12", "NOV", self.LYON), ("C", "1234567", "NOV", self.LYON),
                 ("D", "6658", "NOV", self.LYON)]
        _, _, trip_rows, _, _ = self.build(trips, {"NOV": ["20261124"]}, timezone="Europe/Paris", with_short_names={"D": "FR 6658"})
        self.assertEqual({"A": "6656", "B": "", "C": "", "D": "FR 6658"}, {row["trip_id"]: row["trip_short_name"] for row in trip_rows})

    def test_check_refuses_the_published_utc_pack_and_any_pack_in_another_offset(self) -> None:
        # The Trenitalia France pack of November 2026 as applied: UTC times, no rule recorded.
        published = self.temp / "published"
        published.mkdir()
        write_france_feed(published / "raw.zip", [("70458", "6656", "NOV", self.LYON)], {"NOV": ["20261124"]})
        with mock.patch.object(sys, "stderr", io.StringIO()):
            builder.filter_archive(published / "raw.zip", published / "plain.zip", "some-rail-gtfs")
        # The same bytes declared as the Trenitalia France pack, the way the pack applied in November 2026 was built.
        with zipfile.ZipFile(published / "plain.zip") as archive:
            members = {name: archive.read(name) for name in archive.namelist()}
        metadata = json.loads(members[builder.FILTER_MANIFEST_MEMBER])
        metadata["sourceId"] = self.SOURCE
        members[builder.FILTER_MANIFEST_MEMBER] = json.dumps(metadata).encode()
        utc_pack = published / "trenitalia-france-gtfs.zip"
        with zipfile.ZipFile(utc_pack, "w") as archive:
            for name, data in members.items():
                archive.writestr(name, data)
        with self.assertRaisesRegex(builder.BuildError, r"orari non portati in Europe/Rome"):
            builder.verify_filter_from_asset(utc_pack, self.SOURCE)
        # Any other source in an offset that is not Italy's on a day of its calendar fails too.
        with self.assertRaisesRegex(builder.BuildError, r"agency_timezone UTC il 2026-11-24"):
            builder.verify_filter_from_asset(published / "plain.zip", "some-rail-gtfs")
        # Europe/Paris has Italy's offset every day: nothing to move, nothing refused.
        write_france_feed(published / "paris.zip", [("70458", "6656", "NOV", self.LYON)], {"NOV": ["20261124"]}, timezone="Europe/Paris")
        with mock.patch.object(sys, "stderr", io.StringIO()):
            builder.filter_archive(published / "paris.zip", published / "paris-out.zip", "some-rail-gtfs")
        builder.verify_filter_from_asset(published / "paris-out.zip", "some-rail-gtfs")

    def test_the_rules_belong_to_the_reviewed_sources_only(self) -> None:
        output, _, _, _, _ = self.build([("70458", "6656", "NOV", self.LYON)], {"NOV": ["20261124"]})
        with self.assertRaisesRegex(builder.BuildError, r"metadati filtro non corrispondenti|fonte non rivista"):
            builder.verify_filter_from_asset(output, "some-rail-gtfs")
        with mock.patch.object(builder, "LOCAL_TIME_SOURCES", frozenset()):
            with self.assertRaisesRegex(builder.BuildError, r"local-times su una fonte non rivista"):
                builder.verify_local_times(self.SOURCE, {"tripRules": [builder.LOCAL_TIMES_RULE]}, [], builder.empty_calendar(),
                                           builder.empty_calendar_dates())
        with mock.patch.object(builder, "TRAIN_NUMBER_ROUTE_ID_SOURCES", frozenset()):
            with self.assertRaisesRegex(builder.BuildError, r"train-numbers-from-route-id su una fonte non rivista"):
                builder.verify_train_numbers(self.SOURCE, {"tripRules": [builder.TRAIN_NUMBERS_RULE]}, [])
        # A pack that kept a trip without its number is refused.
        with self.assertRaisesRegex(builder.BuildError, r"1 trip senza trip_short_name"):
            builder.verify_train_numbers(self.SOURCE, {"tripRules": [builder.TRAIN_NUMBERS_RULE]},
                                         [{"trip_id": "X", "route_id": "6656", "trip_short_name": ""}])


def rewrite_member(path: Path, name: str, old: str, new: str) -> None:
    """``path`` with ``old`` replaced by ``new`` in its member ``name``."""
    with zipfile.ZipFile(path) as archive:
        members = {member: archive.read(member) for member in archive.namelist()}
    assert old.encode() in members[name]
    members[name] = members[name].replace(old.encode(), new.encode())
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for member, data in members.items():
            archive.writestr(member, data)


class FranceRoutesAndTripOrderTest(unittest.TestCase):
    """Trenitalia France names each route by its two ends and numbers a few trips out of their time order: the build
    writes the Frecciarossa category as the national pack publishes it and leaves those trips out, counted.  A pack built
    before these rules still passes --check; the weekly refresh filters its source again (missing_reviewed_rules)."""

    SOURCE = "trenitalia-france-gtfs"
    LYON = LocalTimesTest.LYON
    # Numbered Paris first, while Paris is the end of the run: an arrival before the previous departure.
    BACKWARDS = [("PARIS", "18:18:00", "18:18:00"), ("PERRACHE", "16:08:00", "16:08:00"), ("PARTDIEU", "16:16:00", "16:22:00")]

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="gtfs-france-rules-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.builds = 0

    def source(self, trips, **feed) -> Path:
        self.builds += 1
        path = self.temp / f"source-{self.builds}.zip"
        write_france_feed(path, trips, {"NOV": ["20261124"]}, **feed)
        return path

    def build(self, source: Path, source_id: str = SOURCE, **options):
        output = self.temp / f"{source_id}-{self.builds}.zip"
        with mock.patch.object(sys, "stderr", io.StringIO()):
            builder.filter_archive(source, output, source_id, **options)
        with zipfile.ZipFile(output) as archive:
            return (output, json.loads(archive.read(builder.FILTER_MANIFEST_MEMBER)), table(archive, "routes.txt"),
                    table(archive, "trips.txt"))

    def test_every_route_is_named_by_the_national_frecciarossa_category(self) -> None:
        trips = [("70458", "6656", "NOV", self.LYON), ("70950", "9283", "NOV", self.LYON)]
        output, metadata, routes, _ = self.build(self.source(trips))
        self.assertEqual([("6656", "FR", "Frecciarossa", "2", "TI"), ("9283", "FR", "Frecciarossa", "2", "TI")],
                         [(row["route_id"], row["route_short_name"], row["route_long_name"], row["route_type"], row["agency_id"])
                          for row in routes])
        self.assertIn(builder.ROUTE_CATEGORY_RULE, metadata["tripRules"])
        builder.verify_filter_from_asset(output, self.SOURCE)
        self.assertEqual([], builder.missing_reviewed_rules(output, self.SOURCE))
        # Any other pack keeps its routes as published.
        _, other, other_routes, _ = self.build(self.source(trips, timezone="Europe/Paris"), "some-rail-gtfs")
        self.assertEqual({"Paris/Lyon"}, {row["route_short_name"] for row in other_routes})
        self.assertNotIn("tripRules", other)

    def test_a_route_of_another_type_or_agency_fails_the_build(self) -> None:
        trips = [("70458", "6656", "NOV", self.LYON)]
        extended = self.source(trips)
        rewrite_member(extended, "routes.txt", ",2\n", ",101\n")  # still a railway route, kept by the filter
        with self.assertRaisesRegex(builder.BuildError, r"route 6656 ha route_type 101, non 2"):
            self.build(extended)
        other = self.source(trips)
        rewrite_member(other, "agency.txt", "TI,Trenitalia,", "TI,Another Rail,")
        with self.assertRaisesRegex(builder.BuildError, r"route 6656 è dell'agenzia Another Rail, non rivista"):
            self.build(other)

    def test_trips_out_of_time_order_are_left_out_counted_and_capped(self) -> None:
        trips = [(f"7{index:04d}", "6656", "NOV", self.LYON) for index in range(20)] + [("71124", "9281", "NOV", self.BACKWARDS)]
        output, metadata, _, trip_rows = self.build(self.source(trips))
        self.assertEqual(20, len(trip_rows))
        self.assertNotIn("71124", {row["trip_id"] for row in trip_rows})
        self.assertEqual(1, metadata[builder.OUT_OF_ORDER_COUNT_FIELD])
        self.assertEqual(20, metadata["counts"]["trips"])
        self.assertIn(builder.OUT_OF_ORDER_RULE, metadata["tripRules"])
        builder.verify_filter_from_asset(output, self.SOURCE)
        # Filtering the published pack again keeps the count of its source and every byte.
        again, refiltered, _, _ = self.build(output, published_entries=[
            {"sha256": builder.sha256_file(output), "sourceArchiveSha256": metadata["sourceArchiveSha256"]}])
        self.assertEqual(1, refiltered[builder.OUT_OF_ORDER_COUNT_FIELD])
        self.assertEqual(output.read_bytes(), again.read_bytes())
        # Above five per cent of the pack's trips the build fails; nothing is ever reordered.
        with self.assertRaisesRegex(builder.BuildError, r"1 trip su 2 con orari fuori ordine"):
            self.build(self.source([("70458", "6656", "NOV", self.LYON), ("71124", "9281", "NOV", self.BACKWARDS)]))
        # Any other pack keeps such a trip as published.
        _, other, _, other_trips = self.build(self.source(trips, timezone="Europe/Paris"), "some-rail-gtfs")
        self.assertIn("71124", {row["trip_id"] for row in other_trips})
        self.assertNotIn(builder.OUT_OF_ORDER_COUNT_FIELD, other)

    def test_a_pack_built_before_the_rules_passes_check_and_is_marked_for_the_refresh(self) -> None:
        trips = [(f"7{index:04d}", "6656", "NOV", self.LYON) for index in range(20)] + [("71124", "9281", "NOV", self.BACKWARDS)]
        with mock.patch.object(builder, "ROUTE_CATEGORY_SOURCES", {}), mock.patch.object(builder, "OUT_OF_ORDER_SOURCES", frozenset()):
            output, metadata, routes, _ = self.build(self.source(trips))
        self.assertEqual({"Paris/Lyon"}, {row["route_short_name"] for row in routes})
        builder.verify_filter_from_asset(output, self.SOURCE)
        self.assertEqual([builder.ROUTE_CATEGORY_RULE, builder.OUT_OF_ORDER_RULE], builder.missing_reviewed_rules(output, self.SOURCE))
        self.assertEqual([], builder.missing_reviewed_rules(output, "some-rail-gtfs"))

    def test_a_pack_declaring_the_rules_is_checked(self) -> None:
        declared = {"tripRules": [builder.ROUTE_CATEGORY_RULE, builder.OUT_OF_ORDER_RULE], builder.OUT_OF_ORDER_COUNT_FIELD: 0}
        agency = [{"agency_id": "TI", "agency_name": "Trenitalia"}]
        published = [{"route_id": "6656", "agency_id": "TI", "route_short_name": "Paris/Lyon", "route_long_name": "Paris/Lyon", "route_type": "2"}]
        with self.assertRaisesRegex(builder.BuildError, r"route senza la categoria di route-category"):
            builder.verify_route_categories(self.SOURCE, declared, published, agency)
        with self.assertRaisesRegex(builder.BuildError, r"route-category su una fonte non rivista"):
            builder.verify_route_categories("some-rail-gtfs", declared, published, agency)
        backwards = [{"trip_id": "71124", "stop_sequence": str(index), "arrival_time": arrival, "departure_time": departure}
                     for index, (_, arrival, departure) in enumerate(self.BACKWARDS)]
        with self.assertRaisesRegex(builder.BuildError, r"1 trip con orari fuori ordine lungo stop_sequence, es. 71124"):
            builder.verify_out_of_order(self.SOURCE, declared, 1, backwards)
        with self.assertRaisesRegex(builder.BuildError, r"out-of-order-trips su una fonte non rivista"):
            builder.verify_out_of_order("some-rail-gtfs", declared, 1, [])
        with self.assertRaisesRegex(builder.BuildError, r"oltre la quota ammessa"):
            builder.verify_out_of_order(self.SOURCE, {**declared, builder.OUT_OF_ORDER_COUNT_FIELD: 2}, 20, [])
        with self.assertRaisesRegex(builder.BuildError, r"incoerenti"):
            builder.verify_out_of_order(self.SOURCE, {builder.OUT_OF_ORDER_COUNT_FIELD: 0}, 1, [])
        builder.verify_out_of_order(self.SOURCE, {**declared, builder.OUT_OF_ORDER_COUNT_FIELD: 1}, 20, [])


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
        # Trenitalia's calendar runs to December 2027 while only part of the next timetable is published: its full
        # timetable ends well before the calendar. The date moves with each weekly refresh, so it is not pinned here.
        trenitalia = packs["trenitalia-gtfs"]
        self.assertLess(trenitalia["fullServiceUntil"], trenitalia["lastServiceDate"])


class ExpiredTripsTest(unittest.TestCase):
    """Audit2 tools-data improvement 1: trips whose service can never run again are not shipped."""

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="gtfs-expired-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)

    def build(self, output: Path) -> dict[str, object]:
        source = self.temp / "rail-source.zip"
        write_trip_feed(source, [
            ("SUMMER", "1", "OLD", [("X", "08:00", "08:00"), ("Y", "09:00", "09:00")]),
            ("LATE", "2", "OLD", [("X", "23:30", "23:30"), ("Z", "24:40", "24:40")]),
            ("WINTER", "3", "NEW", [("X", "10:00", "10:00"), ("Y", "11:00", "11:00")]),
        ], calendar="OLD,1,1,1,1,1,1,1,20260601,20261031\nNEW,1,1,1,1,1,1,1,20261102,20261213\nEVE,1,1,1,1,1,1,1,20260601,20261107\n")
        registry = self.temp / "registry.json"
        registry.write_text(json.dumps({"generatedAt": "2026-11-09", "packs": [{
            "sourceId": "rail-gtfs", "asset": "gtfs/rail-gtfs.zip", "sourceUrl": "https://example.test/rail.zip",
            "accessMode": "OPEN_DIRECT"}]}), encoding="utf-8")
        return builder.build_packs(self.temp, output, manifest_path=registry, generated_at="2026-11-09",
                                   input_overrides={"rail-gtfs": source})

    def test_a_trip_that_never_runs_again_is_dropped_and_the_check_holds_it(self) -> None:
        output = self.temp / "out"
        self.build(output)
        with zipfile.ZipFile(output / "rail-gtfs.zip") as archive:
            self.assertEqual(["WINTER"], [row["trip_id"] for row in table(archive, "trips.txt")])
            self.assertEqual({"WINTER"}, {row["trip_id"] for row in table(archive, "stop_times.txt")})
            self.assertEqual(["NEW"], [row["service_id"] for row in table(archive, "calendar.txt")])
            self.assertEqual({"X", "Y"}, {row["stop_id"] for row in table(archive, "stops.txt")})
            metadata = json.loads(archive.read(builder.FILTER_MANIFEST_MEMBER))
        # generatedAt 2026-11-09 minus the two days of grace: a run of the 7th after midnight is still kept.
        self.assertEqual("2026-11-07", metadata["tripsRunFrom"])
        builder.verify_assets(output, as_of=None, min_valid_days=None)
        published = (output / "rail-gtfs.zip").read_bytes()
        builder.build_packs(output, output, generated_at="2026-11-09")
        self.assertEqual(published, (output / "rail-gtfs.zip").read_bytes())
        # A pack declaring the day but still carrying an expired trip is refused.
        stale = self.temp / "stale"
        with mock.patch.object(builder, "expired_trips", lambda *_: set()):
            self.build(stale)
        with self.assertRaisesRegex(builder.BuildError, "2 trip non circolano più dal 2026-11-07"):
            builder.verify_assets(stale, as_of=None, min_valid_days=None)

    def test_the_shipped_core_packs_declare_the_day(self) -> None:
        for path in sorted(ASSETS.glob("*.zip")):
            with self.subTest(pack=path.name), zipfile.ZipFile(path) as archive:
                self.assertIn("tripsRunFrom", json.loads(archive.read(builder.FILTER_MANIFEST_MEMBER)))


class ThinTailClipTest(unittest.TestCase):
    """EAV, November 2026: full timetable to the end of 2027, then one trip a day to 2030; the weekly refresh refused
    the pack (``thin_tail`` of tools/apply_ci_gtfs_refresh.py).  The reviewed sources are clipped at their last full
    day instead."""

    FULL = [(f"F{index}", str(index), "FULL", [("X", "08:00", "08:00"), ("Y", "09:00", "09:00")]) for index in range(1, 9)]
    TAIL = [
        ("T1", "90", "TAIL", [("X", "12:00", "12:00"), ("Y", "13:00", "13:00")]),
        ("L1", "91", "LATE", [("X", "14:00", "14:00"), ("W", "15:00", "15:00")]),
    ]
    CALENDAR = "FULL,1,1,1,1,1,1,1,20261102,20271231\n"
    TAIL_CALENDAR = "TAIL,1,1,1,1,1,1,1,20261102,20301231\nLATE,1,1,1,1,1,1,1,20280101,20301231\n"
    CALENDAR_DATES = "FULL,20270101,2\nTAIL,20290101,2\n"

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="gtfs-thin-tail-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)

    def source(self, *, tail: bool = True) -> Path:
        source = self.temp / ("tail-source.zip" if tail else "plain-source.zip")
        write_trip_feed(source, self.FULL + (self.TAIL if tail else []),
                        calendar=self.CALENDAR + (self.TAIL_CALENDAR if tail else ""), calendar_dates=self.CALENDAR_DATES)
        return source

    def build(self, output: Path, source_id: str = "eav-gtfs", *, tail: bool = True, generated_at: str = "2026-11-09") -> dict:
        source = self.source(tail=tail)
        registry = self.temp / "registry.json"
        registry.write_text(json.dumps({"generatedAt": generated_at, "packs": [{
            "sourceId": source_id, "asset": f"gtfs/{source_id}.zip", "sourceUrl": "https://example.test/feed.zip",
            "accessMode": "OPEN_DIRECT"}]}), encoding="utf-8")
        return builder.build_packs(self.temp, output, manifest_path=registry, generated_at=generated_at,
                                   input_overrides={source_id: source})["packs"][0]

    @staticmethod
    def apply_thin_tail(pack: Path, last_service_date: str) -> str | None:
        sys.path.insert(0, str(ROOT / "tools"))
        try:
            import apply_ci_gtfs_refresh as apply
        finally:
            sys.path.remove(str(ROOT / "tools"))
        return apply.thin_tail(pack.read_bytes(), last_service_date)

    def test_a_reviewed_source_is_clipped_at_its_last_full_day_and_the_check_holds_it(self) -> None:
        output = self.temp / "out"
        pack = self.build(output)
        self.assertEqual(("2027-12-31", "2027-12-31"), (pack["lastServiceDate"], pack["fullServiceUntil"]))
        with zipfile.ZipFile(output / "eav-gtfs.zip") as archive:
            trips = sorted(row["trip_id"] for row in table(archive, "trips.txt"))
            self.assertEqual(sorted([f"F{index}" for index in range(1, 9)] + ["T1"]), trips)
            self.assertEqual(set(trips), {row["trip_id"] for row in table(archive, "stop_times.txt")})
            self.assertEqual({"X", "Y"}, {row["stop_id"] for row in table(archive, "stops.txt")})
            self.assertEqual([("FULL", "20261102", "20271231"), ("TAIL", "20261102", "20271231")],
                             [(row["service_id"], row["start_date"], row["end_date"]) for row in table(archive, "calendar.txt")])
            self.assertEqual([("FULL", "20270101", "2")],
                             [(row["service_id"], row["date"], row["exception_type"]) for row in table(archive, "calendar_dates.txt")])
            metadata = json.loads(archive.read(builder.FILTER_MANIFEST_MEMBER))
        self.assertEqual("2027-12-31", metadata["serviceClippedAfter"])
        self.assertEqual("2026-11-07", metadata["tripsRunFrom"])
        self.assertEqual(9, metadata["counts"]["trips"])
        self.assertIsNone(self.apply_thin_tail(output / "eav-gtfs.zip", pack["lastServiceDate"]))
        builder.verify_assets(output, as_of=None, min_valid_days=None)
        # Filtering the published pack again with the same generatedAt gives the same bytes (the clip is carried).
        published = (output / "eav-gtfs.zip").read_bytes()
        builder.build_packs(output, output, generated_at="2026-11-09")
        self.assertEqual(published, (output / "eav-gtfs.zip").read_bytes())

    def test_a_pack_cannot_claim_a_clip_it_did_not_make(self) -> None:
        # A build that records the clip without making it fails its own check, and so does its ZIP.
        stale = self.temp / "stale.zip"
        refusal = "eav-gtfs: serviceClippedAfter 2027-12-31 ma il calendario prosegue dopo"
        with mock.patch.object(builder, "clip_service_after", lambda calendar, calendar_dates, *_: (calendar, calendar_dates)):
            with self.assertRaisesRegex(builder.BuildError, refusal):
                self.build(self.temp / "stale-out")
            builder.filter_archive(self.source(), stale, "eav-gtfs", trips_run_from=date(2026, 11, 7))
        with self.assertRaisesRegex(builder.BuildError, refusal):
            builder.verify_filter_from_asset(stale, "eav-gtfs")
        # Nor can a source outside the reviewed list carry one.
        output = self.temp / "out"
        self.build(output)
        with mock.patch.object(builder, "THIN_TAIL_CLIP_SOURCES", frozenset()), \
                self.assertRaisesRegex(builder.BuildError, "eav-gtfs: serviceClippedAfter su una fonte non rivista"):
            builder.verify_assets(output, as_of=None, min_valid_days=None)

    def test_other_sources_keep_the_tail_and_the_refresh_still_refuses_them(self) -> None:
        output = self.temp / "out"
        pack = self.build(output, "rail-gtfs")
        self.assertEqual("2030-12-31", pack["lastServiceDate"])
        with zipfile.ZipFile(output / "rail-gtfs.zip") as archive:
            self.assertIn("L1", {row["trip_id"] for row in table(archive, "trips.txt")})
            self.assertNotIn("serviceClippedAfter", json.loads(archive.read(builder.FILTER_MANIFEST_MEMBER)))
        reason = self.apply_thin_tail(output / "rail-gtfs.zip", pack["lastServiceDate"])
        self.assertIn("orario completo fino al 2027-12-31 (picco 9 corse al giorno), poi non più di 2 al giorno fino al 2030-12-31", reason)

    def test_a_feed_without_a_thin_tail_builds_byte_identically(self) -> None:
        clipped = self.temp / "rule"
        pack = self.build(clipped, tail=False)
        self.assertEqual("2027-12-31", pack["lastServiceDate"])
        today = self.temp / "today"
        with mock.patch.object(builder, "THIN_TAIL_CLIP_SOURCES", frozenset()):
            self.build(today, tail=False)
        for name in ("eav-gtfs.zip", "manifest.json", "SHA256SUMS"):
            self.assertEqual((today / name).read_bytes(), (clipped / name).read_bytes(), name)
        # The shipped EAV pack, filtered again, is unchanged by the rule as well.
        rule, without = self.temp / "shipped-rule.zip", self.temp / "shipped-today.zip"
        builder.filter_archive(ASSETS / "eav-gtfs.zip", rule, "eav-gtfs", trips_run_from=date(2026, 11, 19))
        with mock.patch.object(builder, "THIN_TAIL_CLIP_SOURCES", frozenset()):
            builder.filter_archive(ASSETS / "eav-gtfs.zip", without, "eav-gtfs", trips_run_from=date(2026, 11, 19))
        self.assertEqual(without.read_bytes(), rule.read_bytes())

    def test_a_full_timetable_already_over_is_an_error_not_an_empty_pack(self) -> None:
        # Eight trains to the end of 2027, each kept alive by one more day in 2029, and one trip a day to 2030: built in
        # 2028 the clip would leave nothing that still runs.
        source = self.temp / "over-source.zip"
        write_trip_feed(
            source,
            [(f"S{index}", str(index), f"S{index}", [("X", "08:00", "08:00"), ("Y", "09:00", "09:00")]) for index in range(1, 9)]
            + self.TAIL[:1],
            calendar="".join(f"S{index},1,1,1,1,1,1,1,20261102,20271231\n" for index in range(1, 9))
            + "TAIL,1,1,1,1,1,1,1,20261102,20301231\n",
            calendar_dates="".join(f"S{index},2029010{index},1\n" for index in range(1, 9)),
        )
        with self.assertRaisesRegex(builder.BuildError, "eav-gtfs: orario completo fino al 2027-12-31, prima del 2028-02-28"):
            builder.filter_archive(source, self.temp / "over.zip", "eav-gtfs", trips_run_from=date(2028, 2, 28))


def csv_text(header: list[str], rows: list[list[str]]) -> str:
    stream = io.StringIO()
    writer = csv.writer(stream, lineterminator="\n")
    writer.writerow(header)
    writer.writerows(rows)
    return stream.getvalue()


BORDER_CATALOGUE = {"schemaVersion": 1, "stations": [
    {"id": "S1", "name": "Confine", "aliases": [], "railwayCode": "S1", "trenitaliaLocationId": "830000001", "latitude": 46.0, "longitude": 11.0},
    {"id": "S9", "name": "Svizzera", "aliases": [], "railwayCode": None, "trenitaliaLocationId": "850000009", "latitude": 46.0, "longitude": 9.0},
    {"id": "NC1", "name": "Lontano", "aliases": [], "railwayCode": None, "trenitaliaLocationId": None, "latitude": 47.0, "longitude": 11.0},
    {"id": "NC2", "name": "Valico", "aliases": [], "railwayCode": None, "trenitaliaLocationId": None, "latitude": 46.29, "longitude": 11.0},
    {"id": "NC3", "name": "Remoto", "aliases": [], "railwayCode": None, "trenitaliaLocationId": None, "latitude": 47.5, "longitude": 11.0},
]}


def write_raw_foreign_feed(target: Path) -> None:
    """Z1 is an Italian station (Confine, trenitaliaLocationId ^83); PZ1, without coordinates, is a platform of PZ,
    the station Valico of the fixture corridor; T1 calls once at an Italian station, T2 never in the zone, T3 only at
    PZ1."""
    members = {
        "agency.txt": csv_text(["agency_id", "agency_name", "agency_url", "agency_timezone"],
                               [["A1", "Rail", "https://example.test", "Europe/Vienna"], ["A2", "Other", "https://example.test", "Europe/Vienna"]]),
        "routes.txt": csv_text(["route_id", "agency_id", "route_short_name", "route_type"], [["R1", "A1", "EC", "2"], ["R2", "A2", "R", "2"]]),
        "trips.txt": csv_text(["route_id", "service_id", "trip_id"], [["R1", "W1", "T1"], ["R2", "W2", "T2"], ["R1", "W1", "T3"]]),
        "stops.txt": csv_text(["stop_id", "stop_name", "stop_lat", "stop_lon", "location_type", "parent_station"], [
            ["Z1", "Confine", "46.0", "11.0", "0", ""],
            ["PZ", "Valico", "46.29", "11.0", "1", ""],
            ["PZ1", "Valico", "", "", "0", "PZ"],
            ["F1", "Lontano", "47.0", "11.0", "0", ""],
            ["F2", "Remoto", "47.5", "11.0", "0", ""],
            ["F3", "Altrove", "48.0", "11.0", "0", ""],
        ]),
        "stop_times.txt": csv_text(["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"], [
            ["T1", "08:00:00", "08:00:00", "Z1", "1"], ["T1", "09:00:00", "09:00:00", "F1", "2"],
            ["T2", "10:00:00", "10:00:00", "F1", "1"], ["T2", "11:00:00", "11:00:00", "F3", "2"],
            ["T3", "12:00:00", "12:00:00", "PZ1", "1"], ["T3", "13:00:00", "13:00:00", "F2", "2"],
        ]),
        "calendar.txt": csv_text(["service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "start_date", "end_date"],
                                 [["W1", "1", "1", "1", "1", "1", "1", "1", "20260101", "20260331"],
                                  ["W2", "1", "1", "1", "1", "1", "1", "1", "20260101", "20260331"]]),
        "transfers.txt": csv_text(["from_stop_id", "to_stop_id", "transfer_type"], [["Z1", "F1", "2"], ["F1", "F3", "2"]]),
        "feed_info.txt": csv_text(["feed_publisher_name", "feed_publisher_url", "feed_lang"], [["Fixture", "https://example.test", "de"]]),
    }
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, text in members.items():
            archive.writestr(name, text)


def read_rows(path: Path, member: str) -> list[dict[str, str]]:
    with zipfile.ZipFile(path) as archive:
        return list(csv.DictReader(io.TextIOWrapper(archive.open(member), encoding="utf-8")))


class BorderSubsetTest(unittest.TestCase):
    """Design §1 and §6: the border subset of a foreign pack whose licence is confirmed (D2)."""

    PAIRS = {"oebb-gtfs": (("S1", "NC1", "Confine – Lontano"), ("NC2", "NC3", "Valico – Remoto"))}
    VALICO = builder.foreign.Corridor("Confine – Valico", "fixture", builder.foreign.station("S1", "Confine"),
                                      (builder.foreign.station("NC2", "Valico"),))
    CORRIDORS = {"oebb-gtfs": (VALICO,)}
    NO_CORRIDORS = {"oebb-gtfs": ()}
    # With Lontano in the corridor, T2 (Lontano – Altrove) is kept too.
    WIDER = {"oebb-gtfs": (builder.foreign.Corridor("Confine – Valico", "fixture", builder.foreign.station("S1", "Confine"),
                                                    (builder.foreign.station("NC2", "Valico"), builder.foreign.station("NC1", "Lontano"))),)}

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="border-subset-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.catalogue = self.temp / "catalogue.json"
        self.catalogue.write_text(json.dumps(BORDER_CATALOGUE), encoding="utf-8")
        self.zone_catalogue = builder.load_zone_catalogue(self.catalogue)
        self.full_dir = self.temp / "full"
        for patcher in (mock.patch.dict(builder.foreign.REQUIRED_BORDER_PAIRS, self.PAIRS, clear=True),
                        mock.patch.dict(builder.foreign.CORRIDORS, self.CORRIDORS, clear=True)):
            patcher.start()
            self.addCleanup(patcher.stop)

    def full_pack(self) -> Path:
        raw = self.temp / "raw.zip"
        write_raw_foreign_feed(raw)
        full = self.temp / "oebb-gtfs.zip"
        builder.filter_archive(raw, full, "oebb-gtfs", trips_run_from=date(2026, 1, 1))
        return full

    def registry(self, licence: str = "CC BY 4.0") -> Path:
        input_dir = self.temp / "input"
        input_dir.mkdir(exist_ok=True)
        write_raw_foreign_feed(input_dir / "oebb-gtfs.zip")
        (input_dir / "manifest.json").write_text(json.dumps({"schemaVersion": 4, "generatedAt": "2026-01-01", "packs": [{
            "sourceId": "oebb-gtfs", "asset": "gtfs/oebb-gtfs.zip", "sourceUrl": "https://example.test/oebb.zip",
            "accessMode": "OPEN_DIRECT", "attribution": "Fixture", "licence": licence, "licenceEvidence": "fixture",
            "sha256": "0" * 64, "lastServiceDate": "2026-03-31", "notes": "fixture",
        }]}), encoding="utf-8")
        return input_dir

    def build(self, licence: str = "CC BY 4.0") -> Path:
        output = self.temp / "gtfs"
        builder.build_packs(self.registry(licence), output, generated_at="2026-01-01",
                            full_pack_dir=self.full_dir, catalogue_path=self.catalogue)
        return output

    def verify(self, output: Path) -> None:
        builder.verify_assets(output, as_of=None, min_valid_days=None, full_pack_dir=self.full_dir, catalogue_path=self.catalogue)

    def assertCheckFails(self, output: Path, fragment: str) -> None:
        with self.assertRaises(builder.BuildError) as raised:
            self.verify(output)
        self.assertIn(fragment, str(raised.exception))

    def republish(self, output: Path, name: str = "oebb-gtfs.zip") -> None:
        """Make the distribution hashes follow a tampered asset, so only the content checks can catch it."""
        manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
        manifest["packs"][0]["sha256"] = hashlib.sha256((output / name).read_bytes()).hexdigest()
        manifest["packs"][0]["contentSha256"] = builder.verify_filter_from_asset(output / name, "oebb-gtfs")
        builder.write_json(output / "manifest.json", manifest)
        builder.write_sha256sums(output / "SHA256SUMS", manifest, output)

    def test_whole_trips_touching_the_zone_are_kept_with_their_rows(self) -> None:
        full = self.full_pack()
        subset = self.temp / "subset.zip"
        result = builder.derive_border_subset(full, subset, "oebb-gtfs", self.zone_catalogue)
        self.assertEqual({"T1", "T3"}, {row["trip_id"] for row in read_rows(subset, "trips.txt")})
        kept_rows = [row for row in read_rows(full, "stop_times.txt") if row["trip_id"] in {"T1", "T3"}]
        self.assertEqual(kept_rows, read_rows(subset, "stop_times.txt"))
        self.assertEqual({"Z1", "PZ", "PZ1", "F1", "F2"}, {row["stop_id"] for row in read_rows(subset, "stops.txt")})
        self.assertEqual(["R1"], [row["route_id"] for row in read_rows(subset, "routes.txt")])
        self.assertEqual(["A1"], [row["agency_id"] for row in read_rows(subset, "agency.txt")])
        self.assertEqual(["W1"], [row["service_id"] for row in read_rows(subset, "calendar.txt")])
        self.assertEqual([("Z1", "F1")], [(row["from_stop_id"], row["to_stop_id"]) for row in read_rows(subset, "transfers.txt")])
        self.assertEqual(read_rows(full, "feed_info.txt"), read_rows(subset, "feed_info.txt"))
        zone = result.metadata["borderZone"]
        self.assertEqual(("frontalieri-corridors-v1", ["Confine – Valico"], 2, 3),
                         (zone["rule"], zone["corridors"], zone["tripsKept"], zone["tripsInFull"]))
        self.assertEqual(hashlib.sha256(full.read_bytes()).hexdigest(), zone["fullPackSha256"])
        self.assertEqual(hashlib.sha256(self.catalogue.read_bytes()).hexdigest(), zone["catalogueSha256"])
        self.assertEqual(3, zone["zoneStops"])  # Z1 (Italian), PZ (corridor station) and its platform PZ1
        self.assertEqual({"agency": 1, "routes": 1, "trips": 2, "stopTimes": 4, "stops": 5}, result.metadata["counts"])
        self.assertEqual(result.content_sha256, builder.verify_filter_from_asset(subset, "oebb-gtfs"))
        # Without the corridor only the trip calling at the Italian station is kept.
        with mock.patch.dict(builder.foreign.CORRIDORS, self.NO_CORRIDORS, clear=True):
            builder.derive_border_subset(full, subset, "oebb-gtfs", self.zone_catalogue)
        self.assertEqual({"T1"}, {row["trip_id"] for row in read_rows(subset, "trips.txt")})

    def test_subset_is_deterministic_and_refuses_a_subset_as_full_pack(self) -> None:
        full = self.full_pack()
        first, second = self.temp / "one.zip", self.temp / "two.zip"
        builder.derive_border_subset(full, first, "oebb-gtfs", self.zone_catalogue)
        builder.derive_border_subset(full, second, "oebb-gtfs", self.zone_catalogue)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        with self.assertRaisesRegex(builder.BuildError, "già un sottoinsieme"):
            builder.derive_border_subset(first, self.temp / "three.zip", "oebb-gtfs", self.zone_catalogue)

    def test_build_bundles_the_subset_and_keeps_the_full_pack_outside_the_assets(self) -> None:
        output = self.build()
        manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
        entry = manifest["packs"][0]
        full = self.full_dir / "oebb-gtfs.zip"
        self.assertEqual({"rule": "frontalieri-corridors-v1"}, entry["filter"]["borderZone"])
        self.assertEqual("data/gtfs-foreign/android/oebb-gtfs.zip", entry["fullPack"]["path"])
        self.assertEqual(hashlib.sha256(full.read_bytes()).hexdigest(), entry["fullPack"]["sha256"])
        self.assertEqual(full.stat().st_size, entry["fullPack"]["sizeBytes"])
        self.assertEqual(hashlib.sha256((output / "oebb-gtfs.zip").read_bytes()).hexdigest(), entry["sha256"])
        self.assertEqual(f"{entry['fullPack']['sha256']}  oebb-gtfs.zip\n", (self.full_dir / "SHA256SUMS").read_text(encoding="utf-8"))
        self.assertEqual({"T1", "T3"}, {row["trip_id"] for row in read_rows(output / "oebb-gtfs.zip", "trips.txt")})
        self.assertEqual({"T1", "T2", "T3"}, {row["trip_id"] for row in read_rows(full, "trips.txt")})
        self.verify(output)
        # Rebuilding from the published directory reads the full pack, never the subset.
        again = self.temp / "again"
        builder.build_packs(output, again, generated_at="2026-01-01", full_pack_dir=self.full_dir, catalogue_path=self.catalogue)
        self.assertEqual(builder.archive_member_bytes(output / "oebb-gtfs.zip"), builder.archive_member_bytes(again / "oebb-gtfs.zip"))
        self.assertEqual(manifest["packs"][0]["fullPack"], json.loads((again / "manifest.json").read_text())["packs"][0]["fullPack"])

    def test_an_unconfirmed_licence_keeps_the_pack_whole(self) -> None:
        output = self.build("Da confermare dal titolare")
        entry = json.loads((output / "manifest.json").read_text(encoding="utf-8"))["packs"][0]
        self.assertNotIn("fullPack", entry)
        self.assertNotIn("borderZone", entry["filter"])
        self.assertEqual({"T1", "T2", "T3"}, {row["trip_id"] for row in read_rows(output / "oebb-gtfs.zip", "trips.txt")})
        self.assertFalse((self.full_dir / "oebb-gtfs.zip").exists())
        self.verify(output)

    def test_check_catches_removed_or_extra_trips(self) -> None:
        output = self.build()
        for corridors in (self.NO_CORRIDORS, self.WIDER):
            with self.subTest(corridors=corridors):
                with mock.patch.dict(builder.foreign.CORRIDORS, corridors, clear=True):
                    builder.derive_border_subset(self.full_dir / "oebb-gtfs.zip", output / "oebb-gtfs.zip", "oebb-gtfs", self.zone_catalogue)
                self.republish(output)
                self.assertCheckFails(output, "non è il sottoinsieme derivato")

    def test_check_catches_a_changed_rule_and_a_wrong_full_pack(self) -> None:
        output = self.build()
        manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
        manifest["packs"][0]["filter"]["borderZone"] = {"radiusKm": 35}
        builder.write_json(output / "manifest.json", manifest)
        self.assertCheckFails(output, "filter.borderZone")
        shutil.rmtree(output)
        shutil.rmtree(self.full_dir)
        output = self.build()
        manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
        manifest["packs"][0]["fullPack"]["sha256"] = "1" * 64
        builder.write_json(output / "manifest.json", manifest)
        self.assertCheckFails(output, "fullPack.sha256")
        with mock.patch.object(builder.foreign, "BORDER_ZONE_RULE", "frontalieri-corridors-v2"):
            self.assertCheckFails(output, "filter.borderZone")

    def test_check_catches_the_licence_gate_and_the_size_budget(self) -> None:
        output = self.build()
        manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
        manifest["packs"][0]["licence"] = "Da confermare dal titolare"
        builder.write_json(output / "manifest.json", manifest)
        self.assertCheckFails(output, "licenza da confermare ma con fullPack")
        output = self.build()
        with mock.patch.object(builder.foreign, "SUBSET_SIZE_BUDGET_BYTES", 10):
            self.assertCheckFails(output, "budget")
        (self.full_dir / "SHA256SUMS").write_text("", encoding="utf-8")
        self.assertCheckFails(output, "SHA256SUMS")

    def test_required_pairs_keep_every_trip_of_the_full_pack(self) -> None:
        full = self.full_pack()
        subset = self.temp / "subset.zip"
        builder.derive_border_subset(full, subset, "oebb-gtfs", self.zone_catalogue)
        self.assertEqual([], builder.border_pair_problems("oebb-gtfs", subset, full, self.catalogue))
        with mock.patch.dict(builder.foreign.CORRIDORS, self.NO_CORRIDORS, clear=True):
            builder.derive_border_subset(full, subset, "oebb-gtfs", self.zone_catalogue)
        problems = builder.border_pair_problems("oebb-gtfs", subset, full, self.catalogue)
        self.assertEqual(1, len(problems))
        self.assertIn("Valico – Remoto (NC2–NC3) ha 0 corse nel sottoinsieme e 1 nel pack completo", problems[0])

    def test_corridor_pairs_keep_every_trip_and_are_reported(self) -> None:
        full = self.full_pack()
        subset = self.temp / "subset.zip"
        with mock.patch.dict(builder.foreign.CORRIDORS, self.WIDER, clear=True):
            builder.derive_border_subset(full, subset, "oebb-gtfs", self.zone_catalogue)
            report = builder.corridor_report("oebb-gtfs", subset, full, self.zone_catalogue)
            self.assertEqual((("Confine – Valico: Confine – Valico", 0, 0), ("Confine – Valico: Confine – Lontano", 1, 1)), report.pairs)
            self.assertEqual(((), (), 4), (report.gaps, report.unresolved, report.zone_stops))  # Z1, PZ, PZ1, F1
            self.assertEqual([], builder.corridor_problems("oebb-gtfs", subset, full, self.zone_catalogue))
            # A subset derived where Confine is not an Italian station and has no corridor loses T1 (Confine – Lontano).
            catalogue = json.loads(self.catalogue.read_text(encoding="utf-8"))
            catalogue["stations"][0]["trenitaliaLocationId"] = None
            other = self.temp / "other-catalogue.json"
            other.write_text(json.dumps(catalogue), encoding="utf-8")
            with mock.patch.dict(builder.foreign.CORRIDORS, self.NO_CORRIDORS, clear=True), \
                    mock.patch.dict(builder.foreign.ITALIAN_STOP_IDS, {}, clear=True):
                with self.assertRaisesRegex(builder.BuildError, "nessuna corsa"):
                    builder.derive_border_subset(full, subset, "oebb-gtfs", builder.load_zone_catalogue(other))
            valico_only = builder.foreign.Corridor("Valico", "fixture", builder.foreign.station("NC2", "Valico"), ())
            with mock.patch.dict(builder.foreign.CORRIDORS, {"oebb-gtfs": (valico_only,)}, clear=True):
                builder.derive_border_subset(full, subset, "oebb-gtfs", builder.load_zone_catalogue(other))
            self.assertEqual({"T3"}, {row["trip_id"] for row in read_rows(subset, "trips.txt")})
            problems = builder.corridor_problems("oebb-gtfs", subset, full, self.zone_catalogue)
            self.assertEqual(["oebb-gtfs: Confine – Valico: Confine – Lontano ha 0 corse nel sottoinsieme e 1 nel pack completo"], problems)
            del catalogue["stations"][2]  # Lontano leaves the catalogue: the station names no stop any more
            other.write_text(json.dumps(catalogue), encoding="utf-8")
            report = builder.corridor_report("oebb-gtfs", subset, full, builder.load_zone_catalogue(other))
            self.assertEqual(("Confine – Valico: Lontano",), report.unresolved)

    def test_apply_border_zones_moves_the_whole_pack_out_and_back(self) -> None:
        output = self.build("Da confermare dal titolare")
        whole = (output / "oebb-gtfs.zip").read_bytes()
        (output / "foreign_packs.json").write_text("{}\n", encoding="utf-8")
        manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
        manifest["packs"][0]["licence"] = "CC BY 4.0"
        builder.write_json(output / "manifest.json", manifest)
        report = builder.apply_border_zones(output, full_pack_dir=self.full_dir, catalogue_path=self.catalogue)
        self.assertEqual([{"sourceId": "oebb-gtfs", "borderOnly": True, "fullBytes": len(whole),
                           "zoneBytes": (output / "oebb-gtfs.zip").stat().st_size, "tripsKept": 2, "tripsInFull": 3}], report)
        self.assertEqual(whole, (self.full_dir / "oebb-gtfs.zip").read_bytes())
        self.assertTrue((output / "foreign_packs.json").is_file())
        self.verify(output)
        subset = (output / "oebb-gtfs.zip").read_bytes()
        builder.apply_border_zones(output, full_pack_dir=self.full_dir, catalogue_path=self.catalogue)
        self.assertEqual(subset, (output / "oebb-gtfs.zip").read_bytes())
        manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
        manifest["packs"][0]["licence"] = "Da confermare dal titolare"
        builder.write_json(output / "manifest.json", manifest)
        builder.apply_border_zones(output, full_pack_dir=self.full_dir, catalogue_path=self.catalogue)
        self.assertEqual(whole, (output / "oebb-gtfs.zip").read_bytes())
        self.assertFalse(self.full_dir.exists())
        self.assertNotIn("fullPack", json.loads((output / "manifest.json").read_text(encoding="utf-8"))["packs"][0])
        self.verify(output)


SWISS = "ch-otd-gtfs"
SWISS_RUNS_FROM = date(2026, 12, 5)
SWISS_RAIL_STOPS = ("8503000", "8503006", "8505000", "8507000")
# Limits of a few kilobytes in place of the gigabytes: the synthetic feed is over the defaults below and within these.
SMALL_DEFAULTS = {"MAX_ARCHIVE_UNCOMPRESSED_BYTES": 64 * 1024, "MAX_ARCHIVE_MEMBER_BYTES": 32 * 1024, "MAX_TABLE_ROWS": 1_000}


def small_review(**changes: int) -> "builder.LargeArchiveReview":
    values: dict[str, object] = {
        "reason": "fixture", "max_download_bytes": 1024 * 1024, "max_uncompressed_bytes": 1024 * 1024,
        "max_streamed_member_bytes": 512 * 1024, "max_streamed_table_rows": 10_000,
    }
    values.update(changes)
    return builder.LargeArchiveReview(**values)  # type: ignore[arg-type]


def write_swiss_like_feed(
    target: Path, *, bus_trips: int = 400, expired_trips: int = 3, frequencies: tuple[str, ...] = (),
    extra: Mapping[str, bytes] | None = None, hierarchy: bool = False, shared_trip_id: bool = False,
    unknown_stop: bool = False,
) -> dict[str, list[str]]:
    """A synthetic feed shaped like the Swiss national one (its identifier style, invented values): three rail trips
    running to 12 December 2026, ``expired_trips`` rail trips whose service ended on 30 November, and ``bus_trips`` buses
    of ten calls each that the rail filter drops, so stop_times.txt is the large table; ``frequencies`` names the kinds
    of rail trip ("alive", "expired") that run by frequencies.txt.  ``hierarchy`` adds two levels of parent stations, a
    parent absent from stops.txt, a stop listed twice (the second row names a parent) and a parent cycle;
    ``shared_trip_id`` gives the third running trip's id to a trip of the expired service as well; ``unknown_stop``
    makes a running trip call at a stop stops.txt lacks.  Returns the trip ids by kind."""
    alive = ["1.TA.91-10-A-j26-1.1.H", "2.TA.91-10-A-j26-1.1.R", "1.TA.91-1-P-j26-1.1.H"]
    expired = [f"{index}.TA+old.91-10-A-j26-1.2.H" for index in range(1, expired_trips + 1)]
    buses = [f"{index}.TA.92-{index % 7}-j26-1.1.H" for index in range(bus_trips)]
    route_of = {trip: trip.split(".")[2] for trip in alive + expired + buses}
    trips = [[route_of[trip], "TA+old" if trip in expired else "TA", trip, str(12000 + index)]
             for index, trip in enumerate(alive + expired)]
    trips += [[route_of[trip], "BUS", trip, ""] for trip in buses]
    stop_times = [[trip, f"{8 + call:02d}:{index % 60:02d}:00", f"{8 + call:02d}:{index % 60:02d}:00",
                   SWISS_RAIL_STOPS[call] if call else "8503000:0:3", str(call + 1)]
                  for index, trip in enumerate(alive + expired) for call in range(4)]
    stop_times += [[trip, f"{6 + call:02d}:{index % 60:02d}:00", f"{6 + call:02d}:{index % 60:02d}:00",
                    f"859{(index + call) % 6:04d}", str(call + 1)]
                   for index, trip in enumerate(buses) for call in range(10)]
    if shared_trip_id:
        trips.append(["91-1-P-j26-1", "TA+old", alive[2], "12999"])
    if unknown_stop:
        stop_times.append([alive[0], "12:00:00", "12:00:00", "8509999", "5"])
    stops = [["Parent8503000", "Zürich HB", "47.378", "8.540", "1", ""],
             ["8503000:0:3", "Zürich HB", "47.378", "8.540", "", "Parent8503000"]]
    stops += [[stop, f"Bahnhof {stop}", "47.0", "8.0", "", ""] for stop in SWISS_RAIL_STOPS[1:]]
    stops += [[f"859{index:04d}", f"Bushalt {index}", "46.9", "7.9", "", ""] for index in range(6)]
    if hierarchy:
        parent_of = {"8505000": "Parent8505000", "8507000": "Missing8507000"}
        stops = [row[:5] + [parent_of.get(row[0], row[5])] for row in stops]
        stops += [["Parent8505000", "Luzern", "47.05", "8.31", "1", "Area8505000"],
                  ["Area8505000", "Luzern Bahnhofplatz", "47.05", "8.31", "1", ""],
                  ["8503006", "Zürich Oerlikon", "47.41", "8.54", "", "Parent8503006"],
                  ["Parent8503006", "Zürich Oerlikon", "47.41", "8.54", "1", "8503006"]]
    weekdays = ["1"] * 7
    members = {
        "agency.txt": csv_text(["agency_id", "agency_name", "agency_url", "agency_timezone"],
                               [["11", "Schweizerische Bundesbahnen SBB", "https://example.test", "Europe/Zurich"],
                                ["801", "PostAuto", "https://example.test", "Europe/Zurich"]]),
        "stops.txt": csv_text(["stop_id", "stop_name", "stop_lat", "stop_lon", "location_type", "parent_station"], stops),
        "routes.txt": csv_text(["route_id", "agency_id", "route_short_name", "route_type"],
                               [["91-10-A-j26-1", "11", "S10", "109"], ["91-1-P-j26-1", "11", "IC1", "102"]]
                               + [[f"92-{index}-j26-1", "801", str(100 + index), "700"] for index in range(7)]),
        "trips.txt": csv_text(["route_id", "service_id", "trip_id", "trip_short_name"], trips),
        "stop_times.txt": csv_text(["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"], stop_times),
        "calendar.txt": csv_text(["service_id", *builder.WEEKDAY_COLUMNS, "start_date", "end_date"],
                                 [["TA", *weekdays, "20251214", "20261212"], ["TA+old", *weekdays, "20251214", "20261130"],
                                  ["BUS", *weekdays, "20251214", "20261212"]]),
        "calendar_dates.txt": csv_text(["service_id", "date", "exception_type"],
                                       [["TA", "20251225", "2"], ["TA+old", "20251226", "2"], ["BUS", "20251225", "2"]]),
        "transfers.txt": csv_text(["from_stop_id", "to_stop_id", "transfer_type", "min_transfer_time"],
                                  [["8503000:0:3", "8503006", "2", "300"], ["Parent8503000", "8503006", "2", "240"],
                                   ["8590000", "8590001", "2", "120"]]),
        "feed_info.txt": csv_text(["feed_publisher_name", "feed_publisher_url", "feed_lang", "feed_version"],
                                  [["Fixture", "https://example.test", "DE", "20261121"]]),
    }
    if frequencies:
        members["frequencies.txt"] = csv_text(["trip_id", "start_time", "end_time", "headway_secs"], [
            row for kind, row in (("alive", [alive[0], "08:00:00", "09:00:00", "1800"]), ("expired", [expired[0], "08:00:00", "10:00:00", "3600"]))
            if kind in frequencies
        ])
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, text in members.items():
            archive.writestr(name, text)
        for name, data in (extra or {}).items():
            archive.writestr(name, data)
    return {"alive": alive, "expired": expired, "buses": buses}


def CsvTableOf(table: "builder.CsvTable", services: set[str]) -> "builder.CsvTable":
    """``table`` with the rows of ``services`` only, as filter_archive reads the calendar of its kept trips."""
    return builder.CsvTable(table.header, tuple(row for row in table.rows if row.get("service_id", "") in services))


class RecordingBudget(builder.RetentionBudget):
    """The retention budget, counting what each table retained and what was released."""

    retained: dict[str, int] = {}
    released: list[int] = []

    def retain(self, basename: str, row: Mapping[str, str]) -> None:
        RecordingBudget.retained[basename] = RecordingBudget.retained.get(basename, 0) + 1
        super().retain(basename, row)

    def release(self, basename, rows) -> None:  # type: ignore[no-untyped-def]
        rows = list(rows)
        RecordingBudget.released.append(len(rows))
        super().release(basename, rows)


class ReviewedLargeArchiveTest(unittest.TestCase):
    """The Swiss national archive (4.6 GB decompressed in November 2026) under its reviewed limits
    (REVIEWED_LARGE_ARCHIVES): streamed, filtered while read, and still refused where the guards must refuse it."""

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="reviewed-large-archive-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.review = small_review()
        patcher = mock.patch.object(builder, "REVIEWED_LARGE_ARCHIVES", {SWISS: self.review})
        patcher.start()
        self.addCleanup(patcher.stop)

    @contextlib.contextmanager
    def small_defaults(self, **extra: int):  # type: ignore[no-untyped-def]
        with contextlib.ExitStack() as stack:
            for name, value in {**SMALL_DEFAULTS, **extra}.items():
                stack.enter_context(mock.patch.object(builder, name, value))
            yield

    def feed(self, name: str = "swiss.zip", **options: Any) -> tuple[Path, dict[str, list[str]]]:
        path = self.temp / name
        return path, write_swiss_like_feed(path, **options)

    def filter(self, source: Path, source_id: str = SWISS, runs_from: date | None = SWISS_RUNS_FROM,
               name: str = "out.zip") -> tuple[Path, "builder.FilterResult", str]:
        output = self.temp / name
        log = io.StringIO()
        with contextlib.redirect_stderr(log):
            result = builder.filter_archive(source, output, source_id, trips_run_from=runs_from)
        return output, result, log.getvalue()

    def test_the_feed_is_over_every_default_limit_the_review_replaces(self) -> None:
        source, _ = self.feed()
        with zipfile.ZipFile(source) as archive, self.small_defaults():
            stop_times = archive.getinfo("stop_times.txt")
            self.assertGreater(sum(entry.file_size for entry in archive.infolist()), builder.MAX_ARCHIVE_UNCOMPRESSED_BYTES)
            self.assertGreater(stop_times.file_size, builder.MAX_ARCHIVE_MEMBER_BYTES)
            self.assertLess(stop_times.file_size / stop_times.compress_size, builder.MAX_COMPRESSION_RATIO)
            self.assertGreater(len(archive.read("stop_times.txt").splitlines()) - 1, builder.MAX_TABLE_ROWS)

    def test_a_large_table_is_streamed_and_only_the_trips_the_pack_keeps_are_retained(self) -> None:
        source, trips = self.feed()
        RecordingBudget.retained, RecordingBudget.released = {}, []
        with self.small_defaults(), mock.patch.object(builder, "RetentionBudget", RecordingBudget):
            output, result, log = self.filter(source)
        # Every table is held only for what the pack keeps: the rail routes, the three rail trips that still run (not
        # the 400 buses, not the trips whose service ended before tripsRunFrom), their calendar, their 12 calls out of
        # 4,024, their stops with the parent station, the two transfers between them.  Nothing is held and dropped.
        self.assertEqual({"routes.txt": 2, "trips.txt": 3, "stop_times.txt": 12, "calendar.txt": 1, "calendar_dates.txt": 1,
                          "stops.txt": 5, "agency.txt": 2, "transfers.txt": 2, "feed_info.txt": 1}, RecordingBudget.retained)
        self.assertEqual([], RecordingBudget.released)
        self.assertIn(f"{SWISS}: {len(trips['expired'])} trip che non circolano più dal {SWISS_RUNS_FROM} rimossi", log)
        self.assertIn(f"{SWISS}: stop_times.txt {4 * 6 + 10 * 400} righe lette, 12 tenute", log)
        self.assertRegex(log, rf"{SWISS}: trattenute 29 righe \(limite 1000000\), \d+ celle \(limite 6000000\), \d+ byte "
                              r"\(limite 134217728\); righe trattenute per tabella: stop_times\.txt 12, stops\.txt 5, trips\.txt 3, ")
        self.assertIn(f"{SWISS}: archivio rivisto, ", log)
        self.assertIn("membri più grandi: stop_times.txt ", log)
        self.assertEqual(trips["alive"], [row["trip_id"] for row in read_rows(output, "trips.txt")])
        self.assertEqual({(trip, str(call)) for trip in trips["alive"] for call in range(1, 5)},
                         {(row["trip_id"], row["stop_sequence"]) for row in read_rows(output, "stop_times.txt")})
        self.assertEqual(["TA"], [row["service_id"] for row in read_rows(output, "calendar.txt")])
        self.assertFalse(any(row["stop_id"].startswith("859") for row in read_rows(output, "stops.txt")))
        self.assertEqual(SWISS_RUNS_FROM.isoformat(), result.filter_metadata["tripsRunFrom"])
        self.assertEqual("gtfs-rail-route-types", result.filter_metadata["policy"]["kind"])

    def test_an_unreviewed_source_over_the_limit_is_refused_naming_its_largest_members(self) -> None:
        source, _ = self.feed()
        for source_id in ("sncf-gtfs", "other-gtfs"):
            with self.subTest(source_id=source_id), self.small_defaults(), self.assertRaises(builder.BuildError) as raised:
                self.filter(source, source_id)
            message = str(raised.exception)
            self.assertRegex(message, r"^archivio GTFS decompresso oltre limite: \d+ > 65536 bytes \(membri più grandi: stop_times\.txt ")
            self.assertNotIn("REVIEWED", message)

    def test_the_reviewed_limits_and_the_other_guards_still_refuse(self) -> None:
        source, _ = self.feed()
        with zipfile.ZipFile(source) as archive:
            total = sum(entry.file_size for entry in archive.infolist())
            stop_times = archive.getinfo("stop_times.txt").file_size
        cases = {
            "reviewed total": (small_review(max_uncompressed_bytes=total - 1), source,
                               r"decompresso oltre limite: \d+ > \d+ bytes \(limite rivisto di ch-otd-gtfs.*membri più grandi: stop_times\.txt"),
            "reviewed member": (small_review(max_streamed_member_bytes=stop_times - 1), source,
                                r"membro GTFS oltre limite: 'stop_times\.txt' \d+ > \d+ bytes \(limite rivisto"),
            "reviewed rows": (small_review(max_streamed_table_rows=4_000), source, r"tabella oltre limite righe: stop_times\.txt > 4000"),
        }
        large_stops = self.temp / "large-stops.zip"
        write_swiss_like_feed(large_stops, bus_trips=5, extra={"nested/stops.txt": b"x" * (48 * 1024)})
        cases["a member that is not streamed keeps the default limit"] = (
            self.review, large_stops, r"membro GTFS oltre limite: 'nested/stops\.txt' 49152 > 32768 bytes$")
        bomb = self.temp / "ratio.zip"
        write_swiss_like_feed(bomb, bus_trips=5, extra={"x/calendar_dates.txt": b"A" * 300_000})
        cases["compression ratio"] = (self.review, bomb, r"rapporto di compressione GTFS sospetto: 'x/calendar_dates\.txt'")
        entries = self.temp / "entries.zip"
        write_swiss_like_feed(entries, bus_trips=5, extra={f"extra/{index}.txt": b"" for index in range(builder.MAX_ARCHIVE_ENTRIES)})
        cases["entries"] = (self.review, entries, r"archivio GTFS con troppe entry")
        for name, (review, archive, pattern) in cases.items():
            with self.subTest(name), self.small_defaults(), \
                    mock.patch.object(builder, "REVIEWED_LARGE_ARCHIVES", {SWISS: review}), \
                    self.assertRaisesRegex(builder.BuildError, pattern):
                self.filter(archive)

    def test_the_retained_row_cell_and_byte_budgets_still_apply_to_what_is_kept(self) -> None:
        # The error names the rows each table holds when the build stops.
        source, _ = self.feed()
        held = r"\(righe trattenute per tabella: stop_times\.txt \d+, trips\.txt 3, routes\.txt 2"
        for name, value, pattern in (
            ("MAX_RETAINED_TABLE_ROWS", 11, r"^tabella oltre budget righe trattenute: stop_times\.txt > 11 \(righe trattenute per tabella: "
                                            r"stop_times\.txt 11, trips\.txt 3, routes\.txt 2\)$"),
            ("MAX_RETAINED_BUILD_ROWS", 20, r"^build GTFS oltre budget righe trattenute: 21 > 20 \(righe trattenute per tabella: "
                                            r"stop_times\.txt 12, trips\.txt 3, routes\.txt 2, stops\.txt 2, calendar\.txt 1, "
                                            r"calendar_dates\.txt 1\)$"),
            ("MAX_RETAINED_BUILD_CELLS", 60, r"^build GTFS oltre budget celle trattenute: \d+ > 60 " + held),
            ("MAX_RETAINED_BUILD_UTF8_BYTES", 400, r"^build GTFS oltre budget byte UTF-8 trattenuti: \d+ > 400 " + held),
        ):
            with self.subTest(name), self.small_defaults(**{name: value}), self.assertRaisesRegex(builder.BuildError, pattern):
                self.filter(source)

    def test_the_reviewed_budget_replaces_the_build_totals_and_names_the_tables(self) -> None:
        source, _ = self.feed()
        with self.small_defaults(MAX_RETAINED_BUILD_ROWS=20, MAX_RETAINED_BUILD_CELLS=60):
            with mock.patch.object(builder, "REVIEWED_LARGE_ARCHIVES", {SWISS: small_review(max_retained_rows=40, max_retained_cells=200)}):
                self.filter(source)
            with mock.patch.object(builder, "REVIEWED_LARGE_ARCHIVES", {SWISS: small_review(max_retained_rows=25, max_retained_cells=200)}), \
                    self.assertRaisesRegex(builder.BuildError, r"^build GTFS oltre budget righe trattenute: 26 > 25 \(righe trattenute "
                                                               r"per tabella: stop_times\.txt 12, stops\.txt 5, trips\.txt 3, "
                                                               r"agency\.txt 2, routes\.txt 2, calendar\.txt 1, calendar_dates\.txt 1\)$"):
                self.filter(source)
            # The per-table limit stays: every reader of the published pack applies it.
            with self.small_defaults(MAX_RETAINED_TABLE_ROWS=11), \
                    mock.patch.object(builder, "REVIEWED_LARGE_ARCHIVES", {SWISS: small_review(max_retained_rows=40, max_retained_cells=200)}), \
                    self.assertRaisesRegex(builder.BuildError, r"tabella oltre budget righe trattenute: stop_times\.txt > 11"):
                self.filter(source)

    def test_every_source_names_the_rows_each_table_holds_when_its_budget_stops_the_build(self) -> None:
        source = self.temp / "amt.zip"
        write_fixture_zip(source)
        with mock.patch.object(builder, "MAX_RETAINED_BUILD_ROWS", 6), \
                self.assertRaisesRegex(builder.BuildError, r"^build GTFS oltre budget righe trattenute: 7 > 6 \(righe trattenute per "
                                                           r"tabella: routes\.txt 2, stop_times\.txt 2, calendar\.txt 1, "
                                                           r"frequencies\.txt 1, trips\.txt 1\)$"):
            self.filter(source, "amt-genova-casella-gtfs")

    def test_the_reviewed_path_keeps_exactly_what_the_current_builder_keeps(self) -> None:
        # Under the old limits the same archive goes through both paths: the pack must be the same bytes.
        cases = {
            "trips that expired": ({}, SWISS_RUNS_FROM),
            "no tripsRunFrom": ({}, None),
            "nothing expired": ({}, date(2026, 1, 1)),
            "frequencies of a trip that runs and of one that expired": ({"frequencies": ("alive", "expired")}, SWISS_RUNS_FROM),
            "frequencies of an expired trip only": ({"frequencies": ("expired",)}, SWISS_RUNS_FROM),
            "no expired trip in the feed": ({"expired_trips": 0}, SWISS_RUNS_FROM),
            "parent stations on two levels, absent, repeated and in a cycle": ({"hierarchy": True}, SWISS_RUNS_FROM),
            "a trip id shared by a running and an expired service": ({"shared_trip_id": True}, SWISS_RUNS_FROM),
        }
        for name, (options, runs_from) in cases.items():
            with self.subTest(name):
                source, trips = self.feed(f"{len(name)}.zip", bus_trips=5, **options)
                reviewed, reviewed_result, log = self.filter(source, runs_from=runs_from, name="reviewed.zip")
                self.assertIn("archivio rivisto", log)
                with mock.patch.object(builder, "REVIEWED_LARGE_ARCHIVES", {}):
                    default, default_result, default_log = self.filter(source, runs_from=runs_from, name="default.zip")
                self.assertNotIn("archivio rivisto", default_log)
                self.assertEqual(default.read_bytes(), reviewed.read_bytes())
                self.assertEqual(default_result, reviewed_result)
                kept = {row["trip_id"].split("@")[0] for row in read_rows(reviewed, "trips.txt")}
                expected = set(trips["alive"]) | (set() if runs_from == SWISS_RUNS_FROM else set(trips["expired"]))
                self.assertEqual(expected - ({trips["alive"][2]} if options.get("shared_trip_id") else set()), kept)
                if options.get("hierarchy"):
                    self.assertEqual({"8503000:0:3", "Parent8503000", "8503006", "Parent8503006", "8505000", "Parent8505000",
                                      "Area8505000", "8507000"}, {row["stop_id"] for row in read_rows(reviewed, "stops.txt")})
        dead, _ = self.feed("dead.zip", bus_trips=5)
        unknown, _ = self.feed("unknown.zip", bus_trips=5, unknown_stop=True)
        for source, runs_from, message in ((dead, date(2026, 12, 20), "i trip filtrati non hanno stop_times"),
                                           (unknown, SWISS_RUNS_FROM, r"stop_times riferisce stop assenti: \['8509999'\]")):
            for table in ({SWISS: self.review}, {}):
                with self.subTest(message, reviewed=bool(table)), mock.patch.object(builder, "REVIEWED_LARGE_ARCHIVES", table), \
                        self.assertRaisesRegex(builder.BuildError, message):
                    self.filter(source, runs_from=runs_from)

    def test_the_running_services_read_without_retaining_are_those_service_days_gives(self) -> None:
        # running_services reads the calendar without holding it: the same services as expired_trips over service_days,
        # on random calendars (several rows per service, removed and added days, unknown exception types, empty dates).
        import random

        def check(calendar: list[list[str]], exceptions: list[list[str]], asked: set[str], runs_from: date, name: object) -> None:
            path = self.temp / "calendar.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("calendar.txt", csv_text(["service_id", *builder.WEEKDAY_COLUMNS, "start_date", "end_date"], calendar))
                archive.writestr("calendar_dates.txt", csv_text(["service_id", "date", "exception_type"], exceptions))
            with zipfile.ZipFile(path) as archive:
                table = builder.read_table(archive, "calendar.txt") or builder.empty_calendar()
                dates = builder.read_table(archive, "calendar_dates.txt") or builder.empty_calendar_dates()
                days = builder.service_days(CsvTableOf(table, asked), CsvTableOf(dates, asked), SWISS)
                expected = {service for service, run in days.items() if run and max(run) >= runs_from}
                with self.subTest(name):
                    self.assertEqual(expected, builder.running_services(archive, SWISS, asked, runs_from, None))

        # Mondays only from 30 November 2026: the two left from 5 December removed (gone), one of them added back
        # (runs), removed by an unknown exception type (gone), or a single added day after a calendar that ended.
        mondays = [["M", "1", "0", "0", "0", "0", "0", "0", "20261130", "20261214"]]
        check(mondays, [["M", "20261207", "2"], ["M", "20261214", "2"]], {"M"}, SWISS_RUNS_FROM, "removed")
        check(mondays, [["M", "20261207", "2"], ["M", "20261214", "2"], ["M", "20261214", "1"]], {"M"}, SWISS_RUNS_FROM, "added back")
        check(mondays, [["M", "20261207", "3"], ["M", "20261214", ""]], {"M"}, SWISS_RUNS_FROM, "unknown types")
        check([["E", *"1111111", "20261101", "20261130"]], [["E", "20261206", "1"]], {"E"}, SWISS_RUNS_FROM, "added later")
        check(mondays, [], set(), SWISS_RUNS_FROM, "not asked")
        generator = random.Random(20261124)
        first = date(2026, 11, 1)
        for attempt in range(300):
            services = [f"S{index}" for index in range(generator.randint(1, 8))]
            calendar, exceptions = [], []
            for service in services:
                for _ in range(generator.randint(0, 2)):
                    start = first + timedelta(days=generator.randint(0, 40))
                    end = start + timedelta(days=generator.randint(-3, 12))
                    calendar.append([service, *(generator.choice("01") for _ in range(7)),
                                     start.strftime("%Y%m%d"), "" if generator.random() < 0.05 else end.strftime("%Y%m%d")])
                for _ in range(generator.randint(0, 12)):
                    day = first + timedelta(days=generator.randint(0, 55))
                    exceptions.append([service, "" if generator.random() < 0.05 else day.strftime("%Y%m%d"),
                                       generator.choice(["1", "2", "2", "2", "3", ""])])
            runs_from = first + timedelta(days=generator.randint(0, 55))
            check(calendar, exceptions, set(generator.sample(services, generator.randint(0, len(services)))), runs_from, attempt)

    def test_the_published_swiss_full_pack_filters_to_the_same_bytes_on_both_paths(self) -> None:
        # Real Swiss rows: the full pack published in data/gtfs-foreign, filtered from the last day of its median trip,
        # so that the trips ending before it no longer run.
        manifest = json.loads((ROOT / "app/src/main/assets/gtfs/manifest.json").read_text(encoding="utf-8"))
        entry = next(pack for pack in manifest["packs"] if pack["sourceId"] == SWISS)
        full = ROOT / entry["fullPack"]["path"]
        with zipfile.ZipFile(full) as archive:
            calendar = builder.read_table(archive, "calendar.txt") or builder.empty_calendar()
            exceptions = builder.read_table(archive, "calendar_dates.txt") or builder.empty_calendar_dates()
            trips = builder.read_table(archive, "trips.txt", required=True)
        assert trips is not None
        days = builder.service_days(calendar, exceptions, SWISS)
        ends = sorted(max(days[row["service_id"]]) for row in trips.rows if days.get(row["service_id"]))
        runs_from = ends[len(ends) // 2]
        with mock.patch.object(builder, "REVIEWED_LARGE_ARCHIVES", {SWISS: builder.LargeArchiveReview(
                "fixture", 1 << 30, 1 << 30, 1 << 30, 10_000_000)}):
            reviewed, reviewed_result, _ = self.filter(full, runs_from=runs_from, name="reviewed.zip")
        with mock.patch.object(builder, "REVIEWED_LARGE_ARCHIVES", {}):
            default, default_result, _ = self.filter(full, runs_from=runs_from, name="default.zip")
        self.assertEqual(default.read_bytes(), reviewed.read_bytes())
        self.assertEqual(default_result, reviewed_result)
        if ends[0] < runs_from:
            self.assertLess(reviewed_result.filter_metadata["counts"]["trips"], len(trips.rows))

    def test_refresh_downloads_each_source_under_its_own_cap(self) -> None:
        manifest = {"packs": [
            {"sourceId": SWISS, "asset": f"gtfs/{SWISS}.zip", "sourceUrl": "https://example.test/ch.zip", "accessMode": "OPEN_DIRECT"},
            {"sourceId": "sncf-gtfs", "asset": "gtfs/sncf-gtfs.zip", "sourceUrl": "https://example.test/fr.zip", "accessMode": "OPEN_DIRECT"},
        ]}
        for explicit, expected in ((None, {SWISS: self.review.max_download_bytes, "sncf-gtfs": builder.DEFAULT_MAX_DOWNLOAD_BYTES}),
                                   (1234, {SWISS: 1234, "sncf-gtfs": 1234})):
            with self.subTest(explicit=explicit), mock.patch.object(builder, "download_to_file") as download:
                _, directory = builder.refresh_overrides(manifest, set(), max_download_bytes=explicit)
                shutil.rmtree(directory, ignore_errors=True)
                self.assertEqual(expected, {call.args[1].stem: call.kwargs["max_bytes"] for call in download.call_args_list})


class ReviewedLargeArchiveRegistryTest(unittest.TestCase):
    def test_only_switzerland_is_reviewed_and_its_limits_are_an_exception_to_the_defaults(self) -> None:
        self.assertEqual({SWISS}, set(builder.REVIEWED_LARGE_ARCHIVES))
        review = builder.REVIEWED_LARGE_ARCHIVES[SWISS]
        self.assertIn("4,6 GB", review.reason)
        self.assertGreater(review.max_uncompressed_bytes, builder.MAX_ARCHIVE_UNCOMPRESSED_BYTES)
        self.assertGreater(review.max_streamed_member_bytes, builder.MAX_ARCHIVE_MEMBER_BYTES)
        self.assertLessEqual(review.max_streamed_member_bytes, review.max_uncompressed_bytes)
        self.assertGreater(review.max_streamed_table_rows, builder.MAX_TABLE_ROWS)
        self.assertGreater(review.max_download_bytes, builder.DEFAULT_MAX_DOWNLOAD_BYTES)
        # The build totals: stop_times.txt up to the per-table limit, which every reader of the published pack keeps.
        self.assertGreaterEqual(review.max_retained_rows, 2 * builder.MAX_RETAINED_TABLE_ROWS)
        self.assertGreater(review.max_retained_cells, builder.MAX_RETAINED_BUILD_CELLS)
        self.assertIsNone(review.max_retained_utf8_bytes)
        self.assertEqual((review.max_retained_rows, review.max_retained_cells, None),
                         (review.budget().max_rows, review.budget().max_cells, review.budget().max_utf8_bytes))
        self.assertNotIn(SWISS, builder.ITINERARY_COPY_SOURCES)
        self.assertFalse({"routes.txt", "stops.txt", "agency.txt"} & builder.STREAMED_TABLES)
        manifest = json.loads((ROOT / "app/src/main/assets/gtfs/manifest.json").read_text(encoding="utf-8"))
        pack = next(pack for pack in manifest["packs"] if pack["sourceId"] == SWISS)
        self.assertEqual("OPEN_DIRECT", pack["accessMode"])
        self.assertIsNone(builder.large_archive_review("sncf-gtfs"))
        self.assertIsNone(builder.large_archive_review(None))
        self.assertEqual(review.max_download_bytes, builder.source_download_limit(SWISS, 1))
        self.assertEqual(1, builder.source_download_limit("sncf-gtfs", 1))


if __name__ == "__main__":
    unittest.main()
