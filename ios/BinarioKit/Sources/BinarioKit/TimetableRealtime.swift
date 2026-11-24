import Foundation

// The Trip Updates of the open feeds on the trains of the bundled timetable, as Android joins them
// (`RealtimeEnrichmentService`, `EmbeddedGtfsRealtimeScheduleResolver`, `GtfsRealtimeEnrichedTrainDataSource`): by
// pack, trip_id, service day and stop, never by train number. The cases both platforms read are
// contracts/shared-rules/v1/gtfs-realtime-v1.json.

/// The labels of the sources of the shared table ("SNCF", "STA Alto Adige", "transport.opendata.ch").
public enum LiveSourceLabels {
    public static func label(_ source: LiveSource) -> String? {
        if source.rawValue == SharedData.RealtimeFeeds.swissBoard.id { return SharedData.RealtimeFeeds.swissBoard.label }
        return SharedData.RealtimeFeeds.tripUpdates.first { $0.id == source.rawValue }?.label
    }
}

/// A trip of the bundled timetable: its pack and its own trip_id.
public struct TimetableTripKey: Hashable, Sendable {
    public let sourceID: String
    public let tripID: String

    public init(sourceID: String, tripID: String) {
        self.sourceID = sourceID
        self.tripID = tripID
    }
}

/// The stops of one timetable trip, in stop_sequence order, and the time zones of its pack's agencies.
public struct TimetableTripStops: Equatable, Sendable {
    public struct Stop: Equatable, Sendable {
        /// The pack's own stop_id.
        public let stopID: String
        public let sequence: Int
        /// GTFS seconds of the departure, else of the arrival; nil when the timetable has neither.
        public let seconds: Int?
        /// The catalogue station the index binds the stop to.
        public let catalogStationID: String?

        public init(stopID: String, sequence: Int, seconds: Int?, catalogStationID: String?) {
            self.stopID = stopID
            self.sequence = sequence
            self.seconds = seconds
            self.catalogStationID = catalogStationID
        }
    }

    public let stops: [Stop]
    public let agencyTimeZones: [String]

    public init(stops: [Stop], agencyTimeZones: [String]) {
        self.stops = stops.sorted { $0.sequence < $1.sequence }
        self.agencyTimeZones = agencyTimeZones
    }
}

/// Reads the stops of timetable trips: the bundled index (`SQLiteGTFSJourneyRepository`).
public protocol TimetableTripReading: Sendable {
    /// The stops of each trip found; a trip the timetable does not hold is left out.
    func tripStops(_ trips: [TimetableTripKey]) -> [TimetableTripKey: TimetableTripStops]
}

/// A listed train of the bundled timetable, as the planner and «Partenze» identify it
/// ("<pack>:<trip_id>:<yyyymmdd>:<seconds>", the trip_id possibly holding colons) and the station it is listed at.
public struct TimetableServiceReference: Hashable, Sendable {
    public let trip: TimetableTripKey
    /// The GTFS service day, yyyymmdd.
    public let serviceDate: Int
    /// GTFS seconds of the departure from the listed station.
    public let seconds: Int

    public init?(serviceID: String) {
        let parts = serviceID.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count >= 4, let seconds = Int(parts[parts.count - 1]), seconds >= 0,
              let date = Int(parts[parts.count - 2]), (10_000_101...99_991_231).contains(date) else { return nil }
        let pack = String(parts[0])
        let trip = parts[1..<(parts.count - 2)].joined(separator: ":")
        guard !pack.isEmpty, !trip.isEmpty else { return nil }
        self.trip = TimetableTripKey(sourceID: pack, tripID: trip)
        serviceDate = date
        self.seconds = seconds
    }
}

/// What the feeds said of the listed trains: each train's live status at the listed station (the departure there,
/// as the iPhone's results and «Partenze» show it), the feeds that answered and those that did not.
public struct TimetableRealtimeAnswer: Equatable, Sendable {
    public let statuses: [String: StationBoardLiveStatus]
    public let sources: [LiveSource]
    public let unavailableSources: [LiveSource: LiveUnavailableReason]

    public static let none = TimetableRealtimeAnswer(statuses: [:], sources: [], unavailableSources: [:])
}

/// The open Trip Updates feeds of the bundled railway packs, joined to the listed trains of a search or a board.
/// Only today's trains are joined (Android enriches a search for today only), only the feeds of the packs of the
/// listed trains are asked, and each feed for its own pack only (`SharedData.RealtimeFeeds.tripUpdates`: STA,
/// SNCF, Trenitalia France). Never throws; cancellable.
public final class TimetableRealtime: Sendable {
    /// Android's `GtfsRealtimeEnrichedTrainDataSource.OBSERVED_DEADLINE_MILLIS`: the listed trains are already on
    /// screen, so the feeds may take this long.
    public static let deadline: TimeInterval = 20

