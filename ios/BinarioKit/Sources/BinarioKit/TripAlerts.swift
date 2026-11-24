import Foundation

// Android's trip notifications and alarms (notifications/ and StopAlarmPlanner.kt) on the iPhone: the
// pure rules, with the values of contracts/shared-data/v1/trip-alerts-v1.json and the cases of
// contracts/shared-rules/v1/trip-alerts-v1.json. Every time is railway-local (Europe/Rome).

/// Clock times of the railway: "H:mm" or "HH:mm", placed on Italian days.
public enum TripTime {
    /// "8:30" or "08:30"; nil for anything else (Android's `LiveStopTimes.parse`).
    public static func parse(_ value: String?) -> PlannedLocalTime? {
        guard let text = value?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
        let parts = text.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, (1...2).contains(parts[0].count), parts[1].count == 2,
              parts[0].allSatisfy({ $0.isASCII && $0.isNumber }), parts[1].allSatisfy({ $0.isASCII && $0.isNumber }),
              let hour = Int(parts[0]), let minute = Int(parts[1]) else { return nil }
        return PlannedLocalTime(hour: hour, minute: minute)
    }

    public static func instant(_ date: PlannedLocalDate, _ time: PlannedLocalTime) -> Date? {
        ItalianWallClock.instant(date: date, time: time)
    }

    /// The occurrence of `time` nearest to `reference`: "00:10" near 23:55 is the next day.
    public static func nearest(_ reference: Date, _ time: PlannedLocalTime) -> Date? {
        guard let day = ItalianWallClock.date(of: reference), let candidate = instant(day, time) else { return nil }
        let halfDay: TimeInterval = 12 * 3_600
        if candidate.timeIntervalSince(reference) > halfDay {
            return day.adding(days: -1).flatMap { instant($0, time) }
        }
        if reference.timeIntervalSince(candidate) > halfDay {
            return day.adding(days: 1).flatMap { instant($0, time) }
        }
        return candidate
    }

    /// "8:30", as Android's notifications print a time.
    public static func clock(_ instant: Date) -> String {
        guard let time = ItalianWallClock.time(of: instant) else { return "" }
        return "\(time.hour):" + LiveText.padded(time.minute, width: 2)
    }

    static func minutes(_ value: Int) -> TimeInterval { TimeInterval(value) * 60 }
}

/// The quiet hours of every alert, 22:00 to 07:00 (contracts/shared-data/v1/evening-alerts-v1.json):
/// Android's `CommuterAlertPlanner.isQuiet`.
public enum TripQuietHours {
    public static let fromHour = SharedData.EveningAlerts.quietFromHour
    public static let untilHour = SharedData.EveningAlerts.quietUntilHour

    public static func isQuiet(_ time: PlannedLocalTime) -> Bool {
        time.minutesOfDay >= fromHour * 60 || time.minutesOfDay < untilHour * 60
    }

    public static func isQuiet(at instant: Date) -> Bool {
        guard let time = ItalianWallClock.time(of: instant) else { return false }
        return isQuiet(time)
    }
}

/// The key a station name is compared by («Milano C.le» and «MILANO CENTRALE» alike), for the app's catalogue lookups.
public enum TripStationNames {
    public static func key(_ name: String) -> String { LiveText.catalogueStationKey(name, sanPrefix: true) }
}

/// Station names as the sources publish them, compared as the live overlay compares them.
enum TripStops {
    static func namesMatch(_ first: String, _ second: String) -> Bool {
        let left = LiveText.catalogueStationKey(first, sanPrefix: true)
        return !left.isEmpty && left == LiveText.catalogueStationKey(second, sanPrefix: true)
    }

    static func index(of name: String, in stops: [LiveStopStatus]) -> Int? {
        stops.firstIndex { namesMatch($0.stationName, name) }
    }
}

// MARK: - Stop alarm

/// A stop alarm still to ring: Android's `StopAlarmPlan`.
public struct StopAlarmPlan: Codable, Equatable, Sendable {
    public let trainNumber: String
    public let destination: String
    public let station: String
    /// "8:30" as the detail showed it.
    public let expectedTime: String
    public let triggerAt: Date
    public let expectedAt: Date

    public init(trainNumber: String, destination: String, station: String, expectedTime: String, triggerAt: Date, expectedAt: Date) {
        self.trainNumber = trainNumber
        self.destination = destination
        self.station = station
        self.expectedTime = expectedTime
        self.triggerAt = triggerAt
        self.expectedAt = expectedAt
    }
}

/// Android's `StopAlarmPlanner`: «Sveglia fermata» five minutes before the expected time of a future stop.
public enum StopAlarmPlanner {
    public static let leadMinutes = SharedData.TripAlerts.stopAlarmLeadMinutes
    public static let maximumAlarms = SharedData.TripAlerts.stopAlarmMaximumAlarms
    public static let keepAfterArrival = TripTime.minutes(SharedData.TripAlerts.stopAlarmKeepAfterArrivalMinutes)
    public static let notificationTimeout = TripTime.minutes(SharedData.TripAlerts.stopAlarmNotificationTimeoutMinutes)
    public static let replanThreshold: TimeInterval = SharedData.TripAlerts.stopAlarmReplanThresholdSeconds

    /// The alarm of a clock time: today's, or tomorrow's for a small hour (before 06:00) asked after
    /// 18:00 whose time today has passed. Nil once the expected time has come.
    public static func plan(trainNumber: String, destination: String, station: String, expectedTime: String, now: Date) -> StopAlarmPlan? {
        guard let time = TripTime.parse(expectedTime), let today = ItalianWallClock.date(of: now),
              let nowTime = ItalianWallClock.time(of: now), let todayExpected = TripTime.instant(today, time) else { return nil }
        var expected = todayExpected
        if todayExpected <= now, nowTime.hour >= 18, time.hour < 6, let tomorrow = today.adding(days: 1),
           let next = TripTime.instant(tomorrow, time) {
            expected = next
        }
        return plan(trainNumber: trainNumber, destination: destination, station: station, expectedTime: expectedTime, expectedAt: expected, now: now)
    }

    /// The alarm of a stop whose expected instant is known (the live run's stops).
    public static func plan(trainNumber: String, destination: String, station: String, expectedTime: String, expectedAt: Date, now: Date) -> StopAlarmPlan? {
        guard expectedAt > now else { return nil }
        let trigger = max(now.addingTimeInterval(1), expectedAt.addingTimeInterval(-TripTime.minutes(leadMinutes)))
        return StopAlarmPlan(trainNumber: trainNumber, destination: destination, station: station, expectedTime: expectedTime, triggerAt: trigger, expectedAt: expectedAt)
    }

    /// One stop alarm per train and stop, whatever its expected time.
    public static func key(_ trainNumber: String, _ station: String) -> String {
        trainNumber.trimmingCharacters(in: .whitespaces) + "|" + station.trimmingCharacters(in: .whitespaces)
    }

    public static func isOver(_ plan: StopAlarmPlan, now: Date) -> Bool {
        now > plan.expectedAt.addingTimeInterval(keepAfterArrival)
    }

