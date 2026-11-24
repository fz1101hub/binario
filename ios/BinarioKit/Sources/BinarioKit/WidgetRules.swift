import Foundation

// The rules of the three home-screen widgets (Tratta, Tabellone stazione, I miei treni), as Android's
// app/src/main/java/it/binario/app/widget/ applies them: automatic refresh windows, the automatic
// return direction, the train type groups a widget can hide, which runs each widget lists, when the
// live values count, which favourite trains a refresh asks for and what one scheduler run does. The
// numbers are contracts/shared-data/v1/widgets-v1.json (`SharedData.Widgets`); the cases both apps run
// are contracts/shared-rules/v1/widgets-v1.json. Times are railway-local (Europe/Rome), whatever zone
// the phone is in. The iPhone's WidgetKit extension (ios/BinarioWidgets) reads these types.

/// «Aggiornamento automatico»: off (only a tap or the app), in the chosen windows, or always.
public enum WidgetAutoRefreshMode: String, CaseIterable, Codable, Sendable {
    case off = "OFF"
    case windows = "WINDOWS"
    case always = "ALWAYS"
}

/// A daily refresh window [start, end), in minutes of the day. A window whose end is not after its
/// start crosses midnight (22:00–01:00) and belongs to the day it starts on.
public struct WidgetRefreshWindow: Equatable, Hashable, Codable, Sendable {
    public let startMinute: Int
    public let endMinute: Int

    public init(startMinute: Int, endMinute: Int) {
        self.startMinute = min(max(startMinute, 0), 1_439)
        self.endMinute = min(max(endMinute, 0), 1_439)
    }

    /// "07:00" to "09:30"; nil when either is not a clock time.
    public init?(start: String, end: String) {
        guard let from = PlannedLocalTime(hhmm: start), let to = PlannedLocalTime(hhmm: end) else { return nil }
        self.init(startMinute: from.minutesOfDay, endMinute: to.minutesOfDay)
    }

    public var crossesMidnight: Bool { endMinute <= startMinute }

    /// 07:00–09:30, the first window of a new widget.
    public static let defaultMorning = WidgetRefreshWindow(shared: SharedData.Widgets.defaultMorningWindow)
    /// 17:00–19:30, the second window when one is added.
    public static let defaultEvening = WidgetRefreshWindow(shared: SharedData.Widgets.defaultEveningWindow)

    init(shared: SharedData.Widgets.RefreshWindow) {
        self = WidgetRefreshWindow(start: shared.start, end: shared.end) ?? WidgetRefreshWindow(startMinute: 0, endMinute: 0)
    }
}

/// The automatic refresh of one widget (Android's `AutoRefreshSettings`).
public struct WidgetAutoRefresh: Equatable, Hashable, Codable, Sendable {
    public let mode: WidgetAutoRefreshMode
    public let intervalMinutes: Int
    /// 1 Monday … 7 Sunday.
    public let days: Set<Int>
    /// One or two windows; more are ignored.
    public let windows: [WidgetRefreshWindow]

    public init(mode: WidgetAutoRefreshMode, intervalMinutes: Int, days: Set<Int>, windows: [WidgetRefreshWindow]) {
        self.mode = mode
        self.intervalMinutes = max(intervalMinutes, 1)
        self.days = days.filter { (1...7).contains($0) }
        self.windows = windows
    }

    /// A new widget: off, with the default interval, days and morning window ready for «fasce orarie».
    public static let off = WidgetAutoRefresh(
        mode: .off, intervalMinutes: SharedData.Widgets.defaultIntervalMinutes,
        days: SharedData.Widgets.weekdays, windows: [.defaultMorning]
    )

    /// «Sempre, ogni 15 minuti».
    public static let always = WidgetAutoRefresh(
        mode: .always, intervalMinutes: SharedData.Widgets.alwaysIntervalMinutes, days: Set(1...7), windows: []
    )

    public var effectiveWindows: [WidgetRefreshWindow] { Array(windows.prefix(SharedData.Widgets.maximumWindows)) }

