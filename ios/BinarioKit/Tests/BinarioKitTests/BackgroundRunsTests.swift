import XCTest
@testable import BinarioKit

/// «Prossimi controlli in background» (Android's `BackgroundRunsTest`).
final class BackgroundRunsTests: XCTestCase {
    private func rome(_ date: String, _ time: String) throws -> Date {
        try XCTUnwrap(ItalianWallClock.instant(date: date, time: time, zoneIdentifier: "Europe/Rome"))
    }

    func testADueCheckIsSoonAndTheOthersAreTodayTomorrowOrTheirDay() throws {
        let now = try rome("2026-11-24", "21:30")
        XCTAssertEqual(BackgroundRunTime.of(now, now: now), .soon)
        XCTAssertEqual(BackgroundRunTime.of(try rome("2026-11-24", "20:00"), now: now), .soon)
        XCTAssertEqual(BackgroundRunTime.of(try rome("2026-11-24", "23:45"), now: now), .today(try XCTUnwrap(PlannedLocalTime(hour: 23, minute: 45))))
        XCTAssertEqual(BackgroundRunTime.of(try rome("2026-11-25", "07:05"), now: now), .tomorrow(try XCTUnwrap(PlannedLocalTime(hour: 7, minute: 5))))
        XCTAssertEqual(
            BackgroundRunTime.of(try rome("2026-11-29", "18:00"), now: now),
            .later(try XCTUnwrap(PlannedLocalDate(iso: "2026-11-29")), try XCTUnwrap(PlannedLocalTime(hour: 18, minute: 0)))
        )
    }
}
