import Foundation

/// A catalogue station found by its exact name or alias (case, accents and punctuation aside),
/// only when one station has it: a pasted name never picks a station by guess.
public struct PlannedJourneyStationResolver: Sendable {
    private let stationsByName: [String: [StationIdentity]]

    public init(catalog: CuratedStationCatalog) {
        self.init(stations: catalog.stations)
    }

    init(stations: [Station]) {
        var byName: [String: [Station]] = [:]
        for station in stations {
            var names = Set([StationSearchNormalizer.normalize(station.name)])
            for alias in station.aliases {
                names.insert(StationSearchNormalizer.normalize(alias))
            }
            for name in names where !name.isEmpty {
                byName[name, default: []].append(station)
            }
        }
        // Several catalogue entries of one station (co-located duplicates) count once: the entry
        // the search offers.
        stationsByName = byName.mapValues { matches in
            matches.filter { !CuratedStationCatalog.isShadowedDuplicate($0, among: matches) }.map { StationIdentity($0) }
        }
    }

    /// The one station named `name`; nil when none or several are.
    public func station(named name: String) -> StationIdentity? {
        guard let matches = stationsByName[StationSearchNormalizer.normalize(name)], matches.count == 1 else { return nil }
        return matches.first
    }
}

/// A line of pasted text read as a journey, before its stations are looked up.
public struct PlannedJourneyTextDraft: Equatable, Sendable {
    public let origin: String
    public let destination: String
    public let date: PlannedLocalDate
    public let time: PlannedLocalTime
}

/// What a pasted text would add: journeys to review and select, and the lines left out.
public struct PlannedJourneyTextPreview: Equatable, Sendable {
    /// New journeys on catalogue stations, in the order of the text, at most the Agenda's free places.
    public let journeys: [PlannedJourney]
    /// Lines left out: incomplete, an unknown or ambiguous station, the same station twice, a time
    /// the clock change skips, repeated, already in the Agenda or beyond the free places.
    public let skippedLines: Int
}

/// The companion's guided text import (`local-journey-itineraries.ts`, `shared-text-contract.ts`)
/// on the Agenda's rules: one journey per line, «origine → destinazione · data ora» (or «Da …
/// a …»), a date with its year and a time after it. Nothing is added before the user reviews the
/// preview, and an import only ever adds.
public enum PlannedJourneyTextImport {
    /// As the companion's limit, in UTF-16 code units (JavaScript's length).
    public static let maximumCharacters = 4_000

    /// The text as it is read: line breaks as "\n", NUL removed, trimmed. Nil when it is longer
    /// than the limit or holds nothing.
    public static func normalize(_ text: String) -> String? {
        guard !text.isEmpty, text.utf16.count <= maximumCharacters else { return nil }
        let normalized = PlannedJourneyText.trimmed(
            text.replacingOccurrences(of: "\r\n", with: "\n")
                .replacingOccurrences(of: "\r", with: "\n")
                .replacingOccurrences(of: "\u{0000}", with: "")
        )
        return normalized.isEmpty ? nil : normalized
    }

    /// The text's lines (a semicolon also ends one), spaces collapsed, empty lines dropped.
    public static func lines(_ text: String) -> [String] {
        text.components(separatedBy: CharacterSet(charactersIn: "\r\n;"))
            .map(clean)
            .filter { !$0.isEmpty }
    }

    /// The journey a line describes; nil when a part is missing or invalid.
    public static func draft(_ line: String) -> PlannedJourneyTextDraft? {
        let value = clean(line)
        let whole = NSRange(value.startIndex..., in: value)
        guard let dateMatch = datePattern?.firstMatch(in: value, range: whole),
              let timeMatch = timePattern?.firstMatch(in: value, range: whole),
              timeMatch.range.location > dateMatch.range.location,
              let dateRange = Range(dateMatch.range(at: 1), in: value),
              let timeRange = Range(timeMatch.range(at: 1), in: value),
              let date = parseDate(String(value[dateRange])),
              let time = parseTime(String(value[timeRange])),
              let routeEnd = Range(dateMatch.range, in: value)?.lowerBound
        else { return nil }
        var route = String(value[..<routeEnd])
        if let trailing = routeTailPattern?.firstMatch(in: route, range: NSRange(route.startIndex..., in: route)),
           let tail = Range(trailing.range, in: route) {
            route.removeSubrange(tail)
        }
        guard let stations = self.route(route) else { return nil }
        return PlannedJourneyTextDraft(origin: stations.origin, destination: stations.destination, date: date, time: time)
    }

    /// «A → B», «A -> B» or «Da A a B»: two station names of at least two characters that differ
    /// (Android's `parseRoute`); nil otherwise.
    public static func route(_ text: String) -> (origin: String, destination: String)? {
        let route = PlannedJourneyText.trimmed(clean(text))
        let routeRange = NSRange(route.startIndex..., in: route)
        guard let match = arrowPattern?.firstMatch(in: route, range: routeRange)
                ?? fromToPattern?.firstMatch(in: route, range: routeRange),
              let originRange = Range(match.range(at: 1), in: route),
              let destinationRange = Range(match.range(at: 2), in: route)
        else { return nil }
        let origin = clean(String(route[originRange]))
        let destination = clean(String(route[destinationRange]))
        guard origin.count >= 2, destination.count >= 2,
              StationSearchNormalizer.normalize(origin) != StationSearchNormalizer.normalize(destination)
        else { return nil }
        return (origin, destination)
    }

