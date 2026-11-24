import AppIntents
import BinarioKit
import SwiftUI
import WidgetKit

// «I miei treni» (Android's TrainsWidgetProvider): the trains saved with the star of the train detail,
// each with its next station or last detection, delay (early in green), platform, cancellation or
// variation; a tap opens the app at «I miei treni». The states come from the app's live train search
// with «Dati in tempo reale» on, shared by every «I miei treni» widget; without it, the timetable.

struct TrainsSettings: Sendable {
    let customTitle: String
    let info: TrainsInfoChoice
    let showPlatform: Bool
    let hideNotRunning: Bool
    let sort: TrainsSortChoice
    let autoRefresh: WidgetAutoRefresh

    init(_ intent: TrainsWidgetIntent) {
        customTitle = WidgetRefreshSettings.title(intent.customTitle)
        info = intent.info
        showPlatform = intent.showPlatform
        hideNotRunning = intent.hideNotRunning
        sort = intent.sort
        autoRefresh = WidgetRefreshSettings.make(
            mode: intent.refresh, interval: intent.interval, days: intent.days,
            chosenDays: [intent.monday, intent.tuesday, intent.wednesday, intent.thursday, intent.friday, intent.saturday, intent.sunday],
            firstWindow: (intent.firstWindowStart, intent.firstWindowEnd),
            secondWindow: intent.secondWindow ? (intent.secondWindowStart, intent.secondWindowEnd) : nil
        )
    }
}

struct TrainsEntry: TimelineEntry {
    let date: Date
    let settings: TrainsSettings?
    let trains: [FavouriteTrain]
    let statuses: [String: WidgetTrainStatus]
    let rowOffset: Int
}

struct TrainsProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> TrainsEntry {
        TrainsEntry(date: Date(), settings: nil, trains: [], statuses: [:], rowOffset: 0)
    }

    func snapshot(for configuration: TrainsWidgetIntent, in context: Context) async -> TrainsEntry {
        let timeline = await TrainsTimeline.make(TrainsSettings(configuration), now: Date(), refreshAllowed: !context.isPreview)
        return timeline.entries.first ?? placeholder(in: context)
    }

    func timeline(for configuration: TrainsWidgetIntent, in context: Context) async -> Timeline<TrainsEntry> {
        await TrainsTimeline.make(TrainsSettings(configuration), now: Date(), refreshAllowed: true)
    }
}

enum TrainsTimeline {
    static let key = "trains"

    static func make(_ settings: TrainsSettings, now: Date, refreshAllowed: Bool) async -> Timeline<TrainsEntry> {
        let store = WidgetSharedStore.shared()
        let trains = store?.favouriteTrains.load().trains ?? []
        var statuses = store?.trainStatuses ?? [:]
        let lastAttempt = store?.lastAttempt(key: key)
        let plan = WidgetRefreshPlanner.plan(
            settings: settings.autoRefresh, lastRefresh: lastAttempt, pendingDirection: nil, nextDirectionBoundary: nil, now: now
        )
        let tapped = store?.refreshRequested(.trains).map { requested in lastAttempt.map { requested > $0 } ?? true } ?? false
        if refreshAllowed, let live = WidgetSources.liveIfEnabled(store) {
            // A tap asks for every train; an automatic refresh for the trains near their run; a train never
            // looked up (just saved in the app) is asked at once, as Android's widget looks up a new favourite.
            var asked = WidgetFeeds.trainsToAsk(trains, statuses: statuses, now: now, automatic: !tapped)
            if !tapped, !plan.fetch {
                asked = asked.filter { statuses[$0.train.id] == nil }
            }
            let unseen = trains.filter { train in
                statuses[train.id] == nil && !asked.contains(where: { entry in entry.train.id == train.id })
            }
            if let today = ItalianWallClock.date(of: now) {
                asked += unseen.map { (train: $0, day: today) }
            }
            if !asked.isEmpty {
                let found = await WidgetFeeds.lookUp(asked, now: now, live: live)
                for entry in asked {
                    switch found[entry.train.id] {
                    case let .found(status)?:
                        statuses[entry.train.id] = status
                    default:
                        // A failed lookup keeps the last known values, marked stale; a train never seen stays absent.
                        if let previous = statuses[entry.train.id] { statuses[entry.train.id] = previous.markedStale }
                    }
                }
                store?.recordAttempt(key: key, at: now)
            }
            let saved = Set(trains.map(\.id))
            statuses = statuses.filter { saved.contains($0.key) }
            store?.saveTrainStatuses(statuses)
        } else if store?.liveDataEnabled == false {
            // «Dati in tempo reale» off: the timetable of each train, as the app saved it.
            statuses = [:]
            for train in trains { statuses[train.id] = WidgetFeeds.timetableStatus(train, now: now) }
        }
        var changes: [Date] = []
        for status in statuses.values {
            if let departure = status.departure { changes.append(departure) }
            if let arrival = status.arrival { changes.append(arrival.addingTimeInterval(WidgetTrainsRules.arrivalGrace)) }
            changes += WidgetTimelinePlan.freshnessChanges(status.retrievedAt)
        }
        let offset = store?.rowOffset(key: key) ?? 0
        let entries = WidgetTimelinePlan.dates(now: now, changes: changes).map { date in
            TrainsEntry(date: date, settings: settings, trains: trains, statuses: statuses, rowOffset: offset)
        }
        let after = WidgetRefreshPlanner.plan(
            settings: settings.autoRefresh, lastRefresh: store?.lastAttempt(key: key), pendingDirection: nil,
            nextDirectionBoundary: nil, now: now
        )
        return Timeline(entries: entries, policy: WidgetTimelinePlan.policy(after.nextRun, now: now))
    }
}

