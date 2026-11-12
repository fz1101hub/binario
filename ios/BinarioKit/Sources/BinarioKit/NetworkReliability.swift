import Foundation

/// A calendar day of the statistics window ("2026-11-09"): the file names days, not instants.
public struct NetworkReliabilityDay: Equatable, Hashable, Sendable {
    public let year: Int
    public let month: Int
    public let day: Int

    public init?(year: Int, month: Int, day: Int) {
        guard (2000...9999).contains(year), (1...12).contains(month), (1...31).contains(day) else { return nil }
        self.year = year
        self.month = month
        self.day = day
    }

    /// An ISO date ("2026-11-09"), or nil.
    public init?(iso value: String) {
        let parts = value.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2])
        else { return nil }
        self.init(year: year, month: month, day: day)
        guard let noon = noonInRome(),
              NetworkReliabilityDay.romeCalendar.component(.day, from: noon) == day
        else { return nil }
    }

    /// The day in Rome of an instant.
    init?(instant: Date) {
        let parts = NetworkReliabilityDay.romeCalendar.dateComponents([.year, .month, .day], from: instant)
        guard let year = parts.year, let month = parts.month, let day = parts.day else { return nil }
        self.init(year: year, month: month, day: day)
    }

    /// Noon in Rome of the day: formatted in any time zone it stays the same calendar day.
    public func noonInRome() -> Date? {
        NetworkReliabilityDay.romeCalendar.date(from: DateComponents(year: year, month: month, day: day, hour: 12))
    }

    /// Days from `self` to `other` (1 for consecutive days), or nil.
    func days(until other: NetworkReliabilityDay) -> Int? {
        guard let start = noonInRome(), let end = other.noonInRome() else { return nil }
        return NetworkReliabilityDay.romeCalendar.dateComponents([.day], from: start, to: end).day
    }

    static var romeCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.timeZone = TimeZone(identifier: "Europe/Rome") ?? TimeZone(secondsFromGMT: 3_600)!
        return calendar
    }
}

/// Network reliability statistics: how every observed train ran over the last days, aggregated by
/// the Binario pipeline from ViaggiaTreno observations and bundled with the app
/// (`binario_punctuality_stats_v1.json.gz`, the same file as the Android asset
/// `punctuality/stats_v1.json.gz`; format in the shared specification). Unofficial statistics:
/// nothing here is fetched, recorded or sent.
public struct NetworkReliabilityStats: Sendable {
    public let windowStart: NetworkReliabilityDay?
    /// The last day the statistics include ("aggiornate al …").
    public let windowEnd: NetworkReliabilityDay
    /// The days of the window the pipeline aggregates (30), observed or not.
    public let windowDays: Int
    /// The delay limits, in minutes, of the cumulative counts of every stop.
    public let buckets: [Int]
    public let trains: [NetworkReliabilityTrain]
    private let indicesByNumber: [String: [Int]]

    init(windowStart: NetworkReliabilityDay?, windowEnd: NetworkReliabilityDay, windowDays: Int, buckets: [Int], trains: [NetworkReliabilityTrain]) {
        self.windowStart = windowStart
        self.windowEnd = windowEnd
        self.windowDays = windowDays
        self.buckets = buckets
        self.trains = trains
        var indices: [String: [Int]] = [:]
        for (index, train) in trains.enumerated() {
            indices[NetworkReliability.numberKey(train.number), default: []].append(index)
        }
        indicesByNumber = indices
    }

    /// The days the statistics really cover, never more than `windowDays`: in the first weeks of
    /// the collection the window is not full yet, and a daily train is observed on at most every
    /// day collected.
    public var coveredDays: Int {
        max(1, min(windowDays, trains.map(\.daysObserved).max() ?? windowDays))
    }

    /// Every train published with `number` ("9558", "FR 9558"); trains with another origin are other trains.
    public func trains(numbered number: String) -> [NetworkReliabilityTrain] {
        (indicesByNumber[NetworkReliability.numberKey(number)] ?? []).map { trains[$0] }
    }
}

