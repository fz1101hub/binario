import Foundation

/// Whether the app may show notifications, as the system says.
public enum PlannedJourneyNotificationPermission: Equatable, Sendable {
    /// Never asked: Binario asks only when the user chooses a reminder.
    case notDetermined
    case denied
    /// Allowed, also provisionally.
    case allowed
}

/// A local notification request of the Agenda, as it is handed to the system or read back from it.
public struct PlannedJourneyNotificationRequest: Equatable, Sendable {
    public let identifier: String
    /// When it fires; nil when the system cannot tell.
    public let fireDate: Date?
    public let title: String
    public let body: String

    public init(identifier: String, fireDate: Date?, title: String, body: String) {
        self.identifier = identifier
        self.fireDate = fireDate
        self.title = title
        self.body = body
    }
}

/// The system's notification centre (`UNUserNotificationCenter` in the app), behind a protocol so
/// that the tests use a fake. Local notifications only: nothing is registered for push.
@MainActor
public protocol PlannedJourneyNotificationCenter: AnyObject {
    func permission() async -> PlannedJourneyNotificationPermission
    /// Shows the system's question; true when notifications are allowed afterwards.
    func requestPermission() async -> Bool
    /// Every pending request of the app.
    func pendingRequests() async -> [PlannedJourneyNotificationRequest]
    /// Adds a request; one with the same identifier is replaced.
    func add(_ request: PlannedJourneyNotificationRequest) async throws
    func removePendingRequests(identifiers: [String])
    func removeDeliveredNotifications(identifiers: [String])
}

/// The reminder of one journey: at its Italian departure minus the lead chosen, whatever the
/// phone's zone. It carries the validated station names and the stored Italian time, never the
/// free label.
public struct PlannedJourneyReminderPlan: Equatable, Sendable {
    public let identifier: String
    public let journeyID: String
    public let fireDate: Date
    public let departure: Date
    public let minutesBefore: Int
    public let origin: String
    public let destination: String
    public let departureDate: PlannedLocalDate
    public let departureTime: PlannedLocalTime
}

/// The words of a notification, written by the app in the user's language.
public struct PlannedJourneyReminderContent: Equatable, Sendable {
    public let title: String
    public let body: String

    public init(title: String, body: String) {
        self.title = title
        self.body = body
    }
}

/// Which reminders the Agenda wants: Android's planner for one-leg journeys (active, a lead
/// chosen, the time to fire still to come), the nearest first.
public enum PlannedJourneyReminderPlanner {
    /// Every Agenda request starts with it; the app schedules no other local notification.
    public static let identifierPrefix = "it.binario.ios.agenda-reminder."

    /// One identifier per journey, the same at every scheduling, so a new request replaces the old
    /// one and a deletion can cancel it before the journey is removed.
    public static func identifier(journeyID: String) -> String {
        identifierPrefix + String(BundledGTFSScheduleInstaller.sha256Hex(Data(journeyID.utf8)).prefix(32))
    }

    public static func isAgendaReminder(_ identifier: String) -> Bool {
        identifier.hasPrefix(identifierPrefix)
    }

    /// The reminder of `journey` at `now`; nil without a lead, for a journey no longer active, or
    /// when the time to fire has already come.
    public static func plan(for journey: PlannedJourney, now: Date) -> PlannedJourneyReminderPlan? {
        guard journey.status == .active, let minutes = journey.reminderMinutes,
              PlannedJourneySchema.reminderMinutes.contains(minutes)
        else { return nil }
        let departure = journey.departure.instant
        let fireDate = departure.addingTimeInterval(-Double(minutes * 60))
        guard fireDate > now, departure > now else { return nil }
        return PlannedJourneyReminderPlan(
            identifier: identifier(journeyID: journey.id),
            journeyID: journey.id,
            fireDate: fireDate,
            departure: departure,
            minutesBefore: minutes,
            origin: journey.origin.displayName,
            destination: journey.destination.displayName,
            departureDate: journey.departure.date,
            departureTime: journey.departure.time
        )
    }

    /// Every reminder to schedule at `now`, the first to fire first.
    public static func plans(_ journeys: [PlannedJourney], now: Date) -> [PlannedJourneyReminderPlan] {
        journeys.compactMap { plan(for: $0, now: now) }.sorted { first, second in
            first.fireDate == second.fireDate ? first.identifier < second.identifier : first.fireDate < second.fireDate
        }
    }
}

/// What the system holds for the Agenda after a reconciliation.
public struct PlannedJourneyReminderStatus: Equatable, Sendable {
    public let permission: PlannedJourneyNotificationPermission
    /// The journeys whose reminder is pending in the system, as read back from it.
    public let scheduledJourneyIDs: Set<String>
    /// A request could not be added.
    public let failed: Bool

    public init(permission: PlannedJourneyNotificationPermission, scheduledJourneyIDs: Set<String>, failed: Bool) {
        self.permission = permission
        self.scheduledJourneyIDs = scheduledJourneyIDs
        self.failed = failed
    }

    /// Before the system was read.
    public static let unknown = PlannedJourneyReminderStatus(permission: .notDetermined, scheduledJourneyIDs: [], failed: false)

    /// The reminder line of `journey`: what is really scheduled, not only the lead chosen.
    public func state(of journey: PlannedJourney, now: Date) -> PlannedJourneyReminderState {
        guard let minutes = journey.reminderMinutes else { return .none }
        guard PlannedJourneyReminderPlanner.plan(for: journey, now: now) != nil else { return .tooLate(minutes: minutes) }
        if scheduledJourneyIDs.contains(journey.id) { return .scheduled(minutes: minutes) }
        if permission != .allowed { return .notAllowed(minutes: minutes) }
        return .notScheduled(minutes: minutes)
    }
}

