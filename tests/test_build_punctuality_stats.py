#!/usr/bin/env python3
"""Offline tests for tools/build_punctuality_stats.py: aggregation, deterministic encoding and the --check validator."""
from __future__ import annotations

import contextlib
import copy
import gzip
import io
import json
import random
import shutil
import sys
import tempfile
import unittest
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import build_punctuality_stats as stats  # noqa: E402
import ci_log_frames  # noqa: E402
import collect_punctuality as collector  # noqa: E402

AS_OF = date(2026, 11, 10)
GENERATED = datetime(2026, 11, 10, 4, 0, tzinfo=timezone.utc)


def record(day: str, number: str = "9558", origin: str = "S08409", status: str = "ok", stops: list[list[Any]] | None = None,
           category: str = "FR", t: int = 1) -> dict[str, Any]:
    return {"d": day, "o": origin, "n": number, "c": category, "st": status, "t": t,
            "s": (stops or []) if status == "ok" else []}


def days_back(count: int) -> list[str]:
    """``count`` departure days ending the day before AS_OF."""
    return [(AS_OF - timedelta(days=offset)).isoformat() for offset in range(1, count + 1)]


class AggregateTest(unittest.TestCase):
    def test_buckets_are_inclusive_and_cumulative(self) -> None:
        delays = [-3, 0, 1, 2, 3, 5, 6, 10, 15, 16, 20, 29, 30, 60, 61, 300]
        records = [record(day, stops=[["S08409", 0, 0, 420], ["S01700", delay, 0, 610]])
                   for day, delay in zip(days_back(16), delays)]
        train = stats.aggregate(records)[0]
        origin, destination = train["s"]
        # Lines collected before platforms existed: no platform; 16 samples, so weekdays (Monday 9 November back to
        # Sunday 25 October: three Mondays and Sundays, two of every other day).
        self.assertEqual(["S08409", 16, 0.0, [16] * 8, 0, 0, 420, None,
                          [[3, 3, 3], [2, 2, 2], [2, 2, 2], [2, 2, 2], [2, 2, 2], [2, 2, 2], [3, 3, 3]]], origin)
        self.assertEqual("S01700", destination[0])
        self.assertEqual(16, destination[1])
        # <= 0: -3, 0 | <= 2: +1, 2 | <= 5: +3, 5 | <= 10: +6, 10 | <= 15: +15 | <= 20: +16, 20 | <= 30: +29, 30 | <= 60: +60
        self.assertEqual([2, 4, 6, 8, 9, 11, 13, 14], destination[3])
        # Early arrivals count as 0 in the mean: (0+0+1+2+3+5+6+10+15+16+20+29+30+60+61+300) / 16 = 34.875 -> 34.9
        self.assertEqual(34.9, destination[2])
        self.assertEqual([300, 610, None], destination[5:8])
        # Monday -3, 10, 61 | Tuesday 6, 60 | Wednesday 5, 30 | Thursday 3, 29 | Friday 2, 20 | Saturday 1, 16 |
        # Sunday 0, 15, 300: 5 and 15 minutes are on time.
        self.assertEqual([[3, 1, 2], [2, 0, 1], [2, 1, 1], [2, 1, 1], [2, 1, 1], [2, 1, 1], [3, 1, 2]], destination[8])
        self.assertEqual(9, len(destination))

    def test_mean_rounds_half_up_with_one_decimal(self) -> None:
        for delays, expected in (([1, 2], 1.5), ([0, 0, 1], 0.3), ([0, 0, 0, 1], 0.3), ([1, 1, 1, 2, 2, 2, 2, 2], 1.6),
                                 ([2, 2, 2, 2, 2, 2, 2, 3], 2.1), ([-5, -5], 0.0), ([7], 7.0)):
            with self.subTest(delays=delays):
                self.assertEqual(expected, stats._mean_tenths(delays))
                self.assertIsInstance(stats._mean_tenths(delays), float)

    def test_days_cancellations_suppressions_and_stop_order(self) -> None:
        days = days_back(6)
        records = [
            record(days[0], stops=[["S08409", 1, 0], ["S08217", 4, 0], ["S01700", 6, 0]]),
            record(days[1], stops=[["S08409", 0, 0], ["S08217", None, 1], ["S01700", 3, 0]]),
            # A day with an extra stop between the others (ordered by its relative position) and no actual at the end.
            record(days[2], stops=[["S08409", 2, 0], ["S08217", 1, 0], ["S06421", 5, 0], ["S01700", None, 0]]),
            record(days[3], status="cancelled"),
            record(days[4], status="unknown"),
            record(days[5], status="missing"),
            record(days[0], status="unknown", t=9),  # the same day again: the known record wins
        ]
        train = stats.aggregate(records)[0]
        self.assertEqual((4, 1), (train["d"], train["x"]))
        self.assertEqual(["S08409", "S08217", "S06421", "S01700"], [stop[0] for stop in train["s"]])
        by_code = {stop[0]: stop for stop in train["s"]}
        # Lines collected before scheduled minutes existed: largest delay, no scheduled minute, platform or weekdays.
        self.assertEqual(["S08217", 2, 2.5, [0, 1, 2, 2, 2, 2, 2, 2], 1, 4, None, None, None], by_code["S08217"])
        self.assertEqual(["S01700", 2, 4.5, [0, 0, 1, 2, 2, 2, 2, 2], 0, 6, None, None, None], by_code["S01700"])
        # A stop never sampled (always suppressed) is left out.
        only_suppressed = [record(day, stops=[["S08409", 1, 0], ["S08217", None, 1]]) for day in days[:3]]
        self.assertEqual(["S08409"], [stop[0] for stop in stats.aggregate(only_suppressed)[0]["s"]])
        # A train only ever cancelled keeps its days, without stops; unknown-only trains are not listed.
        listed = stats.aggregate([record(days[0], number="100", status="cancelled"), record(days[1], number="101", status="unknown")])
        self.assertEqual([{"n": "100", "o": "S08409", "c": "FR", "d": 1, "x": 1, "s": []}], listed)

    def test_max_delay_and_the_scheduled_minute_of_the_most_recent_day(self) -> None:
        days = days_back(5)  # days[0] is the most recent
        records = [
            record(days[1], stops=[["S08409", -1, 0, 425], ["S00219", None, 1, 700], ["S01700", 3, 0, 605]]),
            record(days[4], stops=[["S08409", 2, 0], ["S01700", 12, 0]]),  # collected before scheduled minutes
            record(days[0], stops=[["S08409", 0, 0, 430], ["S01700", None, 0, None]]),  # no time today at Milano
            record(days[2], stops=[["S08409", 1, 0, 420], ["S01700", 61, 0, 600]]),
            record(days[3], status="cancelled"),
        ]
        train = stats.aggregate(records)[0]
        by_code = {stop[0]: stop for stop in train["s"]}
        self.assertEqual(["S08409", "S01700"], [stop[0] for stop in train["s"]])  # S00219 only suppressed
        self.assertEqual(["S08409", 4, 0.8, [2, 4, 4, 4, 4, 4, 4, 4], 0, 2, 430, None, None], by_code["S08409"])
        # The latest day with a scheduled time at Milano is days[1]; 61 minutes is beyond every bucket.
        self.assertEqual(["S01700", 3, 25.3, [0, 0, 1, 1, 2, 2, 2, 2], 0, 61, 605, None, None], by_code["S01700"])
        # Early arrivals only: the largest delay is 0.  A stop reached after midnight keeps minutes beyond 1439.
        night = [record(day, number="1963", category="ICN", stops=[["S08409", -2, 0, 1320], ["S01700", -1, 0, 2070]])
                 for day in days[:2]]
        self.assertEqual([["S08409", 2, 0.0, [2] * 8, 0, 0, 1320, None, None], ["S01700", 2, 0.0, [2] * 8, 0, 0, 2070, None, None]],
                         stats.aggregate(night)[0]["s"])
        payload = dict(valid_payload(), trains=stats.aggregate(records + night))
        self.assertEqual(payload, stats.check_bytes(stats.encode(payload)))

    def test_the_usual_platform_counts_actual_platforms_of_the_sampled_days(self) -> None:
        days = days_back(12)  # days[0] is the most recent

        def stops(origin: list[Any], destination: list[Any]) -> list[list[Any]]:
            return [["S08409", *origin], ["S01700", *destination]]

        records = [
            # Roma: actual 12 on five days, actual 14 on two, programmed only on two (not counted while actual ones
            # exist), no platform on one; a day without an actual time and a suppressed day are not counted either.
            *[record(day, stops=stops([1, 0, 420, "12", 1], [3, 0, 610, None, 0])) for day in days[:5]],
            *[record(day, stops=stops([2, 0, 420, "14", 1], [4, 0, 610, None, 0])) for day in days[5:7]],
            *[record(day, stops=stops([0, 0, 420, "12", 0], [5, 0, 610, "2", 0])) for day in days[7:9]],
            record(days[9], stops=stops([0, 0, 420, None, 0], [1, 0, 610, "3", 0])),
            record(days[10], stops=stops([None, 0, 420, "14", 1], [None, 0, 610, "2", 0])),
            record(days[11], stops=stops([None, 1, 420, None, 0], [2, 0, 610, "2", 0])),
            record(days_back(13)[12], status="cancelled"),
        ]
        by_code = {stop[0]: stop for stop in stats.aggregate(records)[0]["s"]}
        self.assertEqual(["12", 5, 7], by_code["S08409"][7])
        # Milano never had an actual platform: the programmed ones, counted by themselves (2 on three sampled days
        # of four with a programmed platform; days[10] has no actual time).
        self.assertEqual(["2", 3, 4], by_code["S01700"][7])
        # A tie goes to the platform of the most recent day, then to the first name.
        tie = [record(days[0], stops=[["S08409", 0, 0, 420, "3 Ovest", 1]]), record(days[1], stops=[["S08409", 0, 0, 420, "1", 1]]),
               record(days[2], stops=[["S08409", 0, 0, 420, "1", 1]]), record(days[3], stops=[["S08409", 0, 0, 420, "3 Ovest", 1]])]
        self.assertEqual(["3 Ovest", 2, 4], stats.aggregate(tie)[0]["s"][0][7])
        same_day = [record(days[0], number="1", stops=[["S08409", 0, 0, 420, "B", 1]]),
                    record(days[0], number="1", origin="S00219", stops=[["S00219", 0, 0, 420, "A", 1]])]
        self.assertEqual([["A", 1, 1]], [train["s"][0][7] for train in stats.aggregate(same_day) if train["o"] == "S00219"])
        # Lines with and without platforms mix: the old ones only add samples.
        mixed = [record(days[0], stops=[["S08409", 0, 0, 420, "7", 1]]), record(days[1], stops=[["S08409", 0, 0]])]
        self.assertEqual(["7", 1, 1], stats.aggregate(mixed)[0]["s"][0][7])
        payload = dict(valid_payload(), trains=stats.aggregate(records + [dict(r, n="9") for r in tie]))
        self.assertEqual(payload, stats.check_bytes(stats.encode(payload)))

    def test_weekday_punctuality_from_the_departure_day_with_fourteen_samples(self) -> None:
        days = days_back(21)  # three weeks: every weekday three times
        delays = {0: [0, 4, 5], 1: [6, 15, 16], 2: [-1, -1, 0], 3: [20, 3, 9], 4: [5, 5, 5], 5: [], 6: [30, 60, 61]}
        records = []
        for day in days:
            weekday = date.fromisoformat(day).weekday()
            if delays[weekday]:
                records.append(record(day, stops=[["S08409", delays[weekday].pop(), 0, 420, None, 0]]))
        self.assertEqual(18, len(records))
        stop = stats.aggregate(records)[0]["s"][0]
        self.assertEqual(18, stop[1])
        # Monday..Sunday [samples, <= 5, <= 15]; no train on Saturdays.
        self.assertEqual([[3, 3, 3], [3, 0, 2], [3, 3, 3], [3, 1, 2], [3, 3, 3], [0, 0, 0], [3, 0, 0]], stop[8])
        self.assertEqual(10, stop[3][2])  # <= 5 of the stop: the sum of the weekday column
        self.assertEqual(13, stop[3][4])  # <= 15
        thirteen = stats.aggregate(records[:13])[0]["s"][0]
        self.assertEqual((13, None), (thirteen[1], thirteen[8]))
        fourteen = stats.aggregate(records[:14])[0]["s"][0]
        self.assertEqual(14, sum(day[0] for day in fourteen[8]))
        payload = dict(valid_payload(), trains=stats.aggregate(records))
        self.assertEqual(payload, stats.check_bytes(stats.encode(payload)))

    def test_trains_are_keyed_by_number_and_origin_and_sorted_numerically(self) -> None:
        day = days_back(1)[0]
        records = [record(day, number=number, origin=origin, stops=[[origin, 0, 0]])
                   for number, origin in (("9558", "S08409"), ("10", "S00219"), ("9558", "S01700"), ("100", "S08409"))]
        records.append(record(days_back(2)[1], number="10", origin="S00219", stops=[["S00219", 0, 0]], category="RV"))
        records.append(record(days_back(3)[2], number="10", origin="S00219", stops=[["S00219", 0, 0]], category="RV"))
        trains = stats.aggregate(records)
        self.assertEqual([("10", "S00219"), ("100", "S08409"), ("9558", "S01700"), ("9558", "S08409")],
                         [(train["n"], train["o"]) for train in trains])
        self.assertEqual("RV", trains[0]["c"])  # the category of most days

    def test_each_train_says_how_long_before_the_window_end_it_was_last_known(self) -> None:
        # alexa-3: a train dropped by a timetable change keeps 30 days of statistics; "l" (0 = known on windowEnd) lets
        # a reader tell it apart from a train that still runs.
        stops = [["S08409", 1, 0]]
        records = [
            record("2026-11-09", number="1", stops=stops), record("2026-11-01", number="1", stops=stops),
            record("2026-11-02", number="2", stops=stops), record("2026-11-05", number="2", status="cancelled"),
            record("2026-11-08", number="2", status="unknown"),  # not known: does not count
            record("2026-10-11", number="3", status="partial", stops=stops),
        ]
        records[-1]["s"] = stops
        trains = stats.aggregate(records, window_end=date(2026, 11, 9))
        self.assertEqual({"1": 0, "2": 4, "3": 29}, {train["n"]: train["l"] for train in trains})
        self.assertNotIn("l", stats.aggregate(records)[0])  # only with a window
        temp = Path(tempfile.mkdtemp(prefix="punctuality-last-"))
        self.addCleanup(shutil.rmtree, temp, ignore_errors=True)
        state = collector.State(temp)
        state.add(record("2026-11-07", stops=stops))
        state.save(AS_OF)
        payload = stats.build(temp, AS_OF, GENERATED)
        self.assertEqual(2, payload["trains"][0]["l"])
        self.assertEqual(payload, stats.check_bytes(stats.encode(payload)))

    def test_the_window_is_the_last_thirty_complete_days(self) -> None:
        self.assertEqual((date(2026, 10, 11), date(2026, 11, 9)), stats.window(AS_OF))
        temp = Path(tempfile.mkdtemp(prefix="punctuality-stats-"))
        self.addCleanup(shutil.rmtree, temp, ignore_errors=True)
        state = collector.State(temp)
        for day in ("2026-10-10", "2026-10-11", "2026-11-09", "2026-11-10"):
            state.add(record(day, stops=[["S08409", 1, 0]]))
        state.save(AS_OF)
        payload = stats.build(temp, AS_OF, GENERATED)
        self.assertEqual(("2026-10-11", "2026-11-09", 30), (payload["windowStart"], payload["windowEnd"], payload["windowDays"]))
        self.assertEqual(2, payload["trains"][0]["d"])
        self.assertEqual("2026-11-10T04:00:00Z", payload["generatedAt"])
        self.assertEqual("ViaggiaTreno, elaborazione Binario (non ufficiale)", payload["source"])
        self.assertEqual([0, 2, 5, 10, 15, 20, 30, 60], payload["buckets"])


