#!/usr/bin/env python3
"""Controllo read-only degli endpoint open/testabili di Binario.

Non scarica feed completi: invia richieste HEAD e registra status, tipo MIME,
dimensione e ultima modifica. Le fonti best-effort, gateway e agreement-only
sono volutamente assenti.
"""
from __future__ import annotations

import csv
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class SourceProbe:
    source_id: str
    url: str
    expected_types: tuple[str, ...]


SOURCES = (
    SourceProbe("arst-gtfs", "https://arstspa.info/arst-cagliari-it.zip", ("application/zip",)),
    SourceProbe("trentino-gtfs", "https://www.trentinotrasporti.it/opendata/google_transit_extraurbano_tte.zip", ("application/zip",)),
    SourceProbe("alto-adige-sta-gtfs", "https://gtfs.api.opendatahub.com/v1/dataset/sta-time-tables/raw", ("application/zip",)),
    SourceProbe("hzpp-gtfs", "https://www.hzpp.hr/GTFS_files.zip", ("application/zip",)),
    SourceProbe("sncf-gtfs", "https://eu.ftp.opendatasoft.com/sncf/plandata/Export_OpenData_SNCF_GTFS_NewTripId.zip", ("application/zip",)),
    SourceProbe("sncf-gtfs-rt-trip-updates", "https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-trip-updates", ("application/x-protobuf", "application/octet-stream")),
    SourceProbe("sncf-gtfs-rt-alerts", "https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-service-alerts", ("application/x-protobuf", "application/octet-stream")),
    SourceProbe("oebb-gtfs", "https://static.web.oebb.at/open-data/soll-fahrplan-gtfs/GTFS_Fahrplan_2026.zip", ("application/zip",)),
)


def probe(source: SourceProbe) -> dict[str, str]:
    request = urllib.request.Request(
        source.url,
        method="HEAD",
        headers={"User-Agent": "Binario-source-check/0.1 (personal project; read-only)"},
    )
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            mime_type = response.headers.get_content_type().lower()
            expected = mime_type in source.expected_types
            return {
                "source_id": source.source_id,
                "status": str(response.status),
                "content_type": mime_type,
                "content_length": response.headers.get("Content-Length", ""),
                "last_modified": response.headers.get("Last-Modified", ""),
                "result": "PASS" if 200 <= response.status < 300 and expected else "FAIL",
                "detail": "",
            }
    except urllib.error.HTTPError as error:
        return {
            "source_id": source.source_id,
            "status": str(error.code),
            "content_type": error.headers.get_content_type() if error.headers else "",
            "content_length": "",
            "last_modified": "",
            "result": "FAIL",
            "detail": "HTTPError",
        }
    except Exception as error:  # Diagnostic tool: preserve a compact safe class name.
        return {
            "source_id": source.source_id,
            "status": "ERROR",
            "content_type": "",
            "content_length": "",
            "last_modified": "",
            "result": "FAIL",
            "detail": type(error).__name__,
        }


def main() -> int:
    rows = [probe(source) for source in SOURCES]
    writer = csv.DictWriter(sys.stdout, fieldnames=rows[0].keys(), delimiter="\t")
    writer.writeheader()
    writer.writerows(rows)
    return 0 if all(row["result"] == "PASS" for row in rows) else 1


if __name__ == "__main__":
    raise SystemExit(main())
