#!/usr/bin/env python3
"""Compare a GTFS iOS rebuild across SQLite versions without weakening the shipped asset hash."""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import tempfile
from pathlib import Path
from typing import Sequence

from verify_ios_gtfs_schedule import (
    DEFAULT_MAX_COMPRESSED_BYTES,
    DEFAULT_MAX_DATABASE_BYTES,
    REQUIRED_TABLE_COLUMNS,
    VerificationError,
    decompress_bounded,
    load_metadata,
    require,
    verify,
)


def comparable_metadata(path: Path) -> dict[str, object]:
    metadata = load_metadata(path)
    # Packaging (page allocation and zlib output) varies with the host SQLite
    # build, but the committed asset's exact hashes remain independently pinned.
    metadata["compression"] = {key: value for key, value in metadata["compression"].items()
                               if key not in ("sha256", "sizeBytes")}
    metadata["database"] = {key: value for key, value in metadata["database"].items()
                            if key not in ("sha256", "sizeBytes")}
    return metadata


def compare_rebuild(
    canonical: Path,
    canonical_metadata: Path,
    rebuilt: Path,
    rebuilt_metadata: Path,
    *,
    source_policy_path: Path | None = None,
    max_compressed_bytes: int = DEFAULT_MAX_COMPRESSED_BYTES,
    max_database_bytes: int = DEFAULT_MAX_DATABASE_BYTES,
) -> dict[str, object]:
    original = verify(canonical, canonical_metadata,
                      max_compressed_bytes=max_compressed_bytes,
                      max_database_bytes=max_database_bytes,
                      source_policy_path=source_policy_path)
    candidate = verify(rebuilt, rebuilt_metadata,
                       max_compressed_bytes=max_compressed_bytes,
                       max_database_bytes=max_database_bytes,
                       source_policy_path=source_policy_path)
    require(comparable_metadata(canonical_metadata) == comparable_metadata(rebuilt_metadata),
            "rebuild metadata differs beyond platform-dependent binary digests and lengths")
    require(original["inputFingerprintSha256"] == candidate["inputFingerprintSha256"],
            "rebuild input fingerprint differs from canonical asset")

    with tempfile.TemporaryDirectory(prefix="binario-gtfs-compare-") as folder:
        left = Path(folder) / "canonical.sqlite"
        right = Path(folder) / "rebuilt.sqlite"
        decompress_bounded(canonical, left, max_database_bytes)
        decompress_bounded(rebuilt, right, max_database_bytes)
        connection = sqlite3.connect(f"file:{left}?mode=ro&immutable=1", uri=True)
        try:
            connection.execute("ATTACH DATABASE ? AS rebuilt", (f"file:{right}?mode=ro&immutable=1",))
            for table in sorted(REQUIRED_TABLE_COLUMNS):
                for first, second in (("main", "rebuilt"), ("rebuilt", "main")):
                    difference = connection.execute(
                        f"SELECT * FROM {first}.{table} EXCEPT SELECT * FROM {second}.{table} LIMIT 1"
                    ).fetchone()
                    require(difference is None, f"rebuild table content differs: {table}")
            schema_query = "SELECT name, type, sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type, name"
            left_schema = connection.execute(schema_query).fetchall()
            right_schema = connection.execute(schema_query.replace("sqlite_master", "rebuilt.sqlite_master")).fetchall()
            require(left_schema == right_schema, "rebuild table/index definitions differ")
        except sqlite3.Error as error:
            raise VerificationError(f"cannot compare GTFS databases: {error}") from error
        finally:
            connection.close()
    return {"tablesCompared": len(REQUIRED_TABLE_COLUMNS),
            "rowsCompared": original["rowCounts"],
            "canonicalDatabaseSha256": original["databaseSha256"],
            "rebuiltDatabaseSha256": candidate["databaseSha256"]}


def main(argv: Sequence[str] | None = None) -> int:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--canonical", type=Path, default=root / "ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib")
    parser.add_argument("--canonical-metadata", type=Path, default=root / "ios/Resources/binario_gtfs_schedule_v1.metadata.json")
    parser.add_argument("--rebuild", type=Path, required=True)
    parser.add_argument("--rebuild-metadata", type=Path, required=True)
    parser.add_argument("--source-policy", type=Path, default=root / "ios/Resources/binario_gtfs_source_policy_v1.json")
    args = parser.parse_args(sys.argv[1:] if argv is None else argv)
    try:
        result = compare_rebuild(args.canonical, args.canonical_metadata, args.rebuild, args.rebuild_metadata,
                                 source_policy_path=args.source_policy)
    except (VerificationError, KeyError, TypeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
