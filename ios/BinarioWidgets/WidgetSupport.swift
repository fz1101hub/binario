import BinarioKit
import Foundation
import SwiftUI
import WidgetKit

// What the three widgets share: their texts, the catalogue and the sources they read, the links into
// the app, the page arithmetic and the reload policy of a timeline.

/// The widget texts (Resources/<language>.lproj, Android's texts: tools/ios_widget_strings.py).
enum WidgetText {
    static func string(_ key: String) -> String {
        NSLocalizedString(key, comment: "")
    }

    static func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: string(key), locale: Locale.current, arguments: arguments)
    }

    static func plural(_ key: String, _ count: Int) -> String {
        String.localizedStringWithFormat(string(key), count)
    }

    /// «Aggiornato alle HH:mm» (Italian time, as every railway time of the app), or «Mai aggiornato».
    static func updated(_ date: Date?) -> String {
        guard let date else { return string("widgets.updated.never") }
        return format("widgets.updated", WidgetClock.hhmm(date))
    }

    static func freshness(_ value: WidgetFreshness) -> String {
        switch value {
        case .fresh: return string("widgets.freshness.fresh")
        case .aging: return string("widgets.freshness.aging")
        case .stale: return string("widgets.freshness.stale")
        case .unavailable: return string("widgets.freshness.unavailable")
        }
    }

    /// «BIN 7», «BIN 7 era 5» when the source moved the train, «—» without a platform.
    static func platform(_ shown: String?, scheduled: String?) -> (text: String, changed: Bool) {
        guard let shown = shown?.trimmingCharacters(in: .whitespaces), !shown.isEmpty else { return ("—", false) }
        if let scheduled = scheduled?.trimmingCharacters(in: .whitespaces), !scheduled.isEmpty, scheduled != shown {
            return (format("widgets.platform.changed", shown, scheduled), true)
        }
        return (format("widgets.platform", shown), false)
    }

    /// What VoiceOver reads of a platform.
    static func spokenPlatform(_ shown: String?, scheduled: String?) -> String {
        guard let shown = shown?.trimmingCharacters(in: .whitespaces), !shown.isEmpty else {
            return string("widgets.a11y.platform_unavailable")
        }
        if let scheduled = scheduled?.trimmingCharacters(in: .whitespaces), !scheduled.isEmpty, scheduled != shown {
            return format("widgets.a11y.platform_changed", shown, scheduled)
        }
        return format("widgets.a11y.platform", shown)
    }
}

/// The colours of a row's state: the system's, which keep their contrast in light and dark mode.
enum WidgetEmphasis {
    case normal, early, warning, error, muted

    var color: Color {
        switch self {
        case .normal: return .primary
        case .early: return .green
        case .warning: return .orange
        case .error: return .red
        case .muted: return .secondary
        }
    }
}

/// The offline station catalogue the app ships, read once per process.
actor WidgetCatalog {
    static let shared = WidgetCatalog()

    private var loaded: CuratedStationCatalog?

    func catalog() -> CuratedStationCatalog? {
        if let loaded { return loaded }
        guard let url = Bundle.main.url(forResource: "curated_it_rail_stations", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let catalog = try? CuratedStationCatalog(data: data) else { return nil }
        loaded = catalog
        return catalog
    }

    /// The catalogue entry of `identifier`; nil when the catalogue does not have it.
    func station(id identifier: String) -> Station? {
        catalog()?.station(id: identifier)
    }
}

/// The sources of the widgets: the app's bundled timetable index (installed by the app in the App
/// Group) and, with «Dati in tempo reale» on, the app's live sources.
enum WidgetSources {
    /// One for the extension's process: its sources share their gates (one request per resource every
    /// 30 seconds, a pause at the first refusal). The train notices are never asked by a widget.
    static let live: LiveRailData = LiveRailData.standard(noticeRules: nil)

    /// The index the app installed, or nil before the app has run once with this version.
    static func repository(_ store: WidgetSharedStore?) -> SQLiteGTFSJourneyRepository? {
        guard let name = store?.scheduleFileName, let directory = WidgetAppGroup.scheduleDirectory() else { return nil }
        let url = directory.appendingPathComponent(name, isDirectory: false)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return SQLiteGTFSJourneyRepository(databaseURL: url)
    }

    /// The live sources when «Dati in tempo reale» is on, as the app's switch says.
    static func liveIfEnabled(_ store: WidgetSharedStore?) -> LiveRailData? {
        guard store?.liveDataEnabled ?? false else { return nil }
        return live
    }
}

/// Links into the app: a tap opens what the row shows, «📍 Più vicina» asks the app for one position.
enum WidgetLinks {
    static let scheme = "binario"

    static func route(origin: String, destination: String) -> URL? {
        url("route", [URLQueryItem(name: "origin", value: origin), URLQueryItem(name: "destination", value: destination)])
    }

    static func station(_ identifier: String) -> URL? {
        url("station", [URLQueryItem(name: "id", value: identifier)])
    }

    static func train(_ identifier: String) -> URL? {
        url("train", [URLQueryItem(name: "id", value: identifier)])
    }

    static func trains() -> URL? {
        url("trains", [])
    }

    static func nearest(configuredStation identifier: String) -> URL? {
        url("nearest", [URLQueryItem(name: "station", value: identifier)])
    }

    private static func url(_ path: String, _ items: [URLQueryItem]) -> URL? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = "widgets"
        components.path = "/" + path
        components.queryItems = items.isEmpty ? nil : items
        return components.url
    }
}

