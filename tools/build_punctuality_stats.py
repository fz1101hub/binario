#!/usr/bin/env python3
"""Aggregate Binario's ViaggiaTreno observations into the punctuality statistics shipped with the Android app.

Reads the state directory of ``tools/collect_punctuality.py`` (``obs-YYYY-MM-DD.jsonl.gz``) and writes
``stats_v1.json.gz``: the gzip (fixed header, no name or time) of one canonical UTF-8 JSON object (sorted keys, no
spaces) holding, for the last 30 complete departure days, every train known on at least one day (arrived,
cancelled, or seen travelling and closed before arriving: only the stops it had passed have a sample), sorted by
number and origin::

    {"schema": "binario-punctuality-v1", "generatedAt": ..., "windowStart": ..., "windowEnd": ..., "windowDays": 30,
     "source": "ViaggiaTreno, elaborazione Binario (non ufficiale)", "buckets": [0, 2, 5, 10, 15, 20, 30, 60],
     "trains": [{"n": "9558", "o": "S08409", "c": "FR", "d": 28, "x": 1, "l": 0,
                 "s": [["S08409", 27, 0.8, [20, 24, 26, 27, 27, 27, 27, 27], 0, 7, 420, ["12", 20, 25],
                        [[4, 4, 4], [4, 4, 4], [4, 3, 4], [4, 4, 4], [4, 4, 4], [4, 4, 4], [3, 3, 3]]], ...]}]}

``d`` days known, ``x`` of them fully cancelled, ``l`` days from the most recent of them to ``windowEnd`` (0 = known
on ``windowEnd``; a train no longer in the timetable keeps its statistics for the rest of the window, and ``l`` tells
the readers so; optional, absent in files built before 12 November 2026); per stop (in run order, only stops with at least one sample):
station code, samples (days the train ran and the stop was served with an actual time), mean delay in minutes with
one decimal (early arrivals count as 0), cumulative counts of samples with delay <= each bucket (early arrivals are
<= 0), days the stop was suppressed while the train ran, largest delay of the samples in whole minutes (early
arrivals count as 0), scheduled time on the most recent day that has one, as minutes since local midnight of the
departure day (Europe/Rome, 1440 or more after midnight; ``null`` while only observations collected before the
extension of 4 November 2026 cover the stop), usual platform ``[name, count, observedWithPlatform]`` and weekday
punctuality (seven ``[samples, le5, le15]`` for Monday..Sunday of the departure day, ``null`` below 14 samples).  The
usual platform is the most frequent actual platform of the sampled days (``count`` days) among the sampled days
with an actual platform (``observedWithPlatform``); only at a stop where no sampled day had an actual platform the
programmed ones are counted the same way; ``null`` without any.  The origin stop uses the departure delay, time and
platform, the others the arrival ones.  Nothing else from ViaggiaTreno reaches the file: no actual times, station
names or texts.

``--check FILE`` validates a file (schema, canonical encoding, sizes, bounds, monotonic cumulative counts, maximum
delay within the bucket the counts imply and between the mean and the sum, platform labels and counts, weekday
counts within the stop's counts, sorted unique entries; stops of 9 elements, or of 5 or 7 as in files built before
the extensions) and is used by the tests, by the print step and by ``tools/apply_ci_punctuality.py``.
``--print FILE`` checks the file and prints it in the job log as a ``BINARIO_BEGIN``/``BINARIO_DONE`` frame
(``tools/ci_log_frames.py``).  ``--release-check FILE`` is the release gate (pull requests into a stabilization branch,
release signing, the iPhone release archive; never the other checks): beyond ``--check`` it needs at least
``--min-covered-days`` days covered (the largest ``d``, as the apps count them) and a ``windowEnd`` at most
``--max-age-days`` old; a missing file passes (the feature is hidden).  With ``--state``, ``--summary`` also reports
the coverage of the window and whether the release gate would pass.

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
MAX_DELAY_MINUTES = 1440  # bound of maxDelayMinutes in the format (the collector keeps at most MAX_MEAN)
MAX_SCHEDULED_MINUTE = collector.MAX_SCHEDULED_MINUTE  # exclusive
# A stop is [code, samples, mean, cumulative, suppressed, maxDelay, scheduledMinute, platform, weekdays]; files built
# before the extensions of 4 November 2026 have the first five or the first seven.
STOP_LENGTHS = (5, 7, 9)
# Weekday punctuality: [samples, delay <= 5, delay <= 15] per ISO weekday, only for stops with this many samples.
WEEKDAY_MIN_SAMPLES = 14
WEEKDAY_THRESHOLDS = (5, 15)
# Release gate (second audit, tools-data-5): the asset built on 9 November 2026 declared 30 days and held 6.
RELEASE_MIN_COVERED_DAYS = 14
RELEASE_MAX_AGE_DAYS = 14
TOP_KEYS = frozenset({"schema", "generatedAt", "windowStart", "windowEnd", "windowDays", "source", "buckets", "trains"})
TRAIN_KEYS = frozenset({"n", "o", "c", "d", "x", "s"})
OPTIONAL_TRAIN_KEYS = frozenset({"l"})  # days from the train's most recent known day to windowEnd
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


def usual_platform(tallies: Mapping[int, Mapping[str, list[Any]]]) -> list[Any] | None:
    """``[name, count, observedWithPlatform]`` from the actual platforms (``tallies[1]``: name -> [days, latest day]),
    or from the programmed ones (``tallies[0]``) when no day had an actual one; ties go to the most recent, then to
    the first name."""
    for kind in (1, 0):
        names = tallies.get(kind)
        if names:
            name, (count, _) = min(names.items(), key=lambda item: (-item[1][0], _negated_day(item[1][1]), item[0]))
            return [name, count, sum(days for days, _ in names.values())]
    return None


def _negated_day(day: str) -> int:
    return -date.fromisoformat(day).toordinal()


def aggregate(records: Iterable[Mapping[str, Any]], window_end: date | None = None) -> list[dict[str, Any]]:
    """One entry per train (number, origin) known on at least one day of ``records``; with ``window_end``, ``l`` is
    the number of days from its most recent known day to ``window_end``."""
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
        scheduled: dict[str, tuple[str, int]] = {}  # code -> (most recent day with a scheduled time, minute)
        platforms: dict[str, dict[int, dict[str, list[Any]]]] = {}  # code -> actual 1/0 -> name -> [days, latest]
        weekdays: dict[str, list[list[int]]] = {}  # code -> Monday..Sunday [samples, <= 5, <= 15]
        cancelled = 0
        for record in days.values():
            categories[record["c"]] = categories.get(record["c"], 0) + 1
            if record["st"] == "cancelled":
                cancelled += 1
                continue
            last = max(1, len(record["s"]) - 1)
            weekday = date.fromisoformat(record["d"]).weekday()
            for index, stop in enumerate(record["s"]):
                code, delay, stop_suppressed = stop[0], stop[1], stop[2]
                minute = stop[3] if len(stop) > 3 else None  # none in lines collected before the extension
                positions.setdefault(code, []).append(index / last)
                if minute is not None and (code not in scheduled or record["d"] > scheduled[code][0]):
                    scheduled[code] = (record["d"], minute)
                if stop_suppressed:
                    suppressed[code] = suppressed.get(code, 0) + 1
                elif delay is not None:
                    samples.setdefault(code, []).append(delay)
                    counts = weekdays.setdefault(code, [[0, 0, 0] for _ in range(7)])[weekday]
                    counts[0] += 1
                    counts[1] += 1 if delay <= WEEKDAY_THRESHOLDS[0] else 0
                    counts[2] += 1 if delay <= WEEKDAY_THRESHOLDS[1] else 0
                    if len(stop) > 5 and stop[4] is not None:  # none in lines collected before the extension
                        tally = platforms.setdefault(code, {}).setdefault(stop[5], {}).setdefault(stop[4], [0, record["d"]])
                        tally[0] += 1
                        tally[1] = max(tally[1], record["d"])
        stops = []
        for code, delays in samples.items():
            order = sum(positions[code]) / len(positions[code])
            cumulative = [sum(1 for delay in delays if delay <= bucket) for bucket in BUCKETS]
            minute = scheduled[code][1] if code in scheduled else None
            by_weekday = weekdays[code] if len(delays) >= WEEKDAY_MIN_SAMPLES else None
            stops.append((order, code, [code, len(delays), _mean_tenths(delays), cumulative, suppressed.get(code, 0),
                                        max(0, max(delays)), minute, usual_platform(platforms.get(code, {})), by_weekday]))
        stops.sort(key=lambda item: (item[0], item[1]))
        category = min(categories, key=lambda name: (-categories[name], name))
        train = {"n": number, "o": origin, "c": category, "d": len(days), "x": cancelled,
                 "s": [stop for _, _, stop in stops]}
        if window_end is not None:
            train["l"] = (window_end - date.fromisoformat(max(days))).days
        trains.append(train)
    trains.sort(key=lambda train: (int(train["n"]), train["o"]))
    return trains


def build(state: Path, as_of: date, generated_at: datetime, counters: dict[str, int] | None = None,
          known_per_day: dict[str, int] | None = None) -> dict[str, Any]:
    """The statistics of the window before ``as_of``; ``known_per_day`` receives the trains known on each day."""
    start, end = window(as_of)
    counters = counters if counters is not None else {}
    records = read_records(state, start, end, counters)
    if known_per_day is not None:
        for record in records:
            if record["st"] in collector.KNOWN:
                known_per_day[record["d"]] = known_per_day.get(record["d"], 0) + 1
    return {
        "schema": SCHEMA,
        "generatedAt": generated_at.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "windowStart": start.isoformat(),
        "windowEnd": end.isoformat(),
        "windowDays": WINDOW_DAYS,
        "source": SOURCE,
        "buckets": list(BUCKETS),
        "trains": aggregate(records, end),
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


def _check_extension(maximum: object, minute: object, where: str, samples: int, mean: float | None,
                     cumulative: Sequence[int] | None, problems: list[str]) -> None:
    """The two trailing elements of a 7-element stop: largest delay and scheduled minute (``null`` when unknown)."""
    if not _is_int(maximum) or not 0 <= maximum <= MAX_DELAY_MINUTES:
        problems.append(f"{where}: ritardo massimo fuori da 0..{MAX_DELAY_MINUTES}")
    else:
        if cumulative is not None:
            # The largest delay lies in the first bucket that holds every sample, above 60 when some sample is not
            # in any bucket: above the previous bucket, at most this one.
            first = next((index for index, count in enumerate(cumulative) if count == samples), None)
            above = BUCKETS[-1] if first is None else (BUCKETS[first - 1] if first else -1)
            at_most = MAX_DELAY_MINUTES if first is None else BUCKETS[first]
            if not above < maximum <= at_most:
                problems.append(f"{where}: ritardo massimo incompatibile con i cumulati")
        # The largest delay is one of the samples: at least the mean, at most their sum (the mean has one decimal).
        if mean is not None and mean > maximum:
            problems.append(f"{where}: ritardo medio oltre il ritardo massimo")
        if mean is not None and maximum > (mean + 0.05) * samples + 1e-6:
            problems.append(f"{where}: ritardo massimo oltre la somma dei ritardi (media per campioni)")
    if not collector.valid_scheduled_minute(minute):
        problems.append(f"{where}: minuto programmato né null né in 0..{MAX_SCHEDULED_MINUTE - 1}")


def _check_platform(platform: object, where: str, samples: int, problems: list[str]) -> None:
    """Element 7: ``null`` or ``[name, count, observedWithPlatform]`` with ``1 <= count <= observed <= samples``."""
    if platform is None:
        return
    if not isinstance(platform, list) or len(platform) != 3:
        problems.append(f"{where}: binario non è null né [nome, giorni, giorni con binario]")
        return
    name, count, observed = platform
    if not collector.valid_platform(name):
        problems.append(f"{where}: nome del binario non valido (1..{collector.MAX_PLATFORM_CHARS} caratteri, "
                        "lettere, cifre e separatori, senza spazi in testa, in coda o doppi)")
    if not _is_int(count) or not _is_int(observed) or not 1 <= count <= observed <= samples:
        problems.append(f"{where}: giorni del binario fuori da 1 <= giorni <= giorni con binario <= {samples}")


def _check_weekdays(weekdays: object, where: str, samples: int, cumulative: Sequence[int] | None,
                    problems: list[str]) -> None:
    """Element 8: ``null`` or seven ``[samples, le5, le15]`` (Monday..Sunday), a part of the stop's samples."""
    if weekdays is None:
        return
    if (
        not isinstance(weekdays, list) or len(weekdays) != 7
        or not all(isinstance(day, list) and len(day) == 3 and all(_is_int(value) for value in day) for day in weekdays)
    ):
        problems.append(f"{where}: giorni della settimana non sono null né sette [campioni, <= 5, <= 15]")
        return
    if samples < WEEKDAY_MIN_SAMPLES:
        problems.append(f"{where}: giorni della settimana con meno di {WEEKDAY_MIN_SAMPLES} campioni (devono essere null)")
    if not all(0 <= le5 <= le15 <= count for count, le5, le15 in weekdays):
        problems.append(f"{where}: giorni della settimana fuori da 0 <= <=5 <= <=15 <= campioni del giorno")
        return
    total, le5, le15 = (sum(day[index] for day in weekdays) for index in range(3))
    if total > samples:
        problems.append(f"{where}: campioni dei giorni della settimana oltre i campioni della fermata")
    elif cumulative is not None:
        # The weekday samples are some of the stop's samples: no more on time, and no more late, than the stop has.
        on_time = {threshold: cumulative[BUCKETS.index(threshold)] for threshold in WEEKDAY_THRESHOLDS}
        if (
            le5 > on_time[5] or le15 > on_time[15]
            or total - le5 > samples - on_time[5] or total - le15 > samples - on_time[15]
        ):
            problems.append(f"{where}: giorni della settimana incompatibili con i cumulati")


