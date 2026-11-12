import Foundation
import Combine
import BinarioKit

/// The live sources together, `LiveRailData.standard()` in the app: train status by number and the
/// departures of a station, each never throwing and cancellable.
typealias LiveRailProviding = LiveTrainStatusProviding & LiveDeparturesProviding

@MainActor
final class AppModel: ObservableObject {
    typealias CatalogState = StationCatalogLoadState

    enum SearchState: Equatable {
        case idle
        case unavailable(JourneyUnavailableReason)
    }

    enum ScheduleState: Equatable {
        case loading
        case available
        case unavailable
    }

    enum Tab: Hashable {
        case planner
        case departures
        case saved
        case trains
        case settings
    }

    /// What a tap on the star of a train detail did.
    enum FavouriteChange: Equatable {
        case added
        case removed
        /// «I miei treni» is full: a train is removed before another is saved.
        case limitReached
        /// The train cannot be saved, or the list could not be written.
        case failed
    }

    /// «Partenze»: what the board of the chosen station shows.
    enum BoardState: Equatable {
        /// No station chosen, or its board not read yet.
        case idle
        /// The first page is being read, or the timetables are still being opened.
        case loading
        /// `boardDepartures` is on screen; empty, it is «nessun treno».
        case available
        case unavailable(JourneyUnavailableReason)
    }

    /// A saved route tapped in its tab: the planner takes it over and searches it from now.
    struct PlannerRequest: Equatable, Identifiable {
        let id = UUID()
        let origin: StationIdentity
        let destination: StationIdentity
    }

    /// A listed train with the instant it departs, resolved from its "HH:mm" time.
    struct WatchDeparture: Equatable {
        let service: TrainService
        let departure: Date
    }

    /// What the live sources gave a screen. The bundled timetable stays on screen in every state.
    enum LiveState: Equatable {
        /// «Dati in tempo reale» is off, or the build has no live source: nothing is asked.
        case off
        /// Nothing asked for what is on screen: hidden, or outside the live window.
        case idle
        case loading
        /// The sources answered a request started at this instant (some values may be missing).
        case available(Date)
        /// No source gave a value: the screen keeps the bundled timetable alone.
        case unavailable
    }

    /// One run a train detail or «I miei treni» asks the live sources about: the train number proper
    /// (`NetworkReliability.numberKey`, «2153» of «RE_11 - 2153»), the instant it leaves the boarding
    /// station on that day and that station.
    struct LiveTrainKey: Hashable, Sendable {
        let number: String
        let departure: Date
        let boarding: StationIdentity
    }

    /// The live state of one run as its sources published it, with its stop at the boarding station.
    struct TrainLiveStatus: Equatable, Sendable {
        let key: LiveTrainKey
        let run: LiveRunStatus
        let boarding: LiveStopStatus
        /// When the request started: the values are shown for `liveDisplaySeconds` from then.
        let receivedAt: Date
    }

    /// The merged live board of «Partenze», for the station it was asked for, and when.
    private struct BoardLive {
        let stationID: String
        let board: LiveDepartureBoard
        let at: Date
    }

    /// How a train status lookup ended for one run.
    private enum TrainLiveOutcome {
        case found(TrainLiveStatus)
        /// The sources answered and none of their runs is this one.
        case notFound
        case failed
        case cancelled
    }

    /// The bundled timetables print times in their agency zone; every agency they include keeps
    /// Central European time, as Rome does.
    static let timetableTimeZone = TimeZone(identifier: "Europe/Rome") ?? .current

    @Published private(set) var catalogState: CatalogState
    @Published private(set) var savedRoutes: [SavedRoute]
    /// «I miei treni»: the trains saved with the star of the train detail, newest first.
    @Published private(set) var favouriteTrains: [FavouriteTrain]
    @Published private(set) var watchSharingEnabled: Bool
    @Published fileprivate(set) var watchSyncStatus: WatchSyncStatus
    @Published var searchState: SearchState = .idle
    @Published private(set) var scheduleState: ScheduleState
    @Published private(set) var schedulePacks: [GTFSSourcePack] = []
    @Published private(set) var sourcePolicy: GTFSSourcePolicy?
    /// Nil until the timetable index has been read.
    @Published private(set) var coveredStationIDs: Set<String>?
    @Published private(set) var latestSnapshot: JourneySnapshot?
    @Published var persistenceMessageKey: String?
    @Published var selectedTab: Tab = .planner
    @Published private(set) var plannerRequest: PlannerRequest?
    /// The bundled network statistics; nil while reading, when the build carries no file or it
    /// cannot be read (the reliability lines, «Più affidabili» and the detail block are hidden).
    @Published private(set) var reliabilityStats: NetworkReliabilityStats? = nil
    /// The statistics of `latestSnapshot`'s trains and route.
    @Published private(set) var resultReliability: JourneyReliability? = nil
    /// The saved route shown on the Watch whenever no search for now owns it («Mostra su Watch»).
    @Published private(set) var pinnedWatchRouteID: String? = nil
    /// «Collegamenti con gli aeroporti»: the airport routes the bundled timetables can answer.
    @Published private(set) var airportLinks: [AirportLink] = []
    /// «Partenze»: the station chosen, remembered on this iPhone (`StationBoardStationStore`).
    @Published private(set) var boardStation: StationIdentity?
    @Published private(set) var boardState: BoardState = .idle
    /// The trains still to leave, in departure order: the first page and those «Più tardi» added.
    @Published private(set) var boardDepartures: [StationBoardDeparture] = []
    @Published private(set) var boardHasMore = false
    @Published private(set) var boardIsLoadingMore = false
    /// «Non ci sono più treni oggi: primo domani alle …», on a board without any train left today.
    @Published private(set) var boardFirstTomorrow: StationBoardDeparture? = nil
    /// The feeds of the listed trains, for the board footer.
    @Published private(set) var boardAttributions: [String] = []
    /// «Orari scaduti» over the list: a feed of the station has ended while another goes on.
    @Published private(set) var boardSomeTimetablesEnded = false
    /// «Dati in tempo reale» (Settings, `LiveDataPreferenceStore`): on unless the user turned it off.
    @Published private(set) var liveDataEnabled: Bool
    /// The sources not asked again before a time, for «ViaggiaTreno in pausa fino alle 10:42».
    @Published private(set) var liveSourcePauses: [LiveSourcePause] = []
    /// «Partenze»: what the live board of the station gave its rows.
    @Published private(set) var boardLiveState: LiveState
    /// The sources whose boards gave the live values of «Partenze», in Android's provider order.
    @Published private(set) var boardLiveSources: [LiveSource] = []
    /// The planner: the live values of the trains of `latestSnapshot`, by service identifier, from
    /// the live board of the search's origin.
    @Published private(set) var resultLive: [String: StationBoardLiveStatus] = [:]
    @Published private(set) var resultLiveState: LiveState
    @Published private(set) var resultLiveSources: [LiveSource] = []
    /// The train detail on screen: its run as the sources published it.
    @Published private(set) var detailLive: TrainLiveStatus?
    @Published private(set) var detailLiveState: LiveState
    /// «I miei treni»: today's live state of the first favourites, by favourite identifier.
    @Published private(set) var favouriteLive: [String: TrainLiveStatus] = [:]
    @Published private(set) var favouriteLiveState: LiveState

    private(set) var repository: any JourneyRepository
    /// «Partenze»: the bundled timetable once the index is validated (the first provider, and the
    /// fallback of a live one), else the fail-closed provider.
    private(set) var boardProvider: any StationBoardProvider
    /// Bumped by every first page asked for, so only the latest board reaches the screen.
    private var boardRequestID = 0
    /// The Rome day the board was read on: a new day reads it again.
    private var boardDay: Int?
    /// The reading started by a station choice or by the timetables opening.
    private var boardLoad: Task<Void, Never>?
    private var searchRequestID = 0
    /// Where the timetable index was opened from (`loadSchedule`), to open it again after a failed read.
    private var scheduleInputs: ScheduleInputs?
    /// The index has been opened again after a failed read in this launch: at most once.
    private var scheduleReopenedAfterFailedRead = false
    /// The last search that was for now, whose route the Watch shows; nil once the input changes.
    private var watchRouteQuery: JourneyQuery?
    /// Bumped by every publication of the pinned route, so only the latest one reaches the Watch.
    private var pinnedPublicationID = 0
    /// The publication of the pinned route started by a pin, an edit or sharing turned on.
    private var pinnedPublication: Task<Void, Never>?
    /// The Watch asked for a refresh while the timetables were still being opened (a background
    /// launch by Watch Connectivity): answered, and the background time ended, once they are.
    private var pendingWatchRefreshEnd: (@MainActor () -> Void)?
    /// The catalogue name of each ViaggiaTreno station code (the first station with that railway code).
    private var stationNamesByCode: [String: String] = [:]
    let versionDisplay: String

