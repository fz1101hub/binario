import Foundation

/// One bike or scooter sharing system that publishes a GBFS auto-discovery file, with the evidence
/// of its terms (docs/data-sources/SOURCE_POLICY.md, «Bici in condivisione»): the shared table
/// contracts/shared-data/v1/bike-sharing-v1.json, as Android's `BikeSharingSystems`. A
/// `personalOnly` system (no licence in the feed, or one not reviewed) is used only in personal builds.
public struct BikeSharingSystem: Equatable, Sendable {
    public let id: String
    /// Name shown with its vehicles («BikeMi», «nextbike Bergamo»).
    public let name: String
    public let operatorName: String
    public let discoveryURL: String
    public let areas: [SharedData.BikeSharing.Area]
    /// The operator's own page, opened from the section («Apri BikeMi»).
    public let websiteURL: String
    public let licence: String
    /// Shown with the data («BikeMi, NLOD 2.0»): the attribution the licence asks for.
    public let attribution: String
    public let personalOnly: Bool

    /// Asked only for a station inside one of its areas.
    public func serves(latitude: Double, longitude: Double) -> Bool {
        areas.contains { GroundDistance.kilometres($0.latitude, $0.longitude, latitude, longitude) <= $0.radiusKm }
    }

    /// Every system of the table, HTTPS only.
    public static let all: [BikeSharingSystem] = SharedData.BikeSharing.systems.compactMap { system in
        guard system.discoveryUrl.hasPrefix("https://"), system.websiteUrl.hasPrefix("https://") else { return nil }
        return BikeSharingSystem(
            id: system.id, name: system.name, operatorName: system.operatorName, discoveryURL: system.discoveryUrl,
            areas: system.areas, websiteURL: system.websiteUrl, licence: system.licence, attribution: system.attribution,
            personalOnly: system.personalOnly
        )
    }

    /// The systems of a build: the personal ones only when `personalSources`.
    public static func forBuild(personalSources: Bool) -> [BikeSharingSystem] {
        all.filter { personalSources || !$0.personalOnly }
    }
}

public enum SharedVehicleKind: String, Equatable, Sendable {
    case bike = "BIKE"
    case ebike = "EBIKE"
    case scooter = "SCOOTER"
    case other = "OTHER"
}

/// A docking station within reach: what is free there now.
public struct NearbyDock: Equatable, Sendable {
    public let name: String
    public let distanceMeters: Int
    public let bikesAvailable: Int
    /// Electric bikes among `bikesAvailable`, when the system publishes vehicle types per station.
    public let ebikesAvailable: Int?
    public let docksAvailable: Int?
}

/// A free-floating vehicle within reach.
public struct NearbyVehicle: Equatable, Sendable {
    public let kind: SharedVehicleKind
    public let distanceMeters: Int
    /// Battery charge 0–100 when published (GBFS `current_fuel_percent`).
    public let batteryPercent: Int?
}

public struct BikeSharingNearby: Equatable, Sendable {
    public let system: BikeSharingSystem
    public let docks: [NearbyDock]
    public let vehicles: [NearbyVehicle]
    /// When the system's status feed was last updated (GBFS `last_updated`), else when it was read.
    public let observedAt: Date
    /// The last good answer shown after a failed refresh.
    public let stale: Bool
}

public enum BikeSharingResult: Equatable, Sendable {
    /// No system of this build serves the station's place.
    case notCovered
    case loaded([BikeSharingNearby])
    case unavailable
}

/// The parts of a GBFS system the app reads, already reduced to coordinates and counts.
struct GbfsStation: Equatable, Sendable {
    let id: String
    let name: String
    let latitude: Double
    let longitude: Double
    let bikes: Int
    let ebikes: Int?
    let docks: Int?
}

struct GbfsVehicle: Equatable, Sendable {
    let latitude: Double
    let longitude: Double
    let kind: SharedVehicleKind
    let batteryPercent: Int?
}

