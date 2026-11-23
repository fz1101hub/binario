import Foundation
import XCTest
@testable import BinarioKit

/// The national packs on the iPhone as on Android: a Trenitalia train reads «FR 9241» from the
/// operator «Trenitalia», a Trenitalia bus or ItaloBus is a bus with its label, and a train two
/// packs publish (Trenitalia and Trenitalia France for Milano – Paris) is listed once.
final class TimetableVehicleTests: XCTestCase {
    private let rome = TimeZone(identifier: "Europe/Rome")!
    private let milano = StationIdentity(id: "TEST-MILANO", name: "Milano Centrale")
    private let torino = StationIdentity(id: "TEST-TORINO", name: "Torino Porta Susa")

    func testLineLabelsAndOperatorsAreNamedAsOnAndroid() {
        XCTAssertEqual(TimetableNaming.lineLabel("0083_FR"), "FR")
        XCTAssertEqual(TimetableNaming.lineLabel(" 0083_BUS "), "BUS")
        XCTAssertEqual(TimetableNaming.lineLabel("0083_ICN"), "ICN")
        XCTAssertEqual(TimetableNaming.lineLabel("0083_TOOLONG"), "0083_TOOLONG")
        XCTAssertEqual(TimetableNaming.lineLabel("RE_11"), "RE_11")
        XCTAssertEqual(TimetableNaming.lineLabel("Italo"), "Italo")
        XCTAssertEqual(TimetableNaming.operatorName("TRENITALIA", sourceID: "trenitalia-gtfs"), "Trenitalia")
        XCTAssertEqual(TimetableNaming.operatorName("Italo - Nuovo Trasporto Viaggiatori", sourceID: "italo-gtfs"), "Italo")
        XCTAssertEqual(TimetableNaming.operatorName("Schweizerische Bundesbahnen SBB", sourceID: "ch-otd-gtfs"), "SBB")
        // The agency of the bundled ÖBB pack; Android's canonicalAgencyLabel keeps a name it does not know as published.
        XCTAssertEqual(TimetableNaming.operatorName("OEBB Personenverkehr AG Kundenservice", sourceID: "oebb-gtfs"), "ÖBB")
        XCTAssertEqual(TimetableNaming.operatorName("ÖBB-Personenverkehr AG", sourceID: "oebb-gtfs"), "ÖBB-Personenverkehr AG")
        XCTAssertEqual(TimetableNaming.operatorName("", sourceID: "oebb-gtfs"), "ÖBB")
        XCTAssertEqual(TimetableNaming.operatorName("Ferrovie Appulo Lucane", sourceID: "fal-basilicata-puglia"), "Ferrovie Appulo Lucane")
        XCTAssertEqual(TimetableNaming.operatorName("Synthetic Rail", sourceID: "source"), "Synthetic Rail")
    }

    func testABusLabelKeepsItsLineOnlyWhenItAddsSomething() {
        XCTAssertEqual(TimetableVehicle.busLabel("Bus Trenitalia", vehicle: .busTrenitalia, line: "BUS", number: "1234"), "Bus Trenitalia BUS")
        XCTAssertEqual(TimetableVehicle.busLabel("ItaloBus", vehicle: .italobus, line: "ItaloBus", number: "1060"), "ItaloBus")
        XCTAssertEqual(TimetableVehicle.busLabel("Bus sostitutivo", vehicle: .replacementBus, line: "S11", number: "4417"), "Bus sostitutivo S11")
        XCTAssertEqual(TimetableVehicle.busLabel("Autobus", vehicle: .bus, line: "Collegamento (Cortina)", number: "1"), "Autobus")
        XCTAssertEqual(TimetableVehicle.busLabel("Autobus", vehicle: .bus, line: "1060", number: "1060"), "Autobus")
        XCTAssertEqual(TimetableVehicle.busLabel("Autobus", vehicle: .bus, line: nil), "Autobus")
    }

    func testTheCoreNumberIsTheLastRunOfDigits() {
        XCTAssertEqual(TimetableCopies.coreNumber("S11 - 25017"), "25017")
        XCTAssertEqual(TimetableCopies.coreNumber("REX 1826"), "1826")
        XCTAssertEqual(TimetableCopies.coreNumber("09241"), "9241")
        XCTAssertNil(TimetableCopies.coreNumber("Italo"))
        XCTAssertNil(TimetableCopies.coreNumber("000"))
    }

