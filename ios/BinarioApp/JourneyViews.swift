import SwiftUI
import BinarioKit

/// The chips of the results (Android's `ResultFilterChips`): one row, the chosen one filled; «Più affidabili» only
/// with the network statistics.
struct JourneyFilterChips: View {
    let selection: JourneyResultsFilter
    let counts: [JourneyResultsFilter: Int]
    let showsMostReliable: Bool
    let select: (JourneyResultsFilter) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(JourneyResultsFilter.allCases.filter { $0 != .mostReliable || showsMostReliable }, id: \.self) { filter in
                    let selected = filter == selection
                    Button {
                        select(filter)
                    } label: {
                        Text(verbatim: "\(JourneyTexts.filter(filter)) \(counts[filter] ?? 0)")
                            .font(.subheadline.weight(selected ? .semibold : .regular))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Capsule().fill(selected ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.12)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? [.isSelected] : [])
                }
            }
        }
    }
}

/// Under the chips, as Android's results: the changes filter of the planner (a tap removes it), «Preferenze di
/// viaggio», «Solo percorsi accessibili» when on and how many direct trains the preferences hide.
struct JourneyActiveFiltersView: View {
    @EnvironmentObject private var journeys: JourneysModel
    let hiddenTrains: Int

    var body: some View {
        if journeys.changeFilter != .any {
            Button {
                journeys.changeFilter = .any
            } label: {
                Label(JourneyTexts.changeFilter(journeys.changeFilter), systemImage: "xmark")
                    .labelStyle(TrailingIconLabelStyle())
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(Color.accentColor.opacity(0.2)))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(verbatim: String.localizedStringWithFormat(
                NSLocalizedString("result.journey.change_filter_remove", comment: ""), JourneyTexts.changeFilterAccessible(journeys.changeFilter)
            )))
        }
        NavigationLink {
            TravelPreferencesView()
        } label: {
            Label("settings.travel.title", systemImage: "slider.horizontal.3")
        }
        if journeys.preferences.wheelchairOnly {
            Text("settings.travel.wheelchair_only")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        if hiddenTrains > 0 {
            Text(verbatim: String.localizedStringWithFormat(NSLocalizedString("result.journey.hidden_by_preferences", comment: ""), hiddenTrains))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

/// «Ultimo di oggi» (Android's `LastOfDayChip`): the last departure of the day in the timetable.
struct LastOfDayMark: View {
    var body: some View {
        Label("result.journey.last_of_day", systemImage: "moon.stars.fill")
            .font(.caption.weight(.semibold))
            .foregroundStyle(.primary)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(Capsule().fill(Color.secondary.opacity(0.15)))
    }
}

/// The text, then its icon.
private struct TrailingIconLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 6) {
            configuration.title
            configuration.icon
        }
    }
}

