#!/usr/bin/env python3
"""Offline tests of the foreign-pack rules, the pin schema and the release ledger."""
from __future__ import annotations

import copy
import csv
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
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "tests/fixtures/foreign_packs_v1.sample.json"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


rules = load_module("foreign_pack_rules", ROOT / "tools/foreign_pack_rules.py")
verifier = load_module("verify_foreign_packs", ROOT / "tools/verify_foreign_packs.py")
generator = load_module("build_foreign_packs", ROOT / "tools/build_foreign_packs.py")
packs = sys.modules["build_gtfs_packs"]
ios_builder = sys.modules["build_ios_gtfs_schedule"]


def sample() -> dict:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def pack(payload: dict, source_id: str) -> dict:
    return next(item for item in payload["packs"] if item["sourceId"] == source_id)


class ForeignPackRulesTest(unittest.TestCase):
    def test_licence_gate_confirms_only_recorded_terms(self) -> None:
        self.assertTrue(rules.licence_confirmed({"licence": "CC BY 4.0"}))
        self.assertFalse(rules.licence_confirmed({"licence": "Da confermare dal titolare"}))
        self.assertFalse(rules.licence_confirmed({"licence": "da confermare dal titolare (in attesa)"}))
        self.assertFalse(rules.licence_confirmed({"licence": ""}))
        self.assertFalse(rules.licence_confirmed({"licence": None}))
        self.assertTrue(rules.is_border_pack({"sourceId": "oebb-gtfs", "licence": "CC BY 4.0"}))
        self.assertFalse(rules.is_border_pack({"sourceId": "trenitalia-gtfs", "licence": "CC BY 4.0"}))
        self.assertFalse(rules.is_border_pack({"sourceId": "ch-otd-gtfs", "licence": "Da confermare dal titolare"}))

    def test_anchors_are_italian_coded_stations_with_coordinates_only(self) -> None:
        catalogue = {"stations": [
            {"id": "A", "trenitaliaLocationId": "830001", "latitude": 46.0, "longitude": 11.0},
            {"id": "B", "trenitaliaLocationId": "850001", "latitude": 46.5, "longitude": 8.0},  # Swiss code
            {"id": "C", "trenitaliaLocationId": "830002", "latitude": None, "longitude": 11.0},
            {"id": "D", "trenitaliaLocationId": None, "latitude": 45.0, "longitude": 9.0},
            {"id": "E", "trenitaliaLocationId": "1830003", "latitude": 45.0, "longitude": 9.0},
            {"id": "F", "trenitaliaLocationId": "830004", "latitude": True, "longitude": 9.0},
        ]}
        anchors = rules.anchors_from_catalogue(catalogue, "0" * 64)
        self.assertEqual(anchors.points, ((46.0, 11.0),))
        self.assertEqual(anchors.count, 1)

    def test_zone_uses_own_coordinates_else_the_parent_and_haversine(self) -> None:
        anchors = rules.anchors_from_catalogue(
            {"stations": [{"trenitaliaLocationId": "830000001", "latitude": 47.0, "longitude": 11.5}]}, "0" * 64,
        )
        # 0.3 degrees of latitude: about 33.4 km.
        stops = [
            {"stop_id": "near", "stop_lat": "47.3", "stop_lon": "11.5", "parent_station": ""},
            {"stop_id": "far", "stop_lat": "47.33", "stop_lon": "11.5", "parent_station": ""},
            {"stop_id": "parent", "stop_lat": "47.1", "stop_lon": "11.5", "parent_station": ""},
            {"stop_id": "child", "stop_lat": "", "stop_lon": "", "parent_station": "parent"},
            {"stop_id": "orphan", "stop_lat": "", "stop_lon": "", "parent_station": "missing"},
        ]
        self.assertAlmostEqual(rules.haversine_km(47.0, 11.5, 47.3, 11.5), 33.36, places=1)
        self.assertEqual(rules.zone_stop_ids(stops, anchors, 35), {"near", "parent", "child"})
        self.assertEqual(rules.zone_stop_ids(stops, anchors, 30), {"parent", "child"})

    def test_tags_and_urls_are_content_addressed_under_the_repository(self) -> None:
        sha = "64764fcb68ec7f9eeb0ff1280d99e9261ddab928e701f30dd2b46b8ad9561caa"
        tag = rules.tag_for("android", "oebb-gtfs", sha)
        self.assertEqual(tag, "gtfs-full-oebb-gtfs-64764fcb68ec")
        self.assertEqual(rules.tag_for("ios", "oebb-gtfs", sha), "gtfs-ios-oebb-gtfs-64764fcb68ec")
        self.assertEqual(
            rules.url_for(tag, rules.asset_for("android", "oebb-gtfs")),
            "https://github.com/fz1101hub/binario/releases/download/gtfs-full-oebb-gtfs-64764fcb68ec/oebb-gtfs.zip",
        )


