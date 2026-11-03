#!/usr/bin/env python3
"""Aggregate Binario's ViaggiaTreno observations into the punctuality statistics shipped with the Android app.

Reads the state directory of ``tools/collect_punctuality.py`` (``obs-YYYY-MM-DD.jsonl.gz``) and writes
``stats_v1.json.gz``: the gzip (fixed header, no name or time) of one canonical UTF-8 JSON object (sorted keys, no
spaces) holding, for the last 30 complete departure days, every train known on at least one day (arrived or
cancelled), sorted by number and origin::

    {"schema": "binario-punctuality-v1", "generatedAt": ..., "windowStart": ..., "windowEnd": ..., "windowDays": 30,
     "source": "ViaggiaTreno, elaborazione Binario (non ufficiale)", "buckets": [0, 2, 5, 10, 15, 20, 30, 60],
     "trains": [{"n": "9558", "o": "S08409", "c": "FR", "d": 28, "x": 1,
                 "s": [["S08409", 27, 0.8, [20, 24, 26, 27, 27, 27, 27, 27], 0], ...]}]}

``d`` days known, ``x`` of them fully cancelled; per stop (in run order, only stops with at least one sample):
station code, samples (days the train ran and the stop was served with an actual time), mean delay in minutes with
one decimal (early arrivals count as 0), cumulative counts of samples with delay <= each bucket (early arrivals are
<= 0), days the stop was suppressed while the train ran.  The origin stop uses the departure delay, the others the
arrival delay.  Nothing else from ViaggiaTreno reaches the file: no times, names or texts.

``--check FILE`` validates a file (schema, canonical encoding, sizes, bounds, monotonic cumulative counts, sorted
unique entries) and is used by the tests, by the print step and by ``tools/apply_ci_punctuality.py``.  ``--print FILE``
checks the file and prints it in the job log as a ``BINARIO_BEGIN``/``BINARIO_DONE`` frame (``tools/ci_log_frames.py``).

    python3 -I tools/build_punctuality_stats.py --state DIR --output stats_v1.json.gz
    python3 -I tools/build_punctuality_stats.py --check stats_v1.json.gz
"""
from __future__ import annotations

import argparse
import gzip
import io
import json
import re
import sys
import zlib
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import ci_log_frames  # noqa: E402
import collect_punctuality as collector  # noqa: E402

SCHEMA = "binario-punctuality-v1"
SOURCE = "ViaggiaTreno, elaborazione Binario (non ufficiale)"
BUCKETS = (0, 2, 5, 10, 15, 20, 30, 60)
WINDOW_DAYS = 30
MAX_WINDOW_DAYS = 60
PUBLISHED_PATH = "punctuality/stats_v1.json.gz"
ASSET_PATH = Path("app/src/main/assets/punctuality/stats_v1.json.gz")
MAX_GZIP_BYTES = 3 * 1024 * 1024
MAX_JSON_BYTES = 32 * 1024 * 1024
MAX_TRAINS = 50_000
MAX_STOPS = collector.MAX_STOPS
MAX_MEAN = collector.MAX_DELAY
TOP_KEYS = frozenset({"schema", "generatedAt", "windowStart", "windowEnd", "windowDays", "source", "buckets", "trains"})
TRAIN_KEYS = frozenset({"n", "o", "c", "d", "x", "s"})
GENERATED_AT = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")
ISO_DATE = re.compile(r"\d{4}-\d{2}-\d{2}")


class CheckError(ValueError):
    """The statistics file does not follow ``binario-punctuality-v1``."""


# --- Aggregation --------------------------------------------------------------------------------------------------


def window(as_of: date, days: int = WINDOW_DAYS) -> tuple[date, date]:
    """The last ``days`` complete days before ``as_of`` (Europe/Rome)."""
    end = as_of - timedelta(days=1)
    return end - timedelta(days=days - 1), end


def read_records(state: Path, start: date, end: date, counters: dict[str, int]) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for day, _ in sorted(collector.observation_files(state).items()):
        if start.isoformat() <= day <= end.isoformat():
            records += collector.load_observations(state, day, counters).values()
    return records


