#!/usr/bin/env python3
"""Turn the shared tables of ``contracts/shared-data/v1`` into Kotlin, Swift and TypeScript constants.

The common base of the apps (docs/architecture/ADR-0002-BASE-COMUNE.md): a table that more than one platform uses is
written once, as JSON, and every app reads it as generated code, never by parsing JSON at runtime.

- Kotlin: one file, ``app/src/main/java/it/binario/app/shared/SharedData.kt`` (every table);
- Swift: one file, ``ios/BinarioKit/Sources/BinarioKit/Generated/SharedData.swift`` (every table);
- TypeScript: one module, ``alexa-companion/shared/generated/sharedData.ts`` (only the tables Alexa reads).

Each JSON file is checked against the schema registered in ``TABLES`` (types, required keys, no unknown or duplicate
keys, no duplicate entries, the table's own cross checks) before anything is written. The output is deterministic and
committed; ``--check`` fails when a generated file is stale or missing (CI, gtfs-assets-ci.yml).

    python3 tools/generate_shared_code.py           # rewrite the generated files
    python3 tools/generate_shared_code.py --check   # exit 1 when one of them is not what the tables give

Adding a table (contracts/shared-data/README.md): put ``<name>-v1.json`` in contracts/shared-data/v1/, register a
``Table`` in ``TABLES`` with its schema (and ``alexa=True`` when the skill reads it), run the generator, commit the JSON
and the three generated files. A JSON file not registered yet is reported and generates nothing.

Standard library only, offline.
"""
from __future__ import annotations

import argparse
import calendar
import copy
import json
import math
import re
import sys
import textwrap
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
GENERATOR = "tools/generate_shared_code.py"
DATA_DIR = Path("contracts/shared-data/v1")
KOTLIN_OUTPUT = Path("app/src/main/java/it/binario/app/shared/SharedData.kt")
SWIFT_OUTPUT = Path("ios/BinarioKit/Sources/BinarioKit/Generated/SharedData.swift")
TYPESCRIPT_OUTPUT = Path("alexa-companion/shared/generated/sharedData.ts")
SCHEMA_MARKER = "binario-shared-data-v1"
KOTLIN_PACKAGE = "it.binario.app.shared"
LINE_WIDTH = 120
INDENT = "    "
TYPESCRIPT_INDENT = "  "

KEY_NAME = re.compile(r"[a-z][A-Za-z0-9]*")
TYPE_NAME = re.compile(r"[A-Z][A-Za-z0-9]*")
CONTROL = re.compile(r"[\x00-\x1f\x7f  ]")
# Words no key may take: hard keywords of Kotlin, Swift and TypeScript (a key becomes a property in all three).
RESERVED = frozenset("""
    Any Self actor any as associatedtype async await break case catch class const continue debugger default defer
    deinit delete do else enum export extends extension false fallthrough fileprivate finally for fun func function
    guard if import in init inout instanceof interface internal is let new nil null object open operator package private
    protocol public repeat rethrows return self static struct subscript super switch this throw throws true try
    typealias typeof val var void when where while with yield
""".split())


class SchemaError(ValueError):
    """A shared table that does not follow its schema."""


# --- Schema -------------------------------------------------------------------------------------------------------


class Type:
    """A value type of the schema."""


@dataclass(frozen=True)
class Integer(Type):
    minimum: int | None = None
    maximum: int | None = None


@dataclass(frozen=True)
class Seconds(Integer):
    """Whole seconds: an integer in JSON, Kotlin and TypeScript, a ``Double`` (``TimeInterval``) in Swift."""

    minimum: int | None = 0


@dataclass(frozen=True)
class Real(Type):
    """A finite number (``Double``), such as a coordinate."""

    minimum: float | None = None
    maximum: float | None = None


@dataclass(frozen=True)
class Text(Type):
    """A non-empty single-line string, optionally matching ``pattern`` as a whole."""

    pattern: str | None = None
    max_length: int = 240


@dataclass(frozen=True)
class Boolean(Type):
    pass


@dataclass(frozen=True)
class Nullable(Type):
    inner: Type


@dataclass(frozen=True)
class ListOf(Type):
    """A JSON array; ``as_set`` makes it a set in the code. Entries are unique: equal values, or equal ``unique_by``
    fields for records."""

    item: Type
    as_set: bool = False
    unique_by: tuple[str, ...] = ()
    min_items: int = 1


@dataclass(frozen=True)
class MapOf(Type):
    """A JSON object with free keys, kept in file order (the loader refuses a repeated key)."""

    value: Type
    key: Text = Text()
    min_items: int = 1


REQUIRED = object()


@dataclass(frozen=True)
class Field:
    type: Type
    default: Any = REQUIRED


@dataclass(frozen=True)
class Record(Type):
    """A JSON object with fixed keys; ``notes`` admits an optional ``note`` (``note_key``: a comment in the code, not
    data)."""

    name: str
    fields: Mapping[str, Field]
    notes: bool = False
    note_key: str = "note"


@dataclass(frozen=True)
class RecordValue:
    record: Record
    values: Mapping[str, Any]
    note: str | None = None


@dataclass(frozen=True)
class Table:
    """One JSON file of contracts/shared-data/v1 and the namespace it generates (``name`` in every language)."""

    file: str
    name: str
    fields: Mapping[str, Field]
    alexa: bool = False
    check: Callable[[Mapping[str, Any]], list[str]] | None = None
    # Keys of the file checked but not generated (what the table is about, for the tools that read the JSON itself).
    metadata: Mapping[str, Type] = field(default_factory=dict)

    def records(self) -> list[Record]:
        found: list[Record] = []

        def visit(kind: Type) -> None:
            if isinstance(kind, Record):
                if kind not in found:
                    for nested in kind.fields.values():
                        visit(nested.type)
                    found.append(kind)
            elif isinstance(kind, ListOf):
                visit(kind.item)
            elif isinstance(kind, MapOf):
                visit(kind.value)
            elif isinstance(kind, Nullable):
                visit(kind.inner)

        for item in self.fields.values():
            visit(item.type)
        return found


@dataclass
class LoadedTable:
    table: Table
    description: str
    values: dict[str, Any]


# --- Validation ---------------------------------------------------------------------------------------------------


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise SchemaError(f"chiave ripetuta {key!r}")
        result[key] = value
    return result


def _reject_constant(name: str) -> Any:
    raise SchemaError(f"valore non ammesso {name}")


def parse_json(text: str) -> Any:
    """JSON with no repeated key in any object and no NaN or Infinity."""
    return json.loads(text, object_pairs_hook=_reject_duplicates, parse_constant=_reject_constant)


def _identity(value: Any) -> str:
    return json.dumps(_plain(value), ensure_ascii=False, sort_keys=True)


