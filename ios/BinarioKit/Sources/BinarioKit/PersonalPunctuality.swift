import Foundation

/// The delay a run had at one of its stops, in whole minutes (never negative).
public struct PunctualityStopDelay: Equatable, Hashable, Sendable {
    public let station: String
    public let delayMinutes: Int

    public init(station: String, delayMinutes: Int) {
        self.station = station
        self.delayMinutes = delayMinutes
    }
}

/// One operational observation of a train on one service day, kept only on this iPhone.
public struct PunctualityObservation: Equatable, Hashable, Sendable {
    public let serviceDate: PlannedLocalDate
    public let trainNumber: String
    public let operatorName: String
    /// Last known delay in whole minutes, never negative.
    public let delayMinutes: Int
    public let cancelled: Bool
    /// Delays at the stops with verified times, in order; kept only for the newest observations.
    public let stopDelays: [PunctualityStopDelay]

    public init(
        serviceDate: PlannedLocalDate,
        trainNumber: String,
        operatorName: String,
        delayMinutes: Int,
        cancelled: Bool,
        stopDelays: [PunctualityStopDelay] = []
    ) {
        self.serviceDate = serviceDate
        self.trainNumber = trainNumber
        self.operatorName = operatorName
        self.delayMinutes = delayMinutes
        self.cancelled = cancelled
        self.stopDelays = stopDelays
    }

    func with(trainNumber: String? = nil, stopDelays: [PunctualityStopDelay]? = nil) -> PunctualityObservation {
        PunctualityObservation(
            serviceDate: serviceDate,
            trainNumber: trainNumber ?? self.trainNumber,
            operatorName: operatorName,
            delayMinutes: delayMinutes,
            cancelled: cancelled,
            stopDelays: stopDelays ?? self.stopDelays
        )
    }
}

/// The bars of the distribution of the «Puntualità» section.
public enum PunctualityBucket: String, CaseIterable, Sendable {
    case onTime = "ON_TIME"
    case upTo15 = "UP_TO_15"
    case upTo30 = "UP_TO_30"
    case over30 = "OVER_30"
    case cancelled = "CANCELLED"
}

/// The stop where the delay grows most on average.
public struct PunctualityWorstStop: Equatable, Sendable {
    public let station: String
    public let averageIncreaseMinutes: Int
    public let samples: Int
}

public struct PunctualityStats: Equatable, Sendable {
    public let count: Int
    /// Share of observations neither cancelled nor later than the on-time threshold.
    public let onTimePercent: Int
    /// Mean delay of the trains that ran; nil when every observation was a cancellation.
    public let averageDelayMinutes: Int?
    public let cancelledCount: Int
    /// Share of observations that ran later than the late threshold.
    public let latePercent: Int
    /// Observations per bucket, every bucket present.
    public let distribution: [PunctualityBucket: Int]
    public let worstStop: PunctualityWorstStop?
    /// Every observation of the train, newest first.
    public let history: [PunctualityObservation]
}

/// The rules of the local punctuality history, as Android's `PunctualityHistory`: one observation
/// per train number and service day (the latest wins), within the retention, per train and in total;
/// per-stop delays only for the newest observations. Limits and thresholds are
/// `SharedData.PersonalPunctuality`; the cases are contracts/shared-rules/v1/personal-punctuality-v1.json.
public enum PunctualityHistory {
    private typealias Table = SharedData.PersonalPunctuality

    public static func trainKey(_ number: String) -> String {
        number.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ").uppercased()
    }

    /// The observation a live run gives for its service day, or nil for a run without a number or a
    /// future day. The delay is the one of the last stop with a verified time, else the run's.
    public static func observation(
        number: String,
        operatorName: String,
        serviceDate: PlannedLocalDate,
        today: PlannedLocalDate,
        delayMinutes: Int,
        cancelled: Bool,
        stops: [PunctualityStopDelay]
    ) -> PunctualityObservation? {
        let key = trainKey(number)
        guard !key.isEmpty, key.count <= Table.maximumNumberLength, serviceDate <= today else { return nil }
        let delay = stops.last?.delayMinutes ?? delayMinutes
        return PunctualityObservation(
            serviceDate: serviceDate,
            trainNumber: key,
            operatorName: String(operatorName.trimmingCharacters(in: .whitespaces).prefix(Table.maximumOperatorLength)),
            delayMinutes: clampDelay(delay),
            cancelled: cancelled,
            stopDelays: cancelled ? [] : bounded(stops)
        )
    }

    public static func record(
        _ history: [PunctualityObservation],
        _ observations: [PunctualityObservation],
        today: PlannedLocalDate
    ) -> [PunctualityObservation] {
        bound(history + observations, today: today)
    }

