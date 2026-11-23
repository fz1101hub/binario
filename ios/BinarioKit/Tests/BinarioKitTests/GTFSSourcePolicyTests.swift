import Foundation
import XCTest
@testable import BinarioKit

final class GTFSSourcePolicyTests: XCTestCase {
    func testBundledPolicyListsEveryPackWithAnHTTPSSourceAndALicenceEntry() throws {
        let data = try Data(contentsOf: repositoryRoot
            .appendingPathComponent("ios/Resources/binario_gtfs_source_policy_v1.json"))
        let policy = try GTFSSourcePolicy(data: data)

        // Every railway pack the Android planner reads: twelve of gtfs/, five of gtfs-transit/.
        XCTAssertEqual(policy.sources.count, 17)
        XCTAssertTrue(policy.sources.filter { $0.sourceID != "amt-genova-casella-gtfs" }
            .allSatisfy { $0.sourceURL.scheme == "https" && $0.accessMode == "OPEN_DIRECT" })
        // Genova–Casella's origin is plain HTTP, imported offline and corroborated by an HTTPS copy.
        let casella = try XCTUnwrap(policy.source(id: "amt-genova-casella-gtfs"))
        XCTAssertEqual(casella.accessMode, GTFSSourcePolicy.gatewayOnlyAccessMode)
        XCTAssertEqual(casella.licence, "CC BY 4.0")
        XCTAssertEqual(casella.landingPage?.absoluteString, "https://dati.comune.genova.it/")
        for national in ["trenitalia-gtfs", "italo-gtfs"] {
            let source = try XCTUnwrap(policy.source(id: national), national)
            XCTAssertEqual(source.licence, "Dati aperti NAP CCISS (art. 52 c. 2 CAD, Reg. UE 2017/1926); conversione GTFS CC BY 4.0")
            XCTAssertTrue(source.landingPage?.absoluteString.hasPrefix("https://www.cciss.it/nap/") == true, national)
        }
        XCTAssertEqual(policy.source(id: "toscana-tft-gtfs")?.licence, "Creative Commons Attribution (CC BY)")
        for regional in ["fal-basilicata-puglia", "ferrotramviaria-bari-barletta", "fdg-gargano", "micotra-udine-villach"] {
            XCTAssertEqual(policy.source(id: regional)?.accessMode, "OPEN_DIRECT", regional)
        }
        XCTAssertEqual(policy.source(id: "oebb-gtfs")?.licence, "CC BY 4.0")
        XCTAssertEqual(policy.source(id: "alto-adige-sta-gtfs")?.licence, "CC0 1.0")
        XCTAssertEqual(policy.source(id: "arst-gtfs")?.licence, "CC BY 4.0", "as the Android manifest records it")
        XCTAssertEqual(policy.source(id: "eav-gtfs")?.licence, "Italian Open Data License (IODL)")
        XCTAssertTrue(policy.sources.allSatisfy { $0.landingPage?.scheme == "https" }, "every feed links its public page")
        XCTAssertEqual(policy.source(id: "arst-gtfs")?.landingPage?.absoluteString, "https://www.arst.sardegna.it/open-data/")
        XCTAssertNotNil(policy.source(id: "sncf-gtfs"))
        // Bundled whole since 23 Nov 2026 (Etalab Open Licence 2.0, as the Android manifest records it).
        let france = try XCTUnwrap(policy.source(id: "trenitalia-france-gtfs"))
        XCTAssertTrue(france.licence?.hasPrefix("Licence Ouverte / Open Licence 2.0 (Etalab, etalab-2.0)") == true)
        XCTAssertEqual(
            france.landingPage?.absoluteString,
            "https://transport.data.gouv.fr/datasets/horaires-des-trains-trenitalia-france"
        )
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
        for (key, value) in [
            ("sourceUrl", "ftp://example.test/a.zip"), ("accessMode", "GATEWAY"), ("licence", ""), ("sourceId", ""),
            ("landingPage", "ftp://example.test/a"),
        ] {
            var invalid = source
            invalid[key] = value
            XCTAssertThrowsError(try decode(["schemaVersion": 1, "sources": [invalid]]), key) { error in
                XCTAssertEqual(error as? GTFSSourcePolicyError, .invalidSource)
            }
        }
        // A gateway source may name an HTTP origin, never without an HTTPS public page.
        var gateway = source
        gateway["accessMode"] = "GATEWAY_ONLY"
        gateway["sourceUrl"] = "http://example.test/a.zip"
        XCTAssertThrowsError(try decode(["schemaVersion": 1, "sources": [gateway]])) { error in
            XCTAssertEqual(error as? GTFSSourcePolicyError, .invalidSource)
        }
        gateway["landingPage"] = "https://example.test/a"
        XCTAssertNoThrow(try decode(["schemaVersion": 1, "sources": [gateway]]))
        gateway["sourceUrl"] = "ftp://example.test/a.zip"
        XCTAssertThrowsError(try decode(["schemaVersion": 1, "sources": [gateway]]))
        var cleartext = source
        cleartext["sourceUrl"] = "http://example.test/a.zip"
        XCTAssertThrowsError(try decode(["schemaVersion": 1, "sources": [cleartext]]), "an OPEN_DIRECT source is HTTPS")
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
