import Foundation

/// The badge of a city stop in the station search, as on Android (`UrbanStopBadge`): «M» metro,
/// «T» tram, «F» funicular or lift. contracts/shared-rules/v1/urban-stops-v1.json holds the cases.
public enum UrbanStopBadge: String, Codable, Equatable, Sendable, CaseIterable {
    case metro = "M"
    case tram = "T"
    case funicular = "F"

    /// From the modes the index lists for a stop («MT»: metro and tram, `urban_stop.modes`, the
    /// pack's binario_stop_modes.txt); metro first. Nil without one: the search does not offer it.
    public static func of(modes: String) -> UrbanStopBadge? {
        if modes.contains("M") { return .metro }
        if modes.contains("T") { return .tram }
        if modes.contains("F") { return .funicular }
        return nil
    }

    /// The localization key of what the badge says (Android's `station_badge_*`).
    public var descriptionKey: String {
        switch self {
        case .metro: return "planner.station.badge.metro"
        case .tram: return "planner.station.badge.tram"
        case .funicular: return "planner.station.badge.funicular"
        }
    }

    /// Metro before tram before funicular, when two stops share a name.
    fileprivate var rank: Int {
        switch self {
        case .metro: return 0
        case .tram: return 1
        case .funicular: return 2
        }
    }
}

/// A metro, tram or funicular stop of a city network as the station search offers it: a `Station`
/// whose id is `gtfs:<pack>:<stop>`, never a catalogue station.
public struct UrbanStop: Equatable, Identifiable, Sendable {
    public let station: Station
    public let badge: UrbanStopBadge
    public let sourceID: String

    public var id: String { station.id }

    public init(station: Station, badge: UrbanStopBadge, sourceID: String) {
        self.station = station
        self.badge = badge
        self.sourceID = sourceID
    }
}

/// A stop of a city network as the index lists it (`urban_stop` with its `stop` row), in the order
/// of its pack's stops.txt.
public struct UrbanStopRow: Equatable, Sendable {
    public let sourceID: String
    public let externalID: String
    public let name: String
    public let latitude: Double?
    public let longitude: Double?
    public let modes: String

    public init(sourceID: String, externalID: String, name: String, latitude: Double?, longitude: Double?, modes: String) {
        self.sourceID = sourceID
        self.externalID = externalID
        self.name = name
        self.latitude = latitude
        self.longitude = longitude
        self.modes = modes
    }
}

/// The stops of the city networks (contracts/shared-data/v1/pack-roles-v1.json, `urbanPacks`), as
/// Android names and finds them.
public enum UrbanStops {
    /// The city packs: their stops are never catalogue stations.
    public static let packs: Set<String> = SharedData.PackRoles.urbanPacks

    /// Stops of one name at most this far from a stop picked in the search are its platforms
    /// (Android's `UrbanStopPlatforms.SIBLING_METERS`; contracts/shared-data/v1/urban-stops-v1.json).
    static let platformMeters = Double(SharedData.UrbanStops.platformMeters)

    /// Android's id of a city stop: `gtfs:<pack>:<stop>`.
    public static func stationID(sourceID: String, stopID: String) -> String {
        "gtfs:\(sourceID):\(stopID)"
    }

    /// The pack and the stop of a city stop's id; nil for any other id (a catalogue station, the
    /// stop of a railway pack that a board names as a terminus).
    public static func parse(_ id: String) -> (sourceID: String, stopID: String)? {
        let prefix = "gtfs:"
        guard id.hasPrefix(prefix) else { return nil }
        let rest = id.dropFirst(prefix.count)
        guard let colon = rest.firstIndex(of: ":") else { return nil }
        let sourceID = String(rest[..<colon])
        let stopID = String(rest[rest.index(after: colon)...])
        guard packs.contains(sourceID), !stopID.isEmpty else { return nil }
        return (sourceID, stopID)
    }

    /// True for the id of a city stop, whatever the build: never asked to a railway live source.
    public static func isStopID(_ id: String) -> Bool {
        parse(id) != nil
    }

    /// The platforms of the stop `chosen` among the stops of its pack (Android's
    /// `UrbanStopPlatforms.of`): itself and the stops with the same name, as the search folds it,
    /// at most `platformMeters` from it. The positions in `rows`.
    static func platforms(of chosen: Int, among rows: [UrbanStopRow]) -> [Int] {
        guard rows.indices.contains(chosen) else { return [] }
        let stop = rows[chosen]
        let key = StationSearchNormalizer.normalize(stop.name)
        return rows.indices.filter { index in
            if index == chosen { return true }
            let other = rows[index]
            guard other.sourceID == stop.sourceID, StationSearchNormalizer.normalize(other.name) == key,
                  let latitude = stop.latitude, let longitude = stop.longitude,
                  let otherLatitude = other.latitude, let otherLongitude = other.longitude
            else { return false }
            return distanceMeters(latitude, longitude, otherLatitude, otherLongitude) <= platformMeters
        }
    }

