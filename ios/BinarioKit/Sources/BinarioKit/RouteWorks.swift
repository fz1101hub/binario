import Foundation

/// A saved route as the works matcher reads it: its name and the stations its trains call at, both
/// ends included.
public struct WorksRoute: Equatable, Sendable {
    public let id: String
    public let name: String
    public let stations: [Station]

    public init(id: String, name: String, stations: [Station]) {
        self.id = id
        self.name = name
        self.stations = stations
    }
}

/// The dates a notice's text names: the earliest and the latest (the same day when it names one).
public struct NoticeDates: Equatable, Sendable {
    public let first: PlannedLocalDate
    public let last: PlannedLocalDate
}

/// Why a notice concerns a route, in the notice's own words: the stations of the route its text
/// names, and the line «A – B» it names when both ends are stations (or places) of the route.
public struct RouteWorksReason: Equatable, Sendable {
    public let routeID: String
    public let routeName: String
    public let stations: [String]
    public let line: String?
}

/// A notice of works or interruptions that names one or more of the user's routes.
public struct RouteWorksNotice: Equatable, Identifiable, Sendable {
    public let notice: RailNotice
    public let reasons: [RouteWorksReason]
    public let dates: NoticeDates?

    public var id: String { notice.id }
}

/// Android's `RouteWorksMatcher`: the network notices about works or interruptions (not strikes,
/// which have their own section) whose text names a station of a route, or a line whose two ends the
/// route serves. A station counts only as a whole name in the text («Pavia», not «Pavia» inside
/// «Pavia Porta Garibaldi» when that is a station of its own), written with a capital, and never a
/// one-word name that is also a common word («Piano»); city names alone («linea Milano – Venezia»)
/// count only as the two ends of a line. Nothing is inferred beyond the text.
public struct RouteWorksMatcher: Sendable {
    let rules: NoticeRules
    /// Folded catalogue names (and their Italian aliases), for the «longer station at the same place» check.
    private let catalogueNames: [String]

    public init(catalogue: [Station], rules: NoticeRules) {
        self.rules = rules
        var seen: Set<String> = []
        var names: [String] = []
        for station in catalogue {
            for name in Self.names(of: station, rules: rules) where seen.insert(name).inserted { names.append(name) }
        }
        catalogueNames = names
    }

    public func match(_ notices: [RailNotice], routes: [WorksRoute], today: PlannedLocalDate) -> [RouteWorksNotice] {
        // The longer catalogue names of each station name of the routes, folded once per match.
        var longer: [String: (before: [String], after: [String])] = [:]
        for route in routes {
            for station in route.stations {
                for name in Self.names(of: station, rules: rules) where longer[name] == nil {
                    longer[name] = (catalogueNames.filter { $0.hasPrefix(name + " ") }, catalogueNames.filter { $0.hasSuffix(" " + name) })
                }
            }
        }
        let found: [RouteWorksNotice] = notices.compactMap { notice -> RouteWorksNotice? in
            let text = ([notice.title] + (notice.body.map { [$0] } ?? [])).joined(separator: "\n")
            guard Self.isWorks(text, rules: rules) else { return nil }
            let folded = FoldedText(text)
            let value = text as NSString
            let lines = rules.line.matches(in: text).map { match in
                (match.group(1, in: value).trimmingCharacters(in: .whitespacesAndNewlines),
                 match.group(2, in: value).trimmingCharacters(in: .whitespacesAndNewlines))
            }
            let reasons = routes.compactMap { reason($0, text: folded, lines: lines, longer: longer) }
            guard !reasons.isEmpty else { return nil }
            let reference = notice.publishedAt.flatMap { ItalianWallClock.date(of: $0) } ?? today
            return RouteWorksNotice(notice: notice, reasons: reasons, dates: rules.noticeDates(text, reference: reference))
        }
        return found.enumerated().sorted { first, second in
            let a = first.element, b = second.element
            let strengthA = a.reasons.map(Self.strength).max() ?? 0, strengthB = b.reasons.map(Self.strength).max() ?? 0
            if strengthA != strengthB { return strengthA > strengthB }
            let pastA = a.dates.map { $0.last < today } ?? false, pastB = b.dates.map { $0.last < today } ?? false
            if pastA != pastB { return !pastA }
            let startA = a.dates?.first.daysSinceEpoch ?? Int.max, startB = b.dates?.first.daysSinceEpoch ?? Int.max
            if startA != startB { return startA < startB }
            let publishedA = a.notice.publishedAt ?? Date(timeIntervalSince1970: 0)
            let publishedB = b.notice.publishedAt ?? Date(timeIntervalSince1970: 0)
            if publishedA != publishedB { return publishedA > publishedB }
            return first.offset < second.offset
        }.map(\.element)
    }

