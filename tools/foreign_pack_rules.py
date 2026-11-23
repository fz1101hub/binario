#!/usr/bin/env python3
"""Shared rules of the foreign timetable packs: border zone (frontalieri corridors), licence gate, release pins.

The Swiss, Austrian and French packs are bundled whole unless their licence is confirmed in the manifest. A pack
with a confirmed licence is bundled as its *border subset* and its full pack is published as an immutable GitHub
pre-release that the apps download only after an explicit tap (docs/data-sources/GTFS_PACK_REFRESH.md).

The border subset keeps the whole trips that call at least once at a *zone stop*: a stop of an Italian station or a
stop of a station of one of the pack's cross-border corridors (``CORRIDORS``: the lines most used by cross-border
workers and by direct cross-border regional trains, each with its reason). Cross-border trains therefore always stay
whole. A catalogue station is a *zone station* when every stop of it that trips call at is a zone stop.

Every tool reads these rules from here; the apps read the result from the generated manifest and pin files. Changing
a corridor is an edit of ``CORRIDORS`` followed by ``tools/build_foreign_packs.py``.
"""
from __future__ import annotations

import hashlib
import json
import math
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

# The one name of the zone rule (owner, 22 Nov 2026: «più che un raggio km … le tratte estere più usate specie dai
# frontalieri»). Written in the manifest (filter.borderZone.rule), the subset filter manifest and the pin file.
BORDER_ZONE_RULE = "frontalieri-corridors-v1"
EARTH_MEAN_RADIUS_KM = 6371.0088
ANCHOR_PREFIX = "83"  # UIC country code of Italy in trenitaliaLocationId
ITALIAN_STATION_RULE = (
    "stop matched to a catalogue station with trenitaliaLocationId ^83, or whose stop_id carries Italy's country "
    "code (ITALIAN_STOP_IDS)"
)
TRIP_RULE = "whole-trips-touching-zone"
# A corridor station named by its GTFS stop name is the stop of that name within this distance of the coordinates
# read from the pack (its own, else its parent station's), with its platforms.
STATION_MATCH_KM = 1.0

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
# catalogue stations (ids, never names), with at least one trip. A pair of an unpinned pack is not checked (the pack is
# bundled whole). Each pair is also a pair of a corridor below.
REQUIRED_BORDER_PAIRS: Mapping[str, tuple[tuple[str, str, str], ...]] = {
    "sncf-gtfs": (("S04501", "NC000556", "Ventimiglia – Nice-Ville"),),
    "ch-otd-gtfs": (
        ("S01003", "NC000063", "Domodossola – Brig"),
        ("S01307", "NC000225", "Como S. Giovanni – Lugano"),
    ),
    "oebb-gtfs": (("S02001", "NC000544", "Brennero – Innsbruck Hbf"),),
}


@dataclass(frozen=True)
class StationRef:
    """A station of a corridor: a catalogue id (the stops the catalogue matcher resolves to it), else the GTFS stop
    name with the coordinates read from the full pack (never invented)."""

    label: str
    catalogue_id: str | None = None
    stop_name: str | None = None
    latitude: float | None = None
    longitude: float | None = None


def station(catalogue_id: str, label: str) -> StationRef:
    return StationRef(label, catalogue_id=catalogue_id)


def stop(stop_name: str, latitude: float, longitude: float) -> StationRef:
    return StationRef(stop_name, stop_name=stop_name, latitude=latitude, longitude=longitude)


@dataclass(frozen=True)
class Corridor:
    """A cross-border line: its Italian end and the stations of the whole stretch beyond it (every stop the
    corridor's trains call at between the Italian end and the farthest station, so that none is missing)."""

    name: str
    reason: str
    italian_end: StationRef
    stations: tuple[StationRef, ...]


