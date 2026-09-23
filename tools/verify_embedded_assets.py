#!/usr/bin/env python3
"""Verify integrity and release freshness of checked-in Android data assets.

This verifier is intentionally offline: it validates only the asset files and
metadata committed to the repository. It does not fetch feeds or alter reports.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
ASSET_ROOT = ROOT / "app" / "src" / "main" / "assets"
GTFS_ROOT = ASSET_ROOT / "gtfs"
STATION_ROOT = ASSET_ROOT / "stations"
REQUIRED_GTFS_FILES = {"agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path, failures: list[str]) -> Any | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        failures.append(f"Cannot read valid JSON from {path.relative_to(ROOT)}: {exc}")
        return None


def safe_asset_path(value: object, failures: list[str], label: str) -> Path | None:
    if not isinstance(value, str) or not value:
        failures.append(f"{label} must be a non-empty relative asset path")
        return None
    root = ASSET_ROOT.resolve()
    path = (root / value).resolve()
    try:
        path.relative_to(root)
    except ValueError:
        failures.append(f"{label} escapes the Android asset directory: {value}")
        return None
    return path


def read_checksum_file(path: Path, failures: list[str]) -> dict[str, str]:
    entries: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        failures.append(f"Cannot read {path.relative_to(ROOT)}: {exc}")
        return entries
    for line in filter(str.strip, lines):
        parts = line.strip().split(maxsplit=1)
        if len(parts) != 2 or len(parts[0]) != 64 or any(char not in "0123456789abcdef" for char in parts[0]):
            failures.append(f"Malformed SHA256SUMS entry: {line}")
            continue
        name = parts[1].removeprefix("*")
        if name in entries:
            failures.append(f"Duplicate SHA256SUMS entry for {name}")
            continue
        entries[name] = parts[0]
    return entries


def verify_gtfs(today: date, minimum_valid_days: int, failures: list[str]) -> dict[str, int]:
    manifest_path = GTFS_ROOT / "manifest.json"
    manifest = load_json(manifest_path, failures)
    if not isinstance(manifest, dict) or not isinstance(manifest.get("packs"), list):
        failures.append("GTFS manifest must contain a packs array")
        return {"gtfs_packs": 0, "gtfs_expiring_or_expired": 0}

    packs = manifest["packs"]
    expected_files: set[str] = set()
    expiring_or_expired = 0
    cutoff = today + timedelta(days=minimum_valid_days)

    for index, pack in enumerate(packs):
        label = f"GTFS pack #{index}"
        if not isinstance(pack, dict):
            failures.append(f"{label} is not an object")
            continue
        source_id = pack.get("sourceId")
        if not isinstance(source_id, str) or not source_id:
            failures.append(f"{label} has no sourceId")
            source_id = f"#{index}"
        asset = safe_asset_path(pack.get("asset"), failures, f"GTFS pack {source_id} asset")
        expected_hash = pack.get("sha256")
        if not isinstance(expected_hash, str) or len(expected_hash) != 64:
            failures.append(f"GTFS pack {source_id} has no valid sha256")
        if asset is not None:
            expected_files.add(asset.name)
            if not asset.is_file():
                failures.append(f"GTFS pack {source_id} is missing: {asset.relative_to(ROOT)}")
            else:
                actual_hash = sha256(asset)
                if actual_hash != expected_hash:
                    failures.append(f"GTFS pack {source_id} SHA-256 mismatch for {asset.name}")
                try:
                    with zipfile.ZipFile(asset) as archive:
                        broken_member = archive.testzip()
                        if broken_member:
                            failures.append(f"GTFS pack {source_id} has corrupt ZIP member {broken_member}")
                        names = {name.rsplit("/", 1)[-1] for name in archive.namelist() if not name.endswith("/")}
                        missing = sorted(REQUIRED_GTFS_FILES - names)
                        if missing:
                            failures.append(f"GTFS pack {source_id} is missing required files: {', '.join(missing)}")
                except (OSError, zipfile.BadZipFile) as exc:
                    failures.append(f"GTFS pack {source_id} is not a readable ZIP archive: {exc}")

        value = pack.get("lastServiceDate")
        try:
            last_service_date = date.fromisoformat(value) if isinstance(value, str) else None
        except ValueError:
            last_service_date = None
        if last_service_date is None:
            failures.append(f"GTFS pack {source_id} has no ISO-8601 lastServiceDate")
        elif last_service_date < cutoff:
            expiring_or_expired += 1
            relation = "expired" if last_service_date < today else "expires before the required freshness window"
            failures.append(
                f"GTFS pack {source_id} {relation}: lastServiceDate {last_service_date.isoformat()}, "
                f"required through {cutoff.isoformat()}"
            )

    actual_files = {path.name for path in GTFS_ROOT.glob("*.zip")}
    if actual_files != expected_files:
        failures.append(
            "GTFS manifest/assets mismatch: "
            f"unlisted={sorted(actual_files - expected_files)}, missing={sorted(expected_files - actual_files)}"
        )

    checksums = read_checksum_file(GTFS_ROOT / "SHA256SUMS", failures)
    if checksums.keys() != actual_files:
        failures.append(
            "SHA256SUMS/assets mismatch: "
            f"unlisted={sorted(actual_files - checksums.keys())}, missing={sorted(checksums.keys() - actual_files)}"
        )
    for name, expected_hash in checksums.items():
        asset = GTFS_ROOT / name
        if asset.is_file() and sha256(asset) != expected_hash:
            failures.append(f"SHA256SUMS mismatch for {name}")

    return {"gtfs_packs": len(packs), "gtfs_expiring_or_expired": expiring_or_expired}


def verify_stations(failures: list[str]) -> dict[str, int]:
    manifest_path = STATION_ROOT / "manifest.json"
    manifest = load_json(manifest_path, failures)
    if not isinstance(manifest, dict) or not isinstance(manifest.get("catalogs"), list):
        failures.append("Station manifest must contain a catalogs array")
        return {"station_catalogs": 0}

    catalogs = manifest["catalogs"]
    for index, catalog in enumerate(catalogs):
        label = f"station catalog #{index}"
        if not isinstance(catalog, dict):
            failures.append(f"{label} is not an object")
            continue
        catalog_id = catalog.get("id") if isinstance(catalog.get("id"), str) else f"#{index}"
        asset = safe_asset_path(catalog.get("assetPath"), failures, f"Station catalog {catalog_id} assetPath")
        expected_hash = catalog.get("assetSha256")
        if not isinstance(expected_hash, str) or len(expected_hash) != 64:
            failures.append(f"Station catalog {catalog_id} has no valid assetSha256")
        if asset is None or not asset.is_file():
            if asset is not None:
                failures.append(f"Station catalog {catalog_id} is missing: {asset.relative_to(ROOT)}")
            continue
        if sha256(asset) != expected_hash:
            failures.append(f"Station catalog {catalog_id} SHA-256 mismatch for {asset.name}")
        payload = load_json(asset, failures)
        stations = payload.get("stations") if isinstance(payload, dict) else None
        expected_count = catalog.get("stationCount")
        if not isinstance(stations, list):
            failures.append(f"Station catalog {catalog_id} has no stations array")
        elif not isinstance(expected_count, int) or len(stations) != expected_count:
            failures.append(
                f"Station catalog {catalog_id} stationCount mismatch: manifest={expected_count}, asset={len(stations)}"
            )
        created_at = catalog.get("sourceCreatedAtUtc")
        if isinstance(created_at, str):
            try:
                datetime.fromisoformat(created_at.replace("Z", "+00:00"))
            except ValueError:
                failures.append(f"Station catalog {catalog_id} has invalid sourceCreatedAtUtc")
        else:
            failures.append(f"Station catalog {catalog_id} has no sourceCreatedAtUtc")
    return {"station_catalogs": len(catalogs)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--today",
        type=date.fromisoformat,
        default=datetime.now(timezone.utc).date(),
        metavar="YYYY-MM-DD",
        help="Date used for freshness validation (default: current UTC date).",
    )
    parser.add_argument(
        "--min-valid-days",
        type=int,
        default=14,
        help="Minimum remaining calendar days required for every GTFS pack (default: 14).",
    )
    args = parser.parse_args()
    if args.min_valid_days < 0:
        parser.error("--min-valid-days must be non-negative")

    failures: list[str] = []
    summary = {"today": args.today.isoformat(), "min_valid_days": args.min_valid_days}
    summary.update(verify_gtfs(args.today, args.min_valid_days, failures))
    summary.update(verify_stations(failures))
    summary["failures"] = len(failures)
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
    for failure in failures:
        print(f"ERROR: {failure}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
