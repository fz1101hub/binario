import Foundation
import BinarioKit
#if canImport(CoreLocation)
import CoreLocation
#endif

/// The position during the followed trip («Stima dalla tua posizione» and «Scendi tra N fermate» by position):
/// precise When-In-Use location, asked only when the user turns the estimate on, updated only while the app is in the
/// foreground and the trip is followed. No Always authorization and no background location: iOS stops the updates when
/// the app leaves the screen. Each position goes to BinarioKit's GpsTrainTracker and nowhere else; none is stored or
/// sent.
@MainActor
final class TripLocationSession: NSObject {
    enum Outcome {
        case started
        /// Approximate location only: the estimate needs the precise one.
        case preciseRequired
        case denied
    }

    var onTrack: (@MainActor (GpsTrainTrack) -> Void)?
    private(set) var isRunning = false
    private var tracker: GpsTrainTracker?
    #if canImport(CoreLocation)
    private let manager = CLLocationManager()
    #endif

    override init() {
        super.init()
        #if canImport(CoreLocation)
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 50
        manager.activityType = .otherNavigation
        manager.pausesLocationUpdatesAutomatically = true
        #endif
    }

    func start(route: GpsTrainRoute) -> Outcome {
        tracker = GpsTrainTracker(route: route)
        #if canImport(CoreLocation)
        switch manager.authorizationStatus {
        case .notDetermined:
            // The updates start when the answer arrives (locationManagerDidChangeAuthorization).
            isRunning = true
            manager.requestWhenInUseAuthorization()
            return .started
        case .authorizedWhenInUse, .authorizedAlways:
            guard manager.accuracyAuthorization == .fullAccuracy else { return .preciseRequired }
            isRunning = true
            manager.startUpdatingLocation()
            return .started
        default:
            return .denied
        }
        #else
        return .denied
        #endif
    }

    func stop() {
        isRunning = false
        tracker = nil
        #if canImport(CoreLocation)
        manager.stopUpdatingLocation()
        #endif
    }

    #if canImport(CoreLocation)
    fileprivate func authorizationChanged(_ status: CLAuthorizationStatus, precise: Bool) {
        guard isRunning else { return }
        switch status {
        case .authorizedWhenInUse, .authorizedAlways:
            if precise { manager.startUpdatingLocation() } else { stop() }
        case .notDetermined:
            return
        default:
            stop()
        }
    }

    fileprivate func received(_ fix: GpsFix) {
        guard isRunning, let tracker else { return }
        onTrack?(tracker.onFix(fix, now: Date()))
    }
    #endif
}

#if canImport(CoreLocation)
extension TripLocationSession: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        let precise = manager.accuracyAuthorization == .fullAccuracy
        Task { @MainActor [weak self] in
            self?.authorizationChanged(status, precise: precise)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        let fix = GpsFix(
            latitude: last.coordinate.latitude,
            longitude: last.coordinate.longitude,
            accuracyMeters: last.horizontalAccuracy >= 0 ? last.horizontalAccuracy : nil,
            age: max(0, -last.timestamp.timeIntervalSinceNow)
        )
        Task { @MainActor [weak self] in
            self?.received(fix)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}
#endif
