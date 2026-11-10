#!/usr/bin/env python3
"""Collect ViaggiaTreno observations of the Trenitalia trains that ran recently, for Binario's punctuality statistics.

Run on a GitHub runner several times a day (``.github/workflows/punctuality.yml``, ``python3 -I``; the development
container cannot reach viaggiatreno.it).  Each run:

1. reads the embedded Trenitalia and Trenord packs (``app/src/main/assets/gtfs/trenitalia-gtfs.zip`` and
   ``lombardia-trenord-gtfs.zip``, ``--gtfs`` once per pack): the rail trips (bus routes and non-numeric numbers left out) running yesterday or today (``calendar.txt`` and ``calendar_dates.txt``, times in
   Europe/Rome from noon minus 12 h of the service day) whose scheduled arrival at the last stop is at least 20 min
   old and that ViaggiaTreno still answers about (``answerable``): every train that left today (local date), and a
   train that left yesterday only within 3 h of its arrival and if still travelling at midnight (a night train, or a
   late train already asked and not arrived).  ViaggiaTreno forgets the runs of the
   previous day at midnight, and GitHub skips many scheduled runs, so each run covers the whole day so far rather
   than the last hours (trains already known are never asked again).  Number = ``trip_short_name``, category = suffix of ``route_short_name`` (``0083_REG`` -> ``REG``),
   origin = ViaggiaTreno code of the first stop, resolved from its ``stop_code`` (``830008409``) with the curated
   station catalogue as the app does (``GtfsStationCodeIndex``): the station with that ``trenitaliaLocationId``,
   otherwise ``S`` + the last five digits of an ``8300xxxxx`` code, each confirmed by position or name;
2. asks ``andamentoTreno/{origin}/{number}/{midnight}`` once per train (midnight = local midnight of the departure
   day, epoch milliseconds), at most ``--rate`` requests per second (2 by default and at most), within
   ``--max-requests`` and ``--deadline-seconds``, stopping after 20 consecutive failures or at once on HTTP 403/429;
3. keeps per stop the ViaggiaTreno station code, the delay in whole minutes (actual minus scheduled time, truncated
   towards zero as the app shows it: departure at the origin, arrival elsewhere; early arrivals are <= 0), whether
   the stop was suppressed (``actualFermataType`` 3), the scheduled time (same departure/arrival rule) as minutes
   since local midnight of the departure day (Europe/Rome wall clock, 1440 or more after midnight) and the platform
   (``binarioEffettivo...Descrizione``, departure at the origin, arrival elsewhere or the departure one of the same
   stop; the programmed ``binarioProgrammato...Descrizione`` only when no actual one is published, marked as such;
   a short label of at most 12 characters, anything else dropped), and for the run
   whether it was cancelled (``provvedimento`` 1, ``tipoTreno`` ``ST`` or every stop suppressed).  A train with no
   actual time at its last served stop (and not cancelled) stays pending and is asked again by later runs up to 8 h after its scheduled arrival, then dropped as
   ``unknown``; a train left for a later run by the limits stays pending while ViaggiaTreno still answers about it; a train ViaggiaTreno does not know (HTTP 204/404, ``null``) or whose identity differs (number, origin,
   day) is recorded as ``missing``/``mismatch`` and not asked again;
4. writes the state directory (``--state``): ``obs-YYYY-MM-DD.jsonl.gz`` (one compact JSON line per train and
   departure day, deduplicated by day, origin and number) and ``pending.jsonl.gz``, gzip with a fixed header, written
   atomically, observations older than 35 days removed.  A run is resumable and idempotent: a train already known is
   never asked again.

Every response is untrusted: size cap, strict UTF-8 JSON (no NaN, no duplicate keys), identity and value bounds; only
derived numbers are kept, nothing is executed, and the log and ``--summary`` show counts only, never a payload.

    python3 -I tools/collect_punctuality.py --state "$RUNNER_TEMP/punctuality-raw" --summary "$GITHUB_STEP_SUMMARY"
    python3 -I tools/collect_punctuality.py --estimate 2026-11-04      # requests per day and run, no network
"""
from __future__ import annotations

import argparse
import csv
import gzip
import http.client
import io
import json
import math
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Iterator, Mapping, Sequence

from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_GTFS = ROOT / "app/src/main/assets/gtfs/trenitalia-gtfs.zip"
# Trenord publishes its runs on ViaggiaTreno too (the live check finds 651 Milano Cadorna - Asso, origin S01066), and
# its pack already uses ViaggiaTreno codes as stop_id ("S01066"): Lombardy's regional and suburban trains.
DEFAULT_TRENORD = ROOT / "app/src/main/assets/gtfs/lombardia-trenord-gtfs.zip"
DEFAULT_STATIONS = ROOT / "app/src/main/assets/stations/curated_it_rail_stations.json"
BASE_URL = "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/"
# Honest, in the style of the app ("Binario/0.20 (personal realtime client)") and of the discovery tool.  The
# first form carried a URL and ViaggiaTreno answered 403 to it while answering 200 to these from the same
# runners; the collector still stops at the first 403/429 and never poses as a browser.
USER_AGENT = "Binario-punctuality/1.0 (personal timetable app; read-only)"