struct TrainsWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: WidgetKindName.trains.rawValue, intent: TrainsWidgetIntent.self, provider: TrainsProvider()) { entry in
            TrainsWidgetView(entry: entry)
        }
        .configurationDisplayName(Text("widgets.trains.name"))
        .description(Text("widgets.trains.description"))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

/// Android's `TrainsWidgetPresenter`: the order, the «solo in viaggio» filter and the texts of each row.
enum TrainsPresenter {
    static func label(_ train: FavouriteTrain) -> String {
        train.category.isEmpty
            ? WidgetText.format("widgets.trains.train_number", train.number)
            : WidgetText.format("widgets.trains.category_number", train.category, train.number)
    }

    static func visible(_ trains: [FavouriteTrain], statuses: [String: WidgetTrainStatus], settings: TrainsSettings, now: Date) -> [FavouriteTrain] {
        let ordered = trains.enumerated().sorted { first, second in
            let left = first.element
            let right = second.element
            let byTitle = label(left).compare(label(right), options: [.caseInsensitive, .diacriticInsensitive], locale: Locale.current)
            let leftDeparture = PlannedLocalTime(hhmm: left.scheduledDeparture)?.minutesOfDay ?? Int.max
            let rightDeparture = PlannedLocalTime(hhmm: right.scheduledDeparture)?.minutesOfDay ?? Int.max
            switch settings.sort {
            case .departure:
                if leftDeparture != rightDeparture { return leftDeparture < rightDeparture }
                if byTitle != .orderedSame { return byTitle == .orderedAscending }
            case .name:
                if byTitle != .orderedSame { return byTitle == .orderedAscending }
                if leftDeparture != rightDeparture { return leftDeparture < rightDeparture }
            }
            if left.number != right.number { return left.number < right.number }
            return first.offset < second.offset
        }.map { $0.element }
        guard settings.hideNotRunning else { return ordered }
        return ordered.filter { WidgetTrainsRules.isRunning(statuses[$0.id]?.run, now: now) }
    }

    /// «18:30» for data of today, «7 nov 18:30» for another day's.
    static func dataTime(_ retrievedAt: Date, now: Date) -> String {
        let time = WidgetClock.hhmm(retrievedAt)
        guard ItalianWallClock.date(of: retrievedAt) != ItalianWallClock.date(of: now) else { return time }
        let formatter = DateFormatter()
        formatter.timeZone = ItalianWallClock.timeZone
        formatter.locale = Locale.current
        formatter.setLocalizedDateFormatFromTemplate("dMMM")
        return formatter.string(from: retrievedAt) + " " + time
    }
}