    let feeds: [String: GTFSRealtimeFeedSource]
    private let now: @Sendable () -> Date

    init(feeds: [GTFSRealtimeFeedSource], now: @escaping @Sendable () -> Date) {
        var byPack: [String: GTFSRealtimeFeedSource] = [:]
        for feed in feeds where byPack[feed.feed.pack] == nil { byPack[feed.feed.pack] = feed }
        self.feeds = byPack
        self.now = now
    }

    /// The feeds of the shared table over `transport`: one gate per host that pauses its feeds together.
    public static func standard(
        transport: any LiveHTTPTransport = URLSessionLiveTransport(), pauses: LiveSourcePauses = .shared,
        now: @escaping @Sendable () -> Date = { Date() }
    ) -> TimetableRealtime {
        let client = LiveSourceClient(transport: transport, now: now)
        var gates: [String: LiveSourceGate] = [:]
        var sources: [GTFSRealtimeFeedSource] = []
        for feed in SharedData.RealtimeFeeds.tripUpdates {
            let gate = gates[feed.refusalGroup] ?? GTFSRealtimeFeedSource.gate(source: LiveSource(rawValue: feed.id), pauses: pauses, now: now)
            gates[feed.refusalGroup] = gate
            if let source = GTFSRealtimeFeedSource(feed: feed, client: client, gate: gate) { sources.append(source) }
        }
        return TimetableRealtime(feeds: sources, now: now)
    }

    /// The live statuses of `serviceIDs` listed at the catalogue station `stationID` on the Italian day of `day`
    /// (the search's or the board's), read with `timetable`. Nothing is asked for another day than today.
    public func statuses(
        serviceIDs: [String], stationID: String, day: Date, timetable: any TimetableTripReading
    ) async -> TimetableRealtimeAnswer {
        let asked = now()
        guard LiveRailTime.sameDay(day, asked) else { return .none }
        var references: [String: TimetableServiceReference] = [:]
        var tripsByPack: [String: Set<String>] = [:]
        for id in serviceIDs {
            guard let reference = TimetableServiceReference(serviceID: id), feeds[reference.trip.sourceID] != nil else { continue }
            references[id] = reference
            tripsByPack[reference.trip.sourceID, default: []].insert(reference.trip.tripID)
        }
        guard !references.isEmpty else { return .none }
        let packs = tripsByPack.keys.sorted()
        let feeds = self.feeds
        let trips = tripsByPack
        let finished = await LiveTasks.withDeadline(seconds: Self.deadline) {
            await LiveTasks.boundedMap(packs, maximumConcurrent: packs.count) { pack -> GTFSRealtimeFeedRead in
                guard let feed = feeds[pack] else { return .unavailable(.notCovered) }
                return await feed.updates(trips: trips[pack] ?? [])
            }
        }
        if Task.isCancelled { return .none }
        let reads = finished?.count == packs.count ? finished : nil
        var updatesByPack: [String: [GTFSRealtimeStopUpdate]] = [:]
        var answered: [LiveSource] = []
        var unavailable: [LiveSource: LiveUnavailableReason] = [:]
        for (index, pack) in packs.enumerated() {
            guard let source = feeds[pack]?.source else { continue }
            switch reads?[index] ?? .unavailable(.sourceUnavailable) {
            case let .updates(updates):
                updatesByPack[pack] = updates
                answered.append(source)
            case let .unavailable(reason):
                unavailable[source] = reason
            }
        }
        guard !updatesByPack.isEmpty else { return TimetableRealtimeAnswer(statuses: [:], sources: [], unavailableSources: unavailable) }
        let wanted = references.values.filter { updatesByPack[$0.trip.sourceID] != nil }.map(\.trip)
        let stops = timetable.tripStops(Array(Set(wanted)))
        let checked = now()
        var statuses: [String: StationBoardLiveStatus] = [:]
        for (id, reference) in references {
            guard let updates = updatesByPack[reference.trip.sourceID], let trip = stops[reference.trip],
                  let timing = GTFSRealtimeJoin.timing(
                      reference, stationID: stationID, trip: trip, updates: updates, searchDay: day, now: checked
                  ),
                  let source = LiveSource(rawValue: timing.update.feedID) else { continue }
            statuses[id] = StationBoardLiveStatus(
                expectedDeparture: timing.expected, delayMinutes: timing.delayMinutes, cancelled: false, platform: nil,
                sourceLabel: source.displayName, observedAt: timing.update.observedAt, source: source
            )
        }
        return TimetableRealtimeAnswer(statuses: statuses, sources: answered, unavailableSources: unavailable)
    }
}

/// Android's join of a Trip Update to a stop of the timetable, as pure functions.
enum GTFSRealtimeJoin {
    /// The update that times a listed train at its station, with the delay and expected time it gives.
    struct Timing: Equatable {
        let update: GTFSRealtimeStopUpdate
        let scheduled: Date
        let delayMinutes: Int
        let expected: Date
    }