# ViaggiaTreno answers only about the runs of its current day: right after midnight the previous day's are gone (the
# run of 9 November 2026 at 00:58 got 585 "unknown" for the 602 trains of the evening before), except a train still
# travelling.  GitHub also skips most scheduled runs (4 of 12 on 8 November, 5 to 7 hours apart), so a run asks about
# every train of the day not known yet instead of the last few hours.
MIN_AGE = timedelta(minutes=20)
PREVIOUS_DAY_GRACE = timedelta(hours=3)
PENDING_LIMIT = timedelta(hours=8)
RETENTION_DAYS = 35
MAX_RATE = 2.0
MAX_CONSECUTIVE_FAILURES = 20
MAX_RESPONSE_BYTES = 1024 * 1024
MAX_REDIRECTS = 3
MAX_STOPS = 400
# Delays outside this range (minutes) are taken as a clock or data error, not as an observation.
MIN_DELAY, MAX_DELAY = -60, 720
MINUTES_PER_DAY = 1440
# Scheduled times are kept as minutes since local midnight of the departure day, below two days (a stop reached
# after midnight is 1440 or more); anything else is not kept.
MAX_SCHEDULED_MINUTE = 2 * MINUTES_PER_DAY  # exclusive
SUPPRESSED_STOP_TYPE = 3
# actualFermataType 2 marks a stop the timetable does not have (the app shows 2 and 3 as skipped): it has no
# scheduled time to compare with, so it is neither a sample nor a suppression.
EXTRA_STOP_TYPE = 2
# Platforms are short labels as ViaggiaTreno publishes them ("12", "3 Ovest", "I", "1A"): letters, digits and a few
# separators, trimmed with single spaces, at most 12 characters; anything else is not kept.
MAX_PLATFORM_CHARS = 12
_PLATFORM_CHAR = "0-9A-Za-z\u00c0-\u00d6\u00d8-\u00f6\u00f8-\u00ff"
PLATFORM = re.compile(f"[{_PLATFORM_CHAR}](?:[{_PLATFORM_CHAR} ./'-]*[{_PLATFORM_CHAR}])?")
# Departure fields at the origin; arrival fields elsewhere, then the departure ones of the same stop (a train arrives
# and leaves from one track; ViaggiaTreno sometimes fills only one side).
ORIGIN_PLATFORM_FIELDS = (("binarioEffettivoPartenzaDescrizione",), ("binarioProgrammatoPartenzaDescrizione",))
STOP_PLATFORM_FIELDS = (("binarioEffettivoArrivoDescrizione", "binarioEffettivoPartenzaDescrizione"),
                        ("binarioProgrammatoArrivoDescrizione", "binarioProgrammatoPartenzaDescrizione"))
RAIL_ROUTE_TYPES = frozenset({"2", *map(str, range(100, 200))})

NUMBER = re.compile(r"[1-9][0-9]{0,5}")
CODE = re.compile(r"S[0-9]{5}")
CATEGORY = re.compile(r"[A-Z]{1,6}")
LINE = re.compile(r"([A-Z]{1,6})[0-9]{0,3}")  # a Trenord line: RE11, S9, R14
LOCATION_ID = re.compile(r"[0-9]{9}")
OBS_FILE = re.compile(r"obs-(\d{4}-\d{2}-\d{2})\.jsonl\.gz")
PENDING_FILE = "pending.jsonl.gz"
KNOWN = ("ok", "cancelled")
STATUSES = frozenset({"ok", "cancelled", "unknown", "missing", "mismatch"})

try:
    ROME = ZoneInfo("Europe/Rome")
except ZoneInfoNotFoundError:  # pragma: no cover - the runners and the container ship tzdata
    raise SystemExit("fuso Europe/Rome non disponibile: installare tzdata")


class CollectorError(RuntimeError):
    """The timetable, the catalogue or the state cannot be used; nothing is written."""


def log_safe(value: object) -> str:
    """``value`` on one log line with control characters escaped (same rule as ``build_gtfs_packs.log_safe``)."""
    return "".join(
        character if character.isprintable() or character == " " else character.encode("unicode_escape").decode("ascii")
        for character in str(value)
    )


# --- Time ---------------------------------------------------------------------------------------------------------


def service_reference(day: date) -> int:
    """Epoch seconds of noon minus 12 h of ``day`` in Europe/Rome, the origin of GTFS times."""
    return int(datetime(day.year, day.month, day.day, 12, tzinfo=ROME).timestamp()) - 12 * 3600


def midnight_millis(day: date) -> int:
    """Local midnight of ``day`` (Europe/Rome) in epoch milliseconds, the ``dataPartenzaTreno`` of ViaggiaTreno."""
    return int(datetime(day.year, day.month, day.day, tzinfo=ROME).timestamp()) * 1000


def rome_date(epoch_seconds: float) -> date:
    return datetime.fromtimestamp(epoch_seconds, ROME).date()


def scheduled_minute(day: date, epoch_millis: int) -> int | None:
    """``epoch_millis`` as minutes since local midnight of ``day`` on the Europe/Rome wall clock (10:00 is 600 also on
    the days the clocks change; 00:10 of the next day is 1450), ``None`` outside ``0 <= minute < 2880``."""
    local = datetime.fromtimestamp(epoch_millis // 1000, ROME)
    minute = (local.date() - day).days * MINUTES_PER_DAY + local.hour * 60 + local.minute
    return minute if 0 <= minute < MAX_SCHEDULED_MINUTE else None


def gtfs_seconds(value: str) -> int | None:
    match = re.fullmatch(r"\s*(\d{1,3}):([0-5]\d):([0-5]\d)\s*", value or "")
    if not match:
        return None
    return int(match.group(1)) * 3600 + int(match.group(2)) * 60 + int(match.group(3))


def parse_gtfs_date(value: str) -> date:
    return datetime.strptime(value.strip(), "%Y%m%d").date()


# --- Stations -----------------------------------------------------------------------------------------------------


def _tokens(name: str) -> set[str]:
    return {token for token in re.split(r"[^0-9a-zà-ÿ]+", name.lower()) if len(token) >= 4}


def _distance_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((phi2 - phi1) / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(min(1.0, max(0.0, a))))


class StationCodes:
    """ViaggiaTreno code of a Trenitalia ``stop_code`` (location id, ``830008409``).

    The code is usually ``S`` + the last five digits of an ``8300xxxxx`` id (Roma Termini ``830008409`` is ``S08409``)
    but not always (Fidene ``830008225`` is ``S08235``), so the curated catalogue decides first, as the app's
    ``GtfsStationCodeIndex`` does: the station whose ``trenitaliaLocationId`` is the id, confirmed by its position
    (10 km) or, without coordinates, by a word of its name.  Otherwise the derived code is used; a wrong guess costs
    one request answered as unknown or with another origin, never a wrong observation (``interpret`` checks
    ``idOrigine``)."""

    MAX_LOCATION_ID_KM = 10.0

    def __init__(self, stations: Iterable[Mapping[str, Any]]):
        by_location: dict[str, list[Mapping[str, Any]]] = {}
        for station in stations:
            location = str(station.get("trenitaliaLocationId") or "").strip()
            if location:
                by_location.setdefault(location, []).append(station)
        self.by_location = {key: value[0] for key, value in by_location.items() if len(value) == 1}

    @classmethod
    def load(cls, path: Path) -> "StationCodes":
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise CollectorError(f"catalogo stazioni non leggibile: {path}") from error
        stations = payload.get("stations") if isinstance(payload, Mapping) else None
        if not isinstance(stations, list):
            raise CollectorError(f"catalogo stazioni senza elenco: {path}")
        return cls(station for station in stations if isinstance(station, Mapping))

    @staticmethod
    def _corroborated(station: Mapping[str, Any], lat: float | None, lon: float | None, name: str, max_km: float) -> bool:
        station_lat, station_lon = station.get("latitude"), station.get("longitude")
        if lat is not None and lon is not None and isinstance(station_lat, (int, float)) and isinstance(station_lon, (int, float)):
            return _distance_km(lat, lon, float(station_lat), float(station_lon)) <= max_km
        words = _tokens(name)
        labels = [str(station.get("name") or "")] + [str(alias) for alias in station.get("aliases") or []]
        return any(_tokens(label) & words for label in labels)

    def resolve(self, stop_code: str, lat: float | None, lon: float | None, name: str) -> str | None:
        code = stop_code.strip()
        if not LOCATION_ID.fullmatch(code):
            return None
        station = self.by_location.get(code)
        if station is not None and self._corroborated(station, lat, lon, name, self.MAX_LOCATION_ID_KM):
            railway = str(station.get("railwayCode") or "")
            if CODE.fullmatch(railway):
                return railway
        return "S" + code[4:] if code.startswith("8300") else None


# --- Timetable ----------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class TripPlan:
    number: str
    category: str
    origin: str  # ViaggiaTreno code of the first stop
    departure: int  # GTFS seconds at the first stop
    arrival: int  # GTFS seconds at the last stop
    service_id: str


@dataclass
class Timetable:
    trips: list[TripPlan]
    weekly: dict[str, tuple[date, date, tuple[bool, ...]]]
    exceptions: dict[str, dict[date, str]]
    skipped: dict[str, int] = field(default_factory=dict)

    def runs_on(self, service_id: str, day: date) -> bool:
        exception = self.exceptions.get(service_id, {}).get(day)
        if exception == "1":
            return True
        if exception == "2":
            return False
        pattern = self.weekly.get(service_id)
        return pattern is not None and pattern[0] <= day <= pattern[1] and pattern[2][day.weekday()]


WEEKDAYS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")


def _rows(archive: zipfile.ZipFile, name: str, required: bool = True) -> Iterator[dict[str, str]]:
    if name not in archive.namelist():
        if required:
            raise CollectorError(f"GTFS senza {name}")
        return
    with archive.open(name) as raw:
        yield from csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline=""))


