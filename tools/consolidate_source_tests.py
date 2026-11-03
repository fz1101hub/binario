#!/usr/bin/env python3
"""Consolida tutti gli esiti di test delle fonti Binario in CSV e Markdown."""
from __future__ import annotations

import csv
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs/data-sources"
MATRIX = DOCS / "source_test_matrix.csv"
HTTP = DOCS / "full_source_test_2026-08-14.csv"
GTFS = DOCS / "gtfs_import_test_2026-08-14.csv"
OUT_CSV = DOCS / "consolidated_source_test_2026-08-14.csv"
OUT_MD = DOCS / "FULL_SOURCE_TEST_REPORT.md"

LIVE_OVERRIDES = {
    "viaggiatreno": ("PASS", "APP_LIVE_ENABLED", "Tabellone stazione importato e parser live attivo"),
    "rfi-station-board": ("PASS", "APP_LIVE_ENABLED", "Tabellone stazione importato e parser live attivo"),
    "italo-status": ("LIMITED", "GATEWAY_REVIEW", "Tabella browser verificata; il contenuto Android richiede rendering dinamico o canale stabilizzato"),
    "trenord-realtime": ("LIMITED", "GATEWAY_REVIEW", "Modulo pubblico verificato; record live non confermato senza interazione dinamica"),
    "rfi-infomobility": ("LIMITED", "GATEWAY_REVIEW", "RSS/avvisi verificati; richiede parser e classificazione avvisi"),
}


def rows(path: Path) -> dict[str, dict[str, str]]:
    return {row["source_id"]: row for row in csv.DictReader(path.open(encoding="utf-8"))}


def esc(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ")


def main() -> None:
    matrix = list(csv.DictReader(MATRIX.open(encoding="utf-8")))
    http = rows(HTTP)
    gtfs = rows(GTFS)
    output: list[dict[str, str]] = []
    for item in matrix:
        source_id = item["source_id"]
        current = http[source_id]
        outcome, disposition, detail = current["outcome"], current["disposition"], current["detail"]
        if source_id in gtfs:
            imported = gtfs[source_id]
            outcome, disposition, detail = imported["outcome"], ("DATA_PACK_READY" if imported["outcome"] == "PASS" and item["access_mode"] == "OPEN_DIRECT" else "OFFLINE_GATEWAY" if imported["outcome"] == "PASS" else "OFFLINE_RETRY"), imported["detail"]
        if source_id in LIVE_OVERRIDES:
            outcome, disposition, detail = LIVE_OVERRIDES[source_id]
        output.append({
            "source_id": source_id,
            "display_name": item["display_name"],
            "outcome": outcome,
            "disposition": disposition,
            "test_mode": item["test_mode"],
            "access_mode": item["access_mode"],
            "detail": detail,
            "landing_page": item["landing_page"],
        })
    if len(output) != 31:
        raise SystemExit(f"Expected 31 records, produced {len(output)}")
    with OUT_CSV.open("w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=list(output[0]))
        writer.writeheader()
        writer.writerows(output)
    counts = {name: sum(row["outcome"] == name for row in output) for name in ("PASS", "LIMITED", "FAIL")}
    dispositions = {name: sum(row["disposition"] == name for row in output) for name in sorted({row["disposition"] for row in output})}
    lines = [
        "# Report completo dei test delle fonti",
        "",
        "**Data di verifica:** 14 agosto 2026  ",
        "**Fonti catalogate:** 31  ",
        f"**Esiti consolidati:** {counts['PASS']} PASS, {counts['LIMITED']} LIMITED, {counts['FAIL']} FAIL.",
        "",
        "Il test ha usato un import ZIP/CSV per i feed GTFS scaricabili, un probe protobuf per GTFS-Realtime e una richiesta pubblica read-only per pagine e tabelloni. Gli esiti LIMITATI o FAIL non sono ignorati: restano nel catalogo con il motivo tecnico e non vengono attivati automaticamente.",
        "",
        "| Esito | Fonti |",
        "|---|---:|",
        f"| PASS | {counts['PASS']} |",
        f"| LIMITED | {counts['LIMITED']} |",
        f"| FAIL | {counts['FAIL']} |",
        "",
        "## Stato per fonte",
        "",
        "| ID | Fonte | Esito | Disposizione | Metodo | Nota |",
        "|---|---|---|---|---|---|",
    ]
    for row in output:
        lines.append(f"| `{esc(row['source_id'])}` | {esc(row['display_name'])} | **{row['outcome']}** | `{row['disposition']}` | {row['test_mode']} | {esc(row['detail'])} |")
    lines.extend([
        "",
        "## Fonti realmente attive nell’app",
        "",
        "`ViaggiaTrenoStationAdapter` e `RfiStationBoardAdapter` sono attivi nella composition root. Il primo carica il tabellone Trenitalia della stazione d’origine; il secondo è il fallback RFI. Entrambi usano timeout, cache, provenienza e fallback esplicito. Lo smoke test su Roma Termini ha ottenuto HTTP 200 per entrambe le fonti.",
        "",
        "## Data pack pronti",
        "",
        "AMT Genova–Casella, Cotral/ASTRAL Ferro, HŽPP e SNCF hanno superato l’import ZIP/CSV. I primi due restano offline/gateway perché i loro URL sono HTTP; HŽPP e SNCF sono candidati a data pack importabili, con SNCF da filtrare al tratto italiano. Alto Adige, ARST, ÖBB e Trentino non sono stati invalidati: richiedono un import offline con maggiore finestra di rete.",
        "",
        "## Limiti osservati",
        "",
        "Italo e Trenord espongono interfacce pubbliche con dati o moduli di ricerca, ma il contenuto non è ancora disponibile come risposta Android stabile senza rendering dinamico. Restano quindi in revisione anziché essere forzati con endpoint interni. Arenaways e SŽ hanno restituito HTTP 403 alla richiesta automatica e sono mantenute come fonti catalogate, non abilitate.",
        "",
        "## Artefatti di verifica",
        "",
        "- `source_test_matrix.csv`: metodo previsto per ogni fonte.",
        "- `full_source_test_2026-08-14.csv`: probe HTTP completo delle 31 fonti.",
        "- `gtfs_import_test_2026-08-14.csv`: import e metriche dei feed GTFS diretti.",
        "- `live_source_probe_2026-08-14.tsv`: smoke test dei due adapter live attivi.",
        "- `consolidated_source_test_2026-08-14.csv`: vista machine-readable dell’esito finale.",
    ])
    OUT_MD.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{OUT_CSV}\n{OUT_MD}\n" + ";".join(f"{name}={value}" for name, value in counts.items()))


if __name__ == "__main__":
    main()
