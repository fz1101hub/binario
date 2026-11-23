import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/bike-sharing-v1.json: the systems serving a station, the sub-feeds
/// allowed, the GBFS documents read and what the section lists, read as Android's
/// BikeSharingContractTest reads them.
final class BikeSharingContractTests: XCTestCase {
    private func rules() throws -> [String: Any] {
        try SharedRuleFile.load("bike-sharing-v1.json")
    }

    func testTheSystemsServingAStationInEachBuild() throws {
        for item in SharedRuleFile.objects(try rules()["serves"]) {
            let latitude = try XCTUnwrap(SharedRuleFile.double(item["latitude"]))
            let longitude = try XCTUnwrap(SharedRuleFile.double(item["longitude"]))
            let systems = BikeSharingSystem.forBuild(personalSources: try XCTUnwrap(item["personal"] as? Bool))
                .filter { $0.serves(latitude: latitude, longitude: longitude) }
            XCTAssertEqual(systems.map(\.id), item["expected"] as? [String], item["name"] as? String ?? "")
        }
        XCTAssertEqual(BikeSharingSystem.all.filter(\.personalOnly).map(\.id), ["gbfs-bird-roma"])
    }

    func testSubFeedsOnlyOverHTTPSOnTheAutoDiscoveryHost() throws {
        for item in SharedRuleFile.objects(try rules()["sameHost"]) {
            let url = try XCTUnwrap(item["url"] as? String)
            XCTAssertEqual(BikeSharingService.sameHost(url, discovery: try XCTUnwrap(item["discovery"] as? String)), item["expected"] as? Bool, url)
        }
    }

    func testTheGbfsDocumentsRead() throws {
        let rules = try rules()
        for item in SharedRuleFile.objects(rules["feeds"]) {
            XCTAssertEqual(GbfsParser.feeds(try XCTUnwrap(item["json"] as? String)), item["expected"] as? [String: String], item["name"] as? String ?? "")
        }
        for item in SharedRuleFile.objects(rules["vehicleTypes"]) {
            let expected = (item["expected"] as? [String: String] ?? [:]).compactMapValues(SharedVehicleKind.init(rawValue:))
            XCTAssertEqual(GbfsParser.vehicleTypes(try XCTUnwrap(item["json"] as? String)), expected)
        }
        for item in SharedRuleFile.objects(rules["stations"]) {
            let types = GbfsParser.vehicleTypes(try XCTUnwrap(item["types"] as? String))
            let expected = try SharedRuleFile.objects(item["expected"]).map { station in
                GbfsStation(
                    id: try XCTUnwrap(station["id"] as? String), name: try XCTUnwrap(station["name"] as? String),
                    latitude: try XCTUnwrap(SharedRuleFile.double(station["latitude"])), longitude: try XCTUnwrap(SharedRuleFile.double(station["longitude"])),
                    bikes: try XCTUnwrap(SharedRuleFile.int(station["bikes"])),
                    ebikes: SharedRuleFile.int(station["ebikes"]), docks: SharedRuleFile.int(station["docks"])
                )
            }
            let actual = GbfsParser.stations(
                information: try XCTUnwrap(item["information"] as? String), status: try XCTUnwrap(item["status"] as? String), types: types
            )
            XCTAssertEqual(actual, expected, item["name"] as? String ?? "")
        }
        for item in SharedRuleFile.objects(rules["vehicles"]) {
            let types = GbfsParser.vehicleTypes(try XCTUnwrap(item["types"] as? String))
            let expected = try SharedRuleFile.objects(item["expected"]).map { vehicle in
                GbfsVehicle(
                    latitude: try XCTUnwrap(SharedRuleFile.double(vehicle["latitude"])), longitude: try XCTUnwrap(SharedRuleFile.double(vehicle["longitude"])),
                    kind: try XCTUnwrap(SharedVehicleKind(rawValue: vehicle["kind"] as? String ?? "")),
                    batteryPercent: SharedRuleFile.int(vehicle["batteryPercent"])
                )
            }
            XCTAssertEqual(GbfsParser.vehicles(try XCTUnwrap(item["json"] as? String), types: types), expected, item["name"] as? String ?? "")
        }
        for item in SharedRuleFile.objects(rules["lastUpdated"]) {
            let json = try XCTUnwrap(item["json"] as? String)
            XCTAssertEqual(GbfsParser.lastUpdated(json), SharedRuleFile.int(item["expected"]).map { Date(timeIntervalSince1970: TimeInterval($0)) }, json)
        }
        for item in SharedRuleFile.objects(rules["distances"]) {
            let from = try XCTUnwrap(item["from"] as? [String: Any])
            let to = try XCTUnwrap(item["to"] as? [String: Any])
            XCTAssertEqual(
                BikeSharingService.distance(
                    latitude: try XCTUnwrap(SharedRuleFile.double(from["latitude"])), longitude: try XCTUnwrap(SharedRuleFile.double(from["longitude"])),
                    try XCTUnwrap(SharedRuleFile.double(to["latitude"])), try XCTUnwrap(SharedRuleFile.double(to["longitude"]))
                ),
                SharedRuleFile.int(item["expectedMeters"])
            )
        }
    }

