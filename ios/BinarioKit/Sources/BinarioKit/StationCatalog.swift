import Foundation

public struct Station: Codable, Equatable, Hashable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let railwayCode: String?
    public let trenitaliaLocationId: String?
    public let aliases: [String]
    public let latitude: Double?
    public let longitude: Double?
    public let confidence: String

    public init(
        id: String,
        name: String,
        railwayCode: String? = nil,
        trenitaliaLocationId: String? = nil,
        aliases: [String] = [],
        latitude: Double? = nil,
        longitude: Double? = nil,
        confidence: String = ""
    ) {
        self.id = id
        self.name = name
        self.railwayCode = railwayCode
        self.trenitaliaLocationId = trenitaliaLocationId
        self.aliases = aliases
        self.latitude = latitude
        self.longitude = longitude
        self.confidence = confidence
    }
}

public struct StationIdentity: Codable, Equatable, Hashable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let railwayCode: String?

    public init(id: String, name: String, railwayCode: String? = nil) {
        self.id = id
        self.name = name
        self.railwayCode = railwayCode
    }

    public init(_ station: Station) {
        self.init(id: station.id, name: station.name, railwayCode: station.railwayCode)
    }
}

public enum StationCatalogError: Error, Equatable, Sendable {
    case unsupportedSchema
    case unexpectedKind
    case containsOperationalData
    case countMismatch
    case invalidStation
    case duplicateIdentity
}

public struct CuratedStationCatalog: Decodable, Equatable, Sendable {
    public static let supportedSchemaVersion = 1
    public static let expectedKind = "curated-rail-station-catalog"

    public let schemaVersion: Int
    public let kind: String
    public let source: String
    public let sourceDatabaseName: String
    public let sourceDatabaseSha256: String
    public let stationCount: Int
    public let operationalData: Bool
    public let notice: String
    public let stations: [Station]

    public init(data: Data, decoder: JSONDecoder = JSONDecoder()) throws {
        let decoded = try decoder.decode(Self.self, from: data)
        try decoded.validate()
        self = decoded
    }

    public func station(id: String) -> Station? {
        stations.first { $0.id == id }
    }

    public func search(_ query: String, limit requestedLimit: Int = 12) -> [Station] {
        let needle = StationSearchNormalizer.normalize(query)
        guard !needle.isEmpty else { return [] }
        let limit = min(max(requestedLimit, 1), 50)

        return stations.compactMap { station -> RankedStation? in
            let name = StationSearchNormalizer.normalize(station.name)
            var best = Self.matchScore(candidate: name, needle: needle)

            for alias in station.aliases {
                if let score = Self.matchScore(
                    candidate: StationSearchNormalizer.normalize(alias),
                    needle: needle
                ) {
                    best = min(best ?? Int.max, score + 1)
                }
            }

            for code in [station.id, station.railwayCode, station.trenitaliaLocationId].compactMap({ $0 }) {
                let normalizedCode = StationSearchNormalizer.normalize(code)
                if normalizedCode == needle {
                    best = min(best ?? Int.max, 0)
                } else if normalizedCode.hasPrefix(needle) {
                    best = min(best ?? Int.max, 4)
                }
            }

            guard let score = best else { return nil }
            return RankedStation(station: station, score: score, normalizedName: name)
        }
        .sorted {
            if $0.score != $1.score { return $0.score < $1.score }
            if $0.normalizedName != $1.normalizedName { return $0.normalizedName < $1.normalizedName }
            return $0.station.id < $1.station.id
        }
        .prefix(limit)
        .map(\.station)
    }

    public func validate() throws {
        guard schemaVersion == Self.supportedSchemaVersion else { throw StationCatalogError.unsupportedSchema }
        guard kind == Self.expectedKind else { throw StationCatalogError.unexpectedKind }
        guard !operationalData else { throw StationCatalogError.containsOperationalData }
        guard stationCount == stations.count else { throw StationCatalogError.countMismatch }
        guard stations.allSatisfy({ !$0.id.isEmpty && !$0.name.isEmpty }) else { throw StationCatalogError.invalidStation }
        guard Set(stations.map(\.id)).count == stations.count else { throw StationCatalogError.duplicateIdentity }
    }

    private static func matchScore(candidate: String, needle: String) -> Int? {
        if candidate == needle { return 0 }
        if candidate.hasPrefix(needle) { return 1 }
        if candidate.split(separator: " ").contains(where: { $0.hasPrefix(needle) }) { return 2 }
        if candidate.contains(needle) { return 3 }
        return nil
    }

    private struct RankedStation {
        let station: Station
        let score: Int
        let normalizedName: String
    }
}

public enum StationCatalogLoadState: Equatable, Sendable {
    case loading
    case available(CuratedStationCatalog)
    case unavailable
}

public enum StationCatalogLoader {
    public static func load(
        data: @escaping @Sendable () throws -> Data
    ) async -> StationCatalogLoadState {
        await Task.detached(priority: .userInitiated) {
            do {
                return .available(try CuratedStationCatalog(data: data()))
            } catch {
                return .unavailable
            }
        }.value
    }
}

public enum StationSearchNormalizer: Sendable {
    public static func normalize(_ value: String) -> String {
        let folded = value.folding(
            options: [.caseInsensitive, .diacriticInsensitive],
            locale: Locale(identifier: "it_IT_POSIX")
        )
        return folded
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }
}
