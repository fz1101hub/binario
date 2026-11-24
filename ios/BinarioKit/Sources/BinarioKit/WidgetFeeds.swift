import Foundation

// What the three home-screen widgets load, from the sources the app uses: the bundled timetable index
// for the runs, and, when «Dati in tempo reale» is on, the same live sources (`LiveRailData`) for
// their live values, laid over the timetable rows as the planner and «Partenze» lay them
// (`LiveOverlay`). Nothing invents a train, a time or a platform: without a live value a row is the
// timetable's, «PROGRAM.».

/// How one favourite train's lookup ended.
public enum WidgetTrainLookup: Equatable, Sendable {
    case found(WidgetTrainStatus)
    /// The sources answered and none of their runs is this one.
    case notFound
    /// No source answered.
    case failed
}

public enum WidgetFeeds {
    /// Android's three trains asked at a time, each within the train search's time limit.
    static let parallelLookups = 3
    static let lookupDeadline: TimeInterval = 20
    /// Two runs of a number this far apart are two runs (the app's «I miei treni»).
    static let sameRunTolerance: TimeInterval = 2 * 3_600

    // MARK: Tratta

    /// The runs of `origin` → `destination` in `direction` from `now`, with the extra timetable pages of
    /// `timeRange` (Android's `RouteWidgetRefresher.fetch`), each with its arrival at the searched
    /// destination when the index gives it, and the live values of the origin's live board when `live`.
    public static func route(
        origin: Station, destination: Station, direction: WidgetDirection, timeRange: WidgetTimeRange, now: Date,
        repository: any JourneyRepository, trips: (any TimetableTripReading)?, live: (any LiveDeparturesProviding)?
    ) async -> WidgetRouteSnapshot {
        let from = direction == .outbound ? origin : destination
        let to = direction == .outbound ? destination : origin
        let fromIdentity = StationIdentity(from)
        let toIdentity = StationIdentity(to)
        var services: [TrainService] = []
        var seen = Set<String>()
        var failure: WidgetFailure?
        var start = StationBoardQuery.minuteStart(of: now)
        var pages = 0
        paging: while true {
            guard let query = try? JourneyQuery(origin: fromIdentity, destination: toIdentity, departure: start) else {
                failure = .noJourney
                break paging
            }
            switch await repository.journeys(for: query) {
            case let .available(snapshot):
                let fresh = snapshot.services.filter { seen.insert($0.id).inserted }
                if fresh.isEmpty { break paging }
                services += fresh
            case let .unavailable(reason):
                if services.isEmpty { failure = Self.failure(reason) }
                break paging
            }
            let latest = services.compactMap { departure(of: $0, reference: now) }.max()
            guard let next = timeRange.nextPage(now: now, latestDeparture: latest, pagesDone: pages) else { break paging }
            pages += 1
            start = next
        }
        let arrivals = trips.map { Self.arrivals(services, from: fromIdentity, to: toIdentity, trips: $0) } ?? [:]

        var liveValues: [String: WidgetLiveValues] = [:]
        var sources: [String] = []
        var liveUnavailable = false
        if let live, !services.isEmpty, !UrbanStops.isStopID(from.id) {
            let request = LiveDeparturesRequest(station: from, from: StationBoardQuery.minuteStart(of: now))
            switch await live.departures(request) {
            case let .available(board):
                let placed: [(service: TrainService, departure: Date?)] = services.map { ($0, departure(of: $0, reference: now)) }
                let planned: [LiveOverlay.PlannedTrain?] = placed.map { entry -> LiveOverlay.PlannedTrain? in
                    guard entry.service.hasTrainNumber, let scheduled = entry.departure else { return nil }
                    return LiveOverlay.PlannedTrain(number: entry.service.number, scheduled: scheduled, operatorName: entry.service.operatorName)
                }
                let matched = LiveOverlay.departures(for: planned, in: board.departures)
                for (index, entry) in placed.enumerated() {
                    guard let row = matched[index], let status = row.stationBoardLiveStatus(at: now, timetable: entry.departure) else { continue }
                    liveValues[entry.service.id] = WidgetLiveValues(status, scheduledPlatform: row.scheduledPlatform)
                }
                sources = board.sources.map(\.displayName)
            case .unavailable:
                liveUnavailable = true
            }
        }

        let rows: [WidgetRouteRow] = services.prefix(SharedData.Widgets.dayCacheMaximumRows).map { service in
            WidgetRouteRow(
                id: service.id, number: service.number, category: service.category, operatorName: service.operatorName,
                destination: service.destination, scheduledTime: service.scheduledDeparture,
                departure: departure(of: service, reference: now), arrival: arrivals[service.id], isBus: service.isBus,
                group: WidgetTrainTypes.group(of: service), live: liveValues[service.id]
            )
        }
        return WidgetRouteSnapshot(
            originID: origin.id, destinationID: destination.id, direction: direction, timeRange: timeRange, rows: rows,
            retrievedAt: now, liveSources: sources, liveUnavailable: liveUnavailable, failure: failure
        )
    }

