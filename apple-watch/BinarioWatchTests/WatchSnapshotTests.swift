import XCTest
@testable import BinarioWatch

final class WatchSnapshotTests: XCTestCase {
    private let fixtureNow: Int64 = 1_893_484_800_000

    func testUnavailableSnapshotIsCompatibleAndDisclosesNoLiveData() {
        let snapshot = BinarioWatchSnapshot.unavailable

        XCTAssertTrue(snapshot.isCompatible)
        XCTAssertEqual(snapshot.freshness, .unavailable)
        XCTAssertEqual(snapshot.notice, "dati non disponibili in tempo reale")
    }

    func testServiceAccessibilityAnnouncesServiceDataIndependentlyOfLocale() {
        let service = WatchService(number: "9512", operatorName: "Trenitalia", category: "Freccia", destination: "Torino Porta Nuova", scheduledDeparture: "08:10", expectedDeparture: "08:18", platform: "7", delayMinutes: 8, status: "In ritardo")

        XCTAssertTrue(service.accessibilitySummary.contains("Trenitalia"))
        XCTAssertTrue(service.accessibilitySummary.contains("9512"))
        XCTAssertTrue(service.accessibilitySummary.contains("Torino Porta Nuova"))
        XCTAssertTrue(service.accessibilitySummary.contains("08:18"))
        XCTAssertTrue(service.accessibilitySummary.contains("7"))
    }

    func testIOSGoldenFixtureUsesTheSharedV1Contract() throws {
        let snapshot = BinarioWatchSnapshot.decodeContract(try fixtureData(), nowEpochMillis: fixtureNow)

        XCTAssertEqual(snapshot?.schemaVersion, 1)
        XCTAssertEqual(snapshot?.provenanceProducer, .iosApp)
        XCTAssertEqual(snapshot?.operationalState, .operational)
        XCTAssertEqual(snapshot?.services.first?.number, "FR 9505")
    }

