#!/usr/bin/env python3
"""Create Binario's canonical offline station catalog from the user-curated SQLite DB.

The generated asset contains station identity and discovery metadata only.  It
deliberately has no timetable, delay, platform, cancellation, position or other
operational field.  `trenitalia_location_id` is retained only when its mapping is
unique in the source DB, so ambiguous identifiers cannot be used automatically.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import sqlite3
from collections import defaultdict
from datetime import UTC, datetime
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_catalog(database: Path) -> tuple[dict[str, str], list[dict[str, object]], int]:
    connection = sqlite3.connect(f"file:{database}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    try:
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise RuntimeError(f"SQLite integrity check failed: {integrity}")
        metadata = {row["key"]: row["value"] for row in connection.execute("SELECT key, value FROM metadata")}
        identifier_rows = list(
            connection.execute(
                "SELECT station_id, identifier_type, identifier_value FROM station_identifiers "
                "WHERE trim(identifier_value) <> ''"
            )
        )
        location_owners: dict[str, set[str]] = defaultdict(set)
        identifiers: dict[str, dict[str, list[str]]] = defaultdict(lambda: defaultdict(list))
        for row in identifier_rows:
            station_id = row["station_id"]
            identifier_type = row["identifier_type"]
            value = row["identifier_value"].strip()
            identifiers[station_id][identifier_type].append(value)
            if identifier_type == "trenitalia_location_id":
                location_owners[value].add(station_id)
        aliases: dict[str, list[str]] = defaultdict(list)
        for row in connection.execute("SELECT station_id, alias FROM station_aliases WHERE trim(alias) <> '' ORDER BY station_id, alias"):
            aliases[row["station_id"]].append(row["alias"].strip())
        stations: list[dict[str, object]] = []
        for row in connection.execute(
            "SELECT station_id, station_name, station_code, latitude, longitude, confidence "
            "FROM stations ORDER BY station_name COLLATE NOCASE, station_id"
        ):
            station_id = row["station_id"]
            location_ids = identifiers[station_id].get("trenitalia_location_id", [])
            safe_location_id = next(
                (value for value in location_ids if len(location_owners[value]) == 1),
                None,
            )
            stations.append(
                {
                    "id": station_id,
                    "name": row["station_name"].strip(),
                    "railwayCode": (row["station_code"] or "").strip() or None,
                    "trenitaliaLocationId": safe_location_id,
                    "aliases": sorted(set(aliases[station_id]), key=str.casefold),
                    "latitude": row["latitude"],
                    "longitude": row["longitude"],
                    "confidence": row["confidence"],
                }
            )
        if not stations:
            raise RuntimeError("The curated DB has no stations")
        return metadata, stations, sum(1 for owners in location_owners.values() if len(owners) > 1)
    finally:
        connection.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    metadata, stations, ambiguous_location_ids = read_catalog(args.input)
    payload = {
        "schemaVersion": 1,
        "kind": "curated-rail-station-catalog",
        "source": "Database curato fornito dall’utente",
        "sourceDatabaseName": metadata.get("database_name", args.input.name),
        "sourceDatabaseSha256": sha256(args.input),
        "sourceCreatedAtUtc": metadata.get("created_at_utc"),
        "generatedAt": datetime.now(UTC).replace(microsecond=0).isoformat(),
        "stationCount": len(stations),
        "ambiguousTrenitaliaLocationIdsExcluded": ambiguous_location_ids,
        "operationalData": False,
        "notice": "Catalogo canonico di stazioni per ricerca, tratte, widget e riconciliazione di identità. Non contiene né determina dati operativi.",
        "stations": stations,
    }
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("wb") as raw_output:
        with gzip.GzipFile(fileobj=raw_output, mode="wb", compresslevel=9, mtime=0) as stream:
            stream.write(encoded)
    print(
        json.dumps(
            {
                "stations": len(stations),
                "asset_sha256": sha256(args.output),
                "source_db_sha256": sha256(args.input),
                "ambiguous_trenitalia_location_ids_excluded": ambiguous_location_ids,
            }
        )
    )


if __name__ == "__main__":
    main()
