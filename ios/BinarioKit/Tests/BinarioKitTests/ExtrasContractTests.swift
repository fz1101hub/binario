import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1: the delay compensation notice (delay-compensation-v1.json), the
/// weather at the destination (destination-weather-v1.json) and the evening alerts
/// (evening-alerts-v1.json), read as Android's contract tests read them.
final class ExtrasContractTests: XCTestCase {
    // MARK: Delay compensation

    func testDelayCompensationCases() throws {
        let rules = try SharedRuleFile.load("delay-compensation-v1.json")
        let cases = SharedRuleFile.objects(rules["cases"])
        XCTAssertEqual(cases.count, 13)
        for item in cases {
            let name = try XCTUnwrap(item["name"] as? String)
            let stops = SharedRuleFile.objects(item["stops"])
            let last = try XCTUnwrap(stops.last)
            let serviceVerified = try XCTUnwrap(item["serviceVerified"] as? Bool)
            let arrival = DelayCompensationRule.Arrival(
                stationName: try XCTUnwrap(last["name"] as? String),
                delayMinutes: try XCTUnwrap(SharedRuleFile.int(last["delayMinutes"])),
                arrived: try XCTUnwrap(last["arrived"] as? Bool),
                skipped: try XCTUnwrap(last["skipped"] as? Bool),
                // A stop without its own verification takes the run's (Android's null).
                verified: (last["verified"] as? Bool) ?? serviceVerified
            )
            let actual = DelayCompensationRule.of(
                arrival, cancelled: try XCTUnwrap(item["cancelled"] as? Bool), isDemonstration: try XCTUnwrap(item["demonstration"] as? Bool)
            )
            if let expected = item["expected"] as? [String: Any] {
                let percent = try XCTUnwrap(SharedRuleFile.int(expected["percent"]))
                XCTAssertEqual(actual?.stationName, expected["station"] as? String, name)
                XCTAssertEqual(actual?.delayMinutes, SharedRuleFile.int(expected["delayMinutes"]), name)
                XCTAssertEqual(actual?.share.percent, percent, name)
            } else {
                XCTAssertNil(actual, name)
            }
        }
    }

    // MARK: Weather at the destination

    func testDestinationWeatherTargets() throws {
        let rules = try SharedRuleFile.load("destination-weather-v1.json")
        for item in SharedRuleFile.objects(rules["targets"]) {
            let name = try XCTUnwrap(item["name"] as? String)
            let target = DestinationWeatherTarget.of(
                lastStop: item["lastStop"] as? String, lastScheduled: item["lastScheduled"] as? String,
                destination: try XCTUnwrap(item["destination"] as? String),
                departureDate: SharedRuleFile.date(item["departureDate"]), departure: item["departure"] as? String,
                today: try XCTUnwrap(SharedRuleFile.date(item["today"]))
            )
            if let expected = item["expected"] as? [String: Any] {
                XCTAssertEqual(target?.stationName, expected["station"] as? String, name)
                XCTAssertEqual(target?.arrivalDate, SharedRuleFile.date(expected["date"]), name)
            } else {
                XCTAssertNil(target, name)
            }
        }
    }

    func testDestinationWeatherCoordinatesAndRequest() throws {
        let rules = try SharedRuleFile.load("destination-weather-v1.json")
        for item in SharedRuleFile.objects(rules["coordinates"]) {
            let value = try XCTUnwrap(SharedRuleFile.double(item["value"]))
            XCTAssertEqual(DestinationWeatherService.rounded(value), item["expected"] as? String, "\(value)")
        }
        for item in SharedRuleFile.objects(rules["requests"]) {
            let url = DestinationWeatherService.url(
                latitude: try XCTUnwrap(SharedRuleFile.double(item["latitude"])), longitude: try XCTUnwrap(SharedRuleFile.double(item["longitude"]))
            )
            XCTAssertEqual(url?.absoluteString, item["expected"] as? String)
        }
        for item in SharedRuleFile.objects(rules["invalidCoordinates"]) {
            XCTAssertNil(DestinationWeatherService.url(
                latitude: try XCTUnwrap(SharedRuleFile.double(item["latitude"])), longitude: try XCTUnwrap(SharedRuleFile.double(item["longitude"]))
            ))
        }
    }

    func testDestinationWeatherForecastsAreReadAsOnAndroid() throws {
        let rules = try SharedRuleFile.load("destination-weather-v1.json")
        for item in SharedRuleFile.objects(rules["forecasts"]) {
            let name = try XCTUnwrap(item["name"] as? String)
            let body = try (item["file"] as? String).map(SharedRuleFile.text) ?? XCTUnwrap(item["body"] as? String)
            let days = DestinationWeatherService.parse(body)
            if item["expected"] as? String == "unreadable" {
                XCTAssertNil(days, name)
                continue
            }
            let expected = try SharedRuleFile.objects(item["expected"]).map { day in
                DailyForecast(
                    date: try XCTUnwrap(SharedRuleFile.date(day["date"])),
                    weatherCode: SharedRuleFile.int(day["weatherCode"]),
                    maxTemperatureC: SharedRuleFile.double(day["maxTemperatureC"]),
                    minTemperatureC: SharedRuleFile.double(day["minTemperatureC"]),
                    precipitationProbabilityPercent: SharedRuleFile.int(day["precipitationProbabilityPercent"])
                )
            }
            XCTAssertEqual(days, expected, name)
        }
    }

