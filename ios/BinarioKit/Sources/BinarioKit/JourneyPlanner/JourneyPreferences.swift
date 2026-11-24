import Foundation

/// Walking speed of the walks of a journey (to the metro, between two stations), Android's `WalkingSpeed`.
public enum WalkingSpeed: String, Codable, CaseIterable, Sendable {
    case slow = "SLOW"
    case normal = "NORMAL"
    case fast = "FAST"

    public var kmh: Double {
        switch self {
        case .slow: return SharedData.JourneyPlanner.slowWalkingKmh
        case .normal: return SharedData.JourneyPlanner.normalWalkingKmh
        case .fast: return SharedData.JourneyPlanner.fastWalkingKmh
        }
    }
}

/// «Preferisci»: how the results are ranked. It never changes which journeys are correct.
public enum JourneyRanking: String, Codable, CaseIterable, Sendable {
    /// The journeys that arrive first, in order of departure: the default.
    case fastest = "FASTEST"
    case fewestChanges = "FEWEST_CHANGES"
    case leastWalking = "LEAST_WALKING"
}

/// What a leg's train is, from its codes (Android's `ServiceKind`).
public enum JourneyServiceKind: String, Codable, Sendable {
    case highSpeed = "HIGH_SPEED"
    case intercity = "INTERCITY"
    case night = "NIGHT"
    case regional = "REGIONAL"
    case suburban = "SUBURBAN"
    case replacementBus = "REPLACEMENT_BUS"
    case bus = "BUS"
    case urban = "URBAN"
    case unknown = "UNKNOWN"
}

/// The kinds of service a traveller can prefer or avoid (Android's `TravelMode`). A leg of a kind the source does not
/// tell has none and is never avoided.
public enum TravelMode: String, Codable, CaseIterable, Sendable {
    /// Frecce and the other high-speed trains (Railjet, TGV, ICE), Italo excluded.
    case highSpeed = "HIGH_SPEED"
    /// Intercity, EuroCity and night trains.
    case intercity = "INTERCITY"
    /// Regional and suburban trains.
    case regional = "REGIONAL"
    /// Italo trains (ItaloBus is `operatorBus`).
    case italo = "ITALO"
    case metro = "METRO"
    case tram = "TRAM"
    case funicular = "FUNICULAR"
    /// The operators' own coaches (Trenitalia, FrecciaLink, ItaloBus) and rail replacement buses.
    case operatorBus = "OPERATOR_BUS"

    /// The kind of a journey leg; nil for a train of unknown kind.
    public static func of(mode: TimetableLegMode, serviceKind: JourneyServiceKind, operatorName: String?) -> TravelMode? {
        switch mode {
        case .bus, .replacementBus: return .operatorBus
        case .metro: return .metro
        case .tram: return .tram
        case .funicular: return .funicular
        case .train:
            if (operatorName ?? "").lowercased().contains("italo") { return .italo }
            switch serviceKind {
            case .highSpeed: return .highSpeed
            case .intercity, .night: return .intercity
            case .regional, .suburban: return .regional
            default: return nil
            }
        }
    }

    /// The kind of a direct train of the bundled timetable (Android's `ofTrain` on its category); nil when the
    /// category says nothing.
    public static func ofTrain(_ service: TrainService) -> TravelMode? {
        if service.isBus { return .operatorBus }
        if let mode = service.vehicle?.urbanMode { return of(mode: mode, serviceKind: .urban, operatorName: nil) }
        switch JourneyTrainCategory.of(line: service.category, operatorName: service.operatorName, isRail: true) {
        case .frecce: return .highSpeed
        case .avItalo: return .italo
        case .intercity: return .intercity
        case .regionale, .regionaleVeloce, .trenordRegio, .metropolitano: return .regional
        case .other: return nil
        }
    }
}

/// One service kind in the travel preferences.
public enum ModeChoice: String, Codable, CaseIterable, Sendable {
    case any = "ANY"
    case prefer = "PREFER"
    case avoid = "AVOID"
}

