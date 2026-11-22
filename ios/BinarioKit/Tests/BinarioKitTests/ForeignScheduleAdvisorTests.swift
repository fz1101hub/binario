import Foundation
import XCTest
@testable import BinarioKit

/// When the iPhone offers a full foreign timetable, on the synthetic sample: ÖBB pinned (zone
/// Brennero and Innsbruck, served also Salzburg and Wien), Switzerland and France bundled whole.
final class ForeignScheduleAdvisorTests: XCTestCase {
    private let today = 20261122

    private func pins() throws -> ForeignSchedulePins {
        try ForeignSchedulePins(data: Data(LiveFixtures.text("foreign_packs_v1.sample.json").utf8))
    }

    func testANoResultSearchWithAnEndOutsideTheZoneAsksForThePack() throws {
        let advisor = ForeignScheduleAdvisor(pins: try pins(), installed: [])

        let offer = try XCTUnwrap(advisor.offer(origin: "TEST-BRENNERO", destination: "TEST-WIEN", foundTrains: false, today: today))
        XCTAssertEqual(offer.kind, .dialog)
        XCTAssertEqual(offer.packs.map(\.sourceID), ["oebb-gtfs"])
        XCTAssertEqual(offer.ended, [])
        XCTAssertEqual(
            advisor.offer(origin: "TEST-WIEN", destination: "TEST-BRENNERO", foundTrains: false, today: today)?.kind,
            .dialog
        )
    }

    func testZoneStationsStationsOutsideThePackAndWholePacksAreNeverOffered() throws {
        let advisor = ForeignScheduleAdvisor(pins: try pins(), installed: [])

        // Both ends in the border zone: the bundled part is complete.
        XCTAssertNil(advisor.offer(origin: "TEST-BRENNERO", destination: "TEST-INNSBRUCK", foundTrains: false, today: today))
        // An end the pack never serves: a direct train of that pack cannot join them.
        XCTAssertNil(advisor.offer(origin: "TEST-ROMA", destination: "TEST-WIEN", foundTrains: false, today: today))
        // Switzerland and France are bundled whole (no pin): nothing to download.
        XCTAssertNil(advisor.offer(origin: "TEST-DOMODOSSOLA", destination: "TEST-BERN", foundTrains: false, today: today))
        XCTAssertNil(advisor.offer(origin: "TEST-VENTIMIGLIA", destination: "TEST-NICE", foundTrains: false, today: today))
        XCTAssertNil(advisor.boardOffer(station: "TEST-BERN", today: today))
    }

    func testTrainsFoundGiveOneLineOnlyWhenBothEndsAreOutsideTheZone() throws {
        let advisor = ForeignScheduleAdvisor(pins: try pins(), installed: [])

        let banner = try XCTUnwrap(advisor.offer(origin: "TEST-SALZBURG", destination: "TEST-WIEN", foundTrains: true, today: today))
        XCTAssertEqual(banner.kind, .banner)
        XCTAssertEqual(banner.packs.map(\.sourceID), ["oebb-gtfs"])
        XCTAssertNil(advisor.offer(origin: "TEST-BRENNERO", destination: "TEST-WIEN", foundTrains: true, today: today))
    }

    func testAnInstalledPackADeclinedDialogAndAnEndedPin() throws {
        let installed = ForeignScheduleAdvisor(pins: try pins(), installed: ["oebb-gtfs"])
        XCTAssertNil(installed.offer(origin: "TEST-BRENNERO", destination: "TEST-WIEN", foundTrains: false, today: today))
        XCTAssertNil(installed.boardOffer(station: "TEST-WIEN", today: today))

        // «Non ora»: no dialog again in this launch; the one-line offers stay.
        let declined = ForeignScheduleAdvisor(pins: try pins(), installed: [], declined: ["oebb-gtfs"])
        XCTAssertNil(declined.offer(origin: "TEST-BRENNERO", destination: "TEST-WIEN", foundTrains: false, today: today))
        XCTAssertEqual(declined.offer(origin: "TEST-SALZBURG", destination: "TEST-WIEN", foundTrains: true, today: today)?.kind, .banner)
        XCTAssertEqual(declined.boardOffer(station: "TEST-WIEN", today: today)?.packs.map(\.sourceID), ["oebb-gtfs"])

        // After the pin's last day nothing is downloaded: the app needs an update.
        let advisor = ForeignScheduleAdvisor(pins: try pins(), installed: [])
        let ended = try XCTUnwrap(advisor.offer(origin: "TEST-BRENNERO", destination: "TEST-WIEN", foundTrains: false, today: 20261213))
        XCTAssertEqual(ended.packs, [])
        XCTAssertEqual(ended.ended.map(\.sourceID), ["oebb-gtfs"])
        XCTAssertEqual(advisor.boardOffer(station: "TEST-WIEN", today: 20261213)?.ended.map(\.sourceID), ["oebb-gtfs"])
        XCTAssertNotNil(advisor.offer(origin: "TEST-BRENNERO", destination: "TEST-WIEN", foundTrains: false, today: 20261212))
    }

    func testTheBoardOfAStationOutsideTheZoneOffersThePack() throws {
        let advisor = ForeignScheduleAdvisor(pins: try pins(), installed: [])

        let offer = try XCTUnwrap(advisor.boardOffer(station: "TEST-WIEN", today: today))
        XCTAssertEqual(offer.kind, .banner)
        XCTAssertEqual(offer.packs.map(\.sourceID), ["oebb-gtfs"])
        XCTAssertNil(advisor.boardOffer(station: "TEST-INNSBRUCK", today: today))
        XCTAssertNil(advisor.boardOffer(station: "TEST-ROMA", today: today))
    }
}
