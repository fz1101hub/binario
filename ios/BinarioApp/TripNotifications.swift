import Foundation
import UserNotifications
import BinarioKit

/// What the trip notifications and alarms keep, in the app's `UserDefaults` on this iPhone only (Android's
/// preferences of the same features): the followed trip and what its last refresh showed, the stop alarms and the GPS
/// stop alarm, the approach alerts and their day, the bells, the notices already seen, the commuter alerts. Nothing is
/// sent.
final class TripAlertsStore: @unchecked Sendable {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// What the last refresh of the followed trip showed (Android's `FollowedTripStore`).
    struct FollowedState: Codable, Equatable {
        /// The official arrival delay only: an estimate from the position is never written here.
        var lastDelayMinutes: Int?
        var lastTitle: String?
        var lastLines: [String] = []
        var lastUpdatedAt: Date?
        var alertedKeys: [String] = []
        var lastBoarding: FollowedTripAlerts.BoardingPlatform?
        var nextRefreshAt: Date?
    }

    /// One approach alert's day (Android's `ApproachAlertCodec.DayState`), plus the pending alarm planned.
    struct ApproachState: Codable, Equatable {
        var date: String?
        var doneOn: String?
        var notifiedOn: String?
        var observation: ApproachObservation?
        var nextCheckAt: Date?
        var alarmAt: Date?
    }

    /// A commuter alert's status read before one of its triggers.
    struct CommuterStatusRecord: Codable, Equatable {
        var trigger: Date
        var status: CommuterLiveStatus?
    }

    var followedTrip: FollowedTrip? {
        get { value(Keys.followedTrip) }
        set { store(newValue, Keys.followedTrip) }
    }

    var followedState: FollowedState {
        get { value(Keys.followedState) ?? FollowedState() }
        set { store(newValue, Keys.followedState) }
    }

    var stopAlarms: [StopAlarmPlan] {
        get { value(Keys.stopAlarms) ?? [] }
        set { store(newValue, Keys.stopAlarms) }
    }

    /// The one GPS stop alarm iOS watches (GpsStopAlarm.swift): the stop's catalogue coordinates and radius, never a position.
    var gpsAlarm: GpsStopAlarm? {
        get { value(Keys.gpsAlarm) }
        set { store(newValue, Keys.gpsAlarm) }
    }

    /// The radius last chosen for each train class (Android's `GpsArrivalAlarmStore.rememberRadius`).
    var gpsAlarmRadii: [String: Int] {
        get { value(Keys.gpsAlarmRadii) ?? [:] }
        set { store(newValue, Keys.gpsAlarmRadii) }
    }

    var approachAlerts: [ApproachAlert] {
        get { value(Keys.approachAlerts) ?? [] }
        set { store(newValue, Keys.approachAlerts) }
    }

    var approachStates: [String: ApproachState] {
        get { value(Keys.approachStates) ?? [:] }
        set { store(newValue, Keys.approachStates) }
    }

    /// «Avvisi sui treni seguiti e preferiti», off until the user turns it on, as on Android.
    var trainAlertsEnabled: Bool {
        get { defaults.bool(forKey: Keys.trainAlertsEnabled) }
        set { defaults.set(newValue, forKey: Keys.trainAlertsEnabled) }
    }

    var trainAlertSubscriptions: [TrainAlertSubscription] {
        get { value(Keys.trainAlertSubscriptions) ?? [] }
        set { store(newValue, Keys.trainAlertSubscriptions) }
    }

    var trainAlertsSeen: [String: Int] {
        get { value(Keys.trainAlertsSeen) ?? [:] }
        set { store(newValue, Keys.trainAlertsSeen) }
    }

    /// The favourite and followed trains at the last planning, to tell which ones left; nil before any.
    var trainAlertsWatched: [String]? {
        get { value(Keys.trainAlertsWatched) }
        set { store(newValue, Keys.trainAlertsWatched) }
    }

    var trainAlertsLastCheck: Date? {
        get { value(Keys.trainAlertsLastCheck) }
        set { store(newValue, Keys.trainAlertsLastCheck) }
    }

    var commuterAlerts: [CommuterAlert] {
        get { value(Keys.commuterAlerts) ?? [] }
        set { store(newValue, Keys.commuterAlerts) }
    }

    var commuterStatuses: [String: CommuterStatusRecord] {
        get { value(Keys.commuterStatuses) ?? [:] }
        set { store(newValue, Keys.commuterStatuses) }
    }

