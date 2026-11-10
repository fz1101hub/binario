import SwiftUI
import BinarioKit

/// A listed train: its programmed departure, bikes on board when the timetable says, its live
/// state when it leaves within the live window (the run as ViaggiaTreno or Italo publish it: state,
/// delay, expected departure and platform at the boarding station, the last observation, every
/// stop with its expected and actual times, passed and cancelled stops), and the «Affidabilità
/// ultimi N giorni» block of the unofficial network statistics at the searched destination. Every
/// line is a sentence of its own; nothing is said by colour alone. The run is asked when the detail
/// appears and when it is pulled; until it arrives, the live values of the row it was opened from
/// stand in, and the run's own state wins over them. The star of the navigation bar saves the
/// train in «I miei treni» or removes it.
struct TrainDetailView: View {
    @EnvironmentObject private var model: AppModel
    let service: TrainService
    let query: JourneyQuery
    /// The live values of the row the detail was opened from («Partenze», the planner).
    var boardLive: StationBoardLiveStatus? = nil
    /// Why the star did nothing: «I miei treni» is full, or could not be written.
    @State private var favouriteMessage = ""
    @State private var showsFavouriteMessage = false
    /// The run asked about, set when the detail appears; nil outside the live window.
    @State private var liveKey: AppModel.LiveTrainKey? = nil

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(service.scheduledDeparture)
                            .font(.title3.monospacedDigit().weight(.semibold))
                        Text(service.number)
                            .font(.title3.weight(.semibold))
                        Spacer()
                        Text(service.category)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(verbatim: "\(query.origin.name) → \(service.destination)")
                    Text(service.operatorName)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
                if let bikesAllowed = service.bikesAllowed {
                    BikesLabel(allowed: bikesAllowed)
                }
                if liveRun == nil, shownBoardLive == nil {
                    Label("result.programmed.disclosure", systemImage: "clock")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            if let liveRun {
                LiveRunSection(status: liveRun, pauses: model.liveSourcePauses)
                LiveStopsSection(stops: liveRun.run.stops)
            } else if model.isLiveDataActive, liveKey != nil || shownBoardLive != nil {
                LiveFallbackSection(
                    boardLive: shownBoardLive,
                    state: liveKey.map { model.liveRunState(for: $0) } ?? .idle,
                    pauses: model.liveSourcePauses
                )
            }

            if let detail = model.reliabilityDetail(for: service, query: query) {
                NetworkReliabilitySection(detail: detail, query: query, service: service)
            }
        }
        .navigationTitle(Text(verbatim: service.number))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if model.canToggleFavourite(service, query: query) {
                    FavouriteTrainButton(saved: model.isFavourite(service, query: query)) { toggleFavourite() }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                if model.isLiveDataActive, liveKey != nil {
                    // Pull to refresh, as a button VoiceOver finds.
                    Button {
                        Task { await refreshLive() }
                    } label: {
                        Label("action.refresh", systemImage: "arrow.clockwise")
                    }
                }
            }
        }
        .refreshable { await refreshLive() }
        .onAppear {
            let key = model.liveTrainKey(for: service, query: query)
            liveKey = key
            if let key { model.detailLiveDidAppear(key) }
        }
        .onDisappear {
            if let liveKey { model.detailLiveDidDisappear(liveKey) }
        }
        .alert(favouriteMessage, isPresented: $showsFavouriteMessage) {
            Button("action.ok", role: .cancel) { showsFavouriteMessage = false }
        }
    }

    /// The run's own live state, which wins over the row's.
    private var liveRun: AppModel.TrainLiveStatus? {
        guard let liveKey else { return nil }
        return model.liveRun(for: liveKey)
    }

    /// The row's live values while the run's are missing, with «Dati in tempo reale» on and for as
    /// long as any live value is shown.
    private var shownBoardLive: StationBoardLiveStatus? {
        guard model.isLiveDataActive, let boardLive,
              AppModel.isLiveDisplayable(since: boardLive.observedAt, now: Date()) else { return nil }
        return boardLive
    }

    private func refreshLive() async {
        guard let liveKey else { return }
        await model.pullDetailLive(liveKey)
    }

    private func toggleFavourite() {
        switch model.toggleFavourite(service, query: query) {
        case .added, .removed:
            return
        case .limitReached:
            favouriteMessage = MyTrainsTexts.limitError(FavouriteTrainCollection.maximumTrains)
        case .failed:
            favouriteMessage = String(localized: "saved.trains.storage_error")
        }
        showsFavouriteMessage = true
    }
}

