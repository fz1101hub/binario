#!/usr/bin/env python3
"""Genera la matrice di test per tutte le fonti dichiarate nel catalogo Binario."""
from __future__ import annotations

import csv
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/java/it/binario/app/data/sources/RailSourceCatalog.kt"
OUT = ROOT / "docs/data-sources/source_test_matrix.csv"


def extract(field: str, block: str) -> str:
    match = re.search(rf'{re.escape(field)}\s*=\s*(?:uri\("([^"]+)"\)|SourceId\("([^"]+)"\)|"([^"]+)"|([A-Z_]+))', block)
    return next((group for group in match.groups() if group), "") if match else ""


def enum_value(field: str, enum_type: str, block: str) -> str:
    match = re.search(rf'{re.escape(field)}\s*=\s*{re.escape(enum_type)}\.([A-Z_]+)', block)
    return match.group(1) if match else ""


def source_blocks(text: str) -> list[str]:
    marker = "        RailSourceDefinition("
    parts = text.split(marker)[1:]
    return [part.split("\n        ),", 1)[0] for part in parts]


def test_mode(formats: str, access: str, readiness: str, probe_url: str) -> tuple[str, str]:
    if readiness in {"AGREEMENT_REQUIRED", "MONITOR_ONLY"}:
        return "landing_page", "Catalogare raggiungibilità e non tentare import senza feed/accordo"
    if not probe_url:
        return "landing_page", "Verificare pagina del titolare e segnalare URL di download assente"
    if "GTFS_ZIP" in formats and readiness in {"READY_FOR_GTFS_PACK", "IMPORT_VIA_GATEWAY"}:
        return "gtfs_import", "Scaricare e verificare ZIP, file GTFS minimi, agenzie, fermate e viaggi"
    if "GTFS_REALTIME_PROTOBUF" in formats:
        return "gtfs_rt_probe", "Scaricare payload protobuf e verificarne intestazione/campi feed"
    if readiness == "LANDING_PAGE_ONLY":
        return "metadata_probe", "Verificare catalogo/landing page e ricercare una resource URL pubblica"
    return "html_probe", "Verificare risposta pubblica e presenza del contenuto dichiarato"


def main() -> None:
    text = CATALOG.read_text(encoding="utf-8")
    rows: list[dict[str, str]] = []
    for block in source_blocks(text):
        source_id = extract("id", block)
        if not source_id:
            continue
        display_name = extract("displayName", block)
        formats_match = re.search(r'formats\s*=\s*setOf\(([^)]*)\)', block)
        formats = (formats_match.group(1).replace("SourceFormat.", "").replace(" ", "") if formats_match else "")
        access = enum_value("accessMode", "AccessMode", block)
        readiness = enum_value("readiness", "SourceReadiness", block)
        landing = extract("landingPage", block)
        probe = extract("downloadOrProbeUri", block)
        mode, purpose = test_mode(formats, access, readiness, probe)
        rows.append({
            "source_id": source_id,
            "display_name": display_name,
            "formats": formats,
            "access_mode": access,
            "readiness": readiness,
            "landing_page": landing,
            "probe_url": probe or landing,
            "test_mode": mode,
            "test_purpose": purpose,
        })
    if len(rows) != 31:
        raise SystemExit(f"Attese 31 fonti, trovate {len(rows)}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(f"{OUT}\nfonti={len(rows)}")
    for mode in sorted({row['test_mode'] for row in rows}):
        print(f"{mode}={sum(row['test_mode'] == mode for row in rows)}")


if __name__ == "__main__":
    main()
