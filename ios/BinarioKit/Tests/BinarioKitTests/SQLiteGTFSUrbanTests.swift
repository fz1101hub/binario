import Foundation
import XCTest
@testable import BinarioKit

/// The city networks of the bundled index (tools/build_ios_gtfs_schedule.py, `urban_stop` and
/// `urban_walk`), read as Android's planner reads them: a people mover reached on foot from the
/// stations near its ends, its stops picked in the search as `gtfs:<pack>:<stop>`, its board, and
/// its services that do not run on a day left out.
///
/// Trenitalia: Bologna → Imola. Marconi Express (route type 12): Bologna Centrale FS (BFS, 4 minutes
/// on foot from TEST-BOLOGNA), Lazzaretto (LAZ, 9 minutes from it) and Bologna Aeroporto (BLQ,
/// 5 minutes from TEST-AEROPORTO, a station no railway serves).
final class SQLiteGTFSUrbanTests: XCTestCase {
    private let rome = TimeZone(identifier: "Europe/Rome")!
    private let bologna = StationIdentity(id: "TEST-BOLOGNA", name: "Bologna Centrale")
    private let imola = StationIdentity(id: "TEST-IMOLA", name: "Imola")
    private let airport = StationIdentity(id: "TEST-AEROPORTO", name: "Bologna Aeroporto")
    private let bfs = StationIdentity(id: "gtfs:marconi-express-gtfs:BFS", name: "Bologna Centrale FS")
    private let blq = StationIdentity(id: "gtfs:marconi-express-gtfs:BLQ", name: "Bologna Aeroporto")

    func testAStationAShortWalkFromACityLineIsServedByIt() async throws {
        let fixture = try Fixture()
        let repository = SQLiteGTFSJourneyRepository(databaseURL: fixture.database.url)

        let result = await repository.journeys(for: try query(bologna, airport, at: "2024-04-01 09:50"))
        guard case let .available(snapshot) = result else { return XCTFail("expected the people mover, got \(result)") }
        // 09:58 is boarded at BFS (4 minutes on foot), not at Lazzaretto (9 minutes): one row per run.
        // The 10:10 runs on another day only.
        XCTAssertEqual(snapshot.services.map(\.scheduledDeparture), ["09:58", "10:06", "10:20"])
        for service in snapshot.services {
            XCTAssertEqual(service.vehicle, .metro)
            XCTAssertTrue(service.isUrban)
            XCTAssertFalse(service.isTrain)
            XCTAssertFalse(service.hasTrainNumber, "a city run is never asked to the live sources")
            XCTAssertEqual(service.number, "MEX")
            XCTAssertEqual(service.category, "MEX")
            XCTAssertEqual(service.boardingStop, "Bologna Centrale FS")
            XCTAssertEqual(service.destination, "Bologna Aeroporto")
        }

        // The walk comes first: from 10:03, the 10:06 at BFS cannot be reached. The search covers the next 24 hours,
        // so the next run is tomorrow's 09:58 from BFS (the service runs every day to 2 April), not today's.
        let later = await repository.journeys(for: try query(bologna, airport, at: "2024-04-01 10:03"))
        guard case let .available(laterSnapshot) = later else { return XCTFail("expected the people mover, got \(later)") }
        XCTAssertEqual(laterSnapshot.services.map(\.scheduledDeparture), ["10:20", "09:58"])
        XCTAssertEqual(laterSnapshot.services.map(\.id), [
            "marconi-express-gtfs:mex-3:20240401:\(10 * 3_600 + 20 * 60)",
            "marconi-express-gtfs:mex-1:20240402:\(9 * 3_600 + 58 * 60)",
        ])

        // The railway search is the one it always was.
        let rail = await repository.journeys(for: try query(bologna, imola, at: "2024-04-01 09:50"))
        guard case let .available(railSnapshot) = rail else { return XCTFail("expected the train, got \(rail)") }
        XCTAssertEqual(railSnapshot.services.map(\.number), ["REG 1"])
        XCTAssertNil(railSnapshot.services[0].vehicle)
        XCTAssertNil(railSnapshot.services[0].boardingStop)
    }