    /// Newest first; for a repeated train and day the observation listed last wins.
    public static func bound(_ observations: [PunctualityObservation], today: PlannedLocalDate) -> [PunctualityObservation] {
        guard let oldestKept = today.adding(days: -(Table.retentionDays - 1)) else { return [] }
        var seen = Set<String>()
        var unique: [PunctualityObservation] = []
        for observation in observations.reversed() {
            let keyed = observation.with(trainNumber: trainKey(observation.trainNumber))
            guard !keyed.trainNumber.isEmpty, keyed.serviceDate >= oldestKept, keyed.serviceDate <= today else { continue }
            guard seen.insert("\(keyed.trainNumber)|\(keyed.serviceDate.iso)").inserted else { continue }
            unique.append(keyed)
        }
        unique.sort { lhs, rhs in
            lhs.serviceDate != rhs.serviceDate ? lhs.serviceDate > rhs.serviceDate : lhs.trainNumber < rhs.trainNumber
        }
        var perTrain: [String: Int] = [:]
        var detailedPerTrain: [String: Int] = [:]
        var detailed = 0
        var kept: [PunctualityObservation] = []
        for observation in unique {
            let count = perTrain[observation.trainNumber, default: 0]
            guard count < Table.maximumPerTrain else { continue }
            perTrain[observation.trainNumber] = count + 1
            guard kept.count < Table.maximumTotal else { break }
            if observation.stopDelays.isEmpty {
                kept.append(observation)
                continue
            }
            let details = detailedPerTrain[observation.trainNumber, default: 0]
            if details < Table.detailedPerTrain, detailed < Table.maximumDetailedTotal {
                detailedPerTrain[observation.trainNumber] = details + 1
                detailed += 1
                kept.append(observation.with(stopDelays: bounded(observation.stopDelays)))
            } else {
                kept.append(observation.with(stopDelays: []))
            }
        }
        return kept
    }

    public static func withoutTrain(_ history: [PunctualityObservation], _ trainNumber: String) -> [PunctualityObservation] {
        let key = trainKey(trainNumber)
        return history.filter { $0.trainNumber != key }
    }

    /// Nil below the minimum number of observations: a single day says nothing about a train.
    public static func stats(_ history: [PunctualityObservation], trainNumber: String) -> PunctualityStats? {
        let key = trainKey(trainNumber)
        // A stable sort, newest first, as Kotlin's sortedByDescending.
        let observations = history.enumerated()
            .filter { $0.element.trainNumber == key }
            .sorted { lhs, rhs in
                lhs.element.serviceDate != rhs.element.serviceDate
                    ? lhs.element.serviceDate > rhs.element.serviceDate : lhs.offset < rhs.offset
            }
            .map { $0.element }
        guard observations.count >= Table.minimumObservations else { return nil }
        let ran = observations.filter { !$0.cancelled }
        let onTime = ran.filter { $0.delayMinutes <= Table.onTimeMinutes }.count
        let late = ran.filter { $0.delayMinutes > Table.lateMinutes }.count
        var distribution: [PunctualityBucket: Int] = [:]
        for item in PunctualityBucket.allCases { distribution[item] = 0 }
        for observation in observations { distribution[Self.bucket(of: observation), default: 0] += 1 }
        let total = Double(observations.count)
        let delays = ran.reduce(0) { $0 + $1.delayMinutes }
        return PunctualityStats(
            count: observations.count,
            onTimePercent: roundHalfUp(Double(onTime) * 100 / total),
            averageDelayMinutes: ran.isEmpty ? nil : roundHalfUp(Double(delays) / Double(ran.count)),
            cancelledCount: observations.count - ran.count,
            latePercent: roundHalfUp(Double(late) * 100 / total),
            distribution: distribution,
            worstStop: worstStop(ran),
            history: observations
        )
    }

    public static func bucket(of observation: PunctualityObservation) -> PunctualityBucket {
        if observation.cancelled { return .cancelled }
        if observation.delayMinutes <= Table.onTimeMinutes { return .onTime }
        if observation.delayMinutes <= Table.lateMinutes { return .upTo15 }
        if observation.delayMinutes <= Table.severeMinutes { return .upTo30 }
        return .over30
    }

