#!/usr/bin/env python3
"""Offline tests of the GTFS-Realtime and GBFS probes of tools/transit_source_discovery.py."""
from __future__ import annotations

import importlib.util
import io
import tempfile
import unittest
import urllib.error
import zipfile
from contextlib import redirect_stdout
from email.message import Message
from pathlib import Path
from unittest import mock

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

    def test_netex_summary_uses_the_converter_guard_and_escapes_feed_text(self) -> None:
        import tempfile

        directory = Path(tempfile.mkdtemp(prefix="discovery-netex-"))
        hostile = directory / "hostile.xml"
        hostile.write_text('<?xml version="1.0"?><!--' + " " * 70000 + '--><!DOCTYPE x [<!ENTITY a "A">]><r>&a;</r>', encoding="utf-8")
        with redirect_stdout(io.StringIO()), self.assertRaisesRegex(Exception, "DOCTYPE/ENTITY"):
            DISCOVERY.summarize_netex(hostile)
        output = io.StringIO()
        with redirect_stdout(output):
            DISCOVERY.emit("agency x\n::warning::injected\rBINARIO_FILE a 1 b")
        self.assertEqual(1, len(output.getvalue().splitlines()))

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


def alert_entity(entity_id: str, route_id: str) -> bytes:
    informed = field(5, field(2, route_id))
    header = field(10, field(1, field(1, "Lavori") + field(2, "it")))
    return field(2, field(1, entity_id) + field(5, informed + header))


def write_static(folder: Path, name: str = "static.zip") -> Path:
    path = folder / name
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("routes.txt", "route_id,route_short_name,route_type\nR1,C3,2\n")
        archive.writestr("trips.txt", "route_id,service_id,trip_id,trip_short_name\nR1,S,T1,86012\nR1,S,T2,6104\n")
        archive.writestr("stops.txt", "stop_id,stop_name\nS1,Ventimiglia\nS2,Menton\n")
        archive.writestr(
            "stop_times.txt",
            "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n"
            "T1,08:00:00,08:00:00,S1,1\nT1,08:10:00,08:10:00,S2,2\nT2,09:00:00,09:00:00,S2,1\n",
        )
    return path