    private let savedRouteStore: SavedRouteStore
    private let favouriteTrainStore: FavouriteTrainStore
    private let boardStationStore: StationBoardStationStore
    private let watchSharingStore: WatchSharingPreferenceStore
    private let watchPublisher: WatchSnapshotPublisher?
    private let backgroundActivity: (any BackgroundActivityRunning)?
    /// The live sources; nil in a model without them (the tests that do not need them): nothing is asked.
    private let liveData: (any LiveRailProviding)?
    private let liveDataStore: LiveDataPreferenceStore
    private let livePauses: LiveSourcePauses

    /// «Partenze»: the rows of the bundled timetable, before the live values are laid over them.
    private var boardScheduled: [StationBoardDeparture] = []
    private var boardLive: BoardLive?
    /// When the live values of the planner's result were asked for.
    private var resultLiveAt: Date?
    /// False from the moment the app goes to the background until it is active again: no live
    /// request starts meanwhile, not even one a pull or a search begun just before reaches.
    private var isForeground = true
    /// The screens that ask live sources do so only while they are on screen.
    private var boardLiveVisible = false
    private var plannerLiveVisible = false
    private var favouritesLiveVisible = false
    /// The train detail on screen, if any.
    private var detailLiveKey: LiveTrainKey?
    /// Bumped by every live request and whenever its screen goes, so only the latest answer is shown.
    private var boardLiveRequestID = 0
    private var resultLiveRequestID = 0
    private var detailLiveRequestID = 0
    private var favouriteLiveRequestID = 0
    /// The live requests started by a screen appearing, an input changing or a pull.
    private var boardLiveLoad: Task<Void, Never>?
    private var resultLiveLoad: Task<Void, Never>?
    private var detailLiveLoad: Task<Void, Never>?
    private var favouriteLiveLoad: Task<Void, Never>?

    init(
        catalogState: CatalogState,
        savedRouteStore: SavedRouteStore,
        favouriteTrainStore: FavouriteTrainStore = FavouriteTrainStore(),
        boardStationStore: StationBoardStationStore = StationBoardStationStore(),
        watchSharingStore: WatchSharingPreferenceStore,
        repository: any JourneyRepository,
        boardProvider: any StationBoardProvider = FailClosedStationBoardProvider(),
        scheduleState: ScheduleState = .available,
        coveredStationIDs: Set<String>? = nil,
        watchPublisher: WatchSnapshotPublisher?,
        backgroundActivity: (any BackgroundActivityRunning)? = nil,
        liveData: (any LiveRailProviding)? = nil,
        liveDataStore: LiveDataPreferenceStore = LiveDataPreferenceStore(),
        livePauses: LiveSourcePauses = .shared,
        versionDisplay: String
    ) {
        self.catalogState = catalogState
        self.savedRouteStore = savedRouteStore
        self.favouriteTrainStore = favouriteTrainStore
        self.boardStationStore = boardStationStore
        self.watchSharingStore = watchSharingStore
        self.repository = repository
        self.boardProvider = boardProvider
        self.scheduleState = scheduleState
        self.coveredStationIDs = coveredStationIDs
        self.latestSnapshot = nil
        self.watchPublisher = watchPublisher
        self.backgroundActivity = backgroundActivity
        self.liveData = liveData
        self.liveDataStore = liveDataStore
        self.livePauses = livePauses
        let liveEnabled = liveDataStore.load()
        let liveState: LiveState = liveData != nil && liveEnabled ? .idle : .off
        self.liveDataEnabled = liveEnabled
        self.boardLiveState = liveState
        self.resultLiveState = liveState
        self.detailLiveState = liveState
        self.favouriteLiveState = liveState
        self.versionDisplay = versionDisplay
        let routes = savedRouteStore.load()
        self.savedRoutes = routes
        self.favouriteTrains = favouriteTrainStore.load().trains
        self.boardStation = boardStationStore.load()
        let sharingEnabled = watchSharingStore.load()
        self.watchSharingEnabled = sharingEnabled
        self.watchSyncStatus = sharingEnabled ? .activating : .disabled
        // A pin is kept only for a route that is still saved.
        let pinned = watchSharingStore.loadPinnedRouteID()
        if let pinned, routes.contains(where: { $0.id == pinned }) {
            self.pinnedWatchRouteID = pinned
        } else if pinned != nil {
            watchSharingStore.savePinnedRouteID(nil)
        }

        watchPublisher?.statusDidChange = { [weak self] status in
            guard let self else { return }
            self.watchSyncStatus = status
        }
        watchPublisher?.refreshRequested = { [weak self] in
            guard let self else { return }
            Task { await self.refreshWatchSnapshot() }
        }
        watchPublisher?.bootstrap()
        if !watchSharingEnabled {
            revokeWatchSnapshot()
        }
    }

    static func bootstrap(
        bundle: Bundle = .main,
        versionFormatter: any AppVersionFormatting = BundleAppVersionFormatter()
    ) -> AppModel {
        let publisher: WatchSnapshotPublisher?
        #if canImport(WatchConnectivity)
        publisher = WatchSnapshotPublisher(session: WCSessionAdapter())
        #else
        publisher = nil
        #endif

        let background: (any BackgroundActivityRunning)?
        #if canImport(UIKit)
        background = UIKitBackgroundActivity()
        #else
        background = nil
        #endif

        let model = AppModel(
            catalogState: .loading,
            savedRouteStore: SavedRouteStore(),
            favouriteTrainStore: FavouriteTrainStore(),
            boardStationStore: StationBoardStationStore(),
            watchSharingStore: WatchSharingPreferenceStore(),
            repository: FailClosedJourneyRepository(),
            scheduleState: .loading,
            watchPublisher: publisher,
            backgroundActivity: background,
            // One instance for the whole app: its sources share their gates (one request per
            // resource every 30 seconds, pause at the first refusal) across every screen.
            liveData: LiveRailData.standard(),
            liveDataStore: LiveDataPreferenceStore(),
            versionDisplay: versionFormatter.displayVersion(bundle: bundle)
        )

        // The statistics are optional: a build without the file shows no reliability at all.
        let statisticsURL = bundle.url(
            forResource: NetworkReliabilityParser.resourceName,
            withExtension: NetworkReliabilityParser.resourceExtension
        )
        Task { await model.loadReliability(url: statisticsURL) }

        if let url = bundle.url(forResource: "curated_it_rail_stations", withExtension: "json") {
            Task {
                await model.loadCatalog { try Data(contentsOf: url) }
            }
        } else {
            model.catalogState = .unavailable
        }
        guard
            let compressedURL = bundle.url(forResource: "binario_gtfs_schedule_v1.sqlite", withExtension: "zlib"),
            let metadataURL = bundle.url(forResource: "binario_gtfs_schedule_v1.metadata", withExtension: "json"),
            let sourcePolicyURL = bundle.url(forResource: "binario_gtfs_source_policy_v1", withExtension: "json"),
            let applicationSupport = FileManager.default.urls(
                for: .applicationSupportDirectory,
                in: .userDomainMask
            ).first
        else {
            model.scheduleState = .unavailable
            return model
        }
        let cacheDirectory = applicationSupport
            .appendingPathComponent("Binario", isDirectory: true)
            .appendingPathComponent("GTFS", isDirectory: true)
        Task {
            await model.loadSchedule(
                compressedURL: compressedURL,
                metadataURL: metadataURL,
                sourcePolicyURL: sourcePolicyURL,
                cacheDirectory: cacheDirectory
            )
        }
        return model
    }

    func loadCatalog(data: @escaping @Sendable () throws -> Data) async {
        catalogState = .loading
        catalogState = await StationCatalogLoader.load(data: data)
        stationNamesByCode = [:]
        if case let .available(catalog) = catalogState {
            for station in catalog.stations {
                guard let code = NetworkReliability.stationCode(StationIdentity(station)), stationNamesByCode[code] == nil else { continue }
                stationNamesByCode[code] = station.name
            }
        }
        updateAirportLinks()
    }

    /// Reads the bundled network statistics once, off the main actor.
    func loadReliability(url: URL?) async {
        let stats = await NetworkReliabilityLoader.load(url: url)
        reliabilityStats = stats
        if let stats, let latestSnapshot {
            resultReliability = JourneyReliability.assess(latestSnapshot, stats: stats)
        }
    }

    /// The catalogue name of a ViaggiaTreno station code, for the stops the statistics name.
    func stationName(forReliabilityCode code: String) -> String? {
        stationNamesByCode[code]
    }

    /// The «Affidabilità ultimi N giorni» block of a listed train: boarded at the query's origin
    /// and measured at its destination; nil without statistics or enough samples.
    func reliabilityDetail(for service: TrainService, query: JourneyQuery) -> NetworkReliabilityDetail? {
        guard let reliabilityStats else { return nil }
        return NetworkReliabilityDetail.of(
            reliabilityStats,
            number: service.number,
            boardingCode: NetworkReliability.stationCode(query.origin),
            destinationCode: NetworkReliability.stationCode(query.destination)
        )
    }

