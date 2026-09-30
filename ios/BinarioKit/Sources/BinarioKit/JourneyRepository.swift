import Foundation

public enum DataProducer: String, Codable, Equatable, Sendable {
    case androidApp = "ANDROID_APP"
    case companion = "COMPANION"
    case iosApp = "IOS_APP"
    case unknown = "UNKNOWN"
}

public enum DataNature: String, Codable, Equatable, Sendable {
    case programmed = "PROGRAMMED"
    case operational = "OPERATIONAL"
    case unavailable = "UNAVAILABLE"
    case unknown = "UNKNOWN"
}

public enum OperationalState: String, Codable, Equatable, Sendable {
    case programmed = "PROGRAMMED"
    case operational = "OPERATIONAL"
    case unavailable = "UNAVAILABLE"
    case unknown = "UNKNOWN"
}

public enum DataFreshness: String, Codable, Equatable, Sendable {
    case fresh = "FRESH"
    case aging = "AGING"
    case stale = "STALE"
    case unavailable = "UNAVAILABLE"
    case unknown = "UNKNOWN"
}

public enum DataConfidence: String, Codable, Equatable, Sendable {
    case operatorPrimary = "OPERATOR_PRIMARY"
    case infrastructurePrimary = "INFRASTRUCTURE_PRIMARY"
    case openData = "OPEN_DATA"
    case licensedAggregate = "LICENSED_AGGREGATE"
    case staticOnly = "STATIC_ONLY"
    case cached = "CACHED"
    case localPlanned = "LOCAL_PLANNED"
    case unknown = "UNKNOWN"
}

public struct DataProvenance: Codable, Equatable, Sendable {
    public let producer: DataProducer
    public let nature: DataNature
    public let sourceLabel: String
    public let observedAt: Date
    public let freshness: DataFreshness
    public let confidence: DataConfidence

    public init(
        producer: DataProducer,
        nature: DataNature,
        sourceLabel: String,
        observedAt: Date,
        freshness: DataFreshness,
        confidence: DataConfidence
    ) {
        self.producer = producer
        self.nature = nature
        self.sourceLabel = sourceLabel
        self.observedAt = observedAt
        self.freshness = freshness
        self.confidence = confidence
    }
}

public struct TrainService: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let number: String
    public let operatorName: String
    public let category: String
    public let destination: String
    public let scheduledDeparture: String
    public let expectedDeparture: String
    public let status: String
    public let platform: String?
    public let delayMinutes: Int

    public init(
        id: String,
        number: String,
        operatorName: String,
        category: String,
        destination: String,
        scheduledDeparture: String,
        expectedDeparture: String,
        status: String,
        platform: String?,
        delayMinutes: Int
    ) {
        self.id = id
        self.number = number
        self.operatorName = operatorName
        self.category = category
        self.destination = destination
        self.scheduledDeparture = scheduledDeparture
        self.expectedDeparture = expectedDeparture
        self.status = status
        self.platform = platform
        self.delayMinutes = delayMinutes
    }
}

public struct JourneySnapshot: Codable, Equatable, Sendable {
    public let query: JourneyQuery
    public let generatedAt: Date
    public let expiresAt: Date
    public let operationalState: OperationalState
    public let provenance: DataProvenance
    public let services: [TrainService]

    public init(
        query: JourneyQuery,
        generatedAt: Date,
        expiresAt: Date,
        operationalState: OperationalState,
        provenance: DataProvenance,
        services: [TrainService]
    ) {
        self.query = query
        self.generatedAt = generatedAt
        self.expiresAt = expiresAt
        self.operationalState = operationalState
        self.provenance = provenance
        self.services = services
    }
}

public enum JourneyUnavailableReason: String, Codable, Equatable, Sendable {
    case noJourney
    case noAuthorizedProvider
    case providerInvalid
}

public enum JourneyResult: Equatable, Sendable {
    case available(JourneySnapshot)
    case unavailable(JourneyUnavailableReason)
}

public protocol JourneyRepository: Sendable {
    func journeys(for query: JourneyQuery) async -> JourneyResult
}

/// Safe fallback used whenever an authorised provider cannot be installed or validated.
/// It never synthesizes trains, times, delays, platforms, or status.
public struct FailClosedJourneyRepository: JourneyRepository {
    public init() {}

    public func journeys(for query: JourneyQuery) async -> JourneyResult {
        .unavailable(.noAuthorizedProvider)
    }
}