    private enum Keys {
        static let followedTrip = "it.binario.ios.trip.followed.v1"
        static let followedState = "it.binario.ios.trip.followed-state.v1"
        static let stopAlarms = "it.binario.ios.trip.stop-alarms.v1"
        static let gpsAlarm = "it.binario.ios.trip.gps-alarm.v1"
        static let gpsAlarmRadii = "it.binario.ios.trip.gps-alarm-radius.v1"
        static let approachAlerts = "it.binario.ios.trip.approach-alerts.v1"
        static let approachStates = "it.binario.ios.trip.approach-states.v1"
        static let trainAlertsEnabled = "it.binario.ios.trip.train-alerts-enabled.v1"
        static let trainAlertSubscriptions = "it.binario.ios.trip.train-alerts.v1"
        static let trainAlertsSeen = "it.binario.ios.trip.train-alerts-seen.v1"
        static let trainAlertsWatched = "it.binario.ios.trip.train-alerts-watched.v1"
        static let trainAlertsLastCheck = "it.binario.ios.trip.train-alerts-last-check.v1"
        static let commuterAlerts = "it.binario.ios.trip.commuter-alerts.v1"
        static let commuterStatuses = "it.binario.ios.trip.commuter-statuses.v1"
    }

    /// A damaged value reads as none.
    private func value<Value: Decodable>(_ key: String) -> Value? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(Value.self, from: data)
    }

    private func store<Value: Encodable>(_ value: Value?, _ key: String) {
        guard let value, let data = try? JSONEncoder().encode(value) else {
            defaults.removeObject(forKey: key)
            return
        }
        defaults.set(data, forKey: key)
    }
}

/// Local notifications of the trip alerts: requests on this iPhone only, at once or at an instant. No push.
enum TripNotifier {
    static let followedAlertID = "it.binario.ios.trip.followed-alert"

    static func stopAlarmID(_ plan: StopAlarmPlan) -> String {
        "it.binario.ios.trip.stop-alarm." + StopAlarmPlanner.key(plan.trainNumber, plan.station)
    }

    static func approachID(_ key: String) -> String { "it.binario.ios.trip.approach." + key }
    static func trainAlertID(_ key: String) -> String { "it.binario.ios.trip.train-alerts." + key }
    static func commuterID(_ id: String, occurrence: Int) -> String { "it.binario.ios.trip.commuter.\(id).\(occurrence)" }

    static func allowed() async -> Bool {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: return true
        default: return false
        }
    }

    /// Shows the notification at `at`, at once when nil or past; `sound` false in the quiet hours. True when the
    /// system accepted it.
    @discardableResult
    static func post(id: String, title: String, body: String, at: Date? = nil, sound: Bool = true, now: Date = Date()) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = sound ? .default : nil
        content.threadIdentifier = id
        var trigger: UNNotificationTrigger?
        if let at, at.timeIntervalSince(now) >= 1 {
            trigger = UNTimeIntervalNotificationTrigger(timeInterval: at.timeIntervalSince(now), repeats: false)
        }
        do {
            try await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
            return true
        } catch {
            return false
        }
    }

    static func remove(_ ids: [String]) {
        guard !ids.isEmpty else { return }
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
    }
}

/// The texts of the trip alerts, Android's strings in the 17 languages (FollowedTripText, TripLiveText, the stop
/// alarm, the approach alert, the commuter alert).
enum TripTexts {
    static func text(_ key: String) -> String { NSLocalizedString(key, comment: "") }

