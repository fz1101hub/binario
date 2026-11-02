#!/usr/bin/env python3
"""Convert an Italian-profile NeTEx timetable (level 1, EPIP) into a GTFS archive, with the standard library only.

The Italian National Access Point (CCISS, https://www.cciss.it/nap/) publishes operators' timetables as NeTEx
(Italian profile, level 1): one gzip-compressed XML or a ZIP of XML files.  Binario embeds GTFS, so this tool turns
the subset of NeTEx a journey planner needs into GTFS tables; ``tools/build_gtfs_packs.py`` then filters the result
like any other GTFS source.  The input is untrusted data: run with ``python3 -I`` from the repository root, outside
the directory that holds the download.  Nothing in the input is executed and no entity is resolved (the parser of
``xml.etree`` never fetches external DTDs or entities, and DOCTYPEs are rejected outright).

Mapping (NeTEx element -> GTFS):

* ``Operator`` -> agency.txt (``Name``, ``ContactDetails/Url``);
* ``StopPlace`` -> stops.txt station (location_type 1, ``PrivateCode`` as stop_code, ``Centroid/Location``);
  ``ScheduledStopPoint`` -> stops.txt stop (location_type 0) whose parent is the StopPlace (or the Quay's StopPlace)
  that its ``PassengerStopAssignment`` names; an unassigned point is a stop of its own;
* ``Line`` -> routes.txt (``PublicCode``/``ShortName``, ``Name``, ``TransportMode`` -> route_type);
* ``ServiceJourneyPattern``/``JourneyPattern`` with ``StopPointInJourneyPattern`` -> the stop sequence and the
  ``ForBoarding``/``ForAlighting`` restrictions (pickup_type/drop_off_type 1);
* ``ServiceJourney`` (and ``VehicleJourney``) with ``TimetabledPassingTime`` -> trips.txt and stop_times.txt
  (``Name`` or ``PublicCode`` as trip_short_name, day offsets as times past 24:00);
* ``DayType`` + ``DayTypeAssignment`` with ``UicOperatingPeriod`` (``ValidDayBits``), ``OperatingPeriod``
  (``FromDate``..``ToDate`` and the DayType's ``DaysOfWeek``), ``OperatingDay`` or ``Date`` -> the dates of every
  day type; a journey runs on the union of its day types.  Journeys with the same dates share one service, written
  as calendar.txt (the weekday pattern of its date range) plus calendar_dates.txt exceptions, or as plain
  calendar_dates.txt when that is shorter.

Usage::

    python3 -I tools/netex_to_gtfs.py INPUT(.xml|.xml.gz|.zip) OUTPUT.zip [--source-label TEXT]
"""
from __future__ import annotations

import argparse
import csv
import gzip
import io
import json
import sys
import zipfile
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path
from typing import Callable, Iterable, Iterator
import xml.etree.ElementTree as ET

MAX_INPUT_MEMBERS = 4096
MAX_UNCOMPRESSED_BYTES = 6 * 1024 * 1024 * 1024
MAX_DAYS = 3 * 366
TIMEZONE = "Europe/Rome"

ROUTE_TYPES = {
    "rail": 2, "intercityrail": 2, "urbanrail": 2, "suburbanrail": 2,
    "metro": 1, "tram": 0, "bus": 3, "coach": 3, "trolleybus": 11, "water": 4, "ferry": 4,
    "cableway": 6, "funicular": 7, "lift": 6, "air": 1100, "taxi": 1500,
}
WEEKDAYS = ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
DAY_GROUPS = {
    "Weekdays": set(range(5)), "Weekend": {5, 6}, "Everyday": set(range(7)),
    **{name: {index} for index, name in enumerate(WEEKDAYS)},
}


class ConversionError(RuntimeError):
    """Raised for an input this converter cannot turn into a coherent GTFS archive."""


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def child(element: ET.Element, name: str) -> ET.Element | None:
    for item in element:
        if local(item.tag) == name:
            return item
    return None


def text(element: ET.Element | None, *path: str) -> str:
    current = element
    for name in path:
        if current is None:
            return ""
        current = child(current, name)
    return (current.text or "").strip() if current is not None else ""