class PinSchemaTest(unittest.TestCase):
    def assertRejected(self, payload: dict, fragment: str) -> None:
        problems = verifier.pins_problems(payload)
        self.assertTrue(any(fragment in problem for problem in problems), problems)

    def test_shared_fixture_follows_the_schema_with_one_pinned_and_two_unpinned_packs(self) -> None:
        payload = sample()
        self.assertEqual(verifier.pins_problems(payload), [])
        self.assertEqual([item["borderOnly"] for item in payload["packs"]], [False, True, False])

    def test_every_mix_of_pinned_packs_is_valid(self) -> None:
        pinned = pack(sample(), "oebb-gtfs")
        for mask in range(8):
            payload = sample()
            for index, item in enumerate(payload["packs"]):
                if mask & (1 << index):
                    replacement = copy.deepcopy(pinned)
                    for platform in ("android", "ios"):
                        release = replacement[platform]
                        release["tag"] = rules.tag_for(platform, item["sourceId"], release["sha256"])
                        release["asset"] = rules.asset_for(platform, item["sourceId"])
                        release["url"] = rules.url_for(release["tag"], release["asset"])
                    replacement["sourceId"], replacement["country"] = item["sourceId"], item["country"]
                    payload["packs"][index] = replacement
                else:
                    item.update(borderOnly=False, android=None, ios=None, servedStationIds=[], zoneStationIds=[])
            self.assertEqual(verifier.pins_problems(payload), [], mask)

    def test_tag_url_and_asset_rules(self) -> None:
        payload = sample()
        pack(payload, "oebb-gtfs")["android"]["tag"] = "gtfs-full-oebb-gtfs-000000000000"
        self.assertRejected(payload, "tag")
        payload = sample()
        pack(payload, "oebb-gtfs")["android"]["url"] = "https://example.org/oebb-gtfs.zip"
        self.assertRejected(payload, "url")
        payload = sample()
        pack(payload, "oebb-gtfs")["ios"]["asset"] = "oebb-gtfs.sqlite"
        self.assertRejected(payload, "asset")
        payload = sample()
        pack(payload, "oebb-gtfs")["ios"]["database"]["userVersion"] = 3
        self.assertRejected(payload, "userVersion")

    def test_station_lists(self) -> None:
        payload = sample()
        pack(payload, "oebb-gtfs")["zoneStationIds"].append("ZZ999")
        self.assertRejected(payload, "zoneStationIds non incluso")
        payload = sample()
        pack(payload, "oebb-gtfs")["servedStationIds"].reverse()
        self.assertRejected(payload, "lista ordinata")
        payload = sample()
        pack(payload, "ch-otd-gtfs")["servedStationIds"] = ["NC000063"]
        self.assertRejected(payload, "non ha liste")

    def test_licence_gate_shape_unpinned_has_no_download_and_pinned_has_both_platforms(self) -> None:
        payload = sample()
        pack(payload, "ch-otd-gtfs")["android"] = copy.deepcopy(pack(payload, "oebb-gtfs")["android"])
        self.assertRejected(payload, "non ha pin di download")
        payload = sample()
        pack(payload, "oebb-gtfs")["ios"] = None
        self.assertRejected(payload, "ios deve avere")

    def test_radius_rules_and_pack_set(self) -> None:
        payload = sample()
        payload["borderZone"]["radiusKm"] = 30
        self.assertRejected(payload, "radiusKm")
        payload = sample()
        payload["packs"].pop()
        self.assertRejected(payload, "tutti e tre")
        payload = sample()
        payload["packs"].reverse()
        self.assertRejected(payload, "in ordine")
        payload = sample()
        payload["repository"] = "someone/else"
        self.assertRejected(payload, "repository")