struct TrainsWidgetView: View {
    let entry: TrainsEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        content
            .containerBackground(.background, for: .widget)
    }

    @ViewBuilder
    private var content: some View {
        if let settings = entry.settings {
            loaded(settings)
        } else {
            WidgetHeader(title: WidgetText.string("widgets.trains.name"), updated: WidgetText.updated(nil), meta: nil)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }

    private func loaded(_ settings: TrainsSettings) -> some View {
        let title = settings.customTitle.isEmpty ? WidgetText.string("widgets.trains.name") : settings.customTitle
        let visible = TrainsPresenter.visible(entry.trains, statuses: entry.statuses, settings: settings, now: entry.date)
        let rows = WidgetRows.count(family, maximum: SharedData.Widgets.pageRows)
        let offset = WidgetPages.normalized(entry.rowOffset, count: visible.count, rows: rows)
        let page = Array(visible.dropFirst(offset).prefix(rows))
        let hidden = entry.trains.count - visible.count
        let paged = visible.count > rows
        var meta = [WidgetText.plural("widgets.trains.count", entry.trains.count)]
        if hidden > 0 { meta.append(WidgetText.plural("widgets.trains.hidden", hidden)) }
        if paged { meta.append(WidgetText.format("widgets.page", WidgetPages.number(offset, rows: rows), WidgetPages.count(visible.count, rows: rows))) }
        let lastUpdate = entry.statuses.values.map(\.retrievedAt).max()
        return VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top, spacing: 6) {
                WidgetHeader(title: title, updated: WidgetText.updated(lastUpdate), meta: meta.joined(separator: " · "))
                Spacer(minLength: 0)
                Button(intent: RefreshWidgetIntent(kind: .trains)) {
                    WidgetButtonLabel(text: WidgetText.string("widgets.symbol.refresh"), accessibility: WidgetText.string("widgets.action.trains_refresh"))
                }
                .buttonStyle(.plain)
            }
            if page.isEmpty {
                Text(WidgetText.string(entry.trains.isEmpty ? "widgets.trains.empty" : "widgets.trains.none_running"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(page) { train in
                    TrainRowView(
                        train: train, status: entry.statuses[train.id], settings: settings, now: entry.date,
                        compact: family == .systemSmall
                    )
                }
            }
            Spacer(minLength: 0)
            if paged, family != .systemSmall {
                HStack {
                    Spacer()
                    Button(intent: PageWidgetIntent(kind: .trains, key: TrainsTimeline.key, offset: WidgetPages.moved(offset, by: -1, count: visible.count, rows: rows))) {
                        WidgetButtonLabel(text: WidgetText.string("widgets.symbol.previous"), accessibility: WidgetText.string("widgets.action.trains_previous"))
                    }
                    .buttonStyle(.plain)
                    Button(intent: PageWidgetIntent(kind: .trains, key: TrainsTimeline.key, offset: WidgetPages.moved(offset, by: 1, count: visible.count, rows: rows))) {
                        WidgetButtonLabel(text: WidgetText.string("widgets.symbol.next"), accessibility: WidgetText.string("widgets.action.trains_next"))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .widgetURL(WidgetLinks.trains())
    }
}

/// One favourite (Android's `TrainsWidgetPresenter.row`).
private struct TrainRowView: View {
    let title: String
    let detail: String
    let label: String
    let emphasis: WidgetEmphasis
    let platform: (text: String, changed: Bool)?
    let spoken: String
    let link: URL?
    let compact: Bool

    init(train: FavouriteTrain, status lastKnown: WidgetTrainStatus?, settings: TrainsSettings, now: Date, compact: Bool) {
        title = TrainsPresenter.label(train)
        link = WidgetLinks.train(train.id)
        self.compact = compact
        guard let lastKnown else {
            detail = WidgetText.string("widgets.trains.waiting")
            label = "—"
            emphasis = .muted
            platform = settings.showPlatform ? ("—", false) : nil
            spoken = [title, detail].joined(separator: ". ")
            return
        }
        let earlierRun = WidgetTrainsRules.isEarlierRunOver(lastKnown.run, now: now)
        let status = lastKnown.forToday(now: now)
        let live = !status.stale && status.operationalVerified && WidgetFreshness.of(retrievedAt: status.retrievedAt, now: now).showsLive
        var info: String?
        switch settings.info {
        case .nextStation:
            if let next = status.nextStation {
                info = WidgetText.format("widgets.trains.next_station", next, status.nextStationTime ?? "").trimmingCharacters(in: .whitespaces)
            }
        case .lastDetection:
            if let station = status.lastDetectionStation {
                info = WidgetText.format("widgets.trains.last_detection", station, status.lastDetectionTime ?? "").trimmingCharacters(in: .whitespaces)
            } else if status.operationalVerified {
                info = WidgetText.string("widgets.trains.no_detection")
            }
        }
        if info == nil, !status.scheduledDeparture.isEmpty {
            info = WidgetText.format("widgets.trains.departure_from", status.scheduledDeparture, status.origin)
        }
        let stale: String? = live || (!status.operationalVerified && !earlierRun)
            ? nil : WidgetText.format("widgets.trains.stale", TrainsPresenter.dataTime(status.retrievedAt, now: now))
        var label = WidgetText.string("widgets.status.on_time")
        var spokenState = WidgetText.string("widgets.a11y.on_time")
        var emphasis: WidgetEmphasis = live ? .normal : .muted
        let delay = status.delayMinutes ?? 0
        if earlierRun {
            label = "—"
            spokenState = WidgetText.string("widgets.a11y.scheduled")
            emphasis = .muted
        } else if !status.operationalVerified {
            label = WidgetText.string("widgets.status.scheduled")
            spokenState = WidgetText.string("widgets.a11y.scheduled")
            emphasis = .normal
        } else if status.cancelled {
            label = WidgetText.string("widgets.status.cancelled")
            spokenState = WidgetText.string("widgets.a11y.cancelled")
            emphasis = live ? .error : .muted
        } else if status.variation {
            label = WidgetText.string("widgets.status.changed")
            spokenState = WidgetText.string("widgets.a11y.variation")
            emphasis = live ? .warning : .muted
        } else if delay > 0 {
            label = WidgetText.format("widgets.status.delayed", delay)
            spokenState = WidgetText.plural("widgets.a11y.delay", delay)
            emphasis = live ? .warning : .muted
        } else if delay < 0 {
            label = WidgetText.format("widgets.status.early", -delay)
            spokenState = WidgetText.plural("widgets.a11y.early", -delay)
            emphasis = live ? .early : .muted
        }
        let shownPlatform = WidgetText.platform(status.platform, scheduled: status.scheduledPlatform)
        detail = [stale, info].compactMap { $0 }.joined(separator: " · ")
        self.label = label
        self.emphasis = emphasis
        platform = settings.showPlatform ? shownPlatform : nil
        var spokenParts = [title, spokenState]
        if let stale { spokenParts.append(stale) }
        if let info { spokenParts.append(info) }
        if settings.showPlatform { spokenParts.append(WidgetText.spokenPlatform(status.platform, scheduled: status.scheduledPlatform)) }
        spoken = spokenParts.joined(separator: ". ")
    }

    var body: some View {
        if let link {
            Link(destination: link) { row }
                .foregroundStyle(.primary)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(spoken))
        } else {
            row
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(spoken))
        }
    }

    private var row: some View {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(title).font((compact ? Font.caption : Font.subheadline).weight(.semibold)).lineLimit(1)
                    if !detail.isEmpty, !compact {
                        Text(detail).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let platform, !compact {
                    Text(platform.text)
                        .font(.caption2)
                        .foregroundStyle(platform.changed ? WidgetEmphasis.warning.color : WidgetEmphasis.normal.color)
                        .lineLimit(2)
                        .frame(width: 52, alignment: .leading)
                }
                Text(label)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(emphasis.color)
                    .lineLimit(1)
                    .frame(minWidth: 36, alignment: .trailing)
            }
            .padding(.vertical, 1)
    }
}