    func testTheNationalPacksCopyIsListedOnceAndABusAsABus() async throws {
        let fixture = try Fixture()
        let repository = SQLiteGTFSJourneyRepository(databaseURL: fixture.database.url)

        let result = await repository.journeys(for: try JourneyQuery(origin: milano, destination: torino, departure: date("2024-04-01 09:00")))
        guard case let .available(snapshot) = result else { return XCTFail("expected trains, got \(result)") }
        // FR 9241 is in both packs at the same minutes: the national pack's copy stays.
        XCTAssertEqual(snapshot.services.map(\.number), ["9241", "1234", "9243"])
        let train = snapshot.services[0]
        XCTAssertTrue(train.id.hasPrefix("trenitalia-gtfs:"), train.id)
        XCTAssertEqual(train.category, "FR")
        XCTAssertEqual(train.operatorName, "Trenitalia")
        XCTAssertNil(train.vehicle)
        XCTAssertFalse(train.isBus)
        let bus = snapshot.services[1]
        XCTAssertEqual(bus.vehicle, .busTrenitalia)
        XCTAssertTrue(bus.isBus)
        XCTAssertEqual(bus.category, "BUS")
        // Trenitalia France's own train, at other minutes, is listed.
        XCTAssertTrue(snapshot.services[2].id.hasPrefix("trenitalia-france-gtfs:"))

        let board = await repository.departures(for: try StationBoardQuery(station: milano, from: date("2024-04-01 09:00"), limit: 10))
        guard case let .available(page) = board else { return XCTFail("expected a board, got \(board)") }
        XCTAssertEqual(page.departures.map(\.service.number), ["9241", "1234", "9243"])
        XCTAssertTrue(page.departures[0].service.id.hasPrefix("trenitalia-gtfs:"))
        XCTAssertEqual(page.departures[1].service.vehicle, .busTrenitalia)
    }

    func testABusOfAnyOtherPackReplacesATrain() {
        XCTAssertEqual(TimetableLegMode.of(routeType: 3, sourceID: "lombardia-trenord-gtfs"), .replacementBus)
        XCTAssertEqual(TimetableLegMode.of(routeType: 3, sourceID: "trenitalia-gtfs"), .bus)
        XCTAssertEqual(TimetableLegMode.of(routeType: 109, sourceID: "ch-otd-gtfs"), .train)
        XCTAssertNil(TimetableLegMode.of(routeType: 4, sourceID: "trenitalia-gtfs"))
        XCTAssertEqual(
            TrainService(id: "x", number: "1", operatorName: "T", category: "R", destination: "D", scheduledDeparture: "10:00",
                         expectedDeparture: "10:00", status: "PROGRAMMED", platform: nil, delayMinutes: 0, vehicle: .train).vehicle,
            nil,
            "a train keeps no vehicle"
        )
    }

    private func date(_ value: String) throws -> Date {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = rome
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return try XCTUnwrap(formatter.date(from: value))
    }

    /// Trenitalia's pack (1) with FR 9241 and a bus, Trenitalia France's (2) with its copy of FR 9241
    /// and its own FR 9243. Times are seconds after midnight on 2024-04-01, Rome.
    private final class Fixture {
        let database: GTFSFixtureDatabase

        init() throws {
            typealias Call = GTFSFixtureDatabase.Call
            let database = try GTFSFixtureDatabase(packs: [
                (id: 1, sourceID: "trenitalia-gtfs", lastServiceDate: 20241231),
                (id: 2, sourceID: "trenitalia-france-gtfs", lastServiceDate: 20241231),
            ])
            try database.execute("""
                UPDATE agency SET name = 'TRENITALIA' WHERE id = 1;
                UPDATE agency SET name = 'Trenitalia France' WHERE id = 2;
                UPDATE route SET short_name = '0083_FR', long_name = 'Frecciarossa' WHERE id = 1;
                UPDATE route SET short_name = 'FR', long_name = NULL WHERE id = 2;
                INSERT INTO route VALUES (3, 1, 1, 'route-bus', '0083_BUS', 'Autobus', 3);
                """)
            try database.insertStop(id: 1, pack: 1, externalID: "mi", name: "MILANO CENTRALE", catalogStationID: "TEST-MILANO")
            try database.insertStop(id: 2, pack: 1, externalID: "to", name: "TORINO PORTA SUSA", catalogStationID: "TEST-TORINO")
            try database.insertStop(id: 3, pack: 2, externalID: "mi", name: "Milano Centrale", catalogStationID: "TEST-MILANO")
            try database.insertStop(id: 4, pack: 2, externalID: "to", name: "Torino Porta Susa", catalogStationID: "TEST-TORINO")
            try database.insertTrip(id: 1, pack: 1, externalID: "fr-9241", number: "9241", calls: [
                Call(stop: 1, seconds: 10 * 3_600), Call(stop: 2, seconds: 11 * 3_600),
            ])
            try database.insertTrip(id: 2, pack: 2, externalID: "fr-9241-france", number: "9241", calls: [
                Call(stop: 3, seconds: 10 * 3_600), Call(stop: 4, seconds: 11 * 3_600),
            ])
            try database.insertTrip(id: 3, pack: 2, externalID: "fr-9243", number: "9243", calls: [
                Call(stop: 3, seconds: 12 * 3_600), Call(stop: 4, seconds: 13 * 3_600),
            ])
            try database.insertTrip(id: 4, pack: 1, externalID: "bus-1234", number: "1234", calls: [
                Call(stop: 1, seconds: 11 * 3_600), Call(stop: 2, seconds: 12 * 3_600 + 30 * 60),
            ])
            try database.execute("UPDATE trip SET route_id = 3 WHERE id = 4;")
            database.close()
            self.database = database
        }
    }
}
