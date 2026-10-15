import WidgetKit
import SwiftUI

struct BinarioTimelineEntry: TimelineEntry {
    let date: Date
    let snapshot: BinarioWatchSnapshot
}

struct BinarioTimelineProvider: TimelineProvider {
    func placeholder(in context: Context) -> BinarioTimelineEntry {
        BinarioTimelineEntry(date: .now, snapshot: .unavailable)
    }

    func getSnapshot(in context: Context, completion: @escaping (BinarioTimelineEntry) -> Void) {
        completion(BinarioTimelineEntry(date: .now, snapshot: WatchSnapshotStorage.load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<BinarioTimelineEntry>) -> Void) {
        let entry = BinarioTimelineEntry(date: .now, snapshot: WatchSnapshotStorage.load())
        completion(Timeline(entries: [entry], policy: .after(.now.addingTimeInterval(15 * 60))))
    }
}

struct BinarioWatchWidget: Widget {
    let kind = BinarioWatchWidgetConfiguration.kind

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: BinarioTimelineProvider()) { entry in
            BinarioWatchWidgetView(entry: entry)
        }
        .configurationDisplayName("watch.widget.displayName")
        .description("watch.widget.description")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

private struct BinarioWatchWidgetView: View {
    let entry: BinarioTimelineEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        let next = entry.snapshot.services.first
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    Text(next?.expectedDeparture ?? "—").font(.headline)
                    Text(next?.number ?? WatchLocalization.text("watch.widget.compactName")).font(.caption2)
                }
            }
        case .accessoryInline:
            Text(next.map { "\($0.expectedDeparture) · \($0.destination)" } ?? WatchLocalization.text("watch.widget.noData"))
        default:
            VStack(alignment: .leading) {
                Text(entry.snapshot.displayRouteLabel).lineLimit(1)
                Text(next.map { "\($0.expectedDeparture)  \($0.destination)" } ?? WatchLocalization.text("watch.widget.noData"))
                    .font(.headline)
                    .lineLimit(1)
            }
        }
    }
}

@main
struct BinarioWatchWidgets: WidgetBundle {
    var body: some Widget {
        BinarioWatchWidget()
    }
}