def _float(value: str | None) -> float | None:
    try:
        return float(value) if value not in (None, "") else None
    except ValueError:
        return None


def load_timetable(path: Path, stations: StationCodes, namespace: str = "") -> Timetable:
    """The rail trips of a pack whose number and origin ViaggiaTreno can be asked about.

    Trenitalia: number = ``trip_short_name``, category = suffix of ``route_short_name`` (``0083_REG``), origin from the
    ``stop_code`` (``StationCodes``).  Trenord: number after the line (``RE_11 - 2153`` is 2153), category = letters of
    the line (``RE11`` is RE, ``S9`` is S; a line without a name is REG), origin = the ``stop_id``, already a
    ViaggiaTreno code (``S01066``).  ``namespace`` prefixes the service ids, so that two packs never share one."""
    skipped: dict[str, int] = {}

    def skip(reason: str) -> None:
        reason = f"{namespace}{reason}"
        skipped[reason] = skipped.get(reason, 0) + 1

    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as error:
        raise CollectorError(f"GTFS non leggibile: {path}") from error
    with archive:
        routes = {row.get("route_id", ""): row for row in _rows(archive, "routes.txt")}
        stops = {row.get("stop_id", ""): row for row in _rows(archive, "stops.txt")}
        trips = {row.get("trip_id", ""): row for row in _rows(archive, "trips.txt")}
        ends: dict[str, list[Any]] = {}  # trip -> [first seq, first stop, departure, last seq, last stop, arrival]
        for row in _rows(archive, "stop_times.txt"):
            trip_id = row.get("trip_id", "")
            try:
                sequence = int(row.get("stop_sequence", ""))
            except ValueError:
                continue
            arrival = gtfs_seconds(row.get("arrival_time", ""))
            departure = gtfs_seconds(row.get("departure_time", ""))
            arrival = departure if arrival is None else arrival
            departure = arrival if departure is None else departure
            current = ends.get(trip_id)
            if current is None:
                ends[trip_id] = [sequence, row.get("stop_id", ""), departure, sequence, row.get("stop_id", ""), arrival]
                continue
            if sequence < current[0]:
                current[0:3] = [sequence, row.get("stop_id", ""), departure]
            if sequence > current[3]:
                current[3:6] = [sequence, row.get("stop_id", ""), arrival]
        weekly: dict[str, tuple[date, date, tuple[bool, ...]]] = {}
        for row in _rows(archive, "calendar.txt", required=False):
            try:
                weekly[namespace + row.get("service_id", "")] = (
                    parse_gtfs_date(row.get("start_date", "")), parse_gtfs_date(row.get("end_date", "")),
                    tuple(row.get(day, "0").strip() == "1" for day in WEEKDAYS),
                )
            except ValueError:
                skip("calendario non valido")
        exceptions: dict[str, dict[date, str]] = {}
        for row in _rows(archive, "calendar_dates.txt", required=False):
            try:
                day = parse_gtfs_date(row.get("date", ""))
            except ValueError:
                continue
            exceptions.setdefault(namespace + row.get("service_id", ""), {})[day] = row.get("exception_type", "").strip()

    plans: list[TripPlan] = []
    origins: dict[str, str | None] = {}
    for trip_id, trip in sorted(trips.items()):
        route = routes.get(trip.get("route_id", ""))
        if route is None or route.get("route_type", "").strip() not in RAIL_ROUTE_TYPES:
            skip("non ferroviaria")
            continue
        number = trip.get("trip_short_name", "").strip().rsplit(" - ", 1)[-1].strip()
        if not NUMBER.fullmatch(number):
            skip("numero non ViaggiaTreno")
            continue
        end = ends.get(trip_id)
        if end is None or end[2] is None or end[5] is None or end[0] == end[3]:
            skip("orari incompleti")
            continue
        stop_id = end[1].strip()
        coded = CODE.fullmatch(stop_id) is not None
        if stop_id not in origins:
            stop = stops.get(stop_id, {})
            origins[stop_id] = stop_id if coded else stations.resolve(
                stop.get("stop_code", ""), _float(stop.get("stop_lat")), _float(stop.get("stop_lon")), stop.get("stop_name", ""))
        origin = origins[stop_id]
        if origin is None:
            skip("origine senza codice ViaggiaTreno")
            continue
        line = route.get("route_short_name", "").strip().rsplit("_", 1)[-1].upper()
        lettered = LINE.fullmatch(line)
        category = (lettered.group(1) if lettered else "REG") if coded else line
        if not CATEGORY.fullmatch(category):
            skip("categoria non valida")
            continue
        plans.append(TripPlan(number, category, origin, end[2], end[5], namespace + trip.get("service_id", "")))
    return Timetable(plans, weekly, exceptions, skipped)


