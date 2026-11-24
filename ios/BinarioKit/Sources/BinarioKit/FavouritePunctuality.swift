import Foundation

// The punctuality of the favourite trains from the runs recorded on this iPhone (Android's
// FavouriteTrainPunctuality.kt, notifications/WeeklyDigest.kt and notifications/PunctualityRecorder.kt).
// The numbers are `SharedData.FavouritePunctuality`; the cases are
// contracts/shared-rules/v1/favourite-punctuality-v1.json.

/// The runs of one train recorded over some days: how many, how many on time, cancelled.
public struct LocalPunctuality: Equatable, Sendable {
    public let runs: Int
    public let onTime: Int
    public let cancelled: Int

    public init(runs: Int, onTime: Int, cancelled: Int) {
        self.runs = runs
        self.onTime = onTime
        self.cancelled = cancelled
    }

    public var onTimePercent: Int { runs > 0 ? Int((Double(onTime) * 100 / Double(runs)).rounded()) : 0 }
}

/// «In peggioramento» / «In miglioramento»: the last four weeks against the four before them.
public struct PunctualityTrend: Equatable, Sendable {
    public let recent: LocalPunctuality
    public let earlier: LocalPunctuality

    /// Exact comparison of the two on-time shares (never equal: a trend needs a gap).
    public var better: Bool { recent.onTime * earlier.runs > earlier.onTime * recent.runs }
}

public enum FavouriteTrainPunctuality {
    private typealias Table = SharedData.FavouritePunctuality
    public static let maximumRows = Table.maximumRows
    public static let personalDays = Table.personalDays
    public static let trendDays = Table.trendDays

    /// The runs of `trainNumber` recorded from `from` to `to`, both included; nil without any.
    public static func local(_ history: [PunctualityObservation], trainNumber: String, from: PlannedLocalDate, to: PlannedLocalDate) -> LocalPunctuality? {
        let key = PunctualityHistory.trainKey(trainNumber)
        let runs = history.filter { $0.trainNumber == key && $0.serviceDate >= from && $0.serviceDate <= to }
        guard !runs.isEmpty else { return nil }
        let onTimeMinutes = SharedData.PersonalPunctuality.onTimeMinutes
        return LocalPunctuality(
            runs: runs.count,
            onTime: runs.filter { !$0.cancelled && $0.delayMinutes <= onTimeMinutes }.count,
            cancelled: runs.filter(\.cancelled).count
        )
    }

    /// The trend at `today`, each span with enough runs and the shares far enough apart; else nil.
    public static func trend(_ history: [PunctualityObservation], trainNumber: String, today: PlannedLocalDate) -> PunctualityTrend? {
        guard let recentStart = today.adding(days: -(Table.trendDays - 1)),
              let earlierStart = recentStart.adding(days: -Table.trendDays),
              let earlierEnd = recentStart.adding(days: -1),
              let recent = local(history, trainNumber: trainNumber, from: recentStart, to: today), recent.runs >= Table.minimumTrendRuns,
              let earlier = local(history, trainNumber: trainNumber, from: earlierStart, to: earlierEnd), earlier.runs >= Table.minimumTrendRuns
        else { return nil }
        // |recent share − earlier share| × 100 ≥ the minimum, without rounding.
        let gap = abs(recent.onTime * earlier.runs - earlier.onTime * recent.runs) * 100
        guard gap >= Table.minimumTrendPoints * recent.runs * earlier.runs else { return nil }
        return PunctualityTrend(recent: recent, earlier: earlier)
    }
}

// MARK: Weekly digest

/// When the weekly digest comes: Sunday from 19:00, for the week Monday–Sunday that ends that day;
/// a run the system delays still notifies until Monday 12:00, never in the quiet hours (22–7).
public enum WeeklyDigestPlanner {
    private typealias Table = SharedData.FavouritePunctuality

    /// The Sunday whose digest may be shown at `now`, or nil outside Sunday 19:00 – Monday 12:00.
    public static func weekEnd(at now: RailWallClock) -> PlannedLocalDate? {
        let weekday = now.date.isoWeekday
        if weekday == Table.digestIsoWeekday, now.minuteOfDay >= Table.digestHour * 60 { return now.date }
        if weekday == Table.digestIsoWeekday % 7 + 1, now.minuteOfDay < Table.digestLatestHour * 60 { return now.date.adding(days: -1) }
        return nil
    }

