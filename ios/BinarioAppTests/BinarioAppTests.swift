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
                scheduledDeparture: romeTime(now.addingTimeInterval(30 * 60)),
                expectedDeparture: romeTime(now.addingTimeInterval(30 * 60)),
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
        // A time picked at minute precision a moment ago, or a few minutes ahead.
        XCTAssertTrue(AppModel.isCurrent(try query(-4 * 60), now: now))
        XCTAssertTrue(AppModel.isCurrent(try query(10 * 60), now: now))
        // The planner now searches from the current time unless a time is picked: a query 20
        // minutes in the past is a question about the past, not the next trains.
        XCTAssertFalse(AppModel.isCurrent(try query(-20 * 60), now: now))
        XCTAssertFalse(AppModel.isCurrent(try query(2 * 60 * 60), now: now))
        XCTAssertFalse(AppModel.isCurrent(try query(3 * 24 * 60 * 60), now: now))
    }

    func testDepartedTrainsAreDroppedAndTheFollowingOnesListed() throws {
        // Opened at 08:00, searched at 08:25: the 08:05 and 08:20 trains have left.
        let opened = try rome(2026, 10, 6, 8, 0)
        let query = try JourneyQuery(
            origin: StationIdentity(id: "S08409", name: "Roma Termini"),
            destination: StationIdentity(id: "S01700", name: "Milano Centrale"),
            departure: opened
        )
        let journey = programmedJourney(query: query, generatedAt: opened, times: ["08:05", "08:20", "08:40", "09:10"])
        let now = try rome(2026, 10, 6, 8, 25)

        let upcoming = AppModel.upcomingDepartures(in: journey, now: now)

        XCTAssertEqual(upcoming.map(\.service.expectedDeparture), ["08:40", "09:10"])
        XCTAssertEqual(upcoming.map(\.departure), [try rome(2026, 10, 6, 8, 40), try rome(2026, 10, 6, 9, 10)])
        // The expiry follows the trains that are sent, not the ones that left.
        XCTAssertEqual(
            AppModel.watchExpiry(for: journey, departures: Array(upcoming.prefix(2)), now: now),
            try rome(2026, 10, 6, 9, 12)
        )
        XCTAssertTrue(AppModel.upcomingDepartures(in: journey, now: try rome(2026, 10, 6, 9, 11)).isEmpty)
        // Trains beyond the timetable window are never sent: the Watch places times within it.
        let bounded = JourneySnapshot(
            query: query,
            generatedAt: opened,
            expiresAt: try rome(2026, 10, 6, 8, 30),
            operationalState: journey.operationalState,
            provenance: journey.provenance,
            services: journey.services
        )
        XCTAssertEqual(AppModel.upcomingDepartures(in: bounded, now: opened).map(\.service.expectedDeparture), ["08:05", "08:20"])
    }

    func testOnlyTrainsStillToComeArePublishedToTheWatch() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        WatchSharingPreferenceStore(defaults: defaults).save(true)
        let session = FakeAppSession()
        let now = Date()
        let query = try JourneyQuery(
            origin: StationIdentity(id: "S08409", name: "Roma Termini"),
            destination: StationIdentity(id: "S01700", name: "Milano Centrale"),
            departure: now.addingTimeInterval(-4 * 60)
        )
        let journey = programmedJourney(query: query, generatedAt: now, times: [
            romeTime(now.addingTimeInterval(-2 * 60)),
            romeTime(now.addingTimeInterval(20 * 60)),
            romeTime(now.addingTimeInterval(40 * 60)),
        ])
        let model = makeModel(
            defaults: defaults,
            publisher: WatchSnapshotPublisher(session: session),
            repository: StubJourneyRepository(result: .available(journey))
        )
        session.completeActivation(.activated)

        await model.run(query: query)

        // The iPhone still lists every result; the Watch gets only what has not left.
        XCTAssertEqual(model.latestSnapshot?.services.count, 3)
        let object = try publishedObject(session)
        XCTAssertEqual(object["serviceCount"] as? Int, 2)
        XCTAssertEqual(object["service0Expected"] as? String, romeTime(now.addingTimeInterval(20 * 60)))
        XCTAssertEqual(object["service1Expected"] as? String, romeTime(now.addingTimeInterval(40 * 60)))
    }

    func testEnablingSharingPublishesTheSearchAlreadyOnScreen() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
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
        XCTAssertEqual(session.contexts, [[WatchSnapshotPublisher.clearContextKey: Data()]])

        model.setWatchSharingEnabled(true)

        XCTAssertEqual(try publishedObject(session)["service0Number"] as? String, "FR 1")
    }

    func testWatchRefreshRepeatsTheLastSearchForNowAndStopsAfterAnEdit() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        WatchSharingPreferenceStore(defaults: defaults).save(true)
        let session = FakeAppSession()
        let searchedAt = Date().addingTimeInterval(-3 * 60)
        let query = try JourneyQuery(
            origin: StationIdentity(id: "S08409", name: "Roma Termini"),
            destination: StationIdentity(id: "S01700", name: "Milano Centrale"),
            departure: searchedAt
        )
        let repository = RecordingJourneyRepository(result: .available(fixtureSnapshot(query: query, serviceNumber: "FR 2")))
        let model = makeModel(defaults: defaults, publisher: WatchSnapshotPublisher(session: session), repository: repository)
        session.completeActivation(.activated)
        await model.run(query: query)
        let published = session.contexts.count

        let refreshedAt = Date()
        await model.refreshWatchSnapshot(now: refreshedAt)

        let queries = await repository.queries
        XCTAssertEqual(queries.count, 2)
        XCTAssertEqual(queries.last?.departure, refreshedAt)
        XCTAssertEqual(queries.last?.origin, query.origin)
        XCTAssertEqual(queries.last?.destination, query.destination)
        XCTAssertEqual(session.contexts.count, published + 1)
        XCTAssertNotNil(session.contexts.last?[WatchSnapshotPublisher.contextKey])

        // Once the input changes, the Watch was revoked and a refresh must not bring it back.
        model.invalidateSearchResult()
        await model.refreshWatchSnapshot()
        let queriesAfterEdit = await repository.queries
        XCTAssertEqual(queriesAfterEdit.count, 2)
        XCTAssertEqual(session.contexts.last, [WatchSnapshotPublisher.clearContextKey: Data()])
    }

    func testPinnedRouteIsPersistedAndDroppedWithItsRoute() throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let model = makeModel(defaults: defaults, publisher: nil)
        model.save(query: try JourneyQuery(origin: roma, destination: napoli, departure: Date()))
        let route = try XCTUnwrap(model.savedRoutes.first)

        model.setWatchPin(route, pinned: true)

        XCTAssertEqual(model.pinnedWatchRouteID, route.id)
        XCTAssertEqual(model.pinnedWatchRoute, route)
        XCTAssertEqual(WatchSharingPreferenceStore(defaults: defaults).loadPinnedRouteID(), route.id)
        XCTAssertEqual(makeModel(defaults: defaults, publisher: nil).pinnedWatchRouteID, route.id, "kept across a relaunch")
        // Deleting the route removes its pin; a pin whose route is gone is dropped at launch.
        model.deleteAllRoutes()
        XCTAssertNil(model.pinnedWatchRouteID)
        XCTAssertNil(WatchSharingPreferenceStore(defaults: defaults).loadPinnedRouteID())
        WatchSharingPreferenceStore(defaults: defaults).savePinnedRouteID("S00001--S00002")
        XCTAssertNil(makeModel(defaults: defaults, publisher: nil).pinnedWatchRouteID)
        XCTAssertNil(WatchSharingPreferenceStore(defaults: defaults).loadPinnedRouteID())
    }

    func testPinnedRouteIsPublishedOnlyWithSharingOnAndYieldsToASearchForNow() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let session = FakeAppSession()
        let pinnedQuery = try JourneyQuery(origin: roma, destination: napoli, departure: Date())
        let searchQuery = try JourneyQuery(origin: roma, destination: milano, departure: Date())
        let repository = RoutingJourneyRepository(results: [
            napoli.id: .available(fixtureSnapshot(query: pinnedQuery, serviceNumber: "R 9")),
            milano.id: .available(fixtureSnapshot(query: searchQuery, serviceNumber: "FR 1")),
        ])
        let model = makeModel(defaults: defaults, publisher: WatchSnapshotPublisher(session: session), repository: repository)
        session.completeActivation(.activated)
        model.save(query: pinnedQuery)
        let route = try XCTUnwrap(model.savedRoutes.first)

        // Sharing off: the pin is kept, nothing but the initial revocation is sent.
        model.setWatchPin(route, pinned: true)
        await model.pinnedPublicationFinished()
        XCTAssertEqual(session.contexts, [[WatchSnapshotPublisher.clearContextKey: Data()]])

        model.setWatchSharingEnabled(true)
        await model.pinnedPublicationFinished()
        XCTAssertEqual(try publishedObject(session)["routeId"] as? String, route.id)
        XCTAssertEqual(try publishedObject(session)["service0Number"] as? String, "R 9")

        // A search for now owns the Watch; editing the planner gives it back to the pinned route.
        await model.run(query: searchQuery)
        XCTAssertEqual(try publishedObject(session)["service0Number"] as? String, "FR 1")
        await model.publishPinnedRoute()
        XCTAssertEqual(try publishedObject(session)["service0Number"] as? String, "FR 1", "the search for now wins")
        model.invalidateSearchResult()
        await model.pinnedPublicationFinished()
        XCTAssertEqual(try publishedObject(session)["service0Number"] as? String, "R 9")

        // Removing the pin clears the Watch it was shown on.
        model.setWatchPin(route, pinned: false)
        XCTAssertNil(model.pinnedWatchRouteID)
        XCTAssertEqual(session.contexts.last, [WatchSnapshotPublisher.clearContextKey: Data()])
    }

    func testWatchRefreshWithoutASearchForNowPublishesThePinnedRoute() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        WatchSharingPreferenceStore(defaults: defaults).save(true)
        let session = FakeAppSession()
        let pinnedQuery = try JourneyQuery(origin: roma, destination: napoli, departure: Date())
        let repository = RecordingJourneyRepository(result: .available(fixtureSnapshot(query: pinnedQuery, serviceNumber: "R 9")))
        let model = makeModel(defaults: defaults, publisher: WatchSnapshotPublisher(session: session), repository: repository)
        session.completeActivation(.activated)
        model.save(query: pinnedQuery)
        model.setWatchPin(try XCTUnwrap(model.savedRoutes.first), pinned: true)
        await model.pinnedPublicationFinished()
        let published = session.contexts.count

        // «Aggiorna» from the Watch with the iPhone app just launched in the background: no search
        // for now is in memory, so the pinned route is searched from now and sent.
        let refreshedAt = Date()
        await model.refreshWatchSnapshot(now: refreshedAt)

        let queries = await repository.queries
        XCTAssertEqual(queries.count, 2)
        XCTAssertEqual(queries.last?.departure, refreshedAt)
        XCTAssertEqual(queries.last?.destination, napoli)
        XCTAssertEqual(session.contexts.count, published + 1)
        XCTAssertEqual(try publishedObject(session)["service0Number"] as? String, "R 9")
    }

    func testWatchRouteLabelNamesTheStationsAndAddsTheIdOnlyForNamesakes() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        WatchSharingPreferenceStore(defaults: defaults).save(true)
        let session = FakeAppSession()
        let data = try Data(contentsOf: repositoryRoot
            .appendingPathComponent("app/src/main/assets/stations/curated_it_rail_stations.json"))
        let roma = StationIdentity(id: "S08409", name: "Roma Termini")
        let napoli = StationIdentity(id: "S09218", name: "Napoli Centrale")
        let acerra = StationIdentity(id: "S09215", name: "Acerra")
        let plain = try JourneyQuery(origin: roma, destination: napoli, departure: Date())
        let namesake = try JourneyQuery(origin: acerra, destination: napoli, departure: Date())

        for (query, expected) in [
            (plain, "Roma Termini → Napoli Centrale"),
            (namesake, "Acerra → Napoli Centrale · S09215--S09218"),
        ] {
            let model = makeModel(
                defaults: defaults,
                publisher: WatchSnapshotPublisher(session: session),
                repository: StubJourneyRepository(result: .available(fixtureSnapshot(query: query, serviceNumber: "R 1")))
            )
            await model.loadCatalog { data }
            session.completeActivation(.activated)
            await model.run(query: query)
            XCTAssertEqual(try publishedObject(session)["routeLabel"] as? String, expected)
        }
    }

    func testSavedRouteOpensThePlannerWithItsStations() throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let model = makeModel(defaults: defaults, publisher: nil)
        let route = try fixtureRoute()
        model.selectedTab = .saved

        model.openPlanner(for: route)

        XCTAssertEqual(model.selectedTab, .planner)
        let request = try XCTUnwrap(model.takePlannerRequest())
        XCTAssertEqual(request.origin, route.origin)
        XCTAssertEqual(request.destination, route.destination)
        XCTAssertNil(model.plannerRequest)
        XCTAssertNil(model.takePlannerRequest())
        // Without the catalogue the saved identity is enough to search.
        XCTAssertEqual(StationIdentity(model.station(for: route.origin)), route.origin)
    }

    func testTheStarSavesATrainOnThisIPhoneRemovesItAndRefusesOneTooMany() throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let model = AppModel(
            catalogState: .loading,
            savedRouteStore: SavedRouteStore(defaults: defaults),
            favouriteTrainStore: FavouriteTrainStore(defaults: defaults),
            watchSharingStore: WatchSharingPreferenceStore(defaults: defaults),
            repository: FailClosedJourneyRepository(),
            watchPublisher: nil,
            versionDisplay: "test"
        )
        let query = try JourneyQuery(origin: milano, destination: roma, departure: Date())
        let service = fixtureSnapshot(query: query, serviceNumber: "2153").services[0]
        XCTAssertEqual(model.favouriteTrains, [])
        XCTAssertTrue(model.canToggleFavourite(service, query: query))
        XCTAssertFalse(model.isFavourite(service, query: query))

        XCTAssertEqual(model.toggleFavourite(service, query: query), .added)
        XCTAssertTrue(model.isFavourite(service, query: query))
        XCTAssertEqual(FavouriteTrainStore(defaults: defaults).load().trains.map(\.number), ["2153"])
        // The same number boarded at another station is another train.
        let elsewhere = try JourneyQuery(origin: napoli, destination: roma, departure: Date())
        XCTAssertFalse(model.isFavourite(service, query: elsewhere))
        // Without the statistics «Affidabilità dei miei treni» is hidden.
        XCTAssertNil(model.favouriteTrainsReliability)

        XCTAssertEqual(model.toggleFavourite(service, query: query), .removed)
        XCTAssertEqual(model.favouriteTrains, [])
        XCTAssertEqual(FavouriteTrainStore(defaults: defaults).load().trains, [])

        // A line name is not a train number: no star.
        let line = fixtureSnapshot(query: query, serviceNumber: "Linea SASSARI-SORSO").services[0]
        XCTAssertFalse(model.canToggleFavourite(line, query: query))
        XCTAssertEqual(model.toggleFavourite(line, query: query), .failed)

        // Twelve trains: the thirteenth is refused, none is dropped, and removing stays possible.
        for number in 1...FavouriteTrainCollection.maximumTrains {
            let train = fixtureSnapshot(query: query, serviceNumber: "\(2_100 + number)").services[0]
            XCTAssertEqual(model.toggleFavourite(train, query: query), .added)
        }
        let thirteenth = fixtureSnapshot(query: query, serviceNumber: "2199").services[0]
        XCTAssertEqual(model.toggleFavourite(thirteenth, query: query), .limitReached)
        XCTAssertEqual(model.favouriteTrains.count, 12)
        XCTAssertEqual(model.favouriteTrains.first?.number, "2112")
        XCTAssertEqual(model.favouriteTrains.last?.number, "2101")
        XCTAssertTrue(model.removeFavourites(ids: [model.favouriteTrains[0].id]))
        XCTAssertEqual(model.toggleFavourite(thirteenth, query: query), .added)
        XCTAssertTrue(model.removeFavourites(ids: Set(model.favouriteTrains.map(\.id))))
        XCTAssertEqual(FavouriteTrainStore(defaults: defaults).load().trains, [])
    }

    func testPlannerCaptionAppearsOnlyWhenTheDeviceClockDiffersFromTimetables() throws {
        let summer = try rome(2026, 7, 1, 9, 0)
        let winter = try rome(2026, 12, 1, 9, 0)
        let london = try XCTUnwrap(TimeZone(identifier: "Europe/London"))

        XCTAssertTrue(AppModel.needsTimetableZoneCaption(at: summer, deviceTimeZone: london))
        XCTAssertTrue(AppModel.needsTimetableZoneCaption(at: winter, deviceTimeZone: london))
        XCTAssertFalse(AppModel.needsTimetableZoneCaption(at: summer, deviceTimeZone: try XCTUnwrap(TimeZone(identifier: "Europe/Rome"))))
        XCTAssertFalse(AppModel.needsTimetableZoneCaption(at: winter, deviceTimeZone: try XCTUnwrap(TimeZone(identifier: "Europe/Berlin"))))
        XCTAssertEqual(AppModel.timetableTimeZone.identifier, "Europe/Rome")
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
        let journey = programmedJourney(query: query, generatedAt: departure, times: ["23:50", "00:10"])

        let departures = AppModel.upcomingDepartures(in: journey, now: departure)
        let expiry = AppModel.watchExpiry(for: journey, departures: departures, now: departure)

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

    private var roma: StationIdentity { StationIdentity(id: "S08409", name: "Roma Termini") }
    private var napoli: StationIdentity { StationIdentity(id: "S09218", name: "Napoli Centrale") }
    private var milano: StationIdentity { StationIdentity(id: "S01700", name: "Milano Centrale") }

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

    /// A programmed result whose trains depart at `times` ("HH:mm", Rome), valid 12 hours.
    private func programmedJourney(query: JourneyQuery, generatedAt: Date, times: [String]) -> JourneySnapshot {
        let fixture = fixtureSnapshot(query: query, serviceNumber: "FR 1")
        return JourneySnapshot(
            query: query,
            generatedAt: generatedAt,
            expiresAt: generatedAt.addingTimeInterval(12 * 60 * 60),
            operationalState: fixture.operationalState,
            provenance: fixture.provenance,
            services: times.map { fixture.services[0].withDeparture($0) }
        )
    }

    private func publishedObject(_ session: FakeAppSession) throws -> [String: Any] {
        let payload = try XCTUnwrap(session.contexts.last?[WatchSnapshotPublisher.contextKey])
        return try XCTUnwrap(JSONSerialization.jsonObject(with: payload) as? [String: Any])
    }

    private func romeTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Europe/Rome")
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }

    private func rome(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int) throws -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "Europe/Rome"))
        return try XCTUnwrap(calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute)))
    }

    /// A programmed result with one train half an hour from now, so the Watch receives it.
    private func fixtureSnapshot(query: JourneyQuery, serviceNumber: String) -> JourneySnapshot {
        let now = Date()
        let departure = romeTime(now.addingTimeInterval(30 * 60))
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
                scheduledDeparture: departure,
                expectedDeparture: departure,
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
        defaults.removeObject(forKey: FavouriteTrainStore.storageKey)
        defaults.removeObject(forKey: WatchSharingPreferenceStore.storageKey)
        defaults.removeObject(forKey: WatchSharingPreferenceStore.pinnedRouteStorageKey)
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

/// A result per destination station.
private struct RoutingJourneyRepository: JourneyRepository {
    let results: [String: JourneyResult]
    func journeys(for query: JourneyQuery) async -> JourneyResult {
        results[query.destination.id] ?? .unavailable(.noJourney)
    }
}

private actor RecordingJourneyRepository: JourneyRepository {
    private(set) var queries: [JourneyQuery] = []
    private let result: JourneyResult

    init(result: JourneyResult) {
        self.result = result
    }

    func journeys(for query: JourneyQuery) async -> JourneyResult {
        queries.append(query)
        return result
    }
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
    var watchStateHandler: (@MainActor () -> Void)?
    var refreshRequestHandler: (@MainActor () -> Void)?
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
