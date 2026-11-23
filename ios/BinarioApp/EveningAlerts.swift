import Foundation
import BackgroundTasks
import UserNotifications
import BinarioKit

/// The two evening alerts of Android on the iPhone, «Scioperi del giorno dopo» (18:00) and «Lavori
/// di domani sulle tue tratte» (19:00), with one background app refresh task: the only background
/// mode the app declares (`fetch`) and its only permitted identifier. iOS decides when the task
/// runs, never before the hour asked for; a run outside an alert's window (after 22:00) notifies
/// nothing for it, as on Android. A run reads only the public MIT strike calendar and RFI's
/// infomobility feed, with the manners of every source, and schedules local notifications: no
/// remote notification and no push entitlement. What was notified is kept on this iPhone only.
enum EveningAlerts {
    static let taskIdentifier = "it.binario.ios.evening-alerts"
    static let strikeNotificationID = "it.binario.ios.strike-reminder"
    static let worksNotificationID = "it.binario.ios.route-works-alert"

    /// Plans the next run: the earliest of the alerts switched on, or none. With
    /// `immediateWorksAlert` (the works alert just turned on in the evening window) tonight's alert
    /// is asked for at once, as on Android.
    static func schedule(now: Date = Date(), immediateWorksAlert: Bool = false) {
        let preferences = ExtrasPreferenceStore()
        var dates: [Date] = []
        if preferences.strikeReminderEnabled, let next = StrikeReminderPlanner.nextRunAt(now) { dates.append(next) }
        if preferences.routeWorksAlertEnabled, let next = RouteWorksAlertPlanner.nextRunAt(now, immediate: immediateWorksAlert) {
            dates.append(next)
        }
        guard let earliest = dates.min() else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskIdentifier)
            return
        }
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = earliest
        // A refused request (Background App Refresh off, too many requests) leaves the alerts silent.
        try? BGTaskScheduler.shared.submit(request)
    }

    /// One background run: each alert whose window it is, then the next run.
    static func run(now: Date = Date()) async {
        defer { schedule() }
        guard await notificationsAllowed(), let clock = RailWallClock(now) else { return }
        let preferences = ExtrasPreferenceStore()
        guard let service = RailNoticeService.standard(rules: NoticeRules.standard) else { return }
        let store = EveningAlertsStore()
        if preferences.strikeReminderEnabled, StrikeReminderPlanner.mayNotify(clock) {
            await remindStrikes(service: service, today: clock.date, store: store)
        }
        if preferences.routeWorksAlertEnabled, RouteWorksAlertPlanner.mayNotify(clock), !Task.isCancelled {
            await alertWorks(service: service, today: clock.date, store: store)
        }
    }

    private static func notificationsAllowed() async -> Bool {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: return true
        default: return false
        }
    }

    // MARK: Strikes

    private static func remindStrikes(service: RailNoticeService, today: PlannedLocalDate, store: EveningAlertsStore) async {
        let strikes = await service.strikeCalendar()
        let notified = store.strikesNotified
        let tomorrow = StrikeReminderPlanner.toNotify(strikes, today: today, notified: notified)
        guard !tomorrow.isEmpty else { return }
        let title = String.localizedStringWithFormat(NSLocalizedString("notices.alerts.strike_title", comment: ""), tomorrow.count)
        let body = tomorrow.map(\.title).joined(separator: "\n\n")
        if await post(id: strikeNotificationID, title: title, body: body) {
            store.strikesNotified = StrikeReminderPlanner.remember(notified, strikes: tomorrow, today: today)
        }
    }

    // MARK: Works on the saved routes

    private static func alertWorks(service: RailNoticeService, today: PlannedLocalDate, store: EveningAlertsStore) async {
        guard !RouteWorksAlertPlanner.alertedOn(store.lastWorksAlertDay, today: today) else { return }
        // The routes the works sections read, in the saved order; without any, no request at all.
        // The store belongs to the main actor: reading it from the background task is a hop there.
        let saved = await SavedRouteStore().load()
        let routes = Array(saved.prefix(service.rules.maximumWorksRoutes))
        guard !routes.isEmpty, let catalogue = catalogue() else { return }
        // RFI only, as Android's alert: never Trenitalia or Trenord, whatever «Dati in tempo reale» says.
        let notices = await service.general(personal: false, includeTrenord: false).network
        guard !notices.isEmpty, !Task.isCancelled else { return }
        func station(_ identity: StationIdentity) -> Station {
            catalogue.station(id: identity.id) ?? Station(id: identity.id, name: identity.name, railwayCode: identity.railwayCode)
        }
        let worksRoutes = routes.map { WorksRoute(id: $0.id, name: $0.name, stations: [station($0.origin), station($0.destination)]) }
        let matcher = RouteWorksMatcher(catalogue: catalogue.stations, rules: service.rules)
        let works = matcher.match(notices, routes: worksRoutes, today: today)
        let notified = store.worksNotified
        guard let alert = RouteWorksAlertPlanner.select(works, routeIDs: routes.map(\.id), today: today, notified: notified) else { return }
        // The matching can take a while: the quiet hours are checked again right before posting.
        guard let clock = RailWallClock(Date()), RouteWorksAlertPlanner.mayNotify(clock) else { return }
        if await post(id: worksNotificationID, title: EveningAlertTexts.title(alert), body: EveningAlertTexts.lines(alert).joined(separator: "\n\n")) {
            store.worksNotified = RouteWorksAlertPlanner.remember(notified, alert: alert, today: today)
            store.lastWorksAlertDay = today
        }
    }

    private static func catalogue() -> CuratedStationCatalog? {
        guard let url = Bundle.main.url(forResource: "curated_it_rail_stations", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? CuratedStationCatalog(data: data)
    }

    /// A local notification shown at once; true when the system accepted it.
    private static func post(id: String, title: String, body: String) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.threadIdentifier = id
        do {
            try await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
            return true
        } catch {
            return false
        }
    }
}

