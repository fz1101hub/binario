import Foundation
import XCTest
@testable import BinarioKit

final class CommuterPresentationTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    func testFreshStaticFetchCannotBePresentedAsLive() throws {
        XCTAssertEqual(try snapshot().availability(at: now), .scheduled)
    }

    func testExpiryAndClockSkewRemoveCurrentStatusWithoutARefresh() throws {
        let value = try snapshot()
        XCTAssertEqual(value.availability(at: value.expiresAt), .stale)
        XCTAssertEqual(try snapshot(observed: now.addingTimeInterval(61)).availability(at: now), .stale)
    }

    func testOperationalNatureRequiresOperationalStateAndFreshness() throws {
        XCTAssertEqual(try snapshot(nature: .operational, state: .programmed).availability(at: now), .unavailable)
        XCTAssertEqual(try snapshot(nature: .operational, state: .operational, confidence: .openData, freshness: .fresh).availability(at: now), .live)
        XCTAssertEqual(try snapshot(nature: .operational, state: .operational, confidence: .staticOnly, freshness: .fresh).availability(at: now), .unavailable)
    }

    func testScheduledFallbackRetriesLiveAfterOneMinute() throws {
        XCTAssertFalse(CommuterRefreshPolicy.shouldRefresh(loading: true, lastAttempt: nil, now: now))
        XCTAssertFalse(CommuterRefreshPolicy.shouldRefresh(loading: false, lastAttempt: now.addingTimeInterval(-59), now: now))
        XCTAssertTrue(CommuterRefreshPolicy.shouldRefresh(loading: false, lastAttempt: now.addingTimeInterval(-60), now: now))
        XCTAssertTrue(CommuterRefreshPolicy.shouldRefresh(loading: false, lastAttempt: now.addingTimeInterval(-300), now: now))
        XCTAssertFalse(CommuterRefreshPolicy.shouldRefresh(loading: false, lastAttempt: now.addingTimeInterval(-60), now: now, failed: true))
        XCTAssertTrue(CommuterRefreshPolicy.shouldRefresh(loading: false, lastAttempt: now.addingTimeInterval(-120), now: now, failed: true))
    }

    private func snapshot(
        nature: DataNature = .programmed, state: OperationalState = .programmed,
        confidence: DataConfidence = .staticOnly, freshness: DataFreshness = .unknown,
        observed: Date? = nil
    ) throws -> JourneySnapshot {
        let query = try JourneyQuery(
            origin: StationIdentity(id: "a", name: "Bolzano"),
            destination: StationIdentity(id: "b", name: "Merano"), departure: now
        )
        return JourneySnapshot(
            query: query, generatedAt: now, expiresAt: now.addingTimeInterval(600),
            operationalState: state,
            provenance: DataProvenance(producer: .iosApp, nature: nature, sourceLabel: "GTFS",
                observedAt: observed ?? now, freshness: freshness, confidence: confidence), services: []
        )
    }
}
