import Foundation
import SwiftUI
import BinarioKit

/// The journeys with changes of the planner, as Android's `JourneySearchViewModel`: a search next to the direct trains
/// (which keep their own search, live values and Watch snapshot), the chips of the results and the travel preferences
/// sent with every search. The planner runs off the main actor; a new search cancels the previous one and a late answer
/// is never shown.
@MainActor
final class JourneysModel: ObservableObject {
    enum State: Equatable {
        case idle
        case loading
        case done
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var query: JourneyQuery?
    @Published private(set) var result: JourneyPlanResult?
    @Published private(set) var rows: [JourneyOptionRow] = []
    /// The network statistics of each journey, by id; empty without the statistics.
    @Published private(set) var reliability: [String: JourneyPlanReliability] = [:]
    @Published var filter: JourneyResultsFilter = .all
    /// The changes filter of the planner (Android's): it filters the journeys shown, not the search.
    @Published var changeFilter: JourneyChangeFilter = .any
    /// «Preferenze di viaggio», kept on this iPhone only.
    @Published private(set) var preferences: JourneyPreferences

    private let defaults: UserDefaults
    private var searchTask: Task<Void, Never>?
    private var generation = 0
    private var stationsCount = -1
    private var stations: [String: Station] = [:]
    private var lastRepository: SQLiteGTFSJourneyRepository?
    private var lastStats: NetworkReliabilityStats?

    static let preferencesKey = "it.binario.ios.travel-preferences.v1"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let stored = defaults.data(forKey: Self.preferencesKey).flatMap { try? JSONDecoder().decode(JourneyPreferences.self, from: $0) }
        let preferences = stored ?? JourneyPreferences()
        self.preferences = preferences
        filter = JourneyResultsFilter.forRanking(preferences.ranking)
    }

    /// Saves the preferences; the journeys on screen are searched again with them.
    func setPreferences(_ value: JourneyPreferences) {
        guard value != preferences else { return }
        let rankingChanged = value.ranking != preferences.ranking
        preferences = value
        if let data = try? JSONEncoder().encode(value) { defaults.set(data, forKey: Self.preferencesKey) }
        if rankingChanged { filter = JourneyResultsFilter.forRanking(value.ranking) }
        researchIfShown()
    }

    /// The journeys the changes filter keeps, as Android filters them before merging them with the direct trains.
    var filteredRows: [JourneyOptionRow] {
        rows.filter { changeFilter.accepts($0.changes) }
    }

    func reset() {
        searchTask?.cancel()
        generation &+= 1
        state = .idle
        query = nil
        result = nil
        rows = []
        reliability = [:]
    }

    /// Searches `query` on `repository` (the bundled index and the installed foreign timetables). Without the index
    /// nothing is searched.
    func search(_ query: JourneyQuery, repository: any JourneyRepository, catalog: StationCatalogLoadState, stats: NetworkReliabilityStats?) {
        searchTask?.cancel()
        generation &+= 1
        let current = generation
        guard let repository = repository as? SQLiteGTFSJourneyRepository else {
            reset()
            return
        }
        lastRepository = repository
        lastStats = stats
        if case let .available(loaded) = catalog, loaded.stations.count != stationsCount {
            stations = Dictionary(loaded.stations.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            stationsCount = loaded.stations.count
        }
        self.query = query
        state = .loading
        result = nil
        rows = []
        reliability = [:]
        let request = JourneyPlanRequest(
            origin: query.origin, destination: query.destination, departure: query.departure,
            preferences: preferences, stations: stations,
            borderZones: Self.borderZones(installed: Set(repository.foreignDatabaseURLs.keys))
        )
        let stations = self.stations
        searchTask = Task { [weak self] in
            let work = Task.detached(priority: .userInitiated) {
                repository.planJourneys(request, isCancelled: { Task.isCancelled })
            }
            let answer = await withTaskCancellationHandler {
                await work.value
            } onCancel: {
                work.cancel()
            }
            guard let self, !Task.isCancelled, current == self.generation else { return }
            let rows = answer.options.map { JourneyResultsPresenter.row($0) }
            self.result = answer
            self.rows = rows
            self.state = .done
            if let stats {
                var measured: [String: JourneyPlanReliability] = [:]
                for row in rows {
                    let assessed = JourneyPlanReliability.assess(row, stats: stats) { id in
                        stations[id].flatMap { NetworkReliability.stationCode(StationIdentity($0)) }
                    }
                    if let assessed { measured[row.id] = assessed }
                }
                self.reliability = measured
            }
        }
    }

    private func researchIfShown() {
        guard let query, let repository = lastRepository else { return }
        search(query, repository: repository, catalog: .loading, stats: lastStats)
    }

    /// The foreign packs read as their border subset: those the pins reduce to their zone whose full timetable is not
    /// installed (Android's `EmbeddedGtfsPacks.forStore`).
    nonisolated static func borderZones(installed: Set<String>) -> [String: JourneyBorderZone] {
        let url = Bundle.main.url(forResource: ForeignSchedulePins.resourceName, withExtension: ForeignSchedulePins.resourceExtension)
        guard let pins = ForeignSchedulePins.load(url: url) else { return [:] }
        var zones: [String: JourneyBorderZone] = [:]
        for pack in pins.packs where pack.borderOnly && !installed.contains(pack.sourceID) {
            zones[pack.sourceID] = JourneyBorderZone(zoneStationIDs: pack.zoneStationIDs, servedStationIDs: pack.servedStationIDs)
        }
        return zones
    }

    /// The day the results may mark («Ultimo di oggi», «primo domani»): only a search of today.
    func markedDay(now: Date = Date()) -> Int? {
        guard let query else { return nil }
        let today = JourneyResultsMerge.encodedDay(now)
        return JourneyResultsMerge.encodedDay(query.departure) == today ? today : nil
    }
}
