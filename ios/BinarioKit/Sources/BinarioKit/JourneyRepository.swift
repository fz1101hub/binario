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
    /// GTFS bikes_allowed of the trip: true allowed, false not allowed, nil when the timetable
    /// does not say (never inferred from the category).
    public let bikesAllowed: Bool?
    /// The number is the trip's own (GTFS trip_short_name). False when the timetable publishes
    /// none and the row shows its route's short name or the trip's identifier instead («C3»,
    /// «R35»): that names a line, not a train, so it is never asked to the live sources, matched
    /// with their rows or saved in «I miei treni», where every train of the line would be one.
    public let hasTrainNumber: Bool

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
        delayMinutes: Int,
        bikesAllowed: Bool? = nil,
        hasTrainNumber: Bool = true
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
        self.bikesAllowed = bikesAllowed
        self.hasTrainNumber = hasTrainNumber
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
    /// The included timetables that serve the route end before the requested day.
    case scheduleExpired
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
