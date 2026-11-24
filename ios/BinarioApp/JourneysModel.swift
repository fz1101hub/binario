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
        liveTask?.cancel()
        pageTask?.cancel()
        servicePageTask?.cancel()
        generation &+= 1
        state = .idle
        query = nil
        result = nil
        rows = []
        reliability = [:]
        liveLegs = [:]
        liveFetchedAt = [:]
        liveLookupStartedAt = nil
        earlier = ResultsPageState()
        later = ResultsPageState()
        resetServicePages(for: nil)
        resetRoute(for: nil)
        routeTasks[true]?.cancel()
        boardTasks[true]?.cancel()
        returnLive = RouteLive()
    }

    /// Searches `query` on `repository` (the bundled index and the installed foreign timetables) and, with `live` (the
    /// live sources, nil with «Dati in tempo reale» off), looks up the trains of the changes when the answer arrives.
    /// Without the index nothing is searched.
    func search(
        _ query: JourneyQuery, repository: any JourneyRepository, catalog: StationCatalogLoadState, stats: NetworkReliabilityStats?,
        live: (any LiveTrainStatusProviding)? = nil, keepServicePages: Bool = false
    ) {
        searchTask?.cancel()
        liveTask?.cancel()
        pageTask?.cancel()
        generation &+= 1
        let current = generation
        guard let repository = repository as? SQLiteGTFSJourneyRepository else {
            reset()
            return
        }
        lastRepository = repository
        lastStats = stats
        liveProvider = live
        liveLookup = live.map { JourneyLiveLookup(provider: $0) }
        if case let .available(loaded) = catalog, loaded.stations.count != stationsCount {
            stations = Dictionary(loaded.stations.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            stationsCount = loaded.stations.count
        }
        // A new search starts without the pages of the direct trains of the previous one.
        if !keepServicePages || servicePagesQuery != query { resetServicePages(for: query) }
        self.query = query
        state = .loading
        result = nil
        rows = []
        reliability = [:]
        liveLegs = [:]
        liveFetchedAt = [:]
        liveLookupStartedAt = nil
        earlier = ResultsPageState()
        later = ResultsPageState()
        let request = planRequest(query, departure: query.departure, repository: repository)
        searchTask = Task { [weak self] in
            let answer = await Self.plan(request, on: repository)
            guard let self, !Task.isCancelled, current == self.generation else { return }
            self.result = answer
            self.state = .done
            self.present()
            // The timetable results first; the live check of the changes follows when it answers.
            guard let lookup = self.liveLookup else { return }
            let startedAt = Date()
            self.liveLookupStartedAt = startedAt
            let live = await self.runLive(lookup, options: answer.options)
            guard current == self.generation, !live.isEmpty else { return }
            self.publishLive(live, startedAt: startedAt)
        }
    }

    /// Runs the planner off the main actor; cancelling the caller cancels it.
    private nonisolated static func plan(_ request: JourneyPlanRequest, on repository: SQLiteGTFSJourneyRepository) async -> JourneyPlanResult {
        let work = Task.detached(priority: .userInitiated) {
            repository.planJourneys(request, isCancelled: { Task.isCancelled })
        }
        return await withTaskCancellationHandler {
            await work.value
        } onCancel: {
            work.cancel()
        }
    }

    private func planRequest(_ query: JourneyQuery, departure: Date, repository: SQLiteGTFSJourneyRepository) -> JourneyPlanRequest {
        requestMaker(query, repository: repository)(departure, SharedData.JourneyPlanner.maximumResults)
    }

    /// The request of `query` from a departure with a number of results, from values only (no reference to the model).
    private func requestMaker(
        _ query: JourneyQuery, repository: SQLiteGTFSJourneyRepository
    ) -> @Sendable (Date, Int) -> JourneyPlanRequest {
        let preferences = self.preferences
        let stations = self.stations
        let zones = Self.borderZones(installed: Set(repository.foreignDatabaseURLs.keys))
        let origin = query.origin, destination = query.destination
        return { departure, maxResults in
            JourneyPlanRequest(
                origin: origin, destination: destination, departure: departure, maxResults: maxResults,
                preferences: preferences, stations: stations, borderZones: zones
            )
        }
    }

    /// The rows of the result with the live legs, and their network statistics.
    private func present() {
        let options = result?.options ?? []
        rows = options.map { JourneyResultsPresenter.row($0, live: liveLegs) }
        guard let stats = lastStats else {
            reliability = [:]
            return
        }
        let stations = self.stations
        var measured: [String: JourneyPlanReliability] = [:]
        for row in rows {
            let assessed = JourneyPlanReliability.assess(row, stats: stats) { id in
                stations[id].flatMap { NetworkReliability.stationCode(StationIdentity($0)) }
            }
            if let assessed { measured[row.id] = assessed }
        }
        reliability = measured
    }

    private func researchIfShown() {
        guard let query, let repository = lastRepository else { return }
        search(query, repository: repository, catalog: .loading, stats: lastStats, live: liveProvider, keepServicePages: true)
    }

    // MARK: Live data of the changes («Coincidenza a rischio»)

    @Published private(set) var liveLegs: [String: JourneyLegLive] = [:]
    private var liveLookup: JourneyLiveLookup?
    private var liveProvider: (any LiveTrainStatusProviding)?
    private var liveTask: Task<Void, Never>?
    private var liveRunning = false
    /// When the lookup that answered each live leg started (by leg key), and when the last lookup started.
    private var liveFetchedAt: [String: Date] = [:]
    private var liveLookupStartedAt: Date?

    private func runLive(_ lookup: JourneyLiveLookup, options: [JourneyPlanOption]) async -> [String: JourneyLegLive] {
        liveRunning = true
        defer { liveRunning = false }
        return await lookup.lookup(options)
    }

    /// Repeats only the live lookup of the journeys shown (Android's `refreshLive`), the one on screen (`focusedID`)
    /// first, so the expected times and «Coincidenza a rischio» follow the trains while the results or a journey stay
    /// open. Answers older than `JourneyLiveRefresh.maximumAge` are dropped at once; the periodic caller asks at most
    /// once per `JourneyLiveRefresh.minimumInterval`, `force` (pull to refresh) always, but never while a search or
    /// another lookup runs. `live` nil (the switch went off) drops the live legs.
    func refreshLive(live: (any LiveTrainStatusProviding)?, focusedID: String? = nil, force: Bool = false, now: Date = Date()) {
        guard let live else {
            liveProvider = nil
            liveLookup = nil
            if !liveLegs.isEmpty {
                liveLegs = [:]
                liveFetchedAt = [:]
                present()
            }
            return
        }
        if liveLookup == nil { liveLookup = JourneyLiveLookup(provider: live) }
        liveProvider = live
        guard let lookup = liveLookup, let options = result?.options, !options.isEmpty else { return }
        dropExpiredLive(now)
        guard state == .done, !liveRunning else { return }
        guard force || JourneyLiveRefresh.isDue(lastStartedAt: liveLookupStartedAt, now: now) else { return }
        let ordered = JourneyLiveRefresh.focusedFirst(options, focusedID: focusedID) { $0.id }
        let current = generation
        liveLookupStartedAt = now
        liveTask = Task { [weak self] in
            guard let self else { return }
            let found = await self.runLive(lookup, options: ordered)
            guard current == self.generation else { return }
            // Also without an answer: what the earlier lookups found is not shown past its age.
            self.publishLive(found, startedAt: now)
        }
    }

    /// Adds `found` (from the lookup started at `startedAt`) to the legs shown and drops those past their age.
    private func publishLive(_ found: [String: JourneyLegLive], startedAt: Date) {
        for key in found.keys { liveFetchedAt[key] = startedAt }
        let legs = JourneyLiveRefresh.current(liveLegs.merging(found) { _, new in new }, fetchedAt: liveFetchedAt, now: Date())
        liveFetchedAt = liveFetchedAt.filter { legs[$0.key] != nil }
        guard legs != liveLegs else { return }
        liveLegs = legs
        present()
    }

    private func dropExpiredLive(_ now: Date) {
        guard !liveLegs.isEmpty else { return }
        let legs = JourneyLiveRefresh.current(liveLegs, fetchedAt: liveFetchedAt, now: now)
        liveFetchedAt = liveFetchedAt.filter { legs[$0.key] != nil }
        guard legs.count != liveLegs.count else { return }
        liveLegs = legs
        present()
    }

    // MARK: «Più presto» / «Più tardi» of the journeys

    @Published private(set) var earlier = ResultsPageState()
    @Published private(set) var later = ResultsPageState()
    private var pageTask: Task<Void, Never>?

    /// The journeys can page: answered, with options.
    var canPage: Bool { state == .done && !(result?.options.isEmpty ?? true) }

    /// «Più tardi»: the options of the window from the last departure found, merged into the result.
    func loadLater() { page(later: true) }

    /// «Più presto»: the options leaving before the first departure found (`ResultsPaging.earlier`), merged likewise.
    func loadEarlier() { page(later: false) }

    private func page(later isLater: Bool) {
        guard let query, let repository = lastRepository, let shown = result, state == .done,
              !earlier.loading, !later.loading else { return }
        let departures = shown.options.map(\.departure)
        guard let anchor = isLater ? departures.max() : departures.min() else { return }
        let current = generation
        if isLater { later = .loadingPage } else { earlier = .loadingPage }
        let make = requestMaker(query, repository: repository)
        let startOfDay = Self.startOfItalianDay(query.departure)
        pageTask = Task { [weak self] in
            let loaded = await Self.loadJourneyPage(later: isLater, anchor: anchor, notBefore: startOfDay, make: make, repository: repository)
            guard let self, current == self.generation, let shown = self.result else { return }
            // «Nessun treno fino alle …» belongs to the searched window only, never to a page.
            let fresh = ResultsPaging.newOptions(shown: shown.options, page: loaded ?? []).map { option -> JourneyPlanOption in
                var copy = option
                copy.firstAfterEmptyWindow = false
                return copy
            }
            if !fresh.isEmpty {
                let merged = (shown.options + fresh).enumerated().sorted { first, second in
                    let a = first.element, b = second.element
                    if a.departure != b.departure { return a.departure < b.departure }
                    if a.arrival != b.arrival { return a.arrival < b.arrival }
                    return first.offset < second.offset
                }.map(\.element)
                self.result = JourneyPlanResult(
                    status: .found, options: merged, incompleteCoverage: shown.incompleteCoverage,
                    accessibilityDataUnavailable: shown.accessibilityDataUnavailable, staleTimetables: shown.staleTimetables,
                    expiredSources: shown.expiredSources
                )
                self.present()
            }
            let pageState = ResultsPageState(nothingNew: fresh.isEmpty)
            if isLater { self.later = pageState } else { self.earlier = pageState }
            guard !fresh.isEmpty, let lookup = self.liveLookup else { return }
            let startedAt = Date()
            let live = await self.runLive(lookup, options: fresh)
            guard current == self.generation, !live.isEmpty else { return }
            self.publishLive(live, startedAt: startedAt)
        }
    }

    /// «Più tardi» from `anchor`, or «Più presto» before it and never before the day searched (`notBefore`): «Più
    /// presto» at 06:00 is not yesterday evening.
    private nonisolated static func loadJourneyPage(
        later: Bool, anchor: Date, notBefore: Date, make: @escaping @Sendable (Date, Int) -> JourneyPlanRequest,
        repository: SQLiteGTFSJourneyRepository
    ) async -> [JourneyPlanOption]? {
        if later {
            return await ResultsPaging.later(anchor, departureOf: { $0.departure }) { from in
                await planPage(make(from, SharedData.JourneyPlanner.maximumResults), on: repository)
            }
        }
        return await ResultsPaging.earlier(anchor, departureOf: { $0.departure }, notBefore: notBefore) { from in
            await planPage(make(from, ResultsPaging.earlierJourneyResults), on: repository)
        }
    }

    /// One window of the search; nil when the timetables could not answer it.
    private nonisolated static func planPage(_ request: JourneyPlanRequest, on repository: SQLiteGTFSJourneyRepository) async -> [JourneyPlanOption]? {
        let answer = await plan(request, on: repository)
        return answer.status == .found || answer.status == .noSolution ? answer.options : nil
    }

    nonisolated static func startOfItalianDay(_ date: Date) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Rome") ?? .current
        return calendar.startOfDay(for: date)
    }

    // MARK: «Più presto» / «Più tardi» of the direct trains

    /// A window of the direct trains of `query` from the same timetables as its search; nil when they could not answer.
    private nonisolated static func loadServicePage(
        later: Bool, anchor: Date, notBefore: Date, query: JourneyQuery, repository: any JourneyRepository
    ) async -> [TrainService]? {
        let load: (Date) async -> [TrainService]? = { from in
            guard let pageQuery = try? JourneyQuery(origin: query.origin, destination: query.destination, departure: from,
                                                    direction: query.direction) else { return nil }
            guard case let .available(page) = await repository.journeys(for: pageQuery) else { return nil }
            return page.services
        }
        if later { return await ResultsPaging.later(anchor, departureOf: { $0.departureDate }, load: load) }
        return await ResultsPaging.earlier(anchor, departureOf: { $0.departureDate }, notBefore: notBefore, load: load)
    }

    /// The direct trains the pages loaded for `servicePagesQuery`, beyond those of its search.
    @Published private(set) var pagedTrains: [TrainService] = []
    @Published private(set) var serviceEarlier = ResultsPageState()
    @Published private(set) var serviceLater = ResultsPageState()
    private var servicePagesQuery: JourneyQuery?
    private var servicePageTask: Task<Void, Never>?

    private func resetServicePages(for query: JourneyQuery?) {
        servicePageTask?.cancel()
        servicePagesQuery = query
        pagedTrains = []
        serviceEarlier = ResultsPageState()
        serviceLater = ResultsPageState()
    }

    /// The paged direct trains of `query`'s search, none for another search.
    func pagedServices(for query: JourneyQuery) -> [TrainService] {
        servicePagesQuery == query ? pagedTrains : []
    }

    func serviceEarlierState(for query: JourneyQuery) -> ResultsPageState { servicePagesQuery == query ? serviceEarlier : ResultsPageState() }

    func serviceLaterState(for query: JourneyQuery) -> ResultsPageState { servicePagesQuery == query ? serviceLater : ResultsPageState() }

    /// «Più tardi» / «Più presto» of the direct trains of `snapshot`, from the same timetables as its search.
    func pageServices(later isLater: Bool, snapshot: JourneySnapshot, repository: any JourneyRepository) {
        if servicePagesQuery != snapshot.query { resetServicePages(for: snapshot.query) }
        guard !serviceEarlier.loading, !serviceLater.loading else { return }
        let departures = (snapshot.services + pagedTrains).compactMap(\.departureDate)
        guard let anchor = isLater ? departures.max() : departures.min() else { return }
        if isLater { serviceLater = .loadingPage } else { serviceEarlier = .loadingPage }
        let query = snapshot.query
        let startOfDay = Self.startOfItalianDay(query.departure)
        servicePageTask = Task { [weak self] in
            let loaded = await Self.loadServicePage(later: isLater, anchor: anchor, notBefore: startOfDay, query: query, repository: repository)
            guard let self, self.servicePagesQuery == query else { return }
            let fresh = ResultsPaging.newServices(shown: snapshot.services + self.pagedTrains, page: loaded ?? [])
            self.pagedTrains += fresh
            let pageState = ResultsPageState(nothingNew: fresh.isEmpty)
            if isLater { self.serviceLater = pageState } else { self.serviceEarlier = pageState }
            // The trains of a page get their live values as Android's pages do: the board from their time, the route of
            // the page's window.
            self.pageLoaded(fresh)
        }
    }

    // MARK: Live values of the direct trains beyond the search's own

    /// Live values of one list of direct trains: the live route of its search (Android's `liveFirst`), the runs of the
    /// windows «Più presto» / «Più tardi» loaded (Android asks the route of each page's window), and the board of the
    /// origin from the earliest train of those pages or of a return leg.
    struct RouteLive {
        var query: JourneyQuery?
        var runs: [LiveRouteRun] = []
        var source: LiveSource?
        var runsAt: Date?
        var runsStartedAt: Date?
        /// The runs of the paged windows, each with when it was asked.
        var pageRuns: [(runs: [LiveRouteRun], source: LiveSource, at: Date)] = []
        var board: LiveDepartureBoard?
        var boardAt: Date?
        var boardStartedAt: Date?

        /// The runs shown at `now`: the search's, then the pages' not already listed; none older than live values are.
        func shownRuns(now: Date) -> [LiveRouteRun] {
            var shown = runsAt.map { now.timeIntervalSince($0) <= JourneyLiveRefresh.maximumAge ? runs : [] } ?? []
            var ids = Set(shown.map(\.id))
            for page in pageRuns where now.timeIntervalSince(page.at) <= JourneyLiveRefresh.maximumAge {
                for run in page.runs where ids.insert(run.id).inserted { shown.append(run) }
            }
            return shown
        }

        /// The sources of the runs shown, in Android's precedence.
        func shownSources(now: Date) -> [LiveSource] {
            var sources: [LiveSource] = []
            if let source, let runsAt, now.timeIntervalSince(runsAt) <= JourneyLiveRefresh.maximumAge { sources.append(source) }
            for page in pageRuns where now.timeIntervalSince(page.at) <= JourneyLiveRefresh.maximumAge && !sources.contains(page.source) {
                sources.append(page.source)
            }
            return sources
        }

        func boardValues(of services: [TrainService], now: Date) -> [String: StationBoardLiveStatus] {
            guard let board, let boardAt, now.timeIntervalSince(boardAt) <= JourneyLiveRefresh.maximumAge else { return [:] }
            let planned = services.map { service -> LiveOverlay.PlannedTrain? in
                guard service.hasTrainNumber, let departure = service.departureDate else { return nil }
                return LiveOverlay.PlannedTrain(number: service.number, scheduled: departure, operatorName: service.operatorName)
            }
            let matched = LiveOverlay.departures(for: planned, in: board.departures)
            var values: [String: StationBoardLiveStatus] = [:]
            for (index, service) in services.enumerated() {
                guard let row = matched[index], let departure = service.departureDate,
                      let status = row.stationBoardLiveStatus(at: now, timetable: departure) else { continue }
                values[service.id] = status
            }
            return values
        }
    }

    /// The search's list (with its pages) and the return leg of «Andata e ritorno».
    @Published private(set) var outboundLive = RouteLive()
    @Published private(set) var returnLive = RouteLive()
    private var routeTasks: [Bool: Task<Void, Never>] = [:]
    private var boardTasks: [Bool: Task<Void, Never>] = [:]
    private var pageRouteTasks: [Task<Void, Never>] = []
    private var routeLive: (any LiveDeparturesProviding)?
    private var routeStations: [Bool: (origin: Station, destination: Station)] = [:]
    private var routePlanned: [Bool: [TrainService]] = [:]

    private func resetRoute(for query: JourneyQuery?) {
        routeTasks[false]?.cancel()
        boardTasks[false]?.cancel()
        pageRouteTasks.forEach { $0.cancel() }
        pageRouteTasks = []
        outboundLive = RouteLive(query: query)
    }

    private func live(_ isReturn: Bool) -> RouteLive { isReturn ? returnLive : outboundLive }

    private func setLive(_ isReturn: Bool, _ change: (inout RouteLive) -> Void) {
        if isReturn { change(&returnLive) } else { change(&outboundLive) }
    }

    /// The live runs of `query`'s list (the search's and its pages'), none for another search.
    func routeRuns(for query: JourneyQuery, now: Date) -> [LiveRouteRun] {
        if outboundLive.query == query { return outboundLive.shownRuns(now: now) }
        if returnLive.query == query { return returnLive.shownRuns(now: now) }
        return []
    }

    /// The live sources of `query`'s runs (Android's provider label of the merged list).
    func routeSources(for query: JourneyQuery, now: Date) -> [LiveSource] {
        if outboundLive.query == query { return outboundLive.shownSources(now: now) }
        if returnLive.query == query { return returnLive.shownSources(now: now) }
        return []
    }

    /// The origin board's live values of the paged trains, matched as the search's own trains are (`LiveOverlay`).
    func pagedLive(now: Date) -> [String: StationBoardLiveStatus] {
        outboundLive.boardValues(of: pagedTrains, now: now)
    }

    /// The origin board's live values of the return leg's trains.
    func returnBoardLive(_ snapshot: JourneySnapshot, now: Date) -> [String: StationBoardLiveStatus] {
        guard returnLive.query == snapshot.query else { return [:] }
        return returnLive.boardValues(of: snapshot.services, now: now)
    }

    /// Asks the live route of a search of today (Android's `liveFirst`): `planned` are the timetable's direct trains of
    /// the search (none when it found none). At most once a minute unless `force` or a new search; `live` nil (the
    /// switch off) drops the runs. `isReturn`: the return leg of «Andata e ritorno», with its origin's board too.
    func refreshRoute(
        query: JourneyQuery, origin: Station, destination: Station, planned: [TrainService], live: (any LiveDeparturesProviding)?,
        isReturn: Bool = false, force: Bool = false, now: Date = Date()
    ) {
        if isReturn {
            if returnLive.query != query {
                routeTasks[true]?.cancel()
                boardTasks[true]?.cancel()
                returnLive = RouteLive(query: query)
            }
        } else if outboundLive.query != query {
            resetRoute(for: query)
        }
        routeLive = live
        routeStations[isReturn] = (origin, destination)
        routePlanned[isReturn] = planned
        refreshRoute(isReturn: isReturn, force: force || self.live(isReturn).runsStartedAt == nil, now: now)
        if isReturn {
            refreshBoard(isReturn: true, from: query.departure, force: force, now: now)
        } else if let first = pagedTrains.compactMap(\.departureDate).min() {
            refreshBoard(isReturn: false, from: first, force: force, now: now)
        }
    }

    /// Today only (Android: `query.serviceDate == today`), with the live sources on.
    private func isToday(_ query: JourneyQuery, now: Date) -> Bool {
        JourneyResultsMerge.encodedDay(query.departure) == JourneyResultsMerge.encodedDay(now)
    }

    private func refreshRoute(isReturn: Bool, force: Bool, now: Date = Date()) {
        let state = live(isReturn)
        guard let query = state.query, let live = routeLive, let stations = routeStations[isReturn], isToday(query, now: now) else {
            setLive(isReturn) { $0.runs = []; $0.runsAt = nil; $0.pageRuns = [] }
            return
        }
        guard force || JourneyLiveRefresh.isDue(lastStartedAt: state.runsStartedAt, now: now) else { return }
        setLive(isReturn) { $0.runsStartedAt = now }
        let planned = (routePlanned[isReturn] ?? []) + (isReturn ? [] : pagedServices(for: query))
        let request = LiveRouteRequest(
            origin: stations.origin, destination: stations.destination, requested: query.departure,
            plannedNumbers: Set(planned.map(\.number))
        )
        routeTasks[isReturn]?.cancel()
        routeTasks[isReturn] = Task { [weak self] in
            let result = await live.liveRoute(request)
            guard let self, !Task.isCancelled, self.live(isReturn).query == query else { return }
            switch result {
            case let .available(runs, source):
                self.setLive(isReturn) { $0.runs = runs; $0.source = source; $0.runsAt = Date() }
            case .unavailable(.cancelled):
                break
            case .unavailable:
                self.setLive(isReturn) { $0.runs = []; $0.source = nil; $0.runsAt = nil }
            }
        }
    }

    /// The live route of one window «Più presto» / «Più tardi» loaded (Android's `routePage`), from `from`.
    private func refreshPageRoute(from: Date, now: Date = Date()) {
        guard let query = outboundLive.query, let live = routeLive, let stations = routeStations[false], isToday(query, now: now) else { return }
        let request = LiveRouteRequest(
            origin: stations.origin, destination: stations.destination, requested: from,
            plannedNumbers: Set(pagedServices(for: query).map(\.number))
        )
        pageRouteTasks.append(Task { [weak self] in
            let result = await live.liveRoute(request)
            guard let self, !Task.isCancelled, self.outboundLive.query == query,
                  case let .available(runs, source) = result else { return }
            self.outboundLive.pageRuns.append((runs: runs, source: source, at: Date()))
        })
    }

    /// The origin's board from `from`, for the paged trains or the return leg, at most once a minute unless `force`.
    private func refreshBoard(isReturn: Bool, from: Date, force: Bool, now: Date = Date()) {
        let state = live(isReturn)
        guard let query = state.query, let live = routeLive, let stations = routeStations[isReturn], isToday(query, now: now) else {
            setLive(isReturn) { $0.board = nil; $0.boardAt = nil }
            return
        }
        guard force || JourneyLiveRefresh.isDue(lastStartedAt: state.boardStartedAt, now: now) else { return }
        setLive(isReturn) { $0.boardStartedAt = now }
        let request = LiveDeparturesRequest(station: stations.origin, from: StationBoardQuery.minuteStart(of: from))
        boardTasks[isReturn]?.cancel()
        boardTasks[isReturn] = Task { [weak self] in
            let result = await live.departures(request)
            guard let self, !Task.isCancelled, self.live(isReturn).query == query, case let .available(board) = result else { return }
            self.setLive(isReturn) { $0.board = board; $0.boardAt = Date() }
        }
    }

    /// A page of direct trains loaded: its board and the live route of its window.
    private func pageLoaded(_ fresh: [TrainService]) {
        guard !fresh.isEmpty, let first = pagedTrains.compactMap(\.departureDate).min() else { return }
        refreshBoard(isReturn: false, from: first, force: true)
        // The page's window starts at its first train (Android's `routePage` from the window's start).
        if let window = fresh.compactMap(\.departureDate).min() { refreshPageRoute(from: window) }
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
