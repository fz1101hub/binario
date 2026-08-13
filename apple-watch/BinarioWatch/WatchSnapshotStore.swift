import Foundation
import WidgetKit

@MainActor
final class WatchSnapshotStore: ObservableObject {
    @Published private(set) var snapshot: BinarioWatchSnapshot

    init(snapshot: BinarioWatchSnapshot = WatchSnapshotStorage.load()) {
        self.snapshot = snapshot
    }

    func refreshFromCache() {
        snapshot = WatchSnapshotStorage.load()
    }

    func accept(snapshot: BinarioWatchSnapshot) {
        guard snapshot.isCompatible else {
            self.snapshot = .unavailable
            return
        }
        do {
            try WatchSnapshotStorage.save(snapshot)
            self.snapshot = snapshot
            WidgetCenter.shared.reloadAllTimelines()
        } catch {
            self.snapshot = .unavailable
        }
    }
}
