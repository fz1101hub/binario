import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/live-train-positions-v1.json: progress, estimated position, selection,
/// labels and areas of the map of trains in motion, read as Android's
/// LiveTrainPositionsContractTest reads them.
final class LiveTrainPositionsContractTests: XCTestCase {
    private func rules() throws -> [String: Any] {
        try SharedRuleFile.load("live-train-positions-v1.json")
    }

    private func stops(_ value: Any?) throws -> [ScheduledRunStop] {
        try SharedRuleFile.objects(value).map { stop in
            ScheduledRunStop(
                name: try XCTUnwrap(stop["name"] as? String),
                latitude: SharedRuleFile.double(stop["latitude"]), longitude: SharedRuleFile.double(stop["longitude"]),
                arrivalMinute: try XCTUnwrap(SharedRuleFile.int(stop["arrival"])), departureMinute: try XCTUnwrap(SharedRuleFile.int(stop["departure"]))
            )
        }
    }

    private func progress(_ value: Any?) throws -> RunProgress {
        let json = try XCTUnwrap(value as? [String: Any])
        switch json["kind"] as? String {
        case "notStarted": return .notStarted
        case "arrived": return .arrived
        case "atStop": return .atStop(try XCTUnwrap(SharedRuleFile.int(json["stop"])))
        default:
            return .between(
                from: try XCTUnwrap(SharedRuleFile.int(json["from"])), to: try XCTUnwrap(SharedRuleFile.int(json["to"])),
                fraction: try XCTUnwrap(SharedRuleFile.double(json["fraction"]))
            )
        }
    }

    private func assertProgress(_ actual: RunProgress, _ expected: RunProgress, _ message: String) {
        if case let .between(from, to, fraction) = expected, case let .between(actualFrom, actualTo, actualFraction) = actual {
            XCTAssertEqual(actualFrom, from, message)
            XCTAssertEqual(actualTo, to, message)
            XCTAssertEqual(actualFraction, fraction, accuracy: 1e-9, message)
        } else {
            XCTAssertEqual(actual, expected, message)
        }
    }

    private func bounds(_ value: Any?) throws -> GeoBounds {
        let json = try XCTUnwrap(value as? [String: Any])
        return GeoBounds(
            north: try XCTUnwrap(SharedRuleFile.double(json["north"])), south: try XCTUnwrap(SharedRuleFile.double(json["south"])),
            east: try XCTUnwrap(SharedRuleFile.double(json["east"])), west: try XCTUnwrap(SharedRuleFile.double(json["west"]))
        )
    }

    private func point(_ value: Any?) throws -> GeoPoint {
        let json = try XCTUnwrap(value as? [String: Any])
        return GeoPoint(latitude: try XCTUnwrap(SharedRuleFile.double(json["latitude"])), longitude: try XCTUnwrap(SharedRuleFile.double(json["longitude"])))
    }

    private func assertBounds(_ actual: GeoBounds, _ expected: GeoBounds) {
        XCTAssertEqual(actual.north, expected.north, accuracy: 1e-9)
        XCTAssertEqual(actual.south, expected.south, accuracy: 1e-9)
        XCTAssertEqual(actual.east, expected.east, accuracy: 1e-9)
        XCTAssertEqual(actual.west, expected.west, accuracy: 1e-9)
    }

    func testProgressAndPositionOnTheTimetable() throws {
        let rules = try rules()
        let timetable = try stops(rules["stops"])
        for item in SharedRuleFile.objects(rules["progress"]) {
            let minute = try XCTUnwrap(SharedRuleFile.double(item["minute"]))
            assertProgress(LiveTrainEstimator.progress(timetable, minute: minute), try progress(item["expected"]), "\(minute)")
        }
        for item in SharedRuleFile.objects(rules["positions"]) {
            let actual = LiveTrainEstimator.position(try stops(item["stops"]), minute: try XCTUnwrap(SharedRuleFile.double(item["minute"])))
            if let expected = item["expected"] as? [String: Any] {
                let point = try XCTUnwrap(actual, "\(item)")
                XCTAssertEqual(point.latitude, try XCTUnwrap(SharedRuleFile.double(expected["latitude"])), accuracy: 1e-9)
                XCTAssertEqual(point.longitude, try XCTUnwrap(SharedRuleFile.double(expected["longitude"])), accuracy: 1e-9)
            } else {
                XCTAssertNil(actual, "\(item)")
            }
        }
        for item in SharedRuleFile.objects(rules["minuteOfServiceDay"]) {
            let now = try SharedRuleFile.moment(item["now"])
            let minute = LiveTrainEstimator.minuteOfServiceDay(try XCTUnwrap(SharedRuleFile.date(item["serviceDate"])), now: now.date, secondOfDay: now.secondOfDay)
            XCTAssertEqual(minute, try XCTUnwrap(SharedRuleFile.double(item["expected"])), accuracy: 1e-9)
        }
        for item in SharedRuleFile.objects(rules["runNumberKeys"]) {
            let number = try XCTUnwrap(item["number"] as? String)
            XCTAssertEqual(LiveTrainSelector.runNumberKey(number), item["expected"] as? String, number)
        }
    }