    public static func quiet(_ now: RailWallClock) -> Bool {
        now.minuteOfDay >= Table.digestQuietFromHour * 60 || now.minuteOfDay < Table.digestQuietUntilHour * 60
    }

    public static func mayNotify(_ now: RailWallClock) -> Bool { weekEnd(at: now) != nil && !quiet(now) }

    /// The next run: now while this window's digest is still to send (7:00 in the quiet hours),
    /// else the next Sunday at 19:00.
    public static func nextRun(after now: RailWallClock, lastSent: PlannedLocalDate?) -> RailWallClock? {
        if let pending = weekEnd(at: now), pending != lastSent {
            if !quiet(now) { return now }
            let morning: PlannedLocalDate? = now.minuteOfDay < Table.digestQuietUntilHour * 60 ? now.date : now.date.adding(days: 1)
            if let morning {
                let wake = RailWallClock(date: morning, minuteOfDay: Table.digestQuietUntilHour * 60)
                if weekEnd(at: wake) == pending { return wake }
            }
        }
        let days = (Table.digestIsoWeekday - now.date.isoWeekday + 7) % 7
        guard let sunday = now.date.adding(days: days) else { return nil }
        let run = RailWallClock(date: sunday, minuteOfDay: Table.digestHour * 60)
        if (run.date, run.minuteOfDay) > (now.date, now.minuteOfDay) { return run }
        return sunday.adding(days: 7).map { RailWallClock(date: $0, minuteOfDay: Table.digestHour * 60) }
    }

    public static func nextRunAt(_ now: Date, lastSent: PlannedLocalDate?) -> Date? {
        guard let clock = RailWallClock(now), let next = nextRun(after: clock, lastSent: lastSent) else { return nil }
        if next == clock { return now }
        guard let time = PlannedLocalTime(hour: next.minuteOfDay / 60, minute: next.minuteOfDay % 60) else { return nil }
        return ItalianWallClock.instant(date: next.date, time: time)
    }

    /// The week a run handles: with the setting on, notifications allowed, outside the quiet hours
    /// and not sent yet; nil otherwise.
    public static func handles(enabled: Bool, notificationsAllowed: Bool, now: RailWallClock, lastSent: PlannedLocalDate?) -> PlannedLocalDate? {
        guard enabled, notificationsAllowed, mayNotify(now), let week = weekEnd(at: now), week != lastSent else { return nil }
        return week
    }

    /// Monday of the week that ends on `weekEnd`.
    public static func weekStart(_ weekEnd: PlannedLocalDate) -> PlannedLocalDate? { weekEnd.adding(days: -6) }
}

/// A favourite train's figures in the bundled network statistics (Android's `ReliabilityMark`).
public struct DigestNetwork: Equatable, Sendable {
    public let onTime: Int
    public let samples: Int
    public let limitMinutes: Int
    /// Samples at most 5 minutes late, the limit of the runs recorded on the phone; nil when unknown.
    public let onTimeWithin5: Int?
    public let days: Int

    public init(onTime: Int, samples: Int, limitMinutes: Int, onTimeWithin5: Int?, days: Int) {
        self.onTime = onTime
        self.samples = samples
        self.limitMinutes = limitMinutes
        self.onTimeWithin5 = onTimeWithin5
        self.days = days
    }

    public var percent: Int { samples > 0 ? Int((Double(onTime) * 100 / Double(samples)).rounded()) : 0 }

    public var within5Percent: Int? {
        guard let onTimeWithin5, samples > 0 else { return nil }
        return Int((Double(onTimeWithin5) * 100 / Double(samples)).rounded())
    }
}

/// One favourite train in the digest: its runs this week and in the four weeks before, and its
/// network figures when the statistics have them.
public struct DigestTrain: Equatable, Sendable {
    public let number: String
    /// The name the user gave it, if any.
    public let name: String?
    public let week: LocalPunctuality?
    public let earlier: LocalPunctuality?
    public let network: DigestNetwork?