def load_timetables(paths: Sequence[Path], stations: StationCodes) -> Timetable:
    """The packs of ``paths`` as one timetable: each pack's service ids and skip reasons are prefixed by its name."""
    if len(paths) == 1:
        return load_timetable(paths[0], stations)
    merged = Timetable([], {}, {})
    for path in paths:
        part = load_timetable(path, stations, namespace=f"{path.stem}: ")
        merged.trips.extend(part.trips)
        merged.weekly.update(part.weekly)
        merged.exceptions.update(part.exceptions)
        merged.skipped.update(part.skipped)
    return merged


@dataclass(frozen=True)
class Due:
    day: str  # departure day (Europe/Rome) at the origin, ISO
    origin: str
    number: str
    category: str
    arrival: int  # scheduled arrival at the last stop, epoch seconds

    @property
    def key(self) -> tuple[str, str, str]:
        return self.day, self.origin, self.number


def trains_on(timetable: Timetable, service_day: date) -> dict[tuple[str, str, str], Due]:
    """Every train of ``service_day`` keyed by departure day, origin and number (the latest arrival when two trips
    share a key)."""
    reference = service_reference(service_day)
    result: dict[tuple[str, str, str], Due] = {}
    for trip in timetable.trips:
        if not timetable.runs_on(trip.service_id, service_day):
            continue
        due = Due(rome_date(reference + trip.departure).isoformat(), trip.origin, trip.number, trip.category,
                  reference + trip.arrival)
        previous = result.get(due.key)
        if previous is None or (due.arrival, due.category) > (previous.arrival, previous.category):
            result[due.key] = due
    return result


def answerable(day: str, arrival: float, moment: float, *, asked: bool = False) -> bool:
    """Whether ViaggiaTreno still answers about the run that left on ``day`` (ISO date) with its scheduled arrival at
    ``arrival``: it left today (Rome), or yesterday, arrived at most PREVIOUS_DAY_GRACE ago and was still travelling
    at midnight, when ViaggiaTreno forgets the runs that had ended (a night train).  Its scheduled arrival after
    midnight says so; for a train ``asked`` before and not arrived yet, that answer does (a late train)."""
    today = rome_date(moment)
    if day == today.isoformat():
        return True
    if day != (today - timedelta(days=1)).isoformat() or arrival < moment - PREVIOUS_DAY_GRACE.total_seconds():
        return False
    return asked or arrival > midnight_millis(today) // 1000


def due_trains(timetable: Timetable, now: datetime) -> list[Due]:
    """Trains arrived at least 20 min ago that ViaggiaTreno still answers about (``answerable``), oldest first."""
    moment = now.timestamp()
    today = rome_date(moment)
    selected: dict[tuple[str, str, str], Due] = {}
    for service_day in (today - timedelta(days=1), today):
        for key, due in trains_on(timetable, service_day).items():
            if due.arrival <= moment - MIN_AGE.total_seconds() and answerable(due.day, due.arrival, moment):
                previous = selected.get(key)
                if previous is None or due.arrival > previous.arrival:
                    selected[key] = due
    return sorted(selected.values(), key=lambda due: (due.arrival, due.key))


# --- State --------------------------------------------------------------------------------------------------------


def _gzip_lines(lines: Iterable[str]) -> bytes:
    buffer = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buffer, mtime=0, compresslevel=9) as stream:
        for line in lines:
            stream.write(line.encode("utf-8") + b"\n")
    return buffer.getvalue()


def _write_atomic(path: Path, data: bytes) -> None:
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
        os.replace(temporary, path)
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise


def encode(record: Mapping[str, Any]) -> str:
    return json.dumps(record, ensure_ascii=True, separators=(",", ":"), sort_keys=True)


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def valid_scheduled_minute(value: object) -> bool:
    return value is None or (_is_int(value) and 0 <= value < MAX_SCHEDULED_MINUTE)


def platform_name(value: object) -> str | None:
    """``value`` as a platform label (whitespace trimmed and collapsed), ``None`` when it is not a short label."""
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        return None
    name = " ".join(str(value).split())
    return name if len(name) <= MAX_PLATFORM_CHARS and PLATFORM.fullmatch(name) else None


def valid_platform(name: object) -> bool:
    return isinstance(name, str) and platform_name(name) == name


def valid_record(record: object, day: str) -> bool:
    """A stored observation line as this tool writes it.  A stop is ``[code, delay, suppressed, scheduledMinute,
    platform, platformActual]`` (``platform`` ``null`` or a label, ``platformActual`` 1 for an actual platform, 0 for
    a programmed one or none); lines written before the extensions of 4 November 2026 have ``[code, delay,
    suppressed]`` or ``[code, delay, suppressed, scheduledMinute]`` and stay valid."""
    if not isinstance(record, dict) or set(record) != {"d", "o", "n", "c", "st", "t", "s"}:
        return False
    if record["d"] != day or not isinstance(record["o"], str) or not CODE.fullmatch(record["o"]):
        return False
    if not isinstance(record["n"], str) or not NUMBER.fullmatch(record["n"]):
        return False
    if not isinstance(record["c"], str) or not CATEGORY.fullmatch(record["c"]) or record["st"] not in STATUSES:
        return False
    if not _is_int(record["t"]) or not isinstance(record["s"], list) or len(record["s"]) > MAX_STOPS:
        return False
    for stop in record["s"]:
        if (
            not isinstance(stop, list) or len(stop) not in (3, 4, 6) or not isinstance(stop[0], str)
            or not CODE.fullmatch(stop[0])
            or not (stop[1] is None or (_is_int(stop[1]) and MIN_DELAY <= stop[1] <= MAX_DELAY))
            or stop[2] not in (0, 1) or isinstance(stop[2], bool)
            or (len(stop) >= 4 and not valid_scheduled_minute(stop[3]))
            or (len(stop) == 6 and not (
                stop[5] in (0, 1) and not isinstance(stop[5], bool)
                and (valid_platform(stop[4]) if stop[4] is not None else stop[5] == 0)
            ))
        ):
            return False
    if len({stop[0] for stop in record["s"]}) != len(record["s"]):
        return False
    return record["st"] == "ok" or not record["s"]


