#!/usr/bin/env python3
"""Build the compact, versioned static GTFS schedule used by the iOS app.

The builder intentionally uses only the Python standard library. It treats every
input as untrusted, validates referential integrity before publication, and only
keeps complete trips which touch a catalog station having a railwayCode or
trenitaliaLocationId, matched by normalized name (or the leading part of a
bilingual name such as "Merano Meran") and verified by distance. In the Italian
packs a stop matching no coded station may match a catalogue station without
codes the same way (EAV's Cumana and Circumflegrea, the Renon railway, Trento -
Mezzolombardo), as Android's name match does. Catalogue entries describing one
physical station collapse onto a single preferred entry with codes; homonyms that
geography cannot separate stay unmatched (fail closed).
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import os
import re
import sqlite3
import sys
import tempfile
import unicodedata
import zipfile
import zlib
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path, PurePosixPath
from typing import Iterable, Iterator, Mapping, Sequence
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import foreign_pack_rules as foreign_rules  # noqa: E402  (border zone: the one rule of every tool)
import android_stop_matcher  # noqa: E402  (the Android app's stop matcher, for the pins' station lists)

APPLICATION_ID = int.from_bytes(b"BNGT", "big")
# 2: trip.bikes_allowed (GTFS bikes_allowed: 1 allowed, 2 not allowed, NULL unknown).
USER_VERSION = 2
METADATA_SCHEMA_VERSION = 1
DEFAULT_MAX_COMPRESSED_BYTES = 32 * 1024 * 1024
MAX_MANIFEST_BYTES = 1 * 1024 * 1024
MAX_CATALOG_BYTES = 16 * 1024 * 1024
MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
MAX_ARCHIVE_MEMBERS = 64
MAX_MEMBER_BYTES = 128 * 1024 * 1024
MAX_ARCHIVE_UNCOMPRESSED_BYTES = 256 * 1024 * 1024
MAX_ROWS = {
    "agency.txt": 10_000,
    "routes.txt": 100_000,
    "trips.txt": 250_000,
    "stops.txt": 250_000,
    "stop_times.txt": 2_000_000,
    "calendar.txt": 250_000,
    "calendar_dates.txt": 500_000,
}
MAX_FIELD_CHARS = 65_536
MAX_MATERIALIZED_TEXT_CHARS = 32 * 1024 * 1024
MAX_SERVICE_DAYS = 3660
MAX_RETAINED_TRIPS = 250_000
MAX_RETAINED_STOP_TIMES = 750_000
MAX_PARENT_INHERITANCE_DISTANCE_KM = 10.0
# Same-name catalogue entries this close are one station (e.g. an RFI and a Ferrovienord
# code for Varese Nord). The iOS station search hides all but the preferred one with the
# same rule, see CuratedStationCatalog.isShadowedDuplicate.
CO_LOCATED_DUPLICATE_KM = 1.0
# A GTFS name that is only the leading part of a catalogue name ("Merano" for
# "Merano Meran") must be this close to it.
MAX_PREFIX_MATCH_DISTANCE_KM = 1.0
# The packs of the Android core manifest the index reads (androidOnlySources aside): ten until the Croatian HŽPP pack
# was removed in November 2026, ten again with Trenitalia France's pack (23 November 2026).
INDEXED_PACK_COUNT = 10
# Packs of foreign operators: their stops match only catalogue stations with codes, so the index keeps their trips
# into Italy and not the French, Swiss or Austrian networks around a station the catalogue lists without codes (Paris).
# Trenitalia France's pack is bundled whole, outside the border-subset system (foreign_pack_rules): its Paris – Milano
# trains keep Oulx, Torino Porta Susa and Milano Centrale; Lyon Part-Dieu, listed with a Trenitalia location id, keeps
# every train of the pack calling there, its Paris – Lyon and Marseille – Paris ones too, so that station's board
# lists all of this operator's trains; Paris, Chambéry and Marseille, listed without codes, stay unnamed.
FOREIGN_PACKS = frozenset({"ch-otd-gtfs", "oebb-gtfs", "sncf-gtfs", "trenitalia-france-gtfs"})
# Reviewed packs whose source numbers a few trips out of their time order: such a trip (a stop's arrival before the
# previous stop's departure along stop_sequence) is left out of the index and counted in the metadata
# (filter.outOfOrderTripsLeftOut), instead of failing the build as it does for every other pack.  Trenitalia France's
# GTFS of November 2026 numbers four weekend runs of train 9281 (28-29 November, 5-6 December) Oulx 5, Torino Porta
# Susa 24, Milano Centrale 38, Paris 39, Lyon 90..., while their times run from Paris at 07:30 to Milano at 14:07; its
# other runs are in order.  Never reordered by time: the published order is wrong, and guessing another is not ours.
# More than MAX_OUT_OF_ORDER_TRIP_SHARE of a pack's kept trips still fails the build.
OUT_OF_ORDER_TRIPS_LEFT_OUT = frozenset({"trenitalia-france-gtfs"})
MAX_OUT_OF_ORDER_TRIP_SHARE = 0.05
OUT_OF_ORDER_COUNT_KEY = "out_of_order_trip_left_out"
# How a pack's stops may match the catalogue: an Italian pack falls back to stations without codes; a foreign pack
# bundled whole matches stations with codes only; a border subset matches its zone stations with or without codes
# (design §4.2, the list computed from its full pack); a per-country index (--foreign-full) every station.
STATION_MODE_ITALIAN = "italian"
STATION_MODE_CODED = "coded"
STATION_MODE_ZONE = "zone"
STATION_MODE_FULL = "full"
# Version of the per-country index (--foreign-full) builder, part of its input fingerprint: raise it when a change of
# this file changes the content of those indexes, so the apps see a new pin.
FOREIGN_FULL_BUILDER_VERSION = 1
FOREIGN_FULL_KIND = "foreign-full"
RAIL_ROUTE_TYPES = frozenset({2, *range(100, 200)})
# Packs whose bikes_allowed is stored as unknown, as on Android (GtfsDayTimetable.kt): the Italo
# pack publishes 1 on every trip, but Italo admits only folding bikes carried in a bag, so "bikes
# allowed" would mislead. The pack is Android-only today (binario_gtfs_source_policy_v1.json);
# the override is named per pack, never a rule by category, and applies if it joins the index.
BIKES_VALUE_NOT_SHOWN = frozenset({"italo-gtfs"})
REQUIRED_FILES = ("agency.txt", "routes.txt", "trips.txt", "stops.txt", "stop_times.txt")
SCHEMA_TABLES = ("metadata", "pack", "agency", "route", "service", "trip", "stop", "stop_time")
REQUIRED_INDICES = (
    "idx_route_pack_external",
    "idx_service_pack_external",
    "idx_trip_pack_external",
    "idx_trip_route_service",
    "idx_stop_pack_external",
    "idx_stop_catalog_station",
    "idx_stop_time_stop_departure",
)

SCHEMA_SQL = """
CREATE TABLE metadata (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
) WITHOUT ROWID;
CREATE TABLE pack (
    id INTEGER PRIMARY KEY,
    source_id TEXT NOT NULL UNIQUE,
    attribution TEXT NOT NULL,
    last_service_date INTEGER NOT NULL
);
CREATE TABLE agency (
    id INTEGER PRIMARY KEY,
    pack_id INTEGER NOT NULL REFERENCES pack(id),
    external_id TEXT NOT NULL,
    name TEXT NOT NULL,
    timezone TEXT NOT NULL,
    UNIQUE(pack_id, external_id)
);
CREATE TABLE route (
    id INTEGER PRIMARY KEY,
    pack_id INTEGER NOT NULL REFERENCES pack(id),
    agency_id INTEGER NOT NULL REFERENCES agency(id),
    external_id TEXT NOT NULL,
    short_name TEXT,
    long_name TEXT,
    route_type INTEGER NOT NULL,
    UNIQUE(pack_id, external_id)
);
CREATE TABLE service (
    id INTEGER PRIMARY KEY,
    pack_id INTEGER NOT NULL REFERENCES pack(id),
    external_id TEXT NOT NULL,
    start_date INTEGER NOT NULL,
    day_count INTEGER NOT NULL CHECK(day_count > 0),
    active_days BLOB NOT NULL,
    UNIQUE(pack_id, external_id),
    CHECK(length(active_days) = (day_count + 7) / 8)
);
CREATE TABLE trip (
    id INTEGER PRIMARY KEY,
    pack_id INTEGER NOT NULL REFERENCES pack(id),
    route_id INTEGER NOT NULL REFERENCES route(id),
    service_id INTEGER NOT NULL REFERENCES service(id),
    external_id TEXT NOT NULL,
    headsign TEXT,
    short_name TEXT,
    direction_id INTEGER,
    bikes_allowed INTEGER CHECK(bikes_allowed IN (1, 2)),
    UNIQUE(pack_id, external_id)
);
CREATE TABLE stop (
    id INTEGER PRIMARY KEY,
    pack_id INTEGER NOT NULL REFERENCES pack(id),
    external_id TEXT NOT NULL,
    name TEXT NOT NULL,
    latitude REAL,
    longitude REAL,
    catalog_station_id TEXT,
    railway_code TEXT,
    trenitalia_location_id TEXT,
    UNIQUE(pack_id, external_id)
);
CREATE TABLE stop_time (
    trip_id INTEGER NOT NULL REFERENCES trip(id),
    stop_sequence INTEGER NOT NULL,
    stop_id INTEGER NOT NULL REFERENCES stop(id),
    arrival_seconds INTEGER,
    departure_seconds INTEGER,
    pickup_type INTEGER NOT NULL,
    drop_off_type INTEGER NOT NULL,
    PRIMARY KEY(trip_id, stop_sequence)
) WITHOUT ROWID;
CREATE UNIQUE INDEX idx_route_pack_external ON route(pack_id, external_id);
CREATE UNIQUE INDEX idx_service_pack_external ON service(pack_id, external_id);
CREATE UNIQUE INDEX idx_trip_pack_external ON trip(pack_id, external_id);
CREATE INDEX idx_trip_route_service ON trip(route_id, service_id);
CREATE UNIQUE INDEX idx_stop_pack_external ON stop(pack_id, external_id);
CREATE INDEX idx_stop_catalog_station ON stop(catalog_station_id) WHERE catalog_station_id IS NOT NULL;
CREATE INDEX idx_stop_time_stop_departure ON stop_time(stop_id, departure_seconds, trip_id);
"""

class BuildError(RuntimeError):
    """A deterministic, user-facing input validation error."""

@dataclass(frozen=True)
class CatalogIdentity:
    station_id: str
    railway_code: str | None
    trenitalia_location_id: str | None
    latitude: float
    longitude: float

    @property
    def coded(self) -> bool:
        return bool(self.railway_code or self.trenitalia_location_id)

@dataclass(frozen=True)
class CatalogNameMatch:
    identity: CatalogIdentity
    canonical: bool

def identity_preference(identity: CatalogIdentity) -> tuple[int, int, str]:
    """Total order among co-located duplicates; mirrored by the iOS station search."""
    return (
        0 if identity.trenitalia_location_id else 1,
        0 if identity.railway_code else 1,
        identity.station_id,
    )

class CatalogStationMatcher:
    """Resolves a GTFS stop to at most one eligible catalogue identity."""

    def __init__(
        self,
        by_name: Mapping[str, Sequence[CatalogNameMatch]],
        canonical: Sequence[tuple[tuple[str, ...], CatalogIdentity]],
        all_name_positions: Mapping[str, Sequence[tuple[float, float] | None]] = {},
    ) -> None:
        self._by_name = by_name
        self._all_name_positions = all_name_positions
        self._preferred = self._collapse_co_located(canonical)
        self._prefix_candidates: dict[str, list[tuple[tuple[str, ...], CatalogIdentity]]] = defaultdict(list)
        for tokens, identity in canonical:
            if len(tokens) > 1:
                self._prefix_candidates[tokens[0]].append((tokens, identity))

    @staticmethod
    def _collapse_co_located(
        canonical: Sequence[tuple[tuple[str, ...], CatalogIdentity]],
    ) -> dict[str, CatalogIdentity]:
        by_canonical_name: dict[tuple[str, ...], list[CatalogIdentity]] = defaultdict(list)
        for tokens, identity in canonical:
            by_canonical_name[tokens].append(identity)
        better: dict[str, CatalogIdentity] = {}
        for identities in by_canonical_name.values():
            for identity in identities:
                nearby = [
                    other for other in identities
                    if distance_kilometres(identity.latitude, identity.longitude, other.latitude, other.longitude)
                    <= CO_LOCATED_DUPLICATE_KM
                ]
                best = min(nearby, key=identity_preference)
                # Only an entry with codes shadows another, as in the iOS station search (isShadowedDuplicate):
                # two entries without codes both stay offered, so neither may lose its stops to the other.
                if best.station_id != identity.station_id and best.coded:
                    better[identity.station_id] = best
        resolved: dict[str, CatalogIdentity] = {}
        for station_id, target in better.items():
            # Follow the chain to an entry that nothing better shadows.
            seen = {station_id}
            while target.station_id in better and target.station_id not in seen:
                seen.add(target.station_id)
                target = better[target.station_id]
            resolved[station_id] = target
        return resolved

    def preferred(self, identity: CatalogIdentity) -> CatalogIdentity:
        return self._preferred.get(identity.station_id, identity)

    @property
    def unambiguous_name_count(self) -> int:
        return sum(
            1 for matches in self._by_name.values()
            if len({self.preferred(match.identity).station_id for match in matches}) == 1
        )

    def match(self, row: Mapping[str, str], context: str, *, uncoded: bool = False) -> CatalogIdentity | None:
        """The one catalogue station with codes this stop is; with ``uncoded`` (an Italian pack), when none, the one
        station without codes it is, by the same rules.  A stop matched today keeps its station."""
        latitude = parse_optional_float(row.get("stop_lat"), f"{context}:lat", -90, 90)
        longitude = parse_optional_float(row.get("stop_lon"), f"{context}:lon", -180, 180)
        if latitude is None or longitude is None:
            return None
        name = normalize_station_name(row["stop_name"])
        identity = self._match(name, latitude, longitude, coded=True)
        if identity is None and uncoded:
            identity = self._match(name, latitude, longitude, coded=False)
        if identity is not None:
            return identity
        # Only a stop the published name leaves unmatched tries its variants (name_variants), stations with codes
        # first, so a stop matched before keeps its station.
        names = [normalized for variant in name_variants(row["stop_name"])[1:]
                 if (normalized := normalize_station_name(variant)) and normalized != name]
        for coded in (True, False) if uncoded else (True,):
            for variant in dict.fromkeys(names):
                identity = self._match(variant, latitude, longitude, coded=coded)
                if identity is not None:
                    return identity
        return None

    def _match(self, name: str, latitude: float, longitude: float, *, coded: bool) -> CatalogIdentity | None:
        verified = {
            self.preferred(match.identity).station_id: self.preferred(match.identity)
            for match in self._by_name.get(name, ())
            if match.identity.coded == coded
            and distance_kilometres(latitude, longitude, match.identity.latitude, match.identity.longitude)
            <= (10.0 if match.canonical else 1.0)
        }
        if not verified and name and not self._names_another_catalog_station(name, latitude, longitude):
            tokens = tuple(name.split(" "))
            verified = {
                self.preferred(identity).station_id: self.preferred(identity)
                for candidate_tokens, identity in self._prefix_candidates.get(tokens[0], ())
                if identity.coded == coded
                and len(candidate_tokens) > len(tokens)
                and candidate_tokens[: len(tokens)] == tokens
                and distance_kilometres(latitude, longitude, identity.latitude, identity.longitude)
                <= MAX_PREFIX_MATCH_DISTANCE_KM
            }
        # Homonyms that geography cannot separate stay unmatched.
        return next(iter(verified.values())) if len(verified) == 1 else None

    def _names_another_catalog_station(self, name: str, latitude: float, longitude: float) -> bool:
        """A nearby catalogue entry with exactly this name, even one without timetable codes
        (the Cumana "Bagnoli" next to RFI "Bagnoli-Agnano Terme"), is that stop's station."""
        return any(
            position is None
            or distance_kilometres(latitude, longitude, position[0], position[1]) <= 10.0
            for position in self._all_name_positions.get(name, ())
        )

