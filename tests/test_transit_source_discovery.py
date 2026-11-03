#!/usr/bin/env python3
"""Offline tests of the GTFS-Realtime and GBFS probes of tools/transit_source_discovery.py."""
from __future__ import annotations

import importlib.util
import io
import unittest
from contextlib import redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("transit_source_discovery", ROOT / "tools" / "transit_source_discovery.py")
assert SPEC and SPEC.loader
DISCOVERY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DISCOVERY)


def varint(value: int) -> bytes:
    value &= 0xFFFFFFFFFFFFFFFF
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def field(number: int, value: int | bytes | str) -> bytes:
    if isinstance(value, int):
        return varint(number << 3) + varint(value)
    data = value.encode() if isinstance(value, str) else value
    return varint(number << 3 | 2) + varint(len(data)) + data


def trip_update_entity(entity_id: str, trip_id: str, route_id: str, stops: list[tuple[int, str, int]], canceled: bool = False) -> bytes:
    trip = field(1, trip_id) + field(3, "20261103") + field(5, route_id) + (field(4, 3) if canceled else b"")
    updates = b"".join(
        field(2, field(1, sequence) + field(4, stop_id) + field(2, field(1, delay)) + field(3, field(1, delay)))
        for sequence, stop_id, delay in stops
    )
    return field(2, field(1, entity_id) + field(3, field(1, trip) + updates + field(4, 1_793_000_000)))


def feed(*entities: bytes) -> bytes:
    header = field(1, field(1, "2.0") + field(3, 1_793_000_030))
    return header + b"".join(entities)


class GtfsRealtimeProbeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.reference = DISCOVERY.StaticReference(
            "pack:roma-mobilita-gtfs",
            {"MEA": ("1", "MEA"), "8": ("0", "8")},
            {"1#100-1": "MEA", "2#200-3": "8"},
            {"70001", "70002"},
        )

    def test_decodes_header_trip_updates_and_negative_delays(self) -> None:
        payload = feed(
            trip_update_entity("e1", "1#100-1", "", [(3, "70001", -45), (4, "70002", 60)]),
            trip_update_entity("e2", "bus#1", "64", [(1, "99999", 120)]),
        )
        decoded = DISCOVERY.decode_feed(payload)
        self.assertEqual("2.0", decoded["header"]["gtfs_realtime_version"])
        self.assertEqual(1_793_000_030, decoded["header"]["timestamp"])
        first = decoded["entities"][0]["trip_update"]
        self.assertEqual("1#100-1", first["trip"]["trip_id"])
        self.assertEqual(-45, first["stop_time_updates"][0]["arrival"]["delay"])
        self.assertEqual("70002", first["stop_time_updates"][1]["stop_id"])

    def test_summary_matches_trips_routes_and_stops_of_a_city_pack(self) -> None:
        payload = feed(
            trip_update_entity("e1", "1#100-1", "", [(3, "70001", 30)]),
            trip_update_entity("e2", "2#200-3", "8", [(1, "70002", 0)], canceled=True),
            trip_update_entity("e3", "bus#1", "64", [(1, "99999", 120)]),
        )
        lines = DISCOVERY.summarize_realtime(DISCOVERY.decode_feed(payload), 1_793_000_090, [self.reference])
        text = "\n".join(lines)
        self.assertIn("age=60s", text)
        self.assertIn("'CANCELED': 1", text)
        self.assertIn("match pack:roma-mobilita-gtfs trips=2/3 stop_ids=2/3", text)
        self.assertIn("route id=MEA short=MEA type=1 entities=1", text)
        self.assertIn("route id=8 short=8 type=0 entities=1", text)

    def test_sample_is_a_valid_feed_with_only_the_pack_entities(self) -> None:
        payload = feed(
            trip_update_entity("e1", "1#100-1", "", [(3, "70001", 30)]),
            trip_update_entity("e3", "bus#1", "64", [(1, "99999", 120)]),
        )
        sample = DISCOVERY.realtime_sample(DISCOVERY.decode_feed(payload), self.reference)
        decoded = DISCOVERY.decode_feed(sample)
        self.assertEqual(["e1"], [entity["id"] for entity in decoded["entities"]])
        self.assertEqual("2.0", decoded["header"]["gtfs_realtime_version"])

    def test_rejects_bytes_that_are_not_a_feed(self) -> None:
        with self.assertRaises(DISCOVERY.WireError):
            DISCOVERY.decode_feed(b"<html>not protobuf</html>")
        with self.assertRaises(DISCOVERY.WireError):
            DISCOVERY.decode_feed(field(2, field(1, "no header")))


