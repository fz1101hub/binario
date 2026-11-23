#!/usr/bin/env python3
"""Fail-closed verification for the generated iOS static GTFS schedule."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sqlite3
import sys
import tempfile
import zlib
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Mapping, Sequence
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import foreign_pack_rules as foreign_rules  # noqa: E402  (border zone rule shared with the builders)
import generate_shared_code as shared_code  # noqa: E402  (the shared tables, checked against their schema)

APPLICATION_ID = int.from_bytes(b"BNGT", "big")
# Packs in the index; the same value as build_ios_gtfs_schedule.INDEXED_PACK_COUNT.
INDEXED_PACK_COUNT = 22
# Packs of gtfs-personal/ the personal index adds (build_ios_gtfs_schedule.PERSONAL_INDEXED_PACK_COUNT).
PERSONAL_INDEXED_PACK_COUNT = 4
# Route types of the index (build_ios_gtfs_schedule.INDEXED_ROUTE_TYPES): rail, and the packs' bus routes.
INDEXED_ROUTE_TYPES_SQL = "route_type = 2 OR route_type BETWEEN 100 AND 199 OR route_type = 3 OR route_type BETWEEN 700 AND 799"
# Route types of a city network (build_ios_gtfs_schedule.URBAN_ROUTE_TYPES): metro, tram, funicular.
URBAN_ROUTE_TYPES_SQL = ("route_type IN (0, 1, 5, 6, 7, 12) OR route_type BETWEEN 400 AND 499 OR route_type BETWEEN 900 AND 999 "
                         "OR route_type BETWEEN 1300 AND 1499")
# The city networks of the shared pack roles (build_ios_gtfs_schedule.URBAN_PACKS).
URBAN_PACKS = tuple(shared_code.table_values("pack-roles-v1.json")["urbanPacks"])
URBAN_TABLE_COLUMNS = {
    "urban_stop": ("stop_id", "modes"),
    "urban_walk": ("catalog_station_id", "stop_id", "minutes"),
}
# The reader's bound on the city stops a walk joins to one catalogue station (SQLiteGTFSJourneyRepository.swift).
MAX_WALKED_STOPS = 128
# Packs whose trips must touch a catalogue station with codes: the foreign packs of the shared table
# (contracts/shared-data/v1/pack-roles-v1.json), as build_ios_gtfs_schedule.FOREIGN_PACKS.
FOREIGN_PACKS = tuple(shared_code.table_values("pack-roles-v1.json")["foreignPacks"])
# Per-country index (build_ios_gtfs_schedule.py --foreign-full): kind and builder version of its metadata.
FOREIGN_FULL_KIND = "foreign-full"
FOREIGN_FULL_BUILDER_VERSION = 1
USER_VERSION = 2
METADATA_SCHEMA_VERSION = 1
MAX_METADATA_BYTES = 4 * 1024 * 1024
DEFAULT_MAX_COMPRESSED_BYTES = 32 * 1024 * 1024
DEFAULT_MAX_DATABASE_BYTES = 256 * 1024 * 1024
REQUIRED_TABLE_COLUMNS = {
    "metadata": ("key", "value"),
    "pack": ("id", "source_id", "attribution", "last_service_date"),
    "agency": ("id", "pack_id", "external_id", "name", "timezone"),
    "route": ("id", "pack_id", "agency_id", "external_id", "short_name", "long_name", "route_type"),
    "service": ("id", "pack_id", "external_id", "start_date", "day_count", "active_days"),
    "trip": ("id", "pack_id", "route_id", "service_id", "external_id", "headsign", "short_name", "direction_id", "bikes_allowed"),
    "stop": ("id", "pack_id", "external_id", "name", "latitude", "longitude", "catalog_station_id", "railway_code", "trenitalia_location_id"),
    "stop_time": ("trip_id", "stop_sequence", "stop_id", "arrival_seconds", "departure_seconds", "pickup_type", "drop_off_type"),
}
REQUIRED_INDICES = {
    "idx_route_pack_external",
    "idx_service_pack_external",
    "idx_trip_pack_external",
    "idx_trip_route_service",
    "idx_stop_pack_external",
    "idx_stop_catalog_station",
    "idx_stop_time_stop_departure",
}
# The reader's limits (SQLiteGTFSJourneyRepository.swift): rows a board or planner query reads for one station and
# agency time zone, stops bound to one catalogue station, agency time zones.  An index above them fails on the iPhone
# with limitExceeded; the verifier refuses it first.
MAX_CANDIDATE_ROWS = 4_096
MAX_RESOLVED_STOPS = 128
MAX_TIME_ZONES = 16
# The reader computes every service day in the first agency time zone: all of them must keep Rome's UTC offset.
REFERENCE_TIME_ZONE = "Europe/Rome"

class VerificationError(RuntimeError):
    pass

def canonical_json(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")

def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()

def verify_rome_offsets(zones: Sequence[ZoneInfo], first: int | None, last: int | None, last_days: int | None) -> None:
    """Every agency zone has Rome's UTC offset at every hour of the index's service days (one day each side)."""
    if first is None or last is None:
        return
    rome = ZoneInfo(REFERENCE_TIME_ZONE)
    start = datetime(first // 10_000, first // 100 % 100, first % 100, tzinfo=timezone.utc) - timedelta(days=1)
    end = datetime(last // 10_000, last // 100 % 100, last % 100, tzinfo=timezone.utc) + timedelta(days=(last_days or 0) + 1)
    require(end - start <= timedelta(days=4_000), "service range too long to check time zones")
    moment = start
    while moment <= end:
        reference = moment.astimezone(rome).utcoffset()
        for zone in zones:
            require(moment.astimezone(zone).utcoffset() == reference,
                    f"agency time zone {zone.key} differs from {REFERENCE_TIME_ZONE} at {moment.isoformat()}")
        moment += timedelta(hours=1)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)

def load_metadata(path: Path) -> dict[str, object]:
    require(path.is_file(), f"metadata file is missing: {path}")
    require(path.stat().st_size <= MAX_METADATA_BYTES, "metadata file exceeds size bound")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise VerificationError(f"cannot parse metadata: {error}") from error
    require(isinstance(value, dict), "metadata root must be an object")
    return value

def border_subset_packs(metadata: dict[str, object]) -> list[str]:
    """Foreign packs indexed from their border subset (their full pack is an input too)."""
    border = (metadata.get("filter") or {}).get("borderSubsets", []) if isinstance(metadata.get("filter"), dict) else None
    require(isinstance(border, list) and all(item in FOREIGN_PACKS for item in border) and border == sorted(set(border)),
            "metadata filter.borderSubsets is invalid")
    return border

def transit_urban_packs(metadata: dict[str, object]) -> list[str]:
    """The city networks of the gtfs-transit/ manifest in the index (the shared urbanPacks)."""
    urban = (metadata.get("filter") or {}).get("transitUrbanPacks", []) if isinstance(metadata.get("filter"), dict) else None
    require(isinstance(urban, list) and all(item in URBAN_PACKS for item in urban) and urban == sorted(set(urban)),
            "metadata filter.transitUrbanPacks is invalid")
    return urban

def personal_packs(metadata: dict[str, object]) -> list[str]:
    """The packs of gtfs-personal/ in the personal index (none in the public one); its manifest is then an input."""
    personal = (metadata.get("filter") or {}).get("personalPacks", []) if isinstance(metadata.get("filter"), dict) else None
    require(isinstance(personal, list) and all(isinstance(item, str) and item for item in personal)
            and personal == sorted(set(personal)) and len(personal) in (0, PERSONAL_INDEXED_PACK_COUNT),
            "metadata filter.personalPacks is invalid")
    return personal

def city_packs(metadata: dict[str, object]) -> list[str]:
    """Every city network of the index: those of gtfs-transit/ and, in the personal index, of gtfs-personal/."""
    return sorted({*transit_urban_packs(metadata), *(pack for pack in personal_packs(metadata) if pack in URBAN_PACKS)})

def transit_rail_packs(metadata: dict[str, object]) -> list[str]:
    """The railway packs of the gtfs-transit/ manifest in the index; its manifest is then an input too."""
    rail = (metadata.get("filter") or {}).get("transitRailPacks", []) if isinstance(metadata.get("filter"), dict) else None
    require(isinstance(rail, list) and all(isinstance(item, str) and item for item in rail) and rail == sorted(set(rail)),
            "metadata filter.transitRailPacks is invalid")
    return rail

def verify_input_fingerprint(
    metadata: dict[str, object],
    metadata_path: Path,
    source_policy_path: Path | None = None,
    foreign_full: str | None = None,
) -> str:
    return verify_inputs(metadata, metadata_path, source_policy_path, foreign_full)[0]

def verify_inputs(
    metadata: dict[str, object],
    metadata_path: Path,
    source_policy_path: Path | None = None,
    foreign_full: str | None = None,
) -> tuple[str, dict[str, Path]]:
    """The input fingerprint, recomputed from the files, and the verified input paths by their recorded path."""
    inputs = metadata.get("inputs")
    require(isinstance(inputs, dict), "metadata.inputs is missing")
    files = inputs.get("files")
    expected_fingerprint = inputs.get("fingerprintSha256")
    base_path = inputs.get("basePath")
    if foreign_full is not None:
        require(isinstance(files, list) and len(files) == 2, "per-country metadata must describe its full pack and the catalog")
    else:
        border = border_subset_packs(metadata)
        transit = 1 if transit_rail_packs(metadata) or transit_urban_packs(metadata) else 0
        personal = len(personal_packs(metadata))
        transit += 1 if personal else 0
        expected_count = INDEXED_PACK_COUNT + personal + (3 if source_policy_path is not None else 2) + transit + len(border)
        require(
            isinstance(files, list) and len(files) == expected_count,
            f"metadata must describe manifest, catalog, reviewed source policy, {'transit manifest, ' * transit}"
            f"{INDEXED_PACK_COUNT} archives and {len(border)} full packs of border subsets"
            if source_policy_path is not None
            else f"metadata must describe manifest, catalog, {'transit manifest, ' * transit}and {INDEXED_PACK_COUNT} archives",
        )
    require(isinstance(expected_fingerprint, str) and len(expected_fingerprint) == 64, "invalid input fingerprint")
    require(isinstance(base_path, str) and base_path, "metadata input basePath is missing")
    source_generated_at = inputs.get("sourceGeneratedAt")
    require(isinstance(source_generated_at, str), "metadata sourceGeneratedAt is missing")
    try:
        date.fromisoformat(source_generated_at)
    except ValueError as error:
        raise VerificationError("metadata sourceGeneratedAt is invalid") from error
    input_root = (metadata_path.parent / base_path).resolve()
    descriptors: list[dict[str, object]] = []
    resolved_inputs: set[Path] = set()
    paths: dict[str, Path] = {}
    for index, item in enumerate(files):
        require(isinstance(item, dict), f"input descriptor {index} is invalid")
        raw_path, expected_size, expected_hash = item.get("path"), item.get("sizeBytes"), item.get("sha256")
        require(isinstance(raw_path, str) and raw_path, f"input descriptor {index} has no path")
        require(isinstance(expected_size, int) and expected_size >= 0, f"input descriptor {index} has invalid size")
        require(isinstance(expected_hash, str) and len(expected_hash) == 64, f"input descriptor {index} has invalid SHA-256")
        relative = Path(raw_path)
        require(not relative.is_absolute() and ".." not in relative.parts, f"unsafe input path: {relative}")
        path = input_root / relative
        resolved_inputs.add(path.resolve())
        paths[raw_path] = path
        require(path.is_file(), f"input file is missing: {path}")
        require(path.stat().st_size == expected_size, f"input size changed: {path}")
        require(sha256_file(path) == expected_hash, f"input hash changed: {path}")
        descriptors.append({"path": raw_path, "size": expected_size, "sha256": expected_hash})
    if foreign_full is not None:
        require(metadata.get("builderVersion") == FOREIGN_FULL_BUILDER_VERSION, "per-country builder version mismatch")
        actual_fingerprint = hashlib.sha256(canonical_json({
            "builderVersion": FOREIGN_FULL_BUILDER_VERSION, "kind": FOREIGN_FULL_KIND, "sourceId": foreign_full, "files": descriptors,
        })).hexdigest()
    else:
        actual_fingerprint = hashlib.sha256(canonical_json(descriptors)).hexdigest()
    require(actual_fingerprint == expected_fingerprint, "input fingerprint mismatch")
    expected_packs = 1 if foreign_full is not None else INDEXED_PACK_COUNT + len(personal_packs(metadata))
    require(inputs.get("packCount") == expected_packs, f"metadata pack count must be {expected_packs}")
    if source_policy_path is not None and foreign_full is None:
        require(source_policy_path.resolve() in resolved_inputs, "reviewed GTFS source policy is absent from the input fingerprint")
    return actual_fingerprint, paths

def border_zone_rules(metadata: dict[str, object], inputs: Mapping[str, Path]) -> dict[str, set[str]]:
    """The zone stations of each border subset, derived again from its full pack and the catalogue (both
    hash-verified inputs) with the builder's matcher and the corridors of foreign_pack_rules."""
    border = border_subset_packs(metadata)
    if not border:
        return {}
    # The builder lists the manifest first and the catalogue second (build_ios_gtfs_schedule.read_manifest).
    ordered = list(inputs.values())
    require(len(ordered) >= 2, "border subsets need the manifest and catalogue inputs")
    manifest_path, catalogue_path = ordered[0], ordered[1]
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    full_archives: dict[str, Path] = {}
    for entry in manifest.get("packs", []):
        if isinstance(entry, dict) and "fullPack" in entry:
            source_id = str(entry.get("sourceId"))
            rule = ((entry.get("filter") or {}).get("borderZone") or {}).get("rule")
            require(rule == foreign_rules.BORDER_ZONE_RULE, f"{source_id}: invalid borderZone rule {rule!r}")
            full = entry["fullPack"] if isinstance(entry["fullPack"], dict) else {}
            name = str(full.get("path", "")).rsplit("/", 1)[-1]
            found = [path for path in ordered[2:] if path.name == name and sha256_file(path) == full.get("sha256")]
            require(bool(found), f"{source_id}: the full pack of the border subset is not an input")
            full_archives[source_id] = found[0]  # several inputs with these bytes are the same pack
    require(sorted(full_archives) == border, f"border subsets {border} differ from the manifest fullPack entries {sorted(full_archives)}")
    import android_stop_matcher
    import build_ios_gtfs_schedule as builder  # the one catalogue matcher (design §1.1, rule 5)

    try:
        matcher, _ = builder.load_catalog(catalogue_path)
        android = android_stop_matcher.load(catalogue_path)
        return {source_id: builder.foreign_station_coverage(path, matcher, source_id, android)[1]
                for source_id, path in full_archives.items()}
    except (builder.BuildError, OSError, ValueError) as error:
        raise VerificationError(f"cannot derive the border zone stations: {error}") from error

