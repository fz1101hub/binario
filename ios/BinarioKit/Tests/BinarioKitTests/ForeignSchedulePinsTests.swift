import Foundation
import XCTest
@testable import BinarioKit

/// The pins file (`binario_gtfs_foreign_packs_v1.json`): a synthetic sample in the shared schema,
/// one pack pinned (ÖBB) and two bundled whole, as the licence gate leaves them today.
final class ForeignSchedulePinsTests: XCTestCase {
    func testTheSampleDecodesWithOnlyThePinnedPackDownloadable() throws {
        let pins = try ForeignSchedulePins(data: Data(LiveFixtures.text("foreign_packs_v1.sample.json").utf8))

        XCTAssertEqual(pins.radiusKm, 35)
        XCTAssertEqual(pins.packs.map(\.sourceID), ["ch-otd-gtfs", "oebb-gtfs", "sncf-gtfs"])
        XCTAssertEqual(pins.packs.map(\.country), ["CH", "AT", "FR"])
        XCTAssertEqual(pins.downloadablePacks.map(\.sourceID), ["oebb-gtfs"])
        XCTAssertEqual(pins.packs.map(\.borderOnly), [false, true, false])
        XCTAssertNil(pins.pack("ch-otd-gtfs")?.ios)

        let oebb = try XCTUnwrap(pins.pack("oebb-gtfs"))
        let artifact = try XCTUnwrap(oebb.ios)
        XCTAssertEqual(artifact.tag, "gtfs-ios-oebb-gtfs-" + artifact.shortDigest)
        XCTAssertEqual(
            artifact.url.absoluteString,
            ForeignSchedulePins.releaseDownloadPrefix + artifact.tag + "/oebb-gtfs.sqlite.zlib"
        )
        XCTAssertEqual(artifact.sizeBytes, 1_400_000)
        XCTAssertEqual(artifact.databaseSizeBytes, 5_000_000)
        XCTAssertEqual(oebb.lastServiceDay, 20261212)
        XCTAssertTrue(oebb.needsFullTimetable("TEST-WIEN"))
        XCTAssertFalse(oebb.needsFullTimetable("TEST-INNSBRUCK"), "a zone station is complete in the border part")
        XCTAssertFalse(oebb.needsFullTimetable("TEST-ROMA"), "a station the pack never serves")
        XCTAssertFalse(oebb.hasEnded(before: 20261212))
        XCTAssertTrue(oebb.hasEnded(before: 20261213))
        XCTAssertEqual(ForeignSchedulePins.romeDay(oebb.lastServiceDate), 20261212)
    }

    func testTheHostsAndThePrefixAreTheProjectsReleases() {
        XCTAssertEqual(ForeignSchedulePins.releaseDownloadPrefix, "https://github.com/fz1101hub/binario/releases/download/")
        XCTAssertEqual(ForeignSchedulePins.releaseHost, "github.com")
        XCTAssertEqual(ForeignSchedulePins.githubReleaseDownloadHost, "release-assets.githubusercontent.com")
        XCTAssertEqual(ForeignSchedulePins.githubLegacyReleaseDownloadHost, "objects.githubusercontent.com")
    }

