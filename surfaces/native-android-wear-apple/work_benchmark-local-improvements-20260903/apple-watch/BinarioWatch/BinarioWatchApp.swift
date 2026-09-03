import SwiftUI

@main
struct BinarioWatchApp: App {
    @StateObject private var snapshotStore: WatchSnapshotStore
    private let connectivity: PhoneConnectivity

    init() {
        let store = WatchSnapshotStore()
        _snapshotStore = StateObject(wrappedValue: store)
        connectivity = PhoneConnectivity(store: store)
    }

    var body: some Scene {
        WindowGroup {
            WatchHomeView()
                .environmentObject(snapshotStore)
                .onAppear { connectivity.activate() }
        }
    }
}