def _iso_day(value: object) -> bool:
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        return False
    try:
        date.fromisoformat(value)
    except ValueError:
        return False
    return True


def valid_pending(entry: object) -> bool:
    return (
        isinstance(entry, dict) and set(entry) == {"d", "o", "n", "c", "a", "k"} and _iso_day(entry["d"])
        and isinstance(entry["o"], str) and CODE.fullmatch(entry["o"]) is not None
        and isinstance(entry["n"], str) and NUMBER.fullmatch(entry["n"]) is not None
        and isinstance(entry["c"], str) and CATEGORY.fullmatch(entry["c"]) is not None
        and _is_int(entry["a"]) and _is_int(entry["k"]) and 0 <= entry["k"] <= 1000
    )


def _rank(record: Mapping[str, Any]) -> tuple[int, int, int]:
    samples = sum(1 for stop in record.get("s", []) if stop[1] is not None)
    return (2 if record["st"] in KNOWN else 1, samples, record["t"])


def read_lines(path: Path, counters: dict[str, int]) -> Iterator[Any]:
    try:
        text = gzip.decompress(path.read_bytes()).decode("utf-8")
    except (OSError, EOFError, UnicodeDecodeError, gzip.BadGzipFile):
        counters["state_unreadable_files"] = counters.get("state_unreadable_files", 0) + 1
        return
    for line in text.splitlines():
        if not line.strip():
            continue
        try:
            yield json.loads(line, parse_constant=_reject_constant)
        except (ValueError, RecursionError):
            counters["state_invalid_lines"] = counters.get("state_invalid_lines", 0) + 1


def observation_files(directory: Path) -> dict[str, Path]:
    files = {}
    for path in directory.glob("obs-*.jsonl.gz"):
        match = OBS_FILE.fullmatch(path.name)
        if match:
            files[match.group(1)] = path
    return files


def load_observations(directory: Path, day: str, counters: dict[str, int]) -> dict[tuple[str, str], dict[str, Any]]:
    """The records of one departure day keyed by (origin, number), duplicates merged."""
    records: dict[tuple[str, str], dict[str, Any]] = {}
    path = directory / f"obs-{day}.jsonl.gz"
    if not path.is_file():
        return records
    for record in read_lines(path, counters):
        if not valid_record(record, day):
            counters["state_invalid_lines"] = counters.get("state_invalid_lines", 0) + 1
            continue
        key = (record["o"], record["n"])
        if key not in records or _rank(record) > _rank(records[key]):
            records[key] = record
    return records


class State:
    """Observations (one file per departure day) and pending trains in ``directory``."""

    def __init__(self, directory: Path):
        self.directory = directory
        self.counters: dict[str, int] = {}
        self.days: dict[str, dict[tuple[str, str], dict[str, Any]]] = {}
        self.dirty: set[str] = set()
        self.pending: dict[tuple[str, str, str], dict[str, Any]] = {}
        directory.mkdir(parents=True, exist_ok=True)
        pending_path = directory / PENDING_FILE
        if pending_path.is_file():
            for entry in read_lines(pending_path, self.counters):
                if valid_pending(entry):
                    self.pending[(entry["d"], entry["o"], entry["n"])] = entry
                else:
                    self.counters["state_invalid_lines"] = self.counters.get("state_invalid_lines", 0) + 1

    def records(self, day: str) -> dict[tuple[str, str], dict[str, Any]]:
        if day not in self.days:
            self.days[day] = load_observations(self.directory, day, self.counters)
        return self.days[day]

    def known(self, key: tuple[str, str, str]) -> bool:
        return (key[1], key[2]) in self.records(key[0])

    def add(self, record: dict[str, Any]) -> None:
        records = self.records(record["d"])
        key = (record["o"], record["n"])
        if key not in records or _rank(record) > _rank(records[key]):
            records[key] = record
            self.dirty.add(record["d"])
        self.pending.pop((record["d"], record["o"], record["n"]), None)

    def save(self, today: date) -> None:
        cutoff = (today - timedelta(days=RETENTION_DAYS)).isoformat()
        for day in sorted(self.dirty):
            if day < cutoff:
                continue
            lines = [encode(self.days[day][key]) for key in sorted(self.days[day])]
            _write_atomic(self.directory / f"obs-{day}.jsonl.gz", _gzip_lines(lines))
        self.dirty.clear()
        pending = [encode(self.pending[key]) for key in sorted(self.pending)]
        _write_atomic(self.directory / PENDING_FILE, _gzip_lines(pending))
        for day, path in observation_files(self.directory).items():
            if day < cutoff:
                path.unlink()
                self.counters["pruned_days"] = self.counters.get("pruned_days", 0) + 1


# --- ViaggiaTreno -------------------------------------------------------------------------------------------------


@dataclass
class FetchResult:
    kind: str  # "ok", "missing", "failure", "refused"
    body: bytes = b""
    reason: str = ""  # fixed label, never text from the response


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_: Any, **__: Any) -> None:  # type: ignore[override]
        return None


def redirect_target(current: str, location: str | None, base: str) -> str | None:
    """The URL a redirect may lead to: the same resource (same path, no query, credentials or fragment) on the
    configured host, on ``www.viaggiatreno.it`` over HTTP or on ``(www.)viaggiatreno.it`` over HTTPS."""
    if not location or any(ord(character) < 33 for character in location):
        return None
    try:
        source = urllib.parse.urlsplit(current)
        target = urllib.parse.urlsplit(urllib.parse.urljoin(current, location))
        configured = urllib.parse.urlsplit(base)
        port = target.port
    except ValueError:
        return None
    if target.path != source.path or target.query or target.fragment or target.username or target.password:
        return None
    host = (target.hostname or "").lower()
    same_as_base = (target.scheme, host, port) == (configured.scheme, (configured.hostname or "").lower(), configured.port)
    public_http = target.scheme == "http" and host == "www.viaggiatreno.it" and port in (None, 80)
    public_https = target.scheme == "https" and host in ("www.viaggiatreno.it", "viaggiatreno.it") and port in (None, 443)
    return urllib.parse.urlunsplit(target) if same_as_base or public_http or public_https else None