# The frontalieri corridors of each pack (docs/data-sources/SOURCE_POLICY.md, «Paesi confinanti: corridoi»). The
# stations were read from the full packs of 22 Nov 2026 (catalogue ids where the catalogue matcher resolves the stop).
# Left out, with the reason: Samedan (no direct train from Tirano in the pack) and the Roya line Breil–Tende (no SNCF
# train of the pack reaches an Italian station: the Ventimiglia–Cuneo trains are in the Trenitalia pack).
CORRIDORS: Mapping[str, tuple[Corridor, ...]] = {
    "ch-otd-gtfs": (
        Corridor(
            "Como – Chiasso – Lugano – Bellinzona – Biasca/Locarno",
            "Treni regionali TILO (S10 Como–Biasca, RE80 Milano–Locarno) ed EC da Como: frontalieri lombardi verso "
            "Mendrisiotto, Luganese, Bellinzonese, Riviera e Locarnese.",
            station("S01307", "Como S. Giovanni"),
            (
                station("S01301", "Chiasso"), station("NC000631", "Balerna"), station("NC000977", "Mendrisio"),
                stop("Mendrisio S. Martino", 45.877217, 8.983090),
                stop("Capolago-Riva S. Vitale", 45.902834, 8.978904),
                station("NC000957", "Maroggia-Melano"), station("NC000975", "Melide"),
                stop("Paradiso", 45.989371, 8.946888), station("NC000225", "Lugano"),
                station("NC000911", "Lamone-Cadempino"), station("NC001306", "Taverne-Torricella"),
                station("NC000984", "Mezzovico"), station("NC001145", "Rivera-Bironico"),
                station("S00850", "Giubiasco"), station("S00300", "Bellinzona"),
                station("NC000749", "Castione-Arbedo"), station("NC000676", "Biasca"),
                stop("S. Antonino", 46.159051, 8.969525), station("NC000704", "Cadenazzo"),
                station("NC001138", "Riazzino"), stop("Gordola", 46.179040, 8.865770), station("NC001307", "Tenero"),
                station("NC000994", "Minusio"), station("NC000934", "Locarno"),
            ),
        ),
        Corridor(
            "Varese – Stabio – Mendrisio",
            "Ferrovia Arcisate–Stabio (TILO S40, S50 da Varese e Malpensa): frontalieri del Varesotto verso il "
            "Mendrisiotto.",
            station("S01205", "Varese"),
            (
                station("S01204", "Induno Olona"), station("S01203", "Arcisate"),
                station("NC000716", "Cantello Gaggiolo"), station("S19919", "Stabio"), station("NC000977", "Mendrisio"),
            ),
        ),
        Corridor(
            "Luino – Cadenazzo",
            "Linea del Gambarogno (TILO S30 da Luino e Gallarate): frontalieri dell'Alto Varesotto verso Locarnese e "
            "Bellinzonese.",
            station("S01113", "Luino"),
            (
                station("S01111", "Colmegna"), station("S13653", "Maccagno"), station("S01110", "Pino-Tronzano"),
                stop("Ranzo-S. Abbondio", 46.116997, 8.776540), station("NC001445", "Gerra (Gambarogno)"),
                stop("S. Nazzaro", 46.135159, 8.807056), station("NC000939", "Magadino-Vira"),
                station("NC001587", "Quartino"), station("NC000704", "Cadenazzo"),
            ),
        ),
        Corridor(
            "Tirano – Poschiavo – St. Moritz (Bernina)",
            "Ferrovia del Bernina (RhB) da Tirano: frontalieri valtellinesi verso Valposchiavo ed Engadina.",
            station("S01440", "Tirano"),
            (
                stop("Campocologno", 46.233220, 10.142060), stop("Campascio", 46.244671, 10.131694),
                stop("Brusio", 46.259312, 10.125262), stop("Miralago", 46.273117, 10.099552),
                stop("Le Prese", 46.291146, 10.078199), stop("Li Curt", 46.311192, 10.063197),
                stop("Poschiavo", 46.324798, 10.054322), stop("Cadera", 46.345272, 10.051367),
                stop("Cavaglia", 46.364319, 10.044144), stop("Alp Grüm", 46.374751, 10.031136),
                stop("Ospizio Bernina", 46.408760, 10.019701), stop("Bernina Lagalb", 46.436328, 9.991539),
                stop("Bernina Diavolezza", 46.441788, 9.982286), stop("Bernina Suot", 46.444853, 9.969386),
                stop("Morteratsch", 46.450720, 9.940407), stop("Surovas", 46.485628, 9.905678),
                stop("Pontresina", 46.490651, 9.896066), stop("Punt Muragl Staz", 46.507353, 9.880408),
                stop("Celerina Staz", 46.509653, 9.865119), stop("St. Moritz", 46.497973, 9.846389),
            ),
        ),
        Corridor(
            "Domodossola – Re – Camedo – Locarno (Vigezzina-Centovalli)",
            "Ferrovia Vigezzina-Centovalli (SSIF/FART): frontalieri della Valle Vigezzo e dell'Ossola verso il "
            "Locarnese.",
            station("S01003", "Domodossola"),
            (
                stop("Masera", 46.131903, 8.323798), stop("Creggio", 46.124662, 8.324553),
                stop("Trontano", 46.122608, 8.333994), stop("Verigo", 46.121742, 8.351520),
                stop("Gagnone-Orcesco", 46.128610, 8.419613), station("NC000151", "Druogno"),
                stop("S. Maria Maggiore", 46.136447, 8.460594), stop("Prestinone", 46.133042, 8.479512),
                stop("Zornasco", 46.129705, 8.490804), station("NC000237", "Malesco"), station("NC000361", "Re"),
                stop("Folsogno-Dissimo", 46.134374, 8.558106), stop("Camedo", 46.154751, 8.610945),
                stop("Palagnedra", 46.161104, 8.630807), stop("Verdasio", 46.165211, 8.649204),
                stop("Corcapolo", 46.168769, 8.675830), stop("Intragna", 46.177659, 8.702268),
                stop("Cavigliano", 46.183760, 8.721204), stop("Verscio", 46.184563, 8.729478),
                stop("Tegna", 46.186024, 8.742764), stop("Ponte Brolla", 46.185639, 8.753310),
                stop("Solduno S. Martino", 46.171998, 8.770369), stop("Locarno FART", 46.172645, 8.802636),
            ),
        ),
        Corridor(
            "Domodossola – Iselle – Brig – Visp (Sempione)",
            "Linea del Sempione (RegioExpress Domodossola–Brig, EC Milano–Ginevra/Basilea): frontalieri dell'Ossola "
            "verso l'Alto Vallese.",
            station("S01003", "Domodossola"),
            (
                station("NC000353", "Preglia"), station("NC000489", "Varzo"), station("S01952", "Iselle di Trasquera"),
                station("NC000063", "Brig"), station("NC000509", "Visp"),
            ),
        ),
    ),
    "oebb-gtfs": (
        Corridor(
            "Brennero – Innsbruck",
            "S-Bahn Tirol ed EC dal Brennero: pendolari tra Alto Adige e Innsbruck.",
            station("S02001", "Brennero Brenner"),
            (
                stop("Gries a. Br. Bahnhof", 47.041142, 11.485275), stop("St. Jodok a. Br. Bahnhof", 47.063334, 11.499271),
                stop("Steinach a. Br. Bahnhof", 47.089439, 11.469842), stop("Matrei a. Br. Bahnhof", 47.127132, 11.452684),
                station("NC000544", "Innsbruck Hbf."),
            ),
        ),
        Corridor(
            "San Candido – Lienz (Val Pusteria)",
            "Treni regionali Lienz–San Candido: pendolari tra Alta Pusteria e Tirolo orientale.",
            stop("San Candido, Stazione di San Candido", 46.732654, 12.272065),
            (
                stop("Versciaco, Stazione di Versciaco - Elmo", 46.732438, 12.328254),
                station("NC001627", "Weitlanbrunn"), station("NC001528", "Sillian"),
                stop("Heinfels Bahnhof", 46.745828, 12.444191), station("NC001598", "Tassenbach"),
                station("NC001610", "Abfaltersbach"), station("NC001529", "Mittewald a.d. Drau"),
                station("NC001536", "Thal"), station("NC000927", "Lienz"),
            ),
        ),
        Corridor(
            "Tarvisio – Villach – Klagenfurt",
            "Micotra Udine–Villach e treni da Tarvisio: pendolari friulani verso la Carinzia.",
            stop("Tarvisio Citta Boscoverde", 46.506469, 13.607401),
            (
                stop("Thörl-Maglern Bahnhof", 46.540408, 13.648248), station("NC001422", "Arnoldstein"),
                stop("Fürnitz Bahnhof", 46.563185, 13.813116), stop("Villach Warmbad Bahnhst", 46.589102, 13.828800),
                stop("Villach Westbahnhof", 46.608365, 13.840245), station("NC000503", "Villach Hbf"),
                station("NC000204", "Klagenfurt Hbf"),
            ),
        ),
    ),
    "sncf-gtfs": (
        Corridor(
            "Ventimiglia – Menton – Monaco – Nice",
            "TER della Costa Azzurra da Ventimiglia: frontalieri liguri verso Mentone, il Principato di Monaco e "
            "Nizza.",
            station("S04501", "Ventimiglia"),
            (
                station("NC000979", "Menton Garavan"), station("S75648", "Menton"), station("NC000725", "Carnoles"),
                stop("Roquebrune-Cap-Martin", 43.760618, 7.457343), stop("Monte-Carlo Country Club", 43.754354, 7.448208),
                station("NC000551", "Monaco Monte Carlo"), stop("Cap-d'Ail", 43.720600, 7.394384),
                station("NC000842", "Eze"), stop("Beaulieu-sur-Mer", 43.706692, 7.331631),
                stop("Villefranche-sur-Mer", 43.707047, 7.314245), stop("Nice Riquier", 43.705554, 7.290175),
                station("NC000556", "Nice Ville"),
            ),
        ),
        Corridor(
            "Oulx – Modane (Fréjus)",
            "Valico del Fréjus (TGV Parigi–Milano con fermate a Oulx e Modane): tenuto per servizio, il solo treno "
            "diretto Italia–Francia a nord.",
            station("S00204", "Oulx-Cesana-Claviere-Sestriere"),
            (station("S00201", "Modane"),),
        ),
    ),
}

