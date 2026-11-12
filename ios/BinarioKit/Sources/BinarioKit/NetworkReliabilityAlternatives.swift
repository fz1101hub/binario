import Foundation

/// A listed train as «Più puntuale» weighs it: its key in the list, its scheduled departure where
/// the user boards, its reliability where the user gets off and whether it can still be taken.
public struct AlternativeCandidate: Equatable, Sendable {
    public let key: String
    /// Scheduled departure; nil when the time cannot be placed (never compared).
    public let departure: Date?
    /// The train at the user's destination (`NetworkReliability.at`); nil without statistics.
    public let stop: StopReliability?
    /// Not already gone from the boarding station.
    public let stillToDepart: Bool

    public init(key: String, departure: Date?, stop: StopReliability?, stillToDepart: Bool) {
        self.key = key
        self.departure = departure
        self.stop = stop
        self.stillToDepart = stillToDepart
    }
}

/// The train suggested instead of another one: its key in the list, its departure and its
/// reliability at the destination.
public struct ReliableAlternative: Equatable, Sendable {
    public let key: String
    public let departure: Date
    public let stop: StopReliability
}

/// «Più puntuale» under a listed train: the train of the same list to take instead, and its
/// reliability at the searched destination.
public struct ResultAlternative: Equatable, Sendable {
    public let service: TrainService
    public let stop: StopReliability
}

/// «Più puntuale», the rule of the Android app: for a train often late where the user gets off,
/// another train of the same list leaving around the same time that is clearly more often on time
/// there. Only trains measured against the same on-time limit are compared (5 minutes for the
/// high-speed categories, 15 for the others, `NetworkReliability.onTimeLimitMinutes`): a regional
/// on time within 15 minutes is not «more punctual» than a Frecciarossa on time within 5, and the
/// two need different tickets anyway. Cancellations count too: the alternative must also be
/// clearly more likely to run and arrive on time on a day (`StopReliability.score`). Every share
/// is compared exactly, in integers.
public enum ReliableAlternatives {
    /// A train is helped only when it is on time in fewer than this many percent of its samples…
    public static let maximumOnTimePercent = 70
    /// …by a train whose on-time share is at least this many points higher…
    public static let minimumGapPoints = 20
    /// …leaving at most this many minutes before or after it.
    public static let windowMinutes = 30
    /// A train counts as gone this long after its departure: the scheduled one, or the one a live
    /// source moved it to (`stillToDepart(_:live:now:)`).
    public static let departedGraceSeconds: TimeInterval = 60

    /// The alternative of every train of `candidates` that has one, by the train's key. A train
    /// needs at least `NetworkReliability.minimumSamples` samples, an on-time share below
    /// `maximumOnTimePercent` percent and to be still to depart; the alternative too needs the
    /// samples and to be still to depart, has the same on-time limit, leaves within
    /// `windowMinutes` of it (scheduled times), is on time at least `minimumGapPoints` points more
    /// often and its `score` is at least `minimumGapPoints` points higher. Among several, the departure
    /// closest to the train's wins, then the earlier one, then the more punctual one, then the
    /// one with more samples.
    public static func find(_ candidates: [AlternativeCandidate]) -> [String: ReliableAlternative] {
        let eligible = candidates.filter { measured($0) }
        var found: [String: ReliableAlternative] = [:]
        for train in eligible {
            guard let stop = train.stop, oftenLate(stop),
                  let suggestion = bestAlternative(for: train, among: eligible)
            else { continue }
            found[train.key] = suggestion
        }
        return found
    }

