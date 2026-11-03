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


def stop(code: str, at: int, actual: int | None = None, *, first: bool = False, kind: int = 1) -> dict[str, Any]:
    """One ``fermate`` row like the app's fixture: ``at`` minutes after midnight of DAY, ``actual`` seconds late."""
    midnight = collector.midnight_millis(DAY)
    scheduled = midnight + at * MINUTE
    row: dict[str, Any] = {"id": code, "stazione": code, "actualFermataType": kind, "programmata": scheduled,
                           "partenza_teorica": scheduled, "arrivo_teorico": None if first else scheduled}
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

    def test_due_trains_are_those_arrived_between_four_hours_and_twenty_minutes_ago(self) -> None:
        selected = {train.number: train for train in collector.due_trains(self.timetable, NOW)}
        # 2001 arrives 09:40 (08:40 UTC), 2003 at 12:20 (11:20 UTC), 9558 at 10:10, 2011 at 10:00, 1963 (service day
        # 3 November) at 10:30 the next morning, 1965 at 09:00 exactly four hours ago.
        self.assertEqual({"2001", "2003", "9558", "2011", "1963", "1965"}, set(selected))
        self.assertEqual("2026-11-03", selected["1963"].day)
        self.assertEqual("2026-11-04", selected["1965"].day)  # leaves at 24:30 of the 3 November service day
        self.assertEqual(int(datetime(2026, 11, 4, 8, 0, tzinfo=timezone.utc).timestamp()), selected["1965"].arrival)
        self.assertEqual("andamentoTreno/S08409/1965/1793746800000", collector.run_path(selected["1965"]))
        just_before = {train.number for train in collector.due_trains(self.timetable, datetime(2026, 11, 4, 11, 39, tzinfo=timezone.utc))}
        self.assertNotIn("2003", just_before)  # arrived only 19 minutes earlier
        later = {train.number for train in collector.due_trains(self.timetable, datetime(2026, 11, 4, 12, 1, tzinfo=timezone.utc))}
        self.assertNotIn("1965", later)
        self.assertEqual([train.arrival for train in collector.due_trains(self.timetable, NOW)],
                         sorted(train.arrival for train in selected.values()))


