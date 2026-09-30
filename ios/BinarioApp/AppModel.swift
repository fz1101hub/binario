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
    @Published var persistenceMessageKey: String?

    private(set) var repository: any JourneyRepository
    private var searchRequestID = 0
    let versionDisplay: String

    private let savedRouteStore: SavedRouteStore
    private let watchSharingStore: WatchSharingPreferenceStore
    private let watchPublisher: WatchSnapshotPublisher?

    init(
        catalogState: CatalogState,
        savedRouteStore: SavedRouteStore,
        watchSharingStore: WatchSharingPreferenceStore,
        repository: any JourneyRepository,
        scheduleState: ScheduleState = .available,
        watchPublisher: WatchSnapshotPublisher?,
        versionDisplay: String
    ) {
        self.catalogState = catalogState
        self.savedRouteStore = savedRouteStore
        self.watchSharingStore = watchSharingStore
        self.repository = repository
        self.scheduleState = scheduleState
        self.latestSnapshot = nil
        self.watchPublisher = watchPublisher
        self.versionDisplay = versionDisplay
        self.savedRoutes = savedRouteStore.load()
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

        let model = AppModel(
            catalogState: .loading,
            savedRouteStore: SavedRouteStore(),
            watchSharingStore: WatchSharingPreferenceStore(),
            repository: FailClosedJourneyRepository(),
            scheduleState: .loading,
            watchPublisher: publisher,
            versionDisplay: versionFormatter.displayVersion(bundle: bundle)
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
        scheduleState = .loading
        let installedURL = await Task.detached(priority: .utility) {
            try? BundledGTFSScheduleInstaller().install(
                compressedURL: compressedURL,
                metadataURL: metadataURL,
                cacheDirectory: cacheDirectory
            )
        }.value
        guard let installedURL else {
            repository = FailClosedJourneyRepository()
            scheduleState = .unavailable
            return
        }
        repository = SQLiteGTFSJourneyRepository(databaseURL: installedURL)
        scheduleState = .available
    }

    func run(query: JourneyQuery) async {
        searchRequestID &+= 1
        let requestID = searchRequestID
        let result = await repository.journeys(for: query)
        guard requestID == searchRequestID else { return }
        switch result {
        case let .available(snapshot):
            searchState = .idle
            latestSnapshot = snapshot
            publishAvailableSnapshot(snapshot)
        case let .unavailable(reason):
            searchState = .unavailable(reason)
            latestSnapshot = nil
            if reason == .noAuthorizedProvider || reason == .providerInvalid {
                scheduleState = .unavailable
            }
            publishUnavailableSnapshot(for: query)
        }
    }

    func invalidateSearchResult() {
        searchRequestID &+= 1
        searchState = .idle
        latestSnapshot = nil
        if watchSharingEnabled {
            revokeWatchSnapshot()
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
        guard watchSharingEnabled, let watchPublisher else { return }
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
        guard watchSharingEnabled, let watchPublisher else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1_000)
        let generatedAt = Int64(journey.generatedAt.timeIntervalSince1970 * 1_000)
        let expiresAt = Int64(journey.expiresAt.timeIntervalSince1970 * 1_000)
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
            services: journey.services.prefix(WearSnapshotV1.maximumServices).map { service in
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
