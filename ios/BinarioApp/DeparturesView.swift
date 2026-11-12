import SwiftUI
import BinarioKit
#if canImport(UIKit)
import UIKit
#endif

/// «Partenze»: the next trains leaving a station, from the bundled timetable (the first
/// `StationBoardProvider`), with what the live board of the station (RFI, Italo and ViaggiaTreno,
/// merged as on Android) observed of them: expected time and delay, cancellation, actual platform,
/// source and time of the observation. The live board is asked when the board appears, comes back
/// to the foreground, changes station or is pulled, never by itself. Each row reads as one sentence,
/// time, train, destination, platform and live state, and opens the train detail, whose star saves
/// the train boarded at this station. The iPhone's «orario programmato» disclosure is shown while a
/// listed row has no live value. No network reliability per row: Android's station board shows none.
struct DeparturesView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    /// The minute the board is read at; the timetable-zone caption follows it.
    @State private var clock = Date()
    /// The app went to the background since the board was read: it is read again from now when
    /// the app comes back. Back from «inactive» only (Control Centre, a notification pulled down)
    /// the list keeps its «Più tardi» pages and its scroll position; only the trains that left go.
    @State private var returnsFromBackground = false

    var body: some View {
        Form {
            switch model.catalogState {
            case .loading:
                Section {
                    HStack {
                        ProgressView()
                        Text("catalog.loading")
                    }
                }
            case .available:
                Section {
                    StationPickerField(titleKey: "departures.station", selection: stationSelection)
                    // The board lists times in the timetable zone, as the planner does.
                    if AppModel.needsTimetableZoneCaption(at: clock) {
                        Text("planner.departure.italian_time")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } footer: {
                    Text("departures.local_only")
                }
                BoardContent()
            case .unavailable:
                ContentUnavailableView(
                    "catalog.unavailable.title",
                    systemImage: "exclamationmark.triangle",
                    description: Text("catalog.unavailable.body")
                )
            }
        }
        .navigationTitle("departures.title")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if model.isLiveDataActive, model.boardStation != nil {
                    // Pull to refresh, as a button VoiceOver finds.
                    Button {
                        Task { await refresh() }
                    } label: {
                        Label("action.refresh", systemImage: "arrow.clockwise")
                    }
                    .disabled(model.boardLiveState == .loading)
                }
            }
        }
        .refreshable { await refresh() }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background:
                returnsFromBackground = true
            case .active:
                clock = Date()
                if returnsFromBackground {
                    returnsFromBackground = false
                    // Back in the app: the board is read again from now (its live board by RootView).
                    Task { await model.reloadBoard() }
                } else {
                    Task { await model.refreshBoardIfNeeded() }
                }
            default:
                break
            }
        }
        .onAppear { model.boardLiveDidAppear() }
        .onDisappear { model.boardLiveDidDisappear() }
        .task { await followTheClock() }
    }

    /// The board again from now, and its live board through the sources' gates.
    private func refresh() async {
        clock = Date()
        await model.reloadBoard()
        await model.pullBoardLive()
    }

    /// Choosing a station shows its board from now and remembers it on this iPhone.
    private var stationSelection: Binding<Station?> {
        Binding(
            get: {
                guard let identity = model.boardStation else { return nil }
                return model.station(for: identity)
            },
            set: { station in
                guard let station, station.id != model.boardStation?.id else { return }
                model.selectBoardStation(StationIdentity(station))
            }
        )
    }

    /// Every new minute while the board is on screen: the trains that have left go, and the board
    /// is read again when it has to be (`AppModel.refreshBoardIfNeeded`). The task ends when the
    /// board leaves the screen and starts again, from the current time, when it comes back.
    private func followTheClock() async {
        while !Task.isCancelled {
            let current = Date()
            clock = current
            await model.refreshBoardIfNeeded(now: current)
            let seconds = 60 - current.timeIntervalSince1970.truncatingRemainder(dividingBy: 60)
            try? await Task.sleep(nanoseconds: UInt64((seconds + 0.5) * 1_000_000_000))
        }
    }
}