/// One train (number and origin) of the statistics: its days observed and cancelled and, per stop
/// in run order, the samples, the mean delay and the cumulative counts of the delays up to each
/// bucket limit, plus the optional largest delay, scheduled minute, usual platform and weekday
/// counts. Stored in flat arrays: about nine thousand trains stay a few megabytes.
public struct NetworkReliabilityTrain: Equatable, Sendable {
    public let number: String
    /// ViaggiaTreno code of the origin ("S08409").
    public let originCode: String
    /// Category of the timetable pack ("FR", "REG", "IC"), upper case.
    public let category: String
    /// Days the run is known (arrived or cancelled), cancellations included.
    public let daysObserved: Int
    public let daysCancelled: Int
    let codes: [String]
    let sampleCounts: [Int16]
    let meanTenths: [Int32]
    /// `stopCount × buckets.count` counts.
    let cumulativeCounts: [Int16]
    let suppressedCounts: [Int16]
    /// -1 where the file does not publish the value.
    let maxDelays: [Int16]
    let scheduledMinutes: [Int16]
    let platformNames: [String?]
    /// Two values per stop: days at the usual platform, days with any platform.
    let platformCounts: [Int16]
    /// 21 values per stop (samples, ≤ 5 min, ≤ 15 min for Monday…Sunday), -1 where absent; empty
    /// when no stop publishes them.
    let weekdayCounts: [Int16]
    let buckets: [Int]

    public var stopCount: Int { codes.count }

    public func stationCode(at index: Int) -> String { codes[index] }

    /// Days with a delay at the stop: cancelled days and days the stop was suppressed are not samples.
    public func samples(at index: Int) -> Int { Int(sampleCounts[index]) }

    /// Mean delay at the stop in minutes (departure delay at the origin, arrival delay elsewhere); may be negative.
    public func meanDelayMinutes(at index: Int) -> Double { Double(meanTenths[index]) / 10 }

    public func suppressedDays(at index: Int) -> Int { Int(suppressedCounts[index]) }

    /// Samples at the stop with a delay of at most `buckets[bucket]` minutes.
    public func cumulative(at index: Int, bucket: Int) -> Int {
        Int(cumulativeCounts[index * buckets.count + bucket])
    }

    /// The largest delay observed at the stop in minutes (early counts as 0), or nil.
    public func maxDelayMinutes(at index: Int) -> Int? {
        let value = maxDelays[index]
        return value >= 0 ? Int(value) : nil
    }

    /// The scheduled arrival (departure at the origin) in minutes since midnight in Rome, of the
    /// most recent day observed; past 1439 for a stop reached after midnight. Nil when absent.
    public func scheduledMinuteOfDay(at index: Int) -> Int? {
        let value = scheduledMinutes[index]
        return value >= 0 ? Int(value) : nil
    }

    /// The most frequent platform at the stop with its count and the days that had any platform.
    public func platform(at index: Int) -> (name: String, count: Int, observed: Int)? {
        guard let name = platformNames[index] else { return nil }
        return (name, Int(platformCounts[index * 2]), Int(platformCounts[index * 2 + 1]))
    }

    /// Samples and samples within 5 and 15 minutes for ISO weekdays 1 (Monday) … 7 (Sunday).
    public func weekdays(at index: Int) -> [WeekdayCounts]? {
        guard !weekdayCounts.isEmpty, weekdayCounts[index * 21] >= 0 else { return nil }
        return (0..<7).map { weekday in
            let base = index * 21 + weekday * 3
            return WeekdayCounts(
                weekday: weekday + 1,
                samples: Int(weekdayCounts[base]),
                withinFive: Int(weekdayCounts[base + 1]),
                withinFifteen: Int(weekdayCounts[base + 2])
            )
        }
    }

    /// Samples within `limit` minutes when `limit` is one of the bucket limits, else nil.
    public func cumulative(at index: Int, limit: Int) -> Int? {
        guard let bucket = buckets.firstIndex(of: limit) else { return nil }
        return cumulative(at: index, bucket: bucket)
    }

    /// First stop at or after `start` with the code, or nil.
    public func index(of code: String, from start: Int = 0) -> Int? {
        var index = max(0, start)
        while index < codes.count {
            if codes[index] == code { return index }
            index += 1
        }
        return nil
    }

    /// Samples with a delay of at most `minutes`, interpolated linearly between the cumulative
    /// counts of the two bucket limits around it: exact at a limit, the last count beyond the last
    /// limit (later delays are only in the samples) and nothing below the first limit.
    public func count(at index: Int, atMost minutes: Double) -> Double {
        guard !minutes.isNaN, let first = buckets.first, let last = buckets.last, minutes >= Double(first) else { return 0 }
        let base = index * buckets.count
        if minutes >= Double(last) { return Double(cumulativeCounts[base + buckets.count - 1]) }
        var bucket = 0
        while minutes >= Double(buckets[bucket + 1]) { bucket += 1 }
        let low = Double(cumulativeCounts[base + bucket])
        let high = Double(cumulativeCounts[base + bucket + 1])
        let span = Double(buckets[bucket + 1] - buckets[bucket])
        return low + (high - low) * (minutes - Double(buckets[bucket])) / span
    }
}