    /// «Affidabilità dei miei treni»: the first favourite trains at their last stop in the network
    /// statistics; nil while the file is read, in a build without it or without a train measured.
    var favouriteTrainsReliability: FavouriteTrainsReliability? {
        FavouriteTrainsReliability.assess(favouriteTrains, stats: reliabilityStats)
    }

    /// Recomputes `airportLinks` once both the catalogue and the timetable index are read.
    private func updateAirportLinks() {
        guard case let .available(catalog) = catalogState, let coveredStationIDs else {
            airportLinks = []
            return
        }
        airportLinks = AirportRoutes.links(
            catalog: catalog,
            coveredStationIDs: coveredStationIDs,
            packsWithTrips: Set(includedPacks.map(\.sourceID))
        )
    }

    func suggestions(for query: String) -> [Station] {
        guard case let .available(catalog) = catalogState else { return [] }
        let stations = catalog.search(query)
        guard let coveredStationIDs else { return stations }
        // Stations the bundled timetables serve come first; the others stay selectable and
        // are marked, so a search for Roma Termini explains itself instead of "no data".
        return stations.filter { coveredStationIDs.contains($0.id) }
            + stations.filter { !coveredStationIDs.contains($0.id) }
    }

    /// Nil until the timetable index has been read, true when one of its stops is bound to
    /// the station.
    func hasTimetable(_ station: Station) -> Bool? {
        coveredStationIDs?.contains(station.id)
    }

    func loadSchedule(compressedURL: URL, metadataURL: URL, sourcePolicyURL: URL? = nil, cacheDirectory: URL) async {
        scheduleInputs = ScheduleInputs(
            compressedURL: compressedURL, metadataURL: metadataURL, sourcePolicyURL: sourcePolicyURL, cacheDirectory: cacheDirectory
        )
        scheduleState = .loading
        let installed = await Task.detached(priority: .utility) { () -> InstalledSchedule? in
            guard let installedURL = try? BundledGTFSScheduleInstaller().install(
                compressedURL: compressedURL,
                metadataURL: metadataURL,
                cacheDirectory: cacheDirectory
            ) else { return nil }
            let repository = SQLiteGTFSJourneyRepository(databaseURL: installedURL)
            // The source list and the covered stations are read from the same validated index
            // as the journeys: an index that cannot describe itself is not served either.
            guard let packs = try? repository.sourcePacks(),
                  let coveredStationIDs = try? repository.coveredCatalogStationIDs()
            else { return nil }
            let policy = sourcePolicyURL.flatMap { url in
                (try? Data(contentsOf: url)).flatMap { try? GTFSSourcePolicy(data: $0) }
            }
            return InstalledSchedule(
                repository: repository,
                packs: packs,
                coveredStationIDs: coveredStationIDs,
                sourcePolicy: policy
            )
        }.value
        guard let installed else {
            repository = FailClosedJourneyRepository()
            boardProvider = FailClosedStationBoardProvider()
            schedulePacks = []
            coveredStationIDs = nil
            sourcePolicy = nil
            scheduleState = .unavailable
            updateAirportLinks()
            scheduleBoardReload()
            endPendingWatchRefresh()
            return
        }
        repository = installed.repository
        boardProvider = installed.repository
        schedulePacks = installed.packs
        coveredStationIDs = installed.coveredStationIDs
        sourcePolicy = installed.sourcePolicy
        scheduleState = .available
        updateAirportLinks()
        // A station remembered from the last time «Partenze» was open is read now.
        scheduleBoardReload()
        // At launch the Watch gets the pinned route; a refresh it asked for meanwhile is answered.
        await refreshWatchSnapshot()
        endPendingWatchRefresh()
    }

    /// The packs whose filter retained at least one rail trip: the feeds that can answer a search.
    var includedPacks: [GTFSSourcePack] {
        schedulePacks.filter { $0.tripCount > 0 }
    }

    /// The earliest day on which one of the included feeds stops: from then on its routes
    /// report an expired timetable until the app is updated.
    var firstPackExpiry: Date? {
        includedPacks.map(\.lastServiceDate).min()
    }

    /// The attribution of every feed that contributes trips, for the planner footer.
    var includedFeedAttributions: String {
        includedPacks.map(\.attribution).joined(separator: ", ")
    }

    func run(query: JourneyQuery) async {
        searchRequestID &+= 1
        let requestID = searchRequestID
        let result = await repository.journeys(for: query)
        guard requestID == searchRequestID else { return }
        let isForNow = Self.isCurrent(query, now: Date())
        watchRouteQuery = isForNow ? query : nil
        // The live values of the previous result never reach this one's trains.
        clearResultLive()
        switch result {
        case let .available(snapshot):
            searchState = .idle
            latestSnapshot = snapshot
            resultReliability = reliabilityStats.map { JourneyReliability.assess(snapshot, stats: $0) }
            if isForNow { publishAvailableSnapshot(snapshot) }
            restartResultLive()
        case let .unavailable(reason):
            searchState = .unavailable(reason)
            latestSnapshot = nil
            resultReliability = nil
            if reason == .noAuthorizedProvider {
                scheduleState = .unavailable
            }
            if isForNow { publishUnavailableSnapshot(for: query) }
            // One failed read (an I/O error, a cache damaged while the app runs, a pair over a
            // reader limit) is this search's error, not a planner switched off until the app is
            // quit: the index is validated again, and reinstalled from the bundle when damaged.
            if reason == .providerInvalid { await reopenScheduleAfterFailedRead() }
        }
    }

    /// Opens the timetable index again after a failed read, once per launch: `loadSchedule`
    /// validates it and installs it again from the bundle when it is damaged; only if that fails
    /// are the timetables unavailable.
    private func reopenScheduleAfterFailedRead() async {
        guard !scheduleReopenedAfterFailedRead, scheduleState == .available, let inputs = scheduleInputs else { return }
        scheduleReopenedAfterFailedRead = true
        await loadSchedule(
            compressedURL: inputs.compressedURL,
            metadataURL: inputs.metadataURL,
            sourcePolicyURL: inputs.sourcePolicyURL,
            cacheDirectory: inputs.cacheDirectory
        )
    }

    func invalidateSearchResult() {
        searchRequestID &+= 1
        searchState = .idle
        latestSnapshot = nil
        resultReliability = nil
        clearResultLive()
        watchRouteQuery = nil
        guard watchSharingEnabled else { return }
        if pinnedWatchRoute != nil {
            // The search no longer owns the Watch: it goes back to the pinned route.
            schedulePinnedPublication()
        } else {
            revokeWatchSnapshot()
        }
    }

    /// The Watch asked for fresh trains ("Aggiorna", or its app opened with nothing to show):
    /// search the route of the last search for now again, or else the pinned route, and publish
    /// the result, without touching what the planner shows. Watch Connectivity may have launched
    /// the app in the background for this: the work keeps a background activity until it is sent.
    func refreshWatchSnapshot(now: Date = Date()) async {
        guard watchSharingEnabled else { return }
        let end = backgroundActivity?.begin(name: "Binario Watch")
        defer { end?() }
        guard scheduleState == .available else {
            // Held until the index is open (installSchedule answers the refresh, then ends it).
            if scheduleState == .loading, pendingWatchRefreshEnd == nil {
                pendingWatchRefreshEnd = backgroundActivity?.begin(name: "Binario Watch")
            }
            return
        }
        guard let route = watchRouteQuery else {
            await publishPinnedRoute(now: now)
            return
        }
        guard let query = try? JourneyQuery(
            origin: route.origin,
            destination: route.destination,
            departure: now,
            direction: route.direction
        ) else { return }
        let requestID = searchRequestID
        let result = await repository.journeys(for: query)
        // A search or an edit since the request decides what the Watch shows.
        guard requestID == searchRequestID, watchSharingEnabled, watchRouteQuery != nil else { return }
        switch result {
        case let .available(snapshot):
            publishAvailableSnapshot(snapshot)
        case .unavailable:
            publishUnavailableSnapshot(for: query)
        }
    }

    func openPlanner(for route: SavedRoute) {
        openPlanner(origin: route.origin, destination: route.destination)
    }

    /// Fills the planner with a route and searches it from now (a saved route, an airport link).
    func openPlanner(origin: StationIdentity, destination: StationIdentity) {
        plannerRequest = PlannerRequest(origin: origin, destination: destination)
        selectedTab = .planner
    }

    /// The saved route the Watch shows when no search for now owns it.
    var pinnedWatchRoute: SavedRoute? {
        savedRoutes.first { $0.id == pinnedWatchRouteID }
    }

    /// «Mostra su Watch»: pins a saved route (one at a time) or removes the pin. With sharing on,
    /// the Watch gets its next trains at once and whenever the iPhone has nothing more current to
    /// show: at launch, after the planner input changes and when the Watch asks for a refresh.
    func setWatchPin(_ route: SavedRoute, pinned: Bool) {
        if pinned {
            guard savedRoutes.contains(where: { $0.id == route.id }) else { return }
            pinnedWatchRouteID = route.id
            watchSharingStore.savePinnedRouteID(route.id)
            // An explicit choice: the Watch shows this route, whatever was searched before.
            watchRouteQuery = nil
            schedulePinnedPublication()
        } else if pinnedWatchRouteID == route.id {
            unpinWatchRoute()
        }
    }

