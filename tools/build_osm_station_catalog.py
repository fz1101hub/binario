#!/usr/bin/env python3
"""Build a discovery-only Italian rail-station catalog from a saved OSM/Overpass response.

The output retains OSM attribution metadata and deliberately contains no timetable,
platform, delay, cancellation, or realtime field. It is an autocomplete catalog only.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import unicodedata
from collections.abc import Iterable
from datetime import UTC, datetime
from pathlib import Path


SOURCE_URL = "https://www.openstreetmap.org/copyright"
LICENSE_URL = "https://opendatacommons.org/licenses/odbl/1-0/"
ATTRIBUTION = "© OpenStreetMap contributors"


def normalized(value: str) -> str:
    decomposed = unicodedata.normalize("NFD", value)
    without_marks = "".join(char for char in decomposed if unicodedata.category(char) != "Mn")
    return re.sub(r"[^a-z0-9]+", " ", without_marks.lower()).strip()


def element_location(element: dict[str, object]) -> tuple[float | None, float | None]:
    if isinstance(element.get("lat"), (int, float)) and isinstance(element.get("lon"), (int, float)):
        return float(element["lat"]), float(element["lon"])
    center = element.get("center")
    if isinstance(center, dict) and isinstance(center.get("lat"), (int, float)) and isinstance(center.get("lon"), (int, float)):
        return float(center["lat"]), float(center["lon"])
    return None, None


def candidate_stations(elements: Iterable[dict[str, object]]) -> list[dict[str, object]]:
    candidates: dict[str, dict[str, object]] = {}
    for element in elements:
        tags = element.get("tags")
        if not isinstance(tags, dict):
            continue
        railway = str(tags.get("railway", ""))
        name = str(tags.get("name", "")).strip()
        if railway not in {"station", "halt"} or not name:
            continue
        if any(key in tags for key in ("disused:railway", "abandoned:railway", "razed:railway")):
            continue
        if str(tags.get("station", "")).lower() in {"freight", "yard"} or str(tags.get("railway:traffic_mode", "")).lower() == "freight":
            continue
        latitude, longitude = element_location(element)
        element_type = str(element.get("type", "node"))
        native_id = str(element.get("id", ""))
        if not native_id:
            continue
        candidate = {
            "id": f"osm:{element_type}:{native_id}",
            "name": name,
            "city": str(tags.get("addr:city") or tags.get("is_in:city") or "Italia"),
            "railway": railway,
            "latitude": latitude,
            "longitude": longitude,
            "source": "OpenStreetMap",
        }
        key = normalized(name)
        existing = candidates.get(key)
        # Prefer a station over a halt and a node over a geometry duplicate.
        rank = (0 if railway == "station" else 1, 0 if element_type == "node" else 1, candidate["id"])
        existing_rank = (
            0 if existing and existing["railway"] == "station" else 1,
            0 if existing and str(existing["id"]).startswith("osm:node:") else 1,
            str(existing["id"]) if existing else "~",
        )
        if existing is None or rank < existing_rank:
            candidates[key] = candidate
    return sorted(candidates.values(), key=lambda station: normalized(str(station["name"])))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-date", required=True, help="ISO-8601 date/time of the OSM query")
    args = parser.parse_args()

    raw = json.loads(args.input.read_text(encoding="utf-8"))
    elements = raw.get("elements", [])
    if not isinstance(elements, list):
        raise SystemExit("OSM response has no elements list")
    stations = candidate_stations(element for element in elements if isinstance(element, dict))
    if not stations:
        raise SystemExit("No eligible railway stations found")
    payload = {
        "schemaVersion": 1,
        "kind": "geographic-rail-station-discovery-catalog",
        "countryCode": "IT",
        "source": "OpenStreetMap",
        "sourceUrl": SOURCE_URL,
        "license": "ODbL-1.0",
        "licenseUrl": LICENSE_URL,
        "attribution": ATTRIBUTION,
        "queriedAt": args.source_date,
        "generatedAt": datetime.now(UTC).replace(microsecond=0).isoformat(),
        "stationCount": len(stations),
        "operationalData": False,
        "notice": "Catalogo geografico per autocompletamento. Non conferma servizio, orario, binario, ritardo o circolazione.",
        "stations": stations,
    }
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("wb") as raw_output:
        with gzip.GzipFile(fileobj=raw_output, mode="wb", compresslevel=9, mtime=0) as stream:
            stream.write(encoded)
    print(json.dumps({"stations": len(stations), "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest()}))


if __name__ == "__main__":
    main()