/// Samples of one ISO weekday at a stop and how many were within 5 and 15 minutes.
public struct WeekdayCounts: Equatable, Sendable {
    /// 1 = Monday … 7 = Sunday.
    public let weekday: Int
    public let samples: Int
    public let withinFive: Int
    public let withinFifteen: Int
}

/// Reliability of one train at one stop, measured against the on-time limit of its category.
public struct StopReliability: Equatable, Sendable {
    public let stationCode: String
    public let samples: Int
    /// Samples with a delay of at most `onTimeLimitMinutes`.
    public let onTime: Int
    public let onTimeLimitMinutes: Int
    public let meanDelayMinutes: Double
    public let daysObserved: Int
    public let daysCancelled: Int

    public var onTimeShare: Double { samples > 0 ? Double(onTime) / Double(samples) : 0 }
    public var cancelledShare: Double { daysObserved > 0 ? Double(daysCancelled) / Double(daysObserved) : 0 }
    /// The chance of arriving on time on a day of the window: the train runs and is on time at the stop.
    public var score: Double { (1 - cancelledShare) * onTimeShare }
    /// The mean delay the results show, in whole minutes; an early average shows as no delay.
    public var shownMeanDelayMinutes: Int { max(0, NetworkReliability.roundedToInt(meanDelayMinutes)) }
    /// A train cancelled on every day observed: no delays at all, only its cancellations to show.
    public var cancelledEveryDay: Bool { samples == 0 }
}

/// A train of the statistics matched to a ride: the stop where it is boarded (nil when unknown) and left.
public struct NetworkReliabilityMatch: Equatable, Sendable {
    public let train: NetworkReliabilityTrain
    public let boardingIndex: Int?
    public let alightingIndex: Int
}

/// The stop where the mean delay grows most from the stop before it.
public struct WorstStop: Equatable, Sendable {
    public let stationCode: String
    public let increaseMinutes: Int
    public let samples: Int
}

/// The platform a train usually uses at a stop («Di solito al binario 12, 8 volte su 10»).
public struct UsualPlatform: Equatable, Sendable {
    public let stationCode: String
    public let name: String
    /// Days observed at this platform.
    public let count: Int
    /// Days observed with any platform.
    public let observed: Int
}

/// A weekday on which a train is clearly more or less punctual at a stop than on average.
public struct WeekdayRemark: Equatable, Sendable {
    /// 1 = Monday … 7 = Sunday.
    public let weekday: Int
    public let samples: Int
    /// Samples within the train's own on-time limit on that weekday.
    public let onTime: Int
    /// The on-time share of every day at the stop, in whole percent.
    public let overallOnTimePercent: Int

    public var onTimeShare: Double { samples > 0 ? Double(onTime) / Double(samples) : 0 }
}

/// Pure rules of the network statistics (thresholds, minimum samples, matching, interpolation),
/// the same as the Android app's.
public enum NetworkReliability {
    /// Below this many samples at the stop considered nothing is shown.
    public static let minimumSamples = 5
    public static let highSpeedOnTimeMinutes = 5
    public static let otherOnTimeMinutes = 15
    /// «Di solito al binario …» needs this many days at the platform…
    public static let minimumPlatformCount = 5
    /// …and at least this share of the days with a platform, in percent.
    public static let minimumPlatformSharePercent = 60
    /// A weekday is compared with at least this many samples…
    public static let minimumWeekdaySamples = 4
    /// …and remarked when its on-time share differs from the overall one by this many points.
    public static let minimumWeekdayGapPoints = 15
    public static let maximumWeekdayRemarks = 2

    /// Categories measured at 5 minutes, as the operators and Treniamo do; every other category at 15.
    private static let highSpeedCategories: Set<String> = ["FR", "FA", "FB", "EC", "ES", "ESC", "EN", "ITALO"]

    public static func onTimeLimitMinutes(category: String) -> Int {
        let normalized = category.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return highSpeedCategories.contains(normalized) ? highSpeedOnTimeMinutes : otherOnTimeMinutes
    }

