import Foundation
import XCTest
@testable import BinarioKit

/// «Preparazione del viaggio» (the companion's presets, EXPO_PORT_PLAN F15 and §3.5, on Android's
/// rule: lines added after the user's own, never repeated, within the 24-line limit).
final class PlannedJourneyChecklistPresetsTests: XCTestCase {
    private let italian: [PlannedJourneyChecklistLine: String] = [
        .timeMargin: "Verifica il margine di anticipo",
        .documents: "Prepara titolo di viaggio e documenti",
        .belongings: "Controlla gli effetti personali",
        .connection: "Verifica il tempo per la coincidenza",
        .bike: "Verifica condizioni e preparazione della bici",
        .assistance: "Conferma l’assistenza richiesta in stazione",
    ]

    func testEachPresetAddsTheCommonLinesAndItsOwn() {
        XCTAssertEqual(PlannedJourneyChecklistPreset.commuter.lines, [.timeMargin, .documents, .belongings])
        XCTAssertEqual(PlannedJourneyChecklistPreset.connection.lines, [.timeMargin, .documents, .belongings, .connection])
        XCTAssertEqual(PlannedJourneyChecklistPreset.bike.lines, [.timeMargin, .documents, .belongings, .bike])
        XCTAssertEqual(PlannedJourneyChecklistPreset.assistance.lines, [.timeMargin, .documents, .belongings, .assistance])
    }

    func testAPresetAddsAfterTheUsersLinesWithoutRepeatingOne() {
        let bike = PlannedJourneyChecklistPreset.bike.applying(to: "") { self.italian[$0] ?? "" }
        XCTAssertEqual(bike.split(separator: "\n").map(String.init), [
            "Verifica il margine di anticipo", "Prepara titolo di viaggio e documenti",
            "Controlla gli effetti personali", "Verifica condizioni e preparazione della bici",
        ])
        let mine = "Ombrello\n  verifica il margine di anticipo  \n\n"
        let connection = PlannedJourneyChecklistPreset.connection.applying(to: mine) { self.italian[$0] ?? "" }
        XCTAssertEqual(connection.split(separator: "\n").map(String.init), [
            "Ombrello", "verifica il margine di anticipo", "Prepara titolo di viaggio e documenti",
            "Controlla gli effetti personali", "Verifica il tempo per la coincidenza",
        ])
        // Applying the same preset again changes nothing.
        XCTAssertEqual(PlannedJourneyChecklistPreset.connection.applying(to: connection) { self.italian[$0] ?? "" }, connection)

        let full = (1...23).map { "Voce \($0)" }.joined(separator: "\n")
        let capped = PlannedJourneyChecklistPreset.assistance.applying(to: full) { self.italian[$0] ?? "" }
        XCTAssertEqual(capped.split(separator: "\n").count, PlannedJourneySchema.maximumChecklistItems)
        XCTAssertTrue(capped.hasSuffix("Verifica il margine di anticipo"))
    }

    func testEveryLanguagesPresetLinesCanBeSaved() throws {
        let keys: [PlannedJourneyChecklistLine: String] = [
            .timeMargin: "saved.agenda.check.time_margin", .documents: "saved.agenda.check.documents",
            .belongings: "saved.agenda.check.belongings", .connection: "saved.agenda.check.connection",
            .bike: "saved.agenda.check.bike", .assistance: "saved.agenda.check.assistance",
        ]
        let languages = ["it", "en", "de", "fr", "es", "ar", "hi", "id", "ja", "ko", "my", "pt-BR", "ru", "th", "tr", "vi", "zh-Hans"]
        for language in languages {
            let strings = try localizedStrings(language)
            for preset in PlannedJourneyChecklistPreset.allCases {
                var form = PlannedJourneyForm(
                    origin: StationIdentity(id: "S08409", name: "Roma Termini"),
                    destination: StationIdentity(id: "S01700", name: "Milano Centrale"),
                    date: PlannedLocalDate(iso: "2030-05-02"),
                    time: PlannedLocalTime(hhmm: "08:15")
                )
                form.checklistText = preset.applying(to: "") { line in strings[keys[line] ?? ""] ?? "" }
                let journey = try form.journey(id: "j", now: Date(timeIntervalSince1970: 1_762_931_000), editing: nil).get()
                XCTAssertEqual(journey.checklist.count, preset.lines.count, "\(language) \(preset)")
            }
        }
    }

    /// The entries of a Localizable.strings in the repository.
    private func localizedStrings(_ language: String) throws -> [String: String] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("ios/Resources/\(language).lproj/Localizable.strings")
        let expression = try NSRegularExpression(pattern: #"^"([^"]+)"\s*=\s*"(.*)";$"#)
        var entries: [String: String] = [:]
        for rawLine in try String(contentsOf: url, encoding: .utf8).split(separator: "\n") {
            let line = String(rawLine)
            let range = NSRange(line.startIndex..., in: line)
            guard let match = expression.firstMatch(in: line, range: range),
                  let key = Range(match.range(at: 1), in: line), let value = Range(match.range(at: 2), in: line)
            else { continue }
            entries[String(line[key])] = String(line[value]).replacingOccurrences(of: "\\\"", with: "\"")
        }
        return entries
    }
}
