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
        .configurationDisplayName("Binario")
        .description("Mostra la prossima corsa e lo stato del dato disponibile.")
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
            AccessoryWidgetBackground {
                VStack(spacing: 0) {
                    Text(next?.expectedDeparture ?? "—").font(.headline)
                    Text(next?.number ?? "Binario").font(.caption2)
                }
            }
        case .accessoryInline:
            Text(next.map { "\($0.expectedDeparture) · \($0.destination)" } ?? "Dati non disponibili")
        default:
            VStack(alignment: .leading) {
                Text(entry.snapshot.routeLabel).lineLimit(1)
                Text(next.map { "\($0.expectedDeparture)  \($0.destination)" } ?? "Dati non disponibili")
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
