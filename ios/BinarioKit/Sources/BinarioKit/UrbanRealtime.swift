import Foundation

// The open Trip Updates feeds of the city networks (Android's `UrbanRealtime.kt`): metro and tram realtime read only by
// the boards and journeys of that city, never by the railway trains. The feeds are the shared table's
// (`SharedData.RealtimeFeeds.urban`); a personal-only feed exists only in a personal build, as its city pack.

public enum UrbanRealtimeFeeds {
    public typealias Feed = SharedData.RealtimeFeeds.UrbanFeed

    public static let all: [Feed] = SharedData.RealtimeFeeds.urban

    /// Android's `forBuild`: the feeds of this build whose city pack is among `packs`, the personal-only ones only in
    /// a personal build.
    public static func forBuild(personalSources: Bool, packs: Set<String>) -> [Feed] {
        all.filter { (personalSources || !$0.personalOnly) && packs.contains($0.pack) }
    }
}

/// One stop of a city trip: the absolute times when the feed publishes them, else the delays.
public struct UrbanStopTimeRealtime: Equatable, Sendable {
    public let stopID: String?
    public let stopSequence: Int?
    public let arrival: Date?
    public let departure: Date?
    public let arrivalDelaySeconds: Int?
    public let departureDelaySeconds: Int?
    /// SKIPPED: the vehicle does not stop here.
    public let skipped: Bool

    /// The event's time: the departure's for a departure, else the arrival's, each falling back on the other.
    func eventTime(departure wantsDeparture: Bool) -> Date? {
        wantsDeparture ? (departure ?? arrival) : (arrival ?? departure)
    }

    func eventDelay(departure wantsDeparture: Bool) -> Int? {
        wantsDeparture ? (departureDelaySeconds ?? arrivalDelaySeconds) : (arrivalDelaySeconds ?? departureDelaySeconds)
    }
}

public struct UrbanTripRealtime: Equatable, Sendable {
    public let tripID: String
    public let routeID: String?
    /// The GTFS service day (start_date) as yyyymmdd, nil when the feed omits it.
    public let startDate: Int?
    public let cancelled: Bool
    public let stops: [UrbanStopTimeRealtime]
    public let observedAt: Date

    /// Android's `expectedAt`: the expected time of the event scheduled at `scheduled` at `stopID`, from the stop's own
    /// update only (the one nearest the scheduled time when the trip calls twice); cancelled for a cancelled trip or
    /// a skipped stop; nil without an update there, or for a time more than `maximumShiftSeconds` away (another run).
    public func expected(at stopID: String, scheduled: Date, departure: Bool) -> UrbanExpectedTime? {
        if cancelled { return UrbanExpectedTime(expected: nil, cancelled: true) }
        let candidates = stops.filter { $0.stopID == stopID }
        guard !candidates.isEmpty else { return nil }
        var nearest: UrbanStopTimeRealtime?
        var nearestDistance = Double.infinity
        for candidate in candidates {
            let distance = candidate.eventTime(departure: departure).map { abs($0.timeIntervalSince(scheduled)).rounded(.down) } ?? .infinity
            if nearest == nil || distance < nearestDistance {
                nearest = candidate
                nearestDistance = distance
            }
        }
        guard let stop = nearest else { return nil }
        if stop.skipped { return UrbanExpectedTime(expected: nil, cancelled: true) }
        guard let expected = stop.eventTime(departure: departure)
            ?? stop.eventDelay(departure: departure).map({ scheduled.addingTimeInterval(TimeInterval($0)) }) else { return nil }
        guard abs(expected.timeIntervalSince(scheduled)) <= SharedData.RealtimeFeeds.urbanFetch.maximumShiftSeconds else { return nil }
        return UrbanExpectedTime(expected: expected, cancelled: false)
    }
}

/// What a board row or a journey leg shows of a trip at one stop.
public struct UrbanExpectedTime: Equatable, Sendable {
    public let expected: Date?
    public let cancelled: Bool
}

/// The Trip Updates of one city feed kept for the city pack's routes, by trip_id.
public struct UrbanRealtimeSnapshot: Equatable, Sendable {
    public let feedID: String
    /// The feed header's timestamp, else when the feed was received.
    public let observedAt: Date
    public let fetchedAt: Date
    public let trips: [String: [UrbanTripRealtime]]
    let zone: TimeZone

