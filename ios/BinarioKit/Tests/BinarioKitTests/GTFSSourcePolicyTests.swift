import Foundation
import XCTest
@testable import BinarioKit

final class GTFSSourcePolicyTests: XCTestCase {
    func testBundledPolicyListsEveryPackWithAnHTTPSSourceAndALicenceEntry() throws {
        let data = try Data(contentsOf: repositoryRoot
            .appendingPathComponent("ios/Resources/binario_gtfs_source_policy_v1.json"))
        let policy = try GTFSSourcePolicy(data: data)

        // Every pack the Android planner reads in a public build: the twelve railway packs of gtfs/, the five
        // railway packs of gtfs-transit/ and its five public city networks (SharedData.PackRoles.urbanPacks).
        XCTAssertEqual(policy.sources.count, 22)
        let cityPacks: Set<String> = ["milano-atm-gtfs", "genova-amt-gtfs", "firenze-gest-gtfs", "arst-metro-gtfs", "marconi-express-gtfs"]
        XCTAssertTrue(cityPacks.isSubset(of: SharedData.PackRoles.urbanPacks))
        XCTAssertTrue(cityPacks.allSatisfy { policy.source(id: $0) != nil })
        // A source is an HTTPS direct download, or a gateway source: an origin no app fetches, imported offline
        // and corroborated by an HTTPS copy of the same bytes (the manifest's acquisition.corroboratedBy, checked by
        // tools/build_ios_gtfs_schedule.py), with an HTTPS public page. AMT Genova's two packs (Genova–Casella and the
        // city network) are cut from one archive, published over plain HTTP only.
        let gateways = Set(policy.sources.filter { $0.accessMode == GTFSSourcePolicy.gatewayOnlyAccessMode }.map(\.sourceID))
        XCTAssertEqual(gateways, ["amt-genova-casella-gtfs", "genova-amt-gtfs"])
        XCTAssertTrue(policy.sources.filter { !gateways.contains($0.sourceID) }
            .allSatisfy { $0.sourceURL.scheme == "https" && $0.accessMode == "OPEN_DIRECT" })
        let manifest = try JSONSerialization.jsonObject(with: Data(contentsOf: repositoryRoot
            .appendingPathComponent("app/src/main/assets/gtfs-transit/manifest.json"))) as? [String: Any]
        let packs = (manifest?["packs"] as? [[String: Any]]) ?? []
        for id in gateways.sorted() {
            let source = try XCTUnwrap(policy.source(id: id), id)
            XCTAssertEqual(source.sourceURL.absoluteString, "http://opendatagoogletransit.comune.genova.it/downloads/google_transit.zip", id)
            XCTAssertEqual(source.licence, "CC BY 4.0", id)
            XCTAssertEqual(source.landingPage?.absoluteString, "https://dati.comune.genova.it/", id)
            let pack = try XCTUnwrap(packs.first { $0["sourceId"] as? String == id }, id)
            let acquisition = try XCTUnwrap(pack["acquisition"] as? [String: Any], id)
            let copy = try XCTUnwrap(acquisition["corroboratedBy"] as? [String: Any], id)
            XCTAssertTrue((copy["url"] as? String)?.hasPrefix("https://") == true, id)
            XCTAssertEqual(copy["sha256"] as? String, acquisition["downloadSha256"] as? String, id)
        }
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