    static func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: NSLocalizedString(key, comment: ""), locale: Locale.current, arguments: arguments)
    }

    /// «10 min».
    static func minutes(_ value: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.trip.minutes", comment: ""), value)
    }

    /// «Scendi tra 3 fermate», or «Scendi alla prossima fermata: Monza alle 8:56 · Binario 3».
    static func countdown(_ countdown: StopCountdown, platform: String? = nil) -> String {
        guard countdown.alightAtNextStop else {
            return String.localizedStringWithFormat(NSLocalizedString("result.trip.countdown_stops", comment: ""), countdown.stopsLeft)
        }
        let line = countdown.nextStopAt.map { format("result.trip.alight_next", countdown.nextStop, TripTime.clock($0)) }
            ?? format("result.trip.alight_next_untimed", countdown.nextStop)
        guard let platform, !platform.isEmpty else { return line }
        return line + " · " + format("result.trip.platform", platform)
    }

    /// «Stima dalla tua posizione: circa +6 min, arrivo ~18:47 · stima delle 18:31 (fonte ufficiale: +4 min)».
    static func estimate(_ estimate: GpsDelayEstimate, officialDelayMinutes: Int?, now: Date) -> String {
        let arrival = TripTime.clock(estimate.arrivalAt)
        var line = estimate.delayMinutes <= 0
            ? format("result.trip.estimate_on_time", arrival)
            : format("result.trip.estimate_line", minutes(estimate.delayMinutes), arrival)
        if now.timeIntervalSince(estimate.at) > GpsTrainTracking.estimateFresh {
            line += " · " + format("result.trip.estimate_age", TripTime.clock(estimate.at))
        }
        guard let official = officialDelayMinutes else { return line }
        let value = official <= 0 ? text("result.trip.on_time_lowercase") : format("result.trip.delay_short", official)
        return line + " " + format("result.trip.estimate_official", value)
    }

    /// The estimate line, «Posizione fuori dal percorso» when the position left the line, the waiting line when
    /// asked for, else nil.
    static func position(_ estimate: GpsDelayEstimate?, status: GpsTrackStatus?, officialDelayMinutes: Int?, now: Date, includeWaiting: Bool = false) -> String? {
        if status == .offRoute { return text("result.trip.estimate_off_route") }
        if let estimate { return self.estimate(estimate, officialDelayMinutes: officialDelayMinutes, now: now) }
        if includeWaiting, status != nil { return text("result.trip.estimate_waiting") }
        return nil
    }

    static func transferRisk(_ level: TripTransferRisk) -> String {
        switch level {
        case .missed: return text("result.trip.transfer_missed")
        case .atRisk: return text("result.trip.transfer_at_risk")
        case .ok: return text("result.trip.transfer_confirmed")
        case .unknown: return text("result.trip.scheduled_only")
        }
    }

    /// The Live Activity's content for one refresh (Android's `FollowedTripText.content`); `recorded` leaves out the
    /// position estimate, which is never stored.
    static func content(_ trip: FollowedTrip, view: FollowedTripView, answered: Bool, updatedAt: Date, now: Date) -> (state: TripActivityContent, recorded: [String]) {
        let label = view.leg.label.isEmpty ? view.leg.number : view.leg.label
        let title: String
        if !answered {
            title = format("result.trip.title_unknown", label)
        } else if view.cancelled {
            title = format("result.trip.title_cancelled", label)
        } else if !view.live {
            title = format("result.trip.title_scheduled", label)
        } else if let delay = view.delayMinutes, delay > 0 {
            title = format("result.trip.title_delay", label, minutes(delay))
        } else {
            title = format("result.trip.title_on_time", label)
        }
        let line: String
        if let countdown = view.countdown, countdown.alightAtNextStop {
            // At the last stop before getting off the countdown names it, with time and platform.
            line = Self.countdown(countdown, platform: view.platform)
        } else if let stop = view.nextStop, let time = view.nextStopTime, let platform = view.platform {
            line = format("result.trip.next_stop_platform", stop, TripTime.clock(time), platform)
        } else if let stop = view.nextStop, let time = view.nextStopTime {
            line = format("result.trip.next_stop", stop, TripTime.clock(time))
        } else {
            line = trip.title
        }
        let countdownHead = view.countdown.flatMap { $0.alightAtNextStop ? nil : Self.countdown($0) }
        let official = view.live && !view.cancelled ? (view.delayMinutes ?? 0) : nil
        let estimate = position(view.estimate, status: view.trackStatus, officialDelayMinutes: official, now: now)
        let transfer = view.transfer.map { assessment in
            format("result.trip.transfer", view.transferStation ?? "", view.nextLegLabel ?? "") + " · " + transferRisk(assessment.level)
        }
        let updated = format("result.trip.updated_at", TripTime.clock(updatedAt))
        let details = [countdownHead, line, estimate, transfer, updated].compactMap { $0 }
        let recorded = [countdownHead, line, transfer, updated].compactMap { $0 }
        let collapsed: String
        if view.countdown?.alightAtNextStop == true {
            collapsed = line
        } else if let transfer {
            collapsed = transfer
        } else if let countdownHead {
            collapsed = countdownHead + " · " + line
        } else {
            collapsed = line
        }
        let compact: String
        if view.cancelled {
            compact = "×"
        } else if let delay = view.delayMinutes, delay > 0 {
            compact = format("result.trip.delay_short", delay)
        } else if let countdown = view.countdown {
            compact = countdown.nextStopAt.map(TripTime.clock) ?? ""
        } else {
            compact = view.nextStopTime.map(TripTime.clock) ?? ""
        }
        let warning = view.cancelled || view.transfer?.level == .atRisk || view.transfer?.level == .missed
        return (TripActivityContent(title: title, line: collapsed, details: details, compact: compact, warning: warning), recorded)
    }

    /// The status sentence of the approach and commuter alerts: «Stato attuale: ritardo di 5 minuti, binario 3.».
    static func status(_ status: CommuterLiveStatus?) -> String {
        let strings = CommuterAlertStatusStrings(
            unavailable: text("notices.alerts.status_unavailable"),
            onTime: text("notices.alerts.status_on_time"),
            cancelled: text("notices.alerts.status_cancelled"),
            delay: { String.localizedStringWithFormat(NSLocalizedString("notices.alerts.status_delay", comment: ""), $0) },
            withPlatform: { status, platform in
                String(format: NSLocalizedString("notices.alerts.status_platform", comment: ""), locale: Locale.current, arguments: [status, platform])
            }
        )
        return format("notices.alerts.status", CommuterAlertStatusRules.format(status, strings: strings))
    }
}

/// The Live Activity's state without ActivityKit, so the texts stay testable and platform-neutral.
struct TripActivityContent: Equatable {
    var title: String
    var line: String
    var details: [String]
    var compact: String
    var warning: Bool
}
