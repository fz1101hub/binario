import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/trip-alerts-v1.json: the stop alarm, the stop countdown, the followed trip, the approach
/// alert, the notices of the trains with the bell, the status of a commuter alert and the position estimate, as
/// Android's TripAlertsContractTest reads them.
final class TripAlertsContractTests: XCTestCase {
    private var rules: [String: Any] = [:]

    override func setUpWithError() throws {
        rules = try SharedRuleFile.load("trip-alerts-v1.json")
    }

    /// «2026-11-24T08:28» or «2026-11-24T08:28:01», railway-local.
    private func at(_ value: Any?) throws -> Date {
        let text = try XCTUnwrap(value as? String)
        let parts = text.split(separator: "T").map(String.init)
        let date = try XCTUnwrap(PlannedLocalDate(iso: parts[0]), text)
        let clock = parts[1].split(separator: ":").compactMap { Int($0) }
        let time = try XCTUnwrap(PlannedLocalTime(hour: clock[0], minute: clock[1]), text)
        let instant = try XCTUnwrap(ItalianWallClock.instant(date: date, time: time), text)
        return instant.addingTimeInterval(TimeInterval(clock.count > 2 ? clock[2] : 0))
    }

    private func optionalAt(_ value: Any?) throws -> Date? {
        value is String ? try at(value) : nil
    }

    private func section(_ name: String) throws -> [String: Any] {
        try XCTUnwrap(rules[name] as? [String: Any], name)
    }

    private func date(_ value: Any?) -> PlannedLocalDate? { SharedRuleFile.date(value) }

    func testTheQuietHours() throws {
        for item in SharedRuleFile.objects(rules["quietHours"]) {
            let time = try XCTUnwrap(PlannedLocalTime(hhmm: try XCTUnwrap(item["time"] as? String)))
            XCTAssertEqual(TripQuietHours.isQuiet(time), item["quiet"] as? Bool, "\(item)")
        }
    }

    func testTheStopAlarm() throws {
        let alarm = try section("stopAlarm")
        for item in SharedRuleFile.objects(alarm["plan"]) {
            let name = item["name"] as? String ?? ""
            let plan = StopAlarmPlanner.plan(
                trainNumber: "2153", destination: "Lecco", station: "Monza", expectedTime: try XCTUnwrap(item["expected"] as? String),
                now: try at(item["now"])
            )
            XCTAssertEqual(plan?.expectedAt, try optionalAt(item["expectedAt"]), name)
            XCTAssertEqual(plan?.triggerAt, try optionalAt(item["triggerAt"]), name)
        }
        for item in SharedRuleFile.objects(alarm["restoreTrigger"]) {
            let plan = StopAlarmPlan(trainNumber: "2153", destination: "Lecco", station: "Monza", expectedTime: "8:30",
                                     triggerAt: try at(item["trigger"]), expectedAt: try at(item["expectedAt"]))
            XCTAssertEqual(StopAlarmPlanner.restoreTrigger(plan, now: try at(item["now"])), try optionalAt(item["expected"]), "\(item)")
        }
        for item in SharedRuleFile.objects(alarm["isOver"]) {
            let expected = try at(item["expectedAt"])
            let plan = StopAlarmPlan(trainNumber: "2153", destination: "Lecco", station: "Monza", expectedTime: "8:30",
                                     triggerAt: expected.addingTimeInterval(-300), expectedAt: expected)
            XCTAssertEqual(StopAlarmPlanner.isOver(plan, now: try at(item["now"])), item["over"] as? Bool, "\(item)")
        }
    }

    private func assertCountdown(_ expected: Any?, _ actual: StopCountdown?, _ name: String, line: UInt = #line) {
        guard let expected = expected as? [String: Any] else {
            XCTAssertNil(actual, name, line: line)
            return
        }
        XCTAssertEqual(actual?.stopsLeft, SharedRuleFile.int(expected["stopsLeft"]), name, line: line)
        XCTAssertEqual(actual?.nextIndex, SharedRuleFile.int(expected["nextIndex"]), name, line: line)
        XCTAssertEqual(actual?.nextStop, expected["nextStop"] as? String, name, line: line)
        XCTAssertEqual(actual?.alightStop, expected["alightStop"] as? String, name, line: line)
        XCTAssertEqual(actual?.source.rawValue, expected["source"] as? String, name, line: line)
    }

    func testTheStopCountdown() throws {
        let countdown = try section("stopCountdown")
        let stops = try SharedRuleFile.objects(countdown["stops"]).map {
            CountdownStop(name: $0["name"] as? String ?? "", at: try optionalAt($0["at"]), skipped: $0["skipped"] as? Bool ?? false)
        }
        for item in SharedRuleFile.objects(countdown["cases"]) {
            let actual = StopCountdownCalculator.countdown(
                stops, board: try XCTUnwrap(SharedRuleFile.int(item["board"])), alight: try XCTUnwrap(SharedRuleFile.int(item["alight"])),
                now: try at(item["now"]), livePassed: SharedRuleFile.int(item["livePassed"]), positionPassed: SharedRuleFile.int(item["positionPassed"])
            )
            assertCountdown(item["expected"], actual, item["name"] as? String ?? "")
        }
    }

