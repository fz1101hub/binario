import Foundation

/// A moment on the railway's wall clock (Europe/Rome), whatever zone the phone is in: the day and
/// the minute of the day, seconds dropped (Android's `ZonedDateTime` in `RailTime.defaultZone`).
public struct RailWallClock: Equatable, Sendable {
    public let date: PlannedLocalDate
    /// 0 (00:00) to 1439 (23:59).
    public let minuteOfDay: Int

    public init(date: PlannedLocalDate, minuteOfDay: Int) {
        self.date = date
        self.minuteOfDay = minuteOfDay
    }

    public init?(_ instant: Date) {
        guard let date = ItalianWallClock.date(of: instant), let time = ItalianWallClock.time(of: instant) else { return nil }
        self.init(date: date, minuteOfDay: time.minutesOfDay)
    }

    /// The instant of `hour`:00 of `date` in Italy.
    static func instant(_ date: PlannedLocalDate, hour: Int) -> Date? {
        guard let time = PlannedLocalTime(hour: hour, minute: 0) else { return nil }
        return ItalianWallClock.instant(date: date, time: time)
    }
}

/// Android's `StrikeReminderPlanner`: one run a day at 18:00; a strike of the MIT calendar starting
/// tomorrow is notified once. A run the system delays past the start of the quiet hours (22:00)
/// notifies nothing. The hours are contracts/shared-data/v1/evening-alerts-v1.json; the cases
/// contracts/shared-rules/v1/evening-alerts-v1.json.
public enum StrikeReminderPlanner {
    public static let runHour = SharedData.EveningAlerts.strikeReminderHour
    public static let quietFromHour = SharedData.EveningAlerts.quietFromHour
    public static let notifiedRetentionDays = SharedData.EveningAlerts.strikeNotifiedRetentionDays

    /// Today at 18:00 before it, else tomorrow at 18:00 (railway-local).
    public static func nextRun(after now: RailWallClock) -> (date: PlannedLocalDate, hour: Int)? {
        if now.minuteOfDay < runHour * 60 { return (now.date, runHour) }
        guard let tomorrow = now.date.adding(days: 1) else { return nil }
        return (tomorrow, runHour)
    }

    public static func nextRunAt(_ now: Date) -> Date? {
        guard let clock = RailWallClock(now), let next = nextRun(after: clock) else { return nil }
        return RailWallClock.instant(next.date, hour: next.hour)
    }

    public static func mayNotify(_ now: RailWallClock) -> Bool {
        now.minuteOfDay >= runHour * 60 && now.minuteOfDay < quietFromHour * 60
    }

    /// Rail strikes starting tomorrow that were not notified yet (`notified`: id → epoch day).
    public static func toNotify(_ strikes: [StrikeNotice], today: PlannedLocalDate, notified: [String: Int]) -> [StrikeNotice] {
        let tomorrow = today.adding(days: 1)
        return strikes.filter { $0.startDate != nil && $0.startDate == tomorrow && notified[$0.id] == nil }
    }

    /// `notified` with `strikes` added under today's day, without entries older than the retention.
    public static func remember(_ notified: [String: Int], strikes: [StrikeNotice], today: PlannedLocalDate) -> [String: Int] {
        let day = today.daysSinceEpoch
        var merged = notified
        for strike in strikes { merged[strike.id] = day }
        return merged.filter { day - $0.value < notifiedRetentionDays }
    }
}

/// One notice of the evening works alert: works valid tomorrow and the saved route they concern,
/// the first in the routes' order.
public struct RouteWorksAlertItem: Equatable, Sendable {
    public let works: RouteWorksNotice
    public let reason: RouteWorksReason
    public let dates: NoticeDates
}

/// The evening alert of `day` (tomorrow): every notice due, in the routes' order, and every route
/// they name in that order (the title names the first one).
public struct RouteWorksAlert: Equatable, Sendable {
    public let day: PlannedLocalDate
    public let due: [RouteWorksAlertItem]
    public let routeNames: [String]

    /// The notices shown, at most the table's number.
    public var items: [RouteWorksAlertItem] { Array(due.prefix(RouteWorksAlertPlanner.maximumItems)) }
    /// Notices due tomorrow beyond the ones shown: the notification counts them.
    public var hidden: Int { due.count - items.count }
}

/// Android's `RouteWorksAlertPlanner`, «Lavori di domani sulle tue tratte»: one run a day at 19:00;
/// a works or interruption notice that names a saved route and whose dates include tomorrow is
/// notified once, however many days it covers. At most one notification an evening; a notice
/// without readable dates never triggers it, and nothing is shown before 19:00 or in the quiet hours
/// (22–7).
public enum RouteWorksAlertPlanner {
    public static let runHour = SharedData.EveningAlerts.routeWorksAlertHour
    public static let quietFromHour = SharedData.EveningAlerts.quietFromHour
    public static let quietUntilHour = SharedData.EveningAlerts.quietUntilHour
    public static let maximumItems = SharedData.EveningAlerts.routeWorksMaximumItems
    public static let maximumKeepDays = SharedData.EveningAlerts.routeWorksMaximumKeepDays
    public static let maximumNotified = SharedData.EveningAlerts.routeWorksMaximumNotified

    public static func quiet(_ now: RailWallClock) -> Bool {
        now.minuteOfDay >= quietFromHour * 60 || now.minuteOfDay < quietUntilHour * 60
    }

    /// Only in the evening window, 19:00 – 22:00.
    public static func mayNotify(_ now: RailWallClock) -> Bool {
        now.minuteOfDay >= runHour * 60 && !quiet(now)
    }

