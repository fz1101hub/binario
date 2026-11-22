import Foundation
import XCTest
@testable import BinarioKit

/// The download of a full foreign timetable through a fake transport: nothing leaves the machine.
final class ForeignScheduleStoreTests: XCTestCase {
    private let clock = LiveTestClock(Date(timeIntervalSince1970: 1_711_965_600)) // 2024-04-01 12:00 Rome
    private let assetLocation = "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r&sv=2018&sig=abc"
    /// A new folder for every test (XCTest makes one instance per test), removed by `cleanUp`.
    private let root = FileManager.default.temporaryDirectory
        .appendingPathComponent("BinarioForeignStore-\(UUID().uuidString)", isDirectory: true)
    private var directory: URL {
        root.appendingPathComponent(ForeignScheduleStore.directoryName, isDirectory: true)
    }

    func testADownloadFollowsGitHubsRedirectAndInstallsTheVerifiedIndex() async throws {
        defer { cleanUp() }
        let artifact = try makeArtifact()
        let location = assetLocation
        let transport = FakeLiveTransport { request in
            request.url.host == "github.com"
                ? FakeLiveTransport.redirect(location, status: 302)
                : .response(status: 200, headers: [:], body: artifact.compressed)
        }
        let store = makeStore(artifact.pins, transport)

        let outcome = await store.download("oebb-gtfs")

        XCTAssertEqual(outcome, .installed)
        let pinned = try XCTUnwrap(artifact.pins.pack("oebb-gtfs")?.ios)
        XCTAssertEqual(transport.paths, [pinned.url.absoluteString, location])
        for request in transport.requests {
            XCTAssertEqual(request.headers["User-Agent"], LiveSourceUserAgent.general)
            XCTAssertEqual(request.headers["Accept"], "application/octet-stream")
            XCTAssertEqual(request.maximumBytes, artifact.compressed.count, "the cap is the pinned size")
            XCTAssertEqual(request.url.scheme, "https")
        }
        let installed = await store.reconcile()
        let url = try XCTUnwrap(installed["oebb-gtfs"])
        XCTAssertEqual(url.lastPathComponent, "oebb-gtfs-\(pinned.shortDigest).sqlite")
        XCTAssertEqual(try Data(contentsOf: url), artifact.database)
        let statuses = await store.statuses()
        XCTAssertEqual(statuses, ["oebb-gtfs": .installed])
        let generation = await store.generation
        XCTAssertEqual(generation, 1)
        #if os(iOS) || os(macOS) || os(watchOS) || os(tvOS) || os(visionOS)
        XCTAssertTrue(BundledGTFSScheduleInstaller.isExcludedFromBackupOnDisk(url))
        #endif

        // GitHub's other download host is followed as well.
        let legacy = FakeLiveTransport { request in
            request.url.host == "github.com"
                ? FakeLiveTransport.redirect("https://objects.githubusercontent.com/asset?token=x")
                : .response(status: 200, headers: [:], body: artifact.compressed)
        }
        let again = await makeStore(artifact.pins, legacy).download("oebb-gtfs")
        XCTAssertEqual(again, .installed)
        XCTAssertEqual(legacy.requests.count, 2)
    }

    func testARedirectOutsideGitHubsDownloadHostsIsNeverAsked() async throws {
        defer { cleanUp() }
        let artifact = try makeArtifact()
        for location in [
            "https://example.org/oebb-gtfs.sqlite.zlib",
            "https://github.com.example.org/asset",
            "https://release-assets.githubusercontent.com.example.org/asset",
            "https://release-assets.githubusercontent.com:8443/asset",
            "https://user@release-assets.githubusercontent.com/asset",
            "https://raw.githubusercontent.com/fz1101hub/binario/main/asset",
            "https://release-assets.githubusercontent.com/asset#fragment",
            "",
        ] {
            let transport = FakeLiveTransport { request in
                request.url.host == "github.com"
                    ? FakeLiveTransport.redirect(location, status: 302)
                    : .response(status: 200, headers: [:], body: artifact.compressed)
            }
            let outcome = await makeStore(artifact.pins, transport).download("oebb-gtfs")
            XCTAssertEqual(outcome, .failed(.network), location)
            XCTAssertEqual(transport.requests.count, 1, location)
        }
        XCTAssertEqual(sqliteFiles(), [])
    }

