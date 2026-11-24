import Foundation

/// One train of a route as a live source saw it at both ends (Android's live route `TrainService`): the departure from
/// the searched origin and the arrival at the searched destination, with what the source verified of each.
public struct LiveRouteRun: Equatable, Identifiable, Sendable {
    public let id: String
    public let number: String
    public let category: String
    /// "trenitalia", "trenord", "italo", or nil when the source does not say.
    public let operatorID: String?
    public let operatorName: String?
    public let scheduledDeparture: Date
    /// The expected departure, the scheduled one when the source published none.
    public let expectedDeparture: Date
    public let departureVerified: Bool
    public let departurePlatform: String?
    public let scheduledArrival: Date
    /// The expected arrival, the scheduled one when the source published none.
    public let expectedArrival: Date
    public let arrivalVerified: Bool
    public let arrivalPlatform: String?
    /// The published delay at the departure (Android's `officialDelayMinutes`), nil when unpublished.
    public let delayMinutes: Int?
    public let state: LiveRunState
    /// «RITARDO» without minutes: late, with no delay to show and never verified.
    public let lateWithoutMinutes: Bool
    /// The run's operational state is the source's (Android's `operationalStatusVerified`).
    public let verified: Bool
    public let source: LiveSource
    public let observedAt: Date?

    public init(
        id: String, number: String, category: String, operatorID: String?, operatorName: String?, scheduledDeparture: Date,
        expectedDeparture: Date, departureVerified: Bool, departurePlatform: String?, scheduledArrival: Date, expectedArrival: Date,
        arrivalVerified: Bool, arrivalPlatform: String?, delayMinutes: Int?, state: LiveRunState, lateWithoutMinutes: Bool,
        verified: Bool, source: LiveSource, observedAt: Date?
    ) {
        self.id = id
        self.number = number
        self.category = category
        self.operatorID = operatorID
        self.operatorName = operatorName
        self.scheduledDeparture = scheduledDeparture
        self.expectedDeparture = expectedDeparture
        self.departureVerified = departureVerified
        self.departurePlatform = departurePlatform
        self.scheduledArrival = scheduledArrival
        self.expectedArrival = expectedArrival
        self.arrivalVerified = arrivalVerified
        self.arrivalPlatform = arrivalPlatform
        self.delayMinutes = delayMinutes
        self.state = state
        self.lateWithoutMinutes = lateWithoutMinutes
        self.verified = verified
        self.source = source
        self.observedAt = observedAt
    }

    /// The run as the departure line of a result row shows it, only when the source verified it.
    public func boardStatus(at now: Date) -> StationBoardLiveStatus? {
        guard verified || lateWithoutMinutes else { return nil }
        return StationBoardLiveStatus(
            expectedDeparture: departureVerified ? expectedDeparture : nil, delayMinutes: departureVerified ? delayMinutes : nil,
            cancelled: state == .cancelled, platform: departurePlatform, sourceLabel: source.displayName, observedAt: observedAt ?? now,
            source: source, platformSource: departurePlatform == nil ? nil : source, lateWithoutMinutes: lateWithoutMinutes
        )
    }

    /// A run the timetable does not list, as a row of the results (Android adds the live run itself).
    public func trainService(destination: String) -> TrainService {
        TrainService(
            id: id, number: number, operatorName: operatorName ?? "", category: category, destination: destination,
            scheduledDeparture: LiveRoute.clock(scheduledDeparture), expectedDeparture: LiveRoute.clock(expectedDeparture),
            status: OperationalState.operational.rawValue, platform: nil, delayMinutes: departureVerified ? delayMinutes ?? 0 : 0,
            departureDate: scheduledDeparture, arrivalDate: scheduledArrival
        )
    }
}

/// A live route request: the stations of the search, the time asked and the numbers the timetable lists (Android's
/// `routeCandidateSource`, tried first on a busy board).
public struct LiveRouteRequest: Equatable, Sendable {
    public let origin: Station
    public let destination: Station
    public let requested: Date
    public let plannedNumbers: Set<String>

    public init(origin: Station, destination: Station, requested: Date, plannedNumbers: Set<String> = []) {
        self.origin = origin
        self.destination = destination
        self.requested = requested
        self.plannedNumbers = plannedNumbers
    }
}

public enum LiveRouteResult: Equatable, Sendable {
    case available([LiveRouteRun], source: LiveSource)
    case unavailable(LiveUnavailableReason)
}

