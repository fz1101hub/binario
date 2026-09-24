import Foundation
import XCTest
@testable import BinarioKit

final class StationCatalogTests: XCTestCase {
    func testCanonicalRepositoryAssetDecodesAndKeepsItsIdentityContract() throws {
        let data = try Data(contentsOf: repositoryRoot
            .appendingPathComponent("app/src/main/assets/stations/curated_it_rail_stations.json"))
        let catalog = try CuratedStationCatalog(data: data)

        XCTAssertEqual(catalog.stationCount, 5_239)
        XCTAssertFalse(catalog.operationalData)
        XCTAssertEqual(catalog.station(id: "S08328")?.name, "Appiano Proba Petronia")
        XCTAssertEqual(catalog.station(id: "NC001752")?.name, "Boscotrecase - Porta Di Accesso Al Cratere Del Vesuvio")
        XCTAssertEqual(catalog.station(id: "NC001477")?.name, "Morciano - Barbarano - Castrignano - Giuliano")
    }

    func testAsyncLoaderTransfersAvailableCatalog() async throws {
        let data = try Data(contentsOf: repositoryRoot
            .appendingPathComponent("app/src/main/assets/stations/curated_it_rail_stations.json"))
        let state = await StationCatalogLoader.load { data }

        guard case let .available(catalog) = state else {
            return XCTFail("Expected available catalog")
        }
        XCTAssertEqual(catalog.stationCount, 5_239)
    }

    func testAsyncLoaderFailsClosedForInvalidData() async {
        let state = await StationCatalogLoader.load { Data("{}".utf8) }

        XCTAssertEqual(state, .unavailable)
    }

    func testSearchNormalizesCaseDiacriticsWhitespaceAndPunctuation() throws {
        let catalog = try fixtureCatalog()

        XCTAssertEqual(catalog.search("  roma---términi ").first?.id, "S08409")
        XCTAssertEqual(StationSearchNormalizer.normalize("Reggio nell’Emilia"), "reggio nell emilia")
    }

    func testAliasAndCodesAreSearchableWithoutChangingCanonicalNames() throws {
        let catalog = try fixtureCatalog()

        XCTAssertEqual(catalog.search("appiano").first?.name, "Appiano Proba Petronia")
        XCTAssertEqual(catalog.search("S08328").first?.name, "Appiano Proba Petronia")
    }

    func testOperationalCatalogueFailsClosed() throws {
        let invalid = """
        {"schemaVersion":1,"kind":"curated-rail-station-catalog","source":"x","sourceDatabaseName":"x","sourceDatabaseSha256":"x","stationCount":0,"operationalData":true,"notice":"x","stations":[]}
        """

        XCTAssertThrowsError(try CuratedStationCatalog(data: Data(invalid.utf8))) { error in
            XCTAssertEqual(error as? StationCatalogError, .containsOperationalData)
        }
    }

    private func fixtureCatalog() throws -> CuratedStationCatalog {
        let fixture = """
        {
          "schemaVersion": 1,
          "kind": "curated-rail-station-catalog",
          "source": "fixture",
          "sourceDatabaseName": "fixture",
          "sourceDatabaseSha256": "fixture",
          "stationCount": 3,
          "operationalData": false,
          "notice": "identity only",
          "stations": [
            {"id":"S08409","name":"Roma Termini","railwayCode":"S08409","trenitaliaLocationId":"830008409","aliases":[],"latitude":41.9,"longitude":12.5,"confidence":"Alta"},
            {"id":"S08328","name":"Appiano Proba Petronia","railwayCode":"S08328","trenitaliaLocationId":"830008334","aliases":["APPIANO"],"latitude":41.9,"longitude":12.4,"confidence":"Alta"},
            {"id":"S09218","name":"Napoli Centrale","railwayCode":"S09218","trenitaliaLocationId":"830009218","aliases":[],"latitude":40.8,"longitude":14.3,"confidence":"Alta"}
          ]
        }
        """
        return try CuratedStationCatalog(data: Data(fixture.utf8))
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