    func testDestinationWeatherIsAskedOnceAndKeptForItsTimeToLive() async throws {
        let body = try SharedRuleFile.text("app/src/test/resources/sources/open-meteo-lugano.json")
        let transport = FakeLiveTransport { _ in FakeLiveTransport.ok(body) }
        // 2026-10-25 08:00 UTC: the Lugano answer has today and tomorrow.
        let clock = LiveTestClock(Date(timeIntervalSince1970: 1_792_915_200))
        let service = DestinationWeatherService(transport: transport, pauses: LiveSourcePauses(), now: clock.closure)
        let today = try XCTUnwrap(PlannedLocalDate(iso: "2026-10-25"))
        let first = await service.forecast(latitude: 45.99724, longitude: 8.945581, date: today)
        guard case let .forecast(day, stale) = first else { return XCTFail("\(first)") }
        XCTAssertEqual(day.weatherCode, 3)
        XCTAssertFalse(stale)
        _ = await service.forecast(latitude: 45.99724, longitude: 8.945581, date: try XCTUnwrap(today.adding(days: 1)))
        XCTAssertEqual(transport.requests.count, 1)
        XCTAssertEqual(transport.requests.first?.headers["User-Agent"], LiveSourceUserAgent.general)
        // Two days ahead is outside the forecast: nothing is asked.
        let later = await service.forecast(latitude: 45.99724, longitude: 8.945581, date: try XCTUnwrap(today.adding(days: 2)))
        XCTAssertEqual(later, .outOfRange)
        XCTAssertEqual(transport.requests.count, 1)
    }

    func testEveryDescribedWmoCodeHasItsOwnText() throws {
        let rules = try SharedRuleFile.load("destination-weather-v1.json")
        let described = (rules["describedCodes"] as? [Int]) ?? []
        XCTAssertEqual(described.count, 28)
        for code in described { XCTAssertEqual(WeatherCodes.key(code), "result.weather.code.\(code)") }
        for code in (rules["undescribedCodes"] as? [Int]) ?? [] { XCTAssertEqual(WeatherCodes.key(code), "result.weather.code.unknown") }
        XCTAssertEqual(WeatherCodes.key(nil), "result.weather.code.unknown")
    }

    // MARK: Evening alerts

    func testTheStrikeReminder() throws {
        let rules = try XCTUnwrap(try SharedRuleFile.load("evening-alerts-v1.json")["strikeReminder"] as? [String: Any])
        for item in SharedRuleFile.objects(rules["nextRunAt"]) {
            let now = try SharedRuleFile.wall(item["now"])
            let expected = try SharedRuleFile.wall(item["expected"])
            let next = try XCTUnwrap(StrikeReminderPlanner.nextRun(after: now))
            XCTAssertEqual(RailWallClock(date: next.date, minuteOfDay: next.hour * 60), expected, "\(now)")
        }
        for item in SharedRuleFile.objects(rules["mayNotify"]) {
            XCTAssertEqual(StrikeReminderPlanner.mayNotify(try SharedRuleFile.wall(item["now"])), item["expected"] as? Bool, "\(item)")
        }
        func strike(_ id: String, start: PlannedLocalDate?, end: PlannedLocalDate?) -> StrikeNotice {
            StrikeNotice(id: id, title: "Sciopero", description: nil, link: nil, publishedAt: nil, startDate: start, endDate: end)
        }
        for item in SharedRuleFile.objects(rules["toNotify"]) {
            let strikes = SharedRuleFile.objects(item["strikes"]).map {
                strike($0["id"] as? String ?? "", start: SharedRuleFile.date($0["start"]), end: SharedRuleFile.date($0["end"]))
            }
            let notices = StrikeReminderPlanner.toNotify(
                strikes, today: try XCTUnwrap(SharedRuleFile.date(item["today"])), notified: SharedRuleFile.days(item["notified"])
            )
            XCTAssertEqual(notices.map(\.id), item["expected"] as? [String], item["name"] as? String ?? "")
        }
        for item in SharedRuleFile.objects(rules["remember"]) {
            let strikes = ((item["strikes"] as? [String]) ?? []).map { strike($0, start: nil, end: nil) }
            XCTAssertEqual(
                StrikeReminderPlanner.remember(SharedRuleFile.days(item["notified"]), strikes: strikes, today: try XCTUnwrap(SharedRuleFile.date(item["today"]))),
                SharedRuleFile.days(item["expected"])
            )
        }
    }