# Italy's country code in a feed's own stop ids: IFOPT «it:» in the ÖBB feed, the UIC code 83 in the SBB and SNCF
# feeds. It keeps whole the trips calling at an Italian stop the catalogue matcher does not resolve.
ITALIAN_STOP_IDS: Mapping[str, re.Pattern[str]] = {
    "ch-otd-gtfs": re.compile(r"^83\d{5}(?::|$)"),
    "oebb-gtfs": re.compile(r"^it:"),
    "sncf-gtfs": re.compile(r"(?:OCE|-)83\d{6}$"),
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



def corridor_names() -> dict[str, list[str]]:
    """The corridor names of every foreign pack, as the pin file lists them."""
    return {source_id: [corridor.name for corridor in CORRIDORS.get(source_id, ())] for source_id in sorted(FOREIGN_PACK_COUNTRIES)}

@dataclass(frozen=True)
class ZoneCatalogue:
    """The station catalogue the zone is resolved with: the one catalogue matcher of the tools
    (``build_ios_gtfs_schedule.CatalogStationMatcher``, design §1.1 rule 5) and the digest of its file."""

    matcher: Any
    sha256: str


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


@dataclass(frozen=True)
class PackZone:
    """The zone stops of one pack: the stops of Italian stations and, per corridor, the stops of its Italian end and
    of each of its stations (an empty set when the pack has no such stop)."""

    italian: frozenset[str]
    ends: Mapping[str, frozenset[str]]
    stations: Mapping[str, Mapping[str, frozenset[str]]]
    stops: frozenset[str] = field(default=frozenset())

    def unresolved(self) -> list[str]:
        """«corridor: station» of every end or station that names no stop of the pack (renamed, closed, wrong)."""
        missing = [f"{name}: estremo italiano" for name, stops in self.ends.items() if not stops]
        missing += [f"{name}: {label}" for name, by_label in self.stations.items()
                    for label, stops in by_label.items() if not stops]
        return missing


def _resolve(ref: StationRef, stops: Mapping[str, Mapping[str, str]], identities: Mapping[str, Any],
             by_name: Mapping[str, Sequence[str]]) -> frozenset[str]:
    if ref.catalogue_id is not None:
        found = {stop_id for stop_id, identity in identities.items() if identity.station_id == ref.catalogue_id}
    else:
        assert ref.stop_name is not None and ref.latitude is not None and ref.longitude is not None
        found = set()
        for stop_id in by_name.get(ref.stop_name, ()):
            position = stop_position(stops[stop_id], stops)
            if position is not None and haversine_km(position[0], position[1], ref.latitude, ref.longitude) <= STATION_MATCH_KM:
                found.add(stop_id)
    pending = list(found)
    children: dict[str, list[str]] = {}
    if pending:
        for stop_id, row in stops.items():
            parent = row.get("parent_station", "") or ""
            if parent:
                children.setdefault(parent, []).append(stop_id)
    while pending:  # a station's platforms, a platform's boarding areas
        for child in children.get(pending.pop(), ()):
            if child not in found:
                found.add(child)
                pending.append(child)
    return frozenset(found)


def pack_zone(source_id: str, stops: Mapping[str, Mapping[str, str]], identities: Mapping[str, Any]) -> PackZone:
    """The zone of a pack from its stops and their catalogue identities (stop_id → an identity with ``station_id``
    and ``trenitalia_location_id``, as ``build_ios_gtfs_schedule.stop_identities`` returns them)."""
    pattern = ITALIAN_STOP_IDS.get(source_id)
    italian = {stop_id for stop_id, identity in identities.items()
               if str(identity.trenitalia_location_id or "").startswith(ANCHOR_PREFIX)}
    if pattern is not None:
        italian.update(stop_id for stop_id in stops if pattern.search(stop_id))
    by_name: dict[str, list[str]] = {}
    for stop_id, row in stops.items():
        by_name.setdefault(row.get("stop_name", ""), []).append(stop_id)
    ends: dict[str, frozenset[str]] = {}
    stations: dict[str, dict[str, frozenset[str]]] = {}
    zone = set(italian)
    for corridor in CORRIDORS.get(source_id, ()):
        ends[corridor.name] = _resolve(corridor.italian_end, stops, identities, by_name)
        zone.update(ends[corridor.name])
        by_label = stations.setdefault(corridor.name, {})
        for ref in corridor.stations:
            by_label[ref.label] = _resolve(ref, stops, identities, by_name)
            zone.update(by_label[ref.label])
    return PackZone(frozenset(italian), ends, stations, frozenset(zone))


def corridor_pairs(source_id: str, zone: PackZone) -> list[tuple[str, frozenset[str], frozenset[str]]]:
    """(label «corridor: end – station», end stops, station stops) of every pair a corridor of the pack lists."""
    pairs = []
    for corridor in CORRIDORS.get(source_id, ()):
        for ref in corridor.stations:
            pairs.append((f"{corridor.name}: {corridor.italian_end.label} – {ref.label}",
                          zone.ends[corridor.name], zone.stations[corridor.name][ref.label]))
    return pairs


def pair_trip_count(trips: Iterable[Iterable[str]], first: frozenset[str], second: frozenset[str]) -> int:
    """Trips calling at a stop of each set (in either order)."""
    count = 0
    for calls in trips:
        called = set(calls)
        if called & first and called & second:
            count += 1
    return count


def stretch_gaps(source_id: str, zone: PackZone, trips: Iterable[Sequence[str]]) -> set[tuple[str, str]]:
    """(corridor, stop_id) of every stop that a trip calls at between a corridor's Italian end and one of its stations
    and that is not a zone stop: a station missing from the corridor (a new halt, a renamed stop)."""
    corridors = [(corridor.name, zone.ends[corridor.name], frozenset().union(*zone.stations[corridor.name].values()))
                 for corridor in CORRIDORS.get(source_id, ())]
    gaps: set[tuple[str, str]] = set()
    for calls in trips:
        for name, ends, members in corridors:
            first_end = next((index for index, stop_id in enumerate(calls) if stop_id in ends), None)
            if first_end is None:
                continue
            reached = [index for index, stop_id in enumerate(calls) if stop_id in members]
            spans = []
            after = [index for index in reached if index > first_end]
            before = [index for index in reached if index < first_end]
            if after:
                spans.append(calls[first_end + 1:max(after)])
            if before:
                spans.append(calls[min(before) + 1:first_end])
            gaps.update((name, stop_id) for span in spans for stop_id in span if stop_id not in zone.stops)
    return gaps


def canonical_json_bytes(payload: Any) -> bytes:
    """The bytes of a generated pin or ledger file: two-space indent, UTF-8, sorted only where the writer sorts."""
    return (json.dumps(payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
