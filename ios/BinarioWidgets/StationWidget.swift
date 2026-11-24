import AppIntents
import BinarioKit
import SwiftUI
import WidgetKit

// «Tabellone stazione» (Android's StationWidgetProvider): the next departures of a station chosen in
// the edit sheet, with the train types to hide, the platform column, the train names and the
// «📍 Più vicina» button, which opens the app for one position (a widget cannot ask iOS for it).

struct StationSettings: Sendable {
    let station: StationEntity?
    let customTitle: String
    let autoRefresh: WidgetAutoRefresh
    let excluded: Set<WidgetTrainTypeGroup>
    let showPlatform: Bool
    let showTrainName: Bool
    let showNearest: Bool

    init(_ intent: StationWidgetIntent) {
        station = intent.station
        customTitle = WidgetRefreshSettings.title(intent.customTitle)
        autoRefresh = WidgetRefreshSettings.make(
            mode: intent.refresh, interval: intent.interval, days: intent.days,
            chosenDays: [intent.monday, intent.tuesday, intent.wednesday, intent.thursday, intent.friday, intent.saturday, intent.sunday],
            firstWindow: (intent.firstWindowStart, intent.firstWindowEnd),
            secondWindow: intent.secondWindow ? (intent.secondWindowStart, intent.secondWindowEnd) : nil
        )
        excluded = WidgetRefreshSettings.excluded(
            highSpeed: intent.hideHighSpeed, intercityNight: intent.hideIntercity, regionalSuburban: intent.hideRegional,
            italo: intent.hideItalo, replacementBus: intent.hideBus, international: intent.hideInternational
        )
        showPlatform = intent.showPlatform
        showTrainName = intent.showTrainName
        showNearest = intent.showNearest
    }
}

struct StationEntry: TimelineEntry {
    let date: Date
    let settings: StationSettings?
    /// The station shown: the configured one, or the one «📍 Più vicina» chose.
    let shown: StationIdentity?
    let snapshot: WidgetStationSnapshot?
    /// Why the last «📍 Più vicina» kept the station, shown until the next board arrives.
    let nearestNote: WidgetNearestState.Note?
}

struct StationProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> StationEntry {
        StationEntry(date: Date(), settings: nil, shown: nil, snapshot: nil, nearestNote: nil)
    }

    func snapshot(for configuration: StationWidgetIntent, in context: Context) async -> StationEntry {
        let timeline = await StationTimeline.make(StationSettings(configuration), now: Date(), refreshAllowed: !context.isPreview)
        return timeline.entries.first ?? placeholder(in: context)
    }

    func timeline(for configuration: StationWidgetIntent, in context: Context) async -> Timeline<StationEntry> {
        await StationTimeline.make(StationSettings(configuration), now: Date(), refreshAllowed: true)
    }
}

