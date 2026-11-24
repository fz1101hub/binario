import SwiftUI
import MapKit
import BinarioKit

// Android's maps (OpenMapCards.kt) on Apple's map: «Mappa del percorso» in a train's detail,
// «Mappa della stazione» on a board and «Mappa delle stazioni vicine». Each stays closed until a
// tap, as on Android, and the map is given catalogue coordinates only, never names or times.

private enum MapPoints {
    static func coordinate(_ point: GeoPoint) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude)
    }

    static func point(_ station: Station) -> GeoPoint? {
        guard let latitude = station.latitude, let longitude = station.longitude else { return nil }
        let point = GeoPoint(latitude: latitude, longitude: longitude)
        return point.isValid ? point : nil
    }

    /// Android's map height (`MAP_HEIGHT`, 240 dp).
    static let height: CGFloat = 240
}

// MARK: «Mappa del percorso»

/// The stops of the train on a map: the live stops a source published, else the timetable's; the
/// last confirmed stop and the next one marked, every stop listed under the map.
struct RouteMapSection: View {
    @EnvironmentObject private var model: AppModel
    let service: TrainService
    let liveStops: [LiveStopStatus]
    @State private var shown = false
    @State private var route: RouteMapModel?
    @State private var selected: Int?

    var body: some View {
        Section {
            Button {
                shown.toggle()
            } label: {
                Label(LocalizedStringKey(shown ? "action.hide_map" : "action.show_map"), systemImage: "map")
            }
            if shown {
                if let route {
                    RouteMapContent(route: route, selected: $selected)
                } else {
                    HStack {
                        ProgressView()
                        Text("result.route_map.loading")
                    }
                }
            }
        } header: {
            Text("result.route_map.title")
        }
        .task(id: "\(shown)|\(liveStops.count)") {
            guard shown else { return }
            route = await load()
        }
    }

    private func load() async -> RouteMapModel {
        if !liveStops.isEmpty {
            let entries = liveStops.map { stop in
                RouteMapEntry(
                    name: stop.stationName,
                    scheduled: LiveTexts.clock(stop.scheduled),
                    expected: LiveTexts.clock(stop.expected),
                    passed: stop.passed,
                    skipped: stop.skipped,
                    verified: stop.operationalStatusVerified
                )
            }
            var codes: [String: String] = [:]
            for stop in liveStops {
                if let code = stop.stationCode { codes[stop.stationName] = code }
            }
            return RouteMapPlanner.build(entries, serviceVerified: false) { name in
                model.mapStation(code: codes[name], name: name).flatMap(MapPoints.point)
            }
        }
        // Without a live run: the run's stops in the bundled timetable, at catalogue stations.
        let stops = await model.timetableStops(of: service)
        let entries = stops.map { RouteMapEntry(name: $0.station.name, scheduled: $0.time) }
        var points: [String: GeoPoint] = [:]
        for stop in stops {
            if let point = MapPoints.point(stop.station) { points[stop.station.name] = point }
        }
        return RouteMapPlanner.build(entries, serviceVerified: false) { points[$0] }
    }
}

private struct RouteMapContent: View {
    let route: RouteMapModel
    @Binding var selected: Int?

    var body: some View {
        let mapped = route.mappedStops
        if mapped.isEmpty {
            Text("result.route_map.no_coordinates")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } else {
            let markers = mapped.map { stop in
                RouteMarker(id: stop.id, name: stop.name, coordinate: MapPoints.coordinate(stop.point ?? GeoPoint(latitude: 0, longitude: 0)), role: stop.primaryRole)
            }
            let origin = route.stops.first?.name ?? ""
            let destination = route.stops.last?.name ?? ""
            Map(initialPosition: .automatic, selection: $selected) {
                MapPolyline(coordinates: route.line.map(MapPoints.coordinate))
                    .stroke(Color.accentColor, lineWidth: 3)
                ForEach(markers) { marker in
                    Marker(marker.name, systemImage: RouteMapTexts.symbol(marker.role), coordinate: marker.coordinate)
                        .tint(RouteMapTexts.tint(marker.role))
                        .tag(marker.id)
                }
            }
            .frame(height: MapPoints.height)
            .listRowInsets(EdgeInsets())
            .accessibilityLabel(Text(PersonalTexts.format("result.route_map.description", origin, destination)))
            if let stop = route.stops.first(where: { $0.id == selected }) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: stop.name)
                        .font(.subheadline.weight(.semibold))
                    Text(RouteMapTexts.times(stop))
                        .font(.caption)
                    let roles = RouteMapTexts.roles(stop)
                    if !roles.isEmpty {
                        Text(roles)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
            } else {
                Text("result.route_map.tap_hint")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(RouteMapTexts.legend(mapped), id: \.self) { role in
                Label {
                    Text(LocalizedStringKey(RouteMapTexts.titleKey(role)))
                } icon: {
                    Image(systemName: RouteMapTexts.symbol(role))
                        .foregroundStyle(RouteMapTexts.tint(role))
                }
                .font(.footnote)
            }
            Text("result.route_map.note")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text(PersonalTexts.format("result.route_map.coverage", mapped.count, route.stops.count))
                .font(.footnote)
                .foregroundStyle(.secondary)
            // The text alternative: every stop with its times and roles, mapped or not.
            ForEach(route.stops) { stop in
                Text(RouteMapTexts.line(stop))
                    .font(.caption)
            }
        }
    }
}

