#!/usr/bin/env python3
"""Smoke test read-only delle fonti live abilitate in Binario.

Il comando esegue una singola richiesta per fonte, non usa token e non scarica
asset non necessari. È un controllo manuale/CI leggero, non un poller runtime.
"""
from __future__ import annotations

import csv
import sys
import urllib.parse
import urllib.request
from dataclasses import dataclass

from bs4 import BeautifulSoup


USER_AGENT = "Binario-live-check/0.1 (personal project; read-only)"


@dataclass(frozen=True)
class CheckResult:
    source: str
    status: str
    records: int
    sample: str
    result: str
    detail: str


def fetch(request: urllib.request.Request) -> tuple[int, str]:
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.status, response.read().decode("utf-8", errors="replace")


def viaggiatreno() -> CheckResult:
    data = urllib.parse.urlencode({"stazione": "ROMA TERMINI", "lang": "IT"}).encode()
    request = urllib.request.Request(
        "http://www.viaggiatreno.it/vt_pax_internet/mobile/stazione",
        data=data,
        method="POST",
        headers={"User-Agent": USER_AGENT, "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"},
    )
    try:
        status, html = fetch(request)
        document = BeautifulSoup(html, "html.parser")
        blocks = document.select("div.bloccorisultato")
        sample = blocks[0].select_one("h2").get_text(" ", strip=True) if blocks and blocks[0].select_one("h2") else ""
        return CheckResult("viaggiatreno_station_roma_termini", str(status), len(blocks), sample, "PASS" if status == 200 and blocks else "FAIL", "")
    except Exception as error:  # Diagnostic tool only.
        return CheckResult("viaggiatreno_station_roma_termini", "ERROR", 0, "", "FAIL", type(error).__name__)


def rfi() -> CheckResult:
    request = urllib.request.Request(
        "https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=2416&arrivals=False",
        headers={"User-Agent": USER_AGENT, "Accept": "text/html,application/xhtml+xml"},
    )
    try:
        status, html = fetch(request)
        document = BeautifulSoup(html, "html.parser")
        rows = document.select('tr[name="treno"]')
        sample = rows[0].select_one("#RTreno").get_text(" ", strip=True) if rows and rows[0].select_one("#RTreno") else ""
        return CheckResult("rfi_departures_roma_termini", str(status), len(rows), sample, "PASS" if status == 200 and rows else "FAIL", "")
    except Exception as error:  # Diagnostic tool only.
        return CheckResult("rfi_departures_roma_termini", "ERROR", 0, "", "FAIL", type(error).__name__)


def main() -> int:
    rows = [viaggiatreno(), rfi()]
    writer = csv.writer(sys.stdout, delimiter="\t")
    writer.writerow(["source", "status", "records", "sample", "result", "detail"])
    for row in rows:
        writer.writerow([row.source, row.status, row.records, row.sample, row.result, row.detail])
    return 0 if all(row.result == "PASS" for row in rows) else 1


if __name__ == "__main__":
    raise SystemExit(main())
