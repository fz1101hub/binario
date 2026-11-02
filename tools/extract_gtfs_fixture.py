#!/usr/bin/env python3
"""Cut a small, reviewable GTFS test fixture out of a complete feed.

Keeps the trips with the given public numbers (trip_short_name) that run on one
service date, with exactly the rows they reference: their stop_times, stops (and
parent stations), routes, agencies and the complete calendar/calendar_dates rows
of their services. feed_info.txt and attributions.txt are copied unchanged so the
fixture carries the upstream attribution. shapes.txt is never copied.

The input is untrusted data: only CSV is read, with the standard library, and
run this with ``python3 -I`` from outside the input directory.

    python3 -I tools/extract_gtfs_fixture.py --input gtfs-trenitalia.zip \\
      --output app/src/test/resources/gtfs-extracts/trenitalia-gtfs \\
      --date 2026-11-10 --numbers 17106 9721 AO042
"""
from __future__ import annotations

import argparse
import csv
import io
import zipfile
from datetime import date
from pathlib import Path

WEEKDAYS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
COPIED = ("feed_info.txt", "attributions.txt")


def read(archive: zipfile.ZipFile, name: str) -> tuple[list[str], list[dict[str, str]]]:
    if name not in archive.namelist():
        return [], []
    with archive.open(name) as raw:
        reader = csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline=""))
        rows = [dict(row) for row in reader]
        return list(reader.fieldnames or []), rows


def write(target: Path, header: list[str], rows: list[dict[str, str]]) -> None:
    with target.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=header, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def active_services(calendar: list[dict[str, str]], exceptions: list[dict[str, str]], day: date) -> set[str]:
    key = day.strftime("%Y%m%d")
    weekday = WEEKDAYS[day.weekday()]
    active = {row["service_id"] for row in calendar if row["start_date"] <= key <= row["end_date"] and row[weekday] == "1"}
    for row in exceptions:
        if row["date"] == key:
            (active.add if row["exception_type"] == "1" else active.discard)(row["service_id"])
    return active


def extract(source: Path, output: Path, day: date, numbers: list[str]) -> dict[str, int]:
    with zipfile.ZipFile(source) as archive:
        tables = {name: read(archive, name) for name in (
            "agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt", "calendar.txt", "calendar_dates.txt",
        )}
        copied = {name: archive.read(name) for name in COPIED if name in archive.namelist()}
    services = active_services(tables["calendar.txt"][1], tables["calendar_dates.txt"][1], day)
    wanted = set(numbers)
    trips = [row for row in tables["trips.txt"][1] if row.get("trip_short_name") in wanted and row["service_id"] in services]
    missing = wanted - {row["trip_short_name"] for row in trips}
    if missing:
        raise SystemExit(f"no trip running on {day} for: {', '.join(sorted(missing))}")
    trip_ids = {row["trip_id"] for row in trips}
    stop_times = [row for row in tables["stop_times.txt"][1] if row["trip_id"] in trip_ids]
    stop_ids = {row["stop_id"] for row in stop_times}
    stops_by_id = {row["stop_id"]: row for row in tables["stops.txt"][1]}
    for stop_id in list(stop_ids):
        parent = stops_by_id[stop_id].get("parent_station", "")
        if parent:
            stop_ids.add(parent)
    route_ids = {row["route_id"] for row in trips}
    routes = [row for row in tables["routes.txt"][1] if row["route_id"] in route_ids]
    agency_ids = {row.get("agency_id", "") for row in routes}
    service_ids = {row["service_id"] for row in trips}
    output.mkdir(parents=True, exist_ok=True)
    selected = {
        "agency.txt": [row for row in tables["agency.txt"][1] if row.get("agency_id", "") in agency_ids],
        "stops.txt": [row for row in tables["stops.txt"][1] if row["stop_id"] in stop_ids],
        "routes.txt": routes,
        "trips.txt": trips,
        "stop_times.txt": stop_times,
        "calendar.txt": [row for row in tables["calendar.txt"][1] if row["service_id"] in service_ids],
        "calendar_dates.txt": [row for row in tables["calendar_dates.txt"][1] if row["service_id"] in service_ids],
    }
    for name, rows in selected.items():
        write(output / name, tables[name][0], rows)
    for name, payload in copied.items():
        (output / name).write_bytes(payload)
    return {name: len(rows) for name, rows in selected.items()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--date", type=date.fromisoformat, required=True)
    parser.add_argument("--numbers", nargs="+", required=True)
    args = parser.parse_args()
    print(extract(args.input, args.output, args.date, args.numbers))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
