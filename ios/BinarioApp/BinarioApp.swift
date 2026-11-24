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
    /// The notice sections; without the bundled table of the notice sources, none is shown.
    @StateObject private var notices: RailNoticesModel
    /// Weather at the destination, bike sharing and the switches of the evening alerts.
    @StateObject private var extras: ExtrasModel
    /// «Orari salvati», favourite stations, hidden train types, names of «I miei treni», the
    /// personal punctuality and the theme (PersonalModel.swift).
    @StateObject private var personal: PersonalModel
    /// The journeys with changes of the planner and the travel preferences.
    @StateObject private var journeys = JourneysModel()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Built at launch, not with the first window: Watch Connectivity also launches the app in
        // the background, without a scene, to deliver «Aggiorna» from the Watch, and the session
        // must be active by then to answer with the route the Watch shows (or the pinned one).
        let model = AppModel.bootstrap()
        _model = StateObject(wrappedValue: model)
        _notices = StateObject(wrappedValue: RailNoticesModel.bootstrap())
        _extras = StateObject(wrappedValue: ExtrasModel())
        let personal = PersonalModel()
        _personal = StateObject(wrappedValue: personal)
        // The favourite trains' background jobs read the live sources and the punctuality history
        // of these two models (EveningAlerts.swift).
        EveningAlerts.attach(model: model, personal: personal)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .modifier(AppThemeModifier())
                // Android's `binario://` links and the quick actions besides «Agenda» (RouteBoard.swift).
                .modifier(AppLinksHandler())
                .environmentObject(model)
                .environmentObject(notices)
                .environmentObject(extras)
                .environmentObject(personal)
                // The trip notifications and alarms (TripMonitor.swift), made at launch by AppModel.bootstrap.
                .environmentObject(TripMonitor.shared)
                .environmentObject(journeys)
        }
        // The evening alerts' one background app refresh task (EveningAlerts.swift): planned again
        // each time the app leaves the screen, run by iOS from the hour asked for.
        .backgroundTask(.appRefresh(EveningAlerts.taskIdentifier)) {
            await EveningAlerts.run()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { EveningAlerts.schedule() }
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
        if let item = options.shortcutItem, !AgendaQuickAction.handle(item) {
            _ = AppLinks.handle(item)
        }
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = BinarioSceneDelegate.self
        return configuration
    }
}

final class BinarioSceneDelegate: NSObject, UIWindowSceneDelegate {
    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem) async -> Bool {
        if AgendaQuickAction.handle(shortcutItem) { return true }
        return AppLinks.handle(shortcutItem)
    }
}
#endif