@dataclass(frozen=True)
class InputFile:
    path: str
    size: int
    sha256: str

@dataclass
class IdAllocator:
    agency: int = 0
    route: int = 0
    service: int = 0
    trip: int = 0
    stop: int = 0
    def next(self, kind: str) -> int:
        value = getattr(self, kind) + 1
        setattr(self, kind, value)
        return value

def canonical_json(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")

def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()

def parse_yyyymmdd(value: str, context: str) -> date:
    if not re.fullmatch(r"\d{8}", value):
        raise BuildError(f"{context}: expected YYYYMMDD, got {value!r}")
    try:
        return date(int(value[:4]), int(value[4:6]), int(value[6:]))
    except ValueError as error:
        raise BuildError(f"{context}: invalid date {value!r}") from error

def date_int(value: date) -> int:
    return value.year * 10_000 + value.month * 100 + value.day

def normalize_station_name(value: str) -> str:
    # NFKD + alphanumeric tokenization deliberately handles accents, punctuation,
    # whitespace and case, but does not make risky geographic/fuzzy inferences.
    ascii_value = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode("ascii").casefold()
    return " ".join(re.findall(r"[a-z0-9]+", ascii_value))

# Stop-name variants, ported from Android's GtfsStopStationResolver (same patterns, ASCII classes as in Java):
# "Brennero, Stazione di Brennero" -> "Brennero"; "Linz Centrale 1" -> "Linz Centrale"; "Domodossola (I)" ->
# "Domodossola"; "Lienz Bahnhof" -> "Lienz"; "Innsbruck Hbf." and "Innsbruck Hauptbahnhof" are one name.
STATION_OF = re.compile(r"\bstazione(?:\s+di)?\s+(.+)$", re.IGNORECASE | re.ASCII)
TRAILING_PLATFORM = re.compile(r"\s+[0-9]{1,2}[A-Za-z]?$", re.ASCII)
COUNTRY_SUFFIX = re.compile(r"\s*\((?:I|IT|CH|A|AT|F|FR|D|DE|SLO|HR)\)$", re.IGNORECASE | re.ASCII)
STATION_SUFFIX = re.compile(r"\s+(?:stazione|bahnhof|bhf)$", re.IGNORECASE | re.ASCII)
MAIN_STATION_ABBREVIATION = re.compile(r"\bHbf\b\.?", re.IGNORECASE | re.ASCII)

def expand_abbreviations(value: str) -> str:
    """"Innsbruck Hbf." -> "Innsbruck Hauptbahnhof" (Android: GtfsStopStationResolver.expandAbbreviations)."""
    return MAIN_STATION_ABBREVIATION.sub("Hauptbahnhof", value)

def name_variants(stop_name: str) -> list[str]:
    """The published name, then the forms Android's GtfsStopStationResolver.variants tries, in its order: the part
    after "Stazione di", the part after "Town, ", each without a platform number, a country suffix or a trailing
    "stazione"/"Bahnhof"/"Bhf", and with "Hbf" written out."""
    trimmed = stop_name.strip()
    if not trimmed:
        return []
    base = [trimmed]
    station_of = STATION_OF.search(trimmed)
    if station_of and station_of.group(1).strip():
        base.append(station_of.group(1).strip())
    if "," in trimmed and trimmed.split(",", 1)[1].strip():
        base.append(trimmed.split(",", 1)[1].strip())
    variants: list[str] = []
    for value in base:
        cleaned = STATION_SUFFIX.sub("", COUNTRY_SUFFIX.sub("", TRAILING_PLATFORM.sub("", value))).strip()
        for candidate in (value, cleaned, expand_abbreviations(cleaned)):
            if candidate and candidate not in variants:
                variants.append(candidate)
    return variants

def required(row: Mapping[str, str], field: str, context: str) -> str:
    value = row.get(field, "")
    if not value:
        raise BuildError(f"{context}: required field {field!r} is empty")
    return value

def optional_text(value: str | None) -> str | None:
    return value if value else None

def parse_int(value: str, context: str, *, minimum: int | None = None, maximum: int | None = None) -> int:
    if not re.fullmatch(r"-?\d+", value):
        raise BuildError(f"{context}: invalid integer {value!r}")
    result = int(value)
    if minimum is not None and result < minimum or maximum is not None and result > maximum:
        raise BuildError(f"{context}: integer {result} outside [{minimum}, {maximum}]")
    return result

def bikes_allowed(source_id: str, value: str | None) -> int | None:
    """GTFS bikes_allowed as the apps show it: 1 allowed, 2 not allowed; empty, 0 (no information),
    anything else or a pack of BIKES_VALUE_NOT_SHOWN is unknown (NULL)."""
    if source_id in BIKES_VALUE_NOT_SHOWN:
        return None
    raw = (value or "").strip()
    return int(raw) if raw in ("1", "2") else None

def parse_optional_float(value: str | None, context: str, minimum: float, maximum: float) -> float | None:
    if value in (None, ""):
        return None
    try:
        result = float(value)
    except ValueError as error:
        raise BuildError(f"{context}: invalid number {value!r}") from error
    if not minimum <= result <= maximum:
        raise BuildError(f"{context}: number {result} outside [{minimum}, {maximum}]")
    return result

def parse_gtfs_time(value: str | None, context: str) -> int | None:
    if value in (None, ""):
        return None
    match = re.fullmatch(r"(\d{1,3}):(\d{2}):(\d{2})", value)
    if not match:
        raise BuildError(f"{context}: invalid GTFS time {value!r}")
    hour, minute, second = map(int, match.groups())
    if hour > 167 or minute > 59 or second > 59:
        raise BuildError(f"{context}: GTFS time out of bounded range {value!r}")
    return hour * 3600 + minute * 60 + second

def read_bounded(path: Path, maximum_bytes: int, context: str) -> bytes:
    try:
        declared_size = path.stat().st_size
        if declared_size > maximum_bytes:
            raise BuildError(f"{context} exceeds {maximum_bytes} bytes")
        payload = path.read_bytes()
    except OSError as error:
        raise BuildError(f"cannot read {context} {path}: {error}") from error
    if len(payload) > maximum_bytes:
        raise BuildError(f"{context} exceeds {maximum_bytes} bytes")
    return payload

def load_catalog(path: Path) -> tuple[CatalogStationMatcher, int]:
    try:
        payload = json.loads(read_bounded(path, MAX_CATALOG_BYTES, "station catalog"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise BuildError(f"cannot read station catalog {path}: {error}") from error
    if payload.get("schemaVersion") != 1 or not isinstance(payload.get("stations"), list):
        raise BuildError("station catalog has unsupported schema")
    by_name: dict[str, dict[str, CatalogNameMatch]] = defaultdict(dict)
    canonical: list[tuple[tuple[str, ...], CatalogIdentity]] = []
    all_name_positions: dict[str, list[tuple[float, float] | None]] = defaultdict(list)
    eligible = 0
    for index, station in enumerate(payload["stations"]):
        if not isinstance(station, dict):
            raise BuildError(f"catalog station {index} is not an object")
        railway = station.get("railwayCode")
        location = station.get("trenitaliaLocationId")
        record_name_positions(station, all_name_positions)
        station_id = station.get("id")
        name = station.get("name")
        aliases = station.get("aliases", [])
        if not isinstance(station_id, str) or not station_id or not isinstance(name, str) or not isinstance(aliases, list):
            raise BuildError(f"catalog station {index} has invalid identity/name/aliases")
        latitude = station.get("latitude")
        longitude = station.get("longitude")
        if not isinstance(latitude, (int, float)) or not isinstance(longitude, (int, float)):
            # Name-only matching is not a safe geographic identity boundary.
            continue
        if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
            raise BuildError(f"catalog station {station_id}: invalid coordinates")
        identity = CatalogIdentity(station_id, railway or None, location or None, float(latitude), float(longitude))
        eligible += identity.coded
        canonical_name = normalize_station_name(name)
        if canonical_name:
            canonical.append((tuple(canonical_name.split(" ")), identity))
        for candidate_index, candidate in enumerate((name, *aliases)):
            if not isinstance(candidate, str):
                raise BuildError(f"catalog station {station_id}: non-string alias")
            # "Innsbruck Hbf." is also known as "Innsbruck Hauptbahnhof", the name the ÖBB pack publishes.
            for normalized in dict.fromkeys((normalize_station_name(candidate), normalize_station_name(expand_abbreviations(candidate)))):
                if not normalized:
                    continue
                current = by_name[normalized].get(station_id)
                by_name[normalized][station_id] = CatalogNameMatch(
                    identity=identity,
                    canonical=candidate_index == 0 or (current is not None and current.canonical),
                )
    return CatalogStationMatcher(
        {name: list(values.values()) for name, values in by_name.items()},
        canonical,
        all_name_positions,
    ), eligible

def record_name_positions(station: Mapping[str, object], positions: dict[str, list[tuple[float, float] | None]]) -> None:
    latitude = station.get("latitude")
    longitude = station.get("longitude")
    position = (
        (float(latitude), float(longitude))
        if isinstance(latitude, (int, float)) and isinstance(longitude, (int, float))
        else None
    )
    aliases = station.get("aliases")
    for candidate in (station.get("name"), *(aliases if isinstance(aliases, list) else ())):
        if not isinstance(candidate, str):
            continue
        for normalized in dict.fromkeys((normalize_station_name(candidate), normalize_station_name(expand_abbreviations(candidate)))):
            if normalized:
                positions[normalized].append(position)

def distance_kilometres(first_latitude: float, first_longitude: float, second_latitude: float, second_longitude: float) -> float:
    earth_radius_km = 6_371.0
    first_latitude_radians = math.radians(first_latitude)
    second_latitude_radians = math.radians(second_latitude)
    latitude_delta = math.radians(second_latitude - first_latitude)
    longitude_delta = math.radians(second_longitude - first_longitude)
    haversine = (
        math.sin(latitude_delta / 2) ** 2
        + math.cos(first_latitude_radians)
        * math.cos(second_latitude_radians)
        * math.sin(longitude_delta / 2) ** 2
    )
    return 2 * earth_radius_km * math.asin(math.sqrt(haversine))

def geographically_verified_parent_identity(
    row: Mapping[str, str],
    identity: CatalogIdentity,
    context: str,
) -> CatalogIdentity | None:
    latitude = parse_optional_float(row.get("stop_lat"), f"{context}:lat", -90, 90)
    longitude = parse_optional_float(row.get("stop_lon"), f"{context}:lon", -180, 180)
    if latitude is None and longitude is None:
        return identity
    if latitude is None or longitude is None:
        return None
    distance = distance_kilometres(latitude, longitude, identity.latitude, identity.longitude)
    return identity if distance <= MAX_PARENT_INHERITANCE_DISTANCE_KM else None

def validated_timezone(value: str | None, context: str) -> str:
    timezone = (value or "").strip()
    if not timezone:
        raise BuildError(f"{context}: missing IANA timezone")
    try:
        ZoneInfo(timezone)
    except (ZoneInfoNotFoundError, ValueError) as error:
        raise BuildError(f"{context}: invalid IANA timezone {timezone!r}") from error
    return timezone

class BoundedGtfsArchive:
    def __init__(self, path: Path) -> None:
        self.path = path
        if not path.is_file():
            raise BuildError(f"missing GTFS archive: {path}")
        if path.stat().st_size > MAX_ARCHIVE_BYTES:
            raise BuildError(f"archive exceeds {MAX_ARCHIVE_BYTES} bytes: {path}")
        try:
            self.archive = zipfile.ZipFile(path)
        except (OSError, zipfile.BadZipFile) as error:
            raise BuildError(f"invalid ZIP {path}: {error}") from error
        infos = self.archive.infolist()
        if len(infos) > MAX_ARCHIVE_MEMBERS:
            self.close()
            raise BuildError(f"{path}: too many ZIP members")
        total = 0
        names: set[str] = set()
        for info in infos:
            pure = PurePosixPath(info.filename)
            if info.is_dir():
                continue
            if pure.name != info.filename or info.filename in names:
                self.close()
                raise BuildError(f"{path}: unsafe or duplicate ZIP member {info.filename!r}")
            names.add(info.filename)
            if info.file_size > MAX_MEMBER_BYTES:
                self.close()
                raise BuildError(f"{path}: member {info.filename} is too large")
            total += info.file_size
        if total > MAX_ARCHIVE_UNCOMPRESSED_BYTES:
            self.close()
            raise BuildError(f"{path}: uncompressed ZIP size exceeds bound")
        missing = set(REQUIRED_FILES) - names
        if missing:
            self.close()
            raise BuildError(f"{path}: missing required GTFS files {sorted(missing)}")
        if "calendar.txt" not in names and "calendar_dates.txt" not in names:
            self.close()
            raise BuildError(f"{path}: no calendar.txt or calendar_dates.txt")
        self.names = names
    def close(self) -> None:
        self.archive.close()
    def __enter__(self) -> "BoundedGtfsArchive":
        return self
    def __exit__(self, *_: object) -> None:
        self.close()
    def rows(self, filename: str, required_headers: Sequence[str]) -> Iterator[dict[str, str]]:
        if filename not in self.names:
            return
        limit = MAX_ROWS[filename]
        try:
            binary = self.archive.open(filename)
            text = io.TextIOWrapper(binary, encoding="utf-8-sig", errors="strict", newline="")
            reader = csv.DictReader(text, strict=True)
            headers = reader.fieldnames
            if headers is None or len(headers) != len(set(headers)) or None in headers or "" in headers:
                raise BuildError(f"{self.path}:{filename}: missing or duplicate header")
            absent = set(required_headers) - set(headers)
            if absent:
                raise BuildError(f"{self.path}:{filename}: missing columns {sorted(absent)}")
            for line, row in enumerate(reader, start=2):
                if line - 1 > limit:
                    raise BuildError(f"{self.path}:{filename}: exceeds {limit} rows")
                if None in row:
                    raise BuildError(f"{self.path}:{filename}:{line}: extra CSV fields")
                if any(value is None for value in row.values()):
                    raise BuildError(f"{self.path}:{filename}:{line}: missing CSV fields")
                if any(len(value) > MAX_FIELD_CHARS for value in row.values()):
                    raise BuildError(f"{self.path}:{filename}:{line}: field exceeds character bound")
                yield row
        except (UnicodeError, csv.Error, OSError, RuntimeError, zipfile.BadZipFile) as error:
            if isinstance(error, BuildError):
                raise
            raise BuildError(f"cannot parse {self.path}:{filename}: {error}") from error
        finally:
            try:
                text.close()  # type: ignore[possibly-undefined]
            except UnboundLocalError:
                pass

def unique_rows(rows: Iterable[dict[str, str]], key: str, context: str) -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    retained_characters = 0
    for line, row in enumerate(rows, start=2):
        value = required(row, key, f"{context}:{line}")
        if value in result:
            raise BuildError(f"{context}:{line}: duplicate {key} {value!r}")
        retained_characters += sum(len(field) for field in row.values())
        if retained_characters > MAX_MATERIALIZED_TEXT_CHARS:
            raise BuildError(f"{context}: retained text exceeds {MAX_MATERIALIZED_TEXT_CHARS} characters")
        result[value] = row
    return result

def compute_service_bits(
    service_id: str,
    calendar_row: dict[str, str] | None,
    exceptions: Mapping[date, int],
    context: str,
) -> tuple[int, int, bytes]:
    active: set[date] = set()
    bounds: list[date] = list(exceptions)
    if calendar_row is not None:
        start = parse_yyyymmdd(required(calendar_row, "start_date", context), context)
        end = parse_yyyymmdd(required(calendar_row, "end_date", context), context)
        if end < start or (end - start).days + 1 > MAX_SERVICE_DAYS:
            raise BuildError(f"{context}: invalid or excessive calendar range")
        bounds.extend((start, end))
        weekdays = []
        for field in ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"):
            value = required(calendar_row, field, context)
            if value not in ("0", "1"):
                raise BuildError(f"{context}: {field} must be 0 or 1")
            weekdays.append(value == "1")
        current = start
        while current <= end:
            if weekdays[current.weekday()]:
                active.add(current)
            current += timedelta(days=1)
    if not bounds:
        raise BuildError(f"{context}: referenced service {service_id!r} has no calendar definition")
    for day, exception_type in exceptions.items():
        if exception_type == 1:
            active.add(day)
        elif exception_type == 2:
            active.discard(day)
        else:
            raise BuildError(f"{context}: exception_type must be 1 or 2")
    start = min(bounds)
    end = max(bounds)
    day_count = (end - start).days + 1
    if day_count > MAX_SERVICE_DAYS:
        raise BuildError(f"{context}: service bitset exceeds {MAX_SERVICE_DAYS} days")
    bits = bytearray((day_count + 7) // 8)
    for day in active:
        offset = (day - start).days
        if 0 <= offset < day_count:
            bits[offset // 8] |= 1 << (offset % 8)
    return date_int(start), day_count, bytes(bits)

def read_manifest(
    manifest_path: Path,
    gtfs_root: Path,
    catalog_path: Path,
    source_policy_path: Path | None = None,
    full_pack_dir: Path | None = None,
) -> tuple[list[dict[str, object]], Path, list[InputFile], str, str]:
    try:
        manifest_bytes = read_bounded(manifest_path, MAX_MANIFEST_BYTES, "GTFS manifest")
        manifest = json.loads(manifest_bytes)
    except (json.JSONDecodeError, UnicodeError) as error:
        raise BuildError(f"cannot read GTFS manifest: {error}") from error
    generated_at = manifest.get("generatedAt")
    if not isinstance(generated_at, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", generated_at):
        raise BuildError("GTFS manifest generatedAt must be an ISO calendar date")
    try:
        date.fromisoformat(generated_at)
    except ValueError as error:
        raise BuildError("GTFS manifest generatedAt is not a valid calendar date") from error
    packs = manifest.get("packs")
    if not isinstance(packs, list):
        raise BuildError(f"GTFS manifest must contain exactly {INDEXED_PACK_COUNT} packs")
    policy_by_id: dict[str, tuple[str, str]] | None = None
    android_only: dict[str, tuple[str, str]] = {}
    policy_bytes: bytes | None = None
    if source_policy_path is not None:
        try:
            policy_bytes = read_bounded(source_policy_path, MAX_MANIFEST_BYTES, "GTFS source policy")
            policy = json.loads(policy_bytes)
        except (json.JSONDecodeError, UnicodeError) as error:
            raise BuildError(f"cannot read GTFS source policy: {error}") from error
        sources = policy.get("sources") if isinstance(policy, dict) else None
        if not isinstance(policy, dict) or policy.get("schemaVersion") != 1 or not isinstance(sources, list) or len(sources) != INDEXED_PACK_COUNT:
            raise BuildError(f"GTFS source policy must contain exactly {INDEXED_PACK_COUNT} schema-v1 sources")
        policy_by_id = {}
        for index, source in enumerate(sources):
            if not isinstance(source, dict) or set(source) != {"sourceId", "sourceUrl", "accessMode", "licence", "landingPage"}:
                raise BuildError(f"GTFS source policy entry {index} is invalid")
            source_id, source_url, access_mode = source["sourceId"], source["sourceUrl"], source["accessMode"]
            if not all(isinstance(value, str) and value for value in (source_id, source_url, access_mode)):
                raise BuildError(f"GTFS source policy entry {index} is incomplete")
            # The licence is shown in the app's source list; null records that it has not been
            # confirmed yet, an empty or non-string value is a mistake.
            licence = source["licence"]
            if licence is not None and (not isinstance(licence, str) or not licence.strip()):
                raise BuildError(f"GTFS source policy entry {source_id!r} has an invalid licence")
            if source_id in policy_by_id or access_mode != "OPEN_DIRECT" or not source_url.startswith("https://"):
                raise BuildError(f"GTFS source policy entry {source_id!r} is not an authorised HTTPS direct source")
            # The public page of the feed, which the app's source list links to (not the archive).
            landing_page = source["landingPage"]
            if not isinstance(landing_page, str) or not landing_page.startswith("https://") or len(landing_page) <= len("https://"):
                raise BuildError(f"GTFS source policy entry {source_id!r} has an invalid landing page")
            policy_by_id[source_id] = (source_url, access_mode)
        # Packs the Android app embeds but the iOS index deliberately leaves out (reviewed, with a reason): they
        # must still match the manifest exactly, so an unreviewed manifest addition keeps failing closed.
        excluded = policy.get("androidOnlySources", [])
        if not isinstance(excluded, list):
            raise BuildError("GTFS source policy androidOnlySources must be a list")
        for index, source in enumerate(excluded):
            if not isinstance(source, dict) or set(source) != {"sourceId", "sourceUrl", "accessMode", "reason"}:
                raise BuildError(f"GTFS source policy androidOnlySources entry {index} is invalid")
            source_id, source_url, access_mode, reason = (source[key] for key in ("sourceId", "sourceUrl", "accessMode", "reason"))
            if not all(isinstance(value, str) and value.strip() for value in (source_id, source_url, access_mode, reason)):
                raise BuildError(f"GTFS source policy androidOnlySources entry {index} is incomplete")
            if source_id in policy_by_id or source_id in android_only:
                raise BuildError(f"GTFS source policy lists {source_id!r} twice")
            android_only[source_id] = (source_url, access_mode)
    indexed_packs = []
    for item in packs:
        source_id = item.get("sourceId") if isinstance(item, dict) else None
        if source_id in android_only:
            if android_only[source_id] != (item.get("sourceUrl"), item.get("accessMode")):
                raise BuildError(f"{source_id}: manifest source URL/access mode differs from reviewed iOS policy")
            continue
        indexed_packs.append(item)
    if set(android_only) - {item.get("sourceId") for item in packs if isinstance(item, dict)}:
        raise BuildError("GTFS source policy androidOnlySources names packs absent from the manifest")
    packs = indexed_packs
    if len(packs) != INDEXED_PACK_COUNT:
        raise BuildError(f"GTFS manifest must contain exactly {INDEXED_PACK_COUNT} packs")
    inputs = [
        InputFile(manifest_path.as_posix(), len(manifest_bytes), hashlib.sha256(manifest_bytes).hexdigest()),
        InputFile(catalog_path.as_posix(), catalog_path.stat().st_size, sha256_file(catalog_path)),
    ]
    if source_policy_path is not None and policy_bytes is not None:
        inputs.append(InputFile(source_policy_path.as_posix(), len(policy_bytes), hashlib.sha256(policy_bytes).hexdigest()))
    source_ids: set[str] = set()
    normalized: list[dict[str, object]] = []
    for index, item in enumerate(packs):
        if not isinstance(item, dict):
            raise BuildError(f"manifest pack {index} is not an object")
        source_id = item.get("sourceId")
        attribution = item.get("attribution")
        last = item.get("lastServiceDate")
        asset = item.get("asset")
        expected_hash = item.get("sha256")
        if not all(isinstance(value, str) and value for value in (source_id, attribution, last, asset, expected_hash)):
            raise BuildError(f"manifest pack {index} lacks required metadata")
        if source_id in source_ids:
            raise BuildError(f"duplicate sourceId {source_id}")
        source_ids.add(source_id)
        if policy_by_id is not None and policy_by_id.get(source_id) != (item.get("sourceUrl"), item.get("accessMode")):
            raise BuildError(f"{source_id}: manifest source URL/access mode differs from reviewed iOS policy")
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", last):
            raise BuildError(f"{source_id}: invalid lastServiceDate")
        try:
            last_date = date.fromisoformat(last)
        except ValueError as error:
            raise BuildError(f"{source_id}: invalid lastServiceDate") from error
        asset_pure = PurePosixPath(asset)
        if asset_pure.is_absolute() or ".." in asset_pure.parts or asset_pure.name != asset_pure.parts[-1]:
            raise BuildError(f"{source_id}: unsafe asset path")
        # Manifest paths are Android-assets relative (gtfs/foo.zip); gtfs_root is .../gtfs.
        archive_path = gtfs_root / asset_pure.name
        actual_hash = sha256_file(archive_path)
        if actual_hash != expected_hash:
            raise BuildError(f"{source_id}: archive SHA-256 does not match manifest")
        inputs.append(InputFile(archive_path.as_posix(), archive_path.stat().st_size, actual_hash))
        pack_entry: dict[str, object] = {
            "source_id": source_id,
            "attribution": attribution,
            "last_service_date": date_int(last_date),
            "archive": archive_path,
        }
        full = item.get("fullPack")
        if full is not None:
            # A border subset (licence-confirmed foreign pack): its zone stations come from its full pack, an input.
            rule = ((item.get("filter") or {}).get("borderZone") or {}).get("rule") if isinstance(item.get("filter"), dict) else None
            if source_id not in FOREIGN_PACKS or not isinstance(full, dict) or not isinstance(full.get("path"), str) \
                    or not isinstance(full.get("sha256"), str) or rule != foreign_rules.BORDER_ZONE_RULE:
                raise BuildError(f"{source_id}: invalid fullPack/borderZone in the manifest")
            full_archive = (full_pack_dir or ROOT / foreign_rules.FULL_PACK_DIR) / PurePosixPath(full["path"]).name
            if not full_archive.is_file() or sha256_file(full_archive) != full["sha256"]:
                raise BuildError(f"{source_id}: full pack missing or different from manifest fullPack: {full_archive}")
            inputs.append(InputFile(full_archive.as_posix(), full_archive.stat().st_size, full["sha256"]))
            pack_entry.update(station_mode=STATION_MODE_ZONE, full_archive=full_archive)
        normalized.append(pack_entry)
    if policy_by_id is not None and source_ids != set(policy_by_id):
        raise BuildError("GTFS manifest source IDs differ from reviewed iOS policy")
    input_root = Path(os.path.commonpath([item.path for item in inputs]))
    if input_root.is_file():
        input_root = input_root.parent
    inputs = [InputFile(Path(item.path).relative_to(input_root).as_posix(), item.size, item.sha256) for item in inputs]
    descriptor = [{"path": item.path, "size": item.size, "sha256": item.sha256} for item in inputs]
    fingerprint = hashlib.sha256(canonical_json(descriptor)).hexdigest()
    return normalized, input_root, inputs, fingerprint, generated_at

def stop_identities(
    stops: Mapping[str, Mapping[str, str]],
    catalog: CatalogStationMatcher,
    source_id: str,
    *,
    uncoded: bool,
) -> dict[str, CatalogIdentity]:
    """The catalogue station of every stop that has one: by its name verified by distance, else inherited from its
    parent station (only an unambiguous verified identity, geographically coherent with the child's coordinates)."""
    stop_identity: dict[str, CatalogIdentity] = {}
    for stop_id, row in stops.items():
        identity = catalog.match(row, f"{source_id}:stop:{stop_id}", uncoded=uncoded)
        if identity is not None:
            stop_identity[stop_id] = identity
    for stop_id, row in stops.items():
        parent = row.get("parent_station", "")
        child_type = row.get("location_type", "") or "0"
        parent_type = stops.get(parent, {}).get("location_type", "")
        if stop_id not in stop_identity and parent in stop_identity and child_type in {"0", "4"} and parent_type == "1":
            inherited = geographically_verified_parent_identity(row, stop_identity[parent], f"{source_id}:stop:{stop_id}")
            if inherited is not None:
                stop_identity[stop_id] = inherited
    return stop_identity

def archive_station_trips(
    archive_path: Path,
    catalog: CatalogStationMatcher,
    source_id: str,
) -> tuple[dict[str, dict[str, str]], dict[str, CatalogIdentity], dict[str, set[str]]]:
    """A foreign pack read for its station lists: stops, their catalogue stations (with or without codes, as in a
    full per-country index) and, per trip, the stops it calls at."""
    with BoundedGtfsArchive(archive_path) as archive:
        stops = unique_rows(archive.rows("stops.txt", ("stop_id", "stop_name")), "stop_id", f"{source_id}:stops.txt")
        identities = stop_identities(stops, catalog, source_id, uncoded=True)
        calls: dict[str, set[str]] = defaultdict(set)
        for line, row in enumerate(archive.rows("stop_times.txt", ("trip_id", "stop_id", "stop_sequence")), start=2):
            stop_id = required(row, "stop_id", f"{source_id}:stop_times.txt:{line}")
            if stop_id not in stops:
                raise BuildError(f"{source_id}:stop_times.txt:{line}: unknown stop_id {stop_id!r}")
            calls[required(row, "trip_id", f"{source_id}:stop_times.txt:{line}")].add(stop_id)
    return stops, identities, dict(calls)

def foreign_zone(stops: Mapping[str, Mapping[str, str]], catalog: CatalogStationMatcher, source_id: str) -> "foreign_rules.PackZone":
    """The zone stops of a foreign pack (foreign_pack_rules: Italian stations and the pack's corridors), resolved
    with the catalogue identities of this matcher."""
    return foreign_rules.pack_zone(source_id, stops, stop_identities(stops, catalog, source_id, uncoded=True))

def foreign_station_coverage(
    archive_path: Path,
    catalog: CatalogStationMatcher,
    source_id: str,
    android: "android_stop_matcher.AndroidStopMatcher",
) -> tuple[set[str], set[str]]:
    """``(served, zone)`` catalogue ids of a full foreign pack (design §1.1, rule 5).

    A station is *served* when a stop some trip calls at resolves to it, with this matcher (the iPhone's) or with the
    Android app's (``android``): either app may name it as a journey end. A served station is a *zone* station when
    every such stop is complete in the border subset: each trip calling there is a kept trip (one calling at a zone
    stop: an Italian station or a station of one of the pack's corridors). Elsewhere the subset's trains are partial."""
    stops, identities, calls = archive_station_trips(archive_path, catalog, source_id)
    zone_stops = foreign_rules.pack_zone(source_id, stops, identities).stops
    android_ids = android.pack_stations(stops)
    incomplete: set[str] = set()
    called: set[str] = set()
    for stop_ids in calls.values():
        called |= stop_ids
        if not stop_ids & zone_stops:
            incomplete |= stop_ids
    related: dict[str, set[str]] = defaultdict(set)
    for stop_id in called:
        identity = identities.get(stop_id)
        for station_id in {identity.station_id if identity is not None else None, android_ids.get(stop_id)} - {None}:
            related[str(station_id)].add(stop_id)
    return set(related), {station_id for station_id, members in related.items() if not members & incomplete}

def pair_trip_counts(
    archive_path: Path,
    catalog: CatalogStationMatcher,
    source_id: str,
    pairs: Iterable[tuple[str, str]],
) -> dict[tuple[str, str], int]:
    """Trips of a pack calling at both catalogue stations of each pair (in either order)."""
    _, identities, calls = archive_station_trips(archive_path, catalog, source_id)
    stations_by_trip = [
        {identities[stop_id].station_id for stop_id in stop_ids if stop_id in identities} for stop_ids in calls.values()
    ]
    return {
        (first, second): sum(1 for stations in stations_by_trip if first in stations and second in stations)
        for first, second in pairs
    }

def out_of_order_kept_trips(connection: sqlite3.Connection) -> set[str]:
    """Kept trips of the staged pack with an arrival before the previous departure along stop_sequence: the trips the
    ingest below would refuse ("arrival regresses"), by the same rule."""
    found: set[str] = set()
    previous_trip_id: str | None = None
    previous_departure: int | None = None
    for trip_id, arrival, departure in connection.execute(
        "SELECT st.trip_external_id,st.arrival_seconds,st.departure_seconds "
        "FROM temp.ios_gtfs_staged_stop_time st "
        "JOIN temp.ios_gtfs_kept_trip kt ON kt.trip_external_id=st.trip_external_id "
        "ORDER BY st.trip_external_id,st.stop_sequence"
    ):
        if trip_id != previous_trip_id:
            previous_trip_id, previous_departure = str(trip_id), None
        if arrival is not None and previous_departure is not None and arrival < previous_departure:
            found.add(str(trip_id))
        if departure is not None:
            previous_departure = int(departure)
    return found

def ingest_pack(
    connection: sqlite3.Connection,
    pack_id: int,
    pack: Mapping[str, object],
    catalog: CatalogStationMatcher,
    allocator: IdAllocator,
) -> dict[str, int]:
    source_id = str(pack["source_id"])
    archive_path = Path(pack["archive"])
    mode = str(pack.get("station_mode") or (STATION_MODE_CODED if source_id in FOREIGN_PACKS else STATION_MODE_ITALIAN))
    counts = defaultdict(int)
    connection.execute(
        "INSERT INTO pack(id,source_id,attribution,last_service_date) VALUES(?,?,?,?)",
        (pack_id, source_id, pack["attribution"], pack["last_service_date"]),
    )
    counts["pack"] += 1
    with BoundedGtfsArchive(archive_path) as archive:
        agencies: dict[str, dict[str, str]] = {}
        retained_agency_characters = 0
        for line, row in enumerate(archive.rows("agency.txt", ("agency_name", "agency_url", "agency_timezone")), start=2):
            # agency_id is optional when the feed has exactly one agency. Use the
            # empty string as its stable external key rather than rejecting it.
            key = row.get("agency_id", "")
            if key in agencies:
                raise BuildError(f"{source_id}:agency.txt:{line}: duplicate agency_id {key!r}")
            retained_agency_characters += sum(len(field) for field in row.values())
            if retained_agency_characters > MAX_MATERIALIZED_TEXT_CHARS:
                raise BuildError(f"{source_id}:agency.txt: retained text exceeds {MAX_MATERIALIZED_TEXT_CHARS} characters")
            agencies[key] = row
        if not agencies:
            raise BuildError(f"{source_id}:agency.txt: at least one agency is required")
        routes = unique_rows(archive.rows("routes.txt", ("route_id", "route_type")), "route_id", f"{source_id}:routes.txt")
        trips = unique_rows(archive.rows("trips.txt", ("route_id", "service_id", "trip_id")), "trip_id", f"{source_id}:trips.txt")
        stops = unique_rows(archive.rows("stops.txt", ("stop_id", "stop_name")), "stop_id", f"{source_id}:stops.txt")

        stop_identity = stop_identities(stops, catalog, source_id, uncoded=mode != STATION_MODE_CODED)
        if mode == STATION_MODE_ZONE:
            # A border subset names only its zone stations (design §4.2): a stop of any other station keeps no
            # catalogue id, so the iPhone never offers it as a journey end, while trips still list it.
            allowed = pack["zone_station_ids"]
            stop_identity = {stop_id: identity for stop_id, identity in stop_identity.items() if identity.station_id in allowed}

        connection.executescript("""
            DROP TABLE IF EXISTS temp.ios_gtfs_staged_stop_time;
            DROP TABLE IF EXISTS temp.ios_gtfs_kept_trip;
            CREATE TEMP TABLE ios_gtfs_staged_stop_time (
                trip_external_id TEXT NOT NULL,
                stop_sequence INTEGER NOT NULL,
                stop_external_id TEXT NOT NULL,
                arrival_seconds INTEGER,
                departure_seconds INTEGER,
                pickup_type INTEGER NOT NULL,
                drop_off_type INTEGER NOT NULL,
                PRIMARY KEY(trip_external_id, stop_sequence)
            ) WITHOUT ROWID;
            CREATE TEMP TABLE ios_gtfs_kept_trip (
                trip_external_id TEXT PRIMARY KEY NOT NULL
            ) WITHOUT ROWID;
        """)
        for line, row in enumerate(archive.rows("stop_times.txt", ("trip_id", "stop_id", "stop_sequence")), start=2):
            trip_id = required(row, "trip_id", f"{source_id}:stop_times.txt:{line}")
            stop_id = required(row, "stop_id", f"{source_id}:stop_times.txt:{line}")
            if trip_id not in trips:
                raise BuildError(f"{source_id}:stop_times.txt:{line}: unknown trip_id {trip_id!r}")
            if stop_id not in stops:
                raise BuildError(f"{source_id}:stop_times.txt:{line}: unknown stop_id {stop_id!r}")
            sequence = parse_int(required(row, "stop_sequence", f"{source_id}:stop_times.txt:{line}"), f"{source_id}:stop_times.txt:{line}", minimum=0, maximum=1_000_000)
            arrival = parse_gtfs_time(row.get("arrival_time"), f"{source_id}:stop_times.txt:{line}:arrival_time")
            departure = parse_gtfs_time(row.get("departure_time"), f"{source_id}:stop_times.txt:{line}:departure_time")
            if arrival is None and departure is None:
                pass
            elif arrival is None or departure is None:
                raise BuildError(f"{source_id}:stop_times.txt:{line}: arrival/departure must both be set or both empty")
            elif departure < arrival:
                raise BuildError(f"{source_id}:stop_times.txt:{line}: departure precedes arrival")
            pickup = parse_int(row.get("pickup_type", "") or "0", f"{source_id}:stop_times.txt:{line}:pickup_type", minimum=0, maximum=3)
            drop_off = parse_int(row.get("drop_off_type", "") or "0", f"{source_id}:stop_times.txt:{line}:drop_off_type", minimum=0, maximum=3)
            try:
                connection.execute(
                    "INSERT INTO temp.ios_gtfs_staged_stop_time VALUES(?,?,?,?,?,?,?)",
                    (trip_id, sequence, stop_id, arrival, departure, pickup, drop_off),
                )
            except sqlite3.IntegrityError as error:
                raise BuildError(f"{source_id}: duplicate stop_sequence {sequence} in trip {trip_id!r}")
            if stop_id in stop_identity:
                connection.execute(
                    "INSERT OR IGNORE INTO temp.ios_gtfs_kept_trip VALUES(?)",
                    (trip_id,),
                )
        observed_trip_count = int(connection.execute(
            "SELECT count(DISTINCT trip_external_id) FROM temp.ios_gtfs_staged_stop_time"
        ).fetchone()[0])
        if observed_trip_count != len(trips):
            raise BuildError(f"{source_id}: {len(trips) - observed_trip_count} trips have no stop_times")
        if source_id in OUT_OF_ORDER_TRIPS_LEFT_OUT:
            out_of_order = out_of_order_kept_trips(connection)
            kept_before = int(connection.execute("SELECT count(*) FROM temp.ios_gtfs_kept_trip").fetchone()[0])
            if len(out_of_order) > MAX_OUT_OF_ORDER_TRIP_SHARE * kept_before:
                raise BuildError(f"{source_id}: {len(out_of_order)} of {kept_before} kept trips are out of time order")
            connection.executemany(
                "DELETE FROM temp.ios_gtfs_kept_trip WHERE trip_external_id=?", ((trip_id,) for trip_id in sorted(out_of_order)),
            )
            if out_of_order:
                counts[OUT_OF_ORDER_COUNT_KEY] = len(out_of_order)
        kept_trip_count = int(connection.execute("SELECT count(*) FROM temp.ios_gtfs_kept_trip").fetchone()[0])
        if kept_trip_count > MAX_RETAINED_TRIPS:
            raise BuildError(f"{source_id}: retained trips exceed {MAX_RETAINED_TRIPS}")
        retained_stop_time_count = int(connection.execute(
            "SELECT count(*) FROM temp.ios_gtfs_staged_stop_time st "
            "JOIN temp.ios_gtfs_kept_trip kt ON kt.trip_external_id=st.trip_external_id"
        ).fetchone()[0])
        if retained_stop_time_count > MAX_RETAINED_STOP_TIMES:
            raise BuildError(f"{source_id}: retained stop_times exceed {MAX_RETAINED_STOP_TIMES}")
        kept_trip_ids = {
            str(row[0])
            for row in connection.execute("SELECT trip_external_id FROM temp.ios_gtfs_kept_trip")
        }

        used_route_ids = {trips[trip_id]["route_id"] for trip_id in kept_trip_ids}
        unknown_routes = used_route_ids - set(routes)
        if unknown_routes:
            raise BuildError(f"{source_id}: trips reference unknown routes")
        used_service_ids = {trips[trip_id]["service_id"] for trip_id in kept_trip_ids}

        calendar_rows = unique_rows(
            archive.rows("calendar.txt", ("service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "start_date", "end_date")),
            "service_id",
            f"{source_id}:calendar.txt",
        ) if "calendar.txt" in archive.names else {}
        exceptions: dict[str, dict[date, int]] = defaultdict(dict)
        if "calendar_dates.txt" in archive.names:
            for line, row in enumerate(archive.rows("calendar_dates.txt", ("service_id", "date", "exception_type")), start=2):
                service_id = required(row, "service_id", f"{source_id}:calendar_dates.txt:{line}")
                day = parse_yyyymmdd(required(row, "date", f"{source_id}:calendar_dates.txt:{line}"), f"{source_id}:calendar_dates.txt:{line}")
                if day in exceptions[service_id]:
                    raise BuildError(f"{source_id}: duplicate calendar exception for {service_id!r} on {day}")
                exceptions[service_id][day] = parse_int(required(row, "exception_type", f"{source_id}:calendar_dates.txt:{line}"), f"{source_id}:calendar_dates.txt:{line}", minimum=1, maximum=2)

        # GTFS allows omitted agency_id only when exactly one agency exists.
        agency_ids: dict[str, int] = {}
        single_agency_key = next(iter(agencies)) if len(agencies) == 1 else None
        route_agency_key: dict[str, str] = {}
        for route_id in used_route_ids:
            raw_key = routes[route_id].get("agency_id", "")
            key = raw_key or single_agency_key
            if key is None or key not in agencies:
                raise BuildError(f"{source_id}: route {route_id!r} has missing/unknown agency_id")
            route_agency_key[route_id] = key
        for key in sorted(set(route_agency_key.values())):
            row = agencies[key]
            database_id = allocator.next("agency")
            agency_ids[key] = database_id
            external_id = row.get("agency_id", "") or key
            timezone = validated_timezone(row.get("agency_timezone"), f"{source_id}:agency:{external_id}:timezone")
            connection.execute(
                "INSERT INTO agency(id,pack_id,external_id,name,timezone) VALUES(?,?,?,?,?)",
                (database_id, pack_id, external_id, required(row, "agency_name", f"{source_id}:agency"), timezone),
            )
            counts["agency"] += 1

        route_ids: dict[str, int] = {}
        for external_id in sorted(used_route_ids):
            row = routes[external_id]
            route_type = parse_int(required(row, "route_type", f"{source_id}:route:{external_id}"), f"{source_id}:route:{external_id}", minimum=0, maximum=9999)
            # TFT's railway lines, published as buses, are type 2 in the Android pack (build_gtfs_packs.ROUTE_TYPE_OVERRIDES).
            if route_type not in RAIL_ROUTE_TYPES:
                raise BuildError(f"{source_id}: route {external_id!r} is not an authorised railway route")
            database_id = allocator.next("route")
            route_ids[external_id] = database_id
            connection.execute(
                "INSERT INTO route(id,pack_id,agency_id,external_id,short_name,long_name,route_type) VALUES(?,?,?,?,?,?,?)",
                (database_id, pack_id, agency_ids[route_agency_key[external_id]], external_id, optional_text(row.get("route_short_name")), optional_text(row.get("route_long_name")), route_type),
            )
            counts["route"] += 1

        service_ids: dict[str, int] = {}
        for external_id in sorted(used_service_ids):
            start_date, day_count, bits = compute_service_bits(external_id, calendar_rows.get(external_id), exceptions.get(external_id, {}), f"{source_id}:service:{external_id}")
            database_id = allocator.next("service")
            service_ids[external_id] = database_id
            connection.execute(
                "INSERT INTO service(id,pack_id,external_id,start_date,day_count,active_days) VALUES(?,?,?,?,?,?)",
                (database_id, pack_id, external_id, start_date, day_count, bits),
            )
            counts["service"] += 1

        trip_ids: dict[str, int] = {}
        for external_id in sorted(kept_trip_ids):
            row = trips[external_id]
            direction_raw = row.get("direction_id", "")
            direction = None if direction_raw == "" else parse_int(direction_raw, f"{source_id}:trip:{external_id}:direction_id", minimum=0, maximum=1)
            database_id = allocator.next("trip")
            trip_ids[external_id] = database_id
            connection.execute(
                "INSERT INTO trip(id,pack_id,route_id,service_id,external_id,headsign,short_name,direction_id,bikes_allowed) VALUES(?,?,?,?,?,?,?,?,?)",
                (database_id, pack_id, route_ids[row["route_id"]], service_ids[row["service_id"]], external_id, optional_text(row.get("trip_headsign")), optional_text(row.get("trip_short_name")), direction, bikes_allowed(source_id, row.get("bikes_allowed"))),
            )
            counts["trip"] += 1

        used_stop_ids = {
            str(row[0])
            for row in connection.execute(
                "SELECT DISTINCT st.stop_external_id FROM temp.ios_gtfs_staged_stop_time st "
                "JOIN temp.ios_gtfs_kept_trip kt ON kt.trip_external_id=st.trip_external_id"
            )
        }

        stop_ids: dict[str, int] = {}
        for external_id in sorted(used_stop_ids):
            row = stops[external_id]
            database_id = allocator.next("stop")
            stop_ids[external_id] = database_id
            identity = stop_identity.get(external_id)
            latitude = parse_optional_float(row.get("stop_lat"), f"{source_id}:stop:{external_id}:lat", -90, 90)
            longitude = parse_optional_float(row.get("stop_lon"), f"{source_id}:stop:{external_id}:lon", -180, 180)
            connection.execute(
                "INSERT INTO stop(id,pack_id,external_id,name,latitude,longitude,catalog_station_id,railway_code,trenitalia_location_id) VALUES(?,?,?,?,?,?,?,?,?)",
                (database_id, pack_id, external_id, required(row, "stop_name", f"{source_id}:stop:{external_id}"), latitude, longitude, identity.station_id if identity else None, identity.railway_code if identity else None, identity.trenitalia_location_id if identity else None),
            )
            counts["stop"] += 1
        previous_trip_id: str | None = None
        previous_departure: int | None = None
        retained_rows = connection.execute(
            "SELECT st.trip_external_id,st.stop_sequence,st.stop_external_id,"
            "st.arrival_seconds,st.departure_seconds,st.pickup_type,st.drop_off_type "
            "FROM temp.ios_gtfs_staged_stop_time st "
            "JOIN temp.ios_gtfs_kept_trip kt ON kt.trip_external_id=st.trip_external_id "
            "ORDER BY st.trip_external_id,st.stop_sequence"
        )
        for external_trip_id, sequence, external_stop_id, arrival, departure, pickup, drop_off in retained_rows:
            line_context = f"{source_id}:stop_time:{external_trip_id}:{sequence}"
            if external_trip_id != previous_trip_id:
                previous_trip_id = str(external_trip_id)
                previous_departure = None
            if arrival is not None and previous_departure is not None and arrival < previous_departure:
                raise BuildError(f"{line_context}: arrival regresses before the previous departure")
            if departure is not None:
                previous_departure = int(departure)
            connection.execute(
                "INSERT INTO stop_time(trip_id,stop_sequence,stop_id,arrival_seconds,departure_seconds,pickup_type,drop_off_type) VALUES(?,?,?,?,?,?,?)",
                (trip_ids[str(external_trip_id)], int(sequence), stop_ids[str(external_stop_id)], arrival, departure, int(pickup), int(drop_off)),
            )
            counts["stop_time"] += 1
        connection.executescript("""
            DROP TABLE temp.ios_gtfs_staged_stop_time;
            DROP TABLE temp.ios_gtfs_kept_trip;
        """)
    return dict(counts)

def fsync_path(path: Path) -> None:
    with path.open("rb") as stream:
        os.fsync(stream.fileno())

def border_zone_station_ids(
    pack: Mapping[str, object], catalog: CatalogStationMatcher, android: "android_stop_matcher.AndroidStopMatcher",
) -> set[str]:
    """The zone stations of a border subset, computed on its full pack (design §1.1, rule 5)."""
    _, zone = foreign_station_coverage(Path(str(pack["full_archive"])), catalog, str(pack["source_id"]), android)
    return zone

def build_database(
    manifest_path: Path,
    catalog_path: Path,
    output_path: Path,
    metadata_path: Path,
    *,
    source_policy_path: Path | None = None,
    max_compressed_bytes: int = DEFAULT_MAX_COMPRESSED_BYTES,
    full_pack_dir: Path | None = None,
) -> dict[str, object]:
    gtfs_root = manifest_path.parent
    packs, input_root, input_files, input_fingerprint, source_generated_at = read_manifest(
        manifest_path,
        gtfs_root,
        catalog_path,
        source_policy_path,
        full_pack_dir,
    )
    catalog, eligible_catalog_stations = load_catalog(catalog_path)
    border = sorted(str(pack["source_id"]) for pack in packs if pack.get("station_mode") == STATION_MODE_ZONE)
    android = android_stop_matcher.load(catalog_path) if border else None
    for pack in packs:
        if pack.get("station_mode") == STATION_MODE_ZONE:
            assert android is not None
            pack["zone_station_ids"] = border_zone_station_ids(pack, catalog, android)
    filter_metadata: dict[str, object] = {
        "keepsCompleteTripWhen": "at least one stop matches, by normalized name/alias, a stop-name variant (part after 'Stazione di', without a country suffix, a trailing station word or a platform number, Hbf written out) or bilingual-name prefix verified by distance, a single catalog station with railwayCode or trenitaliaLocationId after co-located duplicates collapse onto the preferred entry; in a pack not listed in codedStationsOnly, a stop matching none may match a single catalog station without codes the same way; in a pack listed in borderSubsets only the zone stations of its full pack keep their catalog id",
        "codedStationsOnly": sorted(FOREIGN_PACKS - set(border)),
        "borderSubsets": border,
        "parentIdentityInheritance": True,
        "parentIdentityMaximumChildDistanceKm": MAX_PARENT_INHERITANCE_DISTANCE_KM,
    }
    return write_schedule(
        packs, catalog, eligible_catalog_stations, input_root, input_files, input_fingerprint, source_generated_at,
        output_path, metadata_path, max_compressed_bytes=max_compressed_bytes, filter_metadata=filter_metadata,
    )

def foreign_full_fingerprint(source_id: str, descriptor: Sequence[Mapping[str, object]]) -> str:
    """Input fingerprint of a per-country index: its full pack and the catalogue (paths, sizes, digests), the kind,
    the pack and the builder version.  The manifest and the source policy are left out on purpose: they change every
    week with other packs, and a new fingerprint would publish a new iPhone file for unchanged timetables."""
    return hashlib.sha256(canonical_json({
        "builderVersion": FOREIGN_FULL_BUILDER_VERSION, "kind": FOREIGN_FULL_KIND, "sourceId": source_id, "files": list(descriptor),
    })).hexdigest()

def build_foreign_full_database(
    manifest_path: Path,
    catalog_path: Path,
    source_id: str,
    output_path: Path,
    metadata_path: Path,
    *,
    source_policy_path: Path | None = None,
    max_compressed_bytes: int = DEFAULT_MAX_COMPRESSED_BYTES,
    full_pack_dir: Path | None = None,
) -> dict[str, object]:
    """``--foreign-full``: the index of one full foreign pack, downloaded on request by the iPhone (design §4.3).
    Same schema, application id and user version as the base index; every stop may match a catalogue station with
    or without codes; trips are kept when they touch one."""
    try:
        manifest = json.loads(read_bounded(manifest_path, MAX_MANIFEST_BYTES, "GTFS manifest"))
    except (json.JSONDecodeError, UnicodeError) as error:
        raise BuildError(f"cannot read GTFS manifest: {error}") from error
    entry = next((item for item in manifest.get("packs", []) if isinstance(item, dict) and item.get("sourceId") == source_id), None)
    full = entry.get("fullPack") if entry else None
    if entry is None or source_id not in FOREIGN_PACKS or not isinstance(full, dict):
        raise BuildError(f"{source_id}: not a foreign pack bundled as its border subset (no fullPack in the manifest)")
    attribution, last = entry.get("attribution"), full.get("lastServiceDate")
    if not isinstance(attribution, str) or not attribution or not isinstance(last, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", last):
        raise BuildError(f"{source_id}: manifest lacks attribution or fullPack.lastServiceDate")
    if source_policy_path is not None:
        policy = json.loads(read_bounded(source_policy_path, MAX_MANIFEST_BYTES, "GTFS source policy"))
        reviewed = {(item.get("sourceId"), item.get("sourceUrl"), item.get("accessMode")) for item in policy.get("sources", []) if isinstance(item, dict)}
        if (source_id, entry.get("sourceUrl"), entry.get("accessMode")) not in reviewed:
            raise BuildError(f"{source_id}: manifest source URL/access mode differs from reviewed iOS policy")
    full_archive = (full_pack_dir or ROOT / foreign_rules.FULL_PACK_DIR) / PurePosixPath(str(full.get("path"))).name
    if not full_archive.is_file() or sha256_file(full_archive) != full.get("sha256"):
        raise BuildError(f"{source_id}: full pack missing or different from manifest fullPack: {full_archive}")
    inputs = [
        InputFile(full_archive.as_posix(), full_archive.stat().st_size, str(full["sha256"])),
        InputFile(catalog_path.as_posix(), catalog_path.stat().st_size, sha256_file(catalog_path)),
    ]
    input_root = Path(os.path.commonpath([item.path for item in inputs]))
    inputs = [InputFile(Path(item.path).relative_to(input_root).as_posix(), item.size, item.sha256) for item in inputs]
    fingerprint = foreign_full_fingerprint(source_id, [{"path": item.path, "size": item.size, "sha256": item.sha256} for item in inputs])
    # The day the full pack was filtered (its tripsRunFrom plus the build's grace of two days): stable while the
    # timetable does not change, unlike the manifest's generatedAt.
    generated_at = str(manifest.get("generatedAt"))
    with zipfile.ZipFile(full_archive) as archive:
        if "binario_filter_manifest.json" in archive.namelist():
            runs_from = json.loads(archive.read("binario_filter_manifest.json").decode("utf-8")).get("tripsRunFrom")
            if isinstance(runs_from, str):
                generated_at = (date.fromisoformat(runs_from) + timedelta(days=2)).isoformat()
    catalog, eligible_catalog_stations = load_catalog(catalog_path)
    packs = [{
        "source_id": source_id,
        "attribution": attribution,
        "last_service_date": date_int(date.fromisoformat(last)),
        "archive": full_archive,
        "station_mode": STATION_MODE_FULL,
    }]
    filter_metadata: dict[str, object] = {
        "keepsCompleteTripWhen": "at least one stop matches, by normalized name/alias, a stop-name variant or bilingual-name prefix verified by distance, a single catalog station with or without codes after co-located duplicates collapse onto the preferred entry",
        "codedStationsOnly": [],
        "borderSubsets": [],
        "parentIdentityInheritance": True,
        "parentIdentityMaximumChildDistanceKm": MAX_PARENT_INHERITANCE_DISTANCE_KM,
    }
    return write_schedule(
        packs, catalog, eligible_catalog_stations, input_root, inputs, fingerprint, generated_at,
        output_path, metadata_path, max_compressed_bytes=max_compressed_bytes, filter_metadata=filter_metadata,
        extra_metadata={"kind": FOREIGN_FULL_KIND, "sourceId": source_id, "builderVersion": FOREIGN_FULL_BUILDER_VERSION},
    )

def write_schedule(
    packs: Sequence[dict[str, object]],
    catalog: CatalogStationMatcher,
    eligible_catalog_stations: int,
    input_root: Path,
    input_files: Sequence[InputFile],
    input_fingerprint: str,
    source_generated_at: str,
    output_path: Path,
    metadata_path: Path,
    *,
    max_compressed_bytes: int,
    filter_metadata: Mapping[str, object],
    extra_metadata: Mapping[str, object] | None = None,
) -> dict[str, object]:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    if output_path.parent.resolve() != metadata_path.parent.resolve():
        raise BuildError("output and metadata must share a directory for atomic publication")
    temp_dir = Path(tempfile.mkdtemp(prefix=".binario-gtfs-", dir=output_path.parent))
    database_path = temp_dir / "schedule.sqlite"
    compressed_path = temp_dir / output_path.name
    staged_metadata = temp_dir / metadata_path.name
    connection: sqlite3.Connection | None = None
    try:
        connection = sqlite3.connect(database_path)
        connection.execute("PRAGMA page_size=4096")
        connection.execute("PRAGMA journal_mode=OFF")
        connection.execute("PRAGMA synchronous=OFF")
        connection.execute("PRAGMA temp_store=FILE")
        connection.execute("PRAGMA foreign_keys=ON")
        connection.executescript(SCHEMA_SQL)
        connection.execute(f"PRAGMA application_id={APPLICATION_ID}")
        connection.execute(f"PRAGMA user_version={USER_VERSION}")
        allocator = IdAllocator()
        per_pack: dict[str, dict[str, int]] = {}
        out_of_order: dict[str, int] = {}
        connection.execute("BEGIN")
        for pack_id, pack in enumerate(packs, start=1):
            counts = ingest_pack(connection, pack_id, pack, catalog, allocator)
            left_out = counts.pop(OUT_OF_ORDER_COUNT_KEY, 0)
            if left_out:
                out_of_order[str(pack["source_id"])] = left_out
            per_pack[str(pack["source_id"])] = counts
        totals = {table: int(connection.execute(f"SELECT count(*) FROM {table}").fetchone()[0]) for table in SCHEMA_TABLES if table != "metadata"}
        if totals["trip"] == 0 or totals["stop_time"] == 0:
            raise BuildError("filter produced an empty schedule")
        metadata_rows = {
            "input_fingerprint_sha256": input_fingerprint,
            "schema_version": str(USER_VERSION),
            "source_generated_at": source_generated_at,
            "station_match_policy": "eligible-identity+geography-verified-name-or-bilingual-prefix+co-located-duplicates-collapsed-or-parent",
            "row_counts_json": json.dumps(totals, sort_keys=True, separators=(",", ":")),
        }
        connection.executemany("INSERT INTO metadata(key,value) VALUES(?,?)", metadata_rows.items())
        connection.commit()
        foreign_key_errors = connection.execute("PRAGMA foreign_key_check").fetchall()
        if foreign_key_errors:
            raise BuildError(f"generated database has foreign key errors: {foreign_key_errors[:3]}")
        connection.execute("VACUUM")
        connection.close()
        connection = None
        database_hash = sha256_file(database_path)
        compressor = zlib.compressobj(level=9, method=zlib.DEFLATED, wbits=zlib.MAX_WBITS)
        with database_path.open("rb") as source, compressed_path.open("wb") as target:
            while chunk := source.read(1024 * 1024):
                target.write(compressor.compress(chunk))
            target.write(compressor.flush())
            target.flush()
            os.fsync(target.fileno())
        compressed_size = compressed_path.stat().st_size
        if compressed_size > max_compressed_bytes:
            raise BuildError(f"compressed output is {compressed_size} bytes, above limit {max_compressed_bytes}")
        compressed_hash = sha256_file(compressed_path)
        metadata: dict[str, object] = {
            "schemaVersion": METADATA_SCHEMA_VERSION,
            "database": {
                "applicationId": APPLICATION_ID,
                "userVersion": USER_VERSION,
                "schemaTables": list(SCHEMA_TABLES),
                "requiredIndices": list(REQUIRED_INDICES),
                "sha256": database_hash,
                "sizeBytes": database_path.stat().st_size,
            },
            "compression": {"format": "zlib", "level": 9, "sha256": compressed_hash, "sizeBytes": compressed_size},
            "inputs": {
                "fingerprintSha256": input_fingerprint,
                "sourceGeneratedAt": source_generated_at,
                "basePath": os.path.relpath(input_root, metadata_path.parent),
                "files": [{"path": item.path, "sizeBytes": item.size, "sha256": item.sha256} for item in input_files],
                "packCount": len(packs),
                "eligibleCatalogStationCount": eligible_catalog_stations,
                "unambiguousNormalizedNameCount": catalog.unambiguous_name_count,
            },
            # Only when a reviewed pack (OUT_OF_ORDER_TRIPS_LEFT_OUT) had such trips, so other indexes keep their metadata.
            "filter": {**filter_metadata, **({"outOfOrderTripsLeftOut": out_of_order} if out_of_order else {})},
            "rowCounts": totals,
            "packRowCounts": per_pack,
            "maximumCompressedSizeBytes": max_compressed_bytes,
            **(extra_metadata or {}),
        }
        with staged_metadata.open("wb") as target:
            target.write(canonical_json(metadata))
            target.flush()
            os.fsync(target.fileno())
        os.replace(compressed_path, output_path)
        fsync_path(output_path)
        os.replace(staged_metadata, metadata_path)  # publish metadata last: it is the completion marker
        fsync_path(metadata_path)
        directory_fd = os.open(output_path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
        return metadata
    except (sqlite3.Error, OSError, zlib.error) as error:
        if isinstance(error, BuildError):
            raise
        raise BuildError(str(error)) from error
    finally:
        if connection is not None:
            connection.close()
        for child in temp_dir.glob("*"):
            child.unlink(missing_ok=True)
        temp_dir.rmdir()

def foreign_full_paths(root: Path, source_id: str) -> tuple[Path, Path]:
    directory = root / foreign_rules.IOS_FULL_DIR
    return directory / f"{source_id}.sqlite.zlib", directory / f"{source_id}.metadata.json"

def default_paths(root: Path) -> tuple[Path, Path, Path, Path, Path]:
    return (
        root / "app/src/main/assets/gtfs/manifest.json",
        root / "app/src/main/assets/stations/curated_it_rail_stations.json",
        root / "ios/Resources/binario_gtfs_source_policy_v1.json",
        root / "ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib",
        root / "ios/Resources/binario_gtfs_schedule_v1.metadata.json",
    )

def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[1]
    manifest, catalog, source_policy, output, metadata = default_paths(root)
    parser.add_argument("--manifest", type=Path, default=manifest)
    parser.add_argument("--catalog", type=Path, default=catalog)
    parser.add_argument("--source-policy", type=Path, default=source_policy)
    parser.add_argument("--output", type=Path, help=f"default {output.relative_to(root)} (per-country: data/gtfs-foreign/ios/<id>.sqlite.zlib)")
    parser.add_argument("--metadata", type=Path, help=f"default {metadata.relative_to(root)} (per-country: data/gtfs-foreign/ios/<id>.metadata.json)")
    parser.add_argument("--max-compressed-bytes", type=int, default=DEFAULT_MAX_COMPRESSED_BYTES)
    parser.add_argument("--full-pack-dir", type=Path, default=root / foreign_rules.FULL_PACK_DIR,
                        help="full packs of the foreign packs bundled as their border subset")
    parser.add_argument("--foreign-full", metavar="SOURCE_ID",
                        help="build the per-country index of this full foreign pack (default output in data/gtfs-foreign/ios/)")
    args = parser.parse_args(argv)
    if args.foreign_full:
        output, metadata = foreign_full_paths(root, args.foreign_full)
    args.output = args.output or output
    args.metadata = args.metadata or metadata
    return args

def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        if args.foreign_full:
            metadata = build_foreign_full_database(
                args.manifest,
                args.catalog,
                args.foreign_full,
                args.output,
                args.metadata,
                source_policy_path=args.source_policy,
                max_compressed_bytes=args.max_compressed_bytes,
                full_pack_dir=args.full_pack_dir,
            )
        else:
            metadata = build_database(
                args.manifest,
                args.catalog,
                args.output,
                args.metadata,
                source_policy_path=args.source_policy,
                max_compressed_bytes=args.max_compressed_bytes,
                full_pack_dir=args.full_pack_dir,
            )
    except BuildError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps({"output": str(args.output), "metadata": str(args.metadata), "databaseSizeBytes": metadata["database"]["sizeBytes"], "compressedSizeBytes": metadata["compression"]["sizeBytes"], "rowCounts": metadata["rowCounts"]}, sort_keys=True))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
