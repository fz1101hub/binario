#!/usr/bin/env python3
"""Verifica read-only del raccordo STA GTFS-Realtime -> GTFS statico embedded."""
from __future__ import annotations

import csv
import io
import json
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "app/src/main/assets/gtfs/alto-adige-sta-gtfs.zip"
URL = "https://gtfs.api.opendatahub.com/v1/realtime/sta-time-tables/trip-updates"


def read_csv(zf: zipfile.ZipFile, suffix: str):
    name = next(name for name in zf.namelist() if name.endswith(suffix))
    return list(csv.DictReader(io.TextIOWrapper(zf.open(name), encoding="utf-8-sig")))


def main() -> int:
    request = urllib.request.Request(URL, headers={"Accept": "application/json", "User-Agent": "Binario-quality-check/1.0"})
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.loads(response.read().decode("utf-8"))
        status = response.status
    with zipfile.ZipFile(PACK) as zf:
        trip_ids = {row["trip_id"] for row in read_csv(zf, "trips.txt") if row.get("trip_id")}
        stop_times = read_csv(zf, "stop_times.txt")
    static_pairs = {(row.get("trip_id"), row.get("stop_id"), row.get("stop_sequence")) for row in stop_times}
    updates = []
    for entity in payload.get("entity", []):
        trip_update = entity.get("trip_update")
        if not trip_update:
            continue
        trip_id = trip_update.get("trip", {}).get("trip_id")
        for stop in trip_update.get("stop_time_update", []):
            updates.append((trip_id, stop.get("stop_id"), str(stop.get("stop_sequence")) if stop.get("stop_sequence") is not None else None, stop))
    trip_matches = sum(1 for trip_id, _, _, _ in updates if trip_id in trip_ids)
    stop_matches = sum(1 for trip_id, stop_id, sequence, _ in updates if (trip_id, stop_id, sequence) in static_pairs)
    actual_events = sum(1 for _, _, _, stop in updates if any(k in (stop.get("arrival") or {}) for k in ("time",)) or any(k in (stop.get("departure") or {}) for k in ("time",)))
    print("metric,value")
    print(f"http_status,{status}")
    print(f"feed_entities,{len(payload.get('entity', []))}")
    print(f"stop_updates,{len(updates)}")
    print(f"trip_id_matches,{trip_matches}")
    print(f"trip_stop_matches,{stop_matches}")
    print(f"updates_with_provider_time,{actual_events}")
    print("note,provider time is preserved as predicted unless its semantics explicitly confirm an actual event")
    return 0


if __name__ == "__main__":
    sys.exit(main())
