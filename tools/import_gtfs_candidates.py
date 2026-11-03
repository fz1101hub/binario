#!/usr/bin/env python3
"""Import di prova dei feed GTFS candidati.

Scarica gli otto feed diretti con curl, conserva gli artefatti fuori dal
versionamento, verifica ZIP/CSV e registra metriche. Non esegue alcun contenuto
scaricato e non incorpora token.
"""
from __future__ import annotations

import csv
import io
import shutil
import subprocess
import tempfile
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORK = Path("/home/ubuntu/binario_gtfs_imports")
OUT = ROOT / "docs/data-sources/gtfs_import_test_2026-08-14.csv"
REQUIRED = ("agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt")
CANDIDATES = [
    ("arst-gtfs", "https://arstspa.info/arst-cagliari-it.zip"),
    ("trentino-gtfs", "https://www.trentinotrasporti.it/opendata/google_transit_extraurbano_tte.zip"),
    ("alto-adige-sta-gtfs", "https://gtfs.api.opendatahub.com/v1/dataset/sta-time-tables/raw"),
    ("amt-genova-casella-gtfs", "http://opendatagoogletransit.comune.genova.it/downloads/google_transit.zip"),
    ("cotral-ferro-gtfs", "http://travel.mob.cotralspa.it:7777/GTFS/GTFS_FERRO.zip"),
    ("hzpp-gtfs", "https://www.hzpp.hr/GTFS_files.zip"),
    ("sncf-gtfs", "https://eu.ftp.opendatasoft.com/sncf/plandata/Export_OpenData_SNCF_GTFS_NewTripId.zip"),
    ("oebb-gtfs", "https://static.web.oebb.at/open-data/soll-fahrplan-gtfs/GTFS_Fahrplan_2026.zip"),
]


def count_rows(zf: zipfile.ZipFile, name: str) -> int:
    with zf.open(name) as member:
        return max(0, sum(1 for _ in io.TextIOWrapper(member, encoding="utf-8-sig", errors="replace")) - 1)


def inspect(path: Path) -> tuple[str, str]:
    if not zipfile.is_zipfile(path):
        return "FAIL", "not_a_zip"
    with zipfile.ZipFile(path) as zf:
        names = {Path(name).name for name in zf.namelist() if not name.endswith("/")}
        missing = [name for name in REQUIRED if name not in names]
        if missing:
            return "FAIL", f"missing:{'|'.join(missing)}"
        metrics = ";".join(f"{name[:-4]}={count_rows(zf, name)}" for name in ("agency.txt", "stops.txt", "routes.txt", "trips.txt"))
        return "PASS", metrics


def run(source_id: str, url: str) -> dict[str, str]:
    path = WORK / f"{source_id}.zip"
    WORK.mkdir(parents=True, exist_ok=True)
    if path.exists():
        path.unlink()
    command = [
        "curl", "-fL", "--connect-timeout", "15", "--max-time", "180",
        "--retry", "1", "--retry-delay", "2", "-A", "Binario-gtfs-import/0.1 (personal project; read-only)",
        url, "-o", str(path), "-w", "%{http_code}|%{url_effective}|%{content_type}",
    ]
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=195)
    except subprocess.TimeoutExpired:
        partial_bytes = path.stat().st_size if path.exists() else 0
        if path.exists():
            path.unlink()
        return {"source_id": source_id, "outcome": "LIMITED", "http_status": "TIMEOUT", "bytes": str(partial_bytes), "final_url": url, "content_type": "", "detail": "download_timeout", "artifact": ""}
    meta = completed.stdout.strip().split("|") if completed.stdout.strip() else []
    http_status = meta[0] if meta else "ERROR"
    final_url = meta[1] if len(meta) > 1 else ""
    content_type = meta[2] if len(meta) > 2 else ""
    if completed.returncode != 0 or not path.exists():
        return {"source_id": source_id, "outcome": "FAIL", "http_status": http_status, "bytes": "0", "final_url": final_url, "content_type": content_type, "detail": (completed.stderr.strip() or "curl_failed")[-300:], "artifact": ""}
    outcome, detail = inspect(path)
    return {"source_id": source_id, "outcome": outcome, "http_status": http_status, "bytes": str(path.stat().st_size), "final_url": final_url, "content_type": content_type, "detail": detail, "artifact": str(path)}


def main() -> int:
    results = []
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(run, source_id, url) for source_id, url in CANDIDATES]
        for future in as_completed(futures):
            results.append(future.result())
    results.sort(key=lambda row: row["source_id"])
    with OUT.open("w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=list(results[0]))
        writer.writeheader()
        writer.writerows(results)
    print(OUT)
    for result in results:
        print("\t".join(result[key] for key in ("source_id", "outcome", "http_status", "bytes", "detail")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