def verify_urban_tables(connection: sqlite3.Connection, urban_packs: Sequence[str]) -> None:
    """The city networks' stops: never a catalogue station, each with its modes (urban_stop) and only theirs; the
    walks name stops of theirs, in whole minutes, at most MAX_WALKED_STOPS per station."""
    placeholders = ",".join("?" for _ in urban_packs)
    bound = connection.execute(
        f"SELECT count(*) FROM stop s JOIN pack p ON p.id=s.pack_id WHERE p.source_id IN ({placeholders}) "
        "AND (s.catalog_station_id IS NOT NULL OR s.railway_code IS NOT NULL OR s.trenitalia_location_id IS NOT NULL)",
        list(urban_packs),
    ).fetchone()[0]
    require(bound == 0, "a stop of a city network carries a catalogue identity")
    listed = {row[0] for row in connection.execute("SELECT stop_id FROM urban_stop")}
    urban_stops = {row[0] for row in connection.execute(
        f"SELECT s.id FROM stop s JOIN pack p ON p.id=s.pack_id WHERE p.source_id IN ({placeholders})", list(urban_packs))}
    require(listed == urban_stops, "urban_stop does not list exactly the stops of the city networks")
    invalid_modes = connection.execute("SELECT count(*) FROM urban_stop WHERE modes GLOB '*[^MTF]*'").fetchone()[0]
    require(invalid_modes == 0, "urban_stop modes outside M, T, F")
    walked = connection.execute(
        "SELECT count(*) FROM urban_walk w LEFT JOIN urban_stop u ON u.stop_id=w.stop_id WHERE u.stop_id IS NULL "
        "OR w.minutes < 0 OR w.minutes > 60"
    ).fetchone()[0]
    require(walked == 0, "urban_walk names a stop outside the city networks or a walk out of bounds")
    most = connection.execute(
        "SELECT max(n) FROM (SELECT count(*) AS n FROM urban_walk GROUP BY catalog_station_id)").fetchone()[0] or 0
    require(most <= MAX_WALKED_STOPS, f"a station is a walk from {most} city stops, above {MAX_WALKED_STOPS}")