    /// True when an automatic refresh may run at `instant`: always for `always`, never for `off`.
    public func isActive(at instant: Date) -> Bool {
        switch mode {
        case .off: return false
        case .always: return true
        case .windows:
            guard let moment = RailMoment(instant) else { return false }
            return effectiveWindows.contains { contains($0, date: moment.date, second: moment.secondOfDay) }
        }
    }

    /// The earliest instant at or after `instant` inside a window, or nil when none starts within the
    /// lookahead (no day chosen, or off). A window start in the hour daylight saving skips moves on
    /// with the clocks, as Java's `ZonedDateTime.of`.
    public func nextActive(from instant: Date) -> Date? {
        switch mode {
        case .off: return nil
        case .always: return instant
        case .windows: break
        }
        if isActive(at: instant) { return instant }
        guard let today = ItalianWallClock.date(of: instant) else { return nil }
        var best: Date?
        for offset in 0...SharedData.Widgets.windowLookaheadDays {
            guard let day = today.adding(days: offset), days.contains(day.isoWeekday) else { continue }
            for window in effectiveWindows {
                guard let start = WidgetClock.instant(day, minute: window.startMinute), start > instant else { continue }
                if let current = best, current <= start { continue }
                best = start
            }
        }
        return best
    }

    /// When the next automatic refresh is due: the interval after `lastRefresh` (any refresh, a tap
    /// included), moved to the next window when that instant falls outside every window.
    public func nextDue(lastRefresh: Date?, now: Date) -> Date? {
        let ready = lastRefresh.map { $0.addingTimeInterval(TimeInterval(intervalMinutes * 60)) } ?? now
        return nextActive(from: max(now, ready))
    }

    public func isDue(lastRefresh: Date?, now: Date) -> Bool {
        guard let due = nextDue(lastRefresh: lastRefresh, now: now) else { return false }
        return due <= now
    }

    private func contains(_ window: WidgetRefreshWindow, date: PlannedLocalDate, second: Int) -> Bool {
        let start = window.startMinute * 60
        let end = window.endMinute * 60
        guard window.crossesMidnight else {
            return days.contains(date.isoWeekday) && second >= start && second < end
        }
        let previous = date.adding(days: -1).map { days.contains($0.isoWeekday) } ?? false
        return (days.contains(date.isoWeekday) && second >= start) || (previous && second < end)
    }
}

/// The direction a route widget shows.
public enum WidgetDirection: String, CaseIterable, Codable, Sendable {
    case outbound = "OUTBOUND"
    case `return` = "RETURN"

    public var other: WidgetDirection { self == .outbound ? .return : .outbound }
}

/// «Mostra il ritorno dalle HH:mm» (Android's `WidgetDirectionSchedule`): the outbound direction
/// until that time, the return from then until midnight. The direction button holds until the next
/// change (that time or midnight).
public enum WidgetDirectionSchedule {
    /// 14:00, the time a new «ritorno dalle» starts from.
    public static let defaultReturnFrom: PlannedLocalTime = PlannedLocalTime(hhmm: SharedData.Widgets.defaultReturnFrom)
        ?? PlannedLocalTime(hour: 14, minute: 0)!

    public static func automatic(returnFrom: PlannedLocalTime, now: Date) -> WidgetDirection {
        guard let moment = RailMoment(now) else { return .outbound }
        return moment.secondOfDay < returnFrom.minutesOfDay * 60 ? .outbound : .return
    }

    /// The next instant the automatic direction changes: today at `returnFrom`, else midnight.
    public static func nextBoundary(returnFrom: PlannedLocalTime, now: Date) -> Date? {
        guard let today = ItalianWallClock.date(of: now) else { return nil }
        if let swap = WidgetClock.instant(today, minute: returnFrom.minutesOfDay), now < swap { return swap }
        guard let tomorrow = today.adding(days: 1) else { return nil }
        return WidgetClock.instant(tomorrow, minute: 0)
    }