    /// Android's `trip`: the update of `tripID` on the service day `serviceDate` (yyyymmdd): the one with that
    /// start_date or, when the feed publishes none, only on the day of the observation.
    public func trip(_ tripID: String, serviceDate: Int) -> UrbanTripRealtime? {
        guard let candidates = trips[tripID] else { return nil }
        if let dated = candidates.first(where: { $0.startDate == serviceDate }) { return dated }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let day = calendar.dateComponents([.year, .month, .day], from: observedAt)
        let observedDay = (day.year ?? 0) * 10_000 + (day.month ?? 0) * 100 + (day.day ?? 0)
        return candidates.first { $0.startDate == nil && observedDay == serviceDate }
    }

    /// Live while observed in the last five minutes and at most a minute ahead of `now`.
    public func isFresh(at now: Date) -> Bool {
        observedAt >= now.addingTimeInterval(-SharedData.RealtimeFeeds.observationWindowSeconds)
            && observedAt <= now.addingTimeInterval(SharedData.RealtimeFeeds.futureToleranceSeconds)
    }
}

/// Android's `UrbanGtfsRealtimeDecoder`: the trips of the city pack's routes (`keepRoute`; a trip without route_id is
/// kept, its trip_id still has to match the pack), scheduled or cancelled; added, unscheduled and duplicated trips
/// have no timetable run and are dropped, and so are NO_DATA stops. Nil for a feed that is not GTFS-Realtime
/// (malformed, or a header without a version).
public enum UrbanRealtimeDecoder {
    public static func decode(
        _ data: Data, feed: UrbanRealtimeFeeds.Feed, fetchedAt: Date, keepRoute: (String) -> Bool = { _ in true }
    ) -> UrbanRealtimeSnapshot? {
        guard let message = GTFSRealtimeDecoder.decode(data),
              !message.version.unicodeScalars.allSatisfy({ $0.properties.isWhitespace }),
              let observedAt = instant(message.timestamp, fallback: fetchedAt) else { return nil }
        var trips: [String: [UrbanTripRealtime]] = [:]
        var kept = 0
        for entity in message.entities {
            guard let update = entity.tripUpdate, !entity.isDeleted else { continue }
            let tripID = update.trip.tripID
            guard !isBlank(tripID) else { continue }
            let routeID: String? = isBlank(update.trip.routeID) ? nil : update.trip.routeID
            if let routeID, !keepRoute(routeID) { continue }
            let cancelled = update.trip.scheduleRelationship == .canceled
            if !cancelled, update.trip.scheduleRelationship != .scheduled { continue }
            let dateText: String? = isBlank(update.trip.startDate) ? nil : update.trip.startDate
            let startDate = dateText.flatMap(GTFSRealtimeStopUpdates.basicISODate)
            // A start_date that is not a date cannot be tied to a service day.
            if dateText != nil, startDate == nil { continue }
            var stops: [UrbanStopTimeRealtime] = []
            for stop in update.stopTimeUpdates where stop.scheduleRelationship != .noData {
                let stopID: String? = isBlank(stop.stopID) ? nil : stop.stopID
                let sequence = stop.stopSequence.map { Int($0) }
                guard stopID != nil || sequence != nil else { continue }
                stops.append(UrbanStopTimeRealtime(
                    stopID: stopID, stopSequence: sequence,
                    arrival: positiveInstant(stop.arrival?.time), departure: positiveInstant(stop.departure?.time),
                    arrivalDelaySeconds: stop.arrival?.delay.map { Int($0) },
                    departureDelaySeconds: stop.departure?.delay.map { Int($0) },
                    skipped: stop.scheduleRelationship == .skipped
                ))
            }
            if !cancelled, stops.isEmpty { continue }
            guard let tripObserved = instant(update.timestamp, fallback: observedAt) else { return nil }
            trips[tripID, default: []].append(UrbanTripRealtime(
                tripID: tripID, routeID: routeID, startDate: startDate, cancelled: cancelled, stops: stops, observedAt: tripObserved
            ))
            kept += 1
            if kept >= SharedData.RealtimeFeeds.urbanFetch.maximumTrips { break }
        }
        let zone = TimeZone(identifier: feed.zone) ?? TimeZone(secondsFromGMT: 3_600) ?? .current
        return UrbanRealtimeSnapshot(feedID: feed.id, observedAt: observedAt, fetchedAt: fetchedAt, trips: trips, zone: zone)
    }

