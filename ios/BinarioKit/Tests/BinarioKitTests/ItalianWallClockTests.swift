import Foundation
import XCTest
@testable import BinarioKit

/// The Agenda's Italian wall clock (the companion's travel-time-zone tests, EXPO_PORT_PLAN §3.1):
/// every result must be the same whatever zone the phone is in.
final class ItalianWallClockTests: XCTestCase {
    private let deviceZones = ["UTC", "Europe/Rome", "Europe/London", "America/New_York", "Asia/Tokyo", "Pacific/Auckland"]

    func testItalianDatesAndTimesGiveTheSameInstantInEveryDeviceZone() throws {
        try inEveryDeviceZone { zone in
            XCTAssertEqual(try self.instant("2026-07-12", "09:00"), try self.utc("2026-07-12T07:00:00Z"), zone)
            XCTAssertEqual(try self.instant("2026-11-12", "08:00"), try self.utc("2026-11-12T07:00:00Z"), zone)
            XCTAssertEqual(try self.instant("2026-12-31", "23:59"), try self.utc("2026-12-31T22:59:00Z"), zone)
            XCTAssertEqual(try self.instant("2027-01-01", "00:00"), try self.utc("2026-12-31T23:00:00Z"), zone)
        }
    }

    func testImpossibleDatesAndTimesAreRefused() {
        for date in ["2026-02-30", "2026-14-02", "2026-2-03", "26-02-03", "2026/02/03", "２０２６-02-03", "0000-01-01", ""] {
            XCTAssertNil(PlannedLocalDate(iso: date), date)
        }
        for time in ["24:00", "25:15", "9:00", "09:60", "09.00", "09:00:00", " 9:00", ""] {
            XCTAssertNil(PlannedLocalTime(hhmm: time), time)
        }
        XCTAssertEqual(PlannedLocalDate(iso: "2028-02-29")?.iso, "2028-02-29")
        XCTAssertNil(PlannedLocalDate(iso: "2027-02-29"))
        XCTAssertEqual(PlannedLocalTime(hhmm: "00:00")?.hhmm, "00:00")
        XCTAssertEqual(PlannedLocalTime(hhmm: "23:59")?.hhmm, "23:59")
    }

    func testTheSpringGapHasNoInstantAndTheAutumnOverlapIsItsFirstOccurrence() throws {
        try inEveryDeviceZone { zone in
            // 28 March 2027: 02:00 to 02:59 do not exist in Italy.
            XCTAssertNil(ItalianWallClock.instant(date: "2027-03-28", time: "02:30", zoneIdentifier: "Europe/Rome"), zone)
            XCTAssertNil(ItalianWallClock.instant(date: "2027-03-28", time: "02:00", zoneIdentifier: "Europe/Rome"), zone)
            XCTAssertEqual(try self.instant("2027-03-28", "01:59"), try self.utc("2027-03-28T00:59:00Z"), zone)
            XCTAssertEqual(try self.instant("2027-03-28", "03:00"), try self.utc("2027-03-28T01:00:00Z"), zone)
            // 25 October 2026: 02:00 to 02:59 happen twice; the first, in summer time, counts.
            XCTAssertEqual(try self.instant("2026-10-25", "02:30"), try self.utc("2026-10-25T00:30:00Z"), zone)
            XCTAssertEqual(try self.instant("2026-10-25", "03:00"), try self.utc("2026-10-25T02:00:00Z"), zone)
        }
        // A departure in the gap is not a departure.
        XCTAssertThrowsError(try PlannedJourneyDateTime(
            date: XCTUnwrap(PlannedLocalDate(iso: "2027-03-28")),
            time: XCTUnwrap(PlannedLocalTime(hhmm: "02:30"))
        )) { error in
            XCTAssertEqual(error as? PlannedJourneyValidationError, .nonexistentLocalTime)
        }
    }

    func testOtherZonesUseTheirOwnWallClock() throws {
        try inEveryDeviceZone { zone in
            XCTAssertEqual(
                ItalianWallClock.instant(date: "2026-07-12", time: "09:00", zoneIdentifier: "Europe/London"),
                try self.utc("2026-07-12T08:00:00Z"), zone
            )
            XCTAssertEqual(
                ItalianWallClock.instant(date: "2026-01-12", time: "09:00", zoneIdentifier: "America/New_York"),
                try self.utc("2026-01-12T14:00:00Z"), zone
            )
            XCTAssertNil(ItalianWallClock.instant(date: "2026-07-12", time: "09:00", zoneIdentifier: "Not/A_Zone"), zone)
        }
    }