/// The board under the station: its empty, loading, unavailable and «nessun treno» states, or
/// its trains.
private struct BoardContent: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if model.boardStation == nil {
            Section {
                ContentUnavailableView(
                    "planner.choose_station",
                    systemImage: "list.bullet.rectangle",
                    description: Text("departures.empty.body")
                )
            }
        } else if model.scheduleState == .loading || model.boardState == .loading || model.boardState == .idle {
            Section {
                HStack {
                    ProgressView()
                    Text(LocalizedStringKey(model.scheduleState == .loading ? "schedule.loading" : "departures.loading"))
                }
            }
        } else {
            switch model.boardState {
            case let .unavailable(reason):
                // Live first: a live board answers even where the bundled timetable cannot.
                if model.boardDepartures.isEmpty {
                    BoardUnavailableView(reason: reason)
                } else {
                    DepartureListSection()
                }
            case .available:
                if model.boardDepartures.isEmpty {
                    NoDeparturesView(firstTomorrow: model.boardFirstTomorrow, someTimetablesEnded: model.boardSomeTimetablesEnded)
                } else {
                    DepartureListSection()
                }
            case .idle, .loading:
                EmptyView()
            }
        }
    }
}

/// «Prossime partenze»: the trains in departure order, «Più tardi» for the next ones, the feeds of
/// the listed trains and the «orario programmato» disclosure.
private struct DepartureListSection: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Section {
            if model.boardSomeTimetablesEnded {
                ExpiredTimetablesNotice()
            }
            ForEach(model.boardDepartures) { departure in
                // As a planner row: the detail of the train, boarded at this station.
                if let query = model.boardDetailQuery(for: departure) {
                    NavigationLink {
                        TrainDetailView(service: departure.service, query: query, boardLive: departure.live)
                    } label: {
                        DepartureRow(departure: departure)
                    }
                    .accessibilityHint(Text("accessibility.open_train_detail"))
                } else {
                    DepartureRow(departure: departure)
                }
            }
            if model.boardHasMore {
                Button(action: loadMore) {
                    if model.boardIsLoadingMore {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                    } else {
                        Label("departures.later", systemImage: "chevron.down")
                    }
                }
                .disabled(model.boardIsLoadingMore)
            }
            BoardSourceFooter(rows: model.boardDepartures, attributions: model.boardAttributions)
            LiveSourcesFooter(state: model.boardLiveState, sources: model.boardLiveSources, pauses: model.liveSourcePauses)
        } header: {
            Text("departures.list.title")
        }
    }

    /// «Più tardi»: VoiceOver says how many trains were added below.
    private func loadMore() {
        Task {
            let added = await model.loadMoreBoard()
            guard added > 0 else { return }
            DeparturesTexts.announce(DeparturesTexts.loaded(added))
        }
    }
}

/// The feeds of the listed trains, and «Orari programmati statici. Non sono dati in tempo reale.»
/// while a row has no live value.
private struct BoardSourceFooter: View {
    let rows: [StationBoardDeparture]
    let attributions: [String]

