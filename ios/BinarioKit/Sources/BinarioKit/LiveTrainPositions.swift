import Foundation

// Estimated positions of trains in motion (Android's maps/LiveTrainPositions.kt). Binario has no
// vehicle positions: a train is placed where its timetable puts it at the current time, shifted by
// the live delay when the app already has one for that run, on a straight line between the two
// stops around that time (there is no track geometry). Never a GPS position. The values are
// contracts/shared-data/v1/live-train-map-v1.json; the cases
// contracts/shared-rules/v1/live-train-positions-v1.json.

/// A point in degrees (WGS84).
public struct GeoPoint: Equatable, Hashable, Sendable {
    public let latitude: Double
    public let longitude: Double

    public init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }

    public var isValid: Bool {
        latitude.isFinite && longitude.isFinite && (-90.0...90.0).contains(latitude) && (-180.0...180.0).contains(longitude)
    }
}

/// A box in degrees.
public struct GeoBounds: Equatable, Hashable, Sendable {
    public let north: Double
    public let south: Double
    public let east: Double
    public let west: Double

    public init(north: Double, south: Double, east: Double, west: Double) {
        self.north = north
        self.south = south
        self.east = east
        self.west = west
    }

    public func contains(_ point: GeoPoint) -> Bool {
        point.isValid && (south...north).contains(point.latitude) && (west...east).contains(point.longitude)
    }
}

/// One timed stop of a `ScheduledRailRun`. Minutes count from the midnight of the run's service
/// day, so a stop reached after midnight keeps 24:00+ values (1450 = 00:10 of the next day).
public struct ScheduledRunStop: Equatable, Sendable {
    public let name: String
    public let latitude: Double?
    public let longitude: Double?
    public let arrivalMinute: Int
    public let departureMinute: Int

    public init(name: String, latitude: Double?, longitude: Double?, arrivalMinute: Int, departureMinute: Int) {
        self.name = name
        self.latitude = latitude
        self.longitude = longitude
        self.arrivalMinute = arrivalMinute
        self.departureMinute = departureMinute
    }

    var point: GeoPoint? {
        guard let latitude, let longitude else { return nil }
        let point = GeoPoint(latitude: latitude, longitude: longitude)
        return point.isValid ? point : nil
    }
}

/// Where a run really starts when its first stops were left out.
public struct ScheduledRunOrigin: Equatable, Sendable {
    public let name: String
    public let departureMinute: Int

    public init(name: String, departureMinute: Int) {
        self.name = name
        self.departureMinute = departureMinute
    }
}

/// A train of the static timetable on one service day: its public number and its timed stops in order.
public struct ScheduledRailRun: Equatable, Identifiable, Sendable {
    /// `<pack>:<trip_id>:<service date>`: one run of one pack.
    public let id: String
    public let serviceDate: PlannedLocalDate
    /// The number as the timetable shows it; nil when the pack has none.
    public let publicNumber: String?
    /// The line as the app reads it («REG», «FR», «RE11»), display only.
    public let lineLabel: String?
    /// At least two, in run order.
    public let stops: [ScheduledRunStop]
    public let origin: ScheduledRunOrigin?

    public init(id: String, serviceDate: PlannedLocalDate, publicNumber: String?, lineLabel: String?, stops: [ScheduledRunStop], origin: ScheduledRunOrigin? = nil) {
        self.id = id
        self.serviceDate = serviceDate
        self.publicNumber = publicNumber
        self.lineLabel = lineLabel
        self.stops = stops
        self.origin = origin
    }

    public var firstDepartureMinute: Int { stops.first?.departureMinute ?? 0 }
    public var lastArrivalMinute: Int { stops.last?.arrivalMinute ?? 0 }
    /// The station the run leaves from: its true origin, else its first stop.
    public var originName: String { origin?.name ?? stops.first?.name ?? "" }
    public var originDepartureMinute: Int { origin?.departureMinute ?? firstDepartureMinute }
}

/// Where a run is on its timetable at one moment; stop indices refer to the stops it was computed on.
public enum RunProgress: Equatable, Sendable {
    case notStarted
    /// Stopped at a station (from its arrival to its departure).
    case atStop(Int)
    /// Between two consecutive stops; the share of the scheduled travel time already run (0…1).
    case between(from: Int, to: Int, fraction: Double)
    case arrived
}