/// «Preferenze di viaggio» (Android's `JourneyPreferences`): kept on this iPhone and sent with every journey search to
/// the offline planner, never anywhere else. The walking choices change the walks the planner offers (minutes =
/// distance / `walkingSpeed` + the margin, at most `maxWalkMinutes` per walk); `ranking` and `preferredModes` only
/// reorder; `avoidedModes` and `wheelchairOnly` leave journeys out.
public struct JourneyPreferences: Codable, Equatable, Hashable, Sendable {
    public static let walkMinuteChoices: [Int] = SharedData.JourneyPlanner.walkMinuteChoices
    public static let defaultMaxWalkMinutes = SharedData.JourneyPlanner.defaultMaximumWalkMinutes

    public var walkingSpeed: WalkingSpeed
    /// Longest single walk, margin included: one of `walkMinuteChoices`.
    public var maxWalkMinutes: Int
    public var ranking: JourneyRanking
    public var preferredModes: Set<TravelMode>
    public var avoidedModes: Set<TravelMode>
    /// «Solo percorsi accessibili in sedia a rotelle»: every trip and every boarding and alighting stop must be marked
    /// accessible (GTFS 1), and a walk between stations needs both ends marked; unknown never counts.
    public var wheelchairOnly: Bool

    public init(
        walkingSpeed: WalkingSpeed = .normal,
        maxWalkMinutes: Int = JourneyPreferences.defaultMaxWalkMinutes,
        ranking: JourneyRanking = .fastest,
        preferredModes: Set<TravelMode> = [],
        avoidedModes: Set<TravelMode> = [],
        wheelchairOnly: Bool = false
    ) {
        self.walkingSpeed = walkingSpeed
        self.maxWalkMinutes = Self.walkMinuteChoices.contains(maxWalkMinutes) ? maxWalkMinutes : Self.defaultMaxWalkMinutes
        self.ranking = ranking
        self.preferredModes = preferredModes
        // A kind is never both preferred and avoided.
        self.avoidedModes = avoidedModes.subtracting(preferredModes)
        self.wheelchairOnly = wheelchairOnly
    }

    /// Android's `TravelPreferencesCodec.fromJson`: unknown names and values fall back to the defaults.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        func text(_ key: CodingKeys) -> String? { (try? container.decodeIfPresent(String.self, forKey: key)) ?? nil }
        func modes(_ key: CodingKeys) -> Set<TravelMode> {
            let names = (try? container.decodeIfPresent([String].self, forKey: key)) ?? nil
            return Set((names ?? []).compactMap(TravelMode.init(rawValue:)))
        }
        let walk = (try? container.decodeIfPresent(Int.self, forKey: .maxWalkMinutes)) ?? nil
        let wheelchair = (try? container.decodeIfPresent(Bool.self, forKey: .wheelchairOnly)) ?? nil
        self.init(
            walkingSpeed: text(.walkingSpeed).flatMap(WalkingSpeed.init(rawValue:)) ?? .normal,
            maxWalkMinutes: walk ?? Self.defaultMaxWalkMinutes,
            ranking: text(.ranking).flatMap(JourneyRanking.init(rawValue:)) ?? .fastest,
            preferredModes: modes(.preferredModes),
            avoidedModes: modes(.avoidedModes),
            wheelchairOnly: wheelchair ?? false
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(walkingSpeed.rawValue, forKey: .walkingSpeed)
        try container.encode(maxWalkMinutes, forKey: .maxWalkMinutes)
        try container.encode(ranking.rawValue, forKey: .ranking)
        try container.encode(preferredModes.map(\.rawValue).sorted(), forKey: .preferredModes)
        try container.encode(avoidedModes.map(\.rawValue).sorted(), forKey: .avoidedModes)
        try container.encode(wheelchairOnly, forKey: .wheelchairOnly)
    }

    private enum CodingKeys: String, CodingKey {
        case walkingSpeed, maxWalkMinutes, ranking, preferredModes, avoidedModes, wheelchairOnly
    }

