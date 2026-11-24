import SwiftUI
import BinarioKit

/// «I miei treni»: the trains saved with the star of the train detail, newest first, kept on this
/// iPhone only, with today's live state of the first eight leaving within the live window («In
/// ritardo di 7 minuti», «Soppresso», «In orario»), asked when the tab appears or is pulled and only
/// while it is on screen. A tap opens the train's detail as a planner row does; a swipe, or the
/// VoiceOver action, removes it. Below, «Affidabilità dei miei treni»: each of the first trains at
/// its last stop in the unofficial network statistics, hidden without them.
struct MyTrainsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var personal: PersonalModel
    @State private var showsStorageError = false
    /// The train being renamed (Android's «Rinomina»), and the name typed.
    @State private var renaming: FavouriteTrain?
    @State private var newName = ""

    var body: some View {
        Group {
            if model.favouriteTrains.isEmpty {
                ContentUnavailableView(
                    "saved.trains.empty.title",
                    systemImage: "star",
                    description: Text("saved.trains.empty.body")
                )
            } else {
                List {
                    Section {
                        ForEach(model.favouriteTrains) { train in
                            NavigationLink(value: train) {
                                VStack(alignment: .leading, spacing: 5) {
                                    if let name = personal.name(ofTrain: train.id) {
                                        Text(verbatim: name)
                                            .font(.headline)
                                    }
                                    FavouriteTrainHeading(train: train)
                                    if model.liveDataEnabled, let live = model.favouriteLive[train.id] {
                                        FavouriteLiveLine(status: live)
                                    }
                                }
                                .accessibilityElement(children: .combine)
                            }
                            .accessibilityHint(Text("accessibility.open_train_detail"))
                            .accessibilityAction(named: Text("result.favourite.remove")) {
                                remove([train.id])
                            }
                            .accessibilityAction(named: Text("action.rename")) { startRenaming(train) }
                            .contextMenu {
                                Button {
                                    startRenaming(train)
                                } label: {
                                    Label("action.rename", systemImage: "pencil")
                                }
                            }
                            .swipeActions(edge: .leading) {
                                Button {
                                    startRenaming(train)
                                } label: {
                                    Label("action.rename", systemImage: "pencil")
                                }
                                .tint(.accentColor)
                            }
                        }
                        .onDelete { offsets in
                            let trains = model.favouriteTrains
                            let ids: [String] = offsets.compactMap { index in
                                trains.indices.contains(index) ? trains[index].id : nil
                            }
                            remove(Set(ids))
                        }
                    } footer: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("saved.trains.local_only")
                            LiveSourcesFooter(
                                state: model.favouriteLiveState,
                                sources: favouriteLiveSources,
                                pauses: model.liveSourcePauses
                            )
                        }
                    }

                    // Android's «Affidabilità dei miei treni»: network, runs recorded here and trend.
                    MyTrainsReliabilitySection()
                }
            }
        }
        .navigationTitle("saved.trains.title")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if model.isLiveDataActive, !model.favouriteTrains.isEmpty {
                    // Pull to refresh, as a button VoiceOver finds.
                    Button {
                        Task { await refreshLive() }
                    } label: {
                        Label("action.refresh", systemImage: "arrow.clockwise")
                    }
                    .disabled(model.favouriteLiveState == .loading)
                }
            }
        }
        .refreshable { await refreshLive() }
        // Requests only while the tab is on screen: a detail opened from it asks its own run.
        .onAppear { model.favouritesLiveDidAppear() }
        .onDisappear { model.favouritesLiveDidDisappear() }
        // On the Group, not the List: a detail stays open when its train is removed from the list,
        // even the last one.
        .navigationDestination(for: FavouriteTrain.self) { train in
            FavouriteTrainDetail(train: train)
        }
        .alert("saved.trains.storage_error", isPresented: $showsStorageError) {
            Button("action.ok", role: .cancel) { showsStorageError = false }
        }
        // «Nome del treno»: a blank name removes the one given before.
        .alert("saved.trains.rename_title", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("saved.trains.name_label", text: $newName, prompt: Text(namePlaceholder))
            Button("action.save") {
                if let renaming { personal.renameTrain(id: renaming.id, name: newName) }
                renaming = nil
            }
            Button("action.cancel", role: .cancel) { renaming = nil }
        }
        // Today's runs the sources published, in the personal punctuality as on Android.
        .onChange(of: model.favouriteLive) { _, live in
            for status in live.values { personal.record(status) }
        }
    }

    private func refreshLive() async {
        await model.pullFavouritesLive()
    }

    private func startRenaming(_ train: FavouriteTrain) {
        newName = personal.name(ofTrain: train.id) ?? ""
        renaming = train
    }

    /// «Il mio treno delle 07:42».
    private var namePlaceholder: String {
        PersonalTexts.format("saved.trains.name_placeholder", renaming?.scheduledDeparture ?? "")
    }

    /// The sources of the live states shown, in Android's provider order.
    private var favouriteLiveSources: [LiveSource] {
        let used = Set(model.favouriteLive.values.map(\.run.observation.source))
        return LiveSource.allCases.filter { used.contains($0) }
    }

    /// Removing is always possible; only a list that cannot be written is reported.
    private func remove(_ ids: Set<String>) {
        guard !ids.isEmpty else { return }
        if !model.removeFavourites(ids: ids) {
            showsStorageError = true
        }
    }
}