def _plain(value: Any) -> Any:
    if isinstance(value, RecordValue):
        return {key: _plain(item) for key, item in value.values.items()}
    if isinstance(value, dict):
        return {key: _plain(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_plain(item) for item in value]
    return value


def validate(kind: Type, value: Any, where: str, errors: list[str]) -> Any:
    """``value`` checked against ``kind``, defaults filled in; each problem appended to ``errors``."""
    if isinstance(kind, Integer):
        if type(value) is not int:
            errors.append(f"{where}: atteso un intero, trovato {type(value).__name__}")
        elif kind.minimum is not None and value < kind.minimum:
            errors.append(f"{where}: {value} è minore di {kind.minimum}")
        elif kind.maximum is not None and value > kind.maximum:
            errors.append(f"{where}: {value} è maggiore di {kind.maximum}")
        return value
    if isinstance(kind, Real):
        if type(value) not in (int, float) or not math.isfinite(value):
            errors.append(f"{where}: atteso un numero, trovato {type(value).__name__}")
        elif kind.minimum is not None and value < kind.minimum:
            errors.append(f"{where}: {value} è minore di {kind.minimum}")
        elif kind.maximum is not None and value > kind.maximum:
            errors.append(f"{where}: {value} è maggiore di {kind.maximum}")
        return float(value) if type(value) in (int, float) else value
    if isinstance(kind, Text):
        if not isinstance(value, str):
            errors.append(f"{where}: attesa una stringa, trovato {type(value).__name__}")
        elif not value.strip() or value != value.strip():
            errors.append(f"{where}: stringa vuota o con spazi ai bordi")
        elif CONTROL.search(value):
            errors.append(f"{where}: caratteri di controllo nella stringa")
        elif len(value) > kind.max_length:
            errors.append(f"{where}: oltre {kind.max_length} caratteri")
        elif kind.pattern is not None and not re.fullmatch(kind.pattern, value):
            errors.append(f"{where}: {value!r} non corrisponde a {kind.pattern}")
        return value
    if isinstance(kind, Boolean):
        if type(value) is not bool:
            errors.append(f"{where}: atteso un booleano, trovato {type(value).__name__}")
        return value
    if isinstance(kind, Nullable):
        return None if value is None else validate(kind.inner, value, where, errors)
    if isinstance(kind, ListOf):
        if not isinstance(value, list):
            errors.append(f"{where}: atteso un array, trovato {type(value).__name__}")
            return []
        if len(value) < kind.min_items:
            errors.append(f"{where}: almeno {kind.min_items} elementi")
        items = [validate(kind.item, item, f"{where}[{index}]", errors) for index, item in enumerate(value)]
        seen: dict[str, int] = {}
        for index, item in enumerate(items):
            if kind.unique_by and isinstance(item, RecordValue):
                identity = json.dumps([_plain(item.values.get(key)) for key in kind.unique_by], ensure_ascii=False)
            else:
                identity = _identity(item)
            if identity in seen:
                label = "/".join(kind.unique_by) if kind.unique_by else "valore"
                errors.append(f"{where}[{index}]: {label} ripetuto (già in {where}[{seen[identity]}])")
            else:
                seen[identity] = index
        return items
    if isinstance(kind, MapOf):
        if not isinstance(value, dict):
            errors.append(f"{where}: atteso un oggetto, trovato {type(value).__name__}")
            return {}
        if len(value) < kind.min_items:
            errors.append(f"{where}: almeno {kind.min_items} voci")
        result: dict[str, Any] = {}
        for key, item in value.items():
            validate(kind.key, key, f"{where} chiave {key!r}", errors)
            result[key] = validate(kind.value, item, f"{where}[{key!r}]", errors)
        return result
    if isinstance(kind, Record):
        if not isinstance(value, dict):
            errors.append(f"{where}: atteso un oggetto {kind.name}, trovato {type(value).__name__}")
            return RecordValue(kind, {})
        allowed = set(kind.fields) | ({kind.note_key} if kind.notes else set())
        for key in value:
            if key not in allowed:
                errors.append(f"{where}: chiave non prevista {key!r}")
        values: dict[str, Any] = {}
        for key, item in kind.fields.items():
            if key in value:
                values[key] = validate(item.type, value[key], f"{where}.{key}", errors)
            elif item.default is REQUIRED:
                errors.append(f"{where}: manca la chiave {key!r}")
            else:
                values[key] = copy.deepcopy(item.default)
        note = value.get(kind.note_key) if kind.notes else None
        if note is not None:
            validate(Text(max_length=200), note, f"{where}.{kind.note_key}", errors)
            if isinstance(note, str) and "*/" in note:
                errors.append(f"{where}.{kind.note_key}: «*/» non ammesso in un commento")
        return RecordValue(kind, values, note if isinstance(note, str) else None)
    raise TypeError(f"tipo di schema sconosciuto: {kind!r}")


def load_table(table: Table, root: Path) -> tuple[LoadedTable | None, list[str]]:
    path = root / DATA_DIR / table.file
    where = (DATA_DIR / table.file).as_posix()
    try:
        document = parse_json(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None, [f"{where}: file assente"]
    except (SchemaError, ValueError) as error:
        return None, [f"{where}: JSON non valido ({error})"]
    errors: list[str] = []
    if not isinstance(document, dict):
        return None, [f"{where}: atteso un oggetto JSON"]
    if document.get("schema") != SCHEMA_MARKER:
        errors.append(f"{where}: schema deve essere {SCHEMA_MARKER!r}")
    description = document.get("description")
    validate(Text(max_length=800), description, f"{where}: description", errors)
    if isinstance(description, str) and "*/" in description:
        errors.append(f"{where}: description: «*/» non ammesso in un commento")
    # Optional for every table: its own name (the file's) and the code that reads it.
    if "name" in document and document["name"] != table.file.removesuffix(".json"):
        errors.append(f"{where}: name deve essere {table.file.removesuffix('.json')!r}")
    if "readers" in document:
        validate(MapOf(Text(max_length=300)), document["readers"], f"{where}: readers", errors)
    for key, kind in table.metadata.items():
        if key in document:
            validate(kind, document[key], f"{where}: {key}", errors)
    for key in document:
        if key not in table.fields and key not in {"schema", "description", "name", "readers", *table.metadata}:
            errors.append(f"{where}: chiave non prevista {key!r}")
    values: dict[str, Any] = {}
    for key, item in table.fields.items():
        if key in document:
            values[key] = validate(item.type, document[key], f"{where}: {key}", errors)
        elif item.default is REQUIRED:
            errors.append(f"{where}: manca la chiave {key!r}")
        else:
            values[key] = copy.deepcopy(item.default)
    if not errors and table.check is not None:
        errors += [f"{where}: {problem}" for problem in table.check(values)]
    if errors:
        return None, errors
    return LoadedTable(table, str(description), values), []


def check_registry(tables: Sequence[Table]) -> list[str]:
    """Names usable as identifiers in the three languages, and unique where they meet."""
    problems: list[str] = []
    files: set[str] = set()
    names: set[str] = set()
    records: dict[str, Record] = {}
    for table in tables:
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*(?:-v\d+)?\.json", table.file) or table.file in files:
            problems.append(f"{table.file}: nome di file non valido o ripetuto")
        files.add(table.file)
        if not TYPE_NAME.fullmatch(table.name) or table.name in names or table.name == "SharedData":
            problems.append(f"{table.file}: nome {table.name!r} non valido o ripetuto")
        names.add(table.name)
        # The file's own keys (schema, description, name, readers, the metadata) are no constants.
        keys = [(key, {"schema", "description", "name", "readers", *table.metadata}) for key in table.fields]
        for record in table.records():
            if not TYPE_NAME.fullmatch(record.name) or record.name in names:
                problems.append(f"{table.file}: tipo {record.name!r} non valido o uguale a una tabella")
            if records.get(record.name, record) is not record:
                problems.append(f"{table.file}: tipo {record.name!r} già definito da un'altra tabella")
            records[record.name] = record
            keys += [(key, {"note", record.note_key}) for key in record.fields]
        for key, taken in keys:
            if not KEY_NAME.fullmatch(key) or key in RESERVED or key in taken:
                problems.append(f"{table.file}: chiave {key!r} non utilizzabile come identificatore")
    return problems


# --- Table checks -------------------------------------------------------------------------------------------------


def _check_airport_routes(values: Mapping[str, Any]) -> list[str]:
    packs = [pack.values["id"] for pack in values["packs"]]
    used = {pack for route in values["routes"] for pack in route.values["packs"]}
    problems = [f"routes: pack {pack!r} non dichiarato in packs" for pack in sorted(used - set(packs))]
    problems += [f"packs: {pack!r} non usato da nessuna tratta" for pack in packs if pack not in used]
    problems += [f"routes[{index}]: stazione di città e aeroporto coincidono" for index, route in enumerate(values["routes"])
                 if route.values["city"] == route.values["airport"]]
    return problems


def _check_source_scores(values: Mapping[str, Any]) -> list[str]:
    categories = values["categories"]
    problems = [f"preferences[{index}]: categoria {item.values['category']!r} non in categories"
                for index, item in enumerate(values["preferences"]) if item.values["category"] not in categories]
    owner: dict[str, int] = {}
    for index, rule in enumerate(values["operatorAffinity"]):
        if list(rule.values["pointsByCategory"]) != categories:
            problems.append(f"operatorAffinity[{index}]: pointsByCategory deve elencare categories nello stesso ordine")
        for source in rule.values["sources"]:
            if source in owner:
                problems.append(f"operatorAffinity[{index}]: {source!r} già in operatorAffinity[{owner[source]}]")
            owner[source] = index
    return problems


def _check_holidays(values: Mapping[str, Any]) -> list[str]:
    problems = []
    for index, holiday in enumerate(values["holidays"]):
        month, day = holiday.values["month"], holiday.values["day"]
        if day > calendar.monthrange(2028, month)[1]:  # a leap year: 29 February is a date
            problems.append(f"holidays[{index}]: {day}/{month} non è una data")
    if values["easterOffsetsDays"] != sorted(values["easterOffsetsDays"]):
        problems.append("easterOffsetsDays: in ordine crescente")
    return problems


def _check_planned_journeys(values: Mapping[str, Any]) -> list[str]:
    minutes = values["reminderMinutes"]
    return [] if minutes == sorted(minutes) else ["reminderMinutes: in ordine crescente"]


def _check_italo_gtfs_station_codes(values: Mapping[str, Any]) -> list[str]:
    mapped = {entry.values["code"] for entry in values["codes"]}
    return [f"unmapped: {entry.values['code']!r} è anche in codes" for entry in values["unmapped"]
            if entry.values["code"] in mapped]


def _check_notices_sources(values: Mapping[str, Any]) -> list[str]:
    problems = [] if len(values["months"]) == 12 else ["months: dodici mesi"]
    for index, source in enumerate(values["sources"]):
        if source.values["totalTimeoutSeconds"] < source.values["readTimeoutSeconds"]:
            problems.append(f"sources[{index}]: totalTimeoutSeconds minore di readTimeoutSeconds")
    area = values["trenord"].values["area"].values
    if area["minimumLatitude"] >= area["maximumLatitude"] or area["minimumLongitude"] >= area["maximumLongitude"]:
        problems.append("trenord.area: minimo non minore del massimo")
    return problems


def _check_source_manners(values: Mapping[str, Any]) -> list[str]:
    problems = [] if values["userAgent"].count("<version>") == 1 and values["userAgent"].count("<platform>") == 1 \
        else ["userAgent: un solo <version> e un solo <platform>"]
    if values["baseOpenSeconds"] > values["maximumOpenSeconds"]:
        problems.append("baseOpenSeconds maggiore di maximumOpenSeconds")
    return problems


def _check_delay_compensation(values: Mapping[str, Any]) -> list[str]:
    problems = []
    if values["quarterFromMinutes"] >= values["halfFromMinutes"]:
        problems.append("quarterFromMinutes non minore di halfFromMinutes")
    if values["quarterPercent"] >= values["halfPercent"]:
        problems.append("quarterPercent non minore di halfPercent")
    return problems


def _check_destination_weather(values: Mapping[str, Any]) -> list[str]:
    problems = []
    if values["forecastDays"] > values["maximumDays"]:
        problems.append("forecastDays maggiore di maximumDays")
    if values["readTimeoutSeconds"] > values["totalTimeoutSeconds"]:
        problems.append("readTimeoutSeconds maggiore di totalTimeoutSeconds")
    return problems


def _check_bike_sharing(values: Mapping[str, Any]) -> list[str]:
    problems = []
    for system in values["systems"]:
        fields = system.values
        if not any(fields["attribution"].startswith(word) for word in (fields["name"], fields["name"].split()[0])):
            problems.append(f"{fields['id']}: l'attribuzione non nomina il sistema")
    if values["staticTimeToLiveSeconds"] > values["staticMaximumStaleSeconds"]:
        problems.append("staticTimeToLiveSeconds maggiore di staticMaximumStaleSeconds")
    if values["readTimeoutSeconds"] > values["totalTimeoutSeconds"]:
        problems.append("readTimeoutSeconds maggiore di totalTimeoutSeconds")
    return problems


def _check_live_train_map(values: Mapping[str, Any]) -> list[str]:
    problems = []
    if 60 % values["timetableSlotMinutes"] != 0:
        problems.append("timetableSlotMinutes non divide l'ora")
    if values["positionsRefreshSeconds"] >= values["timetableSlotMinutes"] * 60:
        problems.append("positionsRefreshSeconds non minore dello slot")
    return problems


def _check_evening_alerts(values: Mapping[str, Any]) -> list[str]:
    problems = []
    for key in ("strikeReminderHour", "routeWorksAlertHour"):
        if not values["quietUntilHour"] <= values[key] < values["quietFromHour"]:
            problems.append(f"{key} nelle ore di quiete")
    return problems


def _check_co2_estimate(values: Mapping[str, Any]) -> list[str]:
    problems = []
    if values["planeShortKm"] >= values["planeLongKm"]:
        problems.append("planeShortKm non minore di planeLongKm")
    if not values["trainGramsPerPkm"] < values["carGramsPerPkm"] < values["planeGramsPerPkmLong"] <= values["planeGramsPerPkmShort"]:
        problems.append("fattori non in ordine treno < auto < aereo lungo <= aereo breve")
    return problems


def _check_official_links(values: Mapping[str, Any]) -> list[str]:
    problems = []
    kinds = set(values["linkKinds"])
    for operator in values["operators"]:
        fields = operator.values
        for link in fields["links"]:
            kind, url = link.values["kind"], link.values["url"]
            if kind not in kinds:
                problems.append(f"{fields['id']}: tipo {kind} non in linkKinds")
            if kind == "STRIKES":
                problems.append(f"{fields['id']}: la pagina dei treni garantiti viene da notices-sources-v1.json")
            host = _host(url)
            if not any(host == domain or host.endswith("." + domain) for domain in fields["domains"]):
                problems.append(f"{fields['id']}: {url} fuori dai suoi domini")
    nationals = [operator.values["national"] for operator in values["operators"]]
    if nationals != sorted(nationals, reverse=True):
        problems.append("gli operatori nazionali vengono prima")
    host = _host(values["salaBluPage"])
    if not any(host == domain or host.endswith("." + domain) for domain in values["salaBluDomains"]):
        problems.append("salaBluPage fuori da salaBluDomains")
    return problems


def _check_personal_punctuality(values: Mapping[str, Any]) -> list[str]:
    problems = []
    if not values["onTimeMinutes"] < values["lateMinutes"] < values["severeMinutes"]:
        problems.append("soglie non in ordine onTimeMinutes < lateMinutes < severeMinutes")
    if values["maximumPerTrain"] > values["maximumTotal"]:
        problems.append("maximumPerTrain maggiore di maximumTotal")
    if values["detailedPerTrain"] > values["maximumDetailedTotal"]:
        problems.append("detailedPerTrain maggiore di maximumDetailedTotal")
    return problems


def _check_train_type_groups(values: Mapping[str, Any]) -> list[str]:
    problems = []
    sets = ("highSpeedCodes", "internationalCodes", "intercityCodes", "busCodes")
    for index, first in enumerate(sets):
        for second in sets[index + 1:]:
            if set(values[first]) & set(values[second]):
                problems.append(f"{first} e {second} hanno codici in comune")
    if set(values["italoOperatorIds"]) & set(values["internationalOperatorIds"]):
        problems.append("un operatore sia Italo sia internazionale")
    try:
        re.compile(values["regionalCodePattern"])
    except re.error as error:
        problems.append(f"regionalCodePattern: {error}")
    return problems


def _check_rfi_monitor(values: Mapping[str, Any]) -> list[str]:
    host = "https://iechub.rfi.it/"
    return [f"{key}: fuori da {host}" for key in ("catalogueUrl", "monitorUrl") if not values[key].startswith(host)]


def _check_viaggiatreno(values: Mapping[str, Any]) -> list[str]:
    problems = []
    resources = [values[key] for key in ("departuresResource", "arrivalsResource", "runResource", "numberSearchResource")]
    if len(set(resources)) != len(resources):
        problems.append("le risorse devono essere diverse")
    if not values["baseUrl"].endswith("/"):
        problems.append("baseUrl: termina con /")
    if values["extraordinaryStopType"] == values["suppressedStopType"]:
        problems.append("extraordinaryStopType e suppressedStopType coincidono")
    groups = [set(values[key]) for key in ("trenordClients", "trenitaliaRegionalClients", "trenitaliaLongDistanceClients")]
    for index, group in enumerate(groups):
        for other in groups[index + 1:]:
            problems += [f"codiceCliente {code} in due gruppi" for code in sorted(group & other)]
    return problems


def _check_pack_roles(values: Mapping[str, Any]) -> list[str]:
    both = set(values["nationalPacks"]) & set(values["foreignPacks"])
    errors = [f"{pack!r} è sia nazionale sia estero" for pack in sorted(both)]
    # A city network holds no trains: it is never a railway role.
    railway = set(values["nationalPacks"]) | set(values["foreignPacks"]) | set(values["crossPublishingPacks"]) \
        | set(values["scheduledCoachPacks"])
    errors += [f"{pack!r} è una rete urbana con un ruolo ferroviario" for pack in sorted(set(values["urbanPacks"]) & railway)]
    return errors


def _check_urban_stops(values: Mapping[str, Any]) -> list[str]:
    errors = []
    if values["samePlaceMeters"] > values["platformMeters"]:
        errors.append("samePlaceMeters supera platformMeters")
    if values["walkMarginMinutes"] > 30:
        errors.append("walkMarginMinutes oltre 30 minuti")
    return errors


def _check_realtime_feeds(values: Mapping[str, Any]) -> list[str]:
    problems = []
    for index, feed in enumerate(values["tripUpdates"]):
        feed = feed.values
        # The refusal pause is the feed's own, or the one of every feed of its host (transport.data.gouv.fr).
        if feed["refusalGroup"] != feed["pauseLabel"] and not _host(feed["url"]).endswith(feed["refusalGroup"]):
            problems.append(f"tripUpdates[{index}].refusalGroup: l'etichetta della pausa o il dominio del feed")
        if feed["format"] == "json" and feed["redirects"] != "same-resource":
            problems.append(f"tripUpdates[{index}]: un feed JSON segue solo la stessa risorsa")
    packs = [feed.values["pack"] for feed in values["tripUpdates"]] + [feed.values["pack"] for feed in values["urban"]]
    problems += [f"pack {pack!r} con due feed" for pack in sorted({pack for pack in packs if packs.count(pack) > 1})]
    region = values["swissBoard"].values["borderRegion"].values
    if region["minimumLatitude"] >= region["maximumLatitude"] or region["minimumLongitude"] >= region["maximumLongitude"]:
        problems.append("swissBoard.borderRegion: minimo non minore del massimo")
    return problems


def _host(url: str) -> str:
    return url.split("://", 1)[1].split("/", 1)[0]


# --- Registry -----------------------------------------------------------------------------------------------------

SOURCE_ID = r"[a-z0-9]+(?:-[a-z0-9]+)*"
CATALOGUE_STATION = r"(?:S\d{5}|NC\d{6})"
STATION_NAME = Text(r"[\w .'’()-]+", max_length=96)
COUNT = Integer(1, 1_000_000)

AIRPORT_PACK = Record("Pack", {"id": Field(Text(SOURCE_ID)), "cityLine": Field(Boolean())}, notes=True)
AIRPORT_ROUTE = Record("Route", {
    "city": Field(Text(CATALOGUE_STATION)),
    "airport": Field(Text(CATALOGUE_STATION)),
    "packs": Field(ListOf(Text(SOURCE_ID), as_set=True)),
}, notes=True)
HOLIDAY = Record("Holiday", {
    "month": Field(Integer(1, 12)),
    "day": Field(Integer(1, 31)),
    "fromYear": Field(Nullable(Integer(1900, 9999)), default=None),
}, notes=True)
ITALO_STATION = Record("Station", {
    "code": Field(Text(r"[A-Z0-9_]{3}")),
    "name": Field(STATION_NAME),
    "aliases": Field(ListOf(STATION_NAME, min_items=0), default=[]),
})
SOURCE_PREFERENCE = Record("Preference", {
    "source": Field(Text(SOURCE_ID)),
    "category": Field(Text(r"[A-Z][A-Z_]*")),
    "score": Field(Integer(0, 1_000)),
    "reason": Field(Text()),
}, notes=True)
OPERATOR_AFFINITY = Record("Affinity", {
    "sources": Field(ListOf(Text(SOURCE_ID))),
    "operatorKeywords": Field(ListOf(Text(r"[a-z0-9]+"))),
    "pointsByCategory": Field(MapOf(Integer(0, 1_000), key=Text(r"[A-Z][A-Z_]*"))),
})

ITALO_STATION_CODE = Record("StationCode", {
    "code": Field(Text(r"[A-Z0-9_]{3}")),
    "catalogueId": Field(Text(CATALOGUE_STATION)),
}, notes=True, note_key="comment")
ITALO_UNMAPPED_CODE = Record("UnmappedCode", {"code": Field(Text(r"[A-Z0-9_]{3}"))}, notes=True, note_key="comment")

HTTPS_URL = Text(r"https://[^\s]+", max_length=300)
# A regular expression as Kotlin compiles it; the iPhone compiles the same text with NSRegularExpression.
PATTERN = Text(max_length=1_000)
LENGTH = Integer(1, 1_000_000)
NOTICE_SERVICE = Record("Service", {"sourceTimeoutSeconds": Field(Seconds(1)), "reloadAfterSeconds": Field(Seconds(1))})
NOTICE_SOURCE = Record("Source", {
    "id": Field(Text(SOURCE_ID)),
    "publisher": Field(Nullable(Text(r"[A-Z]+"))),
    "gate": Field(Text(r"all-builds|personal")),
    "url": Field(HTTPS_URL),
    "accept": Field(Text()),
    "connectTimeoutSeconds": Field(Seconds(1)),
    "readTimeoutSeconds": Field(Seconds(1)),
    "totalTimeoutSeconds": Field(Seconds(1)),
    "maximumBytes": Field(Integer(1, 16 * 1_048_576)),
    "maximumRequests": Field(Integer(1, 5)),
    "redirects": Field(Text(r"same-resource|same-https-host")),
    "viaggiaTrenoCleartextHop": Field(Boolean()),
    "minimumIntervalSeconds": Field(Seconds(1)),
    "timeToLiveSeconds": Field(Seconds(1)),
    "maximumStaleSeconds": Field(Seconds()),
    "retryDelayMillis": Field(Nullable(Integer(0, 60_000))),
    "pauseLabel": Field(Nullable(Text())),
})
NOTICE_LINKS = Record("Links", {
    "mitStrikeCalendar": Field(HTTPS_URL),
    "fsNews": Field(HTTPS_URL),
    "guaranteedTrains": Field(MapOf(HTTPS_URL, key=Text(r"[A-Z]+"))),
})
NOTICE_TEXT = Record("TextRules", {
    "maximumInput": Field(LENGTH),
    "noticeTitleLength": Field(LENGTH),
    "noticeBodyLength": Field(LENGTH),
    "strikeWordPattern": Field(PATTERN),
    "urlPattern": Field(PATTERN),
    "urlTrailing": Field(Text()),
    "minimumUrlLength": Field(LENGTH),
    "cleartextOfficialDomains": Field(ListOf(Text(r"[a-z0-9-]+(?:\.[a-z0-9-]+)+"))),
})
NOTICE_RSS = Record("Rss", {"maximumItems": Field(LENGTH), "maximumText": Field(LENGTH)})
NOTICE_STRIKES = Record("Strikes", {
    "railWordsPattern": Field(PATTERN),
    "titleLength": Field(LENGTH),
    "descriptionLength": Field(LENGTH),
    "upcomingShown": Field(LENGTH),
    "undatedRecentDays": Field(LENGTH),
    "startLabelPattern": Field(PATTERN),
    "endLabelPattern": Field(PATTERN),
    "labelWindow": Field(LENGTH),
    "inferNextYearMonthsBack": Field(Integer(0, 11)),
})
NOTICE_TRENITALIA = Record("TrenitaliaRules", {
    "maximumItems": Field(LENGTH),
    "titleLength": Field(LENGTH),
    "descriptionLength": Field(LENGTH),
    "linkLength": Field(LENGTH),
    "maximumTrainTags": Field(LENGTH),
    "trainTagPattern": Field(PATTERN),
})
TRENORD_AREA = Record("TrenordArea", {
    "minimumLatitude": Field(Real(-90, 90)),
    "maximumLatitude": Field(Real(-90, 90)),
    "minimumLongitude": Field(Real(-180, 180)),
    "maximumLongitude": Field(Real(-180, 180)),
})
NOTICE_TRENORD = Record("TrenordRules", {
    "maximumLines": Field(LENGTH),
    "maximumNewsPerLine": Field(LENGTH),
    "textLength": Field(LENGTH),
    "titleLength": Field(LENGTH),
    "lineCodeLength": Field(LENGTH),
    "lineCodePattern": Field(PATTERN),
    "lineDescriptionLength": Field(LENGTH),
    "operationalPattern": Field(PATTERN),
    "stationTokenSeparators": Field(ListOf(Text(max_length=3))),
    "minimumTokenLength": Field(LENGTH),
    "hubLineCount": Field(LENGTH),
    "networkWideMinimumLines": Field(LENGTH),
    "area": Field(TRENORD_AREA),
    "gtfsStationPrefix": Field(Text(r"gtfs:[a-z0-9-]+:")),
})
NOTICE_ROUTE_WORKS = Record("RouteWorksRules", {
    "maximumRoutes": Field(LENGTH),
    "minimumNameLength": Field(LENGTH),
    "worksPattern": Field(PATTERN),
    "linePattern": Field(PATTERN),
    "commonWords": Field(ListOf(Text(r"[a-z]+"))),
})
NOTICE_DISPLAY = Record("Display", {
    "networkCollapsed": Field(LENGTH),
    "bodyCollapsedLines": Field(LENGTH),
    "bodyCollapsedCharacters": Field(LENGTH),
    "maximumLineCodes": Field(LENGTH),
    "worksCollapsed": Field(LENGTH),
})
NOTICE_TRAIN = Record("TrainNoticeRules", {
    "maximumNotes": Field(LENGTH),
    "textLength": Field(LENGTH),
    "maximumTrains": Field(LENGTH),
    "maximumCachedTrains": Field(LENGTH),
})
PACK_SET = ListOf(Text(SOURCE_ID), as_set=True)
CLIENT_CODES = ListOf(Integer(0, 9_999), as_set=True)
RESOURCE = Text(r"[A-Za-z]+/")

BIKE_AREA = Record("Area", {
    "name": Field(Text(r"[\w .'’-]+", max_length=64)),
    "latitude": Field(Real(-90, 90)),
    "longitude": Field(Real(-180, 180)),
    "radiusKm": Field(Real(0.1, 100)),
})
BIKE_SYSTEM = Record("System", {
    "id": Field(Text(r"gbfs-[a-z0-9]+(?:-[a-z0-9]+)*")),
    "name": Field(Text(max_length=64)),
    "operatorName": Field(Text(max_length=96)),
    "discoveryUrl": Field(HTTPS_URL),
    "areas": Field(ListOf(BIKE_AREA, unique_by=("name",))),
    "websiteUrl": Field(HTTPS_URL),
    "licence": Field(Text()),
    "attribution": Field(Text(max_length=96)),
    "personalOnly": Field(Boolean()),
}, notes=True)
MINUTES = Integer(0, 24 * 60)
HOUR = Integer(0, 23)

CO2_FACTOR = Real(0.0, 10_000.0)
LINK_KIND = Text(r"[A-Z_]+", max_length=32)
DOMAIN = Text(r"[a-z0-9.-]+\.[a-z]+", max_length=80)
OPERATOR_LINK = Record("Link", {"kind": Field(LINK_KIND), "url": Field(HTTPS_URL)})
OFFICIAL_OPERATOR = Record("Operator", {
    "id": Field(Text(r"[a-z]+(?:-[a-z]+)*", max_length=40)),
    "name": Field(Text(max_length=64)),
    "national": Field(Boolean()),
    "domains": Field(ListOf(DOMAIN, as_set=True)),
    "links": Field(ListOf(OPERATOR_LINK, unique_by=("kind",))),
    "guaranteedTrains": Field(Nullable(Text(r"[A-Z]+", max_length=32))),
}, notes=True)
OPERATOR_ID = Text(r"[a-z]+(?:-[a-z]+)*", max_length=40)
CATEGORY_CODE = Text(r"[A-Z]+", max_length=8)

TRIP_UPDATES_FEED = Record("TripUpdatesFeed", {
    "id": Field(Text(SOURCE_ID)),
    "pack": Field(Text(SOURCE_ID)),
    "label": Field(Text(max_length=40)),
    "format": Field(Text(r"json|protobuf")),
    "gate": Field(Text(r"all-builds|pack-bundled")),
    "url": Field(HTTPS_URL),
    "accept": Field(Text()),
    "connectTimeoutSeconds": Field(Seconds(1)),
    "readTimeoutSeconds": Field(Seconds(1)),
    "totalTimeoutSeconds": Field(Seconds(1)),
    "maximumBytes": Field(Integer(1, 32 * 1_048_576)),
    "maximumRequests": Field(Integer(1, 5)),
    "redirects": Field(Text(r"same-resource|same-https-host")),
    "timeToLiveSeconds": Field(Seconds(1)),
    "pauseLabel": Field(Text(max_length=60)),
    "refusalGroup": Field(Text(max_length=60)),
    "zone": Field(Text(r"[A-Z][A-Za-z_]+/[A-Z][A-Za-z_]+")),
    "licence": Field(Text()),
}, notes=True)
URBAN_FEED = Record("UrbanFeed", {
    "id": Field(Text(SOURCE_ID)),
    "pack": Field(Text(SOURCE_ID)),
    "displayName": Field(Text(max_length=60)),
    "url": Field(HTTPS_URL),
    "licence": Field(Text()),
    "personalOnly": Field(Boolean()),
    "zone": Field(Text(r"[A-Z][A-Za-z_]+/[A-Z][A-Za-z_]+")),
}, notes=True)
URBAN_FETCH = Record("UrbanFetch", {
    "accept": Field(Text()),
    "connectTimeoutSeconds": Field(Seconds(1)),
    "readTimeoutSeconds": Field(Seconds(1)),
    "totalTimeoutSeconds": Field(Seconds(1)),
    "maximumBytes": Field(Integer(1, 32 * 1_048_576)),
    "maximumRequests": Field(Integer(1, 5)),
    "timeToLiveSeconds": Field(Seconds(30)),
    "maximumStaleSeconds": Field(Seconds()),
    "maximumTrips": Field(LENGTH),
    "maximumShiftSeconds": Field(Seconds(1)),
    "maximumRoutes": Field(LENGTH),
})
BORDER_REGION = Record("BorderRegion", {
    "minimumLatitude": Field(Real(-90, 90)),
    "maximumLatitude": Field(Real(-90, 90)),
    "minimumLongitude": Field(Real(-180, 180)),
    "maximumLongitude": Field(Real(-180, 180)),
})
SWISS_BOARD = Record("SwissBoard", {
    "id": Field(Text(SOURCE_ID)),
    "label": Field(Text(max_length=40)),
    "baseUrl": Field(HTTPS_URL),
    "accept": Field(Text()),
    "connectTimeoutSeconds": Field(Seconds(1)),
    "readTimeoutSeconds": Field(Seconds(1)),
    "totalTimeoutSeconds": Field(Seconds(1)),
    "maximumBytes": Field(Integer(1, 32 * 1_048_576)),
    "maximumRequests": Field(Integer(1, 5)),
    "minimumIntervalSeconds": Field(Seconds(1)),
    "boardTimeToLiveSeconds": Field(Seconds(1)),
    "boardMaximumStaleSeconds": Field(Seconds()),
    "locationTimeToLiveSeconds": Field(Seconds(1)),
    "retryDelayMillis": Field(Integer(0, 60_000)),
    "pauseLabel": Field(Text(max_length=60)),
    "boardLimit": Field(Integer(1, 500)),
    "maximumRows": Field(LENGTH),
    "maximumLocations": Field(LENGTH),
    "maximumDelayMinutes": Field(Integer(1, 1_440)),
    "maximumEarlyArrivalMinutes": Field(Integer(0, 120)),
    "maximumLocationDistanceMeters": Field(Integer(1, 100_000)),
    "currentBoardBeforeMinutes": Field(Integer(0, 1_440)),
    "currentBoardAfterMinutes": Field(Integer(0, 1_440)),
    "zone": Field(Text(r"[A-Z][A-Za-z_]+/[A-Z][A-Za-z_]+")),
    "borderRegion": Field(BORDER_REGION),
    "nonRailCategories": Field(ListOf(Text(r"[A-Z]{1,8}"), as_set=True)),
})

TABLES: tuple[Table, ...] = (
    Table("airport-routes-v1.json", "AirportRoutes", {
        "packs": Field(ListOf(AIRPORT_PACK, unique_by=("id",))),
        "routes": Field(ListOf(AIRPORT_ROUTE, unique_by=("city", "airport"))),
    }, check=_check_airport_routes),
    Table("bike-sharing-v1.json", "BikeSharing", {
        "systems": Field(ListOf(BIKE_SYSTEM, unique_by=("id",))),
        "feedLanguages": Field(ListOf(Text(r"[a-z]{2}"))),
        "radiusMeters": Field(Integer(1, 5_000)),
        "maximumDocks": Field(Integer(1, 50)),
        "maximumVehicles": Field(Integer(1, 50)),
        "staticTimeToLiveSeconds": Field(Seconds(1)),
        "staticMaximumStaleSeconds": Field(Seconds()),
        "staticMaximumEntries": Field(Integer(1, 1_000)),
        "statusTimeToLiveSeconds": Field(Seconds(1)),
        "statusMaximumStaleSeconds": Field(Seconds()),
        "statusMaximumEntries": Field(Integer(1, 1_000)),
        "maximumBytes": Field(Integer(1, 16 * 1_048_576)),
        "readTimeoutSeconds": Field(Seconds(1)),
        "totalTimeoutSeconds": Field(Seconds(1)),
    }, check=_check_bike_sharing),
    Table("co2-estimate-v1.json", "Co2Estimate", {
        "source": Field(Text(max_length=200)),
        "sourceUrl": Field(HTTPS_URL),
        "sourceYear": Field(Integer(2000, 2100)),
        "trainGramsPerPkm": Field(CO2_FACTOR),
        "carGramsPerPkm": Field(CO2_FACTOR),
        "planeGramsPerPkmShort": Field(CO2_FACTOR),
        "planeShortKm": Field(Real(1.0, 20_000.0)),
        "planeGramsPerPkmLong": Field(CO2_FACTOR),
        "planeLongKm": Field(Real(1.0, 20_000.0)),
        "railDetour": Field(Real(1.0, 3.0)),
        "roadDetour": Field(Real(1.0, 3.0)),
        "flightUplift": Field(Real(1.0, 3.0)),
        "minimumKm": Field(Real(0.0, 1_000.0)),
        "earthRadiusKm": Field(Real(6_300.0, 6_400.0)),
    }, check=_check_co2_estimate),
    Table("delay-compensation-v1.json", "DelayCompensation", {
        "quarterFromMinutes": Field(MINUTES),
        "quarterPercent": Field(Integer(1, 100)),
        "halfFromMinutes": Field(MINUTES),
        "halfPercent": Field(Integer(1, 100)),
    }, check=_check_delay_compensation),
    Table("destination-weather-v1.json", "DestinationWeather", {
        "forecastUrl": Field(HTTPS_URL),
        "attributionUrl": Field(HTTPS_URL),
        "dailyVariables": Field(ListOf(Text(r"[a-z0-9_]+"))),
        "timeZone": Field(Text(r"[A-Za-z]+/[A-Za-z_]+")),
        "forecastDays": Field(Integer(1, 16)),
        "coordinateDecimals": Field(Integer(0, 6)),
        "maximumDays": Field(Integer(1, 16)),
        "timeToLiveSeconds": Field(Seconds(1)),
        "maximumStaleSeconds": Field(Seconds()),
        "minimumIntervalSeconds": Field(Seconds(1)),
        "maximumEntries": Field(Integer(1, 1_000)),
        "maximumBytes": Field(Integer(1, 16 * 1_048_576)),
        "readTimeoutSeconds": Field(Seconds(1)),
        "totalTimeoutSeconds": Field(Seconds(1)),
    }, check=_check_destination_weather),
    Table("evening-alerts-v1.json", "EveningAlerts", {
        "strikeReminderHour": Field(HOUR),
        "routeWorksAlertHour": Field(HOUR),
        "quietFromHour": Field(HOUR),
        "quietUntilHour": Field(HOUR),
        "strikeNotifiedRetentionDays": Field(Integer(1, 365)),
        "routeWorksMaximumItems": Field(Integer(1, 20)),
        "routeWorksMaximumKeepDays": Field(Integer(1, 365)),
        "routeWorksMaximumNotified": Field(Integer(1, 10_000)),
    }, check=_check_evening_alerts),
    Table("italian-holidays-v1.json", "ItalianHolidays", {
        "holidays": Field(ListOf(HOLIDAY, unique_by=("month", "day"))),
        "easterOffsetsDays": Field(ListOf(Integer(-60, 60))),
    }, alexa=True, check=_check_holidays),
    Table("italo-gtfs-station-codes.json", "ItaloGtfsStationCodes", {
        "codes": Field(ListOf(ITALO_STATION_CODE, unique_by=("code",))),
        "unmapped": Field(ListOf(ITALO_UNMAPPED_CODE, unique_by=("code",), min_items=0), default=[]),
    }, check=_check_italo_gtfs_station_codes, metadata={"catalogue": Text(r"app/src/main/assets/[\w/.-]+\.json")}),
    Table("italo-stations-v1.json", "ItaloStations", {
        "stations": Field(ListOf(ITALO_STATION, unique_by=("code",))),
        "slugsByName": Field(MapOf(Text(r"[a-z0-9]+(?:-[a-z0-9]+)*"), key=STATION_NAME)),
    }),
    Table("live-train-map-v1.json", "LiveTrainMap", {
        "lookBackMinutes": Field(Integer(0, 720)),
        "lookAheadMinutes": Field(Integer(0, 720)),
        "minimumAppliedDelayMinutes": Field(Integer(-720, 0)),
        "matchMarginMinutes": Field(Integer(0, 60)),
        "sameTrainKilometres": Field(Real(0, 100)),
        "maximumTrains": Field(Integer(1, 500)),
        "areaPacks": Field(Integer(1, 64)),
        "positionsRefreshSeconds": Field(Seconds(1)),
        "timetableSlotMinutes": Field(Integer(1, 60)),
        "stationRadiusKilometres": Field(Real(0.1, 200)),
        "maximumLatitudeSpan": Field(Real(0.01, 90)),
        "maximumLongitudeSpan": Field(Real(0.01, 180)),
        "readAreaSnapDegrees": Field(Real(0.001, 10)),
        "routeMarginShare": Field(Real(0, 1)),
        "minimumRouteMarginDegrees": Field(Real(0, 10)),
        "maximumCodeLength": Field(Integer(1, 32)),
        "maximumLineLabelLength": Field(Integer(1, 32)),
    }, check=_check_live_train_map),
    Table("notices-sources-v1.json", "NoticesSources", {
        "service": Field(NOTICE_SERVICE),
        "sources": Field(ListOf(NOTICE_SOURCE, unique_by=("id",))),
        "links": Field(NOTICE_LINKS),
        "text": Field(NOTICE_TEXT),
        "rss": Field(NOTICE_RSS),
        "strikes": Field(NOTICE_STRIKES),
        "months": Field(ListOf(Text(r"[a-z]+"))),
        "trenitalia": Field(NOTICE_TRENITALIA),
        "trenord": Field(NOTICE_TRENORD),
        "routeWorks": Field(NOTICE_ROUTE_WORKS),
        "display": Field(NOTICE_DISPLAY),
        "trainNotices": Field(NOTICE_TRAIN),
    }, check=_check_notices_sources),
    Table("official-links-v1.json", "OfficialLinks", {
        "linkKinds": Field(ListOf(LINK_KIND)),
        "operators": Field(ListOf(OFFICIAL_OPERATOR, unique_by=("id",))),
        "fsNewsDomains": Field(ListOf(DOMAIN, as_set=True)),
        "salaBluPage": Field(HTTPS_URL),
        "salaBluDomains": Field(ListOf(DOMAIN, as_set=True)),
        "salaBluTollFree": Field(Text(r"[0-9 ]+", max_length=20)),
        "salaBluNational": Field(Text(r"[0-9 ]+", max_length=20)),
        "projectNewIssueUrl": Field(HTTPS_URL),
    }, check=_check_official_links),
    Table("pack-roles-v1.json", "PackRoles", {
        "nationalPacks": Field(PACK_SET),
        "foreignPacks": Field(PACK_SET),
        "crossPublishingPacks": Field(PACK_SET),
        "scheduledCoachPacks": Field(PACK_SET),
        "urbanPacks": Field(PACK_SET),
    }, alexa=True, check=_check_pack_roles),
    Table("personal-punctuality-v1.json", "PersonalPunctuality", {
        "retentionDays": Field(Integer(1, 3_650)),
        "maximumPerTrain": Field(Integer(1, 10_000)),
        "maximumTotal": Field(Integer(1, 100_000)),
        "detailedPerTrain": Field(Integer(0, 10_000)),
        "maximumDetailedTotal": Field(Integer(0, 100_000)),
        "maximumStops": Field(Integer(1, 500)),
        "onTimeMinutes": Field(MINUTES),
        "lateMinutes": Field(MINUTES),
        "severeMinutes": Field(MINUTES),
        "minimumObservations": Field(Integer(1, 100)),
        "minimumStopSamples": Field(Integer(1, 100)),
        "historyCollapsed": Field(Integer(1, 100)),
        "maximumNumberLength": Field(Integer(1, 100)),
        "maximumOperatorLength": Field(Integer(1, 200)),
        "maximumStationLength": Field(Integer(1, 200)),
        "maximumDelayMinutes": Field(MINUTES),
    }, check=_check_personal_punctuality),
    Table("planned-journeys-v1.json", "PlannedJourneys", {
        "schemaVersion": Field(Integer(1, 99)),
        "transferKind": Field(Text(r"[a-z_]+")),
        "maximumJourneys": Field(COUNT),
        "maximumSegments": Field(COUNT),
        "maximumChecklistItems": Field(COUNT),
        "maximumOccurrences": Field(COUNT),
        "maximumLabelLength": Field(COUNT),
        "maximumChecklistLabelLength": Field(COUNT),
        "maximumIdentifierLength": Field(COUNT),
        "maximumTransferBytes": Field(COUNT),
        "maximumNestingDepth": Field(COUNT),
        "reminderMinutes": Field(ListOf(Integer(1, 1_440))),
        "templatesKind": Field(Text(r"[a-z_]+")),
        "maximumTemplates": Field(COUNT),
        "maximumTemplateNameLength": Field(COUNT),
        "maximumTemplatesBytes": Field(COUNT),
    }, check=_check_planned_journeys),
    Table("punctuality-v1.json", "Punctuality", {
        "highSpeedCategories": Field(ListOf(Text(r"[A-Z]{1,8}"), as_set=True)),
        "highSpeedOnTimeMinutes": Field(Integer(0, 60)),
        "otherOnTimeMinutes": Field(Integer(0, 60)),
        "minimumSamples": Field(COUNT),
        "minimumAggregateSamples": Field(COUNT),
        "minimumPlatformDays": Field(COUNT),
        "minimumPlatformSharePercent": Field(Integer(1, 100)),
        "minimumWeekdaySamples": Field(COUNT),
        "minimumWeekdayGapPoints": Field(Integer(1, 100)),
        "maximumWeekdayRemarks": Field(Integer(1, 7)),
        "minimumBandSamples": Field(COUNT),
        "minimumBandGapPoints": Field(Integer(1, 100)),
    }, alexa=True),
    Table("realtime-feeds-v1.json", "RealtimeFeeds", {
        "observationWindowSeconds": Field(Seconds(1)),
        "futureToleranceSeconds": Field(Seconds()),
        "failureMemorySeconds": Field(Seconds(1)),
        "tripUpdates": Field(ListOf(TRIP_UPDATES_FEED, unique_by=("id",))),
        "urban": Field(ListOf(URBAN_FEED, unique_by=("id",))),
        "urbanFetch": Field(URBAN_FETCH),
        "swissBoard": Field(SWISS_BOARD),
    }, check=_check_realtime_feeds),
    Table("rfi-monitor-v1.json", "RfiMonitor", {
        "catalogueUrl": Field(HTTPS_URL),
        "monitorUrl": Field(HTTPS_URL),
        "accept": Field(Text()),
        "connectTimeoutSeconds": Field(Seconds(1)),
        "readTimeoutSeconds": Field(Seconds(1)),
        "totalTimeoutSeconds": Field(Seconds(1)),
        "maximumBytes": Field(Integer(1, 16 * 1_048_576)),
        "minimumIntervalSeconds": Field(Seconds(1)),
        "timeToLiveSeconds": Field(Seconds(1)),
        "catalogueRefreshSeconds": Field(Seconds(1)),
        "catalogueRetrySeconds": Field(Seconds(1)),
    }, check=_check_rfi_monitor),
    Table("rfi-place-ids-v1.json", "RfiPlaceIds", {
        "placeIdsByName": Field(MapOf(Integer(1, 99_999), key=STATION_NAME)),
    }),
    Table("saved-timetables-v1.json", "SavedTimetables", {
        "maximumTimetables": Field(Integer(1, 1_000)),
        "maximumRows": Field(Integer(1, 1_000)),
        "maximumNameLength": Field(Integer(1, 500)),
        "maximumTextLength": Field(Integer(1, 2_000)),
        "maximumTransfers": Field(Integer(0, 100)),
        "maximumArrivalDayOffset": Field(Integer(0, 31)),
        "maximumChanges": Field(Integer(0, 100)),
    }),
    Table("source-manners-v1.json", "SourceManners", {
        "userAgent": Field(Text(r"[\x20-\x7e]+", max_length=120)),
        "refusalStatuses": Field(ListOf(Integer(400, 599), as_set=True)),
        "refusalPauseSeconds": Field(Seconds(1)),
        "maximumRetryAfterSeconds": Field(Seconds(1)),
        "failureThreshold": Field(Integer(1, 100)),
        "baseOpenSeconds": Field(Seconds(1)),
        "maximumOpenSeconds": Field(Seconds(1)),
    }, check=_check_source_manners),
    Table("source-scores-v1.json", "SourceScores", {
        "categories": Field(ListOf(Text(r"[A-Z][A-Z_]*"))),
        "defaultScore": Field(Integer(0, 1_000)),
        "preferences": Field(ListOf(SOURCE_PREFERENCE, unique_by=("source", "category"))),
        "operatorAffinity": Field(ListOf(OPERATOR_AFFINITY)),
        "coverageFallbacks": Field(ListOf(Text(SOURCE_ID), as_set=True)),
    }, check=_check_source_scores),
    Table("train-number-prefixes-v1.json", "TrainNumberPrefixes", {
        # Letters only: each platform joins them into one regular-expression alternation.
        "categories": Field(ListOf(Text(r"[A-Z]{1,16}"))),
    }, alexa=True),
    Table("train-type-groups-v1.json", "TrainTypeGroups", {
        "groups": Field(ListOf(Text(r"[A-Z_]+", max_length=32))),
        "italoOperatorIds": Field(ListOf(OPERATOR_ID, as_set=True)),
        "internationalOperatorIds": Field(ListOf(OPERATOR_ID, as_set=True)),
        "highSpeedCodes": Field(ListOf(CATEGORY_CODE, as_set=True)),
        "internationalCodes": Field(ListOf(CATEGORY_CODE, as_set=True)),
        "intercityCodes": Field(ListOf(CATEGORY_CODE, as_set=True)),
        "busCodes": Field(ListOf(CATEGORY_CODE, as_set=True)),
        "regionalCodePattern": Field(PATTERN),
    }, check=_check_train_type_groups),
    Table("urban-stops-v1.json", "UrbanStops", {
        "maximumWalkMeters": Field(Integer(0, 2_000)),
        "walkingSpeedKmh": Field(Real(1.0, 10.0)),
        "walkMarginMinutes": Field(Integer(0, 60)),
        "samePlaceMeters": Field(Integer(1, 2_000)),
        "platformMeters": Field(Integer(1, 2_000)),
    }, check=_check_urban_stops),
    Table("viaggiatreno-v1.json", "ViaggiaTreno", {
        "baseUrl": Field(HTTPS_URL),
        "departuresResource": Field(RESOURCE),
        "arrivalsResource": Field(RESOURCE),
        "runResource": Field(RESOURCE),
        "numberSearchResource": Field(RESOURCE),
        "connectTimeoutSeconds": Field(Seconds(1)),
        "readTimeoutSeconds": Field(Seconds(1)),
        "totalTimeoutSeconds": Field(Seconds(1)),
        "maximumBytes": Field(Integer(1, 16 * 1_048_576)),
        "maximumRequests": Field(Integer(1, 5)),
        "minimumIntervalSeconds": Field(Seconds(1)),
        "timeToLiveSeconds": Field(Seconds(1)),
        "detailPermits": Field(Integer(1, 16)),
        "runDetailsTimeoutSeconds": Field(Seconds(1)),
        "lookbackTimeoutSeconds": Field(Seconds(1)),
        "maximumAutocompleteLines": Field(LENGTH),
        "maximumRunReferences": Field(LENGTH),
        "maximumBoardRows": Field(LENGTH),
        "numberSearchPastDays": Field(Integer(0, 7)),
        "extraordinaryStopType": Field(Integer(0, 99)),
        "suppressedStopType": Field(Integer(0, 99)),
        "trenordClients": Field(CLIENT_CODES),
        "trenitaliaRegionalClients": Field(CLIENT_CODES),
        "trenitaliaLongDistanceClients": Field(CLIENT_CODES),
    }, check=_check_viaggiatreno),
)


# --- Code emission ------------------------------------------------------------------------------------------------


def constant_name(key: str) -> str:
    """``minimumSamples`` → ``MINIMUM_SAMPLES`` (Kotlin and TypeScript constants)."""
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", key).upper()


def _escape(value: str, *, kotlin: bool) -> str:
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return '"' + (escaped.replace("$", "\\$") if kotlin else escaped) + '"'


class Language:
    """How one language spells the types and values of the schema."""

    indent = INDENT

    def type_name(self, kind: Type) -> str:
        raise NotImplementedError

    def scalar(self, kind: Type, value: Any) -> str:
        raise NotImplementedError

    def brackets(self, kind: Type, empty: bool) -> tuple[str, str]:
        raise NotImplementedError

    def entry(self, key: str, value: str) -> str:
        raise NotImplementedError

    def record_open(self, record: Record) -> str:
        raise NotImplementedError

    def record_close(self) -> str:
        raise NotImplementedError

    def argument(self, name: str, value: str) -> str:
        raise NotImplementedError

    # A value: inline when it fits on its line, else one record or entry per line (records always), plain values
    # filled up to the line width.
    def render(self, kind: Type, value: Any, column: int, depth: int) -> str:
        inline = self.inline(kind, value)
        records = isinstance(kind, ListOf) and isinstance(kind.item, Record)
        flat_record = isinstance(kind, Record) and all(
            isinstance(item.type, (Integer, Real, Text, Boolean, Nullable)) for item in kind.fields.values())
        if (column + len(inline) <= LINE_WIDTH and not records) or flat_record or \
                (isinstance(kind, (ListOf, MapOf)) and not value):
            return inline
        pad = self.indent * (depth + 1)
        if isinstance(kind, ListOf):
            opener, closer = self.brackets(kind, empty=False)
            if records:
                lines = []
                for index, item in enumerate(value):
                    if item.note:
                        lines += [f"{pad}// {line}" for line in _comment(item.note, pad + "// ")]
                    rendered = self.render(kind.item, item, len(pad), depth + 1)
                    lines.append(pad + rendered + ("," if index < len(value) - 1 else ""))
                return opener + "\n" + "\n".join(lines) + "\n" + self.indent * depth + closer
            items = [self.inline(kind.item, item) for item in value]
            lines, line = [], pad
            for index, item in enumerate(items):
                piece = item + ("," if index < len(items) - 1 else "")
                if line != pad and len(line) + 1 + len(piece) > LINE_WIDTH:
                    lines.append(line.rstrip())
                    line = pad
                line += ("" if line == pad else " ") + piece
            lines.append(line)
            return opener + "\n" + "\n".join(lines) + "\n" + self.indent * depth + closer
        if isinstance(kind, MapOf):
            opener, closer = self.brackets(kind, empty=False)
            entries = list(value.items())
            lines = [pad + self.entry(self.scalar(Text(), key), self.render(kind.value, item, len(pad), depth + 1))
                     + ("," if index < len(entries) - 1 else "") for index, (key, item) in enumerate(entries)]
            return opener + "\n" + "\n".join(lines) + "\n" + self.indent * depth + closer
        if isinstance(kind, Record):
            arguments = [self.argument(name, self.render(item.type, value.values[name], len(pad) + len(name) + 3, depth + 1))
                         for name, item in kind.fields.items()]
            return self.record_open(kind) + "\n" + ",\n".join(pad + argument for argument in arguments) + "\n" + \
                self.indent * depth + self.record_close()
        return inline

    def inline(self, kind: Type, value: Any) -> str:
        if isinstance(kind, Nullable):
            return self.null() if value is None else self.inline(kind.inner, value)
        if isinstance(kind, ListOf):
            opener, closer = self.brackets(kind, empty=not value)
            return opener + ", ".join(self.inline(kind.item, item) for item in value) + closer
        if isinstance(kind, MapOf):
            opener, closer = self.brackets(kind, empty=not value)
            return opener + ", ".join(self.entry(self.scalar(Text(), key), self.inline(kind.value, item))
                                      for key, item in value.items()) + closer
        if isinstance(kind, Record):
            return self.record_open(kind) + ", ".join(
                self.argument(name, self.inline(item.type, value.values[name])) for name, item in kind.fields.items()
            ) + self.record_close()
        return self.scalar(kind, value)

    def null(self) -> str:
        raise NotImplementedError


class Kotlin(Language):
    def type_name(self, kind: Type) -> str:
        if isinstance(kind, Integer):
            return "Int"
        if isinstance(kind, Real):
            return "Double"
        if isinstance(kind, Text):
            return "String"
        if isinstance(kind, Boolean):
            return "Boolean"
        if isinstance(kind, Nullable):
            return self.type_name(kind.inner) + "?"
        if isinstance(kind, ListOf):
            return f"{'Set' if kind.as_set else 'List'}<{self.type_name(kind.item)}>"
        if isinstance(kind, MapOf):
            return f"Map<String, {self.type_name(kind.value)}>"
        if isinstance(kind, Record):
            return kind.name
        raise TypeError(kind)

    def scalar(self, kind: Type, value: Any) -> str:
        if isinstance(kind, Boolean):
            return "true" if value else "false"
        if isinstance(kind, Text):
            return _escape(value, kotlin=True)
        return repr(float(value)) if isinstance(kind, Real) else str(value)

    def null(self) -> str:
        return "null"

    def brackets(self, kind: Type, empty: bool) -> tuple[str, str]:
        if isinstance(kind, MapOf):
            return ("emptyMap(", ")") if empty else ("mapOf(", ")")
        name = "Set" if isinstance(kind, ListOf) and kind.as_set else "List"
        return (f"empty{name}(", ")") if empty else (f"{name.lower()}Of(", ")")

    def entry(self, key: str, value: str) -> str:
        return f"{key} to {value}"

    def record_open(self, record: Record) -> str:
        return f"{record.name}("

    def record_close(self) -> str:
        return ")"

    def argument(self, name: str, value: str) -> str:
        return f"{name} = {value}"


class Swift(Language):
    def type_name(self, kind: Type) -> str:
        if isinstance(kind, (Seconds, Real)):
            return "Double"
        if isinstance(kind, Integer):
            return "Int"
        if isinstance(kind, Text):
            return "String"
        if isinstance(kind, Boolean):
            return "Bool"
        if isinstance(kind, Nullable):
            return self.type_name(kind.inner) + "?"
        if isinstance(kind, ListOf):
            return f"Set<{self.type_name(kind.item)}>" if kind.as_set else f"[{self.type_name(kind.item)}]"
        if isinstance(kind, MapOf):
            return f"[String: {self.type_name(kind.value)}]"
        if isinstance(kind, Record):
            return kind.name
        raise TypeError(kind)

    def scalar(self, kind: Type, value: Any) -> str:
        if isinstance(kind, Boolean):
            return "true" if value else "false"
        if isinstance(kind, Text):
            return _escape(value, kotlin=False)
        return repr(float(value)) if isinstance(kind, Real) else str(value)

    def null(self) -> str:
        return "nil"

    def brackets(self, kind: Type, empty: bool) -> tuple[str, str]:
        if isinstance(kind, MapOf) and empty:
            return "[:", "]"
        return "[", "]"

    def entry(self, key: str, value: str) -> str:
        return f"{key}: {value}"

    def record_open(self, record: Record) -> str:
        return f"{record.name}("

    def record_close(self) -> str:
        return ")"

    def argument(self, name: str, value: str) -> str:
        return f"{name}: {value}"


class TypeScript(Language):
    indent = TYPESCRIPT_INDENT

    def type_name(self, kind: Type) -> str:
        if isinstance(kind, (Integer, Real)):
            return "number"
        if isinstance(kind, Text):
            return "string"
        if isinstance(kind, Boolean):
            return "boolean"
        if isinstance(kind, Nullable):
            return self.type_name(kind.inner) + " | null"
        if isinstance(kind, ListOf):
            item = self.type_name(kind.item)
            if kind.as_set:
                return f"ReadonlySet<{item}>"
            return f"readonly ({item})[]" if " | " in item else f"readonly {item}[]"
        if isinstance(kind, MapOf):
            return f"ReadonlyMap<string, {self.type_name(kind.value)}>"
        if isinstance(kind, Record):
            return kind.name
        raise TypeError(kind)

    def scalar(self, kind: Type, value: Any) -> str:
        if isinstance(kind, Boolean):
            return "true" if value else "false"
        if isinstance(kind, Text):
            return json.dumps(value, ensure_ascii=False)
        return repr(float(value)) if isinstance(kind, Real) else str(value)

    def null(self) -> str:
        return "null"

    def brackets(self, kind: Type, empty: bool) -> tuple[str, str]:
        if isinstance(kind, MapOf):
            return f"new Map<string, {self.type_name(kind.value)}>([", "])"
        if isinstance(kind, ListOf) and kind.as_set:
            return f"new Set<{self.type_name(kind.item)}>([", "])"
        return "[", "]"

    def entry(self, key: str, value: str) -> str:
        return f"[{key}, {value}]"

    def record_open(self, record: Record) -> str:
        return "{ "

    def record_close(self) -> str:
        return " }"

    def argument(self, name: str, value: str) -> str:
        return f"{name}: {value}"

    def render(self, kind: Type, value: Any, column: int, depth: int) -> str:
        # Object literals stay on one line: the records of the skill's tables are short.
        if isinstance(kind, Record):
            return self.inline(kind, value)
        return super().render(kind, value, column, depth)


def _comment(text: str, prefix: str, width: int = LINE_WIDTH) -> list[str]:
    return textwrap.wrap(text, width=width - len(prefix), break_long_words=False, break_on_hyphens=False) or [""]


def header(sources: Sequence[str]) -> list[str]:
    return [
        f"// Generated by {GENERATOR}. Do not edit: change the tables, then run",
        f"// `python3 {GENERATOR}` (contracts/shared-data/README.md). Sources:",
    ] + [f"//   {source}" for source in sources]


def _doc(lines: list[str], indent: str, text: str, *, kotlin: bool) -> list[str]:
    if kotlin:
        wrapped = _comment(text, indent + " * ")
        if len(wrapped) == 1 and len(indent) + 7 + len(wrapped[0]) <= LINE_WIDTH:
            return lines + [f"{indent}/** {wrapped[0]} */"]
        return lines + [f"{indent}/**"] + [f"{indent} * {line}" for line in wrapped] + [f"{indent} */"]
    return lines + [f"{indent}/// {line}" for line in _comment(text, indent + "/// ")]


def table_doc(loaded: LoadedTable) -> str:
    return f"{(DATA_DIR / loaded.table.file).as_posix()}: {loaded.description}"


def kotlin_source(tables: Sequence[LoadedTable]) -> str:
    language = Kotlin()
    lines = header([(DATA_DIR / loaded.table.file).as_posix() for loaded in tables])
    lines += ["", f"package {KOTLIN_PACKAGE}", ""]
    lines = _doc(lines, "", "The tables of contracts/shared-data/v1, one object per file: the common base of the apps, "
                 "read as code (docs/architecture/ADR-0002-BASE-COMUNE.md).", kotlin=True)
    lines.append("internal object SharedData {")
    for position, loaded in enumerate(tables):
        if position:
            lines.append("")
        lines = _doc(lines, INDENT, table_doc(loaded), kotlin=True)
        lines.append(f"{INDENT}object {loaded.table.name} {{")
        for record in loaded.table.records():
            fields = ", ".join(f"val {name}: {language.type_name(item.type)}" for name, item in record.fields.items())
            declaration = f"{INDENT * 2}data class {record.name}({fields})"
            if len(declaration) > LINE_WIDTH:
                declaration = f"{INDENT * 2}data class {record.name}(\n" + ",\n".join(
                    f"{INDENT * 3}val {name}: {language.type_name(item.type)}" for name, item in record.fields.items()
                ) + f"\n{INDENT * 2})"
            lines.append(declaration)
        if loaded.table.records():
            lines.append("")
        for key, item in loaded.table.fields.items():
            value = loaded.values[key]
            scalar = isinstance(item.type, (Integer, Real, Text, Boolean))
            prefix = f"{INDENT * 2}{'const val' if scalar else 'val'} {constant_name(key)}: {language.type_name(item.type)} = "
            lines.append(prefix + language.render(item.type, value, len(prefix), 2))
        lines.append(f"{INDENT}}}")
    lines.append("}")
    return "\n".join(lines) + "\n"


def swift_source(tables: Sequence[LoadedTable]) -> str:
    language = Swift()
    lines = header([(DATA_DIR / loaded.table.file).as_posix() for loaded in tables])
    lines.append("")
    lines = _doc(lines, "", "The tables of contracts/shared-data/v1, one namespace per file: the common base of the apps, "
                 "read as code (docs/architecture/ADR-0002-BASE-COMUNE.md).", kotlin=False)
    lines.append("public enum SharedData {")
    for position, loaded in enumerate(tables):
        if position:
            lines.append("")
        lines = _doc(lines, INDENT, table_doc(loaded), kotlin=False)
        lines.append(f"{INDENT}public enum {loaded.table.name} {{")
        for record in loaded.table.records():
            lines.append(f"{INDENT * 2}public struct {record.name}: Hashable, Sendable {{")
            lines += [f"{INDENT * 3}public let {name}: {language.type_name(item.type)}" for name, item in record.fields.items()]
            lines += [f"{INDENT * 2}}}", ""]
        for key, item in loaded.table.fields.items():
            prefix = f"{INDENT * 2}public static let {key}: {language.type_name(item.type)} = "
            lines.append(prefix + language.render(item.type, loaded.values[key], len(prefix), 2))
        lines.append(f"{INDENT}}}")
    lines.append("}")
    return "\n".join(lines) + "\n"


def typescript_source(tables: Sequence[LoadedTable]) -> str:
    language = TypeScript()
    pad = TYPESCRIPT_INDENT
    lines = header([(DATA_DIR / loaded.table.file).as_posix() for loaded in tables])
    for loaded in tables:
        lines.append("")
        for record in loaded.table.records():
            fields = "; ".join(f"readonly {name}: {language.type_name(item.type)}" for name, item in record.fields.items())
            lines.append(f"export type {record.name} = {{ {fields} }};")
            lines.append("")
        lines += _ts_doc(table_doc(loaded))
        lines.append(f"export const {loaded.table.name}: {{")
        lines += [f"{pad}readonly {constant_name(key)}: {language.type_name(item.type)};" for key, item in loaded.table.fields.items()]
        lines.append("} = Object.freeze({")
        for key, item in loaded.table.fields.items():
            prefix = f"{pad}{constant_name(key)}: "
            lines.append(prefix + language.render(item.type, loaded.values[key], len(prefix), 1) + ",")
        lines.append("});")
    return "\n".join(lines) + "\n"


def _ts_doc(text: str) -> list[str]:
    wrapped = _comment(text, " * ")
    return ["/**"] + [f" * {line}" for line in wrapped] + [" */"]


# --- Driver -------------------------------------------------------------------------------------------------------


def load_tables(root: Path, tables: Sequence[Table] = TABLES) -> tuple[list[LoadedTable], list[str]]:
    errors = check_registry(tables)
    loaded: list[LoadedTable] = []
    for table in tables:
        result, problems = load_table(table, root)
        errors += problems
        if result is not None:
            loaded.append(result)
    return loaded, errors


def table_values(file: str, root: Path = ROOT) -> dict[str, Any]:
    """The values of the registered table ``file`` as plain JSON values, checked against its schema: how the pipeline
    (tools/) reads a shared table, as the apps read the generated code."""
    table = next(item for item in TABLES if item.file == file)
    loaded, errors = load_table(table, root)
    if loaded is None:
        raise SchemaError("; ".join(errors))
    return {key: _plain(value) for key, value in loaded.values.items()}


def render_outputs(loaded: Sequence[LoadedTable]) -> dict[Path, str]:
    return {
        KOTLIN_OUTPUT: kotlin_source(loaded),
        SWIFT_OUTPUT: swift_source(loaded),
        TYPESCRIPT_OUTPUT: typescript_source([table for table in loaded if table.table.alexa]),
    }


def unregistered(root: Path, tables: Sequence[Table] = TABLES) -> list[str]:
    known = {table.file for table in tables}
    directory = root / DATA_DIR
    return sorted(path.name for path in directory.glob("*.json") if path.name not in known) if directory.is_dir() else []


def stale_outputs(root: Path, outputs: Mapping[Path, str]) -> list[str]:
    stale = []
    for path, text in outputs.items():
        target = root / path
        if not target.is_file() or target.read_text(encoding="utf-8") != text:
            stale.append(path.as_posix())
    return stale


def run(root: Path, *, check: bool, tables: Sequence[Table] = TABLES, out=sys.stdout, err=sys.stderr) -> int:
    loaded, errors = load_tables(root, tables)
    for name in unregistered(root, tables):
        print(f"nota: {(DATA_DIR / name).as_posix()} non è registrato in {GENERATOR} (TABLES): nessun codice generato",
              file=err)
    if errors:
        for error in errors:
            print(f"ERRORE: {error}", file=err)
        print("Nessun file generato: correggere le tabelle.", file=err)
        return 1
    outputs = render_outputs(loaded)
    stale = stale_outputs(root, outputs)
    if check:
        for path in stale:
            print(f"ERRORE: {path} non corrisponde alle tabelle: eseguire python3 {GENERATOR}", file=err)
        if not stale:
            print(f"Codice condiviso aggiornato: {len(loaded)} tabelle, {len(outputs)} file generati", file=out)
        return 1 if stale else 0
    for path in stale:
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(outputs[Path(path)], encoding="utf-8")
        print(f"scritto {path}", file=out)
    if not stale:
        print(f"Nessuna modifica: {len(loaded)} tabelle, {len(outputs)} file generati già aggiornati", file=out)
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="non scrive nulla; esce con 1 se un file generato non è aggiornato")
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    return run(args.root, check=args.check)


if __name__ == "__main__":
    raise SystemExit(main())
