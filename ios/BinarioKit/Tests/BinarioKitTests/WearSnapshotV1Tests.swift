import Foundation
import XCTest
@testable import BinarioKit

@MainActor
final class WearSnapshotV1Tests: XCTestCase {
    private let now: Int64 = 1_893_484_800_000

    func testGoldenIOSFixtureCanBeRecreatedWithExactlyTheContractKeys() throws {
        let snapshot = operationalFixture()
        let data = try WearSnapshotEncoder().encode(snapshot, nowEpochMillis: now)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let goldenData = try Data(contentsOf: repositoryRoot
            .appendingPathComponent("contracts/wear-snapshot/v1/ios-to-apple-watch-v1.json"))
        let golden = try XCTUnwrap(JSONSerialization.jsonObject(with: goldenData) as? [String: Any])

        XCTAssertEqual(Set(object.keys), Set(golden.keys))
        XCTAssertTrue(NSDictionary(dictionary: object).isEqual(to: golden))
        XCTAssertEqual(object["schemaVersion"] as? Int, 1)
        XCTAssertEqual(object["provenanceProducer"] as? String, "IOS_APP")
        XCTAssertEqual(object["serviceCount"] as? Int, 1)
        XCTAssertLessThanOrEqual(data.count, WearSnapshotV1.maximumEncodedBytes)
    }

    func testUnavailableSnapshotCarriesNoServicesAndIsShortLived() throws {
        let snapshot = WearSnapshotV1.unavailable(
            routeId: "S08409--S09218",
            routeLabel: "Roma Termini → Napoli Centrale",
            direction: .outbound,
            generatedAtEpochMillis: now,
            sourceLabel: "No authorised iOS provider",
            notice: "Realtime railway data is unavailable."
        )
        let data = try WearSnapshotEncoder().encode(snapshot, nowEpochMillis: now)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(object["operationalState"] as? String, "UNAVAILABLE")
        XCTAssertEqual(object["serviceCount"] as? Int, 0)
        XCTAssertNil(object["service0Number"])
        XCTAssertEqual(object["ttlMillis"] as? Int, 900_000)
    }

    func testEncoderRejectsWrongProducerServiceLimitTTLAndIncoherentUnavailableState() {
        XCTAssertThrowsError(try WearSnapshotEncoder().encode(
            replacing(operationalFixture(), producer: .androidApp), nowEpochMillis: now
        ))
        XCTAssertThrowsError(try WearSnapshotEncoder().encode(
            replacing(operationalFixture(), services: Array(repeating: service, count: 3)), nowEpochMillis: now
        ))
        XCTAssertThrowsError(try WearSnapshotEncoder().encode(
            replacing(operationalFixture(), ttl: 0), nowEpochMillis: now
        ))
        XCTAssertThrowsError(try WearSnapshotEncoder().encode(
            replacing(operationalFixture(), nature: .unavailable), nowEpochMillis: now
        ))
    }

    func testBootstrapActivatesOnceAndPublishesLatestValidatedContextExactlyOnceAfterActivation() throws {
        let session = FakeSession()
        let publisher = WatchSnapshotPublisher(session: session)

        publisher.bootstrap()
        publisher.bootstrap()
        let first = try publisher.publish(operationalFixture(), nowEpochMillis: now)
        let secondSnapshot = replacing(operationalFixture(), routeLabel: "Roma → Napoli · S08409--S09218")
        let second = try publisher.publish(secondSnapshot, nowEpochMillis: now)

        XCTAssertEqual(session.activationCount, 1)
        XCTAssertTrue(session.contexts.isEmpty)
        XCTAssertEqual(publisher.status, .pending)

        session.completeActivation(.activated)
        session.completeActivation(.activated)

        XCTAssertEqual(session.contexts.count, 1)
        XCTAssertEqual(session.contexts.first?[WatchSnapshotPublisher.contextKey], second)
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(publisher.status, .contextUpdated)
    }

    func testValidationFailureNeverTouchesSession() throws {
        let session = FakeSession()
        let publisher = WatchSnapshotPublisher(session: session)
        publisher.bootstrap()

        XCTAssertThrowsError(try publisher.publish(
            replacing(operationalFixture(), producer: .companion), nowEpochMillis: now
        ))
        session.completeActivation(.activated)

        XCTAssertTrue(session.contexts.isEmpty)
    }

    func testUnsupportedSessionFailsClosedAndSurfacesStatus() {
        let session = FakeSession(isSupported: false)
        let publisher = WatchSnapshotPublisher(session: session)

        publisher.bootstrap()
        XCTAssertEqual(publisher.status, .unsupported)
        XCTAssertThrowsError(try publisher.publish(operationalFixture(), nowEpochMillis: now)) { error in
            XCTAssertEqual(error as? WearSnapshotError, .unsupportedSession)
        }
        XCTAssertTrue(session.contexts.isEmpty)
    }

    func testPublicationErrorsAreSurfacedAndPendingOperationIsRetained() {
        let session = FakeSession(activationState: .activated)
        session.error = TestError.failed
        let publisher = WatchSnapshotPublisher(session: session)
        publisher.bootstrap()

        XCTAssertThrowsError(try publisher.publish(operationalFixture(), nowEpochMillis: now)) { error in
            XCTAssertEqual(error as? WearSnapshotError, .publicationFailed)
        }
        XCTAssertEqual(publisher.status, .failed)
        XCTAssertTrue(session.contexts.isEmpty)

        session.error = nil
        XCTAssertNoThrow(try publisher.clear())
        XCTAssertEqual(session.contexts, [[WatchSnapshotPublisher.clearContextKey: Data()]])
        XCTAssertEqual(publisher.status, .cleared)
    }

