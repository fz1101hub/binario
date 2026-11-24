import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/rfi-programmed-v1.json (RFI's programmed timetable: periodicity, the day a row leaves,
/// stop names, the trains of a route and of a hub's board on Android's synthetic pages) and calendar-v1.json (the
/// Italian holidays), the cases Android's RfiProgrammedContractTest and CalendarContractTest read too.
final class RfiProgrammedContractTests: XCTestCase {
    func testTheHolidaysAreTheSharedOnes() throws {
        struct Holiday: Decodable { let date: String; let holiday: Bool }
        struct Calendar: Decodable { let holidays: [Holiday] }
        let calendar = try JSONDecoder().decode(Calendar.self, from: Data(contentsOf: file("calendar-v1.json")))
        XCTAssertFalse(calendar.holidays.isEmpty)
        for item in calendar.holidays {
            let day = try XCTUnwrap(RailDay(iso: item.date), item.date)
            XCTAssertEqual(ItalianRailServiceCalendar.isHoliday(day), item.holiday, item.date)
        }
    }

    func testThePeriodicityIsReadAsOnAndroid() throws {
        let rules = try contract()
        XCTAssertGreaterThan(rules.periodicity.count, 200)
        for item in rules.periodicity {
            let day = try XCTUnwrap(RailDay(iso: item.date), item.date)
            XCTAssertEqual(ItalianRailServiceCalendar.runsOnDate(item.text, day), item.runs, "\(item.text) \(item.date)")
        }
    }

    func testARowEarlierThanTheRequestedTimeIsTheNextDays() throws {
        let rules = try contract()
        for item in rules.departureDays {
            let day = try XCTUnwrap(RailDay(iso: item.serviceDate))
            let found = ItalianRailServiceCalendar.rfiDepartureDay(rowMinutes: item.row, serviceDay: day, requestedMinutes: item.requested)
            XCTAssertEqual(found?.description, item.expected, "\(item.row) \(item.requested)")
        }
    }

    func testAPublishedNameNamesTheStationAsOnAndroid() throws {
        let rules = try contract()
        for item in rules.names {
            XCTAssertEqual(
                RailStopNameMatcher.matches(name: item.station, aliases: item.aliases, published: item.published), item.matches,
                "\(item.station) ← \(item.published)"
            )
        }
    }

    func testTheTrainsOfARouteAreAndroidsWithTheirStops() throws {
        let rules = try contract()
        for item in rules.routes {
            let html = try page(rules, item.page, periodicity: item.periodicity)
            let origin = item.origin.station
            let destination = item.destination.station
            let departure = try date(item.departure)
            let arrivals = RfiProgrammedStations.mainTimetableId(origin) == nil
            let parsed = RfiProgrammedParser.parse(html, origin: origin, destinationName: destination.name, arrivals: arrivals)
            let parts = LiveRailTime.calendar.dateComponents([.hour, .minute], from: departure)
            let trains = RfiProgrammedRoutes.departures(
                parsed, destination: destination, serviceDay: RailDay(italianDayOf: departure),
                requestedMinutes: (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
            ).map { train in
                Train(
                    number: train.row.number, departure: "\(train.day)T\(train.row.scheduled)",
                    operator: train.row.operatorKind.rawValue, category: train.row.category.rawValue,
                    stops: train.row.stops.map(\.station)
                )
            }
            XCTAssertEqual(trains, item.expected, item.name)
        }
    }

    func testTheProgrammedTimetableAsksTheHubsPageAndListsTheTrains() async throws {
        let rules = try contract()
        for item in rules.routes {
            let html = try page(rules, item.page, periodicity: item.periodicity)
            let transport = FakeLiveTransport { _ in FakeLiveTransport.ok(html) }
            let timetable = RfiProgrammedTimetable(transport: transport, pauses: LiveSourcePauses())
            let origin = item.origin.station
            let destination = item.destination.station
            let departure = try date(item.departure)
            let query = try JourneyQuery(origin: StationIdentity(origin), destination: StationIdentity(destination), departure: departure)
            let snapshot = await timetable.journeys(origin: origin, destination: destination, departure: departure, query: query)
            XCTAssertTrue(transport.paths.first?.contains(item.url) == true, "\(item.name): \(transport.paths)")
            XCTAssertTrue(transport.paths.allSatisfy { $0.hasPrefix("https://prm.rfi.it/qo_prm/") }, item.name)
            XCTAssertEqual(snapshot?.services.map(\.number) ?? [], item.expected.map(\.number), item.name)
            XCTAssertEqual(snapshot?.services.map(\.scheduledDeparture) ?? [], item.expected.map { String($0.departure.suffix(5)) }, item.name)
            if let snapshot {
                XCTAssertEqual(snapshot.provenance.sourceLabel, RfiProgrammedTimetable.sourceLabel)
                XCTAssertEqual(snapshot.operationalState, .programmed)
                XCTAssertEqual(Set(snapshot.services.map(\.id)).count, snapshot.services.count, item.name)
            }
        }
    }

    func testTheBoardOfAHubListsItsProgrammedDepartures() async throws {
        let rules = try contract()
        for item in rules.boards {
            let html = try page(rules, item.page, periodicity: item.periodicity)
            let transport = FakeLiveTransport { _ in FakeLiveTransport.ok(html) }
            let timetable = RfiProgrammedTimetable(transport: transport, pauses: LiveSourcePauses())
            let from = try date(item.from)
            let query = try StationBoardQuery(station: StationIdentity(item.station.station), from: from, limit: 50)
            let page = await timetable.departures(station: item.station.station, query: query)
            let until = from.addingTimeInterval(TimeInterval(item.hours) * 3_600)
            let listed = (page?.departures ?? []).filter { $0.departure <= until }.map { entry in
                BoardTrain(number: entry.service.number, departure: format(entry.departure), destination: entry.service.destination)
            }
            XCTAssertEqual(listed, item.expected, item.name)
            XCTAssertTrue(transport.paths.first?.contains(item.url) == true, "\(item.name): \(transport.paths)")
            if let page { XCTAssertEqual(page.attributions, [RfiProgrammedTimetable.sourceLabel]) }
        }
    }

    func testAPageIsAskedOnceAndARefusalStopsTheRequests() async throws {
        let rules = try contract()
        let html = try page(rules, "departures", periodicity: "Giornaliero")
        let transport = FakeLiveTransport { _ in FakeLiveTransport.ok(html) }
        let timetable = RfiProgrammedTimetable(transport: transport, pauses: LiveSourcePauses())
        let roma = Station(id: "S08409", name: "Roma Termini", railwayCode: "S08409")
        let milano = Station(id: "S01700", name: "Milano Centrale", railwayCode: "S01700")
        let departure = try date("2026-09-04T08:00")
        let query = try JourneyQuery(origin: StationIdentity(roma), destination: StationIdentity(milano), departure: departure)
        _ = await timetable.journeys(origin: roma, destination: milano, departure: departure, query: query)
        _ = await timetable.journeys(origin: roma, destination: milano, departure: departure.addingTimeInterval(3_600), query: query)
        XCTAssertEqual(transport.requests.count, 1)

        let refusing = FakeLiveTransport { _ in FakeLiveTransport.status(403) }
        let refused = RfiProgrammedTimetable(transport: refusing, pauses: LiveSourcePauses())
        let first = await refused.journeys(origin: roma, destination: milano, departure: departure, query: query)
        let firenze = Station(id: "S06421", name: "Firenze S.M.N.", railwayCode: "S06421")
        let second = await refused.journeys(origin: firenze, destination: milano, departure: departure, query: query)
        XCTAssertNil(first)
        XCTAssertNil(second)
        XCTAssertEqual(refusing.requests.count, 1)
    }

    func testAStationWithoutAHubAsksNothing() async throws {
        let transport = FakeLiveTransport { _ in FakeLiveTransport.ok("") }
        let timetable = RfiProgrammedTimetable(transport: transport, pauses: LiveSourcePauses())
        let pioltello = Station(id: "S01701", name: "Pioltello Limito")
        let treviglio = Station(id: "S01702", name: "Treviglio")
        let departure = try date("2026-09-04T08:00")
        let query = try JourneyQuery(origin: StationIdentity(pioltello), destination: StationIdentity(treviglio), departure: departure)
        let snapshot = await timetable.journeys(origin: pioltello, destination: treviglio, departure: departure, query: query)
        XCTAssertNil(snapshot)
        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testTheHubsAreTheSharedTable() throws {
        let table = try JSONSerialization.jsonObject(
            with: Data(contentsOf: repositoryRoot.appendingPathComponent("contracts/shared-data/v1/rfi-programmed-v1.json"))
        ) as? [String: Any]
        let hubs = try XCTUnwrap(table?["hubs"] as? [[String: Any]])
        XCTAssertEqual(SharedData.RfiProgrammed.hubs.map(\.railwayCode), hubs.compactMap { $0["railwayCode"] as? String })
        for hub in SharedData.RfiProgrammed.hubs {
            for name in hub.names {
                XCTAssertEqual(RfiProgrammedStations.mainTimetableId(Station(id: "x", name: name)), hub.timetableId, name)
            }
            XCTAssertEqual(RfiProgrammedStations.mainTimetableId(Station(id: "x", name: "?", railwayCode: hub.railwayCode)), hub.timetableId)
        }
        XCTAssertEqual(
            RfiProgrammedStations.url(timetableId: 2247, arrivals: false)?.absoluteString,
            "https://prm.rfi.it/qo_prm/QO_Partenze_SiPMR.aspx?Id=2247&lin=it&dalle=00.00&alle=23.59&ora=00.00"
        )
    }

    // MARK: - Contract

    private struct Train: Equatable, Decodable {
        let number: String
        let departure: String
        let `operator`: String
        let category: String
        let stops: [String]
    }

    private struct BoardTrain: Equatable, Decodable {
        let number: String
        let departure: String
        let destination: String
    }

    private struct CaseStation: Decodable {
        let id: String
        let name: String
        let railwayCode: String?
        let aliases: [String]

        var station: Station { Station(id: id, name: name, railwayCode: railwayCode, aliases: aliases) }
    }

    private struct Periodicity: Decodable { let text: String; let date: String; let runs: Bool }
    private struct DepartureDay: Decodable { let row: Int; let serviceDate: String; let requested: Int; let expected: String? }
    private struct Name: Decodable { let station: String; let aliases: [String]; let published: String; let matches: Bool }

    private struct Route: Decodable {
        let name: String
        let page: String
        let periodicity: String
        let origin: CaseStation
        let destination: CaseStation
        let departure: String
        let url: String
        let expected: [Train]
    }

    private struct Board: Decodable {
        let name: String
        let page: String
        let periodicity: String
        let station: CaseStation
        let from: String
        let hours: Int
        let url: String
        let expected: [BoardTrain]
    }

    private struct Contract: Decodable {
        let periodicity: [Periodicity]
        let departureDays: [DepartureDay]
        let names: [Name]
        let pages: [String: String]
        let routes: [Route]
        let boards: [Board]
    }

    private func contract() throws -> Contract {
        try JSONDecoder().decode(Contract.self, from: Data(contentsOf: file("rfi-programmed-v1.json")))
    }

    private func page(_ contract: Contract, _ name: String, periodicity: String) throws -> String {
        try XCTUnwrap(contract.pages[name], name).replacingOccurrences(of: "{periodicity}", with: periodicity)
    }

    private func date(_ value: String) throws -> Date {
        let parser = DateFormatter()
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.timeZone = TimeZone(identifier: "Europe/Rome")
        parser.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return try XCTUnwrap(parser.date(from: value), value)
    }

    private func format(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Europe/Rome")
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return formatter.string(from: date)
    }

    private func file(_ name: String) -> URL {
        repositoryRoot.appendingPathComponent("contracts/shared-rules/v1/\(name)")
    }

    private var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}
