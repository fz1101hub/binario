from __future__ import annotations

import csv
import gzip
import io
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import netex_to_gtfs  # noqa: E402

NETEX = """<?xml version="1.0" encoding="UTF-8"?>
<PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
 <dataObjects>
  <CompositeFrame id="c" version="1">
   <frames>
    <ResourceFrame id="r" version="1"><organisations>
     <Operator id="IT::Operator:FAL" version="1"><Name>Ferrovie Appulo Lucane</Name><ContactDetails><Url>https://ferrovieappulolucane.it</Url></ContactDetails></Operator>
    </organisations></ResourceFrame>
    <SiteFrame id="s" version="1"><stopPlaces>
     <StopPlace id="IT::StopPlace:BARI" version="1"><Name>Bari Centrale FAL</Name><PrivateCode>B01</PrivateCode>
      <Centroid><Location><Longitude>16.8712</Longitude><Latitude>41.1180</Latitude></Location></Centroid>
      <quays><Quay id="IT::Quay:BARI1" version="1"><Name>Bari 1</Name><Centroid><Location><Longitude>16.8713</Longitude><Latitude>41.1181</Latitude></Location></Centroid></Quay></quays>
     </StopPlace>
     <StopPlace id="IT::StopPlace:ALTAMURA" version="1"><Name>Altamura</Name>
      <Centroid><Location><Longitude>16.5517</Longitude><Latitude>40.8277</Latitude></Location></Centroid></StopPlace>
     <StopPlace id="IT::StopPlace:MATERA" version="1"><Name>Matera Centrale</Name>
      <Centroid><Location><Longitude>16.6037</Longitude><Latitude>40.6660</Latitude></Location></Centroid></StopPlace>
    </stopPlaces></SiteFrame>
    <ServiceFrame id="sf" version="1">
     <routes><Route id="IT::Route:R1" version="1"><Name>Bari - Matera</Name><LineRef ref="IT::Line:BM"/></Route></routes>
     <lines><Line id="IT::Line:BM" version="1"><Name>Bari - Matera</Name><TransportMode>rail</TransportMode><PublicCode>FR</PublicCode>
      <OperatorRef ref="IT::Operator:FAL"/></Line>
      <Line id="IT::Line:BUS" version="1"><Name>Autobus</Name><TransportMode>bus</TransportMode><PublicCode>BUS</PublicCode></Line></lines>
     <scheduledStopPoints>
      <ScheduledStopPoint id="IT::SSP:BARI" version="1"><Name>BARI C.LE</Name><Location><Longitude>16.8712</Longitude><Latitude>41.1180</Latitude></Location></ScheduledStopPoint>
      <ScheduledStopPoint id="IT::SSP:ALTAMURA" version="1"><Name>ALTAMURA</Name></ScheduledStopPoint>
      <ScheduledStopPoint id="IT::SSP:MATERA" version="1"><Name>MATERA C.LE</Name></ScheduledStopPoint>
     </scheduledStopPoints>
     <stopAssignments>
      <PassengerStopAssignment id="a1" order="1" version="1"><ScheduledStopPointRef ref="IT::SSP:BARI"/><StopPlaceRef ref="IT::StopPlace:BARI"/><QuayRef ref="IT::Quay:BARI1"/></PassengerStopAssignment>
      <PassengerStopAssignment id="a2" order="2" version="1"><ScheduledStopPointRef ref="IT::SSP:ALTAMURA"/><StopPlaceRef ref="IT::StopPlace:ALTAMURA"/></PassengerStopAssignment>
      <PassengerStopAssignment id="a3" order="3" version="1"><ScheduledStopPointRef ref="IT::SSP:MATERA"/><QuayRef ref="IT::Quay:BARI1"/><StopPlaceRef ref="IT::StopPlace:MATERA"/></PassengerStopAssignment>
     </stopAssignments>
     <serviceLinks><ServiceLink id="IT::ServiceLink:1" version="1"><FromPointRef ref="IT::SSP:BARI"/><ToPointRef ref="IT::SSP:ALTAMURA"/></ServiceLink></serviceLinks>
     <journeyPatterns>
      <ServiceJourneyPattern id="IT::SJP:1" version="1"><RouteView><LineRef ref="IT::Line:BM"/></RouteView>
       <pointsInSequence>
        <StopPointInJourneyPattern id="IT::SPIJP:1" order="1" version="1"><ScheduledStopPointRef ref="IT::SSP:BARI"/><ForAlighting>false</ForAlighting><ForBoarding>true</ForBoarding></StopPointInJourneyPattern>
        <StopPointInJourneyPattern id="IT::SPIJP:3" order="3" version="1"><ScheduledStopPointRef ref="IT::SSP:MATERA"/><ForAlighting>true</ForAlighting><ForBoarding>false</ForBoarding></StopPointInJourneyPattern>
        <StopPointInJourneyPattern id="IT::SPIJP:2" order="2" version="1"><ScheduledStopPointRef ref="IT::SSP:ALTAMURA"/></StopPointInJourneyPattern>
       </pointsInSequence></ServiceJourneyPattern>
      <ServiceJourneyPattern id="IT::SJP:2" version="1"><RouteRef ref="IT::Route:R1"/>
       <pointsInSequence>
        <StopPointInJourneyPattern id="IT::SPIJP:21" order="1" version="1"><ScheduledStopPointRef ref="IT::SSP:MATERA"/></StopPointInJourneyPattern>
        <StopPointInJourneyPattern id="IT::SPIJP:22" order="2" version="1"><ScheduledStopPointRef ref="IT::SSP:BARI"/></StopPointInJourneyPattern>
       </pointsInSequence></ServiceJourneyPattern>
     </journeyPatterns>
    </ServiceFrame>
    <ServiceCalendarFrame id="cf" version="1"><ServiceCalendar id="cal" version="1">
     <dayTypes>
      <DayType id="IT::DayType:WEEKDAYS" version="1"><properties><PropertyOfDay><DaysOfWeek>Monday Tuesday Wednesday Thursday Friday</DaysOfWeek></PropertyOfDay></properties></DayType>
      <DayType id="IT::DayType:BITS" version="1"/>
      <DayType id="IT::DayType:EMPTY" version="1"/>
     </dayTypes>
     <operatingPeriods>
      <OperatingPeriod id="IT::OP:NOV" version="1"><FromDate>2026-11-02T00:00:00</FromDate><ToDate>2026-11-29T23:59:59</ToDate></OperatingPeriod>
      <UicOperatingPeriod id="IT::UOP:1" version="1"><FromDate>2026-11-02T00:00:00</FromDate><ToDate>2026-11-08T00:00:00</ToDate><ValidDayBits>1010001</ValidDayBits></UicOperatingPeriod>
     </operatingPeriods>
     <dayTypeAssignments>
      <DayTypeAssignment id="d1" order="1" version="1"><OperatingPeriodRef ref="IT::OP:NOV"/><DayTypeRef ref="IT::DayType:WEEKDAYS"/></DayTypeAssignment>
      <DayTypeAssignment id="d2" order="2" version="1"><Date>2026-11-04</Date><DayTypeRef ref="IT::DayType:WEEKDAYS"/><isAvailable>false</isAvailable></DayTypeAssignment>
      <DayTypeAssignment id="d3" order="3" version="1"><OperatingPeriodRef ref="IT::UOP:1"/><DayTypeRef ref="IT::DayType:BITS"/></DayTypeAssignment>
     </dayTypeAssignments>
    </ServiceCalendar></ServiceCalendarFrame>
    <TimetableFrame id="tf" version="1"><vehicleJourneys>
     <ServiceJourney id="IT::SJ:A" version="1"><Name>12345</Name><dayTypes><DayTypeRef ref="IT::DayType:WEEKDAYS"/></dayTypes>
      <ServiceJourneyPatternRef ref="IT::SJP:1"/>
      <passingTimes>
       <TimetabledPassingTime id="t1" version="1"><StopPointInJourneyPatternRef ref="IT::SPIJP:1"/><DepartureTime>23:30:00</DepartureTime><DepartureDayOffset>0</DepartureDayOffset></TimetabledPassingTime>
       <TimetabledPassingTime id="t3" version="1"><StopPointInJourneyPatternRef ref="IT::SPIJP:3"/><ArrivalTime>00:40:00</ArrivalTime><ArrivalDayOffset>1</ArrivalDayOffset></TimetabledPassingTime>
       <TimetabledPassingTime id="t2" version="1"><StopPointInJourneyPatternRef ref="IT::SPIJP:2"/><ArrivalTime>00:05:00</ArrivalTime><ArrivalDayOffset>1</ArrivalDayOffset><DepartureTime>00:07:00</DepartureTime><DepartureDayOffset>1</DepartureDayOffset></TimetabledPassingTime>
      </passingTimes></ServiceJourney>
     <ServiceJourney id="IT::SJ:B" version="1"><Name>12346</Name><dayTypes><DayTypeRef ref="IT::DayType:BITS"/></dayTypes>
      <ServiceJourneyPatternRef ref="IT::SJP:2"/>
      <passingTimes>
       <TimetabledPassingTime id="u1" version="1"><StopPointInJourneyPatternRef ref="IT::SPIJP:21"/><DepartureTime>06:00:00</DepartureTime></TimetabledPassingTime>
       <TimetabledPassingTime id="u2" version="1"><StopPointInJourneyPatternRef ref="IT::SPIJP:22"/><ArrivalTime>07:30:00</ArrivalTime></TimetabledPassingTime>
      </passingTimes></ServiceJourney>
     <ServiceJourney id="IT::SJ:C" version="1"><Name>9</Name><dayTypes><DayTypeRef ref="IT::DayType:EMPTY"/></dayTypes>
      <ServiceJourneyPatternRef ref="IT::SJP:2"/><passingTimes/></ServiceJourney>
    </vehicleJourneys></TimetableFrame>
   </frames>
  </CompositeFrame>
 </dataObjects>
</PublicationDelivery>
"""