enum StationTimeline {
    static func make(_ settings: StationSettings, now: Date, refreshAllowed: Bool) async -> Timeline<StationEntry> {
        guard let configured = settings.station else {
            return Timeline(entries: [StationEntry(date: now, settings: settings, shown: nil, snapshot: nil, nearestNote: nil)], policy: .never)
        }
        let store = WidgetSharedStore.shared()
        let nearest = store?.nearest(configuredStationID: configured.id)
        let shown = nearest?.station ?? StationIdentity(id: configured.id, name: configured.name)
        let key = shown.id
        var snapshot = store?.stationSnapshot(key: key)
        let lastAttempt = store?.lastAttempt(key: "station|" + key)
        let plan = WidgetRefreshPlanner.plan(
            settings: settings.autoRefresh, lastRefresh: lastAttempt, pendingDirection: nil, nextDirectionBoundary: nil, now: now
        )
        let tapped = store?.refreshRequested(.station).map { requested in lastAttempt.map { requested > $0 } ?? true } ?? false
        let retry = store?.retry(key: "station|" + key)
        var retryAt: Date?
        if let retry, let delay = WidgetRefreshPlanner.retryDelay(settings: settings.autoRefresh, attempt: retry.attempt, now: retry.failedAt) {
            retryAt = retry.failedAt.addingTimeInterval(delay)
        }
        let retryDue = retryAt.map { now >= $0 } ?? false
        let missing = snapshot == nil || snapshot?.failure == .unavailable
        var note = nearest?.note
        if refreshAllowed, missing || tapped || plan.fetch || retryDue {
            let station = await WidgetCatalog.shared.station(id: shown.id) ?? Station(id: shown.id, name: shown.name, railwayCode: shown.railwayCode)
            let loaded: WidgetStationSnapshot
            if let repository = WidgetSources.repository(store) {
                loaded = await WidgetFeeds.station(station, now: now, board: repository, live: WidgetSources.liveIfEnabled(store))
            } else {
                loaded = WidgetStationSnapshot(
                    stationID: shown.id, stationName: shown.name, rows: [], retrievedAt: now, liveSources: [],
                    liveUnavailable: false, failure: .unavailable
                )
            }
            snapshot = loaded
            store?.saveStationSnapshot(loaded, key: key)
            store?.recordAttempt(key: "station|" + key, at: now)
            if loaded.failure == nil, note != nil {
                // The reason a «📍 Più vicina» tap kept the station is shown until the next board arrives.
                store?.saveNearest(WidgetNearestState(station: nearest?.station, note: nil, at: now), configuredStationID: configured.id)
                note = nil
            }
            if loaded.liveUnavailable, tapped || retryDue {
                let attempt = retryDue ? (retry?.attempt ?? 0) + 1 : 1
                store?.saveRetry(WidgetRetryState(attempt: attempt, failedAt: now), key: "station|" + key)
                retryAt = WidgetRefreshPlanner.retryDelay(settings: settings.autoRefresh, attempt: attempt, now: now)
                    .map { now.addingTimeInterval($0) }
            } else {
                store?.saveRetry(nil, key: "station|" + key)
                retryAt = nil
            }
        }
        var changes: [Date] = []
        if let snapshot {
            for row in snapshot.rows {
                changes.append(row.scheduled.addingTimeInterval(WidgetRouteRules.departedGrace))
                if let expected = row.live?.expectedDeparture { changes.append(expected.addingTimeInterval(WidgetRouteRules.departedGrace)) }
            }
            changes += WidgetTimelinePlan.freshnessChanges(snapshot.retrievedAt)
        }
        let entries = WidgetTimelinePlan.dates(now: now, changes: changes).map { date in
            StationEntry(date: date, settings: settings, shown: shown, snapshot: snapshot, nearestNote: note)
        }
        let after = WidgetRefreshPlanner.plan(
            settings: settings.autoRefresh, lastRefresh: store?.lastAttempt(key: "station|" + key), pendingDirection: nil,
            nextDirectionBoundary: nil, now: now
        )
        let next = [after.nextRun, retryAt].compactMap { $0 }.min()
        return Timeline(entries: entries, policy: WidgetTimelinePlan.policy(next, now: now))
    }
}

struct StationWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: WidgetKindName.station.rawValue, intent: StationWidgetIntent.self, provider: StationProvider()) { entry in
            StationWidgetView(entry: entry)
        }
        .configurationDisplayName(Text("widgets.station.name"))
        .description(Text("widgets.station.description"))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct StationWidgetView: View {
    let entry: StationEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        content
            .containerBackground(.background, for: .widget)
    }

    @ViewBuilder
    private var content: some View {
        if let settings = entry.settings, let configured = settings.station, let shown = entry.shown {
            loaded(settings, configured: configured, shown: shown)
        } else {
            VStack(alignment: .leading, spacing: 4) {
                Text(WidgetText.string("widgets.station.configure.title")).font(.headline)
                Text(WidgetText.string("widgets.station.configure.subtitle")).font(.caption).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }

    private func loaded(_ settings: StationSettings, configured: StationEntity, shown: StationIdentity) -> some View {
        let title = settings.customTitle.isEmpty ? shown.name : settings.customTitle
        let snapshot = entry.snapshot
        let freshness: WidgetFreshness = snapshot.map {
            $0.liveUnavailable ? .unavailable : WidgetFreshness.of(retrievedAt: $0.retrievedAt, now: entry.date)
        } ?? .unavailable
        let rows = WidgetRows.count(family, maximum: WidgetStationRules.maximumRows)
        let departures = WidgetStationRules.upcoming(
            snapshot?.rows ?? [], departure: { $0.departure }, freshness: freshness, now: entry.date, limit: rows,
            excluded: settings.excluded
        )
        var meta: [String] = []
        if let note = entry.nearestNote { meta.append(Self.noteText(note, station: configured.name)) }
        if let snapshot, snapshot.failure == nil {
            if !snapshot.liveSources.isEmpty { meta.append(snapshot.liveSources.joined(separator: ", ")) }
            if !departures.contains(where: { WidgetStationRules.isLive($0.departure, freshness: freshness) }) {
                meta.append(WidgetText.string("widgets.confidence.static"))
            }
            meta.append(WidgetText.freshness(freshness))
        }
        return VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top, spacing: 6) {
                WidgetHeader(title: title, updated: WidgetText.updated(snapshot?.retrievedAt), meta: meta.joined(separator: " · "))
                Spacer(minLength: 0)
                if settings.showNearest, family != .systemSmall, let link = WidgetLinks.nearest(configuredStation: configured) {
                    Link(destination: link) {
                        WidgetButtonLabel(
                            text: WidgetText.string("widgets.station.nearest"),
                            accessibility: WidgetText.string("widgets.station.nearest.accessibility")
                        )
                    }
                }
                Button(intent: RefreshWidgetIntent(kind: .station)) {
                    WidgetButtonLabel(text: WidgetText.string("widgets.symbol.refresh"), accessibility: WidgetText.string("widgets.action.station_refresh"))
                }
                .buttonStyle(.plain)
            }
            if snapshot == nil || snapshot?.failure == .unavailable {
                Text(WidgetText.string("widgets.station.waiting")).font(.caption).foregroundStyle(.secondary)
            } else if departures.isEmpty {
                Text(WidgetText.string("widgets.station.empty")).font(.caption).foregroundStyle(.secondary)
            } else {
                if family == .systemLarge {
                    HStack(spacing: 6) {
                        Text(WidgetText.string("widgets.column.time")).frame(width: 40, alignment: .leading)
                        Text(WidgetText.string("widgets.column.destination")).frame(maxWidth: .infinity, alignment: .leading)
                        if settings.showPlatform { Text(WidgetText.string("widgets.column.platform")).frame(width: 52, alignment: .leading) }
                        Text(WidgetText.string("widgets.column.status")).frame(width: 52, alignment: .trailing)
                    }
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                }
                ForEach(departures) { row in
                    StationRowView(row: row, freshness: freshness, settings: settings, compact: family == .systemSmall)
                }
            }
            Spacer(minLength: 0)
        }
        .widgetURL(WidgetLinks.station(shown))
    }

    static func noteText(_ note: WidgetNearestState.Note, station: String) -> String {
        switch note {
        case .permissionDenied: return WidgetText.format("widgets.station.nearest.denied", station)
        case .locationUnavailable: return WidgetText.format("widgets.station.nearest.unavailable", station)
        case .noneNearby: return WidgetText.format("widgets.station.nearest.none", station)
        }
    }
}

