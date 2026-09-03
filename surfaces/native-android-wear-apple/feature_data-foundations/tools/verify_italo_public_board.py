#!/usr/bin/env python3
"""Verifica read-only delle pagine pubbliche Italo in Viaggio usate da Binario."""
from __future__ import annotations

import csv
import re
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/data-sources/italo_public_board_probe_2026-08-15.csv"
STATIONS = {
    "roma-termini": "roma-termini",
    "roma-tiburtina": "roma-tiburtina",
    "napoli-centrale": "napoli-centrale",
    "firenze-smn": "firenze-santa-maria-novella",
    "milano-centrale": "milano-centrale",
    "torino-pn": "torino-porta-nuova",
    "venezia-sl": "venezia-santa-lucia",
}


def get(url: str) -> tuple[int, bytes, int]:
    started = time.monotonic()
    request = urllib.request.Request(url, headers={
        "Accept": "text/html,application/xhtml+xml",
        "User-Agent": "Binario-italo-smoke/0.1 (personal project; read-only)",
    })
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.status, response.read(), round((time.monotonic() - started) * 1000)


def main() -> None:
    rows = []
    for station_id, slug in STATIONS.items():
        url = f"https://italoinviaggio.italotreno.com/it/stazione/{slug}"
        try:
            status, payload, latency = get(url)
            html = payload.decode("utf-8", "replace")
            title = re.search(r"<title>(.*?)</title>", html, re.I | re.S)
            title_text = re.sub(r"\s+", " ", title.group(1)).strip() if title else ""
            page_not_found = "PageNotFound" in title_text
            realtime_marker = "aggiornati in tempo reale" in html.lower()
            empty_board = "non ci sono treni in circolazione" in html.lower()
            rows.append({
                "station_id": station_id,
                "slug": slug,
                "outcome": "PASS" if status == 200 and not page_not_found and realtime_marker else "FAIL",
                "http_status": status,
                "latency_ms": latency,
                "empty_board": str(empty_board).lower(),
                "title": title_text,
                "checked_at_utc": datetime.now(timezone.utc).isoformat(),
            })
        except Exception as error:
            rows.append({
                "station_id": station_id,
                "slug": slug,
                "outcome": "FAIL",
                "http_status": "ERROR",
                "latency_ms": "",
                "empty_board": "",
                "title": type(error).__name__,
                "checked_at_utc": datetime.now(timezone.utc).isoformat(),
            })
    with OUT.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    for row in rows:
        print("\t".join(str(value) for value in row.values()))

if __name__ == "__main__":
    main()
