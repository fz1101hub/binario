import Foundation

/// What a local backup carries on the iPhone: the saved routes, the favourite stations, «I miei
/// treni» with their names, the personal punctuality history and «Orari salvati». The history and
/// the timetables are nil when the file has none (an older file): an import then keeps this
/// iPhone's own.
public struct LocalBackup: Equatable, Sendable {
    public let routes: [SavedRoute]
    public let favouriteStations: [StationIdentity]
    public let favouriteTrains: [FavouriteTrain]
    /// Names given to favourite trains, by train id.
    public let trainNames: [String: String]
    public let punctualityHistory: [PunctualityObservation]?
    public let savedTimetables: [SavedTimetable]?

    public init(
        routes: [SavedRoute],
        favouriteStations: [StationIdentity],
        favouriteTrains: [FavouriteTrain],
        trainNames: [String: String],
        punctualityHistory: [PunctualityObservation]?,
        savedTimetables: [SavedTimetable]?
    ) {
        self.routes = routes
        self.favouriteStations = favouriteStations
        self.favouriteTrains = favouriteTrains
        self.trainNames = trainNames
        self.punctualityHistory = punctualityHistory
        self.savedTimetables = savedTimetables
    }

    /// Anything besides the routes: a file with none of it and no route is not written.
    public var carriesMoreThanRoutes: Bool {
        !favouriteStations.isEmpty || !favouriteTrains.isEmpty || !(punctualityHistory ?? []).isEmpty || !(savedTimetables ?? []).isEmpty
    }
}

public enum LocalBackupFailure: Error, Equatable, Sendable {
    case tooLarge
    /// Not a Binario backup, a version this app does not read, or routes none of which is valid.
    case unreadable
    /// Nothing to export, or a file that carries nothing to import.
    case nothing
}

/// Android's local backup file (`LocalDataBackupCodec`, `binario-local-backup`, schema 2), written
/// and read by the iPhone so a backup moves between the two phones. Kind, version and bounds are
/// `SharedData.LocalBackup`; the saved timetables and the punctuality history keep Android's JSON.
/// Keys the iPhone does not keep (station monitors, commuter alerts, preferences) are written empty
/// or left out, and ignored on import. A favourite train adds the iPhone's own optional keys
/// (`originId`, `destinationId`, `operator`, `category`, `terminus`) to Android's entry; an entry
/// without them, written by Android, cannot make an iPhone favourite and is skipped.
public enum LocalBackupCodec {
    private typealias Table = SharedData.LocalBackup

