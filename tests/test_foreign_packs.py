#!/usr/bin/env python3
"""Offline tests of the foreign-pack rules, the pin schema and the release ledger."""
from __future__ import annotations

import copy
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

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


if __name__ == "__main__":
    unittest.main()