    /// The instant a listed train leaves: its service day and GTFS seconds when it is a train of the
    /// index, else its "HH:mm" on the day of `reference`.
    public static func departure(of service: TrainService, reference: Date) -> Date? {
        if let reference = TimetableServiceReference(serviceID: service.id),
           let instant = WidgetClock.gtfsInstant(serviceDate: reference.serviceDate, seconds: reference.seconds) {
            return instant
        }
        return LiveOverlay.scheduledDate(service.scheduledDeparture, on: reference)
    }

    /// The arrival of each listed train at the searched destination: its first call there after the
    /// listed origin, from the trip's stops in the index (the departure time there when the index has
    /// both, as `tripStops` reads them).
    static func arrivals(
        _ services: [TrainService], from origin: StationIdentity, to destination: StationIdentity, trips: any TimetableTripReading
    ) -> [String: Date] {
        var references: [String: TimetableServiceReference] = [:]
        for service in services {
            if let reference = TimetableServiceReference(serviceID: service.id) { references[service.id] = reference }
        }
        guard !references.isEmpty else { return [:] }
        let stops = trips.tripStops(Array(Set(references.values.map { $0.trip })))
        var arrivals: [String: Date] = [:]
        for (id, reference) in references {
            guard let trip = stops[reference.trip] else { continue }
            let boarding = trip.stops.firstIndex { $0.catalogStationID == origin.id && $0.seconds == reference.seconds }
                ?? trip.stops.firstIndex { $0.catalogStationID == origin.id }
            guard let boarding else { continue }
            let after = trip.stops[(boarding + 1)...]
            guard let stop = after.first(where: { $0.catalogStationID == destination.id }),
                  let seconds = stop.seconds, seconds >= reference.seconds,
                  let arrival = WidgetClock.gtfsInstant(serviceDate: reference.serviceDate, seconds: seconds) else { continue }
            arrivals[id] = arrival
        }
        return arrivals
    }

    // MARK: Tabellone stazione

    /// The next departures of `station` (Android keeps twelve), with the live board laid over them as
    /// «Partenze» lays it when `live`.
    public static func station(
        _ station: Station, now: Date, board: any StationBoardProvider, live: (any LiveDeparturesProviding)?
    ) async -> WidgetStationSnapshot {
        let identity = StationIdentity(station)
        guard let query = try? StationBoardQuery(
            station: identity, from: StationBoardQuery.minuteStart(of: now), limit: WidgetStationRules.storedDepartures
        ) else {
            return WidgetStationSnapshot(
                stationID: station.id, stationName: station.name, rows: [], retrievedAt: now, liveSources: [],
                liveUnavailable: false, failure: .noJourney
            )
        }
        var rows: [StationBoardDeparture] = []
        var failure: WidgetFailure?
        switch await board.departures(for: query) {
        case let .available(page):
            rows = page.departures
        case let .unavailable(reason):
            failure = Self.failure(reason)
        }
        var sources: [String] = []
        var liveUnavailable = false
        var scheduledPlatforms: [String: String] = [:]
        if let live, !UrbanStops.isStopID(station.id) {
            switch await live.departures(LiveDeparturesRequest(station: station, from: StationBoardQuery.minuteStart(of: now))) {
            case let .available(liveBoard):
                rows = LiveOverlay.stationBoard(rows, with: liveBoard, at: now)
                sources = liveBoard.sources.map(\.displayName)
                // The timetable platform the same source publishes next to the live one («BIN 7 era 5»).
                let planned: [LiveOverlay.PlannedTrain?] = rows.map { row -> LiveOverlay.PlannedTrain? in
                    row.service.hasTrainNumber
                        ? LiveOverlay.PlannedTrain(number: row.service.number, scheduled: row.departure, operatorName: row.service.operatorName)
                        : nil
                }
                let matched = LiveOverlay.departures(for: planned, in: liveBoard.departures)
                for (index, row) in rows.enumerated() {
                    if let platform = matched[index]?.scheduledPlatform { scheduledPlatforms[row.id] = platform }
                }
            case .unavailable:
                liveUnavailable = true
            }
        }
        let threshold = now.addingTimeInterval(-WidgetRouteRules.departedGrace)
        let kept = rows.filter { ($0.live?.expectedDeparture ?? $0.departure) >= threshold }
        let widgetRows: [WidgetBoardRow] = kept.prefix(WidgetStationRules.storedDepartures).map { row in
            WidgetBoardRow(
                id: row.id, number: row.service.hasTrainNumber ? row.service.number : nil, category: row.service.category,
                operatorName: row.service.operatorName, destination: row.service.destination, scheduled: row.departure,
                isBus: row.service.isBus, group: WidgetTrainTypes.group(of: row.service),
                live: row.live.map { WidgetLiveValues($0, scheduledPlatform: scheduledPlatforms[row.id]) }
            )
        }
        return WidgetStationSnapshot(
            stationID: station.id, stationName: station.name, rows: widgetRows, retrievedAt: now, liveSources: sources,
            liveUnavailable: liveUnavailable, failure: widgetRows.isEmpty ? failure : nil
        )
    }

