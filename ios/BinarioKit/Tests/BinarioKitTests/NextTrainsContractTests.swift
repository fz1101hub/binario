import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/next-trains-v1.json: the next trains between two stations of the
/// programmed timetable index, as the planner reads them (`SQLiteGTFSJourneyRepository.journeys`)
/// and as the Alexa skill reads them (alexa-companion/server/timetable/nextTrains.test.ts).
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
                        vehicle: ($0.vehicle ?? TimetableVehicle.train).rawValue
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

    private struct Train: Decodable, Equatable {
        let time: String
        let number: String
        let category: String
        let operatorName: String
        let destination: String
        let vehicle: String

        init(time: String, number: String, category: String, operatorName: String, destination: String, vehicle: String) {
            self.time = time
            self.number = number
            self.category = category
            self.operatorName = operatorName
            self.destination = destination
            self.vehicle = vehicle
        }

        enum CodingKeys: String, CodingKey {
            case time, number, category, operatorName = "operator", destination, vehicle
        }
    }
}