def _check_stop(stop: object, where: str, runs: int, problems: list[str]) -> str | None:
    if not isinstance(stop, list) or len(stop) not in STOP_LENGTHS:
        problems.append(f"{where}: fermata non è [codice, campioni, media, cumulati, soppressioni] seguito o no da "
                        "[ritardo massimo, minuto programmato] e da [binario, giorni della settimana]")
        return None
    code, samples, mean, cumulative, suppressed = stop[:5]
    if not isinstance(code, str) or not collector.CODE.fullmatch(code):
        problems.append(f"{where}: codice stazione non valido")
        return None
    where = f"{where} {code}"
    if not _is_int(samples) or not 1 <= samples <= runs:
        problems.append(f"{where}: campioni fuori da 1..{runs}")
        return code
    if not _is_int(suppressed) or not 0 <= suppressed <= runs - samples:
        problems.append(f"{where}: giorni soppressi fuori da 0..{runs - samples}")
    mean_valid = (
        isinstance(mean, (int, float)) and not isinstance(mean, bool) and mean == mean
        and 0 <= mean <= MAX_MEAN and abs(mean * 10 - round(mean * 10)) <= 1e-6
    )
    if not mean_valid:
        problems.append(f"{where}: ritardo medio fuori da 0..{MAX_MEAN} o con più di un decimale")
    if (
        not isinstance(cumulative, list) or len(cumulative) != len(BUCKETS)
        or not all(_is_int(count) and 0 <= count <= samples for count in cumulative)
    ):
        problems.append(f"{where}: cumulati non validi (uno per soglia, 0..campioni)")
        cumulative = None
    elif any(later < earlier for earlier, later in zip(cumulative, cumulative[1:])):
        problems.append(f"{where}: cumulati non monotoni")
        cumulative = None
    if len(stop) >= 7:
        _check_extension(stop[5], stop[6], where, samples, mean if mean_valid else None, cumulative, problems)
    if len(stop) == 9:
        _check_platform(stop[7], where, samples, problems)
        _check_weekdays(stop[8], where, samples, cumulative, problems)
    if cumulative is None:
        return code
    if isinstance(mean, (int, float)) and not isinstance(mean, bool):
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
        if not isinstance(train, dict) or not TRAIN_KEYS <= set(train) <= TRAIN_KEYS | OPTIONAL_TRAIN_KEYS:
            problems.append(f"{where}: chiavi diverse da {sorted(TRAIN_KEYS)} (più, facoltativa, {sorted(OPTIONAL_TRAIN_KEYS)})")
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
        # Known on d distinct days of the window: the most recent is at most days - d before its end.
        if "l" in train and (not _is_int(train["l"]) or not 0 <= train["l"] <= days - known):
            problems.append(f"{where}: l fuori da 0..{days - known} (giorni dall'ultimo giorno noto a windowEnd)")
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