def ref(element: ET.Element | None, name: str) -> str:
    found = child(element, name) if element is not None else None
    return (found.get("ref") or "").strip() if found is not None else ""


def descendants(element: ET.Element, name: str) -> Iterator[ET.Element]:
    for item in element.iter():
        if local(item.tag) == name:
            yield item


def coordinates(element: ET.Element) -> tuple[str, str]:
    for holder in ("Centroid", "Location"):
        found = child(element, holder)
        if found is None:
            continue
        location = child(found, "Location") if holder == "Centroid" else found
        latitude = text(location, "Latitude")
        longitude = text(location, "Longitude")
        if latitude and longitude:
            return latitude, longitude
    return "", ""


def parse_date(value: str) -> date | None:
    value = value.strip()[:10]
    try:
        return date.fromisoformat(value)
    except ValueError:
        return None


def xml_streams(path: Path) -> Callable[[], Iterator[tuple[str, io.BufferedIOBase]]]:
    """Every XML document of the input, as a re-openable sequence (the conversion reads them twice)."""
    with path.open("rb") as probe:
        magic = probe.read(4)
    if magic[:2] == b"\x1f\x8b":
        def gz() -> Iterator[tuple[str, io.BufferedIOBase]]:
            with gzip.open(path, "rb") as stream:
                yield path.name, stream  # type: ignore[misc]
        return gz
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            members = [info for info in archive.infolist() if not info.is_dir()]
            if len(members) > MAX_INPUT_MEMBERS:
                raise ConversionError(f"troppi file nell'archivio NeTEx: {len(members)}")
            if sum(info.file_size for info in members) > MAX_UNCOMPRESSED_BYTES:
                raise ConversionError("archivio NeTEx decompresso oltre il limite")
            names = sorted(info.filename for info in members if info.filename.lower().endswith(".xml"))
        if not names:
            raise ConversionError("archivio NeTEx senza file XML")

        def zipped() -> Iterator[tuple[str, io.BufferedIOBase]]:
            with zipfile.ZipFile(path) as archive:
                for name in names:
                    with archive.open(name) as stream:
                        yield name, stream  # type: ignore[misc]
        return zipped

    def plain() -> Iterator[tuple[str, io.BufferedIOBase]]:
        with path.open("rb") as stream:
            yield path.name, stream  # type: ignore[misc]
    return plain


class GuardedStream(io.RawIOBase):
    """Reject a DOCTYPE before the parser sees it: NeTEx needs none, and entities could expand the input."""

    def __init__(self, raw: io.BufferedIOBase) -> None:
        self.raw = raw
        self.head = b""
        self.checked = False

    def readable(self) -> bool:
        return True

    def readinto(self, buffer) -> int:  # type: ignore[override]
        data = self.raw.read(len(buffer))
        if not self.checked:
            self.head += data
            if b"<!DOCTYPE" in self.head.upper() or b"<!ENTITY" in self.head.upper():
                raise ConversionError("NeTEx con DOCTYPE/ENTITY non ammesso")
            if len(self.head) > 65536:
                self.checked = True
                self.head = b""
        size = len(data)
        buffer[:size] = data
        return size


@dataclass
class Pattern:
    route: str = ""
    line: str = ""
    points: list[tuple[str, str, bool, bool]] = field(default_factory=list)  # (point id, ssp, boarding, alighting)


@dataclass
class Model:
    operators: dict[str, tuple[str, str]] = field(default_factory=dict)
    stop_places: dict[str, tuple[str, str, str, str]] = field(default_factory=dict)  # name, code, lat, lon
    quays: dict[str, tuple[str, str, str, str, str]] = field(default_factory=dict)  # place, name, code, lat, lon
    points: dict[str, tuple[str, str, str, str]] = field(default_factory=dict)  # name, code, lat, lon
    assignments: dict[str, tuple[str, str]] = field(default_factory=dict)  # ssp -> (stop place, quay)
    lines: dict[str, dict[str, str]] = field(default_factory=dict)
    routes: dict[str, str] = field(default_factory=dict)  # route -> line
    patterns: dict[str, Pattern] = field(default_factory=dict)
    point_in_pattern: dict[str, tuple[str, bool, bool]] = field(default_factory=dict)
    products: dict[str, str] = field(default_factory=dict)
    day_type_weekdays: dict[str, set[int] | None] = field(default_factory=dict)
    uic_periods: dict[str, tuple[date, str]] = field(default_factory=dict)
    periods: dict[str, tuple[date, date]] = field(default_factory=dict)
    operating_days: dict[str, date] = field(default_factory=dict)
    day_type_assignments: list[tuple[int, str, str, str, date | None, bool]] = field(default_factory=list)