    /// `plans` with `plan` replacing the alarm of the same train and stop, without the ones that are over, soonest first.
    public static func upsert(_ plans: [StopAlarmPlan], _ plan: StopAlarmPlan, now: Date) -> [StopAlarmPlan] {
        let wanted = key(plan.trainNumber, plan.station)
        let kept = plans.filter { key($0.trainNumber, $0.station) != wanted && !isOver($0, now: now) }
        return Array(([plan] + kept).sorted { $0.triggerAt < $1.triggerAt }.prefix(maximumAlarms))
    }

    /// When a restored alarm rings: at its trigger while ahead; at once when only the trigger passed; never once the stop is behind.
    public static func restoreTrigger(_ plan: StopAlarmPlan, now: Date) -> Date? {
        if plan.triggerAt > now { return plan.triggerAt }
        if now < plan.expectedAt { return now }
        return nil
    }

    /// The stored alarms that the runs a followed-trip refresh read move: a verified expected time at the stop
    /// that changes the trigger by at least `replanThreshold`. Each keeps its train, stop and destination.
    public static func replanned(_ stored: [StopAlarmPlan], runs: [LiveRunStatus], now: Date) -> [StopAlarmPlan] {
        stored.compactMap { alarm in
            guard let run = runs.first(where: { TrainAlertWatch.key($0.number) == TrainAlertWatch.key(alarm.trainNumber) }),
                  let index = TripStops.index(of: alarm.station, in: run.stops) else { return nil }
            let stop = run.stops[index]
            guard !stop.passed, stop.operationalStatusVerified,
                  let updated = plan(trainNumber: alarm.trainNumber, destination: alarm.destination, station: alarm.station,
                                     expectedTime: TripTime.clock(stop.expected), expectedAt: stop.expected, now: now),
                  abs(updated.triggerAt.timeIntervalSince(alarm.triggerAt)) >= replanThreshold else { return nil }
            return updated
        }
    }
}

// MARK: - Stop countdown

/// Where the progress behind a countdown comes from, best first (Android's `StopProgressSource`).
public enum StopProgressSource: String, Codable, Equatable, Sendable {
    /// A stop the source confirmed, or the train's last detected station.
    case live = "LIVE"
    /// The device position projected on the line, on the device only.
    case position = "POSITION"
    /// Expected or scheduled times against the clock.
    case scheduled = "SCHEDULED"
}

public struct CountdownStop: Equatable, Sendable {
    public let name: String
    public let at: Date?
    /// A stop the train no longer serves today: never counted.
    public let skipped: Bool

    public init(name: String, at: Date?, skipped: Bool = false) {
        self.name = name
        self.at = at
        self.skipped = skipped
    }
}

/// «Scendi tra N fermate»: `stopsLeft` stops still to serve up to the alighting stop; 1 is «Scendi alla prossima fermata».
public struct StopCountdown: Codable, Equatable, Sendable {
    public let stopsLeft: Int
    public let nextIndex: Int
    public let nextStop: String
    public let nextStopAt: Date?
    public let alightStop: String
    public let source: StopProgressSource

    public init(stopsLeft: Int, nextIndex: Int, nextStop: String, nextStopAt: Date?, alightStop: String, source: StopProgressSource) {
        self.stopsLeft = stopsLeft
        self.nextIndex = nextIndex
        self.nextStop = nextStop
        self.nextStopAt = nextStopAt
        self.alightStop = alightStop
        self.source = source
    }

    public var alightAtNextStop: Bool { stopsLeft <= 1 }
}

/// Android's `StopCountdownCalculator`: the last stop reached is the live one, else the position on the line, else the
/// clock; live data and position only move each other forward, the timetable counts only without either.
public enum StopCountdownCalculator {
    public static func countdown(
        _ stops: [CountdownStop], board: Int, alight: Int, now: Date, livePassed: Int? = nil, positionPassed: Int? = nil
    ) -> StopCountdown? {
        guard board >= 0, alight > board, alight < stops.count,
              let found = progress(stops, now: now, livePassed: livePassed, positionPassed: positionPassed) else { return nil }
        let passed = found.index
        guard passed >= board, passed < alight,
              let next = ((passed + 1)...alight).first(where: { !stops[$0].skipped }) else { return nil }
        let left = (next...alight).filter { !stops[$0].skipped }.count
        return StopCountdown(stopsLeft: left, nextIndex: next, nextStop: stops[next].name, nextStopAt: stops[next].at,
                             alightStop: stops[alight].name, source: found.source)
    }

    private static func progress(_ stops: [CountdownStop], now: Date, livePassed: Int?, positionPassed: Int?) -> (index: Int, source: StopProgressSource)? {
        let live = livePassed.flatMap { stops.indices.contains($0) ? $0 : nil }
        let position = positionPassed.flatMap { stops.indices.contains($0) ? $0 : nil }
        if let live, let position { return position > live ? (position, .position) : (live, .live) }
        if let live { return (live, .live) }
        if let position { return (position, .position) }
        guard let index = stops.indices.last(where: { stops[$0].at.map { $0 <= now } == true }) else { return nil }
        return (index, .scheduled)
    }
}

// MARK: - Followed trip

/// One train of a followed trip, at its scheduled times (Android's `FollowedTripLeg`).
public struct FollowedTripLeg: Codable, Equatable, Sendable {
    public let number: String
    /// "REG 2153", as the app shows the train.
    public let label: String
    public let boardStation: String
    public let alightStation: String
    public let departure: Date
    public let arrival: Date
    /// The ViaggiaTreno code of the boarding station, when known: it picks the run among the number's.
    public let boardCode: String?

    public init(number: String, label: String, boardStation: String, alightStation: String, departure: Date, arrival: Date, boardCode: String? = nil) {
        self.number = number
        self.label = label
        self.boardStation = boardStation
        self.alightStation = alightStation
        self.departure = departure
        self.arrival = arrival
        self.boardCode = boardCode
    }
}

/// The trip of the Live Activity (Android's ongoing notification): a followed train or a journey with changes.
public struct FollowedTrip: Codable, Equatable, Sendable {
    public static let maximumLegs = SharedData.TripAlerts.followedTripMaximumLegs

    public let id: String
    public let title: String
    public let legs: [FollowedTripLeg]
    /// Walk before each leg after the first, in minutes; 0 for a change at the same station.
    public let transferMinutes: [Int]

    public init?(id: String, title: String, legs: [FollowedTripLeg], transferMinutes: [Int] = []) {
        guard !legs.isEmpty else { return nil }
        self.id = id
        self.title = title
        self.legs = Array(legs.prefix(Self.maximumLegs))
        self.transferMinutes = transferMinutes
    }

    public var departure: Date { legs[0].departure }
    public var arrival: Date { legs[legs.count - 1].arrival }
    public var trainNumbers: Set<String> { Set(legs.map { TrainAlertWatch.key($0.number) }) }

    public func walkBefore(_ legIndex: Int) -> TimeInterval {
        let minutes = transferMinutes.indices.contains(legIndex - 1) ? transferMinutes[legIndex - 1] : 0
        return TripTime.minutes(min(max(minutes, 0), 120))
    }

