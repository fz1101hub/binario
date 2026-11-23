import SwiftUI
import MapKit
import BinarioKit

/// What a map of trains in motion is about: the route of the planner's result, or the area around
/// the board's station (Android's `LiveTrainMapScope`).
enum LiveTrainMapScope: Equatable {
    case route(origin: Station, destination: Station)
    case area(Station)
}

/// The fixed parts of one map, from catalogue coordinates: nil when a station of the scope has none.
struct LiveTrainMapSetup: Equatable {
    /// Distances in the list are measured from here.
    let focus: GeoPoint
    /// The stations drawn for orientation (route ends, or the station).
    let places: [GeoPoint]
    /// The area assumed visible until the map reports its own.
    let initialArea: GeoBounds

    static func of(_ scope: LiveTrainMapScope) -> LiveTrainMapSetup? {
        func point(_ station: Station) -> GeoPoint? {
            guard let latitude = station.latitude, let longitude = station.longitude else { return nil }
            let point = GeoPoint(latitude: latitude, longitude: longitude)
            return point.isValid ? point : nil
        }
        switch scope {
        case let .route(origin, destination):
            guard let from = point(origin), let to = point(destination) else { return nil }
            return LiveTrainMapSetup(focus: from, places: [from, to], initialArea: LiveTrainAreas.routeArea(from, to))
        case let .area(station):
            guard let centre = point(station) else { return nil }
            return LiveTrainMapSetup(focus: centre, places: [centre], initialArea: LiveTrainAreas.around(centre))
        }
    }
}

/// «Treni in movimento» in «Partenze»: the trains around the board's station, with the delays the
/// board already shows.
struct StationLiveTrainsEntry: View {
    @EnvironmentObject private var model: AppModel
    let station: Station
    @State private var open = false

    var body: some View {
        if let setup = LiveTrainMapSetup.of(.area(station)) {
            Section {
                LiveTrainMapButton { open = true }
            }
            .fullScreenCover(isPresented: $open) {
                LiveTrainMapScreen(scope: .area(station), setup: setup, observations: observations)
            }
        }
    }

    /// The rows of the board on screen: the live delay when a source published one.
    private var observations: [LiveRunObservation] {
        guard model.boardStation?.id == station.id else { return [] }
        return model.boardDepartures.enumerated().compactMap { index, departure in
            guard let moment = RailMoment(departure.departure) else { return nil }
            let live = model.isLiveDataActive ? departure.live : nil
            return LiveRunObservation(
                number: departure.service.number, scheduledAt: moment, delayMinutes: live?.delayMinutes,
                cancelled: live?.cancelled ?? false, ref: index
            )
        }
    }
}

/// «Treni in movimento» under the planner's result: the trains of the route, in its direction.
struct RouteLiveTrainsEntry: View {
    @EnvironmentObject private var model: AppModel
    let snapshot: JourneySnapshot
    @State private var open = false

    var body: some View {
        let scope = LiveTrainMapScope.route(origin: model.station(for: snapshot.query.origin), destination: model.station(for: snapshot.query.destination))
        if let setup = LiveTrainMapSetup.of(scope) {
            Section {
                LiveTrainMapButton { open = true }
            }
            .fullScreenCover(isPresented: $open) {
                LiveTrainMapScreen(scope: scope, setup: setup, observations: observations)
            }
        }
    }

    /// The trains of the result at the departure station, with the live delay the list shows.
    private var observations: [LiveRunObservation] {
        guard let day = ItalianWallClock.date(of: snapshot.query.departure) else { return [] }
        return snapshot.services.enumerated().compactMap { index, service in
            guard let time = PlannedLocalTime(hhmm: service.scheduledDeparture) else { return nil }
            let live = model.isLiveDataActive ? model.resultLive[service.id] : nil
            return LiveRunObservation(
                number: service.number, scheduledAt: RailMoment(date: day, secondOfDay: time.minutesOfDay * 60),
                delayMinutes: live?.delayMinutes, cancelled: live?.cancelled ?? false, ref: index
            )
        }
    }
}

private struct LiveTrainMapButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label("departures.map.title", systemImage: "map")
        }
    }
}

