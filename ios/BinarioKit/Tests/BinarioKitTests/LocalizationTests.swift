import Foundation
import XCTest
@testable import BinarioKit

final class LocalizationTests: XCTestCase {
    func testEverySupportedLocalizationHasEveryRequiredKey() throws {
        for language in ["it", "en", "de", "fr", "es"] {
            let url = repositoryRoot
                .appendingPathComponent("ios/Resources/\(language).lproj/Localizable.strings")
            let contents = try String(contentsOf: url, encoding: .utf8)
            let entries = try contents.split(separator: "\n").reduce(into: [String: String]()) { result, rawLine in
                let line = rawLine.trimmingCharacters(in: .whitespaces)
                guard !line.isEmpty else { return }
                let pattern = #"^\"([^\"]+)\"\s*=\s*\"(.*)\";$"#
                let expression = try NSRegularExpression(pattern: pattern)
                let range = NSRange(line.startIndex..., in: line)
                guard let match = expression.firstMatch(in: line, range: range), match.numberOfRanges == 3,
                      let keyRange = Range(match.range(at: 1), in: line),
                      let valueRange = Range(match.range(at: 2), in: line)
                else { throw LocalizationFixtureError.malformedLine(line) }
                let key = String(line[keyRange])
                guard result[key] == nil else { throw LocalizationFixtureError.duplicateKey(key) }
                result[key] = String(line[valueRange])
            }

            XCTAssertEqual(
                Set(entries.keys),
                BinarioLocalizationKeys.required,
                "Localization \(language) is incomplete or contains an undeclared key"
            )
            XCTAssertTrue(entries.values.allSatisfy { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })
        }
    }

    private var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}

private enum LocalizationFixtureError: Error {
    case malformedLine(String)
    case duplicateKey(String)
}