    func testTheTrainsSelectedForTheMap() throws {
        for item in SharedRuleFile.objects(try rules()["selections"]) {
            let name = try XCTUnwrap(item["name"] as? String)
            let runs = try SharedRuleFile.objects(item["runs"]).map { run in
                ScheduledRailRun(
                    id: try XCTUnwrap(run["id"] as? String), serviceDate: try XCTUnwrap(SharedRuleFile.date(run["serviceDate"])),
                    publicNumber: run["number"] as? String, lineLabel: run["line"] as? String, stops: try stops(run["stops"]),
                    origin: try (run["origin"] as? [String: Any]).map { origin in
                        ScheduledRunOrigin(name: try XCTUnwrap(origin["name"] as? String), departureMinute: try XCTUnwrap(SharedRuleFile.int(origin["departure"])))
                    }
                )
            }
            let observations = try SharedRuleFile.objects(item["observations"]).map { observation in
                LiveRunObservation(
                    number: try XCTUnwrap(observation["number"] as? String), scheduledAt: try SharedRuleFile.moment(observation["scheduledAt"]),
                    delayMinutes: SharedRuleFile.int(observation["delayMinutes"]), cancelled: try XCTUnwrap(observation["cancelled"] as? Bool),
                    ref: try XCTUnwrap(SharedRuleFile.int(observation["ref"]))
                )
            }
            let now = try SharedRuleFile.moment(item["now"])
            let area = try bounds(item["bounds"])
            let focus = try point(item["focus"])
            let selection = SharedRuleFile.int(item["cap"]).map {
                LiveTrainSelector.select(runs, observations: observations, now: now, bounds: area, focus: focus, cap: $0)
            } ?? LiveTrainSelector.select(runs, observations: observations, now: now, bounds: area, focus: focus)
            let expected = try XCTUnwrap(item["expected"] as? [String: Any])
            XCTAssertEqual(selection.inBounds, SharedRuleFile.int(expected["inBounds"]), name)
            let trains = SharedRuleFile.objects(expected["trains"])
            XCTAssertEqual(selection.trains.map(\.run.id), trains.compactMap { $0["id"] as? String }, name)
            for (train, actual) in zip(trains, selection.trains) {
                XCTAssertEqual(actual.point.latitude, try XCTUnwrap(SharedRuleFile.double(train["latitude"])), accuracy: 1e-9, name)
                XCTAssertEqual(actual.point.longitude, try XCTUnwrap(SharedRuleFile.double(train["longitude"])), accuracy: 1e-9, name)
                XCTAssertEqual(actual.delayMinutes, SharedRuleFile.int(train["delayMinutes"]), name)
                XCTAssertEqual(actual.observation?.ref, SharedRuleFile.int(train["ref"]), name)
                assertProgress(actual.progress, try progress(train["progress"]), name)
            }
        }
    }

    func testMarkerLabels() throws {
        for item in SharedRuleFile.objects(try rules()["markers"]) {
            let run = ScheduledRailRun(
                id: "pack:x:2026-11-04", serviceDate: try XCTUnwrap(PlannedLocalDate(iso: "2026-11-04")),
                publicNumber: item["number"] as? String, lineLabel: item["line"] as? String,
                stops: [
                    ScheduledRunStop(name: "Alfa", latitude: 45.0, longitude: 9.0, arrivalMinute: 600, departureMinute: 600),
                    ScheduledRunStop(name: "Beta", latitude: 45.3, longitude: 9.3, arrivalMinute: 630, departureMinute: 630),
                ]
            )
            let train = LiveTrain(
                run: run, point: GeoPoint(latitude: 45.0, longitude: 9.0), progress: .atStop(0),
                delayMinutes: SharedRuleFile.int(item["delayMinutes"]), observation: nil, distanceKm: 0
            )
            XCTAssertEqual(LiveTrainLabels.marker(train), item["expected"] as? String)
        }
    }

    func testTheAreaOfAMapAndItsTimetableSlot() throws {
        let rules = try rules()
        let areas = try XCTUnwrap(rules["areas"] as? [String: Any])
        for item in SharedRuleFile.objects(areas["around"]) {
            let centre = GeoPoint(latitude: try XCTUnwrap(SharedRuleFile.double(item["latitude"])), longitude: try XCTUnwrap(SharedRuleFile.double(item["longitude"])))
            assertBounds(LiveTrainAreas.around(centre), try bounds(item["expected"]))
        }
        for item in SharedRuleFile.objects(areas["routes"]) {
            assertBounds(LiveTrainAreas.routeArea(try point(item["origin"]), try point(item["destination"])), try bounds(item["expected"]))
        }
        for item in SharedRuleFile.objects(areas["tooWide"]) {
            XCTAssertEqual(LiveTrainAreas.tooWide(try bounds(item["bounds"])), item["expected"] as? Bool)
        }
        for item in SharedRuleFile.objects(areas["readAreas"]) {
            assertBounds(LiveTrainAreas.readArea(try bounds(item["visible"])), try bounds(item["expected"]))
        }
        for item in SharedRuleFile.objects(areas["covers"]) {
            XCTAssertEqual(LiveTrainAreas.covers(try bounds(item["read"]), try bounds(item["visible"])), item["expected"] as? Bool)
        }
        for item in SharedRuleFile.objects(rules["slots"]) {
            XCTAssertEqual(LiveTrainRefresh.slotStart(try SharedRuleFile.moment(item["now"])), try SharedRuleFile.moment(item["expected"]))
        }
    }
}
