#!/usr/bin/env python3
"""The Android app's stop-to-station matcher, for the foreign-pack pins.

The pins name, for each foreign pack, the catalogue stations its full pack serves and those its border subset serves
completely (``servedStationIds``, ``zoneStationIds``). The iPhone matches stops with the tools' matcher
(``build_ios_gtfs_schedule.CatalogStationMatcher``); the Android app with its own
(``app/src/main/java/it/binario/app/data/journey/GtfsStopStationResolver.kt`` and ``PackStops.build`` in
``EmbeddedGtfsJourneyProvider.kt``), which binds some stops to other catalogue entries (homonyms without coordinates,
namesakes up to ``MAX_NAME_MATCH_KM`` away, co-located duplicates). A station either app can resolve a stop of the pack to must be in the lists, or the
Android end rule could show the subset's partial trains as complete. This module mirrors the Android rules for the
foreign packs (no station-code scheme, no catalogue name suffixes, not urban); the JVM test
``EmbeddedGtfsBorderZoneTest`` checks the result against the Android matcher itself on the real packs.
"""
from __future__ import annotations

import json
import math
import re
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

SNAP_METERS = 150.0
# ``GtfsStopStationResolver.MAX_NAME_MATCH_KM``: a station a name rule names binds a stop only this close, when both have
# coordinates (the Swiss Guarda is not the Guarda near Ferrara, 266 km away).
MAX_NAME_MATCH_KM = 10.0
STATION_WORDS = frozenset({
    "STAZIONE", "STAZ", "CENTRALE", "FS", "RFI", "FERROVIA", "FTM", "BAHNHOF", "HBF", "HAUPTBAHNHOF", "BF",
    "GARE", "STATION", "CENTRAL",
})
_FILLERS = frozenset({"DI", "DEL", "DELLA", "DELLE", "DEI", "DEGLI", "DE", "D", "IN", "SU", "SUL", "SULLA", "AL", "ALLA", "E"})
_TWO_PART = {("C", "LE"): "CENTRALE", ("M", "MA"): "MARITTIMA", ("P", "ZA"): "PIAZZA", ("V", "LE"): "VIALE"}
_ALL_STATIONS = re.compile(r"\(\s*TUTTE\s+LE\s+STAZIONI\s*\)")
_SEPARATORS = re.compile(r"[^A-Z0-9]+")
_NON_ALPHANUMERIC = re.compile(r"[^a-z0-9]")
_STATION_OF = re.compile(r"\bstazione(?:\s+di)?\s+(.+)$", re.IGNORECASE)
_TRAILING_PLATFORM = re.compile(r"\s+\d{1,2}[A-Za-z]?$")
_COUNTRY_SUFFIX = re.compile(r"\s*\((?:I|IT|CH|A|AT|F|FR|D|DE|SLO|HR)\)$", re.IGNORECASE)
_STATION_SUFFIX = re.compile(r"\s+(?:stazione|bahnhof|bhf)$", re.IGNORECASE)
_HBF = re.compile(r"\bHbf\b\.?", re.IGNORECASE)


def _strip_marks(value: str) -> str:
    return "".join(character for character in unicodedata.normalize("NFD", value) if not unicodedata.category(character).startswith("M"))


def normalize(value: str) -> str:
    """``GtfsStopStationResolver.normalize``."""
    return _NON_ALPHANUMERIC.sub("", _strip_marks(value).lower())


def tokens(value: str) -> list[str]:
    """``RailStopNameMatcher.tokens``."""
    upper = _ALL_STATIONS.sub(" ", _strip_marks(value).upper())
    raw = [token for token in _SEPARATORS.split(upper) if token.strip()]
    joined: list[str] = []
    index = 0
    while index < len(raw):
        pair = _TWO_PART.get((raw[index], raw[index + 1])) if index + 1 < len(raw) else None
        if pair is not None:
            joined.append(pair)
            index += 2
        else:
            joined.append(raw[index])
            index += 1
    return [token for token in joined if token not in _FILLERS]


def _is_abbreviation_of(short: str, full: str) -> bool:
    return 1 <= len(short) <= 2 and short.isalpha() and len(full) > len(short) and full.startswith(short)


def _initials_span(initials: str, values: list[str], start: int) -> int | None:
    if not 2 <= len(initials) <= 4 or not initials.isalpha() or start + len(initials) > len(values):
        return None
    covered = values[start:start + len(initials)]
    if all(covered[index][0] == initials[index] for index in range(len(covered))) and any(len(item) > 1 for item in covered):
        return len(initials)
    return None


def _sequence_matches(published: list[str], station: list[str]) -> bool:
    if not station:
        return False
    if published == station:
        return True
    if not any(len(token) >= 3 and token in station for token in published):
        return False
    memo: dict[tuple[int, int], bool] = {}

    def match(i: int, j: int) -> bool:
        if (i, j) in memo:
            return memo[(i, j)]
        if i == len(published) and j == len(station):
            result = True
        elif i == len(published) or j == len(station):
            result = False
        else:
            p, s = published[i], station[j]
            result = ((p == s and match(i + 1, j + 1))
                      or (_is_abbreviation_of(p, s) and match(i + 1, j + 1))
                      or (_is_abbreviation_of(s, p) and match(i + 1, j + 1)))
            if not result:
                span = _initials_span(p, station, j)
                result = span is not None and match(i + 1, j + span)
            if not result:
                span = _initials_span(s, published, i)
                result = span is not None and match(i + span, j + 1)
        memo[(i, j)] = result
        return result

    return match(0, 0)


