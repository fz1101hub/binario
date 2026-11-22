#!/usr/bin/env python3
"""Shared rules of the foreign timetable packs: border zone, licence gate, release pins.

The Swiss, Austrian and French packs are bundled whole unless their licence is confirmed in the manifest. A pack
with a confirmed licence is bundled as its *border subset* (the whole trips that call at least once within
``BORDER_ZONE_RADIUS_KM`` of an Italian station) and its full pack is published as an immutable GitHub pre-release
that the apps download only after an explicit tap (docs/data-sources/GTFS_PACK_REFRESH.md).

Every tool reads these values from here; the apps read them from the generated manifest and pin files. The radius
is the one number to change (then run ``tools/build_foreign_packs.py``).
"""
from __future__ import annotations

import bisect
import hashlib
import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping

# The one place of the border radius (coordinator decision D1): 30 or 35 km, pending the owner's answer.
BORDER_ZONE_RADIUS_KM = 35
EARTH_MEAN_RADIUS_KM = 6371.0088
ANCHOR_PREFIX = "83"  # UIC country code of Italy in trenitaliaLocationId
ANCHOR_RULE = "catalogue trenitaliaLocationId ^83 with coordinates"
DISTANCE_RULE = "haversine-6371.0088km-stop-or-parent"
TRIP_RULE = "whole-trips-touching-zone"

# Packs of foreign operators and the country the apps name in their texts.
FOREIGN_PACK_COUNTRIES: Mapping[str, str] = {"ch-otd-gtfs": "CH", "oebb-gtfs": "AT", "sncf-gtfs": "FR"}
# The manifest licence text of a pack whose terms the owner has not recorded (coordinator decision D2): such a pack
# is never published as a release file and stays bundled whole.
UNCONFIRMED_LICENCE = "Da confermare dal titolare"

REPOSITORY = "fz1101hub/binario"
RELEASE_DOWNLOAD_PREFIX = f"https://github.com/{REPOSITORY}/releases/download/"
RELEASE_DOWNLOAD_HOST = "github.com"
# Where github.com redirects a release download; nothing else is followed.
REDIRECT_HOSTS = ("release-assets.githubusercontent.com", "objects.githubusercontent.com")
TAG_PREFIXES: Mapping[str, str] = {"android": "gtfs-full-", "ios": "gtfs-ios-"}
ASSET_SUFFIXES: Mapping[str, str] = {"android": ".zip", "ios": ".sqlite.zlib"}
PLATFORMS = ("android", "ios")

ROOT = Path(__file__).resolve().parents[1]
FULL_PACK_DIR = "data/gtfs-foreign/android"
IOS_FULL_DIR = "data/gtfs-foreign/ios"
LEDGER_PATH = "data/gtfs-foreign/published.json"
CATALOGUE_PATH = "app/src/main/assets/stations/curated_it_rail_stations.json"
ANDROID_PINS_PATH = "app/src/main/assets/gtfs/foreign_packs.json"
IOS_PINS_PATH = "ios/Resources/binario_gtfs_foreign_packs_v1.json"
PINS_SCHEMA_VERSION = 1
LEDGER_SCHEMA_VERSION = 1

# Journeys that must stay complete in the border subset of their pack: the same trips as in the full pack, between
# catalogue stations (ids, never names). A pair of an unpinned pack is not checked (the pack is bundled whole).
REQUIRED_BORDER_PAIRS: Mapping[str, tuple[tuple[str, str, str], ...]] = {
    "sncf-gtfs": (("S04501", "NC000556", "Ventimiglia – Nice-Ville"),),
    "ch-otd-gtfs": (
        ("S01003", "NC000063", "Domodossola – Brig"),
        ("S01307", "NC000225", "Como S. Giovanni – Lugano"),
    ),
    "oebb-gtfs": (("S02001", "NC000544", "Brennero – Innsbruck Hbf"),),
}
# Size budget of all bundled border subsets together (design §2.5).
SUBSET_SIZE_BUDGET_BYTES = 1_200_000

HEX64 = re.compile(r"[0-9a-f]{64}")


def licence_confirmed(entry: Mapping[str, Any]) -> bool:
    """A manifest entry whose licence the owner has recorded: a non-empty text other than «Da confermare»."""
    licence = entry.get("licence")
    return isinstance(licence, str) and bool(licence.strip()) and UNCONFIRMED_LICENCE.casefold() not in licence.casefold()


def is_border_pack(entry: Mapping[str, Any]) -> bool:
    """A foreign pack bundled as its border subset, with a full pack published for download (D2)."""
    return entry.get("sourceId") in FOREIGN_PACK_COUNTRIES and licence_confirmed(entry)


