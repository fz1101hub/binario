#!/usr/bin/env python3
"""Verifica offline i data pack GTFS inclusi nell'APK Binario."""
from __future__ import annotations

import csv
import hashlib
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSET_ROOT = ROOT / "app/src/main/assets"
MANIFEST = ASSET_ROOT / "gtfs/manifest.json"
REQUIRED = {"agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt"}
RAIL_TYPES = {"2"} | {str(value) for value in range(100, 200)}


def rows(archive: zipfile.ZipFile, name: str):
    candidate = next((item for item in archive.namelist() if item == name or item.endswith("/" + name)), None)
    if candidate is None:
        return []
    with archive.open(candidate) as raw:
        return list(csv.DictReader((line.decode("utf-8-sig") for line in raw)))


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    results = []
    for pack in manifest["packs"]:
        path = ASSET_ROOT / pack["asset"]
        sha256 = hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None
        present = set()
        rail_routes = 0
        error = None
        try:
            with zipfile.ZipFile(path) as archive:
                present = {entry.rsplit("/", 1)[-1] for entry in archive.namelist() if not entry.endswith("/")}
                rail_routes = sum(1 for row in rows(archive, "routes.txt") if row.get("route_type", "").strip() in RAIL_TYPES)
        except Exception as exc:  # Report di verifica, non propagare un dettaglio singolo.
            error = str(exc)
        results.append({
            "source_id": pack["sourceId"],
            "asset": pack["asset"],
            "bytes": path.stat().st_size if path.exists() else 0,
            "sha256_valid": sha256 == pack.get("sha256"),
            "required_files_valid": REQUIRED.issubset(present),
            "rail_routes": rail_routes,
            "error": error,
        })
    out = ROOT / "docs/data-sources/embedded_gtfs_coverage_2026-08-15.json"
    out.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    failures = [item for item in results if not item["sha256_valid"] or not item["required_files_valid"] or item["error"]]
    print(json.dumps({"packs": len(results), "failures": len(failures), "output": str(out)}, ensure_ascii=False))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