    func testTheFollowedTrip() throws {
        let followed = try section("followedTrip")
        let leg = FollowedTripLeg(number: "2153", label: "REG 2153", boardStation: "Milano Centrale", alightStation: "Lecco",
                                  departure: try at(followed["departure"]), arrival: try at(followed["arrival"]))
        let trip = try XCTUnwrap(FollowedTrip(id: "trip", title: "Milano Centrale → Lecco", legs: [leg]))
        for item in SharedRuleFile.objects(followed["step"]) {
            let name = item["name"] as? String ?? ""
            let expected = try XCTUnwrap(item["expected"] as? [String: Any])
            let step = FollowedTripPlanner.step(trip, now: try at(item["now"]), lastDelayMinutes: SharedRuleFile.int(item["lastDelayMinutes"]))
            switch expected["step"] as? String {
            case "wait": XCTAssertEqual(step, .waitUntil(try at(expected["at"])), name)
            case "refresh": XCTAssertEqual(step, .refresh(try at(expected["at"])), name)
            default: XCTAssertEqual(step, .finish, name)
            }
        }
        for item in SharedRuleFile.objects(followed["isUpcomingOrRunning"]) {
            XCTAssertEqual(FollowedTripPlanner.isUpcomingOrRunning(trip, now: try at(item["now"])), item["expected"] as? Bool, "\(item)")
        }
        for item in SharedRuleFile.objects(followed["nextRefreshAt"]) {
            let countdown = try SharedRuleFile.int(item["stopsLeft"]).map {
                StopCountdown(stopsLeft: $0, nextIndex: 0, nextStop: "Monza", nextStopAt: try optionalAt(item["nextStopAt"]),
                              alightStop: "Lecco", source: .scheduled)
            }
            XCTAssertEqual(
                FollowedTripPlanner.nextRefreshAt(regular: try at(item["regular"]), now: try at(item["now"]), countdown: countdown),
                try at(item["expected"]), item["name"] as? String ?? ""
            )
        }
    }

    private func approachAlert(_ item: [String: Any]) throws -> ApproachAlert {
        ApproachAlert(number: "2153", label: "REG 2153", station: "Monza", stationTime: try XCTUnwrap(item["stationTime"] as? String),
                      departure: item["departure"] as? String ?? "08:00", leadMinutes: try XCTUnwrap(SharedRuleFile.int(item["lead"])),
                      createdAt: try at("2026-11-20T08:00"))
    }

    private func observation(_ value: Any?) throws -> ApproachObservation? {
        guard let item = value as? [String: Any] else { return nil }
        return ApproachObservation(expectedAt: try at(item["expectedAt"]), delayMinutes: SharedRuleFile.int(item["delayMinutes"]),
                                   platform: item["platform"] as? String, passed: item["passed"] as? Bool ?? false,
                                   cancelled: item["cancelled"] as? Bool ?? false)
    }