    // MARK: I miei treni

    /// The favourites a refresh asks for, with the service day of each (Android's
    /// `TrainsWidgetLookupPlanner`): near their run for an automatic refresh, every one for a tap.
    public static func trainsToAsk(
        _ favourites: [FavouriteTrain], statuses: [String: WidgetTrainStatus], now: Date, automatic: Bool
    ) -> [(train: FavouriteTrain, day: PlannedLocalDate)] {
        favourites.compactMap { train -> (train: FavouriteTrain, day: PlannedLocalDate)? in
            let previous = statuses[train.id]
            guard let day = WidgetTrainsRules.lookupDate(
                savedDeparture: train.scheduledDeparture, lastDeparture: previous?.scheduledDeparture,
                lastRun: previous?.run, now: now, automatic: automatic
            ) else { return nil }
            return (train: train, day: day)
        }
    }

    /// The runs of `trains` on their days, three at a time, each within its time limit.
    public static func lookUp(
        _ trains: [(train: FavouriteTrain, day: PlannedLocalDate)], now: Date, live: any LiveTrainStatusProviding
    ) async -> [String: WidgetTrainLookup] {
        let inputs: [TrainLookupInput] = trains.map { TrainLookupInput(train: $0.train, day: $0.day) }
        let deadline = lookupDeadline
        let results: [TrainLookupOutput] = await LiveTasks.boundedMap(inputs, maximumConcurrent: parallelLookups) {
            (input: TrainLookupInput) async -> TrainLookupOutput in
            let outcome: WidgetTrainLookup? = await LiveTasks.withDeadline(seconds: deadline) {
                await WidgetFeeds.train(input.train, on: input.day, now: now, live: live)
            }
            return TrainLookupOutput(id: input.train.id, lookup: outcome ?? .failed)
        }
        var byID: [String: WidgetTrainLookup] = [:]
        for result in results { byID[result.id] = result.lookup }
        return byID
    }

    /// The run of `favourite` on `day`: asked by its number proper and the day, the boarding station's
    /// code picking the run among those calling there within two hours of the saved departure, as the
    /// app's «I miei treni» asks it.
    public static func train(
        _ favourite: FavouriteTrain, on day: PlannedLocalDate, now: Date, live: any LiveTrainStatusProviding
    ) async -> WidgetTrainLookup {
        guard let time = PlannedLocalTime(hhmm: favourite.scheduledDeparture),
              let departure = WidgetClock.instant(day, minute: time.minutesOfDay) else { return .notFound }
        let code = NetworkReliability.stationCode(favourite.origin)
        let request = LiveTrainStatusRequest(
            number: NetworkReliability.numberKey(favourite.number), serviceDate: departure, stationCode: code
        )
        switch await live.trainStatus(request) {
        case let .available(lookup):
            let sameRun = lookup.runs.filter { run in
                guard let stop = LiveOverlay.stop(in: run, stationCode: code, stationName: favourite.origin.name) else { return false }
                let scheduled: Date = stop.scheduledDeparture ?? stop.scheduled
                return abs(scheduled.timeIntervalSince(departure)) <= sameRunTolerance
            }
            let candidates = LiveTrainStatusLookup(runs: sameRun, unavailableSources: lookup.unavailableSources, isComplete: lookup.isComplete)
            guard let run = candidates.run(for: request) else { return .notFound }
            return .found(status(of: run, favourite: favourite, now: now))
        case .unavailable(.notFound), .unavailable(.notCovered), .unavailable(.invalidRequest):
            return .notFound
        case .unavailable:
            return .failed
        }
    }

