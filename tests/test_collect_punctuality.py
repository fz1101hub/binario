#!/usr/bin/env python3
"""Offline tests for tools/collect_punctuality.py: a small GTFS pack, synthetic andamentoTreno answers shaped like the
app's ViaggiaTrenoRealtimeAdapterTest fixtures and a local fake ViaggiaTreno server.  Nothing here is a real train or
delay."""
from __future__ import annotations

import contextlib
import gzip
import http.server
import io
import json
import shutil
import sys
import tempfile
import threading
import unittest
import zipfile
from unittest import mock
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import build_punctuality_stats as stats  # noqa: E402
import collect_punctuality as collector  # noqa: E402

FIXTURES = ROOT / "tests/fixtures/punctuality"
DAY = date(2026, 11, 4)  # a Wednesday, after the end of summer time
NOW = datetime(2026, 11, 4, 12, 0, tzinfo=timezone.utc)  # 13:00 in Rome
MINUTE = 60_000

STATIONS = {"stations": [
    {"id": "S08409", "name": "Roma Termini", "railwayCode": "S08409", "trenitaliaLocationId": "830008409",
     "latitude": 41.9009, "longitude": 12.5018},
    # The ViaggiaTreno code is not the last five digits of the location id (GtfsStationCodeIndex).
    {"id": "S08235", "name": "Fidene", "railwayCode": "S08235", "trenitaliaLocationId": "830008225",
     "latitude": 41.9640, "longitude": 12.5300},
    {"id": "S01700", "name": "Milano Centrale", "railwayCode": "S01700", "latitude": 45.4862, "longitude": 9.2046},
    # A catalogue error: the location id points at a station 200 km away, so the derived code is used.
    {"id": "S09999", "name": "Altrove", "railwayCode": "S09999", "trenitaliaLocationId": "830006421",
     "latitude": 40.0, "longitude": 15.0},
]}

STOPS = [
    # stop_id, stop_code, name, lat, lon
    ("ROMA", "830008409", "ROMA TERMINI", 41.9010, 12.5016),
    ("FIDENE", "830008225", "FIDENE", 41.9641, 12.5301),
    ("FIRENZE", "830006421", "FIRENZE S.M.N.", 43.7765, 11.2480),
    ("MILANO", "830001700", "MILANO CENTRALE", 45.4860, 9.2040),
    ("ZURICH", "850003000", "ZUERICH HB", 47.3780, 8.5400),
]
# trip_id, route, service, number, [(stop_id, arrival, departure)]
TRIPS = [
    ("fr9558", "FR", "DAILY", "9558", [("ROMA", "07:00:00", "07:00:00"), ("FIRENZE", "08:40:00", "08:42:00"),
                                        ("MILANO", "10:10:00", "10:10:00")]),
    ("reg2001", "REG", "WEEKDAY", "2001", [("FIDENE", "09:00:00", "09:00:00"), ("ROMA", "09:40:00", "09:40:00")]),
    ("reg2003", "REG", "WEEKDAY", "2003", [("ROMA", "11:30:00", "11:30:00"), ("FIDENE", "12:20:00", "12:20:00")]),
    ("reg2005", "REG", "WEEKDAY", "2005", [("ROMA", "12:00:00", "12:00:00"), ("FIDENE", "12:41:00", "12:41:00")]),
    ("reg2007", "REG", "WEEKDAY", "2007", [("ROMA", "06:00:00", "06:00:00"), ("FIDENE", "08:59:00", "08:59:00")]),
    ("reg2009", "REG", "REMOVED", "2009", [("ROMA", "09:00:00", "09:00:00"), ("FIDENE", "10:00:00", "10:00:00")]),
    ("reg2011", "REG", "ADDED", "2011", [("ROMA", "09:00:00", "09:00:00"), ("FIDENE", "10:00:00", "10:00:00")]),
    ("icn1963", "ICN", "DAILY", "1963", [("ROMA", "22:00:00", "22:00:00"), ("MILANO", "34:30:00", "34:30:00")]),
    ("icn1965", "ICN", "DAILY", "1965", [("ROMA", "24:30:00", "24:30:00"), ("MILANO", "33:00:00", "33:00:00")]),
    ("bus", "BUS", "DAILY", "VE545", [("ROMA", "09:00:00", "09:00:00"), ("FIDENE", "10:00:00", "10:00:00")]),
    ("ec300", "EC", "DAILY", "300", [("ZURICH", "06:00:00", "06:00:00"), ("MILANO", "09:30:00", "09:30:00")]),
]


def csv_text(header: str, rows: list[tuple[Any, ...]]) -> str:
    return header + "\n" + "".join(",".join(str(value) for value in row) + "\n" for row in rows)


def write_gtfs(path: Path) -> Path:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("routes.txt", csv_text("route_id,agency_id,route_short_name,route_long_name,route_type", [
            ("FR", "T", "0083_FR", "Frecciarossa", 2), ("REG", "T", "0083_REG", "Regionale", 2),
            ("ICN", "T", "0083_ICN", "InterCityNotte", 2), ("EC", "T", "0083_EC", "Eurocity", 2),
            ("BUS", "T", "0083_BUS", "Autobus", 3),
        ]))
        archive.writestr("stops.txt", csv_text("stop_id,stop_code,stop_name,stop_lat,stop_lon,location_type", [
            (*stop, 0) for stop in STOPS
        ]))
        archive.writestr("trips.txt", csv_text("route_id,service_id,trip_id,trip_short_name", [
            (route, service, trip, number) for trip, route, service, number, _ in TRIPS
        ]))
        archive.writestr("stop_times.txt", csv_text("trip_id,arrival_time,departure_time,stop_id,stop_sequence", [
            (trip, arrival, departure, stop, sequence)
            for trip, _, _, _, calls in TRIPS for sequence, (stop, arrival, departure) in enumerate(calls, start=1)
        ]))
        archive.writestr("calendar.txt", csv_text(
            "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date", [
                ("DAILY", 1, 1, 1, 1, 1, 1, 1, 20261001, 20261231),
                ("WEEKDAY", 1, 1, 1, 1, 1, 0, 0, 20261001, 20261231),
                ("REMOVED", 1, 1, 1, 1, 1, 0, 0, 20261001, 20261231),
            ]))
        archive.writestr("calendar_dates.txt", csv_text("service_id,date,exception_type", [
            ("REMOVED", 20261104, 2), ("ADDED", 20261104, 1),
        ]))
    return path


def stop(code: str, at: int, actual: int | None = None, *, first: bool = False, kind: int = 1, **platforms: Any) -> dict[str, Any]:
    """One ``fermate`` row like the app's fixture: ``at`` minutes after midnight of DAY, ``actual`` seconds late;
    ``platforms`` sets ``binario...Descrizione`` fields by their middle part (``EffettivoArrivo="3"``)."""
    midnight = collector.midnight_millis(DAY)
    scheduled = midnight + at * MINUTE
    row: dict[str, Any] = {"id": code, "stazione": code, "actualFermataType": kind, "programmata": scheduled,
                           "partenza_teorica": scheduled, "arrivo_teorico": None if first else scheduled}
    for side in ("EffettivoArrivo", "ProgrammatoArrivo", "EffettivoPartenza", "ProgrammatoPartenza"):
        row[f"binario{side}Descrizione"] = platforms.pop(side, None)
    assert not platforms, platforms
    if actual is not None:
        row["partenzaReale" if first else "arrivoReale"] = scheduled + actual * 1000
    return row


def run(number: str = "2001", origin: str = "S08235", stops: list[dict[str, Any]] | None = None, **extra: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "numeroTreno": int(number), "idOrigine": origin, "dataPartenzaTreno": collector.midnight_millis(DAY),
        "categoria": "REG", "ritardo": 3, "nonPartito": False, "provvedimento": 0, "tipoTreno": "PG",
        "fermate": stops if stops is not None else [stop("S08235", 9 * 60, 60, first=True), stop("S08409", 9 * 60 + 40, 200)],
    }
    payload.update(extra)
    return payload


def due(number: str = "2001", origin: str = "S08235", category: str = "REG", arrival: datetime | None = None) -> collector.Due:
    moment = arrival or datetime(2026, 11, 4, 8, 40, tzinfo=timezone.utc)
    return collector.Due(DAY.isoformat(), origin, number, category, int(moment.timestamp()))


class FakeViaggiaTreno:
    """A local HTTP server answering andamentoTreno paths from a table (204 for anything else)."""

    def __init__(self, routes: dict[str, tuple[int, dict[str, str], bytes]] | None = None):
        self.routes = routes or {}
        self.requests: list[tuple[str, dict[str, str]]] = []
        fake = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802 - http.server API
                fake.requests.append((self.path, dict(self.headers.items())))
                status, headers, body = fake.routes.get(self.path, (204, {}, b""))
                self.send_response(status)
                for name, value in headers.items():
                    self.send_header(name, value)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_: Any) -> None:
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_address[1]}/infomobilita/resteasy/viaggiatreno/"

    def answer(self, train: collector.Due, payload: Any, status: int = 200, headers: dict[str, str] | None = None) -> None:
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.routes["/infomobilita/resteasy/viaggiatreno/" + collector.run_path(train)] = (status, headers or {}, body)

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