/// «In tempo reale»: the run at a glance («In ritardo di 5 minuti», «Soppresso»…), its expected
/// departure and platform at the boarding station, the last place and time the source observed
/// it, and where the values come from.
private struct LiveRunSection: View {
    let status: AppModel.TrainLiveStatus
    let pauses: [LiveSourcePause]

    var body: some View {
        let run = status.run
        Section {
            Label {
                Text(LiveTexts.runState(status).joined(separator: " · "))
            } icon: {
                Image(systemName: LiveTexts.isDisrupted(status) ? "exclamationmark.circle" : "dot.radiowaves.left.and.right")
            }
            .font(.body.weight(.semibold))
            .foregroundStyle(LiveTexts.isDisrupted(status) ? BinarioPalette.statusError : Color.primary)
            if let departure = LiveTexts.boardingDeparture(status.boarding), !departure.actual {
                LabeledContent("result.live.expected_departure") {
                    Text(LiveTexts.clock(departure.time))
                        .monospacedDigit()
                }
            }
            if let platform = status.boarding.platform, !status.boarding.skipped {
                LabeledContent("result.live.platform") {
                    Text(verbatim: platform)
                }
            }
            if let point = run.lastObservation {
                LabeledContent("result.live.last_observation") {
                    Text(LiveTexts.lastObservation(point))
                        .multilineTextAlignment(.trailing)
                }
            }
            Text(LiveTexts.provenance(status).joined(separator: " · "))
                .font(.footnote)
                .foregroundStyle(.secondary)
            LiveSourcesFooter(state: .idle, sources: [], pauses: pauses)
        } header: {
            Label("result.live.title", systemImage: "dot.radiowaves.left.and.right")
        }
    }
}

/// «Fermate»: every stop of the run with its scheduled time, the source's actual or expected time
/// and delay, its platform, and whether the train has passed it or it is cancelled.
private struct LiveStopsSection: View {
    let stops: [LiveStopStatus]

    var body: some View {
        if !stops.isEmpty {
            Section("result.live.stops") {
                ForEach(stops.indices, id: \.self) { index in
                    LiveStopRow(stop: stops[index])
                }
            }
        }
    }
}

private struct LiveStopRow: View {
    let stop: LiveStopStatus

    var body: some View {
        let parts = LiveTexts.stopParts(stop)
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: stop.skipped ? "xmark.circle" : (stop.passed ? "checkmark.circle.fill" : "circle"))
                .foregroundStyle(stop.skipped ? BinarioPalette.statusError : Color.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: stop.stationName)
                    .strikethrough(stop.skipped)
                if !parts.isEmpty {
                    Text(parts.joined(separator: " · "))
                        .font(.caption)
                        .foregroundStyle(stop.skipped ? BinarioPalette.statusError : Color.secondary)
                }
            }
            Spacer()
            Text(LiveTexts.clock(stop.scheduled))
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(LiveTexts.spokenStop(stop)))
    }
}

/// Without the run's own state: the live values of the row the detail was opened from, with their
/// source, and whether the run is being asked or no source has it.
private struct LiveFallbackSection: View {
    let boardLive: StationBoardLiveStatus?
    let state: AppModel.LiveState
    let pauses: [LiveSourcePause]