    /// The journeys of `text` that can be added to an Agenda holding `existing`, built at `now` with
    /// identifiers from `makeID`; nil when the text itself is refused (empty or too long). Each
    /// non-empty line is a journey or a skipped line.
    public static func preview(
        _ text: String,
        resolver: PlannedJourneyStationResolver,
        existing: [PlannedJourney],
        now: Date,
        makeID: () -> String = { UUID().uuidString }
    ) -> PlannedJourneyTextPreview? {
        guard let normalized = normalize(text) else { return nil }
        return preview(
            lines(normalized).map(draft), skipped: 0, resolver: resolver, existing: existing, now: now, makeID: makeID
        )
    }

    /// The journeys of `drafts` (nil for a line that is not one) that can be added to an Agenda
    /// holding `existing`, on the same rules as a pasted text; `skipped` lines were already left out.
    public static func preview(
        _ drafts: [PlannedJourneyTextDraft?],
        skipped alreadySkipped: Int,
        resolver: PlannedJourneyStationResolver,
        existing: [PlannedJourney],
        now: Date,
        makeID: () -> String = { UUID().uuidString }
    ) -> PlannedJourneyTextPreview {
        let free = max(0, PlannedJourneySchema.maximumJourneys - existing.count)
        var fingerprints = Set(existing.map(\.fingerprint))
        var journeys: [PlannedJourney] = []
        var skipped = alreadySkipped
        for candidate in drafts {
            guard let draft = candidate,
                  let origin = resolver.station(named: draft.origin),
                  let destination = resolver.station(named: draft.destination),
                  case let .success(journey) = PlannedJourneyForm(
                      origin: origin, destination: destination, date: draft.date, time: draft.time
                  ).journey(id: makeID(), now: now, editing: nil),
                  fingerprints.insert(journey.fingerprint).inserted,
                  journeys.count < free
            else {
                skipped += 1
                continue
            }
            journeys.append(journey)
        }
        return PlannedJourneyTextPreview(journeys: journeys, skippedLines: skipped)
    }

    // MARK: Patterns (ASCII digits only, as the companion's JavaScript)

    private static var datePattern: NSRegularExpression? {
        try? NSRegularExpression(pattern: "([0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{1,2}[/.\\-][0-9]{1,2}[/.\\-][0-9]{4})")
    }

    private static var timePattern: NSRegularExpression? {
        try? NSRegularExpression(
            pattern: "(?:\\balle\\s*|\\bore\\s*|@\\s*|\\s)([0-9]{1,2}[:.][0-9]{2})\\s*$",
            options: [.caseInsensitive]
        )
    }

    private static var routeTailPattern: NSRegularExpression? {
        try? NSRegularExpression(pattern: "[·,;\\-–—]+\\s*$")
    }

    private static var arrowPattern: NSRegularExpression? {
        try? NSRegularExpression(pattern: "^(.{2,96}?)\\s*(?:→|->)\\s*(.{2,96}?)$")
    }

    private static var fromToPattern: NSRegularExpression? {
        try? NSRegularExpression(pattern: "^da\\s+(.{2,96}?)\\s+(?:a|verso)\\s+(.{2,96}?)$", options: [.caseInsensitive])
    }

    /// Trimmed, every run of spaces as one, at most 480 characters.
    private static func clean(_ value: String) -> String {
        let words = value.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        return PlannedJourneyText.trimmed(String(words.prefix(480)))
    }

    private static func parseDate(_ value: String) -> PlannedLocalDate? {
        if let iso = PlannedLocalDate(iso: value) { return iso }
        let parts = value.split(whereSeparator: { $0 == "/" || $0 == "." || $0 == "-" }).map(String.init)
        guard parts.count == 3, parts[2].count == 4,
              let day = Int(parts[0]), let month = Int(parts[1]), let year = Int(parts[2])
        else { return nil }
        return PlannedLocalDate(year: year, month: month, day: day)
    }

    private static func parseTime(_ value: String) -> PlannedLocalTime? {
        let parts = value.split(whereSeparator: { $0 == ":" || $0 == "." }).map(String.init)
        guard parts.count == 2, parts[1].count == 2, let hour = Int(parts[0]), let minute = Int(parts[1]) else { return nil }
        return PlannedLocalTime(hour: hour, minute: minute)
    }
}

extension PlannedJourneyStore {
    /// Several new journeys after the others (a text import): one already in the Agenda, or
    /// repeated, is left out, and only as many as fit are added; nothing is replaced. Refused when
    /// none is new (duplicate) or the Agenda is full.
    public func add(contentsOf journeys: [PlannedJourney]) -> Result<PlannedJourneyAgendaState, PlannedJourneyRefusal> {
        let current = load()
        guard !current.isReadOnly else { return .failure(.readOnly) }
        var fingerprints = Set(current.journeys.map(\.fingerprint))
        var identifiers = Set(current.journeys.map(\.id))
        var fresh: [PlannedJourney] = []
        for journey in journeys where fingerprints.insert(journey.fingerprint).inserted && identifiers.insert(journey.id).inserted {
            fresh.append(journey)
        }
        guard !fresh.isEmpty else { return .failure(.duplicate) }
        let free = PlannedJourneySchema.maximumJourneys - current.journeys.count
        guard free > 0 else { return .failure(.full) }
        return replaceAll(current.journeys + fresh.prefix(free))
    }
}