    /// The direction to show: a manual choice while `manualUntil` is ahead, else the automatic one;
    /// without «ritorno dalle» the stored direction.
    public static func effective(
        returnFrom: PlannedLocalTime?, stored: WidgetDirection, manual: WidgetDirection?, manualUntil: Date?, now: Date
    ) -> WidgetDirection {
        guard let returnFrom else { return stored }
        if let manual, let manualUntil, now < manualUntil { return manual }
        return automatic(returnFrom: returnFrom, now: now)
    }
}

/// The train type groups a widget can hide (Android's `TrainTypeGroup`).
public enum WidgetTrainTypeGroup: String, CaseIterable, Codable, Sendable {
    case highSpeed = "HIGH_SPEED"
    case intercityNight = "INTERCITY_NIGHT"
    case regionalSuburban = "REGIONAL_SUBURBAN"
    case italo = "ITALO"
    case replacementBus = "REPLACEMENT_BUS"
    case international = "INTERNATIONAL"
}

/// The group of a train from what the sources publish (Android's `WidgetTrainTypes`): nil when the
/// data do not tell, and such a train is never hidden.
public enum WidgetTrainTypes {
    /// Android's board rule: a replacement bus or a bus code, then the operator (Italo, a foreign
    /// operator), then the line code (international, high speed, Intercity, regional).
    public static func group(operatorID: String?, lineLabel: String?, replacementBus: Bool) -> WidgetTrainTypeGroup? {
        let operatorKey = (operatorID ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let code = (lineLabel ?? "").uppercased().replacingOccurrences(of: " ", with: "")
        let prefix = String(code.prefix(while: { $0.isLetter }))
        if replacementBus || SharedData.Widgets.busCodes.contains(prefix) { return .replacementBus }
        if operatorKey == SharedData.Widgets.italoOperatorId { return .italo }
        if SharedData.Widgets.internationalOperatorIds.contains(operatorKey) { return .international }
        if SharedData.Widgets.internationalCodes.contains(prefix) { return .international }
        if SharedData.Widgets.highSpeedCodes.contains(prefix) { return .highSpeed }
        if SharedData.Widgets.intercityCodes.contains(prefix) { return .intercityNight }
        if !code.isEmpty, isRegionalCode(code) { return .regionalSuburban }
        return nil
    }

    /// The group of a timetable or live row of the iPhone: its operator's identifier, its category
    /// as the line code, a replacement bus by its vehicle.
    public static func group(of service: TrainService) -> WidgetTrainTypeGroup? {
        group(
            operatorID: operatorID(forName: service.operatorName),
            lineLabel: service.category,
            replacementBus: service.vehicle == .replacementBus
        )
    }

    /// Android's operator identifier of an operator name as the iPhone shows it (`TimetableNaming`'s
    /// brands, the live sources' names); nil for any other operator.
    public static func operatorID(forName name: String) -> String? {
        let folded = name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX"))
        var key = ""
        for scalar in folded.unicodeScalars where CharacterSet.alphanumerics.contains(scalar) {
            key.unicodeScalars.append(scalar)
        }
        switch key {
        case "italo": return SharedData.Widgets.italoOperatorId
        case "sbb", "sbbcffffs": return "sbb"
        case "bls": return "bls"
        case "rhb", "ferroviaretica": return "rhb"
        case "sncf", "sncfvoyageurs": return "sncf"
        case "obb", "oebb": return "oebb"
        case "db", "deutschebahn": return "db"
        case "westbahn": return "westbahn"
        case "europeansleeper": return "european-sleeper"
        case "trenitalia": return "trenitalia"
        case "trenord": return "trenord"
        default: return nil
        }
    }

    /// A row is hidden only when its group is known and excluded.
    public static func isHidden(_ group: WidgetTrainTypeGroup?, excluded: Set<WidgetTrainTypeGroup>) -> Bool {
        guard let group else { return false }
        return excluded.contains(group)
    }

    /// One of the regional codes followed by at most `regionalMaximumDigits` digits («R», «RE12», «S11»).
    static func isRegionalCode(_ code: String) -> Bool {
        var letters = ""
        var rest: [Unicode.Scalar] = []
        for scalar in code.unicodeScalars {
            if rest.isEmpty, ("A"..."Z").contains(scalar) {
                letters.unicodeScalars.append(scalar)
            } else {
                rest.append(scalar)
            }
        }
        return SharedData.Widgets.regionalCodes.contains(letters)
            && rest.count <= SharedData.Widgets.regionalMaximumDigits
            && rest.allSatisfy { ("0"..."9").contains($0) }
    }
}

/// «Corse mostrate» of the route widget (Android's `RouteTimeRange`).
public enum WidgetTimeRange: String, CaseIterable, Codable, Sendable {
    case next = "NEXT"
    case next3Hours = "NEXT_3H"
    case next6Hours = "NEXT_6H"
    case wholeDay = "WHOLE_DAY"

