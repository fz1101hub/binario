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
sys.path.insert(0, str(ROOT / "tools"))

import build_gtfs_packs as builder  # noqa: E402  (one freshness rule and one derived service end for every check)

ASSET_ROOT = ROOT / "app" / "src" / "main" / "assets"
GTFS_ROOT = ASSET_ROOT / "gtfs"
STATION_ROOT = ASSET_ROOT / "stations"
# The GTFS directories an APK can hold: (asset root, directory, required). gtfs-transit/ (regional railways and city
# networks, every build) and gtfs-personal/ (personal builds only, src/personal/assets) are verified when present.
PERSONAL_ASSET_ROOT = ROOT / "app" / "src" / "personal" / "assets"
GTFS_DIRECTORIES = (
    (ASSET_ROOT, "gtfs", True),
    (ASSET_ROOT, "gtfs-transit", False),
    (PERSONAL_ASSET_ROOT, "gtfs-personal", False),
)
REQUIRED_GTFS_FILES = {"agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt"}
# Every pack names its licensor, licence and the evidence for it (audit A7-03, proposal P3): the Android credits are
# generated from these fields (tools/build_android_gtfs_credits.py). A licence the owner has still to confirm is a
# warning, not a failure; a missing or undeclared one may only ship in personal builds (gtfs-personal/).
LICENCE_FIELDS = ("attribution", "licence", "licenceEvidence")
OWNER_TO_CONFIRM = "da confermare dal titolare"
UNDECLARED_LICENCE_PREFIXES = ("non dichiarata", "non verificabile", "nessuna licenza", "sconosciuta")
PERSONAL_DIRECTORY = "gtfs-personal"
DEFAULT_MIN_VALID_DAYS = builder.DEFAULT_MIN_VALID_DAYS


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


def safe_asset_path(value: object, failures: list[str], label: str, asset_root: Path = ASSET_ROOT) -> Path | None:
    if not isinstance(value, str) or not value:
        failures.append(f"{label} must be a non-empty relative asset path")
        return None
    root = asset_root.resolve()
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


def verify_licence(directory: str, pack: dict[str, Any], source_id: object, failures: list[str], warnings: list[str]) -> None:
    """Licence fields of one manifest entry: present everywhere, declared outside personal builds."""
    missing = [field for field in LICENCE_FIELDS if not isinstance(pack.get(field), str) or not pack[field].strip()]
    licence = pack.get("licence")
    if missing:
        if directory == PERSONAL_DIRECTORY and missing == ["licence"] and licence is None:
            warnings.append(f"GTFS pack {source_id} ({directory}/) has no licence: personal builds only")
        else:
            failures.append(f"GTFS pack {source_id} ({directory}/) has no {', '.join(missing)}")
        return
    text = str(licence).strip().lower()
    if OWNER_TO_CONFIRM in text:
        warnings.append(f"GTFS pack {source_id} ({directory}/): licence to be confirmed by the owner ({pack['licenceEvidence'][:160]})")
    elif text.startswith(UNDECLARED_LICENCE_PREFIXES) and directory != PERSONAL_DIRECTORY:
        failures.append(f"GTFS pack {source_id} ({directory}/) ships with an undeclared licence outside personal builds: {licence}")


def verify_gtfs(
    today: date,
    minimum_valid_days: int,
    failures: list[str],
    *,
    enforce_freshness: bool = True,
    warnings: list[str] | None = None,
) -> dict[str, int]:
    totals = {"gtfs_packs": 0, "gtfs_expiring_or_expired": 0}
    for asset_root, directory, required in GTFS_DIRECTORIES:
        if not (asset_root / directory).is_dir() and not required:
            continue
        result = verify_gtfs_directory(
            asset_root, directory, today, minimum_valid_days, failures, enforce_freshness=enforce_freshness,
            warnings=warnings,
        )
        for key in totals:
            totals[key] += result[key]
    return totals


