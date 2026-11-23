import SwiftUI
import BinarioKit

/// «Arrivi» of «Partenze», Android's arrivals board: the trains arriving at the station as its live
/// boards list them (RFI's monitor and ViaggiaTreno, merged as the departures are; Italo publishes no
/// arrivals), only with «Dati in tempo reale» on, as on Android, where the board has no programmed
/// arrivals. Asked with the board's live request: when «Partenze» appears, comes back to the
/// foreground, changes station or kind, or is pulled. Each row reads as one sentence and opens the
/// run's stops (`BoardRunDetailView`).
struct ArrivalsContent: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let rows = model.boardArrivals
        if model.boardStation == nil {
            Section {
                ContentUnavailableView(
                    "planner.choose_station",
                    systemImage: "list.bullet.rectangle",
                    description: Text("departures.empty.body")
                )
            }
        } else if rows.isEmpty {
            Section {
                switch model.boardLiveState {
                case .loading:
                    ContentUnavailableView(
                        "departures.arrivals.loading.title",
                        systemImage: "clock",
                        description: Text("departures.arrivals.loading.body")
                    )
                case .unavailable:
                    ContentUnavailableView(
                        "departures.arrivals.unavailable.title",
                        systemImage: "exclamationmark.triangle",
                        description: Text("departures.arrivals.unavailable.body")
                    )
                case .available:
                    ContentUnavailableView(
                        "departures.arrivals.empty.title",
                        systemImage: "moon.zzz",
                        description: Text("departures.arrivals.empty.body")
                    )
                case .idle, .off:
                    // The values shown expire after five minutes: the board is asked again on a pull.
                    Button {
                        Task { await model.pullBoardLive() }
                    } label: {
                        Label("action.refresh", systemImage: "arrow.clockwise")
                    }
                }
                LiveSourcesFooter(state: .idle, sources: [], pauses: model.liveSourcePauses)
            }
        } else {
            Section {
                ForEach(rows, id: \.arrivalsListID) { row in
                    NavigationLink {
                        BoardRunDetailView(row: row, kind: .arrivals, stationName: model.boardStation?.name ?? "")
                    } label: {
                        ArrivalRow(row: row, live: row.stationBoardLiveStatus(at: Date()))
                    }
                    .accessibilityHint(Text("accessibility.open_train_detail"))
                }
                LiveSourcesFooter(state: model.boardLiveState, sources: model.boardLiveSources, pauses: model.liveSourcePauses)
            } header: {
                Text("departures.kind.arrivals")
            }
        }
    }
}

/// One arriving train: its scheduled arrival, category and number, its live state («prev. 10:36 ·
/// +6 min», «Soppresso»), where it comes from («Da Viterbo»), its operator and platform («Bin. 7
/// (era 5)» when the source moved it). VoiceOver reads it as one sentence.
struct ArrivalRow: View {
    let row: LiveDeparture
    let live: StationBoardLiveStatus?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(LiveTexts.clock(row.scheduled))
                    .font(.headline.monospacedDigit())
                Text(verbatim: ArrivalsTexts.train(row))
                    .font(.headline)
                Spacer()
                if let platform = ArrivalsTexts.platform(row, live: live) {
                    Text(verbatim: platform)
                        .font(.subheadline.weight(.semibold))
                }
            }
            if let live {
                LiveStatusLine(status: live)
            }
            Text(ArrivalsTexts.origin(row.destination))
            if let name = ArrivalsTexts.operatorName(row.operatorID) {
                Text(verbatim: name)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(ArrivalsTexts.spoken(row, live: live)))
    }
}

/// The run of a live board row, Android's «Dettagli corsa»: the row as the board shows it, the run's
/// ends and source, then every stop as ViaggiaTreno publishes it. Asked when the detail appears and
/// when it is pulled; the row stays when the run cannot be had.
struct BoardRunDetailView: View {
    @EnvironmentObject private var model: AppModel
    let row: LiveDeparture
    let kind: LiveBoardKind
    let stationName: String
    @State private var details: LiveBoardRunDetails?
    @State private var loading = true