    private var shared: SharedData.Widgets.TimeRange? {
        SharedData.Widgets.timeRanges.first { $0.id == rawValue }
    }

    /// Hours listed from now; nil for the next trains and the whole day.
    public var hours: Int? { shared?.hours }

    /// Timetable requests a refresh adds after the first to fill the range.
    public var extraPages: Int { shared?.extraPages ?? 0 }

    /// The last departure a refresh pages to (Android's `RouteWidgetPaging.horizon`): none for the
    /// next trains, the hours ahead, or 23:59 of the day.
    public func horizon(now: Date) -> Date? {
        switch self {
        case .next: return nil
        case .next3Hours, .next6Hours: return now.addingTimeInterval(TimeInterval((hours ?? 0) * 3_600))
        case .wholeDay:
            guard let today = ItalianWallClock.date(of: now) else { return nil }
            return WidgetClock.instant(today, minute: 23 * 60 + 59)
        }
    }

    /// The departure the next timetable page starts from (Android's `RouteWidgetPaging.nextQuery`):
    /// a minute after the latest departure collected, on the same day and before the horizon; nil to stop.
    public func nextPage(now: Date, latestDeparture: Date?, pagesDone: Int) -> Date? {
        guard let horizon = horizon(now: now), pagesDone < extraPages, let latestDeparture else { return nil }
        guard latestDeparture < horizon, let today = ItalianWallClock.date(of: now),
              ItalianWallClock.date(of: latestDeparture) == today else { return nil }
        let next = StationBoardQuery.minuteStart(of: latestDeparture.addingTimeInterval(60))
        return ItalianWallClock.date(of: next) == today ? next : nil
    }
}

/// How fresh the data of a widget are (Android's `WidgetFreshness`): live values count only while fresh or aging.
public enum WidgetFreshness: String, CaseIterable, Codable, Sendable {
    case fresh = "FRESH"
    case aging = "AGING"
    case stale = "STALE"
    case unavailable = "UNAVAILABLE"

    public static func of(retrievedAt: Date?, now: Date) -> WidgetFreshness {
        guard let retrievedAt else { return .unavailable }
        let age = max(0, now.timeIntervalSince(retrievedAt))
        if age < SharedData.Widgets.freshSeconds { return .fresh }
        if age < SharedData.Widgets.agingSeconds { return .aging }
        return .stale
    }

    /// Live values (expected times, delays, cancellations, platform changes) may be shown.
    public var showsLive: Bool { self == .fresh || self == .aging }
}

/// What the route widget needs to know of one listed run.
public struct WidgetRouteRun: Equatable, Sendable {
    /// Expected when the source verified it, else scheduled; nil when the time cannot be read.
    public let departure: Date?
    /// The arrival at the searched destination; nil when unknown.
    public let arrival: Date?
    public let cancelled: Bool
    public let group: WidgetTrainTypeGroup?

    public init(departure: Date?, arrival: Date?, cancelled: Bool, group: WidgetTrainTypeGroup?) {
        self.departure = departure
        self.arrival = arrival
        self.cancelled = cancelled
        self.group = group
    }
}

/// A row a widget lists, and whether it is a run already travelling («In viaggio»).
public struct WidgetListed<Row> {
    public let row: Row
    public let inTransit: Bool

