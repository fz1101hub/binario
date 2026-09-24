import Foundation
import XCTest
@testable import Binario
import BinarioKit

@MainActor
final class BinarioAppTests: XCTestCase {
    func testSavedRoutesRemainInInjectedLocalUserDefaults() throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let store = SavedRouteStore(defaults: defaults)
        let route = try fixtureRoute()

        try store.save([route])

        XCTAssertEqual(store.load(), [route])
        XCTAssertNotNil(defaults.data(forKey: SavedRouteStore.storageKey))
    }

    func testMalformedAndCorruptValidJSONStorageFailClosedToEmpty() throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let invalidPayloads = [
            Data("not-json".utf8),
            encodedRouteArray(id: "", originId: "S08409", destinationId: "S09218", name: "Roma Napoli"),
            encodedRouteArray(id: "route", originId: "S08409", destinationId: "S08409", name: "Roma Roma"),
            encodedRouteArray(id: "route", originId: "S08409", destinationId: "S09218", name: "Roma  Napoli"),
            encodedRouteArray(id: String(repeating: "x", count: 129), originId: "S08409", destinationId: "S09218", name: "Roma Napoli"),
            encodedDuplicateRouteArray(),
        ]

        for payload in invalidPayloads {
            defaults.set(payload, forKey: SavedRouteStore.storageKey)
            XCTAssertEqual(SavedRouteStore(defaults: defaults).load(), [])
        }
    }

    func testWatchSharingDefaultsOffPersistsAcrossRelaunchAndRevokesOnDisable() throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let session = FakeAppSession()
        let publisher = WatchSnapshotPublisher(session: session)
        var model = makeModel(defaults: defaults, publisher: publisher)

        XCTAssertFalse(model.watchSharingEnabled)
        session.completeActivation(.activated)
        XCTAssertEqual(session.contexts.last, [WatchSnapshotPublisher.clearContextKey: Data()])

        model.setWatchSharingEnabled(true)
        XCTAssertTrue(model.watchSharingEnabled)
        model = makeModel(defaults: defaults, publisher: WatchSnapshotPublisher(session: FakeAppSession()))
        XCTAssertTrue(model.watchSharingEnabled)

        model.setWatchSharingEnabled(false)
        XCTAssertFalse(model.watchSharingEnabled)
        let relaunched = makeModel(defaults: defaults, publisher: WatchSnapshotPublisher(session: FakeAppSession()))
        XCTAssertFalse(relaunched.watchSharingEnabled)
    }

    func testDisabledSharingReplacesPendingRouteWithClearSignal() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let session = FakeAppSession()
        let publisher = WatchSnapshotPublisher(session: session)
        let model = makeModel(defaults: defaults, publisher: publisher)
        model.setWatchSharingEnabled(true)
        let query = try JourneyQuery(
            origin: StationIdentity(id: "S08409", name: "Roma Termini"),
            destination: StationIdentity(id: "S09218", name: "Napoli Centrale"),
            departure: Date()
        )

        await model.run(query: query)
        model.setWatchSharingEnabled(false)
        session.completeActivation(.activated)

        XCTAssertEqual(session.contexts, [[WatchSnapshotPublisher.clearContextKey: Data()]])
    }

    func testCatalogStateIsLoadingThenAvailableOrUnavailable() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let model = makeModel(defaults: defaults, publisher: nil)
        XCTAssertEqual(model.catalogState, .loading)

        let data = try Data(contentsOf: repositoryRoot
            .appendingPathComponent("app/src/main/assets/stations/curated_it_rail_stations.json"))
        await model.loadCatalog { data }
        guard case let .available(catalog) = model.catalogState else {
            return XCTFail("Expected catalog")
        }
        XCTAssertEqual(catalog.stationCount, 5_239)

        await model.loadCatalog { Data("{}".utf8) }
        XCTAssertEqual(model.catalogState, .unavailable)
    }

    func testVersionFormatterReadsBundleValuesAndIsInjectable() throws {
        let formatter = StubVersionFormatter(value: "9.8.7 (654)")
        XCTAssertEqual(formatter.displayVersion(bundle: .main), "9.8.7 (654)")

        let bundleURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("BinarioVersionTests-\(UUID().uuidString).bundle")
        try FileManager.default.createDirectory(at: bundleURL, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: bundleURL) }
        let info: [String: Any] = [
            "CFBundleIdentifier": "it.binario.version-tests",
            "CFBundleShortVersionString": "1.2.3",
            "CFBundleVersion": "45",
        ]
        try (info as NSDictionary).write(to: bundleURL.appendingPathComponent("Info.plist"))
        let bundle = try XCTUnwrap(Bundle(url: bundleURL))

        XCTAssertEqual(BundleAppVersionFormatter().displayVersion(bundle: bundle), "1.2.3 (45)")
    }

    private func makeModel(
        defaults: UserDefaults,
        publisher: WatchSnapshotPublisher?
    ) -> AppModel {
        AppModel(
            catalogState: .loading,
            savedRouteStore: SavedRouteStore(defaults: defaults),
            watchSharingStore: WatchSharingPreferenceStore(defaults: defaults),
            repository: FailClosedJourneyRepository(),
            watchPublisher: publisher,
            versionDisplay: "test"
        )
    }

    private func fixtureRoute() throws -> SavedRoute {
        let origin = StationIdentity(id: "S08409", name: "Roma Termini")
        let destination = StationIdentity(id: "S09218", name: "Napoli Centrale")
        return try SavedRoute(
            id: SavedRoute.routeIdentifier(origin: origin, destination: destination),
            name: "Roma Termini → Napoli Centrale",
            origin: origin,
            destination: destination
        )
    }

    private func makeDefaults() throws -> UserDefaults {
        try XCTUnwrap(UserDefaults(suiteName: "it.binario.ios.tests.\(UUID().uuidString)"))
    }

    private func clear(_ defaults: UserDefaults) {
        if let suite = defaults.volatileDomainNames.first(where: { $0.hasPrefix("it.binario.ios.tests.") }) {
            defaults.removePersistentDomain(forName: suite)
        }
        defaults.removeObject(forKey: SavedRouteStore.storageKey)
        defaults.removeObject(forKey: WatchSharingPreferenceStore.storageKey)
    }

    private func encodedRouteArray(
        id: String,
        originId: String,
        destinationId: String,
        name: String
    ) -> Data {
        try! JSONSerialization.data(withJSONObject: [[
            "id": id,
            "name": name,
            "origin": ["id": originId, "name": "Origin"],
            "destination": ["id": destinationId, "name": "Destination"],
            "createdAt": 1_000_000,
        ]])
    }

    private func encodedDuplicateRouteArray() -> Data {
        let route: [String: Any] = [
            "id": "duplicate",
            "name": "Origin Destination",
            "origin": ["id": "origin", "name": "Origin"],
            "destination": ["id": "destination", "name": "Destination"],
            "createdAt": 1_000_000,
        ]
        return try! JSONSerialization.data(withJSONObject: [route, route])
    }

    private var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}

private struct StubVersionFormatter: AppVersionFormatting {
    let value: String
    func displayVersion(bundle: Bundle) -> String { value }
}

@MainActor
private final class FakeAppSession: WatchSnapshotSession {
    var isSupported = true
    private(set) var activationState: WatchSessionActivationState = .notActivated
    var activationHandler: (@MainActor (WatchSessionActivationState) -> Void)?
    var contexts: [[String: Data]] = []

    func activate() {}
    func updateApplicationContext(_ context: [String: Data]) throws { contexts.append(context) }

    func completeActivation(_ state: WatchSessionActivationState) {
        activationState = state
        activationHandler?(state)
    }
}