def urban_rules(metadata: dict[str, object], inputs: Mapping[str, Path]) -> dict[str, object]:
    """What the city networks' rows must be, derived again from their archives and the catalogue (hash-verified
    inputs) with the builder's rules: the name each stop shows and the walks, by pack and stop id."""
    urban = city_packs(metadata)
    if not urban:
        return {}
    ordered = list(inputs.values())
    catalogue_path = ordered[1]
    manifests = [path for path in ordered if path.name == "manifest.json" and path.parent.name in ("gtfs-transit", "gtfs-personal")]
    require(bool(manifests), "the city networks need the transit manifest among the inputs")
    import build_ios_gtfs_schedule as builder  # the one rule for names and walks (urban-stops-v1.json)

    assets: dict[str, tuple[str, object]] = {}
    for manifest_path in manifests:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        assets.update({str(entry.get("sourceId")): (str(entry.get("asset", "")).rsplit("/", 1)[-1], entry.get("sha256"))
                       for entry in manifest.get("packs", []) if isinstance(entry, dict)})
    try:
        points = builder.catalogue_points(catalogue_path)
        names: dict[tuple[str, str], str] = {}
        walks: dict[tuple[str, str, str], int] = {}
        for source_id in urban:
            name, digest = assets.get(source_id, ("", None))
            found = [path for path in ordered if path.name == name and sha256_file(path) == digest]
            require(bool(found), f"{source_id}: the archive of the city network is not an input")
            with builder.BoundedGtfsArchive(found[0]) as archive:
                stops = builder.unique_rows(archive.rows("stops.txt", ("stop_id", "stop_name")), "stop_id", f"{source_id}:stops.txt")
            for stop_id, row in stops.items():
                names[(source_id, stop_id)] = builder.stop_name_display(row["stop_name"])
            for (station_id, stop_id), minutes in builder.urban_walks(source_id, stops, points).items():
                walks[(source_id, stop_id, station_id)] = minutes
        return {"names": names, "walks": walks}
    except (builder.BuildError, OSError, ValueError) as error:
        raise VerificationError(f"cannot derive the city networks' names and walks: {error}") from error

