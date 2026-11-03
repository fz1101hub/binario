#!/usr/bin/env python3
"""Test read-only completo delle fonti catalogate da Binario.

Per i GTFS scaricabili controlla ZIP e tabelle minime senza eseguire contenuti.
Per feed realtime controlla la risposta protobuf. Per landing/HTML registra solo
stato HTTP e titolo/presenza di contenuto. Il programma non usa token, non invia
dati personali e non attiva polling.
"""
from __future__ import annotations

import csv
import io
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "docs/data-sources/source_test_matrix.csv"
OUT = ROOT / "docs/data-sources/full_source_test_2026-08-14.csv"
USER_AGENT = "Binario-source-test/0.1 (personal project; read-only)"
MAX_GTFS_BYTES = 30 * 1024 * 1024
MAX_PROBE_BYTES = 512 * 1024
MAX_SOURCE_SECONDS = 45
REQUIRED = ("agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt")


def get(url: str, max_bytes: int | None, data: bytes | None = None) -> tuple[int, str, bytes, str]:
    request = urllib.request.Request(url, data=data, headers={"User-Agent": USER_AGENT, "Accept": "application/zip,application/x-protobuf,text/html,application/xhtml+xml,*/*"})
    deadline = time.monotonic() + MAX_SOURCE_SECONDS
    with urllib.request.urlopen(request, timeout=20) as response:
        chunks: list[bytes] = []
        read = 0
        while time.monotonic() < deadline:
            chunk = response.read(min(256 * 1024, (max_bytes - read) if max_bytes else 256 * 1024))
            if not chunk:
                break
            chunks.append(chunk)
            read += len(chunk)
            if max_bytes and read >= max_bytes:
                break
        return response.status, response.url, b"".join(chunks), response.headers.get_content_type()


def count_csv(zf: zipfile.ZipFile, filename: str) -> int:
    with zf.open(filename) as member:
        return max(0, sum(1 for _ in io.TextIOWrapper(member, encoding="utf-8-sig", errors="replace")) - 1)


def gtfs_test(row: dict[str, str]) -> dict[str, str]:
    status, final_url, body, content_type = get(row["probe_url"], MAX_GTFS_BYTES)
    if len(body) >= MAX_GTFS_BYTES:
        return result(row, "LIMITED", status, final_url, len(body), content_type, "download cap reached", "OFFLINE_GATEWAY")
    if not zipfile.is_zipfile(io.BytesIO(body)):
        return result(row, "FAIL", status, final_url, len(body), content_type, "response is not a ZIP", "BLOCKED")
    with zipfile.ZipFile(io.BytesIO(body)) as zf:
        names = {Path(name).name for name in zf.namelist() if not name.endswith("/")}
        missing = [filename for filename in REQUIRED if filename not in names]
        if missing:
            return result(row, "FAIL", status, final_url, len(body), content_type, f"missing {','.join(missing)}", "BLOCKED")
        metrics = ";".join(f"{name[:-4]}={count_csv(zf, name)}" for name in ("agency.txt", "stops.txt", "routes.txt", "trips.txt"))
        signal = "rail" if re.search(r"rail|train|ferrov|treno|bahn", " ".join(zf.read("routes.txt").decode("utf-8", errors="replace").splitlines()[:500]), re.I) else "mixed_or_unknown"
        disposition = "OFFLINE_GATEWAY" if row["access_mode"] == "GATEWAY_ONLY" or len(body) > 50 * 1024 * 1024 else "CLIENT_PACK"
        return result(row, "PASS", status, final_url, len(body), content_type, f"{metrics};rail_signal={signal}", disposition)


def realtime_test(row: dict[str, str]) -> dict[str, str]:
    status, final_url, body, content_type = get(row["probe_url"], 8 * 1024 * 1024)
    passed = status == 200 and len(body) > 16 and ("protobuf" in content_type or content_type == "application/octet-stream")
    return result(row, "PASS" if passed else "FAIL", status, final_url, len(body), content_type, "protobuf payload sampled" if passed else "empty or non-protobuf response", "PROTOCOL_READY" if passed else "BLOCKED")


def page_test(row: dict[str, str]) -> dict[str, str]:
    status, final_url, body, content_type = get(row["probe_url"], MAX_PROBE_BYTES)
    text = body.decode("utf-8", errors="replace")
    title_match = re.search(r"<title[^>]*>\s*(.*?)\s*</title>", text, re.I | re.S)
    title = re.sub(r"\s+", " ", title_match.group(1)).strip()[:120] if title_match else ""
    passed = 200 <= status < 400 and bool(body)
    return result(row, "PASS" if passed else "FAIL", status, final_url, len(body), content_type, f"title={title}" if title else "page reachable", "CATALOG_ONLY" if row["test_mode"] in {"landing_page", "metadata_probe"} else "HTML_REVIEW" if passed else "BLOCKED")


def result(row: dict[str, str], outcome: str, status: int | str, final_url: str, byte_count: int, content_type: str, detail: str, disposition: str) -> dict[str, str]:
    return {
        "source_id": row["source_id"],
        "display_name": row["display_name"],
        "test_mode": row["test_mode"],
        "outcome": outcome,
        "http_status": str(status),
        "bytes": str(byte_count),
        "content_type": content_type,
        "final_url": final_url,
        "detail": detail,
        "disposition": disposition,
    }


def test_row(row: dict[str, str]) -> dict[str, str]:
    try:
        if row["test_mode"] == "gtfs_import":
            return gtfs_test(row)
        if row["test_mode"] == "gtfs_rt_probe":
            return realtime_test(row)
        return page_test(row)
    except urllib.error.HTTPError as exc:
        return result(row, "FAIL", exc.code, exc.url, 0, exc.headers.get_content_type() if exc.headers else "", "HTTPError", "BLOCKED")
    except Exception as exc:
        return result(row, "FAIL", "ERROR", "", 0, "", type(exc).__name__, "BLOCKED")


def main() -> int:
    rows = list(csv.DictReader(MATRIX.open(encoding="utf-8")))
    if len(rows) != 31:
        raise SystemExit(f"Matrix must contain 31 sources, found {len(rows)}")
    started = time.monotonic()
    results: list[dict[str, str]] = []
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = [pool.submit(test_row, row) for row in rows]
        for future in as_completed(futures):
            results.append(future.result())
    results.sort(key=lambda item: item["source_id"])
    with OUT.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(results[0]))
        writer.writeheader()
        writer.writerows(results)
    counts = {key: sum(row["outcome"] == key for row in results) for key in ("PASS", "LIMITED", "FAIL")}
    print(f"{OUT}\nsource_count={len(results)} pass={counts['PASS']} limited={counts['LIMITED']} fail={counts['FAIL']} elapsed_seconds={time.monotonic() - started:.1f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