/// Android's live route sources (`RfiLiveRouteDataSource`, the route of `ViaggiaTrenoRealtimeAdapter`), as pure rules on
/// what the iPhone's live sources return.
public enum LiveRoute {
    static let rfiLookBack = TimeInterval(SharedData.LiveRoute.rfiBoardLookBackMinutes * 60)
    static let rfiLookAhead = TimeInterval(SharedData.LiveRoute.rfiBoardLookAheadMinutes * 60)
    static let rfiMaximumJourney = TimeInterval(SharedData.LiveRoute.rfiMaximumJourneyHours * 3_600)
    static let freshObservation = TimeInterval(SharedData.LiveRoute.freshObservationSeconds)
    static let futureObservation = TimeInterval(SharedData.LiveRoute.futureObservationSeconds)
    static let viaggiaTrenoLookBack = TimeInterval(SharedData.LiveRoute.viaggiaTrenoLookBackMinutes * 60)
    static let viaggiaTrenoLookBackWithin = TimeInterval(SharedData.LiveRoute.viaggiaTrenoLookBackWithinSeconds)
    static let viaggiaTrenoHorizon = TimeInterval(SharedData.LiveRoute.viaggiaTrenoHorizonMinutes * 60)

    /// The RFI route: each train of the origin's departures that the destination's arrivals list once (same number and
    /// operator, later, within a day), with only the observations of the last five minutes counted. Nil when none is
    /// verified.
    public static func rfiRuns(
        departures: [LiveDeparture], arrivals: [LiveDeparture], origin: Station, destination: Station, requested: Date, now: Date
    ) -> [LiveRouteRun]? {
        let from = now.addingTimeInterval(-rfiLookBack)
        let to = now.addingTimeInterval(rfiLookAhead)
        func fresh(_ observation: LiveObservation) -> Bool {
            observation.observedAt >= now.addingTimeInterval(-freshObservation)
                && observation.observedAt <= now.addingTimeInterval(futureObservation) && observation.expiresAt > now
        }
        var runs: [LiveRouteRun] = []
        var seen: Set<String> = []
        for departure in departures {
            let number = departure.number
            guard !number.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, let operatorID = departure.operatorID else { continue }
            guard departure.scheduled >= from, departure.scheduled <= to else { continue }
            if (departure.expected?.value ?? departure.scheduled) < requested { continue }
            let candidates = arrivals.filter { arrival in
                arrival.number == number && arrival.operatorID == operatorID && arrival.scheduled >= from && arrival.scheduled <= to
                    && arrival.scheduled > departure.scheduled
                    && arrival.scheduled.timeIntervalSince(departure.scheduled) <= rfiMaximumJourney
            }
            guard candidates.count == 1, let arrival = candidates.first else { continue }
            let timing = departure.expected.flatMap { fresh($0.observation) ? $0.value : nil }
            let status = departure.status.flatMap { fresh($0.observation) ? $0.value : nil }
            let arrivalStatus = arrival.status.flatMap { fresh($0.observation) ? $0.value : nil }
            let cancelled = status == .cancelled || arrivalStatus == .cancelled
            let late = !cancelled && timing == nil && status == .delayed
            let verified = !late && (timing != nil || status != nil || cancelled)
            let delay = timing.map { Int($0.timeIntervalSince(departure.scheduled) / 60) }
            let arrivalTiming = arrival.expected.flatMap { fresh($0.observation) ? $0.value : nil }
            let state: LiveRunState = cancelled ? .cancelled : late ? .onTime : ((delay ?? 0) > 0 || status == .delayed ? .delayed : .onTime)
            let id = "rfi-live:\(origin.id):\(destination.id):\(number):\(Int(departure.scheduled.timeIntervalSince1970))"
            guard seen.insert(id).inserted else { continue }
            let observations = [departure.expected?.observation, departure.status?.observation, arrival.expected?.observation,
                                arrival.status?.observation].compactMap { $0 }.filter(fresh)
            runs.append(LiveRouteRun(
                id: id, number: number, category: departure.category, operatorID: operatorID, operatorName: nil,
                scheduledDeparture: departure.scheduled, expectedDeparture: timing ?? departure.scheduled, departureVerified: timing != nil,
                departurePlatform: departure.platform.flatMap { fresh($0.observation) ? $0.value : nil },
                scheduledArrival: arrival.scheduled, expectedArrival: arrivalTiming ?? arrival.scheduled, arrivalVerified: arrivalTiming != nil,
                arrivalPlatform: arrival.platform?.value, delayMinutes: delay, state: state, lateWithoutMinutes: late, verified: verified,
                source: .rfiStationBoard, observedAt: verified ? observations.map(\.observedAt).max() : nil
            ))
        }
        let ordered = runs.enumerated().sorted { first, second in
            first.element.scheduledDeparture != second.element.scheduledDeparture
                ? first.element.scheduledDeparture < second.element.scheduledDeparture : first.offset < second.offset
        }.map(\.element).prefix(SharedData.LiveRoute.rfiMaximumRuns)
        return ordered.contains(where: \.verified) ? Array(ordered) : nil
    }