    func testAPinThatBreaksARuleInvalidatesTheWholeFile() throws {
        let mutations: [(String, (inout [String: Any]) -> Void)] = [
            ("schema", { $0["schemaVersion"] = 2 }),
            ("repository", { $0["repository"] = "someone/else" }),
            ("tag", { Self.editIOS(&$0) { $0["tag"] = "gtfs-ios-oebb-gtfs-000000000000" } }),
            ("android tag prefix", { Self.editIOS(&$0) { ios in
                let tag = (ios["tag"] as? String ?? "").replacingOccurrences(of: "gtfs-ios-", with: "gtfs-full-")
                ios["tag"] = tag
            } }),
            ("url host", { Self.editIOS(&$0) { ios in
                let url = (ios["url"] as? String ?? "").replacingOccurrences(of: "github.com", with: "example.org")
                ios["url"] = url
            } }),
            ("url repository", { Self.editIOS(&$0) { ios in
                let url = (ios["url"] as? String ?? "").replacingOccurrences(of: "fz1101hub/binario", with: "fz1101hub/other")
                ios["url"] = url
            } }),
            ("url query", { Self.editIOS(&$0) { ios in ios["url"] = (ios["url"] as? String ?? "") + "?x=1" } }),
            ("asset", { Self.editIOS(&$0) { $0["asset"] = "../oebb-gtfs.sqlite.zlib" } }),
            ("sha", { Self.editIOS(&$0) { $0["sha256"] = "ABC" } }),
            ("size", { Self.editIOS(&$0) { $0["sizeBytes"] = 0 } }),
            ("too large", { Self.editIOS(&$0) { $0["sizeBytes"] = BundledGTFSScheduleInstaller.maximumCompressedBytes + 1 } }),
            ("user version", { Self.editIOS(&$0) { ios in
                var database = ios["database"] as? [String: Any] ?? [:]
                database["userVersion"] = 1
                ios["database"] = database
            } }),
            ("application id", { Self.editIOS(&$0) { ios in
                var database = ios["database"] as? [String: Any] ?? [:]
                database["applicationId"] = 1
                ios["database"] = database
            } }),
            ("zone outside served", { Self.editPack(&$0, index: 1) { $0["zoneStationIds"] = ["TEST-BRENNERO", "TEST-MILANO"] } }),
            ("pinned but whole", { Self.editPack(&$0, index: 1) { $0["borderOnly"] = false } }),
            ("subset without pin", { Self.editPack(&$0, index: 0) { $0["borderOnly"] = true } }),
            ("duplicate source", { Self.editPack(&$0, index: 2) { $0["sourceId"] = "oebb-gtfs" } }),
            ("source id", { Self.editPack(&$0, index: 0) { $0["sourceId"] = "CH OTD" } }),
            ("country", { Self.editPack(&$0, index: 0) { $0["country"] = "ch" } }),
            ("date", { Self.editPack(&$0, index: 1) { $0["lastServiceDate"] = "2026-02-30" } }),
            ("border zone", { $0["borderZone"] = ["radiusKm": 0] }),
            ("missing borderOnly", { Self.editPack(&$0, index: 0) { $0["borderOnly"] = nil } }),
        ]
        let sample = try sampleObject()
        XCTAssertNoThrow(try ForeignSchedulePins(data: JSONSerialization.data(withJSONObject: sample)))
        for (name, mutate) in mutations {
            var object = sample
            mutate(&object)
            let data = try JSONSerialization.data(withJSONObject: object)
            XCTAssertThrowsError(try ForeignSchedulePins(data: data), name) { error in
                XCTAssertEqual(error as? ForeignSchedulePinsError, .invalid, name)
            }
        }
        XCTAssertThrowsError(try ForeignSchedulePins(data: Data("not json".utf8)))
        XCTAssertThrowsError(try ForeignSchedulePins(data: Data(repeating: 0x20, count: ForeignSchedulePins.maximumBytes + 1)))
    }

    func testNoPinnedPackAndAnAbsentFileLeaveNothingToDownload() throws {
        var object = try sampleObject()
        Self.editPack(&object, index: 1) { pack in
            pack["borderOnly"] = false
            pack["ios"] = NSNull()
            pack["android"] = NSNull()
        }
        let pins = try ForeignSchedulePins(data: JSONSerialization.data(withJSONObject: object))
        XCTAssertEqual(pins.downloadablePacks, [])
        XCTAssertNil(ForeignSchedulePins.load(url: nil))
        XCTAssertNil(ForeignSchedulePins.load(url: FileManager.default.temporaryDirectory.appendingPathComponent("absent-\(UUID().uuidString).json")))
    }

    func testTheSharedDigestMatchesTheStandardVectors() {
        XCTAssertEqual(SHA256Digest.hex(Data()), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(SHA256Digest.hex(Data("abc".utf8)), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(
            SHA256Digest.hex(Data("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".utf8)),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
        )
        XCTAssertEqual(BundledGTFSScheduleInstaller.sha256Hex(Data("abc".utf8)), SHA256Digest.hex(Data("abc".utf8)))
        XCTAssertTrue(SHA256Digest.isHex(String(repeating: "a", count: 64)))
        XCTAssertFalse(SHA256Digest.isHex(String(repeating: "A", count: 64)))
        XCTAssertFalse(SHA256Digest.isHex(String(repeating: "a", count: 63)))
    }

    private func sampleObject() throws -> [String: Any] {
        let data = Data(try LiveFixtures.text("foreign_packs_v1.sample.json").utf8)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private static func editPack(_ object: inout [String: Any], index: Int, _ edit: (inout [String: Any]) -> Void) {
        var packs = object["packs"] as? [[String: Any]] ?? []
        guard packs.indices.contains(index) else { return }
        var pack = packs[index]
        edit(&pack)
        packs[index] = pack
        object["packs"] = packs
    }

    private static func editIOS(_ object: inout [String: Any], _ edit: (inout [String: Any]) -> Void) {
        editPack(&object, index: 1) { pack in
            var ios = pack["ios"] as? [String: Any] ?? [:]
            edit(&ios)
            pack["ios"] = ios
        }
    }
}