    /// The alternative of each listed train of `snapshot` that has one, by the id of the train it
    /// is suggested for. `reliability` measures the trains at the searched destination
    /// (`JourneyReliability.byServiceID`): nothing without it. Every listed train is a direct
    /// train of the bundled timetables; `live` is what a live source observed of it where the user
    /// boards, by the same id (`AppModel.resultLive`), as the list shows it: a train cancelled
    /// there is never weighed, and a delay the source publishes moves its departure
    /// (`stillToDepart(_:live:now:)`), as on Android.
    public static func find(
        in snapshot: JourneySnapshot,
        reliability: [String: StopReliability],
        live: [String: StationBoardLiveStatus] = [:],
        now: Date
    ) -> [String: ResultAlternative] {
        guard !reliability.isEmpty else { return [:] }
        let departures = scheduledDepartures(in: snapshot)
        var servicesByID: [String: TrainService] = [:]
        var candidates: [AlternativeCandidate] = []
        for service in snapshot.services where servicesByID[service.id] == nil {
            servicesByID[service.id] = service
            let departure = departures[service.id]
            candidates.append(AlternativeCandidate(
                key: service.id,
                departure: departure,
                stop: reliability[service.id],
                stillToDepart: stillToDepart(departure, live: live[service.id], now: now)
            ))
        }
        var found: [String: ResultAlternative] = [:]
        for (key, suggestion) in find(candidates) {
            guard let service = servicesByID[suggestion.key] else { continue }
            found[key] = ResultAlternative(service: service, stop: suggestion.stop)
        }
        return found
    }

    /// The train can still be taken where the user boards: `now` is before its scheduled
    /// departure plus `departedGraceSeconds`. False without a departure.
    public static func stillToDepart(_ departure: Date?, now: Date) -> Bool {
        stillToDepart(departure, live: nil, now: now)
    }

    /// The train can still be taken where the user boards, with what a live source observed of it
    /// there (Android's `ResultsAlternatives.stillToDepart`): not cancelled, and `now` before its
    /// scheduled departure plus the delay the source publishes (never earlier) plus
    /// `departedGraceSeconds`. A train late by unpublished minutes (a bare «RITARDO») counts as
    /// gone once it is due. False without a departure.
    public static func stillToDepart(_ departure: Date?, live: StationBoardLiveStatus?, now: Date) -> Bool {
        if live?.cancelled == true { return false }
        guard let departure else { return false }
        let delaySeconds = TimeInterval(max(live?.delayMinutes ?? 0, 0) * 60)
        return now < departure.addingTimeInterval(delaySeconds + departedGraceSeconds)
    }

    /// The scheduled departure of each listed train, by its id: its "HH:mm" in the timetable zone
    /// (Rome) at its first occurrence from the query time on, the list being in departure order,
    /// as the iPhone places the times it sends to the Watch. A time that cannot be read has none.
    public static func scheduledDepartures(in snapshot: JourneySnapshot) -> [String: Date] {
        let calendar = NetworkReliabilityDay.romeCalendar
        // The repository lists trains from the query time on; "HH:mm" drops the seconds.
        var cursor = snapshot.query.departure.addingTimeInterval(-60)
        var departures: [String: Date] = [:]
        for service in snapshot.services {
            guard let departure = nextOccurrence(of: service.scheduledDeparture, after: cursor, calendar: calendar) else { continue }
            // Two trains at the same minute resolve to the same day.
            cursor = departure.addingTimeInterval(-1)
            departures[service.id] = departure
        }
        return departures
    }

    /// The suggested train as its row names it: the number alone when it already carries its
    /// line or category ("S9 - 24902", "REX 2204"), else the category before it ("REG 16000").
    public static func trainLabel(_ service: TrainService) -> String {
        let number = service.number.trimmingCharacters(in: .whitespaces)
        let category = service.category.trimmingCharacters(in: .whitespaces)
        if category.isEmpty || number.contains(where: { $0.isLetter }) { return number }
        return "\(category) \(number)"
    }

    /// On time in fewer than `maximumOnTimePercent` percent of the samples (exact, in integers).
    static func oftenLate(_ stop: StopReliability) -> Bool {
        let onTimeScaled = Int64(stop.onTime) * 100
        let limitScaled = Int64(maximumOnTimePercent) * Int64(stop.samples)
        return onTimeScaled < limitScaled
    }

    /// `better`'s on-time share is at least `minimumGapPoints` points above `worse`'s (exact, in
    /// integers).
    static func clearlyBetter(_ better: StopReliability, than worse: StopReliability) -> Bool {
        let betterScaled = Int64(better.onTime) * Int64(worse.samples)
        let worseScaled = Int64(worse.onTime) * Int64(better.samples)
        let gap = (betterScaled - worseScaled) * 100
        let needed = Int64(minimumGapPoints) * Int64(better.samples) * Int64(worse.samples)
        return gap >= needed
    }