class HttpFetcher:
    """One ViaggiaTreno resource at a time: at most ``rate`` requests per second, bounded body and time, no cookies."""

    def __init__(
        self,
        base_url: str = BASE_URL,
        *,
        rate: float = MAX_RATE,
        timeout: float = 15.0,
        max_bytes: int = MAX_RESPONSE_BYTES,
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
    ):
        if not 0 < rate <= MAX_RATE:
            raise CollectorError(f"--rate deve essere compreso fra 0 e {MAX_RATE} richieste al secondo")
        self.base_url = base_url
        self.interval = 1.0 / rate
        self.timeout = timeout
        self.max_bytes = max_bytes
        self.clock = clock
        self.sleep = sleep
        self.requests = 0
        self._last_start: float | None = None
        self.opener = urllib.request.build_opener(_NoRedirect())

    def _throttle(self) -> None:
        if self._last_start is not None:
            wait = self._last_start + self.interval - self.clock()
            if wait > 0:
                self.sleep(wait)
        self._last_start = self.clock()

    def _read(self, response: Any, started: float) -> bytes | None:
        declared = response.headers.get("Content-Length")
        if declared is not None and declared.strip().isdigit() and int(declared) > self.max_bytes:
            return None
        chunks: list[bytes] = []
        size = 0
        while True:
            if self.clock() - started > 2 * self.timeout:
                raise TimeoutError("lettura troppo lenta")
            chunk = response.read(64 * 1024)
            if not chunk:
                return b"".join(chunks)
            size += len(chunk)
            if size > self.max_bytes:
                return None
            chunks.append(chunk)

    def fetch(self, path: str) -> FetchResult:
        url = self.base_url + path
        for _ in range(MAX_REDIRECTS + 1):
            self._throttle()
            self.requests += 1
            started = self.clock()
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
            try:
                with self.opener.open(request, timeout=self.timeout) as response:
                    status = response.status
                    if status == 204:
                        return FetchResult("missing", reason="http-204")
                    if not 200 <= status < 300:
                        return FetchResult("failure", reason=f"http-{status}")
                    body = self._read(response, started)
                    if body is None:
                        return FetchResult("failure", reason="troppo-grande")
                    return FetchResult("ok", body)
            except urllib.error.HTTPError as error:
                status, location = error.code, error.headers.get("Location") if error.headers else None
                error.close()
                if status in (301, 302, 303, 307, 308):
                    target = redirect_target(url, location, self.base_url)
                    if target is None:
                        return FetchResult("failure", reason="redirect-non-consentito")
                    url = target
                    continue
                if status == 404:
                    return FetchResult("missing", reason="http-404")
                if status in (403, 429):
                    return FetchResult("refused", reason=f"http-{status}")
                return FetchResult("failure", reason=f"http-{status}")
            except TimeoutError:
                return FetchResult("failure", reason="timeout")
            except urllib.error.URLError as error:
                return FetchResult("failure", reason="timeout" if isinstance(error.reason, TimeoutError) else "rete")
            except (http.client.HTTPException, OSError, ValueError):
                return FetchResult("failure", reason="rete")
        return FetchResult("failure", reason="troppi-redirect")


def run_path(due: Due) -> str:
    if not (CODE.fullmatch(due.origin) and NUMBER.fullmatch(due.number)):
        raise CollectorError("identità del treno non valida")
    return f"andamentoTreno/{due.origin}/{due.number}/{midnight_millis(date.fromisoformat(due.day))}"


# --- Responses ----------------------------------------------------------------------------------------------------


def _reject_constant(name: str) -> Any:
    raise ValueError(f"costante JSON non ammessa: {name}")


def _unique_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("chiave JSON duplicata")
        result[key] = value
    return result


def strict_json(body: bytes) -> Any:
    """``body`` as strict JSON: UTF-8, no NaN/Infinity, no duplicate keys."""
    return json.loads(body.decode("utf-8"), parse_constant=_reject_constant, object_pairs_hook=_unique_pairs)


def _integer(value: object) -> int | None:
    """An integer field as the app reads it (``JSONObject.text(...).toLongOrNull()``): a number or a digit string."""
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer() and abs(value) < 2 ** 53:
        return int(value)
    if isinstance(value, str) and re.fullmatch(r"-?[0-9]{1,16}", value.strip()):
        return int(value.strip())
    return None


def _delay_minutes(scheduled: int, actual: int) -> int:
    """Whole minutes from ``scheduled`` to ``actual``, truncated towards zero (``Duration.toMinutes`` in the app)."""
    difference = actual - scheduled
    return difference // 60000 if difference >= 0 else -((-difference) // 60000)


@dataclass
class Observation:
    status: str  # "ok", "cancelled", "pending", "missing", "mismatch"
    # [code, delay, suppressed, scheduled minute, platform, 1 if the platform is the actual one]
    stops: list[list[Any]] = field(default_factory=list)
    notes: dict[str, int] = field(default_factory=dict)


