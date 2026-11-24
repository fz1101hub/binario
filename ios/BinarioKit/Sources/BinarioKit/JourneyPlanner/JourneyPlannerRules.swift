import Foundation

/// Tuning of the offline planner (Android's `GtfsJourneyPlannerSettings`), from the shared tables
/// contracts/shared-data/v1/journey-planner-v1.json and urban-stops-v1.json.
public struct JourneyPlannerSettings: Equatable, Sendable {
    public var defaultTransferMinutes = SharedData.JourneyPlanner.defaultTransferMinutes
    public var largeHubTransferMinutes = SharedData.JourneyPlanner.largeHubTransferMinutes
    public var largeHubRailwayCodes: Set<String> = SharedData.JourneyPlanner.largeHubRailwayCodes
    public var searchWindowMinutes = SharedData.JourneyPlanner.searchWindowMinutes
    public var journeyHorizonMinutes = SharedData.JourneyPlanner.journeyHorizonMinutes
    public var maxPacksPerSearch = SharedData.JourneyPlanner.maximumPacksPerSearch
    public var maxIterations = SharedData.JourneyPlanner.maximumIterations
    public var maxSearchConnections = SharedData.JourneyPlanner.maximumSearchConnections
    /// Two transfer points at most this far apart (straight line) are joined by a walk.
    public var maxWalkMeters = SharedData.UrbanStops.maximumWalkMeters
    public var walkingSpeedKmh = SharedData.UrbanStops.walkingSpeedKmh
    /// Added to every walk (finding the way, stairs, ticket barriers).
    public var walkMarginMinutes = SharedData.UrbanStops.walkMarginMinutes
    public var urbanTransferMinutes = SharedData.JourneyPlanner.urbanTransferMinutes
    public var maxUrbanPacksPerSearch = SharedData.JourneyPlanner.maximumUrbanPacksPerSearch
    /// A wait between two vehicles longer than this is a long wait.
    public var maxChangeWaitMinutes = SharedData.JourneyPlanner.maximumChangeWaitMinutes

    public init() {}

    /// Minutes of a walk of `meters`: the distance at `walkingSpeedKmh`, rounded up, plus `walkMarginMinutes`.
    public func walkMinutes(_ meters: Double) -> Int {
        Int((meters / (walkingSpeedKmh * 1_000 / 60)).rounded(.up)) + walkMarginMinutes
    }

    /// Minimum change time at a railway station with the catalogue `railwayCode` (nil: an ordinary station).
    public func railTransferMinutes(_ railwayCode: String?) -> Int {
        if let railwayCode, largeHubRailwayCodes.contains(railwayCode) { return largeHubTransferMinutes }
        return defaultTransferMinutes
    }

    /// The walks of one search with the traveller's preferences: their speed, and the longest walk that fits in their
    /// minutes (Android's `plannerSettings`).
    public func with(_ preferences: JourneyPreferences?) -> JourneyPlannerSettings {
        guard let preferences else { return self }
        var copy = self
        copy.walkingSpeedKmh = preferences.walkingSpeed.kmh
        copy.maxWalkMeters = preferences.maxWalkMeters
        return copy
    }
}

/// The planner's rules on numbers, kinds of train and names (Android's `EmbeddedGtfsJourneyProvider` companion), shared
/// with Android through contracts/shared-rules/v1/journey-planner-v1.json.
public enum JourneyPlannerRules {
    /// «RE_11 - 2153» (Trenord's trip_short_name) shows as «2153»; other numbers are kept as published.
    public static func displayNumber(_ published: String) -> String? {
        let tail: Substring
        if let range = published.range(of: " - ", options: .backwards) {
            tail = published[range.upperBound...]
        } else {
            tail = Substring(published)
        }
        let trimmed = tail.trimmingCharacters(in: .whitespaces)
        if !trimmed.isEmpty { return trimmed }
        let whole = published.trimmingCharacters(in: .whitespaces)
        return whole.isEmpty ? nil : whole
    }

    /// The number two packs publish for the same run: the digits of the displayed number without leading zeros («EC 85»,
    /// «85» and «RE_11 - 85» all give «85»), or its letters and digits when it has no digit. Longer than eight
    /// characters it is an id, never merged.
    public static func runNumberKey(_ published: String) -> String? {
        guard let shown = displayNumber(published) else { return nil }
        let digits = String(shown.unicodeScalars.filter { ("0"..."9").contains($0) }.map(Character.init))
        let withoutZeros = String(digits.drop { $0 == "0" })
        let key = withoutZeros.isEmpty
            ? String(shown.uppercased().unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.map(Character.init))
            : withoutZeros
        guard !key.isEmpty, key.count <= 8 else { return nil }
        return key
    }

    /// What a train is by its explicit codes, for the in-seat continuations between packs.
    public enum Brand: String, Sendable {
        case longDistance = "LONG_DISTANCE"
        case local = "LOCAL"
        case unknown = "UNKNOWN"
    }