    /// `seconds` as an instant when positive, else `fallback`; nil past Java's last instant (a parse error there).
    private static func instant(_ seconds: Int64?, fallback: Date) -> Date? {
        guard let seconds, seconds > 0 else { return fallback }
        guard seconds <= GTFSRealtimeStopUpdates.maximumInstantSecond else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(seconds))
    }

    /// A stop's absolute time when positive (and within Java's instants), nil otherwise.
    private static func positiveInstant(_ seconds: Int64?) -> Date? {
        guard let seconds, seconds > 0, seconds <= GTFSRealtimeStopUpdates.maximumInstantSecond else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(seconds))
    }

    private static func isBlank(_ value: String) -> Bool {
        value.unicodeScalars.allSatisfy { $0.properties.isWhitespace }
    }
}

/// The Trip Updates of one city feed, Android's `UrbanGtfsRealtimeAdapter`: asked only when a board or a journey of
/// its city wants them, at most every 30 seconds (concurrent askers share one request), the last snapshot kept two
/// minutes past its time after a failed refresh, the breaker and refusal pause of every public source (no status line
/// names it). HTTPS to the feed's host only, redirects on the same host.
public final class UrbanRealtimeFeedSource: @unchecked Sendable {
    public let feed: UrbanRealtimeFeeds.Feed
    private let client: LiveSourceClient
    private let gate: LiveSourceGate
    private let lock = NSLock()
    private var kept: UrbanRealtimeSnapshot?

    static let policy: LiveRequestPolicy = {
        let fetch = SharedData.RealtimeFeeds.urbanFetch
        return LiveRequestPolicy(
            accept: fetch.accept, requestTimeout: fetch.readTimeoutSeconds, totalTimeout: fetch.totalTimeoutSeconds,
            maximumBytes: fetch.maximumBytes, maximumRequests: fetch.maximumRequests, allowsSameHostRedirects: true
        )
    }()

    init(feed: UrbanRealtimeFeeds.Feed, client: LiveSourceClient, gate: LiveSourceGate) {
        self.feed = feed
        self.client = client
        self.gate = gate
    }

    public convenience init(
        feed: UrbanRealtimeFeeds.Feed, transport: any LiveHTTPTransport = URLSessionLiveTransport(),
        pauses: LiveSourcePauses = .shared, now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.init(
            feed: feed, client: LiveSourceClient(transport: transport, now: now),
            gate: LiveSourceGate(source: nil, configuration: .init(minimumInterval: 1, maximumEntries: 1), pauses: pauses, now: now)
        )
    }

    /// The latest snapshot of the feed's trips on `keepRoute`'s routes, or nil when the feed is unavailable.
    public func snapshot(keepRoute: @escaping @Sendable (String) -> Bool = { _ in true }) async -> UrbanRealtimeSnapshot? {
        let fetch = SharedData.RealtimeFeeds.urbanFetch
        let now = client.now()
        if let current = current(), now < current.fetchedAt.addingTimeInterval(fetch.timeToLiveSeconds) { return current }
        guard let url = URL(string: feed.url) else { return nil }
        let client = self.client
        let feed = self.feed
        let load: @Sendable () async -> LiveFetchResult = { [self] in
            switch await client.getData(url, policy: Self.policy) {
            case let .failure(failure):
                return .failure(failure)
            case let .body(data):
                let received = client.now()
                guard let snapshot = UrbanRealtimeDecoder.decode(data, feed: feed, fetchedAt: received, keepRoute: keepRoute) else {
                    return .failure(.network)
                }
                self.store(snapshot)
                return .body(LiveBody(text: "", fetchedAt: received))
            }
        }
        if case .body = await gate.fetch(feed.url, load: load), let fresh = current() { return fresh }
        // A failed refresh: the last snapshot, while it is not more than the stale time past its own.
        guard let stale = current(),
              client.now() <= stale.fetchedAt.addingTimeInterval(fetch.timeToLiveSeconds + fetch.maximumStaleSeconds) else { return nil }
        return stale
    }

    private func current() -> UrbanRealtimeSnapshot? {
        lock.lock()
        defer { lock.unlock() }
        return kept
    }

    private func store(_ snapshot: UrbanRealtimeSnapshot) {
        lock.lock()
        kept = snapshot
        lock.unlock()
    }
}