# NeTEx objects whose subtree is never needed once they end (or is read when they end): they are dropped from the
# tree right away, so memory stays bounded by one object rather than by the whole document (Trenitalia publishes
# 232 000 ServiceLink geometries the timetable does not need).
RELEASED = frozenset({
    "ServiceLink", "TimingLink", "RoutePoint", "RouteLink", "PointProjection", "Level", "Notice", "NoticeAssignment",
    "DestinationDisplay", "Network", "GroupOfLines", "Direction", "Authority", "Operator", "StopPlace", "Quay",
    "ScheduledStopPoint", "PassengerStopAssignment", "Line", "Route", "ServiceJourneyPattern", "JourneyPattern",
    "StopPointInJourneyPattern", "TypeOfProductCategory", "DayType", "UicOperatingPeriod", "OperatingPeriod",
    "OperatingDay", "DayTypeAssignment", "ServiceJourney", "VehicleJourney", "TimetabledPassingTime",
    "TariffZone", "TopographicPlace", "Interchange", "ServiceJourneyInterchange", "Block", "Train", "TrainNumber",
})


def iter_objects(stream: io.BufferedIOBase, wanted: set[str]) -> Iterator[tuple[str, ET.Element, list[tuple[str, str]]]]:
    """(local name, element, open ancestors as (name, id)) for every wanted object; objects are freed once ended."""
    parser = ET.iterparse(io.BufferedReader(GuardedStream(stream), 1 << 20), events=("start", "end"))
    stack: list[ET.Element] = []
    context: list[tuple[str, str]] = []
    for event, element in parser:
        if event == "start":
            stack.append(element)
            context.append((local(element.tag), element.get("id") or ""))
            continue
        stack.pop()
        name, _ = context.pop()
        if name in wanted:
            yield name, element, context
        if name in wanted or name in RELEASED:
            if stack:
                try:
                    stack[-1].remove(element)
                except ValueError:
                    pass
            element.clear()


