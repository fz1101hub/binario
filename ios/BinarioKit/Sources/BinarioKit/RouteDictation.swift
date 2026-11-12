import Foundation

/// What a typed or dictated sentence asks for (Android's `DictatedPlannerInput`).
public struct DictatedInput: Equatable, Sendable {
    public var origin: Station?
    public var destination: Station?
    public var station: Station?
    public var trainNumber: String?
    public var date: PlannedLocalDate?
    public var time: PlannedLocalTime?
    public var returnJourney: Bool?

    public init(
        origin: Station? = nil,
        destination: Station? = nil,
        station: Station? = nil,
        trainNumber: String? = nil,
        date: PlannedLocalDate? = nil,
        time: PlannedLocalTime? = nil,
        returnJourney: Bool? = nil
    ) {
        self.origin = origin
        self.destination = destination
        self.station = station
        self.trainNumber = trainNumber
        self.date = date
        self.time = time
        self.returnJourney = returnJourney
    }

    /// The departure the day and time said give in Italian time, the part not said taken from
    /// `current`; nil when neither was said, or when Italy's clock skips that time.
    public func departure(replacing current: Date) -> Date? {
        guard date != nil || time != nil,
              let day = date ?? ItalianWallClock.date(of: current),
              let clock = time ?? ItalianWallClock.time(of: current)
        else { return nil }
        return ItalianWallClock.instant(date: day, time: clock)
    }
}

public enum DictationTarget: String, Sendable, CaseIterable {
    case route, train, station
}

public enum DictationReviewStatus: String, Sendable {
    case confirmed, needsReview, unresolved
}

public enum DictatedStationRole: String, Sendable {
    case origin, destination, station
}

public struct DictatedStationCandidates: Equatable, Sendable {
    public let role: DictatedStationRole
    public let candidates: [Station]
}

/// The station names the user must choose between for one role.
public struct DictationAlternatives: Equatable, Sendable {
    public let role: DictatedStationRole
    public let names: [String]
}

/// What to tell the user about a dictation, independent of the display language (Android's
/// `PlannerVoiceFeedback`).
public enum DictationFeedback: Equatable, Sendable {
    case confirmed
    /// Station names the user must choose between, by role; the fields were not changed.
    case ambiguous([DictationAlternatives])
    case incomplete
    /// A route whose stations were not found (a keyboard writes «ミラノ», «Милан», the catalogue «Milano»):
    /// the station fields were not changed, the day and the time said were put in theirs.
    case dateTimeOnly
    case unresolved(DictationTarget)
}

/// A fail-closed decision: an ambiguous sentence is never applied by itself (Android's `PlannerVoiceReview`).
public struct DictationReview: Equatable, Sendable {
    public let status: DictationReviewStatus
    public let input: DictatedInput
    public let stationCandidates: [DictatedStationCandidates]
    public let canApply: Bool

    public func feedback(_ target: DictationTarget) -> DictationFeedback {
        switch status {
        case .confirmed:
            return .confirmed
        case .needsReview:
            let alternatives = stationCandidates
                .filter { $0.candidates.count > 1 }
                .map { DictationAlternatives(role: $0.role, names: $0.candidates.prefix(3).map(\.name)) }
            if !alternatives.isEmpty { return .ambiguous(alternatives) }
            if target == .route, input.date != nil || input.time != nil { return .dateTimeOnly }
            return .incomplete
        case .unresolved:
            return .unresolved(target)
        }
    }
}

/// The catalogue's names, aliases and codes as dictation compares them, made once per catalogue:
/// station names are found only in the catalogue's Latin spelling, in every language.
public struct DictationStations: Sendable {
    let stations: [Station]
    /// For each station, its distinct compared terms (UTF-16) of at least three units.
    let terms: [[[UInt16]]]

    public init(_ stations: [Station]) {
        self.stations = stations
        terms = stations.map { station in
            var seen = Set<[UInt16]>()
            var result: [[UInt16]] = []
            for value in Self.searchableValues(station) {
                let term = Array(DictationText.normalize(value).utf16)
                guard term.count >= RouteDictation.minimumStationTermLength, seen.insert(term).inserted else { continue }
                result.append(term)
            }
            return result
        }
    }

    /// Android's `Station.searchableValues()`: the name, the aliases and the two codes (a catalogue
    /// station's city is its name there).
    static func searchableValues(_ station: Station) -> [String] {
        [station.name] + station.aliases + [station.railwayCode, station.trenitaliaLocationId].compactMap { $0 }
    }
}

/// Reads on the phone a sentence the user typed or said with the keyboard's dictation: the route, the
/// day and the time, in the 17 languages of the app. A port of Android's `PlannerVoiceParser` with the
/// same rules, checked by the same cases (contracts/voice-input/v1/dictation-cases.json): stations
/// only from the catalogue's Latin spelling, «oggi» is Italy's date, an hour followed by words the
/// grammar does not understand is no time, and nothing is guessed in a language without words.
public enum RouteDictation {
    static let minimumStationTermLength = 3