    /// "9558" for "9558", "FR 9558" and "09558"; a number without digits keeps its letters. A
    /// Trenord number of the timetable is the run after the line, whatever it holds: "2153" for
    /// "RE_11 - 2153", "811" for "S5 - 811A" (Android's `NetworkReliabilityStats.numberKey`).
    public static func numberKey(_ number: String) -> String {
        var run = number
        if let separator = number.range(of: " - ", options: .backwards) {
            let tail = number[separator.upperBound...]
            if !tail.allSatisfy(\.isWhitespace) { run = String(tail) }
        }
        let digits = String(run.filter(\.isASCIIDigit).drop(while: { $0 == "0" }))
        if !digits.isEmpty { return digits }
        return number.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: " ", with: "").uppercased()
    }

    /// A ViaggiaTreno station code: "S" and five digits ("S01700").
    public static func isStationCode(_ code: String?) -> Bool {
        guard let code else { return false }
        let characters = Array(code.utf8)
        return characters.count == 6 && characters[0] == UInt8(ascii: "S")
            && characters.dropFirst().allSatisfy { (48...57).contains($0) }
    }

    /// The ViaggiaTreno code of a catalogue station: its railway code ("S01700").
    public static func stationCode(_ identity: StationIdentity?) -> String? {
        guard let code = identity?.railwayCode?.trimmingCharacters(in: .whitespaces), isStationCode(code) else { return nil }
        return code
    }

    /// Kotlin's `roundToInt`: ties go towards positive infinity.
    static func roundedToInt(_ value: Double) -> Int {
        guard value.isFinite else { return 0 }
        return Int((value + 0.5).rounded(.down))
    }

    /// The stop at `index` of `train`, or nil below `minimumSamples` samples.
    public static func at(_ train: NetworkReliabilityTrain, index: Int) -> StopReliability? {
        guard (0..<train.stopCount).contains(index) else { return nil }
        let samples = train.samples(at: index)
        guard samples >= minimumSamples else { return nil }
        let limit = onTimeLimitMinutes(category: train.category)
        return StopReliability(
            stationCode: train.stationCode(at: index),
            samples: samples,
            onTime: onTime(train, index: index),
            onTimeLimitMinutes: limit,
            meanDelayMinutes: train.meanDelayMinutes(at: index),
            daysObserved: train.daysObserved,
            daysCancelled: train.daysCancelled
        )
    }

    /// Samples of `train` at `index` within its own on-time limit (both limits are buckets, so the count is exact).
    static func onTime(_ train: NetworkReliabilityTrain, index: Int) -> Int {
        let limit = onTimeLimitMinutes(category: train.category)
        let count = Int((train.count(at: index, atMost: Double(limit)) + 1e-9).rounded(.down))
        return min(max(count, 0), train.samples(at: index))
    }

    /// The train numbered `number` that is left at `alightingCode`. The same number with another
    /// origin is another train, so the candidates must stop at `alightingCode`; one that also stops
    /// at `boardingCode` before it is preferred, then the one leaving from `originCode`, then the
    /// one with more samples at `alightingCode`. Nil without a number or a station code to check.
    public static func match(
        _ stats: NetworkReliabilityStats,
        number: String?,
        alightingCode: String?,
        boardingCode: String? = nil,
        originCode: String? = nil
    ) -> NetworkReliabilityMatch? {
        guard let number, !number.trimmingCharacters(in: .whitespaces).isEmpty,
              let alightingCode, isStationCode(alightingCode) else { return nil }
        let boarding = isStationCode(boardingCode) ? boardingCode : nil
        let candidates: [NetworkReliabilityMatch] = stats.trains(numbered: number).compactMap { train in
            let boardingIndex = boarding.flatMap { train.index(of: $0) }
            guard let alightingIndex = train.index(of: alightingCode, from: (boardingIndex ?? -1) + 1) else { return nil }
            return NetworkReliabilityMatch(train: train, boardingIndex: boardingIndex, alightingIndex: alightingIndex)
        }
        return candidates.min { first, second in
            let firstBoards = first.boardingIndex != nil, secondBoards = second.boardingIndex != nil
            if firstBoards != secondBoards { return firstBoards }
            let firstOrigin = originCode != nil && first.train.originCode == originCode
            let secondOrigin = originCode != nil && second.train.originCode == originCode
            if firstOrigin != secondOrigin { return firstOrigin }
            let firstSamples = first.train.samples(at: first.alightingIndex)
            let secondSamples = second.train.samples(at: second.alightingIndex)
            if firstSamples != secondSamples { return firstSamples > secondSamples }
            return first.train.originCode < second.train.originCode
        }
    }

    /// The train numbered `number` that serves `boardingCode` (its origin or a stop), measured at
    /// its last stop: for a detail whose destination has no code. Nil without a candidate.
    public static func matchTerminus(_ stats: NetworkReliabilityStats, number: String?, boardingCode: String?) -> NetworkReliabilityMatch? {
        guard let number, !number.trimmingCharacters(in: .whitespaces).isEmpty,
              let boardingCode, isStationCode(boardingCode) else { return nil }
        let candidates: [NetworkReliabilityMatch] = stats.trains(numbered: number).compactMap { train in
            guard let boardingIndex = train.index(of: boardingCode), boardingIndex != train.stopCount - 1 else { return nil }
            return NetworkReliabilityMatch(train: train, boardingIndex: boardingIndex, alightingIndex: train.stopCount - 1)
        }
        return candidates.min { first, second in
            let firstOrigin = first.train.originCode == boardingCode, secondOrigin = second.train.originCode == boardingCode
            if firstOrigin != secondOrigin { return firstOrigin }
            let firstSamples = first.train.samples(at: first.alightingIndex)
            let secondSamples = second.train.samples(at: second.alightingIndex)
            if firstSamples != secondSamples { return firstSamples > secondSamples }
            return first.train.originCode < second.train.originCode
        }
    }

    /// A train numbered `number` leaving `originCode` that was cancelled on every day observed (the
    /// file lists it without stops), as a stop without samples; nil below `minimumSamples` days.
    public static func cancelledEveryDay(_ stats: NetworkReliabilityStats, number: String?, originCode: String?) -> StopReliability? {
        guard let number, !number.trimmingCharacters(in: .whitespaces).isEmpty,
              let originCode, isStationCode(originCode),
              let train = stats.trains(numbered: number).first(where: {
                  $0.stopCount == 0 && $0.originCode == originCode && $0.daysObserved >= minimumSamples
              })
        else { return nil }
        return StopReliability(
            stationCode: originCode,
            samples: 0,
            onTime: 0,
            onTimeLimitMinutes: onTimeLimitMinutes(category: train.category),
            meanDelayMinutes: 0,
            daysObserved: train.daysObserved,
            daysCancelled: train.daysCancelled
        )
    }

    /// Where the delay grows most: between consecutive stops with at least `minimumSamples`
    /// samples, the stop whose mean delay exceeds the one before it by the most, when that is at
    /// least one minute.
    public static func worstStop(_ train: NetworkReliabilityTrain) -> WorstStop? {
        var best: WorstStop?
        var bestIncrease = 0.0
        var previous: Int?
        for index in 0..<train.stopCount where train.samples(at: index) >= minimumSamples {
            if let previous {
                let increase = train.meanDelayMinutes(at: index) - train.meanDelayMinutes(at: previous)
                let rounded = roundedToInt(increase)
                if rounded >= 1, best == nil || increase > bestIncrease {
                    best = WorstStop(stationCode: train.stationCode(at: index), increaseMinutes: rounded, samples: train.samples(at: index))
                    bestIncrease = increase
                }
            }
            previous = index
        }
        return best
    }

    /// The usual platform at `index`: at least `minimumPlatformCount` days there and
    /// `minimumPlatformSharePercent` of the days that had a platform; nil otherwise.
    public static func usualPlatform(_ train: NetworkReliabilityTrain, index: Int) -> UsualPlatform? {
        guard (0..<train.stopCount).contains(index), let platform = train.platform(at: index),
              platform.count >= minimumPlatformCount, platform.observed >= platform.count,
              platform.count * 100 >= minimumPlatformSharePercent * platform.observed
        else { return nil }
        return UsualPlatform(stationCode: train.stationCode(at: index), name: platform.name, count: platform.count, observed: platform.observed)
    }

    /// The weekdays at `index` with at least `minimumWeekdaySamples` samples whose on-time share,
    /// against the train's own limit, differs by at least `minimumWeekdayGapPoints` points from the
    /// stop's overall share: the largest differences first, at most `maximumWeekdayRemarks`.
    public static func weekdayRemarks(_ train: NetworkReliabilityTrain, index: Int) -> [WeekdayRemark] {
        guard let stop = at(train, index: index), let weekdays = train.weekdays(at: index) else { return [] }
        let overall = stop.onTimeShare
        let overallPercent = roundedToInt(overall * 100)
        var remarks: [(remark: WeekdayRemark, gap: Double)] = []
        for counts in weekdays where counts.samples >= minimumWeekdaySamples {
            let onTime = stop.onTimeLimitMinutes == highSpeedOnTimeMinutes ? counts.withinFive : counts.withinFifteen
            let share = Double(onTime) / Double(counts.samples)
            let gap = abs(share - overall) * 100
            guard gap >= Double(minimumWeekdayGapPoints) - 1e-9 else { continue }
            let remark = WeekdayRemark(weekday: counts.weekday, samples: counts.samples, onTime: onTime, overallOnTimePercent: overallPercent)
            remarks.append((remark: remark, gap: gap))
        }
        remarks.sort { $0.gap != $1.gap ? $0.gap > $1.gap : $0.remark.weekday < $1.remark.weekday }
        return remarks.prefix(maximumWeekdayRemarks).map { $0.remark }
    }
}