def tag_for(platform: str, source_id: str, sha256: str) -> str:
    return f"{TAG_PREFIXES[platform]}{source_id}-{sha256[:12]}"


def asset_for(platform: str, source_id: str) -> str:
    return f"{source_id}{ASSET_SUFFIXES[platform]}"


def url_for(tag: str, asset: str) -> str:
    return f"{RELEASE_DOWNLOAD_PREFIX}{tag}/{asset}"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def haversine_km(latitude: float, longitude: float, other_latitude: float, other_longitude: float) -> float:
    first, second = math.radians(latitude), math.radians(other_latitude)
    half = (math.sin((second - first) / 2) ** 2
            + math.cos(first) * math.cos(second) * math.sin(math.radians(other_longitude - longitude) / 2) ** 2)
    return 2 * EARTH_MEAN_RADIUS_KM * math.asin(math.sqrt(min(1.0, half)))


@dataclass(frozen=True)
class Anchors:
    """The Italian catalogue stations the border zone is measured from, sorted by latitude."""

    points: tuple[tuple[float, float], ...]
    catalogue_sha256: str
    latitudes: tuple[float, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "latitudes", tuple(point[0] for point in self.points))

    @property
    def count(self) -> int:
        return len(self.points)

    def within(self, latitude: float, longitude: float, radius_km: float) -> bool:
        """True when some anchor is at most ``radius_km`` away (haversine on the mean Earth radius)."""
        # A great-circle distance is never shorter than its latitude difference: only that band is measured.
        band = math.degrees(radius_km / EARTH_MEAN_RADIUS_KM)
        start = bisect.bisect_left(self.latitudes, latitude - band)
        end = bisect.bisect_right(self.latitudes, latitude + band)
        return any(
            haversine_km(latitude, longitude, anchor_latitude, anchor_longitude) <= radius_km
            for anchor_latitude, anchor_longitude in self.points[start:end]
        )


def anchors_from_catalogue(payload: Mapping[str, Any], catalogue_sha256: str) -> Anchors:
    stations = payload.get("stations")
    if not isinstance(stations, list):
        raise ValueError("catalogo stazioni senza lista stations")
    points: list[tuple[float, float]] = []
    for station in stations:
        if not isinstance(station, Mapping):
            raise ValueError("stazione del catalogo non valida")
        code = station.get("trenitaliaLocationId")
        latitude, longitude = station.get("latitude"), station.get("longitude")
        if not isinstance(code, str) or not code.startswith(ANCHOR_PREFIX):
            continue
        if isinstance(latitude, bool) or isinstance(longitude, bool):
            continue
        if not isinstance(latitude, (int, float)) or not isinstance(longitude, (int, float)):
            continue
        points.append((float(latitude), float(longitude)))
    if not points:
        raise ValueError("nessuna stazione italiana con coordinate nel catalogo")
    return Anchors(tuple(sorted(points)), catalogue_sha256)


def load_anchors(catalogue_path: Path) -> Anchors:
    data = catalogue_path.read_bytes()
    return anchors_from_catalogue(json.loads(data.decode("utf-8")), hashlib.sha256(data).hexdigest())


def _coordinate(value: str | None) -> float | None:
    try:
        number = float(value) if value not in (None, "") else None
    except ValueError:
        return None
    return number if number is not None and math.isfinite(number) else None


def stop_position(stop: Mapping[str, str], stops_by_id: Mapping[str, Mapping[str, str]]) -> tuple[float, float] | None:
    """The stop's own coordinates, else its parent station's (GTFS lets a platform leave them to its parent)."""
    latitude, longitude = _coordinate(stop.get("stop_lat")), _coordinate(stop.get("stop_lon"))
    if latitude is not None and longitude is not None:
        return latitude, longitude
    parent = stops_by_id.get(stop.get("parent_station", "") or "")
    if parent is None:
        return None
    latitude, longitude = _coordinate(parent.get("stop_lat")), _coordinate(parent.get("stop_lon"))
    return (latitude, longitude) if latitude is not None and longitude is not None else None


def zone_stop_ids(stops: Iterable[Mapping[str, str]], anchors: Anchors, radius_km: float) -> set[str]:
    """The stops of a pack inside the border zone (design §1.1, rule 2)."""
    rows = list(stops)
    by_id = {row.get("stop_id", ""): row for row in rows}
    zone: set[str] = set()
    for row in rows:
        position = stop_position(row, by_id)
        if position is not None and anchors.within(position[0], position[1], radius_km):
            zone.add(row.get("stop_id", ""))
    return zone


def canonical_json_bytes(payload: Any) -> bytes:
    """The bytes of a generated pin or ledger file: two-space indent, UTF-8, sorted only where the writer sorts."""
    return (json.dumps(payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
