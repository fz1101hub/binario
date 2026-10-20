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
from datetime import date
from pathlib import Path
from typing import Sequence
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

APPLICATION_ID = int.from_bytes(b"BNGT", "big")
USER_VERSION = 1
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
    "trip": ("id", "pack_id", "route_id", "service_id", "external_id", "headsign", "short_name", "direction_id"),
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
    "idx_stop_time_trip_sequence",
}

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

def verify_input_fingerprint(
    metadata: dict[str, object],
    metadata_path: Path,
    source_policy_path: Path | None = None,
) -> str:
    inputs = metadata.get("inputs")
    require(isinstance(inputs, dict), "metadata.inputs is missing")
    files = inputs.get("files")
    expected_fingerprint = inputs.get("fingerprintSha256")
    base_path = inputs.get("basePath")
    expected_count = 13 if source_policy_path is not None else 12
    require(
        isinstance(files, list) and len(files) == expected_count,
        "metadata must describe manifest, catalog, reviewed source policy, and 10 archives"
        if source_policy_path is not None
        else "metadata must describe manifest, catalog, and 10 archives",
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
        require(path.is_file(), f"input file is missing: {path}")
        require(path.stat().st_size == expected_size, f"input size changed: {path}")
        require(sha256_file(path) == expected_hash, f"input hash changed: {path}")
        descriptors.append({"path": raw_path, "size": expected_size, "sha256": expected_hash})
    actual_fingerprint = hashlib.sha256(canonical_json(descriptors)).hexdigest()
    require(actual_fingerprint == expected_fingerprint, "input fingerprint mismatch")
    require(inputs.get("packCount") == 10, "metadata pack count must be 10")
    if source_policy_path is not None:
        require(source_policy_path.resolve() in resolved_inputs, "reviewed GTFS source policy is absent from the input fingerprint")
    return actual_fingerprint

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

def verify_database(path: Path, metadata: dict[str, object], input_fingerprint: str) -> dict[str, int]:
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
        require(actual_tables == set(REQUIRED_TABLE_COLUMNS), f"unexpected schema tables: {sorted(actual_tables)}")
        for table, expected_columns in REQUIRED_TABLE_COLUMNS.items():
            columns = tuple(row[1] for row in connection.execute(f"PRAGMA table_info({table})"))
            require(columns == expected_columns, f"column schema mismatch for {table}: {columns}")
        actual_indices = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_autoindex_%'")}
        require(actual_indices == REQUIRED_INDICES, f"index mismatch: {sorted(actual_indices)}")
        counts = {table: int(connection.execute(f"SELECT count(*) FROM {table}").fetchone()[0]) for table in REQUIRED_TABLE_COLUMNS if table != "metadata"}
        require(counts == expected_counts, f"row count mismatch: expected {expected_counts}, got {counts}")
        require(counts["pack"] == 10, "database must contain exactly 10 packs")
        require(counts["trip"] > 0 and counts["stop_time"] > 0, "schedule is empty")
        embedded = dict(connection.execute("SELECT key,value FROM metadata"))
        require(embedded.get("input_fingerprint_sha256") == input_fingerprint, "embedded input fingerprint mismatch")
        require(embedded.get("schema_version") == str(USER_VERSION), "embedded schema version mismatch")
        require(embedded.get("source_generated_at") == metadata["inputs"]["sourceGeneratedAt"], "embedded source generation date mismatch")
        require(json.loads(embedded.get("row_counts_json", "null")) == counts, "embedded row counts mismatch")
        incomplete = connection.execute("SELECT count(*) FROM trip t WHERE NOT EXISTS (SELECT 1 FROM stop_time st WHERE st.trip_id=t.id)").fetchone()[0]
        require(incomplete == 0, "trip without stop_times found")
        unverified = connection.execute("SELECT count(*) FROM trip t WHERE NOT EXISTS (SELECT 1 FROM stop_time st JOIN stop s ON s.id=st.stop_id WHERE st.trip_id=t.id AND s.catalog_station_id IS NOT NULL AND (s.railway_code IS NOT NULL OR s.trenitalia_location_id IS NOT NULL))").fetchone()[0]
        require(unverified == 0, "trip not touching a verified catalog station found")
        non_rail = connection.execute(
            """
            SELECT count(*)
            FROM route r JOIN pack p ON p.id=r.pack_id
            WHERE r.route_type != 2
              AND NOT (r.route_type BETWEEN 100 AND 199)
              AND NOT (
                p.source_id='toscana-tft-gtfs'
                AND r.route_type=3
                AND r.external_id IN ('1028056267','2272465657')
              )
            """
        ).fetchone()[0]
        require(non_rail == 0, "non-rail route found")
        invalid_bits = connection.execute("SELECT count(*) FROM service WHERE length(active_days) != (day_count + 7) / 8").fetchone()[0]
        require(invalid_bits == 0, "service bitset length mismatch")
        for (timezone,) in connection.execute("SELECT DISTINCT timezone FROM agency"):
            try:
                ZoneInfo(str(timezone))
            except (ZoneInfoNotFoundError, ValueError) as error:
                raise VerificationError(f"invalid agency IANA timezone: {timezone!r}") from error
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
) -> dict[str, object]:
    metadata = load_metadata(metadata_path)
    require(metadata.get("schemaVersion") == METADATA_SCHEMA_VERSION, "unsupported metadata schemaVersion")
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
    input_fingerprint = verify_input_fingerprint(metadata, metadata_path, source_policy_path)
    fd, raw_name = tempfile.mkstemp(prefix="binario-verify-", suffix=".sqlite")
    os.close(fd)
    raw_path = Path(raw_name)
    try:
        database_hash, database_size = decompress_bounded(output_path, raw_path, max_database_bytes)
        require(database.get("sizeBytes") == database_size, "decompressed size metadata mismatch")
        require(database.get("sha256") == database_hash, "decompressed SHA-256 mismatch")
        counts = verify_database(raw_path, metadata, input_fingerprint)
    finally:
        raw_path.unlink(missing_ok=True)
    return {"compressedSizeBytes": compressed_size, "databaseSizeBytes": database_size, "compressedSha256": compressed_hash, "databaseSha256": database_hash, "inputFingerprintSha256": input_fingerprint, "rowCounts": counts}

def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[1]
    parser.add_argument("--input", type=Path, default=root / "ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib")
    parser.add_argument("--metadata", type=Path, default=root / "ios/Resources/binario_gtfs_schedule_v1.metadata.json")
    parser.add_argument("--source-policy", type=Path, default=root / "ios/Resources/binario_gtfs_source_policy_v1.json")
    parser.add_argument("--max-compressed-bytes", type=int, default=DEFAULT_MAX_COMPRESSED_BYTES)
    parser.add_argument("--max-database-bytes", type=int, default=DEFAULT_MAX_DATABASE_BYTES)
    return parser.parse_args(argv)

def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        result = verify(
            args.input,
            args.metadata,
            max_compressed_bytes=args.max_compressed_bytes,
            max_database_bytes=args.max_database_bytes,
            source_policy_path=args.source_policy,
        )
    except VerificationError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
