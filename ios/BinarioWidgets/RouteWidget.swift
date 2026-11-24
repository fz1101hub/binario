import AppIntents
import BinarioKit
import SwiftUI
import WidgetKit

// «Tratta» (Android's RouteWidgetProvider): the next runs of a route typed in the edit sheet, with
// its direction button, «Mostra il ritorno dalle», the train types to hide, the time range, the trains
// kept «In viaggio» until their arrival, the platform column and the train names.

/// The settings of one «Tratta», read from its edit sheet.
struct RouteSettings: Sendable {
    let origin: StationEntity?
    let destination: StationEntity?
    let customTitle: String
    let autoRefresh: WidgetAutoRefresh
    let returnFrom: PlannedLocalTime?
    let timeRange: WidgetTimeRange
    let excluded: Set<WidgetTrainTypeGroup>
    let untilArrival: Bool
    let showPlatform: Bool
    let showTrainName: Bool

    init(_ intent: RouteWidgetIntent) {
        origin = intent.origin
        destination = intent.destination
        customTitle = WidgetRefreshSettings.title(intent.customTitle)
        autoRefresh = WidgetRefreshSettings.make(
            mode: intent.refresh, interval: intent.interval, days: intent.days,
            chosenDays: [intent.monday, intent.tuesday, intent.wednesday, intent.thursday, intent.friday, intent.saturday, intent.sunday],
            firstWindow: (intent.firstWindowStart, intent.firstWindowEnd),
            secondWindow: intent.secondWindow ? (intent.secondWindowStart, intent.secondWindowEnd) : nil
        )
        returnFrom = intent.showReturn
            ? PlannedLocalTime(hour: intent.returnFrom.minuteOfDay / 60, minute: intent.returnFrom.minuteOfDay % 60) : nil
        timeRange = intent.timeRange.range
        excluded = WidgetRefreshSettings.excluded(
            highSpeed: intent.hideHighSpeed, intercityNight: intent.hideIntercity, regionalSuburban: intent.hideRegional,
            italo: intent.hideItalo, replacementBus: intent.hideBus, international: intent.hideInternational
        )
        untilArrival = intent.untilArrival
        showPlatform = intent.showPlatform
        showTrainName = intent.showTrainName
    }

    /// The route both directions share (its direction, its page); nil until two different stations are chosen.
    var routeKey: String? {
        guard let origin, let destination, origin.id != destination.id else { return nil }
        return origin.id + "|" + destination.id
    }
}

struct RouteEntry: TimelineEntry {
    let date: Date
    let settings: RouteSettings?
    let direction: WidgetDirection
    let snapshot: WidgetRouteSnapshot?
    let rowOffset: Int
}

struct RouteProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> RouteEntry {
        RouteEntry(date: Date(), settings: nil, direction: .outbound, snapshot: nil, rowOffset: 0)
    }

    func snapshot(for configuration: RouteWidgetIntent, in context: Context) async -> RouteEntry {
        let timeline = await RouteTimeline.make(RouteSettings(configuration), now: Date(), refreshAllowed: !context.isPreview)
        return timeline.entries.first ?? placeholder(in: context)
    }

    func timeline(for configuration: RouteWidgetIntent, in context: Context) async -> Timeline<RouteEntry> {
        await RouteTimeline.make(RouteSettings(configuration), now: Date(), refreshAllowed: true)
    }
}

