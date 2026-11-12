import Foundation
import XCTest
@testable import BinarioKit

/// «Stazione più vicina» (EXPO_PORT_PLAN I7, F4, R20 and §3.9): Android's StationProximityTest and
/// NearestStationPickerTest, and the companion's offline-station-index cases. Only stations with
/// coordinates, nearest first, then by name; no invented result.
final class StationProximityTests: XCTestCase {
    func testOrdersCatalogueStationsByDistanceFromThePosition() {
        let termini = Station(id: "S08409", name: "Roma Termini", latitude: 41.9008, longitude: 12.5016)
        let tiburtina = Station(id: "S08217", name: "Roma Tiburtina", latitude: 41.9100, longitude: 12.5307)
        let torino = Station(id: "S00200", name: "Torino Porta Nuova", latitude: 45.0620, longitude: 7.6785)

        let nearby = StationProximity.nearby([torino, tiburtina, termini], latitude: 41.9008, longitude: 12.5016)

        XCTAssertEqual(nearby.map(\.station.id), ["S08409", "S08217", "S00200"])
        XCTAssertLessThan(nearby[0].distanceKilometres, 0.01)
        // Roma Termini → Torino Porta Nuova, about 525 km as the crow flies.
        XCTAssertEqual(nearby[2].distanceKilometres, 525, accuracy: 10)
    }

    func testNoStationIsOfferedForAPositionThatIsNotFinite() {
        let station = Station(id: "S08409", name: "Roma Termini", latitude: 41.9008, longitude: 12.5016)
        XCTAssertTrue(StationProximity.nearby([station], latitude: .nan, longitude: 12.5016).isEmpty)
        XCTAssertTrue(StationProximity.nearby([station], latitude: 41.9008, longitude: .infinity).isEmpty)
        XCTAssertNil(StationProximity.nearest([station], latitude: .nan, longitude: 12.5))
    }

    func testStationsWithoutUsableCoordinatesAreLeftOutAndEqualDistancesFollowTheItalianName() {
        let zeta = Station(id: "Z", name: "Zagarolo", latitude: 41.9008, longitude: 12.5016)
        let abano = Station(id: "A", name: "Abano", latitude: 41.9008, longitude: 12.5016)
        let accented = Station(id: "E", name: "Éboli", latitude: 41.9008, longitude: 12.5016)
        let invalid = Station(id: "X", name: "Coordinate invalide", latitude: .nan, longitude: 12.5016)
        let missing = Station(id: "M", name: "Senza coordinate")

        let nearby = StationProximity.nearby([zeta, invalid, accented, missing, abano], latitude: 41.9008, longitude: 12.5016)

        // Accents do not count in the Italian order: «Éboli» comes between «Abano» and «Zagarolo».
        XCTAssertEqual(nearby.map(\.station.id), ["A", "E", "Z"])
    }

    func testTheNearestStationIsWithinFiftyKilometresOrNone() {
        let roma = Station(id: "roma", name: "Roma Termini", latitude: 41.9010, longitude: 12.5016)
        let tiburtina = Station(id: "tiburtina", name: "Roma Tiburtina", latitude: 41.9109, longitude: 12.5303)
        let napoli = Station(id: "napoli", name: "Napoli Centrale", latitude: 40.8530, longitude: 14.2724)
        let missing = Station(id: "none", name: "Senza coordinate")

        XCTAssertEqual(StationProximity.nearest([napoli, tiburtina, missing, roma], latitude: 41.9005, longitude: 12.4990)?.station, roma)
        XCTAssertEqual(StationProximity.nearest([napoli, roma, tiburtina], latitude: 41.9120, longitude: 12.5320)?.station, tiburtina)
        // Paris is far from both: nothing rather than a station 1,100 km away.
        XCTAssertNil(StationProximity.nearest([roma, napoli], latitude: 48.8566, longitude: 2.3522))
        XCTAssertNil(StationProximity.nearest([missing], latitude: 41.9, longitude: 12.5))
        XCTAssertNil(StationProximity.nearest([], latitude: 41.9, longitude: 12.5))
        XCTAssertEqual(
            StationProximity.nearest([roma, napoli], latitude: 41.0, longitude: 14.0, maximumDistanceKilometres: 500)?.station,
            napoli
        )
    }

    /// On the bundled catalogue, among the entries the search offers: a co-located duplicate entry
    /// is never chosen, and the sea west of Sardinia has no station within 50 km.
    func testTheNearestStationOfTheBundledCatalogue() throws {
        let data = try Data(contentsOf: repositoryRoot
            .appendingPathComponent("app/src/main/assets/stations/curated_it_rail_stations.json"))
        let stations = try CuratedStationCatalog(data: data).offeredStations

        XCTAssertEqual(StationProximity.nearest(stations, latitude: 41.900636, longitude: 12.502026)?.station.id, "S08409")
        // At the coordinates of the Varese Nord entry S01738, the search offers N00024, 19 m away.
        XCTAssertEqual(StationProximity.nearest(stations, latitude: 45.818456, longitude: 8.833304)?.station.id, "N00024")
        XCTAssertNil(StationProximity.nearest(stations, latitude: 38.5, longitude: 6.0))
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
