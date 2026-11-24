import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/widgets-v1.json: the rules of the three home-screen widgets, as Android's
/// WidgetRulesContractTest reads them. BinarioKit's WidgetRules.swift is what the WidgetKit extension runs.
final class WidgetRulesContractTests: XCTestCase {
    /// Read from the checkout once per test; `testTheRulesReadTheSharedTable` fails when it is missing.
    private lazy var rules: [String: Any] = (try? SharedRuleFile.load("widgets-v1.json")) ?? [:]

    /// «2026-11-02T07:59:30» on the railway's wall clock.
    private func instant(_ value: Any?) throws -> Date? {
        guard let text = value as? String else { return nil }
        let moment = try SharedRuleFile.moment(text)
        let time = try XCTUnwrap(PlannedLocalTime(hour: moment.secondOfDay / 3_600, minute: (moment.secondOfDay % 3_600) / 60))
        let minute = try XCTUnwrap(ItalianWallClock.instant(date: moment.date, time: time), text)
        return minute.addingTimeInterval(TimeInterval(moment.secondOfDay % 60))
    }

    private func required(_ value: Any?) throws -> Date {
        try XCTUnwrap(try instant(value), "\(String(describing: value))")
    }

    private func settings(_ name: Any?) throws -> WidgetAutoRefresh {
        let all = try XCTUnwrap(rules["settings"] as? [String: Any])
        let json = try XCTUnwrap(all[try XCTUnwrap(name as? String)] as? [String: Any])
        let mode = try XCTUnwrap(WidgetAutoRefreshMode(rawValue: try XCTUnwrap(json["mode"] as? String)))
        let days = (json["days"] as? [Any] ?? []).compactMap { SharedRuleFile.int($0) }
        let windows = try (json["windows"] as? [[String]] ?? []).map { pair in
            try XCTUnwrap(WidgetRefreshWindow(start: pair[0], end: pair[1]))
        }
        return WidgetAutoRefresh(
            mode: mode, intervalMinutes: try XCTUnwrap(SharedRuleFile.int(json["intervalMinutes"])),
            days: Set(days), windows: windows
        )
    }

    private func groups(_ value: Any?) -> Set<WidgetTrainTypeGroup> {
        Set((value as? [String] ?? []).compactMap { WidgetTrainTypeGroup(rawValue: $0) })
    }

    private func direction(_ value: Any?) -> WidgetDirection? {
        (value as? String).flatMap { WidgetDirection(rawValue: $0) }
    }

    private func flag(_ value: Any?) -> Bool? {
        value is NSNull ? nil : (value as? NSNumber)?.boolValue
    }

    func testAutomaticRefreshWindows() throws {
        for item in SharedRuleFile.objects(rules["isActiveAt"]) {
            let expected = try XCTUnwrap(flag(item["expected"]))
            XCTAssertEqual(expected, try settings(item["settings"]).isActive(at: try required(item["at"])), "\(item)")
        }
        for item in SharedRuleFile.objects(rules["nextDueAt"]) {
            let due = try settings(item["settings"]).nextDue(lastRefresh: try instant(item["lastRefresh"]), now: try required(item["now"]))
            XCTAssertEqual(try instant(item["expected"]), due, "\(item)")
        }
    }

    func testTheAutomaticReturnDirection() throws {
        let section = try XCTUnwrap(rules["direction"] as? [String: Any])
        for item in SharedRuleFile.objects(section["effective"]) {
            let effective = WidgetDirectionSchedule.effective(
                returnFrom: (item["returnFrom"] as? String).flatMap { PlannedLocalTime(hhmm: $0) },
                stored: try XCTUnwrap(direction(item["stored"])),
                manual: direction(item["manual"]),
                manualUntil: try instant(item["manualUntil"]),
                now: try required(item["now"])
            )
            XCTAssertEqual(direction(item["expected"]), effective, "\(item)")
        }
        for item in SharedRuleFile.objects(section["nextBoundary"]) {
            let returnFrom = try XCTUnwrap((item["returnFrom"] as? String).flatMap { PlannedLocalTime(hhmm: $0) })
            let boundary = WidgetDirectionSchedule.nextBoundary(returnFrom: returnFrom, now: try required(item["now"]))
            XCTAssertEqual(try instant(item["expected"]), boundary, "\(item)")
        }
    }

    func testTheTrainTypeGroupOfABoardRow() throws {
        for item in SharedRuleFile.objects(rules["typeGroups"]) {
            let group = WidgetTrainTypes.group(
                operatorID: SharedRuleFile.string(item["operator"]),
                lineLabel: SharedRuleFile.string(item["line"]),
                replacementBus: flag(item["replacementBus"]) == true
            )
            XCTAssertEqual(SharedRuleFile.string(item["expected"]).flatMap { WidgetTrainTypeGroup(rawValue: $0) }, group, "\(item)")
        }
    }

