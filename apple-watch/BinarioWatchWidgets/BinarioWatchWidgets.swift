import WidgetKit
import SwiftUI

struct BinarioTimelineEntry: TimelineEntry {
    let date: Date
    let snapshot: BinarioWatchSnapshot
    /// When the train shown first departs, for the countdown.
    let nextDeparture: Date?

    init(date: Date, snapshot: BinarioWatchSnapshot) {
        self.date = date
        self.snapshot = snapshot
        nextDeparture = snapshot.nextDeparture
    }
}

struct BinarioTimelineProvider: TimelineProvider {
    func placeholder(in context: Context) -> BinarioTimelineEntry {
        BinarioTimelineEntry(date: .now, snapshot: .unavailable)
    }

    func getSnapshot(in context: Context, completion: @escaping (BinarioTimelineEntry) -> Void) {
        let now = Date()
        completion(BinarioTimelineEntry(date: now, snapshot: WatchSnapshotStorage.load().revalidated(at: now)))
    }

    /// One entry now, one at each listed departure (the next train takes over) and one at the
    /// expiry (unavailable); WidgetKit asks again once the last entry is reached. A new snapshot
    /// from the iPhone reloads the timeline straight away (WatchSnapshotStore.accept).
    func getTimeline(in context: Context, completion: @escaping (Timeline<BinarioTimelineEntry>) -> Void) {
        let entries = WatchSnapshotStorage.load()
            .timeline(from: Date())
            .map { BinarioTimelineEntry(date: $0.date, snapshot: $0.snapshot) }
        completion(Timeline(entries: entries, policy: .atEnd))
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
        // watchOS 10 replaces a widget without a container background with a placeholder.
        familyContent
            .containerBackground(for: .widget) { widgetBackground }
    }

    @ViewBuilder
    private var widgetBackground: some View {
        if family == .accessoryCircular {
            AccessoryWidgetBackground()
        } else {
            Color.clear
        }
    }

    @ViewBuilder
    private var familyContent: some View {
        let next = entry.snapshot.services.first
        switch family {
        case .accessoryCircular:
            VStack(spacing: 0) {
                Text(next?.expectedDeparture ?? "—").font(.headline)
                Text(next?.number ?? WatchLocalization.text("watch.widget.compactName")).font(.caption2)
            }
        case .accessoryInline:
            Text(next.map { "\($0.expectedDeparture) · \($0.destination)" } ?? WatchLocalization.text("watch.widget.noData"))
        default:
            VStack(alignment: .leading) {
                Text(entry.snapshot.displayRouteLabel).lineLimit(1)
                Text(next.map { "\($0.expectedDeparture)  \($0.destination)" } ?? WatchLocalization.text("watch.widget.noData"))
                    .font(.headline)
                    .lineLimit(1)
                if next != nil, let departure = entry.nextDeparture {
                    // The system keeps the relative text counting down between timeline entries.
                    Text("watch.countdown.departsIn \(Text(departure, style: .relative))")
                        .font(.caption)
                        .lineLimit(1)
                }
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
