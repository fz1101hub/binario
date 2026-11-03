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
    static var title: LocalizedStringResource {
        "watch.intent.refresh.title"
    }

    static var description: IntentDescription {
        IntentDescription("watch.intent.refresh.description")
    }

    func perform() async throws -> some IntentResult {
        RefreshBinarioAction.requestTimelineReload(
            loadSnapshot: { WatchSnapshotStorage.load() },
            reloadTimeline: { kind in WidgetCenter.shared.reloadTimelines(ofKind: kind) }
        )
        return .result()
    }
}