    public static func encode(_ backup: LocalBackup, station: (StationIdentity) -> Station) -> Result<Data, LocalBackupFailure> {
        guard !backup.routes.isEmpty || backup.carriesMoreThanRoutes else { return .failure(.nothing) }
        var root: [String: Any] = [
            "schemaVersion": Table.schemaVersion,
            "kind": Table.kind,
            "routes": backup.routes.map { route -> [String: Any] in
                ["id": route.id, "name": route.name, "originId": route.origin.id, "destinationId": route.destination.id, "pinned": false]
            },
            "favouriteStationIds": backup.favouriteStations.map(\.id).sorted(),
            "monitoredServices": backup.favouriteTrains.map { train -> [String: Any] in
                var value: [String: Any] = [
                    "number": train.number,
                    "label": "\(train.category) \(train.number)",
                    "origin": train.origin.name,
                    "scheduledDeparture": train.scheduledDeparture,
                    "originId": train.origin.id,
                    "destinationId": train.destination.id,
                    "operator": train.operatorName,
                    "category": train.category,
                    "terminus": train.terminus,
                ]
                if let name = backup.trainNames[train.id] { value["customName"] = name }
                return value
            },
            "stationMonitors": [Any](),
            "commuterAlerts": [Any](),
        ]
        var identities: [StationIdentity] = []
        var seen = Set<String>()
        let used = backup.routes.flatMap { [$0.origin, $0.destination] } + backup.favouriteStations
            + backup.favouriteTrains.flatMap { [$0.origin, $0.destination] }
        for identity in used where seen.insert(identity.id).inserted { identities.append(identity) }
        root["stations"] = identities.prefix(Table.maximumStations).map { stationObject(station($0)) }
        if let history = backup.punctualityHistory { root["punctualityHistory"] = PunctualityHistory.jsonObjects(history) }
        if let timetables = backup.savedTimetables { root["savedTimetables"] = SavedTimetableCodec.jsonObjects(timetables) }
        guard let data = try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys]) else {
            return .failure(.unreadable)
        }
        guard data.count <= Table.maximumBytes else { return .failure(.tooLarge) }
        return .success(data)
    }

    /// `catalogue` gives the catalogue station of an id; stations outside it come from the file.
    public static func decode(_ data: Data, catalogue: (String) -> Station?) -> Result<LocalBackup, LocalBackupFailure> {
        guard data.count <= Table.maximumBytes else { return .failure(.tooLarge) }
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              root["kind"] as? String == Table.kind,
              let version = PunctualityHistory.jsonInt(root["schemaVersion"]), (1...Table.schemaVersion).contains(version)
        else { return .failure(.unreadable) }
        var embedded: [String: StationIdentity] = [:]
        for item in (root["stations"] as? [Any] ?? []).prefix(Table.maximumStations) {
            guard let value = item as? [String: Any], let id = text(value["id"]), let name = text(value["name"]) else { continue }
            if embedded[id] == nil { embedded[id] = StationIdentity(id: id, name: name, railwayCode: text(value["railwayCode"])) }
        }
        func identity(_ id: Any?) -> StationIdentity? {
            guard let id = id as? String, !id.isEmpty else { return nil }
            if let station = catalogue(id) { return StationIdentity(station) }
            return embedded[id]
        }
        let listed = root["routes"] as? [Any] ?? []
        var routes: [SavedRoute] = []
        var routeIDs = Set<String>()
        for item in listed {
            guard let value = item as? [String: Any],
                  let origin = identity(value["originId"]), let destination = identity(value["destinationId"]),
                  let name = text(value["name"]) else { continue }
            let id = SavedRoute.routeIdentifier(origin: origin, destination: destination)
            guard routeIDs.insert(id).inserted,
                  let route = try? SavedRoute(id: id, name: String(name.prefix(128)), origin: origin, destination: destination) else { continue }
            routes.append(route)
            if routes.count == min(Table.maximumRoutes, SavedRouteCollection.maximumRoutes) { break }
        }
        // A file that lists routes of which none is valid stays refused, as on Android.
        guard !routes.isEmpty || listed.isEmpty else { return .failure(.unreadable) }
        var favouriteStations: [StationIdentity] = []
        for id in root["favouriteStationIds"] as? [Any] ?? [] {
            if let station = identity(id), !favouriteStations.contains(where: { $0.id == station.id }) { favouriteStations.append(station) }
        }
        var trains: [FavouriteTrain] = []
        var names: [String: String] = [:]
        for item in root["monitoredServices"] as? [Any] ?? [] {
            guard let value = item as? [String: Any],
                  let number = text(value["number"]),
                  let origin = identity(value["originId"]), let destination = identity(value["destinationId"]),
                  let operatorName = text(value["operator"]), let category = text(value["category"]),
                  let terminus = text(value["terminus"]), let departure = text(value["scheduledDeparture"]),
                  let train = try? FavouriteTrain(
                      number: number, operatorName: operatorName, category: category, terminus: terminus,
                      scheduledDeparture: departure, bikesAllowed: nil, origin: origin, destination: destination, savedAt: Date()
                  ) else { continue }
            trains.append(train)
            if let name = text(value["customName"]) { names[train.id] = name }
        }
        let history = (root["punctualityHistory"] as? [Any]).map { PunctualityHistory.observations(fromJSON: $0) }
        let timetables = (root["savedTimetables"] as? [Any]).map { SavedTimetableCodec.timetables(fromJSON: $0) }
        let backup = LocalBackup(
            routes: routes,
            favouriteStations: favouriteStations,
            favouriteTrains: trains,
            trainNames: names,
            punctualityHistory: history,
            savedTimetables: timetables
        )
        guard !routes.isEmpty || backup.carriesMoreThanRoutes else { return .failure(.nothing) }
        return .success(backup)
    }

    private static func stationObject(_ station: Station) -> [String: Any] {
        var value: [String: Any] = ["id": station.id, "name": station.name, "city": station.name]
        if let code = station.railwayCode { value["railwayCode"] = code }
        if let location = station.trenitaliaLocationId { value["trenitaliaLocationId"] = location }
        if !station.aliases.isEmpty { value["aliases"] = Array(station.aliases.prefix(Table.maximumStationAliases)) }
        if let latitude = station.latitude { value["latitude"] = latitude }
        if let longitude = station.longitude { value["longitude"] = longitude }
        return value
    }

    private static func text(_ raw: Any?) -> String? {
        guard let value = raw as? String else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= Table.maximumStationText else { return nil }
        return trimmed
    }
}