private struct RouteMarker: Identifiable {
    let id: Int
    let name: String
    let coordinate: CLLocationCoordinate2D
    let role: RouteMapRole?
}

/// The texts and marks of the route map (Android's `stopTimes`, `stopRoles`, `routeStopLine`).
enum RouteMapTexts {
    static func titleKey(_ role: RouteMapRole) -> String {
        switch role {
        case .origin: return "result.route_map.origin"
        case .destination: return "result.route_map.destination"
        case .lastConfirmed: return "result.route_map.last_confirmed"
        case .next: return "result.route_map.next"
        case .skipped: return "result.route_map.skipped"
        }
    }

    static func symbol(_ role: RouteMapRole?) -> String {
        switch role {
        case .origin?: return "flag"
        case .destination?: return "flag.checkered"
        case .lastConfirmed?: return "checkmark"
        case .next?: return "arrow.right"
        case .skipped?: return "xmark"
        case nil: return "circle.fill"
        }
    }

    static func tint(_ role: RouteMapRole?) -> Color {
        switch role {
        case .origin?, .destination?: return .accentColor
        case .lastConfirmed?: return .green
        case .next?: return .orange
        case .skipped?: return BinarioPalette.statusError
        case nil: return .gray
        }
    }

    /// The roles the legend explains: the ends in one line, then progress and skipped stops.
    static func legend(_ mapped: [RouteMapStop]) -> [RouteMapRole] {
        let present = Set(mapped.compactMap(\.primaryRole))
        var roles: [RouteMapRole] = []
        if present.contains(.origin) || present.contains(.destination) { roles += [.origin, .destination] }
        for role in [RouteMapRole.lastConfirmed, .next, .skipped] where present.contains(role) {
            roles.append(role)
        }
        return roles
    }

    static func times(_ stop: RouteMapStop) -> String {
        if let expected = stop.expected {
            return PersonalTexts.format("result.route_map.scheduled_expected", stop.scheduled, expected)
        }
        return PersonalTexts.format("result.route_map.scheduled", stop.scheduled)
    }

    static func roles(_ stop: RouteMapStop) -> String {
        RouteMapRole.allCases.filter { stop.roles.contains($0) }
            .map { NSLocalizedString(titleKey($0), comment: "") }
            .joined(separator: " · ")
    }

    static func line(_ stop: RouteMapStop) -> String {
        var parts = [stop.name, times(stop)]
        let named = roles(stop)
        if !named.isEmpty { parts.append(named) }
        if stop.point == nil { parts.append(NSLocalizedString("result.route_map.not_on_map", comment: "")) }
        return parts.joined(separator: " · ")
    }
}

extension AppModel {
    /// The catalogue station of a published stop: by railway code, else by name; nil when unknown.
    func mapStation(code: String?, name: String) -> Station? {
        if let code, case let .available(catalog) = catalogState,
           let station = catalog.offeredStations.first(where: { $0.railwayCode == code }) {
            return station
        }
        return catalogStation(named: name)
    }

    /// The stops of a timetable train in order at catalogue stations, with the time shown there,
    /// read from the bundled index; empty for a train the index cannot name.
    func timetableStops(of service: TrainService) async -> [(station: Station, time: String)] {
        guard let reference = TimetableServiceReference(serviceID: service.id),
              let timetable = repository as? SQLiteGTFSJourneyRepository,
              case let .available(catalog) = catalogState else { return [] }
        let trip = reference.trip
        let stops = await Task.detached(priority: .userInitiated) {
            timetable.tripStops([trip])[trip]?.stops ?? []
        }.value
        var result: [(station: Station, time: String)] = []
        for stop in stops {
            guard let id = stop.catalogStationID, let station = catalog.station(id: id) else { continue }
            if result.last?.station.id == station.id { continue }
            result.append((station: station, time: stop.seconds.map { Self.gtfsClock($0) } ?? "—"))
        }
        return result
    }

    /// GTFS seconds as the clock time shown («24:35» is 00:35).
    nonisolated static func gtfsClock(_ seconds: Int) -> String {
        let minutes = (seconds / 60) % (24 * 60)
        return String(format: "%02d:%02d", minutes / 60, minutes % 60)
    }