    func testTheApproachAlert() throws {
        let approach = try section("approach")
        for item in SharedRuleFile.objects(approach["window"]) {
            let name = item["name"] as? String ?? ""
            let window = ApproachAlertPlanner.window(try approachAlert(item), date: try XCTUnwrap(date(item["date"])))
            let expected = item["expected"] as? [String: Any]
            XCTAssertEqual(window?.lowerBound, try expected.map { try at($0["start"]) }, name)
            XCTAssertEqual(window?.upperBound, try expected.map { try at($0["end"]) }, name)
        }
        for item in SharedRuleFile.objects(approach["serviceDate"]) {
            XCTAssertEqual(ApproachAlertPlanner.serviceDate(try approachAlert(item), day: try XCTUnwrap(date(item["day"]))), date(item["expected"]), "\(item)")
        }
        for item in SharedRuleFile.objects(approach["quietOnTimetable"]) {
            XCTAssertEqual(ApproachAlertPlanner.quietOnTimetable(try approachAlert(item)), item["expected"] as? Bool, "\(item)")
        }
        for item in SharedRuleFile.objects(approach["minutesUntil"]) {
            XCTAssertEqual(ApproachAlertPlanner.minutesUntil(now: try at(item["now"]), expected: try at(item["expected"])),
                           SharedRuleFile.int(item["minutes"]), "\(item)")
        }
        let base = try XCTUnwrap(approach["alert"] as? [String: Any])
        let alert = try approachAlert(base)
        let day = try XCTUnwrap(date(base["date"]))
        for item in SharedRuleFile.objects(approach["decide"]) {
            let name = item["name"] as? String ?? ""
            let decision = ApproachAlertPlanner.decide(alert, date: day, observation: try observation(item["observation"]),
                                                       now: try at(item["now"]), notified: item["notified"] as? Bool ?? false)
            let expected = try XCTUnwrap(item["expected"] as? [String: Any])
            switch expected["decision"] as? String {
            case "wait": XCTAssertEqual(decision, .waitUntil(try at(expected["at"])), name)
            case "notify": XCTAssertEqual(decision, .notify(minutesLeft: try XCTUnwrap(SharedRuleFile.int(expected["minutesLeft"]))), name)
            default: XCTAssertEqual(decision, .done, name)
            }
        }
        for item in SharedRuleFile.objects(approach["nextRun"]) {
            let next = ApproachAlertPlanner.nextRun(alert, doneOn: date(item["doneOn"]), notifiedOn: date(item["notifiedOn"]),
                                                    observation: try observation(item["observation"]), now: try at(item["now"]))
            XCTAssertEqual(next, try optionalAt(item["expected"]), item["name"] as? String ?? "")
        }
        for item in SharedRuleFile.objects(approach["alarmAt"]) {
            let alarm = ApproachAlertPlanner.alarmAt(alert, doneOn: date(item["doneOn"]), notifiedOn: date(item["notifiedOn"]),
                                                     observation: try observation(item["observation"]), now: try at(item["now"]))
            XCTAssertEqual(alarm, try optionalAt(item["expected"]), item["name"] as? String ?? "")
        }
    }

    private func subscription(_ number: String, departure: String) throws -> TrainAlertSubscription {
        TrainAlertSubscription(number: number, originCode: nil, departure: departure, label: number, subscribedAt: try at("2026-11-20T08:00"))
    }

    /// One subscription per departure, each its own train.
    private func subscriptions(_ item: [String: Any]) throws -> [TrainAlertSubscription] {
        try ((item["departures"] as? [String]) ?? []).enumerated().map { try subscription("\(100 + $0.offset)", departure: $0.element) }
    }

    private func days(_ value: Any?) -> [String: Int] {
        ((value as? [String: Any]) ?? [:]).compactMapValues { SharedRuleFile.int($0) }
    }

    func testTheNoticesOfTheTrainsWithTheBell() throws {
        let alerts = try section("trainAlerts")
        for item in SharedRuleFile.objects(alerts["key"]) {
            XCTAssertEqual(TrainAlertWatch.key(try XCTUnwrap(item["number"] as? String)), item["expected"] as? String, "\(item)")
        }
        let number = try XCTUnwrap(alerts["number"] as? String)
        for item in SharedRuleFile.objects(alerts["window"]) {
            let name = item["name"] as? String ?? ""
            let followed = try SharedRuleFile.objects(item["followed"]).map {
                FollowedTrainTimes(number: $0["number"] as? String ?? "", departure: try at($0["departure"]), arrival: try at($0["arrival"]))
            }
            let window = TrainAlertWatch.window(try subscription(number, departure: item["departure"] as? String ?? ""),
                                                date: try XCTUnwrap(date(item["date"])), followed: followed)
            let expected = item["expected"] as? [String: Any]
            XCTAssertEqual(window?.lowerBound, try expected.map { try at($0["start"]) }, name)
            XCTAssertEqual(window?.upperBound, try expected.map { try at($0["end"]) }, name)
        }
        for item in SharedRuleFile.objects(alerts["due"]) {
            let due = TrainAlertWatch.due(try subscriptions(item), followed: [], now: try at(item["now"]))
            XCTAssertEqual(due.map(\.departure), item["expected"] as? [String], item["name"] as? String ?? "")
        }
        for item in SharedRuleFile.objects(alerts["nextRun"]) {
            let next = TrainAlertWatch.nextRun(try subscriptions(item), followed: [], now: try at(item["now"]), lastCheckAt: try optionalAt(item["lastCheckAt"]))
            XCTAssertEqual(next, try optionalAt(item["expected"]), item["name"] as? String ?? "")
        }
        for item in SharedRuleFile.objects(alerts["newNotices"]) {
            let notices = try SharedRuleFile.objects(item["notices"]).map {
                WatchedNotice(id: $0["id"] as? String ?? "", text: "Avviso", publishedAt: try optionalAt($0["publishedAt"]))
            }
            let fresh = TrainAlertWatch.newNotices(notices, seen: days(item["seen"]), subscribedAt: try at(item["subscribedAt"]))
            XCTAssertEqual(fresh.map(\.id), item["expected"] as? [String], item["name"] as? String ?? "")
        }
        for item in SharedRuleFile.objects(alerts["remember"]) {
            let notices = ((item["notices"] as? [String]) ?? []).map { WatchedNotice(id: $0, text: "Avviso", publishedAt: nil) }
            let remembered = TrainAlertWatch.remember(days(item["seen"]), notices: notices, today: try XCTUnwrap(date(item["today"])))
            XCTAssertEqual(remembered, days(item["expected"]), item["name"] as? String ?? "")
        }
    }

