#!/usr/bin/env python3
"""Importa e valida percorsi alternativi GTFS senza token o API interne."""
from __future__ import annotations

import csv
import io
import subprocess
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORK = Path('/home/ubuntu/binario_gtfs_imports_extended')
OUT = ROOT / 'docs/data-sources/additional_gtfs_import_2026-08-14.csv'
REQUIRED = ('agency.txt', 'stops.txt', 'routes.txt', 'trips.txt', 'stop_times.txt')
CANDIDATES = [
    ('arst-gtfs', 'https://arstspa.info/arst-cagliari-it.zip'),
    ('trentino-urban-gtfs', 'https://www.trentinotrasporti.it/opendata/google_transit_urbano_tte.zip'),
    ('lombardia-trenord-gtfs', 'https://www.dati.lombardia.it/download/3z4k-mxz9/application/zip'),
    ('toscana-tft-gtfs', 'https://dati.toscana.it/dataset/8bb8f8fe-fe7d-41d0-90dc-49f2456180d1/resource/59aeacbc-99ee-410b-ac1e-622b5574a666/download/tft.gtfs'),
    ('oebb-gtfs-2025', 'https://static.oebb.at/open-data/soll-fahrplan-gtfs/GTFS_OP_2025_obb.zip'),
]


def count_rows(zf: zipfile.ZipFile, name: str) -> int:
    with zf.open(name) as part:
        return max(0, sum(1 for _ in io.TextIOWrapper(part, encoding='utf-8-sig', errors='replace')) - 1)


def validate(path: Path) -> tuple[str, str]:
    if not zipfile.is_zipfile(path):
        return 'FAIL', 'not_a_zip'
    with zipfile.ZipFile(path) as zf:
        names = {Path(entry).name for entry in zf.namelist() if not entry.endswith('/')}
        missing = [name for name in REQUIRED if name not in names]
        if missing:
            return 'FAIL', 'missing:' + '|'.join(missing)
        counts = ';'.join(f'{name[:-4]}={count_rows(zf, name)}' for name in ('agency.txt', 'stops.txt', 'routes.txt', 'trips.txt'))
        return 'PASS', counts


def import_one(source_id: str, url: str) -> dict[str, str]:
    WORK.mkdir(parents=True, exist_ok=True)
    path = WORK / f'{source_id}.zip'
    if path.exists():
        path.unlink()
    command = ['curl', '-fL', '--connect-timeout', '15', '--max-time', '150', '--retry', '1', '--retry-delay', '2', '-A', 'Binario-gtfs-import/0.1 (personal project; read-only)', url, '-o', str(path), '-w', '%{http_code}|%{url_effective}|%{content_type}']
    try:
        process = subprocess.run(command, capture_output=True, text=True, timeout=165)
    except subprocess.TimeoutExpired:
        bytes_written = path.stat().st_size if path.exists() else 0
        if path.exists():
            path.unlink()
        return {'source_id': source_id, 'outcome': 'LIMITED', 'http_status': 'TIMEOUT', 'bytes': str(bytes_written), 'detail': 'download_timeout', 'artifact': ''}
    metadata = process.stdout.strip().split('|') if process.stdout.strip() else []
    status = metadata[0] if metadata else 'ERROR'
    if process.returncode != 0 or not path.exists():
        return {'source_id': source_id, 'outcome': 'FAIL', 'http_status': status, 'bytes': '0', 'detail': (process.stderr.strip() or 'curl_failed')[-240:], 'artifact': ''}
    outcome, detail = validate(path)
    return {'source_id': source_id, 'outcome': outcome, 'http_status': status, 'bytes': str(path.stat().st_size), 'detail': detail, 'artifact': str(path) if outcome == 'PASS' else ''}


def main() -> None:
    results = []
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(import_one, source_id, url) for source_id, url in CANDIDATES]
        for future in as_completed(futures):
            results.append(future.result())
    results.sort(key=lambda row: row['source_id'])
    with OUT.open('w', newline='', encoding='utf-8') as target:
        writer = csv.DictWriter(target, fieldnames=list(results[0]))
        writer.writeheader()
        writer.writerows(results)
    print(OUT)
    for row in results:
        print('\t'.join(row[field] for field in ('source_id', 'outcome', 'http_status', 'bytes', 'detail')))


if __name__ == '__main__':
    main()