    var body: some View {
        Section {
            if let boardLive {
                LiveStatusLine(status: boardLive, showsPlatform: true)
            }
            // The row's values stay meaningful when the run cannot be had.
            LiveSourcesFooter(state: boardLive != nil && state == .unavailable ? .idle : state, sources: [], pauses: pauses)
        } header: {
            Label("result.live.title", systemImage: "dot.radiowaves.left.and.right")
        }
    }
}

/// The star of a train detail: «Salva in I miei treni» or «Rimuovi da I miei treni», which is
/// what VoiceOver reads; the filled star says the train is saved.
private struct FavouriteTrainButton: View {
    let saved: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            Label(
                LocalizedStringKey(saved ? "result.favourite.remove" : "result.favourite.add"),
                systemImage: saved ? "star.fill" : "star"
            )
        }
    }
}

private struct NetworkReliabilitySection: View {
    @EnvironmentObject private var model: AppModel
    let detail: NetworkReliabilityDetail
    let query: JourneyQuery
    let service: TrainService

    var body: some View {
        let arrival = detail.arrival
        Section {
            if arrival.cancelledEveryDay {
                // Cancelled on every day observed: no delays to measure.
                Text(ReliabilityTexts.cancelledShare(detail.daysCancelled, of: detail.daysObserved))
                    .font(.body.weight(.semibold))
            } else {
                Text(ReliabilityTexts.arrivalOnTime(arrival.onTime, of: arrival.samples, at: arrivalName))
                    .font(.body.weight(.semibold))
                Text(ReliabilityTexts.detailMeanDelay(arrival.shownMeanDelayMinutes))
                Text(ReliabilityTexts.daysObserved(detail.daysObserved, cancelled: detail.daysCancelled))
                if let distribution = detail.distribution {
                    DelayDistributionRows(distribution: distribution)
                }
                if let maxDelay = detail.maxDelayMinutes {
                    Text(ReliabilityTexts.maxDelay(maxDelay))
                }
            }
            if let worst = detail.worstStop, let name = model.stationName(forReliabilityCode: worst.stationCode) {
                Text(ReliabilityTexts.worstStop(name, increase: worst.increaseMinutes))
            }
            if let platform = detail.usualPlatform {
                Text(ReliabilityTexts.usualPlatform(platform, at: stationName(platform.stationCode, fallback: query.origin.name)))
            }
            ForEach(detail.weekdayRemarks, id: \.weekday) { remark in
                Text(ReliabilityTexts.weekday(remark))
            }
            if !arrival.cancelledEveryDay {
                Text(ReliabilityTexts.thresholdNote(arrival.onTimeLimitMinutes))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Text(ReliabilityTexts.attribution(detail.windowEnd))
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Label(ReliabilityTexts.detailTitle(days: detail.windowDays), systemImage: "chart.bar")
        }
    }

    /// The stop measured, as the search names it: the destination, else the catalogue name of its
    /// code (a train matched at its terminus), else the train's destination.
    private var arrivalName: String {
        if detail.arrival.stationCode == NetworkReliability.stationCode(query.destination) {
            return query.destination.name
        }
        return model.stationName(forReliabilityCode: detail.arrival.stationCode) ?? service.destination
    }

    private func stationName(_ code: String, fallback: String) -> String {
        if code == NetworkReliability.stationCode(query.origin) { return query.origin.name }
        return model.stationName(forReliabilityCode: code) ?? fallback
    }
}

/// «Distribuzione dei ritardi»: one row per band with its share and count, read as one sentence.
private struct DelayDistributionRows: View {
    let distribution: DelayDistribution

    var body: some View {
        let rows = ReliabilityTexts.distributionRows(distribution)
        VStack(alignment: .leading, spacing: 4) {
            Text("result.detail.distribution_title")
                .font(.subheadline.weight(.semibold))
            ForEach(rows.indices, id: \.self) { index in
                LabeledContent(rows[index].label, value: rows[index].value)
                    .font(.subheadline)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