class LedgerTest(unittest.TestCase):
    def ledger(self, payload: dict) -> dict:
        return {
            "schemaVersion": 1,
            "repository": rules.REPOSITORY,
            "artifacts": [
                verifier.ledger_entry(platform, source_id, release, "2026-11-22")
                for platform, source_id, release in verifier.pinned_releases(payload)
            ],
        }

    def test_ledger_covers_pins_and_is_append_only(self) -> None:
        payload = sample()
        ledger = self.ledger(payload)
        self.assertEqual(verifier.ledger_problems(ledger), [])
        self.assertEqual(verifier.ledger_coverage_problems(payload, ledger), [])
        extended = copy.deepcopy(ledger)
        older = dict(ledger["artifacts"][0], sha256="1" * 64, sizeBytes=10)
        older["tag"] = rules.tag_for("android", "oebb-gtfs", older["sha256"])
        older["url"] = rules.url_for(older["tag"], older["asset"])
        extended["artifacts"].append(older)
        self.assertEqual(verifier.ledger_problems(extended), [])
        self.assertEqual(verifier.ledger_append_only_problems(ledger, extended), [])
        self.assertTrue(verifier.ledger_append_only_problems(extended, ledger))
        changed = copy.deepcopy(extended)
        changed["artifacts"][0]["firstPinnedOn"] = "2026-11-23"
        self.assertTrue(verifier.ledger_append_only_problems(extended, changed))

    def test_ledger_must_list_every_pin(self) -> None:
        payload = sample()
        ledger = self.ledger(payload)
        ledger["artifacts"].pop()
        self.assertTrue(verifier.ledger_coverage_problems(payload, ledger))

    def test_ledger_entries_follow_the_tag_rules_and_are_unique(self) -> None:
        ledger = self.ledger(sample())
        ledger["artifacts"].append(copy.deepcopy(ledger["artifacts"][0]))
        self.assertTrue(any("ripetuto" in problem for problem in verifier.ledger_problems(ledger)))
        ledger = self.ledger(sample())
        ledger["artifacts"][0]["url"] = "https://github.com/someone/else/releases/download/x/oebb-gtfs.zip"
        self.assertTrue(verifier.ledger_problems(ledger))

    def test_cli_schema_only(self) -> None:
        self.assertEqual(verifier.main(["--schema-only", "--pins", str(FIXTURE)]), 0)
        with tempfile.TemporaryDirectory() as folder:
            broken = Path(folder) / "pins.json"
            payload = sample()
            payload["schemaVersion"] = 2
            broken.write_text(json.dumps(payload), encoding="utf-8")
            self.assertEqual(verifier.main(["--schema-only", "--pins", str(broken)]), 1)


def csv_text(header: list[str], rows: list[list[str]]) -> str:
    stream = io.StringIO()
    writer = csv.writer(stream, lineterminator="\n")
    writer.writerow(header)
    writer.writerows(rows)
    return stream.getvalue()