    func testACityStopPickedInTheSearchIsBoardedAtItself() async throws {
        let fixture = try Fixture()
        let repository = SQLiteGTFSJourneyRepository(databaseURL: fixture.database.url)

        let result = await repository.journeys(for: try query(bfs, blq, at: "2024-04-01 09:50"))
        guard case let .available(snapshot) = result else { return XCTFail("expected the people mover, got \(result)") }
        XCTAssertEqual(snapshot.services.map(\.scheduledDeparture), ["09:58", "10:06", "10:20"])
        XCTAssertEqual(snapshot.services.map(\.boardingStop), [nil, nil, nil])

        let board = await repository.departures(for: try StationBoardQuery(station: bfs, from: date("2024-04-01 09:50"), limit: 10))
        guard case let .available(page) = board else { return XCTFail("expected a board, got \(board)") }
        XCTAssertEqual(page.departures.map(\.service.scheduledDeparture), ["09:58", "10:06", "10:20"])
        XCTAssertEqual(page.departures.map(\.terminus.id), [blq.id, blq.id, blq.id])
        XCTAssertEqual(page.departures.compactMap(\.service.vehicle), [.metro, .metro, .metro])
    }

    func testTheCityStopsAreOfferedAndTheirStationsCovered() throws {
        let fixture = try Fixture()
        let repository = SQLiteGTFSJourneyRepository(databaseURL: fixture.database.url)

        let covered = try repository.coveredCatalogStationIDs()
        XCTAssertTrue(covered.isSuperset(of: ["TEST-BOLOGNA", "TEST-IMOLA", "TEST-AEROPORTO"]))
        let index = try repository.urbanStopIndex()
        XCTAssertEqual(index.stops.map(\.id), [bfs.id, "gtfs:marconi-express-gtfs:LAZ", blq.id])
        XCTAssertEqual(index.search("aerop").map(\.station.name), ["Bologna Aeroporto"])
        XCTAssertEqual(index.badge(of: blq.id), .metro)
        XCTAssertTrue(index.search("a").isEmpty, "two characters at least")
        let merged = UrbanStopIndex.merged(
            stations: [Station(id: "TEST-AEROPORTO", name: "Bologna Aeroporto"), Station(id: "TEST-BOLOGNA", name: "Bologna Centrale")],
            urban: index.search("bologna"),
            query: "bologna"
        )
        // No station is named «Bologna»: the city stops (by rank, then name) before the stations.
        XCTAssertEqual(merged.map(\.id), [blq.id, bfs.id, "TEST-AEROPORTO", "TEST-BOLOGNA"])
    }

    func testCityNamesBadgesAndPlatforms() {
        XCTAssertEqual(TimetableNaming.urbanLine("1", mode: .metro), "M1")
        XCTAssertEqual(TimetableNaming.urbanLine("MEB1", mode: .metro), "B1")
        XCTAssertEqual(TimetableNaming.urbanLine("14", mode: .tram), "14")
        XCTAssertNil(TimetableNaming.urbanLine(" ", mode: .tram))
        XCTAssertEqual(TimetableVehicle.urbanLabel("Metro", line: "M1"), "Metro M1")
        XCTAssertEqual(TimetableVehicle.urbanLabel("Tram", line: "tram"), "Tram")
        XCTAssertEqual(TimetableVehicle.urbanLabel("Tram", line: "METROCA 1-3"), "Tram", "a long name is left out")
        XCTAssertEqual(UrbanStopBadge.of(modes: "TM"), .metro)
        XCTAssertNil(UrbanStopBadge.of(modes: ""))
        XCTAssertEqual(UrbanStops.parse("gtfs:milano-atm-gtfs:DUOMO M1")?.stopID, "DUOMO M1")
        XCTAssertNil(UrbanStops.parse("TEST-BOLOGNA"))
        let rows = [
            UrbanStopRow(sourceID: "x", externalID: "a", name: "Duomo", latitude: 45.0, longitude: 9.0, modes: "M"),
            UrbanStopRow(sourceID: "x", externalID: "b", name: "DUOMO", latitude: 45.003, longitude: 9.0, modes: "M"),
            UrbanStopRow(sourceID: "x", externalID: "c", name: "Duomo", latitude: 45.01, longitude: 9.0, modes: "T"),
            UrbanStopRow(sourceID: "x", externalID: "d", name: "Cordusio", latitude: 45.0, longitude: 9.0, modes: "M"),
        ]
        // About 330 m and 1.1 km away: the first is a platform of «Duomo», the second another place.
        XCTAssertEqual(UrbanStops.platforms(of: 0, among: rows), [0, 1])
        // One entry per name, a metro stop before a tram stop of the same name.
        XCTAssertEqual(UrbanStopIndex(rows: rows).stops.map(\.id), ["gtfs:x:a", "gtfs:x:d"])
    }