class Base(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="punctuality-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.stations_path = self.temp / "stations.json"
        self.stations_path.write_text(json.dumps(STATIONS), encoding="utf-8")
        self.gtfs = write_gtfs(self.temp / "trenitalia-gtfs.zip")
        self.codes = collector.StationCodes.load(self.stations_path)
        self.timetable = collector.load_timetable(self.gtfs, self.codes)


class StationAndTimetableTest(Base):
    def test_station_codes_follow_the_catalogue_then_the_last_five_digits(self) -> None:
        resolve = self.codes.resolve
        self.assertEqual("S08409", resolve("830008409", 41.9010, 12.5016, "ROMA TERMINI"))
        self.assertEqual("S08235", resolve("830008225", 41.9641, 12.5301, "FIDENE"))
        self.assertEqual("S01700", resolve("830001700", 45.4860, 9.2040, "MILANO CENTRALE"))  # no location id
        self.assertEqual("S06421", resolve("830006421", 43.7765, 11.2480, "FIRENZE S.M.N."))  # far: derived code
        self.assertEqual("S08235", resolve("830008225", None, None, "Fidene"))  # no coordinates: by name
        self.assertEqual("S08225", resolve("830008225", None, None, "Pc Orvieto"))
        for code in ("850003000", "83000840", "8300084090", "", "S08409"):
            with self.subTest(code=code):
                self.assertIsNone(resolve(code, None, None, ""))

    def test_a_trenord_pack_gives_numbers_after_the_line_and_its_own_viaggiatreno_codes(self) -> None:
        trenord = self.temp / "lombardia-trenord-gtfs.zip"
        with zipfile.ZipFile(trenord, "w") as archive:
            archive.writestr("routes.txt", csv_text("route_id,agency_id,route_short_name,route_long_name,route_type", [
                ("RE_11", "1", "RE11", "Mantova-Milano", 2), ("S9", "1", "S9", "Saronno-Albairate", 2),
                ("TN", "1", "Trenord (linea non specificata)", "Trenord", 2), ("BUS", "1", "AUTOBUS", "Bus", 3),
            ]))
            archive.writestr("stops.txt", csv_text("stop_id,stop_name,stop_lat,stop_lon", [
                ("S01700", "Milano Centrale", 45.486, 9.204), ("S01066", "Milano Cadorna", 45.468, 9.175),
                ("S00999", "Mantova", 45.15, 10.78), ("X1", "Deposito", 45.5, 9.2),
            ]))
            archive.writestr("trips.txt", csv_text("route_id,service_id,trip_id,trip_short_name", [
                ("RE_11", "DAILY", "t1", "RE_11 - 2153"), ("S9", "DAILY", "t2", "24901"), ("TN", "DAILY", "t3", "TN - 651"),
                ("RE_11", "DAILY", "t4", "RE_11 - 2155A"), ("RE_11", "DAILY", "t5", "RE_11 - 2157"), ("BUS", "DAILY", "t6", "B - 7"),
            ]))
            archive.writestr("stop_times.txt", csv_text("trip_id,arrival_time,departure_time,stop_id,stop_sequence", [
                ("t1", "07:00:00", "07:00:00", "S00999", 1), ("t1", "09:00:00", "09:00:00", "S01700", 2),
                ("t2", "08:00:00", "08:00:00", "S01066", 1), ("t2", "08:50:00", "08:50:00", "S01700", 2),
                ("t3", "08:10:00", "08:10:00", "S01066", 1), ("t3", "09:20:00", "09:20:00", "S00999", 2),
                ("t4", "07:00:00", "07:00:00", "S00999", 1), ("t4", "09:00:00", "09:00:00", "S01700", 2),
                ("t5", "07:00:00", "07:00:00", "X1", 1), ("t5", "09:00:00", "09:00:00", "S01700", 2),
                ("t6", "07:00:00", "07:00:00", "S01066", 1), ("t6", "08:00:00", "08:00:00", "S01700", 2),
            ]))
            # The same service id as the Trenitalia pack, with another calendar: weekends only.
            archive.writestr("calendar.txt", csv_text(
                "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date", [
                    ("DAILY", 0, 0, 0, 0, 0, 1, 1, 20261001, 20261231)]))
        both = collector.load_timetables([self.gtfs, trenord], self.codes)
        plans = {trip.number: trip for trip in both.trips if trip.service_id.startswith("lombardia-trenord-gtfs: ")}
        self.assertEqual({"2153", "24901", "651"}, set(plans))
        self.assertEqual(("RE", "S00999"), (plans["2153"].category, plans["2153"].origin))
        self.assertEqual(("S", "S01066"), (plans["24901"].category, plans["24901"].origin))
        self.assertEqual("REG", plans["651"].category)  # a line without a name is a regional train
        self.assertEqual({"lombardia-trenord-gtfs: numero non ViaggiaTreno": 1, "lombardia-trenord-gtfs: origine senza codice ViaggiaTreno": 1,
                          "lombardia-trenord-gtfs: non ferroviaria": 1, "trenitalia-gtfs: non ferroviaria": 1,
                          "trenitalia-gtfs: origine senza codice ViaggiaTreno": 1}, both.skipped)
        # Each pack keeps its own calendar: on a Wednesday the Trenitalia DAILY trains run, Trenord's weekend ones do not.
        wednesday = {due.number for due in collector.trains_on(both, DAY).values()}
        self.assertIn("9558", wednesday)
        self.assertNotIn("2153", wednesday)
        saturday = {key: due for key, due in collector.trains_on(both, date(2026, 11, 7)).items()}
        self.assertIn(("2026-11-07", "S00999", "2153"), saturday)
        self.assertEqual("andamentoTreno/S01066/24901/" + str(collector.midnight_millis(date(2026, 11, 7))),
                         collector.run_path(saturday[("2026-11-07", "S01066", "24901")]))

    def test_trenord_origin_codes_are_checked_against_the_catalogue(self) -> None:
        # Trenord stop ids are not always ViaggiaTreno codes: "Brescia" is S09999 (Piedimonte Matese in the catalogue,
        # 573 km away) and the Swiss TILO stops use codes of RFI stations elsewhere (S05301 is Buttapietra).
        stations = {"stations": STATIONS["stations"] + [
            {"id": "S01717", "name": "Brescia", "railwayCode": "S01717", "latitude": 45.532315, "longitude": 10.212828},
            {"id": "S05301", "name": "Buttapietra", "railwayCode": "S05301", "latitude": 45.338787, "longitude": 10.988059},
            {"id": "S00300", "name": "Bellinzona", "railwayCode": "S00300", "latitude": 46.1956, "longitude": 9.0293},
            {"id": "S01073", "name": "Garbagnate parco delle Groane", "railwayCode": "S01073",
             "latitude": 45.5912, "longitude": 9.0752},
            {"id": "X1", "name": "Garbagnate Milanese", "latitude": 45.5797, "longitude": 9.0798},
        ]}
        self.stations_path.write_text(json.dumps(stations), encoding="utf-8")
        codes = collector.StationCodes.load(self.stations_path)
        pack_stops = [
            ("S09999", "Brescia", 45.53252, 10.21297),  # contradicted, resolved by name and position
            ("S05301", "Lugano Paradiso", 45.98884, 8.94600),  # contradicted, nothing of that name nearby: skipped
            ("S05213", "Bellinzona", 46.19560, 9.02990),  # absent, the catalogue's Bellinzona is here: S00300
            ("S01074", "Garbagnate Milanese", 45.57970, 9.07980),  # absent, nothing coded of that name: kept
            ("S01700", "Milano Centrale", 45.48600, 9.20400),  # confirmed within 10 km: kept
            ("S08409", "Roma", 45.50000, 9.30000),  # the catalogue's S08409 is in Rome, no "Roma" here: skipped
        ]
        trenord = self.temp / "lombardia-trenord-gtfs.zip"
        with zipfile.ZipFile(trenord, "w") as archive:
            archive.writestr("routes.txt", csv_text("route_id,agency_id,route_short_name,route_long_name,route_type",
                                                    [("R", "1", "R", "Regionale", 2)]))
            archive.writestr("stops.txt", csv_text("stop_id,stop_name,stop_lat,stop_lon", pack_stops))
            archive.writestr("trips.txt", csv_text("route_id,service_id,trip_id,trip_short_name", [
                ("R", "DAILY", f"t{index}", f"R - {100 + index}") for index in range(len(pack_stops))]))
            archive.writestr("stop_times.txt", csv_text("trip_id,arrival_time,departure_time,stop_id,stop_sequence", [
                row for index, stop_row in enumerate(pack_stops) for row in (
                    (f"t{index}", "07:00:00", "07:00:00", stop_row[0], 1), (f"t{index}", "08:00:00", "08:00:00", "S01700", 2))]))
            archive.writestr("calendar.txt", csv_text(
                "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date",
                [("DAILY", 1, 1, 1, 1, 1, 1, 1, 20261001, 20261231)]))
        timetable = collector.load_timetable(trenord, codes)
        self.assertEqual({"100": "S01717", "102": "S00300", "103": "S01074", "104": "S01700"},
                         {trip.number: trip.origin for trip in timetable.trips})
        self.assertEqual({"origine Trenord smentita dal catalogo": 2}, timetable.skipped)
        # Without coordinates a code is confirmed by a word of its name, as for Trenitalia.
        self.assertEqual("S01700", codes.confirm("S01700", None, None, "MILANO CENTRALE"))
        self.assertIsNone(codes.confirm("S09999", None, None, "Brescia"))

    def test_bus_routes_foreign_origins_and_alphanumeric_numbers_are_left_out(self) -> None:
        self.assertEqual({"non ferroviaria": 1, "origine senza codice ViaggiaTreno": 1}, self.timetable.skipped)
        numbers = {trip.number for trip in self.timetable.trips}
        self.assertNotIn("300", numbers)
        self.assertNotIn("VE545", numbers)
        fr = next(trip for trip in self.timetable.trips if trip.number == "9558")
        self.assertEqual(("FR", "S08409", 7 * 3600, 10 * 3600 + 600), (fr.category, fr.origin, fr.departure, fr.arrival))

    def test_service_days_follow_calendar_and_calendar_dates(self) -> None:
        numbers = {due.number for due in collector.trains_on(self.timetable, DAY).values()}
        self.assertIn("2011", numbers)  # added by calendar_dates
        self.assertNotIn("2009", numbers)  # removed by calendar_dates
        saturday = {due.number for due in collector.trains_on(self.timetable, date(2026, 11, 7)).values()}
        self.assertNotIn("2001", saturday)
        self.assertIn("9558", saturday)
        self.assertEqual(set(), set(collector.trains_on(self.timetable, date(2027, 1, 5))))

    def test_europe_rome_midnight_and_gtfs_reference_across_daylight_saving(self) -> None:
        # The app's ViaggiaTrenoCancellationTest uses 1792101600000 for 16 October 2026 (summer time).
        self.assertEqual(1792101600000, collector.midnight_millis(date(2026, 10, 16)))
        self.assertEqual(int(datetime(2026, 11, 3, 23, tzinfo=timezone.utc).timestamp()) * 1000, collector.midnight_millis(DAY))
        # 25 October 2026 ends summer time: midnight is still +02:00, GTFS times count from noon (+01:00) - 12 h.
        self.assertEqual(int(datetime(2026, 10, 24, 22, tzinfo=timezone.utc).timestamp()) * 1000,
                         collector.midnight_millis(date(2026, 10, 25)))
        self.assertEqual(int(datetime(2026, 10, 24, 23, tzinfo=timezone.utc).timestamp()),
                         collector.service_reference(date(2026, 10, 25)))

    def test_scheduled_minutes_follow_the_rome_wall_clock_of_the_departure_day(self) -> None:
        def at(day: date, hour: int, minute: int, *, next_day: int = 0) -> int:
            """Epoch milliseconds of ``hour:minute`` on the Rome wall clock, ``next_day`` days after ``day``."""
            target = day + timedelta(days=next_day)
            return int(datetime(target.year, target.month, target.day, hour, minute, tzinfo=collector.ROME).timestamp()) * 1000

        self.assertEqual(420, collector.scheduled_minute(DAY, at(DAY, 7, 0)))
        self.assertEqual(1439, collector.scheduled_minute(DAY, at(DAY, 23, 59)))
        self.assertEqual(1450, collector.scheduled_minute(DAY, at(DAY, 0, 10, next_day=1)))  # 00:10 of the next day
        self.assertEqual(2879, collector.scheduled_minute(DAY, at(DAY, 23, 59, next_day=1)))
        self.assertEqual(420, collector.scheduled_minute(DAY, at(DAY, 7, 0) + 59_999))  # seconds are dropped
        # The days the clocks change: 10:00 is minute 600, not the 660 or 540 minutes elapsed since midnight.
        for day in (date(2026, 10, 25), date(2026, 3, 29)):
            with self.subTest(day=day):
                self.assertEqual(600, collector.scheduled_minute(day, at(day, 10, 0)))
        self.assertEqual(1450, collector.scheduled_minute(date(2026, 10, 24), at(date(2026, 10, 24), 0, 10, next_day=1)))
        for outside in (at(DAY, 0, 0) - 60_000, at(DAY, 0, 0, next_day=2)):
            with self.subTest(outside=outside):
                self.assertIsNone(collector.scheduled_minute(DAY, outside))

    def test_due_trains_are_todays_arrived_twenty_minutes_ago_and_last_nights_still_answered(self) -> None:
        selected = {train.number: train for train in collector.due_trains(self.timetable, NOW)}
        # Every train that left today and arrived by 12:40: 2001 at 09:40 (08:40 UTC), 2003 at 12:20 (11:20 UTC),
        # 9558 at 10:10, 2011 at 10:00, 2007 at 08:59 and 1965 at 09:00 (left at 00:30); 1963 left yesterday at 22:00
        # and arrived at 10:30, within the three hours ViaggiaTreno still answers about a train of the day before.
        self.assertEqual({"2001", "2003", "9558", "2011", "2007", "1963", "1965"}, set(selected))
        self.assertEqual("2026-11-03", selected["1963"].day)
        self.assertEqual("2026-11-04", selected["1965"].day)  # leaves at 24:30 of the 3 November service day
        self.assertEqual(int(datetime(2026, 11, 4, 8, 0, tzinfo=timezone.utc).timestamp()), selected["1965"].arrival)
        self.assertEqual("andamentoTreno/S08409/1965/1793746800000", collector.run_path(selected["1965"]))
        just_before = {train.number for train in collector.due_trains(self.timetable, datetime(2026, 11, 4, 11, 39, tzinfo=timezone.utc))}
        self.assertNotIn("2003", just_before)  # arrived only 19 minutes earlier
        # The whole day stays due (GitHub skips scheduled runs); yesterday's night train only for three hours.
        later = {train.number for train in collector.due_trains(self.timetable, datetime(2026, 11, 4, 12, 31, tzinfo=timezone.utc))}
        self.assertEqual({"2001", "2003", "2005", "9558", "2011", "2007", "1965"}, later)
        # After midnight ViaggiaTreno has closed the 4 November: none of its day trains is asked any more.
        night = {(train.day, train.number) for train in collector.due_trains(self.timetable, datetime(2026, 11, 4, 23, 30, tzinfo=timezone.utc))}
        self.assertEqual(set(), {key for key in night if key[0] == "2026-11-04" and key[1] != "1963"})
        self.assertEqual([train.arrival for train in collector.due_trains(self.timetable, NOW)],
                         sorted(train.arrival for train in selected.values()))

    def test_after_midnight_only_yesterdays_trains_still_running_at_midnight_are_asked(self) -> None:
        # ViaggiaTreno forgets at midnight the runs that had already ended (585 of 602 unknown at 00:58 on 9 November
        # 2026): of yesterday's trains only one still travelling at midnight is asked, within the three hours.
        self.timetable.trips = [
            collector.TripPlan("3001", "REG", "S08409", 22 * 3600, 23 * 3600 + 30 * 60, "DAILY"),  # ends 23:30
            collector.TripPlan("3003", "REG", "S08409", 22 * 3600 + 30 * 60, 24 * 3600 + 20 * 60, "DAILY"),  # 00:20
        ]
        after_midnight = datetime(2026, 11, 4, 23, 41, tzinfo=timezone.utc)  # 00:41 of 5 November in Rome
        self.assertEqual([("2026-11-04", "3003")],
                         [(train.day, train.number) for train in collector.due_trains(self.timetable, after_midnight)])
        ended = collector.Due("2026-11-04", "S08409", "3001", "REG",
                              int(datetime(2026, 11, 4, 22, 30, tzinfo=timezone.utc).timestamp()))
        moment = after_midnight.timestamp()
        self.assertFalse(collector.answerable(ended.day, ended.arrival, moment))
        # Asked before midnight and not arrived yet: it was still travelling, so it is asked again.
        self.assertTrue(collector.answerable(ended.day, ended.arrival, moment, asked=True))
        self.assertFalse(collector.answerable(ended.day, ended.arrival, moment + 3 * 3600, asked=True))


class InterpretTest(unittest.TestCase):
    def test_a_full_answer_keeps_station_delays_and_suppressed_stops_only(self) -> None:
        payload = collector.strict_json((FIXTURES / "andamentoTreno-9558-arrivato.json").read_bytes())
        train = collector.Due(DAY.isoformat(), "S08409", "9558", "FR", 0)
        observation = collector.interpret(payload, train)
        self.assertEqual("ok", observation.status)
        # Departure delay, time and platform at the origin, arrival ones elsewhere, early arrival negative, suppressed
        # stop without sample or platform but with its scheduled time (07:00, 07:09, 08:40, 09:17, 10:10); the
        # actual platform (Roma 14 for the programmed 12, Milano 21 for 19), the programmed one only where no actual
        # one is published (Tiburtina 3, marked 0).
        self.assertEqual([["S08409", 1, 0, 420, "14", 1], ["S08217", 2, 0, 429, "3", 0], ["S06421", -2, 0, 520, "8", 1],
                          ["S05043", None, 1, 557, None, 0], ["S01700", 9, 0, 610, "21", 1]], observation.stops)

    def test_delays_truncate_towards_zero_like_the_app(self) -> None:
        stops = [stop("S08235", 540, 59, first=True), stop("S08409", 580, 359), stop("S08217", 590, -59), stop("S08406", 600, -61)]
        self.assertEqual([0, 5, 0, -1], [entry[1] for entry in collector.interpret(run(stops=stops), due()).stops])

    def test_a_stop_reached_after_midnight_keeps_minutes_beyond_a_day(self) -> None:
        # ICN 1963 leaves Roma at 22:00 of 3 November and reaches Milano at 10:30 of the 4th (GTFS 34:30:00).
        day = date(2026, 11, 3)
        midnight = collector.midnight_millis(day)
        stops = [
            {"id": "S08409", "actualFermataType": 1, "partenza_teorica": midnight + 22 * 60 * MINUTE,
             "partenzaReale": midnight + (22 * 60 + 4) * MINUTE},
            {"id": "S06421", "actualFermataType": 1, "arrivoReale": midnight + 26 * 60 * MINUTE},  # no scheduled time
            {"id": "S01700", "actualFermataType": 1, "arrivo_teorico": midnight + (34 * 60 + 30) * MINUTE,
             "arrivoReale": midnight + (34 * 60 + 41) * MINUTE},
        ]
        payload = {"numeroTreno": 1963, "idOrigine": "S08409", "dataPartenzaTreno": midnight, "fermate": stops}
        observation = collector.interpret(payload, collector.Due(day.isoformat(), "S08409", "1963", "ICN", 0))
        self.assertEqual([["S08409", 4, 0, 1320, None, 0], ["S06421", None, 0, None, None, 0], ["S01700", 11, 0, 2070, None, 0]],
                         observation.stops)

    def test_cancellation_and_suppressed_stops(self) -> None:
        for payload in (run(provvedimento=1), run(tipoTreno="ST"), run(provvedimento="1"),
                        run(stops=[stop("S08235", 540, None, first=True, kind=3), stop("S08409", 580, None, kind=3)])):
            with self.subTest(payload=payload.get("provvedimento"), kind=payload.get("tipoTreno")):
                observation = collector.interpret(payload, due())
                self.assertEqual(("cancelled", []), (observation.status, observation.stops))
        # Partially cancelled at the end: the last served stop decides, the rest is suppressed.
        partial = run(stops=[stop("S08235", 540, 0, first=True), stop("S08217", 560, 120), stop("S08409", 580, None, kind=3)])
        observation = collector.interpret(partial, due())
        self.assertEqual("ok", observation.status)
        self.assertEqual([["S08235", 0, 0, 540, None, 0], ["S08217", 2, 0, 560, None, 0], ["S08409", None, 1, 580, None, 0]],
                         observation.stops)

    def test_platforms_actual_first_programmed_only_without_one(self) -> None:
        stops = [
            # Origin: the departure fields only (an arrival platform there is ignored).
            stop("S08235", 540, 60, first=True, EffettivoArrivo="9", EffettivoPartenza=" 3  Ovest ", ProgrammatoPartenza="3"),
            # Elsewhere the arrival fields, then the departure ones of the same stop; actual before programmed.
            stop("S08217", 550, 0, ProgrammatoArrivo="2", EffettivoPartenza="4"),
            stop("S08406", 555, 0, ProgrammatoArrivo="5", ProgrammatoPartenza="6"),
            stop("S08407", 556, 0, EffettivoArrivo="", ProgrammatoArrivo="  ", ProgrammatoPartenza="I"),
            # A published value that is not a short label: dropped and counted, the programmed one does not replace it.
            stop("S08408", 557, 0, EffettivoArrivo="1 Tronco Ovest", ProgrammatoArrivo="1"),
            stop("S08410", 558, 0, EffettivoArrivo="<b>7</b>", ProgrammatoArrivo="7"),
            # Numbers become labels; a suppressed stop keeps no platform; booleans and objects are not labels.
            stop("S08411", 559, 0, EffettivoArrivo=12),
            stop("S08412", 560, None, kind=3, EffettivoArrivo="8"),
            stop("S08413", 561, 0, EffettivoArrivo=True),
            stop("S08409", 580, 200, EffettivoArrivo={"x": 1}, ProgrammatoArrivo="2"),
        ]
        observation = collector.interpret(run(stops=stops), due())
        self.assertEqual("ok", observation.status)
        self.assertEqual([("3 Ovest", 1), ("4", 1), ("5", 0), ("I", 0), (None, 0), (None, 0), ("12", 1), (None, 0), (None, 0),
                          (None, 0)], [(entry[4], entry[5]) for entry in observation.stops])
        self.assertEqual({"binari_non_validi": 4}, observation.notes)
        record = {"d": DAY.isoformat(), "o": "S08235", "n": "2001", "c": "REG", "st": "ok", "t": 1, "s": observation.stops}
        self.assertTrue(collector.valid_record(record, DAY.isoformat()))

    def test_trains_still_running_stay_pending(self) -> None:
        running = run(stops=[stop("S08235", 540, 60, first=True), stop("S08409", 580, None)])
        self.assertEqual("pending", collector.interpret(running, due()).status)
        self.assertEqual("pending", collector.interpret(run(stops=[]), due()).status)
        self.assertEqual("ok", collector.interpret(dict(running, arrivato=True), due()).status)
        self.assertEqual("pending", collector.interpret(dict(running, arrivato="true"), due()).status)

    def test_extra_stops_repeated_stops_and_implausible_values_are_dropped(self) -> None:
        stops = [
            stop("S08235", 540, 60, first=True), stop("S08217", 550, 30, kind=2), stop("S08409", 570, 99_999),
            stop("S08409", 575, 60), {"id": "08406", "arrivoReale": 1}, "not a stop", stop("S08406", 580, 0),
        ]
        observation = collector.interpret(run(stops=stops), due())
        self.assertEqual([["S08235", 1, 0, 540, None, 0], ["S08409", None, 0, 570, None, 0], ["S08406", 0, 0, 580, None, 0]],
                         observation.stops)
        self.assertEqual({"fermate_straordinarie": 1, "ritardi_non_plausibili": 1, "fermate_ripetute": 1,
                          "fermate_senza_codice": 1, "fermate_non_valide": 1}, observation.notes)
        out_of_day = [stop("S08235", 540, 60, first=True), dict(stop("S08409", 580), arrivoReale=10 ** 15)]
        self.assertEqual("pending", collector.interpret(run(stops=out_of_day), due()).status)  # actual time ignored
        booleans = [dict(stop("S08235", 540, first=True), partenzaReale=True), stop("S08409", 580, 0)]
        self.assertIsNone(collector.interpret(run(stops=booleans), due()).stops[0][1])

    def test_identity_must_match_number_origin_and_day(self) -> None:
        self.assertEqual("ok", collector.interpret(run(numeroTreno="2001"), due()).status)
        for payload in (run(numeroTreno=2002), run(idOrigine="S08409"), run(idOrigine=None),
                        run(dataPartenzaTreno=collector.midnight_millis(DAY) - 86_400_000)):
            with self.subTest(payload={key: payload.get(key) for key in ("numeroTreno", "idOrigine", "dataPartenzaTreno")}):
                self.assertEqual("mismatch", collector.interpret(payload, due()).status)
        self.assertEqual("missing", collector.interpret(None, due()).status)
        with self.assertRaises(ValueError):
            collector.interpret([], due())
        with self.assertRaises(ValueError):
            collector.interpret(run(stops=[stop("S08235", 540)] * (collector.MAX_STOPS + 1)), due())

    def test_strict_json_refuses_what_a_lenient_parser_would_accept(self) -> None:
        for body in (b'{"a": NaN}', b'{"a": 1, "a": 2}', b'\xff{"a": 1}', b'{"a": Infinity}', b"{'a': 1}"):
            with self.subTest(body=body), self.assertRaises((ValueError, UnicodeDecodeError)):
                collector.strict_json(body)
        self.assertIsNone(collector.strict_json(b"null"))


class StateTest(Base):
    def record(self, day: str = "2026-11-04", number: str = "2001", status: str = "ok", t: int = 1, s: Any = None) -> dict[str, Any]:
        stops = [["S08235", 1, 0], ["S08409", 3, 0]] if s is None else s
        return {"d": day, "o": "S08235", "n": number, "c": "REG", "st": status, "t": t, "s": stops if status == "ok" else []}

    def test_same_train_and_day_observed_twice_is_one_record(self) -> None:
        state = collector.State(self.temp / "state")
        state.add(self.record(status="unknown"))
        state.add(self.record(t=5))
        state.add(self.record(t=9, s=[["S08235", None, 0], ["S08409", None, 0]]))  # fewer samples: kept out
        state.add(self.record(status="missing", t=10))
        state.save(DAY)
        lines = gzip.decompress((self.temp / "state/obs-2026-11-04.jsonl.gz").read_bytes()).decode().splitlines()
        self.assertEqual([self.record(t=5)], [json.loads(line) for line in lines])
        # A cache merged from two runs that both wrote the same train keeps one line too.
        path = self.temp / "state/obs-2026-11-04.jsonl.gz"
        path.write_bytes(path.read_bytes() + gzip.compress((collector.encode(self.record(t=2)) + "\n").encode()))
        self.assertEqual(1, len(collector.load_observations(self.temp / "state", "2026-11-04", {})))

    def test_an_empty_state_or_one_with_fewer_days_than_its_marker_is_refused(self) -> None:
        # A missed or failed cache restore leaves nothing: saving that would replace 35 days of observations that
        # ViaggiaTreno no longer has.  Only an explicit bootstrap starts from nothing.
        directory = self.temp / "state"
        with self.assertRaisesRegex(collector.CollectorError, "stato vuoto"):
            collector.State(directory).verify_retained(DAY, bootstrap=False)
        collector.State(directory).verify_retained(DAY, bootstrap=True)
        state = collector.State(directory)
        for day in ("2026-10-01", "2026-10-02", "2026-11-03"):
            state.add(self.record(day=day))
        state.save(DAY)
        self.assertEqual({"schema": collector.MARKER_SCHEMA, "days": ["2026-10-01", "2026-10-02", "2026-11-03"]},
                         json.loads((directory / collector.MARKER_FILE).read_text(encoding="ascii")))
        collector.State(directory).verify_retained(DAY, bootstrap=False)
        # A day lost on the way (another cache restored, a damaged archive) is refused before any request.
        (directory / "obs-2026-10-02.jsonl.gz").unlink()
        with self.assertRaisesRegex(collector.CollectorError, "1 dei 3 giorni"):
            collector.State(directory).verify_retained(DAY, bootstrap=False)
        # Days the 35-day retention removes are not missed; a bootstrap accepts the loss and counts it.
        collector.State(directory).verify_retained(date(2026, 11, 7), bootstrap=False)
        accepted = collector.State(directory)
        accepted.verify_retained(DAY, bootstrap=True)
        self.assertEqual(1, accepted.counters["giorni_persi"])
        # And the save itself never writes a marker with fewer days than the one it started from.
        thin = collector.State(directory)
        (directory / "obs-2026-10-02.jsonl.gz").write_bytes(collector._gzip_lines([collector.encode(self.record(day="2026-10-02"))]))
        thin.verify_retained(DAY, bootstrap=False)
        (directory / "obs-2026-10-01.jsonl.gz").unlink()
        marker = (directory / collector.MARKER_FILE).read_bytes()
        with self.assertRaisesRegex(collector.CollectorError, "meno giorni"):
            thin.save(DAY)
        self.assertEqual(marker, (directory / collector.MARKER_FILE).read_bytes())
        # A state written before the marker existed loads when it has observations.
        (directory / collector.MARKER_FILE).unlink()
        collector.State(directory).verify_retained(DAY, bootstrap=False)
        for content in ("{", "[]", '{"schema": "other", "days": []}', '{"schema": "%s", "days": ["2026-13-01"]}' % collector.MARKER_SCHEMA,
                        '{"schema": "%s", "days": ["2026-11-03", "2026-10-01"]}' % collector.MARKER_SCHEMA):
            with self.subTest(content=content):
                (directory / collector.MARKER_FILE).write_text(content, encoding="ascii")
                with self.assertRaises(collector.CollectorError):
                    collector.State(directory).verify_retained(DAY, bootstrap=False)

    def test_files_are_deterministic_atomic_and_pruned_after_35_days(self) -> None:
        state = collector.State(self.temp / "state")
        for day in ("2026-09-29", "2026-09-30", "2026-11-04"):
            state.add(self.record(day=day))
        state.save(date(2026, 11, 3))  # 2026-09-29 is 35 days before: kept
        self.assertTrue((self.temp / "state/obs-2026-09-29.jsonl.gz").is_file())
        state.save(date(2026, 11, 4))
        names = sorted(path.name for path in (self.temp / "state").iterdir())
        self.assertEqual(["obs-2026-09-30.jsonl.gz", "obs-2026-11-04.jsonl.gz", "pending.jsonl.gz", "state.json"], names)
        data = (self.temp / "state/obs-2026-11-04.jsonl.gz").read_bytes()
        self.assertEqual(b"\x1f\x8b\x08\x00\x00\x00\x00\x00", data[:8])  # no name, no time
        other = collector.State(self.temp / "other")
        other.add(self.record(day="2026-11-04"))
        other.save(DAY)
        self.assertEqual(data, (self.temp / "other/obs-2026-11-04.jsonl.gz").read_bytes())

    def test_invalid_state_lines_are_skipped_and_counted(self) -> None:
        directory = self.temp / "state"
        directory.mkdir()
        lines = [
            collector.encode(self.record()),
            collector.encode(dict(self.record(number="2003"), d="2026-11-05")),  # wrong file
            collector.encode(dict(self.record(number="2005"), s=[["S08409", "3", 0]])),
            collector.encode(dict(self.record(number="2007"), extra=1)),
            collector.encode(self.record(number="2009", s=[["S08409", 3, 0], ["S08409", 4, 0]])),
            "{not json", '{"d": NaN}',
        ]
        (directory / "obs-2026-11-04.jsonl.gz").write_bytes(gzip.compress("\n".join(lines).encode()))
        (directory / "pending.jsonl.gz").write_bytes(b"not gzip")
        good_pending = {"d": "2026-11-04", "o": "S08409", "n": "2003", "c": "REG", "a": 1, "k": 0}
        self.assertTrue(collector.valid_pending(good_pending))
        for change in ({"d": "2026-13-45"}, {"k": True}, {"o": "../x"}, {"n": "0123"}, {"extra": 1}):
            with self.subTest(change=change):
                self.assertFalse(collector.valid_pending(dict(good_pending, **change)))
        counters: dict[str, int] = {}
        records = collector.load_observations(directory, "2026-11-04", counters)
        self.assertEqual([("S08235", "2001")], list(records))
        self.assertEqual(6, counters["state_invalid_lines"])
        state = collector.State(directory)
        self.assertEqual({}, state.pending)
        self.assertEqual(1, state.counters["state_unreadable_files"])

    def test_lines_collected_before_scheduled_minutes_still_load_and_are_kept(self) -> None:
        # The Actions cache holds lines whose stops are [code, delay, suppressed]; new lines add the scheduled minute.
        directory = self.temp / "state"
        directory.mkdir()
        old = collector.encode(self.record(number="2001"))
        new = collector.encode(self.record(number="2003", s=[["S08235", 1, 0, 540], ["S08409", None, 1, None]]))
        (directory / "obs-2026-11-04.jsonl.gz").write_bytes(gzip.compress((old + "\n" + new + "\n").encode()))
        counters: dict[str, int] = {}
        records = collector.load_observations(directory, "2026-11-04", counters)
        self.assertEqual({}, counters)
        self.assertEqual([["S08235", 1, 0], ["S08409", 3, 0]], records[("S08235", "2001")]["s"])
        self.assertEqual([["S08235", 1, 0, 540], ["S08409", None, 1, None]], records[("S08235", "2003")]["s"])
        state = collector.State(directory)
        state.add(self.record(number="2005", s=[["S08235", 2, 0, 600], ["S08409", 4, 0, 1450]]))
        state.save(DAY)
        lines = gzip.decompress((directory / "obs-2026-11-04.jsonl.gz").read_bytes()).decode().splitlines()
        self.assertEqual([old, new, collector.encode(self.record(number="2005", s=[["S08235", 2, 0, 600], ["S08409", 4, 0, 1450]]))],
                         lines)  # the old line is rewritten unchanged, not given an invented time
        for stops in ([["S08235", 1, 0, -1]], [["S08235", 1, 0, 2880]], [["S08235", 1, 0, "540"]], [["S08235", 1, 0, True]],
                      [["S08235", 1, 0, 540.0]], [["S08235", 1, 0, 540, 1]], [["S08235", 1]],
                      [["S08235", 1, 0, 540, "12", 2]], [["S08235", 1, 0, 540, "12", True]], [["S08235", 1, 0, 540, None, 1]],
                      [["S08235", 1, 0, 540, " 12", 1]], [["S08235", 1, 0, 540, "1 Tronco Ovest", 1]], [["S08235", 1, 0, 540, 12, 1]],
                      [["S08235", 1, 0, 540, "", 0]], [["S08235", 1, 0, 540, "12", 1, 0]], [["S08235", 1, 0, 2880, "12", 1]]):
            with self.subTest(stops=stops):
                self.assertFalse(collector.valid_record(self.record(s=stops), "2026-11-04"))
        for minute in (0, 1439, 1440, 2879, None):
            with self.subTest(minute=minute):
                self.assertTrue(collector.valid_record(self.record(s=[["S08235", 1, 0, minute]]), "2026-11-04"))
        for platform in (["12", 1], ["3 Ovest", 0], ["Città", 1], [None, 0]):
            with self.subTest(platform=platform):
                self.assertTrue(collector.valid_record(self.record(s=[["S08235", 1, 0, 540, *platform]]), "2026-11-04"))

    def test_lines_of_every_form_load_together(self) -> None:
        # The Actions cache mixes the three forms after the extensions of 4 November 2026: they all load and are kept.
        directory = self.temp / "state"
        directory.mkdir()
        forms = [[["S08235", 1, 0], ["S08409", 3, 0]], [["S08235", 1, 0, 540], ["S08409", 3, 0, 580]],
                 [["S08235", 1, 0, 540, "1", 0], ["S08409", 3, 0, 580, "12", 1]]]
        lines = [collector.encode(self.record(number=str(2001 + 2 * index), s=stops)) for index, stops in enumerate(forms)]
        (directory / "obs-2026-11-04.jsonl.gz").write_bytes(gzip.compress(("\n".join(lines) + "\n").encode()))
        counters: dict[str, int] = {}
        records = collector.load_observations(directory, "2026-11-04", counters)
        self.assertEqual({}, counters)
        self.assertEqual(forms, [records[("S08235", number)]["s"] for number in ("2001", "2003", "2005")])


class Answers:
    """An injected fetcher answering from a function of the path; counts requests."""

    def __init__(self, respond: Callable[[str], collector.FetchResult]):
        self.respond = respond
        self.paths: list[str] = []

    def __call__(self, path: str) -> collector.FetchResult:
        self.paths.append(path)
        return self.respond(path)


def ok(payload: Any) -> collector.FetchResult:
    return collector.FetchResult("ok", json.dumps(payload).encode())


class CollectTest(Base):
    def collect(self, fetch: Callable[[str], collector.FetchResult], now: datetime = NOW, **limits: Any) -> tuple[collector.State, collector.Report]:
        state = collector.State(self.temp / "state")
        report = collector.collect(self.timetable, state, fetch, now, max_requests=limits.get("max_requests", 100),
                                   deadline=limits.get("deadline", float("inf")), clock=limits.get("clock", lambda: 0.0))
        state.save(collector.rome_date(now.timestamp()))
        return state, report

    @staticmethod
    def answer_all(path: str, *, arrived: bool = True) -> collector.FetchResult:
        _, origin, number, millis = path.split("/")
        day = collector.rome_date(int(millis) / 1000)
        midnight = int(millis)
        first = {"id": origin, "actualFermataType": 1, "partenza_teorica": midnight + 3_600_000, "partenzaReale": midnight + 3_660_000}
        last = {"id": "S01700", "actualFermataType": 1, "arrivo_teorico": midnight + 7_200_000}
        if arrived:
            last["arrivoReale"] = midnight + 7_200_000 + 420_000
        return ok({"numeroTreno": int(number), "idOrigine": origin, "dataPartenzaTreno": midnight, "fermate": [first, last],
                   "dataPartenzaTrenoAsDate": day.isoformat()})

    def test_a_run_records_each_due_train_once_and_a_second_run_asks_nothing(self) -> None:
        answers = Answers(self.answer_all)
        state, report = self.collect(answers)
        self.assertEqual(7, len(answers.paths))
        self.assertEqual(7, report.counters["arrivati"])
        self.assertEqual(14, report.counters["campioni"])
        self.assertEqual({}, state.pending)
        again = Answers(self.answer_all)
        _, second = self.collect(again)
        self.assertEqual([], again.paths)
        self.assertEqual(7, second.counters["gia_noti"])
        records = collector.load_observations(self.temp / "state", "2026-11-04", {})
        self.assertEqual([1, 7], [stop[1] for stop in records[("S08235", "2001")]["s"]])
        self.assertEqual("REG", records[("S08235", "2001")]["c"])
        self.assertIn(("S08409", "1963"), collector.load_observations(self.temp / "state", "2026-11-03", {}))

    def test_pending_trains_are_retried_by_later_runs_then_dropped_after_eight_hours(self) -> None:
        answers = Answers(lambda path: self.answer_all(path, arrived="/2001/" not in path))
        state, report = self.collect(answers)
        self.assertEqual(1, report.counters["non_ancora_arrivati"])
        entry = state.pending[("2026-11-04", "S08235", "2001")]
        self.assertEqual(1, entry["k"])
        # Two hours later 2001 has left the due window, but it is asked again as a pending train.
        later = Answers(lambda path: self.answer_all(path, arrived=False))
        state, report = self.collect(later, NOW + timedelta(hours=2))
        self.assertIn("andamentoTreno/S08235/2001/1793746800000", later.paths)
        self.assertEqual(2, state.pending[("2026-11-04", "S08235", "2001")]["k"])
        arrived = Answers(lambda path: self.answer_all(path, arrived="/2001/" in path))
        state, _ = self.collect(arrived, NOW + timedelta(hours=4))
        self.assertNotIn(("2026-11-04", "S08235", "2001"), state.pending)
        self.assertEqual("ok", collector.load_observations(self.temp / "state", "2026-11-04", {})[("S08235", "2001")]["st"])

        # 2005 (due from the second run, 11:41 UTC) never arrives: 8 h after its scheduled arrival it becomes unknown.
        never = Answers(lambda path: self.answer_all(path, arrived=False))
        state, _ = self.collect(never, NOW + timedelta(hours=6))
        self.assertEqual(3, state.pending[("2026-11-04", "S08409", "2005")]["k"])  # asked by three runs
        last = Answers(lambda path: self.answer_all(path, arrived=False))
        state, report = self.collect(last, NOW + timedelta(hours=7, minutes=42))
        self.assertEqual(1, report.counters["scaduti_sconosciuti"])
        self.assertEqual([], last.paths)
        record = collector.load_observations(self.temp / "state", "2026-11-04", {})[("S08409", "2005")]
        self.assertEqual(("unknown", []), (record["st"], record["s"]))
        self.assertEqual({}, state.pending)

    def test_unknown_trains_and_other_identities_are_recorded_and_not_asked_again(self) -> None:
        def respond(path: str) -> collector.FetchResult:
            if "/2001/" in path:
                return collector.FetchResult("missing", reason="http-204")
            if "/9558/" in path:
                return ok(run(number="9999", origin="S08409"))
            return self.answer_all(path)
        state, report = self.collect(Answers(respond))
        self.assertEqual((1, 1), (report.counters["missing"], report.counters["mismatch"]))
        records = collector.load_observations(self.temp / "state", "2026-11-04", {})
        self.assertEqual("missing", records[("S08235", "2001")]["st"])
        self.assertEqual("mismatch", records[("S08409", "9558")]["st"])
        again = Answers(respond)
        self.collect(again)
        self.assertEqual([], again.paths)

    def test_twenty_consecutive_failures_stop_the_run_and_keep_the_trains_pending(self) -> None:
        self.timetable.trips = [collector.TripPlan(str(3000 + index), "REG", "S08409", 9 * 3600, 10 * 3600, "DAILY")
                                for index in range(30)]
        failing = Answers(lambda _: collector.FetchResult("failure", reason="http-503"))
        state, report = self.collect(failing)
        self.assertEqual(20, len(failing.paths))
        self.assertEqual("20 errori consecutivi", report.stop_reason)
        self.assertEqual({"http-503": 20}, report.failures)
        self.assertEqual(30, len(state.pending))
        self.assertEqual(10, report.counters["rimandati"])
        # One success resets the count; invalid JSON is a failure too.
        answers = iter([collector.FetchResult("ok", b"{broken")] * 19 + [None] + [collector.FetchResult("failure", reason="rete")] * 10)
        mixed = Answers(lambda path: next(answers) or self.answer_all(path))
        state, report = self.collect(mixed)
        self.assertEqual(30, len(mixed.paths))
        self.assertEqual("completata", report.stop_reason)
        self.assertEqual({"json-non-valido": 19, "rete": 10}, report.failures)

    def test_a_run_of_unknown_or_foreign_answers_trips_the_breaker_and_records_none_of_them(self) -> None:
        # A changed path (every call 404), a soft block (204 or null) or a new day convention (every identity
        # differs) must not mark the whole day as permanently missing while the job stays green.
        self.timetable.trips = [collector.TripPlan(str(3000 + index), "REG", "S08409", 9 * 3600, 10 * 3600, "DAILY")
                                for index in range(70)]
        answers = iter([collector.FetchResult("missing", reason="http-404")] * 30
                       + [ok(run(number="1", origin="S08409"))] * 15 + [None] * 100)
        mixed = Answers(lambda path: next(answers) or self.answer_all(path))
        state, report = self.collect(mixed)
        # 41 of the last (at most) 50 answers are unknown or foreign: more than 80 %, whatever the next 9 say.
        self.assertEqual(collector.MAX_BAD_ANSWERS + 1, len(mixed.paths))
        self.assertTrue(report.stopped_abnormally)
        self.assertIn("sconosciute o con identità diversa", report.stop_reason)
        self.assertEqual((30, 11), (report.counters["missing"], report.counters["mismatch"]))
        self.assertEqual({}, collector.load_observations(self.temp / "state", "2026-11-04", {}))
        self.assertEqual(70, len(state.pending))
        self.assertEqual({0}, {entry["k"] for entry in state.pending.values()})  # as if never asked
        self.assertEqual(41, report.counters["rimessi_in_sospeso"])
        self.assertEqual(29, report.counters["rimandati"])
        # A share at the limit does not trip: 40 of the last 50 unknown is still believed.
        shutil.rmtree(self.temp / "state")
        answers = iter(([collector.FetchResult("missing", reason="http-204")] * 4 + [None]) * 14)
        believed = Answers(lambda path: next(answers, None) or self.answer_all(path))
        state, report = self.collect(believed)
        self.assertEqual(70, len(believed.paths))
        self.assertFalse(report.stopped_abnormally)
        self.assertEqual(56, report.counters["missing"])

    def test_a_refusing_source_stops_the_run_at_once(self) -> None:
        refused = Answers(lambda _: collector.FetchResult("refused", reason="http-429"))
        state, report = self.collect(refused)
        self.assertEqual(1, len(refused.paths))
        self.assertIn("http-429", report.stop_reason)
        self.assertEqual(7, len(state.pending))
        self.assertTrue(report.stopped_abnormally)

    def test_a_refusal_pauses_the_following_runs_with_a_growing_back_off(self) -> None:
        hour = 3600
        start = int(NOW.timestamp())
        self.collect(Answers(lambda _: collector.FetchResult("refused", reason="http-403")))
        self.assertEqual({"since": start, "until": start + 2 * hour, "count": 1, "reason": "http-403"},
                         collector.State(self.temp / "state").refusal)
        # The hourly runs within the pause send nothing; their trains wait as pending; the job is not failed again.
        paused = Answers(self.answer_all)
        state, report = self.collect(paused, NOW + timedelta(hours=1))
        self.assertEqual([], paused.paths)
        self.assertIn("in pausa", report.stop_reason)
        self.assertFalse(report.stopped_abnormally)
        self.assertEqual(7, len(state.pending))
        # Refused again after the pause: twice as long, or the Retry-After if longer.
        self.collect(Answers(lambda _: collector.FetchResult("refused", reason="http-429")), NOW + timedelta(hours=2))
        self.assertEqual((2, start + 2 * hour + 4 * hour), (collector.State(self.temp / "state").refusal["count"],
                                                            collector.State(self.temp / "state").refusal["until"]))
        self.collect(Answers(lambda _: collector.FetchResult("refused", reason="http-503", retry_after=30 * hour)),
                     NOW + timedelta(hours=6))
        self.assertEqual(start + 6 * hour + 30 * hour, collector.State(self.temp / "state").refusal["until"])
        # Never longer than a week; an answer ends the back-off.
        def after_pause() -> datetime:
            """The first 13:00 in Rome (trains are due then) after the pause."""
            until = datetime.fromtimestamp(collector.State(self.temp / "state").refusal["until"], timezone.utc)
            noon = until.replace(hour=12, minute=0, second=0)
            return noon if noon >= until else noon + timedelta(days=1)

        pauses = []
        for _ in range(4, 12):
            moment = after_pause()
            self.collect(Answers(lambda _: collector.FetchResult("refused", reason="http-429")), moment)
            pauses.append((collector.State(self.temp / "state").refusal["until"] - int(moment.timestamp())) // hour)
        self.assertEqual([16, 32, 64, 128, 168, 168, 168, 168], pauses)
        answered = Answers(self.answer_all)
        self.collect(answered, after_pause())
        self.assertTrue(answered.paths)
        self.assertIsNone(collector.State(self.temp / "state").refusal)
        self.assertFalse((self.temp / "state" / collector.REFUSAL_FILE).exists())

    def test_a_tripped_breaker_pauses_the_following_runs_until_a_recognised_answer(self) -> None:
        # A soft block (204 or null to this client) trips the breaker: the following runs must not send the same 41
        # requests again every hour, as after an HTTP refusal; the pause has its own reason label.
        hour = 3600
        start = int(NOW.timestamp())
        self.timetable.trips = [collector.TripPlan(str(3000 + index), "REG", "S08409", 9 * 3600, 10 * 3600, "DAILY")
                                for index in range(60)]
        blocked = Answers(lambda _: collector.FetchResult("missing", reason="http-204"))
        state, report = self.collect(blocked)
        self.assertEqual(collector.MAX_BAD_ANSWERS + 1, len(blocked.paths))
        self.assertTrue(report.stopped_abnormally)
        self.assertEqual({"since": start, "until": start + 2 * hour, "count": 1, "reason": collector.BREAKER_REASON},
                         collector.State(self.temp / "state").refusal)
        paused = Answers(self.answer_all)
        state, report = self.collect(paused, NOW + timedelta(hours=1))
        self.assertEqual([], paused.paths)
        self.assertIn("in pausa", report.stop_reason)
        self.assertIn("sconosciute", report.stop_reason)
        self.assertFalse(report.stopped_abnormally)
        self.assertEqual(60, len(state.pending))
        # After the pause the source still answers 204: unknown answers do not end the back-off, which doubles.
        again = Answers(lambda _: collector.FetchResult("missing", reason="http-204"))
        self.collect(again, NOW + timedelta(hours=2))
        self.assertEqual(collector.MAX_BAD_ANSWERS + 1, len(again.paths))
        refusal = collector.State(self.temp / "state").refusal
        self.assertEqual((2, start + 2 * hour + 4 * hour), (refusal["count"], refusal["until"]))
        # The first recognised answer ends it; a 204 before it does not.
        answers = iter([collector.FetchResult("missing", reason="http-204")] * 3)
        recovered = Answers(lambda path: next(answers, None) or self.answer_all(path))
        self.collect(recovered, NOW + timedelta(hours=6, minutes=1))
        self.assertGreater(len(recovered.paths), 3)
        self.assertIsNone(collector.State(self.temp / "state").refusal)
        self.assertFalse((self.temp / "state" / collector.REFUSAL_FILE).exists())

    def test_a_damaged_refusal_record_stops_the_collector_before_any_request(self) -> None:
        directory = self.temp / "state"
        directory.mkdir()
        for content in ("{", '{"since": 1, "until": 2, "count": 1, "reason": "free text"}', "[]",
                        '{"since": 1, "until": 2, "count": 0, "reason": "http-429"}'):
            with self.subTest(content=content):
                (directory / collector.REFUSAL_FILE).write_text(content, encoding="utf-8")
                with self.assertRaises(collector.CollectorError):
                    collector.State(directory)

    def test_request_budget_and_deadline(self) -> None:
        answers = Answers(self.answer_all)
        state, report = self.collect(answers, max_requests=2)
        self.assertEqual(2, len(answers.paths))
        self.assertEqual("limite --max-requests", report.stop_reason)
        self.assertEqual(5, len(state.pending))
        ticks = iter([0.0, 0.0, 5.0, 11.0])
        answers = Answers(self.answer_all)
        state, report = self.collect(answers, deadline=10.0, clock=lambda: next(ticks, 99.0))
        self.assertEqual(3, len(answers.paths))  # earlier pending first, then one new train before the deadline
        self.assertEqual("limite --deadline-seconds", report.stop_reason)

    def test_a_run_that_crosses_rome_midnight_stops_asking_about_the_closed_day(self) -> None:
        # The 23:47 run with a backlog lasts past midnight, when ViaggiaTreno forgets the day: the rest is deferred
        # (the next run closes it without a request) instead of 41 unknown answers tripping the breaker.
        late = datetime(2026, 11, 4, 22, 50, tzinfo=timezone.utc)  # 23:50 in Rome
        ticks = iter([0.0, 0.0, 300.0, 700.0])  # the fourth request would leave at 00:01:40
        answers = Answers(self.answer_all)
        state, report = self.collect(answers, late, clock=lambda: next(ticks, 999.0))
        self.assertEqual(3, len(answers.paths))
        self.assertIn("mezzanotte", report.stop_reason)
        self.assertFalse(report.stopped_abnormally)
        self.assertEqual(4, report.counters["rimandati"])
        self.assertEqual(4, len(state.pending))
        self.assertEqual({0}, {entry["k"] for entry in state.pending.values()})
        after = Answers(self.answer_all)
        state, report = self.collect(after, late + timedelta(minutes=20))
        self.assertEqual([], after.paths)
        self.assertEqual(4, report.counters["giorno_chiuso"])
        # The clock reading taken with ``now`` counts too: a run that starts at 23:59:50 asks nothing after 00:00.
        shutil.rmtree(self.temp / "state")
        state = collector.State(self.temp / "state")
        early = Answers(self.answer_all)
        report = collector.collect(self.timetable, state, early, datetime(2026, 11, 4, 22, 59, 50, tzinfo=timezone.utc),
                                   max_requests=100, deadline=float("inf"), clock=lambda: 15.0, anchor=0.0)
        self.assertEqual([], early.paths)
        self.assertEqual(7, len(state.pending))

    def test_trains_left_over_by_the_limits_wait_for_the_next_run_until_viaggiatreno_closes_the_day(self) -> None:
        state, report = self.collect(Answers(self.answer_all), max_requests=1)  # 2007, the oldest, is asked
        left = {key for key, entry in state.pending.items() if entry["k"] == 0}
        self.assertEqual({"1965", "2001", "2011", "9558", "1963", "2003"}, {key[2] for key in left})
        # Nine hours later (22:00 in Rome) today's are still asked, although their arrival is more than 8 h old; the
        # night train of the day before is past ViaggiaTreno's three hours and is dropped without a request.
        evening = Answers(self.answer_all)
        state, report = self.collect(evening, NOW + timedelta(hours=9))
        self.assertEqual(0, report.counters.get("scaduti_sconosciuti", 0))
        self.assertEqual(1, report.counters["giorno_chiuso"])
        # (2005, due since 12:41, is new for this run.)
        self.assertEqual({"1965", "2001", "2011", "9558", "2003", "2005"}, {path.split("/")[2] for path in evening.paths})
        # Left over by a run just before midnight, they are lost once ViaggiaTreno has closed the day: recorded as
        # unknown, counted apart, never asked.
        shutil.rmtree(self.temp / "state")
        self.collect(Answers(self.answer_all), max_requests=0)
        after = Answers(self.answer_all)
        state, report = self.collect(after, datetime(2026, 11, 4, 23, 30, tzinfo=timezone.utc))
        self.assertEqual(7, report.counters["giorno_chiuso"])
        self.assertEqual([], after.paths)
        self.assertEqual({}, state.pending)
        self.assertEqual("unknown", collector.load_observations(self.temp / "state", "2026-11-04", {})[("S08235", "2001")]["st"])


class HttpFetcherTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server = FakeViaggiaTreno()
        self.addCleanup(self.server.close)
        self.slept: list[float] = []
        self.now = 0.0

        def sleep(seconds: float) -> None:
            self.slept.append(seconds)
            self.now += seconds

        self.fetcher = collector.HttpFetcher(self.server.base_url, rate=2.0, timeout=5, max_bytes=2048,
                                             clock=lambda: self.now, sleep=sleep)

    def path(self, number: str) -> str:
        return collector.run_path(due(number=number))

    def test_answers_statuses_and_politeness(self) -> None:
        self.server.answer(due(number="2001"), run())
        self.server.answer(due(number="2003"), b"x" * 4096)
        self.server.answer(due(number="2005"), b"", status=503)
        self.server.answer(due(number="2007"), b"", status=404)
        self.server.answer(due(number="2009"), b"", status=429)
        self.server.answer(due(number="2011"), run(), headers={"Set-Cookie": "session=abc"})
        results = [self.fetcher.fetch(self.path(number)) for number in ("2001", "2003", "2005", "2007", "2009", "2011", "2013", "2001")]
        self.assertEqual(["ok", "failure", "failure", "missing", "refused", "ok", "missing", "ok"], [result.kind for result in results])
        self.assertEqual(["", "troppo-grande", "http-503", "http-404", "http-429", "", "http-204", ""], [result.reason for result in results])
        self.assertIsNone(results[4].retry_after)
        self.assertEqual(run(), json.loads(results[0].body))
        self.assertEqual([0.5] * 7, self.slept)  # 2 requests per second, never faster
        for _, headers in self.server.requests:
            self.assertEqual(collector.USER_AGENT, headers.get("User-Agent"))
            self.assertNotIn("Cookie", headers)
        self.assertEqual(8, self.fetcher.requests)
        with self.assertRaises(collector.CollectorError):
            collector.HttpFetcher(rate=3)

    def test_retry_after_is_read_and_a_503_with_it_is_a_refusal(self) -> None:
        self.server.answer(due(number="2001"), b"", status=429, headers={"Retry-After": "120"})
        self.server.answer(due(number="2003"), b"", status=503, headers={"Retry-After": "3600"})
        self.server.answer(due(number="2005"), b"", status=403, headers={"Retry-After": "Wed, 04 Nov 2026 14:00:00 GMT"})
        self.server.answer(due(number="2007"), b"", status=503, headers={"Retry-After": "soon"})
        self.server.answer(due(number="2009"), b"", status=429, headers={"Retry-After": "99999999999"})
        with mock.patch.object(collector.time, "time", return_value=NOW.timestamp()):
            results = [self.fetcher.fetch(self.path(number)) for number in ("2001", "2003", "2005", "2007", "2009")]
        self.assertEqual(["refused", "refused", "refused", "failure", "refused"], [result.kind for result in results])
        self.assertEqual(["http-429", "http-503", "http-403", "http-503", "http-429"], [result.reason for result in results])
        self.assertEqual([120, 3600, 7200, None, collector.MAX_PAUSE.total_seconds()],
                         [result.retry_after for result in results])

    def test_user_agent_says_what_the_collector_is_and_carries_no_address(self) -> None:
        # What the operator sees is what runs: a scheduled, automated, read-only collector of the statistics, at a low
        # rate, for the open-source app (not "a personal timetable app"), never a browser.  No URL (the form with one
        # was refused with HTTP 403) and no e-mail address (the owner's is private).
        agent = collector.USER_AGENT
        self.assertTrue(agent.startswith("Binario-punctuality/"))
        for words in ("automated", "read-only", "collector", "punctuality statistics", "open-source Binario app",
                      "low rate", f"at most {collector.MAX_RATE:g} requests per second"):
            self.assertIn(words, agent)
        for absent in ("http", "www.", "@", "mozilla", "applewebkit", "chrome", "safari", "personal"):
            self.assertNotIn(absent, agent.lower())
        self.assertTrue(agent.isascii() and agent.isprintable())

    def test_redirects_stay_on_the_same_resource(self) -> None:
        base = collector.BASE_URL
        path = base + "andamentoTreno/S08409/9558/1793746800000"
        https = "https://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/andamentoTreno/S08409/9558/1793746800000"
        self.assertEqual(https, collector.redirect_target(path, https, base))
        self.assertEqual(path, collector.redirect_target(https, path, base))
        for location in (
            "https://evil.example/infomobilita/resteasy/viaggiatreno/andamentoTreno/S08409/9558/1793746800000",
            https + "?x=1", https.replace("9558", "9559"), "https://user@www.viaggiatreno.it" + https[29:],
            "http://viaggiatreno.it" + https[29:], "https://www.viaggiatreno.it:8443" + https[29:], "", None, "\x00",
        ):
            with self.subTest(location=location):
                self.assertIsNone(collector.redirect_target(path, location, base))
        target = self.server.base_url + self.path("2001")
        self.server.routes["/infomobilita/resteasy/viaggiatreno/" + self.path("2003")] = (301, {"Location": target}, b"")
        self.assertEqual("redirect-non-consentito", self.fetcher.fetch(self.path("2003")).reason)
        self.server.answer(due(number="2001"), run())
        self.server.routes["/infomobilita/resteasy/viaggiatreno/" + self.path("2005")] = (
            302, {"Location": self.server.base_url + self.path("2005").replace("2005", "2001")}, b"")
        self.assertEqual("redirect-non-consentito", self.fetcher.fetch(self.path("2005")).reason)


class EndToEndTest(Base):
    def test_collector_and_statistics_against_a_fake_viaggiatreno(self) -> None:
        server = FakeViaggiaTreno()
        self.addCleanup(server.close)
        selected = {train.number: train for train in collector.due_trains(self.timetable, NOW)}
        fixture = collector.strict_json((FIXTURES / "andamentoTreno-9558-arrivato.json").read_bytes())
        server.answer(selected["9558"], fixture)
        server.answer(selected["2001"], run())
        server.answer(selected["2003"], run(number="2003", origin="S08409", provvedimento=1, stops=[]))
        server.answer(selected["2011"], run(number="2011", origin="S08409",
                                            stops=[stop("S08409", 540, 0, first=True), stop("S08235", 600, None)]))
        server.answer(selected["1965"], b"<html>maintenance</html>")
        state = self.temp / "state"
        summary = self.temp / "summary.md"
        arguments = ["--state", str(state), "--gtfs", str(self.gtfs), "--stations", str(self.stations_path),
                     "--now", NOW.isoformat(), "--base-url", server.base_url, "--rate", "2", "--summary", str(summary)]
        # An empty state (a cache that was not restored) is refused before any request, unless bootstrapped.
        with contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(1, collector.main(arguments))
        self.assertIn("stato vuoto", errors.getvalue())
        self.assertEqual([], server.requests)
        self.assertEqual([], list(state.iterdir()) if state.exists() else [])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, collector.main(arguments + ["--bootstrap"]))
        log = output.getvalue() + summary.read_text(encoding="utf-8")
        self.assertIn("Treni chiesti: 7", log)
        self.assertIn("Arrivati: 2", log)  # 9558 and 2001
        self.assertIn("Cancellati: 1", log)  # 2003
        self.assertIn("Sconosciuti a ViaggiaTreno: 2", log)  # 1963 and 2007: the server answers 204
        self.assertIn("Non ancora arrivati (in sospeso): 1", log)  # 2011
        self.assertIn("Errore json-non-valido: 1", log)
        for leaked in ("MILANO", "maintenance", "html", "ROMA", "soppressa", "S08409"):
            self.assertNotIn(leaked, log)  # counts only, never text from a response
        self.assertEqual(7, len(server.requests))
        pending = {(entry["n"]) for entry in collector.State(state).pending.values()}
        self.assertEqual({"2011", "1965"}, pending)

        # Second run, same instant: nothing new is asked except the two pending trains.
        server.answer(selected["2011"], run(number="2011", origin="S08409",
                                            stops=[stop("S08409", 540, 0, first=True), stop("S08235", 600, -90)]))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, collector.main(arguments))
        self.assertEqual(9, len(server.requests))

        output_path = self.temp / "stats_v1.json.gz"
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, stats.main(["--state", str(state), "--output", str(output_path),
                                            "--as-of", "2026-11-05", "--generated-at", "2026-11-05T04:00:00Z"]))
        payload = stats.check_bytes(output_path.read_bytes())
        trains = {(train["n"], train["o"]): train for train in payload["trains"]}
        self.assertEqual({("2001", "S08235"), ("2003", "S08409"), ("2011", "S08409"), ("9558", "S08409")}, set(trains))
        self.assertEqual({"n": "2003", "o": "S08409", "c": "REG", "d": 1, "x": 1, "s": []}, trains[("2003", "S08409")])
        self.assertEqual(["S08409", 1, 0.0, [1] * 8, 0, 0, 540, None, None], trains[("2011", "S08409")]["s"][0])
        self.assertEqual(["S08235", 1, 0.0, [1] * 8, 0, 0, 600, None, None], trains[("2011", "S08409")]["s"][1])  # 90 s early
        fr = trains[("9558", "S08409")]
        self.assertEqual(("FR", 1, 0), (fr["c"], fr["d"], fr["x"]))
        self.assertEqual(["S08409", "S08217", "S06421", "S01700"], [entry[0] for entry in fr["s"]])  # suppressed stop left out
        self.assertEqual(["S01700", 1, 9.0, [0, 0, 0, 1, 1, 1, 1, 1], 0, 9, 610, ["21", 1, 1], None], fr["s"][-1])
        # The programmed platform of Tiburtina is counted by itself (no actual one there); one day: no weekdays.
        self.assertEqual([["14", 1, 1], ["3", 1, 1], ["8", 1, 1], ["21", 1, 1]], [entry[7] for entry in fr["s"]])

    def test_a_run_stopped_by_the_breaker_saves_its_state_and_fails(self) -> None:
        server = FakeViaggiaTreno()  # 204 for every train: unknown answers only
        self.addCleanup(server.close)
        trips = [(f"reg{4000 + index}", "REG", "DAILY", str(4000 + index),
                  [("ROMA", "08:00:00", "08:00:00"), ("FIDENE", "08:40:00", "08:40:00")]) for index in range(45)]
        gtfs = self.temp / "many.zip"
        with mock.patch.object(sys.modules[__name__], "TRIPS", trips):
            write_gtfs(gtfs)
        state = self.temp / "state"
        arguments = ["--state", str(state), "--gtfs", str(gtfs), "--stations", str(self.stations_path),
                     "--now", NOW.isoformat(), "--base-url", server.base_url, "--rate", "2", "--bootstrap"]
        output = io.StringIO()
        # The local server needs no pause between requests.
        with contextlib.redirect_stdout(output), mock.patch.object(collector.HttpFetcher, "_throttle", lambda _: None):
            self.assertEqual(collector.EXIT_STOPPED, collector.main(arguments))
        self.assertEqual(collector.MAX_BAD_ANSWERS + 1, len(server.requests))
        self.assertIn("sconosciute o con identità diversa", output.getvalue())
        self.assertEqual(45, len(collector.State(state).pending))  # saved, nothing recorded as missing
        self.assertEqual({}, collector.observation_files(state))  # and no empty day file


if __name__ == "__main__":
    unittest.main()