    private static func strength(_ reason: RouteWorksReason) -> Int {
        (reason.line != nil ? 2 : 0) + min(reason.stations.count, 2)
    }

    private func reason(
        _ route: WorksRoute, text: FoldedText, lines: [(String, String)], longer: [String: (before: [String], after: [String])]
    ) -> RouteWorksReason? {
        var named: [String] = []
        for station in route.stations where !named.contains(station.name) {
            let names = Self.names(of: station, rules: rules)
            if names.contains(where: { text.names($0, longer: longer[$0] ?? ([], [])) }) { named.append(station.name) }
        }
        let line = lines.first { from, to in
            let first = route.stations.firstIndex { endMatches($0, from) }
            let second = route.stations.firstIndex { endMatches($0, to) }
            guard let first, let second else { return false }
            return first != second
        }.map { "\($0.0) – \($0.1)" }
        if named.isEmpty, line == nil { return nil }
        return RouteWorksReason(routeID: route.id, routeName: route.name, stations: named, line: line)
    }

    /// A line end («Milano», «Napoli Centrale», «Firenze AV») is a station named so, or the place a
    /// station's name starts with.
    private func endMatches(_ station: Station, _ end: String) -> Bool {
        let words = Self.fold(end).split(separator: " ").map(String.init)
        let names = Self.names(of: station, rules: rules)
        for count in stride(from: words.count, through: 1, by: -1) {
            let place = words.prefix(count).joined(separator: " ")
            if place.unicodeScalars.count >= NoticeTable.routeWorks.minimumNameLength,
               names.contains(where: { $0 == place || $0.hasPrefix(place + " ") }) { return true }
        }
        return false
    }

    /// The folded name of `station` and of its aliases written as Italian station names (the same
    /// first word: «BOLZANO» of «Bolzano Bozen»).
    static func names(of station: Station, rules: NoticeRules) -> [String] {
        let name = fold(station.name)
        let first = name.split(separator: " ", omittingEmptySubsequences: false).first.map(String.init) ?? name
        let common = Set(NoticeTable.routeWorks.commonWords)
        var result: [String] = []
        for candidate in [name] + station.aliases.map(fold) {
            let firstWord = candidate.split(separator: " ", omittingEmptySubsequences: false).first.map(String.init) ?? candidate
            guard candidate == name || firstWord == first else { continue }
            guard candidate.unicodeScalars.count >= NoticeTable.routeWorks.minimumNameLength,
                  candidate.contains(" ") || !common.contains(candidate), !result.contains(candidate) else { continue }
            result.append(candidate)
        }
        return result
    }

    /// Works and interruptions, by the words RFI uses (the table's pattern); never a strike notice.
    static func isWorks(_ text: String, rules: NoticeRules) -> Bool {
        !NoticeText.mentionsStrike([text], rules: rules) && rules.works.contains(fold(text))
    }

    /// Lower case without accents, every character but letters and digits a single space, trimmed.
    static func fold(_ value: String) -> String {
        var scalars: [Unicode.Scalar] = []
        for scalar in value.unicodeScalars {
            for folded in FoldedText.fold(scalar) {
                if folded == " ", scalars.isEmpty || scalars.last == " " { continue }
                scalars.append(folded)
            }
        }
        while scalars.last == " " { scalars.removeLast() }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: scalars)
        return String(view)
    }
}

