import Foundation

// Android's position during the trip (gpsalarm/GpsTrainRoute.kt): the device position projected on the line between
// the stops of the followed train gives «Scendi tra N fermate» and, optionally, «Stima dalla tua posizione». Pure
// rules: the positions are never stored or sent. Values from contracts/shared-data/v1/trip-alerts-v1.json.

/// One stop of a train's route: its name, the catalogue coordinates when known and its scheduled time.
public struct GpsRouteStop: Codable, Equatable, Sendable {
    public let name: String
    public let latitude: Double?
    public let longitude: Double?
    public let scheduledAt: Date?
    public let skipped: Bool

    public init(name: String, latitude: Double?, longitude: Double?, scheduledAt: Date?, skipped: Bool = false) {
        self.name = name
        self.latitude = latitude
        self.longitude = longitude
        self.scheduledAt = scheduledAt
        self.skipped = skipped
    }

    public var located: Bool { latitude != nil && longitude != nil }
}

/// The route of one train from the boarding stop to the alighting one: the line the position is projected on,
/// straight between consecutive stops with coordinates (no track shapes).
public struct GpsTrainRoute: Codable, Equatable, Sendable {
    public static let maximumStops = SharedData.TripAlerts.positionMaximumRouteStops

    public let trainNumber: String
    public let stops: [GpsRouteStop]
    public let boardIndex: Int
    public let targetIndex: Int

    public init(trainNumber: String, stops: [GpsRouteStop], boardIndex: Int, targetIndex: Int) {
        self.trainNumber = trainNumber
        self.stops = stops
        self.boardIndex = boardIndex
        self.targetIndex = targetIndex
    }

    public var usable: Bool {
        stops.indices.contains(targetIndex) && boardIndex >= 0 && boardIndex < targetIndex && stops.filter(\.located).count >= 2
    }

    /// The delay can be estimated only when the target has a scheduled time.
    public var estimable: Bool { usable && stops[targetIndex].scheduledAt != nil }

    public func isFor(_ number: String) -> Bool { TrainAlertWatch.key(number) == TrainAlertWatch.key(trainNumber) }

    /// The route of `run` from `boardName` to `alightName`, with the coordinates `locate` gives a stop name (the
    /// bundled catalogue); nil without a usable line.
    public static func build(
        _ run: LiveRunStatus, boardName: String, alightName: String, locate: (String) -> (latitude: Double, longitude: Double)?
    ) -> GpsTrainRoute? {
        guard let board = TripStops.index(of: boardName, in: run.stops), let target = TripStops.index(of: alightName, in: run.stops),
              target > board else { return nil }
        let first = max(board, target + 1 - maximumStops)
        let stops: [GpsRouteStop] = run.stops[first...target].map { stop in
            let place = locate(stop.stationName)
            return GpsRouteStop(name: stop.stationName, latitude: place?.latitude, longitude: place?.longitude,
                                scheduledAt: stop.scheduled, skipped: stop.skipped)
        }
        let route = GpsTrainRoute(trainNumber: run.number.trimmingCharacters(in: .whitespaces), stops: stops, boardIndex: 0, targetIndex: stops.count - 1)
        return route.usable ? route : nil
    }
}

/// How the position relates to the route.
public enum GpsTrackStatus: String, Codable, Equatable, Sendable {
    /// No accepted position on the route yet.
    case waiting = "WAITING"
    case onRoute = "ON_ROUTE"
    /// More than `offRouteMeters` from the line: no estimate.
    case offRoute = "OFF_ROUTE"
    /// The target stop reached.
    case arrived = "ARRIVED"
}

/// The delay estimated from the position: an estimate, never official, never stored.
public struct GpsDelayEstimate: Codable, Equatable, Sendable {
    public let delayMinutes: Int
    /// Scheduled arrival at the target plus the estimated delay.
    public let arrivalAt: Date
    /// When the position behind it was evaluated.
    public let at: Date

    public init(delayMinutes: Int, arrivalAt: Date, at: Date) {
        self.delayMinutes = delayMinutes
        self.arrivalAt = arrivalAt
        self.at = at
    }
}