    /// The train of a detail from its boarding stop to `alightName` (else the run's last stop), at the run's
    /// scheduled times; nil without two such stops in order.
    public static func fromRun(_ run: LiveRunStatus, label: String, boardName: String, boardCode: String?, alightName: String?, id: String = UUID().uuidString) -> FollowedTrip? {
        guard let board = TripStops.index(of: boardName, in: run.stops) else { return nil }
        let wanted = alightName.flatMap { TripStops.index(of: $0, in: run.stops) }.flatMap { $0 > board ? $0 : nil }
        let alight = wanted ?? run.stops.count - 1
        guard alight > board else { return nil }
        let first = run.stops[board]
        let last = run.stops[alight]
        let departure = first.scheduledDeparture ?? first.scheduled
        guard last.scheduled > departure else { return nil }
        let leg = FollowedTripLeg(number: run.number.trimmingCharacters(in: .whitespaces), label: label, boardStation: first.stationName,
                                  alightStation: last.stationName, departure: departure, arrival: last.scheduled,
                                  boardCode: first.stationCode ?? boardCode)
        return FollowedTrip(id: id, title: "\(first.stationName) → \(last.stationName)", legs: [leg])
    }
}

/// Android's `FollowedTripPlanner`: from 30 minutes before the departure to 30 after the arrival, stretched by the
/// last known delay (at most six hours), refreshed every 15 minutes and, in the last three stops, after each stop.
public enum FollowedTripPlanner {
    public static let lead = TripTime.minutes(SharedData.TripAlerts.followedTripLeadMinutes)
    public static let trail = TripTime.minutes(SharedData.TripAlerts.followedTripTrailMinutes)
    public static let interval = TripTime.minutes(SharedData.TripAlerts.followedTripRefreshMinutes)
    public static let maximumDelayExtension = TripTime.minutes(SharedData.TripAlerts.followedTripMaximumDelayExtensionMinutes)
    public static let nearAlightStops = SharedData.TripAlerts.followedTripNearAlightStops
    public static let nearAlightDelay: TimeInterval = SharedData.TripAlerts.followedTripNearAlightDelaySeconds
    public static let minimumRefresh = TripTime.minutes(SharedData.TripAlerts.followedTripMinimumRefreshMinutes)

    public enum Step: Equatable, Sendable {
        /// Before the window: the first run is at the date, nothing shown yet.
        case waitUntil(Date)
        /// Inside the window: show the status now, run again at the date.
        case refresh(Date)
        /// After the window: end the Live Activity.
        case finish
    }

    public static func windowStart(_ trip: FollowedTrip) -> Date { trip.departure.addingTimeInterval(-lead) }

    public static func windowEnd(_ trip: FollowedTrip, lastDelayMinutes: Int?) -> Date {
        let delay = min(TripTime.minutes(max(lastDelayMinutes ?? 0, 0)), maximumDelayExtension)
        return trip.arrival.addingTimeInterval(trail + delay)
    }

    public static func step(_ trip: FollowedTrip, now: Date, lastDelayMinutes: Int? = nil) -> Step {
        let start = windowStart(trip)
        let end = windowEnd(trip, lastDelayMinutes: lastDelayMinutes)
        if now < start { return .waitUntil(start) }
        if now > end { return .finish }
        // The run after the end comes a minute after it instead of a full interval later.
        let next = now.addingTimeInterval(interval)
        return .refresh(next > end ? end.addingTimeInterval(60) : next)
    }

    /// The window is open or still ahead: only such trips can be followed.
    public static func isUpcomingOrRunning(_ trip: FollowedTrip, now: Date) -> Bool {
        now <= windowEnd(trip, lastDelayMinutes: nil)
    }

    /// The regular refresh, or in the last stops just after the next stop's time (never sooner than two minutes).
    public static func nextRefreshAt(regular: Date, now: Date, countdown: StopCountdown?) -> Date {
        guard let countdown, countdown.stopsLeft <= nearAlightStops, let nextStop = countdown.nextStopAt else { return regular }
        let sooner = max(nextStop.addingTimeInterval(nearAlightDelay), now.addingTimeInterval(minimumRefresh))
        return min(regular, sooner)
    }
}

/// Live times of one leg (Android's `JourneyLegLive`).
public struct TripLegLive: Equatable, Sendable {
    public let expectedDeparture: Date?
    public let expectedArrival: Date?
    public let cancelled: Bool

    public init(expectedDeparture: Date?, expectedArrival: Date?, cancelled: Bool) {
        self.expectedDeparture = expectedDeparture
        self.expectedArrival = expectedArrival
        self.cancelled = cancelled
    }
}

/// A change seen with live data (Android's `TransferRiskLevel`).
public enum TripTransferRisk: String, Codable, Equatable, Sendable {
    case ok = "OK"
    case atRisk = "AT_RISK"
    case missed = "MISSED"
    case unknown = "UNKNOWN"
}

public struct TripTransferAssessment: Equatable, Sendable {
    public let level: TripTransferRisk
    public let expectedArrival: Date
    public let expectedDeparture: Date
}

/// What the Live Activity shows for one refresh (Android's `FollowedTripView`).
public struct FollowedTripView: Equatable, Sendable {
    public let legIndex: Int
    public let leg: FollowedTripLeg
    /// Verified operational data for the current train.
    public let live: Bool
    public let cancelled: Bool
    public let delayMinutes: Int?
    public let nextStop: String?
    /// Expected with live data, scheduled otherwise.
    public let nextStopTime: Date?
    public let platform: String?
    public let transfer: TripTransferAssessment?
    public let transferStation: String?
    public let nextLegLabel: String?
    public let countdown: StopCountdown?
    /// The delay estimated from the device position: an estimate, never official.
    public let estimate: GpsDelayEstimate?
    public let trackStatus: GpsTrackStatus?
}

/// Android's `FollowedTripStatus`, on the runs of the live sources.
public enum FollowedTripStatus {
    /// The run of `leg` among the number's: the one calling at both stations in order, else the only one.
    public static func pick(_ runs: [LiveRunStatus], leg: FollowedTripLeg) -> LiveRunStatus? {
        let key = TrainAlertWatch.key(leg.number)
        let numbered = runs.filter { TrainAlertWatch.key($0.number) == key }
        let sameNumber = numbered.isEmpty ? runs : numbered
        if let run = sameNumber.first(where: { run in
            guard let board = TripStops.index(of: leg.boardStation, in: run.stops),
                  let alight = TripStops.index(of: leg.alightStation, in: run.stops) else { return false }
            return alight > board
        }) { return run }
        return sameNumber.count == 1 ? sameNumber[0] : nil
    }

    /// The expected time at `station`: the stop's verified expected time, else the scheduled time plus the run's
    /// verified delay when this source does not publish the stop; nil without operational data.
    static func expectedAt(_ run: LiveRunStatus, station: String, scheduled: Date) -> Date? {
        if let index = TripStops.index(of: station, in: run.stops) {
            let stop = run.stops[index]
            return stop.operationalStatusVerified ? stop.expected : nil
        }
        guard run.operationalStatusVerified else { return nil }
        return scheduled.addingTimeInterval(TripTime.minutes(max(run.delayMinutes ?? 0, 0)))
    }

    static func isCancelled(_ run: LiveRunStatus) -> Bool {
        run.operationalStatusVerified && run.state == .cancelled
    }

    public static func live(_ leg: FollowedTripLeg, run: LiveRunStatus?) -> TripLegLive? {
        guard let run else { return nil }
        let cancelled = isCancelled(run)
        let result = TripLegLive(
            expectedDeparture: cancelled ? nil : expectedAt(run, station: leg.boardStation, scheduled: leg.departure),
            expectedArrival: cancelled ? nil : expectedAt(run, station: leg.alightStation, scheduled: leg.arrival),
            cancelled: cancelled
        )
        return result.expectedDeparture != nil || result.expectedArrival != nil || cancelled ? result : nil
    }