    /// The pinned route's next trains, searched from now: only with sharing on, the timetables
    /// loaded and no search for now owning the Watch; a newer search or pin wins.
    func publishPinnedRoute(now: Date = Date()) async {
        guard watchSharingEnabled, scheduleState == .available, watchRouteQuery == nil,
              let route = pinnedWatchRoute,
              let query = try? JourneyQuery(origin: route.origin, destination: route.destination, departure: now)
        else { return }
        pinnedPublicationID &+= 1
        let publicationID = pinnedPublicationID
        let requestID = searchRequestID
        let result = await repository.journeys(for: query)
        guard publicationID == pinnedPublicationID, requestID == searchRequestID,
              watchSharingEnabled, watchRouteQuery == nil, pinnedWatchRouteID == route.id
        else { return }
        switch result {
        case let .available(snapshot):
            publishAvailableSnapshot(snapshot)
        case .unavailable:
            publishUnavailableSnapshot(for: query)
        }
    }

    private func schedulePinnedPublication() {
        pinnedPublication = Task { await self.publishPinnedRoute() }
    }

    /// Waits until the latest publication of the pinned route has finished.
    func pinnedPublicationFinished() async {
        await pinnedPublication?.value
    }

    private func unpinWatchRoute() {
        pinnedWatchRouteID = nil
        watchSharingStore.savePinnedRouteID(nil)
        pinnedPublicationID &+= 1
        // Without a search for now the Watch was showing the pinned route: clear it.
        if watchRouteQuery == nil, watchSharingEnabled {
            revokeWatchSnapshot()
        }
    }

    private func endPendingWatchRefresh() {
        pendingWatchRefreshEnd?()
        pendingWatchRefreshEnd = nil
    }

    // MARK: «Partenze»

    /// Trains asked for at a time; «Più tardi» adds as many.
    static let boardPageSize = 20
    /// «Più tardi» stops adding trains once this many are listed.
    static let maximumBoardDepartures = 200

    /// «Partenze» of `station` from now: remembered on this iPhone, its first page read at once.
    func selectBoardStation(_ station: StationIdentity) {
        guard station != boardStation || boardState == .idle else { return }
        boardStation = station
        boardStationStore.save(station)
        // The trains of another station are never shown under this one, nor its live values.
        boardLive = nil
        boardLiveSources = []
        setBoardRows([])
        boardHasMore = false
        boardFirstTomorrow = nil
        boardAttributions = []
        boardSomeTimetablesEnded = false
        boardState = .loading
        scheduleBoardReload()
        restartBoardLive()
    }

    private func scheduleBoardReload() {
        guard boardStation != nil else { return }
        boardLoad = Task { await self.reloadBoard() }
    }

    /// Waits until the reading started by a station choice or by the timetables has finished.
    func boardLoadFinished() async {
        await boardLoad?.value
    }

    /// Reads the first page of the chosen station from the current minute. While the timetables
    /// are still being opened the board waits: `loadSchedule` reads it once they are.
    func reloadBoard(now: Date = Date()) async {
        guard let station = boardStation else { return }
        boardRequestID &+= 1
        let requestID = boardRequestID
        boardIsLoadingMore = false
        if boardDepartures.isEmpty { boardState = .loading }
        guard scheduleState != .loading else {
            boardState = .loading
            return
        }
        guard let query = try? StationBoardQuery(
            station: station,
            from: StationBoardQuery.minuteStart(of: now),
            limit: Self.boardPageSize
        ) else {
            applyBoardUnavailable(.noJourney, day: Self.boardDay(now))
            return
        }
        let result = await boardProvider.departures(for: query)
        guard requestID == boardRequestID, boardStation == station else { return }
        switch result {
        case let .available(page):
            setBoardRows(page.departures)
            boardHasMore = page.hasMore
            boardFirstTomorrow = page.firstDepartureNextDay
            boardAttributions = page.attributions
            boardSomeTimetablesEnded = page.someTimetablesEnded
            boardDay = Self.boardDay(now)
            boardState = .available
        case let .unavailable(reason):
            applyBoardUnavailable(reason, day: Self.boardDay(now))
        }
    }

    private func applyBoardUnavailable(_ reason: JourneyUnavailableReason, day: Int) {
        setBoardRows([])
        boardHasMore = false
        boardFirstTomorrow = nil
        boardAttributions = []
        boardSomeTimetablesEnded = false
        boardDay = day
        boardState = .unavailable(reason)
    }

    /// «Più tardi»: the page after the last listed train, without repeating one; returns how many
    /// trains were added (for VoiceOver). A newer first page or another station discards it.
    @discardableResult
    func loadMoreBoard() async -> Int {
        guard boardState == .available, boardHasMore, !boardIsLoadingMore,
              let station = boardStation, let last = boardDepartures.last,
              boardDepartures.count < Self.maximumBoardDepartures,
              let query = try? StationBoardQuery(
                  station: station,
                  from: last.departure,
                  limit: Self.boardPageSize,
                  after: last.cursor
              )
        else { return 0 }
        let requestID = boardRequestID
        boardIsLoadingMore = true
        let result = await boardProvider.departures(for: query)
        guard requestID == boardRequestID, boardStation == station else { return 0 }
        boardIsLoadingMore = false
        guard case let .available(page) = result else {
            boardHasMore = false
            return 0
        }
        let known = Set(boardDepartures.map(\.id))
        let room = max(0, Self.maximumBoardDepartures - boardDepartures.count)
        let added = Array(page.departures.filter { !known.contains($0.id) }.prefix(room))
        setBoardRows(boardScheduled + added)
        boardHasMore = page.hasMore && boardDepartures.count < Self.maximumBoardDepartures
        for attribution in page.attributions where !boardAttributions.contains(attribution) {
            boardAttributions.append(attribution)
        }
        return added.count
    }

    /// When «Partenze» appears and every minute while it is on screen: drops the trains that have
    /// left (a minute after their scheduled departure, as the planner's «Più puntuale» counts
    /// them) and reads the board again when it was never read, the list has just emptied or the
    /// Rome day has changed.
    func refreshBoardIfNeeded(now: Date = Date()) async {
        guard boardStation != nil, scheduleState != .loading else { return }
        switch boardState {
        case .idle:
            await reloadBoard(now: now)
            return
        case .loading:
            return
        case .unavailable:
            if boardDay != Self.boardDay(now) { await reloadBoard(now: now) }
            return
        case .available:
            break
        }
        let listed = boardScheduled
        let remaining = listed.filter { $0.stillToLeave(at: now) }
        if remaining.count != listed.count { setBoardRows(remaining) }
        let emptied = remaining.isEmpty && !listed.isEmpty
        if emptied || boardDay != Self.boardDay(now) {
            await reloadBoard(now: now)
        }
    }

    /// The ride a board row opens in the train detail: boarded at the board's station, which with
    /// the number is the identity of its star in «I miei treni», and measured at the train's last
    /// stop, named as the catalogue names it.
    func boardDetailQuery(for departure: StationBoardDeparture) -> JourneyQuery? {
        guard let station = boardStation else { return nil }
        let terminus = StationIdentity(self.station(for: departure.terminus))
        return try? JourneyQuery(origin: station, destination: terminus, departure: departure.departure)
    }

    /// The Rome calendar day of `date` as yyyymmdd.
    static func boardDay(_ date: Date) -> Int {
        let parts = timetableCalendar.dateComponents([.year, .month, .day], from: date)
        let year: Int = parts.year ?? 0
        let month: Int = parts.month ?? 0
        let day: Int = parts.day ?? 0
        return year * 10_000 + month * 100 + day
    }

    // MARK: Live data

    /// Live data is asked only for trains leaving from three hours ago to three hours ahead
    /// (Android's `JourneyLiveCandidates.LOOKBACK` and `LOOKAHEAD`); the others keep their timetable.
    static let liveLookback: TimeInterval = 3 * 3_600
    static let liveLookahead: TimeInterval = 3 * 3_600
    /// A live value is shown for five minutes after it was asked for (Android's recency limit for an
    /// observation), then its row goes back to the timetable until the screen is refreshed.
    static let liveDisplaySeconds: TimeInterval = 5 * 60
    /// Two runs of one number further apart than this at the boarding station are two runs, as
    /// Android's `JourneyLiveCandidates.SAME_RUN_TOLERANCE` (yesterday's night train is not today's).
    static let sameRunTolerance: TimeInterval = 2 * 3_600
    /// «I miei treni» asks the live state of its first favourites only, as Android's
    /// `FavouriteTrainPunctuality.MAX_ROWS`.
    static let maximumLiveFavourites = 8

    /// The switch is on and the build has live sources.
    var isLiveDataActive: Bool { liveDataEnabled && liveData != nil }

