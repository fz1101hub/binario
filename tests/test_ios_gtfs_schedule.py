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
        # An eleventh, Android-only pack whose archive the iOS index never reads.
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


if __name__ == "__main__":
    unittest.main()
