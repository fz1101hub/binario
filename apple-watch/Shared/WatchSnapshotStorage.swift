import Foundation

enum WatchSnapshotStorage {
    static let appGroup = "group.it.binario.watch"
    private static let snapshotKey = "binario_watch_snapshot_v1"

    static func load() -> BinarioWatchSnapshot {
        loadStored() ?? .unavailable
    }

    /// The snapshot the iPhone sent, nil when there is none or it is no longer valid.
    static func loadStored() -> BinarioWatchSnapshot? {
        guard let data = UserDefaults(suiteName: appGroup)?.data(forKey: snapshotKey) else { return nil }
        return BinarioWatchSnapshot.decodeContract(data)
    }

    static func save(_ snapshot: BinarioWatchSnapshot) throws {
        guard snapshot.isCompatible else {
            throw CocoaError(.validationMissingMandatoryProperty)
        }
        let data = try JSONEncoder().encode(snapshot)
        UserDefaults(suiteName: appGroup)?.set(data, forKey: snapshotKey)
    }

    static func clear() {
        UserDefaults(suiteName: appGroup)?.removeObject(forKey: snapshotKey)
    }
}
