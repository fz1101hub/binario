from __future__ import annotations

import csv
import hashlib
import importlib.util
import io
import json
import shutil
import sqlite3
import sys
import tempfile
import unittest
import zipfile
import zlib
from datetime import date
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]

def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module

builder = load_module("build_ios_gtfs_schedule", ROOT / "tools/build_ios_gtfs_schedule.py")
verifier = load_module("verify_ios_gtfs_schedule", ROOT / "tools/verify_ios_gtfs_schedule.py")
rebuild_verifier = load_module("verify_ios_gtfs_rebuild", ROOT / "tools/verify_ios_gtfs_rebuild.py")

def csv_bytes(headers: list[str], rows: list[list[str]]) -> bytes:
    stream = io.StringIO(newline="")
    writer = csv.writer(stream, lineterminator="\n")
    writer.writerow(headers)
    writer.writerows(rows)
    return stream.getvalue().encode("utf-8")

def write_feed(
    path: Path,
    *,
    unsafe: bool = False,
    route_type: str = "2",
    timezone: str = "Europe/Rome",
    child_latitude: str = "45",
    child_longitude: str = "9",
    second_arrival: str = "25:00:00",
    second_departure: str = "25:01:00",
) -> None:
    members = {
        "agency.txt": csv_bytes(["agency_id", "agency_name", "agency_url", "agency_timezone"], [["A", "Rail", "https://example.test", timezone]]),
        "routes.txt": csv_bytes(["route_id", "agency_id", "route_short_name", "route_long_name", "route_type"], [["R", "A", "R1", "Test", route_type]]),
        "trips.txt": csv_bytes(["route_id", "service_id", "trip_id", "trip_headsign", "direction_id"], [["R", "WK", "KEEP", "Terminus", "0"], ["R", "WK", "DROP", "Elsewhere", "1"]]),
        "stops.txt": csv_bytes(["stop_id", "stop_name", "stop_lat", "stop_lon", "location_type", "parent_station"], [["P", "Citta Alta", "45", "9", "1", ""], ["PL", "Platform 1", child_latitude, child_longitude, "0", "P"], ["X", "Nowhere", "46", "10", "0", ""], ["Y", "Elsewhere", "47", "11", "0", ""]]),
        "stop_times.txt": csv_bytes(["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence", "pickup_type", "drop_off_type"], [["KEEP", second_arrival, second_departure, "PL", "2", "0", "0"], ["KEEP", "23:00:00", "23:00:00", "X", "1", "0", "0"], ["DROP", "10:00:00", "10:00:00", "Y", "1", "0", "0"]]),
        "calendar.txt": csv_bytes(["service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "start_date", "end_date"], [["WK", "1", "0", "0", "0", "0", "0", "0", "20260105", "20260107"]]),
        "calendar_dates.txt": csv_bytes(["service_id", "date", "exception_type"], [["WK", "20260105", "2"], ["WK", "20260106", "1"]]),
    }
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in members.items():
            archive.writestr("../stops.txt" if unsafe and name == "stops.txt" else name, content)

def write_inputs(root: Path, **feed_options: object) -> tuple[Path, Path]:
    gtfs = root / "gtfs"
    gtfs.mkdir()
    packs = []
    for index in range(10):
        archive = gtfs / f"pack-{index}.zip"
        options = dict(feed_options)
        options["unsafe"] = bool(feed_options.get("unsafe")) and index == 0
        write_feed(archive, **options)
        packs.append({"sourceId": f"pack-{index}", "asset": f"gtfs/{archive.name}", "sourceUrl": f"https://example.test/pack-{index}.zip", "accessMode": "OPEN_DIRECT", "attribution": "Test data", "lastServiceDate": "2026-01-07", "sha256": hashlib.sha256(archive.read_bytes()).hexdigest()})
    manifest = gtfs / "manifest.json"
    manifest.write_text(json.dumps({"generatedAt": "2026-01-04", "packs": packs}), encoding="utf-8")
    catalog = root / "catalog.json"
    catalog.write_text(json.dumps({"schemaVersion": 1, "stations": [{"id": "S1", "name": "Città-Alta", "aliases": ["CITTA ALTA"], "railwayCode": "S1", "trenitaliaLocationId": None, "latitude": 45.0, "longitude": 9.0}, {"id": "N1", "name": "Nowhere", "aliases": [], "railwayCode": None, "trenitaliaLocationId": None, "latitude": 46.0, "longitude": 10.0}]}), encoding="utf-8")
    return manifest, catalog

class IosGtfsScheduleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="ios-gtfs-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)

    def build(self):
        manifest, catalog = write_inputs(self.temp)
        output = self.temp / "schedule.sqlite.zlib"
        metadata = self.temp / "schedule.metadata.json"
        result = builder.build_database(manifest, catalog, output, metadata, max_compressed_bytes=4 * 1024 * 1024)
        return manifest, catalog, output, metadata, result

    def test_build_keeps_complete_trip_and_encodes_service_days(self) -> None:
        _, _, output, _, result = self.build()
        self.assertEqual(10, result["rowCounts"]["trip"])
        self.assertEqual(20, result["rowCounts"]["stop_time"])
        raw = self.temp / "raw.sqlite"
        raw.write_bytes(zlib.decompress(output.read_bytes()))
        connection = sqlite3.connect(raw)
        self.assertEqual(builder.APPLICATION_ID, connection.execute("PRAGMA application_id").fetchone()[0])
        self.assertEqual(1, connection.execute("PRAGMA user_version").fetchone()[0])
        names = connection.execute("SELECT name,catalog_station_id FROM stop WHERE pack_id=1 ORDER BY external_id").fetchall()
        self.assertEqual([("Platform 1", "S1"), ("Nowhere", None)], names)
        start, days, bits = connection.execute("SELECT start_date,day_count,active_days FROM service WHERE pack_id=1").fetchone()
        self.assertEqual((20260105, 3, b"\x02"), (start, days, bits))
        times = connection.execute("SELECT stop_sequence,arrival_seconds,departure_seconds FROM stop_time WHERE trip_id=1 ORDER BY stop_sequence").fetchall()
        self.assertEqual([(1, 82800, 82800), (2, 90000, 90060)], times)
        self.assertEqual(
            "2026-01-04",
            connection.execute("SELECT value FROM metadata WHERE key='source_generated_at'").fetchone()[0],
        )
        self.assertEqual("2026-01-04", result["inputs"]["sourceGeneratedAt"])

    def test_verifier_accepts_output_and_rejects_tampering(self) -> None:
        _, _, output, metadata, _ = self.build()
        checked = verifier.verify(output, metadata, max_compressed_bytes=4 * 1024 * 1024, max_database_bytes=16 * 1024 * 1024)
        self.assertEqual(10, checked["rowCounts"]["pack"])
        output.write_bytes(output.read_bytes() + b"x")
        with self.assertRaises(verifier.VerificationError):
            verifier.verify(output, metadata, max_compressed_bytes=4 * 1024 * 1024, max_database_bytes=16 * 1024 * 1024)

    def test_cross_platform_rebuild_compares_all_rows_and_rejects_semantic_drift(self) -> None:
        manifest, catalog, output, metadata, _ = self.build()
        other = self.temp / "other.sqlite.zlib"
        other_metadata = self.temp / "other.metadata.json"
        builder.build_database(manifest, catalog, other, other_metadata, max_compressed_bytes=4 * 1024 * 1024)
        options = {"max_compressed_bytes": 4 * 1024 * 1024, "max_database_bytes": 16 * 1024 * 1024}
        result = rebuild_verifier.compare_rebuild(output, metadata, other, other_metadata, **options)
        self.assertEqual(len(verifier.REQUIRED_TABLE_COLUMNS), result["tablesCompared"])

        raw = self.temp / "other.sqlite"
        raw.write_bytes(zlib.decompress(other.read_bytes()))
        with sqlite3.connect(raw) as database:
            database.execute("PRAGMA page_size=8192")
            database.execute("VACUUM")
        self.assertNotEqual(hashlib.sha256(zlib.decompress(output.read_bytes())).hexdigest(), hashlib.sha256(raw.read_bytes()).hexdigest())
        compressed = zlib.compress(raw.read_bytes(), 9)
        other.write_bytes(compressed)
        payload = json.loads(other_metadata.read_text(encoding="utf-8"))
        payload["compression"].update(sha256=hashlib.sha256(compressed).hexdigest(), sizeBytes=len(compressed))
        payload["database"].update(sha256=hashlib.sha256(raw.read_bytes()).hexdigest(), sizeBytes=raw.stat().st_size)
        other_metadata.write_text(json.dumps(payload), encoding="utf-8")
        rebuild_verifier.compare_rebuild(output, metadata, other, other_metadata, **options)

        with sqlite3.connect(raw) as database:
            database.execute("UPDATE trip SET short_name='UNREVIEWED' WHERE id=1")
        compressed = zlib.compress(raw.read_bytes(), 9)
        other.write_bytes(compressed)
        payload = json.loads(other_metadata.read_text(encoding="utf-8"))
        payload["compression"]["sha256"] = hashlib.sha256(compressed).hexdigest()
        payload["compression"]["sizeBytes"] = len(compressed)
        payload["database"]["sha256"] = hashlib.sha256(raw.read_bytes()).hexdigest()
        payload["database"]["sizeBytes"] = raw.stat().st_size
        other_metadata.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaisesRegex(verifier.VerificationError, "table content differs: trip"):
            rebuild_verifier.compare_rebuild(output, metadata, other, other_metadata, **options)

    def test_reviewed_source_policy_is_fingerprinted_and_rejects_manifest_drift(self) -> None:
        manifest, catalog = write_inputs(self.temp)
        manifest_payload = json.loads(manifest.read_text(encoding="utf-8"))
        policy = self.temp / "source-policy.json"
        policy.write_text(json.dumps({
            "schemaVersion": 1,
            "sources": [
                {"sourceId": pack["sourceId"], "sourceUrl": pack["sourceUrl"], "accessMode": pack["accessMode"]}
                for pack in manifest_payload["packs"]
            ],
        }), encoding="utf-8")
        output = self.temp / "policy.sqlite.zlib"
        metadata = self.temp / "policy.metadata.json"
        builder.build_database(
            manifest,
            catalog,
            output,
            metadata,
            source_policy_path=policy,
            max_compressed_bytes=4 * 1024 * 1024,
        )
        verifier.verify(
            output,
            metadata,
            source_policy_path=policy,
            max_compressed_bytes=4 * 1024 * 1024,
            max_database_bytes=16 * 1024 * 1024,
        )
        manifest_payload["packs"][0]["sourceUrl"] = "https://attacker.invalid/feed.zip"
        manifest.write_text(json.dumps(manifest_payload), encoding="utf-8")
        with self.assertRaisesRegex(builder.BuildError, "differs from reviewed iOS policy"):
            builder.build_database(
                manifest,
                catalog,
                output,
                metadata,
                source_policy_path=policy,
                max_compressed_bytes=4 * 1024 * 1024,
            )

    def test_verifier_independently_rejects_non_rail_route(self) -> None:
        _, _, output, metadata_path, result = self.build()
        raw = self.temp / "tampered.sqlite"
        raw.write_bytes(zlib.decompress(output.read_bytes()))
        connection = sqlite3.connect(raw)
        connection.execute("UPDATE route SET route_type=3 WHERE id=1")
        connection.commit()
        connection.close()
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        with self.assertRaisesRegex(verifier.VerificationError, "non-rail route"):
            verifier.verify_database(raw, metadata, result["inputs"]["fingerprintSha256"])

    def rebuild_with_catalog(self, edit) -> Path:
        _, catalog, output, metadata, _ = self.build()
        payload = json.loads(catalog.read_text())
        edit(payload["stations"])
        catalog.write_text(json.dumps(payload), encoding="utf-8")
        output.unlink()
        metadata.unlink()
        builder.build_database(self.temp / "gtfs/manifest.json", catalog, output, metadata, max_compressed_bytes=4 * 1024 * 1024)
        raw = self.temp / "rebuilt.sqlite"
        raw.write_bytes(zlib.decompress(output.read_bytes()))
        return raw

    def bound_catalog_ids(self, raw: Path) -> list[str | None]:
        connection = sqlite3.connect(raw)
        self.addCleanup(connection.close)
        return [row[0] for row in connection.execute("SELECT catalog_station_id FROM stop WHERE pack_id=1 ORDER BY external_id")]

    def test_co_located_duplicate_catalog_entries_bind_to_the_preferred_entry(self) -> None:
        # Two codes for one physical station (RFI and Ferrovienord style): the entry with a
        # Trenitalia location id wins, so the station is planned instead of dropped.
        raw = self.rebuild_with_catalog(lambda stations: stations.append(
            {"id": "S2", "name": "Citta Alta", "aliases": [], "railwayCode": "S2", "trenitaliaLocationId": "830000002", "latitude": 45.001, "longitude": 9.0}
        ))
        self.assertEqual(["S2", None], self.bound_catalog_ids(raw))

    def test_homonyms_that_geography_cannot_separate_are_fail_closed(self) -> None:
        _, catalog, output, metadata, _ = self.build()
        payload = json.loads(catalog.read_text())
        payload["stations"].append({"id": "S2", "name": "Citta Alta", "aliases": [], "railwayCode": "S2", "trenitaliaLocationId": None, "latitude": 45.05, "longitude": 9.0})
        catalog.write_text(json.dumps(payload), encoding="utf-8")
        # Both are within reach of the stop and 5 km apart: no station is matched, nothing is published.
        output.unlink()
        metadata.unlink()
        manifest = self.temp / "gtfs/manifest.json"
        with self.assertRaises(builder.BuildError):
            builder.build_database(manifest, catalog, output, metadata, max_compressed_bytes=4 * 1024 * 1024)
        self.assertFalse(output.exists())
        self.assertFalse(metadata.exists())

    def test_bilingual_catalog_name_matches_its_leading_part_nearby(self) -> None:
        def bilingual(stations: list[dict]) -> None:
            stations[0]["name"] = "Città Alta Oberstadt"
            stations[0]["aliases"] = []
        raw = self.rebuild_with_catalog(bilingual)
        self.assertEqual(["S1", None], self.bound_catalog_ids(raw))

    def test_bilingual_prefix_does_not_capture_a_stop_named_after_another_station(self) -> None:
        # The stop has its own catalogue entry (without timetable codes): the longer name
        # nearby is a different station, so nothing is matched.
        _, catalog, output, metadata, _ = self.build()
        payload = json.loads(catalog.read_text())
        payload["stations"][0].update({"name": "Città Alta Oberstadt", "aliases": []})
        payload["stations"].append({"id": "NC1", "name": "Citta Alta", "aliases": [], "railwayCode": None, "trenitaliaLocationId": None, "latitude": 45.002, "longitude": 9.0})
        catalog.write_text(json.dumps(payload), encoding="utf-8")
        output.unlink()
        metadata.unlink()
        with self.assertRaises(builder.BuildError):
            builder.build_database(self.temp / "gtfs/manifest.json", catalog, output, metadata, max_compressed_bytes=4 * 1024 * 1024)

    def test_bilingual_prefix_match_requires_proximity(self) -> None:
        _, catalog, output, metadata, _ = self.build()
        payload = json.loads(catalog.read_text())
        payload["stations"][0].update({"name": "Città Alta Oberstadt", "aliases": [], "latitude": 45.03})
        catalog.write_text(json.dumps(payload), encoding="utf-8")
        output.unlink()
        metadata.unlink()
        with self.assertRaises(builder.BuildError):
            builder.build_database(self.temp / "gtfs/manifest.json", catalog, output, metadata, max_compressed_bytes=4 * 1024 * 1024)

    def test_rejects_unsafe_zip_member(self) -> None:
        root = self.temp / "unsafe"
        root.mkdir()
        manifest, catalog = write_inputs(root, unsafe=True)
        with self.assertRaisesRegex(builder.BuildError, "unsafe or duplicate"):
            builder.build_database(manifest, catalog, root / "out.zlib", root / "out.json", max_compressed_bytes=4 * 1024 * 1024)

    def test_rejects_non_rail_route_type(self) -> None:
        root = self.temp / "bus"
        root.mkdir()
        manifest, catalog = write_inputs(root, route_type="3")
        with self.assertRaisesRegex(builder.BuildError, "not an authorised railway route"):
            builder.build_database(manifest, catalog, root / "out.zlib", root / "out.json", max_compressed_bytes=4 * 1024 * 1024)

    def test_same_name_far_from_catalog_station_does_not_create_false_identity(self) -> None:
        root = self.temp / "far"
        root.mkdir()
        manifest, catalog = write_inputs(root)
        payload = json.loads(catalog.read_text(encoding="utf-8"))
        payload["stations"][0]["latitude"] = -33.9
        payload["stations"][0]["longitude"] = 151.2
        catalog.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaises(builder.BuildError):
            builder.build_database(
                manifest,
                catalog,
                root / "out.zlib",
                root / "out.json",
                max_compressed_bytes=4 * 1024 * 1024,
            )

    def test_remote_child_coordinates_cannot_inherit_parent_identity(self) -> None:
        root = self.temp / "remote-child"
        root.mkdir()
        manifest, catalog = write_inputs(root, child_latitude="-33.9", child_longitude="151.2")
        with self.assertRaisesRegex(builder.BuildError, "filter produced an empty schedule"):
            builder.build_database(
                manifest,
                catalog,
                root / "out.zlib",
                root / "out.json",
                max_compressed_bytes=4 * 1024 * 1024,
            )

    def test_invalid_iana_timezone_is_rejected_by_builder_and_verifier(self) -> None:
        root = self.temp / "invalid-timezone"
        root.mkdir()
        manifest, catalog = write_inputs(root, timezone="Etc/NoSuchZone")
        with self.assertRaisesRegex(builder.BuildError, "invalid IANA timezone"):
            builder.build_database(
                manifest,
                catalog,
                root / "out.zlib",
                root / "out.json",
                max_compressed_bytes=4 * 1024 * 1024,
            )

        _, _, output, metadata_path, result = self.build()
        raw = self.temp / "invalid-timezone.sqlite"
        raw.write_bytes(zlib.decompress(output.read_bytes()))
        connection = sqlite3.connect(raw)
        connection.execute("UPDATE agency SET timezone='Etc/NoSuchZone' WHERE id=1")
        connection.commit()
        connection.close()
        with self.assertRaisesRegex(verifier.VerificationError, "invalid agency IANA timezone"):
            verifier.verify_database(raw, json.loads(metadata_path.read_text()), result["inputs"]["fingerprintSha256"])

    def test_regressive_stop_times_are_rejected_by_builder_and_verifier(self) -> None:
        root = self.temp / "regressive-time"
        root.mkdir()
        manifest, catalog = write_inputs(root, second_arrival="22:00:00", second_departure="22:00:00")
        with self.assertRaisesRegex(builder.BuildError, "arrival regresses"):
            builder.build_database(
                manifest,
                catalog,
                root / "out.zlib",
                root / "out.json",
                max_compressed_bytes=4 * 1024 * 1024,
            )

        _, _, output, metadata_path, result = self.build()
        raw = self.temp / "regressive-time.sqlite"
        raw.write_bytes(zlib.decompress(output.read_bytes()))
        connection = sqlite3.connect(raw)
        connection.execute("UPDATE stop_time SET arrival_seconds=70000,departure_seconds=70000 WHERE trip_id=1 AND stop_sequence=2")
        connection.commit()
        connection.close()
        with self.assertRaisesRegex(verifier.VerificationError, "stop_times regress"):
            verifier.verify_database(raw, json.loads(metadata_path.read_text()), result["inputs"]["fingerprintSha256"])

    def test_retained_stop_time_budget_fails_before_materializing_rows(self) -> None:
        root = self.temp / "retained-budget"
        root.mkdir()
        manifest, catalog = write_inputs(root)
        with mock.patch.object(builder, "MAX_RETAINED_STOP_TIMES", 1):
            with self.assertRaisesRegex(builder.BuildError, "retained stop_times exceed 1"):
                builder.build_database(
                    manifest,
                    catalog,
                    root / "out.zlib",
                    root / "out.json",
                    max_compressed_bytes=4 * 1024 * 1024,
                )

    def test_calendar_bitset_gtfs_exception_semantics(self) -> None:
        row = {"start_date": "20260105", "end_date": "20260107", "monday": "1", "tuesday": "0", "wednesday": "1", "thursday": "0", "friday": "0", "saturday": "0", "sunday": "0"}
        start, count, bits = builder.compute_service_bits("S", row, {date(2026, 1, 5): 2, date(2026, 1, 6): 1}, "test")
        self.assertEqual((20260105, 3, b"\x06"), (start, count, bits))

if __name__ == "__main__":
    unittest.main()