/// One «Tratta» run of Android's scheduler: the direction applied, a refresh when one is due (first
/// load, the button, an automatic window, the swap to the return, a retry), then the entries at each
/// instant a row leaves and the reload at the next due instant.
enum RouteTimeline {
    static func make(_ settings: RouteSettings, now: Date, refreshAllowed: Bool) async -> Timeline<RouteEntry> {
        guard let routeKey = settings.routeKey, let origin = settings.origin, let destination = settings.destination else {
            return Timeline(entries: [RouteEntry(date: now, settings: settings, direction: .outbound, snapshot: nil, rowOffset: 0)], policy: .never)
        }
        let store = WidgetSharedStore.shared()
        let directionState = store?.direction(routeKey: routeKey) ?? WidgetDirectionState(shown: .outbound, manual: nil, manualUntil: nil)
        let direction = WidgetDirectionSchedule.effective(
            returnFrom: settings.returnFrom, stored: directionState.shown, manual: directionState.manual,
            manualUntil: directionState.manualUntil, now: now
        )
        let pending: WidgetDirection? = direction != directionState.shown ? direction : nil
        let boundary = settings.returnFrom.flatMap { WidgetDirectionSchedule.nextBoundary(returnFrom: $0, now: now) }
        let snapshotKey = "\(routeKey)|\(direction.rawValue)|\(settings.timeRange.rawValue)"
        var snapshot = store?.routeSnapshot(key: snapshotKey)
        let lastAttempt = store?.lastAttempt(key: snapshotKey)
        let plan = WidgetRefreshPlanner.plan(
            settings: settings.autoRefresh, lastRefresh: lastAttempt, pendingDirection: pending,
            nextDirectionBoundary: boundary, now: now
        )
        if pending != nil {
            // The automatic swap, or the end of a manual choice: the stored direction follows.
            let manualLeft = directionState.manualUntil.map { now < $0 } ?? false
            store?.saveDirection(
                WidgetDirectionState(
                    shown: direction, manual: manualLeft ? directionState.manual : nil,
                    manualUntil: manualLeft ? directionState.manualUntil : nil
                ),
                routeKey: routeKey
            )
            store?.setRowOffset(0, key: routeKey)
        }
        let tapped = store?.refreshRequested(.route).map { requested in lastAttempt.map { requested > $0 } ?? true } ?? false
        let retry = store?.retry(key: snapshotKey)
        var retryAt: Date?
        if let retry, let delay = WidgetRefreshPlanner.retryDelay(settings: settings.autoRefresh, attempt: retry.attempt, now: retry.failedAt) {
            retryAt = retry.failedAt.addingTimeInterval(delay)
        }
        let retryDue = retryAt.map { now >= $0 } ?? false
        let missing = snapshot == nil || snapshot?.failure == .unavailable
        if refreshAllowed, missing || tapped || plan.fetch || retryDue {
            let originStation = await WidgetCatalog.shared.station(id: origin.id) ?? Station(id: origin.id, name: origin.name)
            let destinationStation = await WidgetCatalog.shared.station(id: destination.id) ?? Station(id: destination.id, name: destination.name)
            let loaded: WidgetRouteSnapshot
            if let repository = WidgetSources.repository(store) {
                loaded = await WidgetFeeds.route(
                    origin: originStation, destination: destinationStation, direction: direction, timeRange: settings.timeRange,
                    now: now, repository: repository, trips: repository, live: WidgetSources.liveIfEnabled(store)
                )
            } else {
                loaded = WidgetRouteSnapshot(
                    originID: origin.id, destinationID: destination.id, direction: direction, timeRange: settings.timeRange,
                    rows: [], retrievedAt: now, liveSources: [], liveUnavailable: false, failure: .unavailable
                )
            }
            snapshot = loaded
            store?.saveRouteSnapshot(loaded, key: snapshotKey)
            store?.recordAttempt(key: snapshotKey, at: now)
            // A refresh asked with the button that found no live source is retried in the windows (Android's
            // `WidgetRefreshRetryPolicy`); any answer ends the retries.
            if loaded.liveUnavailable, tapped || retryDue {
                let attempt = retryDue ? (retry?.attempt ?? 0) + 1 : 1
                store?.saveRetry(WidgetRetryState(attempt: attempt, failedAt: now), key: snapshotKey)
                retryAt = WidgetRefreshPlanner.retryDelay(settings: settings.autoRefresh, attempt: attempt, now: now)
                    .map { now.addingTimeInterval($0) }
            } else {
                store?.saveRetry(nil, key: snapshotKey)
                retryAt = nil
            }
        }
        let offset = store?.rowOffset(key: routeKey) ?? 0
        var changes: [Date] = boundary.map { [$0] } ?? []
        if let snapshot {
            for row in snapshot.rows {
                let run = row.run
                if let departure = run.departure { changes.append(departure.addingTimeInterval(WidgetRouteRules.departedGrace)) }
                if settings.untilArrival, let arrival = run.arrival { changes.append(arrival) }
            }
            changes += WidgetTimelinePlan.freshnessChanges(snapshot.retrievedAt)
        }
        let entries = WidgetTimelinePlan.dates(now: now, changes: changes).map { date in
            RouteEntry(date: date, settings: settings, direction: direction, snapshot: snapshot, rowOffset: offset)
        }
        // After this run the next one: the next due refresh, the next direction change, a retry.
        let after = WidgetRefreshPlanner.plan(
            settings: settings.autoRefresh, lastRefresh: store?.lastAttempt(key: snapshotKey), pendingDirection: nil,
            nextDirectionBoundary: boundary, now: now
        )
        let next = [after.nextRun, retryAt].compactMap { $0 }.min()
        return Timeline(entries: entries, policy: WidgetTimelinePlan.policy(next, now: now))
    }
}