/// Android's `GbfsParser`: GBFS 2.x and 3.0 documents, untrusted. Every field is optional;
/// coordinates out of range, disabled or reserved vehicles, docked free-bike entries (they are
/// counted at their station) and stations that do not rent are dropped. The cases are
/// contracts/shared-rules/v1/bike-sharing-v1.json.
enum GbfsParser {
    /// {feed name: url} of gbfs.json: v3 lists `data.feeds`, v1/v2 one list per language (Italian,
    /// then English, then any).
    static func feeds(_ json: String) -> [String: String] {
        guard let data = ForeignJSON.object(json)?["data"] as? [String: Any] else { return [:] }
        var list = data["feeds"] as? [Any]
        if list == nil {
            for language in SharedData.BikeSharing.feedLanguages where list == nil {
                list = (data[language] as? [String: Any])?["feeds"] as? [Any]
            }
        }
        if list == nil {
            // Android reads the first language of the document's own order; JSONSerialization keeps
            // no order, so the languages are taken in alphabetical order.
            for key in data.keys.sorted() where list == nil {
                list = (data[key] as? [String: Any])?["feeds"] as? [Any]
            }
        }
        var feeds: [String: String] = [:]
        for feed in ForeignJSON.objects(list) {
            guard let name = ForeignJSON.text(feed["name"]), let url = ForeignJSON.text(feed["url"]) else { continue }
            feeds[name] = url
        }
        return feeds
    }

    /// vehicle_type_id → kind, from `vehicle_types` (form_factor and propulsion_type).
    static func vehicleTypes(_ json: String) -> [String: SharedVehicleKind] {
        let data = ForeignJSON.object(json)?["data"] as? [String: Any]
        var types: [String: SharedVehicleKind] = [:]
        for type in ForeignJSON.objects(data?["vehicle_types"]) {
            guard let id = ForeignJSON.text(type["vehicle_type_id"]) else { continue }
            let form = (ForeignJSON.text(type["form_factor"]) ?? "").lowercased()
            let propulsion = (ForeignJSON.text(type["propulsion_type"]) ?? "").lowercased()
            if form == "bicycle", propulsion == "electric_assist" || propulsion == "electric" {
                types[id] = .ebike
            } else if form == "bicycle" || form == "cargo_bicycle" {
                types[id] = .bike
            } else if form == "scooter" || form == "scooter_standing" {
                types[id] = .scooter
            } else {
                types[id] = .other
            }
        }
        return types
    }

    static func stations(information: String, status: String, types: [String: SharedVehicleKind]) -> [GbfsStation] {
        let statusData = ForeignJSON.object(status)?["data"] as? [String: Any]
        var states: [String: [String: Any]] = [:]
        for state in ForeignJSON.objects(statusData?["stations"]) {
            // Android's `associateBy`: the last entry of an id wins.
            states[ForeignJSON.text(state["station_id"]) ?? ""] = state
        }
        let informationData = ForeignJSON.object(information)?["data"] as? [String: Any]
        return ForeignJSON.objects(informationData?["stations"]).compactMap { station -> GbfsStation? in
            guard let id = ForeignJSON.text(station["station_id"]), let point = coordinates(station), let state = states[id] else { return nil }
            guard ForeignJSON.flag(state["is_installed"], fallback: true), ForeignJSON.flag(state["is_renting"], fallback: true) else { return nil }
            let bikes = ForeignJSON.count(state["num_bikes_available"]) ?? ForeignJSON.count(state["num_vehicles_available"]) ?? 0
            let perType = ForeignJSON.objects(state["vehicle_types_available"])
            let ebikes: Int? = perType.isEmpty || types.isEmpty ? nil : perType.reduce(0) { total, item in
                types[ForeignJSON.text(item["vehicle_type_id"]) ?? ""] == .ebike ? total + (ForeignJSON.count(item["count"]) ?? 0) : total
            }
            return GbfsStation(
                id: id, name: name(station), latitude: point.latitude, longitude: point.longitude,
                bikes: bikes, ebikes: ebikes, docks: ForeignJSON.count(state["num_docks_available"])
            )
        }
    }