/// What the position session knows of the train's progress, in memory only.
public struct GpsTrainTrack: Equatable, Sendable {
    public let trainNumber: String
    public let status: GpsTrackStatus
    public let passedIndex: Int?
    public let passedStop: String?
    public let estimate: GpsDelayEstimate?
    public let lastFixAt: Date?
    public let arrivedAt: Date?

    public init(
        trainNumber: String, status: GpsTrackStatus, passedIndex: Int?, passedStop: String?, estimate: GpsDelayEstimate?,
        lastFixAt: Date?, arrivedAt: Date? = nil
    ) {
        self.trainNumber = trainNumber
        self.status = status
        self.passedIndex = passedIndex
        self.passedStop = passedStop
        self.estimate = estimate
        self.lastFixAt = lastFixAt
        self.arrivedAt = arrivedAt
    }

    public static func waiting(_ trainNumber: String) -> GpsTrainTrack {
        GpsTrainTrack(trainNumber: trainNumber, status: .waiting, passedIndex: nil, passedStop: nil, estimate: nil, lastFixAt: nil)
    }

    public func isFor(_ number: String) -> Bool { TrainAlertWatch.key(number) == TrainAlertWatch.key(trainNumber) }

    /// The progress by position counts only while fresh: after a long gap (a tunnel) the timetable takes over again.
    public func freshPassedIndex(now: Date) -> Int? {
        guard let lastFixAt, status == .onRoute || status == .arrived else { return nil }
        return now.timeIntervalSince(lastFixAt) <= GpsTrainTracking.positionFresh ? passedIndex : nil
    }
}

/// One position as the session hands it over, for one evaluation only.
public struct GpsFix: Equatable, Sendable {
    public let latitude: Double
    public let longitude: Double
    /// Horizontal accuracy in metres; nil when not reported.
    public let accuracyMeters: Double?
    /// How old the position is.
    public let age: TimeInterval

    public init(latitude: Double, longitude: Double, accuracyMeters: Double?, age: TimeInterval) {
        self.latitude = latitude
        self.longitude = longitude
        self.accuracyMeters = accuracyMeters
        self.age = age
    }
}

/// Android's `GpsTrainTracking`: positions less accurate than 200 m are ignored, one within 400 m of a stop stands at
/// it, one more than 1.5 km from the line gives no estimate.
public enum GpsTrainTracking {
    public static let maximumAccuracyMeters = Double(SharedData.TripAlerts.positionMaximumAccuracyMeters)
    public static let maximumFixAge: TimeInterval = SharedData.TripAlerts.positionMaximumFixAgeSeconds
    public static let offRouteMeters = Double(SharedData.TripAlerts.positionOffRouteMeters)
    public static let dwellRadiusMeters = Double(SharedData.TripAlerts.positionDwellRadiusMeters)
    /// A train does not reverse: noise that moves the projection back by less than this is ignored.
    public static let backtrackToleranceMeters = Double(SharedData.TripAlerts.positionBacktrackToleranceMeters)
    public static let positionFresh: TimeInterval = SharedData.TripAlerts.positionFreshSeconds
    /// An estimate older than this shows the time it was made.
    public static let estimateFresh: TimeInterval = SharedData.TripAlerts.estimateFreshSeconds
    public static let maximumDelayMinutes = SharedData.TripAlerts.estimateMaximumDelayMinutes
    public static let afterArrival = TripTime.minutes(SharedData.TripAlerts.estimateAfterArrivalMinutes)
    public static let beforeDeparture = TripTime.minutes(SharedData.TripAlerts.estimateBeforeDepartureMinutes)
    /// The session ends at the latest two hours after the expected arrival and eight hours after its start.
    public static let sessionAfterArrival = TripTime.minutes(SharedData.TripAlerts.positionSessionAfterArrivalMinutes)
    public static let sessionMaximum = TripTime.minutes(SharedData.TripAlerts.positionSessionMaximumMinutes)

    static let earthMeanRadiusMeters = 6_371_008.8
    /// Java's `Math.toRadians` factor, so both phones compute the same metres.
    static let degreesToRadians = 0.017453292519943295

    struct Projection {
        let alongMeters: Double
        let offsetMeters: Double
    }