    static func isInLiveWindow(_ departure: Date, now: Date) -> Bool {
        departure >= now.addingTimeInterval(-liveLookback) && departure <= now.addingTimeInterval(liveLookahead)
    }

    static func isLiveDisplayable(since start: Date, now: Date) -> Bool {
        now.timeIntervalSince(start) <= liveDisplaySeconds
    }

    /// «Dati in tempo reale» in Settings. Off, every request on its way is cancelled, every live
    /// value goes and no screen asks anything: the app is the bundled timetable alone, as before.
    func setLiveDataEnabled(_ enabled: Bool) {
        guard liveDataEnabled != enabled else { return }
        liveDataStore.save(enabled)
        liveDataEnabled = enabled
        guard enabled else {
            clearLiveData()
            return
        }
        let state: LiveState = liveData == nil ? .off : .idle
        boardLiveState = state
        resultLiveState = state
        detailLiveState = state
        favouriteLiveState = state
        restartBoardLive()
        restartResultLive()
        restartDetailLive()
        restartFavouritesLive()
    }

    private func clearLiveData() {
        cancelBoardLive()
        cancelResultLive()
        cancelDetailLive()
        cancelFavouritesLive()
        // The requests the screens left keep going for the next screen; switched off, they stop.
        livePauses.cancelRequestsNobodyWaitsFor()
        boardLive = nil
        boardLiveSources = []
        setBoardRows(boardScheduled)
        resultLive = [:]
        resultLiveSources = []
        resultLiveAt = nil
        detailLive = nil
        favouriteLive = [:]
        liveSourcePauses = []
        boardLiveState = .off
        resultLiveState = .off
        detailLiveState = .off
        favouriteLiveState = .off
    }

    /// Back in the foreground: the screens on display ask again (through the sources' gates).
    func liveDidBecomeActive() {
        liveDidBecomeActive(now: Date())
    }

    /// Back in the foreground at `now`: what went stale meanwhile goes first (the minute tick does
    /// not count the time the device slept), then the screens on display ask again.
    func liveDidBecomeActive(now: Date) {
        isForeground = true
        expireLiveValues(now: now)
        if boardLiveState != .loading { restartBoardLive() }
        if resultLiveState != .loading { restartResultLive() }
        if detailLiveState != .loading { restartDetailLive() }
        if favouriteLiveState != .loading { restartFavouritesLive() }
    }

    /// In the background nothing is asked: the requests on their way are cancelled and none starts.
    func liveDidEnterBackground() {
        isForeground = false
        cancelBoardLive()
        cancelResultLive()
        cancelDetailLive()
        cancelFavouritesLive()
        livePauses.cancelRequestsNobodyWaitsFor()
    }

    /// Every minute while the app is open, and when it comes back to the foreground: the live values
    /// asked for more than `liveDisplaySeconds` ago are no longer shown, nor the pauses that ended.
    func expireLiveValues(now: Date = Date()) {
        let pauses: [LiveSourcePause] = liveDataEnabled ? livePauses.active(at: now) : []
        if pauses != liveSourcePauses { liveSourcePauses = pauses }
        if let live = boardLive, !Self.isLiveDisplayable(since: live.at, now: now) {
            boardLive = nil
            boardLiveSources = []
            if boardLiveState != .loading { boardLiveState = isLiveDataActive ? .idle : .off }
            setBoardRows(boardScheduled)
        }
        if let at = resultLiveAt, !Self.isLiveDisplayable(since: at, now: now) {
            resultLive = [:]
            resultLiveSources = []
            resultLiveAt = nil
            if resultLiveState != .loading { resultLiveState = isLiveDataActive ? .idle : .off }
        }
        if let live = detailLive, !Self.isLiveDisplayable(since: live.receivedAt, now: now) {
            detailLive = nil
            if detailLiveState != .loading { detailLiveState = isLiveDataActive ? .idle : .off }
        }
        let fresh = favouriteLive.filter { Self.isLiveDisplayable(since: $0.value.receivedAt, now: now) }
        if fresh.count != favouriteLive.count {
            favouriteLive = fresh
            if fresh.isEmpty, favouriteLiveState != .loading { favouriteLiveState = isLiveDataActive ? .idle : .off }
        }
    }

    // MARK: Live data of «Partenze»

    /// «Partenze» is on screen: the live board of its station is asked now.
    func boardLiveDidAppear() {
        boardLiveVisible = true
        restartBoardLive()
    }

    /// «Partenze» left the screen: nothing more is asked and an answer on its way is dropped.
    func boardLiveDidDisappear() {
        boardLiveVisible = false
        cancelBoardLive()
    }

    /// Pull to refresh, or «Aggiorna»: the live board again, through the sources' gates.
    func pullBoardLive() async {
        restartBoardLive()
        await boardLiveLoad?.value
    }

    /// Waits until the live board started by an appearance, a station or a pull has been read.
    func boardLiveLoadFinished() async {
        await boardLiveLoad?.value
    }

    /// The live board of the chosen station, merged from RFI, Italo and ViaggiaTreno, over its
    /// timetable rows (`LiveOverlay.stationBoard`): asked only while «Partenze» is on screen with
    /// «Dati in tempo reale» on. When every source fails the rows stay the timetable's.
    func refreshBoardLive(now: Date = Date()) async {
        guard isForeground else { return }
        guard boardLiveVisible, liveDataEnabled, let liveData, let identity = boardStation else { return }
        boardLiveRequestID &+= 1
        let requestID = boardLiveRequestID
        boardLiveState = .loading
        let request = LiveDeparturesRequest(station: station(for: identity), from: StationBoardQuery.minuteStart(of: now))
        let result = await liveData.departures(request)
        guard requestID == boardLiveRequestID, boardStation == identity, liveDataEnabled else { return }
        liveSourcePauses = livePauses.active(at: now)
        switch result {
        case let .available(board):
            boardLive = BoardLive(stationID: identity.id, board: board, at: now)
            boardLiveSources = board.sources
            boardLiveState = .available(now)
        case .unavailable(.cancelled):
            boardLiveState = boardLiveRestingState
        case .unavailable:
            boardLive = nil
            boardLiveSources = []
            boardLiveState = .unavailable
        }
        setBoardRows(boardScheduled)
    }

    private var boardLiveRestingState: LiveState {
        guard isLiveDataActive else { return .off }
        guard let boardLive else { return .idle }
        return .available(boardLive.at)
    }

    private func restartBoardLive() {
        boardLiveLoad?.cancel()
        boardLiveLoad = nil
        guard isForeground, boardLiveVisible, isLiveDataActive, boardStation != nil else { return }
        boardLiveState = .loading
        boardLiveLoad = Task { await self.refreshBoardLive() }
    }

    private func cancelBoardLive() {
        boardLiveRequestID &+= 1
        boardLiveLoad?.cancel()
        boardLiveLoad = nil
        if boardLiveState == .loading { boardLiveState = boardLiveRestingState }
    }

    /// The rows of «Partenze»: the bundled timetable's, with the values of the last live board of
    /// the same station on the trains it lists; every other row stays «orario programmato».
    private func setBoardRows(_ rows: [StationBoardDeparture]) {
        boardScheduled = rows
        guard liveDataEnabled, let live = boardLive, live.stationID == boardStation?.id else {
            boardDepartures = rows
            return
        }
        boardDepartures = LiveOverlay.stationBoard(rows, with: live.board, at: live.at)
    }

    // MARK: Live data of the planner

    /// The planner is on screen: the live values of the result shown are asked again.
    func plannerLiveDidAppear() {
        plannerLiveVisible = true
        restartResultLive()
    }

    func plannerLiveDidDisappear() {
        plannerLiveVisible = false
        cancelResultLive()
    }

    /// Waits until the live values asked for the result have arrived.
    func resultLiveLoadFinished() async {
        await resultLiveLoad?.value
    }

    /// The live values of the listed trains, for a search leaving within the live window: the live
    /// board of its origin, one request whatever the number of trains, each train matched by its
    /// number proper within five minutes of its departure. Every source failing leaves the results
    /// as they are, on the bundled timetable.
    func refreshResultLive(now: Date = Date()) async {
        guard isForeground else { return }
        guard plannerLiveVisible, liveDataEnabled, let liveData, let snapshot = latestSnapshot else { return }
        let query = snapshot.query
        guard Self.isInLiveWindow(query.departure, now: now) else {
            resultLive = [:]
            resultLiveSources = []
            resultLiveAt = nil
            resultLiveState = .idle
            return
        }
        resultLiveRequestID &+= 1
        let requestID = resultLiveRequestID
        let searchID = searchRequestID
        resultLiveState = .loading
        let request = LiveDeparturesRequest(station: station(for: query.origin), from: StationBoardQuery.minuteStart(of: query.departure))
        let result = await liveData.departures(request)
        guard requestID == resultLiveRequestID, searchID == searchRequestID, liveDataEnabled else { return }
        liveSourcePauses = livePauses.active(at: now)
        switch result {
        case let .available(board):
            resultLive = Self.liveValues(of: snapshot, board: board, at: now)
            resultLiveSources = board.sources
            resultLiveAt = now
            resultLiveState = .available(now)
        case .unavailable(.cancelled):
            resultLiveState = resultLiveRestingState
        case .unavailable:
            resultLive = [:]
            resultLiveSources = []
            resultLiveAt = nil
            resultLiveState = .unavailable
        }
    }