    static func distanceMeters(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let radians = Double.pi / 180
        let phi1 = lat1 * radians
        let phi2 = lat2 * radians
        let halfPhi = sin((phi2 - phi1) / 2)
        let halfLambda = sin((lon2 - lon1) * radians / 2)
        let a = halfPhi * halfPhi + cos(phi1) * cos(phi2) * (halfLambda * halfLambda)
        return 2 * 6_371.0 * asin(min(1, max(0, a).squareRoot())) * 1_000
    }
}

/// The city stops the station search offers (Android's `EmbeddedUrbanStopIndex`): one entry per
/// name and pack, the first stop of that name in the pack's order, a metro stop before a tram or
/// funicular stop of the same name; only stops a metro, tram or funicular serves. Built once from
/// the index (`SQLiteGTFSJourneyRepository.urbanStopIndex`); never touches the network.
public struct UrbanStopIndex: Equatable, Sendable {
    public let stops: [UrbanStop]
    private let keys: [String]
    private let badges: [String: UrbanStopBadge]

    public static let empty = UrbanStopIndex(stops: [])

    public init(stops: [UrbanStop]) {
        self.stops = stops
        keys = stops.map { StationSearchNormalizer.normalize($0.station.name) }
        var badges: [String: UrbanStopBadge] = [:]
        for stop in stops { badges[stop.id] = stop.badge }
        self.badges = badges
    }

    /// The offered stops of `rows` (in their packs' order): one per name and pack, a stop without
    /// a badge, a usable name or a usable identifier left out.
    public init(rows: [UrbanStopRow]) {
        var chosen: [UrbanStop] = []
        var position: [String: Int] = [:]
        for row in rows {
            guard let badge = UrbanStopBadge.of(modes: row.modes) else { continue }
            let name = row.name.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
            let id = UrbanStops.stationID(sourceID: row.sourceID, stopID: row.externalID)
            // The planner refuses an identity it cannot validate (JourneyModelValidator): never offer one.
            guard !name.isEmpty, name.count <= 128, id.count <= 128,
                  id.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ") == id
            else { continue }
            let key = row.sourceID + "|" + StationSearchNormalizer.normalize(name)
            let stop = UrbanStop(
                station: Station(id: id, name: name, latitude: row.latitude, longitude: row.longitude),
                badge: badge,
                sourceID: row.sourceID
            )
            if let index = position[key] {
                // Metro wins over tram when two stops share a name ("Duomo" M1/M3 and the tram stop).
                if stop.badge.rank < chosen[index].badge.rank { chosen[index] = stop }
            } else {
                position[key] = chosen.count
                chosen.append(stop)
            }
        }
        self.init(stops: chosen)
    }

    /// The badge of a stop this index offers, by its id.
    public func badge(of stationID: String) -> UrbanStopBadge? {
        badges[stationID]
    }

    /// Stops whose name is the query, starts with it, contains it or contains every word of it, in
    /// that order and then by name; at most `limit`, none for a query shorter than two characters.
    public func search(_ query: String, limit: Int = 3) -> [UrbanStop] {
        let needle = StationSearchNormalizer.normalize(query)
        guard needle.count >= 2, limit > 0 else { return [] }
        let words = needle.split(separator: " ").map(String.init)
        var ranked: [(rank: Int, key: String, index: Int)] = []
        for (index, key) in keys.enumerated() {
            let rank: Int
            if key == needle {
                rank = 0
            } else if key.hasPrefix(needle) {
                rank = 1
            } else if key.contains(needle) {
                rank = 2
            } else if words.allSatisfy({ key.contains($0) }) {
                rank = 3
            } else {
                continue
            }
            ranked.append((rank, key, index))
        }
        ranked.sort { first, second in
            if first.rank != second.rank { return first.rank < second.rank }
            if first.key != second.key { return first.key < second.key }
            return first.index < second.index
        }
        return ranked.prefix(limit).map { stops[$0.index] }
    }

    /// The suggestions of the station search with the city stops (Android's `withUrbanStops`): the
    /// stations whose name is the query, then the city stops, then the other stations.
    public static func merged(stations: [Station], urban: [UrbanStop], query: String) -> [Station] {
        guard !urban.isEmpty else { return stations }
        let needle = StationSearchNormalizer.normalize(query)
        let exact = stations.filter { StationSearchNormalizer.normalize($0.name) == needle }
        let others = stations.filter { StationSearchNormalizer.normalize($0.name) != needle }
        var seen = Set<String>()
        return (exact + urban.map(\.station) + others).filter { seen.insert($0.id).inserted }
    }
}