class GtfsRealtimeAgainstStaticTest(unittest.TestCase):
    """``gtfsrt:URL|gtfs:REF``: synthetic protobuf bytes matched against a synthetic static GTFS."""

    def setUp(self) -> None:
        DISCOVERY.reset_run_state()
        self.root = Path(tempfile.mkdtemp(prefix="discovery-rt-"))
        self.out = self.root / "out"
        self.out.mkdir()
        write_static(self.root)
        self.payload = feed(
            # Stop 1 joins (id and sequence), stop 9 does not (sequence not in the trip), stop 2 joins.
            trip_update_entity("e1", "T1", "R1", [(1, "S1", 60), (9, "S1", 60), (2, "S2", 120)]),
            # A different trip_id whose digits are a train number of the static feed.
            trip_update_entity("e2", "FR:6104:20261103", "", [(1, "X9", 0)]),
            alert_entity("a1", "R1"),
        )

    def fake_fetch(self, url, *, limit, method="GET", accept="*/*", destination=None):
        self.requests.append(url)
        if destination is not None:
            write_static(destination.parent, destination.name)
            return 200, url, {}, destination, None
        return 200, url, {}, self.payload, None

    def probe(self, refs: list[str]) -> str:
        self.requests: list[str] = []
        output = io.StringIO()
        with mock.patch.object(DISCOVERY, "fetch", self.fake_fetch), redirect_stdout(output):
            DISCOVERY.probe_gtfsrt_against("https://rt.example/feed.pb", refs, self.out, now=1_793_000_090, root=self.root)
        return output.getvalue()

    def test_matches_trips_stops_and_alerts_of_a_repository_zip(self) -> None:
        text = self.probe(["static.zip"])
        self.assertIn("age=60s", text)
        self.assertIn("kinds={'alert': 1, 'trip_update': 2}", text)
        self.assertIn("vs gtfs:static.zip trip_ids=1/2 matched_start_dates={'20261103': 1} matched_by_route={'C3': 1}", text)
        self.assertIn("trips_with_attachable_stops=1/1 stop_updates_attachable=2/3 stop_ids_in_static=3/4", text)
        self.assertIn("unmatched_with_train_number_token=1/1", text)
        self.assertIn("alerts_touching_static=1/1 informed_routes=1/1", text)
        self.assertIn("trip 01 trip_update trip_id=T1 route_id=R1 start_date=20261103", text)
        self.assertIn("gtfs:static.zip=yes(route=C3 attachable=2/3)", text)
        self.assertIn("trip_id=FR:6104:20261103", text)
        self.assertIn("gtfs:static.zip=no(train_number_token=6104)", text)
        self.assertIn("RTSAMPLE gtfs:static.zip", text)
        self.assertEqual(["https://rt.example/feed.pb"], self.requests)

    def test_a_static_url_and_the_feed_are_downloaded_once_per_run(self) -> None:
        self.requests = []
        token = "gtfsrt:https://rt.example/feed.pb|gtfs:https://static.example/zou.zip"
        output = io.StringIO()
        with mock.patch.object(DISCOVERY, "fetch", self.fake_fetch), redirect_stdout(output):
            DISCOVERY.run([token, token, "gtfsrt:https://rt.example/feed.pb|rtstatic:https://x"], self.out, keep=False)
        self.assertEqual(["https://rt.example/feed.pb", "https://static.example/zou.zip"], self.requests)
        self.assertEqual(2, output.getvalue().count("vs gtfs:https://static.example/zou.zip trip_ids=1/2"))
        self.assertIn("SKIP token non riconosciuto", output.getvalue())
        self.assertEqual([], list((self.out / "archives").iterdir()))

    def test_only_zip_files_inside_the_repository_are_read(self) -> None:
        outside = self.root.parent / (self.root.name + "-outside.zip")
        write_static(self.root.parent, outside.name)
        (self.root / "notes.txt").write_text("x", encoding="utf-8")
        output = io.StringIO()
        with redirect_stdout(output):
            for ref in (f"../{outside.name}", "notes.txt", "missing.zip"):
                self.assertEqual((None, None), DISCOVERY.resolve_static(ref, self.out, root=self.root))
        self.assertEqual(3, output.getvalue().count("percorso non ammesso"))
        outside.unlink()

    def test_stop_rule_follows_the_app(self) -> None:
        rows = [("S1", 1), ("S2", 2)]
        self.assertTrue(DISCOVERY.attachable_stop({"stop_id": "S1", "stop_sequence": 1, "arrival": {"delay": 0}}, rows))
        self.assertTrue(DISCOVERY.attachable_stop({"stop_id": "S2", "departure": {"time": 1}}, rows))
        self.assertTrue(DISCOVERY.attachable_stop({"stop_sequence": 2, "arrival": {"delay": 5}}, rows))
        self.assertFalse(DISCOVERY.attachable_stop({"stop_id": "S1", "stop_sequence": 2, "arrival": {"delay": 5}}, rows))
        self.assertFalse(DISCOVERY.attachable_stop({"stop_id": "S1", "stop_sequence": 1}, rows))
        self.assertFalse(DISCOVERY.attachable_stop({"stop_id": "S1", "stop_sequence": 1, "schedule_relationship": 1, "arrival": {"delay": 0}}, rows))
        self.assertEqual({"6104", "86012"}, DISCOVERY.number_tokens("OCESN6104:086012:20261103:87756056"))

    def test_sample_takes_known_trips_first_and_spreads_routes(self) -> None:
        rows = [
            {"trip_id": f"bus{i}", "route_id": "B", "start_date": "", "kind": "trip_update", "stops": []} for i in range(30)
        ] + [{"trip_id": "T1", "route_id": "R1", "start_date": "", "kind": "trip_update", "stops": []},
             {"trip_id": "x", "route_id": "Z", "start_date": "", "kind": "trip_update", "stops": []}]
        reference = DISCOVERY.StaticReference("gtfs:s", {"R1": ("2", "C3")}, {"T1": "R1"}, set())
        sample = DISCOVERY.realtime_trip_sample(rows, [reference])
        self.assertEqual(20, len(sample))
        self.assertEqual("T1", sample[0]["trip_id"])
        self.assertIn("x", [row["trip_id"] for row in sample[:3]])


class RefusedHostTest(unittest.TestCase):
    def setUp(self) -> None:
        DISCOVERY.reset_run_state()

    def test_a_host_that_answers_429_is_not_contacted_again(self) -> None:
        calls: list[str] = []

        def refuse(request, timeout):
            calls.append(request.full_url)
            self.assertNotIn("Mozilla", request.get_header("User-agent"))
            raise urllib.error.HTTPError(request.full_url, 429, "Too Many Requests", Message(), None)

        with mock.patch.object(DISCOVERY.urllib.request, "urlopen", refuse), redirect_stdout(io.StringIO()):
            first = DISCOVERY.fetch("https://proxy.example/a.pb", limit=10)
            second = DISCOVERY.fetch("https://proxy.example/b.pb", limit=10)
            other = DISCOVERY.fetch("https://other.example/c.pb", limit=10)
        self.assertEqual(429, first[0])
        self.assertIsNone(second[0])
        self.assertIn("403/429", second[4])
        self.assertEqual(429, other[0])
        self.assertEqual(["https://proxy.example/a.pb", "https://other.example/c.pb"], calls)


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