    /// Each listed train's row on `board`, placed in time as the Watch places them (the "HH:mm" of
    /// each train from the query's minute on, in order), with what is still live at `now`; a live
    /// row goes to one listed train only (`LiveOverlay.departures(for:in:)`).
    static func liveValues(of journey: JourneySnapshot, board: LiveDepartureBoard, at now: Date) -> [String: StationBoardLiveStatus] {
        let calendar = timetableCalendar
        var cursor = journey.query.departure.addingTimeInterval(-60)
        var placed: [(service: TrainService, departure: Date)] = []
        for service in journey.services {
            guard let departure = nextOccurrence(of: service.scheduledDeparture, after: cursor, calendar: calendar) else { continue }
            cursor = departure.addingTimeInterval(-1)
            placed.append((service: service, departure: departure))
        }
        let planned: [LiveOverlay.PlannedTrain?] = placed.map { entry -> LiveOverlay.PlannedTrain? in
            entry.service.hasTrainNumber
                ? LiveOverlay.PlannedTrain(number: entry.service.number, scheduled: entry.departure, operatorName: entry.service.operatorName)
                : nil
        }
        let matched = LiveOverlay.departures(for: planned, in: board.departures)
        var live: [String: StationBoardLiveStatus] = [:]
        for (index, entry) in placed.enumerated() {
            guard let row = matched[index],
                  let status = row.stationBoardLiveStatus(at: now, timetable: entry.departure) else { continue }
            live[entry.service.id] = status
        }
        return live
    }

    private var resultLiveRestingState: LiveState {
        guard isLiveDataActive else { return .off }
        guard let resultLiveAt else { return .idle }
        return .available(resultLiveAt)
    }

    private func restartResultLive() {
        resultLiveLoad?.cancel()
        resultLiveLoad = nil
        guard isForeground, plannerLiveVisible, isLiveDataActive, latestSnapshot != nil else { return }
        resultLiveLoad = Task { await self.refreshResultLive() }
    }

    private func cancelResultLive() {
        resultLiveRequestID &+= 1
        resultLiveLoad?.cancel()
        resultLiveLoad = nil
        if resultLiveState == .loading { resultLiveState = resultLiveRestingState }
    }

    private func clearResultLive() {
        cancelResultLive()
        resultLive = [:]
        resultLiveSources = []
        resultLiveAt = nil
        resultLiveState = isLiveDataActive ? .idle : .off
    }

    // MARK: Live data of a train detail and «I miei treni»

    /// The run a train detail asks about, when it leaves within the live window: its number proper
    /// and its "HH:mm" at the boarding station, from the query's time on (a board row's departure,
    /// a search's time) and never before the window (a favourite saved weeks ago means today's
    /// run). Nil for a train without a number the sources know (a line name, a route's short name)
    /// or outside the window.
    func liveTrainKey(for service: TrainService, query: JourneyQuery, now: Date = Date()) -> LiveTrainKey? {
        guard service.hasTrainNumber else { return nil }
        let number = NetworkReliability.numberKey(service.number)
        let digits = number.unicodeScalars.allSatisfy { ("0"..."9").contains($0) }
        let anchor = max(query.departure, now.addingTimeInterval(-Self.liveLookback))
        guard digits, (1...6).contains(number.count),
              let departure = Self.nextOccurrence(
                  of: service.scheduledDeparture, after: anchor.addingTimeInterval(-60), calendar: Self.timetableCalendar
              ),
              Self.isInLiveWindow(departure, now: now) else { return nil }
        return LiveTrainKey(number: number, departure: departure, boarding: query.origin)
    }

    /// A train detail is on screen: its run is asked now.
    func detailLiveDidAppear(_ key: LiveTrainKey) {
        if detailLive?.key != key { detailLive = nil }
        detailLiveKey = key
        restartDetailLive()
    }

    /// The detail of `key` left the screen (another detail may already be on it).
    func detailLiveDidDisappear(_ key: LiveTrainKey) {
        guard detailLiveKey == key else { return }
        detailLiveKey = nil
        cancelDetailLive()
    }

    func pullDetailLive(_ key: LiveTrainKey) async {
        guard detailLiveKey == key else { return }
        restartDetailLive()
        await detailLiveLoad?.value
    }

    func detailLiveLoadFinished() async {
        await detailLiveLoad?.value
    }

    /// The run of the detail on screen, which wins over the row it was opened from.
    func liveRun(for key: LiveTrainKey) -> TrainLiveStatus? {
        guard liveDataEnabled, let detailLive, detailLive.key == key else { return nil }
        return detailLive
    }

    func liveRunState(for key: LiveTrainKey) -> LiveState {
        guard isLiveDataActive else { return .off }
        return detailLiveKey == key ? detailLiveState : .idle
    }

    /// The status of the run of the detail on screen (`trainLive`).
    func refreshDetailLive(now: Date = Date()) async {
        guard isForeground else { return }
        guard liveDataEnabled, let liveData, let key = detailLiveKey else { return }
        detailLiveRequestID &+= 1
        let requestID = detailLiveRequestID
        detailLiveState = .loading
        let outcome = await Self.trainLive(key, from: liveData, at: now)
        guard requestID == detailLiveRequestID, detailLiveKey == key, liveDataEnabled else { return }
        liveSourcePauses = livePauses.active(at: now)
        switch outcome {
        case let .found(status):
            detailLive = status
            detailLiveState = .available(now)
        case .cancelled:
            if let detailLive {
                detailLiveState = .available(detailLive.receivedAt)
            } else {
                detailLiveState = .idle
            }
        case .notFound, .failed:
            detailLive = nil
            detailLiveState = .unavailable
        }
    }

    private func restartDetailLive() {
        detailLiveLoad?.cancel()
        detailLiveLoad = nil
        guard isForeground, isLiveDataActive, detailLiveKey != nil else { return }
        detailLiveState = .loading
        detailLiveLoad = Task { await self.refreshDetailLive() }
    }

    private func cancelDetailLive() {
        detailLiveRequestID &+= 1
        detailLiveLoad?.cancel()
        detailLiveLoad = nil
        guard detailLiveState == .loading else { return }
        guard isLiveDataActive else {
            detailLiveState = .off
            return
        }
        if let detailLive {
            detailLiveState = .available(detailLive.receivedAt)
        } else {
            detailLiveState = .idle
        }
    }

    /// «I miei treni» is on screen: today's runs of its first favourites are asked now.
    func favouritesLiveDidAppear() {
        favouritesLiveVisible = true
        restartFavouritesLive()
    }

    func favouritesLiveDidDisappear() {
        favouritesLiveVisible = false
        cancelFavouritesLive()
    }

    func pullFavouritesLive() async {
        restartFavouritesLive()
        await favouriteLiveLoad?.value
    }

    func favouritesLiveLoadFinished() async {
        await favouriteLiveLoad?.value
    }

    /// Today's live state of the first `maximumLiveFavourites` favourites leaving within the live
    /// window, asked one train after the other through the sources' gates while «I miei treni» is
    /// on screen; each row shows its state as soon as it arrives.
    func refreshFavouritesLive(now: Date = Date()) async {
        guard isForeground else { return }
        guard favouritesLiveVisible, liveDataEnabled, let liveData else { return }
        favouriteLiveRequestID &+= 1
        let requestID = favouriteLiveRequestID
        var runs: [(id: String, run: LiveTrainKey)] = []
        for train in favouriteTrains.prefix(Self.maximumLiveFavourites) {
            guard let query = train.query, let run = liveTrainKey(for: train.service, query: query, now: now) else { continue }
            runs.append((id: train.id, run: run))
        }
        // Only the values of the runs asked now stay on screen meanwhile.
        var shown: [String: TrainLiveStatus] = [:]
        for entry in runs {
            if let kept = favouriteLive[entry.id], kept.key == entry.run { shown[entry.id] = kept }
        }
        favouriteLive = shown
        guard !runs.isEmpty else {
            favouriteLiveState = .idle
            return
        }
        favouriteLiveState = .loading
        var failures = 0
        for entry in runs {
            let outcome = await Self.trainLive(entry.run, from: liveData, at: now)
            guard requestID == favouriteLiveRequestID, favouritesLiveVisible, liveDataEnabled else { return }
            switch outcome {
            case let .found(status):
                shown[entry.id] = status
            case .notFound:
                shown[entry.id] = nil
            case .failed:
                shown[entry.id] = nil
                failures += 1
            case .cancelled:
                favouriteLiveState = shown.isEmpty ? .idle : .available(now)
                return
            }
            favouriteLive = shown
        }
        liveSourcePauses = livePauses.active(at: now)
        favouriteLiveState = shown.isEmpty && failures > 0 ? .unavailable : .available(now)
    }