/// The texts of the works alert (Android's `RouteWorksAlertText`): the route in the title, then
/// what each notice names and when, and its own title.
enum EveningAlertTexts {
    static func title(_ alert: RouteWorksAlert) -> String {
        let first = alert.routeNames.first ?? ""
        let others = alert.routeNames.count - 1
        if others <= 0 {
            return String.localizedStringWithFormat(NSLocalizedString("notices.alerts.works_title", comment: ""), first)
        }
        // The counted number is the first argument of the plural text.
        return String.localizedStringWithFormat(NSLocalizedString("notices.alerts.works_title_more", comment: ""), others, first)
    }

    /// «L’avviso cita: la linea Milano – Venezia, Brescia · Date citate: dal 14 nov al 16 nov».
    static func summary(_ item: RouteWorksAlertItem, alert: RouteWorksAlert) -> String {
        let reason = item.reason
        let line = reason.line.map { [String.localizedStringWithFormat(NSLocalizedString("notices.works.line", comment: ""), $0)] } ?? []
        let named = (line + reason.stations).joined(separator: ", ")
        let names = alert.routeNames.count > 1
            ? String.localizedStringWithFormat(NSLocalizedString("notices.works.route_names", comment: ""), reason.routeName, named)
            : String.localizedStringWithFormat(NSLocalizedString("notices.works.names", comment: ""), named)
        let first = dayMonth(item.dates.first)
        let dates = item.dates.first == item.dates.last
            ? String.localizedStringWithFormat(NSLocalizedString("notices.works.date", comment: ""), first)
            : String.localizedStringWithFormat(NSLocalizedString("notices.works.dates", comment: ""), first, dayMonth(item.dates.last))
        return "\(names) · \(dates)"
    }

    /// Every notice shown: its summary, then its own title.
    static func lines(_ alert: RouteWorksAlert) -> [String] {
        alert.items.map { summary($0, alert: alert) + "\n" + $0.works.notice.title }
    }

    /// «14 nov» in the device's language, on the Italian calendar.
    static func dayMonth(_ date: PlannedLocalDate) -> String {
        guard let noon = PlannedLocalTime(hour: 12, minute: 0), let instant = ItalianWallClock.instant(date: date, time: noon) else { return date.iso }
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("dMMM")
        formatter.timeZone = ItalianWallClock.timeZone
        return formatter.string(from: instant)
    }
}

/// What the evening alerts remember, in the app's `UserDefaults` on this iPhone only: the strikes
/// and works notices already notified (id → epoch day) and the day of the last works alert.
final class EveningAlertsStore: @unchecked Sendable {
    private static let strikesKey = "it.binario.ios.strike-reminder.notified.v1"
    private static let worksKey = "it.binario.ios.route-works-alert.notified.v1"
    private static let lastWorksKey = "it.binario.ios.route-works-alert.last-day.v1"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var strikesNotified: [String: Int] {
        get { days(Self.strikesKey) }
        set { defaults.set(newValue, forKey: Self.strikesKey) }
    }

    var worksNotified: [String: Int] {
        get { days(Self.worksKey) }
        set { defaults.set(newValue, forKey: Self.worksKey) }
    }

    var lastWorksAlertDay: PlannedLocalDate? {
        get { defaults.string(forKey: Self.lastWorksKey).flatMap { PlannedLocalDate(iso: $0) } }
        set { defaults.set(newValue?.iso, forKey: Self.lastWorksKey) }
    }

    private func days(_ key: String) -> [String: Int] {
        let stored = defaults.dictionary(forKey: key) ?? [:]
        var result: [String: Int] = [:]
        for (id, value) in stored.prefix(RouteWorksAlertPlanner.maximumNotified) {
            if let day = (value as? NSNumber)?.intValue { result[id] = day }
        }
        return result
    }
}
