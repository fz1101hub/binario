import Foundation
import XCTest
@testable import BinarioKit

/// Dictation by text (EXPO_PORT_PLAN I6 and §3.3): the cases shared with Android in
/// contracts/voice-input/v1/dictation-cases.json (Android's PlannerVoiceDictationFormsTest reads the
/// same file), the iPhone's own example sentences in the 17 languages, and the departure a dictated
/// day and time give.
final class RouteDictationTests: XCTestCase {
    private static let localizations = [
        "it", "en", "de", "fr", "es", "ar", "hi", "id", "ja", "ko", "my", "pt-BR", "ru", "th", "tr", "vi", "zh-Hans",
    ]

    func testTheContractCoversEveryLanguageOfTheAppWithTheCompanionSentences() throws {
        let contract = try loadContract()
        XCTAssertEqual(contract["schemaVersion"] as? Int, 1)
        XCTAssertEqual(contract["kind"] as? String, "binario-voice-input-cases")
        let cases = try XCTUnwrap(contract["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 250)
        let companion = Set(cases.compactMap { entry -> String? in
            guard let name = entry["name"] as? String, name.hasPrefix("companion ") else { return nil }
            return entry["language"] as? String
        })
        // "in" is Android's code for Indonesian, "id" the iPhone's: both are read.
        XCTAssertEqual(companion, ["it", "en", "de", "fr", "es", "ar", "hi", "in", "id", "ja", "ko", "my", "pt", "ru", "th", "tr", "vi", "zh"])
    }

    func testTextIsComparedInEveryScriptWithASCIIDigits() throws {
        let contract = try loadContract()
        for entry in try XCTUnwrap(contract["normalization"] as? [[String: Any]]) {
            let text = try XCTUnwrap(entry["text"] as? String)
            let actual = (entry["joinNativeWords"] as? Bool) == true
                ? DictationText.joinNativeWords(text)
                : DictationText.normalize(text, foldLatinMarks: (entry["foldLatinMarks"] as? Bool) ?? true)
            XCTAssertEqual(actual, entry["expected"] as? String, text)
        }
    }

    func testWhatIsReadInEachLanguage() throws {
        let contract = try loadContract()
        for offer in try XCTUnwrap(contract["offers"] as? [[String: Any]]) {
            let language = try XCTUnwrap(offer["language"] as? String)
            for target in DictationTarget.allCases {
                XCTAssertEqual(RouteDictation.offers(target, language: language), offer[target.rawValue] as? Bool, "\(language) \(target)")
            }
        }
    }

    func testEverySharedSentenceIsReadAsTheContractSays() throws {
        let contract = try loadContract()
        let stationsByID = try Dictionary(uniqueKeysWithValues: XCTUnwrap(contract["stations"] as? [[String: Any]]).map { entry in
            let id = try XCTUnwrap(entry["id"] as? String)
            let name = try XCTUnwrap(entry["name"] as? String)
            let aliases = try XCTUnwrap(entry["aliases"] as? [String])
            return (id, Station(id: id, name: name, aliases: aliases))
        })
        let sets = try XCTUnwrap(contract["stationSets"] as? [String: [String]])
        let cases = try XCTUnwrap(contract["cases"] as? [[String: Any]])
        var failures: [String] = []
        for entry in cases {
            let name = try XCTUnwrap(entry["name"] as? String)
            let setName = try XCTUnwrap(entry["stations"] as? String)
            let stations = try XCTUnwrap(sets[setName], name).map { try XCTUnwrap(stationsByID[$0], name) }
            let targetName = try XCTUnwrap(entry["target"] as? String)
            let target = try XCTUnwrap(DictationTarget(rawValue: targetName), name)
            let expect = try XCTUnwrap(entry["expect"] as? [String: Any])
            let today: PlannedLocalDate
            if let now = entry["now"] as? String {
                let instant = try XCTUnwrap(ISO8601DateFormatter().date(from: now), name)
                today = try XCTUnwrap(RouteDictation.italianToday(instant), name)
                if let italian = expect["italianToday"] as? String, italian != today.iso {
                    failures.append("\(name): italianToday expected \(italian), got \(today.iso)")
                }
            } else {
                let day = try XCTUnwrap(entry["today"] as? String, name)
                today = try XCTUnwrap(PlannedLocalDate(iso: day), name)
            }
            let spoken = try XCTUnwrap(entry["spoken"] as? String, name)
            let language = try XCTUnwrap(entry["language"] as? String, name)
            let review = RouteDictation.review(spoken, stations: stations, target: target, today: today, language: language)
            let input = review.input
            func check(_ key: String, _ actual: String?) {
                guard let wanted = expect[key] else { return }
                let expected = wanted is NSNull ? nil : wanted as? String
                if expected != actual { failures.append("\(name): \(key) expected \(expected ?? "nil"), got \(actual ?? "nil")") }
            }
            check("status", review.status.rawValue)
            check("origin", input.origin?.id)
            check("destination", input.destination?.id)
            check("station", input.station?.id)
            check("trainNumber", input.trainNumber)
            check("date", input.date?.iso)
            check("time", input.time?.hhmm)
            if let wanted = expect["returnJourney"] {
                let expected = wanted is NSNull ? nil : wanted as? Bool
                if expected != input.returnJourney { failures.append("\(name): returnJourney expected \(String(describing: expected))") }
            }
            let feedback = review.feedback(target)
            check("feedback", Self.name(of: feedback))
            if let groups = expect["candidates"] as? [[String]] {
                let actual = review.stationCandidates.map { $0.candidates.map(\.id) }
                if groups != actual { failures.append("\(name): candidates expected \(groups), got \(actual)") }
            }
            if let wanted = expect["alternatives"] as? [[String: Any]] {
                let expected = wanted.map { ($0["role"] as? String ?? "", $0["names"] as? [String] ?? []) }
                var actual: [(String, [String])] = []
                if case let .ambiguous(alternatives) = feedback {
                    actual = alternatives.map { ($0.role.rawValue, $0.names) }
                }
                if expected.map({ $0.0 }) != actual.map({ $0.0 }) || expected.map({ $0.1 }) != actual.map({ $0.1 }) {
                    failures.append("\(name): alternatives expected \(expected), got \(actual)")
                }
            }
        }
        XCTAssertEqual(failures, [])
    }

    func testEveryGrammarPatternCompiles() {
        XCTAssertEqual(DictationGrammars.all.count, 13)
        for (index, grammar) in DictationGrammars.all.enumerated() {
            XCTAssertTrue(grammar.patternsAreValid, "grammar \(index)")
        }
    }

    /// Station parsing is not offered in the eight languages written in another script; a route is
    /// read in all 17.
    func testEveryLocalizationOfTheAppHasItsGrammar() {
        let otherScripts: Set<String> = ["ar", "hi", "ja", "ko", "my", "ru", "th", "zh"]
        XCTAssertEqual(RouteDictation.languageCode(forLocalization: "pt-BR"), "pt")
        XCTAssertEqual(RouteDictation.languageCode(forLocalization: "zh-Hans"), "zh")
        XCTAssertEqual(RouteDictation.languageCode(forLocalization: "id"), "id")
        for localization in Self.localizations {
            let language = RouteDictation.languageCode(forLocalization: localization)
            XCTAssertTrue(RouteDictation.offers(.route, language: language), localization)
            XCTAssertEqual(RouteDictation.offers(.station, language: language), !otherScripts.contains(language), localization)
        }
        XCTAssertFalse(RouteDictation.offers(.route, language: "nl"))
    }

    /// The example under the field and the one in «Non ho riconosciuto una tratta. Prova: …» are read
    /// whole in their language (Android's PlannerVoiceLanguagesTest, on the iPhone's texts).
    func testTheExamplesOfEveryLanguageAreUnderstoodWhole() throws {
        let termini = Station(id: "roma-termini", name: "Roma Termini", aliases: ["Roma"])
        let torino = Station(id: "torino-pn", name: "Torino Porta Nuova", aliases: ["Torino", "Torino PN"])
        let today = try XCTUnwrap(PlannedLocalDate(year: 2026, month: 11, day: 11))
        let mark = try NSRegularExpression(pattern: "[:：]\\s*|- ")
        for localization in Self.localizations {
            let strings = try localizedStrings(localization)
            let language = RouteDictation.languageCode(forLocalization: localization)
            let hint = try XCTUnwrap(strings["planner.dictation.unresolved"], localization)
            let found = try XCTUnwrap(mark.firstMatch(in: hint, range: NSRange(hint.startIndex..., in: hint)), localization)
            let markRange = try XCTUnwrap(Range(found.range, in: hint), localization)
            let hinted = String(hint[markRange.upperBound...])
            let example = try XCTUnwrap(strings["planner.dictation.example"], localization)
            for spoken in [hinted, example] {
                let review = RouteDictation.review(spoken, stations: [termini, torino], target: .route, today: today, language: language)
                XCTAssertEqual(review.status, .confirmed, "\(localization): \(spoken)")
                XCTAssertEqual(review.input.origin, termini, "\(localization): \(spoken)")
                XCTAssertEqual(review.input.destination, torino, "\(localization): \(spoken)")
                XCTAssertEqual(review.input.date, today.adding(days: 1), "\(localization): \(spoken)")
                XCTAssertEqual(review.input.time?.hhmm, "18:00", "\(localization): \(spoken)")
            }
            XCTAssertTrue(try XCTUnwrap(strings["planner.dictation.footer"], localization).contains("%@"), localization)
        }
    }

    /// The same examples on the bundled catalogue: both stations are found, and only once.
    func testTheExamplesFindTheirStationsInTheBundledCatalogue() throws {
        let data = try Data(contentsOf: repositoryRoot.appendingPathComponent("app/src/main/assets/stations/curated_it_rail_stations.json"))
        let catalog = try CuratedStationCatalog(data: data)
        let stations = DictationStations(catalog.offeredStations)
        let today = try XCTUnwrap(PlannedLocalDate(year: 2026, month: 11, day: 11))
        for localization in Self.localizations {
            let example = try XCTUnwrap(localizedStrings(localization)["planner.dictation.example"], localization)
            let review = RouteDictation.review(
                example,
                stations: stations,
                target: .route,
                today: today,
                language: RouteDictation.languageCode(forLocalization: localization)
            )
            XCTAssertEqual(review.status, .confirmed, "\(localization): \(example)")
            XCTAssertEqual(review.input.origin?.name, "Roma Termini", localization)
            XCTAssertEqual(review.input.destination?.name, "Torino Porta Nuova", localization)
        }
    }

    func testTheDictatedDayAndTimeReplaceOnlyWhatWasSaid() throws {
        // 2026-11-12 08:15 in Italy.
        let current = try XCTUnwrap(ItalianWallClock.instant(date: "2026-11-12", time: "08:15", zoneIdentifier: "Europe/Rome"))
        let day = try XCTUnwrap(PlannedLocalDate(year: 2026, month: 11, day: 20))
        let time = try XCTUnwrap(PlannedLocalTime(hour: 18, minute: 30))
        XCTAssertNil(DictatedInput().departure(replacing: current))
        XCTAssertEqual(
            DictatedInput(date: day, time: time).departure(replacing: current),
            ItalianWallClock.instant(date: "2026-11-20", time: "18:30", zoneIdentifier: "Europe/Rome")
        )
        XCTAssertEqual(
            DictatedInput(time: time).departure(replacing: current),
            ItalianWallClock.instant(date: "2026-11-12", time: "18:30", zoneIdentifier: "Europe/Rome")
        )
        XCTAssertEqual(
            DictatedInput(date: day).departure(replacing: current),
            ItalianWallClock.instant(date: "2026-11-20", time: "08:15", zoneIdentifier: "Europe/Rome")
        )
        // 02:30 on the last Sunday of March does not exist in Italy: nothing is applied.
        let spring = try XCTUnwrap(PlannedLocalDate(year: 2027, month: 3, day: 28))
        XCTAssertNil(DictatedInput(date: spring, time: PlannedLocalTime(hour: 2, minute: 30)).departure(replacing: current))
    }

    func testFeedbackOffersTheDayAndTimeOnlyForARouteWithoutAmbiguity() throws {
        let today = try XCTUnwrap(PlannedLocalDate(year: 2026, month: 11, day: 11))
        let termini = Station(id: "roma-termini", name: "Roma Termini")
        // «ミラノ»: the keyboard wrote the name in Japanese; only the day and time are offered.
        let japanese = RouteDictation.review("明日 18 時 ローマ から ミラノ まで", stations: [termini], target: .route, today: today, language: "ja")
        XCTAssertEqual(japanese.feedback(.route), .dateTimeOnly)
        XCTAssertFalse(japanese.canApply)
        XCTAssertEqual(
            RouteDictation.review("ritardo del treno", stations: [termini], target: .route, today: today, language: "it").feedback(.route),
            .unresolved(.route)
        )
        XCTAssertEqual(
            RouteDictation.review("da Roma Termini", stations: [termini], target: .route, today: today, language: "it").feedback(.route),
            .incomplete
        )
    }

    // MARK: Fixtures

    private static func name(of feedback: DictationFeedback) -> String {
        switch feedback {
        case .confirmed: return "confirmed"
        case .ambiguous: return "ambiguous"
        case .incomplete: return "incomplete"
        case .dateTimeOnly: return "dateTimeOnly"
        case .unresolved: return "unresolved"
        }
    }

    private func loadContract() throws -> [String: Any] {
        let url = repositoryRoot.appendingPathComponent("contracts/voice-input/v1/dictation-cases.json")
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    private func localizedStrings(_ language: String) throws -> [String: String] {
        let url = repositoryRoot.appendingPathComponent("ios/Resources/\(language).lproj/Localizable.strings")
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

    private var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}