    func testTheStatusOfACommuterAlert() throws {
        let commuter = try section("commuterStatus")
        let texts = try XCTUnwrap(commuter["strings"] as? [String: String])
        let delay = texts["delay"] ?? ""
        let withPlatform = texts["withPlatform"] ?? ""
        let strings = CommuterAlertStatusStrings(
            unavailable: texts["unavailable"] ?? "", onTime: texts["onTime"] ?? "", cancelled: texts["cancelled"] ?? "",
            delay: { delay.replacingOccurrences(of: "%d", with: String($0)) },
            withPlatform: { status, platform in
                let first = withPlatform.range(of: "%s").map { withPlatform.replacingCharacters(in: $0, with: status) } ?? withPlatform
                return first.range(of: "%s").map { first.replacingCharacters(in: $0, with: platform) } ?? first
            }
        )
        for item in SharedRuleFile.objects(commuter["format"]) {
            let status = (item["status"] as? [String: Any]).map {
                CommuterLiveStatus(delayMinutes: SharedRuleFile.int($0["delayMinutes"]) ?? 0, platform: $0["platform"] as? String,
                                   cancelled: $0["cancelled"] as? Bool ?? false)
            }
            XCTAssertEqual(CommuterAlertStatusRules.format(status, strings: strings), item["expected"] as? String, "\(item)")
        }
        for item in SharedRuleFile.objects(commuter["routeStations"]) {
            let stations = CommuterAlertStatusRules.routeStations(item["label"] as? String ?? "")
            XCTAssertEqual(stations.map { [$0.origin, $0.destination] }, item["expected"] as? [String], "\(item)")
        }
    }

    private func fix(_ item: [String: Any]) -> GpsFix {
        GpsFix(latitude: SharedRuleFile.double(item["latitude"]) ?? .nan, longitude: SharedRuleFile.double(item["longitude"]) ?? .nan,
               accuracyMeters: SharedRuleFile.double(item["accuracy"]), age: SharedRuleFile.double(item["ageSeconds"]) ?? 0)
    }

    private func assertTrack(_ expected: Any?, _ track: GpsTrainTrack, _ name: String) throws {
        let expected = try XCTUnwrap(expected as? [String: Any], name)
        XCTAssertEqual(track.status.rawValue, expected["status"] as? String, name)
        XCTAssertEqual(track.passedIndex, SharedRuleFile.int(expected["passedIndex"]), name)
        XCTAssertEqual(track.estimate?.delayMinutes, SharedRuleFile.int(expected["delayMinutes"]), name)
        XCTAssertEqual(track.estimate?.arrivalAt, try optionalAt(expected["arrivalAt"]), name)
    }

    func testThePositionEstimate() throws {
        let position = try section("position")
        let route = GpsTrainRoute(
            trainNumber: "2153",
            stops: try SharedRuleFile.objects(position["route"]).map {
                GpsRouteStop(name: $0["name"] as? String ?? "", latitude: SharedRuleFile.double($0["latitude"]),
                             longitude: SharedRuleFile.double($0["longitude"]), scheduledAt: try at($0["at"]))
            },
            boardIndex: try XCTUnwrap(SharedRuleFile.int(position["board"])),
            targetIndex: try XCTUnwrap(SharedRuleFile.int(position["target"]))
        )
        let tracker = GpsTrainTracker(route: route)
        for item in SharedRuleFile.objects(position["fixes"]) {
            try assertTrack(item["expected"], tracker.onFix(fix(item), now: try at(item["now"])), item["name"] as? String ?? "")
        }
        let countdown = try XCTUnwrap(position["countdown"] as? [String: Any])
        assertCountdown(countdown["expected"], GpsTrainTracking.countdown(route, track: tracker.track, now: try at(countdown["now"])), "countdown")
        let arrival = try XCTUnwrap(position["arrival"] as? [String: Any])
        try assertTrack(arrival["expected"], tracker.onFix(fix(arrival), now: try at(arrival["now"])), "arrival")
        for item in SharedRuleFile.objects(position["canStartEstimate"]) {
            XCTAssertEqual(
                GpsTrainTracking.canStartEstimate(departure: try at(item["departure"]), arrival: try at(item["arrival"]),
                                                  officialDelayMinutes: SharedRuleFile.int(item["officialDelayMinutes"]), now: try at(item["now"])),
                item["expected"] as? Bool, "\(item)"
            )
        }
    }
}
