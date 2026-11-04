import SwiftUI
import BinarioKit

@main
struct BinarioApp: App {
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