class LicenceProbeTest(unittest.TestCase):
    def run_probe(self, payload: object) -> str:
        output = io.StringIO()
        with redirect_stdout(output):
            DISCOVERY.probe_licence("https://x/api", label="demo-gtfs", fetch_json=lambda url: (200, {}, payload, None))
        return output.getvalue()

    def test_prints_the_licence_of_a_socrata_view(self) -> None:
        text = self.run_probe({
            "name": "Orario Ferroviario Regionale Gtfs", "viewType": "blobby", "licenseId": "CC_40_BY",
            "license": {"name": "Creative Commons Attribution 4.0", "termsLink": "https://creativecommons.org/licenses/by/4.0/"},
            "attribution": "Regione Lombardia", "rowsUpdatedAt": 1_794_000_000, "columns": [{"name": "x" * 9000}],
        })
        self.assertIn("LICENCE demo-gtfs https://x/api status=200", text)
        self.assertIn("licenseId=CC_40_BY | license=Creative Commons Attribution 4.0 | terms=https://creativecommons.org/licenses/by/4.0/", text)
        self.assertIn("attribution=Regione Lombardia", text)
        self.assertIn("rowsUpdatedAt=2026-11-06", text)
        self.assertNotIn("x" * 100, text)

    def test_prints_the_licence_of_ckan_show_and_search(self) -> None:
        dataset = {"name": "rt-oraritb", "title": "Orari TPL", "license_id": "cc-by", "license_title": "CC BY 4.0",
                   "license_url": "https://creativecommons.org/licenses/by/4.0/", "organization": {"title": "Regione Toscana"},
                   "resources": [{"format": "GTFS", "name": "tft", "url": "https://x/tft.gtfs"}]}
        for payload in ({"result": dataset}, {"result": {"count": 1, "results": [dataset]}}):
            text = self.run_probe(payload)
            self.assertIn("ckan rt-oraritb | Orari TPL | licence_id=cc-by | licence_title=CC BY 4.0", text)
            self.assertIn("org=Regione Toscana", text)
            self.assertIn("resource GTFS | tft | https://x/tft.gtfs", text)
        self.assertIn("nessun dataset", self.run_probe({"result": {"count": 0, "results": []}}))
        self.assertIn("error=HTTP 404", self.run_probe_error())

    def run_probe_error(self) -> str:
        output = io.StringIO()
        with redirect_stdout(output):
            DISCOVERY.probe_licence("https://x/api", fetch_json=lambda url: (404, {}, None, "HTTP 404"))
        return output.getvalue()

    def test_the_licences_scan_reads_every_registered_portal_once(self) -> None:
        seen: list[str] = []
        with redirect_stdout(io.StringIO()):
            DISCOVERY.scan_licences(fetch_json=lambda url: (seen.append(url), (200, {}, {}, None))[1])
        self.assertEqual(list(DISCOVERY.LICENCE_METADATA.values()), seen)
        self.assertIn("lombardia-trenord-gtfs", DISCOVERY.LICENCE_METADATA)
        self.assertTrue(all(url.startswith("https://") for url in seen))

class NextJsonProbeTest(unittest.TestCase):
    def test_prints_the_whole_page_props_in_numbered_escaped_parts(self) -> None:
        page = {"props": {"pageProps": {"__namespaces": {"x": "drop me"}, "enumerations": {"DATA_CATEGORY": ["a" * 4000, "::warning::x\nline"]}}},
                "query": {"type": "Dataset"}}
        html = ('<html><script id="__NEXT_DATA__" type="application/json">' + __import__("json").dumps(page) + "</script></html>").encode()
        output = io.StringIO()
        with redirect_stdout(output):
            DISCOVERY.probe_nextjson("https://x/it/catalog/Dataset", fetch_page=lambda url: (200, url, {}, html, None))
        lines = output.getvalue().splitlines()
        parts = [line[len("  NJ 001 "):] for line in lines if line.startswith("  NJ ")]
        self.assertIn("parts=2", output.getvalue())
        joined = "".join(parts)
        self.assertNotIn("drop me", joined)
        self.assertIn('"query": {"type": "Dataset"}', joined)
        self.assertTrue(all(not line.startswith("::") for line in lines))


if __name__ == "__main__":
    unittest.main()