def first_pass(streams: Callable[[], Iterator[tuple[str, io.BufferedIOBase]]]) -> Model:
    model = Model()
    wanted = {
        "Operator", "StopPlace", "Quay", "ScheduledStopPoint", "PassengerStopAssignment", "Line", "Route",
        "ServiceJourneyPattern", "JourneyPattern", "StopPointInJourneyPattern", "TypeOfProductCategory", "DayType",
        "UicOperatingPeriod", "OperatingPeriod", "OperatingDay", "DayTypeAssignment",
    }
    pending_points: dict[str, list[tuple[str, str, bool, bool, int]]] = defaultdict(list)
    for _, stream in streams():
        for name, element, context in iter_objects(stream, wanted):
            identifier = element.get("id") or ""
            if name == "Operator":
                model.operators[identifier] = (text(element, "Name") or text(element, "ShortName") or identifier,
                                               text(element, "ContactDetails", "Url"))
            elif name == "StopPlace":
                latitude, longitude = coordinates(element)
                model.stop_places[identifier] = (text(element, "Name"), text(element, "PrivateCode"), latitude, longitude)
            elif name == "Quay":
                place = next((owner for kind, owner in reversed(context) if kind == "StopPlace"), "")
                latitude, longitude = coordinates(element)
                model.quays[identifier] = (place, text(element, "Name"), text(element, "PrivateCode"), latitude, longitude)
            elif name == "ScheduledStopPoint":
                latitude, longitude = coordinates(element)
                model.points[identifier] = (text(element, "Name"), text(element, "PrivateCode"), latitude, longitude)
            elif name == "PassengerStopAssignment":
                point = ref(element, "ScheduledStopPointRef")
                if point:
                    model.assignments[point] = (ref(element, "StopPlaceRef"), ref(element, "QuayRef"))
            elif name == "Line":
                model.lines[identifier] = {
                    "name": text(element, "Name"),
                    "short": text(element, "ShortName"),
                    "public": text(element, "PublicCode"),
                    "mode": text(element, "TransportMode"),
                    "operator": ref(element, "OperatorRef"),
                    "product": ref(element, "TypeOfProductCategoryRef"),
                }
            elif name == "Route":
                model.routes[identifier] = ref(element, "LineRef")
            elif name == "StopPointInJourneyPattern":
                pattern = next((owner for kind, owner in reversed(context) if kind in {"ServiceJourneyPattern", "JourneyPattern"}), "")
                point = ref(element, "ScheduledStopPointRef")
                boarding = text(element, "ForBoarding").lower() != "false"
                alighting = text(element, "ForAlighting").lower() != "false"
                order = int(element.get("order") or len(pending_points[pattern]) + 1)
                model.point_in_pattern[identifier] = (point, boarding, alighting)
                pending_points[pattern].append((identifier, point, boarding, alighting, order))
            elif name in {"ServiceJourneyPattern", "JourneyPattern"}:
                route = ref(element, "RouteRef")
                # The Italian profile names the line in RouteView (Trenitalia), others directly or through the route.
                line = ref(element, "LineRef") or ref(child(element, "RouteView"), "LineRef")
                points = sorted(pending_points.pop(identifier, []), key=lambda item: item[4])
                model.patterns[identifier] = Pattern(route, line, [(item[0], item[1], item[2], item[3]) for item in points])
            elif name == "TypeOfProductCategory":
                model.products[identifier] = text(element, "ShortName") or text(element, "Name")
            elif name == "DayType":
                days: set[int] | None = None
                for property_element in descendants(element, "DaysOfWeek"):
                    tokens = (property_element.text or "").split()
                    days = (days or set()) | {index for token in tokens for index in DAY_GROUPS.get(token, set())}
                model.day_type_weekdays[identifier] = days
            elif name == "UicOperatingPeriod":
                start = parse_date(text(element, "FromDate"))
                bits = text(element, "ValidDayBits")
                if start and bits:
                    model.uic_periods[identifier] = (start, bits)
            elif name == "OperatingPeriod":
                start = parse_date(text(element, "FromDate"))
                end = parse_date(text(element, "ToDate"))
                if start and end:
                    model.periods[identifier] = (start, end)
            elif name == "OperatingDay":
                day = parse_date(text(element, "CalendarDate"))
                if day:
                    model.operating_days[identifier] = day
            elif name == "DayTypeAssignment":
                available = text(element, "isAvailable").lower() != "false"
                order = int(element.get("order") or 0)
                model.day_type_assignments.append((
                    order, ref(element, "DayTypeRef"), ref(element, "OperatingPeriodRef"), ref(element, "OperatingDayRef"),
                    parse_date(text(element, "Date")), available,
                ))
    return model


def day_type_dates(model: Model) -> dict[str, set[date]]:
    dates: dict[str, set[date]] = defaultdict(set)
    for _, day_type, period, operating_day, single, available in sorted(model.day_type_assignments, key=lambda item: item[0]):
        days: set[date] = set()
        if period in model.uic_periods:
            start, bits = model.uic_periods[period]
            days = {start + timedelta(days=offset) for offset, bit in enumerate(bits[:MAX_DAYS]) if bit == "1"}
        elif period in model.periods:
            start, end = model.periods[period]
            weekdays = model.day_type_weekdays.get(day_type)
            current = start
            while current <= end and (current - start).days < MAX_DAYS:
                if weekdays is None or current.weekday() in weekdays:
                    days.add(current)
                current += timedelta(days=1)
        elif operating_day in model.operating_days:
            days = {model.operating_days[operating_day]}
        elif single is not None:
            days = {single}
        if available:
            dates[day_type] |= days
        else:
            dates[day_type] -= days
    return dates