def covered_days(payload: Mapping[str, Any]) -> int:
    """Days the statistics cover as the apps count them: the largest ``d``, at most the window."""
    return min(payload["windowDays"], max((train["d"] for train in payload["trains"]), default=0))


def release_problems(payload: Mapping[str, Any], as_of: date, *, min_covered_days: int = RELEASE_MIN_COVERED_DAYS,
                     max_age_days: int = RELEASE_MAX_AGE_DAYS) -> list[str]:
    """Why a valid statistics file should not ship in a release on ``as_of`` (empty when it may)."""
    problems = []
    covered = covered_days(payload)
    if covered < min_covered_days:
        problems.append(f"{covered} giorni coperti su {payload['windowDays']}: una release ne richiede almeno {min_covered_days}")
    age = (as_of - date.fromisoformat(payload["windowEnd"])).days
    if age > max_age_days:
        problems.append(f"windowEnd {payload['windowEnd']} di {age} giorni fa: una release la richiede entro {max_age_days} giorni")
    return problems


def coverage_line(payload: Mapping[str, Any], known_per_day: Mapping[str, int], as_of: date) -> str:
    """One summary line: days of the window with known trains, trains known per day, and the release gate."""
    counts = [known_per_day[day] for day in sorted(known_per_day)]
    spread = f"da {min(counts)} a {max(counts)} treni noti al giorno" if counts else "nessun treno noto"
    problems = release_problems(payload, as_of)
    gate = "superato" if not problems else "non superato (" + "; ".join(problems) + ")"
    return (f"Copertura: {len(counts)} giorni su {payload['windowDays']} con treni noti, {spread}, "
            f"ultimo giorno {payload['windowEnd']}: {known_per_day.get(payload['windowEnd'], 0)}; "
            f"gate di rilascio: {gate}")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--state", type=Path, help="directory dello stato di collect_punctuality.py")
    action.add_argument("--check", type=Path, metavar="FILE", help="verifica un file stats_v1.json.gz")
    action.add_argument("--print", type=Path, metavar="FILE", dest="print_file",
                        help="verifica e stampa il file nel log come blocco BINARIO_BEGIN/BINARIO_DONE")
    action.add_argument("--release-check", type=Path, metavar="FILE",
                        help="gate di rilascio: --check più giorni coperti ed età di windowEnd (file assente: superato)")
    parser.add_argument("--output", type=Path, help="con --state: file da scrivere")
    parser.add_argument("--as-of", type=date.fromisoformat, help="giorno di riferimento (predefinito: oggi a Roma)")
    parser.add_argument("--generated-at", help="istante di generazione ISO 8601 (predefinito: adesso)")
    parser.add_argument("--summary", type=Path, help="file Markdown a cui aggiungere il riepilogo (GITHUB_STEP_SUMMARY)")
    parser.add_argument("--min-covered-days", type=int, default=RELEASE_MIN_COVERED_DAYS,
                        help=f"con --release-check: giorni coperti richiesti (predefinito {RELEASE_MIN_COVERED_DAYS})")
    parser.add_argument("--max-age-days", type=int, default=RELEASE_MAX_AGE_DAYS,
                        help=f"con --release-check: età massima di windowEnd in giorni (predefinito {RELEASE_MAX_AGE_DAYS})")
    args = parser.parse_args(argv)
    try:
        if args.release_check:
            as_of = args.as_of or collector.rome_date(datetime.now(timezone.utc).timestamp())
            if not args.release_check.exists():
                print(f"{args.release_check.name}: assente, statistiche nascoste nell'app: nulla da verificare")
                return 0
            data = args.release_check.read_bytes()
            payload = check_bytes(data)
            problems = release_problems(payload, as_of, min_covered_days=args.min_covered_days,
                                        max_age_days=args.max_age_days)
            for problem in problems:
                print(f"ERRORE: {problem}", file=sys.stderr)
            if problems:
                print("ERRORE: aggiornare le statistiche (docs/data-sources/PUNCTUALITY_STATS.md, «Applicazione con "
                      "l'aggiornamento settimanale») o costruire la release senza con -Pbinario.punctualityStats=false",
                      file=sys.stderr)
                return 1
            print(f"{args.release_check.name}: pronto per una release, {covered_days(payload)} giorni coperti, "
                  f"windowEnd {payload['windowEnd']}")
            return 0
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
        known_per_day: dict[str, int] = {}
        payload = build(args.state, as_of, generated_at, counters, known_per_day)
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
                summary.write(f"### Puntualità: statistiche\n\n{line}\n\n{coverage_line(payload, known_per_day, as_of)}\n")
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