    /// Android's `TransferRiskCalculator.assess` with its five-minute default change time.
    public static func assess(scheduledArrival: Date, live: TripLegLive?, scheduledDeparture: Date, nextLive: TripLegLive?, walk: TimeInterval) -> TripTransferAssessment {
        let planned = max(walk, 0)
        let wait = scheduledDeparture.timeIntervalSince(scheduledArrival)
        let transfer = planned == 0 ? (wait < 0 ? 0 : min(wait, TripTime.minutes(5))) : planned
        let minimum = planned / 2
        let liveArrival = live?.expectedArrival
        let liveDeparture = nextLive?.expectedDeparture
        let cancelled = live?.cancelled == true || nextLive?.cancelled == true
        let arrival = liveArrival ?? scheduledArrival
        let departure = liveDeparture ?? scheduledDeparture
        let margin = departure.timeIntervalSince(arrival.addingTimeInterval(transfer))
        let level: TripTransferRisk
        if liveArrival == nil, liveDeparture == nil, !cancelled {
            level = .unknown
        } else if cancelled {
            level = .missed
        } else if margin >= 0 {
            level = .ok
        } else if departure >= arrival.addingTimeInterval(minimum) {
            level = .atRisk
        } else {
            level = .missed
        }
        return TripTransferAssessment(level: level, expectedArrival: arrival, expectedDeparture: departure)
    }

    /// The train the traveller is on or waiting for: the first whose (expected) arrival is still ahead, else the last.
    public static func currentLegIndex(_ trip: FollowedTrip, lives: [TripLegLive?], now: Date) -> Int {
        trip.legs.indices.first { index in
            let arrival = (lives.indices.contains(index) ? lives[index]?.expectedArrival : nil) ?? trip.legs[index].arrival
            return arrival > now
        } ?? trip.legs.count - 1
    }

    /// `position` is the progress the position session derived; used only when it follows the current train.
    public static func view(_ trip: FollowedTrip, runs: [LiveRunStatus?], now: Date, position: GpsTrainTrack? = nil) -> FollowedTripView {
        func run(_ index: Int) -> LiveRunStatus? { runs.indices.contains(index) ? runs[index] : nil }
        let lives = trip.legs.indices.map { live(trip.legs[$0], run: run($0)) }
        let index = currentLegIndex(trip, lives: lives, now: now)
        let leg = trip.legs[index]
        let current = run(index)
        let verified = current?.operationalStatusVerified == true
        let cancelled = current.map(isCancelled) ?? false
        let stops = current?.stops ?? []
        let board = TripStops.index(of: leg.boardStation, in: stops) ?? 0
        let alight = TripStops.index(of: leg.alightStation, in: stops).flatMap { $0 >= board ? $0 : nil } ?? stops.count - 1
        let window: [LiveStopStatus] = stops.isEmpty || alight < board ? [] : Array(stops[board...alight])
        let track = position.flatMap { $0.isFor(leg.number) ? $0 : nil }
        var countdown: StopCountdown?
        if let current, !cancelled {
            countdown = Self.countdown(current, board: board, alight: alight, now: now, track: track)
        }
        let next: LiveStopStatus? = countdown.map { stops[$0.nextIndex] } ?? window.first(where: { !$0.passed }) ?? window.last
        var transfer: TripTransferAssessment?
        if index < trip.legs.count - 1 {
            let following = trip.legs[index + 1]
            transfer = assess(scheduledArrival: leg.arrival, live: lives[index], scheduledDeparture: following.departure,
                              nextLive: lives[index + 1], walk: trip.walkBefore(index + 1))
        }
        let nextIsFirst: Bool
        if let next { nextIsFirst = window.first == next } else { nextIsFirst = true }
        let nextPlatform = next?.platform?.trimmingCharacters(in: .whitespaces)
        let runPlatform = current?.platform?.trimmingCharacters(in: .whitespaces)
        let platform: String?
        if let nextPlatform, !nextPlatform.isEmpty {
            platform = nextPlatform
        } else if nextIsFirst, let runPlatform, !runPlatform.isEmpty {
            platform = runPlatform
        } else {
            platform = nil
        }
        return FollowedTripView(
            legIndex: index,
            leg: leg,
            live: verified,
            cancelled: cancelled,
            delayMinutes: verified && !cancelled ? max(current?.delayMinutes ?? 0, 0) : nil,
            nextStop: next?.stationName,
            nextStopTime: next.map { $0.operationalStatusVerified ? $0.expected : $0.scheduled },
            platform: platform,
            transfer: transfer,
            transferStation: transfer == nil ? nil : leg.alightStation,
            nextLegLabel: transfer == nil ? nil : trip.legs[index + 1].label,
            countdown: countdown,
            estimate: cancelled ? nil : track?.estimate,
            trackStatus: track?.status
        )
    }

    /// The countdown to `alight` of `run`: live progress (the last confirmed stop or the last observed station), the
    /// device position when a fresh one follows this train, else the expected or scheduled times against `now`.
    public static func countdown(_ run: LiveRunStatus, board: Int, alight: Int, now: Date, track: GpsTrainTrack? = nil) -> StopCountdown? {
        let stops = run.stops
        guard alight > board, alight < stops.count else { return nil }
        let timeline = stops.map { CountdownStop(name: $0.stationName, at: $0.expected, skipped: $0.skipped) }
        let completed = stops.indices.last { stops[$0].passed && stops[$0].operationalStatusVerified }
        let detected = run.lastObservation?.stationName.flatMap { TripStops.index(of: $0, in: stops) }
        let livePassed = [completed, detected].compactMap { $0 }.max()
        var positionPassed: Int?
        if let track, track.freshPassedIndex(now: now) != nil, let name = track.passedStop {
            positionPassed = TripStops.index(of: name, in: stops)
        }
        return StopCountdownCalculator.countdown(timeline, board: board, alight: alight, now: now, livePassed: livePassed, positionPassed: positionPassed)
    }

    /// The arrival delay that stretches the window: the last train's, once known.
    public static func arrivalDelayMinutes(_ trip: FollowedTrip, runs: [LiveRunStatus?]) -> Int? {
        let index = trip.legs.count - 1
        let last = trip.legs[index]
        guard runs.indices.contains(index), let arrival = live(last, run: runs[index])?.expectedArrival else { return nil }
        return max(Int(arrival.timeIntervalSince(last.arrival) / 60), 0)
    }
}

/// A change of the followed trip notified with sound (Android's `FollowedTripChange`).
public enum FollowedTripChange: Equatable, Sendable {
    case cancelled(legIndex: Int, key: String)
    case platformChanged(legIndex: Int, station: String, platform: String, previous: String, key: String)
    case connectionAtRisk(legIndex: Int, level: TripTransferRisk, key: String)

    public var key: String {
        switch self {
        case let .cancelled(_, key): return key
        case let .platformChanged(_, _, _, _, key): return key
        case let .connectionAtRisk(_, _, key): return key
        }
    }

