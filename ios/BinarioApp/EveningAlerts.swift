import Foundation
import BackgroundTasks
import UserNotifications
import BinarioKit

/// The two evening alerts of Android on the iPhone, «Scioperi del giorno dopo» (18:00) and «Lavori
/// di domani sulle tue tratte» (19:00), with one background app refresh task: the only background
/// mode the app declares (`fetch`) and its only permitted identifier. The same task also runs the
/// trip checks that are due (TripMonitor.swift: the followed trip's Live Activity, «Avvisami quando
/// sta arrivando», the notices of the trains with the bell, the status of the commuter alerts). iOS decides when the task
/// runs, never before the hour asked for; a run outside an alert's window (after 22:00) notifies
/// nothing for it, as on Android. A run reads only the public MIT strike calendar and RFI's
/// infomobility feed, with the manners of every source, and schedules local notifications: no
/// remote notification and no push entitlement. What was notified is kept on this iPhone only.
/// When iOS last started the task and which checks it handled are kept there too, for «Attività in
/// background» (BackgroundStatusView.swift).
/// The same task also runs Android's two background jobs of the favourite trains: «Puntualità dei
/// treni preferiti», the one live request the app makes in the background (with «Dati in tempo
/// reale» on, the favourite's number on its day, through `AppModel.trainNumberRuns`), and the
/// Sunday «Riepilogo settimanale», which reads only what is on the iPhone.
enum EveningAlerts {
    static let taskIdentifier = "it.binario.ios.evening-alerts"
    static let strikeNotificationID = "it.binario.ios.strike-reminder"
    static let worksNotificationID = "it.binario.ios.route-works-alert"
    static let digestNotificationID = "it.binario.ios.weekly-digest"

    /// The app's models, for the favourite trains' jobs: the live sources and the punctuality history.
    @MainActor private static weak var app: AppModel?
    @MainActor private static weak var personal: PersonalModel?

    @MainActor
    static func attach(model: AppModel, personal: PersonalModel) {
        app = model
        self.personal = personal
    }