class InterpretTest(unittest.TestCase):
    def test_a_full_answer_keeps_station_delays_and_suppressed_stops_only(self) -> None:
        payload = collector.strict_json((FIXTURES / "andamentoTreno-9558-arrivato.json").read_bytes())
        train = collector.Due(DAY.isoformat(), "S08409", "9558", "FR", 0)
        observation = collector.interpret(payload, train)
        self.assertEqual("ok", observation.status)
        # Departure delay at the origin, arrival delay elsewhere, early arrival negative, suppressed stop without sample.
        self.assertEqual([["S08409", 1, 0], ["S08217", 2, 0], ["S06421", -2, 0], ["S05043", None, 1], ["S01700", 9, 0]],
                         observation.stops)

    def test_delays_truncate_towards_zero_like_the_app(self) -> None:
        stops = [stop("S08235", 540, 59, first=True), stop("S08409", 580, 359), stop("S08217", 590, -59), stop("S08406", 600, -61)]
        self.assertEqual([0, 5, 0, -1], [entry[1] for entry in collector.interpret(run(stops=stops), due()).stops])

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
        self.assertEqual([["S08235", 0, 0], ["S08217", 2, 0], ["S08409", None, 1]], observation.stops)

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
        self.assertEqual([["S08235", 1, 0], ["S08409", None, 0], ["S08406", 0, 0]], observation.stops)
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

    def test_files_are_deterministic_atomic_and_pruned_after_35_days(self) -> None:
        state = collector.State(self.temp / "state")
        for day in ("2026-09-29", "2026-09-30", "2026-11-04"):
            state.add(self.record(day=day))
        state.save(date(2026, 11, 3))  # 2026-09-29 is 35 days before: kept
        self.assertTrue((self.temp / "state/obs-2026-09-29.jsonl.gz").is_file())
        state.save(date(2026, 11, 4))
        names = sorted(path.name for path in (self.temp / "state").iterdir())
        self.assertEqual(["obs-2026-09-30.jsonl.gz", "obs-2026-11-04.jsonl.gz", "pending.jsonl.gz"], names)
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
        self.assertEqual(6, len(answers.paths))
        self.assertEqual(6, report.counters["arrivati"])
        self.assertEqual(12, report.counters["campioni"])
        self.assertEqual({}, state.pending)
        again = Answers(self.answer_all)
        _, second = self.collect(again)
        self.assertEqual([], again.paths)
        self.assertEqual(6, second.counters["gia_noti"])
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

    def test_a_refusing_source_stops_the_run_at_once(self) -> None:
        refused = Answers(lambda _: collector.FetchResult("refused", reason="http-429"))
        state, report = self.collect(refused)
        self.assertEqual(1, len(refused.paths))
        self.assertIn("http-429", report.stop_reason)
        self.assertEqual(6, len(state.pending))

    def test_request_budget_and_deadline(self) -> None:
        answers = Answers(self.answer_all)
        state, report = self.collect(answers, max_requests=2)
        self.assertEqual(2, len(answers.paths))
        self.assertEqual("limite --max-requests", report.stop_reason)
        self.assertEqual(4, len(state.pending))
        ticks = iter([0.0, 0.0, 5.0, 11.0])
        answers = Answers(self.answer_all)
        state, report = self.collect(answers, deadline=10.0, clock=lambda: next(ticks, 99.0))
        self.assertEqual(3, len(answers.paths))  # earlier pending first, then one new train before the deadline
        self.assertEqual("limite --deadline-seconds", report.stop_reason)


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
        self.assertEqual(run(), json.loads(results[0].body))
        self.assertEqual([0.5] * 7, self.slept)  # 2 requests per second, never faster
        for _, headers in self.server.requests:
            self.assertEqual(collector.USER_AGENT, headers.get("User-Agent"))
            self.assertNotIn("Cookie", headers)
        self.assertEqual(8, self.fetcher.requests)
        with self.assertRaises(collector.CollectorError):
            collector.HttpFetcher(rate=3)

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
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, collector.main(arguments))
        log = output.getvalue() + summary.read_text(encoding="utf-8")
        self.assertIn("Treni chiesti: 6", log)
        self.assertIn("Arrivati: 2", log)  # 9558 and 2001
        self.assertIn("Cancellati: 1", log)  # 2003
        self.assertIn("Sconosciuti a ViaggiaTreno: 1", log)  # 1963: the server answers 204
        self.assertIn("Non ancora arrivati (in sospeso): 1", log)  # 2011
        self.assertIn("Errore json-non-valido: 1", log)
        for leaked in ("MILANO", "maintenance", "html", "ROMA", "soppressa", "S08409"):
            self.assertNotIn(leaked, log)  # counts only, never text from a response
        self.assertEqual(6, len(server.requests))
        pending = {(entry["n"]) for entry in collector.State(state).pending.values()}
        self.assertEqual({"2011", "1965"}, pending)

        # Second run, same instant: nothing new is asked except the two pending trains.
        server.answer(selected["2011"], run(number="2011", origin="S08409",
                                            stops=[stop("S08409", 540, 0, first=True), stop("S08235", 600, -90)]))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, collector.main(arguments))
        self.assertEqual(8, len(server.requests))

        output_path = self.temp / "stats_v1.json.gz"
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, stats.main(["--state", str(state), "--output", str(output_path),
                                            "--as-of", "2026-11-05", "--generated-at", "2026-11-05T04:00:00Z"]))
        payload = stats.check_bytes(output_path.read_bytes())
        trains = {(train["n"], train["o"]): train for train in payload["trains"]}
        self.assertEqual({("2001", "S08235"), ("2003", "S08409"), ("2011", "S08409"), ("9558", "S08409")}, set(trains))
        self.assertEqual({"n": "2003", "o": "S08409", "c": "REG", "d": 1, "x": 1, "s": []}, trains[("2003", "S08409")])
        self.assertEqual(["S08409", 1, 0.0, [1] * 8, 0], trains[("2011", "S08409")]["s"][0])
        self.assertEqual(["S08235", 1, 0.0, [1] * 8, 0], trains[("2011", "S08409")]["s"][1])  # 90 s early
        fr = trains[("9558", "S08409")]
        self.assertEqual(("FR", 1, 0), (fr["c"], fr["d"], fr["x"]))
        self.assertEqual(["S08409", "S08217", "S06421", "S01700"], [entry[0] for entry in fr["s"]])  # suppressed stop left out
        self.assertEqual(["S01700", 1, 9.0, [0, 0, 0, 1, 1, 1, 1, 1], 0], fr["s"][-1])


if __name__ == "__main__":
    unittest.main()