public enum LiveTrainEstimator {
    /// Progress of a run at `minute` of its service day (fractional, 24:00+ after midnight). The
    /// first stop counts from its departure, the last one until its arrival.
    public static func progress(_ stops: [ScheduledRunStop], minute: Double) -> RunProgress {
        guard let first = stops.first, let last = stops.last, minute.isFinite else { return .notStarted }
        if minute < Double(first.departureMinute) { return .notStarted }
        if minute > Double(last.arrivalMinute) { return .arrived }
        for index in stops.indices {
            let stop = stops[index]
            if minute >= Double(stop.arrivalMinute), minute <= Double(stop.departureMinute) { return .atStop(index) }
            guard index + 1 < stops.count else { break }
            let next = stops[index + 1]
            if minute > Double(stop.departureMinute), minute < Double(next.arrivalMinute) {
                let span = Double(next.arrivalMinute - stop.departureMinute)
                return .between(from: index, to: index + 1, fraction: min(max((minute - Double(stop.departureMinute)) / span, 0), 1))
            }
        }
        // Only a timetable whose times go back gets here: such a run is not placed.
        return .notStarted
    }

    /// The estimated point at `minute`: a located stop when the run is stopped there, otherwise the
    /// straight line between the located stops before and after, in proportion to the scheduled
    /// time. Stops without coordinates are bridged by their located neighbours.
    public static func position(_ stops: [ScheduledRunStop], minute: Double) -> GeoPoint? {
        let located = stops.compactMap { stop in stop.point.map { (stop, $0) } }
        switch progress(located.map { $0.0 }, minute: minute) {
        case let .atStop(index): return located[index].1
        case let .between(from, to, fraction): return interpolate(located[from].1, located[to].1, fraction: fraction)
        case .notStarted, .arrived: return nil
        }
    }

    /// Linear in latitude and longitude: a straight segment at the scale of two neighbouring stations.
    public static func interpolate(_ from: GeoPoint, _ to: GeoPoint, fraction: Double) -> GeoPoint {
        let share = min(max(fraction, 0), 1)
        return GeoPoint(latitude: from.latitude + (to.latitude - from.latitude) * share,
                        longitude: from.longitude + (to.longitude - from.longitude) * share)
    }

    /// Minutes (seconds as a fraction) from the midnight of `serviceDate` to `now`, on the railway's wall clock.
    public static func minuteOfServiceDay(_ serviceDate: PlannedLocalDate, now: PlannedLocalDate, secondOfDay: Int) -> Double {
        Double((now.daysSinceEpoch - serviceDate.daysSinceEpoch) * 86_400 + secondOfDay) / 60
    }

    /// `minuteOfServiceDay` for an instant, read on the Italian wall clock.
    public static func minuteOfServiceDay(_ serviceDate: PlannedLocalDate, at instant: Date) -> Double? {
        guard let wall = RailMoment(instant) else { return nil }
        return minuteOfServiceDay(serviceDate, now: wall.date, secondOfDay: wall.secondOfDay)
    }
}

/// A moment on the Italian wall clock, to the second.
public struct RailMoment: Equatable, Hashable, Sendable {
    public let date: PlannedLocalDate
    public let secondOfDay: Int

    public init(date: PlannedLocalDate, secondOfDay: Int) {
        self.date = date
        self.secondOfDay = secondOfDay
    }

    public init?(_ instant: Date) {
        guard let date = ItalianWallClock.date(of: instant), let time = ItalianWallClock.time(of: instant) else { return nil }
        // The seconds within the minute do not depend on the zone's offset (whole minutes in Italy).
        let seconds = Int(instant.timeIntervalSince1970.rounded(.down))
        let withinMinute = ((seconds % 60) + 60) % 60
        self.init(date: date, secondOfDay: time.minutesOfDay * 60 + withinMinute)
    }
}

/// A run the app has already loaded live data for: a row of a station board or of the planner.
/// Nothing is requested for the map.
public struct LiveRunObservation: Equatable, Sendable {
    /// Public train number as the source shows it.
    public let number: String
    /// Scheduled time of the run where the observation is, railway-local.
    public let scheduledAt: RailMoment
    /// Delay the source published, in minutes; nil when it published none.
    public let delayMinutes: Int?
    /// The source says the run is cancelled: it is not drawn as moving.
    public let cancelled: Bool
    /// Position of the row in the caller's list, so a tap opens that row's detail.
    public let ref: Int

    public init(number: String, scheduledAt: RailMoment, delayMinutes: Int?, cancelled: Bool = false, ref: Int = -1) {
        self.number = number
        self.scheduledAt = scheduledAt
        self.delayMinutes = delayMinutes
        self.cancelled = cancelled
        self.ref = ref
    }

