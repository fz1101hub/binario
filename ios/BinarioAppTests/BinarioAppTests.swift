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

    func testUnpairedIPhoneReadsOffWhenSharingIsOffAndNotPairedWhenOn() throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let session = FakeAppSession()
        session.isPaired = false
        let model = makeModel(defaults: defaults, publisher: WatchSnapshotPublisher(session: session))
        session.completeActivation(.activated)

        // No Watch: the clear is held back and the row reads as off, never as a failure.
        XCTAssertTrue(session.contexts.isEmpty)
        XCTAssertEqual(model.watchSyncStatus, .notPaired)
        XCTAssertEqual(model.displayedWatchSyncStatus, .disabled)

        model.setWatchSharingEnabled(true)
        XCTAssertEqual(model.displayedWatchSyncStatus, .notPaired)
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

    func testProgrammedResultIsRenderedInStateAndPublishedToWatchWhenEnabled() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        WatchSharingPreferenceStore(defaults: defaults).save(true)
        let session = FakeAppSession()
        let publisher = WatchSnapshotPublisher(session: session)
        let query = try JourneyQuery(
            origin: StationIdentity(id: "S08409", name: "Roma Termini"),
            destination: StationIdentity(id: "S01700", name: "Milano Centrale"),
            departure: Date()
        )
        let now = Date()
        let snapshot = JourneySnapshot(
            query: query,
            generatedAt: now,
            expiresAt: now.addingTimeInterval(3_600),
            operationalState: .programmed,
            provenance: DataProvenance(
                producer: .iosApp,
                nature: .programmed,
                sourceLabel: "Official GTFS",
                observedAt: now.addingTimeInterval(-86_400),
                freshness: .unknown,
                confidence: .staticOnly
            ),
            services: [TrainService(
                id: "service-1",
                number: "FR 1",
                operatorName: "Trenitalia",
                category: "Frecciarossa",
                destination: "Milano Centrale",
                scheduledDeparture: "10:00",
                expectedDeparture: "10:00",
                status: "PROGRAMMED",
                platform: nil,
                delayMinutes: 0
            )]
        )
        let model = makeModel(
            defaults: defaults,
            publisher: publisher,
            repository: StubJourneyRepository(result: .available(snapshot))
        )

        await model.run(query: query)
        XCTAssertEqual(model.latestSnapshot, snapshot)
        XCTAssertEqual(model.searchState, .idle)
        session.completeActivation(.activated)

        let payload = try XCTUnwrap(session.contexts.last?[WatchSnapshotPublisher.contextKey])
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: payload) as? [String: Any])
        XCTAssertEqual(object["provenanceDataNature"] as? String, "PROGRAMMED")
        XCTAssertEqual(object["operationalState"] as? String, "PROGRAMMED")
        XCTAssertEqual(object["serviceCount"] as? Int, 1)
        XCTAssertEqual(object["service0Number"] as? String, "FR 1")
    }

    func testInputInvalidationClearsPreviouslyPublishedWatchSnapshot() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        WatchSharingPreferenceStore(defaults: defaults).save(true)
        let session = FakeAppSession()
        let query = try JourneyQuery(
            origin: StationIdentity(id: "S08409", name: "Roma Termini"),
            destination: StationIdentity(id: "S01700", name: "Milano Centrale"),
            departure: Date()
        )
        let model = makeModel(
            defaults: defaults,
            publisher: WatchSnapshotPublisher(session: session),
            repository: StubJourneyRepository(result: .available(fixtureSnapshot(query: query, serviceNumber: "FR 1")))
        )
        session.completeActivation(.activated)
        await model.run(query: query)
        XCTAssertNotNil(session.contexts.last?[WatchSnapshotPublisher.contextKey])

        model.invalidateSearchResult()

        XCTAssertEqual(session.contexts.last, [WatchSnapshotPublisher.clearContextKey: Data()])
        XCTAssertNil(model.latestSnapshot)
    }

    func testOlderSearchResponseCannotReplaceNewerResult() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let repository = ControlledJourneyRepository()
        let model = makeModel(defaults: defaults, publisher: nil, repository: repository)
        let origin = StationIdentity(id: "S08409", name: "Roma Termini")
        let olderQuery = try JourneyQuery(
            origin: origin,
            destination: StationIdentity(id: "S09218", name: "Napoli Centrale"),
            departure: Date()
        )
        let newerQuery = try JourneyQuery(
            origin: origin,
            destination: StationIdentity(id: "S01700", name: "Milano Centrale"),
            departure: Date()
        )
        let olderSnapshot = fixtureSnapshot(query: olderQuery, serviceNumber: "OLD")
        let newerSnapshot = fixtureSnapshot(query: newerQuery, serviceNumber: "NEW")

        let olderTask = Task { await model.run(query: olderQuery) }
        await repository.waitUntilPending(queryID: olderQuery.destination.id)
        let newerTask = Task { await model.run(query: newerQuery) }
        await repository.waitUntilPending(queryID: newerQuery.destination.id)
        await repository.complete(queryID: newerQuery.destination.id, with: .available(newerSnapshot))
        await newerTask.value
        await repository.complete(queryID: olderQuery.destination.id, with: .available(olderSnapshot))
        await olderTask.value

        XCTAssertEqual(model.latestSnapshot, newerSnapshot)
    }

    func testInputInvalidationDiscardsPendingSearchResponse() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let repository = ControlledJourneyRepository()
        let model = makeModel(defaults: defaults, publisher: nil, repository: repository)
        let query = try JourneyQuery(
            origin: StationIdentity(id: "S08409", name: "Roma Termini"),
            destination: StationIdentity(id: "S01700", name: "Milano Centrale"),
            departure: Date()
        )
        let task = Task { await model.run(query: query) }
        await repository.waitUntilPending(queryID: query.destination.id)

        model.invalidateSearchResult()
        await repository.complete(queryID: query.destination.id, with: .available(fixtureSnapshot(query: query, serviceNumber: "STALE")))
        await task.value

        XCTAssertNil(model.latestSnapshot)
        XCTAssertEqual(model.searchState, .idle)
    }

    func testNoJourneyAndInvalidProviderHaveDistinctState() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let query = try JourneyQuery(
            origin: StationIdentity(id: "S08409", name: "Roma Termini"),
            destination: StationIdentity(id: "S01700", name: "Milano Centrale"),
            departure: Date()
        )
        let noJourney = makeModel(
            defaults: defaults,
            publisher: nil,
            repository: StubJourneyRepository(result: .unavailable(.noJourney))
        )
        await noJourney.run(query: query)
        XCTAssertEqual(noJourney.searchState, .unavailable(.noJourney))
        XCTAssertEqual(noJourney.scheduleState, .available)

        let invalidProvider = makeModel(
            defaults: defaults,
            publisher: nil,
            repository: StubJourneyRepository(result: .unavailable(.providerInvalid))
        )
        await invalidProvider.run(query: query)
        XCTAssertEqual(invalidProvider.searchState, .unavailable(.providerInvalid))
        XCTAssertEqual(invalidProvider.scheduleState, .unavailable)
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

    func testStationsWithoutTimetableAreMarkedAndListedAfterCoveredOnes() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let data = try Data(contentsOf: repositoryRoot
            .appendingPathComponent("app/src/main/assets/stations/curated_it_rail_stations.json"))
        let unmarked = makeModel(defaults: defaults, publisher: nil)
        await unmarked.loadCatalog { data }
        let covered = AppModel(
            catalogState: .loading,
            savedRouteStore: SavedRouteStore(defaults: defaults),
            watchSharingStore: WatchSharingPreferenceStore(defaults: defaults),
            repository: FailClosedJourneyRepository(),
            coveredStationIDs: ["NC000040"],
            watchPublisher: nil,
            versionDisplay: "test"
        )
        await covered.loadCatalog { data }

        let plain = unmarked.suggestions(for: "Bergamo")
        let ranked = covered.suggestions(for: "Bergamo")

        // Without an index nothing is marked and the catalogue order stands.
        XCTAssertEqual(plain.first?.name, "Bergamo")
        XCTAssertNil(unmarked.hasTimetable(try XCTUnwrap(plain.first)))
        // With an index the served station leads and the others are flagged, not hidden.
        XCTAssertEqual(ranked.first?.id, "NC000040")
        XCTAssertEqual(ranked.map(\.id).sorted(), plain.map(\.id).sorted())
        XCTAssertEqual(covered.hasTimetable(try XCTUnwrap(ranked.first)), true)
        XCTAssertEqual(covered.hasTimetable(try XCTUnwrap(ranked.first { $0.name == "Bergamo" })), false)
    }

    func testOnlySearchesForNowAreCurrentForTheWatch() throws {
        let now = Date(timeIntervalSince1970: 1_790_000_000)
        func query(_ offset: TimeInterval) throws -> JourneyQuery {
            try JourneyQuery(
                origin: StationIdentity(id: "S08409", name: "Roma Termini"),
                destination: StationIdentity(id: "S01700", name: "Milano Centrale"),
                departure: now.addingTimeInterval(offset)
            )
        }

        XCTAssertTrue(AppModel.isCurrent(try query(0), now: now))
        XCTAssertTrue(AppModel.isCurrent(try query(-20 * 60), now: now))
        XCTAssertFalse(AppModel.isCurrent(try query(2 * 60 * 60), now: now))
        XCTAssertFalse(AppModel.isCurrent(try query(3 * 24 * 60 * 60), now: now))
    }

    func testWatchSnapshotExpiresWithTheLastListedTrain() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "Europe/Rome"))
        let departure = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 10, day: 6, hour: 23, minute: 40)))
        let query = try JourneyQuery(
            origin: StationIdentity(id: "S08409", name: "Roma Termini"),
            destination: StationIdentity(id: "S01700", name: "Milano Centrale"),
            departure: departure
        )
        let fixture = fixtureSnapshot(query: query, serviceNumber: "FR 1")
        let services = [
            fixture.services[0].withDeparture("23:50"),
            fixture.services[0].withDeparture("00:10")
        ]
        let journey = JourneySnapshot(
            query: query,
            generatedAt: departure,
            expiresAt: departure.addingTimeInterval(12 * 60 * 60),
            operationalState: fixture.operationalState,
            provenance: fixture.provenance,
            services: services
        )

        let expiry = AppModel.watchExpiry(for: journey, services: services, now: departure)

        // 00:10 is the next day; the snapshot lasts two minutes past that departure, not 12 hours.
        let lastDeparture = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 10, day: 7, hour: 0, minute: 10)))
        XCTAssertEqual(expiry, lastDeparture.addingTimeInterval(2 * 60))
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
        XCTAssertTrue((info as NSDictionary).write(
            to: bundleURL.appendingPathComponent("Info.plist"),
            atomically: true
        ))
        let bundle = try XCTUnwrap(Bundle(url: bundleURL))

        XCTAssertEqual(BundleAppVersionFormatter().displayVersion(bundle: bundle), "1.2.3 (45)")
    }

    private func makeModel(
        defaults: UserDefaults,
        publisher: WatchSnapshotPublisher?,
        repository: any JourneyRepository = FailClosedJourneyRepository()
    ) -> AppModel {
        AppModel(
            catalogState: .loading,
            savedRouteStore: SavedRouteStore(defaults: defaults),
            watchSharingStore: WatchSharingPreferenceStore(defaults: defaults),
            repository: repository,
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
            destination: destination,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
    }

    private func fixtureSnapshot(query: JourneyQuery, serviceNumber: String) -> JourneySnapshot {
        let now = Date()
        return JourneySnapshot(
            query: query,
            generatedAt: now,
            expiresAt: now.addingTimeInterval(3_600),
            operationalState: .programmed,
            provenance: DataProvenance(
                producer: .iosApp,
                nature: .programmed,
                sourceLabel: "Official GTFS",
                observedAt: now.addingTimeInterval(-86_400),
                freshness: .unknown,
                confidence: .staticOnly
            ),
            services: [TrainService(
                id: serviceNumber,
                number: serviceNumber,
                operatorName: "Operator",
                category: "Rail",
                destination: query.destination.name,
                scheduledDeparture: "10:00",
                expectedDeparture: "10:00",
                status: "PROGRAMMED",
                platform: nil,
                delayMinutes: 0
            )]
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

private struct StubJourneyRepository: JourneyRepository {
    let result: JourneyResult
    func journeys(for query: JourneyQuery) async -> JourneyResult { result }
}

private actor ControlledJourneyRepository: JourneyRepository {
    private var pending: [String: CheckedContinuation<JourneyResult, Never>] = [:]

    func journeys(for query: JourneyQuery) async -> JourneyResult {
        await withCheckedContinuation { continuation in
            pending[query.destination.id] = continuation
        }
    }

    func waitUntilPending(queryID: String) async {
        while pending[queryID] == nil {
            await Task.yield()
        }
    }

    func complete(queryID: String, with result: JourneyResult) {
        pending.removeValue(forKey: queryID)?.resume(returning: result)
    }
}

@MainActor
private final class FakeAppSession: WatchSnapshotSession {
    var isSupported = true
    var isPaired = true
    var isWatchAppInstalled = true
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

private extension TrainService {
    func withDeparture(_ time: String) -> TrainService {
        TrainService(
            id: id + time,
            number: number,
            operatorName: operatorName,
            category: category,
            destination: destination,
            scheduledDeparture: time,
            expectedDeparture: time,
            status: status,
            platform: platform,
            delayMinutes: delayMinutes
        )
    }
}
