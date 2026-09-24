#!/usr/bin/env python3
"""Offline regression tests for tools/build_gtfs_packs.py."""
from __future__ import annotations

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
            self.assertEqual(
                builder.canonical_member_digests(archive, "binario_filter_manifest.json"),
                filter_manifest["memberSha256"],
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
                    payload = payload.replace(b"FGC-164", b"FGC-165")
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


if __name__ == "__main__":
    unittest.main()