class GbfsProbeTest(unittest.TestCase):
    def test_feed_urls_of_versions_two_and_three(self) -> None:
        v2 = {"version": "2.3", "data": {"en": {"feeds": [{"name": "station_status", "url": "https://x/ss"}]}}}
        v3 = {"version": "3.0", "data": {"feeds": [{"name": "vehicle_status", "url": "https://x/vs"}]}}
        self.assertEqual(("2.3", {"station_status": "https://x/ss"}), DISCOVERY.gbfs_feed_urls(v2))
        self.assertEqual(("3.0", {"vehicle_status": "https://x/vs"}), DISCOVERY.gbfs_feed_urls(v3))

    def test_counts_docks_near_catalogue_stations(self) -> None:
        documents = {
            "https://x/gbfs.json": {"version": "2.3", "ttl": 60, "data": {"it": {"feeds": [
                {"name": "system_information", "url": "https://x/si"},
                {"name": "station_information", "url": "https://x/st"},
                {"name": "station_status", "url": "https://x/ss"},
            ]}}},
            "https://x/si": {"data": {"name": "BikeMi", "license_url": "https://example.org/licence"}},
            "https://x/st": {"data": {"stations": [
                {"station_id": "1", "name": "Centrale", "lat": 45.4862, "lon": 9.2050, "capacity": 30},
                {"station_id": "2", "name": "Lontana", "lat": 45.5200, "lon": 9.2500},
            ]}},
            "https://x/ss": {"data": {"stations": [{"station_id": "1", "num_bikes_available": 4, "num_docks_available": 20, "is_renting": True}]}},
        }
        grid = DISCOVERY.StationGrid([("Milano Centrale", 45.4859, 9.2036)])
        output = io.StringIO()
        with redirect_stdout(output):
            DISCOVERY.probe_gbfs("https://x/gbfs.json", fetch_json=lambda url: (200, {}, documents.get(url), None), grid=grid)
        text = output.getvalue()
        self.assertIn("license_url", text)
        self.assertIn("near-catalogue stations points=1/2 railway_stations=1 [Milano Centrale", text)
        self.assertIn("bikes=4 with_docks=1 renting=1", text)


class CkanProbeTest(unittest.TestCase):
    def test_lists_datasets_with_licence_and_resources(self) -> None:
        payload = {"result": {"count": 1, "results": [{
            "name": "gtfs-rt", "title": "Feed GTFS Realtime", "license_id": "cc-by", "license_title": "CC BY 4.0",
            "organization": {"title": "Comune"}, "resources": [{"format": "PB", "name": "trip updates", "url": "https://x/tu.pb"}],
        }]}}
        output = io.StringIO()
        with redirect_stdout(output):
            DISCOVERY.probe_ckan("https://x/api/3/action/package_search?q=gtfs", fetch_json=lambda url: (200, {}, payload, None))
        self.assertIn("dataset gtfs-rt | Feed GTFS Realtime | licence=cc-by CC BY 4.0 | org=Comune", output.getvalue())
        self.assertIn("resource PB | trip updates | https://x/tu.pb", output.getvalue())


if __name__ == "__main__":
    unittest.main()