    static func vehicles(_ json: String, types: [String: SharedVehicleKind]) -> [GbfsVehicle] {
        guard let data = ForeignJSON.object(json)?["data"] as? [String: Any] else { return [] }
        let list = data["bikes"] as? [Any] ?? data["vehicles"] as? [Any]
        return ForeignJSON.objects(list).compactMap { vehicle -> GbfsVehicle? in
            if ForeignJSON.flag(vehicle["is_disabled"], fallback: false) || ForeignJSON.flag(vehicle["is_reserved"], fallback: false) { return nil }
            if ForeignJSON.text(vehicle["station_id"]) != nil { return nil }
            guard let point = coordinates(vehicle) else { return nil }
            let fuel = ForeignJSON.looseNumber(vehicle["current_fuel_percent"]).flatMap { (0.0...1.0).contains($0) ? $0 : nil }
            return GbfsVehicle(
                latitude: point.latitude, longitude: point.longitude,
                kind: types[ForeignJSON.text(vehicle["vehicle_type_id"]) ?? ""] ?? .other,
                // Java's Math.round: half up.
                batteryPercent: fuel.map { Int(($0 * 100).rounded(.toNearestOrAwayFromZero)) }
            )
        }
    }

    /// `last_updated`: POSIX seconds in GBFS 2.x, an RFC 3339 timestamp in 3.0.
    static func lastUpdated(_ json: String) -> Date? {
        guard let value = ForeignJSON.object(json)?["last_updated"] else { return nil }
        if let seconds = ForeignJSON.number(value) {
            let whole = Int64(exactly: seconds.rounded(.towardZero)) ?? 0
            return whole > 0 ? Date(timeIntervalSince1970: TimeInterval(whole)) : nil
        }
        guard let text = value as? String else { return nil }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        if let date = formatter.date(from: text) { return date }
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: text) { return date }
        if let seconds = Int64(text), seconds > 0 { return Date(timeIntervalSince1970: TimeInterval(seconds)) }
        return nil
    }

    private static func coordinates(_ item: [String: Any]) -> (latitude: Double, longitude: Double)? {
        guard let latitude = ForeignJSON.looseNumber(item["lat"]), let longitude = ForeignJSON.looseNumber(item["lon"]),
              (-90.0...90.0).contains(latitude), (-180.0...180.0).contains(longitude) else { return nil }
        return (latitude, longitude)
    }

    /// GBFS 3.0 names are localized arrays ([{"text": …, "language": …}]).
    private static func name(_ item: [String: Any]) -> String {
        if let names = item["name"] as? [Any] {
            let list = ForeignJSON.objects(names)
            let chosen = list.first { (ForeignJSON.text($0["language"]) ?? "").hasPrefix("it") } ?? list.first
            return chosen.flatMap { ForeignJSON.text($0["text"]) } ?? ""
        }
        return ForeignJSON.text(item["name"]) ?? ""
    }
}

/// Android's `BikeSharingService`: shared bikes and scooters within 300 m of a station's catalogue
/// coordinates (never the device position), from the GBFS feeds of the systems serving that place.
/// Asked only when the user taps the section's button: the static documents (auto-discovery,
/// station list, vehicle types) are kept for six hours, the status for one minute. One gate per
/// operator host (an outage of one system does not pause the others), with the manners of every
/// source. Feed URLs must stay on the auto-discovery host over HTTPS.
public final class BikeSharingService: Sendable {
    public let systems: [BikeSharingSystem]
    private let client: LiveSourceClient
    private let gates: [String: LiveSourceGate]
    private let staticCache: KeyedSourceCache<String>
    private let statusCache: KeyedSourceCache<String>
    private let now: @Sendable () -> Date