    /// When the next run is.
    public enum NextRun: Equatable, Sendable {
        /// At once: the user has just turned the alert on inside this evening's window.
        case now
        case at(PlannedLocalDate, hour: Int)
    }

    /// Today at 19:00 before it, else tomorrow; with `immediate` inside this evening's window, now,
    /// so tonight's alert is not lost.
    public static func nextRun(after now: RailWallClock, immediate: Bool) -> NextRun? {
        if immediate && mayNotify(now) { return .now }
        if now.minuteOfDay < runHour * 60 { return .at(now.date, hour: runHour) }
        guard let tomorrow = now.date.adding(days: 1) else { return nil }
        return .at(tomorrow, hour: runHour)
    }

    public static func nextRunAt(_ now: Date, immediate: Bool) -> Date? {
        guard let clock = RailWallClock(now), let next = nextRun(after: clock, immediate: immediate) else { return nil }
        switch next {
        case .now: return now
        case let .at(date, hour): return RailWallClock.instant(date, hour: hour)
        }
    }

    public static func validOn(_ dates: NoticeDates?, day: PlannedLocalDate) -> Bool {
        guard let dates else { return false }
        return !(day < dates.first) && !(dates.last < day)
    }

    /// Whether this evening already had its alert: one notification an evening at most.
    public static func alertedOn(_ lastAlertDay: PlannedLocalDate?, today: PlannedLocalDate) -> Bool {
        lastAlertDay == today
    }

    /// Whether `noticeID` was notified already: `notified` keeps it (id → last day kept, epoch day) until that day.
    public static func notifiedBefore(_ notified: [String: Int], noticeID: String, today: PlannedLocalDate) -> Bool {
        guard let until = notified[noticeID] else { return false }
        return until >= today.daysSinceEpoch
    }

    /// The last day a notice notified today is remembered: the day after its last date, at most the table's days ahead.
    public static func keepUntil(_ dates: NoticeDates, today: PlannedLocalDate) -> PlannedLocalDate {
        let after = dates.last.daysSinceEpoch + 1
        let limit = today.daysSinceEpoch + maximumKeepDays
        return PlannedLocalDate(daysSinceEpoch: min(after, limit)) ?? dates.last
    }

    /// The alert of tomorrow from the notices matched to `routeIDs` (in the routes' order), or nil
    /// when none is due: works and interruptions only (never a strike), with dates that include
    /// tomorrow, never notified before. Each notice goes under the first route it names; within a
    /// route the notices keep the matcher's order.
    public static func select(
        _ works: [RouteWorksNotice], routeIDs: [String], today: PlannedLocalDate, notified: [String: Int]
    ) -> RouteWorksAlert? {
        guard let tomorrow = today.adding(days: 1) else { return nil }
        var rank: [String: Int] = [:]
        for (index, id) in routeIDs.enumerated() where rank[id] == nil { rank[id] = index }
        var seen: Set<String> = []
        let found: [RouteWorksAlertItem] = works.compactMap { matched -> RouteWorksAlertItem? in
            guard !matched.notice.mentionsStrike, !notifiedBefore(notified, noticeID: matched.notice.id, today: today),
                  let dates = matched.dates, validOn(dates, day: tomorrow) else { return nil }
            let named = matched.reasons.filter { rank[$0.routeID] != nil }
            guard let reason = named.min(by: { (rank[$0.routeID] ?? 0) < (rank[$1.routeID] ?? 0) }) else { return nil }
            return RouteWorksAlertItem(works: matched, reason: reason, dates: dates)
        }.filter { seen.insert($0.works.notice.id).inserted }
        // A stable sort by the route's rank, as Kotlin's `sortedBy`.
        let due = found.enumerated().sorted { first, second in
            let a = rank[first.element.reason.routeID] ?? 0, b = rank[second.element.reason.routeID] ?? 0
            return a == b ? first.offset < second.offset : a < b
        }.map(\.element)
        guard !due.isEmpty else { return nil }
        let reasons = due.flatMap { $0.works.reasons }.filter { rank[$0.routeID] != nil }.enumerated().sorted { first, second in
            let a = rank[first.element.routeID] ?? 0, b = rank[second.element.routeID] ?? 0
            return a == b ? first.offset < second.offset : a < b
        }.map(\.element)
        var named: Set<String> = []
        let routeNames = reasons.filter { named.insert($0.routeID).inserted }.map(\.routeName)
        return RouteWorksAlert(day: tomorrow, due: due, routeNames: routeNames)
    }

    /// `notified` with every notice of `alert` (also those beyond the ones shown) until its
    /// `keepUntil`, without the entries whose day is past; at most the table's number, the ones kept
    /// longest first.
    public static func remember(_ notified: [String: Int], alert: RouteWorksAlert, today: PlannedLocalDate) -> [String: Int] {
        var added: [(String, Int)] = []
        var addedIDs: Set<String> = []
        for item in alert.due where addedIDs.insert(item.works.notice.id).inserted {
            added.append((item.works.notice.id, keepUntil(item.dates, today: today).daysSinceEpoch))
        }
        let day = today.daysSinceEpoch
        let kept = notified.filter { !addedIDs.contains($0.key) && $0.value >= day }
            .sorted { $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value }
            .prefix(max(0, maximumNotified - added.count))
        var result: [String: Int] = [:]
        for (id, until) in added.prefix(maximumNotified) { result[id] = until }
        for (id, until) in kept { result[id] = until }
        return result
    }
}