/// The map of trains in motion, full screen: Apple's map (MapKit), its legend and the same trains
/// as a list sorted by distance (what VoiceOver reads). Positions are computed every 30 seconds
/// while the app is in the foreground; the timetable is read from the bundled index once per
/// quarter of an hour, and nothing is requested for the trains themselves.
struct LiveTrainMapScreen: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var extras: ExtrasModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    let scope: LiveTrainMapScope
    let setup: LiveTrainMapSetup
    let observations: [LiveRunObservation]

    @State private var now = Date()
    @State private var visible: GeoBounds
    @State private var readArea: GeoBounds
    @State private var runs: [ScheduledRailRun]?
    @State private var position: MapCameraPosition

    init(scope: LiveTrainMapScope, setup: LiveTrainMapSetup, observations: [LiveRunObservation]) {
        self.scope = scope
        self.setup = setup
        self.observations = observations
        _visible = State(initialValue: setup.initialArea)
        _readArea = State(initialValue: LiveTrainAreas.readArea(setup.initialArea))
        _position = State(initialValue: .region(Self.region(setup.initialArea)))
    }

    private struct RunsKey: Equatable {
        let slot: RailMoment?
        let area: GeoBounds?
        let tooWide: Bool
    }

    private var isArea: Bool {
        if case .area = scope { return true }
        return false
    }

    private var tooWide: Bool { isArea && LiveTrainAreas.tooWide(visible) }

    private var subtitle: String {
        switch scope {
        case let .route(origin, destination):
            return String.localizedStringWithFormat(NSLocalizedString("departures.map.route_subtitle", comment: ""), origin.name, destination.name)
        case let .area(station):
            return String.localizedStringWithFormat(NSLocalizedString("departures.map.area_subtitle", comment: ""), station.name)
        }
    }

    private var selection: LiveTrainSelection? {
        guard let runs, let moment = RailMoment(now) else { return nil }
        if tooWide { return LiveTrainSelection(trains: [], inBounds: 0) }
        return LiveTrainSelector.select(runs, observations: observations, now: moment, bounds: visible, focus: setup.focus)
    }

    var body: some View {
        let current = selection
        let trains = current?.trains ?? []
        NavigationStack {
            VStack(spacing: 0) {
                Map(position: $position) {
                    ForEach(setup.places.indices, id: \.self) { index in
                        Marker(index == 0 ? stationName(0) : stationName(1), systemImage: "building.columns",
                               coordinate: Self.coordinate(setup.places[index]))
                    }
                    ForEach(trains) { train in
                        Annotation(LiveTrainLabels.marker(train), coordinate: Self.coordinate(train.point)) {
                            LiveTrainDot(live: train.hasLiveDelay)
                        }
                    }
                }
                .onMapCameraChange(frequency: .onEnd) { context in
                    visible = Self.bounds(context.region)
                    if isArea, !tooWide, !LiveTrainAreas.covers(readArea, visible) {
                        readArea = LiveTrainAreas.readArea(visible)
                    }
                }
                .accessibilityLabel(Text(String.localizedStringWithFormat(
                    NSLocalizedString("departures.map.content_description", comment: ""), subtitle
                )))
                .frame(maxHeight: .infinity)
                List {
                    Section {
                        LegendRow(live: true, text: Text("departures.map.legend_live"))
                        LegendRow(live: false, text: Text("departures.map.legend_timetable"))
                        Text("departures.map.note")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    Section {
                        ForEach(trains) { train in
                            LiveTrainRow(train: train)
                        }
                    } header: {
                        Text(status(current))
                            .accessibilityAddTraits(.isHeader)
                    }
                }
                .frame(maxHeight: .infinity)
            }
            .navigationTitle("departures.map.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 0) {
                        Text("departures.map.title")
                            .font(.headline)
                        Text(verbatim: subtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Label("action.ok", systemImage: "xmark")
                    }
                }
            }
        }
        // Only while the app is in the foreground: the clock stops with it and starts again.
        .task(id: scenePhase == .active) {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                now = Date()
                try? await Task.sleep(nanoseconds: UInt64(LiveTrainRefresh.positionsSeconds * 1_000_000_000))
            }
        }
        .task(id: RunsKey(slot: RailMoment(now).map(LiveTrainRefresh.slotStart), area: isArea ? readArea : nil, tooWide: tooWide)) {
            await loadRuns()
        }
    }

    private func stationName(_ index: Int) -> String {
        switch scope {
        case let .route(origin, destination): return index == 0 ? origin.name : destination.name
        case let .area(station): return station.name
        }
    }

    private func loadRuns() async {
        guard !tooWide, let moment = RailMoment(now) else { return }
        guard let repository = model.repository as? SQLiteGTFSJourneyRepository, case let .available(catalog) = model.catalogState else {
            runs = []
            return
        }
        let stations = extras.stations(catalog.stations)
        let request: ScheduledRunRequest
        switch scope {
        case let .route(origin, destination):
            request = ScheduledRunRequest(at: LiveTrainRefresh.slotStart(moment), scope: .route(origin: StationIdentity(origin), destination: StationIdentity(destination)))
        case .area:
            request = ScheduledRunRequest(at: LiveTrainRefresh.slotStart(moment), scope: .area(readArea))
        }
        let found = await Task.detached(priority: .userInitiated) {
            await repository.scheduledRailRuns(request, stations: stations)
        }.value
        guard !Task.isCancelled else { return }
        runs = found
    }

    private func status(_ current: LiveTrainSelection?) -> String {
        if tooWide { return String(localized: "departures.map.zoom_in") }
        guard let current else { return String(localized: "departures.map.loading") }
        if current.trains.isEmpty { return String(localized: "departures.map.none") }
        let count = String.localizedStringWithFormat(NSLocalizedString("departures.map.count", comment: ""), current.capped ? current.inBounds : current.trains.count)
        guard current.capped else { return count }
        return count + " " + String.localizedStringWithFormat(NSLocalizedString("departures.map.capped", comment: ""), current.trains.count)
    }

    static func coordinate(_ point: GeoPoint) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude)
    }

    static func region(_ bounds: GeoBounds) -> MKCoordinateRegion {
        MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: (bounds.north + bounds.south) / 2, longitude: (bounds.east + bounds.west) / 2),
            span: MKCoordinateSpan(latitudeDelta: bounds.north - bounds.south, longitudeDelta: bounds.east - bounds.west)
        )
    }

    static func bounds(_ region: MKCoordinateRegion) -> GeoBounds {
        GeoBounds(
            north: min(region.center.latitude + region.span.latitudeDelta / 2, 90),
            south: max(region.center.latitude - region.span.latitudeDelta / 2, -90),
            east: min(region.center.longitude + region.span.longitudeDelta / 2, 180),
            west: max(region.center.longitude - region.span.longitudeDelta / 2, -180)
        )
    }
}