def table(archive: zipfile.ZipFile, name: str) -> list[dict[str, str]]:
    with archive.open(name) as raw:
        return list(csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8")))


class NetexToGtfsTest(unittest.TestCase):
    def convert(self, payload: bytes, suffix: str = ".xml.gz") -> tuple[dict, zipfile.ZipFile]:
        directory = Path(tempfile.mkdtemp())
        source = directory / f"input{suffix}"
        source.write_bytes(payload)
        report = netex_to_gtfs.convert(source, directory / "out.zip", source_label="FAL")
        return report, zipfile.ZipFile(directory / "out.zip")

    def test_italian_profile_level_1_becomes_gtfs(self) -> None:
        report, archive = self.convert(gzip.compress(NETEX.encode("utf-8")))
        self.assertEqual({"trips": 2, "stopTimes": 5, "routes": 1, "agency": 1}, {k: report["counts"][k] for k in ("trips", "stopTimes", "routes", "agency")})
        self.assertEqual({"senza giorni": 1}, report["skipped"])

        trips = {row["trip_id"]: row for row in table(archive, "trips.txt")}
        self.assertEqual("12345", trips["IT::SJ:A"]["trip_short_name"])
        self.assertEqual("IT::Line:BM", trips["IT::SJ:A"]["route_id"])
        # The second pattern names its line through the route.
        self.assertEqual("IT::Line:BM", trips["IT::SJ:B"]["route_id"])

        times = [row for row in table(archive, "stop_times.txt") if row["trip_id"] == "IT::SJ:A"]
        # Ordered by the pattern, day offsets past midnight, a single time on the first and last stop.
        self.assertEqual(["IT::SSP:BARI", "IT::SSP:ALTAMURA", "IT::SSP:MATERA"], [row["stop_id"] for row in times])
        self.assertEqual([("23:30:00", "23:30:00"), ("24:05:00", "24:07:00"), ("24:40:00", "24:40:00")],
                         [(row["arrival_time"], row["departure_time"]) for row in times])
        self.assertEqual([("", "1"), ("", ""), ("1", "")], [(row["pickup_type"], row["drop_off_type"]) for row in times])

        routes = {row["route_id"]: row for row in table(archive, "routes.txt")}
        self.assertEqual(("FR", "2", "IT::Operator:FAL"), (routes["IT::Line:BM"]["route_short_name"], routes["IT::Line:BM"]["route_type"], routes["IT::Line:BM"]["agency_id"]))

        stops = {row["stop_id"]: row for row in table(archive, "stops.txt")}
        self.assertEqual(("1", "B01", "Bari Centrale FAL"), (stops["IT::StopPlace:BARI"]["location_type"], stops["IT::StopPlace:BARI"]["stop_code"], stops["IT::StopPlace:BARI"]["stop_name"]))
        self.assertEqual(("IT::StopPlace:BARI", "B01"), (stops["IT::SSP:BARI"]["parent_station"], stops["IT::SSP:BARI"]["stop_code"]))
        # An unlocated point takes its stop place's coordinates.
        self.assertEqual(("40.8277", "16.5517"), (stops["IT::SSP:ALTAMURA"]["stop_lat"], stops["IT::SSP:ALTAMURA"]["stop_lon"]))
        self.assertEqual("IT::StopPlace:MATERA", stops["IT::SSP:MATERA"]["parent_station"])

        agency = table(archive, "agency.txt")[0]
        self.assertEqual(("Ferrovie Appulo Lucane", "Europe/Rome"), (agency["agency_name"], agency["agency_timezone"]))

    def test_day_types_become_calendar_and_exceptions(self) -> None:
        _, archive = self.convert(gzip.compress(NETEX.encode("utf-8")))
        trips = {row["trip_id"]: row["service_id"] for row in table(archive, "trips.txt")}
        calendar = {row["service_id"]: row for row in table(archive, "calendar.txt")}
        exceptions = table(archive, "calendar_dates.txt")

        def dates(service: str) -> set[date]:
            days: set[date] = set()
            row = calendar.get(service)
            if row:
                start = date.fromisoformat(f"{row['start_date'][:4]}-{row['start_date'][4:6]}-{row['start_date'][6:]}")
                end = date.fromisoformat(f"{row['end_date'][:4]}-{row['end_date'][4:6]}-{row['end_date'][6:]}")
                flags = [row[name] == "1" for name in ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")]
                current = start
                while current <= end:
                    if flags[current.weekday()]:
                        days.add(current)
                    current = date.fromordinal(current.toordinal() + 1)
            for item in exceptions:
                if item["service_id"] != service:
                    continue
                day = date.fromisoformat(f"{item['date'][:4]}-{item['date'][4:6]}-{item['date'][6:]}")
                if item["exception_type"] == "1":
                    days.add(day)
                else:
                    days.discard(day)
            return days

        weekdays = {date(2026, 11, day) for day in range(2, 30) if date(2026, 11, day).weekday() < 5} - {date(2026, 11, 4)}
        self.assertEqual(weekdays, dates(trips["IT::SJ:A"]))
        # 1010001 from Monday 2 November: Monday, Wednesday and Sunday.
        self.assertEqual({date(2026, 11, 2), date(2026, 11, 4), date(2026, 11, 8)}, dates(trips["IT::SJ:B"]))
        self.assertIn(trips["IT::SJ:A"], calendar)

    def test_zip_of_xml_and_doctype_rejection(self) -> None:
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("netex/line.xml", NETEX)
        report, _ = self.convert(buffer.getvalue(), suffix=".zip")
        self.assertEqual(2, report["counts"]["trips"])

        hostile = NETEX.replace('<?xml version="1.0" encoding="UTF-8"?>', '<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa">]>')
        with self.assertRaises(netex_to_gtfs.ConversionError):
            self.convert(hostile.encode("utf-8"), suffix=".xml")

    def test_command_line_reports_counts(self) -> None:
        directory = Path(tempfile.mkdtemp())
        (directory / "in.xml.gz").write_bytes(gzip.compress(NETEX.encode("utf-8")))
        completed = subprocess.run(
            [sys.executable, "-I", str(ROOT / "tools" / "netex_to_gtfs.py"), str(directory / "in.xml.gz"), str(directory / "out.zip")],
            capture_output=True, text=True, check=True,
        )
        self.assertIn('"trips": 2', completed.stdout)

    def test_internal_line_ids_are_not_public_names(self) -> None:
        # Producers write their database id as ShortName ("route170", "3000"); the public names stay.
        for internal in ("route170", "3000", "1", "Linea 5", "line_12"):
            self.assertEqual("", netex_to_gtfs.public_line_name(internal), internal)
        for public in ("REG", "FR1", "M1", "SIR1", "T2", "FM2"):
            self.assertEqual(public, netex_to_gtfs.public_line_name(public))


class CompareGtfsTest(unittest.TestCase):
    """tools/compare_gtfs.py: whole runs are compared regardless of how variants of one number pair up."""

    @staticmethod
    def feed(directory: Path, name: str, runs: list[tuple[str, str, list[tuple[str, str]]]]) -> Path:
        path = directory / name
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("stops.txt", "stop_id,stop_code,stop_name,location_type\nA,1,A,\nB,2,B,\nC,3,C,\n")
            archive.writestr("routes.txt", "route_id,route_type\nR,2\n")
            archive.writestr("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
                             "W,1,1,1,1,1,0,0,20261102,20261231\nE,0,0,0,0,0,1,1,20261102,20261231\n")
            archive.writestr("trips.txt", "route_id,service_id,trip_id,trip_short_name\n" + "".join(
                f"R,{service},{trip_id},100\n" for trip_id, service, _ in runs))
            archive.writestr("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" + "".join(
                f"{trip_id},{time},{time},{stop},{index}\n" for trip_id, _, stops in runs for index, (stop, time) in enumerate(stops, 1)))
        return path

    def test_variants_of_one_number_are_found_whatever_the_pairing(self) -> None:
        sys.path.insert(0, str(ROOT / "tools"))
        import compare_gtfs

        directory = Path(tempfile.mkdtemp())
        weekday = [("A", "08:00:00"), ("B", "08:10:00"), ("C", "08:20:00")]
        weekend = [("A", "08:00:00"), ("B", "08:12:00"), ("C", "08:25:00")]
        reference = self.feed(directory, "reference.zip", [("r1", "W", weekday), ("r2", "E", weekend)])
        candidate = self.feed(directory, "candidate.zip", [("c1", "E", weekend), ("c2", "W", weekday)])
        result = compare_gtfs.compare(compare_gtfs.Feed(candidate, date(2026, 11, 2), 14), compare_gtfs.Feed(reference, date(2026, 11, 2), 14))
        self.assertEqual(1.0, result["referenceRunsFoundShare"])
        self.assertEqual(1.0, result["referenceDayRunsFoundShare"])
        self.assertEqual(1.0, result["candidateDayRunsFoundShare"])

        shifted = self.feed(directory, "shifted.zip", [("c1", "E", weekend), ("c2", "W", [("A", "08:00:00"), ("B", "08:11:00"), ("C", "08:20:00")])])
        result = compare_gtfs.compare(compare_gtfs.Feed(shifted, date(2026, 11, 2), 14), compare_gtfs.Feed(reference, date(2026, 11, 2), 14))
        self.assertEqual(0.5, result["referenceRunsFoundShare"])
        # Two weeks from Monday 2 November: the 4 weekend days are found, the 10 weekdays are not.
        self.assertEqual(round(4 / 14, 4), result["referenceDayRunsFoundShare"])


class TransitRegistryTest(unittest.TestCase):
    """tools/acquire_transit_sources.py: what the registry of the transit packs may declare."""

    @staticmethod
    def registry(**changes: object) -> Path:
        pack = {
            "sourceId": "demo-gtfs", "directory": "gtfs-transit", "distribution": "all", "kind": "urban",
            "format": "gtfs", "sourceUrl": "https://example.test/demo.zip", "licence": "CC BY 4.0",
            "attribution": "Demo", "notes": "fixture",
        }
        pack.update(changes)
        path = Path(tempfile.mkdtemp()) / "transit_packs.json"
        path.write_text(json.dumps({"schemaVersion": 1, "packs": [pack]}), encoding="utf-8")
        return path

    def test_shipped_registry_is_valid_and_personal_packs_stay_personal(self) -> None:
        import acquire_transit_sources as acquire

        packs = acquire.load_registry()
        self.assertTrue(packs)
        for pack in packs:
            self.assertEqual(pack["directory"] == "gtfs-personal", pack["distribution"] == "personal", pack["sourceId"])

    def test_http_needs_an_https_mirror_or_a_personal_reason(self) -> None:
        import acquire_transit_sources as acquire

        acquire.load_registry(self.registry())
        with self.assertRaises(acquire.AcquisitionError):
            acquire.load_registry(self.registry(sourceUrl="http://example.test/demo.zip"))
        with self.assertRaises(acquire.AcquisitionError):
            acquire.load_registry(self.registry(sourceUrl="http://example.test/demo.zip", transport="http"))
        with self.assertRaises(acquire.AcquisitionError):
            acquire.load_registry(self.registry(sourceUrl="http://example.test/demo.zip", transport="http", corroborateWith="http://mirror.test/demo.zip"))
        acquire.load_registry(self.registry(sourceUrl="http://example.test/demo.zip", transport="http", corroborateWith="https://mirror.test/demo.zip"))
        # Without a mirror only a personal-build pack, with the reason written down.
        with self.assertRaises(acquire.AcquisitionError):
            acquire.load_registry(self.registry(sourceUrl="http://example.test/demo.zip", transport="http", httpReason="no HTTPS"))
        acquire.load_registry(self.registry(sourceUrl="http://example.test/demo.zip", transport="http", httpReason="no HTTPS",
                                            directory="gtfs-personal", distribution="personal"))

    def test_directory_distribution_and_licence_are_required(self) -> None:
        import acquire_transit_sources as acquire

        for changes in ({"directory": "gtfs"}, {"distribution": "personal"}, {"format": "pdf"}, {"licence": " "}):
            with self.subTest(changes=changes), self.assertRaises(acquire.AcquisitionError):
                acquire.load_registry(self.registry(**changes))


if __name__ == "__main__":
    unittest.main()