    var body: some View {
        if !attributions.isEmpty {
            Text(verbatim: "\(String(localized: "result.source_label")) \(attributions.joined(separator: " + "))")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        if rows.contains(where: { $0.isScheduled }) {
            Label("result.programmed.disclosure", systemImage: "clock")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

/// One train: scheduled time and the train as the planner rows name it, its live state when a
/// source observed it («prev. 08:17 · +5 min», «Soppresso»), its destination (the trip's last stop),
/// the actual platform or the timetable's, the operator, bikes on board and where the live values
/// come from. VoiceOver reads «08:12, treno REG 2153 per Bergamo, binario 3. Previsto 08:17. In
/// ritardo di 5 minuti. Fonte: ViaggiaTreno. Rilevato alle 08:10», then the operator and bikes.
private struct DepartureRow: View {
    let departure: StationBoardDeparture

    var body: some View {
        let service = departure.service
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(service.scheduledDeparture)
                    .font(.headline.monospacedDigit())
                Text(ReliableAlternatives.trainLabel(service))
                    .font(.headline)
                Spacer()
                if let platform = departure.live?.platform ?? service.platform {
                    Text(DeparturesTexts.platform(platform))
                        .font(.subheadline.weight(.semibold))
                }
            }
            if let live = departure.live {
                LiveStatusLine(status: live)
            }
            Text(DeparturesTexts.destination(service.destination))
            Text(service.operatorName)
                .font(.caption)
                .foregroundStyle(.secondary)
            if let bikesAllowed = service.bikesAllowed {
                BikesLabel(allowed: bikesAllowed)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(DeparturesTexts.spoken(departure)))
    }
}

/// «Nessun treno in partenza»: no train of today's service day is left; with the first train of
/// the next one when the timetable has it, as on Android.
private struct NoDeparturesView: View {
    let firstTomorrow: StationBoardDeparture?
    let someTimetablesEnded: Bool

    var body: some View {
        Section {
            ContentUnavailableView(
                "departures.none.title",
                systemImage: "moon.zzz",
                description: Text(message)
            )
            if someTimetablesEnded {
                ExpiredTimetablesNotice()
            }
            if firstTomorrow?.isScheduled == true {
                Label("result.programmed.disclosure", systemImage: "clock")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var message: String {
        guard let firstTomorrow else { return String(localized: "departures.none.body") }
        return DeparturesTexts.noMoreToday(firstTomorrow.service.scheduledDeparture)
    }
}

/// «Orari scaduti» over a list: the timetables of one feed of the station have ended while another's
/// go on, so some of its trains are missing until the app is updated.
private struct ExpiredTimetablesNotice: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label("result.expired.title", systemImage: "calendar.badge.exclamationmark")
                .font(.subheadline.weight(.semibold))
            Text("departures.expired.body")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A station without timetable, timetables that end before today, or no valid index.
private struct BoardUnavailableView: View {
    let reason: JourneyUnavailableReason

    var body: some View {
        Section {
            ContentUnavailableView(
                titleKey,
                systemImage: reason == .providerInvalid || reason == .noAuthorizedProvider
                    ? "exclamationmark.triangle" : "calendar.badge.exclamationmark",
                description: Text(bodyKey)
            )
        }
    }

    private var titleKey: LocalizedStringKey {
        switch reason {
        case .noJourney: "planner.station.no_timetable"
        case .scheduleExpired: "result.expired.title"
        case .noAuthorizedProvider, .providerInvalid: "result.provider_error.title"
        }
    }

    private var bodyKey: LocalizedStringKey {
        switch reason {
        case .noJourney: "departures.not_covered.body"
        case .scheduleExpired: "departures.expired.body"
        case .noAuthorizedProvider, .providerInvalid: "result.provider_error.body"
        }
    }
}

/// The texts of «Partenze»; the counted one comes from Localizable.stringsdict.
enum DeparturesTexts {
    /// «Per Bergamo».
    static func destination(_ name: String) -> String {
        String.localizedStringWithFormat(NSLocalizedString("departures.row.destination", comment: ""), name)
    }

    /// «Bin. 3».
    static func platform(_ name: String) -> String {
        String.localizedStringWithFormat(NSLocalizedString("departures.row.platform", comment: ""), name)
    }

    /// «Non ci sono più treni oggi: primo domani alle 05:42».
    static func noMoreToday(_ time: String) -> String {
        String.localizedStringWithFormat(NSLocalizedString("departures.no_more_today", comment: ""), time)
    }

    /// «20 treni in più», what VoiceOver says after «Più tardi».
    static func loaded(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("departures.later.loaded", comment: ""), count)
    }

    /// The row as one sentence, «08:12, treno REG 2153 per Bergamo, binario non disponibile», then
    /// its live state and provenance when a source observed it, the operator and, when the
    /// timetable publishes it, bikes on board.
    static func spoken(_ departure: StationBoardDeparture) -> String {
        let service = departure.service
        let platform: String
        if let name = departure.live?.platform ?? service.platform {
            platform = String.localizedStringWithFormat(NSLocalizedString("departures.row.platform_spoken", comment: ""), name)
        } else {
            platform = NSLocalizedString("departures.row.platform_unknown", comment: "")
        }
        let sentence = String.localizedStringWithFormat(
            NSLocalizedString("departures.row.spoken", comment: ""),
            service.scheduledDeparture,
            ReliableAlternatives.trainLabel(service),
            service.destination,
            platform
        )
        var parts: [String] = [sentence]
        if let live = departure.live {
            parts += LiveTexts.spoken(live, includingPlatform: false)
        }
        parts.append(service.operatorName)
        if let bikesAllowed = service.bikesAllowed {
            parts.append(NSLocalizedString(bikesAllowed ? "result.bikes.allowed" : "result.bikes.not_allowed", comment: ""))
        }
        return parts.joined(separator: ". ")
    }

    /// Says `text` with VoiceOver, when it is running.
    @MainActor
    static func announce(_ text: String) {
        #if canImport(UIKit)
        UIAccessibility.post(notification: .announcement, argument: text)
        #endif
    }
}
