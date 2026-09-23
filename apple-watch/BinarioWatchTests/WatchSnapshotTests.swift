import XCTest
@testable import BinarioWatch

final class WatchSnapshotTests: XCTestCase {
    private let fixtureNow: Int64 = 1_893_484_800_000

    func testUnavailableSnapshotIsCompatibleAndDisclosesNoLiveData() {
        let snapshot = BinarioWatchSnapshot.unavailable

        XCTAssertTrue(snapshot.isCompatible)
        XCTAssertEqual(snapshot.freshness, .unavailable)
        XCTAssertEqual(snapshot.notice, "dati non disponibili in tempo reale")
    }

    func testServiceAccessibilityMentionsTrainAndPlatform() {
        let service = WatchService(number: "9512", operatorName: "Trenitalia", category: "Freccia", destination: "Torino Porta Nuova", scheduledDeparture: "08:10", expectedDeparture: "08:18", platform: "7", delayMinutes: 8, status: "In ritardo")

        XCTAssertTrue(service.accessibilitySummary.contains("treno 9512"))
        XCTAssertTrue(service.accessibilitySummary.contains("binario 7"))
    }

    func testIOSGoldenFixtureUsesTheSharedV1Contract() throws {
        let snapshot = BinarioWatchSnapshot.decodeContract(try fixtureData(), nowEpochMillis: fixtureNow)

        XCTAssertEqual(snapshot?.schemaVersion, 1)
        XCTAssertEqual(snapshot?.provenanceProducer, .iosApp)
        XCTAssertEqual(snapshot?.operationalState, .operational)
        XCTAssertEqual(snapshot?.services.first?.number, "FR 9505")
    }

    func testUnknownFutureEnumsDegradeToUnknown() throws {
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: fixtureData()) as? [String: Any])
        var future = object
        future["direction"] = "FUTURE_DIRECTION"
        future["freshness"] = "FUTURE_FRESHNESS"
        future["confidence"] = "FUTURE_CONFIDENCE"
        future["provenanceProducer"] = "FUTURE_PRODUCER"
        future["provenanceDataNature"] = "FUTURE_NATURE"
        future["operationalState"] = "FUTURE_STATE"
        let data = try JSONSerialization.data(withJSONObject: future)
        let snapshot = BinarioWatchSnapshot.decodeContract(data, nowEpochMillis: fixtureNow)

        XCTAssertEqual(snapshot?.direction, .unknown)
        XCTAssertEqual(snapshot?.freshness, .unknown)
        XCTAssertEqual(snapshot?.confidence, .unknown)
        XCTAssertEqual(snapshot?.provenanceProducer, .unknown)
        XCTAssertEqual(snapshot?.provenanceDataNature, .unknown)
        XCTAssertEqual(snapshot?.operationalState, .unknown)
    }

    func testFutureVersionExtraKeyAndExpiredPayloadAreRejected() throws {
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: fixtureData()) as? [String: Any])
        var futureVersion = object
        futureVersion["schemaVersion"] = 2
        var extraKey = object
        extraKey["token"] = "must-not-be-accepted"

        XCTAssertNil(BinarioWatchSnapshot.decodeContract(try JSONSerialization.data(withJSONObject: futureVersion), nowEpochMillis: fixtureNow))
        XCTAssertNil(BinarioWatchSnapshot.decodeContract(try JSONSerialization.data(withJSONObject: extraKey), nowEpochMillis: fixtureNow))
        XCTAssertNil(BinarioWatchSnapshot.decodeContract(try fixtureData(), nowEpochMillis: 1_893_485_700_001))
    }

    func testRefreshReloadsTheBinarioWidgetOnlyAfterReadingCache() {
        var events: [String] = []

        RefreshBinarioAction.requestTimelineReload(
            loadSnapshot: {
                events.append("load")
                return .unavailable
            },
            reloadTimeline: { kind in events.append("reload:\(kind)") }
        )

        XCTAssertEqual(events, ["load", "reload:\(BinarioWatchWidgetConfiguration.kind)"])
    }

    private func fixtureData() throws -> Data {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "ios-to-apple-watch-v1", withExtension: "json"))
        return try Data(contentsOf: url)
    }
}
