import Foundation
import XCTest
@testable import BinarioKit

/// The guided text import (the companion's shared-text and import tests, EXPO_PORT_PLAN §3.4 and
/// F22): what a pasted text becomes, on catalogue stations, never more than the Agenda holds.
final class PlannedJourneyTextImportTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_762_931_000)
    private let resolver = PlannedJourneyStationResolver(stations: [
        Station(id: "S08409", name: "Roma Termini", railwayCode: "S08409"),
        Station(id: "S05043", name: "Bologna Centrale", railwayCode: "S05043"),
        Station(id: "S01700", name: "Milano Centrale", railwayCode: "S01700", aliases: ["Milano C.le"]),
        Station(id: "S09218", name: "Napoli Centrale", railwayCode: "S09218"),
        Station(id: "S06421", name: "Firenze Santa Maria Novella", railwayCode: "S06421"),
        // Two different stations with one name: a pasted «San Pietro» picks neither.
        Station(id: "X00001", name: "San Pietro", railwayCode: "X00001", latitude: 41.9, longitude: 12.4),
        Station(id: "X00002", name: "San Pietro", railwayCode: "X00002", latitude: 45.5, longitude: 9.1),
    ])

    func testEveryLanguagesExampleGivesExactlyTwoJourneys() throws {
        let languages = ["it", "en", "de", "fr", "es", "ar", "hi", "id", "ja", "ko", "my", "pt-BR", "ru", "th", "tr", "vi", "zh-Hans"]
        for language in languages {
            let example = try XCTUnwrap(localizedStrings(language)["saved.agenda.text_import.example"], language)
            let preview = try XCTUnwrap(PlannedJourneyTextImport.preview(example, resolver: resolver, existing: [], now: now), language)
            XCTAssertEqual(preview.journeys.count, 2, language)
            XCTAssertEqual(preview.skippedLines, 0, language)
            XCTAssertEqual(preview.journeys.map(\.origin.canonicalId), ["S08409", "S05043"], language)
            XCTAssertEqual(preview.journeys.map(\.destination.canonicalId), ["S05043", "S01700"], language)
            XCTAssertEqual(preview.journeys.map(\.departure.date.iso), ["2026-09-12", "2026-09-12"], language)
            XCTAssertEqual(preview.journeys.map(\.departure.time.hhmm), ["08:30", "11:05"], language)
            XCTAssertEqual(preview.journeys.map(\.departure.timeZone), ["Europe/Rome", "Europe/Rome"], language)
        }
    }

    func testTheTextIsNormalisedAndBounded() {
        XCTAssertEqual(
            PlannedJourneyTextImport.normalize("  Roma → Napoli\r\n12/09/2026 alle 08:30 "),
            "Roma → Napoli\n12/09/2026 alle 08:30"
        )
        XCTAssertNotNil(PlannedJourneyTextImport.normalize(String(repeating: "a", count: 4_000)))
        XCTAssertNil(PlannedJourneyTextImport.normalize(String(repeating: "a", count: 4_001)))
        XCTAssertNil(PlannedJourneyTextImport.normalize(" \n\t \r\n "))
        XCTAssertNil(PlannedJourneyTextImport.normalize(""))
        XCTAssertEqual(PlannedJourneyTextImport.normalize("Ro\u{0000}ma"), "Roma")
        XCTAssertNil(PlannedJourneyTextImport.preview(String(repeating: "x", count: 4_001), resolver: resolver, existing: [], now: now))
        XCTAssertEqual(PlannedJourneyTextImport.lines("a;b\r\n\nc  d "), ["a", "b", "c d"])
    }

    func testEachLineNeedsAnOriginADestinationADateWithItsYearAndATimeAfterIt() {
        func draft(_ line: String) -> PlannedJourneyTextDraft? { PlannedJourneyTextImport.draft(line) }
        XCTAssertEqual(draft("Da Roma Termini a Milano Centrale 12/09/2026 08:30")?.origin, "Roma Termini")
        XCTAssertEqual(draft("Da Roma Termini a Milano Centrale 12/09/2026 08:30")?.destination, "Milano Centrale")
        let short = draft("Roma -> Milano · 2026-09-12 ore 8.30")
        XCTAssertEqual(short?.origin, "Roma")
        XCTAssertEqual(short?.destination, "Milano")
        XCTAssertEqual(short?.date.iso, "2026-09-12")
        XCTAssertEqual(short?.time.hhmm, "08:30")
        XCTAssertEqual(draft("Roma Termini → Milano Centrale · 12.09.2026 08.30")?.time.hhmm, "08:30")
        XCTAssertNil(draft("Roma → Milano · 12/09/2026"), "no time")
        XCTAssertNil(draft("Roma → Milano alle 08:30"), "no date")
        XCTAssertNil(draft("Roma → Milano 08:30 12/09/2026"), "the time before the date")
        XCTAssertNil(draft("Roma → Milano · 31/02/2026 alle 08:30"))
        XCTAssertNil(draft("Roma → Milano · 12/09 alle 08:30"), "the year is required")
        XCTAssertNil(draft("Roma → Milano · 12/09/2026 alle 24:00"))
        XCTAssertNil(draft("Roma → Roma · 12/09/2026 alle 08:30"))
        XCTAssertNil(draft("Città → citta · 12/09/2026 alle 08:30"), "the same station, accents and case aside")
        XCTAssertNil(draft("Roma Milano 12/09/2026 08:30"), "no arrow and no «Da … a …»")
        // Digits of other scripts are not read as times or dates (as the companion's ASCII \\d).
        XCTAssertNil(draft("Roma → Milano · ١٢/٠٩/٢٠٢٦ ٠٨:٣٠"))
    }

    func testStationsAreTheCataloguesByUniqueNameOrAliasNeverAGuess() throws {
        let text = """
        roma termini → MILANO C.LE · 12/09/2026 alle 08:30
        Roma Termini → Torino Porta Nuova · 12/09/2026 alle 09:30
        San Pietro → Roma Termini · 12/09/2026 alle 10:30
        Roma Termini → Roma Termini · 12/09/2026 alle 11:30
        """
        let preview = try XCTUnwrap(PlannedJourneyTextImport.preview(text, resolver: resolver, existing: [], now: now))
        XCTAssertEqual(preview.journeys.count, 1)
        XCTAssertEqual(preview.journeys.first?.origin.displayName, "Roma Termini", "the catalogue's name is stored")
        XCTAssertEqual(preview.journeys.first?.destination.canonicalId, "S01700")
        XCTAssertEqual(preview.skippedLines, 3, "unknown, ambiguous and same station")
        XCTAssertNil(resolver.station(named: "San Pietro"))
        XCTAssertEqual(resolver.station(named: "  firenze   santa maria novella ")?.id, "S06421")
    }

    func testRepeatedLinesAndJourneysAlreadyInTheAgendaAreLeftOutAndCounted() throws {
        let existing = try XCTUnwrap(PlannedJourneyTextImport.preview(
            "Roma Termini → Milano Centrale · 12/09/2026 alle 08:30", resolver: resolver, existing: [], now: now
        )).journeys
        let text = """
        Roma Termini → Milano Centrale · 12/09/2026 alle 08:30
        Napoli Centrale → Roma Termini · 13/09/2026 alle 07:00
        napoli centrale → roma termini · 13/09/2026 alle 7.00
        Riunione alle 10:00
        """
        let preview = try XCTUnwrap(PlannedJourneyTextImport.preview(text, resolver: resolver, existing: existing, now: now))
        XCTAssertEqual(preview.journeys.map(\.origin.canonicalId), ["S09218"])
        XCTAssertEqual(preview.skippedLines, 3)
    }

    func testAJourneyAtATimeTheClockChangeSkipsIsLeftOut() throws {
        let preview = try XCTUnwrap(PlannedJourneyTextImport.preview(
            "Roma Termini → Milano Centrale · 28/03/2027 alle 02:30", resolver: resolver, existing: [], now: now
        ))
        XCTAssertTrue(preview.journeys.isEmpty)
        XCTAssertEqual(preview.skippedLines, 1)
    }

    func testOnlyTheFreePlacesAreFilledAndTheRestIsCounted() throws {
        var existing: [PlannedJourney] = []
        for index in 0..<39 {
            let line = "Roma Termini → Milano Centrale · 01/10/2026 alle " + String(format: "%02d:%02d", 6 + index / 60, index % 60)
            existing += try XCTUnwrap(PlannedJourneyTextImport.preview(line, resolver: resolver, existing: existing, now: now)).journeys
        }
        XCTAssertEqual(existing.count, 39)
        let text = """
        Napoli Centrale → Roma Termini · 13/09/2026 alle 07:00
        Napoli Centrale → Roma Termini · 14/09/2026 alle 07:00
        Napoli Centrale → Roma Termini · 15/09/2026 alle 07:00
        """
        let preview = try XCTUnwrap(PlannedJourneyTextImport.preview(text, resolver: resolver, existing: existing, now: now))
        XCTAssertEqual(preview.journeys.map(\.departure.date.iso), ["2026-09-13"])
        XCTAssertEqual(preview.skippedLines, 2)
    }

    func testTheStoreOnlyAddsNeverReplacesAndStopsAtTheLimit() throws {
        let suite = "it.binario.ios.tests.text-import.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = PlannedJourneyStore(defaults: defaults)
        var counter = 0
        let makeID = { () -> String in
            counter += 1
            return "t-\(counter)"
        }
        let first = try XCTUnwrap(PlannedJourneyTextImport.preview(
            "Roma Termini → Milano Centrale · 12/09/2026 alle 08:30", resolver: resolver, existing: [], now: now, makeID: makeID
        )).journeys
        XCTAssertEqual(try store.add(contentsOf: first).get().journeys.map(\.id), ["t-1"])
        XCTAssertEqual(store.add(contentsOf: first), .failure(.duplicate), "nothing new, nothing written")

        let again = try XCTUnwrap(PlannedJourneyTextImport.preview(
            "Roma Termini → Milano Centrale · 12/09/2026 alle 08:30\nNapoli Centrale → Roma Termini · 13/09/2026 alle 07:00",
            resolver: resolver, existing: [], now: now, makeID: makeID
        )).journeys
        let state = try store.add(contentsOf: again).get()
        XCTAssertEqual(state.journeys.map(\.id), ["t-1", "t-3"], "the stored journey is kept, the new one added after it")

        defaults.set(Data("not json".utf8), forKey: PlannedJourneyStore.storageKey)
        XCTAssertEqual(store.add(contentsOf: again), .failure(.readOnly))
    }

    /// The entries of a Localizable.strings in the repository, with "\n" read as a line break.
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
            entries[String(line[key])] = String(line[value])
                .replacingOccurrences(of: "\\n", with: "\n")
                .replacingOccurrences(of: "\\\"", with: "\"")
        }
        return entries
    }
}