    // MARK: - Fixture

    private func query(_ origin: StationIdentity, _ destination: StationIdentity, at time: String) throws -> JourneyQuery {
        try JourneyQuery(origin: origin, destination: destination, departure: date(time))
    }

    private func date(_ value: String) throws -> Date {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = rome
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return try XCTUnwrap(formatter.date(from: value))
    }

    /// Times are seconds after midnight on 2024-04-01, Rome.
    private final class Fixture {
        let database: GTFSFixtureDatabase

        init() throws {
            typealias Call = GTFSFixtureDatabase.Call
            let database = try GTFSFixtureDatabase(packs: [
                (id: 1, sourceID: "trenitalia-gtfs", lastServiceDate: 20241231),
                (id: 2, sourceID: "marconi-express-gtfs", lastServiceDate: 20241231),
            ])
            try database.execute("""
                CREATE TABLE urban_stop (stop_id INTEGER PRIMARY KEY REFERENCES stop(id), modes TEXT NOT NULL CHECK(modes NOT GLOB '*[^MTF]*'));
                CREATE TABLE urban_walk (catalog_station_id TEXT NOT NULL, stop_id INTEGER NOT NULL REFERENCES urban_stop(stop_id), minutes INTEGER NOT NULL CHECK(minutes >= 0), PRIMARY KEY(catalog_station_id, stop_id)) WITHOUT ROWID;
                UPDATE route SET route_type = 12, short_name = 'MEX', long_name = 'Marconi Express' WHERE id = 2;
                INSERT INTO service VALUES (3, 2, 'saturday-only', 20240330, 4, X'01');
                """)
            try database.insertStop(id: 1, pack: 1, externalID: "bologna", name: "Bologna Centrale", catalogStationID: "TEST-BOLOGNA")
            try database.insertStop(id: 2, pack: 1, externalID: "imola", name: "Imola", catalogStationID: "TEST-IMOLA")
            try database.insertStop(id: 3, pack: 2, externalID: "BFS", name: "Bologna Centrale FS", catalogStationID: nil)
            try database.insertStop(id: 4, pack: 2, externalID: "LAZ", name: "Lazzaretto", catalogStationID: nil)
            try database.insertStop(id: 5, pack: 2, externalID: "BLQ", name: "Bologna Aeroporto", catalogStationID: nil)
            try database.execute("""
                INSERT INTO urban_stop VALUES (3, 'M'), (4, 'M'), (5, 'M');
                INSERT INTO urban_walk VALUES ('TEST-BOLOGNA', 3, 4), ('TEST-BOLOGNA', 4, 9), ('TEST-AEROPORTO', 5, 5);
                """)
            try database.insertTrip(id: 1, pack: 1, externalID: "reg-1", number: "REG 1", calls: [
                Call(stop: 1, seconds: 10 * 3_600), Call(stop: 2, seconds: 10 * 3_600 + 30 * 60),
            ])
            try database.insertTrip(id: 2, pack: 2, externalID: "mex-1", number: "", calls: [
                Call(stop: 3, seconds: 9 * 3_600 + 58 * 60), Call(stop: 4, seconds: 10 * 3_600 + 2 * 60),
                Call(stop: 5, seconds: 10 * 3_600 + 5 * 60),
            ])
            try database.insertTrip(id: 3, pack: 2, externalID: "mex-2", number: "", calls: [
                Call(stop: 3, seconds: 10 * 3_600 + 6 * 60), Call(stop: 5, seconds: 10 * 3_600 + 13 * 60),
            ])
            try database.insertTrip(id: 4, pack: 2, externalID: "mex-3", number: "", calls: [
                Call(stop: 3, seconds: 10 * 3_600 + 20 * 60), Call(stop: 5, seconds: 10 * 3_600 + 27 * 60),
            ])
            // A run of a service that does not run on 1 April: left out in SQL (`idleUrbanServices`).
            try database.execute("""
                INSERT INTO trip VALUES (5, 2, 2, 3, 'mex-saturday', NULL, NULL, NULL, NULL);
                INSERT INTO stop_time VALUES (5, 1, 3, 36600, 36600, 0, 0), (5, 2, 5, 37020, 37020, 0, 0);
                """)
            database.close()
            self.database = database
        }
    }
}