def valid_payload() -> dict[str, Any]:
    return {
        "schema": stats.SCHEMA, "generatedAt": "2026-11-10T04:00:00Z", "windowStart": "2026-10-11",
        "windowEnd": "2026-11-09", "windowDays": 30, "source": stats.SOURCE, "buckets": list(stats.BUCKETS),
        "trains": [
            {"n": "2001", "o": "S08235", "c": "REG", "d": 3, "x": 0,
             "s": [["S08235", 3, 0.3, [2, 3, 3, 3, 3, 3, 3, 3], 0, 1, 540, ["1", 2, 3], None]]},
            {"n": "9558", "o": "S08409", "c": "FR", "d": 28, "x": 1, "s": [
                ["S08409", 27, 0.8, [20, 24, 26, 27, 27, 27, 27, 27], 0, 7, 420, ["12", 20, 25],
                 [[4, 4, 4], [4, 4, 4], [4, 3, 4], [4, 4, 4], [4, 4, 4], [4, 4, 4], [3, 3, 3]]],
                ["S00219", 26, 4.6, [9, 14, 19, 23, 25, 26, 26, 26], 1, 18, 610, None,
                 [[4, 3, 4], [4, 3, 4], [4, 3, 4], [4, 3, 3], [4, 3, 4], [3, 2, 3], [3, 2, 3]]],
            ]},
        ],
    }