    /// The route as a measured line: `positions` is the along-line distance of every stop; stops without coordinates
    /// lie between their neighbours in proportion to the scheduled time (or to their order).
    struct Geometry {
        let route: GpsTrainRoute
        let located: [Int]
        let positions: [Double]

        init(_ route: GpsTrainRoute) {
            let located = route.stops.indices.filter { route.stops[$0].located }
            var positions = [Double](repeating: 0, count: route.stops.count)
            var along = 0.0
            for (order, index) in located.enumerated() {
                if order > 0 { along += GpsTrainTracking.planarDistance(route.stops[located[order - 1]], route.stops[index]) }
                positions[index] = along
            }
            for index in route.stops.indices where !route.stops[index].located {
                let before = located.last { $0 < index }
                let after = located.first { $0 > index }
                if let before, let after {
                    positions[index] = positions[before] + (positions[after] - positions[before]) * Self.fraction(route, before, index, after)
                } else if let after {
                    positions[index] = positions[after]
                } else if let before {
                    positions[index] = positions[before]
                }
            }
            self.route = route
            self.located = located
            self.positions = positions
        }

        private static func fraction(_ route: GpsTrainRoute, _ before: Int, _ index: Int, _ after: Int) -> Double {
            if let start = route.stops[before].scheduledAt, let time = route.stops[index].scheduledAt,
               let end = route.stops[after].scheduledAt, end > start {
                return min(max(time.timeIntervalSince(start) / end.timeIntervalSince(start), 0), 1)
            }
            return Double(index - before) / Double(after - before)
        }

        /// Every segment's nearest point to the position, nearest first (equal offsets keep the line's order).
        func project(latitude: Double, longitude: Double) -> [Projection] {
            var result: [Projection] = []
            for order in located.indices.dropLast() {
                let from = located[order]
                let to = located[order + 1]
                guard let aLatitude = route.stops[from].latitude, let aLongitude = route.stops[from].longitude,
                      let bLatitude = route.stops[to].latitude, let bLongitude = route.stops[to].longitude else { continue }
                let reference = (aLatitude + bLatitude) / 2 * GpsTrainTracking.degreesToRadians
                let bx = GpsTrainTracking.metresEast(aLongitude, bLongitude, reference)
                let by = GpsTrainTracking.metresNorth(aLatitude, bLatitude)
                let px = GpsTrainTracking.metresEast(aLongitude, longitude, reference)
                let py = GpsTrainTracking.metresNorth(aLatitude, latitude)
                let lengthSquared = bx * bx + by * by
                let t = lengthSquared <= 0 ? 0 : min(max((px * bx + py * by) / lengthSquared, 0), 1)
                let dx = px - t * bx
                let dy = py - t * by
                result.append(Projection(alongMeters: positions[from] + (positions[to] - positions[from]) * t,
                                         offsetMeters: (dx * dx + dy * dy).squareRoot()))
            }
            let ordered = result.enumerated().sorted { first, second in
                first.element.offsetMeters != second.element.offsetMeters
                    ? first.element.offsetMeters < second.element.offsetMeters : first.offset < second.offset
            }
            return ordered.map(\.element)
        }

        /// The located stop the position stands at, within the dwell radius (the nearest).
        func stopAt(latitude: Double, longitude: Double) -> Int? {
            var best: Int?
            var bestDistance = Double.infinity
            for index in located {
                guard let stopLatitude = route.stops[index].latitude, let stopLongitude = route.stops[index].longitude else { continue }
                let distance = GpsTrainTracking.distanceMeters(latitude, longitude, stopLatitude, stopLongitude)
                if distance <= GpsTrainTracking.dwellRadiusMeters, distance < bestDistance {
                    best = index
                    bestDistance = distance
                }
            }
            return best
        }

        /// The last stop at or behind `alongMeters`.
        func passedAt(_ alongMeters: Double) -> Int {
            route.stops.indices.last { positions[$0] <= alongMeters + 1e-6 } ?? 0
        }
    }

    public static func accepted(_ fix: GpsFix) -> Bool {
        guard let accuracy = fix.accuracyMeters else { return false }
        return fix.latitude.isFinite && fix.longitude.isFinite && (-90...90).contains(fix.latitude) && (-180...180).contains(fix.longitude)
            && !accuracy.isNaN && accuracy >= 0 && accuracy <= maximumAccuracyMeters && fix.age <= maximumFixAge
    }