    /// Languages of the app written in another script: a keyboard writes the station names in that
    /// script too, and the catalogue has them in Latin letters.
    private static let otherScriptLanguages: Set<String> = ["ar", "hi", "ja", "ko", "my", "ru", "th", "zh"]
    private static let trainPattern = DictationPattern(
        "(?:^|\\s)(?:treno|numero|train|number|no|zug|nummer|nr|tren|frecciarossa|frecciargento|frecciabianca|" +
            "italo|intercity|eurocity|ic|ec|ice|tgv|railjet|rj)\\s+(\\d{1,6})(?=\\s|$)"
    )
    private static let bareTrainNumberPattern = DictationPattern("^(\\d{1,6})$")
    private static let clockTimePattern = DictationPattern("(?<![\\d/])(\\d{1,2})\\s*:\\s*(\\d{2})(?!\\d)")
    private static let numericDatePattern = DictationPattern(
        "(?<![\\d:])(\\d{1,2})\\s*/\\s*(\\d{1,2})(?:\\s*/\\s*(\\d{4}))?(?![\\d:])"
    )

    /// Whether a sentence for `target` is read in `language` (a language code, "it", "zh"). A train
    /// number is digits everywhere. A station is matched by its name in Latin letters, which a
    /// keyboard of another script does not write: station parsing is not offered there. A route also
    /// carries a day and a time: it is read in every language whose words for them are known.
    public static func offers(_ target: DictationTarget, language: String) -> Bool {
        switch target {
        case .train: return true
        case .station: return !otherScriptLanguages.contains(language.lowercased())
        case .route: return DictationGrammars.forLanguage(language) != nil
        }
    }

    /// The language code of an `.lproj` localization: "pt-BR" is "pt", "zh-Hans" is "zh".
    public static func languageCode(forLocalization identifier: String) -> String {
        let code = identifier.split(whereSeparator: { $0 == "-" || $0 == "_" }).first.map(String.init) ?? identifier
        return code.lowercased()
    }

    /// Today in Italy, whatever the zone of the phone: the timetable and the planner count days in Italy.
    public static func italianToday(_ now: Date) -> PlannedLocalDate? {
        ItalianWallClock.date(of: now)
    }

    /// The decision for `spoken` in `language`, the language of the app, which chooses the words for
    /// the day, the time and a return: where they are not known neither is read and a route is never
    /// confirmed.
    public static func review(
        _ spoken: String,
        stations: DictationStations,
        target: DictationTarget,
        today: PlannedLocalDate,
        language: String
    ) -> DictationReview {
        let grammar = DictationGrammars.forLanguage(language)
        var analysis = analyze(spoken, stations: stations, today: today, grammar: grammar)
        // The train field takes a number and nothing else: the only number said is the train, whatever
        // the language calls a train («trem 9512», «kereta 9512»).
        if target == .train, analysis.input.trainNumber == nil {
            analysis.input.trainNumber = onlyNumber(spoken)
        }
        let groups: [DictatedStationCandidates]
        switch target {
        case .route:
            groups = analysis.mentions.prefix(2).enumerated().map { index, mention in
                DictatedStationCandidates(role: index == 0 ? .origin : .destination, candidates: mention.candidates)
            }
        case .station:
            groups = analysis.mentions.prefix(1).map { DictatedStationCandidates(role: .station, candidates: $0.candidates) }
        case .train:
            groups = []
        }
        let input = analysis.input
        let hasRelevantValue: Bool
        let isComplete: Bool
        switch target {
        case .route:
            hasRelevantValue = input.origin != nil || input.destination != nil || input.date != nil
                || input.time != nil || input.returnJourney != nil || !groups.isEmpty
            isComplete = input.origin != nil && input.destination != nil
        case .train:
            hasRelevantValue = input.trainNumber != nil
            isComplete = input.trainNumber != nil
        case .station:
            hasRelevantValue = input.station != nil || !groups.isEmpty
            isComplete = input.station != nil
        }
        let hasAmbiguousStation = groups.contains { $0.candidates.count != 1 }
        let status: DictationReviewStatus
        if !hasRelevantValue {
            status = .unresolved
        } else if hasAmbiguousStation || !isComplete {
            status = .needsReview
        } else if target == .route, grammar == nil {
            // Two stations and words this parser cannot read: the day and the hour said with them are unknown.
            status = .needsReview
        } else {
            status = .confirmed
        }
        return DictationReview(status: status, input: input, stationCandidates: groups, canApply: status == .confirmed)
    }

