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

    private func rome(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int) throws -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "Europe/Rome"))
        return try XCTUnwrap(calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute)))
    }

    private func fixtureData() throws -> Data {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "ios-to-apple-watch-v1", withExtension: "json"))
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
