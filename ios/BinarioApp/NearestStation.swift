import SwiftUI
import BinarioKit
#if canImport(CoreLocation)
import CoreLocation
#endif

/// «Stazione più vicina» (EXPO_PORT_PLAN I7, F4, R20): on a tap, and only then, the When-In-Use
/// authorization is asked if it never was, one position is read with a single `requestLocation()`,
/// and the nearest station the search offers is chosen on the iPhone (Android's rules, 50 km at
/// most). The position is passed to that computation and kept nowhere: not stored, not sent, not
/// shown.
struct NearestStationButton: View {
    @EnvironmentObject private var model: AppModel
    let titleKey: LocalizedStringKey
    /// The message once a station is chosen, its name as `%@`.
    let foundKey: String
    @Binding var selection: Station?

    @State private var locator: OneShotLocation?
    @State private var locating = false
    @State private var message: String?

    var body: some View {
        Button(action: locate) {
            if locating {
                HStack {
                    ProgressView()
                    Text("planner.nearest.locating")
                }
            } else {
                Label(titleKey, systemImage: "location")
            }
        }
        .disabled(locating)
        .accessibilityHint(Text("planner.nearest.hint"))
        if let message {
            Text(message)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    private func locate() {
        guard !locating else { return }
        locating = true
        message = nil
        let locator = self.locator ?? OneShotLocation()
        self.locator = locator
        Task {
            let text: String
            switch await locator.locate() {
            case .denied:
                text = NSLocalizedString("planner.nearest.denied", comment: "")
            case .unavailable:
                text = NSLocalizedString("planner.nearest.unavailable", comment: "")
            case let .located(latitude, longitude):
                if let nearest = await model.nearestStation(latitude: latitude, longitude: longitude) {
                    selection = nearest.station
                    text = String.localizedStringWithFormat(NSLocalizedString(foundKey, comment: ""), nearest.station.name)
                } else {
                    text = NSLocalizedString("planner.nearest.none", comment: "")
                }
            }
            locating = false
            message = text
            DeparturesTexts.announce(text)
        }
    }
}

/// What one position request gave.
enum OneShotLocationOutcome: Sendable {
    case located(latitude: Double, longitude: Double)
    /// The user refused, or location services are off or restricted.
    case denied
    case unavailable
}

/// One foreground position for one tap, behind CoreLocation: When-In-Use authorization asked only
/// while a request waits for it, a single `requestLocation()`, never continuous updates or
/// background use. One request at a time.
@MainActor
final class OneShotLocation: NSObject {
    #if canImport(CoreLocation)
    private let manager = CLLocationManager()
    #endif
    private var continuation: CheckedContinuation<OneShotLocationOutcome, Never>?
    /// The position of the waiting request was asked for: the authorization the manager reports when
    /// it is made must not ask a second time.
    private var positionRequested = false

    override init() {
        super.init()
        #if canImport(CoreLocation)
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        #endif
    }

    func locate() async -> OneShotLocationOutcome {
        guard continuation == nil else { return .unavailable }
        #if canImport(CoreLocation)
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            switch manager.authorizationStatus {
            case .notDetermined:
                // The answer arrives in locationManagerDidChangeAuthorization.
                manager.requestWhenInUseAuthorization()
            case .authorizedWhenInUse, .authorizedAlways:
                requestPosition()
            default:
                finish(.denied)
            }
        }
        #else
        return .unavailable
        #endif
    }

    private func finish(_ outcome: OneShotLocationOutcome) {
        let waiting = continuation
        continuation = nil
        positionRequested = false
        waiting?.resume(returning: outcome)
    }

    #if canImport(CoreLocation)
    private func requestPosition() {
        guard !positionRequested else { return }
        positionRequested = true
        manager.requestLocation()
    }

    private func authorizationChanged(_ status: CLAuthorizationStatus) {
        guard continuation != nil else { return }
        switch status {
        case .notDetermined:
            // Told once when the manager is made, before the user answers.
            return
        case .authorizedWhenInUse, .authorizedAlways:
            requestPosition()
        default:
            finish(.denied)
        }
    }
    #endif
}

#if canImport(CoreLocation)
extension OneShotLocation: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor [weak self] in
            self?.authorizationChanged(status)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let latitude = locations.last?.coordinate.latitude
        let longitude = locations.last?.coordinate.longitude
        Task { @MainActor [weak self] in
            if let latitude, let longitude {
                self?.finish(.located(latitude: latitude, longitude: longitude))
            } else {
                self?.finish(.unavailable)
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let denied = (error as? CLError)?.code == .denied
        Task { @MainActor [weak self] in
            self?.finish(denied ? .denied : .unavailable)
        }
    }
}
#endif