    public var legIndex: Int {
        switch self {
        case let .cancelled(index, _): return index
        case let .platformChanged(index, _, _, _, _): return index
        case let .connectionAtRisk(index, _, _): return index
        }
    }
}

/// Android's `FollowedTripAlerts`: the current train is cancelled, its boarding platform moves, or the next change
/// becomes at risk or missed; each change once per trip.
public enum FollowedTripAlerts {
    public struct BoardingPlatform: Codable, Equatable, Sendable {
        public let legIndex: Int
        public let station: String
        public let platform: String
        public let scheduled: String?

        public init(legIndex: Int, station: String, platform: String, scheduled: String?) {
            self.legIndex = legIndex
            self.station = station
            self.platform = platform
            self.scheduled = scheduled
        }
    }

    /// "05" and "5", "7 Ovest" and "7  OVEST" name the same platform (Android's `PlatformChange.normalize`).
    public static func normalizePlatform(_ value: String) -> String {
        let upper = value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        var characters = Array(upper.split(whereSeparator: { $0.isWhitespace }).joined(separator: " "))
        while characters.count > 1, characters[0] == "0", characters[1].isASCII, characters[1].isNumber {
            characters.removeFirst()
        }
        return String(characters)
    }

    /// The platform moved away from `scheduled`: nil when either is missing or both name the same platform.
    public static func platformChange(_ actual: String?, _ scheduled: String?) -> (actual: String, scheduled: String)? {
        guard let now = actual?.trimmingCharacters(in: .whitespaces), !now.isEmpty,
              let planned = scheduled?.trimmingCharacters(in: .whitespaces), !planned.isEmpty,
              normalizePlatform(now) != normalizePlatform(planned) else { return nil }
        return (now, planned)
    }

    public static func boardingPlatform(_ trip: FollowedTrip, view: FollowedTripView, runs: [LiveRunStatus?]) -> BoardingPlatform? {
        guard runs.indices.contains(view.legIndex), let run = runs[view.legIndex], run.operationalStatusVerified else { return nil }
        let leg = trip.legs[view.legIndex]
        let index = TripStops.index(of: leg.boardStation, in: run.stops)
        let stop = index.map { run.stops[$0] }
        if stop?.passed == true { return nil }
        let first = index == nil || index == 0
        guard let platform = (stop?.platform ?? (first ? run.platform : nil))?.trimmingCharacters(in: .whitespaces),
              !platform.isEmpty else { return nil }
        let scheduled = (stop?.scheduledPlatform ?? (first ? run.scheduledPlatform : nil))?.trimmingCharacters(in: .whitespaces)
        return BoardingPlatform(legIndex: view.legIndex, station: stop?.stationName ?? leg.boardStation, platform: platform,
                                scheduled: scheduled?.isEmpty == false ? scheduled : nil)
    }

    public static func changes(
        _ trip: FollowedTrip, view: FollowedTripView, runs: [LiveRunStatus?], previousBoarding: BoardingPlatform?, notified: Set<String>
    ) -> [FollowedTripChange] {
        let leg = trip.legs[view.legIndex]
        var found: [FollowedTripChange] = []
        if view.cancelled {
            // A cancelled train says it all: its platform and the change it was to reach no longer matter.
            found.append(.cancelled(legIndex: view.legIndex, key: "cancelled|\(view.legIndex)|\(TrainAlertWatch.key(leg.number))"))
        } else {
            if let boarding = boardingPlatform(trip, view: view, runs: runs) {
                let earlier = previousBoarding.flatMap { $0.legIndex == boarding.legIndex ? $0.platform : nil }
                if let change = platformChange(boarding.platform, boarding.scheduled) ?? platformChange(boarding.platform, earlier) {
                    found.append(.platformChanged(legIndex: view.legIndex, station: boarding.station, platform: change.actual,
                                                  previous: change.scheduled, key: "platform|\(view.legIndex)|\(normalizePlatform(change.actual))"))
                }
            }
            if let level = view.transfer?.level, level == .atRisk || level == .missed {
                found.append(.connectionAtRisk(legIndex: view.legIndex, level: level, key: "transfer|\(view.legIndex)|\(level.rawValue)"))
            }
        }
        return found.filter { !notified.contains($0.key) }
    }
}

// MARK: - «Avvisami quando sta arrivando»

/// An approach alert of a favourite train (Android's `ApproachAlert`).
public struct ApproachAlert: Codable, Equatable, Sendable {
    public let number: String
    public let label: String
    public let station: String
    /// The scheduled "HH:mm" at the station.
    public let stationTime: String
    /// The train's scheduled departure, which tells a stop after midnight from one of the same evening.
    public let departure: String
    public let leadMinutes: Int
    public let createdAt: Date

    public init(number: String, label: String, station: String, stationTime: String, departure: String, leadMinutes: Int, createdAt: Date) {
        self.number = number
        self.label = label
        self.station = station
        self.stationTime = stationTime
        self.departure = departure
        self.leadMinutes = leadMinutes
        self.createdAt = createdAt
    }

    public var key: String { TrainAlertWatch.key(number) }
}

public struct ApproachObservation: Codable, Equatable, Sendable {
    /// Expected time at the station with live data, else the scheduled one.
    public let expectedAt: Date
    /// Verified delay at the station, nil for a schedule-only answer.
    public let delayMinutes: Int?
    public let platform: String?
    public let passed: Bool
    public let cancelled: Bool

    public init(expectedAt: Date, delayMinutes: Int?, platform: String?, passed: Bool, cancelled: Bool) {
        self.expectedAt = expectedAt
        self.delayMinutes = delayMinutes
        self.platform = platform
        self.passed = passed
        self.cancelled = cancelled
    }

    public var live: Bool { delayMinutes != nil }
}

public enum ApproachLookup: Equatable, Sendable {
    case observed(ApproachObservation)
    /// Every source answered: no run that day, or its run does not call at the station.
    case notRunning
    /// No usable answer: the last observation or the timetable stays the fallback.
    case unknown
}

public enum ApproachDecision: Equatable, Sendable {
    case notify(minutesLeft: Int)
    case waitUntil(Date)
    case done
}

/// Android's `ApproachAlertPlanner`: checks every 15 minutes from 30 minutes before the moment to notify, which each
/// check moves with the live delay; never between 22 and 7, at most once a day per train.
public enum ApproachAlertPlanner {
    public static let leadOptions = SharedData.TripAlerts.approachLeadOptionsMinutes
    public static let defaultLead = SharedData.TripAlerts.approachDefaultLeadMinutes
    public static let checkInterval = TripTime.minutes(SharedData.TripAlerts.approachCheckMinutes)
    public static let firstCheckBefore = TripTime.minutes(SharedData.TripAlerts.approachFirstCheckBeforeMinutes)
    public static let maximumDelay = TripTime.minutes(SharedData.TripAlerts.approachMaximumDelayMinutes)
    public static let earlyTolerance = TripTime.minutes(SharedData.TripAlerts.approachEarlyToleranceMinutes)
    public static let lateTolerance = TripTime.minutes(SharedData.TripAlerts.approachLateToleranceMinutes)
    public static let minimumGap = TripTime.minutes(SharedData.TripAlerts.approachMinimumGapMinutes)
    public static let notificationTimeout = TripTime.minutes(SharedData.TripAlerts.approachNotificationTimeoutMinutes)
    public static let maximumAlerts = SharedData.TripAlerts.approachMaximumAlerts

