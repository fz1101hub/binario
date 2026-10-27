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

    @Published private(set) var catalogState: CatalogState
    @Published private(set) var savedRoutes: [SavedRoute]
    @Published private(set) var watchSharingEnabled: Bool
    @Published fileprivate(set) var watchSyncStatus: WatchSyncStatus
    @Published var searchState: SearchState = .idle
    @Published private(set) var scheduleState: ScheduleState
    @Published private(set) var latestSnapshot: JourneySnapshot?
    @Published private(set) var isRefreshing = false
    @Published private(set) var commuterRouteID: String?
    @Published private(set) var commuterDirection: JourneyDirection = .outbound
    @Published var persistenceMessageKey: String?

    private(set) var repository: any JourneyRepository
    private var searchRequestID = 0
    private var lastCommuteAttempt: Date?
    let versionDisplay: String

    private let savedRouteStore: SavedRouteStore
    private let watchSharingStore: WatchSharingPreferenceStore
    private let watchPublisher: WatchSnapshotPublisher?
    private let realtimeCatalog: RfiStationCatalog?
    var hasRealtimeProvider: Bool { realtimeCatalog != nil }

    init(
        catalogState: CatalogState,
        savedRouteStore: SavedRouteStore,
        watchSharingStore: WatchSharingPreferenceStore,
        repository: any JourneyRepository,
        scheduleState: ScheduleState = .available,
        watchPublisher: WatchSnapshotPublisher?,
        versionDisplay: String,
        realtimeCatalog: RfiStationCatalog? = nil
    ) {
        self.catalogState = catalogState
        self.savedRouteStore = savedRouteStore
        self.watchSharingStore = watchSharingStore
        self.repository = repository
        self.scheduleState = scheduleState
        self.latestSnapshot = nil
        self.watchPublisher = watchPublisher
        self.realtimeCatalog = realtimeCatalog
        self.versionDisplay = versionDisplay
        self.savedRoutes = savedRouteStore.load()
        self.commuterRouteID = savedRouteStore.commuterRouteID
        self.commuterDirection = savedRouteStore.commuterDirection
        let sharingEnabled = watchSharingStore.load()
        self.watchSharingEnabled = sharingEnabled
        self.watchSyncStatus = sharingEnabled ? .activating : .disabled

        watchPublisher?.statusDidChange = { [weak self] status in
            guard let self else { return }
            self.watchSyncStatus = status
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

        let realtimeCatalog = bundle.url(forResource: "rfi_stations.v1", withExtension: "json")
            .flatMap { try? Data(contentsOf: $0) }
            .flatMap { try? RfiStationCatalog(data: $0) }
        let liveRepository: any JourneyRepository
        if let realtimeCatalog {
            liveRepository = RfiJourneyRepository(catalog: realtimeCatalog)
        } else {
            liveRepository = FailClosedJourneyRepository()
        }
        let model = AppModel(
            catalogState: .loading,
            savedRouteStore: SavedRouteStore(),
            watchSharingStore: WatchSharingPreferenceStore(),
            repository: liveRepository,
            scheduleState: .loading,
            watchPublisher: publisher,
            versionDisplay: versionFormatter.displayVersion(bundle: bundle),
            realtimeCatalog: realtimeCatalog
        )

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
            let applicationSupport = FileManager.default.urls(
                for: .applicationSupportDirectory,
                in: .userDomainMask
            ).first
        else {
            model.scheduleState = realtimeCatalog == nil ? .unavailable : .available
            return model
        }
        let cacheDirectory = applicationSupport
            .appendingPathComponent("Binario", isDirectory: true)
            .appendingPathComponent("GTFS", isDirectory: true)
        Task {
            await model.loadSchedule(
                compressedURL: compressedURL,
                metadataURL: metadataURL,
                cacheDirectory: cacheDirectory
            )
        }
        return model
    }

    func loadCatalog(data: @escaping @Sendable () throws -> Data) async {
        catalogState = .loading
        catalogState = await StationCatalogLoader.load(data: data)
    }

    func suggestions(for query: String) -> [Station] {
        guard case let .available(catalog) = catalogState else { return [] }
        return catalog.search(query)
    }

    func loadSchedule(compressedURL: URL, metadataURL: URL, cacheDirectory: URL) async {
        scheduleState = realtimeCatalog == nil ? .loading : .available
        let installedURL = await Task.detached(priority: .utility) {
            try? BundledGTFSScheduleInstaller().install(
                compressedURL: compressedURL,
                metadataURL: metadataURL,
                cacheDirectory: cacheDirectory
            )
        }.value
        guard let installedURL else {
            repository = withRealtime(fallback: FailClosedJourneyRepository())
            scheduleState = realtimeCatalog == nil ? .unavailable : .available
            return
        }
        repository = withRealtime(fallback: SQLiteGTFSJourneyRepository(databaseURL: installedURL))
        scheduleState = .available
    }

    private func withRealtime(fallback: any JourneyRepository) -> any JourneyRepository {
        guard let realtimeCatalog else { return fallback }
        return RfiJourneyRepository(catalog: realtimeCatalog, fallback: fallback)
    }

    func run(query: JourneyQuery, followingCommute: Bool = false) async {
        if !followingCommute { lastCommuteAttempt = nil }
        searchRequestID &+= 1
        let requestID = searchRequestID
        isRefreshing = true
        defer {
            if requestID == searchRequestID {
                isRefreshing = false
                if Task.isCancelled && followingCommute { lastCommuteAttempt = nil }
            }
        }
        let result = await repository.journeys(for: query)
        guard requestID == searchRequestID else { return }
        guard !Task.isCancelled else { return }
        switch result {
        case let .available(snapshot):
            searchState = .idle
            latestSnapshot = snapshot
            publishAvailableSnapshot(snapshot)
        case let .unavailable(reason):
            searchState = .unavailable(reason)
            latestSnapshot = nil
            if realtimeCatalog == nil && (reason == .noAuthorizedProvider || reason == .providerInvalid) {
                scheduleState = .unavailable
            }
            publishUnavailableSnapshot(for: query)
        }
    }

    func invalidateSearchResult() {
        searchRequestID &+= 1
        isRefreshing = false
        lastCommuteAttempt = nil
        searchState = .idle
        latestSnapshot = nil
        if watchSharingEnabled {
            revokeWatchSnapshot()
        }
    }

    var commuterRoute: SavedRoute? { savedRoutes.first { $0.id == commuterRouteID } }

    func followRoute(_ route: SavedRoute, direction: JourneyDirection = .outbound, now: Date = Date()) async {
        guard scheduleState == .available, savedRoutes.contains(where: { $0.id == route.id }) else { return }
        let reversed = direction == .return
        guard let query = try? JourneyQuery(
            origin: reversed ? route.destination : route.origin,
            destination: reversed ? route.origin : route.destination,
            departure: now,
            direction: direction
        ) else { return }
        if commuterRouteID != route.id || commuterDirection != direction {
            invalidateSearchResult()
        }
        commuterRouteID = route.id
        commuterDirection = direction
        savedRouteStore.selectCommute(routeID: route.id, direction: direction)
        lastCommuteAttempt = now
        await run(query: query, followingCommute: true)
    }

    /// The view owns the cancellable foreground loop. There is no background timer or location access.
    func refreshCommute(now: Date = Date(), force: Bool = false) async {
        guard let route = commuterRoute, scheduleState == .available, !isRefreshing else { return }
        let failed: Bool
        if case .unavailable = searchState { failed = true } else { failed = false }
        if force || CommuterRefreshPolicy.shouldRefresh(
            loading: isRefreshing, lastAttempt: lastCommuteAttempt,
            now: now, failed: failed
        ) {
            await followRoute(route, direction: commuterDirection, now: now)
        }
    }

    func setWatchSharingEnabled(_ enabled: Bool) {
        guard watchSharingEnabled != enabled else { return }
        watchSharingStore.save(enabled)
        watchSharingEnabled = enabled
        if enabled {
            watchSyncStatus = watchPublisher?.status ?? .unsupported
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

    private func setRoutes(_ routes: [SavedRoute]) throws {
        let validated = try SavedRouteCollection(validating: routes)
        try savedRouteStore.save(validated.routes)
        savedRoutes = validated.routes
        if commuterRouteID != nil, commuterRoute == nil {
            commuterRouteID = nil
            savedRouteStore.selectCommute(routeID: nil, direction: .outbound)
            invalidateSearchResult()
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

    private func publishUnavailableSnapshot(for query: JourneyQuery) {
        guard watchSharingEnabled, let watchPublisher, Self.isCurrent(query, now: Date()) else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1_000)
        let snapshot = WearSnapshotV1.unavailable(
            routeId: SavedRoute.routeIdentifier(origin: query.origin, destination: query.destination),
            routeLabel: WatchRouteLabelFormatter.format(origin: query.origin, destination: query.destination),
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

    private func publishAvailableSnapshot(_ journey: JourneySnapshot) {
        let nowDate = Date()
        guard watchSharingEnabled, let watchPublisher, Self.isCurrent(journey.query, now: nowDate) else { return }
        let now = Int64(nowDate.timeIntervalSince1970 * 1_000)
        let services = Array(journey.services.prefix(WearSnapshotV1.maximumServices))
        let generatedAt = Int64(journey.generatedAt.timeIntervalSince1970 * 1_000)
        let expiresAt = Int64(Self.watchExpiry(for: journey, services: services, now: nowDate).timeIntervalSince1970 * 1_000)
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
            routeLabel: WatchRouteLabelFormatter.format(
                origin: journey.query.origin,
                destination: journey.query.destination
            ),
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
    /// now is published: a search for another day or time stays on the iPhone. The window
    /// tolerates a planner opened a while ago with its default time.
    static func isCurrent(_ query: JourneyQuery, now: Date) -> Bool {
        let offset = query.departure.timeIntervalSince(now)
        return offset >= -30 * 60 && offset <= 15 * 60
    }

    /// The snapshot must not outlive the last train it lists; the repository's 12-hour expiry
    /// would keep departed trains on the Watch as upcoming ones.
    static func watchExpiry(for journey: JourneySnapshot, services: [TrainService], now: Date) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Rome") ?? .current
        var cursor = journey.query.departure.addingTimeInterval(-60)
        var lastDeparture: Date?
        for service in services {
            guard let departure = nextOccurrence(of: service.expectedDeparture, after: cursor, calendar: calendar) else {
                return journey.expiresAt
            }
            lastDeparture = departure
            // Two trains at the same minute must resolve to the same day.
            cursor = departure.addingTimeInterval(-1)
        }
        guard let lastDeparture else { return journey.expiresAt }
        return min(journey.expiresAt, max(now.addingTimeInterval(60), lastDeparture.addingTimeInterval(2 * 60)))
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

@MainActor
final class SavedRouteStore {
    static let storageKey = "it.binario.ios.saved-routes.v1"
    private static let commuterRouteKey = "it.binario.ios.commuter-route.v1"
    private static let commuterDirectionKey = "it.binario.ios.commuter-direction.v1"

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

    var commuterRouteID: String? { defaults.string(forKey: Self.commuterRouteKey) }
    var commuterDirection: JourneyDirection {
        JourneyDirection(rawValue: defaults.string(forKey: Self.commuterDirectionKey) ?? "") == .return ? .return : .outbound
    }

    func selectCommute(routeID: String?, direction: JourneyDirection) {
        if let routeID {
            defaults.set(routeID, forKey: Self.commuterRouteKey)
            defaults.set(direction.rawValue, forKey: Self.commuterDirectionKey)
        } else {
            defaults.removeObject(forKey: Self.commuterRouteKey)
            defaults.removeObject(forKey: Self.commuterDirectionKey)
        }
    }
}

@MainActor
final class WatchSharingPreferenceStore {
    static let storageKey = "it.binario.ios.watch-sharing-enabled.v1"

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
        case .failed: "watch.status.failed"
        }
    }
}
