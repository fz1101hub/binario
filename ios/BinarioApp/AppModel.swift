import Foundation
import BinarioKit

@MainActor
final class AppModel: ObservableObject {
    typealias CatalogState = StationCatalogLoadState

    enum SearchState: Equatable {
        case idle
        case unavailable
    }

    @Published private(set) var catalogState: CatalogState
    @Published private(set) var savedRoutes: [SavedRoute]
    @Published private(set) var watchSharingEnabled: Bool
    @Published fileprivate(set) var watchSyncStatus: WatchSyncStatus
    @Published var searchState: SearchState = .idle
    @Published var persistenceMessageKey: String?

    let repository: any JourneyRepository
    let versionDisplay: String

    private let savedRouteStore: SavedRouteStore
    private let watchSharingStore: WatchSharingPreferenceStore
    private let watchPublisher: WatchSnapshotPublisher?

    init(
        catalogState: CatalogState,
        savedRouteStore: SavedRouteStore,
        watchSharingStore: WatchSharingPreferenceStore,
        repository: any JourneyRepository,
        watchPublisher: WatchSnapshotPublisher?,
        versionDisplay: String
    ) {
        self.catalogState = catalogState
        self.savedRouteStore = savedRouteStore
        self.watchSharingStore = watchSharingStore
        self.repository = repository
        self.watchPublisher = watchPublisher
        self.versionDisplay = versionDisplay
        self.savedRoutes = savedRouteStore.load()
        self.watchSharingEnabled = watchSharingStore.load()
        self.watchSyncStatus = watchSharingEnabled ? .activating : .disabled

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
            watchPublisher: publisher,
            versionDisplay: versionFormatter.displayVersion(bundle: bundle)
        )

        guard let url = bundle.url(forResource: "curated_it_rail_stations", withExtension: "json") else {
            model.catalogState = .unavailable
            return model
        }
        Task {
            await model.loadCatalog { try Data(contentsOf: url) }
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

    func run(query: JourneyQuery) async {
        let result = await repository.journeys(for: query)
        switch result {
        case .available:
            searchState = .idle
        case .unavailable:
            searchState = .unavailable
            publishUnavailableSnapshot(for: query)
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
