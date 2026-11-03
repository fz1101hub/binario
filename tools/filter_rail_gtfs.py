#!/usr/bin/env python3
"""Riduce un GTFS ai servizi ferroviari, preservando le tabelle necessarie a orari e stazioni."""
from __future__ import annotations

import argparse
import csv
import io
import json
import shutil
import sys
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path

RAIL_TYPES = {"2"} | {str(value) for value in range(100, 200)}
KEEP_OPTIONAL = ("agency.txt", "calendar.txt", "calendar_dates.txt", "feed_info.txt", "translations.txt", "attributions.txt")


def read_rows(archive: zipfile.ZipFile, name: str):
    with archive.open(name) as raw:
        yield from csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline=""))


def write_rows(path: Path, fieldnames: list[str], rows):
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        count = 0
        for row in rows:
            writer.writerow(row)
            count += 1
    return count


def fields(archive: zipfile.ZipFile, name: str) -> list[str]:
    with archive.open(name) as raw:
        return next(csv.reader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")))


def find_entry(archive: zipfile.ZipFile, basename: str) -> str | None:
    """Restituisce la tabella anche se il GTFS è racchiuso in una directory ZIP."""
    names = archive.namelist()
    if basename in names:
        return basename
    matches = [name for name in names if name.endswith("/" + basename)]
    if len(matches) == 1:
        return matches[0]
    if len(matches) > 1:
        raise RuntimeError(f"GTFS ambiguo: più tabelle {basename}")
    return None


def filter_gtfs(source: Path, output: Path, report: Path, source_id: str):
    with zipfile.ZipFile(source) as archive:
        required = {"routes.txt", "trips.txt", "stop_times.txt", "stops.txt"}
        entries = {name: find_entry(archive, name) for name in required | set(KEEP_OPTIONAL)}
        missing = {name for name in required if entries[name] is None}
        if missing:
            raise RuntimeError(f"GTFS senza tabelle richieste: {', '.join(sorted(missing))}")
        with tempfile.TemporaryDirectory(prefix="binario_rail_gtfs_") as temp:
            work = Path(temp)
            route_fields = fields(archive, entries["routes.txt"])
            route_rows = [row for row in read_rows(archive, entries["routes.txt"]) if row.get("route_type", "").strip() in RAIL_TYPES]
            route_ids = {row["route_id"] for row in route_rows if row.get("route_id")}
            if not route_ids:
                raise RuntimeError("Nessuna route ferroviaria trovata nel feed")
            route_count = write_rows(work / "routes.txt", route_fields, route_rows)

            trip_fields = fields(archive, entries["trips.txt"])
            trip_rows = [row for row in read_rows(archive, entries["trips.txt"]) if row.get("route_id") in route_ids]
            trip_ids = {row["trip_id"] for row in trip_rows if row.get("trip_id")}
            service_ids = {row.get("service_id", "") for row in trip_rows} - {""}
            trip_count = write_rows(work / "trips.txt", trip_fields, trip_rows)

            stop_time_fields = fields(archive, entries["stop_times.txt"])
            stop_ids = set()
            def filtered_stop_times():
                for row in read_rows(archive, entries["stop_times.txt"]):
                    if row.get("trip_id") in trip_ids:
                        if row.get("stop_id"):
                            stop_ids.add(row["stop_id"])
                        yield row
            stop_time_count = write_rows(work / "stop_times.txt", stop_time_fields, filtered_stop_times())

            stop_fields = fields(archive, entries["stops.txt"])
            stop_count = write_rows(work / "stops.txt", stop_fields, (row for row in read_rows(archive, entries["stops.txt"]) if row.get("stop_id") in stop_ids))

            if entries["calendar.txt"]:
                calendar_fields = fields(archive, entries["calendar.txt"])
                write_rows(work / "calendar.txt", calendar_fields, (row for row in read_rows(archive, entries["calendar.txt"]) if row.get("service_id") in service_ids))
            if entries["calendar_dates.txt"]:
                calendar_date_fields = fields(archive, entries["calendar_dates.txt"])
                write_rows(work / "calendar_dates.txt", calendar_date_fields, (row for row in read_rows(archive, entries["calendar_dates.txt"]) if row.get("service_id") in service_ids))

            for name in KEEP_OPTIONAL:
                if entries[name] and name not in {"calendar.txt", "calendar_dates.txt"}:
                    with archive.open(entries[name]) as source_file, (work / name).open("wb") as target_file:
                        shutil.copyfileobj(source_file, target_file)

            metadata = {
                "source_id": source_id,
                "filtered_at_utc": datetime.now(timezone.utc).isoformat(),
                "filter": {"route_types": sorted(RAIL_TYPES), "description": "Servizi ferroviari GTFS standard ed estesi"},
                "counts": {"routes": route_count, "trips": trip_count, "stop_times": stop_time_count, "stops": stop_count},
            }
            (work / "binario_filter_manifest.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            output.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as target:
                for path in sorted(work.iterdir()):
                    target.write(path, path.name)
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text(json.dumps({**metadata, "output_bytes": output.stat().st_size}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            return metadata


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--source-id", required=True)
    args = parser.parse_args()
    result = filter_gtfs(args.input, args.output, args.report, args.source_id)
    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__":
    main()