    func testClearSignalReplacesPendingSnapshotOutsideV1JSONContract() throws {
        let session = FakeSession()
        let publisher = WatchSnapshotPublisher(session: session)
        publisher.bootstrap()
        _ = try publisher.publish(operationalFixture(), nowEpochMillis: now)
        try publisher.clear()

        session.completeActivation(.activated)

        XCTAssertEqual(session.contexts, [[WatchSnapshotPublisher.clearContextKey: Data()]])
        XCTAssertNil(session.contexts.first?[WatchSnapshotPublisher.contextKey])
    }

    func testUnicodeSafeRouteFormatterIsDeterministicBoundedAndKeepsRouteIdentity() {
        let origin = StationIdentity(
            id: "NC001752",
            name: "Boscotrecase - Porta Di Accesso Al Cratere Del Vesuvio"
        )
        let destination = StationIdentity(
            id: "NC001477",
            name: "Morciano - Barbarano - Castrignano - Giuliano"
        )
        let routeId = SavedRoute.routeIdentifier(origin: origin, destination: destination)
        let first = WatchRouteLabelFormatter.format(origin: origin, destination: destination)
        let second = WatchRouteLabelFormatter.format(origin: origin, destination: destination)

        XCTAssertEqual(first, second)
        XCTAssertLessThanOrEqual(first.count, WearSnapshotV1.maximumRouteLabelCharacters)
        XCTAssertTrue(first.hasSuffix(routeId))
        XCTAssertTrue(first.contains("Boscotrecase"))
        XCTAssertTrue(first.contains("Morciano"))
        XCTAssertFalse(first.unicodeScalars.contains("\u{FFFD}"))
    }

    private var service: WearServiceV1 {
        WearServiceV1(
            number: "FR 9505",
            operatorName: "Trenitalia Frecce",
            category: "Freccia",
            destination: "Napoli Centrale",
            scheduled: "08:10",
            expected: "08:18",
            status: "In ritardo",
            platform: "7",
            delayMinutes: 8
        )
    }

    private func operationalFixture() -> WearSnapshotV1 {
        WearSnapshotV1(
            generatedAtEpochMillis: now,
            expiresAtEpochMillis: now + 900_000,
            ttlMillis: 900_000,
            provenanceProducer: .iosApp,
            provenanceDataNature: .operational,
            provenanceSourceLabel: "Fonte primaria del gestore dell’infrastruttura",
            provenanceObservedAtEpochMillis: now - 5_000,
            operationalState: .operational,
            freshness: .fresh,
            confidence: .infrastructurePrimary,
            routeId: "roma-termini-napoli-centrale",
            routeLabel: "Roma Termini → Napoli Centrale",
            direction: .outbound,
            notice: "Dati operativi ricevuti dall’iPhone.",
            services: [service]
        )
    }

    private func replacing(
        _ snapshot: WearSnapshotV1,
        producer: DataProducer? = nil,
        nature: DataNature? = nil,
        ttl: Int64? = nil,
        routeLabel: String? = nil,
        services: [WearServiceV1]? = nil
    ) -> WearSnapshotV1 {
        let selectedTTL = ttl ?? snapshot.ttlMillis
        return WearSnapshotV1(
            schemaVersion: snapshot.schemaVersion,
            generatedAtEpochMillis: snapshot.generatedAtEpochMillis,
            expiresAtEpochMillis: snapshot.generatedAtEpochMillis + selectedTTL,
            ttlMillis: selectedTTL,
            provenanceProducer: producer ?? snapshot.provenanceProducer,
            provenanceDataNature: nature ?? snapshot.provenanceDataNature,
            provenanceSourceLabel: snapshot.provenanceSourceLabel,
            provenanceObservedAtEpochMillis: snapshot.provenanceObservedAtEpochMillis,
            operationalState: snapshot.operationalState,
            freshness: snapshot.freshness,
            confidence: snapshot.confidence,
            routeId: snapshot.routeId,
            routeLabel: routeLabel ?? snapshot.routeLabel,
            direction: snapshot.direction,
            notice: snapshot.notice,
            services: services ?? snapshot.services
        )
    }

    private var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}

private enum TestError: Error {
    case failed
}

@MainActor
private final class FakeSession: WatchSnapshotSession {
    let isSupported: Bool
    private(set) var activationState: WatchSessionActivationState
    var activationHandler: (@MainActor (WatchSessionActivationState) -> Void)?
    var activationCount = 0
    var contexts: [[String: Data]] = []
    var error: Error?

    init(
        isSupported: Bool = true,
        activationState: WatchSessionActivationState = .notActivated
    ) {
        self.isSupported = isSupported
        self.activationState = activationState
    }

    func activate() { activationCount += 1 }

    func updateApplicationContext(_ context: [String: Data]) throws {
        if let error { throw error }
        contexts.append(context)
    }

    func completeActivation(_ state: WatchSessionActivationState) {
        activationState = state
        activationHandler?(state)
    }
}