    /// The ViaggiaTreno route of one run (Android's `parseRun`): it stops at the origin's code and later at the
    /// destination (its code, else the only stop of its name), neither stop suppressed nor extraordinary unless the run
    /// is cancelled, and it leaves within eight hours of the time asked.
    public static func viaggiaTrenoRun(_ run: LiveRunStatus, origin: Station, destination: Station, requested: Date) -> LiveRouteRun? {
        guard let originCode = origin.railwayCode, NetworkReliability.isStationCode(originCode),
              let start = run.stops.firstIndex(where: { $0.stationCode == originCode }) else { return nil }
        let end: Int?
        if let code = destination.railwayCode, NetworkReliability.isStationCode(code) {
            end = run.stops.firstIndex { $0.stationCode == code }
        } else {
            let named = run.stops.indices.filter {
                RailStopNameMatcher.matches(name: destination.name, aliases: destination.aliases, published: run.stops[$0].stationName)
            }
            end = named.count == 1 ? named[0] : nil
        }
        guard let end, end > start else { return nil }
        let boarding = run.stops[start], alighting = run.stops[end]
        let cancelled = run.state == .cancelled
        if !cancelled, boarding.skipped || boarding.added || alighting.skipped || alighting.added { return nil }
        let departure = boarding.scheduledDeparture ?? boarding.scheduled
        let verified = run.operationalStatusVerified
        let delay = verified ? run.delayMinutes : nil
        let visible = TimeInterval((delay ?? 0) * 60)
        let actualDeparture = boarding.actualDeparture ?? (start == 0 ? boarding.actual : nil)
        let expectedDeparture = actualDeparture ?? departure.addingTimeInterval(visible)
        guard expectedDeparture >= requested, expectedDeparture <= requested.addingTimeInterval(viaggiaTrenoHorizon) else { return nil }
        let expectedArrival = alighting.actual ?? alighting.scheduled.addingTimeInterval(visible)
        return LiveRouteRun(
            id: "\(run.id):\(originCode)", number: run.number, category: run.category, operatorID: run.operatorID,
            operatorName: run.operatorName, scheduledDeparture: departure, expectedDeparture: expectedDeparture,
            departureVerified: actualDeparture != nil || (verified && !cancelled), departurePlatform: boarding.platform,
            scheduledArrival: alighting.scheduled, expectedArrival: expectedArrival,
            arrivalVerified: alighting.actual != nil || (verified && !cancelled), arrivalPlatform: alighting.platform,
            delayMinutes: delay, state: cancelled ? .cancelled : ((delay ?? 0) > 0 ? .delayed : .onTime),
            lateWithoutMinutes: false, verified: verified, source: .viaggiaTreno,
            observedAt: verified ? run.lastObservation?.at ?? run.observation.observedAt : nil
        )
    }

    /// "HH:mm" in Italian time.
    static func clock(_ date: Date) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Rome") ?? .current
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", parts.hour ?? 0, parts.minute ?? 0)
    }
}

/// The live runs of a route over the timetable list (Android's `LiveRunMerge`): a live run takes the place of the
/// planned train of the same run (core number, compatible operator family, the nearest scheduled departure within the
/// retiming window) unless it is unverified and the boards verified the planned one; a run the timetable does not list
/// is added. Each planned train is taken once.
public struct LiveRouteMerge: Equatable, Sendable {
    /// The live run in the place of each planned train, by the planned train's id.
    public let replaced: [String: LiveRouteRun]
    /// The runs the timetable does not list.
    public let added: [LiveRouteRun]

    public static let empty = LiveRouteMerge(replaced: [:], added: [])

    public init(replaced: [String: LiveRouteRun], added: [LiveRouteRun]) {
        self.replaced = replaced
        self.added = added
    }
}

