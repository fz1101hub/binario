import XCTest
@testable import BinarioWatch

final class WatchSnapshotTests: XCTestCase {
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
}
