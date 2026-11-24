import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/connection-scan-v1.json and journey-planner-v1.json: the planner of journeys with changes
/// and its rules, the same cases Android reads (ConnectionScanContractTest.kt, JourneyPlannerRulesContractTest.kt).
final class JourneyPlannerContractTests: XCTestCase {
    func testEverySharedCaseGivesTheSharedJourneys() throws {
        let document = try json("connection-scan-v1.json")
        let cases = try XCTUnwrap(document["cases"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        for item in cases {
            let name = try XCTUnwrap(item["name"] as? String)
            let timetable = try self.timetable(try XCTUnwrap(item["timetable"] as? [String: Any]))
            let query = try self.query(item)
            let outcome = ConnectionScanPlanner(timetable: timetable).planWithOutcome(query)
            let expected = try XCTUnwrap(item["expected"] as? [String: Any])
            XCTAssertEqual(outcome.exhausted, expected["exhausted"] as? Bool, name)
            let journeys = try XCTUnwrap(expected["journeys"] as? [[String: Any]])
            XCTAssertEqual(outcome.journeys.map(describe), journeys.map(describe), name)
        }
    }

    private func timetable(_ json: [String: Any]) throws -> ConnectionScanTimetable {
        let nodes = try XCTUnwrap(json["nodes"] as? Int)
        let connections = try XCTUnwrap(json["connections"] as? [[Int]])
        let noPickup = Set(json["noPickup"] as? [Int] ?? [])
        let noDropOff = Set(json["noDropOff"] as? [Int] ?? [])
        let walks = json["footpaths"] as? [[Int]] ?? []
        var footStart: [Int] = []
        var footTarget: [Int] = []
        var footMinutes: [Int] = []
        for node in 0..<nodes {
            footStart.append(footTarget.count)
            for walk in walks where walk[0] == node {
                footTarget.append(walk[1])
                footMinutes.append(walk[2])
            }
        }
        footStart.append(footTarget.count)
        let links = json["continuations"] as? [[Int]] ?? []
        var start = Array(repeating: -1, count: connections.count)
        var continuationTrip: [Int] = []
        var continuationNext: [Int] = []
        for link in links {
            continuationNext.append(start[link[0]])
            continuationTrip.append(link[1])
            start[link[0]] = continuationTrip.count - 1
        }
        return try ConnectionScanTimetable(
            nodeCount: nodes,
            tripCount: (connections.map { $0[4] }.max() ?? -1) + 1,
            transferMinutes: try XCTUnwrap(json["transferMinutes"] as? [Int]),
            departureNode: connections.map { $0[0] },
            arrivalNode: connections.map { $0[1] },
            departureMinute: connections.map { $0[2] },
            arrivalMinute: connections.map { $0[3] },
            trip: connections.map { $0[4] },
            boarding: connections.indices.map {
                ConnectionScanTimetable.boardingFlags(pickupAllowed: !noPickup.contains($0), dropOffAllowed: !noDropOff.contains($0))
            },
            footStart: footStart,
            footTarget: footTarget,
            footMinutes: footMinutes,
            continuationStart: links.isEmpty ? nil : start,
            continuationTrip: continuationTrip,
            continuationNext: continuationNext
        )
    }

    private func query(_ item: [String: Any]) throws -> ConnectionScanQuery {
        let json = try XCTUnwrap(item["query"] as? [String: Any])
        let nodes = try XCTUnwrap((item["timetable"] as? [String: Any])?["nodes"] as? Int)
        func marks(_ key: String) -> [Bool] {
            let set = Set(json[key] as? [Int] ?? [])
            return (0..<nodes).map(set.contains)
        }
        func walks(_ key: String) -> [Int]? {
            guard let walk = json[key] as? [String: Int] else { return nil }
            return (0..<nodes).map { walk[String($0)] ?? -1 }
        }
        return try ConnectionScanQuery(
            origins: marks("origins"),
            destinations: marks("destinations"),
            departureMinute: try XCTUnwrap(json["departureMinute"] as? Int),
            latestDepartureMinute: try XCTUnwrap(json["latestDepartureMinute"] as? Int),
            maxTransfers: try XCTUnwrap(json["maxTransfers"] as? Int),
            maxResults: try XCTUnwrap(json["maxResults"] as? Int),
            maxIterations: try XCTUnwrap(json["maxIterations"] as? Int),
            lastConnectionMinute: json["lastConnectionMinute"] as? Int,
            originWalkMinutes: walks("originWalk"),
            destinationWalkMinutes: walks("destinationWalk"),
            preferLessWalking: json["preferLessWalking"] as? Bool ?? false,
            maxChangeWaitMinutes: json["maxChangeWaitMinutes"] as? Int ?? Int.max
        )
    }

    private func describe(_ journey: ScannedJourney) -> String {
        func walk(_ value: ScannedWalk?) -> String { value.map { "\($0.fromNode),\($0.toNode),\($0.minutes)" } ?? "-" }
        let legs = journey.legs.map { "\($0.boardConnection)>\($0.alightConnection)" }.joined(separator: " ")
        return "\(legs) \(journey.departureMinute)-\(journey.arrivalMinute) access \(walk(journey.accessWalk)) "
            + "transfers \(journey.transferWalks.map(walk).joined(separator: " ")) egress \(walk(journey.egressWalk))"
    }

    private func describe(_ journey: [String: Any]) -> String {
        func walk(_ value: Any?) -> String { (value as? [Int]).map { $0.map(String.init).joined(separator: ",") } ?? "-" }
        let legs = (journey["legs"] as? [[Int]] ?? []).map { $0.map(String.init).joined(separator: ">") }.joined(separator: " ")
        let transfers = (journey["transferWalks"] as? [Any] ?? []).map(walk).joined(separator: " ")
        return "\(legs) \(journey["departure"] as? Int ?? -1)-\(journey["arrival"] as? Int ?? -1) access \(walk(journey["accessWalk"])) "
            + "transfers \(transfers) egress \(walk(journey["egressWalk"]))"
    }

    // MARK: - The contract files

    func json(_ name: String) throws -> [String: Any] {
        let data = try Data(contentsOf: contract.appendingPathComponent(name))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private var contract: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("contracts/shared-rules/v1")
    }
}
