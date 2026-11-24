import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/next-trains-v1.json: the next trains between two stations of the
/// programmed timetable index, as the planner reads them (`SQLiteGTFSJourneyRepository.journeys`)
/// and as the Alexa skill reads them (alexa-companion/server/timetable/nextTrains.test.ts), with the
/// city runs boarded at a city stop a short walk from a station (`urban_walk`).
final class NextTrainsContractTests: XCTestCase {
    func testEverySharedCaseGivesTheSharedTrains() async throws {
        let contract = try JSONDecoder().decode(
            Contract.self,
            from: Data(contentsOf: repositoryRoot.appendingPathComponent("contracts/shared-rules/v1/next-trains-v1.json"))
        )
        XCTAssertFalse(contract.cases.isEmpty)
        let fixture = try GTFSFixtureDatabase(packs: [])
        // The fixture removes its file when released: keep it for the whole test.
        defer { withExtendedLifetime(fixture) {} }
        try insert(contract.index, into: fixture)
        fixture.close()
        let repository = SQLiteGTFSJourneyRepository(databaseURL: fixture.url)
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime]

        for item in contract.cases {
            let departure = try XCTUnwrap(parser.date(from: item.at), item.why)
            let query = try JourneyQuery(origin: item.origin.identity, destination: item.destination.identity, departure: departure)
            let result = await repository.journeys(for: query)
            switch (result, item.expected.unavailable) {
            case (.available(let snapshot), nil):
                let trains = snapshot.services.map {
                    Train(
                        time: $0.scheduledDeparture,
                        number: $0.number,
                        category: $0.category,
                        operatorName: $0.operatorName,
                        destination: $0.destination,
                        // A train stores no vehicle (`TrainService.vehicle` is a bus's or a city line's only):
                        // the shared cases, like the skill, name it TRAIN.
                        vehicle: ($0.vehicle ?? TimetableVehicle.train).rawValue,
                        boardingStop: $0.boardingStop
                    )
                }
                XCTAssertEqual(trains, item.expected.trains ?? [], item.why)
            case (.unavailable(.noJourney), "noRoute"?), (.unavailable(.scheduleExpired), "scheduleExpired"?):
                continue
            default:
                XCTFail("\(item.why): \(result)")
            }
        }
    }

    /// «Ultimo di oggi» read from the same index (`staticDayDepartures`): the last departure of the day towards each
    /// terminus (a run past midnight is still the day's), the first of the next service day, and with a destination
    /// one terminus; a run not boarding at the station is left out.
    func testTheLastDeparturesOfTheDayComeFromTheSameIndex() async throws {
        let contract = try JSONDecoder().decode(
            Contract.self,
            from: Data(contentsOf: repositoryRoot.appendingPathComponent("contracts/shared-rules/v1/next-trains-v1.json"))
        )
        let fixture = try GTFSFixtureDatabase(packs: [])
        defer { withExtendedLifetime(fixture) {} }
        try insert(contract.index, into: fixture)
        fixture.close()
        let repository = SQLiteGTFSJourneyRepository(databaseURL: fixture.url)
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime]
        let alfa = StationIdentity(id: "S90001", name: "Alfa Centrale", railwayCode: "S90001")
        let gamma = StationIdentity(id: "S90003", name: "Gamma", railwayCode: "S90003")
        let now = try XCTUnwrap(parser.date(from: "2024-03-28T12:00:00+01:00"))

        let day = await repository.staticDayDepartures(origin: alfa, destination: nil, at: now)
        XCTAssertEqual(day.lastByTerminus.count, 3)
        let last = Dictionary(day.lastByTerminus.map { ($0.terminus, $0.departure) }, uniquingKeysWith: { first, _ in first })
        XCTAssertEqual(last, [
            "Gamma": try XCTUnwrap(parser.date(from: "2024-03-29T00:30:00+01:00")),
            "Beta": try XCTUnwrap(parser.date(from: "2024-03-28T10:00:00+01:00")),
            "Delta Confine": try XCTUnwrap(parser.date(from: "2024-03-28T09:10:00+01:00")),
        ])
        XCTAssertTrue(LastOfDayMarks.isLast(day, publicNumber: "REG 2199", scheduledDeparture: last["Gamma"]))
        XCTAssertFalse(LastOfDayMarks.isLast(day, publicNumber: "2115", scheduledDeparture: parser.date(from: "2024-03-28T16:00:00+01:00")))
        // Alfa Centrale has a railway code, so it reads only the stops of that code, as the board does: the 07:50 coach
        // from «Alfa Centrale Piazzale» belongs to the station only for an identity without a code (next-trains-v1.json).
        XCTAssertEqual(day.firstNextDay?.departure, parser.date(from: "2024-03-29T08:00:00+01:00"))
        XCTAssertNil(LastOfDayMarks.firstNextDay(day, now: now))

        let route = await repository.staticDayDepartures(origin: alfa, destination: gamma, at: now)
        XCTAssertEqual(route.lastByTerminus.map(\.terminus), ["Gamma"])
        XCTAssertEqual(route.lastByTerminus.first?.departure, last["Gamma"])
        XCTAssertEqual(route.firstNextDay?.departure, parser.date(from: "2024-03-29T08:00:00+01:00"))
    }

    /// The rows of the contract's index, in the schema `GTFSFixtureDatabase` creates.
    private func insert(_ index: Index, into fixture: GTFSFixtureDatabase) throws {
        var sql = ""
        for pack in index.packs {
            sql += "INSERT INTO pack VALUES (\(pack.id), \(literal(pack.sourceId)), \(literal(pack.attribution)), \(pack.lastServiceDate));"
        }
        for agency in index.agencies {
            sql += "INSERT INTO agency VALUES (\(agency.id), \(agency.pack), \(literal(agency.externalId)), \(literal(agency.name)), \(literal(agency.timezone)));"
        }
        for route in index.routes {
            sql += "INSERT INTO route VALUES (\(route.id), \(route.pack), \(route.agency), \(literal(route.externalId)), \(literal(route.shortName)), \(literal(route.longName)), \(route.routeType));"
        }
        for service in index.services {
            sql += "INSERT INTO service VALUES (\(service.id), \(service.pack), \(literal(service.externalId)), \(service.startDate), \(service.dayCount), X'\(service.activeDays)');"
        }
        for stop in index.stops {
            sql += "INSERT INTO stop VALUES (\(stop.id), \(stop.pack), \(literal(stop.externalId)), \(literal(stop.name)), NULL, NULL, \(literal(stop.catalogStationId)), \(literal(stop.railwayCode)), NULL);"
        }
        // The city networks' tables (URBAN_SCHEMA_SQL of tools/build_ios_gtfs_schedule.py), when the index has them.
        if let urbanStops = index.urbanStops {
            sql += """
                CREATE TABLE urban_stop (stop_id INTEGER PRIMARY KEY REFERENCES stop(id), modes TEXT NOT NULL CHECK(modes NOT GLOB '*[^MTF]*'));
                CREATE TABLE urban_walk (catalog_station_id TEXT NOT NULL, stop_id INTEGER NOT NULL REFERENCES urban_stop(stop_id), minutes INTEGER NOT NULL CHECK(minutes >= 0), PRIMARY KEY(catalog_station_id, stop_id)) WITHOUT ROWID;
                """
            for stop in urbanStops {
                sql += "INSERT INTO urban_stop VALUES (\(stop.stop), \(literal(stop.modes)));"
            }
            for walk in index.urbanWalks ?? [] {
                sql += "INSERT INTO urban_walk VALUES (\(literal(walk.catalogStationId)), \(walk.stop), \(walk.minutes));"
            }
        }
        for trip in index.trips {
            sql += "INSERT INTO trip VALUES (\(trip.id), \(trip.pack), \(trip.route), \(trip.service), \(literal(trip.externalId)), NULL, \(literal(trip.shortName)), NULL, NULL);"
            for (offset, call) in trip.calls.enumerated() {
                guard call.count >= 3 else { throw GTFSFixtureError.sqlite }
                let pickup = call.count > 3 ? call[3] : 0
                let dropOff = call.count > 4 ? call[4] : 0
                sql += "INSERT INTO stop_time VALUES (\(trip.id), \(offset + 1), \(call[0]), \(call[1]), \(call[2]), \(pickup), \(dropOff));"
            }
        }
        try fixture.execute(sql)
    }

    private func literal(_ value: String?) -> String {
        guard let value else { return "NULL" }
        return "'" + value.replacingOccurrences(of: "'", with: "''") + "'"
    }

    private var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }

    // MARK: - The contract file

    private struct Contract: Decodable {
        let index: Index
        let cases: [Case]
    }

    private struct Index: Decodable {
        let packs: [Pack]
        let agencies: [Agency]
        let routes: [Route]
        let services: [Service]
        let stops: [Stop]
        let trips: [Trip]
        /// The city stops with their modes (`urban_stop`) and the walks to them (`urban_walk`).
        let urbanStops: [UrbanStopEntry]?
        let urbanWalks: [UrbanWalkEntry]?
    }

    private struct UrbanStopEntry: Decodable {
        let stop: Int
        let modes: String
    }

    private struct UrbanWalkEntry: Decodable {
        let catalogStationId: String
        let stop: Int
        let minutes: Int
    }

    private struct Pack: Decodable {
        let id: Int
        let sourceId: String
        let attribution: String
        let lastServiceDate: Int
    }

    private struct Agency: Decodable {
        let id: Int
        let pack: Int
        let externalId: String
        let name: String
        let timezone: String
    }

    private struct Route: Decodable {
        let id: Int
        let pack: Int
        let agency: Int
        let externalId: String
        let shortName: String?
        let longName: String?
        let routeType: Int
    }

    private struct Service: Decodable {
        let id: Int
        let pack: Int
        let externalId: String
        let startDate: Int
        let dayCount: Int
        /// Hexadecimal bytes of the day bitmask.
        let activeDays: String
    }

    private struct Stop: Decodable {
        let id: Int
        let pack: Int
        let externalId: String
        let name: String
        let catalogStationId: String?
        let railwayCode: String?
    }

    private struct Trip: Decodable {
        let id: Int
        let pack: Int
        let route: Int
        let service: Int
        let externalId: String
        let shortName: String?
        /// [stop, arrival, departure, pickupType = 0, dropOffType = 0] in stop order.
        let calls: [[Int]]
    }

    private struct Station: Decodable {
        let id: String
        let name: String
        let railwayCode: String?

        var identity: StationIdentity { StationIdentity(id: id, name: name, railwayCode: railwayCode) }
    }

    private struct Case: Decodable {
        let why: String
        let origin: Station
        let destination: Station
        let at: String
        let expected: Expected
    }

    private struct Expected: Decodable {
        let trains: [Train]?
        let unavailable: String?
    }

    /// A city run boarded a walk from the station also has `boardingStop` (compared) and `walkMinutes`, which the
    /// skill says and the planner keeps inside the search (`Candidate.walkMinutes`): the times of the cases check it.
    private struct Train: Decodable, Equatable {
        let time: String
        let number: String
        let category: String
        let operatorName: String
        let destination: String
        let vehicle: String
        let boardingStop: String?

        init(time: String, number: String, category: String, operatorName: String, destination: String, vehicle: String, boardingStop: String?) {
            self.time = time
            self.number = number
            self.category = category
            self.operatorName = operatorName
            self.destination = destination
            self.vehicle = vehicle
            self.boardingStop = boardingStop
        }

        enum CodingKeys: String, CodingKey {
            case time, number, category, operatorName = "operator", destination, vehicle, boardingStop
        }
    }
}