    /// Seconds of the railway's wall clock since 1970-01-01 00:00, to compare moments of several days.
    var wallSeconds: Int { scheduledAt.date.daysSinceEpoch * 86_400 + scheduledAt.secondOfDay }
}

/// One train drawn on the map and listed under it.
public struct LiveTrain: Equatable, Identifiable, Sendable {
    public let run: ScheduledRailRun
    public let point: GeoPoint
    public let progress: RunProgress
    /// Live delay applied to the timetable, nil when the estimate comes from the timetable alone.
    public let delayMinutes: Int?
    public let observation: LiveRunObservation?
    /// Straight-line distance from the map's station, for the list order.
    public let distanceKm: Double

    public var id: String { run.id }
    /// From the timetable shifted by a live delay, or from the timetable alone (the legend's two markers).
    public var hasLiveDelay: Bool { delayMinutes != nil }

    /// Name of the stop the train is at, or nil between stops.
    public var stopName: String? {
        guard case let .atStop(index) = progress, run.stops.indices.contains(index) else { return nil }
        return run.stops[index].name
    }

    /// The two stops around the train when it is between stops.
    public var between: (from: String, to: String)? {
        guard case let .between(from, to, _) = progress, run.stops.indices.contains(from), run.stops.indices.contains(to) else { return nil }
        return (run.stops[from].name, run.stops[to].name)
    }

    public var destination: String { run.stops.last?.name ?? "" }
}

/// The trains of one refresh: `trains` (at most the cap, nearest first) out of `inBounds` placed in the area.
public struct LiveTrainSelection: Equatable, Sendable {
    public let trains: [LiveTrain]
    public let inBounds: Int

    /// Public so the map can show an empty selection when the area is too wide to read.
    public init(trains: [LiveTrain], inBounds: Int) {
        self.trains = trains
        self.inBounds = inBounds
    }

    public var capped: Bool { inBounds > trains.count }
}

public enum LiveTrainSelector {
    public static let maximumTrains = SharedData.LiveTrainMap.maximumTrains
    public static let minimumAppliedDelay = SharedData.LiveTrainMap.minimumAppliedDelayMinutes
    /// The largest delay the timetable window can follow (its look-back).
    public static let maximumAppliedDelay = SharedData.LiveTrainMap.lookBackMinutes
    public static let sameTrainKilometres = SharedData.LiveTrainMap.sameTrainKilometres
    static let matchMarginMinutes = SharedData.LiveTrainMap.matchMarginMinutes

    /// The runs under way at `now` whose estimated point is inside `bounds`: delayed by the matching
    /// observations, copies of one train merged, nearest to `focus` first, at most `cap`.
    public static func select(
        _ runs: [ScheduledRailRun], observations: [LiveRunObservation], now: RailMoment, bounds: GeoBounds, focus: GeoPoint,
        cap: Int = maximumTrains
    ) -> LiveTrainSelection {
        var byNumber: [String: [LiveRunObservation]] = [:]
        for observation in observations {
            guard let key = runNumberKey(observation.number) else { continue }
            byNumber[key, default: []].append(observation)
        }
        var placed: [LiveTrain] = []
        for run in runs where run.stops.count >= 2 {
            let observation = match(run, byNumber: byNumber)
            if observation?.cancelled == true { continue }
            let delay = observation?.delayMinutes.flatMap { (minimumAppliedDelay...maximumAppliedDelay).contains($0) ? $0 : nil }
            let minute = LiveTrainEstimator.minuteOfServiceDay(run.serviceDate, now: now.date, secondOfDay: now.secondOfDay) - Double(delay ?? 0)
            let progress = LiveTrainEstimator.progress(run.stops, minute: minute)
            if progress == .notStarted || progress == .arrived { continue }
            guard let point = LiveTrainEstimator.position(run.stops, minute: minute), bounds.contains(point) else { continue }
            placed.append(LiveTrain(
                run: run, point: point, progress: progress, delayMinutes: delay, observation: observation,
                distanceKm: GroundDistance.kilometres(focus.latitude, focus.longitude, point.latitude, point.longitude)
            ))
        }
        let trains = merged(placed).sorted { $0.distanceKm == $1.distanceKm ? $0.run.id < $1.run.id : $0.distanceKm < $1.distanceKm }
        return LiveTrainSelection(trains: Array(trains.prefix(max(0, cap))), inBounds: trains.count)
    }