    public init(number: String, name: String?, week: LocalPunctuality?, earlier: LocalPunctuality?, network: DigestNetwork?) {
        self.number = number
        self.name = name
        self.week = week
        self.earlier = earlier
        self.network = network
    }
}

/// The train whose on-time share dropped most this week, against its usual share.
public struct DigestWorse: Equatable, Sendable {
    public let train: DigestTrain
    public let weekPercent: Int
    public let usualPercent: Int
}

public struct WeeklyDigest: Equatable, Sendable {
    public let weekEnd: PlannedLocalDate
    public let trains: [DigestTrain]
    public let worse: DigestWorse?

    public var runs: Int { trains.reduce(0) { $0 + ($1.week?.runs ?? 0) } }
    public var onTime: Int { trains.reduce(0) { $0 + ($1.week?.onTime ?? 0) } }
}

public enum WeeklyDigestBuilder {
    private typealias Table = SharedData.FavouritePunctuality

    /// The digest of the week ending on `weekEnd`, or nil when no favourite has a run recorded this
    /// week or network statistics.
    public static func build(
        favourites: [(number: String, name: String?)],
        history: [PunctualityObservation],
        weekEnd: PlannedLocalDate,
        network: (String) -> DigestNetwork?
    ) -> WeeklyDigest? {
        guard let weekStart = WeeklyDigestPlanner.weekStart(weekEnd),
              let earlierStart = weekStart.adding(days: -Table.digestEarlierDays),
              let earlierEnd = weekStart.adding(days: -1) else { return nil }
        let trains = favourites.map { favourite in
            DigestTrain(
                number: favourite.number,
                name: favourite.name,
                week: FavouriteTrainPunctuality.local(history, trainNumber: favourite.number, from: weekStart, to: weekEnd),
                earlier: FavouriteTrainPunctuality.local(history, trainNumber: favourite.number, from: earlierStart, to: earlierEnd),
                network: network(favourite.number)
            )
        }.filter { $0.week != nil || $0.network != nil }
        guard !trains.isEmpty else { return nil }
        return WeeklyDigest(weekEnd: weekEnd, trains: trains, worse: worse(trains))
    }

    /// The largest drop of a train's on-time share this week (enough runs) against its earlier weeks
    /// (enough runs) or, without them, its network share within 5 minutes; at least the minimum drop.
    public static func worse(_ trains: [DigestTrain]) -> DigestWorse? {
        let candidates: [DigestWorse] = trains.compactMap { train in
            guard let week = train.week, week.runs >= Table.digestMinimumWeekRuns else { return nil }
            let usual: Int
            if let earlier = train.earlier, earlier.runs >= Table.digestMinimumEarlierRuns {
                usual = earlier.onTimePercent
            } else if let network = train.network?.within5Percent {
                usual = network
            } else {
                return nil
            }
            guard usual - week.onTimePercent >= Table.digestMinimumDropPoints else { return nil }
            return DigestWorse(train: train, weekPercent: week.onTimePercent, usualPercent: usual)
        }
        // The largest drop, then the most runs this week, then the lower number.
        return candidates.max { left, right in
            let leftDrop = left.usualPercent - left.weekPercent
            let rightDrop = right.usualPercent - right.weekPercent
            if leftDrop != rightDrop { return leftDrop < rightDrop }
            let leftRuns = left.train.week?.runs ?? 0
            let rightRuns = right.train.week?.runs ?? 0
            if leftRuns != rightRuns { return leftRuns < rightRuns }
            return left.train.number > right.train.number
        }
    }
}

// MARK: Recording in the background

/// What the recorder knows of one favourite train: its saved departure and what it learned or did.
public struct RecordingCandidate: Equatable, Sendable {
    public let number: String
    public let departure: PlannedLocalTime?
    /// Minutes from the departure to the scheduled arrival at the last stop, from a lookup.
    public var durationMinutes: Int?
    public var recordedOn: PlannedLocalDate?
    public var attemptDate: PlannedLocalDate?
    public var attempts: Int
    public var lastAttempt: Date?

