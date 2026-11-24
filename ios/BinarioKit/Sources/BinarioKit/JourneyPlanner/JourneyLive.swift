import Foundation

/// Expected times of a train at a named stop, from a train number lookup (Android's `LiveStopTimes`). Only verified
/// operational times count: a run without them gives nothing, so it is never presented as live.
public enum LiveStopTimes {
    /// Station names as different sources publish them ("Milano Centrale", "MILANO CENTRALE", "Milano C.le").
    public static func namesMatch(_ first: String, _ second: String) -> Bool {
        guard !first.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !second.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        return RailStopNameMatcher.matches(name: first, published: second) || RailStopNameMatcher.matches(name: second, published: first)
    }

    /// The index of the first stop named `name`, or nil.
    public static func stopNamed(_ stops: [LiveStopStatus], _ name: String) -> Int? {
        stops.firstIndex { namesMatch($0.stationName, name) }
    }

    /// The expected time at `station` near `scheduled`: the stop's verified expected time, else the scheduled time plus
    /// the run's verified delay when this source does not publish the stop. Nil without operational data.
    public static func expectedAt(_ run: LiveRunStatus, station: String, scheduled: Date) -> Date? {
        if let index = stopNamed(run.stops, station) {
            let stop = run.stops[index]
            guard stop.operationalStatusVerified else { return nil }
            return stop.expected
        }
        guard run.operationalStatusVerified else { return nil }
        return scheduled.addingTimeInterval(TimeInterval(max(run.delayMinutes ?? 0, 0) * 60))
    }

    /// The live data of a leg from `from` to `to` on `run`; nil without any.
    public static func live(_ run: LiveRunStatus, from: String, to: String, departure: Date, arrival: Date) -> JourneyLegLive? {
        let cancelled = run.state == .cancelled && run.operationalStatusVerified
        let result = JourneyLegLive(
            expectedDeparture: cancelled ? nil : expectedAt(run, station: from, scheduled: departure),
            expectedArrival: cancelled ? nil : expectedAt(run, station: to, scheduled: arrival),
            cancelled: cancelled
        )
        return result.hasData ? result : nil
    }
}

/// Which legs of the journeys get a live lookup (Android's `JourneyLiveCandidates`): train legs with a public number of
/// journeys with changes, running from `lookBack` before to `lookAhead` after now, at most `maximumTrains` trains.
/// Everything else keeps its timetable times.
public enum JourneyLiveCandidates {
    public static let lookBack = TimeInterval(SharedData.JourneyLive.lookBackMinutes * 60)
    public static let lookAhead = TimeInterval(SharedData.JourneyLive.lookAheadMinutes * 60)
    public static let maximumTrains = SharedData.JourneyLive.maximumTrains
    /// Runs of one number further apart than this at the boarding stop are two runs: the number search also returns
    /// yesterday's run of a night train, still running today.
    public static let sameRunTolerance = TimeInterval(SharedData.JourneyLive.sameRunToleranceMinutes * 60)

    /// A train asked by number: its number and its day (yyyymmdd, Italian time).
    public struct Train: Hashable, Sendable {
        public let number: String
        public let serviceDate: Int
    }