/// The text of one notice folded once: lower case, no accents, every other character a single space,
/// with the place in the original text of each folded character.
struct FoldedText {
    private let folded: [Unicode.Scalar]
    /// Index in `original` of each folded scalar.
    private let origin: [Int]
    private let original: [Unicode.Scalar]

    init(_ text: String) {
        original = Array(text.unicodeScalars)
        var out: [Unicode.Scalar] = [" "]
        var map: [Int] = [0]
        for (index, scalar) in original.enumerated() {
            for base in Self.fold(scalar) {
                if base == " ", out.last == " " { continue }
                out.append(base)
                map.append(index)
            }
        }
        if out.last != " " {
            out.append(" ")
            map.append(max(0, original.count - 1))
        }
        folded = out
        origin = map
    }

    /// The base letter of `scalar` (its canonical decomposition without marks) in lower case, a digit,
    /// or a space for anything else.
    static func fold(_ scalar: Unicode.Scalar) -> [Unicode.Scalar] {
        guard let base = String(scalar).decomposedStringWithCanonicalMapping.unicodeScalars.first else { return [" "] }
        switch base.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber:
            return Array(String(base).lowercased().unicodeScalars)
        default:
            return [" "]
        }
    }

    /// True when `name` stands in the text as a whole name with a capital, and not as part of a
    /// longer station name (`longer`: the names that go on after it, and those that end with it).
    func names(_ name: String, longer: (before: [String], after: [String])) -> Bool {
        let needle = Array(" \(name) ".unicodeScalars)
        let length = name.unicodeScalars.count
        var from = 0
        while let at = index(of: needle, from: from) {
            from = at + 1
            let start = at + 1
            let end = start + length
            guard original.indices.contains(origin[start]), original[origin[start]].properties.isUppercase else { continue }
            if longer.before.contains(where: { starts(with: Array(" \($0) ".unicodeScalars), at: at) }) { continue }
            if longer.after.contains(where: { candidate in
                let offset = end - candidate.unicodeScalars.count - 1
                return offset >= 0 && starts(with: Array(" \(candidate) ".unicodeScalars), at: offset)
            }) { continue }
            return true
        }
        return false
    }

    private func index(of needle: [Unicode.Scalar], from start: Int) -> Int? {
        guard !needle.isEmpty, start >= 0 else { return nil }
        var index = start
        while index + needle.count <= folded.count {
            if starts(with: needle, at: index) { return index }
            index += 1
        }
        return nil
    }

    private func starts(with needle: [Unicode.Scalar], at offset: Int) -> Bool {
        guard offset >= 0, offset + needle.count <= folded.count else { return false }
        for (position, scalar) in needle.enumerated() where folded[offset + position] != scalar { return false }
        return true
    }
}

/// The date patterns of a notice's Italian text, Android's `NoticeDateParser`, with the months of the table.
struct NoticeDatePatterns: Sendable {
    let months: [String]
    let numeric: NoticeRegex
    let textual: NoticeRegex
    let bareDay: NoticeRegex
    let listedDay: NoticeRegex

