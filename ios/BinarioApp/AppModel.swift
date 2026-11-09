import Foundation
import Combine
import BinarioKit

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

    private(set) var repository: any JourneyRepository
    private var searchRequestID = 0
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
    private let watchSharingStore: WatchSharingPreferenceStore
    private let watchPublisher: WatchSnapshotPublisher?
    private let backgroundActivity: (any BackgroundActivityRunning)?

    init(
        catalogState: CatalogState,
        savedRouteStore: SavedRouteStore,
        favouriteTrainStore: FavouriteTrainStore = FavouriteTrainStore(),
        watchSharingStore: WatchSharingPreferenceStore,
        repository: any JourneyRepository,
        scheduleState: ScheduleState = .available,
        coveredStationIDs: Set<String>? = nil,
        watchPublisher: WatchSnapshotPublisher?,
        backgroundActivity: (any BackgroundActivityRunning)? = nil,
        versionDisplay: String
    ) {
        self.catalogState = catalogState
        self.savedRouteStore = savedRouteStore
        self.favouriteTrainStore = favouriteTrainStore
        self.watchSharingStore = watchSharingStore
        self.repository = repository
        self.scheduleState = scheduleState
        self.coveredStationIDs = coveredStationIDs
        self.latestSnapshot = nil
        self.watchPublisher = watchPublisher
        self.backgroundActivity = backgroundActivity
        self.versionDisplay = versionDisplay
        let routes = savedRouteStore.load()
        self.savedRoutes = routes
        self.favouriteTrains = favouriteTrainStore.load().trains
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
            watchSharingStore: WatchSharingPreferenceStore(),
            repository: FailClosedJourneyRepository(),
            scheduleState: .loading,
            watchPublisher: publisher,
            backgroundActivity: background,
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
            schedulePacks = []
            coveredStationIDs = nil
            sourcePolicy = nil
            scheduleState = .unavailable
            updateAirportLinks()
            endPendingWatchRefresh()
            return
        }
        repository = installed.repository
        schedulePacks = installed.packs
        coveredStationIDs = installed.coveredStationIDs
        sourcePolicy = installed.sourcePolicy
        scheduleState = .available
        updateAirportLinks()
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
        switch result {
        case let .available(snapshot):
            searchState = .idle
            latestSnapshot = snapshot
            resultReliability = reliabilityStats.map { JourneyReliability.assess(snapshot, stats: $0) }
            if isForNow { publishAvailableSnapshot(snapshot) }
        case let .unavailable(reason):
            searchState = .unavailable(reason)
            latestSnapshot = nil
            resultReliability = nil
            if reason == .noAuthorizedProvider || reason == .providerInvalid {
                scheduleState = .unavailable
            }
            if isForNow { publishUnavailableSnapshot(for: query) }
        }
    }

    func invalidateSearchResult() {
        searchRequestID &+= 1
        searchState = .idle
        latestSnapshot = nil
        resultReliability = nil
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
    /// saved only with a train number (not a line name) and the texts the timetable bounds.
    func canToggleFavourite(_ service: TrainService, query: JourneyQuery) -> Bool {
        if isFavourite(service, query: query) { return true }
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
        guard let train = try? FavouriteTrain(service: service, query: query, savedAt: now) else { return .failed }
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