def gtfs_time(value: str, offset: str) -> str:
    if not value:
        return ""
    parts = value.split(":")
    try:
        hours = int(parts[0]) + 24 * int(offset or 0)
        minutes = int(parts[1])
        seconds = int(parts[2][:2]) if len(parts) > 2 else 0
    except (ValueError, IndexError):
        return ""
    return f"{hours:02d}:{minutes:02d}:{seconds:02d}"


@dataclass
class Output:
    trips: list[dict[str, str]] = field(default_factory=list)
    stop_times: list[dict[str, str]] = field(default_factory=list)
    services: dict[frozenset[date], str] = field(default_factory=dict)
    used_points: set[str] = field(default_factory=set)
    used_lines: set[str] = field(default_factory=set)
    skipped: dict[str, int] = field(default_factory=lambda: defaultdict(int))


def second_pass(streams: Callable[[], Iterator[tuple[str, io.BufferedIOBase]]], model: Model, dates: dict[str, set[date]]) -> Output:
    output = Output()
    passing: dict[str, list[ET.Element]] = defaultdict(list)
    for _, stream in streams():
        for name, element, context in iter_objects(stream, {"TimetabledPassingTime", "ServiceJourney", "VehicleJourney"}):
            if name == "TimetabledPassingTime":
                journey = next((owner for kind, owner in reversed(context) if kind in {"ServiceJourney", "VehicleJourney"}), "")
                passing[journey].append(_passing_record(element))
                continue
            identifier = element.get("id") or ""
            records = passing.pop(identifier, [])
            _journey(identifier, element, records, model, dates, output)
    return output


def _passing_record(element: ET.Element):  # type: ignore[no-untyped-def]
    point = ref(element, "StopPointInJourneyPatternRef") or ref(element, "PointInJourneyPatternRef")
    return (
        point,
        text(element, "ArrivalTime"), text(element, "ArrivalDayOffset"),
        text(element, "DepartureTime"), text(element, "DepartureDayOffset"),
    )


def _journey(identifier: str, element: ET.Element, records, model: Model, dates: dict[str, set[date]], output: Output) -> None:  # type: ignore[no-untyped-def]
    pattern_id = ref(element, "ServiceJourneyPatternRef") or ref(element, "JourneyPatternRef")
    pattern = model.patterns.get(pattern_id)
    line = (
        ref(element, "LineRef")
        or ref(child(element, "FlexibleLineView"), "LineRef")
        or ref(child(element, "LineView"), "LineRef")
        or (pattern.line if pattern else "")
    )
    if not line and pattern is not None:
        line = model.routes.get(pattern.route, "")
    if not line or line not in model.lines:
        output.skipped["senza linea"] += 1
        return
    day_types = [item.get("ref") or "" for container in descendants(element, "dayTypes") for item in container if local(item.tag) == "DayTypeRef"]
    active: set[date] = set()
    for day_type in day_types:
        active |= dates.get(day_type, set())
    if not active:
        output.skipped["senza giorni"] += 1
        return
    order = {point: index for index, (point, _, _, _) in enumerate(pattern.points)} if pattern else {}
    rows = []
    for point_ref, arrival, arrival_offset, departure, departure_offset in records:
        point = model.point_in_pattern.get(point_ref)
        if point is None:
            continue
        ssp, boarding, alighting = point
        arrival_time = gtfs_time(arrival or departure, arrival_offset if arrival else departure_offset)
        departure_time = gtfs_time(departure or arrival, departure_offset if departure else arrival_offset)
        if not arrival_time or not departure_time:
            continue
        rows.append((order.get(point_ref, len(rows)), ssp, arrival_time, departure_time, boarding, alighting))
    rows.sort(key=lambda row: row[0])
    if len(rows) < 2:
        output.skipped["meno di due fermate"] += 1
        return
    key = frozenset(active)
    service = output.services.setdefault(key, f"S{len(output.services) + 1}")
    output.used_lines.add(line)
    number = journey_number(element)
    output.trips.append({
        "route_id": line, "service_id": service, "trip_id": identifier,
        "trip_short_name": number, "trip_headsign": "",
    })
    for sequence, (_, ssp, arrival_time, departure_time, boarding, alighting) in enumerate(rows, 1):
        output.used_points.add(ssp)
        output.stop_times.append({
            "trip_id": identifier, "arrival_time": arrival_time, "departure_time": departure_time, "stop_id": ssp,
            "stop_sequence": str(sequence),
            "pickup_type": "" if boarding else "1",
            "drop_off_type": "" if alighting else "1",
        })