public enum LiveRunMerge {
    public static let retimingWindow = TimeInterval(SharedData.LiveRoute.retimingWindowMinutes * 60)

    /// `verified`: the ids of the planned trains whose boards already verified them.
    public static func merge(planned: [TrainService], verified: Set<String>, runs: [LiveRouteRun], requested: Date) -> LiveRouteMerge {
        var taken: Set<Int> = []
        var replaced: [String: LiveRouteRun] = [:]
        var added: [LiveRouteRun] = []
        for run in runs {
            var best: (index: Int, minutes: Int)?
            for (index, service) in planned.enumerated() where !taken.contains(index) && sameRun(service, run) {
                guard let time = scheduled(service, requested: requested) else { continue }
                let minutes = abs(Int(run.scheduledDeparture.timeIntervalSince(time) / 60))
                guard TimeInterval(minutes * 60) <= retimingWindow else { continue }
                if best.map({ minutes < $0.minutes }) ?? true { best = (index, minutes) }
            }
            guard let best else {
                added.append(run)
                continue
            }
            taken.insert(best.index)
            let service = planned[best.index]
            // An unverified live run does not mask what the boards verified for the same train.
            if run.verified || !verified.contains(service.id) { replaced[service.id] = run }
        }
        return LiveRouteMerge(replaced: replaced, added: added)
    }

    static func sameRun(_ planned: TrainService, _ run: LiveRouteRun) -> Bool {
        guard let number = coreNumber(run.number), coreNumber(planned.number) == number else { return false }
        guard let plannedFamily = family(operatorName: planned.operatorName), let liveFamily = family(operatorID: run.operatorID) else {
            return true
        }
        return plannedFamily == liveFamily
    }

    /// "S11 - 25017" → "25017", "REG 16348" → "16348", leading zeros dropped; nil without digits.
    public static func coreNumber(_ value: String) -> String? {
        let tail = value.components(separatedBy: " - ").last ?? value
        var groups: [String] = []
        var current = ""
        for character in tail {
            if ("0"..."9").contains(character) {
                current.append(character)
            } else if !current.isEmpty {
                groups.append(current)
                current = ""
            }
        }
        if !current.isEmpty { groups.append(current) }
        guard let last = groups.last else { return nil }
        let trimmed = String(last.drop { $0 == "0" })
        return trimmed.isEmpty ? nil : trimmed
    }

    /// The family of a live source's operator id (contracts/shared-data/v1/live-route-v1.json); another operator is
    /// its own family, an unknown one nil (any).
    public static func family(operatorID: String?) -> String? {
        guard let id = operatorID?.lowercased(), !id.isEmpty else { return nil }
        return SharedData.LiveRoute.operatorFamilies[id] ?? id
    }

    /// The operator of a timetable train by its agency name, as Android's `legacyOperator` reads it ("Trenord", "NTV
    /// Italo", "Trenitalia"): nil for any other name, Android's `OTHER`.
    public static func timetableOperator(_ operatorName: String) -> String? {
        let folded = operatorName.lowercased()
        let table = SharedData.LiveRoute.timetableOperators
        // Android's order: Trenord, then Italo, then Trenitalia.
        for id in ["trenord", "italo", "trenitalia"] {
            if table.contains(where: { $0.value == id && folded.contains($0.key) }) { return id }
        }
        return nil
    }

    /// The family of a timetable operator; an unknown one is nil and matches any family, as Android's `OTHER`.
    public static func family(operatorName: String) -> String? {
        timetableOperator(operatorName).flatMap { SharedData.LiveRoute.operatorFamilies[$0] }
    }

    /// The scheduled departure of a planned train: its date, else its "HH:mm" around the time asked (an hour earlier
    /// than twelve hours before it is the next day's).
    static func scheduled(_ service: TrainService, requested: Date) -> Date? {
        if let date = service.departureDate { return date }
        let parts = service.scheduledDeparture.trimmingCharacters(in: .whitespaces).split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2, (0...23).contains(parts[0]), (0...59).contains(parts[1]) else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Rome") ?? .current
        guard let candidate = calendar.date(bySettingHour: parts[0], minute: parts[1], second: 0, of: requested) else { return nil }
        return candidate < requested.addingTimeInterval(-12 * 3_600) ? calendar.date(byAdding: .day, value: 1, to: candidate) : candidate
    }
}