    /// The nearest other catalogue stations of `station`, one per railway code, closest first
    /// (Android's `OpenMapStationIndex.neighbours`).
    func stationNeighbours(of station: Station, limit: Int = 6) async -> [NearbyStation] {
        guard case let .available(catalog) = catalogState, let centre = MapPoints.point(station) else { return [] }
        let stations = catalog.offeredStations
        return await Task.detached(priority: .userInitiated) {
            var seen = Set<String>()
            var result: [NearbyStation] = []
            for nearby in StationProximity.nearby(stations, latitude: centre.latitude, longitude: centre.longitude) {
                let other = nearby.station
                guard other.id != station.id, station.railwayCode == nil || other.railwayCode != station.railwayCode,
                      seen.insert(other.railwayCode ?? other.id).inserted else { continue }
                result.append(nearby)
                if result.count == limit { break }
            }
            return result
        }.value
    }
}

// MARK: «Mappa della stazione» and «Mappa delle stazioni vicine»

/// The board's station and its six nearest catalogue neighbours; a neighbour opens its board.
struct StationMapSection: View {
    @EnvironmentObject private var model: AppModel
    let station: Station
    @State private var shown = false
    @State private var neighbours: [NearbyStation]?

    var body: some View {
        if let centre = MapPoints.point(station) {
            Section {
                Button {
                    shown.toggle()
                } label: {
                    Label(LocalizedStringKey(shown ? "action.hide_map" : "action.show_map"), systemImage: "map")
                }
                if shown {
                    if let neighbours {
                        StationsMap(
                            focus: (name: station.name, point: centre),
                            stations: neighbours.map(\.station),
                            position: nil,
                            description: PersonalTexts.format("departures.station_map.description", station.name)
                        )
                        Text("departures.station_map.tap_hint")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        NearbyStationRows(stations: neighbours)
                    } else {
                        ProgressView()
                    }
                }
            } header: {
                Text("departures.station_map.title")
            }
            .task(id: "\(station.id)|\(shown)") {
                guard shown else { return }
                neighbours = await model.stationNeighbours(of: station)
            }
        }
    }
}

/// «Mappa delle stazioni vicine»: the stations already listed by distance, loaded on a tap, with the
/// approximate position the list was sorted from (kept in memory while the screen is open).
struct NearbyStationsMapRows: View {
    let stations: [NearbyStation]
    let position: GeoPoint?
    @State private var loaded = false

    var body: some View {
        if stations.contains(where: { MapPoints.point($0.station) != nil }) {
            if loaded {
                StationsMap(focus: nil, stations: stations.map(\.station), position: position,
                            description: String(localized: "departures.nearby_map.description"))
                Text("departures.nearby_map.tap_hint")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if position != nil {
                    Label("departures.nearby_map.position", systemImage: "circle.dashed")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } else {
                Button {
                    loaded = true
                } label: {
                    Label("departures.nearby_map.load", systemImage: "map")
                }
            }
        }
    }
}

/// A map of catalogue stations; a tap on one opens its board.
private struct StationsMap: View {
    @EnvironmentObject private var model: AppModel
    let focus: (name: String, point: GeoPoint)?
    let stations: [Station]
    let position: GeoPoint?
    let description: String
    @State private var selected: String?

    var body: some View {
        let mapped = stations.compactMap { station in MapPoints.point(station).map { MappedStation(station: station, point: $0) } }
        let circle = position.map { [$0] } ?? []
        let centre = focus.map { [$0] } ?? []
        Map(initialPosition: .automatic, selection: $selected) {
            ForEach(centre.indices, id: \.self) { index in
                Marker(centre[index].name, systemImage: "building.columns.fill", coordinate: MapPoints.coordinate(centre[index].point))
                    .tint(Color.accentColor)
            }
            ForEach(mapped) { entry in
                Marker(entry.station.name, systemImage: "tram.fill", coordinate: MapPoints.coordinate(entry.point))
                    .tint(Color.gray)
                    .tag(entry.id)
            }
            ForEach(circle.indices, id: \.self) { index in
                MapCircle(center: MapPoints.coordinate(circle[index]), radius: 300)
                    .foregroundStyle(Color.secondary.opacity(0.3))
            }
        }
        .frame(height: MapPoints.height)
        .listRowInsets(EdgeInsets())
        .accessibilityLabel(Text(description))
        .onChange(of: selected) { _, id in
            guard let id, let station = stations.first(where: { $0.id == id }) else { return }
            model.selectBoardStation(StationIdentity(station))
        }
    }
}

private struct MappedStation: Identifiable {
    let station: Station
    let point: GeoPoint
    var id: String { station.id }
}

/// The stations of a map as a list: name and distance; a tap opens the board.
private struct NearbyStationRows: View {
    @EnvironmentObject private var model: AppModel
    let stations: [NearbyStation]

    var body: some View {
        ForEach(stations, id: \.station.id) { nearby in
            Button {
                model.selectBoardStation(StationIdentity(nearby.station))
            } label: {
                Text(verbatim: "\(nearby.station.name) · \(PersonalTexts.distance(nearby.distanceKilometres * 1_000))")
                    .foregroundStyle(.primary)
            }
            .accessibilityLabel(Text(PersonalTexts.format("departures.station_map.open", nearby.station.name)
                                     + " · " + PersonalTexts.distance(nearby.distanceKilometres * 1_000)))
        }
    }
}