    /// `review` on a plain list of stations (tests and short lists).
    public static func review(
        _ spoken: String,
        stations: [Station],
        target: DictationTarget,
        today: PlannedLocalDate,
        language: String
    ) -> DictationReview {
        review(spoken, stations: DictationStations(stations), target: target, today: today, language: language)
    }

    // MARK: Reading

    private struct StationMention {
        let range: Range<Int>
        let candidates: [Station]
    }

    private struct Analysis {
        var input: DictatedInput
        let mentions: [StationMention]
    }

    private static func analyze(_ spoken: String, stations: DictationStations, today: PlannedLocalDate, grammar: DictationGrammar?) -> Analysis {
        // Station names are compared without accents in every language: the catalogue writes them in Latin letters.
        let stationText = DictationText.normalize(spoken)
        let mentions = inOrderOfTravel(findStationMentions(stationText, stations: stations, grammar: grammar), stationText, grammar)
        let uniqueStations: [Station?] = mentions.map { $0.candidates.count == 1 ? $0.candidates[0] : nil }
        // Without the words of the language a number is not a time and a day is not said: nothing is guessed.
        let words = grammar?.text(spoken) ?? ""
        let raw = DictationText.raw(spoken)
        let time = grammar.flatMap { parseTime(words, raw, $0) }
        let date = grammar.flatMap { parseDate(words, raw, today, $0) }
        let trainNumber = trainPattern.firstMatch(in: stationText).map { $0.value(1) }
            ?? bareTrainNumberPattern.firstMatch(in: stationText).map { $0.value(1) }
        let returnJourney: Bool?
        if let grammar, grammar.hasAny(words, grammar.returnTrip) {
            returnJourney = true
        } else if let grammar, grammar.hasAny(words, grammar.oneWay) {
            returnJourney = false
        } else {
            returnJourney = nil
        }
        let input = DictatedInput(
            origin: uniqueStations.count > 0 ? uniqueStations[0] : nil,
            destination: uniqueStations.count > 1 ? uniqueStations[1] : nil,
            station: mentions.count == 1 ? uniqueStations[0] : nil,
            trainNumber: trainNumber,
            date: date,
            time: time,
            returnJourney: returnJourney
        )
        return Analysis(input: input, mentions: mentions)
    }

    private static func findStationMentions(_ normalized: String, stations: DictationStations, grammar: DictationGrammar?) -> [StationMention] {
        let text = Array(normalized.utf16)
        guard text.contains(where: { !DictationText.isSpace($0) }) else { return [] }
        // Turkish writes the case onto the name («Romadan», «Roma'ya»): the name before the ending is the station.
        let endings = grammar?.stationEndings ?? []
        var raw: [(range: Range<Int>, station: Int)] = []
        for (index, terms) in stations.terms.enumerated() {
            for term in terms {
                for range in DictationText.wordMatches(of: term, in: text, endings: endings) {
                    raw.append((range: range, station: index))
                }
            }
        }
        let maximal = raw.filter { candidate in
            !raw.contains { other in
                other.range != candidate.range
                    && other.range.lowerBound <= candidate.range.lowerBound
                    && other.range.upperBound >= candidate.range.upperBound
                    && other.range.count > candidate.range.count
            }
        }
        var order: [Range<Int>] = []
        var byRange: [Range<Int>: [Int]] = [:]
        for match in maximal {
            if byRange[match.range] == nil { order.append(match.range) }
            byRange[match.range, default: []].append(match.station)
        }
        let mentions = order.map { range -> StationMention in
            var seen = Set<String>()
            var candidates: [(position: Int, station: Station)] = []
            for (position, index) in (byRange[range] ?? []).enumerated() {
                let station = stations.stations[index]
                if seen.insert(station.id).inserted { candidates.append((position: position, station: station)) }
            }
            // Android's `sortedBy { it.name }`: UTF-16 order, the earlier station first between equal names.
            let sorted = candidates.sorted { left, right in
                if left.station.name != right.station.name {
                    return left.station.name.utf16.lexicographicallyPrecedes(right.station.name.utf16)
                }
                return left.position < right.position
            }
            return StationMention(range: range, candidates: sorted.map { $0.station })
        }
        return mentions.sorted { $0.range.lowerBound < $1.range.lowerBound }
    }

    /// The first station said is the origin, unless the words around the two names say the opposite
    /// («to Milano from Roma», «Milano まで Roma から»). A name with no such word, or with contradicting
    /// ones, keeps its place.
    private static func inOrderOfTravel(_ mentions: [StationMention], _ stationText: String, _ grammar: DictationGrammar?) -> [StationMention] {
        guard let grammar, mentions.count == 2 else { return mentions }
        let first = grammar.role(stationText, range: mentions[0].range)
        let second = grammar.role(stationText, range: mentions[1].range)
        let reversed = (first == .to || second == .from) && first != .from && second != .to
        return reversed ? Array(mentions.reversed()) : mentions
    }