    init(systems: [BikeSharingSystem], transport: any LiveHTTPTransport, pauses: LiveSourcePauses, now: @escaping @Sendable () -> Date) {
        self.systems = systems
        client = LiveSourceClient(transport: transport, now: now)
        var gates: [String: LiveSourceGate] = [:]
        for system in systems {
            guard let host = URLComponents(string: system.discoveryURL)?.host?.lowercased(), gates[host] == nil else { continue }
            gates[host] = LiveSourceGate(
                source: nil,
                configuration: .init(minimumInterval: SharedData.BikeSharing.statusTimeToLiveSeconds,
                                     maximumEntries: SharedData.BikeSharing.staticMaximumEntries),
                pauses: pauses, now: now
            )
        }
        self.gates = gates
        staticCache = KeyedSourceCache(
            timeToLive: SharedData.BikeSharing.staticTimeToLiveSeconds,
            maximumStale: SharedData.BikeSharing.staticMaximumStaleSeconds,
            maximumEntries: SharedData.BikeSharing.staticMaximumEntries
        )
        statusCache = KeyedSourceCache(
            timeToLive: SharedData.BikeSharing.statusTimeToLiveSeconds,
            maximumStale: SharedData.BikeSharing.statusMaximumStaleSeconds,
            maximumEntries: SharedData.BikeSharing.statusMaximumEntries
        )
        self.now = now
    }

    /// The systems of this build over `transport` (ephemeral `URLSession`s).
    public static func standard(
        personalSources: Bool, transport: any LiveHTTPTransport = URLSessionLiveTransport(), pauses: LiveSourcePauses = .shared,
        now: @escaping @Sendable () -> Date = { Date() }
    ) -> BikeSharingService {
        BikeSharingService(systems: BikeSharingSystem.forBuild(personalSources: personalSources), transport: transport, pauses: pauses, now: now)
    }

    public func serves(latitude: Double, longitude: Double) -> Bool {
        systems.contains { $0.serves(latitude: latitude, longitude: longitude) }
    }

    public func nearby(latitude: Double, longitude: Double) async -> BikeSharingResult {
        let serving = systems.filter { $0.serves(latitude: latitude, longitude: longitude) }
        guard !serving.isEmpty else { return .notCovered }
        let answers = await LiveTasks.boundedMap(serving, maximumConcurrent: serving.count) { system in
            await self.nearby(in: system, latitude: latitude, longitude: longitude)
        }
        let loaded = answers.compactMap { $0 }
        return loaded.isEmpty ? .unavailable : .loaded(loaded)
    }

    private struct Body: Sendable {
        let text: String
        let fetchedAt: Date
        let stale: Bool
    }

    private func nearby(in system: BikeSharingSystem, latitude: Double, longitude: Double) async -> BikeSharingNearby? {
        guard let discovery = await read(staticCache, system.discoveryURL, system: system) else { return nil }
        let feeds = GbfsParser.feeds(discovery.text)
        var types: [String: SharedVehicleKind] = [:]
        if let url = feeds["vehicle_types"], let body = await read(staticCache, url, system: system) {
            types = GbfsParser.vehicleTypes(body.text)
        }
        var stale = discovery.stale
        var observedAt: Date?
        let radius = SharedData.BikeSharing.radiusMeters
        var docks: [NearbyDock] = []
        if let informationURL = feeds["station_information"], let statusURL = feeds["station_status"],
           let information = await read(staticCache, informationURL, system: system),
           let status = await read(statusCache, statusURL, system: system) {
            stale = stale || status.stale
            observedAt = GbfsParser.lastUpdated(status.text) ?? status.fetchedAt
            docks = nearest(GbfsParser.stations(information: information.text, status: status.text, types: types),
                            latitude: latitude, longitude: longitude, radius: radius, limit: SharedData.BikeSharing.maximumDocks,
                            point: { ($0.latitude, $0.longitude) })
                .map { station, meters in
                    NearbyDock(name: station.name, distanceMeters: meters, bikesAvailable: station.bikes,
                               ebikesAvailable: station.ebikes, docksAvailable: station.docks)
                }
        }
        var vehicles: [NearbyVehicle] = []
        if let url = feeds["vehicle_status"] ?? feeds["free_bike_status"], let status = await read(statusCache, url, system: system) {
            stale = stale || status.stale
            let updated = GbfsParser.lastUpdated(status.text) ?? status.fetchedAt
            observedAt = observedAt.map { max($0, updated) } ?? updated
            vehicles = nearest(GbfsParser.vehicles(status.text, types: types),
                               latitude: latitude, longitude: longitude, radius: radius, limit: SharedData.BikeSharing.maximumVehicles,
                               point: { ($0.latitude, $0.longitude) })
                .map { vehicle, meters in NearbyVehicle(kind: vehicle.kind, distanceMeters: meters, batteryPercent: vehicle.batteryPercent) }
        }
        return BikeSharingNearby(system: system, docks: docks, vehicles: vehicles, observedAt: observedAt ?? now(), stale: stale)
    }