    /// The stop where the delay grows most: the increase at a stop is its delay minus the delay at
    /// the stop before; the highest average over enough observations wins, from one minute.
    public static func worstStop(_ observations: [PunctualityObservation]) -> PunctualityWorstStop? {
        var increases: [String: [Int]] = [:]
        var names: [String: String] = [:]
        var order: [String] = []
        for observation in observations where observation.stopDelays.count > 1 {
            for (before, at) in zip(observation.stopDelays, observation.stopDelays.dropFirst()) {
                let key = at.station.uppercased()
                if names[key] == nil {
                    names[key] = at.station
                    order.append(key)
                }
                increases[key, default: []].append(at.delayMinutes - before.delayMinutes)
            }
        }
        let candidates: [PunctualityWorstStop] = order.compactMap { key in
            guard let values = increases[key], values.count >= Table.minimumStopSamples, let name = names[key] else { return nil }
            let average = roundHalfUp(Double(values.reduce(0, +)) / Double(values.count))
            return average >= 1 ? PunctualityWorstStop(station: name, averageIncreaseMinutes: average, samples: values.count) : nil
        }
        return candidates.min { lhs, rhs in
            if lhs.averageIncreaseMinutes != rhs.averageIncreaseMinutes { return lhs.averageIncreaseMinutes > rhs.averageIncreaseMinutes }
            if lhs.samples != rhs.samples { return lhs.samples > rhs.samples }
            // Kotlin compares strings by UTF-16 code units.
            return Array(lhs.station.utf16).lexicographicallyPrecedes(Array(rhs.station.utf16))
        }
    }

    // MARK: JSON, the form of Android's history and of the local backup

    /// `[{"date","train","operator","delayMinutes","cancelled","stops":[[station, minutes]]}]`.
    public static func jsonObjects(_ history: [PunctualityObservation]) -> [[String: Any]] {
        history.map { observation in
            var value: [String: Any] = [
                "date": observation.serviceDate.iso,
                "train": observation.trainNumber,
                "operator": observation.operatorName,
                "delayMinutes": observation.delayMinutes,
                "cancelled": observation.cancelled,
            ]
            if !observation.stopDelays.isEmpty {
                value["stops"] = observation.stopDelays.map { [$0.station, $0.delayMinutes] as [Any] }
            }
            return value
        }
    }

    /// Invalid entries are skipped one by one; values are clamped to the stored limits.
    public static func observations(fromJSON array: [Any]) -> [PunctualityObservation] {
        array.prefix(Table.maximumTotal * 4).compactMap { item -> PunctualityObservation? in
            guard let value = item as? [String: Any],
                  let date = (value["date"] as? String).flatMap({ PlannedLocalDate(iso: $0) }),
                  let delay = jsonInt(value["delayMinutes"]) else { return nil }
            let number = trainKey(value["train"] as? String ?? "")
            guard !number.isEmpty, number.count <= Table.maximumNumberLength else { return nil }
            let stops = (value["stops"] as? [Any] ?? []).prefix(Table.maximumStops).compactMap { stop -> PunctualityStopDelay? in
                guard let pair = stop as? [Any], pair.count >= 2, let station = pair[0] as? String,
                      !station.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
                return PunctualityStopDelay(station: station, delayMinutes: jsonInt(pair[1]) ?? 0)
            }
            let operatorName = (value["operator"] as? String ?? "").trimmingCharacters(in: .whitespaces)
            return PunctualityObservation(
                serviceDate: date,
                trainNumber: number,
                operatorName: String(operatorName.prefix(Table.maximumOperatorLength)),
                delayMinutes: clampDelay(delay),
                cancelled: value["cancelled"] as? Bool ?? false,
                stopDelays: bounded(Array(stops))
            )
        }
    }

    public static func encode(_ history: [PunctualityObservation]) -> Data? {
        try? JSONSerialization.data(withJSONObject: jsonObjects(history))
    }

    public static func decode(_ data: Data?) -> [PunctualityObservation] {
        guard let data, let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return [] }
        return observations(fromJSON: array)
    }

    // MARK: Helpers

    private static func bounded(_ stops: [PunctualityStopDelay]) -> [PunctualityStopDelay] {
        var result: [PunctualityStopDelay] = []
        for stop in stops {
            let station = String(stop.station.trimmingCharacters(in: .whitespaces).prefix(Table.maximumStationLength))
            guard !station.isEmpty else { continue }
            result.append(PunctualityStopDelay(station: station, delayMinutes: clampDelay(stop.delayMinutes)))
            if result.count == Table.maximumStops { break }
        }
        return result
    }

    private static func clampDelay(_ minutes: Int) -> Int {
        min(max(minutes, 0), Table.maximumDelayMinutes)
    }

    /// Kotlin's `roundToInt`: half up, also for negative values.
    static func roundHalfUp(_ value: Double) -> Int {
        Int((value + 0.5).rounded(.down))
    }

    /// A JSON number as JSONSerialization gives it (Android's `optInt`).
    static func jsonInt(_ value: Any?) -> Int? {
        (value as? NSNumber)?.intValue
    }
}
