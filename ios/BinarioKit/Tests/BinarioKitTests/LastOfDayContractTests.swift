import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/last-of-day-v1.json, read as Android's LastOfDayContractTest reads it: «Ultimo di oggi»
/// and «primo domani alle …» from the static timetable's day.
final class LastOfDayContractTests: XCTestCase {
    private var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }

    private func cases(_ key: String) throws -> [[String: Any]] {
        let data = try Data(contentsOf: repositoryRoot.appendingPathComponent("contracts/shared-rules/v1/last-of-day-v1.json"))
        let rules = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        return try XCTUnwrap(rules[key] as? [[String: Any]], key)
    }

    /// "2026-11-24T21:40" or "2026-11-24T21:40:30", railway-local time.
    private func date(_ value: Any?) throws -> Date? {
        guard let text = value as? String else { return nil }
        let parts = text.split(whereSeparator: { "-T:".contains($0) }).compactMap { Int($0) }
        XCTAssertTrue(parts.count == 5 || parts.count == 6, text)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = ItalianWallClock.timeZone
        let components = DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: parts[3], minute: parts[4],
                                        second: parts.count > 5 ? parts[5] : 0)
        return try XCTUnwrap(calendar.date(from: components), text)
    }

    private func departure(_ value: Any?) throws -> StaticDeparture? {
        guard let value = value as? [String: Any] else { return nil }
        return StaticDeparture(
            departure: try XCTUnwrap(try date(value["departure"])),
            publicNumber: value["publicNumber"] as? String,
            terminus: try XCTUnwrap(value["terminus"] as? String)
        )
    }

    private func day(_ item: [String: Any]) throws -> StaticDayDepartures? {
        guard let day = item["day"] as? [String: Any] else { return nil }
        return StaticDayDepartures(
            lastByTerminus: try (day["lastByTerminus"] as? [Any] ?? []).map { try XCTUnwrap(try departure($0)) },
            firstNextDay: try departure(day["firstNextDay"])
        )
    }

    func testIsLast() throws {
        let items = try cases("isLast")
        XCTAssertEqual(items.count, 9)
        for item in items {
            let found = LastOfDayMarks.isLast(try day(item), publicNumber: item["publicNumber"] as? String,
                                              scheduledDeparture: try date(item["scheduledDeparture"]))
            XCTAssertEqual(found, item["expected"] as? Bool, item["name"] as? String ?? "")
        }
    }

    func testFirstOfTheNextDay() throws {
        let items = try cases("firstNextDay")
        XCTAssertEqual(items.count, 6)
        for item in items {
            let now = try XCTUnwrap(try date(item["now"]))
            let found = LastOfDayMarks.firstNextDay(try day(item), now: now)
            XCTAssertEqual(found?.departure, try date(item["expected"]), item["name"] as? String ?? "")
        }
    }

    func testMarkedDay() throws {
        let items = try cases("markedDay")
        XCTAssertEqual(items.count, 4)
        for item in items {
            let today = try XCTUnwrap(PlannedLocalDate(iso: try XCTUnwrap(item["today"] as? String)))
            let search = try XCTUnwrap(PlannedLocalDate(iso: try XCTUnwrap(item["searchDate"] as? String)))
            let expected = (item["expected"] as? String).flatMap(PlannedLocalDate.init(iso:))
            let found = LastOfDayMarks.markedDay(today: today, followsCurrentTime: item["followsCurrentTime"] as? Bool ?? false, searchDate: search)
            XCTAssertEqual(found, expected, item["name"] as? String ?? "")
        }
    }
}
