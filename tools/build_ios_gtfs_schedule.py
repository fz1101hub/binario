#!/usr/bin/env python3
"""Build the compact, versioned static GTFS schedule used by the iOS app.

The builder intentionally uses only the Python standard library. It treats every
input as untrusted, validates referential integrity before publication, and only
keeps complete trips which touch an unambiguously name-matched catalog station
having a railwayCode or trenitaliaLocationId.
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

APPLICATION_ID = int.from_bytes(b"BNGT", "big")
USER_VERSION = 1
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
RAIL_ROUTE_TYPES = frozenset({2, *range(100, 200)})
EXCLUSIVE_RAIL_ROUTE_IDS: Mapping[str, frozenset[str]] = {
    # TFT publishes its two railway lines as route_type=3 (bus).
    "toscana-tft-gtfs": frozenset({"1028056267", "2272465657"}),
}
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
    "idx_stop_time_trip_sequence",
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
CREATE INDEX idx_stop_time_trip_sequence ON stop_time(trip_id, stop_sequence);
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

@dataclass(frozen=True)
class CatalogNameMatch:
    identity: CatalogIdentity
    canonical: bool

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

def load_catalog(path: Path) -> tuple[dict[str, CatalogNameMatch], int]:
    try:
        payload = json.loads(read_bounded(path, MAX_CATALOG_BYTES, "station catalog"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise BuildError(f"cannot read station catalog {path}: {error}") from error
    if payload.get("schemaVersion") != 1 or not isinstance(payload.get("stations"), list):
        raise BuildError("station catalog has unsupported schema")
    by_name: dict[str, dict[str, CatalogNameMatch]] = defaultdict(dict)
    eligible = 0
    for index, station in enumerate(payload["stations"]):
        if not isinstance(station, dict):
            raise BuildError(f"catalog station {index} is not an object")
        railway = station.get("railwayCode")
        location = station.get("trenitaliaLocationId")
        if not railway and not location:
            continue
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
        identity = CatalogIdentity(station_id, railway, location, float(latitude), float(longitude))
        eligible += 1
        for candidate_index, candidate in enumerate((name, *aliases)):
            if not isinstance(candidate, str):
                raise BuildError(f"catalog station {station_id}: non-string alias")
            normalized = normalize_station_name(candidate)
            if normalized:
                current = by_name[normalized].get(station_id)
                by_name[normalized][station_id] = CatalogNameMatch(
                    identity=identity,
                    canonical=candidate_index == 0 or (current is not None and current.canonical),
                )
    # Ambiguous normalized names are intentionally excluded (fail closed).
    return {name: next(iter(values.values())) for name, values in by_name.items() if len(values) == 1}, eligible

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

def geographically_verified_match(row: Mapping[str, str], match: CatalogNameMatch, context: str) -> CatalogIdentity | None:
    latitude = parse_optional_float(row.get("stop_lat"), f"{context}:lat", -90, 90)
    longitude = parse_optional_float(row.get("stop_lon"), f"{context}:lon", -180, 180)
    if latitude is None or longitude is None:
        return None
    maximum_distance_km = 10.0 if match.canonical else 1.0
    distance = distance_kilometres(latitude, longitude, match.identity.latitude, match.identity.longitude)
    return match.identity if distance <= maximum_distance_km else None

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
    if not isinstance(packs, list) or len(packs) != 10:
        raise BuildError("GTFS manifest must contain exactly 10 packs")
    policy_by_id: dict[str, tuple[str, str]] | None = None
    policy_bytes: bytes | None = None
    if source_policy_path is not None:
        try:
            policy_bytes = read_bounded(source_policy_path, MAX_MANIFEST_BYTES, "GTFS source policy")
            policy = json.loads(policy_bytes)
        except (json.JSONDecodeError, UnicodeError) as error:
            raise BuildError(f"cannot read GTFS source policy: {error}") from error
        sources = policy.get("sources") if isinstance(policy, dict) else None
        if not isinstance(policy, dict) or policy.get("schemaVersion") != 1 or not isinstance(sources, list) or len(sources) != 10:
            raise BuildError("GTFS source policy must contain exactly 10 schema-v1 sources")
        policy_by_id = {}
        for index, source in enumerate(sources):
            if not isinstance(source, dict) or set(source) != {"sourceId", "sourceUrl", "accessMode"}:
                raise BuildError(f"GTFS source policy entry {index} is invalid")
            source_id, source_url, access_mode = source["sourceId"], source["sourceUrl"], source["accessMode"]
            if not all(isinstance(value, str) and value for value in (source_id, source_url, access_mode)):
                raise BuildError(f"GTFS source policy entry {index} is incomplete")
            if source_id in policy_by_id or access_mode != "OPEN_DIRECT" or not source_url.startswith("https://"):
                raise BuildError(f"GTFS source policy entry {source_id!r} is not an authorised HTTPS direct source")
            policy_by_id[source_id] = (source_url, access_mode)
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
        normalized.append({
            "source_id": source_id,
            "attribution": attribution,
            "last_service_date": date_int(last_date),
            "archive": archive_path,
        })
    if policy_by_id is not None and source_ids != set(policy_by_id):
        raise BuildError("GTFS manifest source IDs differ from reviewed iOS policy")
    input_root = Path(os.path.commonpath([item.path for item in inputs]))
    if input_root.is_file():
        input_root = input_root.parent
    inputs = [InputFile(Path(item.path).relative_to(input_root).as_posix(), item.size, item.sha256) for item in inputs]
    descriptor = [{"path": item.path, "size": item.size, "sha256": item.sha256} for item in inputs]
    fingerprint = hashlib.sha256(canonical_json(descriptor)).hexdigest()
    return normalized, input_root, inputs, fingerprint, generated_at

def ingest_pack(
    connection: sqlite3.Connection,
    pack_id: int,
    pack: Mapping[str, object],
    catalog_by_name: Mapping[str, CatalogNameMatch],
    allocator: IdAllocator,
) -> dict[str, int]:
    source_id = str(pack["source_id"])
    archive_path = Path(pack["archive"])
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

        stop_identity: dict[str, CatalogIdentity] = {}
        # Direct exact-normalized matching first.
        for stop_id, row in stops.items():
            match = catalog_by_name.get(normalize_station_name(row["stop_name"]))
            identity = geographically_verified_match(row, match, f"{source_id}:stop:{stop_id}") if match is not None else None
            if identity is not None:
                stop_identity[stop_id] = identity
        # A platform/boarding area inherits only an unambiguous verified parent identity.
        # Explicit child coordinates must remain geographically coherent with that identity.
        for stop_id, row in stops.items():
            parent = row.get("parent_station", "")
            child_type = row.get("location_type", "") or "0"
            parent_type = stops.get(parent, {}).get("location_type", "")
            if stop_id not in stop_identity and parent in stop_identity and child_type in {"0", "4"} and parent_type == "1":
                inherited = geographically_verified_parent_identity(
                    row,
                    stop_identity[parent],
                    f"{source_id}:stop:{stop_id}",
                )
                if inherited is not None:
                    stop_identity[stop_id] = inherited

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
            exceptional_rail_route = route_type == 3 and external_id in EXCLUSIVE_RAIL_ROUTE_IDS.get(source_id, frozenset())
            if route_type not in RAIL_ROUTE_TYPES and not exceptional_rail_route:
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
                "INSERT INTO trip(id,pack_id,route_id,service_id,external_id,headsign,short_name,direction_id) VALUES(?,?,?,?,?,?,?,?)",
                (database_id, pack_id, route_ids[row["route_id"]], service_ids[row["service_id"]], external_id, optional_text(row.get("trip_headsign")), optional_text(row.get("trip_short_name")), direction),
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

def build_database(
    manifest_path: Path,
    catalog_path: Path,
    output_path: Path,
    metadata_path: Path,
    *,
    source_policy_path: Path | None = None,
    max_compressed_bytes: int = DEFAULT_MAX_COMPRESSED_BYTES,
) -> dict[str, object]:
    gtfs_root = manifest_path.parent
    packs, input_root, input_files, input_fingerprint, source_generated_at = read_manifest(
        manifest_path,
        gtfs_root,
        catalog_path,
        source_policy_path,
    )
    catalog_by_name, eligible_catalog_stations = load_catalog(catalog_path)
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
        connection.execute("BEGIN")
        for pack_id, pack in enumerate(packs, start=1):
            counts = ingest_pack(connection, pack_id, pack, catalog_by_name, allocator)
            per_pack[str(pack["source_id"])] = counts
        totals = {table: int(connection.execute(f"SELECT count(*) FROM {table}").fetchone()[0]) for table in SCHEMA_TABLES if table != "metadata"}
        if totals["trip"] == 0 or totals["stop_time"] == 0:
            raise BuildError("filter produced an empty schedule")
        metadata_rows = {
            "input_fingerprint_sha256": input_fingerprint,
            "schema_version": str(USER_VERSION),
            "source_generated_at": source_generated_at,
            "station_match_policy": "eligible-identity+unique-normalized-name-or-parent",
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
                "unambiguousNormalizedNameCount": len(catalog_by_name),
            },
            "filter": {
                "keepsCompleteTripWhen": "at least one stop matches an unambiguous normalized name/alias of a catalog station with railwayCode or trenitaliaLocationId",
                "parentIdentityInheritance": True,
                "parentIdentityMaximumChildDistanceKm": MAX_PARENT_INHERITANCE_DISTANCE_KM,
            },
            "rowCounts": totals,
            "packRowCounts": per_pack,
            "maximumCompressedSizeBytes": max_compressed_bytes,
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
    parser.add_argument("--output", type=Path, default=output)
    parser.add_argument("--metadata", type=Path, default=metadata)
    parser.add_argument("--max-compressed-bytes", type=int, default=DEFAULT_MAX_COMPRESSED_BYTES)
    return parser.parse_args(argv)

def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        metadata = build_database(
            args.manifest,
            args.catalog,
            args.output,
            args.metadata,
            source_policy_path=args.source_policy,
            max_compressed_bytes=args.max_compressed_bytes,
        )
    except BuildError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps({"output": str(args.output), "metadata": str(args.metadata), "databaseSizeBytes": metadata["database"]["sizeBytes"], "compressedSizeBytes": metadata["compression"]["sizeBytes"], "rowCounts": metadata["rowCounts"]}, sort_keys=True))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