def write_feed(target: Path, far_name: str = "Lontano") -> None:
    """One trip from the anchor (Confine) to a station 111 km away, one trip far away only."""
    members = {
        "agency.txt": csv_text(["agency_id", "agency_name", "agency_url", "agency_timezone"], [["A", "Rail", "https://example.test", "Europe/Rome"]]),
        "routes.txt": csv_text(["route_id", "agency_id", "route_short_name", "route_type"], [["R", "A", "EC", "2"]]),
        "trips.txt": csv_text(["route_id", "service_id", "trip_id"], [["R", "W", "T1"], ["R", "W", "T2"]]),
        "stops.txt": csv_text(["stop_id", "stop_name", "stop_lat", "stop_lon"], [
            ["Z", "Confine", "46.0", "11.0"], ["F", far_name, "47.0", "11.0"], ["G", "Remoto", "47.5", "11.0"]]),
        "stop_times.txt": csv_text(["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"], [
            ["T1", "08:00:00", "08:00:00", "Z", "1"], ["T1", "09:00:00", "09:00:00", "F", "2"],
            ["T2", "10:00:00", "10:00:00", "F", "1"], ["T2", "11:00:00", "11:00:00", "G", "2"]]),
        "calendar.txt": csv_text(["service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "start_date", "end_date"],
                                 [["W", "1", "1", "1", "1", "1", "1", "1", "20260101", "20260331"]]),
    }
    with zipfile.ZipFile(target, "w") as archive:
        for name, text in members.items():
            archive.writestr(name, text)


class GeneratorTest(unittest.TestCase):
    """tools/build_foreign_packs.py on a synthetic repository: any mix of pinned packs, pins, ledger (D2)."""

    CATALOGUE = {"schemaVersion": 1, "stations": [
        {"id": "S1", "name": "Confine", "aliases": [], "railwayCode": "S1", "trenitaliaLocationId": "830000001", "latitude": 46.0, "longitude": 11.0},
        {"id": "NC1", "name": "Lontano", "aliases": [], "railwayCode": None, "trenitaliaLocationId": None, "latitude": 47.0, "longitude": 11.0},
        {"id": "NC2", "name": "Remoto", "aliases": [], "railwayCode": None, "trenitaliaLocationId": None, "latitude": 47.5, "longitude": 11.0},
    ]}

    def setUp(self) -> None:
        self.root = Path(tempfile.mkdtemp(prefix="foreign-generator-"))
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        patcher = mock.patch.dict(rules.REQUIRED_BORDER_PAIRS, {"oebb-gtfs": (("S1", "NC1", "Confine – Lontano"),)}, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)
        catalogue = self.root / rules.CATALOGUE_PATH
        catalogue.parent.mkdir(parents=True)
        catalogue.write_text(json.dumps(self.CATALOGUE), encoding="utf-8")
        assets = self.root / "app/src/main/assets/gtfs"
        assets.mkdir(parents=True)
        entries = []
        for source_id in sorted(rules.FOREIGN_PACK_COUNTRIES):
            raw = self.root / f"{source_id}.raw.zip"
            write_feed(raw)
            packs.filter_archive(raw, assets / f"{source_id}.zip", source_id, trips_run_from=date(2026, 1, 1))
            raw.unlink()
            entry = {"sourceId": source_id, "asset": f"gtfs/{source_id}.zip", "sourceUrl": f"https://example.test/{source_id}.zip",
                     "accessMode": "OPEN_DIRECT", "attribution": "Fixture", "licence": "Da confermare dal titolare",
                     "licenceEvidence": "fixture", "filter": {"kind": "fixture"}}
            entry.update(packs.whole_manifest_entry(entry))
            entries.append(entry)
        manifest = {"schemaVersion": 4, "generatedAt": "2026-01-02", "packs": entries}
        for entry in entries:
            path = assets / f"{entry['sourceId']}.zip"
            entry.update(sha256=packs.sha256_file(path), lastServiceDate=packs.service_end_from_asset(path, entry["sourceId"]).isoformat(),
                         fullServiceUntil=packs.full_service_end_from_asset(path, entry["sourceId"]).isoformat(),
                         contentSha256=packs.verify_filter_from_asset(path, entry["sourceId"]),
                         sourceArchiveSha256=json.loads(zipfile.ZipFile(path).read("binario_filter_manifest.json"))["sourceArchiveSha256"])
        packs.write_json(assets / "manifest.json", manifest)
        packs.write_sha256sums(assets / "SHA256SUMS", manifest, assets)
        self.assets = assets

    def confirm(self, *source_ids: str) -> None:
        manifest = json.loads((self.assets / "manifest.json").read_text(encoding="utf-8"))
        for entry in manifest["packs"]:
            entry["licence"] = "CC BY 4.0" if entry["sourceId"] in source_ids else "Da confermare dal titolare"
        packs.write_json(self.assets / "manifest.json", manifest)

    def generate(self) -> dict:
        """The steps of regenerate() but the base index (which needs the nine packs of the real manifest)."""
        packs.apply_border_zones(self.assets, full_pack_dir=self.root / rules.FULL_PACK_DIR, catalogue_path=self.root / rules.CATALOGUE_PATH)
        manifest = json.loads((self.assets / "manifest.json").read_text(encoding="utf-8"))
        ios_dir = self.root / rules.IOS_FULL_DIR
        for entry in manifest["packs"]:
            if "fullPack" in entry:
                ios_dir.mkdir(parents=True, exist_ok=True)
                ios_builder.build_foreign_full_database(
                    self.assets / "manifest.json", self.root / rules.CATALOGUE_PATH, entry["sourceId"],
                    ios_dir / f"{entry['sourceId']}.sqlite.zlib", ios_dir / f"{entry['sourceId']}.metadata.json",
                    full_pack_dir=self.root / rules.FULL_PACK_DIR)
        generator.write_pins_and_ledger(self.root)
        return json.loads((self.root / rules.ANDROID_PINS_PATH).read_text(encoding="utf-8"))

    def test_no_pinned_pack_writes_pins_without_downloads_and_no_ledger(self) -> None:
        pins = self.generate()
        self.assertEqual([], verifier.pins_problems(pins))
        self.assertEqual([False, False, False], [item["borderOnly"] for item in pins["packs"]])
        self.assertFalse((self.root / rules.LEDGER_PATH).exists())
        self.assertFalse((self.root / rules.FULL_PACK_DIR).exists())
        self.assertEqual((self.root / rules.ANDROID_PINS_PATH).read_bytes(), (self.root / rules.IOS_PINS_PATH).read_bytes())
        self.assertEqual([], verifier.repository_problems(self.root))

    def test_one_pinned_pack(self) -> None:
        self.confirm("oebb-gtfs")
        pins = self.generate()
        self.assertEqual([], verifier.pins_problems(pins))
        oebb = pack(pins, "oebb-gtfs")
        self.assertTrue(oebb["borderOnly"])
        self.assertEqual((["NC1", "NC2", "S1"], ["S1"]), (oebb["servedStationIds"], oebb["zoneStationIds"]))
        full = self.root / rules.FULL_PACK_DIR / "oebb-gtfs.zip"
        self.assertEqual(packs.sha256_file(full), oebb["android"]["sha256"])
        self.assertEqual(rules.tag_for("android", "oebb-gtfs", oebb["android"]["sha256"]), oebb["android"]["tag"])
        self.assertEqual(packs.sha256_file(self.root / rules.IOS_FULL_DIR / "oebb-gtfs.sqlite.zlib"), oebb["ios"]["sha256"])
        self.assertIsNone(pack(pins, "ch-otd-gtfs")["android"])
        ledger = json.loads((self.root / rules.LEDGER_PATH).read_text(encoding="utf-8"))
        self.assertEqual(["android", "ios"], [entry["platform"] for entry in ledger["artifacts"]])
        self.assertEqual("2026-01-02", ledger["artifacts"][0]["firstPinnedOn"])
        self.assertEqual([], verifier.repository_problems(self.root))
        # Idempotent: the same inputs give the same bytes.
        before = {path: (self.root / path).read_bytes() for path in (rules.ANDROID_PINS_PATH, rules.LEDGER_PATH)}
        self.generate()
        self.assertEqual(before, {path: (self.root / path).read_bytes() for path in before})
        # A one-line edit of the iPhone copy is caught.
        (self.root / rules.IOS_PINS_PATH).write_text("{}", encoding="utf-8")
        self.assertTrue(any("stessi byte" in problem for problem in verifier.repository_problems(self.root)))

    def test_every_pack_pinned_and_a_new_full_pack_appends_to_the_ledger(self) -> None:
        self.confirm(*rules.FOREIGN_PACK_COUNTRIES)
        pins = self.generate()
        self.assertEqual([True, True, True], [item["borderOnly"] for item in pins["packs"]])
        old = json.loads((self.root / rules.LEDGER_PATH).read_text(encoding="utf-8"))
        self.assertEqual(6, len(old["artifacts"]))
        # A new full pack for one country: a new pin appended, the old entries kept in place.
        raw = self.root / "new.zip"
        write_feed(raw, far_name="Lontano")
        full = self.root / rules.FULL_PACK_DIR / "sncf-gtfs.zip"
        packs.filter_archive(raw, full, "sncf-gtfs", trips_run_from=date(2026, 1, 5))
        packs.write_full_pack_sums(self.root / rules.FULL_PACK_DIR, {"packs": []})  # rewritten by the next step
        manifest = json.loads((self.assets / "manifest.json").read_text(encoding="utf-8"))
        packs.write_full_pack_sums(self.root / rules.FULL_PACK_DIR, manifest)
        pins = self.generate()
        ledger = json.loads((self.root / rules.LEDGER_PATH).read_text(encoding="utf-8"))
        self.assertEqual(old["artifacts"], ledger["artifacts"][:6])
        self.assertEqual(pack(pins, "sncf-gtfs")["android"]["tag"], ledger["artifacts"][6]["tag"])
        self.assertEqual([], verifier.ledger_append_only_problems(old, ledger))
        self.assertEqual([], verifier.repository_problems(self.root))

    def test_licence_withdrawn_restores_the_whole_pack(self) -> None:
        self.confirm("oebb-gtfs")
        self.generate()
        whole = (self.root / rules.FULL_PACK_DIR / "oebb-gtfs.zip").read_bytes()
        self.confirm()
        pins = self.generate()
        self.assertFalse(pack(pins, "oebb-gtfs")["borderOnly"])
        self.assertEqual(whole, (self.assets / "oebb-gtfs.zip").read_bytes())
        # The ledger keeps what was published; its pins stay downloadable for installed apps.
        self.assertEqual(2, len(json.loads((self.root / rules.LEDGER_PATH).read_text(encoding="utf-8"))["artifacts"]))
        shutil.rmtree(self.root / rules.IOS_FULL_DIR)
        self.assertEqual([], verifier.repository_problems(self.root))


if __name__ == "__main__":
    unittest.main()