    func testTheRunsTheRouteWidgetLists() throws {
        let section = try XCTUnwrap(rules["routeRows"] as? [String: Any])
        var rows: [(id: String, run: WidgetRouteRun)] = []
        for row in SharedRuleFile.objects(section["rows"]) {
            let run = WidgetRouteRun(
                departure: try instant(row["departure"]),
                arrival: try instant(row["arrival"]),
                cancelled: flag(row["cancelled"]) == true,
                group: SharedRuleFile.string(row["group"]).flatMap { WidgetTrainTypeGroup(rawValue: $0) }
            )
            rows.append((id: try XCTUnwrap(SharedRuleFile.string(row["id"])), run: run))
        }
        for item in SharedRuleFile.objects(section["cases"]) {
            let range = try XCTUnwrap(WidgetTimeRange(rawValue: try XCTUnwrap(SharedRuleFile.string(item["timeRange"]))))
            let listed = WidgetRouteRules.visible(
                rows, run: { $0.run }, now: try required(item["now"]), excluded: groups(item["excluded"]),
                untilArrival: flag(item["untilArrival"]) == true, timeRange: range
            )
            let expected = (item["expected"] as? [[Any]] ?? []).map { pair in
                "\(pair[0]):\((pair[1] as? NSNumber)?.boolValue == true)"
            }
            XCTAssertEqual(expected, listed.map { "\($0.row.id):\($0.inTransit)" }, "\(item["name"] ?? "")")
        }
    }

    func testTheDeparturesTheStationWidgetLists() throws {
        let section = try XCTUnwrap(rules["stationRows"] as? [String: Any])
        var departures: [(id: String, departure: WidgetBoardDeparture)] = []
        for row in SharedRuleFile.objects(section["departures"]) {
            let expected = try instant(row["expected"])
            let status = SharedRuleFile.string(row["status"])
            // Android's `hasObservation`: an expected time, or a state other than the timetable's.
            let observed = expected != nil || (status != nil && status != "SCHEDULED" && status != "UNKNOWN")
            let departure = WidgetBoardDeparture(
                scheduled: try required(row["scheduled"]), expected: expected, observed: observed,
                group: SharedRuleFile.string(row["group"]).flatMap { WidgetTrainTypeGroup(rawValue: $0) }
            )
            departures.append((id: try XCTUnwrap(SharedRuleFile.string(row["id"])), departure: departure))
        }
        for item in SharedRuleFile.objects(section["cases"]) {
            let freshness = try XCTUnwrap(WidgetFreshness(rawValue: try XCTUnwrap(SharedRuleFile.string(item["freshness"]))))
            let upcoming = WidgetStationRules.upcoming(
                departures, departure: { $0.departure }, freshness: freshness, now: try required(item["now"]),
                limit: try XCTUnwrap(SharedRuleFile.int(item["limit"])), excluded: groups(item["excluded"])
            )
            XCTAssertEqual(item["expected"] as? [String] ?? [], upcoming.map { $0.id }, "\(item["name"] ?? "")")
        }
    }

    func testWhenAFavouriteTrainIsRunningAndWhichDayARefreshAsksFor() throws {
        let section = try XCTUnwrap(rules["trains"] as? [String: Any])
        for item in SharedRuleFile.objects(section["isRunning"]) {
            var run: WidgetTrainRun?
            if let status = item["status"] as? [String: Any] {
                run = WidgetTrainRun(
                    departure: try instant(status["departure"]), arrival: try instant(status["arrival"]),
                    departed: flag(status["departed"]), arrived: flag(status["arrived"]),
                    cancelled: flag(status["cancelled"]) == true
                )
            }
            let expected = try XCTUnwrap(flag(item["expected"]))
            XCTAssertEqual(expected, WidgetTrainsRules.isRunning(run, now: try required(item["now"])), "\(item)")
        }
        for item in SharedRuleFile.objects(section["lookupDate"]) {
            var lastRun: WidgetTrainRun?
            var lastDeparture: String?
            if let status = item["status"] as? [String: Any] {
                let departure = try instant(status["departure"])
                lastRun = WidgetTrainRun(
                    departure: departure, arrival: try instant(status["arrival"]), departed: nil, arrived: nil, cancelled: false
                )
                lastDeparture = departure.map { WidgetClock.hhmm($0) }
            }
            let date = WidgetTrainsRules.lookupDate(
                savedDeparture: SharedRuleFile.string(item["savedDeparture"]) ?? "", lastDeparture: lastDeparture,
                lastRun: lastRun, now: try required(item["now"]), automatic: flag(item["automatic"]) == true
            )
            XCTAssertEqual(SharedRuleFile.string(item["expected"]), date?.iso, "\(item)")
        }
    }