    public init(number: String, departure: PlannedLocalTime?, durationMinutes: Int? = nil, recordedOn: PlannedLocalDate? = nil,
                attemptDate: PlannedLocalDate? = nil, attempts: Int = 0, lastAttempt: Date? = nil) {
        self.number = number
        self.departure = departure
        self.durationMinutes = durationMinutes
        self.recordedOn = recordedOn
        self.attemptDate = attemptDate
        self.attempts = attempts
        self.lastAttempt = lastAttempt
    }
}

/// When the final delay of each favourite train is recorded: once a day, 20 minutes after its
/// scheduled arrival (two hours after its departure until the duration is known, 23:15 for a train
/// saved without one); a failed or early attempt is retried after 45 minutes, three times a day.
public enum PunctualityRecordingPlanner {
    private typealias Table = SharedData.FavouritePunctuality

    public static func dueAt(_ candidate: RecordingCandidate, date: PlannedLocalDate) -> Date? {
        if candidate.recordedOn == date { return nil }
        let attempts = candidate.attemptDate == date ? candidate.attempts : 0
        guard attempts < Table.recordingMaximumAttempts else { return nil }
        var planned: Date
        if let departure = candidate.departure {
            guard let start = ItalianWallClock.instant(date: date, time: departure) else { return nil }
            if let duration = candidate.durationMinutes {
                planned = start.addingTimeInterval(TimeInterval((duration + Table.recordingAfterArrivalMinutes) * 60))
            } else {
                planned = start.addingTimeInterval(TimeInterval(Table.recordingFirstTryAfterDepartureMinutes * 60))
            }
        } else {
            let minute = Table.recordingWithoutDepartureMinute
            guard let time = PlannedLocalTime(hour: minute / 60, minute: minute % 60),
                  let late = ItalianWallClock.instant(date: date, time: time) else { return nil }
            planned = late
        }
        if attempts > 0, let last = candidate.lastAttempt {
            let retry = last.addingTimeInterval(TimeInterval(Table.recordingRetryMinutes * 60))
            if retry > planned { planned = retry }
        }
        return planned
    }

    /// The runs to record at `now`: yesterday's late arrivals and today's, with their service day.
    public static func due(_ candidates: [RecordingCandidate], now: Date) -> [(candidate: RecordingCandidate, date: PlannedLocalDate)] {
        guard let today = ItalianWallClock.date(of: now), let yesterday = today.adding(days: -1) else { return [] }
        let oldest = now.addingTimeInterval(-TimeInterval(Table.recordingLookbackHours * 3_600))
        var result: [(candidate: RecordingCandidate, date: PlannedLocalDate)] = []
        for candidate in candidates {
            for date in [yesterday, today] {
                guard let at = dueAt(candidate, date: date), at <= now, at > oldest else { continue }
                result.append((candidate: candidate, date: date))
            }
        }
        return result
    }

    /// The next run: now when one is due, else the next due instant of yesterday's, today's or
    /// tomorrow's run.
    public static func nextRun(_ candidates: [RecordingCandidate], now: Date) -> Date? {
        if !due(candidates, now: now).isEmpty { return now }
        guard let today = ItalianWallClock.date(of: now) else { return nil }
        let days = [today.adding(days: -1), today, today.adding(days: 1)].compactMap { $0 }
        return candidates.flatMap { candidate in days.compactMap { dueAt(candidate, date: $0) } }
            .filter { $0 > now }
            .min()
    }
}

extension DigestNetwork {
    /// A favourite train in the network statistics at its last stop (Android's
    /// `ReliabilityMarks.favourite`): nil without a match, below the minimum samples or for a train
    /// cancelled every day.
    public static func of(_ stats: NetworkReliabilityStats, number: String, originCode: String?) -> DigestNetwork? {
        guard let found = FavouriteTrainsReliability.match(stats, number: number, originCode: originCode),
              let stop = NetworkReliability.at(found.train, index: found.alightingIndex), !stop.cancelledEveryDay else { return nil }
        let within5 = NetworkReliabilityAggregates.distribution(found.train, index: found.alightingIndex)
            .map { min(max($0.noDelay + $0.upTo5, 0), stop.samples) }
        return DigestNetwork(onTime: stop.onTime, samples: stop.samples, limitMinutes: stop.onTimeLimitMinutes, onTimeWithin5: within5, days: stats.coveredDays)
    }
}
