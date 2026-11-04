import Foundation

/// A ready route between a city station and an airport station of the curated catalogue, served
/// by the trains of at least one of `packs`.
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
/// carry (Trenitalia, Ferrotramviaria) never appear. Station names are the catalogue's; the
/// timetables carry no service brand ("Malpensa Express"), so none is shown.
public enum AirportRoutes {
    static let trenitalia = "trenitalia-gtfs"
    static let trenord = "lombardia-trenord-gtfs"
    static let ferrotramviaria = "ferrotramviaria-bari-barletta"

    public static let all: [AirportRoute] = [
        // Roma Termini ↔ Fiumicino Aeroporto (Trenitalia regional express).
        AirportRoute(cityStationID: "S08409", airportStationID: "S08411", packs: [trenitalia]),
        // Milano Centrale and Milano Cadorna ↔ Malpensa Aeroporto (Terminal 1; Trenord RE51 and RE54).
        AirportRoute(cityStationID: "S01700", airportStationID: "S01139", packs: [trenord]),
        AirportRoute(cityStationID: "S01066", airportStationID: "S01139", packs: [trenord]),
        // Bari ↔ Bari Aeroporto (Ferrotramviaria; "Bari" is the catalogue station of its stop at Bari Centrale).
        AirportRoute(cityStationID: "NC000634", airportStationID: "NC001565", packs: [ferrotramviaria]),
        // Palermo Centrale ↔ Punta Raisi (Palermo Aeroporto).
        AirportRoute(cityStationID: "S12002", airportStationID: "S12133", packs: [trenitalia]),
        // Cagliari ↔ Elmas Aeroporto.
        AirportRoute(cityStationID: "S12891", airportStationID: "S12819", packs: [trenitalia]),
        // Trieste Centrale ↔ Trieste Airport.
        AirportRoute(cityStationID: "S03317", airportStationID: "S03213", packs: [trenitalia]),
        // Torino Porta Susa ↔ Caselle Torinese Aeroporto (SFM A).
        AirportRoute(cityStationID: "S00035", airportStationID: "S00088", packs: [trenitalia]),
    ]

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