    private func restartFavouritesLive() {
        favouriteLiveLoad?.cancel()
        favouriteLiveLoad = nil
        guard isForeground, favouritesLiveVisible, isLiveDataActive, !favouriteTrains.isEmpty else { return }
        favouriteLiveState = .loading
        favouriteLiveLoad = Task { await self.refreshFavouritesLive() }
    }

    private func cancelFavouritesLive() {
        favouriteLiveRequestID &+= 1
        favouriteLiveLoad?.cancel()
        favouriteLiveLoad = nil
        guard favouriteLiveState == .loading else { return }
        guard isLiveDataActive else {
            favouriteLiveState = .off
            return
        }
        if let receivedAt = favouriteLive.values.map(\.receivedAt).max() {
            favouriteLiveState = .available(receivedAt)
        } else {
            favouriteLiveState = .idle
        }
    }

    /// The live state of the run `key` names: ViaggiaTreno and Italo asked by its number proper and
    /// the day, the boarding station's code picking the run (`LiveTrainStatusLookup.run(for:)`)
    /// among those calling there within `sameRunTolerance` of the timetable's departure, so another
    /// day's run of the same number, or another train with that number, is never shown.
    private static func trainLive(_ key: LiveTrainKey, from liveData: any LiveRailProviding, at now: Date) async -> TrainLiveOutcome {
        let code = NetworkReliability.stationCode(key.boarding)
        let request = LiveTrainStatusRequest(number: key.number, serviceDate: key.departure, stationCode: code)
        switch await liveData.trainStatus(request) {
        case let .available(lookup):
            let sameRun = lookup.runs.filter { run in
                guard let stop = LiveOverlay.stop(in: run, stationCode: code, stationName: key.boarding.name) else { return false }
                let scheduled: Date = stop.scheduledDeparture ?? stop.scheduled
                return abs(scheduled.timeIntervalSince(key.departure)) <= sameRunTolerance
            }
            let candidates = LiveTrainStatusLookup(runs: sameRun, unavailableSources: lookup.unavailableSources, isComplete: lookup.isComplete)
            guard let run = candidates.run(for: request),
                  let stop = LiveOverlay.stop(in: run, stationCode: code, stationName: key.boarding.name) else { return .notFound }
            return .found(TrainLiveStatus(key: key, run: run, boarding: stop, receivedAt: now))
        case .unavailable(.cancelled):
            return .cancelled
        case .unavailable(.notFound), .unavailable(.notCovered), .unavailable(.invalidRequest):
            return .notFound
        case .unavailable:
            return .failed
        }
    }

    func takePlannerRequest() -> PlannerRequest? {
        defer { plannerRequest = nil }
        return plannerRequest
    }

    /// The catalogue entry of a saved station, or the saved identity itself while the catalogue
    /// is not loaded: a query needs only the identity.
    func station(for identity: StationIdentity) -> Station {
        if case let .available(catalog) = catalogState, let station = catalog.station(id: identity.id) {
            return station
        }
        return Station(id: identity.id, name: identity.name, railwayCode: identity.railwayCode)
    }

    /// What Settings shows: a switched-off toggle reads as off, whatever the session reports.
    var displayedWatchSyncStatus: WatchSyncStatus {
        watchSharingEnabled ? watchSyncStatus : .disabled
    }

    func setWatchSharingEnabled(_ enabled: Bool) {
        guard watchSharingEnabled != enabled else { return }
        watchSharingStore.save(enabled)
        watchSharingEnabled = enabled
        if enabled {
            watchSyncStatus = watchPublisher?.status ?? .unsupported
            republishLatestSearch()
        } else {
            revokeWatchSnapshot()
        }
    }

    func save(query: JourneyQuery) {
        let id = SavedRoute.routeIdentifier(origin: query.origin, destination: query.destination)
        guard !savedRoutes.contains(where: { $0.id == id }) else { return }
        guard savedRoutes.count < SavedRouteCollection.maximumRoutes else {
            persistenceMessageKey = "saved.limit_error"
            return
        }

        do {
            let route = try SavedRoute(
                id: id,
                name: "\(query.origin.name) → \(query.destination.name)",
                origin: query.origin,
                destination: query.destination
            )
            try setRoutes(savedRoutes + [route])
        } catch {
            persistenceMessageKey = "saved.storage_error"
        }
    }

    func deleteRoutes(at offsets: IndexSet) {
        do {
            let retained = savedRoutes.enumerated().compactMap { index, route in
                offsets.contains(index) ? nil : route
            }
            try setRoutes(retained)
        } catch {
            persistenceMessageKey = "saved.storage_error"
        }
    }

    func deleteAllRoutes() {
        do {
            try setRoutes([])
        } catch {
            persistenceMessageKey = "saved.storage_error"
        }
    }

    /// Whether the train of a detail is in «I miei treni»: its number boarded at the query's origin.
    func isFavourite(_ service: TrainService, query: JourneyQuery) -> Bool {
        let id = FavouriteTrain.identifier(number: service.number, originID: query.origin.id)
        return favouriteTrains.contains { $0.id == id }
    }

    /// Whether the star of a detail is offered: a saved train can always be removed; a train is
    /// saved only with a train number (not a line name nor a route's short name, which every train
    /// of the line shares) and the texts the timetable bounds.
    func canToggleFavourite(_ service: TrainService, query: JourneyQuery) -> Bool {
        if isFavourite(service, query: query) { return true }
        guard service.hasTrainNumber else { return false }
        return (try? FavouriteTrain(service: service, query: query, savedAt: Date())) != nil
    }

    /// The star of a train detail: saves the train first in «I miei treni» or, when it is there,
    /// removes it. A full list refuses the train rather than dropping another one.
    func toggleFavourite(_ service: TrainService, query: JourneyQuery, now: Date = Date()) -> FavouriteChange {
        let id = FavouriteTrain.identifier(number: service.number, originID: query.origin.id)
        let current = FavouriteTrainCollection.normalized(favouriteTrains)
        if current.contains(id: id) {
            return setFavourites(current.removing(ids: [id])) ? .removed : .failed
        }
        guard service.hasTrainNumber,
              let train = try? FavouriteTrain(service: service, query: query, savedAt: now) else { return .failed }
        let updated: FavouriteTrainCollection
        do {
            updated = try current.adding(train)
        } catch FavouriteTrainValidationError.tooManyTrains {
            return .limitReached
        } catch {
            return .failed
        }
        return setFavourites(updated) ? .added : .failed
    }

    /// Removes favourite trains from «I miei treni»; false when the list could not be written.
    func removeFavourites(ids: Set<String>) -> Bool {
        setFavourites(FavouriteTrainCollection.normalized(favouriteTrains).removing(ids: ids))
    }

    private func setFavourites(_ collection: FavouriteTrainCollection) -> Bool {
        do {
            try favouriteTrainStore.save(collection)
        } catch {
            return false
        }
        favouriteTrains = collection.trains
        return true
    }

    private func setRoutes(_ routes: [SavedRoute]) throws {
        let validated = try SavedRouteCollection(validating: routes)
        try savedRouteStore.save(validated.routes)
        savedRoutes = validated.routes
        if pinnedWatchRouteID != nil, pinnedWatchRoute == nil {
            unpinWatchRoute()
        }
    }

    private func revokeWatchSnapshot() {
        guard let watchPublisher else {
            watchSyncStatus = .unsupported
            return
        }
        do {
            try watchPublisher.clear()
        } catch {
            watchSyncStatus = watchPublisher.status
        }
    }

    /// Turning sharing on sends the Watch the search already on screen, if it was for now, or
    /// else the pinned route.
    private func republishLatestSearch() {
        guard let route = watchRouteQuery else {
            schedulePinnedPublication()
            return
        }
        if let snapshot = latestSnapshot {
            publishAvailableSnapshot(snapshot)
        } else if case .unavailable = searchState {
            publishUnavailableSnapshot(for: route)
        }
    }