    /// The estimate can be started from shortly before the departure until ten minutes after the arrival.
    public static func canStartEstimate(departure: Date, arrival: Date, officialDelayMinutes: Int?, now: Date) -> Bool {
        let delay = TripTime.minutes(min(max(officialDelayMinutes ?? 0, 0), maximumDelayMinutes))
        return now >= departure.addingTimeInterval(-beforeDeparture) && now < arrival.addingTimeInterval(delay + afterArrival)
    }

    /// When the session ends: ten minutes after the arrival (reached, else scheduled plus the larger delay), never
    /// later than two hours after the scheduled arrival or eight hours after the start.
    public static func endsAt(arrival: Date, startedAt: Date, track: GpsTrainTrack?, officialDelayMinutes: Int?) -> Date {
        let delay = min(max(officialDelayMinutes ?? 0, track?.estimate?.delayMinutes ?? 0, 0), maximumDelayMinutes)
        let reached = track?.arrivedAt ?? arrival.addingTimeInterval(TripTime.minutes(delay))
        let expires = min(arrival.addingTimeInterval(sessionAfterArrival), startedAt.addingTimeInterval(sessionMaximum))
        return min(reached.addingTimeInterval(afterArrival), expires)
    }

    /// The countdown to the target from the position when fresh, else from the scheduled times shifted by the estimate.
    public static func countdown(_ route: GpsTrainRoute, track: GpsTrainTrack?, now: Date) -> StopCountdown? {
        let shift = TripTime.minutes(track?.estimate?.delayMinutes ?? 0)
        let timeline = route.stops.map { CountdownStop(name: $0.name, at: $0.scheduledAt?.addingTimeInterval(shift), skipped: $0.skipped) }
        return StopCountdownCalculator.countdown(timeline, board: route.boardIndex, alight: route.targetIndex, now: now,
                                                 positionPassed: track?.freshPassedIndex(now: now))
    }

    /// The delay at the projected point: at a stop, the time since its scheduled time; between stops, since the time
    /// interpolated along the line between the neighbouring timed stops. Never negative.
    static func estimate(_ route: GpsTrainRoute, geometry: Geometry, along: Double, atStop: Int?, passed: Int, now: Date) -> GpsDelayEstimate? {
        guard let target = route.stops[route.targetIndex].scheduledAt else { return nil }
        let scheduledHere: Int64
        if let atStop {
            guard let time = route.stops[atStop].scheduledAt else { return nil }
            scheduledHere = millis(time)
        } else {
            let timed = route.stops.indices.filter { route.stops[$0].scheduledAt != nil }
            guard let before = timed.last(where: { $0 <= passed }), let after = timed.first(where: { $0 > passed }),
                  let startDate = route.stops[before].scheduledAt, let endDate = route.stops[after].scheduledAt else { return nil }
            let start = millis(startDate)
            let end = millis(endDate)
            let span = geometry.positions[after] - geometry.positions[before]
            let fraction = span <= 0 ? 0 : min(max((along - geometry.positions[before]) / span, 0), 1)
            scheduledHere = start + Int64(Double(end - start) * fraction)
        }
        // Kotlin's roundToInt: half up.
        let elapsed = Double(millis(now) - scheduledHere) / 60_000
        let minutes = min(max(Int((elapsed + 0.5).rounded(.down)), 0), maximumDelayMinutes)
        return GpsDelayEstimate(delayMinutes: minutes, arrivalAt: target.addingTimeInterval(TripTime.minutes(minutes)), at: now)
    }