    /// Within `radius` metres, nearest first (a stable sort, as Kotlin's `sortedBy`), at most `limit`.
    private func nearest<Item>(
        _ items: [Item], latitude: Double, longitude: Double, radius: Int, limit: Int, point: (Item) -> (Double, Double)
    ) -> [(Item, Int)] {
        let measured = items.enumerated().compactMap { offset, item -> (Int, Item, Int)? in
            let (itemLatitude, itemLongitude) = point(item)
            let meters = Self.distance(latitude: latitude, longitude: longitude, itemLatitude, itemLongitude)
            return meters <= radius ? (offset, item, meters) : nil
        }
        return measured.sorted { $0.2 == $1.2 ? $0.0 < $1.0 : $0.2 < $1.2 }.prefix(limit).map { ($0.1, $0.2) }
    }

    static func distance(latitude: Double, longitude: Double, _ otherLatitude: Double, _ otherLongitude: Double) -> Int {
        GroundDistance.metres(latitude, longitude, otherLatitude, otherLongitude)
    }

    private func read(_ cache: KeyedSourceCache<String>, _ url: String, system: BikeSharingSystem) async -> Body? {
        guard Self.sameHost(url, discovery: system.discoveryURL), let target = URL(string: url),
              let host = target.host?.lowercased(), let gate = gates[host] else { return nil }
        let client = self.client
        let policy = LiveRequestPolicy(
            accept: "application/json",
            requestTimeout: SharedData.BikeSharing.readTimeoutSeconds,
            totalTimeout: SharedData.BikeSharing.totalTimeoutSeconds,
            maximumBytes: SharedData.BikeSharing.maximumBytes
        )
        let answer = await cache.read(url, at: now()) {
            switch await gate.fetch(url, load: { await client.get(target, policy: policy) }) {
            case let .failure(failure): return .failed(failure.unavailableReason)
            case let .body(body): return .loaded(body.text, fetchedAt: body.fetchedAt)
            }
        }
        switch answer.read {
        case let .fresh(text): return Body(text: text, fetchedAt: answer.fetchedAt ?? now(), stale: false)
        case let .stale(text): return Body(text: text, fetchedAt: answer.fetchedAt ?? now(), stale: true)
        case .failed: return nil
        }
    }

    /// A sub-feed is read only over HTTPS on the auto-discovery host, port 443, without
    /// credentials: a document cannot send the app elsewhere.
    static func sameHost(_ url: String, discovery: String) -> Bool {
        guard let target = URLComponents(string: url), let origin = URLComponents(string: discovery),
              target.scheme?.lowercased() == "https", target.user == nil, target.password == nil,
              let host = target.host, !host.isEmpty, let originHost = origin.host,
              host.lowercased() == originHost.lowercased() else { return false }
        return target.port == nil || target.port == 443
    }
}