    public static func legs(_ options: [JourneyPlanOption], now: Date) -> [JourneyPlanLeg] {
        options.filter { $0.transfers > 0 }.flatMap(\.legs).filter { leg in
            leg.mode == .train && !(leg.publicNumber ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && leg.arrival >= now.addingTimeInterval(-lookBack) && leg.departure <= now.addingTimeInterval(lookAhead)
        }
    }

    public static func train(_ leg: JourneyPlanLeg) -> Train {
        Train(number: (leg.publicNumber ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
              serviceDate: JourneyResultsMerge.encodedDay(leg.departure))
    }

    /// Each number and day once, in order, at most `maximumTrains`.
    public static func trains(_ legs: [JourneyPlanLeg]) -> [Train] {
        var seen: Set<Train> = []
        var trains: [Train] = []
        for leg in legs {
            let train = train(leg)
            if seen.insert(train).inserted { trains.append(train) }
        }
        return Array(trains.prefix(maximumTrains))
    }

    /// The live data of `leg` among the runs found for its number, or nil when none is its run or none is live. The run
    /// stops at both ends in order and is scheduled at the boarding stop near the leg's departure (today's run, not
    /// yesterday's still on its way); of those, one with verified live data first, then the nearest in time. A single
    /// run whose stops cannot be matched is taken only when it starts on the leg's service day or the day after.
    public static func liveFor(_ leg: JourneyPlanLeg, runs: [LiveRunStatus]) -> JourneyLegLive? {
        let from = leg.originLabel
        let to = leg.destinationLabel
        struct Candidate {
            let run: LiveRunStatus
            let board: Int?
            let offset: TimeInterval?
            var otherRun: Bool { offset.map { abs($0) > JourneyLiveCandidates.sameRunTolerance } ?? false }
        }
        func candidate(_ run: LiveRunStatus) -> Candidate {
            let board = LiveStopTimes.stopNamed(run.stops, from)
            return Candidate(run: run, board: board, offset: board.map { run.stops[$0].scheduled.timeIntervalSince(leg.departure) })
        }
        let found = runs.map(candidate).filter { found in
            guard let board = found.board, let end = LiveStopTimes.stopNamed(found.run.stops, to) else { return false }
            return end > board && !found.otherRun
        }
        let chosen: LiveRunStatus?
        if !found.isEmpty {
            chosen = found.enumerated().min { first, second in
                let a = first.element, b = second.element
                let verifiedA = a.board.map { a.run.stops[$0].operationalStatusVerified } ?? a.run.operationalStatusVerified
                let verifiedB = b.board.map { b.run.stops[$0].operationalStatusVerified } ?? b.run.operationalStatusVerified
                if verifiedA != verifiedB { return verifiedA }
                if (a.offset == nil) != (b.offset == nil) { return a.offset != nil }
                let distanceA = abs(a.offset ?? 0), distanceB = abs(b.offset ?? 0)
                if distanceA != distanceB { return distanceA < distanceB }
                return first.offset < second.offset
            }?.element.run
        } else if runs.count == 1, let single = runs.first, !candidate(single).otherRun, startsOnServiceDay(single, leg) {
            chosen = single
        } else {
            chosen = nil
        }
        guard let chosen else { return nil }
        return LiveStopTimes.live(chosen, from: from, to: to, departure: leg.departure, arrival: leg.arrival)
    }

    /// The run starts on the leg's service day or the day after; true when the leg's day is unknown.
    private static func startsOnServiceDay(_ run: LiveRunStatus, _ leg: JourneyPlanLeg) -> Bool {
        guard let day = leg.serviceDate else { return true }
        let start = JourneyResultsMerge.encodedDay(run.scheduledDeparture)
        return start >= day && start <= nextDay(day)
    }

    private static func nextDay(_ day: Int) -> Int {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Rome") ?? .current
        let parts = DateComponents(year: day / 10_000, month: day / 100 % 100, day: day % 100, hour: 12)
        guard let noon = calendar.date(from: parts), let next = calendar.date(byAdding: .day, value: 1, to: noon) else { return day }
        return JourneyResultsMerge.encodedDay(next)
    }
}

/// How the live data of the legs stays current while the results or a journey are on screen (Android's
/// `JourneyLiveRefresh`): the lookup is repeated at most every `minimumInterval` by the periodic refresh, its answers
/// replace the earlier ones and an answer not confirmed again is shown for `maximumAge` at most.
public enum JourneyLiveRefresh {
    public static let maximumAge = TimeInterval(SharedData.JourneyLive.maximumAgeMinutes * 60)
    public static let minimumInterval = TimeInterval(SharedData.JourneyLive.minimumIntervalSeconds)

    /// The legs whose lookup (`fetchedAt`, by leg key) is not older than `maximumAge` at `now`.
    public static func current(_ legs: [String: JourneyLegLive], fetchedAt: [String: Date], now: Date) -> [String: JourneyLegLive] {
        legs.filter { key, _ in fetchedAt[key].map { now.timeIntervalSince($0) <= maximumAge } ?? false }
    }

    /// A periodic lookup is due when none started in the last `minimumInterval`.
    public static func isDue(lastStartedAt: Date?, now: Date) -> Bool {
        guard let lastStartedAt else { return true }
        return now.timeIntervalSince(lastStartedAt) >= minimumInterval
    }

    /// `options` with the journey on screen first, so its trains are always among those looked up.
    public static func focusedFirst<Item>(_ options: [Item], focusedID: String?, id: (Item) -> String) -> [Item] {
        guard let focusedID else { return options }
        return options.filter { id($0) == focusedID } + options.filter { id($0) != focusedID }
    }
}

/// Live data for the legs of journeys with changes through the train number lookup of the live sources (Android's
/// `JourneyLiveLookup`), a few trains at a time, each within `trainTimeout`. A lookup that fails leaves its legs on the
/// timetable.
public struct JourneyLiveLookup: Sendable {
    public static let parallelLookups = SharedData.JourneyLive.parallelLookups
    public static let trainTimeout = TimeInterval(SharedData.JourneyLive.trainTimeoutSeconds)

    private let provider: any LiveTrainStatusProviding
    private let now: @Sendable () -> Date

    public init(provider: any LiveTrainStatusProviding, now: @escaping @Sendable () -> Date = { Date() }) {
        self.provider = provider
        self.now = now
    }

    /// The live data of the legs of `options`, by `TransferRiskCalculator.legKey`.
    public func lookup(_ options: [JourneyPlanOption]) async -> [String: JourneyLegLive] {
        let legs = JourneyLiveCandidates.legs(options, now: now())
        guard !legs.isEmpty else { return [:] }
        let trains = JourneyLiveCandidates.trains(legs)
        var days: [JourneyLiveCandidates.Train: Date] = [:]
        for leg in legs where days[JourneyLiveCandidates.train(leg)] == nil {
            days[JourneyLiveCandidates.train(leg)] = leg.departure
        }
        var found: [JourneyLiveCandidates.Train: [LiveRunStatus]] = [:]
        let provider = self.provider
        var start = 0
        while start < trains.count {
            let chunk = Array(trains[start..<min(start + Self.parallelLookups, trains.count)])
            start += Self.parallelLookups
            let answers = await withTaskGroup(of: (JourneyLiveCandidates.Train, [LiveRunStatus]).self,
                                              returning: [(JourneyLiveCandidates.Train, [LiveRunStatus])].self) { group in
                for train in chunk {
                    let request = LiveTrainStatusRequest(number: train.number, serviceDate: days[train] ?? Date())
                    group.addTask {
                        let answer = await LiveTasks.withDeadline(seconds: Self.trainTimeout) { await provider.trainStatus(request) }
                        guard case let .available(lookup)? = answer else { return (train, []) }
                        return (train, lookup.runs)
                    }
                }
                var collected: [(JourneyLiveCandidates.Train, [LiveRunStatus])] = []
                for await answer in group { collected.append(answer) }
                return collected
            }
            for (train, runs) in answers { found[train] = runs }
        }
        var live: [String: JourneyLegLive] = [:]
        for leg in legs {
            guard let runs = found[JourneyLiveCandidates.train(leg)],
                  let value = JourneyLiveCandidates.liveFor(leg, runs: runs) else { continue }
            live[TransferRiskCalculator.legKey(leg)] = value
        }
        return live
    }
}