    public init(row: Row, inTransit: Bool) {
        self.row = row
        self.inTransit = inTransit
    }
}

extension WidgetListed: Sendable where Row: Sendable {}
extension WidgetListed: Equatable where Row: Equatable {}

/// The runs the route widget lists (Android's `RouteWidgetPresenter.visible`): without hidden types,
/// within the time range, and a departed train only until a minute after its departure or, with «fino
/// all'arrivo», until its arrival, marked «In viaggio» (never a cancelled one).
public enum WidgetRouteRules {
    public static let departedGrace: TimeInterval = SharedData.Widgets.departedGraceSeconds

    public static func visible<Row>(
        _ rows: [Row], run: (Row) -> WidgetRouteRun, now: Date,
        excluded: Set<WidgetTrainTypeGroup>, untilArrival: Bool, timeRange: WidgetTimeRange
    ) -> [WidgetListed<Row>] {
        let horizon = timeRange.hours.map { now.addingTimeInterval(TimeInterval($0 * 3_600)) }
        var listed: [WidgetListed<Row>] = []
        for row in rows {
            let value = run(row)
            if WidgetTrainTypes.isHidden(value.group, excluded: excluded) { continue }
            guard let departure = value.departure else {
                listed.append(WidgetListed(row: row, inTransit: false))
                continue
            }
            if let horizon, departure > horizon { continue }
            if now < departure.addingTimeInterval(departedGrace) {
                listed.append(WidgetListed(row: row, inTransit: false))
            } else if untilArrival, !value.cancelled, let arrival = value.arrival, now < arrival {
                listed.append(WidgetListed(row: row, inTransit: true))
            }
        }
        return listed
    }
}

/// What the station widget needs to know of one departure.
public struct WidgetBoardDeparture: Equatable, Sendable {
    public let scheduled: Date
    public let expected: Date?
    /// The source published an observation (an expected time, a state), not the timetable alone.
    public let observed: Bool
    public let group: WidgetTrainTypeGroup?

    public init(scheduled: Date, expected: Date?, observed: Bool, group: WidgetTrainTypeGroup?) {
        self.scheduled = scheduled
        self.expected = expected
        self.observed = observed
        self.group = group
    }
}

/// The departures the station widget lists (Android's `StationWidgetPresenter.upcoming`): without
/// hidden types, still to leave by their live time (only while the data are fresh or aging) or else
/// their scheduled time, earliest first, at most `maximumRows`.
public enum WidgetStationRules {
    public static let maximumRows: Int = SharedData.Widgets.stationMaximumRows
    public static let storedDepartures: Int = SharedData.Widgets.stationStoredDepartures

    public static func isLive(_ departure: WidgetBoardDeparture, freshness: WidgetFreshness) -> Bool {
        freshness.showsLive && departure.observed
    }

    public static func upcoming<Row>(
        _ rows: [Row], departure: (Row) -> WidgetBoardDeparture, freshness: WidgetFreshness, now: Date,
        limit: Int, excluded: Set<WidgetTrainTypeGroup>
    ) -> [Row] {
        let threshold = now.addingTimeInterval(-WidgetRouteRules.departedGrace)
        var kept: [(row: Row, effective: Date, scheduled: Date, index: Int)] = []
        for (index, row) in rows.enumerated() {
            let value = departure(row)
            if WidgetTrainTypes.isHidden(value.group, excluded: excluded) { continue }
            let live: Date? = isLive(value, freshness: freshness) ? value.expected : nil
            let effective = live ?? value.scheduled
            guard effective >= threshold else { continue }
            kept.append((row: row, effective: effective, scheduled: value.scheduled, index: index))
        }
        kept.sort { first, second in
            if first.effective != second.effective { return first.effective < second.effective }
            if first.scheduled != second.scheduled { return first.scheduled < second.scheduled }
            return first.index < second.index
        }
        let count = min(max(limit, 0), maximumRows)
        return kept.prefix(count).map { $0.row }
    }
}

/// What «I miei treni» knows of a favourite's last run.
public struct WidgetTrainRun: Equatable, Sendable {
    public let departure: Date?
    public let arrival: Date?
    /// Observed by the source; nil when it published no observation.
    public let departed: Bool?
    public let arrived: Bool?
    public let cancelled: Bool

