import Foundation

public enum JourneyDirection: String, Codable, Equatable, CaseIterable, Sendable {
    case outbound = "OUTBOUND"
    case `return` = "RETURN"
    case unknown = "UNKNOWN"
}

public enum JourneyValidationError: Error, Equatable, Sendable {
    case missingIdentity
    case missingName
    case sameStation
    case invalidIdentifier
    case invalidName
    case tooManySavedRoutes
    case duplicateSavedRoute
}

private enum JourneyModelValidator {
    static func text(
        _ value: String,
        maximum: Int,
        error: JourneyValidationError
    ) throws {
        let normalized = value.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        guard !normalized.isEmpty, normalized.count <= maximum, normalized == value else { throw error }
    }

    static func identity(_ value: StationIdentity) throws {
        guard !value.id.isEmpty else { throw JourneyValidationError.missingIdentity }
        guard !value.name.isEmpty else { throw JourneyValidationError.missingName }
        try text(value.id, maximum: 128, error: .invalidIdentifier)
        try text(value.name, maximum: 128, error: .invalidName)
        if let railwayCode = value.railwayCode {
            try text(railwayCode, maximum: 64, error: .invalidIdentifier)
        }
    }
}

public struct JourneyQuery: Codable, Equatable, Sendable {
    public let origin: StationIdentity
    public let destination: StationIdentity
    public let departure: Date
    public let direction: JourneyDirection

    public init(
        origin: StationIdentity,
        destination: StationIdentity,
        departure: Date,
        direction: JourneyDirection = .outbound
    ) throws {
        try Self.validate(origin: origin, destination: destination)
        self.origin = origin
        self.destination = destination
        self.departure = departure
        self.direction = direction
    }

    private static func validate(origin: StationIdentity, destination: StationIdentity) throws {
        try JourneyModelValidator.identity(origin)
        try JourneyModelValidator.identity(destination)
        guard origin.id != destination.id else { throw JourneyValidationError.sameStation }
    }
}

public struct SavedRoute: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let origin: StationIdentity
    public let destination: StationIdentity
    public let createdAt: Date

    public init(
        id: String,
        name: String,
        origin: StationIdentity,
        destination: StationIdentity,
        createdAt: Date = Date()
    ) throws {
        try JourneyModelValidator.text(id, maximum: 128, error: .invalidIdentifier)
        try JourneyModelValidator.text(name, maximum: 128, error: .invalidName)
        try JourneyModelValidator.identity(origin)
        try JourneyModelValidator.identity(destination)
        guard origin.id != destination.id else { throw JourneyValidationError.sameStation }

        self.id = id
        self.name = name
        self.origin = origin
        self.destination = destination
        self.createdAt = createdAt
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(
            id: container.decode(String.self, forKey: .id),
            name: container.decode(String.self, forKey: .name),
            origin: container.decode(StationIdentity.self, forKey: .origin),
            destination: container.decode(StationIdentity.self, forKey: .destination),
            createdAt: container.decode(Date.self, forKey: .createdAt)
        )
    }

    public static func routeIdentifier(origin: StationIdentity, destination: StationIdentity) -> String {
        "\(origin.id)--\(destination.id)"
    }
}

public struct SavedRouteCollection: Codable, Equatable, Sendable {
    public static let maximumRoutes = 32
    public let routes: [SavedRoute]

    public init(validating routes: [SavedRoute]) throws {
        guard routes.count <= Self.maximumRoutes else { throw JourneyValidationError.tooManySavedRoutes }
        guard Set(routes.map(\.id)).count == routes.count else { throw JourneyValidationError.duplicateSavedRoute }
        self.routes = routes
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(validating: container.decode([SavedRoute].self, forKey: .routes))
    }
}
