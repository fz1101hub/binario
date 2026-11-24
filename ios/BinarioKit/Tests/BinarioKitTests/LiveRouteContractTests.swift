import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/live-route-v1.json and contracts/shared-data/v1/live-route-v1.json, read as Android's
/// LiveRouteContractTest reads them: the RFI route of two boards and the live runs merged into the timetable list.
final class LiveRouteContractTests: XCTestCase {
    private var rome: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Rome") ?? .current
        return calendar
    }

    private func date(_ value: String, day: String) throws -> Date {
        let text = value.contains("T") ? value : "\(day)T\(value)"
        let parts = text.split(whereSeparator: { "-T:".contains($0) }).compactMap { Int($0) }
        XCTAssertEqual(parts.count, 5, text)
        return try XCTUnwrap(rome.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: parts[3], minute: parts[4])))
    }

    private func clock(_ date: Date) -> String {
        let parts = rome.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", parts.hour ?? 0, parts.minute ?? 0)
    }

    func testTheLiveRouteReadsTheSharedTable() throws {
        let table = try file("contracts/shared-data/v1/live-route-v1.json")
        XCTAssertEqual(LiveRunMerge.retimingWindow, TimeInterval(try XCTUnwrap(table["retimingWindowMinutes"] as? Int) * 60))
        XCTAssertEqual(SharedData.LiveRoute.operatorFamilies, table["operatorFamilies"] as? [String: String])
        XCTAssertEqual(LiveRoute.rfiLookBack, TimeInterval(try XCTUnwrap(table["rfiBoardLookBackMinutes"] as? Int) * 60))
        XCTAssertEqual(LiveRoute.rfiLookAhead, TimeInterval(try XCTUnwrap(table["rfiBoardLookAheadMinutes"] as? Int) * 60))
        XCTAssertEqual(LiveRoute.freshObservation, TimeInterval(try XCTUnwrap(table["freshObservationSeconds"] as? Int)))
        XCTAssertEqual(LiveRoute.viaggiaTrenoHorizon, TimeInterval(try XCTUnwrap(table["viaggiaTrenoHorizonMinutes"] as? Int) * 60))
        XCTAssertEqual(SharedData.LiveRoute.rfiMaximumRuns, table["rfiMaximumRuns"] as? Int)
        XCTAssertEqual(SharedData.LiveRoute.viaggiaTrenoMaximumRuns, table["viaggiaTrenoMaximumRuns"] as? Int)
    }

    private func row(_ value: [String: Any], index: Int, kind: String, day: String, now: Date) throws -> LiveDeparture {
        let scheduled = try date(try XCTUnwrap(value["scheduled"] as? String), day: day)
        let observed = now.addingTimeInterval(-TimeInterval(value["observedSecondsAgo"] as? Int ?? 0))
        let observation = LiveObservation(
            source: .rfiStationBoard, fetchedAt: now, observedAt: observed,
            expiresAt: now.addingTimeInterval(TimeInterval(value["expiresInSeconds"] as? Int ?? 45))
        )
        let statuses: [String: LiveDepartureStatus] = ["ON_TIME": .onTime, "DELAYED": .delayed, "CANCELLED": .cancelled]
        let status = (value["status"] as? String).flatMap { statuses[$0] }
        return LiveDeparture(
            id: "contract:\(kind):\(index)", number: value["number"] as? String ?? "", operatorID: value["operator"] as? String,
            category: "", destination: "Capolinea", scheduled: scheduled,
            expected: (value["delay"] as? Int).map { LiveSourced(scheduled.addingTimeInterval(TimeInterval($0 * 60)), observation: observation) },
            platform: nil, scheduledPlatform: nil, status: status.map { LiveSourced($0, observation: observation) },
            sources: [.rfiStationBoard], viaggiaTrenoRun: nil, observation: observation
        )
    }

    func testTheRfiRoute() throws {
        let rules = try file("contracts/shared-rules/v1/live-route-v1.json")
        let day = try XCTUnwrap(rules["day"] as? String)
        let cases = try XCTUnwrap(rules["rfiRoute"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 11)
        let origin = Station(id: "curated:S01234", name: "Origine", railwayCode: "S01234")
        let destination = Station(id: "curated:S05678", name: "Destinazione", railwayCode: "S05678")
        let states: [String: LiveRunState] = ["ON_TIME": .onTime, "DELAYED": .delayed, "CANCELLED": .cancelled]
        for item in cases {
            let name = item["name"] as? String ?? "?"
            let now = try date(try XCTUnwrap(item["now"] as? String), day: day)
            let departures = try XCTUnwrap(item["departures"] as? [[String: Any]]).enumerated().map { try row($1, index: $0, kind: "d", day: day, now: now) }
            let arrivals = try XCTUnwrap(item["arrivals"] as? [[String: Any]]).enumerated().map { try row($1, index: $0, kind: "a", day: day, now: now) }
            let runs = LiveRoute.rfiRuns(
                departures: departures, arrivals: arrivals, origin: origin, destination: destination,
                requested: try date(try XCTUnwrap(item["requested"] as? String), day: day), now: now
            )
            guard let expected = item["expected"] as? [[String: Any]] else {
                XCTAssertNil(runs, name)
                continue
            }
            let found = try XCTUnwrap(runs, name)
            XCTAssertEqual(found.count, expected.count, name)
            for (run, wanted) in zip(found, expected) {
                XCTAssertEqual(run.number, wanted["number"] as? String, name)
                XCTAssertEqual(clock(run.scheduledDeparture), wanted["scheduledDeparture"] as? String, name)
                XCTAssertEqual(clock(run.expectedDeparture), wanted["expectedDeparture"] as? String, name)
                XCTAssertEqual(run.delayMinutes, wanted["delayMinutes"] as? Int, name)
                XCTAssertEqual(clock(run.scheduledArrival), wanted["scheduledArrival"] as? String, name)
                XCTAssertEqual(clock(run.expectedArrival), wanted["expectedArrival"] as? String, name)
                XCTAssertEqual(run.state, states[wanted["status"] as? String ?? ""], name)
                XCTAssertEqual(run.verified, wanted["verified"] as? Bool, name)
                XCTAssertEqual(run.lateWithoutMinutes, wanted["lateWithoutMinutes"] as? Bool, name)
                XCTAssertEqual(run.departureVerified, wanted["departureVerified"] as? Bool, name)
                XCTAssertEqual(run.arrivalVerified, wanted["arrivalVerified"] as? Bool, name)
            }
        }
    }

    func testTheLiveRunsMergedIntoTheTimetable() throws {
        let rules = try file("contracts/shared-rules/v1/live-route-v1.json")
        let day = try XCTUnwrap(rules["day"] as? String)
        let cases = try XCTUnwrap(rules["merge"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 12)
        let names: [String: String] = ["trenitalia": "Trenitalia", "trenord": "Trenord", "italo": "Italo", "sbb": "SBB CFF FFS"]
        for item in cases {
            let name = item["name"] as? String ?? "?"
            let requested = try date(try XCTUnwrap(item["requested"] as? String), day: day)
            var verified: Set<String> = []
            let planned = try XCTUnwrap(item["planned"] as? [[String: Any]]).map { value -> TrainService in
                let id = try XCTUnwrap(value["id"] as? String)
                let text = try XCTUnwrap(value["scheduled"] as? String)
                let dated = value["dated"] as? Bool ?? true
                if value["verified"] as? Bool == true { verified.insert(id) }
                return TrainService(
                    id: id, number: try XCTUnwrap(value["number"] as? String), operatorName: (value["operator"] as? String).flatMap { names[$0] } ?? "",
                    category: "REG", destination: "Destinazione", scheduledDeparture: dated ? clock(try date(text, day: day)) : text,
                    expectedDeparture: text, status: OperationalState.programmed.rawValue, platform: nil, delayMinutes: 0,
                    departureDate: dated ? try date(text, day: day) : nil
                )
            }
            let runs = try XCTUnwrap(item["live"] as? [[String: Any]]).map { value -> LiveRouteRun in
                let at = try date(try XCTUnwrap(value["scheduled"] as? String), day: day)
                return LiveRouteRun(
                    id: try XCTUnwrap(value["id"] as? String), number: try XCTUnwrap(value["number"] as? String), category: "REG",
                    operatorID: value["operator"] as? String, operatorName: nil, scheduledDeparture: at, expectedDeparture: at,
                    departureVerified: true, departurePlatform: nil, scheduledArrival: at.addingTimeInterval(3_600),
                    expectedArrival: at.addingTimeInterval(3_600), arrivalVerified: true, arrivalPlatform: nil, delayMinutes: 0,
                    state: .onTime, lateWithoutMinutes: false, verified: value["verified"] as? Bool ?? false, source: .viaggiaTreno, observedAt: nil
                )
            }
            let merged = LiveRunMerge.merge(planned: planned, verified: verified, runs: runs, requested: requested)
            let expected = try XCTUnwrap(item["expected"] as? [String: Any])
            XCTAssertEqual(merged.replaced.mapValues(\.id), expected["replaced"] as? [String: String], name)
            XCTAssertEqual(merged.added.map(\.id), expected["added"] as? [String], name)
        }
    }

    private func file(_ path: String) throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent(path)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any], path)
    }
}