    func testProgrammedSnapshotIsPresentedAsScheduledNotAsMissingRealtime() throws {
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: fixtureData()) as? [String: Any])
        var programmed = object
        programmed["provenanceDataNature"] = "PROGRAMMED"
        programmed["operationalState"] = "PROGRAMMED"
        programmed["freshness"] = "UNKNOWN"
        programmed["service0Status"] = "PROGRAMMED"
        let snapshot = try XCTUnwrap(BinarioWatchSnapshot.decodeContract(
            try JSONSerialization.data(withJSONObject: programmed),
            nowEpochMillis: fixtureNow
        ))

        XCTAssertTrue(snapshot.isScheduledOnly)
        XCTAssertNotEqual(snapshot.displayFreshnessLabel, snapshot.freshness.label)
        XCTAssertNotEqual(snapshot.services.first?.displayStatus, "PROGRAMMED")
        XCTAssertFalse(BinarioWatchSnapshot.unavailable.isScheduledOnly)
        XCTAssertEqual(BinarioWatchSnapshot.unavailable.displayFreshnessLabel, BinarioFreshness.unavailable.label)
        let operational = try XCTUnwrap(BinarioWatchSnapshot.decodeContract(try fixtureData(), nowEpochMillis: fixtureNow))
        XCTAssertFalse(operational.isScheduledOnly)
        XCTAssertEqual(operational.services.first?.displayStatus, "In ritardo")
    }

    func testUnknownFutureEnumsDegradeToUnknown() throws {
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: fixtureData()) as? [String: Any])
        var future = object
        future["direction"] = "FUTURE_DIRECTION"
        future["freshness"] = "FUTURE_FRESHNESS"
        future["confidence"] = "FUTURE_CONFIDENCE"
        future["provenanceProducer"] = "FUTURE_PRODUCER"
        future["provenanceDataNature"] = "FUTURE_NATURE"
        future["operationalState"] = "FUTURE_STATE"
        let data = try JSONSerialization.data(withJSONObject: future)
        let snapshot = BinarioWatchSnapshot.decodeContract(data, nowEpochMillis: fixtureNow)

        XCTAssertEqual(snapshot?.direction, .unknown)
        XCTAssertEqual(snapshot?.freshness, .unknown)
        XCTAssertEqual(snapshot?.confidence, .unknown)
        XCTAssertEqual(snapshot?.provenanceProducer, .unknown)
        XCTAssertEqual(snapshot?.provenanceDataNature, .unknown)
        XCTAssertEqual(snapshot?.operationalState, .unknown)
    }

    func testOptionalFieldsFixtureDecodesReliabilityAndVehicles() throws {
        let snapshot = try XCTUnwrap(BinarioWatchSnapshot.decodeContract(try fixtureData("ios-to-apple-watch-v1-optional"), nowEpochMillis: fixtureNow))

        XCTAssertEqual(snapshot.services.first?.reliability, WatchReliability(onTime: 22, samples: 27))
        XCTAssertEqual(snapshot.services.first?.vehicle, .train)
        XCTAssertEqual(snapshot.services.first?.isBus, false)
        XCTAssertNil(snapshot.services.last?.reliability)
        XCTAssertEqual(snapshot.services.last?.vehicle, .italobus)
        XCTAssertEqual(snapshot.services.last?.isBus, true)
        // The iPhone sends the bus label as the category: the row reads «ItaloBus 1060», never «treno».
        XCTAssertEqual(snapshot.services.last?.category, "ItaloBus")
        XCTAssertNotEqual(snapshot.services.last?.accessibilitySummary, snapshot.services.last.map { service in
            WatchService(
                number: service.number, operatorName: service.operatorName, category: service.category,
                destination: service.destination, scheduledDeparture: service.scheduledDeparture,
                expectedDeparture: service.expectedDeparture, platform: service.platform,
                delayMinutes: service.delayMinutes, status: service.status
            ).accessibilitySummary
        })
        // Without the fields, as an earlier iPhone sends it, every service is a train without reliability.
        let plain = try XCTUnwrap(BinarioWatchSnapshot.decodeContract(try fixtureData(), nowEpochMillis: fixtureNow))
        XCTAssertTrue(plain.services.allSatisfy { $0.vehicle == nil && !$0.isBus && $0.reliability == nil })
    }

    func testOptionalFieldsAreRejectedWhenIncompleteOutOfRangeOrMalformed() throws {
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: fixtureData("ios-to-apple-watch-v1-optional")) as? [String: Any])
        func decoded(_ change: (inout [String: Any]) -> Void) throws -> BinarioWatchSnapshot? {
            var variant = object
            change(&variant)
            return BinarioWatchSnapshot.decodeContract(try JSONSerialization.data(withJSONObject: variant), nowEpochMillis: fixtureNow)
        }

        XCTAssertNil(try decoded { $0["service0ReliabilitySamples"] = nil })
        XCTAssertNil(try decoded { $0["service0ReliabilityOnTime"] = nil })
        XCTAssertNil(try decoded { $0["service0ReliabilitySamples"] = 4 })
        XCTAssertNil(try decoded { $0["service0ReliabilitySamples"] = 10_001 })
        XCTAssertNil(try decoded { $0["service0ReliabilityOnTime"] = 28 })
        XCTAssertNil(try decoded { $0["service0ReliabilityOnTime"] = "22" })
        XCTAssertNil(try decoded { $0["service0Vehicle"] = "" })
        XCTAssertNil(try decoded { $0["service0Vehicle"] = " BUS" })
        XCTAssertNil(try decoded { $0["service0Vehicle"] = String(repeating: "B", count: 33) })
        XCTAssertNil(try decoded { $0["service2Vehicle"] = "BUS" })
        XCTAssertNil(try decoded { $0["service2ReliabilityOnTime"] = 1; $0["service2ReliabilitySamples"] = 5 })
        // A vehicle this version does not know is shown as a train; each service carries its own.
        XCTAssertEqual(try decoded { $0["service1Vehicle"] = "FERRY" }?.services.last?.vehicle, .unknown)
        XCTAssertEqual(try decoded { $0["service1Vehicle"] = "FERRY" }?.services.last?.isBus, false)
        XCTAssertNil(try decoded { $0["service0Vehicle"] = nil }?.services.first?.vehicle)
        XCTAssertEqual(try decoded { $0["service0ReliabilityOnTime"] = 0; $0["service0ReliabilitySamples"] = 5 }?.services.first?.reliability, WatchReliability(onTime: 0, samples: 5))
    }

    func testOptionalFieldsSurviveTheAppGroupCopy() throws {
        let snapshot = try XCTUnwrap(BinarioWatchSnapshot.decodeContract(try fixtureData("ios-to-apple-watch-v1-optional"), nowEpochMillis: fixtureNow))
        let stored = try JSONEncoder().encode(snapshot)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: stored) as? [String: Any])

        XCTAssertEqual(object["service0ReliabilityOnTime"] as? Int, 22)
        XCTAssertEqual(object["service1Vehicle"] as? String, "ITALOBUS")
        XCTAssertNil(object["service1ReliabilityOnTime"])
        XCTAssertEqual(BinarioWatchSnapshot.decodeContract(stored, nowEpochMillis: fixtureNow), snapshot)
        // Revalidation keeps them on the trains still to come.
        let later = snapshot.revalidated(at: Date(timeIntervalSince1970: TimeInterval(fixtureNow) / 1_000))
        XCTAssertEqual(later.services.map(\.vehicle), snapshot.services.map(\.vehicle))
    }

    func testLiveDataNameTheTimeTheirSourceObservedThem() throws {
        let snapshot = try XCTUnwrap(BinarioWatchSnapshot.decodeContract(try fixtureData(), nowEpochMillis: fixtureNow))
        // 1_893_484_795_000 is 08:59:55 in Rome on 1 January 2030.
        XCTAssertEqual(snapshot.observedClockTime, "08:59")
        XCTAssertEqual(snapshot.displayFreshnessLabel, WatchLocalization.text("watch.freshness.liveAsOf \("08:59")"))
        XCTAssertNotEqual(snapshot.displayFreshnessLabel, snapshot.freshness.label)
    }

    func testTheWatchDeclaresTheOptionalFieldsItDecodes() {
        XCTAssertEqual(WatchCapabilityDeclaration.contextKey, "binario_watch_capabilities")
        XCTAssertEqual(
            Set(WatchCapabilityDeclaration.capabilities),
            ["binario_wear_reliability_v1", "binario_wear_vehicle_v1", "binario_wear_programmed_fallback_v1"]
        )
        XCTAssertEqual(WatchCapabilityDeclaration.context[WatchCapabilityDeclaration.contextKey] as? [String], WatchCapabilityDeclaration.capabilities)
    }

    func testFutureVersionExtraKeyAndExpiredPayloadAreRejected() throws {
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: fixtureData()) as? [String: Any])
        var futureVersion = object
        futureVersion["schemaVersion"] = 2
        var extraKey = object
        extraKey["token"] = "must-not-be-accepted"

        XCTAssertNil(BinarioWatchSnapshot.decodeContract(try JSONSerialization.data(withJSONObject: futureVersion), nowEpochMillis: fixtureNow))
        XCTAssertNil(BinarioWatchSnapshot.decodeContract(try JSONSerialization.data(withJSONObject: extraKey), nowEpochMillis: fixtureNow))
        XCTAssertNil(BinarioWatchSnapshot.decodeContract(try fixtureData(), nowEpochMillis: 1_893_485_700_001))
    }

    func testApplicationContextAcceptsSnapshotAndClearSignalTakesPrecedence() throws {
        let data = try fixtureData()
        let accepted = WatchApplicationContextAction.decode(
            [WatchApplicationContextAction.snapshotKey: data],
            nowEpochMillis: fixtureNow
        )
        guard case let .accept(snapshot) = accepted else {
            return XCTFail("Expected accepted snapshot")
        }
        XCTAssertEqual(snapshot.services.first?.number, "FR 9505")

        XCTAssertEqual(
            WatchApplicationContextAction.decode([
                WatchApplicationContextAction.snapshotKey: data,
                WatchApplicationContextAction.clearKey: Data(),
            ], nowEpochMillis: fixtureNow),
            .clear
        )
        XCTAssertEqual(
            WatchApplicationContextAction.decode([
                WatchApplicationContextAction.snapshotKey: Data("invalid".utf8),
            ], nowEpochMillis: fixtureNow),
            .ignore
        )
    }

    func testRefreshReloadsTheBinarioWidgetOnlyAfterReadingCache() {
        var events: [String] = []

        RefreshBinarioAction.requestTimelineReload(
            loadSnapshot: {
                events.append("load")
                return .unavailable
            },
            reloadTimeline: { kind in events.append("reload:\(kind)") }
        )

        XCTAssertEqual(events, ["load", "reload:\(BinarioWatchWidgetConfiguration.kind)"])
    }

    func testProgrammedStateOrNatureIsScheduledWhateverTheFreshness() throws {
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: fixtureData()) as? [String: Any])
        func decoded(nature: String, state: String, freshness: String) throws -> BinarioWatchSnapshot {
            var variant = object
            variant["provenanceDataNature"] = nature
            variant["operationalState"] = state
            variant["freshness"] = freshness
            return try XCTUnwrap(BinarioWatchSnapshot.decodeContract(
                try JSONSerialization.data(withJSONObject: variant),
                nowEpochMillis: fixtureNow
            ))
        }

        // The Companion and Android producers send PROGRAMMED with FRESH: still not realtime.
        XCTAssertTrue(try decoded(nature: "PROGRAMMED", state: "PROGRAMMED", freshness: "FRESH").isScheduledOnly)
        XCTAssertTrue(try decoded(nature: "UNKNOWN", state: "PROGRAMMED", freshness: "FRESH").isScheduledOnly)
        XCTAssertTrue(try decoded(nature: "PROGRAMMED", state: "UNKNOWN", freshness: "AGING").isScheduledOnly)
        XCTAssertFalse(try decoded(nature: "OPERATIONAL", state: "OPERATIONAL", freshness: "FRESH").isScheduledOnly)
        let scheduled = try decoded(nature: "PROGRAMMED", state: "PROGRAMMED", freshness: "FRESH")
        XCTAssertEqual(scheduled.displayFreshnessLabel, WatchLocalization.text("watch.freshness.scheduled"))
    }

    func testDeparturesResolveInRomeAfterGenerationAcrossMidnight() throws {
        let generatedAt = try rome(2026, 10, 6, 23, 40)
        let departures = WatchDepartureClock.departures(
            of: ["23:50", "23:50", "00:10", "8:05", "25:00", "0a:10"],
            after: generatedAt.addingTimeInterval(-60)
        )

        XCTAssertEqual(departures[0], try rome(2026, 10, 6, 23, 50))
        // Two trains in the same minute share the day; after midnight is the next day.
        XCTAssertEqual(departures[1], try rome(2026, 10, 6, 23, 50))
        XCTAssertEqual(departures[2], try rome(2026, 10, 7, 0, 10))
        XCTAssertEqual(Array(departures[3...]), [nil, nil, nil])
    }

    func testRevalidationDropsTrainsThatLeftAndExpiresToUnavailable() throws {
        let snapshot = try programmedSnapshot(times: ["23:50", "00:10"])

        let before = snapshot.revalidated(at: try rome(2026, 10, 6, 23, 45))
        XCTAssertEqual(before.services.map(\.expectedDeparture), ["23:50", "00:10"])
        XCTAssertEqual(before.nextDeparture, try rome(2026, 10, 6, 23, 50))

        let afterFirst = snapshot.revalidated(at: try rome(2026, 10, 6, 23, 50))
        XCTAssertEqual(afterFirst.services.map(\.expectedDeparture), ["00:10"])
        XCTAssertEqual(afterFirst.nextDeparture, try rome(2026, 10, 7, 0, 10))
        XCTAssertEqual(afterFirst.routeLabel, snapshot.routeLabel)
        XCTAssertTrue(afterFirst.isScheduledOnly)

        XCTAssertTrue(snapshot.revalidated(at: try rome(2026, 10, 7, 0, 11)).services.isEmpty)
        let expired = snapshot.revalidated(at: try rome(2026, 10, 7, 0, 12))
        XCTAssertEqual(expired.freshness, .unavailable)
        XCTAssertEqual(expired.operationalState, .unavailable)
        XCTAssertTrue(expired.services.isEmpty)

        // A time that cannot be read is never dropped as departed: only the expiry removes it.
        let unreadable = try programmedSnapshot(times: ["--:--"])
        XCTAssertEqual(unreadable.revalidated(at: try rome(2026, 10, 7, 0, 11)).services.count, 1)
        XCTAssertNil(unreadable.nextDeparture)
    }

    func testWidgetTimelineHasAnEntryPerDepartureAndEndsUnavailableAtExpiry() throws {
        let snapshot = try programmedSnapshot(times: ["23:50", "00:10"])
        let now = try rome(2026, 10, 6, 23, 45)

        let timeline = snapshot.timeline(from: now)

        XCTAssertEqual(timeline.map { $0.date }, [
            now,
            try rome(2026, 10, 6, 23, 50),
            try rome(2026, 10, 7, 0, 10),
            try rome(2026, 10, 7, 0, 12),
        ])
        XCTAssertEqual(timeline.map { $0.snapshot.services.first?.expectedDeparture }, ["23:50", "00:10", nil, nil])
        XCTAssertEqual(timeline.last?.snapshot.freshness, .unavailable)
        XCTAssertEqual(snapshot.nextChange(after: now), try rome(2026, 10, 6, 23, 50))

        // Trains in the same minute make one entry; past departures make none.
        let sameMinute = try programmedSnapshot(times: ["23:50", "23:50"])
        XCTAssertEqual(sameMinute.timelineDates(from: try rome(2026, 10, 6, 23, 55)), [
            try rome(2026, 10, 6, 23, 55),
            try rome(2026, 10, 7, 0, 12),
        ])
        let expiredNow = try rome(2026, 10, 7, 1, 0)
        XCTAssertEqual(snapshot.timelineDates(from: expiredNow), [expiredNow])
        XCTAssertNil(snapshot.nextChange(after: expiredNow))
    }

    func testLiveValuesGiveWayToTheProgrammedTimesWhenTheirWindowEnds() throws {
        // Generated at 08:00: the 07:50 train is 30 minutes late, the 08:40 one a minute late.
        let snapshot = try liveSnapshot(
            [("R 1", "07:50", "08:20", 30, "2"), ("R 2", "08:40", "08:41", 1, "4")],
            expiresAt: try rome(2026, 10, 6, 8, 42)
        )
        XCTAssertEqual(snapshot.liveUntil, try rome(2026, 10, 6, 8, 15))

        let live = snapshot.revalidated(at: try rome(2026, 10, 6, 8, 14))
        XCTAssertEqual(live, snapshot)

        // From 08:15 the programmed times: the 07:50 had left by then, the 08:40 is shown as programmed.
        let programmed = snapshot.revalidated(at: try rome(2026, 10, 6, 8, 15))
        XCTAssertEqual(programmed.operationalState, .programmed)
        XCTAssertEqual(programmed.provenanceDataNature, .programmed)
        XCTAssertEqual(programmed.confidence, .staticOnly)
        XCTAssertTrue(programmed.isScheduledOnly)
        XCTAssertEqual(programmed.displayFreshnessLabel, WatchLocalization.text("watch.freshness.scheduled"))
        XCTAssertEqual(programmed.notice, WatchLocalization.text("watch.notice.programmed"))
        XCTAssertEqual(programmed.providerName, "")
        XCTAssertEqual(programmed.expiresAtEpochMillis, snapshot.expiresAtEpochMillis)
        XCTAssertEqual(programmed.services.map(\.number), ["R 2"])
        let service = try XCTUnwrap(programmed.services.first)
        XCTAssertEqual(service.expectedDeparture, "08:40")
        XCTAssertEqual(service.delayMinutes, 0)
        XCTAssertNil(service.platform)
        XCTAssertEqual(service.displayStatus, WatchLocalization.text("watch.serviceStatus.programmed"))
        XCTAssertEqual(programmed.nextDeparture, try rome(2026, 10, 6, 8, 40))

        // The widget and the app switch at 08:15 without a new snapshot, then follow the programmed times.
        let now = try rome(2026, 10, 6, 8, 0)
        XCTAssertEqual(snapshot.timelineDates(from: now), [
            now, try rome(2026, 10, 6, 8, 15), try rome(2026, 10, 6, 8, 40), try rome(2026, 10, 6, 8, 42),
        ])
        XCTAssertEqual(snapshot.nextChange(after: now), try rome(2026, 10, 6, 8, 15))
        XCTAssertEqual(snapshot.timeline(from: now).map { $0.snapshot.operationalState }, [.operational, .programmed, .programmed, .unavailable])
        XCTAssertEqual(snapshot.timeline(from: now).map { $0.snapshot.services.count }, [2, 1, 0, 0])
    }

    func testTheProgrammedTimesKeepTheirOwnOrderAndProgrammedDataStaysAsItIs() throws {
        // The 08:25 runs 6 minutes late, after the 08:30: the iPhone lists them as they leave.
        let snapshot = try liveSnapshot(
            [("R 3", "08:30", "08:30", 0, "1"), ("R 4", "08:25", "08:31", 6, "5")],
            expiresAt: try rome(2026, 10, 6, 8, 32)
        )
        let programmed = snapshot.programmedFallback()
        XCTAssertEqual(programmed.services.map(\.number), ["R 4", "R 3"])
        XCTAssertEqual(programmed.services.map(\.expectedDeparture), ["08:25", "08:30"])

        let timetable = try programmedSnapshot(times: ["23:50", "00:10"])
        XCTAssertNil(timetable.liveUntil)
        XCTAssertEqual(timetable.programmedFallback(), timetable)
    }

    func testALiveSnapshotFromAnEarlierIPhoneEndsWithItsLiveValues() throws {
        // Valid for the 15 minutes alone: unavailable at their end, as before.
        let snapshot = try liveSnapshot([("R 1", "08:40", "08:40", 0, "4")], expiresAt: try rome(2026, 10, 6, 8, 15))
        let now = try rome(2026, 10, 6, 8, 0)
        XCTAssertEqual(snapshot.timelineDates(from: now), [now, try rome(2026, 10, 6, 8, 15)])
        XCTAssertEqual(snapshot.revalidated(at: try rome(2026, 10, 6, 8, 15)).operationalState, .unavailable)
    }

    @MainActor
    func testStoreShowsTheProgrammedTimesWhenTheLiveValuesEnd() throws {
        let clock = TestClock(now: try rome(2026, 10, 6, 8, 10))
        let snapshot = try liveSnapshot([("R 2", "08:40", "08:41", 1, "4")], expiresAt: try rome(2026, 10, 6, 8, 42))
        let store = WatchSnapshotStore(snapshot: snapshot, clock: { clock.now })
        XCTAssertEqual(store.snapshot.operationalState, .operational)
        XCTAssertEqual(store.snapshot.services.first?.expectedDeparture, "08:41")

        clock.now = try rome(2026, 10, 6, 8, 16)
        store.revalidate()
        XCTAssertEqual(store.snapshot.operationalState, .programmed)
        XCTAssertEqual(store.snapshot.services.first?.expectedDeparture, "08:40")
    }

    func testWithoutASnapshotTheWidgetShowsUnavailableOnceAndWaitsForTheIPhone() throws {
        let now = try rome(2026, 10, 6, 23, 45)
        let empty = WatchWidgetTimeline.plan(for: nil, from: now)
        XCTAssertEqual(empty.entries.map { $0.date }, [now])
        XCTAssertEqual(empty.entries.first?.snapshot.operationalState, .unavailable)
        XCTAssertFalse(empty.reloadsAtEnd, "no reload every 15 minutes for nothing: a new snapshot reloads the timeline")

        let snapshot = try programmedSnapshot(times: ["23:50", "00:10"])
        let planned = WatchWidgetTimeline.plan(for: snapshot, from: now)
        XCTAssertEqual(planned.entries.map { $0.date }, snapshot.timeline(from: now).map { $0.date })
        XCTAssertTrue(planned.reloadsAtEnd)
    }

    @MainActor
    func testStoreRevalidatesTheCachedSnapshotAgainstTheClock() throws {
        let clock = TestClock(now: try rome(2026, 10, 6, 23, 45))
        let store = WatchSnapshotStore(snapshot: try programmedSnapshot(times: ["23:50", "00:10"]), clock: { clock.now })
        XCTAssertEqual(store.snapshot.services.count, 2)

        // Back from the wrist-down suspension: the 23:50 train has left.
        clock.now = try rome(2026, 10, 6, 23, 55)
        store.revalidate()
        XCTAssertEqual(store.snapshot.services.map(\.expectedDeparture), ["00:10"])

        clock.now = try rome(2026, 10, 7, 0, 13)
        store.revalidate()
        XCTAssertEqual(store.snapshot.freshness, .unavailable)
        XCTAssertTrue(store.snapshot.services.isEmpty)
    }

    @MainActor
    func testRefreshTellsWhetherTheIPhoneReceivedTheRequest() async {
        let reachable = StubPhoneRefresher(outcome: .sent)
        let store = WatchSnapshotStore(snapshot: .unavailable, refresher: reachable)
        await store.requestPhoneRefresh()
        XCTAssertEqual(store.refreshFeedback, .sent)
        XCTAssertEqual(reachable.requests, 1)

        let unreachable = WatchSnapshotStore(snapshot: .unavailable, refresher: StubPhoneRefresher(outcome: .unreachable))
        await unreachable.requestPhoneRefresh()
        XCTAssertEqual(unreachable.refreshFeedback, .unreachable)

        let withoutPhone = WatchSnapshotStore(snapshot: .unavailable)
        await withoutPhone.requestPhoneRefresh()
        XCTAssertEqual(withoutPhone.refreshFeedback, .unreachable)

        store.sceneDidBecomeActive()
        XCTAssertEqual(store.refreshFeedback, .idle)
    }

    @MainActor
    func testOpeningWithNothingToShowAsksTheIPhoneOnceWithoutFeedback() async throws {
        WatchSnapshotStorage.clear()
        let refresher = StubPhoneRefresher(outcome: .sent)
        let empty = WatchSnapshotStore(snapshot: .unavailable, refresher: refresher)
        empty.sceneDidBecomeActive()
        empty.sceneDidBecomeActive()
        await empty.automaticRequestFinished()
        XCTAssertEqual(refresher.requests, 1, "one request at a time")
        XCTAssertEqual(empty.refreshFeedback, .idle, "the automatic request shows no feedback")
        empty.sceneDidBecomeActive()
        await empty.automaticRequestFinished()
        XCTAssertEqual(refresher.requests, 2, "a later activation asks again")

        // Without a paired iPhone nothing is sent.
        let alone = WatchSnapshotStore(snapshot: .unavailable)
        alone.sceneDidBecomeActive()
        await alone.automaticRequestFinished()
        XCTAssertEqual(alone.refreshFeedback, .idle)
    }

    /// A programmed snapshot generated at 23:40 Rome time that expires two minutes after its last
    /// listed train, as the iPhone producer publishes it.
    private func programmedSnapshot(times: [String]) throws -> BinarioWatchSnapshot {
        let generatedAt = Int64(try rome(2026, 10, 6, 23, 40).timeIntervalSince1970 * 1_000)
        let expiresAt = Int64(try rome(2026, 10, 7, 0, 12).timeIntervalSince1970 * 1_000)
        return BinarioWatchSnapshot(
            schemaVersion: 1,
            generatedAtEpochMillis: generatedAt,
            expiresAtEpochMillis: expiresAt,
            ttlMillis: expiresAt - generatedAt,
            provenanceProducer: .iosApp,
            provenanceDataNature: .programmed,
            provenanceSourceLabel: "Official GTFS",
            provenanceObservedAtEpochMillis: generatedAt - 86_400_000,
            operationalState: .programmed,
            freshness: .unknown,
            confidence: .staticOnly,
            routeId: "S08409--S09218",
            routeLabel: "Roma Termini → Napoli Centrale",
            direction: .outbound,
            notice: nil,
            services: times.enumerated().map { index, time in
                WatchService(
                    number: "R \(index + 1)",
                    operatorName: "Trenitalia",
                    category: "Regionale",
                    destination: "Napoli Centrale",
                    scheduledDeparture: time,
                    expectedDeparture: time,
                    platform: nil,
                    delayMinutes: 0,
                    status: "PROGRAMMED"
                )
            }
        )
    }

    /// An operational snapshot generated at 08:00 Rome time, its trains as (number, scheduled,
    /// expected, delay, platform) in the order they leave, as the iPhone lists them.
    private func liveSnapshot(_ trains: [(String, String, String, Int, String)], expiresAt: Date) throws -> BinarioWatchSnapshot {
        let generatedAt = Int64(try rome(2026, 10, 6, 8, 0).timeIntervalSince1970 * 1_000)
        let expires = Int64(expiresAt.timeIntervalSince1970 * 1_000)
        return BinarioWatchSnapshot(
            schemaVersion: 1,
            generatedAtEpochMillis: generatedAt,
            expiresAtEpochMillis: expires,
            ttlMillis: expires - generatedAt,
            provenanceProducer: .iosApp,
            provenanceDataNature: .operational,
            provenanceSourceLabel: "Fonte primaria dell’operatore",
            provenanceObservedAtEpochMillis: generatedAt,
            operationalState: .operational,
            freshness: .fresh,
            confidence: .operatorPrimary,
            routeId: "S01700--S01529",
            routeLabel: "Milano Centrale → Bergamo",
            direction: .outbound,
            notice: nil,
            services: trains.map { train in
                let (number, scheduled, expected, delay, platform) = train
                return WatchService(
                    number: number,
                    operatorName: "Trenord",
                    category: "R",
                    destination: "Bergamo",
                    scheduledDeparture: scheduled,
                    expectedDeparture: expected,
                    platform: platform,
                    delayMinutes: delay,
                    status: delay > 0 ? "In ritardo" : "In orario"
                )
            }
        )
    }

    private func rome(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int) throws -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "Europe/Rome"))
        return try XCTUnwrap(calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute)))
    }

    private func fixtureData(_ name: String = "ios-to-apple-watch-v1") throws -> Data {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: "json"))
        return try Data(contentsOf: url)
    }
}

@MainActor
private final class TestClock {
    var now: Date

    init(now: Date) {
        self.now = now
    }
}

@MainActor
private final class StubPhoneRefresher: PhoneRefreshRequesting {
    let outcome: PhoneRefreshOutcome
    private(set) var requests = 0

    init(outcome: PhoneRefreshOutcome) {
        self.outcome = outcome
    }

    func requestRefresh() async -> PhoneRefreshOutcome {
        requests += 1
        return outcome
    }
}