def _expand(value: str) -> str:
    return _HBF.sub("Hauptbahnhof", value)


def variants(stop_name: str) -> list[str]:
    """``GtfsStopStationResolver.variants``."""
    trimmed = stop_name.strip()
    if not trimmed:
        return []
    base = [trimmed]
    found = _STATION_OF.search(trimmed)
    if found and found.group(1).strip() and found.group(1).strip() not in base:
        base.append(found.group(1).strip())
    if "," in trimmed:
        after = trimmed.split(",", 1)[1].strip()
        if after and after not in base:
            base.append(after)
    result: list[str] = []
    for value in base:
        cleaned = _STATION_SUFFIX.sub("", _COUNTRY_SUFFIX.sub("", _TRAILING_PLATFORM.sub("", value))).strip()
        for item in (value, cleaned, _expand(cleaned)):
            if item and item not in result:
                result.append(item)
    return result


def display_name(stop_name: str) -> str:
    trimmed = stop_name.strip()
    found = _STATION_OF.search(trimmed)
    after = found.group(1).strip() if found and found.group(1).strip() else trimmed
    return _TRAILING_PLATFORM.sub("", after).strip() or trimmed


def distance_km(latitude: float, longitude: float, other_latitude: float, other_longitude: float) -> float:
    """``GtfsStationCodeIndex.distanceKm``."""
    phi1, phi2 = math.radians(latitude), math.radians(other_latitude)
    a = math.sin((phi2 - phi1) / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(math.radians(other_longitude - longitude) / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(min(1.0, max(0.0, a))))


@dataclass(frozen=True)
class Station:
    id: str
    name: str
    aliases: tuple[str, ...]
    latitude: float | None
    longitude: float | None


def _candidate_names(station: Station) -> list[str]:
    return [station.name] + [alias for alias in station.aliases
                             if "_" not in alias and not alias.startswith("@") and sum(len(token) for token in tokens(alias)) >= 3]


def is_named_by(station: Station, published: str) -> bool:
    """``RailStopNameMatcher.matches``."""
    published_tokens = tokens(published)
    return bool(published_tokens) and any(_sequence_matches(published_tokens, tokens(name)) for name in _candidate_names(station))


def candidate_keys(station: Station) -> list[str]:
    name_words = {token for token in tokens(station.name) if len(token) >= 4}
    names = [station.name] + [alias for alias in station.aliases
                              if "_" not in alias and not alias.startswith("@") and len(normalize(alias)) >= 3
                              and any(token in name_words for token in tokens(alias))]
    keys: list[str] = []
    for name in names:
        for value in (name, _expand(name)):
            key = normalize(value)
            if key and key not in keys:
                keys.append(key)
    return keys


def within_name_bound(station: Station, latitude: float | None, longitude: float | None) -> bool:
    """``GtfsStopStationResolver.isWithinNameBound``: within ``MAX_NAME_MATCH_KM``, or either position unknown."""
    if latitude is None or longitude is None or station.latitude is None or station.longitude is None:
        return True
    return distance_km(latitude, longitude, station.latitude, station.longitude) <= MAX_NAME_MATCH_KM


def _in_south_tyrol(station: Station) -> bool:
    return (station.latitude is not None and station.longitude is not None
            and 46.20 <= station.latitude <= 47.10 and 10.35 <= station.longitude <= 12.50)


class AndroidStopMatcher:
    """``GtfsStopStationResolver`` over the station catalogue as ``EmbeddedCuratedRailStationCatalog`` loads it."""

    def __init__(self, stations: list[Station]) -> None:
        keyed: dict[str, list[str]] = {}
        for station in stations:
            for key in candidate_keys(station):
                ids = keyed.setdefault(key, [])
                if station.id not in ids:
                    ids.append(station.id)
        by_id = {station.id: station for station in stations}
        self.by_key = {key: by_id[ids[0]] for key, ids in keyed.items() if len(ids) == 1}
        self.ambiguous = {key for key, ids in keyed.items() if len(ids) > 1}
        self.bilingual: dict[str, list[tuple[Station, int]]] = {}
        for station in stations:
            if not _in_south_tyrol(station):
                continue
            words = tokens(station.name)
            for length in range(1, len(words)):
                self.bilingual.setdefault(" ".join(words[:length]), []).append((station, len(words) - length))
        self.by_token: dict[str, list[Station]] = {}
        for station in stations:
            for token in dict.fromkeys(token for token in tokens(station.name) if len(token) >= 4):
                self.by_token.setdefault(token, []).append(station)
        self.located = [station for station in stations if station.latitude is not None and station.longitude is not None]

    def resolve(self, stop_name: str, latitude: float | None = None, longitude: float | None = None) -> Station | None:
        """``GtfsStopStationResolver.resolve``: a rule whose station is farther than ``MAX_NAME_MATCH_KM`` from the
        stop does not bind it, and the next rule is tried."""
        names = variants(stop_name)
        for variant in names:
            station = self.by_key.get(normalize(variant))
            if station is not None and within_name_bound(station, latitude, longitude):
                return station
        if any(normalize(variant) in self.ambiguous for variant in names):
            return None
        for variant in names:
            candidates = self.bilingual.get(" ".join(tokens(variant)))
            if not candidates:
                continue
            fewest = min(extra for _, extra in candidates)
            best = list(dict.fromkeys(station.id for station, extra in candidates if extra == fewest))
            if len(best) == 1:
                station = next(station for station, _ in candidates if station.id == best[0])
                if within_name_bound(station, latitude, longitude):
                    return station
        for variant in names:
            seen: dict[str, Station] = {}
            for token in tokens(variant):
                if len(token) >= 4:
                    for station in self.by_token.get(token, ()):
                        seen.setdefault(station.id, station)
            matches = [station for station in seen.values() if is_named_by(station, variant)]
            if len(matches) == 1 and within_name_bound(matches[0], latitude, longitude):
                return matches[0]
        return None

    def nearby(self, stop_name: str, latitude: float | None, longitude: float | None) -> Station | None:
        if latitude is None or longitude is None:
            return None
        words = tokens(display_name(stop_name))
        first = next((word for word in words if len(word) >= 4), None)
        if first is None:
            return None
        after = words[words.index(first) + 1:]
        if any(len(word) >= 3 and word not in STATION_WORDS for word in after):
            return None
        match: Station | None = None
        for station in self.located:
            if abs(station.latitude - latitude) > 0.02 or abs(station.longitude - longitude) > 0.03:  # type: ignore[operator]
                continue
            if distance_km(latitude, longitude, station.latitude, station.longitude) * 1000.0 > SNAP_METERS:  # type: ignore[arg-type]
                continue
            if next((word for word in tokens(station.name) if len(word) >= 4), None) != first:
                continue
            if match is not None and match.id != station.id:
                return None
            match = station
        return match

    def pack_stations(self, stops: Mapping[str, Mapping[str, str]]) -> dict[str, str]:
        """``PackStops.build``: the catalogue id each stop of a (rail, foreign) pack resolves to, with its parent
        station's group (the station any of its stops resolves to, when they agree)."""
        by_name: dict[str, Station | None] = {}
        resolved: dict[str, Station] = {}
        for stop_id, row in stops.items():
            name = row.get("stop_name", "")
            latitude, longitude = _coordinate(row.get("stop_lat")), _coordinate(row.get("stop_lon"))
            if name not in by_name:
                by_name[name] = self.resolve(name)
            station = by_name[name]
            if station is not None and not within_name_bound(station, latitude, longitude):
                station = self.resolve(name, latitude, longitude)
            station = station or self.nearby(name, latitude, longitude)
            if station is not None:
                resolved[stop_id] = station
        group_station: dict[str, str] = {}
        conflicting: set[str] = set()
        for stop_id, row in stops.items():
            station = resolved.get(stop_id)
            if station is None:
                continue
            group = row.get("parent_station", "") or stop_id
            previous = group_station.setdefault(group, station.id)
            if previous != station.id:
                conflicting.add(group)
        result: dict[str, str] = {}
        for stop_id, row in stops.items():
            group = row.get("parent_station", "") or stop_id
            if group not in conflicting and group in group_station:
                result[stop_id] = group_station[group]
            elif stop_id in resolved:
                result[stop_id] = resolved[stop_id].id
        return result


def _coordinate(value: str | None) -> float | None:
    try:
        number = float(value) if value not in (None, "") else None
    except ValueError:
        return None
    if number is None or not math.isfinite(number):
        return None
    return number


def load(catalogue_path: Path) -> AndroidStopMatcher:
    """The catalogue as ``EmbeddedCuratedRailStationCatalog`` reads it (ids without the ``curated:`` prefix)."""
    payload: Any = json.loads(catalogue_path.read_text(encoding="utf-8"))
    stations: list[Station] = []
    seen: set[str] = set()
    for value in payload.get("stations", []):
        if not isinstance(value, Mapping):
            continue
        station_id, name = str(value.get("id") or "").strip(), str(value.get("name") or "").strip()
        if not station_id or not name or station_id in seen:
            continue
        seen.add(station_id)
        aliases = tuple(dict.fromkeys(str(alias).strip() for alias in value.get("aliases") or [] if str(alias).strip()))
        latitude, longitude = value.get("latitude"), value.get("longitude")
        stations.append(Station(
            station_id, name, aliases,
            float(latitude) if isinstance(latitude, (int, float)) and not isinstance(latitude, bool) and math.isfinite(latitude) else None,
            float(longitude) if isinstance(longitude, (int, float)) and not isinstance(longitude, bool) and math.isfinite(longitude) else None,
        ))
    return AndroidStopMatcher(stations)
