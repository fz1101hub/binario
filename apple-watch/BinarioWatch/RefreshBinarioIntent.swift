import AppIntents
import WidgetKit

enum RefreshBinarioAction {
    static func requestTimelineReload(
        loadSnapshot: () -> BinarioWatchSnapshot,
        reloadTimeline: (String) -> Void
    ) {
        _ = loadSnapshot()
        reloadTimeline(BinarioWatchWidgetConfiguration.kind)
    }
}

struct RefreshBinarioIntent: AppIntent {
    static var title: LocalizedStringResource = "Aggiorna Binario"
    static var description = IntentDescription("Rileggere lo snapshot ferroviario già disponibile sul dispositivo.")

    func perform() async throws -> some IntentResult {
        RefreshBinarioAction.requestTimelineReload(
            loadSnapshot: { WatchSnapshotStorage.load() },
            reloadTimeline: { kind in WidgetCenter.shared.reloadTimelines(ofKind: kind) }
        )
        return .result()
    }
}