    func testWhatTheSectionListsNearAStation() async throws {
        for item in SharedRuleFile.objects(try rules()["nearby"]) {
            let name = try XCTUnwrap(item["name"] as? String)
            let documents = try XCTUnwrap(item["documents"] as? [String: String])
            let transport = FakeLiveTransport { request in
                documents[request.url.absoluteString].map { FakeLiveTransport.ok($0) } ?? .response(status: 404, headers: [:], body: Data())
            }
            let system = try XCTUnwrap(BikeSharingSystem.all.first { $0.id == item["system"] as? String })
            let service = BikeSharingService(
                systems: [system], transport: transport, pauses: LiveSourcePauses(), now: { Date(timeIntervalSince1970: 1_793_670_120) }
            )
            let result = await service.nearby(
                latitude: try XCTUnwrap(SharedRuleFile.double(item["latitude"])), longitude: try XCTUnwrap(SharedRuleFile.double(item["longitude"]))
            )
            guard case let .loaded(systems) = result, let nearby = systems.first else { return XCTFail("\(name): \(result)") }
            let expected = try XCTUnwrap(item["expected"] as? [String: Any])
            let docks = try SharedRuleFile.objects(expected["docks"]).map { dock in
                NearbyDock(
                    name: try XCTUnwrap(dock["name"] as? String), distanceMeters: try XCTUnwrap(SharedRuleFile.int(dock["distanceMeters"])),
                    bikesAvailable: try XCTUnwrap(SharedRuleFile.int(dock["bikes"])),
                    ebikesAvailable: SharedRuleFile.int(dock["ebikes"]), docksAvailable: SharedRuleFile.int(dock["docks"])
                )
            }
            let vehicles = try SharedRuleFile.objects(expected["vehicles"]).map { vehicle in
                NearbyVehicle(
                    kind: try XCTUnwrap(SharedVehicleKind(rawValue: vehicle["kind"] as? String ?? "")),
                    distanceMeters: try XCTUnwrap(SharedRuleFile.int(vehicle["distanceMeters"])),
                    batteryPercent: SharedRuleFile.int(vehicle["batteryPercent"])
                )
            }
            XCTAssertEqual(nearby.docks, docks, name)
            XCTAssertEqual(nearby.vehicles, vehicles, name)
            XCTAssertEqual(nearby.observedAt, Date(timeIntervalSince1970: TimeInterval(try XCTUnwrap(SharedRuleFile.int(expected["observedAt"])))), name)
            XCTAssertEqual(Set(transport.paths), Set(item["asked"] as? [String] ?? []), name)
            // Every request names Binario honestly and asks for JSON.
            for request in transport.requests {
                XCTAssertEqual(request.headers["User-Agent"], LiveSourceUserAgent.general)
                XCTAssertEqual(request.maximumBytes, SharedData.BikeSharing.maximumBytes)
            }
        }
    }

    func testAStationNoSystemServesAsksNothing() async {
        let transport = FakeLiveTransport { _ in FakeLiveTransport.ok("{}") }
        let service = BikeSharingService(systems: BikeSharingSystem.forBuild(personalSources: false), transport: transport,
                                         pauses: LiveSourcePauses(), now: { Date() })
        let result = await service.nearby(latitude: 41.9010, longitude: 12.5016)
        XCTAssertEqual(result, .notCovered)
        XCTAssertTrue(transport.requests.isEmpty)
    }
}