/// Android's `WidgetRowPagination`: the stored position is a row offset, so a resize never skips runs.
enum WidgetPages {
    static func maxOffset(count: Int, rows: Int) -> Int {
        guard count > 0 else { return 0 }
        let size = max(rows, 1)
        return ((count - 1) / size) * size
    }

    static func normalized(_ offset: Int, count: Int, rows: Int) -> Int {
        min(max(offset, 0), maxOffset(count: count, rows: rows))
    }

    static func moved(_ offset: Int, by pages: Int, count: Int, rows: Int) -> Int {
        normalized(offset + pages * max(rows, 1), count: count, rows: rows)
    }

    static func number(_ offset: Int, rows: Int) -> Int {
        offset / max(rows, 1) + 1
    }

    static func count(_ count: Int, rows: Int) -> Int {
        maxOffset(count: count, rows: rows) / max(rows, 1) + 1
    }
}

/// The rows each size shows: Android's two in the compact form and five per page; the medium size of
/// an iPhone has room for three.
enum WidgetRows {
    static func count(_ family: WidgetFamily, maximum: Int) -> Int {
        switch family {
        case .systemSmall: return min(SharedData.Widgets.compactRows, maximum)
        case .systemMedium: return min(3, maximum)
        default: return maximum
        }
    }
}

/// The instants a timeline needs an entry at and when it asks WidgetKit to run again.
enum WidgetTimelinePlan {
    /// At most this many entries: a day of departures is far fewer.
    static let maximumEntries = 60

    /// `now`, then every later instant of `changes` within a day, in order, without duplicates.
    static func dates(now: Date, changes: [Date]) -> [Date] {
        let limit = now.addingTimeInterval(24 * 3_600)
        let later = Set(changes.filter { $0 > now && $0 <= limit }.map { $0.timeIntervalSince1970.rounded() })
        return [now] + later.sorted().prefix(maximumEntries - 1).map { Date(timeIntervalSince1970: $0) }
    }

    /// The reload policy: after the next run, or never when nothing automatic is left (refresh off, no
    /// window ahead, no direction change).
    static func policy(_ next: Date?, now: Date) -> TimelineReloadPolicy {
        guard let next else { return .never }
        return .after(max(next, now.addingTimeInterval(SharedData.Widgets.minimumRescheduleSeconds)))
    }

    /// The live values change their meaning five and fifteen minutes after they were read.
    static func freshnessChanges(_ retrievedAt: Date) -> [Date] {
        [retrievedAt.addingTimeInterval(SharedData.Widgets.freshSeconds), retrievedAt.addingTimeInterval(SharedData.Widgets.agingSeconds)]
    }
}

/// What the buttons change before their widget is drawn again.
enum WidgetActions {
    /// Android's `WidgetStateStore.switchDirection`: the other direction; with «ritorno dalle» the choice
    /// holds until the next change (that time or midnight), then the automatic direction applies again.
    static func switchDirection(routeKey: String, returnFromMinute: Int, now: Date) {
        guard let store = WidgetSharedStore.shared() else { return }
        let current = store.direction(routeKey: routeKey)
        let returnFrom = returnFromMinute >= 0 ? PlannedLocalTime(hour: returnFromMinute / 60, minute: returnFromMinute % 60) : nil
        let shown = WidgetDirectionSchedule.effective(
            returnFrom: returnFrom, stored: current.shown, manual: current.manual, manualUntil: current.manualUntil, now: now
        )
        let next = shown.other
        var until: Date?
        if let returnFrom { until = WidgetDirectionSchedule.nextBoundary(returnFrom: returnFrom, now: now) }
        store.saveDirection(
            WidgetDirectionState(shown: next, manual: returnFrom == nil ? nil : next, manualUntil: until), routeKey: routeKey
        )
        store.setRowOffset(0, key: routeKey)
    }
}

/// The header of every widget: its title, «Aggiornato alle HH:mm» and the meta line.
struct WidgetHeader: View {
    let title: String
    let updated: String
    let meta: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(title)
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Text(updated)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            if let meta, !meta.isEmpty {
                Text(meta)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// A small button of a widget's header (direction, refresh, pages).
struct WidgetButtonLabel: View {
    let text: String
    let accessibility: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Color.secondary.opacity(0.18), in: Capsule())
            .accessibilityLabel(Text(accessibility))
    }
}
