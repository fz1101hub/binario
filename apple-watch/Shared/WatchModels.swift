import Foundation

private let snapshotMaximumBytes = 8_192
private let snapshotMaximumServices = 2
private let snapshotMaximumTTL: Int64 = 365 * 24 * 60 * 60 * 1_000
private let snapshotMaximumFutureSkew: Int64 = 60_000
private let snapshotMaximumVehicleCharacters = 32

private struct SnapshotCodingKey: CodingKey, Hashable {
    let stringValue: String
    let intValue: Int? = nil

    init(_ stringValue: String) { self.stringValue = stringValue }
    init?(stringValue: String) { self.init(stringValue) }
    init?(intValue: Int) { return nil }
}

protocol UnknownWireEnum: RawRepresentable, Codable where RawValue == String {
    static var unknownValue: Self { get }
}

extension UnknownWireEnum {
    init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer().decode(String.self)
        self = Self(rawValue: value) ?? Self.unknownValue
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
}

enum BinarioDirection: String, UnknownWireEnum, CaseIterable {
    case outbound = "OUTBOUND"
    case `return` = "RETURN"
    case unknown = "UNKNOWN"

    static let unknownValue = BinarioDirection.unknown

    var accessibilityLabel: String {
        switch self {
        case .outbound: return WatchLocalization.text("watch.direction.outbound")
        case .return: return WatchLocalization.text("watch.direction.return")
        case .unknown: return WatchLocalization.text("watch.direction.unknown")
        }
    }
}

enum BinarioFreshness: String, UnknownWireEnum {
    case fresh = "FRESH"
    case aging = "AGING"
    case stale = "STALE"
    case unavailable = "UNAVAILABLE"
    case unknown = "UNKNOWN"

    static let unknownValue = BinarioFreshness.unknown

    var label: String {
        switch self {
        case .fresh: return WatchLocalization.text("watch.freshness.fresh")
        case .aging: return WatchLocalization.text("watch.freshness.aging")
        case .stale: return WatchLocalization.text("watch.freshness.stale")
        case .unavailable, .unknown: return WatchLocalization.text("watch.freshness.unavailable")
        }
    }
}

enum BinarioProvenanceProducer: String, UnknownWireEnum {
    case androidApp = "ANDROID_APP"
    case companion = "COMPANION"
    case iosApp = "IOS_APP"
    case unknown = "UNKNOWN"

    static let unknownValue = BinarioProvenanceProducer.unknown
}

enum BinarioDataNature: String, UnknownWireEnum {
    case programmed = "PROGRAMMED"
    case operational = "OPERATIONAL"
    case unavailable = "UNAVAILABLE"
    case unknown = "UNKNOWN"

    static let unknownValue = BinarioDataNature.unknown
}

enum BinarioOperationalState: String, UnknownWireEnum {
    case programmed = "PROGRAMMED"
    case operational = "OPERATIONAL"
    case unavailable = "UNAVAILABLE"
    case unknown = "UNKNOWN"

    static let unknownValue = BinarioOperationalState.unknown
}

enum BinarioConfidence: String, UnknownWireEnum {
    case operatorPrimary = "OPERATOR_PRIMARY"
    case infrastructurePrimary = "INFRASTRUCTURE_PRIMARY"
    case openData = "OPEN_DATA"
    case licensedAggregate = "LICENSED_AGGREGATE"
    case staticOnly = "STATIC_ONLY"
    case cached = "CACHED"
    case localPlanned = "LOCAL_PLANNED"
    case unknown = "UNKNOWN"

    static let unknownValue = BinarioConfidence.unknown

    var label: String {
        switch self {
        case .operatorPrimary: return WatchLocalization.text("watch.confidence.operatorPrimary")
        case .infrastructurePrimary: return WatchLocalization.text("watch.confidence.infrastructurePrimary")
        case .openData: return WatchLocalization.text("watch.confidence.openData")
        case .licensedAggregate: return WatchLocalization.text("watch.confidence.licensedAggregate")
        case .staticOnly: return WatchLocalization.text("watch.confidence.staticOnly")
        case .cached: return WatchLocalization.text("watch.confidence.cached")
        case .localPlanned: return WatchLocalization.text("watch.confidence.localPlanned")
        case .unknown: return WatchLocalization.text("watch.confidence.unknown")
        }
    }
}