struct RouteWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: WidgetKindName.route.rawValue, intent: RouteWidgetIntent.self, provider: RouteProvider()) { entry in
            RouteWidgetView(entry: entry)
        }
        .configurationDisplayName(Text("widgets.route.name"))
        .description(Text("widgets.route.description"))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct RouteWidgetView: View {
    let entry: RouteEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        content
            .containerBackground(.background, for: .widget)
    }

    @ViewBuilder
    private var content: some View {
        if let settings = entry.settings, let routeKey = settings.routeKey, let origin = settings.origin, let destination = settings.destination {
            loaded(settings, routeKey: routeKey, origin: origin, destination: destination)
        } else {
            // A new widget: its edit sheet chooses the route (Android's «CONFIGURA BINARIO»).
            VStack(alignment: .leading, spacing: 4) {
                Text(WidgetText.string("widgets.route.configure.title")).font(.headline)
                Text(WidgetText.string("widgets.route.configure.subtitle")).font(.caption).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }

    private func loaded(_ settings: RouteSettings, routeKey: String, origin: StationEntity, destination: StationEntity) -> some View {
        let from = entry.direction == .outbound ? origin : destination
        let to = entry.direction == .outbound ? destination : origin
        let title = settings.customTitle.isEmpty ? "\(from.name) → \(to.name)" : settings.customTitle
        let snapshot = entry.snapshot
        let freshness: WidgetFreshness = snapshot.map {
            $0.liveUnavailable ? .unavailable : WidgetFreshness.of(retrievedAt: $0.retrievedAt, now: entry.date)
        } ?? .unavailable
        let listed = WidgetRouteRules.visible(
            snapshot?.rows ?? [], run: { $0.run }, now: entry.date, excluded: settings.excluded,
            untilArrival: settings.untilArrival, timeRange: settings.timeRange
        )
        let rows = WidgetRows.count(family, maximum: SharedData.Widgets.pageRows)
        let offset = WidgetPages.normalized(entry.rowOffset, count: listed.count, rows: rows)
        let page = Array(listed.dropFirst(offset).prefix(rows))
        let paged = listed.count > rows
        var meta: [String] = []
        if let snapshot {
            if !snapshot.liveSources.isEmpty { meta.append(snapshot.liveSources.joined(separator: ", ")) }
            if !snapshot.rows.contains(where: { $0.live != nil }) { meta.append(WidgetText.string("widgets.confidence.static")) }
            meta.append(WidgetText.freshness(freshness))
        }
        if paged { meta.append(WidgetText.format("widgets.page", WidgetPages.number(offset, rows: rows), WidgetPages.count(listed.count, rows: rows))) }
        let directionLabel = WidgetText.string(entry.direction == .outbound ? "widgets.direction.outbound" : "widgets.direction.return")
        return VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top, spacing: 6) {
                WidgetHeader(title: title, updated: WidgetText.updated(snapshot?.retrievedAt), meta: meta.joined(separator: " · "))
                Spacer(minLength: 0)
                if family != .systemSmall {
                    Button(intent: SwitchRouteDirectionIntent(routeKey: routeKey, returnFrom: settings.returnFrom)) {
                        WidgetButtonLabel(text: directionLabel, accessibility: WidgetText.string("widgets.action.direction"))
                    }
                    .buttonStyle(.plain)
                    Button(intent: RefreshWidgetIntent(kind: .route)) {
                        WidgetButtonLabel(text: WidgetText.string("widgets.symbol.refresh"), accessibility: WidgetText.string("widgets.action.route_refresh"))
                    }
                    .buttonStyle(.plain)
                }
            }
            if let failure = snapshot?.failure, page.isEmpty {
                Text(failure == .unavailable
                     ? WidgetText.format("widgets.unavailable", WidgetText.string("widgets.target.route"))
                     : WidgetText.string("widgets.route.empty"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else if page.isEmpty {
                Text(WidgetText.string("widgets.route.empty")).font(.caption).foregroundStyle(.secondary)
            } else {
                if family == .systemLarge {
                    RouteColumnHeader(showPlatform: settings.showPlatform)
                }
                ForEach(page, id: \.row.id) { listedRow in
                    RouteRowView(
                        row: listedRow.row, inTransit: listedRow.inTransit, freshness: freshness, settings: settings,
                        compact: family == .systemSmall
                    )
                }
            }
            Spacer(minLength: 0)
            if paged, family != .systemSmall {
                HStack {
                    Spacer()
                    Button(intent: PageWidgetIntent(kind: .route, key: routeKey, offset: WidgetPages.moved(offset, by: -1, count: listed.count, rows: rows))) {
                        WidgetButtonLabel(text: WidgetText.string("widgets.symbol.previous"), accessibility: WidgetText.string("widgets.action.previous"))
                    }
                    .buttonStyle(.plain)
                    Button(intent: PageWidgetIntent(kind: .route, key: routeKey, offset: WidgetPages.moved(offset, by: 1, count: listed.count, rows: rows))) {
                        WidgetButtonLabel(text: WidgetText.string("widgets.symbol.next"), accessibility: WidgetText.string("widgets.action.next"))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .widgetURL(WidgetLinks.route(origin: from.id, destination: to.id))
    }
}

private struct RouteColumnHeader: View {
    let showPlatform: Bool

    var body: some View {
        HStack(spacing: 6) {
            Text(WidgetText.string("widgets.column.time")).frame(width: 40, alignment: .leading)
            Text(WidgetText.string("widgets.column.destination")).frame(maxWidth: .infinity, alignment: .leading)
            if showPlatform { Text(WidgetText.string("widgets.column.platform")).frame(width: 52, alignment: .leading) }
            Text(WidgetText.string("widgets.column.status")).frame(width: 52, alignment: .trailing)
        }
        .font(.caption2.weight(.semibold))
        .foregroundStyle(.secondary)
        .accessibilityHidden(true)
    }
}

/// One run: time, destination and train, platform, state (Android's route widget row).
private struct RouteRowView: View {
    let time: String
    let destination: String
    let train: String
    let platform: (text: String, changed: Bool)?
    let state: RouteRowState
    let spoken: String
    let compact: Bool

    init(row: WidgetRouteRow, inTransit: Bool, freshness: WidgetFreshness, settings: RouteSettings, compact: Bool) {
        // Without fresh data the row is the timetable's: no old delay, cancellation or platform change.
        let live = freshness.showsLive ? row.live : nil
        let shownPlatform: String? = freshness.showsLive ? row.live?.platform : (row.live?.scheduledPlatform ?? row.live?.platform)
        let scheduledPlatform: String? = freshness.showsLive ? row.live?.scheduledPlatform : nil
        let state = RouteRowState(live: live)
        var trainParts: [String] = []
        if inTransit { trainParts.append(WidgetText.string("widgets.in_transit")) }
        if settings.showTrainName { trainParts.append("\(row.operatorName) · \(row.category) \(row.number)") }
        var time = row.scheduledTime
        if let expected = live?.expectedDeparture { time = WidgetClock.hhmm(expected) }
        var spokenParts: [String] = ["\(row.category) \(row.number)", row.destination, time, state.spoken]
        if settings.showPlatform { spokenParts.append(WidgetText.spokenPlatform(shownPlatform, scheduled: scheduledPlatform)) }
        if inTransit { spokenParts.append(WidgetText.string("widgets.in_transit")) }
        self.time = time
        self.destination = row.destination
        self.train = trainParts.joined(separator: " · ")
        self.platform = settings.showPlatform ? WidgetText.platform(shownPlatform, scheduled: scheduledPlatform) : nil
        self.state = state
        self.spoken = spokenParts.joined(separator: ". ")
        self.compact = compact
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(time)
                .font((compact ? Font.caption : Font.subheadline).monospacedDigit().weight(.semibold))
                .frame(width: compact ? 36 : 40, alignment: .leading)
            VStack(alignment: .leading, spacing: 0) {
                Text(destination).font(compact ? .caption : .subheadline).lineLimit(1)
                if !train.isEmpty, !compact {
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
            Text(state.label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(state.emphasis.color)
                .lineLimit(1)
                .frame(minWidth: 36, alignment: .trailing)
        }
        .padding(.vertical, 1)
        .background(state.highlighted ? WidgetEmphasis.warning.color.opacity(0.12) : Color.clear)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(spoken))
    }
}

/// Android's `WidgetChangeHighlight.displayStatus` for a route row.
private struct RouteRowState {
    let label: String
    let spoken: String
    let emphasis: WidgetEmphasis
    let highlighted: Bool

    init(live: WidgetLiveValues?) {
        guard let live else {
            label = WidgetText.string("widgets.status.scheduled")
            spoken = WidgetText.string("widgets.a11y.scheduled")
            emphasis = .normal
            highlighted = false
            return
        }
        if live.cancelled {
            label = WidgetText.string("widgets.status.cancelled")
            spoken = WidgetText.string("widgets.a11y.cancelled")
            emphasis = .error
            highlighted = true
        } else if let delay = live.delayMinutes, delay > 0 {
            label = WidgetText.format("widgets.status.delayed", delay)
            spoken = WidgetText.plural("widgets.a11y.delay", delay)
            emphasis = .warning
            highlighted = true
        } else if live.lateWithoutMinutes {
            label = WidgetText.string("widgets.status.late")
            spoken = WidgetText.string("widgets.status.late")
            emphasis = .warning
            highlighted = true
        } else {
            label = WidgetText.string("widgets.status.on_time")
            spoken = WidgetText.string("widgets.a11y.on_time")
            emphasis = .normal
            highlighted = false
        }
    }
}