def verify_gtfs_directory(
    asset_root: Path,
    directory: str,
    today: date,
    minimum_valid_days: int,
    failures: list[str],
    *,
    enforce_freshness: bool = True,
    warnings: list[str] | None = None,
) -> dict[str, int]:
    warnings = warnings if warnings is not None else []
    gtfs_root = asset_root / directory
    manifest_path = gtfs_root / "manifest.json"
    manifest = load_json(manifest_path, failures)
    if not isinstance(manifest, dict) or not isinstance(manifest.get("packs"), list):
        failures.append(f"GTFS manifest {directory}/manifest.json must contain a packs array")
        return {"gtfs_packs": 0, "gtfs_expiring_or_expired": 0}

    packs = manifest["packs"]
    expected_files: set[str] = set()
    expiring_or_expired = 0

    for index, pack in enumerate(packs):
        label = f"GTFS pack #{index}"
        if not isinstance(pack, dict):
            failures.append(f"{label} is not an object")
            continue
        source_id = pack.get("sourceId")
        if not isinstance(source_id, str) or not source_id:
            failures.append(f"{label} has no sourceId")
            source_id = f"#{index}"
        verify_licence(directory, pack, source_id, failures, warnings)
        if not str(pack.get("asset", "")).startswith(directory + "/"):
            failures.append(f"GTFS pack {source_id} asset is outside {directory}/: {pack.get('asset')}")
        asset = safe_asset_path(pack.get("asset"), failures, f"GTFS pack {source_id} asset", asset_root)
        # A city feed published a few weeks ahead declares a shorter release window (minValidDays, at most the default).
        try:
            window = builder.pack_min_valid_days(pack, minimum_valid_days)
        except builder.BuildError:
            failures.append(f"GTFS pack {source_id} has an invalid minValidDays: {pack.get('minValidDays')!r}")
            window = minimum_valid_days
        cutoff = builder.release_freshness_cutoff(today, window)
        derived_end: date | None = None
        derived_full_end: date | None = None
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
                else:
                    # Freshness is judged on the service end derived from the calendar, as build_gtfs_packs.py does.
                    try:
                        derived_end = builder.service_end_from_asset(asset, str(source_id))
                        derived_full_end = builder.full_service_end_from_asset(asset, str(source_id))
                    except builder.BuildError as exc:
                        failures.append(f"GTFS pack {source_id}: service end not derivable: {builder.log_safe(exc)}")

        value = pack.get("lastServiceDate")
        try:
            last_service_date = date.fromisoformat(value) if isinstance(value, str) else None
        except ValueError:
            last_service_date = None
        if last_service_date is None:
            failures.append(f"GTFS pack {source_id} has no ISO-8601 lastServiceDate")
            continue
        if derived_end is not None and derived_end != last_service_date:
            failures.append(
                f"GTFS pack {source_id} declares lastServiceDate {last_service_date.isoformat()} "
                f"but its calendar ends {derived_end.isoformat()}"
            )
        if derived_full_end is not None and pack.get("fullServiceUntil") != derived_full_end.isoformat():
            failures.append(
                f"GTFS pack {source_id} declares fullServiceUntil {builder.log_safe(pack.get('fullServiceUntil'))} "
                f"but its trips run the full timetable until {derived_full_end.isoformat()}"
            )
        # Freshness is judged on the end of full coverage (audit2 tools-data-2): a thin tail of trips is not a timetable.
        end = min(derived_end or last_service_date, derived_full_end or last_service_date)
        if not builder.is_release_fresh(end, today, window):
            expiring_or_expired += 1
            if enforce_freshness:
                relation = "expired" if end < today else "expires before the required freshness window"
                failures.append(
                    f"GTFS pack {source_id} {relation}: service ends {end.isoformat()}, "
                    f"required through {cutoff.isoformat()}"
                )

    actual_files = {path.name for path in gtfs_root.glob("*.zip")}
    if actual_files != expected_files:
        failures.append(
            "GTFS manifest/assets mismatch: "
            f"unlisted={sorted(actual_files - expected_files)}, missing={sorted(expected_files - actual_files)}"
        )

    checksums = read_checksum_file(gtfs_root / "SHA256SUMS", failures)
    if checksums.keys() != actual_files:
        failures.append(
            "SHA256SUMS/assets mismatch: "
            f"unlisted={sorted(actual_files - checksums.keys())}, missing={sorted(checksums.keys() - actual_files)}"
        )
    for name, expected_hash in checksums.items():
        asset = gtfs_root / name
        if asset.is_file() and sha256(asset) != expected_hash:
            failures.append(f"SHA256SUMS mismatch for {name}")

    return {"gtfs_packs": len(packs), "gtfs_expiring_or_expired": expiring_or_expired}


def verify_stations(failures: list[str], warnings: list[str] | None = None) -> dict[str, int]:
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
        # Origin and licence of the catalogue (audit A7-09): required fields; "da confermare dal titolare" is a warning
        # until the owner records them, since the in-app credit and the store content-rights answers depend on them.
        for field in ("provenance", "licence"):
            value = catalog.get(field)
            if not isinstance(value, str) or not value.strip():
                failures.append(f"Station catalog {catalog_id} has no {field}")
            elif OWNER_TO_CONFIRM in value.lower() and warnings is not None:
                warnings.append(f"Station catalog {catalog_id}: {field} to be confirmed by the owner")
        created_at = catalog.get("sourceCreatedAtUtc")
        if isinstance(created_at, str):
            try:
                datetime.fromisoformat(created_at.replace("Z", "+00:00"))
            except ValueError:
                failures.append(f"Station catalog {catalog_id} has invalid sourceCreatedAtUtc")
        else:
            failures.append(f"Station catalog {catalog_id} has no sourceCreatedAtUtc")
    return {"station_catalogs": len(catalogs)}


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
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
        default=DEFAULT_MIN_VALID_DAYS,
        help=f"Minimum remaining calendar days required for every GTFS pack (default: {DEFAULT_MIN_VALID_DAYS}).",
    )
    parser.add_argument(
        "--integrity-only",
        action="store_true",
        help="Validate files, manifests, hashes and dates without failing for stale data. Release gates must omit this flag.",
    )
    args = parser.parse_args(argv)
    if args.min_valid_days < 0:
        parser.error("--min-valid-days must be non-negative")
    return args


def main() -> int:
    args = parse_args()

    failures: list[str] = []
    warnings: list[str] = []
    summary = {
        "today": args.today.isoformat(),
        "min_valid_days": args.min_valid_days,
        "freshness_enforced": not args.integrity_only,
    }
    summary.update(
        verify_gtfs(
            args.today,
            args.min_valid_days,
            failures,
            enforce_freshness=not args.integrity_only,
            warnings=warnings,
        )
    )
    summary.update(verify_stations(failures, warnings))
    summary["failures"] = len(failures)
    summary["warnings"] = len(warnings)
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
    for warning in warnings:
        print(f"WARNING: {warning}", file=sys.stderr)
    for failure in failures:
        print(f"ERROR: {failure}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