/// What a service travels on (contract v1 `service{n}Vehicle`, since 23 November 2026): the iPhone's
/// `TimetableVehicle`. A service without it (an earlier iPhone, an older cache) is a train, and so is
/// an unknown value: only a known bus gets the bus symbol.
enum WatchVehicle: String, UnknownWireEnum {
    case train = "TRAIN"
    case busTrenitalia = "BUS_TRENITALIA"
    case freccialink = "FRECCIALINK"
    case italobus = "ITALOBUS"
    case bus = "BUS"
    case replacementBus = "REPLACEMENT_BUS"
    case unknown = "UNKNOWN"

    static let unknownValue = WatchVehicle.unknown
    /// The SF Symbol of a bus, as the iPhone draws one (VehicleTexts.busSymbol).
    static let busSymbol = "bus"

    var isBus: Bool {
        switch self {
        case .busTrenitalia, .freccialink, .italobus, .bus, .replacementBus: return true
        case .train, .unknown: return false
        }
    }
}

/// «In orario 22 volte su 27»: how often the train was on time in the unofficial network statistics
/// the iPhone includes (contract v1, the optional reliability pair). Shown under the service in the
/// app, never in a widget.
struct WatchReliability: Codable, Equatable {
    static let minimumSamples = 5
    static let maximumSamples = 10_000

    let onTime: Int
    let samples: Int

    var isValid: Bool {
        (Self.minimumSamples...Self.maximumSamples).contains(samples) && (0...samples).contains(onTime)
    }

    /// The counted part agrees with the days on time («22 volte», «1 volta»); the line puts it in
    /// the language's order with the samples.
    var text: String {
        let onTimeText = WatchLocalization.text("watch.reliability.onTime \(onTime)")
        return WatchLocalization.text("watch.reliability.line \(onTimeText) \(samples)")
    }

    /// What VoiceOver reads: the line, saying the statistics are unofficial.
    var spokenText: String {
        WatchLocalization.text("watch.reliability.spoken \(text)")
    }
}

struct WatchService: Codable, Equatable, Identifiable {
    let number: String
    let operatorName: String
    let category: String
    let destination: String
    let scheduledDeparture: String
    let expectedDeparture: String
    let platform: String?
    let delayMinutes: Int
    let status: String
    /// Optional in contract v1: sent only to a Watch app that declares it decodes it.
    var reliability: WatchReliability? = nil
    var vehicle: WatchVehicle? = nil

    var id: String { number + "-" + expectedDeparture }

    /// A bus of the iPhone's timetable: the bus symbol before its label, which the iPhone sends as
    /// the category («Bus Trenitalia BUS»), and never called a train.
    var isBus: Bool { vehicle?.isBus == true }

    /// The service as the iPhone rows name it (`ReliableAlternatives.trainLabel`, `VehicleTexts.rowLabel`):
    /// a bus by its label and number, a train by category and number («FR 9505»), or by its number
    /// alone when that already names the line («RE_11 - 2153»).
    var label: String {
        let trimmed = number.trimmingCharacters(in: .whitespaces)
        if !isBus, category.isEmpty || trimmed.contains(where: { $0.isLetter }) { return trimmed }
        return "\(category) \(trimmed)"
    }

    /// The wire status is the OperationalState raw value for programmed data (free text for
    /// operational data): never show the raw token.
    var displayStatus: String {
        status == BinarioOperationalState.programmed.rawValue
            ? WatchLocalization.text("watch.serviceStatus.programmed")
            : status
    }

