#!/usr/bin/env python3
"""Offline tests for tools/build_punctuality_stats.py: aggregation, deterministic encoding and the --check validator."""
from __future__ import annotations

import contextlib
import copy
import gzip
import io
import json
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
        records = [record(day, stops=[["S08409", 0, 0], ["S01700", delay, 0]]) for day, delay in zip(days_back(16), delays)]
        train = stats.aggregate(records)[0]
        origin, destination = train["s"]
        self.assertEqual(["S08409", 16, 0.0, [16] * 8, 0], origin)
        self.assertEqual("S01700", destination[0])
        self.assertEqual(16, destination[1])
        # <= 0: -3, 0 | <= 2: +1, 2 | <= 5: +3, 5 | <= 10: +6, 10 | <= 15: +15 | <= 20: +16, 20 | <= 30: +29, 30 | <= 60: +60
        self.assertEqual([2, 4, 6, 8, 9, 11, 13, 14], destination[3])
        # Early arrivals count as 0 in the mean: (0+0+1+2+3+5+6+10+15+16+20+29+30+60+61+300) / 16 = 34.875 -> 34.9
        self.assertEqual(34.9, destination[2])

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
        self.assertEqual(["S08217", 2, 2.5, [0, 1, 2, 2, 2, 2, 2, 2], 1], by_code["S08217"])
        self.assertEqual(["S01700", 2, 4.5, [0, 0, 1, 2, 2, 2, 2, 2], 0], by_code["S01700"])
        # A stop never sampled (always suppressed) is left out.
        only_suppressed = [record(day, stops=[["S08409", 1, 0], ["S08217", None, 1]]) for day in days[:3]]
        self.assertEqual(["S08409"], [stop[0] for stop in stats.aggregate(only_suppressed)[0]["s"]])
        # A train only ever cancelled keeps its days, without stops; unknown-only trains are not listed.
        listed = stats.aggregate([record(days[0], number="100", status="cancelled"), record(days[1], number="101", status="unknown")])
        self.assertEqual([{"n": "100", "o": "S08409", "c": "FR", "d": 1, "x": 1, "s": []}], listed)

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
            {"n": "2001", "o": "S08235", "c": "REG", "d": 3, "x": 0, "s": [["S08235", 3, 0.3, [2, 3, 3, 3, 3, 3, 3, 3], 0]]},
            {"n": "9558", "o": "S08409", "c": "FR", "d": 28, "x": 1, "s": [
                ["S08409", 27, 0.8, [20, 24, 26, 27, 27, 27, 27, 27], 0],
                ["S00219", 26, 4.6, [9, 14, 19, 23, 25, 26, 26, 26], 1],
            ]},
        ],
    }


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

    def test_a_network_sized_file_stays_under_three_megabytes(self) -> None:
        trains = []
        for index in range(9000):
            stops = []
            for position in range(14):
                samples = 20 + (index + position) % 10
                cumulative = [min(samples, samples // 3 + bucket // 2 + position % 3) for bucket in stats.BUCKETS]
                cumulative[-1] = samples - (index % 2)
                for i in range(1, len(cumulative)):
                    cumulative[i] = max(cumulative[i], cumulative[i - 1])
                stops.append([f"S{(index * 7 + position * 13) % 100000:05d}", samples, round(3.4 + (index % 50) / 10, 1),
                              cumulative, position % 2])
            unique = {stop[0]: stop for stop in stops}
            trains.append({"n": str(index + 1), "o": "S08409", "c": "REG", "d": 30, "x": 0, "s": list(unique.values())})
        payload = dict(valid_payload(), trains=trains)
        data = stats.encode(payload)
        self.assertLess(len(data), stats.MAX_GZIP_BYTES)

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
        observations.add(record("2026-11-09", stops=[["S08409", 2, 0], ["S01700", 7, 0]]))
        observations.save(AS_OF)
        first, second = temp / "first.json.gz", temp / "second/stats_v1.json.gz"
        with contextlib.redirect_stdout(io.StringIO()):
            for path in (first, second):
                self.assertEqual(0, stats.main(["--state", str(state), "--output", str(path), "--as-of", "2026-11-10",
                                                "--generated-at", "2026-11-10T04:00:00Z"]))
        self.assertEqual(first.read_bytes(), second.read_bytes())
        self.assertEqual(["S01700", 1, 7.0, [0, 0, 0, 1, 1, 1, 1, 1], 0], stats.check_bytes(first.read_bytes())["trains"][0]["s"][1])


if __name__ == "__main__":
    unittest.main()
