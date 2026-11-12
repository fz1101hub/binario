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

extension Station {
    /// The station with its name and aliases on single spaces, without spaces at either end.
    var whitespaceNormalized: Station {
        Station(
            id: id,
            name: Self.singleSpaced(name),
            railwayCode: railwayCode,
            trenitaliaLocationId: trenitaliaLocationId,
            aliases: aliases.map { Self.singleSpaced($0) },
            latitude: latitude,
            longitude: longitude,
            confidence: confidence
        )
    }

    static func singleSpaced(_ value: String) -> String {
        value.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
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
    public private(set) var stations: [Station]
    /// The search keys of `stations`, in the same order, folded once when the catalogue is read
    /// instead of on every keystroke of the station search.
    private var searchKeys: [StationSearchKeys] = []

    private enum CodingKeys: String, CodingKey {
        case schemaVersion, kind, source, sourceDatabaseName, sourceDatabaseSha256
        case stationCount, operationalData, notice, stations
    }

    public init(data: Data, decoder: JSONDecoder = JSONDecoder()) throws {
        var decoded = try decoder.decode(Self.self, from: data)
        // Every query refuses a name that is not on single spaces (`JourneyModelValidator`): a
        // slip of the catalogue («Ercolano -  Miglio D'Oro») must not make a station unusable.
        decoded.stations = decoded.stations.map(\.whitespaceNormalized)
        try decoded.validate()
        decoded.searchKeys = StationSearchKeys.of(decoded.stations)
        self = decoded
    }

    public func station(id: String) -> Station? {
        stations.first { $0.id == id }
    }

    public func search(_ query: String, limit requestedLimit: Int = 12) -> [Station] {
        let needle = StationSearchNormalizer.normalize(query)
        guard !needle.isEmpty else { return [] }
        let limit = min(max(requestedLimit, 1), 50)
        // A catalogue decoded without `init(data:)` has no keys yet: they are folded here instead.
        let keys = searchKeys.count == stations.count ? searchKeys : StationSearchKeys.of(stations)

        let ranked = stations.indices.compactMap { index -> RankedStation? in
            let station = stations[index]
            let key = keys[index]
            // Shadowed co-located duplicates are never offered, whatever the query.
            guard !key.isShadowedDuplicate else { return nil }
            var best = Self.matchScore(candidate: key.name, needle: needle)

            for alias in key.aliases {
                if let score = Self.matchScore(candidate: alias, needle: needle) {
                    best = min(best ?? Int.max, score + 1)
                }
            }

            for normalizedCode in key.codes {
                if normalizedCode == needle {
                    best = min(best ?? Int.max, 0)
                } else if normalizedCode.hasPrefix(needle) {
                    best = min(best ?? Int.max, 4)
                }
            }

            guard let score = best else { return nil }
            return RankedStation(station: station, score: score, normalizedName: key.name)
        }
        return ranked
            .sorted {
                if $0.score != $1.score { return $0.score < $1.score }
                if $0.normalizedName != $1.normalizedName { return $0.normalizedName < $1.normalizedName }
                return $0.station.id < $1.station.id
            }
            .prefix(limit)
            .map(\.station)
    }

    /// Every station the search can offer, in catalogue order: the co-located duplicate entries it
    /// hides (`isShadowedDuplicate`) are left out, so dictation and the nearest station pick the
    /// entry the timetable index binds, as the search does.
    public var offeredStations: [Station] {
        let byName = Dictionary(grouping: stations) { StationSearchNormalizer.normalize($0.name) }
        return stations.filter { station in
            !Self.isShadowedDuplicate(station, among: byName[StationSearchNormalizer.normalize(station.name)] ?? [])
        }
    }

    /// True when the search also offers another station with exactly this name, so a label made of
    /// station names alone would not tell the two apart. Co-located duplicate entries of one station
    /// are hidden by the search (isShadowedDuplicate) and are not namesakes.
    public func hasNamesake(_ identity: StationIdentity) -> Bool {
        let sameName = stations.filter { $0.name == identity.name }
        return sameName.contains { station in
            station.id != identity.id && !Self.isShadowedDuplicate(station, among: sameName)
        }
    }

    /// Some stations have several catalogue entries (an RFI and a Ferrovienord code for Varese
    /// Nord). The timetable index binds them all to the preferred entry, so only that one is
    /// offered: choosing another would find no trains. Mirrors CO_LOCATED_DUPLICATE_KM and
    /// identity_preference in tools/build_ios_gtfs_schedule.py.
    static func isShadowedDuplicate(_ station: Station, among sameName: [Station]) -> Bool {
        guard let latitude = station.latitude, let longitude = station.longitude else { return false }
        return sameName.contains { other in
            guard other.id != station.id,
                  other.railwayCode != nil || other.trenitaliaLocationId != nil,
                  let otherLatitude = other.latitude, let otherLongitude = other.longitude,
                  Self.preferenceKey(other) < Self.preferenceKey(station) else { return false }
            return distanceKilometres(latitude, longitude, otherLatitude, otherLongitude) <= 1.0
        }
    }

    private static func preferenceKey(_ station: Station) -> (Int, Int, String) {
        (station.trenitaliaLocationId == nil ? 1 : 0, station.railwayCode == nil ? 1 : 0, station.id)
    }

    private static func distanceKilometres(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let radians = Double.pi / 180
        let latitudeDelta = (lat2 - lat1) * radians
        let longitudeDelta = (lon2 - lon1) * radians
        let haversine = sin(latitudeDelta / 2) * sin(latitudeDelta / 2)
            + cos(lat1 * radians) * cos(lat2 * radians) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2 * 6_371.0 * asin(min(1, haversine.squareRoot()))
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

/// What the station search compares a query with, for one station: its name, aliases and codes
/// as `StationSearchNormalizer` folds them, and whether the search hides it as a co-located
/// duplicate. None of this depends on the query, so it is computed once per catalogue.
struct StationSearchKeys: Equatable, Sendable {
    let name: String
    let aliases: [String]
    let codes: [String]
    let isShadowedDuplicate: Bool

    static func of(_ stations: [Station]) -> [StationSearchKeys] {
        let names = stations.map { StationSearchNormalizer.normalize($0.name) }
        var stationsByName: [String: [Station]] = [:]
        for (station, name) in zip(stations, names) {
            stationsByName[name, default: []].append(station)
        }
        return stations.indices.map { index -> StationSearchKeys in
            let station = stations[index]
            let name = names[index]
            return StationSearchKeys(
                name: name,
                aliases: station.aliases.map { StationSearchNormalizer.normalize($0) },
                codes: [station.id, station.railwayCode, station.trenitaliaLocationId]
                    .compactMap { $0 }
                    .map { StationSearchNormalizer.normalize($0) },
                isShadowedDuplicate: CuratedStationCatalog.isShadowedDuplicate(
                    station,
                    among: stationsByName[name] ?? []
                )
            )
        }
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