    func testTheBodyIsCappedAtThePinnedSize() async throws {
        defer { cleanUp() }
        let artifact = try makeArtifact()
        let larger = artifact.compressed + Data([0])
        let transport = FakeLiveTransport { _ in .response(status: 200, headers: [:], body: larger) }

        let outcome = await makeStore(artifact.pins, transport).download("oebb-gtfs")

        XCTAssertEqual(outcome, .failed(.invalid))
        XCTAssertEqual(transport.requests.first?.maximumBytes, artifact.compressed.count)
        XCTAssertEqual(sqliteFiles(), [])
    }

    func testAFileThatIsNotThePinnedOneIsNeverInstalled() async throws {
        defer { cleanUp() }
        let artifact = try makeArtifact()
        var tampered = artifact.compressed
        tampered[tampered.startIndex + tampered.count / 2] ^= 0xff
        let tamperedBody = tampered
        let transport = FakeLiveTransport { _ in .response(status: 200, headers: [:], body: tamperedBody) }

        let outcome = await makeStore(artifact.pins, transport).download("oebb-gtfs")

        XCTAssertEqual(outcome, .failed(.invalid))
        XCTAssertEqual(sqliteFiles(), [])
        XCTAssertEqual(hiddenFiles(), [], "no staged copy is left behind")

        // The right digests of an index holding another pack: refused by the single-pack check.
        let other = try makeArtifact(packInDatabase: "sncf-gtfs")
        let otherTransport = FakeLiveTransport { _ in .response(status: 200, headers: [:], body: other.compressed) }
        let otherOutcome = await makeStore(other.pins, otherTransport).download("oebb-gtfs")
        XCTAssertEqual(otherOutcome, .failed(.invalid))
        XCTAssertEqual(sqliteFiles(), [])
    }

    func testARefusalStopsAtOnceAndPausesTheHostWithoutRetry() async throws {
        defer { cleanUp() }
        let artifact = try makeArtifact()
        let refused = FakeLiveTransport { _ in FakeLiveTransport.status(403) }
        let store = makeStore(artifact.pins, refused)

        let first = await store.download("oebb-gtfs")
        let until = clock.now().addingTimeInterval(ForeignScheduleStore.refusalPause)
        XCTAssertEqual(first, .failed(.refused(until: until)))
        XCTAssertEqual(refused.requests.count, 1, "the first 403 ends the download")

        clock.advance(60)
        let second = await store.download("oebb-gtfs")
        XCTAssertEqual(second, .failed(.refused(until: until)))
        XCTAssertEqual(refused.requests.count, 1, "nothing is sent during the pause")
        let paused = await store.refusedUntil()
        XCTAssertEqual(paused, until)

        clock.advance(ForeignScheduleStore.refusalPause)
        _ = await store.download("oebb-gtfs")
        XCTAssertEqual(refused.requests.count, 2, "after the pause a new tap asks again")

        let limited = FakeLiveTransport { _ in FakeLiveTransport.status(429, headers: ["retry-after": "7200"]) }
        let limitedOutcome = await makeStore(artifact.pins, limited).download("oebb-gtfs")
        XCTAssertEqual(limitedOutcome, .failed(.refused(until: clock.now().addingTimeInterval(7_200))))
        XCTAssertEqual(limited.requests.count, 1)
    }

    func testAFailureIsNeverRetriedByItself() async throws {
        defer { cleanUp() }
        let artifact = try makeArtifact()
        for outcome in [
            FakeLiveTransport.status(503),
            FakeLiveTransport.status(404),
            LiveTransportOutcome.failure(.offline),
            LiveTransportOutcome.failure(.timedOut),
        ] {
            let transport = FakeLiveTransport { _ in outcome }
            let result = await makeStore(artifact.pins, transport).download("oebb-gtfs")
            XCTAssertEqual(result, .failed(.network))
            XCTAssertEqual(transport.requests.count, 1)
        }
        XCTAssertEqual(sqliteFiles(), [])
    }