    init?(months: [String]) {
        let month = months.joined(separator: "|")
        let separator = #"(?:\s*,\s*|\s+e\s+|\s+ed\s+)"#
        guard let numeric = NoticeRegex(#"(?<![\d/.])(\d{1,2})[/.](\d{1,2})[/.](\d{4}|\d{2})(?![\d/.])"#),
              let textual = NoticeRegex(#"\b(\d{1,2})(?:°|º)?\s+("# + month + #")(?:\s+(\d{4}))?\b"#),
              let bareDay = NoticeRegex(
                #"\b(?:dal|dall|del|dell|al|tra il|fra il|tra|fra|il|lunedi|martedi|mercoledi|giovedi|venerdi|sabato|domenica)\s+(\d{1,2})(?!\d)(?:°|º)?(?!\s*(?:[:.]\d|°|º|"#
                    + month + #"|/))"#
              ),
              let listedDay = NoticeRegex(
                #"(?<![\d.:/])\b(\d{1,2})(?="# + separator + #"(?:\d{1,2}"# + separator + #")*\d{1,2}(?:°|º)?\s+(?:"# + month + #")\b)"#
              )
        else { return nil }
        self.months = months
        self.numeric = numeric
        self.textual = textual
        self.bareDay = bareDay
        self.listedDay = listedDay
    }
}

extension NoticeRules {
    /// The dates named in a notice's Italian text: «9 novembre 2026», «14/11/2026», «dal 9 al 13
    /// novembre», «dalle 23.30 di sabato 7 alle 5 di lunedì 9 novembre», «nei giorni 14, 15, 21 e 22
    /// novembre» (a bare day, or a day of a list, takes the month of the next full date). A date
    /// without a year takes the year that puts it nearest to `reference` (the publication day).
    func noticeDates(_ text: String, reference: PlannedLocalDate) -> NoticeDates? {
        let lower = NoticeText.withoutMarks(text).lowercased()
            .replacingOccurrences(of: "’", with: " ").replacingOccurrences(of: "'", with: " ")
        let value = lower as NSString
        let patterns = noticeDatePatterns
        var found: [PlannedLocalDate] = []
        func date(_ year: Int, _ month: Int, _ day: Int) -> PlannedLocalDate? {
            (1...12).contains(month) && (1...31).contains(day) ? PlannedLocalDate(year: year, month: month, day: day) : nil
        }
        for match in patterns.numeric.matches(in: lower) {
            guard let day = Int(match.group(1, in: value)), let month = Int(match.group(2, in: value)),
                  let year = Int(match.group(3, in: value)), let parsed = date(year < 100 ? 2000 + year : year, month, day) else { continue }
            found.append(parsed)
        }
        var textual: [(position: Int, date: PlannedLocalDate)] = []
        for match in patterns.textual.matches(in: lower) {
            guard let day = Int(match.group(1, in: value)), let index = patterns.months.firstIndex(of: match.group(2, in: value)) else { continue }
            let month = index + 1
            let explicit = Int(match.group(3, in: value))
            guard let parsed = explicit.map({ date($0, month, day) }) ?? nearest(day: day, month: month, reference: reference) else { continue }
            textual.append((match.start, parsed))
        }
        found += textual.map(\.date)
        for match in patterns.bareDay.matches(in: lower) + patterns.listedDay.matches(in: lower) {
            guard let next = textual.first(where: { $0.position > match.start })?.date,
                  let day = Int(match.group(1, in: value)), let sameMonth = date(next.year, next.month, day) else { continue }
            if next < sameMonth {
                guard let previous = next.addingMonths(-1), let earlier = date(previous.year, previous.month, day) else { continue }
                found.append(earlier)
            } else {
                found.append(sameMonth)
            }
        }
        guard let earliest = reference.addingMonths(-12), let latest = reference.addingMonths(24) else { return nil }
        let plausible = found.filter { !($0 < earliest) && !(latest < $0) }
        guard let first = plausible.min(), let last = plausible.max() else { return nil }
        return NoticeDates(first: first, last: last)
    }

    /// `day` `month` in the year that puts it within six months of `reference`.
    private func nearest(day: Int, month: Int, reference: PlannedLocalDate) -> PlannedLocalDate? {
        guard let candidate = PlannedLocalDate(year: reference.year, month: month, day: day),
              let before = reference.addingMonths(-6), let after = reference.addingMonths(6) else { return nil }
        if candidate < before { return PlannedLocalDate(year: reference.year + 1, month: month, day: day) }
        if after < candidate { return PlannedLocalDate(year: reference.year - 1, month: month, day: day) }
        return candidate
    }
}