    var body: some View {
        List {
            Section {
                ArrivalRow(row: row, live: row.stationBoardLiveStatus(at: Date()))
                if let details {
                    Text(verbatim: "\(details.run.originName) → \(details.run.destinationName)")
                    Text(LiveTexts.provenance(details).joined(separator: " · "))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text(verbatim: stationName)
            }
            if details == nil {
                Section {
                    if loading {
                        HStack {
                            ProgressView()
                            Text("departures.run.loading")
                        }
                    } else {
                        Text("departures.run.unavailable")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            if let details {
                LiveStopsSection(stops: details.run.stops)
            }
        }
        .navigationTitle(Text("departures.run.title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                // Pull to refresh, as a button VoiceOver finds.
                Button {
                    Task { await load() }
                } label: {
                    Label("action.refresh", systemImage: "arrow.clockwise")
                }
                .disabled(loading)
            }
        }
        .refreshable { await load() }
        .task { await load() }
    }

    /// The run again, through the sources' gates; the stops already read stay while it is read.
    private func load() async {
        loading = true
        let result = await model.boardRun(row, kind: kind)
        if Task.isCancelled { return }
        loading = false
        // A failed read keeps the stops already shown, as Android's details while they are read again.
        if case let .available(found)? = result { details = found }
    }
}

/// The texts of «Arrivi»: Android's (`station_board_*`) wherever it has them.
enum ArrivalsTexts {
    /// «Da Viterbo».
    static func origin(_ name: String) -> String {
        String.localizedStringWithFormat(NSLocalizedString("departures.arrivals.from", comment: ""), name)
    }

    /// «REG 12345», or the number alone without a category.
    static func train(_ row: LiveDeparture) -> String {
        let category = singleSpaced(row.category)
        let number = singleSpaced(row.number)
        return category.isEmpty || number.hasPrefix(category) ? number : "\(category) \(number)"
    }

    private static func singleSpaced(_ value: String) -> String {
        value.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    /// «Bin. 7», or «Bin. 7 (era 5)» when the source moved the train from its timetable platform.
    static func platform(_ row: LiveDeparture, live: StationBoardLiveStatus?) -> String? {
        guard let platform = live?.platform else { return nil }
        if let scheduled = row.scheduledPlatform, !scheduled.isEmpty,
           scheduled.caseInsensitiveCompare(platform) != .orderedSame {
            return String.localizedStringWithFormat(NSLocalizedString("departures.arrivals.platform_changed", comment: ""), platform, scheduled)
        }
        return DeparturesTexts.platform(platform)
    }

    /// The operator the source names, as Binario names it; nil when unknown.
    static func operatorName(_ operatorID: String?) -> String? {
        switch operatorID {
        case "trenitalia": return "Trenitalia"
        case "trenord": return "Trenord"
        case "italo": return "Italo"
        default: return nil
        }
    }

    /// The row as one sentence, as Android's `StationBoardEntrySpeech`: «Da Viterbo. Orario programmato
    /// 10:30», the live state and its provenance, «Binario 7» (or «Binario cambiato: 7, previsto 5»),
    /// «Treno REG 12345», then the operator.
    static func spoken(_ row: LiveDeparture, live: StationBoardLiveStatus?) -> String {
        var parts: [String] = [
            origin(row.destination),
            String.localizedStringWithFormat(NSLocalizedString("departures.arrivals.scheduled_spoken", comment: ""), LiveTexts.clock(row.scheduled)),
        ]
        if let live {
            parts += LiveTexts.spoken(live, includingPlatform: false)
        }
        if let platform = live?.platform {
            if let scheduled = row.scheduledPlatform, !scheduled.isEmpty, scheduled.caseInsensitiveCompare(platform) != .orderedSame {
                parts.append(String.localizedStringWithFormat(
                    NSLocalizedString("departures.arrivals.platform_changed_spoken", comment: ""), platform, scheduled
                ))
            } else {
                parts.append(String.localizedStringWithFormat(NSLocalizedString("departures.arrivals.platform_spoken", comment: ""), platform))
            }
        } else {
            parts.append(NSLocalizedString("departures.arrivals.platform_unknown", comment: ""))
        }
        parts.append(String.localizedStringWithFormat(NSLocalizedString("departures.arrivals.number", comment: ""), train(row)))
        if let name = operatorName(row.operatorID) { parts.append(name) }
        return parts.joined(separator: ". ")
    }
}

extension LiveDeparture {
    /// One row of a live board: a source may list a number twice in a day.
    var arrivalsListID: String { "\(id)|\(scheduled.timeIntervalSince1970)" }
}

extension LiveTexts {
    /// «Fonte: ViaggiaTreno · Rilevato alle 10:43», the provenance of a board row's run.
    static func provenance(_ details: LiveBoardRunDetails) -> [String] {
        [fromSource(details.run.observation.source), observedAt(details.run.observation.observedAt)]
    }
}
