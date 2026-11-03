#!/usr/bin/env python3
"""Smoke test read-only delle fonti live ufficiali integrate in Binario."""
from __future__ import annotations

import csv
import json
import time
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/data-sources/quality_smoke_test_2026-08-14.csv'
SOURCES = [
    ('rfi_infomobility_rss', 'https://www.rfi.it/content/rfi/it/news-e-media/infomobilita.rss.updates.xml', 'xml_rss'),
    ('sta_trip_updates', 'https://gtfs.api.opendatahub.com/v1/realtime/sta-time-tables/trip-updates', 'json_gtfsrt'),
    ('sta_vehicle_positions', 'https://gtfs.api.opendatahub.com/v1/realtime/sta-time-tables/vehicle-positions', 'json_gtfsrt'),
    ('sta_service_alerts', 'https://gtfs.api.opendatahub.com/v1/realtime/sta-time-tables/service-alerts', 'json_gtfsrt'),
    ('rfi_station_board', 'https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=2416&arrivals=False', 'html_board'),
    ('viaggiatreno_station_board', 'http://www.viaggiatreno.it/vt_pax_internet/mobile/stazione', 'html_board_post'),
]


def request(url: str, method: str = 'GET', body: bytes | None = None) -> tuple[int, bytes, float]:
    start = time.monotonic()
    req = urllib.request.Request(url, data=body, method=method, headers={
        'Accept': 'application/json, application/rss+xml, application/xml, text/html',
        'User-Agent': 'Binario-quality-smoke/0.1 (personal project; read-only)'
    })
    with urllib.request.urlopen(req, timeout=45) as response:
        return response.status, response.read(), round((time.monotonic() - start) * 1000)


def inspect(kind: str, body: bytes) -> tuple[str, str]:
    if kind == 'xml_rss':
        root = ET.fromstring(body)
        return 'PASS', f'items={len(root.findall("./channel/item"))}'
    if kind == 'json_gtfsrt':
        root = json.loads(body.decode('utf-8'))
        timestamp = root.get('header', {}).get('timestamp')
        return ('PASS' if root.get('entity') is not None and timestamp else 'FAIL'), f'entities={len(root.get("entity", []))};header_timestamp={timestamp}'
    if kind == 'html_board':
        return ('PASS' if b'<tr' in body.lower() else 'FAIL'), f'bytes={len(body)}'
    if kind == 'html_board_post':
        return ('PASS' if b'bloccorisultato' in body.lower() else 'FAIL'), f'bytes={len(body)}'
    return 'FAIL', 'unknown_kind'


def main() -> None:
    rows = []
    for source_id, url, kind in SOURCES:
        try:
            method = 'POST' if kind == 'html_board_post' else 'GET'
            body = b'stazione=Roma+Termini&lang=IT' if method == 'POST' else None
            status, payload, latency = request(url, method, body)
            outcome, detail = inspect(kind, payload)
            rows.append({'source_id': source_id, 'outcome': outcome, 'http_status': status, 'latency_ms': latency, 'detail': detail, 'checked_at_utc': datetime.now(timezone.utc).isoformat()})
        except Exception as error:
            rows.append({'source_id': source_id, 'outcome': 'FAIL', 'http_status': 'ERROR', 'latency_ms': '', 'detail': type(error).__name__, 'checked_at_utc': datetime.now(timezone.utc).isoformat()})
    with OUT.open('w', newline='', encoding='utf-8') as target:
        writer = csv.DictWriter(target, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    for row in rows:
        print('\t'.join(str(value) for value in row.values()))

if __name__ == '__main__':
    main()