    /// Callers publish only searches for now (isCurrent at completion).
    private func publishUnavailableSnapshot(for query: JourneyQuery) {
        guard watchSharingEnabled, let watchPublisher else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1_000)
        let snapshot = WearSnapshotV1.unavailable(
            routeId: SavedRoute.routeIdentifier(origin: query.origin, destination: query.destination),
            routeLabel: watchRouteLabel(origin: query.origin, destination: query.destination),
            direction: query.direction,
            generatedAtEpochMillis: now,
            sourceLabel: String(localized: "watch.source.unavailable"),
            notice: String(localized: "watch.notice.unavailable")
        )
        do {
            try watchPublisher.publish(snapshot, nowEpochMillis: now)
        } catch {
            watchSyncStatus = watchPublisher.status
        }
    }

    /// The Watch lists the next trains: only those that have not left are sent, so a result
    /// searched a while ago never shows departed trains as upcoming. Without any, the Watch gets
    /// the unavailable state for the route.
    private func publishAvailableSnapshot(_ journey: JourneySnapshot) {
        let nowDate = Date()
        guard watchSharingEnabled, let watchPublisher else { return }
        let departures = Array(Self.upcomingDepartures(in: journey, now: nowDate).prefix(WearSnapshotV1.maximumServices))
        guard !departures.isEmpty else {
            publishUnavailableSnapshot(for: journey.query)
            return
        }
        let services = departures.map(\.service)
        let now = Int64(nowDate.timeIntervalSince1970 * 1_000)
        let generatedAt = Int64(journey.generatedAt.timeIntervalSince1970 * 1_000)
        let expiresAt = Int64(Self.watchExpiry(for: journey, departures: departures, now: nowDate).timeIntervalSince1970 * 1_000)
        let snapshot = WearSnapshotV1(
            generatedAtEpochMillis: generatedAt,
            expiresAtEpochMillis: expiresAt,
            ttlMillis: expiresAt - generatedAt,
            provenanceProducer: journey.provenance.producer,
            provenanceDataNature: journey.provenance.nature,
            provenanceSourceLabel: journey.provenance.sourceLabel,
            provenanceObservedAtEpochMillis: Int64(
                journey.provenance.observedAt.timeIntervalSince1970 * 1_000
            ),
            operationalState: journey.operationalState,
            freshness: journey.provenance.freshness,
            confidence: journey.provenance.confidence,
            routeId: SavedRoute.routeIdentifier(
                origin: journey.query.origin,
                destination: journey.query.destination
            ),
            routeLabel: watchRouteLabel(origin: journey.query.origin, destination: journey.query.destination),
            direction: journey.query.direction,
            notice: String(localized: "watch.notice.programmed"),
            services: services.map { service in
                WearServiceV1(
                    number: service.number,
                    operatorName: service.operatorName,
                    category: service.category,
                    destination: service.destination,
                    scheduled: service.scheduledDeparture,
                    expected: service.expectedDeparture,
                    status: service.status,
                    platform: service.platform ?? "",
                    delayMinutes: service.delayMinutes
                )
            }
        )
        do {
            try watchPublisher.publish(snapshot, nowEpochMillis: now)
        } catch {
            watchSyncStatus = watchPublisher.status
        }
    }

    /// The Watch presents its snapshot as the next trains of the route, so only a search for
    /// now is published: a search for another day or time stays on the iPhone. The planner
    /// searches from the current time unless the user picked one, so a few minutes cover a time
    /// picked at minute precision; the trains that have left are dropped before publishing.
    static func isCurrent(_ query: JourneyQuery, now: Date) -> Bool {
        let offset = query.departure.timeIntervalSince(now)
        return offset >= -5 * 60 && offset <= 15 * 60
    }

    /// The listed trains that have not left at `now`, with their departure instants, up to the
    /// repository's expiry. The Watch resolves the same "HH:mm" times after the snapshot's
    /// generation (apple-watch/Shared/WatchDepartures.swift), unambiguous within that window.
    static func upcomingDepartures(in journey: JourneySnapshot, now: Date) -> [WatchDeparture] {
        let calendar = timetableCalendar
        // The repository lists trains from the query time on; "HH:mm" drops the seconds.
        var cursor = journey.query.departure.addingTimeInterval(-60)
        var upcoming: [WatchDeparture] = []
        for service in journey.services {
            guard let departure = nextOccurrence(of: service.expectedDeparture, after: cursor, calendar: calendar) else {
                // A train that cannot be placed in time cannot be shown as the next one.
                continue
            }
            // Two trains at the same minute must resolve to the same day.
            cursor = departure.addingTimeInterval(-1)
            guard departure >= now, departure < journey.expiresAt else { continue }
            upcoming.append(WatchDeparture(service: service, departure: departure))
        }
        return upcoming
    }

    /// The snapshot must not outlive the last train it lists; the repository's 12-hour expiry
    /// would keep departed trains on the Watch as upcoming ones.
    static func watchExpiry(for journey: JourneySnapshot, departures: [WatchDeparture], now: Date) -> Date {
        guard let lastDeparture = departures.last?.departure else { return journey.expiresAt }
        return min(journey.expiresAt, max(now.addingTimeInterval(60), lastDeparture.addingTimeInterval(2 * 60)))
    }

    /// The planner shows and picks times in the timetable zone; a caption says so when the
    /// device clock reads differently at that moment.
    static func needsTimetableZoneCaption(at date: Date, deviceTimeZone: TimeZone = .current) -> Bool {
        deviceTimeZone.secondsFromGMT(for: date) != timetableTimeZone.secondsFromGMT(for: date)
    }

    private static var timetableCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timetableTimeZone
        return calendar
    }

    /// The route title of the Watch is the station names; the route identifier is added only
    /// when another catalogue station shares a name, the one way two routes read the same (no
    /// two catalogue names share the 45-character prefix kept when a long label is shortened).
    private func watchRouteLabel(origin: StationIdentity, destination: StationIdentity) -> String {
        var disambiguate = false
        if case let .available(catalog) = catalogState {
            disambiguate = catalog.hasNamesake(origin) || catalog.hasNamesake(destination)
        }
        return WatchRouteLabelFormatter.format(origin: origin, destination: destination, disambiguate: disambiguate)
    }

    private static func nextOccurrence(of time: String, after start: Date, calendar: Calendar) -> Date? {
        let parts = time.split(separator: ":")
        guard parts.count == 2,
              let hour = Int(parts[0]), let minute = Int(parts[1]),
              (0..<24).contains(hour), (0..<60).contains(minute) else { return nil }
        return calendar.nextDate(
            after: start,
            matching: DateComponents(hour: hour, minute: minute),
            matchingPolicy: .nextTime
        )
    }
}

private struct InstalledSchedule: Sendable {
    let repository: SQLiteGTFSJourneyRepository
    let packs: [GTFSSourcePack]
    let coveredStationIDs: Set<String>
    let sourcePolicy: GTFSSourcePolicy?
}

/// The bundled index and its cache, as `AppModel.loadSchedule` opened them.
private struct ScheduleInputs: Sendable {
    let compressedURL: URL
    let metadataURL: URL
    let sourcePolicyURL: URL?
    let cacheDirectory: URL
}

@MainActor
final class SavedRouteStore {
    static let storageKey = "it.binario.ios.saved-routes.v1"

    private let defaults: UserDefaults
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        encoder = JSONEncoder()
        decoder = JSONDecoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        decoder.dateDecodingStrategy = .millisecondsSince1970
    }

    func load() -> [SavedRoute] {
        guard let data = defaults.data(forKey: Self.storageKey),
              let routes = try? decoder.decode([SavedRoute].self, from: data),
              let validated = try? SavedRouteCollection(validating: routes)
        else { return [] }
        return validated.routes
    }

    func save(_ routes: [SavedRoute]) throws {
        let validated = try SavedRouteCollection(validating: routes)
        defaults.set(try encoder.encode(validated.routes), forKey: Self.storageKey)
    }
}

@MainActor
final class WatchSharingPreferenceStore {
    static let storageKey = "it.binario.ios.watch-sharing-enabled.v1"
    static let pinnedRouteStorageKey = "it.binario.ios.watch-pinned-route.v1"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> Bool {
        defaults.bool(forKey: Self.storageKey)
    }

    func save(_ enabled: Bool) {
        defaults.set(enabled, forKey: Self.storageKey)
    }

    /// The identifier of the saved route pinned to the Watch («Mostra su Watch»), or nil.
    func loadPinnedRouteID() -> String? {
        guard let identifier = defaults.string(forKey: Self.pinnedRouteStorageKey),
              !identifier.isEmpty, identifier.count <= 128 else { return nil }
        return identifier
    }

    func savePinnedRouteID(_ identifier: String?) {
        if let identifier {
            defaults.set(identifier, forKey: Self.pinnedRouteStorageKey)
        } else {
            defaults.removeObject(forKey: Self.pinnedRouteStorageKey)
        }
    }
}

/// «Dati in tempo reale» in Settings, in the app's `UserDefaults` on this iPhone only: on by
/// default, as the owner decided; the user can turn it off. Only a boolean the app wrote counts:
/// a missing value, or a value of any other type, reads as on.
final class LiveDataPreferenceStore {
    static let storageKey = "it.binario.ios.live-data-enabled.v1"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> Bool {
        guard let stored = defaults.object(forKey: Self.storageKey) as? NSNumber,
              CFGetTypeID(stored) == CFBooleanGetTypeID() else { return true }
        return stored.boolValue
    }

    func save(_ enabled: Bool) {
        defaults.set(enabled, forKey: Self.storageKey)
    }
}

extension WatchSyncStatus {
    var localizationKey: String {
        switch self {
        case .disabled: "watch.status.disabled"
        case .activating: "watch.status.activating"
        case .pending: "watch.status.pending"
        case .contextUpdated: "watch.status.context_updated"
        case .cleared: "watch.status.cleared"
        case .unsupported: "watch.status.unsupported"
        case .notPaired: "watch.status.not_paired"
        case .failed: "watch.status.failed"
        }
    }
}
