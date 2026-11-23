#!/usr/bin/env python3
"""The Android app's station-code rule, for the stops of the national operators' packs in the iPhone index.

Trenitalia and Italo publish, as ``stop_code``, a code that names the catalogue station better than their spelling of
it ("BOLOGNA C.LE", "Padua", "NA Afragola PES"). Android resolves such a stop by its code before its name
(``app/src/main/java/it/binario/app/data/gtfs/GtfsStationCodeIndex.kt``, used by ``GtfsStopStationResolver.resolveStop``
and ``PackStops.build`` in ``EmbeddedGtfsJourneyProvider.kt``); this module is that rule, line for line, so both phones
bind a stop of these packs to the same catalogue station:

* Trenitalia (``TRENITALIA_LOCATION_ID``): the nine-digit location id is the catalogue's ``trenitaliaLocationId``,
  within ``MAX_LOCATION_ID_KM``; else, for an "8300xxxxx" code only, the station whose ``railwayCode`` is "S" and the
  last five digits, when it has no other location id, within ``MAX_DERIVED_CODE_KM``. Without coordinates on either
  side a shared word of four letters or more of the names corroborates the code instead.
* Italo (``ITALO_STATION_CODE``): the reviewed table ``ItaloGtfsStationCodes``, read from
  ``contracts/shared-data/v1/italo-gtfs-station-codes.json`` (``ITALO_STATION_CODES``).

A stop no code binds returns None and is matched by name, as in every other pack. ``tests/test_ios_gtfs_schedule.py``
mirrors ``GtfsStationCodeIndexTest``, runs the cases of ``contracts/shared-rules/v1/gtfs-station-codes-v1.json`` (as
``GtfsStationCodesContractTest`` does on Android) and compares the shared table with the generated Kotlin and the bounds
with the Kotlin file.
"""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping

from android_stop_matcher import distance_km, tokens

# GtfsStopCodeScheme, and the packs publishing each (EmbeddedGtfsPacks.kt, stopCodeScheme).
TRENITALIA_LOCATION_ID = "TRENITALIA_LOCATION_ID"
ITALO_STATION_CODE = "ITALO_STATION_CODE"
PACK_SCHEMES: Mapping[str, str] = {"trenitalia-gtfs": TRENITALIA_LOCATION_ID, "italo-gtfs": ITALO_STATION_CODE}
# GtfsStationCodeIndex.MAX_LOCATION_ID_KM: a location id names the station, only this distance reveals a catalogue error.
MAX_LOCATION_ID_KM = 10.0
# GtfsStationCodeIndex.MAX_DERIVED_CODE_KM: the derived ViaggiaTreno code is a guess the coordinates confirm closely.
MAX_DERIVED_CODE_KM = 3.0
# Location ids are a nine-digit UIC-style code; the catalogue only holds Italian (83…) ones.
_TRENITALIA_LOCATION = re.compile(r"\d{9}")

# ItaloGtfsStationCodes: the reviewed table of Italo's codes, shared by every platform as data (contracts/shared-data/v1;
# Android reads the Kotlin tools/generate_shared_code.py writes from it, this pipeline the JSON).
ITALO_STATION_CODES_PATH = Path(__file__).resolve().parents[1] / "contracts/shared-data/v1/italo-gtfs-station-codes.json"


def load_italo_station_codes(path: Path = ITALO_STATION_CODES_PATH) -> tuple[dict[str, str], frozenset[str]]:
    """Italo's stop_code -> catalogue id, and the codes reviewed and deliberately left to the pack ("LUX")."""
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, Mapping) or payload.get("schema") != "binario-shared-data-v1" \
            or payload.get("name") != "italo-gtfs-station-codes":
        raise ValueError(f"{path}: not the shared Italo station-code table")
    codes: dict[str, str] = {}
    for entry in payload.get("codes", []):
        code, station_id = entry.get("code"), entry.get("catalogueId")
        if not isinstance(code, str) or not code.strip() or not isinstance(station_id, str) or not station_id.strip() or code in codes:
            raise ValueError(f"{path}: invalid or repeated entry {entry!r}")
        codes[code] = station_id
    unmapped = frozenset(str(entry.get("code")) for entry in payload.get("unmapped", []))
    if not codes or unmapped & set(codes):
        raise ValueError(f"{path}: empty table or a code both mapped and unmapped")
    return codes, unmapped


ITALO_STATION_CODES, ITALO_UNMAPPED = load_italo_station_codes()