    static let zone = TimeZone(identifier: "Europe/Rome") ?? TimeZone(secondsFromGMT: 3_600) ?? .current

    /// The timing of `reference` at the stop it leaves `stationID` from: the latest fresh update of its own trip and
    /// service day that resolves to that very stop (`timedStops`), when it gives a time or a delay; nil otherwise.
    static func timing(
        _ reference: TimetableServiceReference, stationID: String, trip: TimetableTripStops,
        updates: [GTFSRealtimeStopUpdate], searchDay: Date, now: Date
    ) -> Timing? {
        guard keepsRomeTime(trip.agencyTimeZones, serviceDate: reference.serviceDate),
              let origin = listedStop(reference, stationID: stationID, in: trip),
              let seconds = origin.seconds,
              let scheduled = scheduledDate(serviceDate: reference.serviceDate, seconds: seconds) else { return nil }
        let searchDate = LiveRailTime.isoDay(searchDay)
        var latest: GTFSRealtimeStopUpdate?
        for update in updates where update.tripID == reference.trip.tripID {
            if let date = update.serviceDate, date != reference.serviceDate { continue }
            // Without start_date, never apply today's observation to another day's train.
            if update.serviceDate == nil, LiveRailTime.isoDay(scheduled) != searchDate { continue }
            guard update.isFresh(at: now), resolved(update, in: trip) == origin else { continue }
            if let kept = latest, kept.observedAt >= update.observedAt { continue }
            latest = update
        }
        guard let latest, let delay = delayMinutes(latest, scheduled: scheduled) else { return nil }
        let expected = latest.predicted ?? scheduled.addingTimeInterval(TimeInterval(delay) * 60)
        return Timing(update: latest, scheduled: scheduled, delayMinutes: delay, expected: expected)
    }

    /// The stop of the trip the train is listed at: its departure seconds, at the listed station when two stops
    /// share them; nil when that does not decide.
    static func listedStop(_ reference: TimetableServiceReference, stationID: String, in trip: TimetableTripStops) -> TimetableTripStops.Stop? {
        let timed = trip.stops.filter { $0.seconds == reference.seconds }
        if timed.count == 1 { return timed[0] }
        let atStation = timed.filter { $0.catalogStationID == stationID }
        return atStation.count == 1 ? atStation[0] : nil
    }

    /// `EmbeddedGtfsRealtimeScheduleResolver.resolve`: the first stop of the trip, by sequence, with the update's
    /// stop_id (and its stop_sequence when it gives one), or with its stop_sequence when it gives no stop_id.
    static func resolved(_ update: GTFSRealtimeStopUpdate, in trip: TimetableTripStops) -> TimetableTripStops.Stop? {
        trip.stops.first { stop in
            if let stopID = update.stopID {
                return stop.stopID == stopID && (update.stopSequence == nil || update.stopSequence == stop.sequence)
            }
            return update.stopSequence == stop.sequence
        }
    }

    /// Android's `delay`: the published delay, else the predicted time less the scheduled one, in whole minutes
    /// truncated towards zero (`Duration.toMinutes`).
    static func delayMinutes(_ update: GTFSRealtimeStopUpdate, scheduled: Date) -> Int? {
        if let seconds = update.delaySeconds { return seconds / 60 }
        guard let predicted = update.predicted else { return nil }
        let seconds: Double = predicted.timeIntervalSince(scheduled).rounded(.towardZero)
        guard seconds.isFinite, abs(seconds) < 1e15 else { return nil }
        return Int(seconds) / 60
    }

    /// `DayStopTimes.timesIn`: every valid agency time zone of the pack has Rome's offset at noon of the service day
    /// (a pack publishing UTC times would put an update on the wrong scheduled time).
    static func keepsRomeTime(_ zones: [String], serviceDate: Int) -> Bool {
        guard let noon = noon(serviceDate: serviceDate) else { return false }
        let offset = zone.secondsFromGMT(for: noon)
        return zones.compactMap { TimeZone(identifier: $0) }.allSatisfy { $0.secondsFromGMT(for: noon) == offset }
    }

    /// GTFS time: the seconds from noon less twelve hours of the service day, in Italian time.
    static func scheduledDate(serviceDate: Int, seconds: Int) -> Date? {
        noon(serviceDate: serviceDate).map { $0.addingTimeInterval(TimeInterval(seconds) - 12 * 3_600) }
    }

    private static func noon(serviceDate: Int) -> Date? {
        var components = DateComponents()
        components.year = serviceDate / 10_000
        components.month = serviceDate / 100 % 100
        components.day = serviceDate % 100
        components.hour = 12
        return LiveRailTime.calendar.date(from: components)
    }
}
