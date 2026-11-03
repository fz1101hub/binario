import Foundation
import XCTest
@testable import BinarioKit

final class GTFSSourcePolicyTests: XCTestCase {
    func testBundledPolicyListsEveryPackWithAnHTTPSSourceAndALicenceEntry() throws {
        let data = try Data(contentsOf: repositoryRoot
            .appendingPathComponent("ios/Resources/binario_gtfs_source_policy_v1.json"))
        let policy = try GTFSSourcePolicy(data: data)

        XCTAssertEqual(policy.sources.count, 10)
        XCTAssertTrue(policy.sources.allSatisfy { $0.sourceURL.scheme == "https" && $0.accessMode == "OPEN_DIRECT" })
        XCTAssertEqual(policy.source(id: "oebb-gtfs")?.licence, "CC BY 4.0")
        XCTAssertEqual(policy.source(id: "alto-adige-sta-gtfs")?.licence, "CC0 1.0")
        XCTAssertNotNil(policy.source(id: "sncf-gtfs"))
        XCTAssertNil(policy.source(id: "missing"))
    }

    func testPolicyRejectsUnsupportedSchemaInsecureSourcesDuplicatesAndBlankLicences() throws {
        let source: [String: Any] = [
            "sourceId": "a", "sourceUrl": "https://example.test/a.zip", "accessMode": "OPEN_DIRECT", "licence": NSNull(),
        ]
        XCTAssertNoThrow(try decode(["schemaVersion": 1, "sources": [source]]))
        XCTAssertThrowsError(try decode(["schemaVersion": 2, "sources": [source]])) { error in
            XCTAssertEqual(error as? GTFSSourcePolicyError, .unsupportedSchema)
        }
        for (key, value) in [("sourceUrl", "ftp://example.test/a.zip"), ("accessMode", "GATEWAY"), ("licence", ""), ("sourceId", "")] {
            var invalid = source
            invalid[key] = value
            XCTAssertThrowsError(try decode(["schemaVersion": 1, "sources": [invalid]]), key) { error in
                XCTAssertEqual(error as? GTFSSourcePolicyError, .invalidSource)
            }
        }
        XCTAssertThrowsError(try decode(["schemaVersion": 1, "sources": [source, source]])) { error in
            XCTAssertEqual(error as? GTFSSourcePolicyError, .invalidSource)
        }
        XCTAssertThrowsError(try decode(["schemaVersion": 1, "sources": []]))
    }

    private func decode(_ object: [String: Any]) throws -> GTFSSourcePolicy {
        try GTFSSourcePolicy(data: try JSONSerialization.data(withJSONObject: object))
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
