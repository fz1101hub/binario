import SwiftUI
import BinarioKit
#if canImport(UIKit)
import UIKit
#endif

@main
struct BinarioApp: App {
    #if canImport(UIKit)
    /// Receives the Home-screen quick action «Agenda».
    @UIApplicationDelegateAdaptor(BinarioAppDelegate.self) private var appDelegate
    #endif
    @StateObject private var model: AppModel

    init() {
        // Built at launch, not with the first window: Watch Connectivity also launches the app in
        // the background, without a scene, to deliver «Aggiorna» from the Watch, and the session
        // must be active by then to answer with the route the Watch shows (or the pinned one).
        let model = AppModel.bootstrap()
        _model = StateObject(wrappedValue: model)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
        }
    }
}

/// The Home-screen quick action «Agenda» (`UIApplicationShortcutItems` in Binario-Info.plist): it
/// opens the Agenda page of «Tratte». A launch by the action is kept until the first screen is up.
@MainActor
enum AgendaQuickAction {
    static let type = "it.binario.ios.agenda"
    static let notification = Notification.Name("it.binario.ios.agenda-quick-action")
    /// Asked before the screen could follow (a launch by the action).
    private(set) static var pending = false

    #if canImport(UIKit)
    /// True when the item is the Agenda's.
    static func handle(_ item: UIApplicationShortcutItem) -> Bool {
        guard item.type == type else { return false }
        pending = true
        NotificationCenter.default.post(name: notification, object: nil)
        return true
    }
    #endif

    /// Whether the Agenda was asked for since the last call.
    static func consume() -> Bool {
        defer { pending = false }
        return pending
    }
}

#if canImport(UIKit)
/// The quick action of a launch arrives with the scene's connection, a later one through the scene.
final class BinarioAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        if let item = options.shortcutItem {
            _ = AgendaQuickAction.handle(item)
        }
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = BinarioSceneDelegate.self
        return configuration
    }
}

final class BinarioSceneDelegate: NSObject, UIWindowSceneDelegate {
    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem) async -> Bool {
        AgendaQuickAction.handle(shortcutItem)
    }
}
#endif
