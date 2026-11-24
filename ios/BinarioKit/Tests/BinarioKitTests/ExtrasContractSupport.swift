import Foundation
import XCTest
@testable import BinarioKit

/// Reads the shared rule files of contracts/shared-rules/v1 as they are, from the checkout.
enum SharedRuleFile {
    static var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }

    static func load(_ name: String) throws -> [String: Any] {
        let url = repositoryRoot.appendingPathComponent("contracts/shared-rules/v1").appendingPathComponent(name)
        let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        return try XCTUnwrap(object as? [String: Any], name)
    }

    static func text(_ relativePath: String) throws -> String {
        try String(contentsOf: repositoryRoot.appendingPathComponent(relativePath), encoding: .utf8)
    }

    static func date(_ value: Any?) -> PlannedLocalDate? {
        (value as? String).flatMap { PlannedLocalDate(iso: $0) }
    }

    /// «2026-11-04T10:25» or «2026-11-04T10:25:30» on the railway's wall clock.
    static func moment(_ value: Any?) throws -> RailMoment {
        let text = try XCTUnwrap(value as? String)
        let parts = text.split(separator: "T").map(String.init)
        let date = try XCTUnwrap(PlannedLocalDate(iso: parts[0]), text)
        let clock = parts[1].split(separator: ":").compactMap { Int($0) }
        return RailMoment(date: date, secondOfDay: clock[0] * 3_600 + clock[1] * 60 + (clock.count > 2 ? clock[2] : 0))
    }

    static func wall(_ value: Any?) throws -> RailWallClock {
        let moment = try moment(value)
        return RailWallClock(date: moment.date, minuteOfDay: moment.secondOfDay / 60)
    }

    static func objects(_ value: Any?) -> [[String: Any]] {
        value as? [[String: Any]] ?? []
    }

    /// An `Int` of a JSON value, nil for JSON null or a missing key.
    static func int(_ value: Any?) -> Int? {
        value is NSNull ? nil : (value as? NSNumber)?.intValue
    }

    static func double(_ value: Any?) -> Double? {
        value is NSNull ? nil : (value as? NSNumber)?.doubleValue
    }

    static func string(_ value: Any?) -> String? {
        value as? String
    }

    static func days(_ value: Any?) -> [String: Int] {
        (value as? [String: Any] ?? [:]).compactMapValues { ($0 as? NSNumber)?.intValue }
    }
}