/// How the delays of one train at one stop spread: no delay at all, 1–5, 6–15, 16–30 and over 30
/// minutes, from the cumulative counts (whole minutes, so "at most 5" is "1–5" once the days
/// without delay are taken out). The five counts add up to `samples`.
public struct DelayDistribution: Equatable, Sendable {
    public let samples: Int
    /// Delay of 0 or less (early arrivals included): not the train's on-time limit of 5 or 15 minutes.
    public let noDelay: Int
    public let upTo5: Int
    public let upTo15: Int
    public let upTo30: Int
    public let over30: Int

    public var counts: [Int] { [noDelay, upTo5, upTo15, upTo30, over30] }

    /// Whole percentages of `samples`, one per band, that add up to 100: rounded down, then the
    /// points left over go to the bands with the largest remainders (the earlier band on a tie).
    public var percentages: [Int] {
        guard samples > 0 else { return counts.map { _ in 0 } }
        let exact = counts.map { Double($0) * 100 / Double(samples) }
        var shares = exact.map { Int(($0 + 1e-9).rounded(.down)) }
        var left = 100 - shares.reduce(0, +)
        let byRemainder = exact.indices.sorted { first, second in
            let firstRemainder = exact[first] - Double(shares[first])
            let secondRemainder = exact[second] - Double(shares[second])
            return firstRemainder != secondRemainder ? firstRemainder > secondRemainder : first < second
        }
        for index in byRemainder where left > 0 && counts[index] > 0 {
            shares[index] += 1
            left -= 1
        }
        return shares
    }
}

