#!/usr/bin/env python3
"""Compare a GTFS archive produced by ``tools/netex_to_gtfs.py`` with a reference GTFS of the same timetable.

Used to validate the NeTEx converter on Trenitalia's National Access Point dataset against the Trenitalia pack the
app already embeds (``app/src/main/assets/gtfs/trenitalia-gtfs.zip``, from a different converter).  Trips are
paired by public number (trip_short_name) and first departure time; for every pair the stop sequence (by stop_code,
or stop_id when a stop has no code), the times and the service days over a date range are compared.  Standard
library only; run with ``python3 -I``.

    python3 -I tools/compare_gtfs.py CANDIDATE.zip REFERENCE.zip --from 2026-11-03 --days 42
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import random
import sys
import zipfile
from collections import Counter, defaultdict
from datetime import date, timedelta
from pathlib import Path
from typing import Iterator

WEEKDAYS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")


def rows(archive: zipfile.ZipFile, name: str) -> Iterator[dict[str, str]]:
    member = next((entry for entry in archive.namelist() if entry.rsplit("/", 1)[-1] == name), None)
    if member is None:
        return iter(())
    raw = archive.open(member)
    return csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline=""))


def parse_day(value: str) -> date:
    return date(int(value[:4]), int(value[4:6]), int(value[6:8]))


class Feed:
    def __init__(self, path: Path, start: date, days: int) -> None:
        with zipfile.ZipFile(path) as archive:
            self.codes = {row["stop_id"]: (row.get("stop_code") or row["stop_id"]).strip() for row in rows(archive, "stops.txt")}
            self.stations = sum(1 for row in rows(archive, "stops.txt") if row.get("location_type", "").strip() == "1")
            self.route_type = {row["route_id"]: row.get("route_type", "") for row in rows(archive, "routes.txt")}
            self.trips = {row["trip_id"]: row for row in rows(archive, "trips.txt")}
            self.times: dict[str, list[tuple[int, str, str, str]]] = defaultdict(list)
            self.stop_time_rows = 0
            for row in rows(archive, "stop_times.txt"):
                self.stop_time_rows += 1
                self.times[row["trip_id"]].append((int(row["stop_sequence"]), self.codes.get(row["stop_id"], row["stop_id"]),
                                                   row["arrival_time"][:5], row["departure_time"][:5]))
            window = [start + timedelta(days=offset) for offset in range(days)]
            active: dict[str, set[date]] = defaultdict(set)
            for row in rows(archive, "calendar.txt"):
                first, last = parse_day(row["start_date"]), parse_day(row["end_date"])
                for day in window:
                    if first <= day <= last and row[WEEKDAYS[day.weekday()]] == "1":
                        active[row["service_id"]].add(day)
            for row in rows(archive, "calendar_dates.txt"):
                day = parse_day(row["date"])
                if day not in window:
                    continue
                if row["exception_type"].strip() == "1":
                    active[row["service_id"]].add(day)
                else:
                    active[row["service_id"]].discard(day)
            self.active = active
        for values in self.times.values():
            values.sort()

    def key(self, trip_id: str) -> tuple[str, str] | None:
        stops = self.times.get(trip_id)
        if not stops:
            return None
        return (self.trips[trip_id].get("trip_short_name", "").strip(), stops[0][3])

    def days(self, trip_id: str) -> set[date]:
        return self.active.get(self.trips[trip_id]["service_id"], set())

    def describe(self, trip_id: str) -> str:
        stops = self.times[trip_id]
        return " ".join(f"{code}@{arrival}/{departure}" for _, code, arrival, departure in stops[:6]) + (" …" if len(stops) > 6 else "")


def compare(candidate: Feed, reference: Feed, *, seed: int = 7) -> dict[str, object]:
    by_key_candidate: dict[tuple[str, str], list[str]] = defaultdict(list)
    for trip_id in candidate.trips:
        key = candidate.key(trip_id)
        if key:
            by_key_candidate[key].append(trip_id)
    matched: list[tuple[str, str]] = []
    unmatched_reference: list[str] = []
    used: set[str] = set()
    for trip_id in reference.trips:
        key = reference.key(trip_id)
        options = [option for option in by_key_candidate.get(key, []) if option not in used] if key else []
        if not options:
            unmatched_reference.append(trip_id)
            continue
        # Same number and departure: prefer the candidate with the same service days in the window.
        best = max(options, key=lambda option: len(candidate.days(option) & reference.days(trip_id)))
        used.add(best)
        matched.append((best, trip_id))
    same_stops = sum(1 for mine, theirs in matched if [s[1] for s in candidate.times[mine]] == [s[1] for s in reference.times[theirs]])
    same_times = sum(1 for mine, theirs in matched if [s[1:] for s in candidate.times[mine]] == [s[1:] for s in reference.times[theirs]])
    same_days = sum(1 for mine, theirs in matched if candidate.days(mine) == reference.days(theirs))
    day_counts_reference: Counter[date] = Counter()
    day_counts_candidate: Counter[date] = Counter()
    for trip_id in reference.trips:
        for day in reference.days(trip_id):
            day_counts_reference[day] += 1
    for trip_id in candidate.trips:
        for day in candidate.days(trip_id):
            day_counts_candidate[day] += 1
    randomizer = random.Random(seed)
    samples = randomizer.sample(matched, min(5, len(matched)))
    differing = [(mine, theirs) for mine, theirs in matched if [s[1:] for s in candidate.times[mine]] != [s[1:] for s in reference.times[theirs]]]
    return {
        "counts": {
            "candidate": {"trips": len(candidate.trips), "stopTimes": candidate.stop_time_rows, "stations": candidate.stations},
            "reference": {"trips": len(reference.trips), "stopTimes": reference.stop_time_rows, "stations": reference.stations},
        },
        "matchedTrips": len(matched),
        "referenceMatchedShare": round(len(matched) / max(len(reference.trips), 1), 4),
        "candidateUnmatched": len(candidate.trips) - len(matched),
        "referenceUnmatched": len(unmatched_reference),
        "sameStopSequenceShare": round(same_stops / max(len(matched), 1), 4),
        "sameTimesShare": round(same_times / max(len(matched), 1), 4),
        "sameServiceDaysShare": round(same_days / max(len(matched), 1), 4),
        "tripsPerDay": [
            {"date": day.isoformat(), "candidate": day_counts_candidate.get(day, 0), "reference": day_counts_reference.get(day, 0)}
            for day in sorted(set(day_counts_reference) | set(day_counts_candidate))[:: max(1, len(set(day_counts_reference)) // 12)]
        ],
        "samples": [
            {"number": candidate.trips[mine].get("trip_short_name"), "candidate": candidate.describe(mine), "reference": reference.describe(theirs)}
            for mine, theirs in samples
        ],
        "differences": [
            {"number": candidate.trips[mine].get("trip_short_name"), "candidate": candidate.describe(mine), "reference": reference.describe(theirs)}
            for mine, theirs in differing[:5]
        ],
        "unmatchedReferenceSamples": [
            {"number": reference.trips[trip_id].get("trip_short_name"), "reference": reference.describe(trip_id) if reference.times.get(trip_id) else ""}
            for trip_id in unmatched_reference[:5]
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("reference", type=Path)
    parser.add_argument("--from", dest="start", type=date.fromisoformat, required=True)
    parser.add_argument("--days", type=int, default=42)
    args = parser.parse_args(argv)
    result = compare(Feed(args.candidate, args.start, args.days), Feed(args.reference, args.start, args.days))
    print(json.dumps(result, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