    private static func parseTime(_ words: String, _ raw: String, _ grammar: DictationGrammar) -> PlannedLocalTime? {
        let afternoon = grammar.saysAfternoon(words)
        for pattern in grammar.times {
            guard let match = pattern.firstMatch(in: words), let hour = match.group(1) else { continue }
            let minutes = match.value(2)
            // «alle 8 meno un quarto», «alle 8 e tre quarti»: an hour followed by words not understood is no time.
            if grammar.continues(DictationText.suffix(words, from: match.range.upperBound), minutesSaid: !minutes.isEmpty) {
                return nil
            }
            let shift = grammar.modifierBefore(words, hourStart: hour.lowerBound)
            if shift != nil, !minutes.isEmpty { return nil }
            guard let time = clockTime(match.value(1), minutes, match.value(3), afternoon, grammar.minuteWords) else { return nil }
            guard let shift else { return time }
            let minuteOfDay = ((time.minutesOfDay + shift) % 1_440 + 1_440) % 1_440
            return PlannedLocalTime(hour: minuteOfDay / 60, minute: minuteOfDay % 60)
        }
        return clockTimePattern.firstMatch(in: raw).flatMap { clockTime($0.value(1), $0.value(2), "", afternoon, [:]) }
    }

    private static func clockTime(
        _ hourText: String,
        _ minuteText: String,
        _ meridiem: String,
        _ afternoon: Bool,
        _ minuteWords: [String: Int]
    ) -> PlannedLocalTime? {
        guard var hour = Int(hourText) else { return nil }
        let minute: Int
        if minuteText.isEmpty {
            minute = 0
        } else if let word = minuteWords[minuteText] {
            minute = word
        } else if let number = Int(minuteText) {
            minute = number
        } else {
            return nil
        }
        switch meridiem.replacingOccurrences(of: " ", with: "") {
        case "pm":
            if (1...11).contains(hour) { hour += 12 } else if hour != 12 { return nil }
        case "am":
            if hour == 12 { hour = 0 } else if !(1...11).contains(hour) { return nil }
        default:
            // "alle 8 di sera" is 20:00; "alle 18 di sera" is already a 24-hour time.
            if afternoon, (1...11).contains(hour) { hour += 12 }
        }
        return PlannedLocalTime(hour: hour, minute: minute)
    }

    private static func parseDate(_ words: String, _ raw: String, _ today: PlannedLocalDate, _ grammar: DictationGrammar) -> PlannedLocalDate? {
        let relative = grammar.withoutNotDays(words)
        if grammar.hasAny(relative, grammar.dayAfterTomorrow) { return today.adding(days: 2) }
        if grammar.hasAny(relative, grammar.tomorrow) { return today.adding(days: 1) }
        if grammar.hasAny(words, grammar.today) { return today }
        for rule in grammar.dateRules {
            guard let match = rule.pattern.firstMatch(in: words) else { continue }
            let (first, second) = (match.value(1), match.value(2))
            let (dayText, monthText) = rule.monthFirst ? (second, first) : (first, second)
            if let date = calendarDate(dayText, grammar.month(monthText), match.value(3), today, grammar) { return date }
        }
        guard grammar.readsSlashDates, let match = numericDatePattern.firstMatch(in: raw) else { return nil }
        return calendarDate(match.value(1), Int(match.value(2)), match.value(3), today, grammar)
    }

    private static func calendarDate(
        _ dayText: String,
        _ month: Int?,
        _ yearText: String,
        _ today: PlannedLocalDate,
        _ grammar: DictationGrammar
    ) -> PlannedLocalDate? {
        guard let day = Int(dayText), let month else { return nil }
        let chosenYear = grammar.year(yearText) ?? today.year
        guard let candidate = PlannedLocalDate(year: chosenYear, month: month, day: day) else { return nil }
        guard yearText.isEmpty, candidate < today else { return candidate }
        // Java's `plusYears(1)`: the 29th of February becomes the 28th in a common year.
        return PlannedLocalDate(year: candidate.year + 1, month: candidate.month, day: candidate.day)
            ?? PlannedLocalDate(year: candidate.year + 1, month: candidate.month, day: candidate.day - 1)
    }

    /// The number of a sentence that has exactly one, of at most six digits.
    private static func onlyNumber(_ spoken: String) -> String? {
        var numbers: [String] = []
        var current = ""
        for unit in DictationText.asciiDigits(spoken).utf16 {
            if (0x30...0x39).contains(unit) {
                current.append(Character(Unicode.Scalar(UInt8(unit))))
            } else if !current.isEmpty {
                numbers.append(current)
                current = ""
            }
        }
        if !current.isEmpty { numbers.append(current) }
        guard numbers.count == 1, let number = numbers.first, number.count <= 6 else { return nil }
        return number
    }
}