/// One departure (Android's `StationWidgetPresenter.row`): the timetable time, the train, the
/// destination, the platform and the state, live only while the data are fresh or aging.
private struct StationRowView: View {
    let time: String
    let train: String?
    let destination: String
    let platform: (text: String, changed: Bool)?
    let label: String
    let emphasis: WidgetEmphasis
    let spoken: String
    let compact: Bool

    init(row: WidgetBoardRow, freshness: WidgetFreshness, settings: StationSettings, compact: Bool) {
        let live = WidgetStationRules.isLive(row.departure, freshness: freshness) ? row.live : nil
        let scheduled = WidgetClock.hhmm(row.scheduled)
        var label = WidgetText.string("widgets.status.scheduled")
        var spokenState = WidgetText.string("widgets.a11y.scheduled")
        var emphasis = WidgetEmphasis.normal
        if let live {
            if live.cancelled {
                label = WidgetText.string("widgets.status.cancelled")
                spokenState = WidgetText.string("widgets.a11y.cancelled")
                emphasis = .error
            } else if let delay = live.delayMinutes, delay > 0 {
                label = WidgetText.format("widgets.status.delayed", delay)
                spokenState = WidgetText.plural("widgets.a11y.delay", delay)
                emphasis = .warning
            } else if live.lateWithoutMinutes {
                label = WidgetText.string("widgets.status.late")
                spokenState = label
                emphasis = .warning
            } else {
                label = WidgetText.string("widgets.status.on_time")
                spokenState = WidgetText.string("widgets.a11y.on_time")
            }
        }
        let shownPlatform: String? = live?.platform ?? (freshness.showsLive ? nil : row.live?.scheduledPlatform)
        let scheduledPlatform: String? = live?.scheduledPlatform
        let trainText = [row.operatorName.isEmpty ? nil : row.operatorName.uppercased(), row.number].compactMap { $0 }.joined(separator: " · ")
        var spokenParts: [String] = []
        if let number = row.number { spokenParts.append("\(row.category) \(number)") }
        spokenParts += [row.destination, scheduled, spokenState]
        if settings.showPlatform { spokenParts.append(WidgetText.spokenPlatform(shownPlatform, scheduled: scheduledPlatform)) }
        time = scheduled
        train = settings.showTrainName ? (trainText.isEmpty ? "—" : trainText) : nil
        destination = row.destination
        platform = settings.showPlatform ? WidgetText.platform(shownPlatform, scheduled: scheduledPlatform) : nil
        self.label = label
        self.emphasis = emphasis
        spoken = spokenParts.joined(separator: ". ")
        self.compact = compact
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(time)
                .font((compact ? Font.caption : Font.subheadline).monospacedDigit().weight(.semibold))
                .frame(width: compact ? 36 : 40, alignment: .leading)
            VStack(alignment: .leading, spacing: 0) {
                Text(destination).font(compact ? .caption : .subheadline).lineLimit(1)
                if let train, !compact {
                    Text(train).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
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
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(spoken))
    }
}