    func testOnlyAPinnedTimetableStillInServiceIsDownloaded() async throws {
        defer { cleanUp() }
        let artifact = try makeArtifact()
        let transport = FakeLiveTransport { _ in .response(status: 200, headers: [:], body: artifact.compressed) }
        let store = makeStore(artifact.pins, transport)

        let unknown = await store.download("ch-otd-gtfs")
        XCTAssertEqual(unknown, .failed(.notOffered))
        clock.advance(366 * 86_400)
        let ended = await store.download("oebb-gtfs")
        XCTAssertEqual(ended, .failed(.notOffered))
        XCTAssertEqual(transport.requests.count, 0)

        let sample = try ForeignSchedulePins(data: Data(LiveFixtures.text("foreign_packs_v1.sample.json").utf8))
        let wholePack = await makeStore(sample, transport).download("ch-otd-gtfs")
        XCTAssertEqual(wholePack, .failed(.notOffered), "a pack bundled whole has nothing to download")
        XCTAssertEqual(transport.requests.count, 0)
    }

    func testCancellingTheTapStopsTheTransfer() async throws {
        defer { cleanUp() }
        let artifact = try makeArtifact()
        let calls = LiveCounter()
        let compressed = artifact.compressed
        // The first transfer never answers; a later tap gets the file.
        let stalled = FakeLiveTransport { _ in
            calls.increment()
            if calls.value == 1 { return await FakeLiveTransport.stall() }
            return .response(status: 200, headers: [:], body: compressed)
        }
        let store = makeStore(artifact.pins, stalled)

        let task = Task { await store.download("oebb-gtfs") }
        for _ in 0..<200 where stalled.requests.isEmpty {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        task.cancel()
        let outcome = await task.value

        XCTAssertEqual(outcome, .failed(.cancelled))
        XCTAssertEqual(sqliteFiles(), [])
        let again = await store.download("oebb-gtfs")
        XCTAssertEqual(again, .installed, "the store is free again after a cancellation")
        XCTAssertEqual(stalled.requests.count, 2)
    }

    func testALaunchRemovesEarlierPinsLeftoversAndUnpinnedPacks() async throws {
        defer { cleanUp() }
        let first = try makeArtifact()
        let firstTransport = FakeLiveTransport { _ in .response(status: 200, headers: [:], body: first.compressed) }
        let installed = await makeStore(first.pins, firstTransport).download("oebb-gtfs")
        XCTAssertEqual(installed, .installed)
        let staged = directory.appendingPathComponent(".binario-foreign-\(UUID().uuidString).sqlite")
        try Data("staged".utf8).write(to: staged)
        let stranger = directory.appendingPathComponent("unknown-gtfs-0123456789ab.sqlite")
        try Data("other".utf8).write(to: stranger)

        // An app update pins another artifact of the same pack.
        let updated = try makeArtifact(extraTrip: true)
        XCTAssertNotEqual(updated.pins.pack("oebb-gtfs")?.ios?.sha256, first.pins.pack("oebb-gtfs")?.ios?.sha256)
        let transport = FakeLiveTransport { _ in .response(status: 200, headers: [:], body: updated.compressed) }
        let store = makeStore(updated.pins, transport)

        let afterUpdate = await store.reconcile()
        XCTAssertEqual(afterUpdate, [:])
        XCTAssertEqual(sqliteFiles(), [], "the earlier pin's file is deleted, nothing is downloaded")
        XCTAssertFalse(FileManager.default.fileExists(atPath: staged.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: stranger.path))
        let statuses = await store.statuses()
        XCTAssertEqual(statuses, ["oebb-gtfs": .updateAvailable])
        XCTAssertEqual(transport.requests.count, 0, "no download starts by itself")
        // A second launch still says so.
        _ = await store.reconcile()
        let stillUpdate = await store.statuses()
        XCTAssertEqual(stillUpdate, ["oebb-gtfs": .updateAvailable])

        let downloaded = await store.download("oebb-gtfs")
        XCTAssertEqual(downloaded, .installed)
        let afterDownload = await store.statuses()
        XCTAssertEqual(afterDownload, ["oebb-gtfs": .installed])

        await store.delete("oebb-gtfs")
        let afterDelete = await store.statuses()
        XCTAssertEqual(afterDelete, ["oebb-gtfs": .borderOnly])
        XCTAssertEqual(sqliteFiles(), [])
        let generation = await store.generation
        XCTAssertEqual(generation, 2)

        // Pins without any pinned pack: everything downloaded before goes.
        _ = await makeStore(first.pins, firstTransport).download("oebb-gtfs")
        let sample = try ForeignSchedulePins(data: Data(LiveFixtures.text("foreign_packs_v1.sample.json").utf8))
        var unpinned = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(LiveFixtures.text("foreign_packs_v1.sample.json").utf8)) as? [String: Any])
        var packs = try XCTUnwrap(unpinned["packs"] as? [[String: Any]])
        packs[1]["borderOnly"] = false
        packs[1]["ios"] = NSNull()
        unpinned["packs"] = packs
        let noPins = try ForeignSchedulePins(data: JSONSerialization.data(withJSONObject: unpinned))
        XCTAssertEqual(sample.downloadablePacks.count, 1)
        let none = await makeStore(noPins, transport).reconcile()
        XCTAssertEqual(none, [:])
        XCTAssertEqual(sqliteFiles(), [])
        XCTAssertEqual(markerFiles(), [])
    }

    func testFileNamesCarryTheSourceAndThePinsDigest() {
        defer { cleanUp() }
        XCTAssertEqual(ForeignScheduleStore.parse(fileName: "oebb-gtfs-8e7ed242109b.sqlite")?.sourceID, "oebb-gtfs")
        XCTAssertEqual(ForeignScheduleStore.parse(fileName: "oebb-gtfs-8e7ed242109b.sqlite")?.digest, "8e7ed242109b")
        XCTAssertNil(ForeignScheduleStore.parse(fileName: "oebb-gtfs-8E7ED242109B.sqlite"))
        XCTAssertNil(ForeignScheduleStore.parse(fileName: "oebb-gtfs.sqlite"))
        XCTAssertNil(ForeignScheduleStore.parse(fileName: "-8e7ed242109b.sqlite"))
        XCTAssertNil(ForeignScheduleStore.parse(fileName: "oebb-gtfs-8e7ed242109b.sqlite.zlib"))
    }

    // MARK: - Helpers

    private struct Artifact {
        let pins: ForeignSchedulePins
        let compressed: Data
        let database: Data
    }

    private func makeArtifact(packInDatabase: String = "oebb-gtfs", extraTrip: Bool = false) throws -> Artifact {
        let fixture = try GTFSFixtureDatabase(packs: [(id: 1, sourceID: packInDatabase, lastServiceDate: 20241231)])
        try fixture.insertStop(id: 1, pack: 1, externalID: "brennero", name: "Brennero", catalogStationID: "TEST-BRENNERO")
        try fixture.insertStop(id: 2, pack: 1, externalID: "wien", name: "Wien Hbf", catalogStationID: "TEST-WIEN")
        try fixture.insertTrip(id: 1, pack: 1, externalID: "rj-1", number: "RJ 1", calls: [
            GTFSFixtureDatabase.Call(stop: 1, seconds: 10 * 3_600),
            GTFSFixtureDatabase.Call(stop: 2, seconds: 15 * 3_600),
        ])
        if extraTrip {
            try fixture.insertTrip(id: 2, pack: 1, externalID: "rj-3", number: "RJ 3", calls: [
                GTFSFixtureDatabase.Call(stop: 1, seconds: 12 * 3_600),
                GTFSFixtureDatabase.Call(stop: 2, seconds: 17 * 3_600),
            ])
        }
        let database = try fixture.bytes()
        let compressed = try GTFSFixtureDatabase.zlibCompress(database)
        let pins = try GTFSFixtureDatabase.pins(
            served: ["TEST-BRENNERO", "TEST-INNSBRUCK", "TEST-WIEN"],
            zone: ["TEST-BRENNERO", "TEST-INNSBRUCK"],
            compressed: compressed,
            database: database
        )
        return Artifact(pins: pins, compressed: compressed, database: database)
    }

    private func cleanUp() {
        try? FileManager.default.removeItem(at: root)
    }

    private func makeStore(_ pins: ForeignSchedulePins, _ transport: FakeLiveTransport) -> ForeignScheduleStore {
        ForeignScheduleStore(directory: directory, pins: pins, transport: transport, now: clock.closure)
    }

    private func names() -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []).sorted()
    }

    private func sqliteFiles() -> [String] {
        names().filter { !$0.hasPrefix(".") && $0.hasSuffix(".sqlite") }
    }

    private func hiddenFiles() -> [String] {
        names().filter { $0.hasPrefix(".") }
    }

    private func markerFiles() -> [String] {
        names().filter { $0.hasSuffix(".update-available") }
    }
}