    public init(departure: Date?, arrival: Date?, departed: Bool?, arrived: Bool?, cancelled: Bool) {
        self.departure = departure
        self.arrival = arrival
        self.departed = departed
        self.arrived = arrived
        self.cancelled = cancelled
    }
}

/// «I miei treni» (Android's `TrainsWidgetPresenter` and `TrainsWidgetLookupPlanner`).
public enum WidgetTrainsRules {
    public static let arrivalGrace = TimeInterval(SharedData.Widgets.trainsArrivalGraceMinutes * 60)

    /// The run of an earlier service day that is over (arrived, or past its arrival): it says nothing
    /// of today's train.
    public static func isEarlierRunOver(_ run: WidgetTrainRun, now: Date) -> Bool {
        guard let departure = run.departure,
              let today = ItalianWallClock.date(of: now),
              let serviceDay = ItalianWallClock.date(of: departure),
              serviceDay < today else { return false }
        let end = run.arrival ?? departure
        return run.arrived == true || now > end.addingTimeInterval(arrivalGrace)
    }

    /// Running unless cancelled, clearly not left yet or arrived (observed, or ten minutes past its
    /// arrival). Without times it counts as running: an unknown state never hides a train.
    public static func isRunning(_ run: WidgetTrainRun?, now: Date) -> Bool {
        guard let run else { return true }
        if isEarlierRunOver(run, now: now) { return false }
        if run.cancelled { return false }
        var notLeftYet = false
        if run.departed != true, let departure = run.departure, now < departure { notLeftYet = true }
        var arrived = run.arrived == true
        if let arrival = run.arrival, now > arrival.addingTimeInterval(arrivalGrace) { arrived = true }
        return !notLeftYet && !arrived
    }

    /// The service day a refresh asks for a favourite: the day whose run is near now (from an hour
    /// before its departure to half an hour after its arrival, or three hours after the departure when
    /// no arrival is known); a refresh the user asked for asks today's otherwise, an automatic one none.
    /// A train without a known departure is asked for today: only a lookup can teach it.
    public static func lookupDate(
        savedDeparture: String, lastDeparture: String?, lastRun: WidgetTrainRun?, now: Date, automatic: Bool
    ) -> PlannedLocalDate? {
        guard let today = ItalianWallClock.date(of: now) else { return nil }
        var departure: PlannedLocalTime?
        for value in [savedDeparture, lastDeparture ?? ""] {
            let trimmed = value.trimmingCharacters(in: .whitespaces)
            if let time = PlannedLocalTime(hhmm: String(trimmed.prefix(5))) {
                departure = time
                break
            }
        }
        guard let departure else { return today }
        var duration = TimeInterval(SharedData.Widgets.lookupDefaultDurationMinutes * 60)
        if let start = lastRun?.departure, let end = lastRun?.arrival {
            let measured = end.timeIntervalSince(start)
            if measured > 0, measured <= TimeInterval(SharedData.Widgets.lookupMaximumDurationMinutes * 60) { duration = measured }
        }
        let before = TimeInterval(SharedData.Widgets.lookupBeforeDepartureMinutes * 60)
        let after = TimeInterval(SharedData.Widgets.lookupAfterArrivalMinutes * 60)
        for offset in [-1, 0, 1] {
            guard let day = today.adding(days: offset),
                  let start = WidgetClock.instant(day, minute: departure.minutesOfDay) else { continue }
            if now >= start.addingTimeInterval(-before), now <= start.addingTimeInterval(duration + after) { return day }
        }
        return automatic ? nil : today
    }
}

/// What one scheduler run does for one widget, and when the next is needed.
public struct WidgetRefreshPlan: Equatable, Sendable {
    /// Ask the sources now.
    public let fetch: Bool
    /// The direction the route widget changes to now (the automatic swap, or the end of a manual choice).
    public let applyDirection: WidgetDirection?
    /// The next instant something is due; nil when nothing automatic is left.
    public let nextRun: Date?