def _mean_tenths(delays: Sequence[int]) -> float:
    """Mean of ``delays`` with early arrivals as 0, rounded half up to one decimal with integer arithmetic."""
    total = sum(max(0, delay) for delay in delays)
    return ((20 * total + len(delays)) // (2 * len(delays))) / 10


def aggregate(records: Iterable[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """One entry per train (number, origin) known on at least one day of ``records``."""
    groups: dict[tuple[str, str], dict[str, Mapping[str, Any]]] = {}
    for record in records:
        if record["st"] not in collector.KNOWN:
            continue
        days = groups.setdefault((record["n"], record["o"]), {})
        previous = days.get(record["d"])
        if previous is None or collector._rank(record) > collector._rank(previous):
            days[record["d"]] = record
    trains = []
    for (number, origin), days in groups.items():
        categories: dict[str, int] = {}
        positions: dict[str, list[float]] = {}
        samples: dict[str, list[int]] = {}
        suppressed: dict[str, int] = {}
        cancelled = 0
        for record in days.values():
            categories[record["c"]] = categories.get(record["c"], 0) + 1
            if record["st"] == "cancelled":
                cancelled += 1
                continue
            last = max(1, len(record["s"]) - 1)
            for index, (code, delay, stop_suppressed) in enumerate(record["s"]):
                positions.setdefault(code, []).append(index / last)
                if stop_suppressed:
                    suppressed[code] = suppressed.get(code, 0) + 1
                elif delay is not None:
                    samples.setdefault(code, []).append(delay)
        stops = []
        for code, delays in samples.items():
            order = sum(positions[code]) / len(positions[code])
            cumulative = [sum(1 for delay in delays if delay <= bucket) for bucket in BUCKETS]
            stops.append((order, code, [code, len(delays), _mean_tenths(delays), cumulative, suppressed.get(code, 0)]))
        stops.sort(key=lambda item: (item[0], item[1]))
        category = min(categories, key=lambda name: (-categories[name], name))
        trains.append({"n": number, "o": origin, "c": category, "d": len(days), "x": cancelled,
                       "s": [stop for _, _, stop in stops]})
    trains.sort(key=lambda train: (int(train["n"]), train["o"]))
    return trains


def build(state: Path, as_of: date, generated_at: datetime, counters: dict[str, int] | None = None) -> dict[str, Any]:
    start, end = window(as_of)
    counters = counters if counters is not None else {}
    return {
        "schema": SCHEMA,
        "generatedAt": generated_at.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "windowStart": start.isoformat(),
        "windowEnd": end.isoformat(),
        "windowDays": WINDOW_DAYS,
        "source": SOURCE,
        "buckets": list(BUCKETS),
        "trains": aggregate(read_records(state, start, end, counters)),
    }


def canonical_json(payload: Mapping[str, Any]) -> bytes:
    return json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False).encode("ascii")


def encode(payload: Mapping[str, Any]) -> bytes:
    """Deterministic gzip of the canonical JSON: same input, same bytes."""
    buffer = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buffer, mtime=0, compresslevel=9) as stream:
        stream.write(canonical_json(payload))
    return buffer.getvalue()


# --- Validation ---------------------------------------------------------------------------------------------------


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _decompress(data: bytes) -> bytes:
    if len(data) > MAX_GZIP_BYTES:
        raise CheckError(f"file di {len(data)} byte: oltre {MAX_GZIP_BYTES}")
    if data[:3] != b"\x1f\x8b\x08" or len(data) < 18:
        raise CheckError("non è un file gzip")
    if data[3] != 0 or data[4:8] != b"\0\0\0\0":
        raise CheckError("intestazione gzip non deterministica (nome, commento o data presenti)")
    decompressor = zlib.decompressobj(wbits=31)
    try:
        raw = decompressor.decompress(data, MAX_JSON_BYTES + 1)
    except zlib.error as error:
        raise CheckError("gzip non valido") from error
    if len(raw) > MAX_JSON_BYTES or decompressor.unconsumed_tail:
        raise CheckError(f"JSON oltre {MAX_JSON_BYTES} byte")
    if not decompressor.eof or decompressor.unused_data:
        raise CheckError("gzip troncato o con più membri")
    return raw


def _date(value: object, label: str) -> date:
    if not isinstance(value, str) or not ISO_DATE.fullmatch(value):
        raise CheckError(f"{label} non è una data AAAA-MM-GG")
    try:
        return date.fromisoformat(value)
    except ValueError as error:
        raise CheckError(f"{label} non è una data valida") from error


def _check_stop(stop: object, where: str, runs: int, problems: list[str]) -> str | None:
    if not isinstance(stop, list) or len(stop) != 5:
        problems.append(f"{where}: fermata non è [codice, campioni, media, cumulati, soppressioni]")
        return None
    code, samples, mean, cumulative, suppressed = stop
    if not isinstance(code, str) or not collector.CODE.fullmatch(code):
        problems.append(f"{where}: codice stazione non valido")
        return None
    where = f"{where} {code}"
    if not _is_int(samples) or not 1 <= samples <= runs:
        problems.append(f"{where}: campioni fuori da 1..{runs}")
        return code
    if not _is_int(suppressed) or not 0 <= suppressed <= runs - samples:
        problems.append(f"{where}: giorni soppressi fuori da 0..{runs - samples}")
    if (
        not isinstance(mean, (int, float)) or isinstance(mean, bool) or mean != mean
        or not 0 <= mean <= MAX_MEAN or abs(mean * 10 - round(mean * 10)) > 1e-6
    ):
        problems.append(f"{where}: ritardo medio fuori da 0..{MAX_MEAN} o con più di un decimale")
    if (
        not isinstance(cumulative, list) or len(cumulative) != len(BUCKETS)
        or not all(_is_int(count) and 0 <= count <= samples for count in cumulative)
    ):
        problems.append(f"{where}: cumulati non validi (uno per soglia, 0..campioni)")
        return code
    if any(later < earlier for earlier, later in zip(cumulative, cumulative[1:])):
        problems.append(f"{where}: cumulati non monotoni")
    elif isinstance(mean, (int, float)) and not isinstance(mean, bool):
        # Every sample within a bucket bounds the mean from above, every sample beyond it from below.
        if cumulative[0] == samples and mean != 0:
            problems.append(f"{where}: media positiva con tutti i campioni in orario")
        between = [cumulative[index + 1] - cumulative[index] for index in range(len(BUCKETS) - 1)]
        beyond = samples - cumulative[-1]
        lower = sum(count * BUCKETS[index] for index, count in enumerate(between)) + beyond * BUCKETS[-1]
        upper = sum(count * BUCKETS[index + 1] for index, count in enumerate(between)) + beyond * MAX_MEAN
        if not lower - 0.05 * samples <= mean * samples <= upper + 0.05 * samples:
            problems.append(f"{where}: media incompatibile con i cumulati")
    return code


def check_payload(payload: object) -> list[str]:
    """Every rule of ``binario-punctuality-v1`` the payload breaks."""
    problems: list[str] = []
    if not isinstance(payload, dict):
        return ["il JSON non è un oggetto"]
    if set(payload) != TOP_KEYS:
        return [f"chiavi di primo livello diverse da {sorted(TOP_KEYS)}"]
    if payload["schema"] != SCHEMA:
        problems.append(f"schema diverso da {SCHEMA}")
    if payload["source"] != SOURCE:
        problems.append("source diverso dall'attribuzione prevista")
    if payload["buckets"] != list(BUCKETS) or not all(_is_int(value) for value in payload["buckets"]):
        problems.append(f"buckets diversi da {list(BUCKETS)}")
    generated = payload["generatedAt"]
    if not isinstance(generated, str) or not GENERATED_AT.fullmatch(generated):
        problems.append("generatedAt non è AAAA-MM-GGTHH:MM:SSZ")
        generated = None
    try:
        start, end = _date(payload["windowStart"], "windowStart"), _date(payload["windowEnd"], "windowEnd")
    except CheckError as error:
        return problems + [str(error)]
    days = payload["windowDays"]
    if not _is_int(days) or not 1 <= days <= MAX_WINDOW_DAYS or (end - start).days + 1 != days:
        problems.append("windowDays non corrisponde a windowStart..windowEnd (1..60 giorni)")
        days = max(1, (end - start).days + 1)
    if generated is not None and generated[:10] < payload["windowEnd"]:
        problems.append("generatedAt precede la fine della finestra")
    trains = payload["trains"]
    if not isinstance(trains, list) or len(trains) > MAX_TRAINS:
        return problems + [f"trains non è un elenco di al più {MAX_TRAINS} treni"]
    previous: tuple[int, str] | None = None
    for position, train in enumerate(trains):
        where = f"treno {position}"
        if not isinstance(train, dict) or set(train) != TRAIN_KEYS:
            problems.append(f"{where}: chiavi diverse da {sorted(TRAIN_KEYS)}")
            continue
        number, origin, category = train["n"], train["o"], train["c"]
        if (
            not isinstance(number, str) or not collector.NUMBER.fullmatch(number)
            or not isinstance(origin, str) or not collector.CODE.fullmatch(origin)
        ):
            problems.append(f"{where}: numero o origine non validi")
            continue
        where = f"treno {number}/{origin}"
        if not isinstance(category, str) or not collector.CATEGORY.fullmatch(category):
            problems.append(f"{where}: categoria non valida")
        key = (int(number), origin)
        if previous is not None and key <= previous:
            problems.append(f"{where}: treni non ordinati per numero e origine o ripetuti")
        previous = key
        known, cancelled = train["d"], train["x"]
        if not _is_int(known) or not 1 <= known <= days or not _is_int(cancelled) or not 0 <= cancelled <= known:
            problems.append(f"{where}: d o x fuori intervallo (1 <= d <= {days}, 0 <= x <= d)")
            continue
        stops = train["s"]
        if not isinstance(stops, list) or len(stops) > MAX_STOPS:
            problems.append(f"{where}: s non è un elenco di al più {MAX_STOPS} fermate")
            continue
        seen: set[str] = set()
        for stop in stops:
            code = _check_stop(stop, where, known - cancelled, problems)
            if code is not None:
                if code in seen:
                    problems.append(f"{where}: fermata {code} ripetuta")
                seen.add(code)
    return problems


def check_bytes(data: bytes) -> dict[str, Any]:
    """The payload of a valid statistics file; ``CheckError`` listing every problem otherwise."""
    raw = _decompress(data)
    try:
        payload = collector.strict_json(raw)
    except (ValueError, UnicodeDecodeError, RecursionError) as error:
        raise CheckError("JSON non valido (UTF-8, NaN o chiavi duplicate)") from error
    problems = check_payload(payload)
    if not problems and canonical_json(payload) != raw:
        problems.append("JSON non canonico (chiavi ordinate, senza spazi, ASCII)")
    if problems:
        raise CheckError("\n".join(problems[:50]) + (f"\n… altri {len(problems) - 50} problemi" if len(problems) > 50 else ""))
    return payload


def describe(payload: Mapping[str, Any], size: int) -> str:
    trains = payload["trains"]
    stops = sum(len(train["s"]) for train in trains)
    return (f"{len(trains)} treni, {stops} fermate, finestra {payload['windowStart']} - {payload['windowEnd']}, "
            f"generato {payload['generatedAt']}, {size} byte")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--state", type=Path, help="directory dello stato di collect_punctuality.py")
    action.add_argument("--check", type=Path, metavar="FILE", help="verifica un file stats_v1.json.gz")
    action.add_argument("--print", type=Path, metavar="FILE", dest="print_file",
                        help="verifica e stampa il file nel log come blocco BINARIO_BEGIN/BINARIO_DONE")
    parser.add_argument("--output", type=Path, help="con --state: file da scrivere")
    parser.add_argument("--as-of", type=date.fromisoformat, help="giorno di riferimento (predefinito: oggi a Roma)")
    parser.add_argument("--generated-at", help="istante di generazione ISO 8601 (predefinito: adesso)")
    parser.add_argument("--summary", type=Path, help="file Markdown a cui aggiungere il riepilogo (GITHUB_STEP_SUMMARY)")
    args = parser.parse_args(argv)
    try:
        if args.check or args.print_file:
            path = args.check or args.print_file
            data = path.read_bytes()
            payload = check_bytes(data)
            if args.print_file:
                print(f"Statistiche di puntualità: {describe(payload, len(data))}", flush=True)
                ci_log_frames.print_files([(PUBLISHED_PATH, data)])
            else:
                print(f"{path.name}: valido, {describe(payload, len(data))}")
            return 0
        if args.output is None:
            parser.error("--state richiede --output")
        now = datetime.now(timezone.utc)
        generated_at = datetime.fromisoformat(args.generated_at.replace("Z", "+00:00")) if args.generated_at else now
        if generated_at.tzinfo is None:
            parser.error("--generated-at deve indicare il fuso orario")
        as_of = args.as_of or collector.rome_date(generated_at.timestamp())
        counters: dict[str, int] = {}
        payload = build(args.state, as_of, generated_at, counters)
        data = encode(payload)
        check_bytes(data)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(data)
        line = describe(payload, len(data))
        if counters.get("state_invalid_lines"):
            line += f", {counters['state_invalid_lines']} righe di stato scartate"
        print(f"Scritto {args.output.name}: {line}")
        if args.summary:
            with args.summary.open("a", encoding="utf-8") as summary:
                summary.write(f"### Puntualità: statistiche\n\n{line}\n")
    except CheckError as error:
        for message in str(error).split("\n"):
            print(f"ERRORE: {collector.log_safe(message)}", file=sys.stderr)
        return 1
    except OSError as error:
        print(f"ERRORE: {collector.log_safe(error)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