def verify_urban_rows(connection: sqlite3.Connection, rules: Mapping[str, object]) -> None:
    """The names and the walks of the city networks are those the rules derive from the inputs."""
    if not rules:
        return
    names, walks = rules["names"], rules["walks"]
    used: set[tuple[str, str]] = set()
    for source_id, external_id, name in connection.execute(
        "SELECT p.source_id, s.external_id, s.name FROM urban_stop u JOIN stop s ON s.id=u.stop_id JOIN pack p ON p.id=s.pack_id"
    ):
        used.add((source_id, external_id))
        require(names.get((source_id, external_id)) == name, f"{source_id}: stop {external_id} is not named as Android shows it")
    stored = {
        (source_id, external_id, station_id): minutes
        for source_id, external_id, station_id, minutes in connection.execute(
            "SELECT p.source_id, s.external_id, w.catalog_station_id, w.minutes FROM urban_walk w "
            "JOIN stop s ON s.id=w.stop_id JOIN pack p ON p.id=s.pack_id"
        )
    }
    expected = {key: minutes for key, minutes in walks.items() if (key[0], key[1]) in used}
    require(stored == expected, f"urban_walk differs from the walks of the inputs ({len(stored)} rows, {len(expected)} expected)")

def verify_city_row_bounds(connection: sqlite3.Connection, urban_packs: Sequence[str]) -> None:
    """The rows the iPhone reads for one service day stay within MAX_CANDIDATE_ROWS wherever a city stop is involved
    (SQLiteGTFSJourneyRepository.swift): a city stop's board (its name's stops, the services running that day), and
    a search with a city stop at an end (one row per boarding to the first destination stop, the city services
    running that day, the trains of every service, the stops a walk from both ends left out). Same-name stops of a
    pack stand in for a picked stop's platforms (a superset)."""
    if not urban_packs:
        return
    from collections import defaultdict
    urban_pack_ids = {row[0] for row in connection.execute(
        f"SELECT id FROM pack WHERE source_id IN ({','.join('?' for _ in urban_packs)})", list(urban_packs))}
    keys: dict[int, set[str]] = defaultdict(set)
    for stop_id, station_id in connection.execute("SELECT id, catalog_station_id FROM stop WHERE catalog_station_id IS NOT NULL"):
        keys[stop_id].add(station_id)
    city_keys: set[str] = set()
    for station_id, stop_id in connection.execute("SELECT catalog_station_id, stop_id FROM urban_walk"):
        keys[stop_id].add(station_id)
        city_keys.add(station_id)
    for stop_id, pack_id, name in connection.execute(
        "SELECT s.id, s.pack_id, s.name FROM urban_stop u JOIN stop s ON s.id=u.stop_id"
    ):
        place = f"city:{pack_id}:{name.casefold()}"
        keys[stop_id].add(place)
        city_keys.add(place)
    services: dict[int, tuple[int, int, bytes, bool]] = {}
    for service_id, pack_id, start, count, bits in connection.execute(
        "SELECT id, pack_id, start_date, day_count, active_days FROM service"
    ):
        services[service_id] = (start, count, bits, pack_id in urban_pack_ids)
    trip_service = dict(connection.execute("SELECT id, service_id FROM trip"))
    # Rows by (origin key, destination key, service): a train's service is counted whatever the day.
    rows: dict[tuple[str, str], dict[int, int]] = defaultdict(lambda: defaultdict(int))
    boards: dict[str, dict[int, int]] = defaultdict(lambda: defaultdict(int))
    calls: list[tuple[int, int, set[str], int]] = []
    current: int | None = None

    def flush(trip_id: int) -> None:
        service = trip_service[trip_id]
        for position, (pickup, _, origin_keys, _) in enumerate(calls):
            if pickup != 0 or not origin_keys:
                continue
            for key in origin_keys & city_keys:
                if key.startswith("city:"):
                    boards[key][service] += 1
            reached: set[str] = set()
            for _, drop_off, destination_keys, _ in calls[position + 1:]:
                if drop_off != 0:
                    continue
                for destination in destination_keys - reached:
                    reached.add(destination)
                    if destination in origin_keys:
                        continue
                    for origin in origin_keys:
                        if (origin in city_keys or destination in city_keys) and origin != destination \
                                and origin not in destination_keys:
                            rows[(origin, destination)][service] += 1

    for trip_id, stop_id, pickup, drop_off in connection.execute(
        "SELECT trip_id, stop_id, pickup_type, drop_off_type FROM stop_time ORDER BY trip_id, stop_sequence"
    ):
        if trip_id != current:
            if current is not None:
                flush(current)
            current, calls = trip_id, []
        calls.append((pickup, drop_off, keys.get(stop_id, set()), stop_id))
    if current is not None:
        flush(current)

    def worst_day(by_service: Mapping[int, int]) -> int:
        trains = sum(count for service, count in by_service.items() if not services[service][3])
        city = {service: count for service, count in by_service.items() if services[service][3]}
        if trains + sum(city.values()) <= MAX_CANDIDATE_ROWS:
            return trains + sum(city.values())
        days: dict[int, int] = defaultdict(int)
        for service, count in city.items():
            start, day_count, bits, _ = services[service]
            first = date(start // 10_000, start // 100 % 100, start % 100).toordinal()
            for offset in range(day_count):
                if bits[offset // 8] & (1 << (offset % 8)):
                    days[first + offset] += count
        return trains + max(days.values(), default=0)

    for key, by_service in boards.items():
        busiest = worst_day(by_service)
        require(busiest <= MAX_CANDIDATE_ROWS, f"the board of {key} reads {busiest} rows a day, above {MAX_CANDIDATE_ROWS}")
    for (origin, destination), by_service in rows.items():
        busiest = worst_day(by_service)
        require(busiest <= MAX_CANDIDATE_ROWS,
                f"a search from {origin} to {destination} reads {busiest} rows a day, above {MAX_CANDIDATE_ROWS}")

def verify_source_policy(source_policy_path: Path, pack_source_ids: set[str]) -> None:
    """The reviewed policy must describe exactly the packs of the index, each with a licence entry."""
    try:
        policy = json.loads(source_policy_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise VerificationError(f"cannot parse GTFS source policy: {error}") from error
    sources = policy.get("sources") if isinstance(policy, dict) else None
    require(isinstance(policy, dict) and policy.get("schemaVersion") == 1 and isinstance(sources, list), "invalid GTFS source policy")
    policy_ids: set[str] = set()
    for index, source in enumerate(sources):
        require(isinstance(source, dict) and set(source) == {"sourceId", "sourceUrl", "accessMode", "licence", "landingPage"}, f"GTFS source policy entry {index} is invalid")
        source_id, source_url, access_mode, licence = source["sourceId"], source["sourceUrl"], source["accessMode"], source["licence"]
        require(isinstance(source_id, str) and source_id and source_id not in policy_ids, f"GTFS source policy entry {index} has an invalid sourceId")
        # build_ios_gtfs_schedule.reviewed_source: an HTTPS direct download, or a GATEWAY_ONLY origin imported offline
        # in CI and corroborated by an HTTPS copy (checked by the builder on the manifest entry).
        require(isinstance(source_url, str) and isinstance(access_mode, str)
                and (access_mode == "OPEN_DIRECT" and source_url.startswith("https://")
                     or access_mode == "GATEWAY_ONLY" and source_url.startswith(("https://", "http://")))
                and len(source_url) > len("https://"),
                f"GTFS source policy entry {source_id!r} is not an HTTPS direct or a gateway source")
        require(licence is None or (isinstance(licence, str) and licence.strip()), f"GTFS source policy entry {source_id!r} has an invalid licence")
        landing_page = source["landingPage"]
        require(isinstance(landing_page, str) and landing_page.startswith("https://") and len(landing_page) > len("https://"),
                f"GTFS source policy entry {source_id!r} has an invalid landing page")
        policy_ids.add(source_id)
    require(policy_ids == pack_source_ids, f"GTFS source policy ids {sorted(policy_ids)} differ from the index packs {sorted(pack_source_ids)}")

def verify_source_policy_lists(source_policy_path: Path, source_id: str) -> None:
    """A per-country index is of a source the reviewed policy lists."""
    try:
        policy = json.loads(source_policy_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise VerificationError(f"cannot parse GTFS source policy: {error}") from error
    sources = policy.get("sources") if isinstance(policy, dict) else None
    require(isinstance(sources, list) and any(isinstance(item, dict) and item.get("sourceId") == source_id for item in sources),
            f"{source_id} is not a reviewed source of the GTFS source policy")

def pack_source_ids(path: Path) -> set[str]:
    try:
        connection = sqlite3.connect(f"file:{path}?mode=ro&immutable=1", uri=True)
        try:
            return {str(row[0]) for row in connection.execute("SELECT source_id FROM pack")}
        finally:
            connection.close()
    except sqlite3.Error as error:
        raise VerificationError(f"SQLite verification failed: {error}") from error

def decompress_bounded(source_path: Path, destination: Path, maximum: int) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    decompressor = zlib.decompressobj(wbits=zlib.MAX_WBITS)
    try:
        with source_path.open("rb") as source, destination.open("wb") as target:
            while chunk := source.read(1024 * 1024):
                data = decompressor.decompress(chunk, max(0, maximum - size + 1))
                size += len(data)
                require(size <= maximum, f"decompressed database exceeds {maximum} bytes")
                target.write(data)
                digest.update(data)
                require(not decompressor.unconsumed_tail, "decompression output bound reached")
            data = decompressor.flush(max(0, maximum - size + 1))
            size += len(data)
            require(size <= maximum, f"decompressed database exceeds {maximum} bytes")
            target.write(data)
            digest.update(data)
            target.flush()
            os.fsync(target.fileno())
    except (OSError, zlib.error) as error:
        raise VerificationError(f"cannot decompress database: {error}") from error
    require(decompressor.eof, "truncated zlib stream")
    require(not decompressor.unused_data, "trailing data after zlib stream")
    return digest.hexdigest(), size

def verify_database(
    path: Path,
    metadata: dict[str, object],
    input_fingerprint: str,
    *,
    foreign_full: str | None = None,
    border_zones: Mapping[str, set[str]] | None = None,
    urban: Mapping[str, object] | None = None,
) -> dict[str, int]:
    border_zones = border_zones or {}
    database = metadata.get("database")
    expected_counts = metadata.get("rowCounts")
    require(isinstance(database, dict), "metadata.database is missing")
    require(isinstance(expected_counts, dict), "metadata.rowCounts is missing")
    uri = f"file:{path}?mode=ro&immutable=1"
    try:
        connection = sqlite3.connect(uri, uri=True)
        application_id = connection.execute("PRAGMA application_id").fetchone()[0]
        user_version = connection.execute("PRAGMA user_version").fetchone()[0]
        require(application_id == APPLICATION_ID == database.get("applicationId"), "application_id mismatch")
        require(user_version == USER_VERSION == database.get("userVersion"), "user_version mismatch")
        integrity = connection.execute("PRAGMA integrity_check").fetchall()
        require(integrity == [("ok",)], f"integrity_check failed: {integrity[:3]}")
        require(connection.execute("PRAGMA foreign_key_check").fetchall() == [], "foreign_key_check failed")
        actual_tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        # An index with city networks adds their two tables (build_ios_gtfs_schedule.URBAN_SCHEMA_SQL); the reader
        # accepts exactly the base tables or both sets.
        urban_packs = [] if foreign_full is not None else city_packs(metadata)
        tables = {**REQUIRED_TABLE_COLUMNS, **(URBAN_TABLE_COLUMNS if urban_packs else {})}
        require(actual_tables == set(tables), f"unexpected schema tables: {sorted(actual_tables)}")
        for table, expected_columns in tables.items():
            columns = tuple(row[1] for row in connection.execute(f"PRAGMA table_info({table})"))
            require(columns == expected_columns, f"column schema mismatch for {table}: {columns}")
        actual_indices = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_autoindex_%'")}
        require(actual_indices == REQUIRED_INDICES, f"index mismatch: {sorted(actual_indices)}")
        counts = {table: int(connection.execute(f"SELECT count(*) FROM {table}").fetchone()[0]) for table in tables if table != "metadata"}
        require(counts == expected_counts, f"row count mismatch: expected {expected_counts}, got {counts}")
        if foreign_full is not None:
            packs = [str(row[0]) for row in connection.execute("SELECT source_id FROM pack")]
            require(packs == [foreign_full], f"per-country database must contain only {foreign_full}, not {packs}")
        else:
            expected = INDEXED_PACK_COUNT + len(personal_packs(metadata))
            require(counts["pack"] == expected, f"database must contain exactly {expected} packs")
        require(counts["trip"] > 0 and counts["stop_time"] > 0, "schedule is empty")
        embedded = dict(connection.execute("SELECT key,value FROM metadata"))
        require(embedded.get("input_fingerprint_sha256") == input_fingerprint, "embedded input fingerprint mismatch")
        require(embedded.get("schema_version") == str(USER_VERSION), "embedded schema version mismatch")
        require(embedded.get("source_generated_at") == metadata["inputs"]["sourceGeneratedAt"], "embedded source generation date mismatch")
        require(json.loads(embedded.get("row_counts_json", "null")) == counts, "embedded row counts mismatch")
        incomplete = connection.execute("SELECT count(*) FROM trip t WHERE NOT EXISTS (SELECT 1 FROM stop_time st WHERE st.trip_id=t.id)").fetchone()[0]
        require(incomplete == 0, "trip without stop_times found")
        # A trip touches a catalogue station; in a foreign operator's pack bundled whole, one with codes (FOREIGN_PACKS
        # but borderSubsets); a border subset or a per-country index may touch one without codes.
        coded_only = [] if foreign_full is not None else sorted(set(FOREIGN_PACKS) - set(border_zones))
        unverified = connection.execute(
            "SELECT count(*) FROM trip t JOIN pack p ON p.id=t.pack_id WHERE "
            f"p.source_id NOT IN ({','.join('?' for _ in urban_packs)}) AND NOT EXISTS ("
            "SELECT 1 FROM stop_time st JOIN stop s ON s.id=st.stop_id WHERE st.trip_id=t.id AND s.catalog_station_id IS NOT NULL "
            f"AND (p.source_id NOT IN ({','.join('?' for _ in coded_only) or ''}) OR s.railway_code IS NOT NULL OR s.trenitalia_location_id IS NOT NULL))",
            [*urban_packs, *coded_only],
        ).fetchone()[0]
        require(unverified == 0, "trip not touching a verified catalog station found")
        if urban_packs:
            verify_urban_tables(connection, urban_packs)
        # A border subset names only zone stations: no stop of another station carries a catalogue id (design §4.2).
        for source_id, zone_ids in sorted(border_zones.items()):
            for external_id, station_id in connection.execute(
                "SELECT s.external_id, s.catalog_station_id FROM stop s JOIN pack p ON p.id=s.pack_id "
                "WHERE p.source_id=? AND s.catalog_station_id IS NOT NULL", (source_id,),
            ):
                require(station_id in zone_ids,
                        f"{source_id}: stop {external_id} carries catalogue id {station_id} outside the border zone")
        urban_placeholders = ",".join("?" for _ in urban_packs)
        non_rail = connection.execute(
            f"SELECT count(*) FROM route r JOIN pack p ON p.id=r.pack_id WHERE p.source_id NOT IN ({urban_placeholders}) "
            f"AND NOT ({INDEXED_ROUTE_TYPES_SQL})", urban_packs,
        ).fetchone()[0]
        require(non_rail == 0, "route neither rail nor bus found")
        non_urban = connection.execute(
            f"SELECT count(*) FROM route r JOIN pack p ON p.id=r.pack_id WHERE p.source_id IN ({urban_placeholders}) "
            f"AND NOT ({URBAN_ROUTE_TYPES_SQL})", urban_packs,
        ).fetchone()[0]
        require(non_urban == 0, "route of a city network neither metro, tram nor funicular found")
        invalid_bikes = connection.execute("SELECT count(*) FROM trip WHERE bikes_allowed IS NOT NULL AND bikes_allowed NOT IN (1, 2)").fetchone()[0]
        require(invalid_bikes == 0, "trip bikes_allowed outside 1, 2 or NULL")
        invalid_bits = connection.execute("SELECT count(*) FROM service WHERE length(active_days) != (day_count + 7) / 8").fetchone()[0]
        require(invalid_bits == 0, "service bitset length mismatch")
        zones: list[ZoneInfo] = []
        for (timezone,) in connection.execute("SELECT DISTINCT timezone FROM agency ORDER BY timezone"):
            try:
                zones.append(ZoneInfo(str(timezone)))
            except (ZoneInfoNotFoundError, ValueError) as error:
                raise VerificationError(f"invalid agency IANA timezone: {timezone!r}") from error
        require(len(zones) <= MAX_TIME_ZONES, f"more than {MAX_TIME_ZONES} agency time zones")
        first, last = connection.execute(
            "SELECT min(start_date), max(start_date) FROM service"
        ).fetchone()
        last_days = connection.execute("SELECT max(day_count) FROM service").fetchone()[0]
        verify_rome_offsets(zones, first, last, last_days)
        busiest = connection.execute(
            "SELECT max(rows) FROM (SELECT count(*) AS rows FROM stop_time AS st JOIN stop AS s ON s.id = st.stop_id "
            "JOIN trip AS t ON t.id = st.trip_id JOIN route AS r ON r.id = t.route_id JOIN agency AS a ON a.id = r.agency_id "
            "WHERE s.catalog_station_id IS NOT NULL AND st.pickup_type = 0 GROUP BY s.catalog_station_id, a.timezone)"
        ).fetchone()[0] or 0
        require(busiest <= MAX_CANDIDATE_ROWS, f"a station has {busiest} boarding rows in one time zone, above {MAX_CANDIDATE_ROWS}")
        most_stops = connection.execute(
            "SELECT max(stops) FROM (SELECT count(*) AS stops FROM stop WHERE catalog_station_id IS NOT NULL GROUP BY catalog_station_id)"
        ).fetchone()[0] or 0
        require(most_stops <= MAX_RESOLVED_STOPS, f"a station has {most_stops} stops, above {MAX_RESOLVED_STOPS}")
        invalid_same_stop = connection.execute(
            "SELECT count(*) FROM stop_time WHERE arrival_seconds IS NOT NULL "
            "AND (departure_seconds IS NULL OR departure_seconds < arrival_seconds)"
        ).fetchone()[0]
        require(invalid_same_stop == 0, "departure precedes arrival in stop_time")
        regressive = connection.execute(
            """
            WITH ordered AS (
                SELECT trip_id, stop_sequence, arrival_seconds,
                       MAX(departure_seconds) OVER (
                           PARTITION BY trip_id ORDER BY stop_sequence
                           ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                       ) AS prior_departure
                FROM stop_time
            )
            SELECT count(*) FROM ordered
            WHERE arrival_seconds IS NOT NULL
              AND prior_departure IS NOT NULL
              AND arrival_seconds < prior_departure
            """
        ).fetchone()[0]
        require(regressive == 0, "stop_times regress along a trip")
        if urban_packs:
            verify_urban_rows(connection, urban or {})
            verify_city_row_bounds(connection, urban_packs)
        connection.close()
        return counts
    except (sqlite3.Error, json.JSONDecodeError) as error:
        if isinstance(error, VerificationError):
            raise
        raise VerificationError(f"SQLite verification failed: {error}") from error

def verify(
    output_path: Path,
    metadata_path: Path,
    *,
    max_compressed_bytes: int,
    max_database_bytes: int,
    source_policy_path: Path | None = None,
    foreign_full: str | None = None,
    personal: bool = False,
) -> dict[str, object]:
    metadata = load_metadata(metadata_path)
    require(metadata.get("schemaVersion") == METADATA_SCHEMA_VERSION, "unsupported metadata schemaVersion")
    # The public index never carries a personal pack; the personal index carries them all (--personal).
    require(bool(personal_packs(metadata)) == personal, "personal packs in the public index, or missing from the personal one")
    if foreign_full is not None:
        require(foreign_full in FOREIGN_PACKS, f"{foreign_full} is not a foreign pack")
        require(metadata.get("kind") == FOREIGN_FULL_KIND and metadata.get("sourceId") == foreign_full,
                f"metadata is not the per-country index of {foreign_full}")
    else:
        require("kind" not in metadata, "a per-country index is not the base index")
    require(output_path.is_file(), f"compressed database is missing: {output_path}")
    compressed_size = output_path.stat().st_size
    require(compressed_size <= max_compressed_bytes, f"compressed database exceeds {max_compressed_bytes} bytes")
    compression = metadata.get("compression")
    database = metadata.get("database")
    require(isinstance(compression, dict) and compression.get("format") == "zlib" and compression.get("level") == 9, "invalid compression metadata")
    require(isinstance(database, dict), "invalid database metadata")
    require(compression.get("sizeBytes") == compressed_size, "compressed size metadata mismatch")
    compressed_hash = sha256_file(output_path)
    require(compression.get("sha256") == compressed_hash, "compressed SHA-256 mismatch")
    require(metadata.get("maximumCompressedSizeBytes") == max_compressed_bytes, "configured maximum size differs from build metadata")
    input_fingerprint, inputs = verify_inputs(metadata, metadata_path, source_policy_path, foreign_full)
    border_zones = border_zone_rules(metadata, inputs) if foreign_full is None else {}
    urban = urban_rules(metadata, inputs) if foreign_full is None else {}
    fd, raw_name = tempfile.mkstemp(prefix="binario-verify-", suffix=".sqlite")
    os.close(fd)
    raw_path = Path(raw_name)
    try:
        database_hash, database_size = decompress_bounded(output_path, raw_path, max_database_bytes)
        require(database.get("sizeBytes") == database_size, "decompressed size metadata mismatch")
        require(database.get("sha256") == database_hash, "decompressed SHA-256 mismatch")
        counts = verify_database(raw_path, metadata, input_fingerprint, foreign_full=foreign_full,
                                 border_zones=border_zones, urban=urban)
        if source_policy_path is not None and foreign_full is None:
            verify_source_policy(source_policy_path, pack_source_ids(raw_path))
        elif source_policy_path is not None:
            verify_source_policy_lists(source_policy_path, foreign_full)
    finally:
        raw_path.unlink(missing_ok=True)
    return {"compressedSizeBytes": compressed_size, "databaseSizeBytes": database_size, "compressedSha256": compressed_hash, "databaseSha256": database_hash, "inputFingerprintSha256": input_fingerprint, "rowCounts": counts}

def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[1]
    parser.add_argument("--input", type=Path, help="default: the base index, or data/gtfs-foreign/ios/<id>.sqlite.zlib")
    parser.add_argument("--metadata", type=Path, help="default: the base index metadata, or data/gtfs-foreign/ios/<id>.metadata.json")
    parser.add_argument("--source-policy", type=Path, default=root / "ios/Resources/binario_gtfs_source_policy_v1.json")
    parser.add_argument("--max-compressed-bytes", type=int, default=DEFAULT_MAX_COMPRESSED_BYTES)
    parser.add_argument("--max-database-bytes", type=int, default=DEFAULT_MAX_DATABASE_BYTES)
    parser.add_argument("--foreign-full", metavar="SOURCE_ID", help="verify the per-country index of this foreign pack")
    parser.add_argument("--personal", action="store_true", help="verify the personal index (ios/Personal/)")
    args = parser.parse_args(argv)
    if args.personal:
        directory = root / "ios/Personal"
        args.input = args.input or directory / "binario_gtfs_schedule_personal_v1.sqlite.zlib"
        args.metadata = args.metadata or directory / "binario_gtfs_schedule_personal_v1.metadata.json"
        if args.source_policy == root / "ios/Resources/binario_gtfs_source_policy_v1.json":
            args.source_policy = directory / "binario_gtfs_source_policy_personal_v1.json"
    if args.foreign_full:
        directory = root / foreign_rules.IOS_FULL_DIR
        args.input = args.input or directory / f"{args.foreign_full}.sqlite.zlib"
        args.metadata = args.metadata or directory / f"{args.foreign_full}.metadata.json"
    args.input = args.input or root / "ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib"
    args.metadata = args.metadata or root / "ios/Resources/binario_gtfs_schedule_v1.metadata.json"
    return args

def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        result = verify(
            args.input,
            args.metadata,
            max_compressed_bytes=args.max_compressed_bytes,
            max_database_bytes=args.max_database_bytes,
            source_policy_path=args.source_policy,
            foreign_full=args.foreign_full,
            personal=args.personal,
        )
    except VerificationError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
