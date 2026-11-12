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

    func testTheAgendaIsChangedThroughTheModelAndARefusalLeavesItAsItWas() throws {
        let defaults = try makeDefaults()
        defer {
            defaults.removeObject(forKey: PlannedJourneyStore.storageKey)
            clear(defaults)
        }
        let model = AppModel(
            catalogState: .loading,
            savedRouteStore: SavedRouteStore(defaults: defaults),
            plannedJourneyStore: PlannedJourneyStore(defaults: defaults),
            watchSharingStore: WatchSharingPreferenceStore(defaults: defaults),
            repository: FailClosedJourneyRepository(),
            watchPublisher: nil,
            versionDisplay: "test"
        )
        XCTAssertEqual(model.agenda, .empty)
        // The Home-screen quick action «Agenda» opens «Tratte» on its Agenda page.
        XCTAssertFalse(model.showingAgenda)
        model.openAgenda()
        XCTAssertEqual(model.selectedTab, .saved)
        XCTAssertTrue(model.showingAgenda)
        let form = PlannedJourneyForm(
            origin: StationIdentity(id: "S08409", name: "Roma Termini"),
            destination: StationIdentity(id: "S09218", name: "Napoli Centrale"),
            date: PlannedLocalDate(iso: "2030-05-02"),
            time: PlannedLocalTime(hhmm: "08:15")
        )
        let journey = try form.journey(id: "j-1", now: Date(), editing: nil).get()
        XCTAssertNil(model.saveJourney(journey, isNew: true))
        XCTAssertEqual(model.agenda.journeys, [journey])
        let copy = try form.journey(id: "j-2", now: Date(), editing: nil).get()
        XCTAssertEqual(model.saveJourney(copy, isNew: true), .duplicate)
        XCTAssertEqual(AgendaText.messageKey(PlannedJourneyRefusal.duplicate), "saved.agenda.duplicate")
        XCTAssertEqual(model.agenda.journeys, [journey])

        XCTAssertNil(model.completeJourney(id: "j-1"))
        XCTAssertEqual(model.agenda.journeys.first?.status, .completed)
        XCTAssertNil(model.deleteJourney(id: "j-1"))
        XCTAssertEqual(model.agenda.journeys, [])

        // A stored value this version cannot read is never written over.
        defaults.set(Data("not json".utf8), forKey: PlannedJourneyStore.storageKey)
        model.reloadAgenda()
        XCTAssertTrue(model.agenda.isReadOnly)
        XCTAssertEqual(model.saveJourney(journey, isNew: true), .readOnly)
        XCTAssertEqual(AgendaText.messageKey(PlannedJourneyRefusal.readOnly), "saved.agenda.read_only")
        XCTAssertEqual(defaults.data(forKey: PlannedJourneyStore.storageKey), Data("not json".utf8))
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
        // One failed read is the search's error: the planner is not switched off until the app is quit.
        XCTAssertEqual(invalidProvider.scheduleState, .available)

        let noProvider = makeModel(
            defaults: defaults,
            publisher: nil,
            repository: StubJourneyRepository(result: .unavailable(.noAuthorizedProvider))
        )
        await noProvider.run(query: query)
        XCTAssertEqual(noProvider.searchState, .unavailable(.noAuthorizedProvider))
        XCTAssertEqual(noProvider.scheduleState, .unavailable, "no timetables installed at all")
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
        // Nor is a route's short name («C3» of every SNCF train of the line): no star.
        let route = TrainService(
            id: "sncf:c3", number: "C3", operatorName: "SNCF", category: "C3", destination: "Nice",
            scheduledDeparture: service.scheduledDeparture, expectedDeparture: service.scheduledDeparture,
            status: "PROGRAMMED", platform: nil, delayMinutes: 0, hasTrainNumber: false
        )
        XCTAssertFalse(model.canToggleFavourite(route, query: query))
        XCTAssertEqual(model.toggleFavourite(route, query: query), .failed)
        XCTAssertEqual(model.favouriteTrains, [])

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

    func testPartenzeRemembersTheStationReadsLaterPagesAndOpensTheTrainBoardedThere() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let base = try rome(2026, 11, 10, 8, 0)
        let provider = StubBoardProvider(rows: (0..<25).map { boardDeparture($0, after: base) })
        let station = StationIdentity(id: "S01700", name: "Milano Centrale", railwayCode: "S01700")
        func makeBoardModel() -> AppModel {
            AppModel(
                catalogState: .loading,
                savedRouteStore: SavedRouteStore(defaults: defaults),
                favouriteTrainStore: FavouriteTrainStore(defaults: defaults),
                boardStationStore: StationBoardStationStore(defaults: defaults),
                watchSharingStore: WatchSharingPreferenceStore(defaults: defaults),
                repository: FailClosedJourneyRepository(),
                boardProvider: provider,
                watchPublisher: nil,
                versionDisplay: "test"
            )
        }
        let model = makeBoardModel()
        XCTAssertNil(model.boardStation)
        XCTAssertEqual(model.boardState, .idle)

        // Choosing a station shows its first page and remembers it on this iPhone.
        model.selectBoardStation(station)
        XCTAssertEqual(model.boardState, .loading)
        await model.boardLoadFinished()
        await model.reloadBoard(now: base)
        XCTAssertEqual(model.boardState, .available)
        XCTAssertEqual(model.boardDepartures.count, AppModel.boardPageSize)
        XCTAssertEqual(model.boardDepartures.first?.service.number, "2101")
        XCTAssertTrue(model.boardHasMore)
        XCTAssertEqual(model.boardAttributions, ["Synthetic feed"])
        XCTAssertEqual(StationBoardStationStore(defaults: defaults).load(), station)
        let firstPage = await provider.queries.last
        XCTAssertEqual(firstPage?.from, base)
        XCTAssertNil(firstPage?.after)

        // «Più tardi» continues after the last listed train, without repeating one.
        let added = await model.loadMoreBoard()
        XCTAssertEqual(added, 5)
        XCTAssertEqual(model.boardDepartures.map(\.service.number), (0..<25).map { "\(2_101 + $0)" })
        XCTAssertFalse(model.boardHasMore)
        let laterPage = await provider.queries.last
        XCTAssertEqual(laterPage?.after, model.boardDepartures[AppModel.boardPageSize - 1].cursor)
        let nothingMore = await model.loadMoreBoard()
        XCTAssertEqual(nothingMore, 0)

        // A row opens the train boarded at the board's station: the identity of its star.
        let first = model.boardDepartures[0]
        let query = try XCTUnwrap(model.boardDetailQuery(for: first))
        XCTAssertEqual(query.origin, station)
        XCTAssertEqual(query.destination, first.terminus)
        XCTAssertEqual(model.toggleFavourite(first.service, query: query), .added)
        XCTAssertEqual(model.favouriteTrains.map(\.id), [FavouriteTrain.identifier(number: "2101", originID: "S01700")])
        XCTAssertTrue(model.isFavourite(first.service, query: query))

        // A train goes a minute after its departure; an emptied board is read again from then.
        await model.refreshBoardIfNeeded(now: first.departure.addingTimeInterval(60))
        XCTAssertEqual(model.boardDepartures.count, 24)
        let last = try XCTUnwrap(model.boardDepartures.last)
        await model.refreshBoardIfNeeded(now: last.departure.addingTimeInterval(60))
        XCTAssertEqual(model.boardState, .available)
        XCTAssertEqual(model.boardDepartures, [])
        XCTAssertFalse(model.boardHasMore)

        // Another station never shows the trains of the previous one.
        model.selectBoardStation(StationIdentity(id: "S01529", name: "Bergamo", railwayCode: "S01529"))
        XCTAssertEqual(model.boardDepartures, [])
        XCTAssertEqual(model.boardState, .loading)
        await model.boardLoadFinished()

        // The next launch opens on the station chosen last.
        let relaunched = makeBoardModel()
        XCTAssertEqual(relaunched.boardStation?.id, "S01529")
        XCTAssertEqual(relaunched.boardState, .idle)
        await relaunched.refreshBoardIfNeeded(now: base)
        XCTAssertEqual(relaunched.boardState, .available)
        XCTAssertEqual(relaunched.boardDepartures.count, AppModel.boardPageSize)

        // Without a validated index the board fails closed.
        let failClosed = AppModel(
            catalogState: .loading,
            savedRouteStore: SavedRouteStore(defaults: defaults),
            boardStationStore: StationBoardStationStore(defaults: defaults),
            watchSharingStore: WatchSharingPreferenceStore(defaults: defaults),
            repository: FailClosedJourneyRepository(),
            watchPublisher: nil,
            versionDisplay: "test"
        )
        await failClosed.reloadBoard(now: base)
        XCTAssertEqual(failClosed.boardState, .unavailable(.noAuthorizedProvider))
        XCTAssertEqual(failClosed.boardDepartures, [])
    }

    // MARK: Live data

    func testPartenzeLaysTheLiveBoardOverItsRowsOnlyOnScreenAndFallsBackWhenEverySourceFails() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let base = try rome(2026, 11, 10, 8, 0)
        let rows = (0..<5).map { boardDeparture($0, after: base) }
        let live = FakeLiveRailData(board: .available(LiveFixtures.board([
            LiveFixtures.row("2101", scheduled: rows[0].departure, delay: 5, platform: "3", status: .delayed,
                             platformSource: .rfiStationBoard, at: base),
            LiveFixtures.row("2102", scheduled: rows[1].departure, delay: nil, platform: nil, status: .cancelled, at: base),
        ], stationID: milanoCode.id, at: base)))
        let model = makeLiveModel(defaults: defaults, live: live, boardProvider: StubBoardProvider(rows: rows))
        XCTAssertTrue(model.liveDataEnabled, "on by default")
        XCTAssertEqual(model.boardLiveState, .idle)

        model.selectBoardStation(milanoCode)
        await model.boardLoadFinished()
        await model.reloadBoard(now: base)
        // Not on screen: nothing is asked and the rows are the timetable's.
        await model.refreshBoardLive(now: base)
        let hidden = await live.requestCount
        XCTAssertEqual(hidden, 0)
        XCTAssertTrue(model.boardDepartures.allSatisfy(\.isScheduled))

        // On screen: the live board of the station over its rows.
        model.boardLiveDidAppear()
        await model.boardLiveLoadFinished()
        await model.refreshBoardLive(now: base)
        let asked = await live.departureRequests
        XCTAssertEqual(asked.last?.station.id, milanoCode.id)
        XCTAssertEqual(asked.last?.from, base)
        XCTAssertEqual(model.boardLiveState, .available(base))
        XCTAssertEqual(model.boardLiveSources, [.rfiStationBoard, .viaggiaTreno])
        let delayed = try XCTUnwrap(model.boardDepartures[0].live)
        XCTAssertEqual(delayed.delayMinutes, 5)
        XCTAssertEqual(delayed.expectedDeparture, rows[0].departure.addingTimeInterval(5 * 60))
        XCTAssertEqual(delayed.platform, "3")
        XCTAssertEqual(delayed.source, .viaggiaTreno)
        XCTAssertEqual(delayed.platformSource, .rfiStationBoard, "the platform keeps its own source")
        XCTAssertEqual(model.boardDepartures[1].live?.cancelled, true)
        XCTAssertTrue(model.boardDepartures[2].isScheduled, "a train the live board does not list stays scheduled")
        // The live state is part of the row's spoken sentence.
        let sentence = DeparturesTexts.spoken(model.boardDepartures[0])
        XCTAssertTrue(sentence.contains(LiveTexts.delaySentence(5)))
        XCTAssertTrue(sentence.contains(LiveTexts.fromSource(.viaggiaTreno)))
        XCTAssertTrue(sentence.contains(LiveTexts.platformFrom(.rfiStationBoard)))
        XCTAssertEqual(LiveTexts.state(delayed), [LiveTexts.expectedShortTime(rows[0].departure.addingTimeInterval(5 * 60)),
                                                  LiveTexts.delayBadge(5)].joined(separator: " · "))
        let cancelledRow = try XCTUnwrap(model.boardDepartures[1].live)
        XCTAssertEqual(LiveTexts.state(cancelledRow), LiveTexts.cancelled)

        // «Più tardi» keeps the live values of the rows already listed.
        _ = await model.loadMoreBoard()
        XCTAssertEqual(model.boardDepartures[0].live?.delayMinutes, 5)

        // Five minutes on, the values are no longer shown as live.
        model.expireLiveValues(now: base.addingTimeInterval(AppModel.liveDisplaySeconds + 1))
        XCTAssertTrue(model.boardDepartures.allSatisfy(\.isScheduled))
        XCTAssertEqual(model.boardLiveState, .idle)

        // Every source failing: the rows stay the timetable's, all of them.
        await live.setBoard(.unavailable(.sourceUnavailable))
        await model.refreshBoardLive(now: base)
        XCTAssertEqual(model.boardLiveState, .unavailable)
        XCTAssertEqual(model.boardDepartures.count, rows.count)
        XCTAssertTrue(model.boardDepartures.allSatisfy(\.isScheduled))

        // Off screen again: nothing more is asked.
        model.boardLiveDidDisappear()
        let before = await live.requestCount
        await model.refreshBoardLive(now: base)
        let after = await live.requestCount
        XCTAssertEqual(before, after)
    }

    func testAnAnswerForTheStationLeftBehindNeverReachesTheNewOne() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let base = try rome(2026, 11, 10, 8, 0)
        let rows = (0..<3).map { boardDeparture($0, after: base) }
        let live = ControlledLiveBoards()
        let model = makeLiveModel(defaults: defaults, live: live, boardProvider: StubBoardProvider(rows: rows))
        model.selectBoardStation(milanoCode)
        await model.boardLoadFinished()
        await model.reloadBoard(now: base)
        model.boardLiveDidAppear()
        await live.waitUntilPending(milanoCode.id)

        model.selectBoardStation(bergamoCode)
        await live.waitUntilPending(bergamoCode.id)
        await live.complete(milanoCode.id, with: .available(LiveFixtures.board([
            LiveFixtures.row("2101", scheduled: rows[0].departure, delay: 9, platform: "1", status: .delayed, at: base),
        ], stationID: milanoCode.id, at: base)))
        await live.complete(bergamoCode.id, with: .unavailable(.sourceUnavailable))
        await model.boardLiveLoadFinished()
        await model.boardLoadFinished()
        await model.reloadBoard(now: base)

        XCTAssertEqual(model.boardStation, bergamoCode)
        XCTAssertEqual(model.boardLiveState, .unavailable)
        XCTAssertEqual(model.boardDepartures.count, rows.count)
        XCTAssertTrue(model.boardDepartures.allSatisfy(\.isScheduled), "Milano's answer never reaches Bergamo's rows")
    }

    func testNoLiveRequestStartsInTheBackgroundEvenFromAReadingBegunJustBefore() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let base = try rome(2026, 11, 10, 8, 0)
        let rows = (0..<3).map { boardDeparture($0, after: base) }
        let now = Date()
        let query = try JourneyQuery(origin: milanoCode, destination: bergamoCode, departure: now)
        let snapshot = fixtureSnapshot(query: query, serviceNumber: "2153")
        let live = FakeLiveRailData(board: .available(LiveFixtures.board([
            LiveFixtures.row("2101", scheduled: rows[0].departure, delay: 2, platform: "4", status: .delayed, at: base),
        ], stationID: milanoCode.id, at: base)))
        let model = makeLiveModel(
            defaults: defaults, live: live, repository: StubJourneyRepository(result: .available(snapshot)),
            boardProvider: StubBoardProvider(rows: rows)
        )
        model.selectBoardStation(milanoCode)
        await model.boardLoadFinished()
        model.boardLiveDidAppear()
        model.plannerLiveDidAppear()
        await model.boardLiveLoadFinished()
        let before = await live.requestCount
        XCTAssertGreaterThan(before, 0)

        // Home pressed while a pull or a search was still reading the timetable: when it reaches
        // its live step, nothing is asked.
        model.liveDidEnterBackground()
        await model.pullBoardLive()
        await model.run(query: query)
        await model.resultLiveLoadFinished()
        await model.refreshBoardLive(now: base)
        let background = await live.requestCount
        XCTAssertEqual(background, before, "no live request starts in the background")

        // Back in the foreground the screens on display ask again.
        model.liveDidBecomeActive()
        await model.boardLiveLoadFinished()
        await model.resultLiveLoadFinished()
        let after = await live.requestCount
        XCTAssertGreaterThan(after, background)
    }

    func testBackInTheForegroundStaleLiveValuesGoBeforeTheScreenAsksAgain() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let base = try rome(2026, 11, 10, 8, 0)
        let rows = (0..<3).map { boardDeparture($0, after: base) }
        let live = ControlledLiveBoards()
        let model = makeLiveModel(defaults: defaults, live: live, boardProvider: StubBoardProvider(rows: rows))
        model.selectBoardStation(milanoCode)
        await model.boardLoadFinished()
        await model.reloadBoard(now: base)
        model.boardLiveDidAppear()
        await live.waitUntilPending(milanoCode.id)
        await live.complete(milanoCode.id, with: .available(LiveFixtures.board([
            LiveFixtures.row("2101", scheduled: rows[0].departure, delay: 7, platform: "1", status: .delayed, at: base),
        ], stationID: milanoCode.id, at: base)))
        await model.boardLiveLoadFinished()
        XCTAssertEqual(model.boardDepartures[0].live?.delayMinutes, 7)

        // Two hours away, with the minute tick asleep meanwhile: the old values are not shown as
        // current while the board is asked again.
        model.liveDidEnterBackground()
        model.liveDidBecomeActive(now: Date().addingTimeInterval(2 * 3_600))
        XCTAssertTrue(model.boardDepartures.allSatisfy(\.isScheduled), "values of two hours ago are not the train's state")
        XCTAssertEqual(model.boardLiveState, .loading)
        XCTAssertEqual(model.liveSourcePauses, [])
        await live.waitUntilPending(milanoCode.id)
        await live.complete(milanoCode.id, with: .unavailable(.sourceUnavailable))
        await model.boardLiveLoadFinished()
        XCTAssertEqual(model.boardLiveState, .unavailable)
    }

    func testPlannerResultsTakeTheLiveBoardOfTheOriginWithinTheLiveWindow() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let now = Date()
        let query = try JourneyQuery(origin: milanoCode, destination: bergamoCode, departure: now)
        let snapshot = fixtureSnapshot(query: query, serviceNumber: "RE_11 - 2153")
        let live = FakeLiveRailData(board: .available(LiveFixtures.board([
            LiveFixtures.row("2153", scheduled: now.addingTimeInterval(30 * 60), delay: 4, platform: "7", status: .delayed, at: now),
        ], stationID: milanoCode.id, at: now)))
        let model = makeLiveModel(defaults: defaults, live: live, repository: StubJourneyRepository(result: .available(snapshot)))

        // A search with the planner off screen asks nothing.
        await model.run(query: query)
        let hidden = await live.requestCount
        XCTAssertEqual(hidden, 0)
        XCTAssertTrue(model.resultLive.isEmpty)

        // On screen: one board request for the origin, whatever the number of trains.
        model.plannerLiveDidAppear()
        await model.resultLiveLoadFinished()
        let asked = await live.departureRequests
        XCTAssertEqual(asked.count, 1)
        XCTAssertEqual(asked.last?.station.id, milanoCode.id)
        XCTAssertEqual(asked.last?.from, StationBoardQuery.minuteStart(of: now))
        let status = try XCTUnwrap(model.resultLive[snapshot.services[0].id], "matched by the number proper of «RE_11 - 2153»")
        XCTAssertEqual(status.delayMinutes, 4)
        XCTAssertEqual(status.platform, "7")
        guard case .available = model.resultLiveState else { return XCTFail("expected live values, got \(model.resultLiveState)") }
        XCTAssertEqual(model.resultLiveSources, [.rfiStationBoard, .viaggiaTreno])

        // Every source failing: the results stay, without live values.
        await live.setBoard(.unavailable(.refused))
        await model.refreshResultLive(now: now)
        XCTAssertEqual(model.resultLiveState, .unavailable)
        XCTAssertTrue(model.resultLive.isEmpty)
        XCTAssertEqual(model.latestSnapshot, snapshot)

        // A changed input drops them and the answer on its way.
        await live.setBoard(.available(LiveFixtures.board([
            LiveFixtures.row("2153", scheduled: now.addingTimeInterval(30 * 60), delay: 4, platform: "7", status: .delayed, at: now),
        ], stationID: milanoCode.id, at: now)))
        await model.refreshResultLive(now: now)
        XCTAssertFalse(model.resultLive.isEmpty)
        model.invalidateSearchResult()
        XCTAssertTrue(model.resultLive.isEmpty)
        XCTAssertEqual(model.resultLiveState, .idle)

        // A search beyond the live window asks nothing.
        let later = try JourneyQuery(origin: milanoCode, destination: bergamoCode, departure: now.addingTimeInterval(5 * 3_600))
        let laterLive = FakeLiveRailData()
        let laterModel = makeLiveModel(
            defaults: defaults, live: laterLive,
            repository: StubJourneyRepository(result: .available(fixtureSnapshot(query: later, serviceNumber: "2153")))
        )
        laterModel.plannerLiveDidAppear()
        await laterModel.run(query: later)
        await laterModel.resultLiveLoadFinished()
        let laterAsked = await laterLive.requestCount
        XCTAssertEqual(laterAsked, 0)
        XCTAssertEqual(laterModel.resultLiveState, .idle)
    }

    func testTrainDetailShowsItsOwnRunAtTheBoardingStationAndNeverAnotherDays() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let now = Date()
        let departure = StationBoardQuery.minuteStart(of: now.addingTimeInterval(20 * 60))
        let query = try JourneyQuery(origin: milanoCode, destination: bergamoCode, departure: departure)
        let service = TrainService(
            id: "trenord:2153", number: "RE_11 - 2153", operatorName: "Trenord", category: "RE", destination: "Bergamo",
            scheduledDeparture: romeTime(departure), expectedDeparture: romeTime(departure), status: "PROGRAMMED",
            platform: nil, delayMinutes: 0
        )
        let today = LiveFixtures.run("2153", id: "viaggiatreno:today", boardingCode: "S01700", departure: departure, delay: 7, at: now)
        let yesterday = LiveFixtures.run(
            "2153", id: "viaggiatreno:yesterday", boardingCode: "S01700", departure: departure.addingTimeInterval(-86_400), delay: 30, at: now
        )
        let live = FakeLiveRailData(status: { _ in
            .available(LiveTrainStatusLookup(runs: [yesterday, today], unavailableSources: [:], isComplete: true))
        })
        let model = makeLiveModel(defaults: defaults, live: live)

        let key = try XCTUnwrap(model.liveTrainKey(for: service, query: query, now: now))
        XCTAssertEqual(key.number, "2153", "the number proper, as Android asks it")
        XCTAssertEqual(key.departure, departure)
        // Not on screen: nothing is asked.
        await model.refreshDetailLive(now: now)
        let hidden = await live.requestCount
        XCTAssertEqual(hidden, 0)

        model.detailLiveDidAppear(key)
        await model.detailLiveLoadFinished()
        let request = await live.statusRequests.last
        XCTAssertEqual(request?.number, "2153")
        XCTAssertEqual(request?.stationCode, "S01700")
        XCTAssertEqual(request?.serviceDate, departure)
        let status = try XCTUnwrap(model.liveRun(for: key))
        XCTAssertEqual(status.run.id, "viaggiatreno:today", "yesterday's run of the same number is another run")
        XCTAssertEqual(status.boarding.delayMinutes, 7)
        XCTAssertEqual(status.boarding.platform, "5")
        XCTAssertEqual(LiveTexts.runState(status), [LiveTexts.delaySentence(7)])
        XCTAssertEqual(LiveTexts.boardingDeparture(status.boarding)?.time, departure.addingTimeInterval(7 * 60))
        XCTAssertTrue(LiveTexts.spokenStop(status.boarding).contains(LiveTexts.delaySentence(7)))
        if case .available = model.liveRunState(for: key) {} else { XCTFail("expected the run, got \(model.liveRunState(for: key))") }

        // Only another day's run: nothing is shown rather than a guess.
        await live.setStatus { _ in .available(LiveTrainStatusLookup(runs: [yesterday], unavailableSources: [:], isComplete: true)) }
        await model.refreshDetailLive(now: now)
        XCTAssertNil(model.liveRun(for: key))
        XCTAssertEqual(model.liveRunState(for: key), .unavailable)

        // Every source failing: the detail stays on its timetable.
        await live.setStatus { _ in .unavailable(.sourceUnavailable) }
        await model.refreshDetailLive(now: now)
        XCTAssertNil(model.liveRun(for: key))
        XCTAssertEqual(model.liveRunState(for: key), .unavailable)

        // Leaving the screen: nothing more is asked.
        model.detailLiveDidDisappear(key)
        let before = await live.requestCount
        await model.refreshDetailLive(now: now)
        let after = await live.requestCount
        XCTAssertEqual(before, after)
        XCTAssertEqual(model.liveRunState(for: key), .idle)

        // A train outside the live window, or a line name, is never asked about.
        let tomorrow = try JourneyQuery(origin: milanoCode, destination: bergamoCode, departure: departure.addingTimeInterval(86_400))
        XCTAssertNil(model.liveTrainKey(for: service, query: tomorrow, now: now))
        let line = fixtureSnapshot(query: query, serviceNumber: "Linea SASSARI-SORSO").services[0]
        XCTAssertNil(model.liveTrainKey(for: line, query: query, now: now))
        // A route's short name («R35») would be asked as train 35: never.
        let route = TrainService(
            id: "trentino:r35", number: "R35", operatorName: "Trentino Trasporti", category: "R35", destination: "Bergamo",
            scheduledDeparture: romeTime(departure), expectedDeparture: romeTime(departure), status: "PROGRAMMED",
            platform: nil, delayMinutes: 0, hasTrainNumber: false
        )
        XCTAssertNil(model.liveTrainKey(for: route, query: query, now: now))
    }

    func testAnExtraordinaryStopIsShownAsSuchNeverAsASkippedOne() {
        let scheduled = Date(timeIntervalSince1970: 1_793_778_000)
        let added = LiveStopStatus(
            stationCode: "S05043", stationName: "BOLOGNA CENTRALE", scheduled: scheduled, scheduledDeparture: nil,
            expected: scheduled, actual: nil, actualDeparture: nil, platform: "3", scheduledPlatform: nil, delayMinutes: 0,
            passed: false, skipped: false, operationalStatusVerified: false, added: true
        )
        XCTAssertEqual(LiveTexts.stopParts(added), [LiveTexts.added, DeparturesTexts.platform("3")])
        XCTAssertTrue(LiveTexts.spokenStop(added).contains(LiveTexts.added))
        XCTAssertFalse(LiveTexts.spokenStop(added).contains(LiveTexts.skipped))
        XCTAssertNotEqual(LiveTexts.added, LiveTexts.skipped)
    }

    func testMyTrainsAsksTheFirstEightFavouritesOnlyWhileOnScreen() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let now = Date()
        let query = try JourneyQuery(origin: milanoCode, destination: bergamoCode, departure: now)
        let live = FakeLiveRailData(status: { request in
            .available(LiveTrainStatusLookup(runs: [
                LiveFixtures.run(request.number, id: "viaggiatreno:\(request.number)", boardingCode: request.stationCode,
                                 departure: request.serviceDate, delay: 3, at: request.serviceDate),
            ], unavailableSources: [:], isComplete: true))
        })
        let model = makeLiveModel(defaults: defaults, live: live)
        for number in 1...10 {
            let time = romeTime(now.addingTimeInterval(TimeInterval(10 + number) * 60))
            let service = TrainService(
                id: "trenord:\(number)", number: "\(2_100 + number)", operatorName: "Trenord", category: "RE",
                destination: "Bergamo", scheduledDeparture: time, expectedDeparture: time, status: "PROGRAMMED",
                platform: nil, delayMinutes: 0
            )
            XCTAssertEqual(model.toggleFavourite(service, query: query, now: now), .added)
        }

        // Not on screen: nothing is asked.
        await model.refreshFavouritesLive(now: now)
        let hidden = await live.requestCount
        XCTAssertEqual(hidden, 0)

        model.favouritesLiveDidAppear()
        await model.favouritesLiveLoadFinished()
        let asked = await live.statusRequests
        XCTAssertEqual(asked.count, AppModel.maximumLiveFavourites)
        XCTAssertEqual(asked.map(\.number), model.favouriteTrains.prefix(AppModel.maximumLiveFavourites).map { NetworkReliability.numberKey($0.number) })
        XCTAssertEqual(model.favouriteLive.count, AppModel.maximumLiveFavourites)
        let first = try XCTUnwrap(model.favouriteLive[model.favouriteTrains[0].id])
        XCTAssertEqual(LiveTexts.runState(first), [LiveTexts.delaySentence(3)])
        XCTAssertNil(model.favouriteLive[model.favouriteTrains[9].id], "the ninth and tenth are not asked")
        guard case .available = model.favouriteLiveState else { return XCTFail("expected live states, got \(model.favouriteLiveState)") }

        // Off screen: nothing more is asked.
        model.favouritesLiveDidDisappear()
        await model.refreshFavouritesLive(now: now)
        let after = await live.requestCount
        XCTAssertEqual(after, AppModel.maximumLiveFavourites)
    }

    func testTheLiveSwitchIsOnByDefaultPersistedValidatedAndOffAsksNothing() async throws {
        let defaults = try makeDefaults()
        defer { clear(defaults) }
        let store = LiveDataPreferenceStore(defaults: defaults)
        XCTAssertTrue(store.load(), "on by default")
        let foreign: [Any] = ["false", 0, Data([0])]
        for value in foreign {
            defaults.set(value, forKey: LiveDataPreferenceStore.storageKey)
            XCTAssertTrue(store.load(), "a value the app did not write reads as on: \(value)")
        }
        store.save(false)
        XCTAssertFalse(LiveDataPreferenceStore(defaults: defaults).load())

        // Off: no screen asks anything, and every state reads as off.
        let base = try rome(2026, 11, 10, 8, 0)
        let rows = (0..<3).map { boardDeparture($0, after: base) }
        let now = Date()
        let query = try JourneyQuery(origin: milanoCode, destination: bergamoCode, departure: now)
        let snapshot = fixtureSnapshot(query: query, serviceNumber: "2153")
        let live = FakeLiveRailData(
            board: .available(LiveFixtures.board([
                LiveFixtures.row("2101", scheduled: rows[0].departure, delay: 2, platform: "4", status: .delayed, at: base),
            ], stationID: milanoCode.id, at: base)),
            status: { request in
                .available(LiveTrainStatusLookup(runs: [
                    LiveFixtures.run(request.number, id: "viaggiatreno:\(request.number)", boardingCode: request.stationCode,
                                     departure: request.serviceDate, delay: 1, at: request.serviceDate),
                ], unavailableSources: [:], isComplete: true))
            }
        )
        let model = makeLiveModel(
            defaults: defaults, live: live, repository: StubJourneyRepository(result: .available(snapshot)),
            boardProvider: StubBoardProvider(rows: rows)
        )
        XCTAssertFalse(model.liveDataEnabled)
        XCTAssertFalse(model.isLiveDataActive)
        model.selectBoardStation(milanoCode)
        await model.boardLoadFinished()
        await model.reloadBoard(now: base)
        model.boardLiveDidAppear()
        await model.boardLiveLoadFinished()
        await model.refreshBoardLive(now: base)
        model.plannerLiveDidAppear()
        await model.run(query: query)
        await model.resultLiveLoadFinished()
        let service = snapshot.services[0]
        XCTAssertEqual(model.toggleFavourite(service, query: query, now: now), .added)
        model.favouritesLiveDidAppear()
        await model.favouritesLiveLoadFinished()
        let key = try XCTUnwrap(model.liveTrainKey(for: service, query: query, now: now))
        model.detailLiveDidAppear(key)
        await model.detailLiveLoadFinished()
        let offCount = await live.requestCount
        XCTAssertEqual(offCount, 0, "with the switch off the app makes no request")
        XCTAssertEqual(model.boardLiveState, .off)
        XCTAssertEqual(model.resultLiveState, .off)
        XCTAssertEqual(model.favouriteLiveState, .off)
        XCTAssertEqual(model.liveRunState(for: key), .off)
        XCTAssertTrue(model.boardDepartures.allSatisfy(\.isScheduled))

        // On again: the screens on display ask, through the provider.
        model.setLiveDataEnabled(true)
        XCTAssertTrue(LiveDataPreferenceStore(defaults: defaults).load())
        await model.boardLiveLoadFinished()
        await model.resultLiveLoadFinished()
        await model.detailLiveLoadFinished()
        await model.favouritesLiveLoadFinished()
        let onCount = await live.requestCount
        XCTAssertGreaterThan(onCount, 0)
        XCTAssertEqual(model.boardDepartures[0].live?.delayMinutes, 2)
        XCTAssertNotNil(model.liveRun(for: key))

        // Off again: every live value goes at once and nothing more is asked.
        model.setLiveDataEnabled(false)
        XCTAssertFalse(LiveDataPreferenceStore(defaults: defaults).load())
        XCTAssertTrue(model.boardDepartures.allSatisfy(\.isScheduled))
        XCTAssertTrue(model.resultLive.isEmpty)
        XCTAssertTrue(model.favouriteLive.isEmpty)
        XCTAssertNil(model.liveRun(for: key))
        XCTAssertEqual(model.boardLiveState, .off)
        await model.refreshBoardLive(now: base)
        await model.refreshDetailLive(now: now)
        await model.refreshFavouritesLive(now: now)
        let finalCount = await live.requestCount
        XCTAssertEqual(finalCount, onCount)

        // A model without live sources never asks either, whatever the switch says.
        let offline = makeLiveModel(defaults: defaults, live: nil)
        offline.setLiveDataEnabled(true)
        XCTAssertFalse(offline.isLiveDataActive)
        XCTAssertEqual(offline.boardLiveState, .off)
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
    /// With their railway codes, which the live sources are asked with.
    private var milanoCode: StationIdentity { StationIdentity(id: "S01700", name: "Milano Centrale", railwayCode: "S01700") }
    private var bergamoCode: StationIdentity { StationIdentity(id: "S01529", name: "Bergamo", railwayCode: "S01529") }

    /// A model with live sources (or none), every store in `defaults`, and its own pauses.
    private func makeLiveModel(
        defaults: UserDefaults,
        live: (any LiveRailProviding)?,
        repository: any JourneyRepository = FailClosedJourneyRepository(),
        boardProvider: any StationBoardProvider = FailClosedStationBoardProvider()
    ) -> AppModel {
        AppModel(
            catalogState: .loading,
            savedRouteStore: SavedRouteStore(defaults: defaults),
            favouriteTrainStore: FavouriteTrainStore(defaults: defaults),
            boardStationStore: StationBoardStationStore(defaults: defaults),
            watchSharingStore: WatchSharingPreferenceStore(defaults: defaults),
            repository: repository,
            boardProvider: boardProvider,
            watchPublisher: nil,
            liveData: live,
            liveDataStore: LiveDataPreferenceStore(defaults: defaults),
            livePauses: LiveSourcePauses(),
            versionDisplay: "test"
        )
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

    /// The board train `index` minutes after the first one, ten minutes after `base`.
    private func boardDeparture(_ index: Int, after base: Date) -> StationBoardDeparture {
        let departure = base.addingTimeInterval(TimeInterval(10 * 60 + index * 60))
        let time = romeTime(departure)
        return StationBoardDeparture(
            departure: departure,
            service: TrainService(
                id: "source:trip-\(index):20261110:\(36_600 + index * 60)",
                number: "\(2_101 + index)",
                operatorName: "Trenord",
                category: "REG",
                destination: "Bergamo",
                scheduledDeparture: time,
                expectedDeparture: time,
                status: "PROGRAMMED",
                platform: nil,
                delayMinutes: 0
            ),
            terminus: StationIdentity(id: "S01529", name: "Bergamo", railwayCode: "S01529")
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
        defaults.removeObject(forKey: StationBoardStationStore.storageKey)
        defaults.removeObject(forKey: WatchSharingPreferenceStore.storageKey)
        defaults.removeObject(forKey: WatchSharingPreferenceStore.pinnedRouteStorageKey)
        defaults.removeObject(forKey: LiveDataPreferenceStore.storageKey)
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

/// «Partenze» over a fixed list of trains: those from the query's time on, after its cursor.
private actor StubBoardProvider: StationBoardProvider {
    private(set) var queries: [StationBoardQuery] = []
    private let rows: [StationBoardDeparture]

    init(rows: [StationBoardDeparture]) {
        self.rows = rows
    }

    func departures(for query: StationBoardQuery) async -> StationBoardResult {
        queries.append(query)
        let following = rows.filter { row in
            guard row.departure >= query.from else { return false }
            guard let after = query.after else { return true }
            return after < row.cursor
        }
        return .available(StationBoardPage(
            query: query,
            departures: Array(following.prefix(query.limit)),
            hasMore: following.count > query.limit,
            firstDepartureNextDay: nil,
            attributions: ["Synthetic feed"]
        ))
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

/// Live values for the model tests. Their observations stay live for a year, so no test depends
/// on the machine's clock; nothing here touches the network.
private enum LiveFixtures {
    static func observation(_ source: LiveSource, at date: Date) -> LiveObservation {
        LiveObservation(source: source, fetchedAt: date, observedAt: date, expiresAt: date.addingTimeInterval(365 * 86_400))
    }

    /// A row of a live board, its time and state from `source`, its platform from `platformSource`.
    static func row(
        _ number: String, scheduled: Date, delay: Int?, platform: String?, status: LiveDepartureStatus?,
        source: LiveSource = .viaggiaTreno, platformSource: LiveSource? = nil, at date: Date
    ) -> LiveDeparture {
        let seen = observation(source, at: date)
        let platformSeen = observation(platformSource ?? source, at: date)
        let expected: LiveSourced<Date>? = delay.map { minutes in
            LiveSourced(scheduled.addingTimeInterval(TimeInterval(minutes) * 60), observation: seen)
        }
        return LiveDeparture(
            id: "\(source.rawValue):\(number)", number: number, operatorID: "trenitalia", category: "REG",
            destination: "Bergamo", scheduled: scheduled, expected: expected,
            platform: platform.map { LiveSourced($0, observation: platformSeen) }, scheduledPlatform: nil,
            status: status.map { LiveSourced($0, observation: seen) }, sources: [source], viaggiaTrenoRun: nil,
            observation: seen
        )
    }

    static func board(_ rows: [LiveDeparture], stationID: String, at date: Date) -> LiveDepartureBoard {
        LiveDepartureBoard(
            stationID: stationID, from: date, to: date.addingTimeInterval(6 * 3_600), departures: rows,
            sources: [.rfiStationBoard, .viaggiaTreno], unavailableSources: [:], warnings: [], fetchedAt: date
        )
    }

    /// A verified ViaggiaTreno run leaving the boarding station at `departure` with `delay`
    /// minutes, platform 5, then Bergamo an hour later.
    static func run(
        _ number: String, id: String, boardingCode: String?, departure: Date, delay: Int, at date: Date
    ) -> LiveRunStatus {
        let late: TimeInterval = TimeInterval(delay) * 60
        let arrival = departure.addingTimeInterval(3_600)
        let boarding = LiveStopStatus(
            stationCode: boardingCode, stationName: "MILANO CENTRALE", scheduled: departure, scheduledDeparture: nil,
            expected: departure.addingTimeInterval(late), actual: nil, actualDeparture: nil, platform: "5",
            scheduledPlatform: "4", delayMinutes: delay, passed: false, skipped: false, operationalStatusVerified: true
        )
        let last = LiveStopStatus(
            stationCode: "S01529", stationName: "BERGAMO", scheduled: arrival, scheduledDeparture: nil,
            expected: arrival.addingTimeInterval(late), actual: nil, actualDeparture: nil, platform: nil,
            scheduledPlatform: nil, delayMinutes: delay, passed: false, skipped: false, operationalStatusVerified: true
        )
        return LiveRunStatus(
            id: id, number: number, category: "REG", operatorName: "Trenitalia", operatorID: "trenitalia",
            originCode: boardingCode, originName: "MILANO CENTRALE", destinationName: "BERGAMO",
            scheduledDeparture: departure, expectedDeparture: departure.addingTimeInterval(late), platform: "5",
            scheduledPlatform: "4", state: delay > 0 ? .delayed : .onTime, cancellation: .none, delayMinutes: delay,
            operationalStatusVerified: true,
            lastObservation: LiveRunObservationPoint(stationName: "MILANO LAMBRATE", at: date, isRecent: true),
            stops: [boarding, last], observation: observation(.viaggiaTreno, at: date), viaggiaTrenoRun: nil
        )
    }
}

/// Live sources answering what the test sets and recording every request.
private actor FakeLiveRailData: LiveTrainStatusProviding, LiveDeparturesProviding {
    typealias StatusHandler = @Sendable (LiveTrainStatusRequest) -> LiveTrainStatusResult

    private(set) var departureRequests: [LiveDeparturesRequest] = []
    private(set) var statusRequests: [LiveTrainStatusRequest] = []
    private var board: LiveDeparturesResult
    private var status: StatusHandler

    init(
        board: LiveDeparturesResult = .unavailable(.sourceUnavailable),
        status: @escaping StatusHandler = { _ in .unavailable(.sourceUnavailable) }
    ) {
        self.board = board
        self.status = status
    }

    var requestCount: Int { departureRequests.count + statusRequests.count }

    func setBoard(_ result: LiveDeparturesResult) {
        board = result
    }

    func setStatus(_ handler: @escaping StatusHandler) {
        status = handler
    }

    func departures(_ request: LiveDeparturesRequest) async -> LiveDeparturesResult {
        departureRequests.append(request)
        return board
    }

    func trainStatus(_ request: LiveTrainStatusRequest) async -> LiveTrainStatusResult {
        statusRequests.append(request)
        return status(request)
    }
}

/// Live boards that answer only when the test says, by station.
private actor ControlledLiveBoards: LiveTrainStatusProviding, LiveDeparturesProviding {
    private var pending: [String: CheckedContinuation<LiveDeparturesResult, Never>] = [:]

    func departures(_ request: LiveDeparturesRequest) async -> LiveDeparturesResult {
        await withCheckedContinuation { continuation in
            pending[request.station.id] = continuation
        }
    }

    func trainStatus(_ request: LiveTrainStatusRequest) async -> LiveTrainStatusResult {
        .unavailable(.notCovered)
    }

    func waitUntilPending(_ stationID: String) async {
        while pending[stationID] == nil {
            await Task.yield()
        }
    }

    func complete(_ stationID: String, with result: LiveDeparturesResult) {
        pending.removeValue(forKey: stationID)?.resume(returning: result)
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