    public static func lead(_ alert: ApproachAlert) -> TimeInterval {
        let first = leadOptions.first ?? defaultLead
        let last = leadOptions.last ?? defaultLead
        return TripTime.minutes(min(max(alert.leadMinutes, first), last))
    }

    /// The scheduled time at the station for the run that departs on `date`.
    public static func scheduledAt(_ alert: ApproachAlert, date: PlannedLocalDate) -> Date? {
        guard let time = TripTime.parse(alert.stationTime) else { return nil }
        var day = date
        if let departure = TripTime.parse(alert.departure), time < departure, let next = date.adding(days: 1) { day = next }
        return TripTime.instant(day, time)
    }

    /// The departure date of the run reaching the station on `day`: the day before for a station after midnight.
    public static func serviceDate(_ alert: ApproachAlert, day: PlannedLocalDate) -> PlannedLocalDate {
        guard let arrival = scheduledAt(alert, date: day), let arrivalDay = ItalianWallClock.date(of: arrival) else { return day }
        return arrivalDay > day ? (day.adding(days: -1) ?? day) : day
    }

    /// When the checks of `date` run: around the scheduled time, inside 07:00 – 22:00 of the day the train gets there.
    public static func window(_ alert: ApproachAlert, date: PlannedLocalDate) -> ClosedRange<Date>? {
        guard let scheduled = scheduledAt(alert, date: date), let day = ItalianWallClock.date(of: scheduled),
              let untilTime = PlannedLocalTime(hour: TripQuietHours.untilHour, minute: 0),
              let fromTime = PlannedLocalTime(hour: TripQuietHours.fromHour, minute: 0),
              let quietEnd = TripTime.instant(day, untilTime), let quietStart = TripTime.instant(day, fromTime) else { return nil }
        let lead = lead(alert)
        if scheduled.addingTimeInterval(lateTolerance) < quietEnd || !(scheduled.addingTimeInterval(-lead) < quietStart) { return nil }
        let start = max(scheduled.addingTimeInterval(-lead - firstCheckBefore), quietEnd)
        let end = min(scheduled.addingTimeInterval(maximumDelay), quietStart)
        return start < end ? start...end : nil
    }

    /// The timetable moment to notify falls in the quiet hours: the alert comes later, or not at all.
    public static func quietOnTimetable(_ alert: ApproachAlert) -> Bool {
        guard let time = TripTime.parse(alert.stationTime) else { return false }
        let minutes = ((time.minutesOfDay - Int(lead(alert) / 60)) % 1_440 + 1_440) % 1_440
        guard let notifyTime = PlannedLocalTime(hour: minutes / 60, minute: minutes % 60) else { return false }
        return TripQuietHours.isQuiet(notifyTime)
    }

    public static func notifyAt(_ alert: ApproachAlert, expectedAt: Date) -> Date {
        expectedAt.addingTimeInterval(-lead(alert))
    }

    /// Whole minutes still to go, rounded up: «tra ~4 min» at 3 minutes 20 seconds.
    public static func minutesUntil(now: Date, expected: Date) -> Int {
        let seconds = Int(expected.timeIntervalSince(now).rounded(.down))
        return seconds <= 0 ? 0 : (seconds + 59) / 60
    }

    public static func decide(_ alert: ApproachAlert, date: PlannedLocalDate, observation: ApproachObservation?, now: Date, notified: Bool) -> ApproachDecision {
        guard let window = window(alert, date: date) else { return .done }
        if notified || now > window.upperBound { return .done }
        if observation?.passed == true || observation?.cancelled == true { return .done }
        guard let expected = observation?.expectedAt ?? scheduledAt(alert, date: date) else { return .done }
        let moment = notifyAt(alert, expectedAt: expected)
        if !(now < moment.addingTimeInterval(-earlyTolerance)) {
            if now > expected.addingTimeInterval(lateTolerance) || TripQuietHours.isQuiet(at: now) { return .done }
            return .notify(minutesLeft: minutesUntil(now: now, expected: expected))
        }
        let nextCheck = now < window.lowerBound ? window.lowerBound : now.addingTimeInterval(checkInterval)
        return .waitUntil(max(min(nextCheck, moment), now.addingTimeInterval(minimumGap)))
    }

    /// The check after a lookup: a day without the train at the station is over; without an answer the last
    /// observation of the day, else the timetable, decides.
    public static func decide(_ alert: ApproachAlert, date: PlannedLocalDate, lookup: ApproachLookup, last: ApproachObservation?, now: Date) -> ApproachDecision {
        if lookup == .notRunning { return .done }
        return decide(alert, date: date, observation: observationAfter(lookup, last: last), now: now, notified: false)
    }

    public static func observationAfter(_ lookup: ApproachLookup, last: ApproachObservation?) -> ApproachObservation? {
        switch lookup {
        case let .observed(observation): return observation
        case .notRunning: return nil
        case .unknown: return last
        }
    }

    /// The next run: the next check or moment to notify of today's run, else the window of the next run.
    public static func nextRun(_ alert: ApproachAlert, doneOn: PlannedLocalDate?, notifiedOn: PlannedLocalDate?, observation: ApproachObservation?, now: Date) -> Date? {
        guard let today = ItalianWallClock.date(of: now) else { return nil }
        let date = serviceDate(alert, day: today)
        if doneOn != date, notifiedOn != date, let window = window(alert, date: date), now <= window.upperBound {
            switch decide(alert, date: date, observation: observation, now: now, notified: false) {
            case let .waitUntil(at): return at
            case .notify: return now
            case .done: break
            }
        }
        for offset in 1...2 {
            if let day = date.adding(days: offset), let start = window(alert, date: day)?.lowerBound, start > now { return start }
        }
        return nil
    }

    /// When the stored observation delivers the alert by itself (a pending local notification, Android's wake-up
    /// alarm): at its moment to notify, in the future, inside the window and outside the quiet hours.
    public static func alarmAt(_ alert: ApproachAlert, doneOn: PlannedLocalDate?, notifiedOn: PlannedLocalDate?, observation: ApproachObservation?, now: Date) -> Date? {
        guard let observation, !observation.passed, !observation.cancelled, let today = ItalianWallClock.date(of: now) else { return nil }
        let date = serviceDate(alert, day: today)
        guard doneOn != date, notifiedOn != date, let window = window(alert, date: date) else { return nil }
        let at = notifyAt(alert, expectedAt: observation.expectedAt)
        return at > now && at <= window.upperBound && !TripQuietHours.isQuiet(at: at) ? at : nil
    }

    /// What `run` (the run of `date`) says about the alert's station; nil when it does not call there.
    public static func observe(_ run: LiveRunStatus, alert: ApproachAlert, date: PlannedLocalDate) -> ApproachObservation? {
        guard let index = TripStops.index(of: alert.station, in: run.stops), let stored = scheduledAt(alert, date: date) else { return nil }
        let stop = run.stops[index]
        // The run's own timetable wins over the time saved with the alert.
        let scheduled = abs(stop.scheduled.timeIntervalSince(stored)) <= 12 * 3_600 ? stop.scheduled : stored
        let verified = run.operationalStatusVerified && stop.operationalStatusVerified
        let expected: Date? = verified ? stop.expected : nil
        let platform = stop.platform?.trimmingCharacters(in: .whitespaces)
        return ApproachObservation(
            expectedAt: expected ?? scheduled,
            delayMinutes: expected.map { max(Int($0.timeIntervalSince(scheduled) / 60), 0) },
            platform: platform?.isEmpty == false ? platform : nil,
            passed: verified && stop.passed,
            cancelled: FollowedTripStatus.isCancelled(run) || stop.skipped
        )
    }

