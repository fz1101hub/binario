import SwiftUI
import UIKit
import BinarioKit

/// The checks of the app's one background refresh task (EveningAlerts.swift), in the order
/// «Attività in background» lists them (Android's `BackgroundCheck`). The commuter alerts are
/// among them on the iPhone, where the same task plans them; the widgets are not, as iOS refreshes
/// them on its own.
enum BackgroundCheck: String, CaseIterable, Sendable {
    case followedTrip, trainAlerts, approachAlerts, commuterAlerts, punctuality, strikes, routeWorks, weeklyDigest

    var title: String {
        switch self {
        case .followedTrip: NSLocalizedString("settings.background.followed", comment: "")
        case .trainAlerts: NSLocalizedString("settings.background.train_alerts", comment: "")
        case .approachAlerts: NSLocalizedString("settings.background.approach", comment: "")
        case .commuterAlerts: NSLocalizedString("settings.alerts.commuter_title", comment: "")
        case .punctuality: NSLocalizedString("settings.punctuality_recording.title", comment: "")
        case .strikes: NSLocalizedString("settings.alerts.strike_title", comment: "")
        case .routeWorks: NSLocalizedString("settings.alerts.works_title", comment: "")
        case .weeklyDigest: NSLocalizedString("settings.weekly_digest.title", comment: "")
        }
    }
}

/// The last background run: when iOS started it and the checks it handled.
struct BackgroundRunRecord: Equatable, Sendable {
    let startedAt: Date
    let checks: [BackgroundCheck]
}

/// What the last background run did, in the app's `UserDefaults` on this iPhone only, never in a
/// backup: one record, replaced by every run, shown by «Attività in background».
enum BackgroundRunLog {
    static let key = "it.binario.ios.background-run.v1"

    static func record(_ checks: [BackgroundCheck], startedAt: Date, defaults: UserDefaults = .standard) {
        let value: [String: Any] = ["startedAt": Int64(startedAt.timeIntervalSince1970 * 1_000), "checks": checks.map(\.rawValue)]
        defaults.set(value, forKey: key)
    }

    static func last(defaults: UserDefaults = .standard) -> BackgroundRunRecord? {
        guard let stored = defaults.dictionary(forKey: key), let millis = (stored["startedAt"] as? NSNumber)?.doubleValue else { return nil }
        let checks = (stored["checks"] as? [String] ?? []).compactMap(BackgroundCheck.init(rawValue:))
        return BackgroundRunRecord(startedAt: Date(timeIntervalSince1970: millis / 1_000), checks: checks)
    }
}

/// «Attività in background» (Android's `BackgroundStatusPanel`): whether notifications and iOS's
/// Background App Refresh are allowed, when iOS last started the background checks and what they
/// did, the start asked of iOS and the next run of every check. Read again whenever the screen
/// comes back or a switch changes; everything is read on this iPhone, nothing is sent.
struct BackgroundStatusSection: View {
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var extras: ExtrasModel
    @EnvironmentObject private var trips: TripMonitor
    @State private var status: BackgroundStatus?

    var body: some View {
        Section("settings.background.title") {
            if let status {
                ForEach(status.lines, id: \.self) { line in
                    Text(line)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Text("settings.background.runs_title")
                    .font(.subheadline.weight(.semibold))
                ForEach(status.planned, id: \.self) { line in
                    Text(line)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Text("settings.background.note")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .task(id: refreshKey) {
            guard scenePhase == .active else { return }
            status = await BackgroundStatus.read(now: Date())
        }
    }

    /// What the status depends on: the screen coming back and every switch of a check.
    private var refreshKey: String {
        let switches = [extras.strikeReminderEnabled, extras.routeWorksAlertEnabled, extras.punctualityRecordingEnabled, extras.weeklyDigestEnabled,
                        trips.trainAlertsEnabled]
        let counts = [trips.approachAlerts.count, trips.trainAlertSubscriptions.count, trips.commuterAlerts.count]
        return "\(scenePhase)|\(switches)|\(counts)|\(trips.followedTrip?.id ?? "")"
    }
}

/// The lines of «Attività in background», read once.
@MainActor
struct BackgroundStatus {
    let lines: [String]
    let planned: [String]

    static func read(now: Date) async -> BackgroundStatus {
        // The start asked of iOS follows what is switched on now, as Android's works do at once.
        EveningAlerts.schedule(now: now)
        let notifications = await EveningAlerts.notificationsAllowed()
        let requested = await EveningAlerts.requestedStart()
        var lines = [NSLocalizedString(notifications ? "settings.background.notifications_on" : "settings.background.notifications_off", comment: "")]
        let refreshAllowed = UIApplication.shared.backgroundRefreshStatus == .available
        lines.append(NSLocalizedString(refreshAllowed ? "settings.background.refresh_on" : "settings.background.refresh_off", comment: ""))
        if ProcessInfo.processInfo.isLowPowerModeEnabled { lines.append(String(localized: "settings.background.low_power")) }
        if let last = BackgroundRunLog.last() {
            lines.append(PersonalTexts.format("settings.background.last_run", Self.dateTime(last.startedAt)))
            lines.append(last.checks.isEmpty
                ? String(localized: "settings.background.did_nothing")
                : PersonalTexts.format("settings.background.did", last.checks.map(\.title).joined(separator: ", ")))
        } else {
            lines.append(String(localized: "settings.background.never"))
        }
        if let requested {
            lines.append(PersonalTexts.format("settings.background.requested", Self.dateTime(max(requested, now))))
        } else {
            lines.append(String(localized: "settings.background.requested_none"))
        }
        var planned = EveningAlerts.plannedChecks(now: now).compactMap { item in
            BackgroundRunTime.of(item.at, now: now).map { Self.text(item.check, $0) }
        }
        if planned.isEmpty { planned = [String(localized: "settings.background.runs_none")] }
        return BackgroundStatus(lines: lines, planned: planned)
    }

    /// Android's `BackgroundRuns.text`: «Avvisi sui treni: oggi alle 18:40».
    static func text(_ check: BackgroundCheck, _ time: BackgroundRunTime) -> String {
        switch time {
        case .soon: PersonalTexts.format("settings.background.runs_soon", check.title)
        case let .today(clock): PersonalTexts.format("settings.background.runs_today", check.title, hhmm(clock))
        case let .tomorrow(clock): PersonalTexts.format("settings.background.runs_tomorrow", check.title, hhmm(clock))
        case let .later(day, clock): PersonalTexts.format("settings.background.runs_later", check.title, Self.day(day), hhmm(clock))
        }
    }

    private static func hhmm(_ time: PlannedLocalTime) -> String {
        String(format: "%02d:%02d", time.hour, time.minute)
    }

    /// «mar 24 nov» in the device's language, on the Italian calendar (Android's «EEEdMMM»).
    private static func day(_ date: PlannedLocalDate) -> String {
        guard let noon = PlannedLocalTime(hour: 12, minute: 0), let instant = ItalianWallClock.instant(date: date, time: noon) else { return date.iso }
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("EEEdMMM")
        formatter.timeZone = ItalianWallClock.timeZone
        return formatter.string(from: instant)
    }

    /// «oggi, 18:40» or the day and time, in the device's language, on the Italian clock.
    private static func dateTime(_ instant: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        formatter.doesRelativeDateFormatting = true
        formatter.timeZone = ItalianWallClock.timeZone
        return formatter.string(from: instant)
    }
}
