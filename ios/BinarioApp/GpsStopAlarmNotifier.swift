import Foundation
import UserNotifications
import BinarioKit
#if canImport(CoreLocation)
import CoreLocation
#endif

/// Android's GPS stop alarm («Avvisami anche in base alla posizione») the iOS way, by the owner's rule (the iPhone
/// equals Android, November 2026): one local notification with a `UNLocationNotificationTrigger` on entering a circle
/// around the stop, from the bundled catalogue's coordinates and the radius chosen (BinarioKit's GpsStopAlarm.swift).
/// iOS watches the circle, also with the app closed, and shows the notification once; Binario never receives a
/// position. The trigger needs the When-In-Use authorization with precise location, asked only when the user turns
/// the alarm on: no Always authorization, no region monitoring or location updates of the app's own, no background
/// mode.
enum GpsStopAlarmNotifier {
    static let notificationID = "it.binario.ios.trip.gps-alarm"

    /// Hands the circle to iOS, replacing the previous one; true when iOS accepted it.
    static func schedule(_ alarm: GpsStopAlarm) async -> Bool {
        #if canImport(CoreLocation)
        let center = CLLocationCoordinate2D(latitude: alarm.latitude, longitude: alarm.longitude)
        let region = CLCircularRegion(center: center, radius: CLLocationDistance(alarm.radiusMeters), identifier: notificationID)
        region.notifyOnEntry = true
        region.notifyOnExit = false
        let content = UNMutableNotificationContent()
        content.title = TripTexts.format("notices.alerts.gps_title", alarm.station)
        content.body = TripTexts.format("notices.alerts.gps_body", alarm.trainNumber, alarm.destination, alarm.station, alarm.expectedTime)
        content.sound = .default
        content.threadIdentifier = notificationID
        let trigger = UNLocationNotificationTrigger(region: region, repeats: false)
        do {
            try await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: notificationID, content: content, trigger: trigger))
            return true
        } catch {
            return false
        }
        #else
        return false
        #endif
    }

    static func remove() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [notificationID])
    }

    /// Whether iOS still watches the circle: false once the notification was shown or removed.
    static func isPending() async -> Bool {
        let requests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        return requests.contains { $0.identifier == notificationID }
    }
}

/// The location authorization the trigger needs.
enum GpsStopAlarmAccess {
    case allowed
    /// Approximate location only: iOS does not watch areas without the precise one.
    case preciseRequired
    case denied
}

/// Asks the When-In-Use authorization, only when the user turns the GPS stop alarm on. The manager is used for the
/// authorization alone: it never starts location updates or region monitoring.
@MainActor
final class GpsStopAlarmAuthorization: NSObject {
    private var continuation: CheckedContinuation<Void, Never>?
    #if canImport(CoreLocation)
    private let manager = CLLocationManager()
    #endif

    override init() {
        super.init()
        #if canImport(CoreLocation)
        manager.delegate = self
        #endif
    }

    func authorize() async -> GpsStopAlarmAccess {
        #if canImport(CoreLocation)
        if manager.authorizationStatus == .notDetermined, continuation == nil {
            await withCheckedContinuation { continuation in
                self.continuation = continuation
                // The answer arrives in locationManagerDidChangeAuthorization.
                manager.requestWhenInUseAuthorization()
            }
        }
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            return manager.accuracyAuthorization == .fullAccuracy ? .allowed : .preciseRequired
        default:
            return .denied
        }
        #else
        return .denied
        #endif
    }

    #if canImport(CoreLocation)
    fileprivate func authorizationChanged(_ status: CLAuthorizationStatus) {
        // Told once when the manager is made, before the user answers.
        guard status != .notDetermined, let waiting = continuation else { return }
        continuation = nil
        waiting.resume()
    }
    #endif
}

#if canImport(CoreLocation)
extension GpsStopAlarmAuthorization: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor [weak self] in
            self?.authorizationChanged(status)
        }
    }
}
#endif

/// The texts of the GPS stop alarm, Android's in the 17 languages.
enum GpsStopAlarmTexts {
    /// «2 km».
    static func radius(_ meters: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.trip.gps_radius_km", comment: ""), meters / 1_000)
    }

    /// «L’avviso in base alla posizione è disponibile solo da 8 ore prima dell’arrivo previsto…».
    static func tooEarly() -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.trip.gps_too_early", comment: ""), GpsStopAlarmPolicy.maximumDurationHours)
    }
}