    /// Android's `FavouriteTrainStatusMapper.fromService` for a live run.
    static func status(of run: LiveRunStatus, favourite: FavouriteTrain, now: Date) -> WidgetTrainStatus {
        let verified = run.operationalStatusVerified
        let last = run.stops.last
        let first = run.stops.first
        let next = run.stops.first { !$0.passed && !$0.skipped }
        let nextVerified = next?.operationalStatusVerified ?? verified
        let lastPassed = run.stops.last { $0.passed }
        let shownStop = next ?? first
        var platform = shownStop?.platform
        var scheduledPlatform = shownStop?.platform == nil ? nil : shownStop?.scheduledPlatform
        if platform == nil {
            platform = run.platform
            scheduledPlatform = run.platform == nil ? nil : run.scheduledPlatform
        }
        var lastDetectionStation: String? = run.lastObservation?.stationName
        var lastDetectionTime: String? = run.lastObservation.map { WidgetClock.hhmm($0.at) }
        if lastDetectionStation == nil, verified, let passed = lastPassed {
            lastDetectionStation = passed.stationName
            lastDetectionTime = WidgetClock.hhmm(passed.actual ?? passed.expected)
        }
        let arrival: Date? = last.map { $0.operationalStatusVerified ? $0.expected : $0.scheduled }
        return WidgetTrainStatus(
            retrievedAt: now,
            category: run.category.isEmpty ? favourite.category : run.category,
            operatorName: run.operatorName.isEmpty ? favourite.operatorName : run.operatorName,
            origin: run.originName,
            destination: run.destinationName,
            scheduledDeparture: favourite.scheduledDeparture,
            departure: run.scheduledDeparture,
            arrival: arrival,
            delayMinutes: verified && run.cancellation != .full ? run.delayMinutes : nil,
            cancelled: verified && run.cancellation == .full,
            variation: run.cancellation == .partial || run.stops.contains { $0.skipped || $0.added },
            nextStation: next?.stationName,
            nextStationTime: next.map { WidgetClock.hhmm(nextVerified ? $0.expected : $0.scheduled) },
            lastDetectionStation: lastDetectionStation,
            lastDetectionTime: lastDetectionTime,
            platform: platform,
            scheduledPlatform: scheduledPlatform,
            departed: verified ? (run.lastObservation != nil || first?.passed == true) : nil,
            arrived: verified ? (last?.passed == true) : nil,
            operationalVerified: verified,
            providerName: run.observation.source.displayName,
            stale: false
        )
    }

    /// The timetable of a favourite as the app saved it, for today, without live data (the switch is
    /// off): its departure and origin, «PROGRAM.».
    public static func timetableStatus(_ favourite: FavouriteTrain, now: Date) -> WidgetTrainStatus {
        var departure: Date?
        if let today = ItalianWallClock.date(of: now), let time = PlannedLocalTime(hhmm: favourite.scheduledDeparture) {
            departure = WidgetClock.instant(today, minute: time.minutesOfDay)
        }
        return WidgetTrainStatus(
            retrievedAt: now, category: favourite.category, operatorName: favourite.operatorName,
            origin: favourite.origin.name, destination: favourite.terminus, scheduledDeparture: favourite.scheduledDeparture,
            departure: departure, arrival: nil, delayMinutes: nil, cancelled: false, variation: false, nextStation: nil,
            nextStationTime: nil, lastDetectionStation: nil, lastDetectionTime: nil, platform: nil, scheduledPlatform: nil,
            departed: nil, arrived: nil, operationalVerified: false, providerName: "", stale: false
        )
    }

    private static func failure(_ reason: JourneyUnavailableReason) -> WidgetFailure {
        switch reason {
        case .noJourney: return .noJourney
        case .scheduleExpired: return .scheduleExpired
        case .noAuthorizedProvider, .providerInvalid: return .unavailable
        }
    }
}

/// One favourite to look up, sent to a lookup task.
struct TrainLookupInput: Sendable {
    let train: FavouriteTrain
    let day: PlannedLocalDate
}

/// How the lookup of one favourite ended.
struct TrainLookupOutput: Sendable {
    let id: String
    let lookup: WidgetTrainLookup
}