    var accessibilitySummary: String {
        let platformLabel = platform ?? WatchLocalization.text("watch.detail.noValue")
        if delayMinutes > 0 {
            let delay = WatchLocalization.text("watch.detail.delay \(delayMinutes)")
            if isBus {
                return WatchLocalization.text(
                    "watch.accessibility.vehicle.withDelay \(operatorName) \(category) \(number) \(destination) \(expectedDeparture) \(delay) \(platformLabel)"
                )
            }
            return WatchLocalization.text(
                "watch.accessibility.service.withDelay \(operatorName) \(category) \(number) \(destination) \(expectedDeparture) \(delay) \(platformLabel)"
            )
        }
        if isBus {
            return WatchLocalization.text(
                "watch.accessibility.vehicle.withoutDelay \(operatorName) \(category) \(number) \(destination) \(expectedDeparture) \(platformLabel)"
            )
        }
        return WatchLocalization.text(
            "watch.accessibility.service.withoutDelay \(operatorName) \(category) \(number) \(destination) \(expectedDeparture) \(platformLabel)"
        )
    }

    /// The row's sentence with its reliability, as Wear OS reads it.
    var accessibilityLabelWithReliability: String {
        guard let reliability else { return accessibilitySummary }
        return accessibilitySummary + " " + reliability.spokenText
    }
}

struct BinarioWatchSnapshot: Codable, Equatable {
    static let currentSchemaVersion = 1

    let schemaVersion: Int
    let generatedAtEpochMillis: Int64
    let expiresAtEpochMillis: Int64
    let ttlMillis: Int64
    let provenanceProducer: BinarioProvenanceProducer
    let provenanceDataNature: BinarioDataNature
    let provenanceSourceLabel: String
    let provenanceObservedAtEpochMillis: Int64
    let operationalState: BinarioOperationalState
    let freshness: BinarioFreshness
    let confidence: BinarioConfidence
    let routeId: String
    let routeLabel: String
    let direction: BinarioDirection
    let notice: String?
    let services: [WatchService]

    // These values are part of the cached snapshot payload. Keep them untouched and
    // localize only their presentation through the display properties below.
    private static let unavailableProviderName = "Nessun provider"
    private static let unavailableRouteLabel = "Nessuna tratta attiva"
    private static let unavailableNotice = "dati non disponibili in tempo reale"