/// The reminder line of a journey.
public enum PlannedJourneyReminderState: Equatable, Sendable {
    /// «Nessuno»: no lead chosen.
    case none
    case scheduled(minutes: Int)
    /// Notifications are not allowed (refused, or never asked).
    case notAllowed(minutes: Int)
    /// The time to fire has come, or the journey is completed or archived.
    case tooLate(minutes: Int)
    /// The system does not hold it.
    case notScheduled(minutes: Int)
}

/// What became of the reminder of a journey just saved; the journey is saved in every case.
public enum PlannedJourneyReminderOutcome: Equatable, Sendable {
    case none
    case scheduled
    case notAllowed
    case tooLate
    case failed
}

/// The Agenda's local reminders. Permission is asked only by `scheduleAfterSaving` (the user has
/// just chosen a reminder), never by a reconciliation. A reconciliation makes the system's pending
/// requests those of the Agenda: it removes the requests of journeys deleted, completed or
/// changed, and adds the missing ones, the nearest `maximumPending` only (iOS keeps at most 64 per
/// app); the next one refills the window. Runs never overlap.
@MainActor
public final class PlannedJourneyReminderScheduler {
    /// iOS keeps the 64 nearest local notifications of an app; the Agenda is the only sender.
    public nonisolated static let defaultMaximumPending = 64

    private let center: any PlannedJourneyNotificationCenter
    private let content: @MainActor (PlannedJourneyReminderPlan) -> PlannedJourneyReminderContent
    private let maximumPending: Int
    private var lastRun: Task<PlannedJourneyReminderStatus, Never>?

    public init(
        center: any PlannedJourneyNotificationCenter,
        maximumPending: Int = PlannedJourneyReminderScheduler.defaultMaximumPending,
        content: @escaping @MainActor (PlannedJourneyReminderPlan) -> PlannedJourneyReminderContent
    ) {
        self.center = center
        self.maximumPending = max(0, maximumPending)
        self.content = content
    }

    /// The system's requests made those of `journeys` at `now`.
    public func reconcile(_ journeys: [PlannedJourney], now: Date) async -> PlannedJourneyReminderStatus {
        let previous = lastRun
        let run = Task { @MainActor [weak self] () -> PlannedJourneyReminderStatus in
            _ = await previous?.value
            guard let self else { return .unknown }
            return await self.performReconciliation(journeys, now: now)
        }
        lastRun = run
        return await run.value
    }

    /// After `journey` was saved among `journeys`: asks for permission the first time a reminder
    /// is chosen, then reconciles, and says what became of its reminder.
    public func scheduleAfterSaving(
        _ journey: PlannedJourney,
        journeys: [PlannedJourney],
        now: Date
    ) async -> (outcome: PlannedJourneyReminderOutcome, status: PlannedJourneyReminderStatus) {
        guard journey.reminderMinutes != nil else {
            return (.none, await reconcile(journeys, now: now))
        }
        guard PlannedJourneyReminderPlanner.plan(for: journey, now: now) != nil else {
            return (.tooLate, await reconcile(journeys, now: now))
        }
        if await center.permission() == .notDetermined {
            _ = await center.requestPermission()
        }
        let status = await reconcile(journeys, now: now)
        if status.permission != .allowed { return (.notAllowed, status) }
        return (status.scheduledJourneyIDs.contains(journey.id) ? .scheduled : .failed, status)
    }

    /// Before a journey is deleted or completed: its request and its notification go first.
    public func cancel(journeyID: String) {
        let identifier = PlannedJourneyReminderPlanner.identifier(journeyID: journeyID)
        center.removePendingRequests(identifiers: [identifier])
        center.removeDeliveredNotifications(identifiers: [identifier])
    }

    private func performReconciliation(_ journeys: [PlannedJourney], now: Date) async -> PlannedJourneyReminderStatus {
        let permission = await center.permission()
        let ours = await center.pendingRequests().filter { PlannedJourneyReminderPlanner.isAgendaReminder($0.identifier) }
        let wanted = permission == .allowed
            ? Array(PlannedJourneyReminderPlanner.plans(journeys, now: now).prefix(maximumPending))
            : []
        let wantedIdentifiers = Set(wanted.map(\.identifier))
        let stale = ours.map(\.identifier).filter { !wantedIdentifiers.contains($0) }
        if !stale.isEmpty {
            center.removePendingRequests(identifiers: stale)
        }
        var failed = false
        for plan in wanted {
            let words = content(plan)
            let request = PlannedJourneyNotificationRequest(
                identifier: plan.identifier, fireDate: plan.fireDate, title: words.title, body: words.body
            )
            if let current = ours.first(where: { $0.identifier == plan.identifier }), Self.isSame(current, as: request) {
                continue
            }
            do {
                try await center.add(request)
            } catch {
                failed = true
            }
        }
        // The line of each journey says what the system really holds.
        let journeyByIdentifier = Dictionary(wanted.map { ($0.identifier, $0.journeyID) }, uniquingKeysWith: { first, _ in first })
        let pending = await center.pendingRequests()
        let scheduled = Set(pending.compactMap { journeyByIdentifier[$0.identifier] })
        return PlannedJourneyReminderStatus(permission: permission, scheduledJourneyIDs: scheduled, failed: failed)
    }

    /// The same words and the same time to fire, within a second (the system keeps an interval).
    private static func isSame(_ current: PlannedJourneyNotificationRequest, as wanted: PlannedJourneyNotificationRequest) -> Bool {
        guard current.title == wanted.title, current.body == wanted.body,
              let currentDate = current.fireDate, let wantedDate = wanted.fireDate
        else { return false }
        return abs(currentDate.timeIntervalSince(wantedDate)) < 1
    }
}