def interpret(payload: Any, due: Due) -> Observation:
    """What a parsed ``andamentoTreno`` answer says about ``due``."""
    if payload is None:
        return Observation("missing")
    if not isinstance(payload, dict):
        raise ValueError("risposta non è un oggetto JSON")
    day = date.fromisoformat(due.day)
    midnight = midnight_millis(day)
    if _integer(payload.get("numeroTreno")) != int(due.number) or payload.get("idOrigine") != due.origin:
        return Observation("mismatch")
    declared_day = _integer(payload.get("dataPartenzaTreno"))
    if declared_day is not None and declared_day > 0 and declared_day != midnight:
        return Observation("mismatch")
    notes: dict[str, int] = {}

    def note(name: str) -> None:
        notes[name] = notes.get(name, 0) + 1

    low, high = midnight - 86_400_000, midnight + 3 * 86_400_000

    def instant(row: Mapping[str, Any], *keys: str) -> int | None:
        for key in keys:
            value = _integer(row.get(key))
            if value is not None and value > 0:
                return value if low <= value <= high else None
        return None

    def platform(row: Mapping[str, Any], fields: tuple[tuple[str, ...], tuple[str, ...]]) -> tuple[str | None, int]:
        """The actual platform of the stop (1), else the programmed one (0) when no actual one is published, else
        none; a published value that is not a short label ends the search (no programmed platform stands in for it)."""
        for actual, keys in ((1, fields[0]), (0, fields[1])):
            for key in keys:
                value = row.get(key)
                if value is None or (isinstance(value, str) and not value.strip()):
                    continue
                name = platform_name(value)
                if name is None:
                    note("binari_non_validi")
                    return None, 0
                return name, actual
        return None, 0

    calls = payload.get("fermate")
    rows = calls if isinstance(calls, list) else []
    if len(rows) > MAX_STOPS:
        raise ValueError("troppe fermate")
    stops: list[list[Any]] = []
    actual_at: list[bool] = []
    seen: set[str] = set()
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            note("fermate_non_valide")
            continue
        code = row.get("id")
        if not isinstance(code, str) or not CODE.fullmatch(code):
            note("fermate_senza_codice")
            continue
        stop_type = _integer(row.get("actualFermataType"))
        if stop_type == EXTRA_STOP_TYPE:
            note("fermate_straordinarie")
            continue
        if code in seen:
            note("fermate_ripetute")
            continue
        seen.add(code)
        if index == 0:
            scheduled, actual = instant(row, "partenza_teorica", "programmata"), instant(row, "partenzaReale")
        else:
            scheduled, actual = instant(row, "arrivo_teorico", "programmata"), instant(row, "arrivoReale")
        suppressed = 1 if stop_type == SUPPRESSED_STOP_TYPE else 0
        delay = None
        if not suppressed and scheduled is not None and actual is not None:
            delay = _delay_minutes(scheduled, actual)
            if not MIN_DELAY <= delay <= MAX_DELAY:
                note("ritardi_non_plausibili")
                delay = None
        minute = None if scheduled is None else scheduled_minute(day, scheduled)
        name, actual_platform = (None, 0) if suppressed else platform(
            row, ORIGIN_PLATFORM_FIELDS if index == 0 else STOP_PLATFORM_FIELDS)
        stops.append([code, delay, suppressed, minute, name, actual_platform])
        actual_at.append(actual is not None and not suppressed)

    cancelled = (
        _integer(payload.get("provvedimento")) == 1
        or payload.get("tipoTreno") == "ST"
        or (bool(stops) and all(stop[2] for stop in stops))
    )
    if cancelled:
        return Observation("cancelled", [], notes)
    served = [index for index, stop in enumerate(stops) if not stop[2]]
    arrived = bool(served) and actual_at[served[-1]]
    if not arrived and payload.get("arrivato") is True and served:
        arrived = True
    if not arrived:
        return Observation("pending", [], notes)
    return Observation("ok", stops, notes)


# --- Run ----------------------------------------------------------------------------------------------------------


@dataclass
class Report:
    counters: dict[str, int] = field(default_factory=dict)
    failures: dict[str, int] = field(default_factory=dict)
    stop_reason: str = "completata"

    def add(self, name: str, amount: int = 1) -> None:
        self.counters[name] = self.counters.get(name, 0) + amount


def pending_entry(due: Due, attempts: int) -> dict[str, Any]:
    return {"d": due.day, "o": due.origin, "n": due.number, "c": due.category, "a": due.arrival, "k": attempts}


def collect(
    timetable: Timetable,
    state: State,
    fetch: Callable[[str], FetchResult],
    now: datetime,
    *,
    max_requests: int,
    deadline: float,
    clock: Callable[[], float] = time.monotonic,
    requests: Callable[[], int] | None = None,
) -> Report:
    """One run: expire old pending trains, ask about the pending and newly due ones, record the answers."""
    report = Report()
    moment = int(now.timestamp())
    for key, entry in sorted(state.pending.items()):
        # Asked and not arrived yet: 8 h after the scheduled arrival.  Left over by the limits of a run, never asked:
        # while ViaggiaTreno still answers about it.  Either way, never past ViaggiaTreno's day.
        closed = not answerable(entry["d"], entry["a"], moment, asked=entry["k"] > 0)
        if closed or (entry["k"] > 0 and moment > entry["a"] + PENDING_LIMIT.total_seconds()):
            state.add({"d": key[0], "o": key[1], "n": key[2], "c": entry["c"], "st": "unknown", "t": moment, "s": []})
            report.add("giorno_chiuso" if closed and entry["k"] == 0 else "scaduti_sconosciuti")
    queue: list[tuple[Due, int]] = []
    for key, entry in sorted(state.pending.items(), key=lambda item: (item[1]["a"], item[0])):
        if state.known(key):  # answered by an earlier run whose pending list was not saved
            del state.pending[key]
            continue
        queue.append((Due(entry["d"], entry["o"], entry["n"], entry["c"], entry["a"]), entry["k"]))
    queued = {due.key for due, _ in queue}
    for due in due_trains(timetable, now):
        if due.key in queued:
            continue
        if state.known(due.key):
            report.add("gia_noti")
            continue
        queue.append((due, 0))
        queued.add(due.key)
        report.add("nuovi")
    report.add("in_coda", len(queue))
    consecutive = 0
    issued = requests or (lambda: report.counters.get("treni_chiesti", 0))

    def defer(rest: Sequence[tuple[Due, int]], reason: str) -> None:
        """Stop the run: the trains not asked stay pending, with their attempts, for the next run."""
        report.stop_reason = reason
        for later, later_attempts in rest:
            state.pending[later.key] = pending_entry(later, later_attempts)
        report.add("rimandati", len(rest))

    def failed(due: Due, attempts: int, reason: str) -> None:
        report.failures[reason] = report.failures.get(reason, 0) + 1
        state.pending[due.key] = pending_entry(due, attempts + 1)
        report.add("errori")

    for position, (due, attempts) in enumerate(queue):
        if issued() >= max_requests:
            defer(queue[position:], "limite --max-requests")
            break
        if clock() >= deadline:
            defer(queue[position:], "limite --deadline-seconds")
            break
        if consecutive >= MAX_CONSECUTIVE_FAILURES:
            defer(queue[position:], f"{MAX_CONSECUTIVE_FAILURES} errori consecutivi")
            break
        report.add("treni_chiesti")
        result = fetch(run_path(due))
        if result.kind in ("failure", "refused"):
            consecutive += 1
            failed(due, attempts, result.reason)
            if result.kind == "refused":
                defer(queue[position + 1:], f"fonte che rifiuta le richieste ({result.reason})")
                break
            continue
        if result.kind == "missing":
            observation = Observation("missing")
        else:
            try:
                observation = interpret(strict_json(result.body), due)
            except (ValueError, UnicodeDecodeError, RecursionError):
                consecutive += 1
                failed(due, attempts, "json-non-valido")
                continue
        consecutive = 0
        for name, amount in observation.notes.items():
            report.add(name, amount)
        if observation.status == "pending":
            state.pending[due.key] = pending_entry(due, attempts + 1)
            report.add("non_ancora_arrivati")
            continue
        state.add({"d": due.day, "o": due.origin, "n": due.number, "c": due.category, "st": observation.status,
                   "t": moment, "s": observation.stops})
        report.add({"ok": "arrivati", "cancelled": "cancellati"}.get(observation.status, observation.status))
        report.add("campioni", sum(1 for stop in observation.stops if stop[1] is not None))
    report.add("in_sospeso_dopo", len(state.pending))
    return report


