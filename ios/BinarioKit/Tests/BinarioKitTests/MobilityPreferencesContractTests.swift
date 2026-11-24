import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/mobility-preferences-v1.json and contracts/shared-data/v1/mobility-preferences-v1.json,
/// read as Android's MobilityPreferencesContractTest reads them: «Mobilità e accessibilità» in the local backup.
final class MobilityPreferencesContractTests: XCTestCase {
    private var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }

    private func json(_ path: String) throws -> [String: Any] {
        let data = try Data(contentsOf: repositoryRoot.appendingPathComponent(path))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any], path)
    }

    private func cases(_ key: String) throws -> [[String: Any]] {
        try XCTUnwrap(try json("contracts/shared-rules/v1/mobility-preferences-v1.json")[key] as? [[String: Any]], key)
    }

    private func preferences(_ value: Any?) throws -> MobilityPreferences {
        let value = try XCTUnwrap(value as? [String: Any])
        return MobilityPreferences(
            maxChanges: value["maxChanges"] as? Int,
            transferMarginMinutes: try XCTUnwrap(value["transferMarginMinutes"] as? Int),
            stepFreePreferred: try XCTUnwrap(value["stepFreePreferred"] as? Bool),
            compactTimetablePreferred: try XCTUnwrap(value["compactTimetablePreferred"] as? Bool)
        )
    }

    private let milano = StationIdentity(id: "S01700", name: "Milano Centrale", railwayCode: "S01700")
    private let pavia = StationIdentity(id: "S01860", name: "Pavia", railwayCode: "S01860")

    func testThePreferencesReadTheSharedTable() throws {
        let table = try json("contracts/shared-data/v1/mobility-preferences-v1.json")
        XCTAssertEqual(table["defaultTransferMarginMinutes"] as? Int, MobilityPreferences().transferMarginMinutes)
        XCTAssertEqual(table["maximumTransferMarginMinutes"] as? Int, SharedData.MobilityPreferences.maximumTransferMarginMinutes)
        XCTAssertEqual(table["changeChoices"] as? [Int], SharedData.MobilityPreferences.changeChoices)
        XCTAssertEqual(table["transferMarginChoices"] as? [Int], SharedData.MobilityPreferences.transferMarginChoices)
    }

    func testFromABackup() throws {
        let items = try cases("fromBackup")
        XCTAssertEqual(items.count, 9)
        for item in items {
            let name = item["name"] as? String ?? ""
            var root: [String: Any] = [
                "schemaVersion": 2, "kind": SharedData.LocalBackup.kind,
                "routes": [["id": "r1", "name": "Lavoro", "originId": milano.id, "destinationId": pavia.id]],
                "stations": [["id": milano.id, "name": milano.name, "city": "Milano"], ["id": pavia.id, "name": pavia.name, "city": "Pavia"]],
            ]
            if let mobility = item["mobility"] as? [String: Any] { root["mobility"] = mobility }
            let data = try JSONSerialization.data(withJSONObject: root)
            let backup = try LocalBackupCodec.decode(data) { _ in nil }.get()
            XCTAssertEqual(backup.mobilityPreferences, try preferences(item["expected"]), name)
        }
    }

    func testToABackup() throws {
        let items = try cases("toBackup")
        XCTAssertEqual(items.count, 3)
        let route = try SavedRoute(id: SavedRoute.routeIdentifier(origin: milano, destination: pavia), name: "Lavoro", origin: milano, destination: pavia)
        for item in items {
            let name = item["name"] as? String ?? ""
            let backup = LocalBackup(
                routes: [route], favouriteStations: [], favouriteTrains: [], trainNames: [:], punctualityHistory: nil,
                savedTimetables: nil, mobilityPreferences: try preferences(item["preferences"])
            )
            let data = try LocalBackupCodec.encode(backup) { Station(id: $0.id, name: $0.name, railwayCode: $0.railwayCode) }.get()
            let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
            let written = try XCTUnwrap(root["mobility"] as? NSDictionary, name)
            XCTAssertEqual(written, try XCTUnwrap(item["expected"] as? [String: Any]) as NSDictionary, name)
        }
    }

    func testCarriesMoreThanRoutes() throws {
        let items = try cases("carriesMoreThanRoutes")
        XCTAssertEqual(items.count, 5)
        for item in items {
            let backup = LocalBackup(
                routes: [], favouriteStations: [], favouriteTrains: [], trainNames: [:], punctualityHistory: nil,
                savedTimetables: nil, mobilityPreferences: try preferences(item["preferences"])
            )
            XCTAssertEqual(backup.carriesMoreThanRoutes, item["expected"] as? Bool, item["name"] as? String ?? "")
        }
    }
}