def journey_number(element: ET.Element) -> str:
    """The public number of a run: PublicCode, else a Name with digits (Trenitalia writes the train number there,
    other producers a description such as "Corsa Intercity"), else the train number of a TrainNumberRef or PrivateCode."""
    public = text(element, "PublicCode")
    if public:
        return public
    name = text(element, "Name")
    if any(character.isdigit() for character in name) and len(name) <= 12:
        return name
    train_numbers = child(element, "trainNumbers")
    if train_numbers is not None:
        for item in train_numbers:
            value = (item.get("ref") or "").rsplit(":", 1)[-1]
            if value:
                return value
    return text(element, "PrivateCode")


def calendar_rows(services: dict[frozenset[date], str]) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    """A weekday pattern over each service's date range plus exceptions, or plain dates when that is shorter."""
    calendar: list[dict[str, str]] = []
    exceptions: list[dict[str, str]] = []
    for days, service in sorted(services.items(), key=lambda item: item[1]):
        ordered = sorted(days)
        start, end = ordered[0], ordered[-1]
        span = [start + timedelta(days=offset) for offset in range((end - start).days + 1)]
        mask = []
        for weekday in range(7):
            total = sum(1 for day in span if day.weekday() == weekday)
            running = sum(1 for day in ordered if day.weekday() == weekday)
            mask.append(total > 0 and running * 2 > total)
        added = [day for day in ordered if not mask[day.weekday()]]
        removed = [day for day in span if mask[day.weekday()] and day not in days]
        if len(added) + len(removed) + 1 < len(ordered):
            calendar.append({
                "service_id": service,
                **{name.lower(): "1" if mask[index] else "0" for index, name in enumerate(WEEKDAYS)},
                "start_date": start.strftime("%Y%m%d"), "end_date": end.strftime("%Y%m%d"),
            })
            exceptions += [{"service_id": service, "date": day.strftime("%Y%m%d"), "exception_type": "1"} for day in added]
            exceptions += [{"service_id": service, "date": day.strftime("%Y%m%d"), "exception_type": "2"} for day in removed]
        else:
            exceptions += [{"service_id": service, "date": day.strftime("%Y%m%d"), "exception_type": "1"} for day in ordered]
    return calendar, exceptions


def stops_rows(model: Model, used_points: set[str]) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    parents: dict[str, dict[str, str]] = {}
    for point in sorted(used_points):
        name, code, latitude, longitude = model.points.get(point, ("", "", "", ""))
        place, quay = model.assignments.get(point, ("", ""))
        if quay and not place:
            place = model.quays.get(quay, ("",))[0]
        parent = ""
        if place in model.stop_places:
            place_name, place_code, place_latitude, place_longitude = model.stop_places[place]
            parent = place
            if place not in parents:
                parents[place] = {
                    "stop_id": place, "stop_code": place_code, "stop_name": place_name or name,
                    "stop_lat": place_latitude or latitude, "stop_lon": place_longitude or longitude,
                    "location_type": "1", "parent_station": "",
                }
            name = name or place_name
            code = code or place_code
            latitude = latitude or (model.quays.get(quay, ("", "", "", "", ""))[3] if quay else "") or place_latitude
            longitude = longitude or (model.quays.get(quay, ("", "", "", "", ""))[4] if quay else "") or place_longitude
        rows.append({
            "stop_id": point, "stop_code": code, "stop_name": name or point, "stop_lat": latitude, "stop_lon": longitude,
            "location_type": "0", "parent_station": parent,
        })
    return sorted(parents.values(), key=lambda row: row["stop_id"]) + rows


def route_type(mode: str) -> str:
    return str(ROUTE_TYPES.get(mode.strip().lower(), 3 if "bus" in mode.lower() else 2))


