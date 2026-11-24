from __future__ import annotations

import csv
import hashlib
import importlib.util
import io
import json
import re
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
    bikes: str | None = None,
) -> None:
    trip_headers = ["route_id", "service_id", "trip_id", "trip_headsign", "direction_id"]
    trip_rows = [["R", "WK", "KEEP", "Terminus", "0"], ["R", "WK", "DROP", "Elsewhere", "1"]]
    if bikes is not None:
        trip_headers.append("bikes_allowed")
        trip_rows = [row + [bikes] for row in trip_rows]
    members = {
        "agency.txt": csv_bytes(["agency_id", "agency_name", "agency_url", "agency_timezone"], [["A", "Rail", "https://example.test", timezone]]),
        "routes.txt": csv_bytes(["route_id", "agency_id", "route_short_name", "route_long_name", "route_type"], [["R", "A", "R1", "Test", route_type]]),
        "trips.txt": csv_bytes(trip_headers, trip_rows),
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
    for index in range(builder.INDEXED_PACK_COUNT):
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

FIXTURE_PACKS = frozenset(f"pack-{index}" for index in range(builder.INDEXED_PACK_COUNT))


class IosGtfsScheduleTest(unittest.TestCase):
    """The fixture packs match catalogue stations with codes only (as a foreign operator's pack): the catalogue's
    uncoded "Nowhere" (N1) is the station a stop must not be bound to here; see UncodedStationTest."""

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="ios-gtfs-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        patcher = mock.patch.object(builder, "FOREIGN_PACKS", FIXTURE_PACKS)
        patcher.start()
        self.addCleanup(patcher.stop)

    def build(self):
        manifest, catalog = write_inputs(self.temp)
        output = self.temp / "schedule.sqlite.zlib"
        metadata = self.temp / "schedule.metadata.json"
        result = builder.build_database(manifest, catalog, output, metadata, max_compressed_bytes=4 * 1024 * 1024)
        return manifest, catalog, output, metadata, result

    def test_build_keeps_complete_trip_and_encodes_service_days(self) -> None:
        _, _, output, _, result = self.build()
        self.assertEqual(builder.INDEXED_PACK_COUNT, result["rowCounts"]["trip"])
        self.assertEqual(2 * builder.INDEXED_PACK_COUNT, result["rowCounts"]["stop_time"])
        raw = self.temp / "raw.sqlite"
        raw.write_bytes(zlib.decompress(output.read_bytes()))
        connection = sqlite3.connect(raw)
        self.assertEqual(builder.APPLICATION_ID, connection.execute("PRAGMA application_id").fetchone()[0])
        self.assertEqual(2, connection.execute("PRAGMA user_version").fetchone()[0])
        # A feed without the column leaves bikes unknown.
        self.assertEqual([(None,)], connection.execute("SELECT DISTINCT bikes_allowed FROM trip").fetchall())
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

    def test_bikes_allowed_keeps_published_values_and_treats_italo_as_unknown(self) -> None:
        self.assertEqual(1, builder.bikes_allowed("alto-adige-sta-gtfs", "1"))
        self.assertEqual(2, builder.bikes_allowed("arst-gtfs", " 2 "))
        for raw in ("0", "", None, "3", "yes"):
            with self.subTest(raw=raw):
                self.assertIsNone(builder.bikes_allowed("arst-gtfs", raw))
        # Italo admits only folding bikes in a bag: its pack-wide 1 is not shown, as on Android.
        self.assertIsNone(builder.bikes_allowed("italo-gtfs", "1"))

        manifest, catalog = write_inputs(self.temp, bikes="2")
        output = self.temp / "bikes.sqlite.zlib"
        metadata = self.temp / "bikes.metadata.json"
        builder.build_database(manifest, catalog, output, metadata, max_compressed_bytes=4 * 1024 * 1024)
        raw = self.temp / "bikes.sqlite"
        raw.write_bytes(zlib.decompress(output.read_bytes()))
        connection = sqlite3.connect(raw)
        self.addCleanup(connection.close)
        self.assertEqual([(2,)], connection.execute("SELECT DISTINCT bikes_allowed FROM trip").fetchall())
        verifier.verify(output, metadata, max_compressed_bytes=4 * 1024 * 1024, max_database_bytes=16 * 1024 * 1024)
        with self.assertRaises(sqlite3.IntegrityError):
            connection.execute("UPDATE trip SET bikes_allowed=0 WHERE id=1")

    def test_verifier_accepts_output_and_rejects_tampering(self) -> None:
        _, _, output, metadata, _ = self.build()
        checked = verifier.verify(output, metadata, max_compressed_bytes=4 * 1024 * 1024, max_database_bytes=16 * 1024 * 1024)
        self.assertEqual(builder.INDEXED_PACK_COUNT, checked["rowCounts"]["pack"])
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
                {"sourceId": pack["sourceId"], "sourceUrl": pack["sourceUrl"], "accessMode": pack["accessMode"], "licence": "CC BY 4.0" if index else None,
                 "landingPage": "https://example.test/" + pack["sourceId"]}
                for index, pack in enumerate(manifest_payload["packs"])
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

    def test_source_policy_must_record_a_licence_entry_for_every_index_pack(self) -> None:
        manifest, catalog = write_inputs(self.temp)
        manifest_payload = json.loads(manifest.read_text(encoding="utf-8"))
        sources = [
            {"sourceId": pack["sourceId"], "sourceUrl": pack["sourceUrl"], "accessMode": pack["accessMode"], "licence": None,
             "landingPage": "https://example.test/" + pack["sourceId"]}
            for pack in manifest_payload["packs"]
        ]
        policy = self.temp / "source-policy.json"
        output = self.temp / "policy.sqlite.zlib"
        metadata = self.temp / "policy.metadata.json"
        options = {"source_policy_path": policy, "max_compressed_bytes": 4 * 1024 * 1024}

        without_licence = [{key: value for key, value in source.items() if key != "licence"} for source in sources]
        policy.write_text(json.dumps({"schemaVersion": 1, "sources": without_licence}), encoding="utf-8")
        with self.assertRaisesRegex(builder.BuildError, "policy entry 0 is invalid"):
            builder.build_database(manifest, catalog, output, metadata, **options)
        blank_licence = [dict(source, licence=" ") for source in sources]
        policy.write_text(json.dumps({"schemaVersion": 1, "sources": blank_licence}), encoding="utf-8")
        with self.assertRaisesRegex(builder.BuildError, "invalid licence"):
            builder.build_database(manifest, catalog, output, metadata, **options)
        # The source list links each feed's public page: never a cleartext or missing one.
        cleartext_page = [dict(source, landingPage="http://example.test/page") for source in sources]
        policy.write_text(json.dumps({"schemaVersion": 1, "sources": cleartext_page}), encoding="utf-8")
        with self.assertRaisesRegex(builder.BuildError, "invalid landing page"):
            builder.build_database(manifest, catalog, output, metadata, **options)
        without_page = [{key: value for key, value in source.items() if key != "landingPage"} for source in sources]
        policy.write_text(json.dumps({"schemaVersion": 1, "sources": without_page}), encoding="utf-8")
        with self.assertRaisesRegex(builder.BuildError, "policy entry 0 is invalid"):
            builder.build_database(manifest, catalog, output, metadata, **options)

        policy.write_text(json.dumps({"schemaVersion": 1, "sources": sources}), encoding="utf-8")
        builder.build_database(manifest, catalog, output, metadata, **options)
        verify_options = {"source_policy_path": policy, "max_compressed_bytes": 4 * 1024 * 1024, "max_database_bytes": 16 * 1024 * 1024}
        verifier.verify(output, metadata, **verify_options)
        # The verifier reads the policy independently of the builder: a source renamed away
        # from the index packs or a blank licence is rejected on its own.
        renamed = [dict(source, sourceId=source["sourceId"] + "-renamed") for source in sources]
        with self.assertRaisesRegex(verifier.VerificationError, "differ from the index packs"):
            verifier.verify_source_policy(self.write_policy(renamed), {source["sourceId"] for source in sources})
        with self.assertRaisesRegex(verifier.VerificationError, "invalid licence"):
            verifier.verify_source_policy(self.write_policy([dict(source, licence="") for source in sources]), {source["sourceId"] for source in sources})
        with self.assertRaisesRegex(verifier.VerificationError, "invalid landing page"):
            verifier.verify_source_policy(self.write_policy([dict(source, landingPage="http://example.test/") for source in sources]), {source["sourceId"] for source in sources})
        verifier.verify_source_policy(self.write_policy(sources), {source["sourceId"] for source in sources})

    def test_android_only_packs_are_reviewed_in_the_policy_and_left_out_of_the_index(self) -> None:
        manifest, catalog = write_inputs(self.temp)
        payload = json.loads(manifest.read_text(encoding="utf-8"))
        sources = [
            {"sourceId": pack["sourceId"], "sourceUrl": pack["sourceUrl"], "accessMode": pack["accessMode"], "licence": None,
             "landingPage": "https://example.test/" + pack["sourceId"]}
            for pack in payload["packs"]
        ]
        # One more, Android-only pack whose archive the iOS index never reads.
        payload["packs"].append({"sourceId": "national-gtfs", "asset": "gtfs/national.zip", "sourceUrl": "https://example.test/national.zip",
                                 "accessMode": "OPEN_DIRECT", "attribution": "Test data", "lastServiceDate": "2026-01-07", "sha256": "0" * 64})
        manifest.write_text(json.dumps(payload), encoding="utf-8")
        policy = self.temp / "source-policy.json"
        output = self.temp / "policy.sqlite.zlib"
        metadata = self.temp / "policy.metadata.json"
        options = {"source_policy_path": policy, "max_compressed_bytes": 4 * 1024 * 1024}
        policy.write_text(json.dumps({"schemaVersion": 1, "sources": sources}), encoding="utf-8")
        with self.assertRaisesRegex(builder.BuildError, f"differ from reviewed iOS policy|exactly {builder.INDEXED_PACK_COUNT} packs"):
            builder.build_database(manifest, catalog, output, metadata, **options)
        excluded = {"sourceId": "national-gtfs", "sourceUrl": "https://example.test/national.zip", "accessMode": "OPEN_DIRECT", "reason": "bus legs"}
        policy.write_text(json.dumps({"schemaVersion": 1, "sources": sources, "androidOnlySources": [excluded]}), encoding="utf-8")
        result = builder.build_database(manifest, catalog, output, metadata, **options)
        self.assertEqual(builder.INDEXED_PACK_COUNT, result["rowCounts"]["pack"])
        verifier.verify(output, metadata, source_policy_path=policy, max_compressed_bytes=4 * 1024 * 1024, max_database_bytes=16 * 1024 * 1024)
        # A drifted URL of the excluded pack still fails closed.
        policy.write_text(json.dumps({"schemaVersion": 1, "sources": sources, "androidOnlySources": [dict(excluded, sourceUrl="https://attacker.invalid/x.zip")]}), encoding="utf-8")
        with self.assertRaisesRegex(builder.BuildError, "differs from reviewed iOS policy"):
            builder.build_database(manifest, catalog, output, metadata, **options)

    def write_policy(self, sources: list[dict]) -> Path:
        path = self.temp / f"policy-{len(sources)}-{hash(json.dumps(sources, sort_keys=True)) & 0xFFFF}.json"
        path.write_text(json.dumps({"schemaVersion": 1, "sources": sources}), encoding="utf-8")
        return path

    def test_verifier_independently_rejects_non_rail_route(self) -> None:
        _, _, output, metadata_path, result = self.build()
        raw = self.temp / "tampered.sqlite"
        raw.write_bytes(zlib.decompress(output.read_bytes()))
        connection = sqlite3.connect(raw)
        connection.execute("UPDATE route SET route_type=4 WHERE id=1")
        connection.commit()
        connection.close()
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        with self.assertRaisesRegex(verifier.VerificationError, "route neither rail nor bus"):
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

    def test_keeps_bus_routes_and_rejects_any_other_route_type(self) -> None:
        # A bus route is a journey leg, as on Android (journeyLegMode): the operator's coach or a replacement bus.
        root = self.temp / "bus"
        root.mkdir()
        manifest, catalog = write_inputs(root, route_type="3")
        result = builder.build_database(manifest, catalog, root / "out.zlib", root / "out.json", max_compressed_bytes=4 * 1024 * 1024)
        self.assertEqual(builder.INDEXED_PACK_COUNT, result["rowCounts"]["route"])
        verifier.verify(root / "out.zlib", root / "out.json", max_compressed_bytes=4 * 1024 * 1024, max_database_bytes=16 * 1024 * 1024)
        ferry = self.temp / "ferry"
        ferry.mkdir()
        manifest, catalog = write_inputs(ferry, route_type="4")
        with self.assertRaisesRegex(builder.BuildError, "neither a railway nor a bus route"):
            builder.build_database(manifest, catalog, ferry / "out.zlib", ferry / "out.json", max_compressed_bytes=4 * 1024 * 1024)

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

    def test_a_reviewed_pack_leaves_out_its_trips_numbered_out_of_time_order(self) -> None:
        # Only pack-0 numbers its kept trip out of time order (Trenitalia France's train 9281 on four days, Nov 2026).
        root = self.temp / "out-of-order"
        root.mkdir()
        manifest, catalog = write_inputs(root)
        archive = root / "gtfs/pack-0.zip"
        write_feed(archive, second_arrival="22:00:00", second_departure="22:00:00")
        payload = json.loads(manifest.read_text(encoding="utf-8"))
        payload["packs"][0]["sha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
        manifest.write_text(json.dumps(payload), encoding="utf-8")

        def build(name: str) -> dict:
            return builder.build_database(manifest, catalog, root / f"{name}.zlib", root / f"{name}.json",
                                          max_compressed_bytes=4 * 1024 * 1024)

        # Any other pack still fails the build, and so does a reviewed pack above the share.
        with self.assertRaisesRegex(builder.BuildError, "arrival regresses"):
            build("unreviewed")
        with mock.patch.object(builder, "OUT_OF_ORDER_TRIPS_LEFT_OUT", frozenset({"pack-0"})):
            with self.assertRaisesRegex(builder.BuildError, "pack-0: 1 of 1 kept trips are out of time order"):
                build("above-share")
            with mock.patch.object(builder, "MAX_OUT_OF_ORDER_TRIP_SHARE", 1.0):
                result = build("reviewed")
        self.assertEqual({"pack-0": 1}, result["filter"]["outOfOrderTripsLeftOut"])
        self.assertEqual(0, result["packRowCounts"]["pack-0"].get("trip", 0))
        self.assertNotIn(builder.OUT_OF_ORDER_COUNT_KEY, result["packRowCounts"]["pack-0"])
        self.assertEqual(builder.INDEXED_PACK_COUNT - 1, result["rowCounts"]["trip"])
        verifier.verify(root / "reviewed.zlib", root / "reviewed.json",
                        max_compressed_bytes=4 * 1024 * 1024, max_database_bytes=16 * 1024 * 1024)
        # An index without such trips keeps the metadata it had.
        self.assertNotIn("outOfOrderTripsLeftOut", self.build()[4]["filter"])

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

class ReaderLimitsTest(unittest.TestCase):
    """The verifier refuses an index the iPhone reader would reject (limitExceeded) or misdate."""

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="ios-gtfs-limits-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)

    def build(self, **feed: object) -> tuple[Path, Path]:
        root = Path(tempfile.mkdtemp(dir=self.temp))
        manifest, catalog = write_inputs(root, **feed)
        output, metadata = root / "out.zlib", root / "out.json"
        builder.build_database(manifest, catalog, output, metadata, max_compressed_bytes=4 * 1024 * 1024)
        return output, metadata

    def verify(self, output: Path, metadata: Path) -> None:
        verifier.verify(output, metadata, max_compressed_bytes=4 * 1024 * 1024, max_database_bytes=16 * 1024 * 1024)

    def test_the_redundant_trip_sequence_index_is_gone(self) -> None:
        # stop_time is WITHOUT ROWID with PRIMARY KEY(trip_id, stop_sequence): that key is the index.
        self.assertNotIn("idx_stop_time_trip_sequence", builder.REQUIRED_INDICES)
        self.assertEqual(set(builder.REQUIRED_INDICES), verifier.REQUIRED_INDICES)

    def test_limits_of_the_reader(self) -> None:
        output, metadata = self.build()
        self.verify(output, metadata)
        for name, message in (("MAX_CANDIDATE_ROWS", "boarding rows"), ("MAX_RESOLVED_STOPS", "stops, above"),
                              ("MAX_TIME_ZONES", "agency time zones")):
            with self.subTest(limit=name), mock.patch.object(verifier, name, 0), \
                    self.assertRaisesRegex(verifier.VerificationError, message):
                self.verify(output, metadata)

    def test_every_agency_zone_keeps_romes_offset(self) -> None:
        self.verify(*self.build(timezone="Europe/Zurich"))
        output, metadata = self.build(timezone="Europe/London")
        with self.assertRaisesRegex(verifier.VerificationError, "Europe/London differs from Europe/Rome"):
            self.verify(output, metadata)


class UncodedStationTest(unittest.TestCase):
    """Audit2 tools-data-4: in an Italian pack a stop may be the catalogue station without codes it is named after
    (EAV's Cumana), as on Android; a foreign pack stays with coded stations; a coded match always wins."""

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="ios-gtfs-uncoded-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)

    def bound(self, edit=lambda stations: None, foreign: frozenset[str] = frozenset()) -> list[list[str | None]]:
        root = Path(tempfile.mkdtemp(dir=self.temp))
        manifest, catalog = write_inputs(root)
        payload = json.loads(catalog.read_text(encoding="utf-8"))
        edit(payload["stations"])
        catalog.write_text(json.dumps(payload), encoding="utf-8")
        output, metadata = root / "out.zlib", root / "out.json"
        with mock.patch.object(builder, "FOREIGN_PACKS", foreign):
            builder.build_database(manifest, catalog, output, metadata, max_compressed_bytes=4 * 1024 * 1024)
        verifier.verify(output, metadata, max_compressed_bytes=4 * 1024 * 1024, max_database_bytes=16 * 1024 * 1024)
        raw = root / "out.sqlite"
        raw.write_bytes(zlib.decompress(output.read_bytes()))
        connection = sqlite3.connect(raw)
        self.addCleanup(connection.close)
        return [[row[0] for row in connection.execute(
            "SELECT catalog_station_id FROM stop WHERE pack_id=? ORDER BY external_id", (pack,))] for pack in (1, 2)]

    def test_an_italian_pack_binds_the_uncoded_station_and_a_foreign_pack_does_not(self) -> None:
        # The kept trip calls at PL (platform of Citta Alta, S1) and X (Nowhere); N1 "Nowhere" has no codes.
        self.assertEqual([["S1", "N1"], ["S1", "N1"]], self.bound())
        self.assertEqual([["S1", None], ["S1", "N1"]], self.bound(foreign=frozenset({"pack-0"})))

    def test_builder_and_verifier_name_the_same_foreign_packs(self) -> None:
        self.assertEqual(sorted(builder.FOREIGN_PACKS), list(verifier.FOREIGN_PACKS))

    def test_a_coded_station_keeps_its_stops_and_uncoded_namesakes_stay_apart(self) -> None:
        # A coded "Nowhere" 5 km away: the stop stays bound to it, as before.
        coded = {"id": "S9", "name": "Nowhere", "aliases": [], "railwayCode": "S9", "trenitaliaLocationId": None,
                 "latitude": 46.045, "longitude": 10.0}
        self.assertEqual("S9", self.bound(lambda stations: stations.append(coded))[0][1])
        # A second uncoded "Nowhere" next to N1: the iPhone search offers both, so neither takes the stop.
        twin = {"id": "N2", "name": "Nowhere", "aliases": [], "railwayCode": None, "trenitaliaLocationId": None,
                "latitude": 46.001, "longitude": 10.0}
        self.assertIsNone(self.bound(lambda stations: stations.append(twin))[0][1])
        # A coded entry at the same place shadows the uncoded one (as the search hides it).
        shadow = dict(coded, latitude=46.001)
        self.assertEqual("S9", self.bound(lambda stations: stations.append(shadow))[0][1])


class StopNameVariantTest(unittest.TestCase):
    """The stop-name forms of Android's GtfsStopStationResolver (design §4.2), each matched against a small catalogue."""

    STATIONS = [
        {"id": "S01003", "name": "Domodossola", "railwayCode": "S01003", "trenitaliaLocationId": "830001003", "latitude": 46.115429, "longitude": 8.296684},
        {"id": "S02001", "name": "Brennero Brenner", "aliases": ["BRENNERO"], "railwayCode": "S02001", "trenitaliaLocationId": "830002001", "latitude": 47.00256, "longitude": 11.505346},
        {"id": "NC000544", "name": "Innsbruck Hbf.", "latitude": 47.26385, "longitude": 11.4009},
        {"id": "NC000927", "name": "Lienz", "latitude": 46.8283, "longitude": 12.7713},
        {"id": "NC000514", "name": "Wien Hauptbahnhof", "trenitaliaLocationId": "810001003", "latitude": 48.185, "longitude": 16.3765},
        {"id": "S07000", "name": "Linz Centrale", "railwayCode": "S07000", "latitude": 45.5, "longitude": 9.5},
        {"id": "S07001", "name": "Borgo Bahnhof", "railwayCode": "S07001", "latitude": 45.0, "longitude": 10.0},
        {"id": "S07002", "name": "Borgo", "railwayCode": "S07002", "latitude": 45.0005, "longitude": 10.0},
    ]

    @classmethod
    def setUpClass(cls) -> None:
        with tempfile.TemporaryDirectory(prefix="ios-variants-") as folder:
            path = Path(folder) / "catalog.json"
            stations = [{"aliases": [], "railwayCode": None, "trenitaliaLocationId": None, **station} for station in cls.STATIONS]
            path.write_text(json.dumps({"schemaVersion": 1, "stations": stations}), encoding="utf-8")
            cls.catalog, _ = builder.load_catalog(path)

    def matched(self, name: str, latitude: float, longitude: float, *, uncoded: bool = False) -> str | None:
        row = {"stop_name": name, "stop_lat": str(latitude), "stop_lon": str(longitude)}
        identity = self.catalog.match(row, "test", uncoded=uncoded)
        return identity.station_id if identity else None

    def test_country_suffix(self) -> None:
        self.assertEqual(["Domodossola (I)", "Domodossola"], builder.name_variants("Domodossola (I)"))
        self.assertEqual("S01003", self.matched("Domodossola (I)", 46.11529, 8.29624))
        self.assertEqual(["Chiasso"], builder.name_variants("Chiasso"))

    def test_station_of(self) -> None:
        self.assertEqual(
            ["Brennero, Stazione di Brennero", "Brennero", "Stazione di Brennero"],
            builder.name_variants("Brennero, Stazione di Brennero"),
        )
        self.assertEqual("S02001", self.matched("Brennero, Stazione di Brennero", 47.00268, 11.50557))

    def test_trailing_station_word(self) -> None:
        for name in ("Lienz Bahnhof", "Lienz Bhf", "Lienz stazione"):
            with self.subTest(name=name):
                self.assertEqual(name.rsplit(" ", 1)[0], builder.name_variants(name)[1])
                self.assertEqual("NC000927", self.matched(name, 46.8284, 12.7713, uncoded=True))
        # A station without codes is only for packs that may match one.
        self.assertIsNone(self.matched("Lienz Bahnhof", 46.8284, 12.7713))
        self.assertEqual(["Brig Bahnhofplatz"], builder.name_variants("Brig Bahnhofplatz"))

    def test_hbf_is_the_hauptbahnhof(self) -> None:
        self.assertEqual(["Innsbruck Hbf.", "Innsbruck Hauptbahnhof"], builder.name_variants("Innsbruck Hbf."))
        self.assertEqual("NC000544", self.matched("Innsbruck Hauptbahnhof", 47.2632, 11.4008, uncoded=True))
        self.assertEqual("NC000514", self.matched("Wien Hbf", 48.1851, 16.3766))

    def test_trailing_platform_number(self) -> None:
        self.assertEqual(["Linz Centrale 1", "Linz Centrale"], builder.name_variants("Linz Centrale 1"))
        self.assertEqual(["Linz Centrale 12b", "Linz Centrale"], builder.name_variants("Linz Centrale 12b"))
        self.assertEqual("S07000", self.matched("Linz Centrale 1", 45.5, 9.5))
        self.assertEqual(["Linz Centrale 123"], builder.name_variants("Linz Centrale 123"))

    def test_a_stop_matched_by_its_published_name_keeps_that_station(self) -> None:
        self.assertEqual("S07001", self.matched("Borgo Bahnhof", 45.0, 10.0))
        self.assertEqual("S07002", self.matched("Borgo", 45.0, 10.0))

    def test_variants_are_still_verified_by_distance(self) -> None:
        self.assertIsNone(self.matched("Domodossola (I)", 45.5, 8.29624))


def write_border_feed(path: Path) -> None:
    """Citta Alta (an Italian station, coded) and Vicino (no codes, the station of the fixture corridor) are in the
    border zone; Nowhere is not."""
    members = {
        "agency.txt": csv_bytes(["agency_id", "agency_name", "agency_url", "agency_timezone"], [["A", "Rail", "https://example.test", "Europe/Rome"]]),
        "routes.txt": csv_bytes(["route_id", "agency_id", "route_short_name", "route_long_name", "route_type"], [["R", "A", "EC", "Test", "2"]]),
        "trips.txt": csv_bytes(["route_id", "service_id", "trip_id"], [["R", "WK", "KEEP"], ["R", "WK", "NEAR"], ["R", "WK", "FAR"]]),
        "stops.txt": csv_bytes(["stop_id", "stop_name", "stop_lat", "stop_lon", "location_type", "parent_station"], [
            ["P", "Citta Alta", "45", "9", "1", ""], ["PL", "Citta Alta 1", "45", "9", "0", "P"],
            ["V", "Vicino Bahnhof", "45.1", "9", "0", ""], ["X", "Nowhere", "46", "10", "0", ""], ["Y", "Elsewhere", "47", "11", "0", ""],
        ]),
        "stop_times.txt": csv_bytes(["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"], [
            ["KEEP", "08:00:00", "08:00:00", "X", "1"], ["KEEP", "09:00:00", "09:00:00", "PL", "2"],
            ["NEAR", "10:00:00", "10:00:00", "V", "1"], ["NEAR", "11:00:00", "11:00:00", "X", "2"],
            ["FAR", "12:00:00", "12:00:00", "X", "1"], ["FAR", "13:00:00", "13:00:00", "Y", "2"],
        ]),
        "calendar.txt": csv_bytes(["service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "start_date", "end_date"],
                                  [["WK", "1", "1", "1", "1", "1", "0", "0", "20260105", "20260109"]]),
    }
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in members.items():
            archive.writestr(name, content)


class BorderSubsetIndexTest(unittest.TestCase):
    """Design §4.2-§4.3: a border subset names its zone stations only (with or without codes); the per-country index
    of its full pack names every station; a foreign pack bundled whole keeps the coded-only rule."""

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="ios-gtfs-border-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.manifest, self.catalog = write_inputs(self.temp)
        catalog = json.loads(self.catalog.read_text(encoding="utf-8"))
        catalog["stations"][0]["trenitaliaLocationId"] = "830000001"  # the anchor of the zone
        catalog["stations"].append({"id": "N2", "name": "Vicino", "aliases": [], "railwayCode": None, "trenitaliaLocationId": None, "latitude": 45.1, "longitude": 9.0})
        self.catalog.write_text(json.dumps(catalog), encoding="utf-8")
        subset = self.temp / "gtfs/pack-0.zip"
        write_border_feed(subset)
        self.full_dir = self.temp / "full"
        self.full_dir.mkdir()
        shutil.copyfile(subset, self.full_dir / "pack-0.zip")
        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        digest = hashlib.sha256(subset.read_bytes()).hexdigest()
        manifest["packs"][0].update(sha256=digest, filter={"borderZone": {"rule": "frontalieri-corridors-v1"}}, fullPack={
            "path": "data/gtfs-foreign/android/pack-0.zip", "sha256": digest, "lastServiceDate": "2026-01-09",
        })
        self.manifest.write_text(json.dumps(manifest), encoding="utf-8")
        corridor = builder.foreign_rules.Corridor("Citta Alta – Vicino", "fixture", builder.foreign_rules.station("S1", "Citta Alta"),
                                                  (builder.foreign_rules.stop("Vicino Bahnhof", 45.1, 9.0),))
        for patcher in (mock.patch.object(builder, "FOREIGN_PACKS", frozenset({"pack-0", "pack-1"})),
                        mock.patch.object(verifier, "FOREIGN_PACKS", ("pack-0", "pack-1")),
                        mock.patch.dict(builder.foreign_rules.CORRIDORS, {"pack-0": (corridor,)}, clear=True)):
            patcher.start()
            self.addCleanup(patcher.stop)

    def stops(self, output: Path, pack: str) -> dict[str, str | None]:
        raw = output.with_suffix(".sqlite")
        raw.write_bytes(zlib.decompress(output.read_bytes()))
        connection = sqlite3.connect(raw)
        self.addCleanup(connection.close)
        return dict(connection.execute(
            "SELECT s.external_id, s.catalog_station_id FROM stop s JOIN pack p ON p.id=s.pack_id WHERE p.source_id=?", (pack,)))

    def build_base(self) -> tuple[Path, Path, dict]:
        output, metadata = self.temp / "base.zlib", self.temp / "base.json"
        result = builder.build_database(self.manifest, self.catalog, output, metadata, max_compressed_bytes=4 * 1024 * 1024,
                                        full_pack_dir=self.full_dir)
        return output, metadata, result

    def test_zone_stations_match_with_or_without_codes_and_out_of_zone_stops_stay_unnamed(self) -> None:
        output, metadata, result = self.build_base()
        self.assertEqual({"PL": "S1", "V": "N2", "X": None}, self.stops(output, "pack-0"))
        # A foreign pack bundled whole (pack-1) matches stations with codes only, an Italian pack (pack-2) any.
        self.assertEqual({"PL": "S1", "X": None}, self.stops(output, "pack-1"))
        self.assertEqual({"PL": "S1", "X": "N1"}, self.stops(output, "pack-2"))
        self.assertEqual(["pack-0"], result["filter"]["borderSubsets"])
        self.assertEqual(["pack-1"], result["filter"]["codedStationsOnly"])
        self.assertEqual(builder.INDEXED_PACK_COUNT + 3, len(result["inputs"]["files"]))
        self.assertEqual(2, result["packRowCounts"]["pack-0"]["trip"])  # FAR touches no zone station
        verifier.verify(output, metadata, max_compressed_bytes=4 * 1024 * 1024, max_database_bytes=16 * 1024 * 1024)

    def test_verifier_rejects_a_catalogue_id_outside_the_zone(self) -> None:
        output, metadata, _ = self.build_base()
        raw = self.temp / "tampered.sqlite"
        raw.write_bytes(zlib.decompress(output.read_bytes()))
        with sqlite3.connect(raw) as database:
            database.execute("UPDATE stop SET catalog_station_id='N1' WHERE external_id='X' AND pack_id=1")
        compressed = zlib.compress(raw.read_bytes(), 9)
        output.write_bytes(compressed)
        payload = json.loads(metadata.read_text(encoding="utf-8"))
        payload["compression"].update(sha256=hashlib.sha256(compressed).hexdigest(), sizeBytes=len(compressed))
        payload["database"].update(sha256=hashlib.sha256(raw.read_bytes()).hexdigest(), sizeBytes=raw.stat().st_size)
        metadata.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaisesRegex(verifier.VerificationError, "carries catalogue id N1 outside the border zone"):
            verifier.verify(output, metadata, max_compressed_bytes=4 * 1024 * 1024, max_database_bytes=16 * 1024 * 1024)

    def test_full_pack_must_match_the_manifest(self) -> None:
        (self.full_dir / "pack-0.zip").write_bytes(b"other")
        with self.assertRaisesRegex(builder.BuildError, "full pack missing or different"):
            self.build_base()

    def test_served_and_zone_lists(self) -> None:
        catalog, _ = builder.load_catalog(self.catalog)
        android = builder.android_stop_matcher.load(self.catalog)
        served, zone = builder.foreign_station_coverage(self.full_dir / "pack-0.zip", catalog, "pack-0", android)
        self.assertEqual(({"S1", "N1", "N2"}, {"S1", "N2"}), (served, zone))
        with mock.patch.dict(builder.foreign_rules.CORRIDORS, {}, clear=True):  # only the Italian station is left
            _, zone = builder.foreign_station_coverage(self.full_dir / "pack-0.zip", catalog, "pack-0", android)
        self.assertEqual({"S1"}, zone)

    def test_stations_only_the_android_matcher_resolves_are_served_and_complete_ones_in_the_zone(self) -> None:
        # «Elsewhere» is a catalogue entry without coordinates: the tools' matcher never binds a stop to it, the Android
        # one by its unique name. Its stop Y has a trip outside the subset (FAR): served, outside the zone. The second
        # «Nowhere» (no coordinates) makes the name ambiguous; the Android matcher places X at N1 by its position.
        catalog_payload = json.loads(self.catalog.read_text(encoding="utf-8"))
        catalog_payload["stations"].append({"id": "N9", "name": "Nowhere", "aliases": [], "railwayCode": None,
                                            "trenitaliaLocationId": None, "latitude": None, "longitude": None})
        catalog_payload["stations"].append({"id": "N8", "name": "Elsewhere", "aliases": [], "railwayCode": None,
                                            "trenitaliaLocationId": None, "latitude": None, "longitude": None})
        other = self.temp / "catalog-homonyms.json"
        other.write_text(json.dumps(catalog_payload), encoding="utf-8")
        catalog, _ = builder.load_catalog(other)
        android = builder.android_stop_matcher.load(other)
        self.assertEqual({"PL": "S1", "P": "S1", "V": "N2", "X": "N1", "Y": "N8"},
                         {stop: station for stop, station in android.pack_stations(
                             {row_id: row for row_id, row in self.border_stops().items()}).items()})
        served, zone = builder.foreign_station_coverage(self.full_dir / "pack-0.zip", catalog, "pack-0", android)
        self.assertEqual(({"S1", "N1", "N2", "N8"}, {"S1", "N2"}), (served, zone))

    def test_android_matcher_binds_a_name_only_within_the_bound(self) -> None:
        # GtfsStopStationDistanceTest: the Swiss Guarda is not the Guarda near Ferrara (266 km), Steinach SG not
        # Steinach in Tirol (159 km, the South Tyrol bilingual rule); an entry without coordinates binds by its name.
        matcher_module = builder.android_stop_matcher
        guarda = matcher_module.Station("S05998", "Guarda", (), 44.611476, 11.623357)
        steinach = matcher_module.Station("NC001488", "Steinach in Tirol", (), 47.08896, 11.459997)
        altstetten = matcher_module.Station("S03001", "Zurich Altstetten", (), None, None)
        matcher = matcher_module.AndroidStopMatcher([guarda, steinach, altstetten])
        self.assertIsNone(matcher.resolve("Guarda", 46.77152162, 10.15053152))
        self.assertEqual(guarda, matcher.resolve("Guarda", 44.6115, 11.6234))
        self.assertEqual(guarda, matcher.resolve("Guarda", 44.611476 + 9.0 / 111.2, 11.623357))
        self.assertIsNone(matcher.resolve("Guarda", 44.611476 + 11.0 / 111.2, 11.623357))
        self.assertEqual(guarda, matcher.resolve("Guarda"))
        self.assertIsNone(matcher.resolve("Steinach", 47.50081251, 9.44226382))
        self.assertEqual(steinach, matcher.resolve("Steinach", 47.0890, 11.4600))
        self.assertEqual(altstetten, matcher.resolve("Zürich Altstetten", 47.39147, 8.48894))
        # One name, two stops: the cached name binding is checked against each stop's own position.
        stops = {
            "far": {"stop_id": "far", "stop_name": "Guarda", "stop_lat": "46.77152162", "stop_lon": "10.15053152"},
            "near": {"stop_id": "near", "stop_name": "Guarda", "stop_lat": "44.6115", "stop_lon": "11.6234"},
            "sg": {"stop_id": "sg", "stop_name": "Steinach", "stop_lat": "47.50081251", "stop_lon": "9.44226382"},
        }
        self.assertEqual({"near": "S05998"}, matcher.pack_stations(stops))

    def test_android_matcher_ignores_namesakes_beyond_the_bound(self) -> None:
        # GtfsStopStationDistanceTest: a namesake farther than the bound neither binds a stop nor makes its name
        # ambiguous (San Giorgio in Sicily and in Sardinia; Sant'Antonino in Ticino and on the Genova-Casella line).
        matcher_module = builder.android_stop_matcher
        sicily = matcher_module.Station("S12034", "San Giorgio", (), 38.173967, 14.944088)
        sardinia = matcher_module.Station("NC001829", "San Giorgio (ARST)", ("San Giorgio",), 40.69019, 8.470571)
        ticino = matcher_module.Station("NC001417", "Sant'Antonino", (), 46.15515, 8.980107)
        genova = matcher_module.Station("NC001833", "Sant'Antonino (AMT)", ("Sant'Antonino",), 44.433632, 8.939717)
        matcher = matcher_module.AndroidStopMatcher([sicily, sardinia, ticino, genova])
        self.assertEqual(sardinia, matcher.resolve("SAN GIORGIO", 40.6901903, 8.4705713))
        self.assertEqual(sicily, matcher.resolve("S.GIORGIO", 38.1740, 14.9441))
        self.assertIsNone(matcher.resolve("San Giorgio"))  # no position: both are candidates
        self.assertEqual(genova, matcher.resolve("SANT'ANTONINO/", 44.433632, 8.939717))
        self.assertEqual(ticino, matcher.resolve("S. Antonino", 46.16, 8.97))  # the strict rule
        near = matcher_module.Station("X", "San Giorgio", (), 40.70, 8.48)
        self.assertIsNone(matcher_module.AndroidStopMatcher([sicily, sardinia, near]).resolve("SAN GIORGIO", 40.6901903, 8.4705713))
        # A name ambiguous without a position is decided, stop by stop, by the stations near each one.
        stops = {
            "sa": {"stop_id": "sa", "stop_name": "SAN GIORGIO", "stop_lat": "40.6901903", "stop_lon": "8.4705713"},
            "si": {"stop_id": "si", "stop_name": "SAN GIORGIO", "stop_lat": "38.1740", "stop_lon": "14.9441"},
            "nowhere": {"stop_id": "nowhere", "stop_name": "SAN GIORGIO", "stop_lat": "", "stop_lon": ""},
        }
        self.assertEqual({"sa": "NC001829", "si": "S12034"}, matcher.pack_stations(stops))

    def border_stops(self) -> dict[str, dict[str, str]]:
        with zipfile.ZipFile(self.full_dir / "pack-0.zip") as archive:
            return {row["stop_id"]: row for row in csv.DictReader(io.TextIOWrapper(archive.open("stops.txt"), encoding="utf-8"))}

    def test_foreign_full_index_has_one_pack_and_every_station(self) -> None:
        output, metadata = self.temp / "full.zlib", self.temp / "full.json"
        result = builder.build_foreign_full_database(self.manifest, self.catalog, "pack-0", output, metadata,
                                                     max_compressed_bytes=4 * 1024 * 1024, full_pack_dir=self.full_dir)
        self.assertEqual(("foreign-full", "pack-0", 1), (result["kind"], result["sourceId"], result["inputs"]["packCount"]))
        self.assertEqual({"PL": "S1", "V": "N2", "X": "N1", "Y": None}, self.stops(output, "pack-0"))
        self.assertEqual(3, result["rowCounts"]["trip"])
        options = {"max_compressed_bytes": 4 * 1024 * 1024, "max_database_bytes": 16 * 1024 * 1024}
        checked = verifier.verify(output, metadata, foreign_full="pack-0", **options)
        self.assertEqual(result["inputs"]["fingerprintSha256"], checked["inputFingerprintSha256"])
        with self.assertRaisesRegex(verifier.VerificationError, "not the base index"):
            verifier.verify(output, metadata, **options)
        with self.assertRaisesRegex(verifier.VerificationError, "per-country index of pack-1"):
            verifier.verify(output, metadata, foreign_full="pack-1", **options)
        base_output, base_metadata, _ = self.build_base()
        with self.assertRaisesRegex(verifier.VerificationError, "per-country index"):
            verifier.verify(base_output, base_metadata, foreign_full="pack-0", **options)
        # The fingerprint leaves out the manifest: another manifest date does not make a new iPhone file.
        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        manifest["generatedAt"] = "2026-01-05"
        self.manifest.write_text(json.dumps(manifest), encoding="utf-8")
        again = builder.build_foreign_full_database(self.manifest, self.catalog, "pack-0", self.temp / "again.zlib", self.temp / "again.json",
                                                    max_compressed_bytes=4 * 1024 * 1024, full_pack_dir=self.full_dir)
        self.assertEqual(result["inputs"]["fingerprintSha256"], again["inputs"]["fingerprintSha256"])
        with self.assertRaisesRegex(builder.BuildError, "not a foreign pack bundled as its border subset"):
            builder.build_foreign_full_database(self.manifest, self.catalog, "pack-2", output, metadata, full_pack_dir=self.full_dir)

    def test_builder_and_verifier_share_the_per_country_version(self) -> None:
        self.assertEqual(builder.FOREIGN_FULL_BUILDER_VERSION, verifier.FOREIGN_FULL_BUILDER_VERSION)
        self.assertEqual(builder.FOREIGN_FULL_KIND, verifier.FOREIGN_FULL_KIND)


class RenonRailwayTest(unittest.TestCase):
    """The Renon railway (route 160 of the bundled STA pack): its stations are catalogue entries without codes taken
    from the pack, and the shipped iPhone index keeps every trip of the line on them."""

    PACK = ROOT / "app/src/main/assets/gtfs/alto-adige-sta-gtfs.zip"
    CATALOG = ROOT / "app/src/main/assets/stations/curated_it_rail_stations.json"
    INDEX = ROOT / "ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib"
    IDS = [f"NC00{number}" for number in range(1810, 1820)]

    @classmethod
    def setUpClass(cls) -> None:
        with zipfile.ZipFile(cls.PACK) as pack:
            def rows(name: str) -> list[dict[str, str]]:
                return list(csv.DictReader(io.TextIOWrapper(pack.open(name), encoding="utf-8-sig")))
            routes = {row["route_id"]: row for row in rows("routes.txt") if row["route_short_name"] == "160"}
            cls.route_types = {row["route_type"] for row in routes.values()}
            cls.trips = {row["trip_id"] for row in rows("trips.txt") if row["route_id"] in routes}
            cls.stops = {row["stop_id"]: row for row in rows("stops.txt")}
            cls.line_stops = {row["stop_id"] for row in rows("stop_times.txt") if row["trip_id"] in cls.trips}
        stations = json.loads(cls.CATALOG.read_text(encoding="utf-8"))["stations"]
        cls.renon = {station["id"]: station for station in stations if station["id"] in cls.IDS}

    def test_catalogue_entries_are_the_stations_of_the_pack(self) -> None:
        self.assertEqual({"2"}, self.route_types)
        self.assertEqual(10, len(self.line_stops))
        expected = {}
        for stop_id in self.line_stops:
            stop = self.stops[stop_id]
            # One entry per station: the parent_station when the platform has one.
            place = self.stops[stop["parent_station"]] if stop["parent_station"] else stop
            expected[place["stop_name"]] = (round(float(place["stop_lat"]), 6), round(float(place["stop_lon"]), 6))
        self.assertEqual(sorted(self.IDS), sorted(self.renon))
        self.assertEqual(expected, {s["name"]: (s["latitude"], s["longitude"]) for s in self.renon.values()})
        for station in self.renon.values():
            self.assertIsNone(station["railwayCode"])
            self.assertIsNone(station["trenitaliaLocationId"])

    def test_the_shipped_index_keeps_every_trip_of_the_line_on_its_stations(self) -> None:
        with tempfile.TemporaryDirectory(prefix="ios-renon-") as temporary:
            database = Path(temporary) / "schedule.sqlite"
            database.write_bytes(zlib.decompress(self.INDEX.read_bytes()))
            connection = sqlite3.connect(f"file:{database}?mode=ro&immutable=1", uri=True)
            try:
                bound = dict(connection.execute(
                    "SELECT s.external_id, s.catalog_station_id FROM stop s JOIN pack p ON p.id = s.pack_id "
                    "WHERE p.source_id = 'alto-adige-sta-gtfs' AND s.catalog_station_id IN (%s)" % ",".join("?" * len(self.IDS)),
                    self.IDS,
                ).fetchall())
                trips = {row[0] for row in connection.execute(
                    "SELECT DISTINCT t.external_id FROM trip t JOIN stop_time st ON st.trip_id = t.id "
                    "JOIN stop s ON s.id = st.stop_id WHERE s.catalog_station_id IN (%s)" % ",".join("?" * len(self.IDS)),
                    self.IDS,
                )}
            finally:
                connection.close()
        self.assertEqual(self.line_stops, set(bound))
        self.assertEqual(set(self.IDS), set(bound.values()))
        self.assertGreater(len(self.trips), 0)
        self.assertEqual(self.trips, trips)


class TrenitaliaFranceIndexTest(unittest.TestCase):
    """Trenitalia France's pack, bundled whole on the iPhone as a foreign operator's pack (coded stations only): the
    shipped index keeps every Paris – Milano trip of the pack on its Italian stations, and names no uncoded French one."""

    PACK = ROOT / "app/src/main/assets/gtfs/trenitalia-france-gtfs.zip"
    INDEX = ROOT / "ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib"
    METADATA = ROOT / "ios/Resources/binario_gtfs_schedule_v1.metadata.json"
    ITALIAN = {"Oulx Cesana Claviere Sestriere": "S00204", "Torino Porta Susa": "S00035", "Milano Centrale": "S01700"}

    def test_the_shipped_index_keeps_the_italian_trips_on_their_stations(self) -> None:
        self.assertIn("trenitalia-france-gtfs", builder.FOREIGN_PACKS)
        with zipfile.ZipFile(self.PACK) as pack:
            def rows(name: str) -> list[dict[str, str]]:
                return list(csv.DictReader(io.TextIOWrapper(pack.open(name), encoding="utf-8-sig")))
            names = {row["stop_id"]: row["stop_name"] for row in rows("stops.txt")}
            italian_trips = {row["trip_id"] for row in rows("stop_times.txt") if names[row["stop_id"]] in self.ITALIAN}
        metadata = json.loads(self.METADATA.read_text(encoding="utf-8"))
        left_out = metadata["filter"].get("outOfOrderTripsLeftOut", {}).get("trenitalia-france-gtfs", 0)
        with tempfile.TemporaryDirectory(prefix="ios-tfr-") as temporary:
            database = Path(temporary) / "schedule.sqlite"
            database.write_bytes(zlib.decompress(self.INDEX.read_bytes()))
            connection = sqlite3.connect(f"file:{database}?mode=ro&immutable=1", uri=True)
            try:
                bound = dict(connection.execute(
                    "SELECT s.name, s.catalog_station_id FROM stop s JOIN pack p ON p.id = s.pack_id "
                    "WHERE p.source_id = 'trenitalia-france-gtfs'"
                ).fetchall())
                kept = {row[0] for row in connection.execute(
                    "SELECT DISTINCT t.external_id FROM trip t JOIN pack p ON p.id = t.pack_id "
                    "JOIN stop_time st ON st.trip_id = t.id JOIN stop s ON s.id = st.stop_id "
                    "WHERE p.source_id = 'trenitalia-france-gtfs' AND s.railway_code IS NOT NULL"
                )}
            finally:
                connection.close()
        for name, station_id in self.ITALIAN.items():
            self.assertEqual(station_id, bound[name], name)
        # Uncoded catalogue stations stay unnamed; Lyon Part-Dieu has a Trenitalia location id.
        self.assertIsNone(bound["Paris-Gare-de-Lyon"])
        self.assertIsNone(bound["Chambéry-Challes-les-Eaux"])
        self.assertEqual("NC000228", bound["Lyon-Part-Dieu"])
        self.assertGreater(len(kept), 0)
        self.assertLessEqual(kept, italian_trips)
        self.assertEqual(len(italian_trips) - left_out, len(kept))


KOTLIN_GTFS = ROOT / "app/src/main/java/it/binario/app/data/gtfs"
SHIPPED_CATALOG = ROOT / "app/src/main/assets/stations/curated_it_rail_stations.json"


def kotlin_pack_entries(path: Path) -> dict[str, str]:
    """The text of each EmbeddedGtfsPack(...) entry of a Kotlin pack list, by source id."""
    text = path.read_text(encoding="utf-8")
    starts = [match.start() for match in re.finditer(r"EmbeddedGtfsPack\(\s*SourceId\(", text)]
    entries = {}
    for index, start in enumerate(starts):
        body = text[start:starts[index + 1] if index + 1 < len(starts) else len(text)]
        entries[re.search(r'SourceId\("([^"]+)"\)', body).group(1)] = body
    return entries


class StationCodePortTest(unittest.TestCase):
    """android_station_codes is GtfsStationCodeIndex: GtfsStationCodeIndexTest's cases, and the reviewed table and
    bounds read from the Kotlin file, so the two cannot drift apart."""

    codes = builder.android_station_codes
    scheme = builder.android_station_codes.TRENITALIA_LOCATION_ID

    @staticmethod
    def station(station_id: str, name: str, railway_code: str | None, location_id: str | None,
                latitude: float | None, longitude: float | None):
        return builder.android_station_codes.Station(station_id, name, (), railway_code, location_id, latitude, longitude)

    def setUp(self) -> None:
        # Fidene's location id is 830008225 but its ViaggiaTreno code is S08235; S08225 is another place.
        self.fidene = self.station("S08235", "Fidene", "S08235", "830008225", 41.979773, 12.508451)
        self.orvieto = self.station("S08225", "Pc Orvieto", "S08225", None, None, None)
        self.bologna = self.station("S05043", "Bologna Centrale", "S05043", None, 44.506145, 11.342267)
        self.wrong_fiera = self.station("S12137", "Fiera", "S12137", "830008415", 38.144004, 13.351336)
        self.la_rustica = self.station("S08514", "La Rustica Uir", "S08514", "830008505", 41.91, 12.60)
        self.no_coordinates = self.station("S07000", "Castel Giubileo", "S07000", "830007000", None, None)
        self.index = self.codes.StationCodeIndex(
            [self.fidene, self.orvieto, self.bologna, self.wrong_fiera, self.la_rustica, self.no_coordinates])

    def test_the_location_id_wins_over_the_derived_viaggiatreno_code(self) -> None:
        self.assertEqual(self.fidene, self.index.resolve(self.scheme, "830008225", 41.9797, 12.5084, "FIDENE"))

    def test_the_derived_code_binds_a_station_without_a_location_id_when_the_coordinates_agree(self) -> None:
        self.assertEqual(self.bologna, self.index.resolve(self.scheme, "830005043", 44.506234, 11.342267, "BOLOGNA C.LE"))
        # Too far away for a guess: Bologna is not 20 km from its own platform.
        self.assertIsNone(self.index.resolve(self.scheme, "830005043", 44.70, 11.40, "BOLOGNA C.LE"))

    def test_a_derived_code_never_takes_a_station_that_has_another_location_id(self) -> None:
        # S08514 is La Rustica (830008505); "SERENISSIMA" 830008514 must not become La Rustica.
        self.assertIsNone(self.index.resolve(self.scheme, "830008514", 41.91, 12.60, "SERENISSIMA"))

    def test_a_catalogue_location_id_far_from_the_stop_is_rejected(self) -> None:
        # The catalogue gives Palermo's "Fiera" the location id of Fiera di Roma, 417 km away.
        self.assertIsNone(self.index.resolve(self.scheme, "830008415", 41.79, 12.33, "FIERA DI ROMA"))

    def test_without_coordinates_a_shared_word_of_the_name_corroborates_the_code(self) -> None:
        self.assertEqual(self.no_coordinates, self.index.resolve(self.scheme, "830007000", None, None, "CASTEL GIUBILEO"))
        self.assertIsNone(self.index.resolve(self.scheme, "830007000", None, None, "SETTEBAGNI"))

    def test_foreign_codes_malformed_codes_and_packs_without_a_scheme_resolve_nothing(self) -> None:
        self.assertIsNone(self.index.resolve(self.scheme, "850001008", 46.21, 6.14, "GENEVE"))
        self.assertIsNone(self.index.resolve(self.scheme, "8300", None, None, "x"))
        self.assertIsNone(self.index.resolve(None, "830008225", 41.9797, 12.5084, "FIDENE"))

    def test_a_code_two_stations_share_names_neither(self) -> None:
        twin = self.station("S99999", "Fidene bis", "S99999", "830008225", 41.98, 12.51)
        index = self.codes.StationCodeIndex([self.fidene, twin])
        self.assertIsNone(index.resolve(self.scheme, "830008225", 41.9797, 12.5084, "FIDENE"))

    def test_every_reviewed_italo_code_names_a_station_of_the_shipped_catalogue(self) -> None:
        real = self.codes.load(SHIPPED_CATALOG)
        self.assertEqual(63, len(self.codes.ITALO_STATION_CODES))
        italo_stops = {
            "MC_": "Milano Centrale", "PD_": "Padova", "BC_": "Bologna Centrale", "AAV": "Reggio Emilia AV",
            "RCE": "Reggio Di Calabria Centrale", "VEM": "Venezia Mestre", "CDZ": "Cortina D'Ampezzo", "SGM": "Villa S. Giovanni",
        }
        for code, name in italo_stops.items():
            station = real.resolve(self.codes.ITALO_STATION_CODE, code, None, None, "")
            self.assertEqual(name, station.name if station else None, code)
        self.assertIsNone(real.resolve(self.codes.ITALO_STATION_CODE, "LUX", 43.84, 10.51, "Lucca Comics and Games BUS"))
        self.assertTrue(all(code not in self.codes.ITALO_STATION_CODES for code in self.codes.ITALO_UNMAPPED))
        for code in ("AGR", "BAC", "BEN", "NAF", "SDX", "TDX", "TRX"):
            self.assertIn(self.codes.ITALO_STATION_CODES[code], real.by_id, code)

    def test_samples_of_the_trenitalia_location_ids_on_the_shipped_catalogue(self) -> None:
        real = self.codes.load(SHIPPED_CATALOG)
        def name(code: str, latitude: float, longitude: float, published: str) -> str | None:
            station = real.resolve(self.scheme, code, latitude, longitude, published)
            return station.name if station else None
        self.assertEqual("Milano Centrale", name("830001700", 45.4863, 9.2045, "MILANO CENTRALE"))
        self.assertEqual("Fidene", name("830008225", 41.9797, 12.5084, "FIDENE"))
        self.assertEqual("Bologna Centrale", name("830005043", 44.506234, 11.342267, "BOLOGNA C.LE"))
        self.assertEqual("Napoli Afragola", name("830009988", 40.930568, 14.328634, "NA Afragola PES"))
        self.assertEqual("Firenze S. M. Novella", name("830006421", 43.7765, 11.2482, "FIRENZE S.MARIA NOVELLA"))
        self.assertIsNone(name("830008415", 41.79, 12.33, "FIERA DI ROMA"))

    def test_the_shared_table_and_the_bounds_are_the_kotlin_ones(self) -> None:
        # contracts/shared-data/v1 holds the table every platform reads: this module reads the JSON, Android the Kotlin
        # tools/generate_shared_code.py writes from it, entries and comments in the table's order.
        text = (KOTLIN_GTFS / "GtfsStationCodeIndex.kt").read_text(encoding="utf-8")
        table = text.split("internal object ItaloGtfsStationCodes", 1)[1]
        self.assertIn("SharedData.ItaloGtfsStationCodes.CODES.associate { it.code to it.catalogueId }", table)
        self.assertIn("SharedData.ItaloGtfsStationCodes.UNMAPPED.mapTo(LinkedHashSet()) { it.code }", table)
        self.assertNotRegex(table, r'"[A-Z0-9_]{3}" to "')
        generated = (ROOT / "app/src/main/java/it/binario/app/shared/SharedData.kt").read_text(encoding="utf-8")
        generated = generated.split("    object ItaloGtfsStationCodes {", 1)[1].split("\n    }\n", 1)[0]
        kotlin = re.findall(r'// ([^\n]+)\n\s*StationCode\(code = "([A-Z0-9_]{3})", catalogueId = "([A-Z0-9]+)"\)', generated)
        shared = json.loads(self.codes.ITALO_STATION_CODES_PATH.read_text(encoding="utf-8"))
        self.assertEqual("binario-shared-data-v1", shared["schema"])
        self.assertEqual([(code, station, comment) for comment, code, station in kotlin],
                         [(entry["code"], entry["catalogueId"], entry["comment"]) for entry in shared["codes"]])
        self.assertEqual({code: station for _, code, station in kotlin}, dict(self.codes.ITALO_STATION_CODES))
        self.assertEqual(set(re.findall(r'UnmappedCode\(code = "([A-Z0-9_]{3})"\)', generated)), set(self.codes.ITALO_UNMAPPED))
        self.assertEqual(set(self.codes.ITALO_UNMAPPED), {entry["code"] for entry in shared["unmapped"]})
        self.assertIn(f"const val MAX_LOCATION_ID_KM = {self.codes.MAX_LOCATION_ID_KM}", text)
        self.assertIn(f"const val MAX_DERIVED_CODE_KM = {self.codes.MAX_DERIVED_CODE_KM}", text)
        self.assertIn('private val TRENITALIA_LOCATION = Regex("\\\\d{9}")', text)
        self.assertIn('if (!code.startsWith("8300")) return null', text)

    def test_the_pack_rules_are_the_kotlin_ones(self) -> None:
        core = kotlin_pack_entries(KOTLIN_GTFS / "EmbeddedGtfsPacks.kt")
        transit = kotlin_pack_entries(KOTLIN_GTFS / "TransitGtfsPacks.kt")
        packs = {**core, **transit}
        schemes = {source_id: match.group(1) for source_id, body in packs.items()
                   if (match := re.search(r"stopCodeScheme = GtfsStopCodeScheme\.([A-Z_]+)", body))}
        self.assertEqual(schemes, dict(self.codes.PACK_SCHEMES))
        # The scheduled coaches are the shared pack roles on both sides (contracts/shared-data/v1/pack-roles-v1.json).
        self.assertFalse(any("busRoutesAreScheduledCoaches = true" in body for body in packs.values()))
        self.assertIn("busRoutesAreScheduledCoaches = pack.sourceId.value in SharedData.PackRoles.SCHEDULED_COACH_PACKS",
                      (KOTLIN_GTFS / "EmbeddedGtfsPacks.kt").read_text(encoding="utf-8"))
        roles = json.loads((ROOT / "contracts/shared-data/v1/pack-roles-v1.json").read_text(encoding="utf-8"))
        self.assertEqual(set(roles["scheduledCoachPacks"]), set(builder.SCHEDULED_COACH_PACKS))
        self.assertEqual({source_id for source_id, body in packs.items() if "itineraryCopies = true" in body},
                         set(builder.ITINERARY_COPY_PACKS))
        suffixes = {source_id: tuple(re.findall(r'"([^"]+)"', match.group(1))) for source_id, body in packs.items()
                    if (match := re.search(r"catalogueNameSuffixes = listOf\(([^)]*)\)", body)) and "personalOnly = true" not in body}
        self.assertEqual(suffixes, dict(builder.CATALOGUE_NAME_SUFFIXES))
        self.assertIn("private const val MAX_DWELL_SKEW_MINUTES = 20", (KOTLIN_GTFS / "EmbeddedGtfsRouteAdapter.kt").read_text(encoding="utf-8"))
        # The railway packs of gtfs-transit/ are the Android railway transit packs of every build, its city networks the
        # shared urbanPacks of every build (pack-roles-v1.json, which Android reads too: EmbeddedGtfsPacks.withPackRoles).
        manifest = json.loads((ROOT / "app/src/main/assets/gtfs-transit/manifest.json").read_text(encoding="utf-8"))
        public = {source_id for source_id, body in transit.items() if "personalOnly = true" not in body}
        rail = {pack["sourceId"] for pack in manifest["packs"] if pack["kind"] == builder.TRANSIT_RAIL_KIND}
        urban = {pack["sourceId"] for pack in manifest["packs"] if pack["kind"] == builder.TRANSIT_URBAN_KIND}
        self.assertEqual(rail, public - builder.URBAN_PACKS)
        self.assertEqual(urban, public & builder.URBAN_PACKS)
        self.assertEqual(set(roles["urbanPacks"]), set(builder.URBAN_PACKS))
        self.assertLessEqual({pack["kind"] for pack in manifest["packs"]}, {builder.TRANSIT_RAIL_KIND, builder.TRANSIT_URBAN_KIND})
        self.assertIn("EmbeddedGtfsPackKind.URBAN else pack.kind", (KOTLIN_GTFS / "EmbeddedGtfsPacks.kt").read_text(encoding="utf-8"))


class StationCodeContractTest(unittest.TestCase):
    """contracts/shared-rules/v1/gtfs-station-codes-v1.json, the cases GtfsStationCodesContractTest runs on Android."""

    def test_every_shared_stop_code_names_the_shared_catalogue_station(self) -> None:
        rules = json.loads((ROOT / "contracts/shared-rules/v1/gtfs-station-codes-v1.json").read_text(encoding="utf-8"))
        self.assertEqual("binario-shared-rules-v1", rules["schema"])
        index = builder.android_station_codes.load(ROOT / rules["catalogue"])
        schemes = {builder.android_station_codes.TRENITALIA_LOCATION_ID, builder.android_station_codes.ITALO_STATION_CODE}
        self.assertEqual(schemes, {case["scheme"] for case in rules["stationCodes"]})
        for case in rules["stationCodes"]:
            with self.subTest(code=case["stopCode"], name=case["name"]):
                station = index.resolve(case["scheme"], case["stopCode"], case["latitude"], case["longitude"], case["name"])
                self.assertEqual(case["expected"], station.id if station else None, case["why"])
        # Both outcomes of every rule are covered: a station, and none.
        for scheme in schemes:
            self.assertEqual({True, False}, {case["expected"] is not None for case in rules["stationCodes"] if case["scheme"] == scheme})


class StationCodeIdentityTest(unittest.TestCase):
    """The stops of a pack publishing station codes are bound by the code first (GtfsStopStationResolver.resolveStop),
    by name only when no code binds them; the FAL pack's names try the catalogue's operator suffix first."""

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="ios-gtfs-codes-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.catalog = self.temp / "catalog.json"
        self.catalog.write_text(json.dumps({"schemaVersion": 1, "stations": [
            {"id": "S05043", "name": "Bologna Centrale", "aliases": [], "railwayCode": "S05043", "trenitaliaLocationId": None,
             "latitude": 44.506145, "longitude": 11.342267},
            {"id": "S02581", "name": "Padova", "aliases": [], "railwayCode": "S02581", "trenitaliaLocationId": "830002581",
             "latitude": 45.417, "longitude": 11.880},
            {"id": "S07000", "name": "Castel Giubileo", "aliases": [], "railwayCode": "S07000", "trenitaliaLocationId": "830007000",
             "latitude": None, "longitude": None},
            {"id": "S09999", "name": "Ferrara", "aliases": [], "railwayCode": "S09999", "trenitaliaLocationId": None,
             "latitude": 44.84, "longitude": 11.60},
            {"id": "NC1", "name": "Matera Centrale FAL", "aliases": [], "railwayCode": None, "trenitaliaLocationId": None,
             "latitude": 40.667, "longitude": 16.604},
            {"id": "NC2", "name": "Matera Centrale", "aliases": [], "railwayCode": None, "trenitaliaLocationId": None,
             "latitude": 40.670, "longitude": 16.600},
        ]}), encoding="utf-8")
        self.matcher, _ = builder.load_catalog(self.catalog)
        self.codes = builder.android_station_codes.load(self.catalog)

    def bound(self, source_id: str, stops: dict[str, dict[str, str]], codes=None) -> tuple[dict[str, str], set[str]]:
        by_code: set[str] = set()
        identities = builder.stop_identities(stops, self.matcher, source_id, uncoded=True, codes=codes, code_bound=by_code)
        return {stop_id: identity.station_id for stop_id, identity in identities.items()}, by_code

    def test_a_trenitalia_stop_is_its_code_station_before_any_name(self) -> None:
        stops = {
            # Bologna's spelling matches no catalogue name; its derived ViaggiaTreno code does.
            "BO": {"stop_id": "BO", "stop_code": "830005043", "stop_name": "BOLOGNA C.LE", "stop_lat": "44.506234", "stop_lon": "11.342267"},
            # A published name of another station: the code decides.
            "PD": {"stop_id": "PD", "stop_code": "830002581", "stop_name": "Ferrara", "stop_lat": "45.4171", "stop_lon": "11.8801"},
            # A station without coordinates, corroborated by a word of the name.
            "CG": {"stop_id": "CG", "stop_code": "830007000", "stop_name": "CASTEL GIUBILEO", "stop_lat": "", "stop_lon": ""},
            # A foreign code binds nothing: the name decides.
            "FE": {"stop_id": "FE", "stop_code": "850009999", "stop_name": "Ferrara", "stop_lat": "44.8401", "stop_lon": "11.6001"},
        }
        bound, by_code = self.bound("trenitalia-gtfs", stops, self.codes)
        self.assertEqual({"BO": "S05043", "PD": "S02581", "CG": "S07000", "FE": "S09999"}, bound)
        self.assertEqual({"BO", "PD", "CG"}, by_code)
        # Without the code rule (another pack) the same stops are bound by name only: "Ferrara" 66 km from Ferrara is none.
        self.assertEqual({"FE": "S09999"}, self.bound("pack-0", stops, self.codes)[0])

    def test_an_italo_stop_is_the_reviewed_station_of_its_code(self) -> None:
        stops = {"PD_": {"stop_id": "PD_", "stop_code": "PD_", "stop_name": "Padua", "stop_lat": "45.4177", "stop_lon": "11.8807"},
                 "LUX": {"stop_id": "LUX", "stop_code": "LUX", "stop_name": "Lucca Comics and Games BUS", "stop_lat": "43.84", "stop_lon": "10.51"}}
        self.assertEqual(({"PD_": "S02581"}, {"PD_"}), self.bound("italo-gtfs", stops, self.codes))

    def test_a_fal_stop_is_the_station_the_catalogue_names_with_the_operator_suffix(self) -> None:
        stops = {"MT": {"stop_id": "MT", "stop_name": "Matera Centrale", "stop_lat": "40.6671", "stop_lon": "16.6041"}}
        self.assertEqual({"MT": "NC1"}, self.bound("fal-basilicata-puglia", stops)[0])
        self.assertEqual({"MT": "NC2"}, self.bound("eav-gtfs", stops)[0])


class ItineraryCopiesTest(unittest.TestCase):
    """EmbeddedGtfsRouteAdapter.finishStopTimes and copiesOn for Italo's pack: a run published again as part of a
    combined itinerary, under the first train's number, is left out on every day the train itself runs."""

    STATIONS = {"MC": ("Milano Centrale", 45.0, 9.0), "VR": ("Verona Porta Nuova", 45.4, 11.0),
                "VE": ("Venezia S. Lucia", 45.4, 12.3), "RM": ("Roma Termini", 41.9, 12.5), "FI": ("Firenze S. M. Novella", 43.7, 11.2)}

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="ios-gtfs-copies-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.catalog = self.temp / "catalog.json"
        self.catalog.write_text(json.dumps({"schemaVersion": 1, "stations": [
            {"id": f"S-{code}", "name": name, "aliases": [], "railwayCode": f"S-{code}", "trenitaliaLocationId": None,
             "latitude": latitude, "longitude": longitude} for code, (name, latitude, longitude) in self.STATIONS.items()
        ]}), encoding="utf-8")
        trips = [
            ["R", "MON-TUE", "8987-own", "", "8987"],   # the train: Milano 10:00, Verona 11:00/11:05, Venezia 12:00
            ["R", "DAILY", "9920-own", "", "9920"],     # train 9920 itself, Roma - Firenze, every day
            ["R", "MON-WED", "9920-copy", "", "9920"],  # 9920's itinerary goes on with 8987: Verona 11:03 - Venezia
            ["R", "DAILY", "9930-own", "", "9930"],
            ["R", "MON-TUE", "9930-copy", "", "9930"],  # a copy on every day it runs
            ["B", "DAILY", "bus", "", "1060"],          # an ItaloBus coach: no train run, never a copy
        ]
        times = [
            ["8987-own", "10:00:00", "10:00:00", "MC", "1"], ["8987-own", "11:00:00", "11:05:00", "VR", "2"], ["8987-own", "12:00:00", "12:00:00", "VE", "3"],
            ["9920-own", "08:00:00", "08:00:00", "RM", "1"], ["9920-own", "09:30:00", "09:30:00", "FI", "2"],
            ["9920-copy", "11:00:00", "11:03:00", "VR", "1"], ["9920-copy", "12:00:00", "12:00:00", "VE", "2"],
            ["9930-own", "07:00:00", "07:00:00", "RM", "1"], ["9930-own", "08:30:00", "08:30:00", "FI", "2"],
            ["9930-copy", "10:00:00", "10:00:00", "MC", "1"], ["9930-copy", "11:00:00", "11:05:00", "VR", "2"],
            ["bus", "11:00:00", "11:05:00", "VR", "1"], ["bus", "12:00:00", "12:00:00", "VE", "2"],
        ]
        days = {"MON-TUE": "1100000", "MON-WED": "1110000", "DAILY": "1111111"}
        self.archive = self.temp / "italo.zip"
        with zipfile.ZipFile(self.archive, "w") as archive:
            archive.writestr("agency.txt", csv_bytes(["agency_id", "agency_name", "agency_url", "agency_timezone"], [["A", "Italo", "https://example.test", "Europe/Rome"]]))
            archive.writestr("routes.txt", csv_bytes(["route_id", "agency_id", "route_short_name", "route_long_name", "route_type"], [["R", "A", "Italo", "Italo AV", "2"], ["B", "A", "ItaloBus", "ItaloBus", "3"]]))
            archive.writestr("trips.txt", csv_bytes(["route_id", "service_id", "trip_id", "trip_headsign", "trip_short_name"], trips))
            archive.writestr("stops.txt", csv_bytes(["stop_id", "stop_name", "stop_lat", "stop_lon"], [[code, name, str(latitude), str(longitude)] for code, (name, latitude, longitude) in self.STATIONS.items()]))
            archive.writestr("stop_times.txt", csv_bytes(["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"], times))
            archive.writestr("calendar.txt", csv_bytes(
                ["service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "start_date", "end_date"],
                [[service, *pattern, "20260105", "20260111"] for service, pattern in days.items()]))

    def ingest(self, source_id: str = "italo-gtfs") -> tuple[sqlite3.Connection, dict[str, int]]:
        connection = sqlite3.connect(":memory:")
        self.addCleanup(connection.close)
        connection.executescript(builder.SCHEMA_SQL)
        matcher, _ = builder.load_catalog(self.catalog)
        counts = builder.ingest_pack(connection, 1, {"source_id": source_id, "attribution": "Test", "last_service_date": 20260111,
                                                     "archive": self.archive}, matcher, builder.IdAllocator())
        return connection, counts

    def test_copies_are_left_out_on_the_days_the_train_itself_runs(self) -> None:
        connection, counts = self.ingest()
        services = dict(connection.execute(
            "SELECT t.external_id, s.active_days FROM trip t JOIN service s ON s.id = t.service_id").fetchall())
        # 9930-copy is a copy on both of its days; 9920-copy only on Monday and Tuesday: it keeps Wednesday (bit 2).
        self.assertEqual({"8987-own", "9920-own", "9920-copy", "9930-own", "bus"}, set(services))
        self.assertEqual(bytes([0b0000100]), services["9920-copy"])
        self.assertEqual(bytes([0b0000011]), services["8987-own"])
        self.assertEqual((1, 2, 1), (counts[builder.ITINERARY_COPY_TRIPS_KEY], counts[builder.ITINERARY_COPY_DAYS_KEY],
                                     counts[builder.ITINERARY_ALIGNED_KEY]))
        # The copy lost Verona's dwell: it leaves at 11:05 with the train, as the train's own trip says.
        departure = connection.execute(
            "SELECT st.departure_seconds FROM stop_time st JOIN trip t ON t.id = st.trip_id WHERE t.external_id='9920-copy' "
            "AND st.stop_sequence=1").fetchone()[0]
        self.assertEqual(11 * 3600 + 5 * 60, departure)

    def test_other_packs_keep_every_trip_as_published(self) -> None:
        connection, counts = self.ingest("pack-0")
        self.assertEqual(6, connection.execute("SELECT count(*) FROM trip").fetchone()[0])
        self.assertNotIn(builder.ITINERARY_COPY_TRIPS_KEY, counts)
        self.assertEqual(11 * 3600 + 3 * 60, connection.execute(
            "SELECT st.departure_seconds FROM stop_time st JOIN trip t ON t.id = st.trip_id WHERE t.external_id='9920-copy' "
            "AND st.stop_sequence=1").fetchone()[0])

    def test_coupled_portions_under_their_own_numbers_are_never_merged(self) -> None:
        def run(index: int, number: str, stops: list[int], arrivals: list[int], departures: list[int]):
            return builder.ItineraryRun(f"trip-{index}", index, "S", number, list(range(len(stops))), stops, arrivals, departures)
        first = run(0, "100", [1, 2], [600, 660], [600, 660])
        second = run(1, "200", [1, 2], [600, 660], [600, 660])
        self.assertEqual(set(), builder.itinerary_copies_of_day([first, second]))
        # The same run twice under one number: the first declared stays.
        twin = run(2, "100", [1, 2], [600, 660], [600, 660])
        self.assertEqual({"trip-2"}, builder.itinerary_copies_of_day([first, twin]))

    def test_the_aligned_departures_do_not_depend_on_the_order_of_the_pairs(self) -> None:
        italo = ROOT / "app/src/main/assets/gtfs/italo-gtfs.zip"
        import random

        def aligned(seed: int | None) -> dict[tuple[str, int], int]:
            connection = sqlite3.connect(":memory:")
            try:
                connection.execute("CREATE TEMP TABLE ios_gtfs_staged_stop_time (trip_external_id TEXT, stop_sequence INTEGER, "
                                   "stop_external_id TEXT, arrival_seconds INTEGER, departure_seconds INTEGER)")
                with builder.BoundedGtfsArchive(italo) as archive:
                    trips = builder.unique_rows(archive.rows("trips.txt", ("trip_id",)), "trip_id", "trips")
                    routes = builder.unique_rows(archive.rows("routes.txt", ("route_id",)), "route_id", "routes")
                    stops = builder.unique_rows(archive.rows("stops.txt", ("stop_id",)), "stop_id", "stops")
                    for row in archive.rows("stop_times.txt", ("trip_id",)):
                        connection.execute("INSERT INTO temp.ios_gtfs_staged_stop_time VALUES(?,?,?,?,?)", (
                            row["trip_id"], int(row["stop_sequence"]), row["stop_id"],
                            builder.parse_gtfs_time(row["arrival_time"], "a"), builder.parse_gtfs_time(row["departure_time"], "d")))
                runs = builder.itinerary_runs(connection, trips, routes, stops)
            finally:
                connection.close()
            pairs = builder.sharing_pairs(runs)
            if seed is not None:
                random.Random(seed).shuffle(pairs)
            builder.align_copy_departures(runs, pairs)
            return {(run.trip_id, sequence): departure for run in runs for sequence, departure in zip(run.sequences, run.departures)}

        reference = aligned(None)
        for seed in range(4):
            self.assertEqual(reference, aligned(seed))


def write_city_feed(path: Path) -> None:
    """A city network: a people mover (route type 12) and a tram (0) calling at stops near the fixture catalogue's
    S1 (45.0, 9.0): "STAZIONE/" 100 m north, its second platform 50 m further, "PIAZZA CENTRALE" 2 km away, and a
    funicular stop listed without modes. Names are published in capitals, as Milan's and Genoa's are."""
    stop_times = [
        ["MEX1", "10:00:00", "10:00:00", "A", "1"], ["MEX1", "10:07:00", "10:07:00", "C", "2"],
        ["MEX2", "10:10:00", "10:10:00", "C", "1"], ["MEX2", "10:17:00", "10:17:00", "A2", "2"],
        ["T1", "11:00:00", "11:00:00", "A2", "1"], ["T1", "11:05:00", "11:05:00", "C", "2"],
        ["F1", "12:00:00", "12:00:00", "C", "1"], ["F1", "12:03:00", "12:03:00", "F", "2"],
    ]
    members = {
        "agency.txt": csv_bytes(["agency_id", "agency_name", "agency_url", "agency_timezone"], [["M", "City Mover", "https://example.test", "Europe/Rome"]]),
        "routes.txt": csv_bytes(["route_id", "agency_id", "route_short_name", "route_long_name", "route_type"],
                                [["MEX", "M", "MEX", "People mover", "12"], ["T", "M", "1", "Tram", "0"], ["F", "M", "", "Funicolare", "7"]]),
        "trips.txt": csv_bytes(["route_id", "service_id", "trip_id"], [["MEX", "WK", "MEX1"], ["MEX", "WK", "MEX2"], ["T", "WK", "T1"], ["F", "WK", "F1"]]),
        "stops.txt": csv_bytes(["stop_id", "stop_name", "stop_lat", "stop_lon"], [
            ["A", "STAZIONE/", "45.0009", "9.0"], ["A2", "STAZIONE/", "45.00135", "9.0"],
            ["C", "PIAZZA CENTRALE", "45.018", "9.0"], ["F", "Collina", "45.03", "9.0"],
        ]),
        "stop_times.txt": csv_bytes(["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"], stop_times),
        "calendar.txt": csv_bytes(["service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "start_date", "end_date"],
                                  [["WK", "1", "1", "1", "1", "1", "1", "1", "20260105", "20260107"]]),
        builder.STOP_MODES_FILE: csv_bytes(["stop_id", "modes"], [["A", "MT"], ["A2", "MT"], ["C", "M"], ["F", ""]]),
    }
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in members.items():
            archive.writestr(name, content)


class TransitPacksTest(unittest.TestCase):
    """The railway packs and the city networks of the gtfs-transit/ manifest join the index; a pack of any other kind,
    or a city network the shared pack roles do not name, fails closed. A GATEWAY_ONLY source (HTTP origin,
    Genova–Casella) needs its HTTPS copy."""

    CITY = "marconi-express-gtfs"

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="ios-gtfs-transit-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        patcher = mock.patch.object(builder, "FOREIGN_PACKS", FIXTURE_PACKS)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.manifest, self.catalog = write_inputs(self.temp)
        core = json.loads(self.manifest.read_text(encoding="utf-8"))
        # The last fixture pack moves to the transit manifest; the one before it becomes a city network there.
        moved = core["packs"].pop()
        replaced = core["packs"].pop()
        (self.temp / "gtfs" / Path(replaced["asset"]).name).unlink()
        self.manifest.write_text(json.dumps(core), encoding="utf-8")
        transit_dir = self.temp / "gtfs-transit"
        transit_dir.mkdir()
        shutil.move(str(self.temp / "gtfs" / Path(moved["asset"]).name), transit_dir / Path(moved["asset"]).name)
        self.rail = dict(moved, kind="rail", asset=f"gtfs-transit/{Path(moved['asset']).name}")
        city = transit_dir / f"{self.CITY}.zip"
        write_city_feed(city)
        self.urban = {"sourceId": self.CITY, "kind": "urban", "asset": f"gtfs-transit/{city.name}",
                      "sourceUrl": f"https://example.test/{self.CITY}.zip", "accessMode": "OPEN_DIRECT", "attribution": "City data",
                      "lastServiceDate": "2026-01-07", "sha256": hashlib.sha256(city.read_bytes()).hexdigest()}
        self.transit = transit_dir / "manifest.json"
        self.write_transit([self.rail, self.urban])
        self.sources = [
            {"sourceId": pack["sourceId"], "sourceUrl": pack["sourceUrl"], "accessMode": pack["accessMode"], "licence": "CC BY 4.0",
             "landingPage": "https://example.test/" + pack["sourceId"]} for pack in [*core["packs"], self.urban, self.rail]
        ]
        self.policy = self.temp / "policy.json"
        self.policy.write_text(json.dumps({"schemaVersion": 1, "sources": self.sources}), encoding="utf-8")

    def write_transit(self, packs: list[dict]) -> None:
        self.transit.write_text(json.dumps({"schemaVersion": 4, "packs": packs}), encoding="utf-8")

    def build(self) -> dict:
        return builder.build_database(self.manifest, self.catalog, self.temp / "out.zlib", self.temp / "out.json",
                                      source_policy_path=self.policy, max_compressed_bytes=4 * 1024 * 1024,
                                      transit_manifest_path=self.transit)

    def verify(self) -> dict:
        return verifier.verify(self.temp / "out.zlib", self.temp / "out.json", source_policy_path=self.policy,
                               max_compressed_bytes=4 * 1024 * 1024, max_database_bytes=16 * 1024 * 1024)

    def database(self) -> sqlite3.Connection:
        database = self.temp / "out.sqlite"
        database.write_bytes(zlib.decompress((self.temp / "out.zlib").read_bytes()))
        connection = sqlite3.connect(database)
        self.addCleanup(connection.close)
        return connection

    def test_rail_packs_and_city_networks_are_indexed(self) -> None:
        result = self.build()
        self.assertEqual(builder.INDEXED_PACK_COUNT, result["rowCounts"]["pack"])
        self.assertEqual([self.rail["sourceId"]], result["filter"]["transitRailPacks"])
        self.assertEqual([self.CITY], result["filter"]["transitUrbanPacks"])
        self.assertEqual(dict(builder.URBAN_STOPS), result["filter"]["urbanStops"])
        self.assertEqual([*builder.SCHEMA_TABLES, *builder.URBAN_SCHEMA_TABLES], result["database"]["schemaTables"])
        self.assertIn("gtfs-transit/manifest.json", [item["path"] for item in result["inputs"]["files"]])
        self.verify()
        # Without the transit manifest the index misses two packs and fails closed.
        with self.assertRaisesRegex(builder.BuildError, f"exactly {builder.INDEXED_PACK_COUNT} packs"):
            builder.build_database(self.manifest, self.catalog, self.temp / "out.zlib", self.temp / "out.json",
                                   source_policy_path=self.policy, max_compressed_bytes=4 * 1024 * 1024)

    def test_a_city_network_keeps_every_trip_and_never_names_a_railway_station(self) -> None:
        self.build()
        connection = self.database()
        city = "SELECT {} FROM stop s JOIN pack p ON p.id = s.pack_id WHERE p.source_id = ?"
        # Every trip, the funicular's too; no stop is a catalogue station, whatever its name.
        self.assertEqual(4, connection.execute(
            "SELECT count(*) FROM trip t JOIN pack p ON p.id = t.pack_id WHERE p.source_id = ?", (self.CITY,)).fetchone()[0])
        self.assertEqual([(None, None, None)] * 4, connection.execute(
            city.format("s.catalog_station_id, s.railway_code, s.trenitalia_location_id"), (self.CITY,)).fetchall())
        # Names as Android shows them, the stops in stops.txt order (the search offers the first of a name).
        self.assertEqual([("A", "Stazione"), ("A2", "Stazione"), ("C", "Piazza Centrale"), ("F", "Collina")],
                         connection.execute(city.format("s.external_id, s.name") + " ORDER BY s.id", (self.CITY,)).fetchall())
        self.assertEqual({"A": "MT", "A2": "MT", "C": "M", "F": ""}, dict(connection.execute(
            "SELECT s.external_id, u.modes FROM urban_stop u JOIN stop s ON s.id = u.stop_id")))
        self.assertEqual({(12,), (0,), (7,)}, set(connection.execute(
            "SELECT r.route_type FROM route r JOIN pack p ON p.id = r.pack_id WHERE p.source_id = ?", (self.CITY,))))
        # S1 (45.0, 9.0): "Stazione" is one place 100 m and 150 m away, reached in 4 minutes at both platforms (the
        # place's shortest walk); "Piazza Centrale", 2 km away, is no walk. N1 is far from every stop.
        self.assertEqual({("S1", "A", 4), ("S1", "A2", 4)}, set(connection.execute(
            "SELECT w.catalog_station_id, s.external_id, w.minutes FROM urban_walk w JOIN stop s ON s.id = w.stop_id")))

    def test_the_verifier_derives_the_names_and_walks_again(self) -> None:
        self.build()
        self.verify()
        metadata = json.loads((self.temp / "out.json").read_text(encoding="utf-8"))
        inputs = verifier.verify_inputs(metadata, self.temp / "out.json", self.policy)[1]
        rules = verifier.urban_rules(metadata, inputs)
        connection = self.database()
        verifier.verify_urban_rows(connection, rules)
        connection.execute("UPDATE urban_walk SET minutes = minutes + 1")
        with self.assertRaisesRegex(verifier.VerificationError, "urban_walk differs"):
            verifier.verify_urban_rows(connection, rules)
        connection.execute("UPDATE urban_walk SET minutes = minutes - 1")
        connection.execute("UPDATE stop SET name = 'STAZIONE/' WHERE external_id = 'A' AND pack_id = (SELECT id FROM pack WHERE source_id = ?)", (self.CITY,))
        with self.assertRaisesRegex(verifier.VerificationError, "not named as Android shows it"):
            verifier.verify_urban_rows(connection, rules)
        connection.execute("UPDATE stop SET catalog_station_id = 'S1' WHERE external_id = 'C' AND pack_id = (SELECT id FROM pack WHERE source_id = ?)", (self.CITY,))
        with self.assertRaisesRegex(verifier.VerificationError, "carries a catalogue identity"):
            verifier.verify_urban_tables(connection, [self.CITY])

    def test_the_rows_of_a_city_stop_are_bounded(self) -> None:
        self.build()
        connection = self.database()
        verifier.verify_city_row_bounds(connection, [self.CITY])
        with mock.patch.object(verifier, "MAX_CANDIDATE_ROWS", 0):
            with self.assertRaisesRegex(verifier.VerificationError, "rows a day, above 0"):
                verifier.verify_city_row_bounds(connection, [self.CITY])

    def test_a_city_network_the_shared_roles_do_not_name_fails_closed(self) -> None:
        self.write_transit([self.rail, dict(self.urban, sourceId="city-metro")])
        with self.assertRaisesRegex(builder.BuildError, "shared pack roles say otherwise"):
            self.build()
        self.write_transit([dict(self.rail, sourceId=self.CITY), self.urban])
        with self.assertRaisesRegex(builder.BuildError, "shared pack roles say otherwise"):
            self.build()

    def test_a_city_network_holds_metro_tram_and_funicular_routes_only(self) -> None:
        city = self.temp / "gtfs-transit" / f"{self.CITY}.zip"
        with zipfile.ZipFile(city) as archive:
            members = {name: archive.read(name) for name in archive.namelist()}
        members["routes.txt"] = members["routes.txt"].replace(b",12\n", b",3\n")
        with zipfile.ZipFile(city, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, content in members.items():
                archive.writestr(name, content)
        self.write_transit([self.rail, dict(self.urban, sha256=hashlib.sha256(city.read_bytes()).hexdigest())])
        with self.assertRaisesRegex(builder.BuildError, "no metro, tram or funicular"):
            self.build()

    def test_an_unreviewed_kind_fails_closed(self) -> None:
        self.write_transit([self.rail, dict(self.urban, kind="ferry")])
        with self.assertRaisesRegex(builder.BuildError, "unreviewed kind 'ferry'"):
            self.build()

    def test_a_gateway_source_needs_an_https_copy_of_the_same_bytes(self) -> None:
        gateway = dict(self.rail, sourceUrl="http://example.test/feed.zip", accessMode="GATEWAY_ONLY")
        self.sources[-1].update(sourceUrl=gateway["sourceUrl"], accessMode="GATEWAY_ONLY")
        self.policy.write_text(json.dumps({"schemaVersion": 1, "sources": self.sources}), encoding="utf-8")
        self.write_transit([gateway, self.urban])
        with self.assertRaisesRegex(builder.BuildError, "corroboratedBy"):
            self.build()
        self.write_transit([dict(gateway, acquisition={"downloadSha256": "a" * 64, "corroboratedBy": {"url": "https://mirror.test/feed.zip", "sha256": "a" * 64}}), self.urban])
        self.build()
        self.verify()
        # A cleartext URL is never an OPEN_DIRECT source.
        self.sources[-1].update(accessMode="OPEN_DIRECT")
        self.policy.write_text(json.dumps({"schemaVersion": 1, "sources": self.sources}), encoding="utf-8")
        with self.assertRaisesRegex(builder.BuildError, "not an authorised HTTPS direct"):
            self.build()


class UrbanStopsContractTest(unittest.TestCase):
    """contracts/shared-rules/v1/urban-stops-v1.json: the names and walks the index builder gives the city stops, the
    cases Android's UrbanStopsContractTest runs."""

    RULES = json.loads((ROOT / "contracts/shared-rules/v1/urban-stops-v1.json").read_text(encoding="utf-8"))

    def test_every_shared_stop_name_is_shown_as_the_rule_says(self) -> None:
        self.assertTrue(self.RULES["stopNames"])
        for case in self.RULES["stopNames"]:
            self.assertEqual(case["shown"], builder.stop_name_display(case["published"]), case["why"])

    def test_every_shared_walk_takes_the_shared_minutes(self) -> None:
        self.assertTrue(self.RULES["walks"])
        for case in self.RULES["walks"]:
            self.assertEqual(case["minutes"], builder.walk_minutes(case["meters"]), case["why"])

    def test_the_walk_constants_are_the_kotlin_ones(self) -> None:
        provider = (ROOT / "app/src/main/java/it/binario/app/data/journey/EmbeddedGtfsJourneyProvider.kt").read_text(encoding="utf-8")
        self.assertIn("val maxWalkMeters: Int = SharedData.UrbanStops.MAXIMUM_WALK_METERS,", provider)
        self.assertIn("val walkingSpeedKmh: Double = SharedData.UrbanStops.WALKING_SPEED_KMH,", provider)
        self.assertIn("val walkMarginMinutes: Int = SharedData.UrbanStops.WALK_MARGIN_MINUTES,", provider)
        self.assertIn("private val SAME_PLACE_METERS: Double = SharedData.UrbanStops.SAME_PLACE_METERS.toDouble()", provider)
        self.assertIn("Math.ceil(meters / (walkingSpeedKmh * 1000.0 / 60.0)).toInt() + walkMarginMinutes", provider)

    def test_a_place_is_one_name_within_the_same_place_distance(self) -> None:
        stops = {
            "a": {"stop_id": "a", "stop_name": "Duomo", "stop_lat": "45.0", "stop_lon": "9.0"},
            "b": {"stop_id": "b", "stop_name": "DUOMO", "stop_lat": "45.001", "stop_lon": "9.0"},
            "c": {"stop_id": "c", "stop_name": "Duomo", "stop_lat": "45.01", "stop_lon": "9.0"},
            "d": {"stop_id": "d", "stop_name": "Duomo", "stop_lat": "45.0105", "stop_lon": "9.0", "parent_station": "p"},
            "p": {"stop_id": "p", "stop_name": "Duomo station", "stop_lat": "45.0105", "stop_lon": "9.0"},
        }
        self.assertEqual({"a": "x:name:duomo", "b": "x:name:duomo", "c": "x:name:duomo#2", "d": "x:group:p", "p": "x:group:p"},
                         builder.urban_places("x", stops))


class NationalPacksIndexTest(unittest.TestCase):
    """The shipped index carries the packs Android plans with: Trenitalia's and Italo's stops on the stations of their
    codes, their coaches as bus routes, the railway packs of gtfs-transit/, and the policy lists exactly them."""

    INDEX = ROOT / "ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib"
    METADATA = ROOT / "ios/Resources/binario_gtfs_schedule_v1.metadata.json"
    POLICY = ROOT / "ios/Resources/binario_gtfs_source_policy_v1.json"

    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory(prefix="ios-national-")
        database = Path(cls.temporary.name) / "schedule.sqlite"
        database.write_bytes(zlib.decompress(cls.INDEX.read_bytes()))
        cls.connection = sqlite3.connect(f"file:{database}?mode=ro&immutable=1", uri=True)
        cls.metadata = json.loads(cls.METADATA.read_text(encoding="utf-8"))

    @classmethod
    def tearDownClass(cls) -> None:
        cls.connection.close()
        cls.temporary.cleanup()

    def bound(self, source_id: str) -> dict[str, str | None]:
        return dict(self.connection.execute(
            "SELECT s.external_id, s.catalog_station_id FROM stop s JOIN pack p ON p.id = s.pack_id WHERE p.source_id = ?",
            (source_id,)).fetchall())

    def test_every_android_pack_of_every_build_is_indexed_and_reviewed(self) -> None:
        core = json.loads((ROOT / "app/src/main/assets/gtfs/manifest.json").read_text(encoding="utf-8"))["packs"]
        transit = json.loads((ROOT / "app/src/main/assets/gtfs-transit/manifest.json").read_text(encoding="utf-8"))["packs"]
        expected = {pack["sourceId"] for pack in core} | {pack["sourceId"] for pack in transit}
        packs = {row[0] for row in self.connection.execute("SELECT source_id FROM pack")}
        self.assertEqual(expected, packs)
        self.assertEqual(expected, set(self.metadata["packRowCounts"]))
        policy = json.loads(self.POLICY.read_text(encoding="utf-8"))
        self.assertEqual(expected, {source["sourceId"] for source in policy["sources"]})
        self.assertEqual([], policy["androidOnlySources"])
        self.assertTrue(all(self.metadata["packRowCounts"][source_id]["trip"] > 0 for source_id in expected))

    def test_trenitalia_and_italo_stops_are_the_stations_of_their_codes(self) -> None:
        codes = builder.android_station_codes.load(SHIPPED_CATALOG)
        matcher, _ = builder.load_catalog(SHIPPED_CATALOG)
        for source_id, asset in (("trenitalia-gtfs", "trenitalia-gtfs.zip"), ("italo-gtfs", "italo-gtfs.zip")):
            with zipfile.ZipFile(ROOT / "app/src/main/assets/gtfs" / asset) as pack:
                stops = list(csv.DictReader(io.TextIOWrapper(pack.open("stops.txt"), encoding="utf-8-sig")))
            bound = self.bound(source_id)
            scheme = builder.android_station_codes.PACK_SCHEMES[source_id]
            checked = 0
            for row in stops:
                if row["stop_id"] not in bound:
                    continue  # a parent station no trip calls at
                station = codes.resolve(scheme, row["stop_code"], float(row["stop_lat"]), float(row["stop_lon"]), row["stop_name"])
                if station is not None:
                    self.assertEqual(matcher.by_code(station).station_id, bound[row["stop_id"]], row["stop_id"])
                    checked += 1
            self.assertEqual(self.metadata["filter"]["stopsBoundByStationCode"][source_id], checked)
        trenitalia = {row[0]: row[1] for row in self.connection.execute(
            "SELECT s.name, s.catalog_station_id FROM stop s JOIN pack p ON p.id = s.pack_id WHERE p.source_id = 'trenitalia-gtfs'")}
        self.assertEqual("S05043", trenitalia["BOLOGNA C.LE"])
        self.assertEqual("S06421", trenitalia["FIRENZE S.MARIA NOVELLA"])
        italo = {row[0]: row[1] for row in self.connection.execute(
            "SELECT s.name, s.catalog_station_id FROM stop s JOIN pack p ON p.id = s.pack_id WHERE p.source_id = 'italo-gtfs'")}
        self.assertEqual("S02581", italo["Padua"])
        self.assertEqual("S01700", italo["Milano Centrale"])

    def test_the_operators_coaches_are_bus_routes(self) -> None:
        buses = dict(self.connection.execute(
            "SELECT p.source_id, count(*) FROM trip t JOIN route r ON r.id = t.route_id JOIN pack p ON p.id = t.pack_id "
            "WHERE r.route_type = 3 GROUP BY p.source_id").fetchall())
        self.assertEqual({"trenitalia-gtfs", "italo-gtfs"}, set(buses))
        self.assertEqual(set(builder.SCHEDULED_COACH_PACKS), set(buses))
        self.assertEqual({("0083_BUS", 3)}, set(self.connection.execute(
            "SELECT r.short_name, r.route_type FROM route r JOIN pack p ON p.id = r.pack_id "
            "WHERE p.source_id = 'trenitalia-gtfs' AND r.route_type = 3").fetchall()))
        # Italo's bikes stay unknown (BIKES_VALUE_NOT_SHOWN).
        self.assertEqual([(None,)], self.connection.execute(
            "SELECT DISTINCT t.bikes_allowed FROM trip t JOIN pack p ON p.id = t.pack_id WHERE p.source_id = 'italo-gtfs'").fetchall())

    def test_the_regional_railways_of_gtfs_transit_reach_their_stations(self) -> None:
        self.assertEqual(sorted({"fal-basilicata-puglia", "ferrotramviaria-bari-barletta", "fdg-gargano", "micotra-udine-villach",
                                 "amt-genova-casella-gtfs"}), self.metadata["filter"]["transitRailPacks"])
        for source_id in self.metadata["filter"]["transitRailPacks"]:
            with self.subTest(source=source_id):
                self.assertTrue(any(station is not None for station in self.bound(source_id).values()))

    def test_the_city_networks_are_indexed_whole_with_their_walks(self) -> None:
        self.assertEqual(sorted({"milano-atm-gtfs", "genova-amt-gtfs", "firenze-gest-gtfs", "arst-metro-gtfs", "marconi-express-gtfs"}),
                         self.metadata["filter"]["transitUrbanPacks"])
        for source_id in self.metadata["filter"]["transitUrbanPacks"]:
            with self.subTest(source=source_id):
                self.assertEqual({None}, set(self.bound(source_id).values()), "a city stop is never a catalogue station")
        # The Marconi Express (monorail, a metro line): Bologna Centrale and Bologna Aeroporto are a short walk from its
        # two ends, as on Android (airport-routes-v1.json).
        walks = dict(((row[0], row[1]), row[2]) for row in self.connection.execute(
            "SELECT w.catalog_station_id, s.name, w.minutes FROM urban_walk w JOIN stop s ON s.id = w.stop_id "
            "JOIN pack p ON p.id = s.pack_id WHERE p.source_id = 'marconi-express-gtfs'"))
        self.assertEqual(4, walks[("S05043", "Bologna Centrale FS")])
        self.assertEqual(5, walks[("NC000683", "Bologna Aeroporto")])
        self.assertEqual({(12,)}, set(self.connection.execute(
            "SELECT r.route_type FROM route r JOIN pack p ON p.id = r.pack_id WHERE p.source_id = 'marconi-express-gtfs'")))
        # Milan's metro lines (route type 1) and trams (0), its stop names as Android shows them.
        self.assertEqual({(0,), (1,)}, set(self.connection.execute(
            "SELECT DISTINCT r.route_type FROM route r JOIN pack p ON p.id = r.pack_id WHERE p.source_id = 'milano-atm-gtfs'")))
        self.assertIn(("Duomo M1", "M"), self.connection.execute(
            "SELECT s.name, u.modes FROM urban_stop u JOIN stop s ON s.id = u.stop_id JOIN pack p ON p.id = s.pack_id "
            "WHERE p.source_id = 'milano-atm-gtfs'").fetchall())



class PersonalIndexTest(unittest.TestCase):
    """The personal index of the iPhone's Personal configuration (ios/Personal), as Android's personal flavour: the
    public packs and the city networks of gtfs-personal/, reviewed in their own policy; Cotral's railway, whose
    plain-HTTP origin has no HTTPS copy recorded, stays on Android. The public index carries none of them."""

    DIRECTORY = ROOT / "ios/Personal"
    PUBLIC_POLICY = ROOT / "ios/Resources/binario_gtfs_source_policy_v1.json"

    def test_the_personal_policy_is_the_public_one_and_the_personal_packs(self) -> None:
        manifest = json.loads((ROOT / "app/src/personal/assets/gtfs-personal/manifest.json").read_text(encoding="utf-8"))
        personal = json.loads((self.DIRECTORY / "binario_gtfs_source_policy_personal_v1.json").read_text(encoding="utf-8"))
        public = json.loads(self.PUBLIC_POLICY.read_text(encoding="utf-8"))
        personal_ids = {source["sourceId"] for source in personal["sources"]}
        public_ids = {source["sourceId"] for source in public["sources"]}
        added = personal_ids - public_ids
        self.assertLessEqual(public_ids, personal_ids)
        self.assertEqual(builder.PERSONAL_INDEXED_PACK_COUNT, len(added))
        self.assertLessEqual(added, set(builder.URBAN_PACKS))
        left = {source["sourceId"] for source in personal["androidOnlySources"]}
        self.assertEqual({pack["sourceId"] for pack in manifest["packs"]}, added | left)
        self.assertEqual({"cotral-ferro-gtfs"}, left)
        for source in public["sources"]:
            self.assertIn(source, personal["sources"])

    def test_the_personal_index_is_verified_and_the_public_one_has_no_personal_pack(self) -> None:
        metadata = json.loads((self.DIRECTORY / "binario_gtfs_schedule_personal_v1.metadata.json").read_text(encoding="utf-8"))
        self.assertEqual(sorted({"gtt-gtfs", "napoli-anm-gtfs", "roma-mobilita-gtfs", "venezia-actv-gtfs"}),
                         metadata["filter"]["personalPacks"])
        result = verifier.verify(
            self.DIRECTORY / "binario_gtfs_schedule_personal_v1.sqlite.zlib",
            self.DIRECTORY / "binario_gtfs_schedule_personal_v1.metadata.json",
            source_policy_path=self.DIRECTORY / "binario_gtfs_source_policy_personal_v1.json",
            max_compressed_bytes=verifier.DEFAULT_MAX_COMPRESSED_BYTES,
            max_database_bytes=verifier.DEFAULT_MAX_DATABASE_BYTES,
            personal=True,
        )
        self.assertEqual(builder.INDEXED_PACK_COUNT + builder.PERSONAL_INDEXED_PACK_COUNT, result["rowCounts"]["pack"])
        public = json.loads((ROOT / "ios/Resources/binario_gtfs_schedule_v1.metadata.json").read_text(encoding="utf-8"))
        self.assertNotIn("personalPacks", public["filter"])
        with self.assertRaisesRegex(verifier.VerificationError, "personal packs"):
            verifier.verify(self.DIRECTORY / "binario_gtfs_schedule_personal_v1.sqlite.zlib",
                            self.DIRECTORY / "binario_gtfs_schedule_personal_v1.metadata.json",
                            source_policy_path=self.DIRECTORY / "binario_gtfs_source_policy_personal_v1.json",
                            max_compressed_bytes=verifier.DEFAULT_MAX_COMPRESSED_BYTES,
                            max_database_bytes=verifier.DEFAULT_MAX_DATABASE_BYTES)

    def test_only_the_personal_configuration_copies_the_personal_index(self) -> None:
        project = (ROOT / "ios/project.yml").read_text(encoding="utf-8")
        import yaml  # requirements-ci.txt
        spec = yaml.safe_load(project)
        self.assertEqual({"Debug": "debug", "Release": "release", "Personal": "debug"}, spec["configs"])
        app = spec["targets"]["Binario"]
        self.assertEqual("DEBUG BINARIO_PERSONAL_SOURCES", app["settings"]["configs"]["Personal"]["SWIFT_ACTIVE_COMPILATION_CONDITIONS"])
        self.assertNotIn("BINARIO_PERSONAL_SOURCES", json.dumps(app["settings"]["base"]) + json.dumps(app["settings"]["configs"]["debug"]))
        script = app["postBuildScripts"][0]["script"]
        self.assertIn('if [ "$CONFIGURATION" != "Personal" ]; then exit 0; fi', script)
        self.assertNotIn("${", script)
        for target in spec["targets"].values():
            for source in target.get("sources", []):
                path = source["path"] if isinstance(source, dict) else source
                self.assertNotIn("Personal", path)
        self.assertFalse(any("personal" in path.name for path in (ROOT / "ios/Resources").iterdir()))
        self.assertEqual("Personal", spec["schemes"]["Binario Personal"]["archive"]["config"])
        swift = (ROOT / "ios/BinarioApp/PersonalTimetable.swift").read_text(encoding="utf-8")
        self.assertIn("#if BINARIO_PERSONAL_SOURCES", swift)
        for name in ("binario_gtfs_schedule_personal_v1", "binario_gtfs_source_policy_personal_v1"):
            self.assertIn(name, swift)
            self.assertIn(name, script)

if __name__ == "__main__":
    unittest.main()