/// A saved train opened from «I miei treni»: the detail of the planner, boarded where it was saved
/// and measured at the searched destination.
private struct FavouriteTrainDetail: View {
    let train: FavouriteTrain

    var body: some View {
        // A valid favourite always makes a query (its stations are checked when it is built).
        if let query = train.query {
            TrainDetailView(service: train.service, query: query)
        }
    }
}

/// A saved train as a planner row shows it: departure, number and category, then where it is
/// boarded and where it goes, and its operator.
private struct FavouriteTrainHeading: View {
    let train: FavouriteTrain

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(train.scheduledDeparture)
                    .font(.headline.monospacedDigit())
                Text(train.number)
                    .font(.headline)
                Spacer()
                Text(train.category)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(verbatim: "\(train.origin.name) → \(train.terminus)")
            Text(train.operatorName)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

/// Today's live state of a favourite: «In ritardo di 7 minuti», «Soppresso», «In orario» or
/// «Stato operativo non verificato», then where it comes from and when it was observed.
private struct FavouriteLiveLine: View {
    let status: AppModel.TrainLiveStatus

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Label {
                Text(LiveTexts.runState(status).joined(separator: " · "))
            } icon: {
                Image(systemName: LiveTexts.isDisrupted(status) ? "exclamationmark.circle" : "dot.radiowaves.left.and.right")
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(LiveTexts.isDisrupted(status) ? BinarioPalette.statusError : Color.primary)
            Text(LiveTexts.provenance(status).joined(separator: " · "))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

/// «Affidabilità dei miei treni» (Android's `MyTrainsReliabilitySection`): the first eight favourite
/// trains in their order, each with its figures at its last stop in the bundled network statistics,
/// its runs recorded on this iPhone in the last 30 days and the trend of the last four weeks against
/// the four before (`FavouriteTrainPunctuality`); a tap opens the train. The attribution closes it
/// whenever a network figure is shown.
private struct MyTrainsReliabilitySection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var personal: PersonalModel

    var body: some View {
        let trains = Array(model.favouriteTrains.prefix(FavouriteTrainPunctuality.maximumRows))
        let measured = model.favouriteTrainsReliability
        let today = PersonalModel.today()
        let from = today.adding(days: -(FavouriteTrainPunctuality.personalDays - 1)) ?? today
        if !trains.isEmpty {
            Section {
                Text("saved.trains.reliability.summary")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                ForEach(trains) { train in
                    let network = measured?.rows.first { $0.id == train.id }?.network
                    NavigationLink(value: train) {
                        MyTrainReliabilityRow(
                            train: train,
                            network: network,
                            arrivalName: network.flatMap { model.stationName(forReliabilityCode: $0.stationCode) },
                            recorded: FavouriteTrainPunctuality.local(personal.punctuality, trainNumber: train.number, from: from, to: today),
                            trend: FavouriteTrainPunctuality.trend(personal.punctuality, trainNumber: train.number, today: today)
                        )
                    }
                    .accessibilityHint(Text("accessibility.open_train_detail"))
                }
                if let reliability = model.favouriteTrainsReliability {
                    Text(ReliabilityTexts.attribution(reliability.windowEnd))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Label("saved.trains.reliability.title", systemImage: "chart.bar")
            }
        }
    }
}

/// One train of «Affidabilità dei miei treni»: the train as its row in «I miei treni», then its
/// network figures, its runs recorded on this iPhone, the trend or «Ancora nessun dato», read by
/// VoiceOver as one sentence that says the network figures are unofficial.
private struct MyTrainReliabilityRow: View {
    let train: FavouriteTrain
    let network: StopReliability?
    /// The catalogue name of the last stop, when the catalogue knows its code.
    let arrivalName: String?
    let recorded: LocalPunctuality?
    let trend: PunctualityTrend?

    var body: some View {
        let parts = network.map { MyTrainsTexts.networkParts($0, at: arrivalName) } ?? []
        let personalLine = recorded.map(MyTrainsTexts.recordedLine)
        let trendLine = trend.map { NSLocalizedString($0.better ? "saved.trains.reliability.better" : "saved.trains.reliability.worse", comment: "") }
        let noData: String? = network == nil && recorded == nil ? NSLocalizedString("saved.trains.reliability.no_data", comment: "") : nil
        VStack(alignment: .leading, spacing: 5) {
            FavouriteTrainHeading(train: train)
            if !parts.isEmpty {
                Label {
                    Text(parts.joined(separator: " · "))
                } icon: {
                    Image(systemName: "chart.bar")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .accessibilityLabel(Text(ReliabilityTexts.spoken(parts.joined(separator: ", "))))
            }
            if let personalLine {
                Label(personalLine, systemImage: "iphone")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let trendLine, let trend {
                Label(trendLine, systemImage: trend.better ? "chart.line.uptrend.xyaxis" : "chart.line.downtrend.xyaxis")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let noData {
                Text(noData)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// The texts of «I miei treni»; counted texts come from Localizable.stringsdict, the counted
/// number first.
enum MyTrainsTexts {
    /// «Puoi salvare al massimo 12 treni.»
    static func limitError(_ maximum: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("saved.trains.limit_error", comment: ""), maximum)
    }

    /// The figures of a favourite train at its last stop, worded as the result rows word them:
    /// «Arrivo a Pavia: in orario 27 volte su 30 (entro 15 min) · ritardo medio 3 min» when the
    /// catalogue names the stop, else «In orario 27 volte su 30 (entro 15 min) · …»; cancellations
    /// after them. As on Android, the on-time count says the train's own limit.
    static func networkParts(_ stop: StopReliability, at station: String?) -> [String] {
        var parts = ReliabilityTexts.trainParts(stop)
        if let station, !stop.cancelledEveryDay, !parts.isEmpty {
            parts[0] = ReliabilityTexts.arrivalOnTime(stop.onTime, of: stop.samples, at: station)
        }
        if !stop.cancelledEveryDay, !parts.isEmpty {
            parts[0] = WeeklyDigestTexts.within(parts[0], minutes: stop.onTimeLimitMinutes)
        }
        return parts
    }

    /// «Registrato su questo telefono: in orario 8 volte su 10 (entro 5 min) · soppresso 1 volta».
    static func recordedLine(_ runs: LocalPunctuality) -> String {
        let count = String.localizedStringWithFormat(NSLocalizedString("saved.trains.reliability.personal", comment: ""), runs.onTime, runs.runs)
        var parts = [WeeklyDigestTexts.within(count, minutes: SharedData.PersonalPunctuality.onTimeMinutes)]
        if runs.cancelled > 0 { parts.append(ReliabilityTexts.cancelled(runs.cancelled)) }
        return parts.joined(separator: " · ")
    }
}