@dataclass(frozen=True)
class Station:
    """A catalogue station as EmbeddedCuratedRailStationCatalog loads it (``id`` without the "curated:" prefix)."""

    id: str
    name: str
    aliases: tuple[str, ...]
    railway_code: str | None
    trenitalia_location_id: str | None
    latitude: float | None
    longitude: float | None


def _unique_by(stations: Iterable[Station], key: str) -> dict[str, Station]:
    """``GtfsStationCodeIndex.uniqueBy``: a code two stations share names neither."""
    grouped: dict[str, list[Station]] = {}
    for station in stations:
        value = (getattr(station, key) or "").strip()
        if value:
            grouped.setdefault(value, []).append(station)
    return {value: members[0] for value, members in grouped.items() if len(members) == 1}


class StationCodeIndex:
    """``GtfsStationCodeIndex`` over the catalogue."""

    def __init__(self, stations: Iterable[Station]) -> None:
        listed = list(stations)
        self.by_id = {station.id: station for station in listed}
        self._by_location_id = _unique_by(listed, "trenitalia_location_id")
        self._by_railway_code = _unique_by(listed, "railway_code")

    def resolve(self, scheme: str | None, code: str | None, latitude: float | None, longitude: float | None,
                name: str) -> Station | None:
        """The catalogue station ``code`` names under ``scheme``, or None (the stop is then matched by name)."""
        trimmed = (code or "").strip()
        if not trimmed or scheme is None:
            return None
        if scheme == ITALO_STATION_CODE:
            station_id = ITALO_STATION_CODES.get(trimmed)
            return self.by_id.get(station_id) if station_id is not None else None
        if scheme == TRENITALIA_LOCATION_ID:
            return self._trenitalia(trimmed, latitude, longitude, name)
        raise ValueError(f"unknown station-code scheme {scheme!r}")

    def _trenitalia(self, code: str, latitude: float | None, longitude: float | None, name: str) -> Station | None:
        if not _TRENITALIA_LOCATION.fullmatch(code):
            return None
        by_location = self._by_location_id.get(code)
        if by_location is not None and _corroborated(by_location, latitude, longitude, name, MAX_LOCATION_ID_KM):
            return by_location
        if not code.startswith("8300"):
            return None
        derived = self._by_railway_code.get("S" + code[4:])
        if derived is None:
            return None
        if derived.trenitalia_location_id is not None and derived.trenitalia_location_id != code:
            return None
        return derived if _corroborated(derived, latitude, longitude, name, MAX_DERIVED_CODE_KM) else None


def _corroborated(station: Station, latitude: float | None, longitude: float | None, name: str, maximum_km: float) -> bool:
    if latitude is not None and longitude is not None and station.latitude is not None and station.longitude is not None:
        return distance_km(latitude, longitude, station.latitude, station.longitude) <= maximum_km
    words = {token for token in tokens(name) if len(token) >= 4}
    return any(token in words for label in (station.name, *station.aliases) for token in tokens(label))


def _text(value: Any) -> str | None:
    """``optTextOrNull``: a JSON null or blank text is no code."""
    return value.strip() or None if isinstance(value, str) else None


def _coordinate(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        return None
    return float(value)


def stations_of(payload: Mapping[str, Any]) -> list[Station]:
    """The catalogue stations as ``EmbeddedCuratedRailStationCatalog.stationsFrom`` keeps them: an id and a name,
    the first entry of an id, trimmed distinct aliases."""
    stations: list[Station] = []
    seen: set[str] = set()
    for value in payload.get("stations", []):
        if not isinstance(value, Mapping):
            continue
        station_id, name = str(value.get("id") or "").strip(), str(value.get("name") or "").strip()
        if not station_id or not name or station_id in seen:
            continue
        seen.add(station_id)
        aliases = value.get("aliases") if isinstance(value.get("aliases"), list) else []
        stations.append(Station(
            station_id, name, tuple(dict.fromkeys(alias.strip() for alias in aliases if isinstance(alias, str) and alias.strip())),
            _text(value.get("railwayCode")), _text(value.get("trenitaliaLocationId")),
            _coordinate(value.get("latitude")), _coordinate(value.get("longitude")),
        ))
    return stations


def load(catalogue_path: Path) -> StationCodeIndex:
    return StationCodeIndex(stations_of(json.loads(catalogue_path.read_text(encoding="utf-8"))))