/// The departure hour bands of the route statistics, by scheduled time at the boarding stop.
public enum HourBand: Int, CaseIterable, Equatable, Sendable {
    case night, earlyMorning, morning, midday, afternoon, evening, lateEvening

    public var startHour: Int { [0, 6, 9, 12, 15, 18, 21][rawValue] }
    public var endHour: Int { [6, 9, 12, 15, 18, 21, 24][rawValue] }

    /// The band of a scheduled minute; minutes past midnight of the service day (1450 = 00:10) wrap round.
    public static func of(minuteOfDay: Int) -> HourBand {
        let minute = ((minuteOfDay % 1_440) + 1_440) % 1_440
        let hour = minute / 60
        return allCases.first { hour < $0.endHour } ?? .lateEvening
    }
}

/// The on-time share of the trains of a route leaving in one band.
public struct HourBandPunctuality: Equatable, Sendable {
    public let band: HourBand
    public let samples: Int
    public let onTime: Int
    public var onTimeShare: Double { samples > 0 ? Double(onTime) / Double(samples) : 0 }
}

/// Every train of the statistics that serves the origin of a route before its destination,
/// measured where the route is left, each against its own on-time limit.
public struct RoutePunctuality: Equatable, Sendable {
    public let trains: Int
    public let samples: Int
    public let onTime: Int
    public let meanDelayMinutes: Double
    /// The days the statistics cover.
    public let days: Int
    public let bands: [HourBandPunctuality]
    public let bestBand: HourBand?
    public let worstBand: HourBand?

    public var onTimeShare: Double { samples > 0 ? Double(onTime) / Double(samples) : 0 }
    public var onTimePercent: Int { NetworkReliability.roundedToInt(onTimeShare * 100) }
    public var shownMeanDelayMinutes: Int { max(0, NetworkReliability.roundedToInt(meanDelayMinutes)) }
}

/// Aggregates of the network statistics: one stop's delay distribution and a route.
public enum NetworkReliabilityAggregates {
    /// Below this many samples in all, a route shows nothing.
    public static let minimumAggregateSamples = 20
    /// An hour band takes part in "più / meno puntuali" with at least this many samples.
    public static let minimumBandSamples = 20
    /// The best and the worst band are named only when their shares differ by this many points.
    public static let minimumBandGapPoints = 5

    /// The delay distribution of `train` at `index`, or nil below `minimumSamples` samples or when
    /// the buckets of the file do not include 0, 5, 15 and 30 minutes.
    public static func distribution(_ train: NetworkReliabilityTrain, index: Int) -> DelayDistribution? {
        guard (0..<train.stopCount).contains(index) else { return nil }
        let samples = train.samples(at: index)
        guard samples >= NetworkReliability.minimumSamples,
              let zero = train.cumulative(at: index, limit: 0),
              let five = train.cumulative(at: index, limit: 5),
              let fifteen = train.cumulative(at: index, limit: 15),
              let thirty = train.cumulative(at: index, limit: 30),
              zero <= five, five <= fifteen, fifteen <= thirty, thirty <= samples
        else { return nil }
        return DelayDistribution(samples: samples, noDelay: zero, upTo5: five - zero, upTo15: fifteen - five, upTo30: thirty - fifteen, over30: samples - thirty)
    }

