import Foundation

private let snapshotMaximumBytes = 8_192
private let snapshotMaximumServices = 2
private let snapshotMaximumTTL: Int64 = 365 * 24 * 60 * 60 * 1_000
private let snapshotMaximumFutureSkew: Int64 = 60_000

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
        case .outbound: return "Andata"
        case .return: return "Ritorno"
        case .unknown: return "Direzione non specificata"
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
        case .fresh: return "Dati aggiornati"
        case .aging: return "Dati in aggiornamento"
        case .stale: return "Ultimo dato disponibile"
        case .unavailable, .unknown: return "Dati non disponibili in tempo reale"
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
        case .operatorPrimary: return "Fonte primaria dell’operatore"
        case .infrastructurePrimary: return "Fonte primaria dell’infrastruttura"
        case .openData: return "Open data ufficiale"
        case .licensedAggregate: return "Fonte aggregata autorizzata"
        case .staticOnly: return "Orario programmato"
        case .cached: return "Ultimo snapshot in cache"
        case .localPlanned: return "Pianificazione locale"
        case .unknown: return "Qualità non disponibile"
        }
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

    var id: String { number + "-" + expectedDeparture }

    var accessibilitySummary: String {
        let delay = delayMinutes > 0 ? ", ritardo di \(delayMinutes) minuti" : ""
        let platformLabel = platform.map { ", binario \($0)" } ?? ", binario non disponibile"
        return "\(operatorName), \(category), treno \(number), destinazione \(destination), previsto alle \(expectedDeparture)\(delay)\(platformLabel)."
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

    var providerName: String { provenanceSourceLabel }
    var confidenceLabel: String { confidence.label }
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
            return WatchService(
                number: try decode(String.self, "service\(index)Number"),
                operatorName: try decode(String.self, "service\(index)Operator"),
                category: try decode(String.self, "service\(index)Category"),
                destination: try decode(String.self, "service\(index)Destination"),
                scheduledDeparture: try decode(String.self, "service\(index)Scheduled"),
                expectedDeparture: try decode(String.self, "service\(index)Expected"),
                platform: platform.isEmpty ? nil : platform,
                delayMinutes: try decode(Int.self, "service\(index)DelayMinutes"),
                status: try decode(String.self, "service\(index)Status")
            )
        }
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
                (-720...1_440).contains(service.delayMinutes)
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
            provenanceSourceLabel: "Nessun provider",
            provenanceObservedAtEpochMillis: generatedAt,
            operationalState: .unavailable,
            freshness: .unavailable,
            confidence: .cached,
            routeId: "unavailable",
            routeLabel: "Nessuna tratta attiva",
            direction: .unknown,
            notice: "dati non disponibili in tempo reale",
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
