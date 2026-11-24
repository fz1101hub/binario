import Foundation

/// A stop of the followed trip in Per te's «Viaggio e monitoraggi» (Android's `ActiveTripStop`).
public struct ActiveTripStop: Equatable, Sendable {
    public let station: String
    /// Expected with live data, else the timetable's time.
    public let expected: Date
    public let platform: String?
    /// A source confirmed the train's passage there.
    public let passed: Bool

    public init(station: String, expected: Date, platform: String?, passed: Bool) {
        self.station = station
        self.expected = expected
        self.platform = platform
        self.passed = passed
    }
}

/// Android's `ActiveTrip` on the followed trip: the stops of its current train after the boarding stop up to the
/// alighting one, the next stop still to reach and «Fermata raggiunta», which moves it on by hand.
public enum ActiveTripProgress {
    /// The train the trip is on at `now`: the first whose arrival is ahead, else the last.
    public static func legIndex(_ trip: FollowedTrip, now: Date) -> Int {
        trip.legs.firstIndex { $0.arrival > now } ?? trip.legs.count - 1
    }

    /// The stops of `run` after the leg's boarding stop up to its alighting stop, skipped ones left out; without a
    /// run (or without both stations in it), the alighting station alone at the timetable's arrival.
    public static func stops(_ leg: FollowedTripLeg, run: LiveRunStatus?) -> [ActiveTripStop] {
        if let run, let board = TripStops.index(of: leg.boardStation, in: run.stops),
           let alight = TripStops.index(of: leg.alightStation, in: run.stops), alight > board {
            let stops = run.stops[(board + 1)...alight].filter { !$0.skipped }.map { stop in
                ActiveTripStop(station: stop.stationName, expected: stop.expected, platform: stop.platform, passed: stop.passed)
            }
            if !stops.isEmpty { return stops }
        }
        return [ActiveTripStop(station: leg.alightStation, expected: leg.arrival, platform: nil, passed: false)]
    }

    /// The index of the next stop: after the last one a source confirmed, and after those marked reached by hand
    /// (`reached`, a count); `stops.count` once the trip is complete.
    public static func nextIndex(_ stops: [ActiveTripStop], reached: Int) -> Int {
        let confirmed = (stops.lastIndex { $0.passed }).map { $0 + 1 } ?? 0
        return min(max(confirmed, reached, 0), stops.count)
    }
}
