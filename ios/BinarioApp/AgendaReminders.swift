import Foundation
import BinarioKit

#if canImport(UserNotifications)
import UserNotifications

/// `UNUserNotificationCenter` for the Agenda's reminders: local requests only, each with a time
/// interval trigger (an instant, so the phone's zone does not move it). Nothing is registered for
/// push and no entitlement is needed; the request holds the station names and the Italian time.
@MainActor
final class SystemAgendaNotificationCenter: PlannedJourneyNotificationCenter {
    /// Shows a reminder that fires while Binario is open; the centre keeps its delegate weakly.
    private static let presenter = AgendaNotificationPresenter()

    private let center: UNUserNotificationCenter

    init(center: UNUserNotificationCenter = .current()) {
        self.center = center
        center.delegate = Self.presenter
    }

    func permission() async -> PlannedJourneyNotificationPermission {
        let center = self.center
        let status: UNAuthorizationStatus = await withCheckedContinuation { continuation in
            center.getNotificationSettings { settings in
                continuation.resume(returning: settings.authorizationStatus)
            }
        }
        switch status {
        case .authorized, .provisional, .ephemeral: return .allowed
        case .notDetermined: return .notDetermined
        case .denied: return .denied
        @unknown default: return .denied
        }
    }

    func requestPermission() async -> Bool {
        let center = self.center
        return await withCheckedContinuation { continuation in
            center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
                continuation.resume(returning: granted)
            }
        }
    }

    func pendingRequests() async -> [PlannedJourneyNotificationRequest] {
        let center = self.center
        return await withCheckedContinuation { continuation in
            center.getPendingNotificationRequests { requests in
                let pending = requests.map { request in
                    PlannedJourneyNotificationRequest(
                        identifier: request.identifier,
                        fireDate: (request.trigger as? UNTimeIntervalNotificationTrigger)?.nextTriggerDate(),
                        title: request.content.title,
                        body: request.content.body
                    )
                }
                continuation.resume(returning: pending)
            }
        }
    }

    func add(_ request: PlannedJourneyNotificationRequest) async throws {
        // A trigger needs a positive interval: a time already reached is not scheduled.
        guard let fireDate = request.fireDate, fireDate.timeIntervalSinceNow >= 1 else {
            throw AgendaNotificationError.timeReached
        }
        let content = UNMutableNotificationContent()
        content.title = request.title
        content.body = request.body
        content.sound = .default
        content.threadIdentifier = PlannedJourneyReminderPlanner.identifierPrefix
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: fireDate.timeIntervalSinceNow, repeats: false)
        let notification = UNNotificationRequest(identifier: request.identifier, content: content, trigger: trigger)
        let center = self.center
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
            center.add(notification) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    func removePendingRequests(identifiers: [String]) {
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
    }

    func removeDeliveredNotifications(identifiers: [String]) {
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
    }
}

/// Without a delegate iOS hides a notification that arrives while the app is in the foreground.
final class AgendaNotificationPresenter: NSObject, UNUserNotificationCenterDelegate, Sendable {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}

private enum AgendaNotificationError: Error {
    case timeReached
}
#endif

/// The words of a reminder in the user's language: the stored Italian time and the validated
/// station names, never the free label.
enum AgendaReminderText {
    static func content(_ plan: PlannedJourneyReminderPlan) -> PlannedJourneyReminderContent {
        PlannedJourneyReminderContent(
            title: NSLocalizedString("saved.agenda.reminder.notification_title", comment: ""),
            body: String.localizedStringWithFormat(
                NSLocalizedString("saved.agenda.reminder.notification_body", comment: ""),
                plan.departureTime.hhmm, plan.origin, plan.destination
            )
        )
    }
}
