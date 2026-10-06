import SwiftUI

@main
struct BinarioWatchApp: App {
    @StateObject private var snapshotStore: WatchSnapshotStore
    private let connectivity: PhoneConnectivity

    init() {
        let store = WatchSnapshotStore()
        _snapshotStore = StateObject(wrappedValue: store)
        connectivity = PhoneConnectivity(store: store)
        // Activate at launch, not when the window appears: the system also launches the app in
        // the background to deliver application contexts, and a revocation must reach the
        // store and the widgets without the user opening the app.
        connectivity.activate()
    }

    var body: some Scene {
        WindowGroup {
            WatchHomeView()
                .environmentObject(snapshotStore)
        }
    }
}