    /// A copy where `mode` is preferred, avoided or neither (exactly one of the three).
    public func with(_ mode: TravelMode, choice: ModeChoice) -> JourneyPreferences {
        var copy = self
        if choice == .prefer { copy.preferredModes.insert(mode) } else { copy.preferredModes.remove(mode) }
        if choice == .avoid { copy.avoidedModes.insert(mode) } else { copy.avoidedModes.remove(mode) }
        return copy
    }

    public func choice(of mode: TravelMode) -> ModeChoice {
        if preferredModes.contains(mode) { return .prefer }
        if avoidedModes.contains(mode) { return .avoid }
        return .any
    }

    /// Something differs from the defaults: the results say that the travel preferences apply.
    public var isCustomized: Bool { self != JourneyPreferences() }

    /// The longest walk of these preferences in metres: the minutes left after the walk margin at the chosen speed,
    /// bounded by the table's longest walk (Android's `plannerSettings`).
    public var maxWalkMeters: Int {
        let metersPerMinute = walkingSpeed.kmh * 1_000 / 60
        let budget = max(maxWalkMinutes - SharedData.UrbanStops.walkMarginMinutes, 0)
        return min(max(Int((Double(budget) * metersPerMinute).rounded(.down)), 0), SharedData.JourneyPlanner.maximumWalkMeters)
    }
}

/// The app's category of a train (Android's `TrainCategory`), for its kind of service.
public enum JourneyTrainCategory: String, Sendable {
    case regionale, regionaleVeloce, intercity, frecce, avItalo, trenordRegio, metropolitano, other

    /// Android's `EmbeddedGtfsRouteAdapter.category`: Trenitalia's line code first («0083_FR» is «FR»), then the words
    /// of the route's names and the operator, without accents, spaces or punctuation. `trenitaliaCode` is the code of a
    /// Trenitalia route, `names` the short name, long name and operator as published.
    static func of(trenitaliaCode: String?, names: [String?], isRail: Bool) -> JourneyTrainCategory {
        let identity = TimetableNaming.normalize(names.compactMap { $0 }.joined(separator: " "))
        if identity.contains("trenitalia"), let code = trenitaliaCode?.uppercased() {
            switch code {
            case "FR", "FA", "FB": return .frecce
            case "IC", "ICN": return .intercity
            case "RV": return .regionaleVeloce
            case "REG", "SFM": return .regionale
            case "MET": return .metropolitano
            case "EC", "EN", "EXP": return .other
            default: break
            }
        }
        if identity.contains("italo") { return .avItalo }
        if identity.contains("frecc") { return .frecce }
        if identity.contains("intercity") || containsIntercityCode(identity) { return .intercity }
        if identity.contains("regionaleveloce") { return .regionaleVeloce }
        if identity.contains("trenord") { return .trenordRegio }
        return isRail ? .regionale : .other
    }

    /// The category of a listed train of the bundled timetable, whose `category` is its route's line as Android shows
    /// it (Trenitalia's code without «0083_»).
    static func of(line: String, operatorName: String, isRail: Bool) -> JourneyTrainCategory {
        let trenitalia = operatorName.lowercased().contains("trenitalia")
        return of(trenitaliaCode: trenitalia ? line : nil, names: [line, operatorName], isRail: isRail)
    }

    /// `(^|[^a-z])icn?([^a-z]|$)` on the normalized text.
    private static func containsIntercityCode(_ text: String) -> Bool {
        let scalars = Array(text.unicodeScalars)
        func isLetter(_ index: Int) -> Bool { index >= 0 && index < scalars.count && ("a"..."z").contains(scalars[index]) }
        var index = 0
        while index + 1 < scalars.count {
            if scalars[index] == "i", scalars[index + 1] == "c", !isLetter(index - 1) {
                if !isLetter(index + 2) { return true }
                if index + 2 < scalars.count, scalars[index + 2] == "n", !isLetter(index + 3) { return true }
            }
            index += 1
        }
        return false
    }
}