/// One journey of the planner among the results (Android's `JourneyResultCard`): times, duration, the vehicles, the
/// source, a long wait, the risk of a change, «Ultimo di oggi», accessibility, bikes, the statistics, the changes.
struct JourneyResultRow: View {
    let row: JourneyOptionRow
    let reliability: JourneyPlanReliability?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: "\(JourneyTexts.time(row.departure)) → \(JourneyTexts.time(row.arrival))")
                    .font(.headline.monospacedDigit())
                Spacer()
                Text(verbatim: JourneyTexts.duration(row.durationMinutes))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            if JourneyTexts.arrivesNextDay(row) {
                Text("result.journey.arrival_next_day")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(verbatim: JourneyTexts.trainsLabel(row))
                .font(.subheadline.weight(.medium))
            if row.hasLongWait {
                Text(verbatim: JourneyTexts.wait(row.longestWaitMinutes))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(BinarioPalette.statusError)
            }
            Text("result.journey.source_offline")
                .font(.caption)
                .foregroundStyle(.secondary)
            if let risk = JourneyTexts.risk(row.transferRisk, rowLevel: true) {
                Label(risk, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(BinarioPalette.statusError)
            }
            HStack(spacing: 10) {
                Text(verbatim: JourneyTexts.changes(row.changes))
                    .font(.caption.weight(.semibold))
                if row.lastOfDay {
                    LastOfDayMark()
                }
                if row.wheelchairAccessible {
                    Image(systemName: "figure.roll")
                        .accessibilityLabel(Text("result.journey.wheelchair_journey"))
                }
                if let bikes = row.bikesAllowed {
                    BikesLabel(allowed: bikes)
                        .font(.caption)
                }
            }
            .foregroundStyle(.secondary)
            if let reliability {
                ForEach(JourneyTexts.reliabilityLines(reliability, row: row), id: \.self) { line in
                    Label(line, systemImage: "chart.bar")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// A journey with changes (Android's `JourneyDetailScreen`): every leg with its departure and arrival, its vehicle,
/// operator, accessibility, bikes and the «Resta a bordo» notes; each change with its wait and, with live data, its
/// risk; the walks; the source with the scheduled-only notice.
struct JourneyDetailView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var journeys: JourneysModel
    /// The journey as it was opened; the model's copy (with the live legs that arrive meanwhile) is shown.
    let opened: JourneyOptionRow
    let openedReliability: JourneyPlanReliability?

    init(row: JourneyOptionRow, reliability: JourneyPlanReliability?) {
        opened = row
        openedReliability = reliability
    }

    private var row: JourneyOptionRow { journeys.rows.first { $0.id == opened.id } ?? opened }

    private var reliability: JourneyPlanReliability? { journeys.reliability[opened.id] ?? openedReliability }

    var body: some View {
        let row = self.row
        let reliability = self.reliability
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: JourneyTexts.changes(row.changes).uppercased())
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Text(verbatim: "\(row.legs.first?.originLabel ?? "") → \(row.legs.last?.destinationLabel ?? "")")
                        .font(.title3.weight(.semibold))
                    HStack {
                        Text(verbatim: "\(JourneyTexts.time(row.departure)) → \(JourneyTexts.time(row.arrival)) · \(JourneyTexts.duration(row.durationMinutes))")
                            .font(.subheadline.monospacedDigit())
                        if JourneyTexts.arrivesNextDay(row) {
                            Text("result.journey.arrival_next_day")
                                .font(.caption)
                        }
                    }
                    .foregroundStyle(.secondary)
                    if row.wheelchairAccessible {
                        Label("result.journey.wheelchair_journey", systemImage: "figure.roll")
                            .font(.caption)
                    }
                }
            }
            Section {
                ForEach(Array(row.legs.enumerated()), id: \.offset) { index, leg in
                    JourneyLegView(leg: leg, live: row.live[index])
                    if let transfer = row.transfers.first(where: { $0.afterLegIndex == index }) {
                        JourneyTransferView(transfer: transfer)
                    }
                }
            }
            if let reliability {
                Section {
                    ForEach(JourneyTexts.reliabilityLines(reliability, row: row), id: \.self) { line in
                        Label(line, systemImage: "chart.bar")
                            .font(.footnote)
                    }
                }
            }
            Section("result.journey.source_title") {
                Text("result.journey.source_offline")
                Text("result.journey.scheduled_only")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text("result.journey.coverage")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(Text(verbatim: JourneyTexts.changes(row.changes)))
        .navigationBarTitleDisplayMode(.inline)
        // The trains of this journey looked up first, at most once a minute while it is open (JourneyLiveRefresh).
        .task(id: opened.id) { await followLive() }
        .refreshable { await refreshLive() }
    }

    private func followLive() async {
        while !Task.isCancelled {
            journeys.refreshLive(live: model.tripLiveData, focusedID: opened.id)
            try? await Task.sleep(nanoseconds: 30_000_000_000)
        }
    }

    private func refreshLive() async {
        journeys.refreshLive(live: model.tripLiveData, focusedID: opened.id, force: true)
    }
}

/// «Più presto» above the results and «Più tardi» below them (Android's `ResultsPageControl`): a button, a progress
/// bar while a window loads, and «Nessun’altra soluzione …» when every source looked and found nothing new.
struct ResultsPageControl: View {
    let later: Bool
    let loading: Bool
    let nothingNew: Bool
    let load: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if loading {
                ProgressView()
                    .frame(maxWidth: .infinity)
            } else if later {
                Button(action: load) {
                    Label("result.paging.later", systemImage: "chevron.down")
                }
            } else {
                Button(action: load) {
                    Label("result.paging.earlier", systemImage: "chevron.up")
                }
            }
            if nothingNew, later {
                Text("result.paging.no_more_later")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else if nothingNew {
                Text("result.paging.no_more_earlier")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// One leg: its vehicle and times, or a walk.
private struct JourneyLegView: View {
    let leg: JourneyPlanLeg
    let live: JourneyLegLive?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Image(systemName: JourneyTexts.symbol(leg))
                    .foregroundStyle(BinarioPalette.brandGreen)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: JourneyTexts.legLabel(leg).uppercased())
                        .font(.subheadline.weight(.bold))
                    if !leg.isWalk, let operatorName = leg.operatorName, !operatorName.isEmpty {
                        Text(verbatim: operatorName)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if leg.wheelchair?.vehicle == true, !leg.isWalk {
                    Image(systemName: "figure.roll")
                        .accessibilityLabel(Text("result.journey.wheelchair_trip"))
                }
                if let bikes = leg.bikesAllowed {
                    BikesLabel(allowed: bikes)
                        .font(.caption)
                        .labelStyle(.iconOnly)
                }
                Text(verbatim: JourneyTexts.duration(leg.durationMinutes))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            JourneyStopLine(time: leg.departure, expected: live?.cancelled == true ? nil : live?.expectedDeparture, station: leg.originLabel)
            ForEach(leg.continuations, id: \.self) { continuation in
                Label {
                    Text(verbatim: "\(continuation.stationLabel) · \(JourneyTexts.inSeat(continuation))")
                } icon: {
                    Image(systemName: "chair")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            JourneyStopLine(time: leg.arrival, expected: live?.cancelled == true ? nil : live?.expectedArrival, station: leg.destinationLabel)
        }
        .accessibilityElement(children: .combine)
    }
}

/// «08:12 Milano Centrale», with the expected time when a live source published one.
private struct JourneyStopLine: View {
    let time: Date
    let expected: Date?
    let station: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(verbatim: JourneyTexts.time(time))
                .font(.body.monospacedDigit())
                .strikethrough(expected.map { Int($0.timeIntervalSince(time) / 60) != 0 } ?? false)
            if let expected, Int(expected.timeIntervalSince(time) / 60) != 0 {
                Text(verbatim: JourneyTexts.time(expected))
                    .font(.body.monospacedDigit().weight(.semibold))
                    .foregroundStyle(BinarioPalette.statusError)
            }
            Text(verbatim: station)
        }
    }
}

/// «Cambio a Milano Centrale · Attesa di 15 minuti», with the arrival and the next departure and, with live data,
/// «Coincidenza a rischio» and the expected times.
private struct JourneyTransferView: View {
    let transfer: JourneyTransferRow

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Label {
                Text(verbatim: "\(JourneyTexts.transferAt(transfer.stationLabel)) · \(JourneyTexts.wait(transfer.waitMinutes))")
                    .font(.subheadline.weight(.semibold))
            } icon: {
                Image(systemName: "arrow.left.arrow.right")
                    .foregroundStyle(BinarioPalette.statusError)
            }
            Text(verbatim: "\(JourneyTexts.time(transfer.arrival)) → \(JourneyTexts.time(transfer.departure))")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            if let risk = JourneyTexts.risk(transfer.risk) {
                Text(verbatim: risk)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(transfer.risk == .ok ? Color.secondary : BinarioPalette.statusError)
            }
            if let arrival = transfer.expectedArrival, let departure = transfer.expectedDeparture {
                Text(verbatim: JourneyTexts.liveTimes(arrival: arrival, departure: departure))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

/// The notices of the journeys with changes under the results: the search running, why nothing was found, an
/// incomplete or expired timetable, the accessibility data missing around the ends.
struct JourneyNoticesView: View {
    @EnvironmentObject private var journeys: JourneysModel
    /// Some entry of the merged list is shown.
    let hasEntries: Bool

    var body: some View {
        if journeys.state == .loading {
            HStack {
                ProgressView()
                Text("result.journey.searching")
            }
            .font(.footnote)
        } else if let result = journeys.result {
            if result.accessibilityDataUnavailable {
                Text("result.journey.accessibility_unavailable")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if result.options.isEmpty, !hasEntries {
                Text(verbatim: JourneyTexts.empty(result.status, hasOptions: false))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if result.incompleteCoverage {
                Text("result.journey.incomplete")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if result.staleTimetables, !result.options.isEmpty {
                Text("result.journey.empty.schedule_expired")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