    /// The kind of a train from the letters before its number («RJ 80», «REX 1826») or else its line («EC», «S10»).
    public static func brand(publicNumber: String, lineLabel: String?) -> Brand {
        let prefix = displayNumber(publicNumber).map { String($0.prefix { !$0.isNumber }) }
        for code in [prefix, lineLabel] {
            let normalized = String((code ?? "").uppercased().unicodeScalars
                .filter { CharacterSet.alphanumerics.contains($0) }.map(Character.init))
            if normalized.isEmpty { continue }
            if SharedData.JourneyPlanner.longDistanceCodes.contains(normalized) { return .longDistance }
            if isLocalCode(normalized) { return .local }
        }
        return .unknown
    }

    /// Two trains of two packs may be one vehicle unless one is long-distance and the other local.
    public static func brandsAgree(_ first: Brand, _ second: Brand) -> Bool {
        first == second || first == .unknown || second == .unknown
    }

    /// One of the table's local letters, then up to its digits («S10», «RE11»).
    static func isLocalCode(_ code: String) -> Bool {
        let letters = String(code.prefix { !$0.isNumber })
        let digits = code.dropFirst(letters.count)
        guard SharedData.JourneyPlanner.localCodes.contains(letters),
              digits.count <= SharedData.JourneyPlanner.localCodeMaximumDigits
        else { return false }
        return digits.allSatisfy { $0.isASCII && $0.isNumber }
    }

    /// Android's `serviceKind`: buses and city lines by their mode, an Intercity Notte as a night train, then the
    /// category, then the line's code.
    public static func serviceKind(mode: TimetableLegMode, category: JourneyTrainCategory, line: String?) -> JourneyServiceKind {
        switch mode {
        case .replacementBus: return .replacementBus
        case .bus: return .bus
        case .metro, .tram, .funicular: return .urban
        case .train: break
        }
        let code = (line ?? "").uppercased().replacingOccurrences(of: " ", with: "")
        if code == "ICN" { return .night }
        switch category {
        case .frecce, .avItalo: return .highSpeed
        case .intercity: return .intercity
        default: break
        }
        if ["NJ", "EN", "ICN"].contains(code) { return .night }
        if ["FR", "FA", "FB", "AV", "RJ", "RJX", "ICE", "TGV", "ES"].contains(code) { return .highSpeed }
        if ["EC", "IC", "IR"].contains(code) { return .intercity }
        if matches(code, letters: ["S"]) { return .suburban }
        if matches(code, letters: ["R", "RE", "REX", "RV", "REG", "RB", "TER"]) { return .regional }
        if category == .regionale || category == .regionaleVeloce || category == .trenordRegio { return .regional }
        return .unknown
    }

    /// `(letters)\d{0,3}` as a whole.
    private static func matches(_ code: String, letters: Set<String>) -> Bool {
        let prefix = String(code.prefix { !$0.isNumber })
        let digits = code.dropFirst(prefix.count)
        return letters.contains(prefix) && digits.count <= 3 && digits.allSatisfy { $0.isASCII && $0.isNumber }
    }

    /// The number and the line a leg shows: a city run has a line («M1», «14») but no number (its trip id is not one); a
    /// line that is a long name, a placeholder or the number itself is left out.
    static func numberAndLine(mode: TimetableLegMode, publicNumber: String, lineLabel: String?) -> (number: String?, line: String?) {
        let number = mode.isUrban ? nil : displayNumber(publicNumber)
        let raw = mode.isUrban ? TimetableNaming.urbanLine(lineLabel, mode: mode) : lineLabel
        guard let label = raw, label.count <= 10, !label.contains("("), label.caseInsensitiveCompare("INCONNU") != .orderedSame,
              number.map({ label.caseInsensitiveCompare($0) != .orderedSame }) ?? true
        else { return (number, nil) }
        return (number, label)
    }

    /// Android's `GtfsStopStationResolver.displayName`: the name after «Stazione di», without a trailing platform number.
    static func displayName(_ stopName: String) -> String {
        let trimmed = stopName.trimmingCharacters(in: .whitespaces)
        let afterStation = firstGroup(stationOf, in: trimmed)?.trimmingCharacters(in: .whitespaces)
        let base = (afterStation?.isEmpty == false ? afterStation! : trimmed)
        let cleaned = replacing(trailingPlatform, in: base, with: "").trimmingCharacters(in: .whitespaces)
        return cleaned.isEmpty ? trimmed : cleaned
    }