    /// The answer of the number lookup for the run of `date`; nil when the lookup could not run.
    public static func lookup(_ result: LiveTrainStatusResult?, alert: ApproachAlert, date: PlannedLocalDate) -> ApproachLookup {
        // «The sources answered and know no such train»: Android's complete answer without matches.
        if case .unavailable(.notFound)? = result { return .notRunning }
        guard let result, case let .available(found) = result else { return .unknown }
        let runs = found.runs.filter { TrainAlertWatch.key($0.number) == alert.key }
        let calling = runs.filter { TripStops.index(of: alert.station, in: $0.stops) != nil }
        if calling.count == 1, let observation = observe(calling[0], alert: alert, date: date) { return .observed(observation) }
        if runs.count == 1, !runs[0].stops.isEmpty, calling.isEmpty { return .notRunning }
        let everySourceAnswered = found.isComplete && found.unavailableSources.isEmpty
        return everySourceAnswered && runs.isEmpty ? .notRunning : .unknown
    }

    /// Only the alerts of trains still among the favourites.
    public static func active(_ alerts: [ApproachAlert], favouriteNumbers: [String]) -> [ApproachAlert] {
        let keys = Set(favouriteNumbers.map(TrainAlertWatch.key))
        return alerts.filter { keys.contains($0.key) }
    }
}

// MARK: - New notices of the trains with the bell

/// A train whose new notices the user asked for (the bell of the detail), Android's `TrainAlertSubscription`.
public struct TrainAlertSubscription: Codable, Equatable, Sendable {
    public let number: String
    /// The ViaggiaTreno origin code when known.
    public let originCode: String?
    /// Scheduled departure "HH:mm", when known: the window is built around it.
    public let departure: String
    public let label: String
    public var subscribedAt: Date

    public init(number: String, originCode: String?, departure: String, label: String, subscribedAt: Date) {
        self.number = number
        self.originCode = originCode
        self.departure = departure
        self.label = label
        self.subscribedAt = subscribedAt
    }

    public var key: String { TrainAlertWatch.key(number) }
}

/// A notice as the dedupe sees it.
public struct WatchedNotice: Equatable, Sendable {
    public let id: String
    public let text: String
    public let publishedAt: Date?

    public init(id: String, text: String, publishedAt: Date?) {
        self.id = id
        self.text = text
        self.publishedAt = publishedAt
    }
}

/// A followed-trip train with its scheduled times, which then decide the window.
public struct FollowedTrainTimes: Equatable, Sendable {
    public let number: String
    public let departure: Date
    public let arrival: Date

    public init(number: String, departure: Date, arrival: Date) {
        self.number = number
        self.departure = departure
        self.arrival = arrival
    }
}

/// Android's `TrainAlertWatch`: a check every 15 minutes in each train's window of its day, never between 22 and 7,
/// and only the notices the iPhone has not seen yet.
public enum TrainAlertWatch {
    public static let interval = TripTime.minutes(SharedData.TripAlerts.trainAlertsCheckMinutes)
    public static let beforeDeparture = TripTime.minutes(SharedData.TripAlerts.trainAlertsBeforeDepartureMinutes)
    public static let afterDeparture = TripTime.minutes(SharedData.TripAlerts.trainAlertsAfterDepartureMinutes)
    public static let afterArrival = TripTime.minutes(SharedData.TripAlerts.trainAlertsAfterArrivalMinutes)
    public static let maximumSubscriptions = SharedData.TripAlerts.trainAlertsMaximumSubscriptions
    public static let maximumSeen = SharedData.TripAlerts.trainAlertsMaximumSeen
    public static let seenRetentionDays = SharedData.TripAlerts.trainAlertsSeenRetentionDays

    /// "REG 2153", "2153" and "02153" are the same public train: the numeric part when there is one.
    public static func key(_ number: String) -> String {
        let query = LiveTrainNumberQuery(number)
        return query.numeric ?? query.normalized
    }

    public static func window(_ subscription: TrainAlertSubscription, date: PlannedLocalDate, followed: [FollowedTrainTimes]) -> ClosedRange<Date>? {
        guard let untilTime = PlannedLocalTime(hour: TripQuietHours.untilHour, minute: 0),
              let fromTime = PlannedLocalTime(hour: TripQuietHours.fromHour, minute: 0),
              let dayStart = TripTime.instant(date, untilTime), let dayEnd = TripTime.instant(date, fromTime) else { return nil }
        var start = dayStart
        var end = dayEnd
        if let trip = followed.first(where: { key($0.number) == subscription.key && ItalianWallClock.date(of: $0.departure) == date }) {
            start = trip.departure.addingTimeInterval(-beforeDeparture)
            end = trip.arrival.addingTimeInterval(afterArrival)
        } else if let time = TripTime.parse(subscription.departure), let departure = TripTime.instant(date, time) {
            start = departure.addingTimeInterval(-beforeDeparture)
            end = departure.addingTimeInterval(afterDeparture)
        }
        let clippedStart = max(start, dayStart)
        let clippedEnd = min(end, dayEnd)
        return clippedStart < clippedEnd ? clippedStart...clippedEnd : nil
    }

    public static func due(_ subscriptions: [TrainAlertSubscription], followed: [FollowedTrainTimes], now: Date) -> [TrainAlertSubscription] {
        guard !TripQuietHours.isQuiet(at: now), let today = ItalianWallClock.date(of: now) else { return [] }
        return subscriptions.filter { window($0, date: today, followed: followed)?.contains(now) == true }
    }

    /// The next check: 15 minutes after the last one while a window is open, else the next window today or tomorrow.
    public static func nextRun(_ subscriptions: [TrainAlertSubscription], followed: [FollowedTrainTimes], now: Date, lastCheckAt: Date?) -> Date? {
        guard !subscriptions.isEmpty, let today = ItalianWallClock.date(of: now) else { return nil }
        if !due(subscriptions, followed: followed, now: now).isEmpty {
            if let next = lastCheckAt?.addingTimeInterval(interval), next > now { return next }
            return now
        }
        var starts: [Date] = []
        for offset in 0...1 {
            guard let day = today.adding(days: offset) else { continue }
            starts += subscriptions.compactMap { window($0, date: day, followed: followed)?.lowerBound }
        }
        return starts.filter { $0 > now }.min()
    }

    /// Notices not seen before; one published before the bell was already in the detail the user opened.
    public static func newNotices(_ notices: [WatchedNotice], seen: [String: Int], subscribedAt: Date) -> [WatchedNotice] {
        var ids = Set<String>()
        return notices.filter { notice in
            guard ids.insert(notice.id).inserted, seen[notice.id] == nil else { return false }
            return !(notice.publishedAt.map { $0 < subscribedAt } ?? false)
        }
    }