/// A train's marker: filled when a live delay moved it, a ring when the timetable alone placed it.
/// The legend says which is which; the shape, not only the colour, tells them apart.
private struct LiveTrainDot: View {
    let live: Bool

    var body: some View {
        ZStack {
            Circle()
                .fill(live ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(BackgroundStyle()))
            Circle()
                .strokeBorder(Color.accentColor, lineWidth: 3)
        }
        .frame(width: 16, height: 16)
        .accessibilityHidden(true)
    }
}

private struct LegendRow: View {
    let live: Bool
    let text: Text

    var body: some View {
        HStack(spacing: 10) {
            LiveTrainDot(live: live)
            text
                .font(.subheadline)
        }
    }
}

/// One train of the list: line and number with its destination, where it is, how it was placed and
/// how far from the map's station.
private struct LiveTrainRow: View {
    let train: LiveTrain

    var body: some View {
        let title = String.localizedStringWithFormat(
            NSLocalizedString("departures.map.row_title", comment: ""), LiveTrainLabels.title(train.run), train.destination
        )
        let place: String? = train.stopName.map {
            String.localizedStringWithFormat(NSLocalizedString("departures.map.row_at", comment: ""), $0)
        } ?? train.between.map {
            String.localizedStringWithFormat(NSLocalizedString("departures.map.row_between", comment: ""), $0.from, $0.to)
        }
        let estimate: String = train.delayMinutes.map { delay in
            let value = delay > 0
                ? String.localizedStringWithFormat(NSLocalizedString("departures.map.delay", comment: ""), delay)
                : String(localized: "departures.map.on_time")
            return String.localizedStringWithFormat(NSLocalizedString("departures.map.row_live", comment: ""), value)
        } ?? String(localized: "departures.map.row_timetable")
        let distance = String.localizedStringWithFormat(NSLocalizedString("departures.map.distance", comment: ""), train.distanceKm)
        HStack(spacing: 12) {
            LiveTrainDot(live: train.hasLiveDelay)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: title)
                    .font(.body.weight(.semibold))
                if let place {
                    Text(verbatim: place)
                        .font(.subheadline)
                }
                Text(verbatim: "\(estimate) · \(distance)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