    /// Android's `GtfsStopStationResolver.variants`, normalized: the spellings of a stop name two packs may publish for
    /// one station.
    static func normalizedVariants(_ stopName: String) -> Set<String> {
        let trimmed = stopName.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return [] }
        var base = [trimmed]
        if let after = firstGroup(stationOf, in: trimmed)?.trimmingCharacters(in: .whitespaces), !after.isEmpty { base.append(after) }
        if let comma = trimmed.firstIndex(of: ",") {
            let after = trimmed[trimmed.index(after: comma)...].trimmingCharacters(in: .whitespaces)
            if !after.isEmpty { base.append(after) }
        }
        var all = Set<String>()
        for value in base {
            all.insert(TimetableNaming.normalize(value))
            var cleaned = replacing(trailingPlatform, in: value, with: "")
            cleaned = replacing(countrySuffix, in: cleaned, with: "")
            cleaned = replacing(stationSuffix, in: cleaned, with: "").trimmingCharacters(in: .whitespaces)
            if !cleaned.isEmpty { all.insert(TimetableNaming.normalize(cleaned)) }
            let expanded = replacing(mainStation, in: cleaned, with: "Hauptbahnhof")
            if !expanded.isEmpty { all.insert(TimetableNaming.normalize(expanded)) }
        }
        all.remove("")
        return all
    }

    private static var stationOf: NSRegularExpression? { try? NSRegularExpression(pattern: "\\bstazione(?:\\s+di)?\\s+(.+)$", options: [.caseInsensitive]) }
    private static var trailingPlatform: NSRegularExpression? { try? NSRegularExpression(pattern: "\\s+\\d{1,2}[A-Za-z]?$") }
    private static var countrySuffix: NSRegularExpression? { try? NSRegularExpression(pattern: "\\s*\\((?:I|IT|CH|A|AT|F|FR|D|DE|SLO|HR)\\)$", options: [.caseInsensitive]) }
    private static var stationSuffix: NSRegularExpression? { try? NSRegularExpression(pattern: "\\s+(?:stazione|bahnhof|bhf)$", options: [.caseInsensitive]) }
    private static var mainStation: NSRegularExpression? { try? NSRegularExpression(pattern: "\\bHbf\\b\\.?", options: [.caseInsensitive]) }

    private static func firstGroup(_ expression: NSRegularExpression?, in text: String) -> String? {
        guard let expression,
              let match = expression.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              match.numberOfRanges > 1, let range = Range(match.range(at: 1), in: text)
        else { return nil }
        return String(text[range])
    }

    private static func replacing(_ expression: NSRegularExpression?, in text: String, with template: String) -> String {
        guard let expression else { return text }
        return expression.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: template)
    }

    /// Great-circle distance in metres (Android's `GtfsStationCodeIndex.distanceKm` × 1000).
    static func distanceMeters(_ first: (latitude: Double, longitude: Double), _ second: (latitude: Double, longitude: Double)) -> Double {
        UrbanStops.distanceMeters(first.latitude, first.longitude, second.latitude, second.longitude)
    }

    /// The pack's class in the planned order (Android's `PlannedPackClass`).
    enum PackClass: Int {
        case national = 0, italianRegional = 1, foreign = 2, communityFallback = 3
    }

    static func packClass(_ sourceID: String) -> PackClass {
        if SharedData.SourceScores.coverageFallbacks.contains(sourceID) { return .communityFallback }
        if SharedData.PackRoles.nationalPacks.contains(sourceID) { return .national }
        if SharedData.PackRoles.foreignPacks.contains(sourceID) { return .foreign }
        return .italianRegional
    }

    /// The planned-itinerary score of a pack (Android's `RailSourceSelectionPolicy.score` without an operator hint).
    static func plannedScore(_ sourceID: String) -> Int {
        SharedData.SourceScores.preferences.first { $0.source == sourceID && $0.category == "PLANNED_ITINERARY" }?.score
            ?? SharedData.SourceScores.defaultScore
    }

    /// Android's `PlannedPackOrder.ordered`: when no national pack serves an end, the packs serving it first, foreign
    /// before Italian regional; then by class; within a class by score. A stable sort.
    static func plannedOrder<T>(_ items: [T], sourceID: (T) -> String, coversOrigin: (T) -> Bool, coversDestination: (T) -> Bool) -> [T] {
        let nationalAtOrigin = items.contains { coversOrigin($0) && packClass(sourceID($0)) == .national }
        let nationalAtDestination = items.contains { coversDestination($0) && packClass(sourceID($0)) == .national }
        let borderOrder: [PackClass] = [.foreign, .italianRegional, .national, .communityFallback]
        func key(_ item: T) -> (Int, Int, Int) {
            let first = (!nationalAtOrigin && coversOrigin(item)) || (!nationalAtDestination && coversDestination(item))
            let packClass = packClass(sourceID(item))
            let classRank = first ? (borderOrder.firstIndex(of: packClass) ?? 0) : packClass.rawValue
            return (first ? 0 : 1, classRank, -plannedScore(sourceID(item)))
        }
        return items.enumerated().sorted { a, b in
            let first = key(a.element)
            let second = key(b.element)
            return first != second ? first < second : a.offset < b.offset
        }.map(\.element)
    }
}