    /// The trains leaving `originCode` (at their first stop there) and later stopping at
    /// `destinationCode`, measured at the destination; nil without both codes, for the same code
    /// twice or below `minimumAggregateSamples` samples. The hour bands use the scheduled minute at
    /// the origin; a train whose file does not publish it counts in the route, not in the bands.
    public static func route(_ stats: NetworkReliabilityStats, originCode: String?, destinationCode: String?) -> RoutePunctuality? {
        guard let originCode, let destinationCode,
              NetworkReliability.isStationCode(originCode), NetworkReliability.isStationCode(destinationCode),
              originCode != destinationCode else { return nil }
        var trains = 0, samples = 0, onTime = 0
        var delaySum = 0.0
        var bandSamples = Array(repeating: 0, count: HourBand.allCases.count)
        var bandOnTime = Array(repeating: 0, count: HourBand.allCases.count)
        for train in stats.trains {
            guard let boarding = train.index(of: originCode),
                  let alighting = train.index(of: destinationCode, from: boarding + 1) else { continue }
            let count = train.samples(at: alighting)
            guard count > 0 else { continue }
            let inTime = NetworkReliability.onTime(train, index: alighting)
            trains += 1
            samples += count
            onTime += inTime
            delaySum += train.meanDelayMinutes(at: alighting) * Double(count)
            if let minute = train.scheduledMinuteOfDay(at: boarding) {
                let band = HourBand.of(minuteOfDay: minute).rawValue
                bandSamples[band] += count
                bandOnTime[band] += inTime
            }
        }
        guard samples >= minimumAggregateSamples else { return nil }
        let bands = HourBand.allCases.filter { bandSamples[$0.rawValue] > 0 }
            .map { HourBandPunctuality(band: $0, samples: bandSamples[$0.rawValue], onTime: bandOnTime[$0.rawValue]) }
        let (best, worst) = bestAndWorst(bands)
        return RoutePunctuality(
            trains: trains,
            samples: samples,
            onTime: onTime,
            meanDelayMinutes: delaySum / Double(samples),
            days: stats.coveredDays,
            bands: bands,
            bestBand: best,
            worstBand: worst
        )
    }

    /// The best and the worst band among those with at least `minimumBandSamples` samples, or nils
    /// when fewer than two qualify or their shares are less than `minimumBandGapPoints` apart.
    /// Equal shares go to the earlier band for the best and the later band for the worst.
    public static func bestAndWorst(_ bands: [HourBandPunctuality]) -> (HourBand?, HourBand?) {
        let eligible = bands.filter { $0.samples >= minimumBandSamples }
        guard eligible.count >= 2,
              let best = eligible.max(by: { first, second in
                  first.onTimeShare != second.onTimeShare ? first.onTimeShare < second.onTimeShare : first.band.rawValue > second.band.rawValue
              }),
              let worst = eligible.min(by: { first, second in
                  first.onTimeShare != second.onTimeShare ? first.onTimeShare < second.onTimeShare : first.band.rawValue > second.band.rawValue
              }),
              best.band != worst.band,
              (best.onTimeShare - worst.onTimeShare) * 100 >= Double(minimumBandGapPoints) - 1e-9
        else { return (nil, nil) }
        return (best.band, worst.band)
    }
}

/// The statistics of the direct trains of a planner result: each measured at the user's
/// destination, boarded at the user's origin, and the punctuality of the route.
public struct JourneyReliability: Equatable, Sendable {
    public let byServiceID: [String: StopReliability]
    public let route: RoutePunctuality?
    public let windowEnd: NetworkReliabilityDay

    public static func assess(_ snapshot: JourneySnapshot, stats: NetworkReliabilityStats) -> JourneyReliability {
        let originCode = NetworkReliability.stationCode(snapshot.query.origin)
        let destinationCode = NetworkReliability.stationCode(snapshot.query.destination)
        var byServiceID: [String: StopReliability] = [:]
        for service in snapshot.services {
            if let stop = train(service, stats: stats, originCode: originCode, destinationCode: destinationCode) {
                byServiceID[service.id] = stop
            }
        }
        return JourneyReliability(
            byServiceID: byServiceID,
            route: NetworkReliabilityAggregates.route(stats, originCode: originCode, destinationCode: destinationCode),
            windowEnd: stats.windowEnd
        )
    }