    /// Plans the next run: the earliest of the alerts and jobs switched on, or none. With
    /// `immediateWorksAlert` (the works alert just turned on in the evening window) tonight's alert
    /// is asked for at once, as on Android.
    @MainActor
    static func schedule(now: Date = Date(), immediateWorksAlert: Bool = false) {
        var dates = eveningChecks(now: now, immediateWorksAlert: immediateWorksAlert).map { $0.at }
        if let next = TripBackgroundSchedule.nextRunAt(now) { dates.append(next) }
        guard let earliest = dates.min() else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskIdentifier)
            return
        }
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = earliest
        // A refused request (Background App Refresh off, too many requests) leaves the alerts silent.
        try? BGTaskScheduler.shared.submit(request)
    }

    /// The next run of the evening alerts and of the favourites' jobs switched on.
    @MainActor
    private static func eveningChecks(now: Date, immediateWorksAlert: Bool) -> [(check: BackgroundCheck, at: Date)] {
        let preferences = ExtrasPreferenceStore()
        var planned: [(check: BackgroundCheck, at: Date)] = []
        if preferences.punctualityRecordingEnabled, app?.liveDataEnabled ?? true,
           let next = PunctualityRecordingPlanner.nextRun(PunctualityRecorderStore().candidates(FavouriteTrainStore().load().trains), now: now) {
            // iOS runs a refresh task some minutes after the date at the earliest.
            planned.append((check: .punctuality, at: max(next, now.addingTimeInterval(60))))
        }
        if preferences.strikeReminderEnabled, let next = StrikeReminderPlanner.nextRunAt(now) { planned.append((check: .strikes, at: next)) }
        if preferences.routeWorksAlertEnabled, let next = RouteWorksAlertPlanner.nextRunAt(now, immediate: immediateWorksAlert) {
            planned.append((check: .routeWorks, at: next))
        }
        if preferences.weeklyDigestEnabled,
           let next = WeeklyDigestPlanner.nextRunAt(now, lastSent: EveningAlertsStore().lastDigestWeekEnd) {
            planned.append((check: .weeklyDigest, at: next))
        }
        return planned
    }

    /// «Prossimi controlli in background»: the next run of every check, in the order of
    /// [BackgroundCheck] (Android's `BackgroundRuns.planned`).
    @MainActor
    static func plannedChecks(now: Date) -> [(check: BackgroundCheck, at: Date)] {
        let trips = TripBackgroundSchedule.dates(now)
        let tripChecks: [(check: BackgroundCheck, at: Date?)] = [
            (check: .followedTrip, at: trips.followed), (check: .trainAlerts, at: trips.trainAlerts),
            (check: .approachAlerts, at: trips.approach), (check: .commuterAlerts, at: trips.commuter),
        ]
        let all = tripChecks.compactMap { item in item.at.map { (check: item.check, at: $0) } } + eveningChecks(now: now, immediateWorksAlert: false)
        return BackgroundCheck.allCases.compactMap { check in all.first { $0.check == check } }
    }

    /// The checks a run starting at `now` handles, as `run` below decides them, for «Attività in background».
    @MainActor
    private static func dueChecks(now: Date, notificationsAllowed: Bool) -> [BackgroundCheck] {
        let trips = TripBackgroundSchedule.dates(now)
        var due: [BackgroundCheck] = []
        if trips.followed.map({ $0 <= now }) == true { due.append(.followedTrip) }
        if trips.trainAlerts.map({ $0 <= now }) == true { due.append(.trainAlerts) }
        if trips.approach.map({ $0 <= now }) == true { due.append(.approachAlerts) }
        if trips.commuter.map({ $0 <= now }) == true { due.append(.commuterAlerts) }
        let preferences = ExtrasPreferenceStore()
        if preferences.punctualityRecordingEnabled, app?.liveDataEnabled == true,
           !PunctualityRecordingPlanner.due(PunctualityRecorderStore().candidates(FavouriteTrainStore().load().trains), now: now).isEmpty {
            due.append(.punctuality)
        }
        if notificationsAllowed, let clock = RailWallClock(now) {
            if preferences.strikeReminderEnabled, StrikeReminderPlanner.mayNotify(clock) { due.append(.strikes) }
            if preferences.routeWorksAlertEnabled, RouteWorksAlertPlanner.mayNotify(clock) { due.append(.routeWorks) }
            if WeeklyDigestPlanner.handles(
                enabled: preferences.weeklyDigestEnabled, notificationsAllowed: true, now: clock, lastSent: EveningAlertsStore().lastDigestWeekEnd
            ) != nil {
                due.append(.weeklyDigest)
            }
        }
        return due
    }

    /// The earliest start asked of iOS for the background task, if one is pending.
    static func requestedStart() async -> Date? {
        await withCheckedContinuation { continuation in
            BGTaskScheduler.shared.getPendingTaskRequests { requests in
                let request = requests.first { $0.identifier == taskIdentifier }
                continuation.resume(returning: request.map { $0.earliestBeginDate ?? .distantPast })
            }
        }
    }

    /// One background run: each alert whose window it is, the favourite trains' jobs, then the next run.
    static func run(now: Date = Date()) async {
        // «Attività in background»: when iOS started this run and what it is about to check, on this iPhone only.
        let allowed = await notificationsAllowed()
        let due = await dueChecks(now: now, notificationsAllowed: allowed)
        BackgroundRunLog.record(due, startedAt: now)
        // The trip checks that are due (TripMonitor.swift), then the evening alerts and the favourites' jobs.
        await TripMonitor.runInBackground(now: now)
        await runAlerts(now: now)
        if ExtrasPreferenceStore().punctualityRecordingEnabled, !Task.isCancelled {
            await recordPunctuality(now: now)
        }
        if !Task.isCancelled {
            await sendWeeklyDigest(now: Date())
        }
        await schedule()
    }

    private static func runAlerts(now: Date) async {
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

    // MARK: Favourite trains: punctuality and weekly digest

    /// Android's `PunctualityRecorderWorker`: the favourites whose run is due get their state asked
    /// on that day and, once arrived, one observation in the personal punctuality. Nothing is shown.
    @MainActor
    private static func recordPunctuality(now: Date) async {
        guard let app, let personal, app.liveDataEnabled else { return }
        let favourites = FavouriteTrainStore().load().trains
        let store = PunctualityRecorderStore()
        store.retainOnly(favourites.map(\.number))
        for (candidate, date) in PunctualityRecordingPlanner.due(store.candidates(favourites), now: now) {
            guard !Task.isCancelled,
                  let favourite = favourites.first(where: { PunctualityHistory.trainKey($0.number) == PunctualityHistory.trainKey(candidate.number) }),
                  let noon = PlannedLocalTime(hour: 12, minute: 0),
                  let day = ItalianWallClock.instant(date: date, time: noon) else { continue }
            var attempted = candidate
            attempted.attempts = (candidate.attemptDate == date ? candidate.attempts : 0) + 1
            attempted.attemptDate = date
            attempted.lastAttempt = now
            store.save(attempted)
            let request = LiveTrainStatusRequest(
                number: NetworkReliability.numberKey(favourite.number), serviceDate: day,
                stationCode: NetworkReliability.stationCode(favourite.origin), originName: favourite.origin.name
            )
            guard let result = await app.trainNumberRuns(request) else { return }
            switch result {
            case let .available(lookup):
                guard let run = lookup.run(for: request) else {
                    // A day the train does not run is done after one complete answer.
                    if lookup.runs.isEmpty, lookup.isComplete, lookup.unavailableSources.isEmpty {
                        attempted.recordedOn = date
                        store.save(attempted)
                    }
                    continue
                }
                let arrival = run.stops.last?.scheduled
                if let arrival {
                    attempted.durationMinutes = max(1, Int(arrival.timeIntervalSince(run.scheduledDeparture) / 60))
                }
                let afterArrival = TimeInterval(SharedData.FavouritePunctuality.recordingAfterArrivalMinutes * 60)
                let arrived = run.stops.last?.passed == true || arrival.map { Date() >= $0.addingTimeInterval(afterArrival) } == true
                if arrived, personal.recordFavourite(run, number: favourite.number, date: date) {
                    attempted.recordedOn = date
                }
                store.save(attempted)
            case .unavailable(.notFound):
                attempted.recordedOn = date
                store.save(attempted)
            case .unavailable:
                continue
            }
        }
    }

    /// Android's `WeeklyDigestWorker`: on Sunday evening, one notification with the week of each
    /// favourite train recorded on this iPhone and its network share; no request at all.
    @MainActor
    private static func sendWeeklyDigest(now: Date) async {
        let store = EveningAlertsStore()
        guard let clock = RailWallClock(now),
              let weekEnd = WeeklyDigestPlanner.handles(
                  enabled: ExtrasPreferenceStore().weeklyDigestEnabled, notificationsAllowed: await notificationsAllowed(),
                  now: clock, lastSent: store.lastDigestWeekEnd
              ) else { return }
        // The week is handled even with nothing to say: next Sunday tries again.
        store.lastDigestWeekEnd = weekEnd
        let favourites = FavouriteTrainStore().load().trains
        guard !favourites.isEmpty else { return }
        let history = personal?.punctuality ?? []
        let stats = app?.reliabilityStats
        var origins: [String: String?] = [:]
        for favourite in favourites { origins[favourite.number] = NetworkReliability.stationCode(favourite.origin) }
        guard let digest = WeeklyDigestBuilder.build(
            favourites: favourites.map { (number: $0.number, name: personal?.name(ofTrain: $0.id)) },
            history: history,
            weekEnd: weekEnd,
            network: { number in stats.flatMap { DigestNetwork.of($0, number: number, originCode: origins[number] ?? nil) } }
        ) else { return }
        let lines = WeeklyDigestTexts.lines(digest, statsEnd: stats?.windowEnd)
        _ = await post(id: digestNotificationID, title: String(localized: "saved.digest.title"), body: lines.joined(separator: "\n"), sound: false)
    }

    static func notificationsAllowed() async -> Bool {
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
    private static func post(id: String, title: String, body: String, sound: Bool = true) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = sound ? .default : nil
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
    private static let lastDigestKey = "it.binario.ios.weekly-digest.last-sent.v1"

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

    /// The Sunday of the last weekly digest handled.
    var lastDigestWeekEnd: PlannedLocalDate? {
        get { defaults.string(forKey: Self.lastDigestKey).flatMap { PlannedLocalDate(iso: $0) } }
        set { defaults.set(newValue?.iso, forKey: Self.lastDigestKey) }
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

/// The recorder's schedule of each favourite train (Android's `PunctualityRecorderStore`): what it
/// learned (the duration) and did (the day recorded, today's attempts), in the app's `UserDefaults`
/// on this iPhone only, never in a backup: it is only a schedule.
final class PunctualityRecorderStore {
    private static let key = "it.binario.ios.punctuality-recorder.v1"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private var stored: [String: [String: Any]] {
        (defaults.dictionary(forKey: Self.key) ?? [:]).compactMapValues { $0 as? [String: Any] }
    }

    func candidates(_ favourites: [FavouriteTrain]) -> [RecordingCandidate] {
        let all = stored
        return favourites.map { favourite in
            let value = all[PunctualityHistory.trainKey(favourite.number)] ?? [:]
            let duration = (value["duration"] as? NSNumber)?.intValue
            return RecordingCandidate(
                number: favourite.number,
                departure: PlannedLocalTime(hhmm: favourite.scheduledDeparture),
                durationMinutes: duration.flatMap { (1...(36 * 60)).contains($0) ? $0 : nil },
                recordedOn: (value["recorded"] as? String).flatMap { PlannedLocalDate(iso: $0) },
                attemptDate: (value["attemptDate"] as? String).flatMap { PlannedLocalDate(iso: $0) },
                attempts: (value["attempts"] as? NSNumber)?.intValue ?? 0,
                lastAttempt: (value["lastAttempt"] as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue / 1_000) }
            )
        }
    }

    func save(_ candidate: RecordingCandidate) {
        var value: [String: Any] = ["attempts": candidate.attempts]
        if let duration = candidate.durationMinutes { value["duration"] = duration }
        if let recorded = candidate.recordedOn { value["recorded"] = recorded.iso }
        if let attemptDate = candidate.attemptDate { value["attemptDate"] = attemptDate.iso }
        if let last = candidate.lastAttempt { value["lastAttempt"] = Int64(last.timeIntervalSince1970 * 1_000) }
        var all = stored
        all[PunctualityHistory.trainKey(candidate.number)] = value
        defaults.set(all, forKey: Self.key)
    }

    /// Forgets the trains that are no longer favourites.
    func retainOnly(_ numbers: [String]) {
        let keep = Set(numbers.map(PunctualityHistory.trainKey))
        let all = stored
        let kept = all.filter { keep.contains($0.key) }
        if kept.count != all.count { defaults.set(kept, forKey: Self.key) }
    }
}

/// The texts of the weekly digest (Android's `WeeklyDigestText`): one line per train, the train
/// that got worse first, the statistics' attribution last when a network share is shown.
enum WeeklyDigestTexts {
    static func title(_ train: DigestTrain) -> String {
        train.name ?? PersonalTexts.format("saved.digest.train", train.number)
    }

    /// «%1$s (entro %2$s)»: a count or share with the on-time limit it counts against.
    static func within(_ text: String, minutes: Int) -> String {
        PersonalTexts.format("saved.digest.within", text, String.localizedStringWithFormat(NSLocalizedString("result.minutes_short", comment: ""), minutes))
    }

    static func line(_ train: DigestTrain) -> String {
        var parts: [String] = []
        if let week = train.week {
            parts.append(within(ReliabilityTexts.onTime(week.onTime, of: week.runs), minutes: SharedData.PersonalPunctuality.onTimeMinutes))
            if week.cancelled > 0 { parts.append(ReliabilityTexts.cancelled(week.cancelled)) }
        } else {
            parts.append(String(localized: "saved.digest.no_runs"))
        }
        if let network = train.network {
            let share = String.localizedStringWithFormat(NSLocalizedString("saved.digest.network", comment: ""), network.days, network.percent)
            parts.append(within(share, minutes: network.limitMinutes))
        }
        return PersonalTexts.format("saved.digest.train_line", title(train), parts.joined(separator: " · "))
    }

    static func lines(_ digest: WeeklyDigest, statsEnd: NetworkReliabilityDay?) -> [String] {
        var lines: [String] = []
        if let worse = digest.worse {
            lines.append(PersonalTexts.format("saved.digest.worse", title(worse.train), worse.weekPercent, worse.usualPercent))
        }
        lines += digest.trains.map(line)
        if let statsEnd, digest.trains.contains(where: { $0.network != nil }) {
            lines.append(ReliabilityTexts.attribution(statsEnd))
        }
        return lines
    }
}