    /// `seen` with `notices` marked as seen today, ids not seen for a week dropped, the most recent kept.
    public static func remember(_ seen: [String: Int], notices: [WatchedNotice], today: PlannedLocalDate) -> [String: Int] {
        let day = today.daysSinceEpoch
        var merged = seen
        for notice in notices { merged[notice.id] = day }
        let kept = merged.filter { day - $0.value < seenRetentionDays }
            .sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
            .prefix(maximumSeen)
        var result: [String: Int] = [:]
        for entry in kept { result[entry.key] = entry.value }
        return result
    }

    /// The bells after the favourites or the followed trip changed: a train that left loses its bell; one that comes
    /// back starts now. Without a previous state nothing changes.
    public static func prune(_ subscriptions: [TrainAlertSubscription], previouslyWatched: Set<String>?, watched: Set<String>, now: Date) -> [TrainAlertSubscription] {
        guard let previouslyWatched else { return subscriptions }
        return subscriptions.compactMap { subscription in
            var subscription = subscription
            if previouslyWatched.contains(subscription.key), !watched.contains(subscription.key) { return nil }
            if !previouslyWatched.contains(subscription.key), watched.contains(subscription.key) {
                subscription.subscribedAt = max(subscription.subscribedAt, now)
            }
            return subscription
        }
    }

    public static func watchedKeys(favourites: [String], followed: [String]) -> Set<String> {
        Set((favourites + followed).map(key))
    }
}

// MARK: - Commuter alerts

/// The state of the train a commuter alert is about, from verified data only.
public struct CommuterLiveStatus: Codable, Equatable, Sendable {
    public let delayMinutes: Int
    public let platform: String?
    public let cancelled: Bool

    public init(delayMinutes: Int, platform: String?, cancelled: Bool) {
        self.delayMinutes = delayMinutes
        self.platform = platform
        self.cancelled = cancelled
    }
}

/// The texts of the status sentence, resolved by the app.
public struct CommuterAlertStatusStrings: Sendable {
    public let unavailable: String
    public let onTime: String
    public let cancelled: String
    public let delay: @Sendable (Int) -> String
    public let withPlatform: @Sendable (String, String) -> String

    public init(unavailable: String, onTime: String, cancelled: String, delay: @escaping @Sendable (Int) -> String,
                withPlatform: @escaping @Sendable (String, String) -> String) {
        self.unavailable = unavailable
        self.onTime = onTime
        self.cancelled = cancelled
        self.delay = delay
        self.withPlatform = withPlatform
    }
}

/// Android's `CommuterAlertStatusRules`.
public enum CommuterAlertStatusRules {
    /// "Milano Centrale → Pavia": the two station names of a route label.
    public static func routeStations(_ label: String) -> (origin: String, destination: String)? {
        let parts = label.components(separatedBy: " → ")
        guard parts.count == 2 else { return nil }
        let origin = parts[0].trimmingCharacters(in: .whitespaces)
        let destination = parts[1].trimmingCharacters(in: .whitespaces)
        return origin.isEmpty || destination.isEmpty ? nil : (origin, destination)
    }

    /// The status at the station; nil for schedule-only data, which says nothing about the train today.
    public static func statusOf(_ run: LiveRunStatus, station: String) -> CommuterLiveStatus? {
        guard run.operationalStatusVerified else { return nil }
        if run.state == .cancelled { return CommuterLiveStatus(delayMinutes: 0, platform: nil, cancelled: true) }
        let index = TripStops.index(of: station, in: run.stops)
        let stop = index.map { run.stops[$0] }
        let verifiedDelay = stop.flatMap { $0.operationalStatusVerified ? $0.delayMinutes : nil }
        let delay = verifiedDelay ?? run.delayMinutes ?? 0
        let platform = (stop?.platform ?? (index == nil || index == 0 ? run.platform : nil))?.trimmingCharacters(in: .whitespaces)
        return CommuterLiveStatus(delayMinutes: max(delay, 0), platform: platform?.isEmpty == false ? platform : nil, cancelled: false)
    }

    /// «ritardo di 5 minuti, binario 3», «in orario», «treno soppresso» or «stato non disponibile».
    public static func format(_ status: CommuterLiveStatus?, strings: CommuterAlertStatusStrings) -> String {
        guard let status else { return strings.unavailable }
        if status.cancelled { return strings.cancelled }
        let base = status.delayMinutes > 0 ? strings.delay(status.delayMinutes) : strings.onTime
        guard let platform = status.platform else { return base }
        return strings.withPlatform(base, platform)
    }
}

/// How often a commuter alert repeats (Android's `AlertRepeat`), ISO weekdays (1 Monday … 7 Sunday).
public enum CommuterAlertRepeat: String, Codable, CaseIterable, Equatable, Sendable {
    case once = "ONCE"
    case weekdays = "WEEKDAYS"
    case daily = "DAILY"

    public var isoWeekdays: Set<Int> {
        switch self {
        case .once: return []
        case .weekdays: return [1, 2, 3, 4, 5]
        case .daily: return [1, 2, 3, 4, 5, 6, 7]
        }
    }
}

/// A commuter alert (Android's `CommuterAlert`): a reminder `leadMinutes` before a train's time at a station.
public struct CommuterAlert: Codable, Equatable, Identifiable, Sendable {
    public static let maximumAlerts = SharedData.TripAlerts.commuterMaximumAlerts
    public static let leadOptions = SharedData.TripAlerts.commuterLeadOptionsMinutes

    public let id: String
    public let routeLabel: String
    public let stationName: String
    /// "HH:mm".
    public let time: String
    public let leadMinutes: Int
    public let repeatMode: CommuterAlertRepeat
    public let quietHoursEnabled: Bool
    public var enabled: Bool

    public init(id: String, routeLabel: String, stationName: String, time: String, leadMinutes: Int, repeatMode: CommuterAlertRepeat, quietHoursEnabled: Bool, enabled: Bool = true) {
        self.id = id
        self.routeLabel = routeLabel
        self.stationName = stationName
        self.time = time
        self.leadMinutes = min(max(leadMinutes, 1), 60)
        self.repeatMode = repeatMode
        self.quietHoursEnabled = quietHoursEnabled
        self.enabled = enabled
    }
}

public enum CommuterAlertTrigger: Equatable, Sendable {
    case at(Date)
    case disabled
    case invalidTime
    case quietHours
    case noUpcomingTrigger
}

/// Android's `CommuterAlertPlanner.nextTriggerResult`.
public enum CommuterAlertPlanner {
    public static func nextTrigger(_ alert: CommuterAlert, now: Date) -> CommuterAlertTrigger {
        guard alert.enabled else { return .disabled }
        guard let time = TripTime.parse(alert.time) else { return .invalidTime }
        guard let today = ItalianWallClock.date(of: now) else { return .noUpcomingTrigger }
        for offset in 0...7 {
            guard let date = today.adding(days: offset) else { continue }
            if alert.repeatMode != .once, !alert.repeatMode.isoWeekdays.contains(date.isoWeekday) { continue }
            guard let scheduled = TripTime.instant(date, time) else { continue }
            let trigger = scheduled.addingTimeInterval(-TripTime.minutes(alert.leadMinutes))
            if trigger <= now { continue }
            if alert.quietHoursEnabled, TripQuietHours.isQuiet(at: trigger) { return .quietHours }
            return .at(trigger)
        }
        return .noUpcomingTrigger
    }
}