    public init(fetch: Bool, applyDirection: WidgetDirection?, nextRun: Date?) {
        self.fetch = fetch
        self.applyDirection = applyDirection
        self.nextRun = nextRun
    }
}

/// Android's `WidgetAutoRefreshPlanner` and `WidgetRefreshRetryPolicy` for one widget. On the iPhone
/// the next run is the date of the timeline's reload policy (`.after`), and no run means `.never`:
/// outside the windows, and with automatic refresh off, nothing wakes the widget.
public enum WidgetRefreshPlanner {
    public static func plan(
        settings: WidgetAutoRefresh, lastRefresh: Date?, pendingDirection: WidgetDirection?,
        nextDirectionBoundary: Date?, now: Date
    ) -> WidgetRefreshPlan {
        let due = settings.isDue(lastRefresh: lastRefresh, now: now)
        // The swap to the return at the chosen time loads the return trains once; the swap back at
        // midnight only changes the label, unless a refresh window is open anyway.
        var fetchForDirection = false
        if let pendingDirection {
            fetchForDirection = pendingDirection == .return || settings.isActive(at: now)
        }
        var nextRun: Date?
        if pendingDirection != nil {
            nextRun = now
        } else {
            nextRun = [settings.nextDue(lastRefresh: lastRefresh, now: now), nextDirectionBoundary].compactMap { $0 }.min()
        }
        return WidgetRefreshPlan(fetch: due || fetchForDirection, applyDirection: pendingDirection, nextRun: nextRun)
    }

    /// The wait before retrying a refresh the user asked for that failed for a temporary reason, after
    /// run `attempt` (from 1): fifteen minutes doubling, at most three runs in all, and only when the
    /// retry falls inside the widget's automatic refresh windows. Nil: no retry.
    public static func retryDelay(settings: WidgetAutoRefresh, attempt: Int, now: Date) -> TimeInterval? {
        guard attempt >= 1, attempt < SharedData.Widgets.retryMaximumAttempts else { return nil }
        let first = TimeInterval(SharedData.Widgets.retryFirstDelayMinutes * 60)
        let delay = first * Double(1 << (attempt - 1))
        return settings.isActive(at: now.addingTimeInterval(delay)) ? delay : nil
    }
}

/// Instants of the railway clock for the widgets.
public enum WidgetClock {
    /// `minute` of `day` in Italy; a time the clocks skip moves on by the hour skipped, as Java's
    /// `ZonedDateTime.of` does.
    public static func instant(_ day: PlannedLocalDate, minute: Int) -> Date? {
        guard let time = PlannedLocalTime(hour: minute / 60, minute: minute % 60) else { return nil }
        if let exact = ItalianWallClock.instant(date: day, time: time) { return exact }
        let later = minute + 60
        guard later < 1_440, let moved = PlannedLocalTime(hour: later / 60, minute: later % 60) else { return nil }
        return ItalianWallClock.instant(date: day, time: moved)
    }

    /// "HH:mm" in Italy.
    public static func hhmm(_ instant: Date) -> String {
        ItalianWallClock.time(of: instant)?.hhmm ?? ""
    }

    /// The instant of a GTFS time of a timetable service day (yyyymmdd): noon minus twelve hours plus
    /// `seconds`, in Italy, as the bundled index counts it.
    public static func gtfsInstant(serviceDate: Int, seconds: Int) -> Date? {
        guard let day = PlannedLocalDate(year: serviceDate / 10_000, month: (serviceDate / 100) % 100, day: serviceDate % 100),
              let noon = PlannedLocalTime(hour: 12, minute: 0),
              let middle = ItalianWallClock.instant(date: day, time: noon) else { return nil }
        return middle.addingTimeInterval(TimeInterval(seconds - 12 * 3_600))
    }
}