    /// The observation of `run`: same number, and a time inside the run's scheduled span from its
    /// true origin (so not yesterday's train).
    static func match(_ run: ScheduledRailRun, byNumber: [String: [LiveRunObservation]]) -> LiveRunObservation? {
        guard let key = run.publicNumber.flatMap(runNumberKey), let candidates = byNumber[key] else { return nil }
        let midnight = run.serviceDate.daysSinceEpoch * 86_400
        let start = midnight + (run.originDepartureMinute - matchMarginMinutes) * 60
        let end = midnight + (run.lastArrivalMinute + matchMarginMinutes) * 60
        let inSpan = candidates.filter { $0.wallSeconds >= start && $0.wallSeconds <= end }
        return inSpan.first { $0.cancelled } ?? inSpan.first { $0.delayMinutes != nil } ?? inSpan.first
    }

    /// Copies of one train in two packs: the one with a live delay, then the one with more located stops, wins.
    private static func merged(_ trains: [LiveTrain]) -> [LiveTrain] {
        func located(_ train: LiveTrain) -> Int { train.run.stops.filter { $0.latitude != nil && $0.longitude != nil }.count }
        let preferred = trains.sorted { first, second in
            let a = first.delayMinutes != nil ? 0 : 1, b = second.delayMinutes != nil ? 0 : 1
            if a != b { return a < b }
            let stopsA = located(first), stopsB = located(second)
            if stopsA != stopsB { return stopsA > stopsB }
            return first.run.id < second.run.id
        }
        var kept: [LiveTrain] = []
        for train in preferred {
            let key = train.run.publicNumber.flatMap(runNumberKey)
            let duplicate = key != nil && kept.contains { other in
                other.run.serviceDate == train.run.serviceDate && other.run.publicNumber.flatMap(runNumberKey) == key
                    && GroundDistance.kilometres(other.point.latitude, other.point.longitude, train.point.latitude, train.point.longitude) < sameTrainKilometres
            }
            if !duplicate { kept.append(train) }
        }
        return kept
    }

    /// Android's `runNumberKey`: the number two sources publish for the same run, compared across
    /// them: the digits of the displayed number («EC 85», «85» and «RE_11 - 85» all give «85»), or
    /// the letters and digits when it has no digit. Longer than eight characters it is an id.
    public static func runNumberKey(_ published: String) -> String? {
        let after = published.components(separatedBy: " - ").last ?? published
        var shown = after.trimmingCharacters(in: .whitespaces)
        if shown.isEmpty { shown = published.trimmingCharacters(in: .whitespaces) }
        guard !shown.isEmpty else { return nil }
        let digits = String(shown.filter { $0.isASCII && $0.isNumber }.drop { $0 == "0" })
        let key = digits.isEmpty ? String(shown.uppercased().filter { ($0.isLetter || $0.isNumber) }) : digits
        return key.isEmpty || key.count > 8 ? nil : key
    }
}

/// The text drawn next to a marker: code, number and delay, in characters every map font has.
public enum LiveTrainLabels {
    /// «REG 2153 +5'», «FR 9558 0'», «RE11 2153» (no delay known).
    public static func marker(_ train: LiveTrain) -> String {
        ([title(train.run)] + (train.delayMinutes.map { [delay($0)] } ?? [])).joined(separator: " ")
    }

    /// Line and number: «REG 2153»; the number alone when no short line is known.
    public static func title(_ run: ScheduledRailRun) -> String {
        let number = run.publicNumber ?? ""
        let line = run.lineLabel.flatMap { label -> String? in
            label.count <= SharedData.LiveTrainMap.maximumCodeLength && label.caseInsensitiveCompare(number) != .orderedSame ? label : nil
        } ?? ""
        return [line, number].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.joined(separator: " ")
    }

    /// «+5'», «-2'» or «0'»: a machine value, never formatted with the device locale.
    public static func delay(_ minutes: Int) -> String {
        if minutes > 0 { return "+\(minutes)'" }
        if minutes < 0 { return "-\(-minutes)'" }
        return "0'"
    }
}

/// The cadence of the map: positions every 30 seconds, the timetable once per quarter of an hour.
public enum LiveTrainRefresh {
    public static let positionsSeconds = SharedData.LiveTrainMap.positionsRefreshSeconds

    /// Start of the quarter hour of `now`: the runs read then cover the whole quarter.
    public static func slotStart(_ now: RailMoment) -> RailMoment {
        let slot = SharedData.LiveTrainMap.timetableSlotMinutes * 60
        let minuteStart = now.secondOfDay / 60 * 60
        let hourStart = minuteStart / 3_600 * 3_600
        return RailMoment(date: now.date, secondOfDay: hourStart + (minuteStart - hourStart) / slot * slot)
    }
}