    var providerName: String { provenanceSourceLabel }
    var displayProviderName: String {
        providerName == Self.unavailableProviderName
            ? WatchLocalization.text("watch.provider.none")
            : providerName
    }
    var displayRouteLabel: String {
        routeLabel == Self.unavailableRouteLabel
            ? WatchLocalization.text("watch.route.none")
            : routeLabel
    }
    var displayNotice: String? {
        guard let notice else { return nil }
        return notice == Self.unavailableNotice
            ? WatchLocalization.text("watch.unavailable.description")
            : notice
    }
    var confidenceLabel: String { confidence.label }
    /// PROGRAMMED alone declares that the data is not realtime (contract README), whatever the
    /// freshness says: present it as scheduled data, never as fresh live data and never as the
    /// red "not available in real time" reserved for unavailable data.
    var isScheduledOnly: Bool {
        guard operationalState != .unavailable else { return false }
        return operationalState == .programmed || provenanceDataNature == .programmed
    }
    /// Live values name the time their source observed them, as on Wear OS: the Watch keeps a live
    /// snapshot up to 15 minutes, and its values must not read as current for that long.
    var displayFreshnessLabel: String {
        if isScheduledOnly { return WatchLocalization.text("watch.freshness.scheduled") }
        if operationalState == .operational {
            switch freshness {
            case .fresh: return WatchLocalization.text("watch.freshness.liveAsOf \(observedClockTime)")
            case .aging: return WatchLocalization.text("watch.freshness.notRecentAsOf \(observedClockTime)")
            case .stale, .unavailable, .unknown: break
            }
        }
        return freshness.label
    }
    /// The "HH:mm" of the observation in Rome, the clock of the departures.
    var observedClockTime: String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Europe/Rome") ?? TimeZone(secondsFromGMT: 3_600) ?? .current
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(provenanceObservedAtEpochMillis) / 1_000))
    }
    var retrievedAtEpochMillis: Int64 { generatedAtEpochMillis }
    var retrievedAt: Date { Date(timeIntervalSince1970: TimeInterval(generatedAtEpochMillis) / 1_000) }
    var isCompatible: Bool { isCompatible(at: Int64(Date().timeIntervalSince1970 * 1_000)) }

    private static let baseKeys: Set<String> = [
        "schemaVersion", "generatedAtEpochMillis", "expiresAtEpochMillis", "ttlMillis",
        "provenanceProducer", "provenanceDataNature", "provenanceSourceLabel", "provenanceObservedAtEpochMillis",
        "operationalState", "freshness", "confidence", "routeId", "routeLabel", "direction", "notice", "serviceCount"
    ]

    init(
        schemaVersion: Int,
        generatedAtEpochMillis: Int64,
        expiresAtEpochMillis: Int64,
        ttlMillis: Int64,
        provenanceProducer: BinarioProvenanceProducer,
        provenanceDataNature: BinarioDataNature,
        provenanceSourceLabel: String,
        provenanceObservedAtEpochMillis: Int64,
        operationalState: BinarioOperationalState,
        freshness: BinarioFreshness,
        confidence: BinarioConfidence,
        routeId: String,
        routeLabel: String,
        direction: BinarioDirection,
        notice: String?,
        services: [WatchService]
    ) {
        self.schemaVersion = schemaVersion
        self.generatedAtEpochMillis = generatedAtEpochMillis
        self.expiresAtEpochMillis = expiresAtEpochMillis
        self.ttlMillis = ttlMillis
        self.provenanceProducer = provenanceProducer
        self.provenanceDataNature = provenanceDataNature
        self.provenanceSourceLabel = provenanceSourceLabel
        self.provenanceObservedAtEpochMillis = provenanceObservedAtEpochMillis
        self.operationalState = operationalState
        self.freshness = freshness
        self.confidence = confidence
        self.routeId = routeId
        self.routeLabel = routeLabel
        self.direction = direction
        self.notice = notice
        self.services = services
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: SnapshotCodingKey.self)
        func decode<T: Decodable>(_ type: T.Type, _ key: String) throws -> T {
            try container.decode(type, forKey: SnapshotCodingKey(key))
        }

        let serviceCount = try decode(Int.self, "serviceCount")
        guard (0...snapshotMaximumServices).contains(serviceCount) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "serviceCount non valido"))
        }
        var expectedKeys = Self.baseKeys
        for index in 0..<serviceCount {
            ["Number", "Operator", "Category", "Destination", "Scheduled", "Expected", "Status", "Platform", "DelayMinutes"]
                .forEach { expectedKeys.insert("service\(index)\($0)") }
            // The optional fields of a service: the reliability pair whole or not at all, the vehicle alone.
            let pair = Self.reliabilityKeys(index).filter { container.contains(SnapshotCodingKey($0)) }
            guard pair.count != 1 else {
                throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Coppia di affidabilità incompleta"))
            }
            expectedKeys.formUnion(pair)
            if container.contains(SnapshotCodingKey(Self.vehicleKey(index))) {
                expectedKeys.insert(Self.vehicleKey(index))
            }
        }
        guard Set(container.allKeys.map(\.stringValue)) == expectedKeys else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Set di chiavi snapshot non valido"))
        }

        schemaVersion = try decode(Int.self, "schemaVersion")
        generatedAtEpochMillis = try decode(Int64.self, "generatedAtEpochMillis")
        expiresAtEpochMillis = try decode(Int64.self, "expiresAtEpochMillis")
        ttlMillis = try decode(Int64.self, "ttlMillis")
        provenanceProducer = try decode(BinarioProvenanceProducer.self, "provenanceProducer")
        provenanceDataNature = try decode(BinarioDataNature.self, "provenanceDataNature")
        provenanceSourceLabel = try decode(String.self, "provenanceSourceLabel")
        provenanceObservedAtEpochMillis = try decode(Int64.self, "provenanceObservedAtEpochMillis")
        operationalState = try decode(BinarioOperationalState.self, "operationalState")
        freshness = try decode(BinarioFreshness.self, "freshness")
        confidence = try decode(BinarioConfidence.self, "confidence")
        routeId = try decode(String.self, "routeId")
        routeLabel = try decode(String.self, "routeLabel")
        direction = try decode(BinarioDirection.self, "direction")
        let decodedNotice = try decode(String.self, "notice")
        notice = decodedNotice.isEmpty ? nil : decodedNotice
        services = try (0..<serviceCount).map { index in
            let platform = try decode(String.self, "service\(index)Platform")
            var reliability: WatchReliability?
            let reliabilityKeys = Self.reliabilityKeys(index)
            if container.contains(SnapshotCodingKey(reliabilityKeys[0])) {
                reliability = WatchReliability(
                    onTime: try decode(Int.self, reliabilityKeys[0]),
                    samples: try decode(Int.self, reliabilityKeys[1])
                )
            }
            var vehicle: WatchVehicle?
            if container.contains(SnapshotCodingKey(Self.vehicleKey(index))) {
                let raw = try decode(String.self, Self.vehicleKey(index))
                guard Self.validRequired(raw, limit: snapshotMaximumVehicleCharacters) else {
                    throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Mezzo non valido"))
                }
                vehicle = WatchVehicle(rawValue: raw) ?? .unknown
            }
            return WatchService(
                number: try decode(String.self, "service\(index)Number"),
                operatorName: try decode(String.self, "service\(index)Operator"),
                category: try decode(String.self, "service\(index)Category"),
                destination: try decode(String.self, "service\(index)Destination"),
                scheduledDeparture: try decode(String.self, "service\(index)Scheduled"),
                expectedDeparture: try decode(String.self, "service\(index)Expected"),
                platform: platform.isEmpty ? nil : platform,
                delayMinutes: try decode(Int.self, "service\(index)DelayMinutes"),
                status: try decode(String.self, "service\(index)Status"),
                reliability: reliability,
                vehicle: vehicle
            )
        }
    }

    /// `service{n}ReliabilityOnTime` and `service{n}ReliabilitySamples`, in that order.
    private static func reliabilityKeys(_ index: Int) -> [String] {
        ["service\(index)ReliabilityOnTime", "service\(index)ReliabilitySamples"]
    }

    private static func vehicleKey(_ index: Int) -> String {
        "service\(index)Vehicle"
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: SnapshotCodingKey.self)
        func encode<T: Encodable>(_ value: T, _ key: String) throws {
            try container.encode(value, forKey: SnapshotCodingKey(key))
        }
        try encode(schemaVersion, "schemaVersion")
        try encode(generatedAtEpochMillis, "generatedAtEpochMillis")
        try encode(expiresAtEpochMillis, "expiresAtEpochMillis")
        try encode(ttlMillis, "ttlMillis")
        try encode(provenanceProducer, "provenanceProducer")
        try encode(provenanceDataNature, "provenanceDataNature")
        try encode(provenanceSourceLabel, "provenanceSourceLabel")
        try encode(provenanceObservedAtEpochMillis, "provenanceObservedAtEpochMillis")
        try encode(operationalState, "operationalState")
        try encode(freshness, "freshness")
        try encode(confidence, "confidence")
        try encode(routeId, "routeId")
        try encode(routeLabel, "routeLabel")
        try encode(direction, "direction")
        try encode(notice ?? "", "notice")
        try encode(services.count, "serviceCount")
        for (index, service) in services.enumerated() {
            try encode(service.number, "service\(index)Number")
            try encode(service.operatorName, "service\(index)Operator")
            try encode(service.category, "service\(index)Category")
            try encode(service.destination, "service\(index)Destination")
            try encode(service.scheduledDeparture, "service\(index)Scheduled")
            try encode(service.expectedDeparture, "service\(index)Expected")
            try encode(service.status, "service\(index)Status")
            try encode(service.platform ?? "", "service\(index)Platform")
            try encode(service.delayMinutes, "service\(index)DelayMinutes")
            if let reliability = service.reliability {
                let keys = Self.reliabilityKeys(index)
                try encode(reliability.onTime, keys[0])
                try encode(reliability.samples, keys[1])
            }
            if let vehicle = service.vehicle {
                try encode(vehicle, Self.vehicleKey(index))
            }
        }
    }

    func isCompatible(at nowEpochMillis: Int64) -> Bool {
        guard schemaVersion == Self.currentSchemaVersion,
              services.count <= snapshotMaximumServices,
              generatedAtEpochMillis >= 0,
              provenanceObservedAtEpochMillis >= 0,
              generatedAtEpochMillis <= nowEpochMillis + snapshotMaximumFutureSkew,
              provenanceObservedAtEpochMillis <= generatedAtEpochMillis + snapshotMaximumFutureSkew,
              expiresAtEpochMillis > nowEpochMillis,
              (1...snapshotMaximumTTL).contains(ttlMillis),
              expiresAtEpochMillis - generatedAtEpochMillis == ttlMillis,
              Self.validRequired(provenanceSourceLabel, limit: 96),
              Self.validRequired(routeId, limit: 64),
              Self.validRequired(routeLabel, limit: 96),
              Self.validOptional(notice ?? "", limit: 180)
        else { return false }

        if provenanceDataNature == .unavailable && (operationalState != .unavailable || !services.isEmpty) { return false }
        if operationalState == .unavailable && !services.isEmpty { return false }
        return services.allSatisfy { service in
            Self.validRequired(service.number, limit: 24) &&
                Self.validRequired(service.operatorName, limit: 64) &&
                Self.validRequired(service.category, limit: 64) &&
                Self.validRequired(service.destination, limit: 96) &&
                Self.validRequired(service.scheduledDeparture, limit: 16) &&
                Self.validRequired(service.expectedDeparture, limit: 16) &&
                Self.validRequired(service.status, limit: 64) &&
                Self.validOptional(service.platform ?? "", limit: 16) &&
                (-720...1_440).contains(service.delayMinutes) &&
                (service.reliability?.isValid ?? true)
        }
    }

    static func decodeContract(_ data: Data, nowEpochMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)) -> BinarioWatchSnapshot? {
        guard data.count <= snapshotMaximumBytes,
              let snapshot = try? JSONDecoder().decode(BinarioWatchSnapshot.self, from: data),
              snapshot.isCompatible(at: nowEpochMillis)
        else { return nil }
        return snapshot
    }

    static var unavailable: BinarioWatchSnapshot {
        let generatedAt = Int64(Date().timeIntervalSince1970 * 1_000)
        return BinarioWatchSnapshot(
            schemaVersion: currentSchemaVersion,
            generatedAtEpochMillis: generatedAt,
            expiresAtEpochMillis: generatedAt + 15 * 60 * 1_000,
            ttlMillis: 15 * 60 * 1_000,
            provenanceProducer: .unknown,
            provenanceDataNature: .unavailable,
            provenanceSourceLabel: unavailableProviderName,
            provenanceObservedAtEpochMillis: generatedAt,
            operationalState: .unavailable,
            freshness: .unavailable,
            confidence: .cached,
            routeId: "unavailable",
            routeLabel: unavailableRouteLabel,
            direction: .unknown,
            notice: unavailableNotice,
            services: []
        )
    }

    private static func normalised(_ value: String) -> String {
        value.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    private static func validRequired(_ value: String, limit: Int) -> Bool {
        !value.isEmpty && value.count <= limit && value == normalised(value)
    }

    private static func validOptional(_ value: String, limit: Int) -> Bool {
        value.count <= limit && (value.isEmpty || value == normalised(value))
    }
}
