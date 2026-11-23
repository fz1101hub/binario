import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/checklist-presets-v1.json: the lines of each «Preparazione del
/// viaggio» preset and how a preset is added to the editor's text, read from the same file by the
/// Android tests.
final class ChecklistPresetsContractTests: XCTestCase {
    func testTheLinesAndThePresetsAreTheSharedOnes() throws {
        let rules = try contract()
        XCTAssertEqual(PlannedJourneyChecklistLine.allCases.map { String(describing: $0) }, rules.lines)
        XCTAssertEqual(PlannedJourneyChecklistPreset.allCases.count, rules.presets.count)
        for item in rules.presets {
            let preset = try XCTUnwrap(PlannedJourneyChecklistPreset.allCases.first { String(describing: $0) == item.preset }, item.preset)
            XCTAssertEqual(preset.lines.map { String(describing: $0) }, item.lines, item.preset)
        }
    }

    func testAPresetIsAddedToTheTextAsOnAndroid() throws {
        let rules = try contract()
        for (index, item) in rules.apply.enumerated() {
            let preset = try XCTUnwrap(PlannedJourneyChecklistPreset.allCases.first { String(describing: $0) == item.preset }, item.preset)
            let applied = preset.applying(to: item.text) { line in rules.labels[String(describing: line)] ?? "" }
            XCTAssertEqual(applied, item.expected, "case \(index)")
        }
    }

    // MARK: - The contract file

    private struct Rules: Decodable {
        let lines: [String]
        let presets: [PresetCase]
        let apply: [ApplyCase]
        let labels: [String: String]
    }

    private struct PresetCase: Decodable {
        let preset: String
        let lines: [String]
    }

    private struct ApplyCase: Decodable {
        let text: String
        let preset: String
        let expected: String
    }

    private func contract() throws -> Rules {
        let url = repositoryRoot.appendingPathComponent("contracts/shared-rules/v1/checklist-presets-v1.json")
        return try JSONDecoder().decode(Rules.self, from: Data(contentsOf: url))
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
