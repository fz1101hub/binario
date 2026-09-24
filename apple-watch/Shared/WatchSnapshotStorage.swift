import Foundation

enum WatchSnapshotStorage {
    static let appGroup = "group.it.binario.watch"
    private static let snapshotKey = "binario_watch_snapshot_v1"

    static func load() -> BinarioWatchSnapshot {
        guard let data = UserDefaults(suiteName: appGroup)?.data(forKey: snapshotKey),
              let snapshot = BinarioWatchSnapshot.decodeContract(data) else {
            return .unavailable
        }
        return snapshot
    }

    static func save(_ snapshot: BinarioWatchSnapshot) throws {
        guard snapshot.isCompatible else {
            throw CocoaError(.validationMissingMandatoryProperty)
        }
        let data = try JSONEncoder().encode(snapshot)
        UserDefaults(suiteName: appGroup)?.set(data, forKey: snapshotKey)
    }
}
