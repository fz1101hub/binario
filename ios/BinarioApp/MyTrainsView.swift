import SwiftUI
import BinarioKit

/// «I miei treni»: the trains saved with the star of the train detail, newest first, kept on this
/// iPhone only. A tap opens the train's detail as a planner row does; a swipe, or the VoiceOver
/// action, removes it. Below, «Affidabilità dei miei treni»: each of the first trains at its last
/// stop in the unofficial network statistics, hidden without them.
struct MyTrainsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showsStorageError = false

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
                                FavouriteTrainHeading(train: train)
                                    .accessibilityElement(children: .combine)
                            }
                            .accessibilityHint(Text("accessibility.open_train_detail"))
                            .accessibilityAction(named: Text("result.favourite.remove")) {
                                remove([train.id])
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
                        Text("saved.trains.local_only")
                    }

                    if let reliability = model.favouriteTrainsReliability {
                        MyTrainsReliabilitySection(reliability: reliability)
                    }
                }
            }
        }
        .navigationTitle("saved.trains.title")
        // On the Group, not the List: a detail stays open when its train is removed from the list,
        // even the last one.
        .navigationDestination(for: FavouriteTrain.self) { train in
            FavouriteTrainDetail(train: train)
        }
        .alert("saved.trains.storage_error", isPresented: $showsStorageError) {
            Button("action.ok", role: .cancel) { showsStorageError = false }
        }
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

/// «Affidabilità dei miei treni»: what the statistics cover, one row per train measured (a tap
/// opens it) and the attribution that says the statistics are unofficial.
private struct MyTrainsReliabilitySection: View {
    @EnvironmentObject private var model: AppModel
    let reliability: FavouriteTrainsReliability

    var body: some View {
        Section {
            Text(MyTrainsTexts.reliabilityDescription(days: reliability.days))
                .font(.footnote)
                .foregroundStyle(.secondary)
            ForEach(reliability.rows) { row in
                NavigationLink(value: row.train) {
                    MyTrainReliabilityRow(
                        row: row,
                        arrivalName: model.stationName(forReliabilityCode: row.network.stationCode)
                    )
                }
                .accessibilityHint(Text("accessibility.open_train_detail"))
            }
            Text(ReliabilityTexts.attribution(reliability.windowEnd))
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Label("saved.trains.reliability.title", systemImage: "chart.bar")
        }
    }
}

/// One train of «Affidabilità dei miei treni»: the train as its row in «I miei treni», then its
/// figures at its last stop, read by VoiceOver as one sentence that says they are unofficial.
private struct MyTrainReliabilityRow: View {
    let row: FavouriteTrainReliability
    /// The catalogue name of the last stop, when the catalogue knows its code.
    let arrivalName: String?

    var body: some View {
        let parts = MyTrainsTexts.networkParts(row.network, at: arrivalName)
        VStack(alignment: .leading, spacing: 5) {
            FavouriteTrainHeading(train: row.train)
            Label {
                Text(parts.joined(separator: " · "))
            } icon: {
                Image(systemName: "chart.bar")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .accessibilityLabel(Text(ReliabilityTexts.spoken(parts.joined(separator: ", "))))
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

    /// «Ultimi 30 giorni, all’arrivo all’ultima fermata di ogni treno. Tocca un treno per aprirlo.»
    static func reliabilityDescription(days: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("saved.trains.reliability.description", comment: ""), days)
    }

    /// The figures of a favourite train at its last stop, worded as the result rows word them:
    /// «Arrivo a Pavia: in orario 27 volte su 30 · ritardo medio 3 min» when the catalogue names
    /// the stop, else «In orario 27 volte su 30 · ritardo medio 3 min»; cancellations after them.
    static func networkParts(_ stop: StopReliability, at station: String?) -> [String] {
        var parts = ReliabilityTexts.trainParts(stop)
        if let station, !stop.cancelledEveryDay, !parts.isEmpty {
            parts[0] = ReliabilityTexts.arrivalOnTime(stop.onTime, of: stop.samples, at: station)
        }
        return parts
    }
}