    /// `better`'s `score` (runs and is on time, cancelled days included) is at least
    /// `minimumGapPoints` points above `worse`'s, exact in integers: the score is
    /// (days − cancelled) × on time ÷ (days × samples), with no cancellation counted when no day
    /// is known.
    static func clearlyMoreReliable(_ better: StopReliability, than worse: StopReliability) -> Bool {
        let (betterRan, betterDays) = ran(better)
        let (worseRan, worseDays) = ran(worse)
        let betterDenominator = betterDays * Int64(better.samples)
        let worseDenominator = worseDays * Int64(worse.samples)
        let betterScaled: Int64 = betterRan * Int64(better.onTime) * worseDenominator
        let worseScaled: Int64 = worseRan * Int64(worse.onTime) * betterDenominator
        let gap = (betterScaled - worseScaled) * 100
        return gap >= Int64(minimumGapPoints) * betterDenominator * worseDenominator
    }

    /// The days the train ran and the days known; one day run of one known when no day is.
    private static func ran(_ stop: StopReliability) -> (Int64, Int64) {
        guard stop.daysObserved > 0 else { return (1, 1) }
        let cancelled = min(max(stop.daysCancelled, 0), stop.daysObserved)
        return (Int64(stop.daysObserved - cancelled), Int64(stop.daysObserved))
    }

    /// Whether `first` comes before `second` as the alternative of a train leaving at
    /// `departure`: the closer departure, then the earlier one, then the higher on-time share,
    /// then more samples, then the key.
    static func precedes(_ first: ReliableAlternative, _ second: ReliableAlternative, around departure: Date) -> Bool {
        let firstDistance = abs(first.departure.timeIntervalSince(departure))
        let secondDistance = abs(second.departure.timeIntervalSince(departure))
        if firstDistance != secondDistance { return firstDistance < secondDistance }
        if first.departure != second.departure { return first.departure < second.departure }
        let firstShare = Int64(first.stop.onTime) * Int64(second.stop.samples)
        let secondShare = Int64(second.stop.onTime) * Int64(first.stop.samples)
        if firstShare != secondShare { return firstShare > secondShare }
        if first.stop.samples != second.stop.samples { return first.stop.samples > second.stop.samples }
        return first.key < second.key
    }

    /// The best alternative of `train` among `eligible`, or nil.
    private static func bestAlternative(for train: AlternativeCandidate, among eligible: [AlternativeCandidate]) -> ReliableAlternative? {
        guard let departure = train.departure, let stop = train.stop else { return nil }
        let window = TimeInterval(windowMinutes * 60)
        var best: ReliableAlternative?
        for candidate in eligible where candidate.key != train.key {
            guard let otherDeparture = candidate.departure, let otherStop = candidate.stop,
                  otherStop.onTimeLimitMinutes == stop.onTimeLimitMinutes,
                  abs(otherDeparture.timeIntervalSince(departure)) <= window,
                  clearlyBetter(otherStop, than: stop),
                  clearlyMoreReliable(otherStop, than: stop)
            else { continue }
            let suggestion = ReliableAlternative(key: candidate.key, departure: otherDeparture, stop: otherStop)
            if let current = best, !precedes(suggestion, current, around: departure) { continue }
            best = suggestion
        }
        return best
    }

    /// Statistics with enough samples at the destination, a departure time and still to depart.
    private static func measured(_ candidate: AlternativeCandidate) -> Bool {
        guard let stop = candidate.stop else { return false }
        return candidate.stillToDepart && candidate.departure != nil && !stop.cancelledEveryDay
            && stop.samples >= NetworkReliability.minimumSamples
    }

    private static func nextOccurrence(of time: String, after start: Date, calendar: Calendar) -> Date? {
        let parts = time.split(separator: ":")
        guard parts.count == 2,
              let hour = Int(parts[0]), let minute = Int(parts[1]),
              (0..<24).contains(hour), (0..<60).contains(minute) else { return nil }
        return calendar.nextDate(
            after: start,
            matching: DateComponents(hour: hour, minute: minute),
            matchingPolicy: .nextTime
        )
    }
}