def legacy_payload(length: int = 5) -> dict[str, Any]:
    """``valid_payload`` as files built before the extensions of 4 November 2026 had it: stops of five elements, or
    of seven before the usual platform and the weekdays."""
    payload = valid_payload()
    for train in payload["trains"]:
        train["s"] = [stop[:length] for stop in train["s"]]
    return payload


class EncodeAndCheckTest(unittest.TestCase):
    def test_encoding_is_deterministic_and_checked(self) -> None:
        payload = valid_payload()
        data = stats.encode(payload)
        self.assertEqual(data, stats.encode(copy.deepcopy(payload)))
        self.assertEqual(b"\x1f\x8b\x08\x00\x00\x00\x00\x00", data[:8])
        self.assertEqual(payload, stats.check_bytes(data))
        raw = gzip.decompress(data)
        self.assertTrue(raw.startswith(b'{"buckets":[0,2,5,10,15,20,30,60],"generatedAt":"2026-11-10T04:00:00Z"'))
        self.assertNotIn(b" ", raw.replace(stats.SOURCE.encode(), b""))

    def test_check_accepts_the_optional_last_known_field_within_the_window(self) -> None:
        for value in (0, 2):  # train 9558 is known on 28 days of 30: last known at most 2 days before windowEnd
            payload = valid_payload()
            payload["trains"][1]["l"] = value
            self.assertEqual(payload, stats.check_bytes(stats.encode(payload)))
        for value in (3, -1, 30, "0", True, None, 1.0):
            with self.subTest(value=value):
                payload = valid_payload()
                payload["trains"][1]["l"] = value
                with self.assertRaisesRegex(stats.CheckError, "l fuori"):
                    stats.check_bytes(stats.encode(payload))
        payload = valid_payload()
        payload["trains"][1]["z"] = 0
        with self.assertRaisesRegex(stats.CheckError, "chiavi diverse"):
            stats.check_bytes(stats.encode(payload))

    def test_check_rejects_malformed_files(self) -> None:
        def mutate(change) -> bytes:
            payload = valid_payload()
            change(payload)
            return stats.encode(payload)

        def gzip_with(raw: bytes, mtime: int = 0, name: str = "") -> bytes:
            buffer = io.BytesIO()
            with gzip.GzipFile(filename=name, mode="wb", fileobj=buffer, mtime=mtime) as stream:
                stream.write(raw)
            return buffer.getvalue()

        canonical = stats.canonical_json(valid_payload())
        cases = {
            "gzip": b"not gzip at all, not even close",
            "data presenti": gzip_with(canonical, mtime=1_700_000_000),
            "nome": gzip_with(canonical, name="stats.json"),
            "più membri": stats.encode(valid_payload()) + stats.encode(valid_payload()),
            "troncato": stats.encode(valid_payload())[:-12],
            "byte: oltre": b"\x1f\x8b\x08\x00" + b"\0" * stats.MAX_GZIP_BYTES,
            "JSON non valido": gzip_with(b'{"schema": NaN}'),
            "chiavi duplicate": gzip_with(b'{"schema":"a","schema":"b"}'),
            "non canonico": gzip_with(json.dumps(valid_payload(), indent=1).encode()),
            "chiavi di primo livello": mutate(lambda p: p.pop("source")),
            "schema diverso": mutate(lambda p: p.update(schema="binario-punctuality-v2")),
            "source diverso": mutate(lambda p: p.update(source="Trenitalia")),
            "buckets diversi": mutate(lambda p: p.update(buckets=[0, 5, 15])),
            "generatedAt non": mutate(lambda p: p.update(generatedAt="2026-11-10 04:00")),
            "generatedAt precede": mutate(lambda p: p.update(generatedAt="2026-11-01T04:00:00Z")),
            "windowDays non corrisponde": mutate(lambda p: p.update(windowDays=31)),
            "windowEnd non è una data": mutate(lambda p: p.update(windowEnd="2026-11-31")),
            "chiavi diverse": mutate(lambda p: p["trains"][0].update(extra=1)),
            "numero o origine": mutate(lambda p: p["trains"][0].update(o="8235")),
            "categoria non valida": mutate(lambda p: p["trains"][0].update(c="reg")),
            "non ordinati": mutate(lambda p: p["trains"].reverse()),
            "ripetuti": mutate(lambda p: p["trains"].append(copy.deepcopy(p["trains"][1]))),
            "d o x fuori": mutate(lambda p: p["trains"][0].update(x=4)),
            "d o x fuori intervallo": mutate(lambda p: p["trains"][1].update(d=31)),
            "campioni fuori": mutate(lambda p: p["trains"][1].update(x=5)),
            "cumulati non monotoni": mutate(lambda p: p["trains"][1]["s"][1].__setitem__(3, [9, 14, 13, 23, 25, 26, 26, 26])),
            "cumulati non validi": mutate(lambda p: p["trains"][1]["s"][1].__setitem__(3, [9, 14, 19, 23, 25, 26, 26, 27])),
            "uno per soglia": mutate(lambda p: p["trains"][1]["s"][1].__setitem__(3, [9, 14, 19])),
            "più di un decimale": mutate(lambda p: p["trains"][1]["s"][1].__setitem__(2, 4.65)),
            "ritardo medio fuori": mutate(lambda p: p["trains"][1]["s"][1].__setitem__(2, -1.0)),
            "incompatibile": mutate(lambda p: p["trains"][1]["s"][1].__setitem__(2, 70.0)),
            "media positiva": mutate(lambda p: p["trains"][0]["s"][0].__setitem__(3, [3] * 8)),
            "giorni soppressi": mutate(lambda p: p["trains"][1]["s"][1].__setitem__(4, 2)),
            "codice stazione": mutate(lambda p: p["trains"][1]["s"][1].__setitem__(0, "Torino")),
            "fermata S08409 ripetuta": mutate(lambda p: p["trains"][1]["s"].append(copy.deepcopy(p["trains"][1]["s"][0]))),
            "[codice, campioni": mutate(lambda p: p["trains"][1]["s"][1].pop()),
        }
        for expected, data in cases.items():
            with self.subTest(expected=expected):
                with self.assertRaises(stats.CheckError) as raised:
                    stats.check_bytes(data)
                self.assertIn(expected, str(raised.exception))

    def test_check_rejects_bad_max_delays_and_scheduled_minutes(self) -> None:
        def stop_value(train: int, stop: int, index: int, value: Any) -> bytes:
            payload = valid_payload()
            payload["trains"][train]["s"][stop][index] = value
            return stats.encode(payload)

        def first_stop(values: list[Any]) -> bytes:
            payload = valid_payload()
            payload["trains"][0]["s"][0] = values
            return stats.encode(payload)

        cases: list[tuple[str, str, bytes]] = []
        for value in (1441, -1, None, True, 7.0, "7"):
            cases.append(("ritardo massimo fuori", repr(value), stop_value(1, 0, 5, value)))
        # S08409: 26 of 27 samples <= 5, all <= 10, so its largest delay is 6..10; S00219: all <= 20, not all <= 15.
        for value in (5, 11):
            cases.append(("ritardo massimo incompatibile", f"S08409 {value}", stop_value(1, 0, 5, value)))
        cases.append(("ritardo massimo incompatibile", "S00219 21", stop_value(1, 1, 5, 21)))
        cases.append(("ritardo massimo incompatibile", "all on time", first_stop(["S08235", 3, 0.0, [3] * 8, 0, 1, 540])))
        # One sample beyond 60 minutes: the largest delay must be above 60.
        cases.append(("ritardo massimo incompatibile", "beyond 60", first_stop(["S08235", 3, 21.0, [2] * 8, 0, 60, 540])))
        cases.append(("ritardo medio oltre il ritardo massimo", "mean 4.5, max 3",
                      first_stop(["S08235", 2, 4.5, [0, 0, 2, 2, 2, 2, 2, 2], 0, 3, 540])))
        cases.append(("ritardo massimo oltre la somma", "3 samples of mean 21.0, max 120",
                      first_stop(["S08235", 3, 21.0, [2] * 8, 0, 120, 540])))
        for value in (2880, -1, 1439.0, "540", True):
            cases.append(("minuto programmato", repr(value), stop_value(1, 1, 6, value)))
        for length in (6, 8, 10):
            payload = valid_payload()
            payload["trains"][1]["s"][1] = (payload["trains"][1]["s"][1] + [0])[:length]
            cases.append(("seguito o no da [ritardo massimo, minuto programmato] e da [binario, giorni della settimana]",
                          f"{length} elements", stats.encode(payload)))
        for expected, label, data in cases:
            with self.subTest(expected=expected, label=label):
                with self.assertRaises(stats.CheckError) as raised:
                    stats.check_bytes(data)
                self.assertIn(expected, str(raised.exception))

    def test_check_rejects_bad_platforms_and_weekdays(self) -> None:
        def stop_value(train: int, stop: int, index: int, value: Any) -> bytes:
            payload = valid_payload()
            payload["trains"][train]["s"][stop][index] = value
            return stats.encode(payload)

        week = [[4, 4, 4], [4, 4, 4], [4, 3, 4], [4, 4, 4], [4, 4, 4], [4, 4, 4], [3, 3, 3]]  # 9558 at Roma

        def weekday(day: int, triple: list[Any]) -> list[Any]:
            changed = copy.deepcopy(week)
            changed[day] = triple
            return changed

        cases: list[tuple[str, Any, bytes]] = []
        for platform in ([" 12", 20, 25], ["12 ", 20, 25], ["3  Ovest", 20, 25], ["1 Tronco Ovest", 20, 25], ["", 20, 25],
                         [12, 20, 25], [None, 20, 25], ["<b>", 20, 25], ["12\n", 20, 25]):
            cases.append(("nome del binario non valido", platform, stop_value(1, 0, 7, platform)))
        for platform in (["12", 0, 25], ["12", 26, 25], ["12", 20, 28], ["12", 20.0, 25], ["12", True, 25], ["12", 20, None]):
            cases.append(("giorni del binario fuori", platform, stop_value(1, 0, 7, platform)))
        for platform in (["12", 20], ["12", 20, 25, 1], "12", {"name": "12"}, 0):
            cases.append(("binario non è null", platform, stop_value(1, 0, 7, platform)))
        for weekdays in (week[:6], week + [[0, 0, 0]], [[4, 4]] + week[1:], weekday(0, [4, 4, 4.0]), weekday(0, [4, 4, True]),
                         weekday(0, None), [], {}, 0):
            cases.append(("giorni della settimana non sono null", weekdays, stop_value(1, 0, 8, weekdays)))
        for triple in ([4, 5, 4], [3, 3, 4], [-1, 0, 0], [4, 4, 3]):
            cases.append(("fuori da 0 <= <=5 <= <=15", triple, stop_value(1, 0, 8, weekday(0, triple))))
        cases.append(("oltre i campioni della fermata", "28 samples", stop_value(1, 0, 8, weekday(6, [4, 3, 4]))))
        # S00219: 19 samples within 5 minutes and 25 within 15 of 26: a week with 20 or with 26 is not part of it, nor
        # one with 7 later than 5 minutes when the stop has 7 of them... in 25 samples of 26.
        s00219 = [[4, 3, 4], [4, 3, 4], [4, 3, 4], [4, 3, 3], [4, 3, 4], [3, 2, 3], [3, 2, 3]]
        for day, triple in ((5, [3, 3, 3]), (3, [4, 3, 4]), (5, [2, 0, 2])):
            changed = copy.deepcopy(s00219)
            changed[day] = triple
            cases.append(("incompatibili con i cumulati", f"S00219 {triple}", stop_value(1, 1, 8, changed)))
        cases.append(("con meno di 14 campioni", "2001 with weekdays",
                      stop_value(0, 0, 8, [[1, 1, 1], [1, 1, 1], [1, 0, 1], [0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0]])))
        for expected, label, data in cases:
            with self.subTest(expected=expected, label=label):
                with self.assertRaises(stats.CheckError) as raised:
                    stats.check_bytes(data)
                self.assertIn(expected, str(raised.exception))

    def test_check_accepts_five_seven_and_nine_element_stops(self) -> None:
        for length in (5, 7):
            legacy = legacy_payload(length)
            self.assertEqual(legacy, stats.check_bytes(stats.encode(legacy)))
        mixed = valid_payload()
        mixed["trains"][1]["s"][1] = mixed["trains"][1]["s"][1][:5]
        mixed["trains"][1]["s"][0] = mixed["trains"][1]["s"][0][:7]
        self.assertEqual(mixed, stats.check_bytes(stats.encode(mixed)))
        for platform in (None, ["3 Ovest", 1, 1], ["I", 27, 27], ["Città", 5, 9], ["1A", 1, 27], ["XIII Tronco", 3, 4], ["3/4", 2, 2]):
            with self.subTest(platform=platform):
                payload = valid_payload()
                payload["trains"][1]["s"][0][7] = platform
                self.assertEqual(payload, stats.check_bytes(stats.encode(payload)))
        # Weekday samples may be fewer than the stop's (never more); null is always allowed.
        for weekdays in (None, [[4, 4, 4], [4, 4, 4], [4, 3, 4], [4, 4, 4], [4, 4, 4], [4, 4, 4], [0, 0, 0]],
                         [[27, 26, 27], [0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0]]):
            with self.subTest(weekdays=weekdays):
                payload = valid_payload()
                payload["trains"][1]["s"][0][8] = weekdays
                self.assertEqual(payload, stats.check_bytes(stats.encode(payload)))
        accepted = {
            "unknown scheduled minute": ["S08235", 3, 0.3, [2, 3, 3, 3, 3, 3, 3, 3], 0, 1, None],
            "after midnight": ["S08235", 3, 0.3, [2, 3, 3, 3, 3, 3, 3, 3], 0, 1, 1450],
            "last minute of the second day": ["S08235", 3, 0.3, [2, 3, 3, 3, 3, 3, 3, 3], 0, 1, 2879],
            "midnight": ["S08235", 3, 0.3, [2, 3, 3, 3, 3, 3, 3, 3], 0, 1, 0],
            "all on time": ["S08235", 3, 0.0, [3] * 8, 0, 0, 540],
            "beyond 60": ["S08235", 3, 21.0, [2] * 8, 0, 63, 540],
            "the whole sum in one sample": ["S08235", 3, 240.0, [2] * 8, 0, 720, 540],
            "mean equal to max": ["S08235", 1, 7.0, [0, 0, 0, 1, 1, 1, 1, 1], 0, 7, 540],
        }
        for label, stop in accepted.items():
            with self.subTest(label=label):
                payload = valid_payload()
                payload["trains"][0]["s"][0] = stop
                self.assertEqual(payload, stats.check_bytes(stats.encode(payload)))
        for value in (6, 10):  # either end of the bucket the counts imply
            payload = valid_payload()
            payload["trains"][1]["s"][0][5] = value
            self.assertEqual(payload, stats.check_bytes(stats.encode(payload)))

    def test_a_network_sized_file_stays_under_three_megabytes(self) -> None:
        # 9,000 trains x 14 stops of nine elements; platforms and weekday triples drawn at random (seeded) so that they
        # compress like real ones, not like a pattern.  Simulated observations through aggregate() give 2.4-2.5 MiB.
        rng = random.Random(20261104)
        names = [str(number) for number in range(1, 25)] + ["1 Ovest", "2 Est", "I", "II", "1A", "16 Sud"]
        trains = []
        for index in range(9000):
            stops = []
            for position in range(14):
                samples = 20 + (index + position) % 10
                cumulative = [min(samples, samples // 3 + bucket // 2 + position % 3) for bucket in stats.BUCKETS]
                cumulative[-1] = samples - (index % 2)
                for i in range(1, len(cumulative)):
                    cumulative[i] = max(cumulative[i], cumulative[i - 1])
                # Largest delay inside the first bucket holding every sample (above 60 when one is beyond).
                first = next((bucket for bucket, count in enumerate(cumulative) if count == samples), None)
                if first is None:
                    maximum = 61 + (index * 13 + position * 7) % 120
                elif first == 0:
                    maximum = 0
                else:
                    low, high = stats.BUCKETS[first - 1], stats.BUCKETS[first]
                    maximum = low + 1 + (index + position) % (high - low)
                minute = (300 + index * 37 + position * 11) % 1500
                observed = samples - rng.randrange(4)
                platform = None if rng.random() < 0.25 else [rng.choice(names), observed - rng.randrange(observed // 3 + 1), observed]
                # The samples fall on consecutive days (four or five per weekday in a 30-day window), each in a random
                # band of the cumulative counts (<= 5, 6-15, later).
                weekdays = [[0, 0, 0] for _ in range(7)]
                bands = [2] * cumulative[2] + [1] * (cumulative[4] - cumulative[2]) + [0] * (samples - cumulative[4])
                rng.shuffle(bands)
                first = rng.randrange(7)
                for offset, band in enumerate(bands):
                    day = weekdays[(first + offset) % 7]
                    day[0] += 1
                    day[1] += band == 2
                    day[2] += band >= 1
                stops.append([f"S{(index * 7 + position * 13) % 100000:05d}", samples, round(3.4 + (index % 50) / 10, 1),
                              cumulative, position % 2, maximum, minute, platform, weekdays])
            unique = {stop[0]: stop for stop in stops}
            trains.append({"n": str(index + 1), "o": "S08409", "c": "REG", "d": 30, "x": 0, "s": list(unique.values())})
        payload = dict(valid_payload(), trains=trains)
        data = stats.encode(payload)
        self.assertEqual(9, len(payload["trains"][0]["s"][0]))
        self.assertEqual(126_000, sum(len(train["s"]) for train in trains))
        self.assertLess(len(data), stats.MAX_GZIP_BYTES)
        problems: list[str] = []
        for train in trains:  # the synthetic means are not consistent with the counts; the new elements are
            for stop in train["s"]:
                stats._check_platform(stop[7], stop[0], stop[1], problems)
                stats._check_weekdays(stop[8], stop[0], stop[1], stop[3], problems)
        self.assertEqual([], problems)

    def test_command_line_check_build_and_print(self) -> None:
        temp = Path(tempfile.mkdtemp(prefix="punctuality-cli-"))
        self.addCleanup(shutil.rmtree, temp, ignore_errors=True)
        good, bad = temp / "good.json.gz", temp / "bad.json.gz"
        good.write_bytes(stats.encode(valid_payload()))
        bad.write_bytes(gzip.compress(b"{}"))
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            self.assertEqual(0, stats.main(["--check", str(good)]))
            self.assertEqual(1, stats.main(["--check", str(bad)]))
            self.assertEqual(1, stats.main(["--print", str(bad)]))
        self.assertIn("2 treni, 3 fermate, finestra 2026-10-11 - 2026-11-09", output.getvalue())
        self.assertNotIn("BINARIO_BEGIN", output.getvalue())
        self.assertIn("ERRORE:", errors.getvalue())
        printed = io.StringIO()
        with contextlib.redirect_stdout(printed):
            self.assertEqual(0, stats.main(["--print", str(good)]))
        self.assertEqual({stats.PUBLISHED_PATH: good.read_bytes()}, ci_log_frames.parse_printed_files(printed.getvalue()))

        state = temp / "state"
        observations = collector.State(state)
        observations.add(record("2026-11-09", stops=[["S08409", 2, 0, 1320, "12", 0], ["S01700", 7, 0, 1450, "21", 1]]))
        observations.save(AS_OF)
        first, second = temp / "first.json.gz", temp / "second/stats_v1.json.gz"
        with contextlib.redirect_stdout(io.StringIO()):
            for path in (first, second):
                self.assertEqual(0, stats.main(["--state", str(state), "--output", str(path), "--as-of", "2026-11-10",
                                                "--generated-at", "2026-11-10T04:00:00Z"]))
        self.assertEqual(first.read_bytes(), second.read_bytes())
        self.assertEqual(["S01700", 1, 7.0, [0, 0, 0, 1, 1, 1, 1, 1], 0, 7, 1450, ["21", 1, 1], None],
                         stats.check_bytes(first.read_bytes())["trains"][0]["s"][1])

    def test_a_release_needs_enough_covered_days_and_a_recent_window(self) -> None:
        # Second audit, tools-data-5: the shipped file declared 30 days and held 6; a release check refuses that, and a
        # window older than two weeks, without touching the pull request and push checks.
        temp = Path(tempfile.mkdtemp(prefix="punctuality-release-"))
        self.addCleanup(shutil.rmtree, temp, ignore_errors=True)
        path = temp / "stats_v1.json.gz"

        def release(payload: dict[str, Any] | None, as_of: str, *extra: str) -> tuple[int, str]:
            if payload is not None:
                path.write_bytes(stats.encode(payload))
            output = io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                code = stats.main(["--release-check", str(path), "--as-of", as_of, *extra])
            return code, output.getvalue()

        code, output = release(valid_payload(), "2026-11-12")  # 28 days covered, window ended 3 days ago
        self.assertEqual(0, code, output)
        self.assertIn("28 giorni coperti", output)
        thin = valid_payload()
        del thin["trains"][1]  # the longest train known on 3 days
        code, output = release(thin, "2026-11-12")
        self.assertEqual(1, code)
        self.assertIn("3 giorni coperti", output)
        self.assertIn("-Pbinario.punctualityStats=false", output)
        self.assertEqual(0, release(thin, "2026-11-12", "--min-covered-days", "3")[0])
        code, output = release(valid_payload(), "2026-11-24")  # 15 days after windowEnd
        self.assertEqual(1, code)
        self.assertIn("15 giorni", output)
        self.assertEqual(0, release(None, "2026-11-23")[0])  # 14 days: still accepted
        self.assertTrue(any("windowEnd" in problem for problem in stats.release_problems(valid_payload(), date(2026, 12, 1))))
        path.write_bytes(gzip.compress(b"{}"))
        self.assertEqual(1, release(None, "2026-11-12")[0])
        path.unlink()
        code, output = release(None, "2026-11-12")
        self.assertEqual(0, code, output)  # no file: the feature is hidden, nothing to gate
        self.assertIn("assente", output)

    def test_the_job_summary_shows_the_coverage_of_the_window(self) -> None:
        temp = Path(tempfile.mkdtemp(prefix="punctuality-coverage-"))
        self.addCleanup(shutil.rmtree, temp, ignore_errors=True)
        state = collector.State(temp / "state")
        for day, numbers in (("2026-11-08", ("100", "101", "102")), ("2026-11-09", ("100",))):
            for number in numbers:
                state.add(record(day, number=number, stops=[["S08409", 2, 0, 600, None, 0]]))
        state.add(record("2026-11-09", number="103", status="missing"))
        state.save(AS_OF)
        summary = temp / "summary.md"
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, stats.main(["--state", str(temp / "state"), "--output", str(temp / "out.json.gz"),
                                            "--as-of", "2026-11-10", "--generated-at", "2026-11-10T04:00:00Z",
                                            "--summary", str(summary)]))
        text = summary.read_text(encoding="utf-8")
        self.assertIn("2 giorni su 30 con treni noti", text)
        self.assertIn("da 1 a 3 treni noti al giorno", text)
        self.assertIn("2026-11-09: 1", text)
        self.assertIn("non superato", text)


if __name__ == "__main__":
    unittest.main()