    func testOneRunOfTheSchedulerForOneWidget() throws {
        for item in SharedRuleFile.objects(rules["planner"]) {
            let plan = WidgetRefreshPlanner.plan(
                settings: try settings(item["settings"]),
                lastRefresh: try instant(item["lastRefresh"]),
                pendingDirection: direction(item["pendingDirection"]),
                nextDirectionBoundary: try instant(item["nextDirectionBoundary"]),
                now: try required(item["now"])
            )
            XCTAssertEqual(flag(item["fetch"]), plan.fetch, "\(item)")
            XCTAssertEqual(direction(item["applyDirection"]), plan.applyDirection, "\(item)")
            XCTAssertEqual(try instant(item["nextRun"]), plan.nextRun, "\(item)")
        }
    }

    func testTheRetriesOfARefreshTheUserAskedFor() throws {
        for item in SharedRuleFile.objects(rules["retry"]) {
            let delay = WidgetRefreshPlanner.retryDelay(
                settings: try settings(item["settings"]),
                attempt: try XCTUnwrap(SharedRuleFile.int(item["attempt"])),
                now: try required(item["now"])
            )
            let expected = SharedRuleFile.int(item["expectedDelayMinutes"]).map { TimeInterval($0 * 60) }
            XCTAssertEqual(expected, delay, "\(item)")
        }
    }

    func testHowFreshTheDataOfAWidgetAre() throws {
        for item in SharedRuleFile.objects(rules["freshness"]) {
            let freshness = WidgetFreshness.of(retrievedAt: try instant(item["retrievedAt"]), now: try required(item["now"]))
            XCTAssertEqual(SharedRuleFile.string(item["expected"]), freshness.rawValue, "\(item)")
        }
    }

    /// The numbers the rules read are the shared table's.
    func testTheRulesReadTheSharedTable() throws {
        XCTAssertEqual(8, (rules["settings"] as? [String: Any])?.count, "contracts/shared-rules/v1/widgets-v1.json")
        let url = SharedRuleFile.repositoryRoot.appendingPathComponent("contracts/shared-data/v1/widgets-v1.json")
        let table = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        // The groups a widget hides are the board's, from the one shared table.
        let groupsURL = SharedRuleFile.repositoryRoot.appendingPathComponent("contracts/shared-data/v1/train-type-groups-v1.json")
        let groups = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: groupsURL)) as? [String: Any])
        XCTAssertEqual(groups["groups"] as? [String], WidgetTrainTypeGroup.allCases.map(\.rawValue))
        XCTAssertEqual((table["timeRanges"] as? [[String: Any]])?.compactMap { $0["id"] as? String }, WidgetTimeRange.allCases.map(\.rawValue))
        XCTAssertEqual([nil, 3, 6, nil], WidgetTimeRange.allCases.map(\.hours))
        XCTAssertEqual([0, 1, 2, 3], WidgetTimeRange.allCases.map(\.extraPages))
        XCTAssertEqual(SharedRuleFile.int(table["stationMaximumRows"]), WidgetStationRules.maximumRows)
        XCTAssertEqual(WidgetRefreshWindow(start: "07:00", end: "09:30"), WidgetRefreshWindow.defaultMorning)
        XCTAssertEqual(WidgetAutoRefreshMode.off, WidgetAutoRefresh.off.mode)
        XCTAssertEqual(SharedRuleFile.int(table["defaultIntervalMinutes"]), WidgetAutoRefresh.off.intervalMinutes)
        XCTAssertEqual(SharedRuleFile.int(table["alwaysIntervalMinutes"]), WidgetAutoRefresh.always.intervalMinutes)
        XCTAssertEqual("14:00", WidgetDirectionSchedule.defaultReturnFrom.hhmm)
        // The iPhone's operator names map to Android's identifiers.
        XCTAssertEqual("italo", TrainTypeGroups.operatorID("Italo"))
        XCTAssertEqual("oebb", TrainTypeGroups.operatorID("ÖBB"))
        XCTAssertEqual("european-sleeper", TrainTypeGroups.operatorID("European Sleeper"))
        XCTAssertEqual("sncf", TrainTypeGroups.operatorID("SNCF Voyageurs"))
        XCTAssertNil(TrainTypeGroups.operatorID("Ferrovie della Calabria"))
    }
}