def write_csv(archive: zipfile.ZipFile, name: str, header: Iterable[str], rows: Iterable[dict[str, str]]) -> int:
    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=list(header), extrasaction="ignore", lineterminator="\n")
    writer.writeheader()
    count = 0
    for row in rows:
        writer.writerow(row)
        count += 1
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    archive.writestr(info, buffer.getvalue().encode("utf-8"), compresslevel=9)
    return count


def convert(source: Path, destination: Path, *, source_label: str) -> dict[str, object]:
    streams = xml_streams(source)
    model = first_pass(streams)
    dates = day_type_dates(model)
    output = second_pass(streams, model, dates)
    if not output.trips:
        raise ConversionError(f"nessuna corsa convertita (scartate: {dict(output.skipped)})")
    calendar, exceptions = calendar_rows(output.services)
    stops = stops_rows(model, output.used_points)
    lines = sorted(output.used_lines)
    operators = sorted({model.lines[line]["operator"] for line in lines if model.lines[line]["operator"]})
    agency_rows = [
        {"agency_id": operator, "agency_name": model.operators.get(operator, (operator, ""))[0],
         "agency_url": model.operators.get(operator, ("", ""))[1] or "https://www.cciss.it/nap/", "agency_timezone": TIMEZONE,
         "agency_lang": "it"}
        for operator in operators
    ] or [{"agency_id": "", "agency_name": source_label, "agency_url": "https://www.cciss.it/nap/", "agency_timezone": TIMEZONE, "agency_lang": "it"}]
    route_rows = []
    for line in lines:
        values = model.lines[line]
        route_rows.append({
            "route_id": line, "agency_id": values["operator"],
            "route_short_name": values["short"] or values["public"] or model.products.get(values["product"], ""),
            "route_long_name": values["name"], "route_type": route_type(values["mode"]),
        })
    all_dates = sorted({day for days in output.services for day in days})
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".part")
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        counts = {
            "agency": write_csv(archive, "agency.txt", ("agency_id", "agency_name", "agency_url", "agency_timezone", "agency_lang"), agency_rows),
            "stops": write_csv(archive, "stops.txt", ("stop_id", "stop_code", "stop_name", "stop_lat", "stop_lon", "location_type", "parent_station"), stops),
            "routes": write_csv(archive, "routes.txt", ("route_id", "agency_id", "route_short_name", "route_long_name", "route_type"), route_rows),
            "trips": write_csv(archive, "trips.txt", ("route_id", "service_id", "trip_id", "trip_short_name", "trip_headsign"), output.trips),
            "stopTimes": write_csv(archive, "stop_times.txt", ("trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence", "pickup_type", "drop_off_type"), output.stop_times),
            "calendar": write_csv(archive, "calendar.txt", ("service_id", *(name.lower() for name in WEEKDAYS), "start_date", "end_date"), calendar),
            "calendarDates": write_csv(archive, "calendar_dates.txt", ("service_id", "date", "exception_type"), exceptions),
        }
        write_csv(archive, "feed_info.txt", ("feed_publisher_name", "feed_publisher_url", "feed_lang", "feed_start_date", "feed_end_date", "feed_version"), [{
            "feed_publisher_name": f"{source_label} (NeTEx, National Access Point CCISS), convertito da Binario",
            "feed_publisher_url": "https://www.cciss.it/nap/", "feed_lang": "it",
            "feed_start_date": all_dates[0].strftime("%Y%m%d"), "feed_end_date": all_dates[-1].strftime("%Y%m%d"),
            "feed_version": "netex-to-gtfs-1",
        }])
    temporary.replace(destination)
    return {
        "counts": counts,
        "services": len(output.services),
        "skipped": dict(output.skipped),
        "firstDate": all_dates[0].isoformat(),
        "lastDate": all_dates[-1].isoformat(),
        "lines": len(model.lines),
        "patterns": len(model.patterns),
        "dayTypes": len(model.day_type_weekdays),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--source-label", default="Operatore", help="nome dell'operatore per feed_info e agency di riserva")
    args = parser.parse_args(argv)
    try:
        report = convert(args.input, args.output, source_label=args.source_label)
    except (ConversionError, ET.ParseError, OSError, zipfile.BadZipFile) as error:
        print(f"ERRORE NeTEx: {error}", file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