    /// A direct train at `destinationCode`, or, leaving from `originCode`, cancelled on every day observed.
    static func train(_ service: TrainService, stats: NetworkReliabilityStats, originCode: String?, destinationCode: String?) -> StopReliability? {
        if let match = NetworkReliability.match(stats, number: service.number, alightingCode: destinationCode, boardingCode: originCode) {
            return NetworkReliability.at(match.train, index: match.alightingIndex)
        }
        return NetworkReliability.cancelledEveryDay(stats, number: service.number, originCode: originCode)
    }

    public var isEmpty: Bool { byServiceID.isEmpty && route == nil }

    /// «Più affidabili»: the trains with statistics first, the most likely to arrive on time first,
    /// then the lower mean delay; those without statistics after them; the departure order (the
    /// order of `services`) settles the rest. Scores equal to six decimals are a tie.
    public func mostReliableFirst(_ services: [TrainService]) -> [TrainService] {
        services.enumerated().sorted { first, second in
            let firstStop = byServiceID[first.element.id], secondStop = byServiceID[second.element.id]
            switch (firstStop, secondStop) {
            case let (firstStop?, secondStop?):
                let firstScore = Int64((firstStop.score * 1_000_000).rounded()), secondScore = Int64((secondStop.score * 1_000_000).rounded())
                if firstScore != secondScore { return firstScore > secondScore }
                if firstStop.meanDelayMinutes != secondStop.meanDelayMinutes { return firstStop.meanDelayMinutes < secondStop.meanDelayMinutes }
            case (.some, nil): return true
            case (nil, .some): return false
            case (nil, nil): break
            }
            return first.offset < second.offset
        }.map(\.element)
    }
}

/// The «Affidabilità ultimi N giorni» block of a train detail: the train at the user's destination
/// (else its terminus), how its delays spread there and the largest one, its days observed and
/// cancelled, the stop where its delay grows most, the usual platform where it is boarded and the
/// weekdays that differ. A train cancelled on every day observed has an arrival without samples.
public struct NetworkReliabilityDetail: Equatable, Sendable {
    public let windowDays: Int
    public let windowEnd: NetworkReliabilityDay
    public let arrival: StopReliability
    public let daysObserved: Int
    public let daysCancelled: Int
    public let worstStop: WorstStop?
    public let distribution: DelayDistribution?
    public let maxDelayMinutes: Int?
    public let usualPlatform: UsualPlatform?
    public let weekdayRemarks: [WeekdayRemark]

    /// The detail of the train numbered `number` boarded at `boardingCode` and left at
    /// `destinationCode`, or nil without a match, below `minimumSamples` at the stop measured or
    /// with fewer days observed.
    public static func of(_ stats: NetworkReliabilityStats, number: String, boardingCode: String?, destinationCode: String?) -> NetworkReliabilityDetail? {
        guard let match = NetworkReliability.match(stats, number: number, alightingCode: destinationCode, boardingCode: boardingCode, originCode: boardingCode)
            ?? NetworkReliability.matchTerminus(stats, number: number, boardingCode: boardingCode)
        else {
            guard let cancelled = NetworkReliability.cancelledEveryDay(stats, number: number, originCode: boardingCode) else { return nil }
            return NetworkReliabilityDetail(
                windowDays: stats.coveredDays, windowEnd: stats.windowEnd, arrival: cancelled,
                daysObserved: cancelled.daysObserved, daysCancelled: cancelled.daysCancelled,
                worstStop: nil, distribution: nil, maxDelayMinutes: nil, usualPlatform: nil, weekdayRemarks: []
            )
        }
        let train = match.train
        guard train.daysObserved >= NetworkReliability.minimumSamples,
              let arrival = NetworkReliability.at(train, index: match.alightingIndex) else { return nil }
        return NetworkReliabilityDetail(
            windowDays: stats.coveredDays,
            windowEnd: stats.windowEnd,
            arrival: arrival,
            daysObserved: train.daysObserved,
            daysCancelled: train.daysCancelled,
            worstStop: NetworkReliability.worstStop(train),
            distribution: NetworkReliabilityAggregates.distribution(train, index: match.alightingIndex),
            maxDelayMinutes: train.maxDelayMinutes(at: match.alightingIndex),
            usualPlatform: match.boardingIndex.flatMap { NetworkReliability.usualPlatform(train, index: $0) },
            weekdayRemarks: NetworkReliability.weekdayRemarks(train, index: match.alightingIndex)
        )
    }
}

private extension Character {
    var isASCIIDigit: Bool { ("0"..."9").contains(self) }
}