LABELS = (
    ("in_coda", "Treni in coda"), ("nuovi", "nuovi nella finestra"), ("gia_noti", "già noti, non richiesti"),
    ("treni_chiesti", "Treni chiesti"), ("richieste_http", "Richieste HTTP"), ("arrivati", "Arrivati"), ("cancellati", "Cancellati"),
    ("non_ancora_arrivati", "Non ancora arrivati (in sospeso)"), ("missing", "Sconosciuti a ViaggiaTreno"),
    ("mismatch", "Identità diversa"), ("errori", "Errori"), ("rimandati", "Rimandati alla prossima run"),
    ("scaduti_sconosciuti", "Scaduti dopo 8 h (sconosciuti)"), ("giorno_chiuso", "Mai chiesti, giorno chiuso da ViaggiaTreno"), ("in_sospeso_dopo", "In sospeso dopo la run"),
    ("campioni", "Campioni di ritardo"), ("fermate_straordinarie", "Fermate straordinarie ignorate"),
    ("ritardi_non_plausibili", "Ritardi fuori intervallo scartati"), ("binari_non_validi", "Binari non validi scartati"), ("state_invalid_lines", "Righe di stato scartate"),
    ("pruned_days", "Giorni eliminati (oltre 35)"),
)


def render(report: Report, *, markdown: bool) -> str:
    rows = [(label, report.counters.get(key, 0)) for key, label in LABELS if report.counters.get(key, 0) or key in
            ("in_coda", "treni_chiesti", "richieste_http", "arrivati", "errori")]
    rows += [(f"Errore {log_safe(reason)}", count) for reason, count in sorted(report.failures.items())]
    if markdown:
        lines = ["### Puntualità: raccolta ViaggiaTreno", "", f"Esito: {log_safe(report.stop_reason)}", "",
                 "| Voce | Numero |", "|---|---|"]
        lines += [f"| {label} | {count} |" for label, count in rows]
        return "\n".join(lines) + "\n"
    return "\n".join([f"Esito: {log_safe(report.stop_reason)}"] + [f"{label}: {count}" for label, count in rows])


def estimate(timetable: Timetable, days: Sequence[date]) -> str:
    lines = [f"Corse del pack scartate: {', '.join(f'{log_safe(k)} {v}' for k, v in sorted(timetable.skipped.items())) or 'nessuna'}"]
    for day in days:
        trains = trains_on(timetable, day)
        categories: dict[str, int] = {}
        for due in trains.values():
            categories[due.category] = categories.get(due.category, 0) + 1
        per_category = ", ".join(f"{name} {count}" for name, count in sorted(categories.items()))
        lines.append(f"{day.isoformat()}: {len(trains)} treni ({per_category}); "
                     f"{len(trains) / 12:.0f} richieste per run in media con 12 run al giorno")
    return "\n".join(lines)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--state", type=Path, help="directory dello stato (osservazioni e treni in sospeso)")
    parser.add_argument("--gtfs", type=Path, action="append",
                        help="pack GTFS, una volta per pack (predefiniti: Trenitalia e Trenord inclusi nell'app)")
    parser.add_argument("--stations", type=Path, default=DEFAULT_STATIONS, help="catalogo curato delle stazioni")
    parser.add_argument("--now", help="istante della run (ISO 8601 con fuso; predefinito: adesso)")
    parser.add_argument("--max-requests", type=int, default=3000, help="richieste HTTP al massimo (predefinito 3000)")
    parser.add_argument("--rate", type=float, default=MAX_RATE, help="richieste al secondo, al massimo 2 (predefinito 2)")
    parser.add_argument("--deadline-seconds", type=float, default=1800.0, help="durata massima della raccolta in secondi")
    parser.add_argument("--timeout", type=float, default=15.0, help="timeout di una richiesta in secondi")
    parser.add_argument("--summary", type=Path, help="file Markdown a cui aggiungere il riepilogo (GITHUB_STEP_SUMMARY)")
    parser.add_argument("--estimate", action="append", type=date.fromisoformat, metavar="AAAA-MM-GG",
                        help="stampa i treni del giorno e le richieste per run, senza rete né stato")
    parser.add_argument("--base-url", default=BASE_URL, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    started = time.monotonic()
    try:
        stations = StationCodes.load(args.stations)
        timetable = load_timetables(args.gtfs or [DEFAULT_GTFS, DEFAULT_TRENORD], stations)
        if args.estimate:
            print(estimate(timetable, args.estimate))
            return 0
        if args.state is None:
            parser.error("--state è obbligatorio")
        if args.max_requests < 0 or args.deadline_seconds <= 0 or args.timeout <= 0:
            parser.error("--max-requests, --deadline-seconds e --timeout devono essere positivi")
        now = datetime.fromisoformat(args.now) if args.now else datetime.now(timezone.utc)
        if now.tzinfo is None:
            parser.error("--now deve indicare il fuso orario")
        fetcher = HttpFetcher(args.base_url, rate=args.rate, timeout=args.timeout)
        state = State(args.state)
        report = collect(timetable, state, fetcher.fetch, now, max_requests=args.max_requests,
                         deadline=started + args.deadline_seconds, requests=lambda: fetcher.requests)
        report.counters["richieste_http"] = fetcher.requests
        state.save(rome_date(now.timestamp()))
        for name, amount in state.counters.items():
            report.add(name, amount)
    except CollectorError as error:
        print(f"ERRORE: {log_safe(error)}", file=sys.stderr)
        return 1
    print(render(report, markdown=False))
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as summary:
            summary.write(render(report, markdown=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
