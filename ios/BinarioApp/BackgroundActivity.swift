import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Time to finish work the Watch asked for while the iPhone app runs in the background: Watch
/// Connectivity wakes the app for «Aggiorna» (or when the Watch app opens with nothing to show),
/// and searching the local index for the route and sending the new context must not be cut short.
/// No background mode is declared and nothing runs on a schedule.
@MainActor
protocol BackgroundActivityRunning: AnyObject {
    /// Starts an activity; the returned closure ends it.
    func begin(name: String) -> @MainActor () -> Void
}

#if canImport(UIKit)
@MainActor
final class UIKitBackgroundActivity: BackgroundActivityRunning {
    func begin(name: String) -> @MainActor () -> Void {
        let task = BackgroundTask()
        task.identifier = UIApplication.shared.beginBackgroundTask(withName: name) {
            // The system's time is up: end the task before the app is suspended.
            MainActor.assumeIsolated { task.end() }
        }
        return { task.end() }
    }
}

@MainActor
private final class BackgroundTask {
    var identifier: UIBackgroundTaskIdentifier = .invalid

    func end() {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        identifier = .invalid
    }
}
#endif