    func testTodayIsItalysDateAndTheClockIsItalysTime() throws {
        try inEveryDeviceZone { zone in
            // 23:30Z on 11 November is 00:30 on 12 November in Italy: «oggi» is the 12th, «domani» the 13th.
            let now = try self.utc("2026-11-11T23:30:00Z")
            let today = try XCTUnwrap(ItalianWallClock.date(of: now))
            XCTAssertEqual(today.iso, "2026-11-12", zone)
            XCTAssertEqual(today.adding(days: 1)?.iso, "2026-11-13", zone)
            XCTAssertEqual(ItalianWallClock.time(of: now)?.hhmm, "00:30", zone)
            // In summer the same instant is two hours ahead.
            XCTAssertEqual(ItalianWallClock.time(of: try self.utc("2026-07-12T07:00:59Z"))?.hhmm, "09:00", zone)
        }
    }

    func testCalendarArithmeticRoundTripsAndKnowsTheWeekday() throws {
        for days in stride(from: -719_162, through: 2_932_896, by: 997) {
            let date = try XCTUnwrap(PlannedLocalDate(daysSinceEpoch: days), "\(days)")
            XCTAssertEqual(date.daysSinceEpoch, days)
            XCTAssertEqual(PlannedLocalDate(iso: date.iso), date)
        }
        XCTAssertEqual(PlannedLocalDate(iso: "1970-01-01")?.daysSinceEpoch, 0)
        XCTAssertEqual(PlannedLocalDate(iso: "1969-12-31")?.daysSinceEpoch, -1)
        // 12 November 2026 is a Thursday; 15 November 2026 a Sunday.
        XCTAssertEqual(PlannedLocalDate(iso: "2026-11-12")?.isoWeekday, 4)
        XCTAssertEqual(PlannedLocalDate(iso: "2026-11-15")?.isoWeekday, 7)
        XCTAssertEqual(PlannedLocalDate(iso: "2026-11-16")?.isoWeekday, 1)
        XCTAssertEqual(PlannedLocalDate(iso: "2026-12-31")?.adding(days: 1)?.iso, "2027-01-01")
        XCTAssertNil(PlannedLocalDate(iso: "9999-12-31")?.adding(days: 1))
    }

    func testInstantsAreWrittenAsAndroidWritesThemAndReadWithAnyFraction() throws {
        let date = try utc("2026-11-12T10:15:30Z")
        XCTAssertEqual(PlannedJourneyInstant.format(date), "2026-11-12T10:15:30Z")
        XCTAssertEqual(PlannedJourneyInstant.format(date.addingTimeInterval(0.75)), "2026-11-12T10:15:30Z")
        XCTAssertEqual(PlannedJourneyInstant.parse("2026-11-12T10:15:30.123Z"), date)
        XCTAssertEqual(PlannedJourneyInstant.parse("2026-11-12T10:15:30.123456789Z"), date)
        for refused in ["2026-11-12T10:15:30", "2026-11-12 10:15:30Z", "2026-11-12T24:00:00Z", "2026-11-12T10:15:30.Z",
                        "2026-11-12T10:15:30.1234567890Z", "2026-11-12T10:15:30+01:00", "2026-02-30T10:15:30Z"] {
            XCTAssertNil(PlannedJourneyInstant.parse(refused), refused)
        }
    }

    // MARK: Helpers

    private func instant(_ date: String, _ time: String) throws -> Date {
        try XCTUnwrap(ItalianWallClock.instant(date: date, time: time, zoneIdentifier: ItalianWallClock.zoneIdentifier))
    }

    private func utc(_ text: String) throws -> Date {
        try XCTUnwrap(PlannedJourneyInstant.parse(text), text)
    }

    /// Runs `body` with the phone set to each zone, then restores the phone's zone.
    private func inEveryDeviceZone(_ body: (String) throws -> Void) throws {
        let original = NSTimeZone.default
        defer { NSTimeZone.default = original }
        for identifier in deviceZones {
            NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: identifier))
            try body(identifier)
        }
    }
}