/// The area a map of trains covers, and when its timetable must be read again.
public enum LiveTrainAreas {
    public static let stationRadiusKilometres = SharedData.LiveTrainMap.stationRadiusKilometres
    private static let kilometresPerDegree = 111.32

    public static func around(_ point: GeoPoint, radiusKilometres: Double = stationRadiusKilometres) -> GeoBounds {
        let latitudeDelta = radiusKilometres / kilometresPerDegree
        let longitudeDelta = radiusKilometres / (kilometresPerDegree * max(cos(point.latitude * .pi / 180), 0.1))
        return GeoBounds(
            north: min(point.latitude + latitudeDelta, 85), south: max(point.latitude - latitudeDelta, -85),
            east: min(point.longitude + longitudeDelta, 180), west: max(point.longitude - longitudeDelta, -180)
        )
    }

    /// The first area of a route's map: the box of its two ends with a margin.
    public static func routeArea(_ origin: GeoPoint, _ destination: GeoPoint) -> GeoBounds {
        let share = SharedData.LiveTrainMap.routeMarginShare
        let minimum = SharedData.LiveTrainMap.minimumRouteMarginDegrees
        let latitudeMargin = max(abs(origin.latitude - destination.latitude) * share, minimum)
        let longitudeMargin = max(abs(origin.longitude - destination.longitude) * share, minimum)
        return GeoBounds(
            north: min(max(origin.latitude, destination.latitude) + latitudeMargin, 85),
            south: max(min(origin.latitude, destination.latitude) - latitudeMargin, -85),
            east: min(max(origin.longitude, destination.longitude) + longitudeMargin, 180),
            west: max(min(origin.longitude, destination.longitude) - longitudeMargin, -180)
        )
    }

    /// Wider than the table's spans the area map asks to zoom in instead of reading timetables.
    public static func tooWide(_ bounds: GeoBounds) -> Bool {
        bounds.north - bounds.south > SharedData.LiveTrainMap.maximumLatitudeSpan
            || bounds.east - bounds.west > SharedData.LiveTrainMap.maximumLongitudeSpan
    }

    /// The area whose runs are read for `visible`: half a span more on every side, snapped outwards to the grid.
    public static func readArea(_ visible: GeoBounds) -> GeoBounds {
        let latitudeMargin = (visible.north - visible.south) / 2
        let longitudeMargin = (visible.east - visible.west) / 2
        return GeoBounds(
            north: min(snapUp(visible.north + latitudeMargin), 85), south: max(snapDown(visible.south - latitudeMargin), -85),
            east: min(snapUp(visible.east + longitudeMargin), 180), west: max(snapDown(visible.west - longitudeMargin), -180)
        )
    }

    /// True when `read` still holds every train that can be inside `visible`.
    public static func covers(_ read: GeoBounds, _ visible: GeoBounds) -> Bool {
        visible.north <= read.north && visible.south >= read.south && visible.east <= read.east && visible.west >= read.west
    }

    private static func snapUp(_ value: Double) -> Double {
        let snap = SharedData.LiveTrainMap.readAreaSnapDegrees
        return (value / snap).rounded(.up) * snap
    }

    private static func snapDown(_ value: Double) -> Double {
        let snap = SharedData.LiveTrainMap.readAreaSnapDegrees
        return (value / snap).rounded(.down) * snap
    }
}

/// Which runs the map asks the static timetable for.
public enum ScheduledRunScope: Equatable, Sendable {
    /// Runs that call at `origin` and later at `destination` (the planner's direction).
    case route(origin: StationIdentity, destination: StationIdentity)
    /// Runs with at least one stop inside the box.
    case area(GeoBounds)
}

/// The runs that can be on the move around `at` (railway-local): those whose scheduled span
/// overlaps `[at - lookBack, at + lookAhead]`. The look-back is the largest live delay applied.
public struct ScheduledRunRequest: Equatable, Sendable {
    public let at: RailMoment
    public let scope: ScheduledRunScope
    public let lookBackMinutes: Int
    public let lookAheadMinutes: Int

    public init(
        at: RailMoment, scope: ScheduledRunScope,
        lookBackMinutes: Int = SharedData.LiveTrainMap.lookBackMinutes, lookAheadMinutes: Int = SharedData.LiveTrainMap.lookAheadMinutes
    ) {
        self.at = at
        self.scope = scope
        self.lookBackMinutes = lookBackMinutes
        self.lookAheadMinutes = lookAheadMinutes
    }
}
