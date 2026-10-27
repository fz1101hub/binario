import Foundation
import XCTest
@testable import BinarioKit

@MainActor
final class RfiJourneyRepositoryTests: XCTestCase {
    private let current = Date(timeIntervalSince1970: 1_793_062_002) // 2026-10-27 00:46:42 UTC

    func testRecordedMonitorReachesIntermediateCommuterRouteWithoutStaticCoverage() async throws {
        let data = try fixture("rfi-milano-20261027.html")
        let repository = try repository(data: data)
        guard case let .available(snapshot) = await repository.journeys(for: try query()) else { return XCTFail("Missing live route") }
        let train = try XCTUnwrap(snapshot.services.first { $0.number == "2451" })
        XCTAssertEqual(train.scheduledDeparture, "05:15")
        XCTAssertEqual(train.status, "ON_TIME")
        XCTAssertEqual(snapshot.availability(at: current), .live)
        XCTAssertEqual(snapshot.provenance.confidence, .infrastructurePrimary)
        XCTAssertEqual(snapshot.provenance.observedAt, current.addingTimeInterval(-4))
    }

    func testPublishedDelayChangesExpectedDepartureAndStatus() async throws {
        let source = String(decoding: try fixture("rfi-milano-20261027.html"), as: UTF8.self)
        // Mutate only the source's explicit zero-delay labels in the recorded DOM.
        let delayed = source.replacingOccurrences(of: "&quot;Nessuno&quot;", with: "&quot;15 minuti&quot;")
        let repository = try repository(data: Data(delayed.utf8))
        guard case let .available(snapshot) = await repository.journeys(for: try query()) else { return XCTFail("Missing delayed route") }
        let train = try XCTUnwrap(snapshot.services.first { $0.number == "2451" })
        XCTAssertEqual(train.delayMinutes, 15)
        XCTAssertEqual(train.expectedDeparture, "05:30")
        XCTAssertEqual(train.status, "DELAYED")
    }

    func testOldBoardCannotBecomeLiveAfterANewDownload() async throws {
        let stale = String(decoding: try fixture("rfi-milano-20261027.html"), as: UTF8.self)
            .replacingOccurrences(of: "27/10/2026", with: "26/10/2026")
        let result = await (try repository(data: Data(stale.utf8))).journeys(for: try query())
        XCTAssertEqual(result, .unavailable(.noAuthorizedProvider))
    }

    func testWrongStationIsRejectedEvenIfTheRequestedDestinationAppears() async throws {
        let repository = try repository(data: fixture("rfi-roma-20261027.html"))
        let result = await repository.journeys(for: try query())
        XCTAssertEqual(result, .unavailable(.noAuthorizedProvider))
    }

    func testTomorrowDoesNotContactCurrentBoard() async throws {
        let repository = RfiJourneyRepository(catalog: try catalog(), now: { [current] in current }, fetch: { _ in
            XCTFail("A future date must use the timetable")
            throw URLError(.unsupportedURL)
        })
        let query = try query(departure: current.addingTimeInterval(86_400))
        let result = await repository.journeys(for: query)
        XCTAssertEqual(result, .unavailable(.noAuthorizedProvider))
    }

    func testPublicCatalogIncludesSelectedAndOrdinaryStationOptions() throws {
        let catalog = try catalog()
        XCTAssertEqual(catalog.placeID(for: "Milano Centrale"), 1728)
        XCTAssertEqual(catalog.placeID(for: "Roma Termini"), 2416)
        XCTAssertEqual(catalog.placeID(for: "Latina"), 1539)
        XCTAssertNotNil(catalog.placeID(for: "Lodi"))
        XCTAssertNil(catalog.placeID(for: "Unknown"))
    }

    func testCurrentHTTPSMonitorReachesJourneySnapshot() async throws {
        guard ProcessInfo.processInfo.environment["BINARIO_VERIFY_LIVE"] == "true" else {
            throw XCTSkip("Explicit read-only live verification")
        }
        let now = Date()
        let repository = RfiJourneyRepository(catalog: try catalog())
        let query = try JourneyQuery(
            origin: StationIdentity(id: "S08409", name: "Roma Termini"),
            destination: StationIdentity(id: "S08419", name: "Roma Tiburtina"), departure: now
        )
        guard case let .available(snapshot) = await repository.journeys(for: query) else { return XCTFail("Current RFI route unavailable") }
        XCTAssertEqual(snapshot.availability(at: Date()), .live)
        XCTAssertFalse(snapshot.services.isEmpty)
        XCTAssertLessThan(Date().timeIntervalSince(snapshot.provenance.observedAt), 120)
        print("LIVE VERIFIED iOS: Roma Termini → Roma Tiburtina; trains \(snapshot.services.map(\.number)); observation \(snapshot.provenance.observedAt)")
    }

    private func repository(data: Data) throws -> RfiJourneyRepository {
        RfiJourneyRepository(catalog: try catalog(), now: { [current] in current }, fetch: { _ in data })
    }

    private func query(departure: Date? = nil) throws -> JourneyQuery {
        try JourneyQuery(origin: StationIdentity(id: "S01700", name: "Milano Centrale"),
            destination: StationIdentity(id: "S01825", name: "Lodi"), departure: departure ?? current)
    }

    private func catalog() throws -> RfiStationCatalog {
        try RfiStationCatalog(data: Data(contentsOf: repositoryRoot.appendingPathComponent("contracts/rail-sources/rfi_stations.v1.json")))
    }

    private func fixture(_ name: String) throws -> Data {
        try Data(contentsOf: repositoryRoot.appendingPathComponent("contracts/rail-live-fixtures/\(name)"))
    }

    private var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    }
}