    private func works(_ item: [String: Any]) throws -> RouteWorksNotice {
        let id = try XCTUnwrap(item["id"] as? String)
        let strike = try XCTUnwrap(item["mentionsStrike"] as? Bool)
        let notice = RailNotice(
            id: id, publisher: .rfi, title: strike ? "Sciopero \(id)" : "Lavori \(id)", body: nil, publishedAt: nil, link: nil,
            lines: [], cleartextHop: false, mentionsStrike: strike
        )
        let dates = try (item["dates"] as? [String: Any]).map { dates in
            NoticeDates(first: try XCTUnwrap(SharedRuleFile.date(dates["first"])), last: try XCTUnwrap(SharedRuleFile.date(dates["last"])))
        }
        let reasons = SharedRuleFile.objects(item["reasons"]).map { reason in
            RouteWorksReason(
                routeID: reason["routeId"] as? String ?? "", routeName: reason["routeName"] as? String ?? "",
                stations: reason["stations"] as? [String] ?? [], line: reason["line"] as? String
            )
        }
        return RouteWorksNotice(notice: notice, reasons: reasons, dates: dates)
    }

    func testTheWorksAlert() throws {
        let rules = try XCTUnwrap(try SharedRuleFile.load("evening-alerts-v1.json")["routeWorks"] as? [String: Any])
        for item in SharedRuleFile.objects(rules["nextRunAt"]) {
            let now = try SharedRuleFile.wall(item["now"])
            let expected = try SharedRuleFile.wall(item["expected"])
            let next = try XCTUnwrap(RouteWorksAlertPlanner.nextRun(after: now, immediate: try XCTUnwrap(item["immediate"] as? Bool)))
            switch next {
            case .now: XCTAssertEqual(now, expected, "\(item)")
            case let .at(date, hour): XCTAssertEqual(RailWallClock(date: date, minuteOfDay: hour * 60), expected, "\(item)")
            }
        }
        for item in SharedRuleFile.objects(rules["mayNotify"]) {
            XCTAssertEqual(RouteWorksAlertPlanner.mayNotify(try SharedRuleFile.wall(item["now"])), item["expected"] as? Bool, "\(item)")
        }
        for item in SharedRuleFile.objects(rules["quiet"]) {
            XCTAssertEqual(RouteWorksAlertPlanner.quiet(try SharedRuleFile.wall(item["now"])), item["expected"] as? Bool, "\(item)")
        }
        for item in SharedRuleFile.objects(rules["select"]) {
            let name = item["name"] as? String ?? ""
            let alert = RouteWorksAlertPlanner.select(
                try SharedRuleFile.objects(item["works"]).map(works),
                routeIDs: item["routeIds"] as? [String] ?? [],
                today: try XCTUnwrap(SharedRuleFile.date(item["today"])),
                notified: SharedRuleFile.days(item["notified"])
            )
            guard let expected = item["expected"] as? [String: Any] else {
                XCTAssertNil(alert, name)
                continue
            }
            let selected = try XCTUnwrap(alert, name)
            XCTAssertEqual(selected.day, SharedRuleFile.date(expected["day"]), name)
            XCTAssertEqual(selected.due.map(\.works.notice.id), SharedRuleFile.objects(expected["due"]).compactMap { $0["id"] as? String }, name)
            XCTAssertEqual(selected.due.map(\.reason.routeID), SharedRuleFile.objects(expected["due"]).compactMap { $0["routeId"] as? String }, name)
            XCTAssertEqual(selected.routeNames, expected["routeNames"] as? [String], name)
            if let shown = SharedRuleFile.int(expected["shown"]) {
                XCTAssertEqual(selected.items.count, shown, name)
                XCTAssertEqual(selected.hidden, SharedRuleFile.int(expected["hidden"]), name)
            }
        }
        for item in SharedRuleFile.objects(rules["keepUntil"]) {
            let dates = NoticeDates(first: try XCTUnwrap(SharedRuleFile.date(item["first"])), last: try XCTUnwrap(SharedRuleFile.date(item["last"])))
            XCTAssertEqual(
                RouteWorksAlertPlanner.keepUntil(dates, today: try XCTUnwrap(SharedRuleFile.date(item["today"]))),
                SharedRuleFile.date(item["expected"])
            )
        }
        for item in SharedRuleFile.objects(rules["remember"]) {
            let today = try XCTUnwrap(SharedRuleFile.date(item["today"]))
            let reason = RouteWorksReason(routeID: "casa", routeName: "Casa", stations: [], line: nil)
            let due = try SharedRuleFile.objects(item["due"]).map { entry -> RouteWorksAlertItem in
                let dates = NoticeDates(first: try XCTUnwrap(SharedRuleFile.date(entry["first"])), last: try XCTUnwrap(SharedRuleFile.date(entry["last"])))
                let notice = RailNotice(id: entry["id"] as? String ?? "", publisher: .rfi, title: "Lavori", body: nil, publishedAt: nil, link: nil,
                                        lines: [], cleartextHop: false, mentionsStrike: false)
                return RouteWorksAlertItem(works: RouteWorksNotice(notice: notice, reasons: [reason], dates: dates), reason: reason, dates: dates)
            }
            let alert = RouteWorksAlert(day: try XCTUnwrap(today.adding(days: 1)), due: due, routeNames: ["Casa"])
            XCTAssertEqual(RouteWorksAlertPlanner.remember(SharedRuleFile.days(item["notified"]), alert: alert, today: today), SharedRuleFile.days(item["expected"]))
        }
    }
}
