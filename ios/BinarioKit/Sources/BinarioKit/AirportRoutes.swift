import Foundation

/// A ready route between a city station and an airport station of the curated catalogue, served
/// by the trains of at least one of `packs` (for Bologna, the Marconi Express people mover of a
/// city pack).
public struct AirportRoute: Equatable, Hashable, Sendable {
    public let cityStationID: String
    public let airportStationID: String
    public let packs: Set<String>

    public init(cityStationID: String, airportStationID: String, packs: Set<String>) {
        self.cityStationID = cityStationID
        self.airportStationID = airportStationID
        self.packs = packs
    }
}

/// The route with its catalogue stations, as the planner lists it.
public struct AirportLink: Equatable, Identifiable, Sendable {
    public let route: AirportRoute
    public let city: Station
    public let airport: Station

    public var id: String { route.cityStationID + "--" + route.airportStationID }
}

/// «Collegamenti con gli aeroporti» of the planner: the same list as the Android app
/// (app/src/main/java/it/binario/app/data/AirportRoutes.kt). An entry shows only when one of its
/// packs contributes trips to the bundled iOS index and both stations are in the catalogue and
/// bound to stops of that index, so the pairs served only by packs that the iPhone app does not
/// carry (Trenitalia, Ferrotramviaria, the Marconi Express city pack) never appear. Station names are the catalogue's; the
/// timetables carry no service brand ("Malpensa Express"), so none is shown.
public enum AirportRoutes {
    /// The shared table contracts/shared-data/v1/airport-routes-v1.json (`SharedData.AirportRoutes`).
    public static let all: [AirportRoute] = SharedData.AirportRoutes.routes.map { route in
        AirportRoute(cityStationID: route.city, airportStationID: route.airport, packs: route.packs)
    }

    /// Packs whose airport line is a city line (the Marconi Express people mover), not a train:
    /// flagged in the table, not inferred from the asset directory.
    static let cityLinePacks: Set<String> = Set(SharedData.AirportRoutes.packs.filter(\.cityLine).map(\.id))

    /// Whether `route` is served only by a city line (a people mover, a metro), so its rows do not
    /// say "trains" (Android's `byCityLine`). No pack the iPhone carries is a city line yet.
    public static func byCityLine(_ route: AirportRoute) -> Bool {
        !route.packs.isEmpty && route.packs.isSubset(of: cityLinePacks)
    }

    /// The routes of `routes` that the bundled index can answer: a pack with trips among
    /// `packsWithTrips` and both stations in `catalog` and in `coveredStationIDs`.
    public static func links(
        _ routes: [AirportRoute] = all,
        catalog: CuratedStationCatalog,
        coveredStationIDs: Set<String>,
        packsWithTrips: Set<String>
    ) -> [AirportLink] {
        routes.compactMap { route in
            guard !route.packs.isDisjoint(with: packsWithTrips),
                  coveredStationIDs.contains(route.cityStationID),
                  coveredStationIDs.contains(route.airportStationID),
                  let city = catalog.station(id: route.cityStationID),
                  let airport = catalog.station(id: route.airportStationID)
            else { return nil }
            return AirportLink(route: route, city: city, airport: airport)
        }
    }
}