    static func millis(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1_000).rounded()) }

    /// Great-circle (haversine) distance on the mean Earth radius.
    public static func distanceMeters(_ fromLatitude: Double, _ fromLongitude: Double, _ toLatitude: Double, _ toLongitude: Double) -> Double {
        let lat1 = fromLatitude * degreesToRadians
        let lat2 = toLatitude * degreesToRadians
        let halfLatitude = sin((lat2 - lat1) / 2)
        let halfLongitude = sin((toLongitude - fromLongitude) * degreesToRadians / 2)
        let h = halfLatitude * halfLatitude + cos(lat1) * cos(lat2) * halfLongitude * halfLongitude
        return 2 * earthMeanRadiusMeters * asin(min(max(h, 0), 1).squareRoot())
    }

    static func planarDistance(_ from: GpsRouteStop, _ to: GpsRouteStop) -> Double {
        guard let fromLatitude = from.latitude, let fromLongitude = from.longitude,
              let toLatitude = to.latitude, let toLongitude = to.longitude else { return 0 }
        let reference = (fromLatitude + toLatitude) / 2 * degreesToRadians
        let x = metresEast(fromLongitude, toLongitude, reference)
        let y = metresNorth(fromLatitude, toLatitude)
        return (x * x + y * y).squareRoot()
    }

    static func metresEast(_ fromLongitude: Double, _ toLongitude: Double, _ referenceLatitudeRadians: Double) -> Double {
        (toLongitude - fromLongitude) * degreesToRadians * cos(referenceLatitudeRadians) * earthMeanRadiusMeters
    }

    static func metresNorth(_ fromLatitude: Double, _ toLatitude: Double) -> Double {
        (toLatitude - fromLatitude) * degreesToRadians * earthMeanRadiusMeters
    }
}

/// Follows one train with the positions of a session (Android's `GpsTrainTracking.Tracker`). A rejected position keeps
/// the last state and its estimate; leaving the line clears progress and estimate. Used on one actor only.
public final class GpsTrainTracker {
    public let route: GpsTrainRoute
    private let geometry: GpsTrainTracking.Geometry
    private var alongMeters: Double?
    public private(set) var track: GpsTrainTrack

    public init(route: GpsTrainRoute) {
        self.route = route
        geometry = GpsTrainTracking.Geometry(route)
        track = .waiting(route.trainNumber)
    }

    @discardableResult
    public func onFix(_ fix: GpsFix, now: Date) -> GpsTrainTrack {
        guard route.usable, GpsTrainTracking.accepted(fix) else { return track }
        let projections = geometry.project(latitude: fix.latitude, longitude: fix.longitude)
        guard let nearest = projections.first, nearest.offsetMeters <= GpsTrainTracking.offRouteMeters else {
            alongMeters = nil
            track = GpsTrainTrack(trainNumber: route.trainNumber, status: .offRoute, passedIndex: nil, passedStop: nil, estimate: nil, lastFixAt: now)
            return track
        }
        let previous = alongMeters
        var chosen = nearest
        if let previous, let ahead = projections.first(where: {
            $0.offsetMeters <= GpsTrainTracking.offRouteMeters && $0.alongMeters >= previous - GpsTrainTracking.backtrackToleranceMeters
        }) {
            chosen = ahead
        }
        var along = chosen.alongMeters
        if let previous, chosen.alongMeters < previous, chosen.alongMeters >= previous - GpsTrainTracking.backtrackToleranceMeters {
            along = previous
        }
        alongMeters = along
        let atStop = geometry.stopAt(latitude: fix.latitude, longitude: fix.longitude)
        let reached = atStop ?? geometry.passedAt(along)
        let earlier = previous == nil ? nil : track.passedIndex
        let passed = min(max(reached, earlier ?? reached), route.targetIndex)
        let arrived = passed >= route.targetIndex
        // At the boarding stop the traveller may still be waiting on the platform: nothing to estimate yet.
        let waitingToBoard = passed == route.boardIndex && atStop == route.boardIndex
        let fresh = route.estimable && !waitingToBoard
            ? GpsTrainTracking.estimate(route, geometry: geometry, along: along, atStop: atStop, passed: passed, now: now) : nil
        track = GpsTrainTrack(
            trainNumber: route.trainNumber,
            status: arrived ? .arrived : .onRoute,
            passedIndex: passed,
            passedStop: route.stops[passed].name,
            // A stop without a scheduled time keeps the last estimate, which then shows its age.
            estimate: waitingToBoard ? nil : (fresh ?? track.estimate),
            lastFixAt: now,
            arrivedAt: arrived ? (track.arrivedAt ?? now) : nil
        )
        return track
    }
}
