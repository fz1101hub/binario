import XCTest
@testable import BinarioKit

/// The GPS stop alarm's rules (Android's `GpsArrivalAlarmPolicyTest`): radius choices and defaults, train
/// class, availability and end.
final class GpsStopAlarmTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_790_000_000)

    private func at(_ minutes: Int) -> Date { now.addingTimeInterval(TimeInterval(minutes * 60)) }

    func testRadiusOptionsGoFromOneToTwentyFiveKilometresAsOnAndroid() {
        XCTAssertEqual(GpsStopAlarmPolicy.radiusOptionsMeters, [1_000, 2_000, 3_000, 5_000, 10_000, 15_000, 20_000, 25_000])
        XCTAssertTrue(GpsStopAlarmPolicy.isRadiusOption(25_000))
        XCTAssertFalse(GpsStopAlarmPolicy.isRadiusOption(4_000))
    }

    func testLongDistanceTrainsGetAWiderDefaultAndARememberedChoiceWins() {
        XCTAssertEqual(GpsStopAlarmPolicy.trainClass(.highSpeed), .longDistance)
        XCTAssertEqual(GpsStopAlarmPolicy.trainClass(.intercityNight), .longDistance)
        XCTAssertEqual(GpsStopAlarmPolicy.trainClass(.italo), .longDistance)
        XCTAssertEqual(GpsStopAlarmPolicy.trainClass(.international), .longDistance)
        XCTAssertEqual(GpsStopAlarmPolicy.trainClass(.regionalSuburban), .regional)
        XCTAssertEqual(GpsStopAlarmPolicy.trainClass(.replacementBus), .regional)
        XCTAssertEqual(GpsStopAlarmPolicy.trainClass(nil), .regional)
        XCTAssertEqual(GpsStopAlarmPolicy.initialRadiusMeters(.regional, remembered: nil), 2_000)
        XCTAssertEqual(GpsStopAlarmPolicy.initialRadiusMeters(.longDistance, remembered: nil), 20_000)
        XCTAssertEqual(GpsStopAlarmPolicy.initialRadiusMeters(.regional, remembered: 5_000), 5_000)
        XCTAssertEqual(GpsStopAlarmPolicy.initialRadiusMeters(.regional, remembered: 4_000), 2_000)
    }

    func testTheOptionNeedsCoordinatesAndAnArrivalWithinEightHours() {
        XCTAssertEqual(GpsStopAlarmPolicy.availability(now: now, arrival: at(30), hasCoordinates: false), .noCoordinates)
        XCTAssertEqual(GpsStopAlarmPolicy.availability(now: now, arrival: at(480), hasCoordinates: true), .available)
        XCTAssertEqual(GpsStopAlarmPolicy.availability(now: now, arrival: at(481), hasCoordinates: true), .tooEarly)
        XCTAssertEqual(GpsStopAlarmPolicy.maximumDurationHours, 8)
    }

    func testTheAlarmEndsTwoHoursAfterTheArrivalAtMostEightHoursAfterItWasSet() throws {
        XCTAssertEqual(GpsStopAlarmPolicy.expiresAt(startedAt: now, arrival: at(60)), at(180))
        XCTAssertEqual(GpsStopAlarmPolicy.expiresAt(startedAt: now, arrival: at(420)), at(480))
        let plan = try XCTUnwrap(StopAlarmPlanner.plan(trainNumber: "2153", destination: "Como", station: "Seregno",
                                                       expectedTime: "9:00", expectedAt: at(60), now: now))
        let alarm = try XCTUnwrap(GpsStopAlarmPolicy.alarm(for: plan, latitude: 45.65, longitude: 9.2, radiusMeters: 2_000, now: now))
        XCTAssertEqual(alarm.expiresAt, at(180))
        XCTAssertTrue(alarm.isFor(trainNumber: " 2153", station: "Seregno"))
        XCTAssertFalse(GpsStopAlarmPolicy.isExpired(alarm, now: at(179)))
        XCTAssertTrue(GpsStopAlarmPolicy.isExpired(alarm, now: at(180)))
        XCTAssertNil(GpsStopAlarmPolicy.alarm(for: plan, latitude: 45.65, longitude: 9.2, radiusMeters: 4_000, now: now))
        XCTAssertNil(GpsStopAlarmPolicy.alarm(for: plan, latitude: 95, longitude: 9.2, radiusMeters: 2_000, now: now))
        XCTAssertNil(GpsStopAlarmPolicy.alarm(for: plan, latitude: 45.65, longitude: 9.2, radiusMeters: 2_000, now: at(-481)))
    }
}
