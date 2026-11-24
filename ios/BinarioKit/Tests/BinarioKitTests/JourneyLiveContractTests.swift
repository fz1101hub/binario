import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/journey-live-v1.json and results-paging-v1.json, with their tables in
/// contracts/shared-data/v1, read as Android's JourneyLiveContractTest and ResultsPagingContractTest read them.
final class JourneyLiveContractTests: XCTestCase {
    private var rome: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Rome") ?? .current
        return calendar
    }

    private func date(_ value: String, day: String) throws -> Date {
        let text = value.contains("T") ? value : "\(day)T\(value)"
        let parts = text.split(whereSeparator: { "-T:".contains($0) }).compactMap { Int($0) }
        XCTAssertEqual(parts.count, 5, text)
        let components = DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: parts[3], minute: parts[4])
        return try XCTUnwrap(rome.date(from: components), text)
    }

    /// "18:45" on the day of `start`, the day after when it is before `start` (a stop past the run's midnight).
    private func stopDate(_ clock: String, start: Date) throws -> Date {
        let parts = clock.split(separator: ":").compactMap { Int($0) }
        var value = try XCTUnwrap(rome.date(bySettingHour: parts[0], minute: parts[1], second: 0, of: start))
        if value < start { value = try XCTUnwrap(rome.date(byAdding: .day, value: 1, to: value)) }
        return value
    }

    /// The occurrence of "HH:mm" nearest to `reference` (Android's `LiveStopTimes.nearest`).
    private func nearest(_ clock: String, to reference: Date) throws -> Date {
        let parts = clock.split(separator: ":").compactMap { Int($0) }
        let candidate = try XCTUnwrap(rome.date(bySettingHour: parts[0], minute: parts[1], second: 0, of: reference))
        if candidate.timeIntervalSince(reference) > 12 * 3_600 { return try XCTUnwrap(rome.date(byAdding: .day, value: -1, to: candidate)) }
        if reference.timeIntervalSince(candidate) > 12 * 3_600 { return try XCTUnwrap(rome.date(byAdding: .day, value: 1, to: candidate)) }
        return candidate
    }

    private func optionalDate(_ value: Any?, day: String) throws -> Date? {
        guard let text = value as? String else { return nil }
        return try date(text, day: day)
    }

    private func live(_ value: Any?, day: String) throws -> JourneyLegLive? {
        guard let live = value as? [String: Any] else { return nil }
        return JourneyLegLive(
            expectedDeparture: try optionalDate(live["expectedDeparture"], day: day),
            expectedArrival: try optionalDate(live["expectedArrival"], day: day),
            cancelled: live["cancelled"] as? Bool ?? false
        )
    }

    private func leg(
        mode: JourneyLegMode, number: String?, from: String, to: String, departure: Date, arrival: Date, serviceDate: Int?, id: String
    ) -> JourneyPlanLeg {
        JourneyPlanLeg(
            mode: mode, runID: id, sourceID: "contract", tripID: id, originID: "\(id):from", originLabel: from,
            destinationID: "\(id):to", destinationLabel: to, departure: departure, arrival: arrival, publicNumber: number,
            hasTrainNumber: number != nil, lineLabel: nil, operatorName: nil, serviceKind: .regional, serviceDate: serviceDate,
            wheelchair: nil, bikesAllowed: nil, continuations: [], changeMinutes: nil
        )
    }

    func testTheLookupAndItsRefreshReadTheSharedTable() throws {
        let table = try sharedData("journey-live-v1.json")
        XCTAssertEqual(JourneyLiveCandidates.lookBack, TimeInterval(try XCTUnwrap(table["lookBackMinutes"] as? Int) * 60))
        XCTAssertEqual(JourneyLiveCandidates.lookAhead, TimeInterval(try XCTUnwrap(table["lookAheadMinutes"] as? Int) * 60))
        XCTAssertEqual(JourneyLiveCandidates.maximumTrains, table["maximumTrains"] as? Int)
        XCTAssertEqual(JourneyLiveCandidates.sameRunTolerance, TimeInterval(try XCTUnwrap(table["sameRunToleranceMinutes"] as? Int) * 60))
        XCTAssertEqual(JourneyLiveLookup.parallelLookups, table["parallelLookups"] as? Int)
        XCTAssertEqual(JourneyLiveLookup.trainTimeout, TimeInterval(try XCTUnwrap(table["trainTimeoutSeconds"] as? Int)))
        XCTAssertEqual(JourneyLiveRefresh.maximumAge, TimeInterval(try XCTUnwrap(table["maximumAgeMinutes"] as? Int) * 60))
        XCTAssertEqual(JourneyLiveRefresh.minimumInterval, TimeInterval(try XCTUnwrap(table["minimumIntervalSeconds"] as? Int)))
    }

    func testTheRiskOfAChange() throws {
        let rules = try sharedRules("journey-live-v1.json")
        let day = try XCTUnwrap(rules["day"] as? String)
        let cases = try XCTUnwrap(rules["transferRisk"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 11)
        for item in cases {
            let name = item["name"] as? String ?? "?"
            let walk = try XCTUnwrap(item["walkMinutes"] as? Int)
            let assessment = TransferRiskCalculator.assess(
                scheduledArrival: try date(try XCTUnwrap(item["scheduledArrival"] as? String), day: day),
                live: try live(item["arrivingLive"], day: day),
                scheduledDeparture: try date(try XCTUnwrap(item["scheduledDeparture"] as? String), day: day),
                nextLive: try live(item["leavingLive"], day: day),
                walkSeconds: TimeInterval(walk * 60), changeMinutes: try XCTUnwrap(item["changeMinutes"] as? Int)
            )
            let expected = try XCTUnwrap(item["expected"] as? [String: Any])
            let levels: [String: TransferRiskLevel] = ["OK": .ok, "AT_RISK": .atRisk, "MISSED": .missed, "UNKNOWN": .unknown]
            XCTAssertEqual(assessment.level, levels[expected["level"] as? String ?? ""], name)
            XCTAssertEqual(assessment.marginMinutes, expected["marginMinutes"] as? Int, name)
            XCTAssertEqual(assessment.expectedArrival, try date(try XCTUnwrap(expected["expectedArrival"] as? String), day: day), name)
            XCTAssertEqual(assessment.expectedDeparture, try date(try XCTUnwrap(expected["expectedDeparture"] as? String), day: day), name)
        }
    }

    private func run(_ value: [String: Any], number: String, day: String) throws -> LiveRunStatus {
        let start = try date(try XCTUnwrap(value["start"] as? String), day: day)
        let stops = try XCTUnwrap(value["stops"] as? [[String: Any]]).map { stop -> LiveStopStatus in
            let scheduled = try stopDate(try XCTUnwrap(stop["scheduled"] as? String), start: start)
            let expected = try nearest(try XCTUnwrap(stop["expected"] as? String), to: scheduled)
            let verified = stop["verified"] as? Bool ?? false
            return LiveStopStatus(
                stationCode: nil, stationName: try XCTUnwrap(stop["station"] as? String), scheduled: scheduled, scheduledDeparture: nil,
                expected: expected, actual: nil, actualDeparture: nil, platform: nil, scheduledPlatform: nil,
                delayMinutes: Int(expected.timeIntervalSince(scheduled) / 60), passed: false, skipped: false, operationalStatusVerified: verified
            )
        }
        let verified = value["verified"] as? Bool ?? false
        let states: [String: LiveRunState] = ["ON_TIME": .onTime, "DELAYED": .delayed, "CANCELLED": .cancelled]
        let state = try XCTUnwrap(states[value["status"] as? String ?? ""])
        let observation = LiveObservation(source: .viaggiaTreno, fetchedAt: start, observedAt: start, expiresAt: start.addingTimeInterval(30))
        return LiveRunStatus(
            id: try XCTUnwrap(value["id"] as? String), number: number, category: "IC", operatorName: "Trenitalia", operatorID: "trenitalia",
            originCode: nil, originName: stops.first?.stationName ?? "", destinationName: stops.last?.stationName ?? "",
            scheduledDeparture: start, expectedDeparture: stops.first?.expected ?? start, platform: nil, scheduledPlatform: nil,
            state: state, cancellation: state == .cancelled ? .full : .none,
            delayMinutes: verified ? value["delayMinutes"] as? Int : nil, operationalStatusVerified: verified,
            lastObservation: nil, stops: stops, observation: observation, viaggiaTrenoRun: nil
        )
    }

    func testTheRunOfANumberSearchThatIsTheLegs() throws {
        let rules = try sharedRules("journey-live-v1.json")
        let day = try XCTUnwrap(rules["day"] as? String)
        let cases = try XCTUnwrap(rules["liveRuns"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 8)
        for item in cases {
            let name = item["name"] as? String ?? "?"
            let legCase = try XCTUnwrap(item["leg"] as? [String: Any])
            let number = try XCTUnwrap(legCase["number"] as? String)
            let serviceDate = Int(try XCTUnwrap(legCase["serviceDate"] as? String).replacingOccurrences(of: "-", with: ""))
            let journeyLeg = leg(
                mode: .train, number: number, from: try XCTUnwrap(legCase["from"] as? String), to: try XCTUnwrap(legCase["to"] as? String),
                departure: try date(try XCTUnwrap(legCase["departure"] as? String), day: day),
                arrival: try date(try XCTUnwrap(legCase["arrival"] as? String), day: day), serviceDate: serviceDate, id: "contract:\(number)"
            )
            let runs = try XCTUnwrap(item["runs"] as? [[String: Any]]).map { try run($0, number: number, day: day) }
            let found = JourneyLiveCandidates.liveFor(journeyLeg, runs: runs)
            guard let expected = item["expected"] as? [String: Any] else {
                XCTAssertNil(found, name)
                continue
            }
            let value = try XCTUnwrap(found, name)
            XCTAssertEqual(value.expectedDeparture, try optionalDate(expected["expectedDeparture"], day: day), name)
            XCTAssertEqual(value.expectedArrival, try optionalDate(expected["expectedArrival"], day: day), name)
            XCTAssertEqual(value.cancelled, expected["cancelled"] as? Bool, name)
        }
    }

    func testTheLegsLookedUpAndTheirTrains() throws {
        let rules = try sharedRules("journey-live-v1.json")
        let day = try XCTUnwrap(rules["day"] as? String)
        let cases = try XCTUnwrap(rules["candidates"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 2)
        let modes: [String: JourneyLegMode] = ["TRAIN": .train, "WALK": .walk, "REPLACEMENT_BUS": .replacementBus]
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = rome.timeZone
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm"
        for item in cases {
            let name = item["name"] as? String ?? "?"
            let options = try XCTUnwrap(item["options"] as? [[[String: Any]]]).enumerated().map { index, legs -> JourneyPlanOption in
                let built = try legs.enumerated().map { legIndex, value -> JourneyPlanLeg in
                    let id = "contract:\(index):\(legIndex)"
                    return leg(
                        mode: try XCTUnwrap(modes[value["mode"] as? String ?? ""]), number: value["number"] as? String,
                        from: "\(id):from", to: "\(id):to", departure: try date(try XCTUnwrap(value["departure"] as? String), day: day),
                        arrival: try date(try XCTUnwrap(value["arrival"] as? String), day: day), serviceDate: nil, id: id
                    )
                }
                let first = try XCTUnwrap(built.first), last = try XCTUnwrap(built.last)
                return JourneyPlanOption(id: "option-\(index)", originID: first.originID, destinationID: last.destinationID,
                                         departure: first.departure, arrival: last.arrival, legs: built, sourceIDs: ["contract"])
            }
            let legs = JourneyLiveCandidates.legs(options, now: try date(try XCTUnwrap(item["now"] as? String), day: day))
            let expected = try XCTUnwrap(item["expected"] as? [String: Any])
            XCTAssertEqual(legs.map { "\($0.publicNumber ?? "")@\(formatter.string(from: $0.departure))" }, expected["legs"] as? [String], name)
            let trains = try XCTUnwrap(expected["trains"] as? [[String: String]]).map { train in
                "\(train["number"] ?? "")@\((train["serviceDate"] ?? "").replacingOccurrences(of: "-", with: ""))"
            }
            XCTAssertEqual(JourneyLiveCandidates.trains(legs).map { "\($0.number)@\($0.serviceDate)" }, trains, name)
        }
    }

    func testHowLongTheAnswersAreShownAndHowOftenTheyAreAsked() throws {
        let rules = try sharedRules("journey-live-v1.json")
        let refresh = try XCTUnwrap(rules["refresh"] as? [String: Any])
        let now = Date(timeIntervalSince1970: 1_795_000_000)
        for item in try XCTUnwrap(refresh["current"] as? [[String: Any]]) {
            let fetched = try XCTUnwrap(item["fetchedSecondsAgo"] as? [String: Int])
            let keys = item["legs"] as? [String] ?? Array(fetched.keys)
            let legs = Dictionary(uniqueKeysWithValues: keys.map { ($0, JourneyLegLive(cancelled: true)) })
            let fetchedAt = fetched.mapValues { now.addingTimeInterval(-TimeInterval($0)) }
            XCTAssertEqual(JourneyLiveRefresh.current(legs, fetchedAt: fetchedAt, now: now).keys.sorted(), (item["expected"] as? [String])?.sorted(),
                           item["name"] as? String ?? "?")
        }
        for item in try XCTUnwrap(refresh["isDue"] as? [[String: Any]]) {
            let started = (item["startedSecondsAgo"] as? Int).map { now.addingTimeInterval(-TimeInterval($0)) }
            XCTAssertEqual(JourneyLiveRefresh.isDue(lastStartedAt: started, now: now), item["expected"] as? Bool, item["name"] as? String ?? "?")
        }
        for item in try XCTUnwrap(refresh["focusedFirst"] as? [[String: Any]]) {
            let ids = try XCTUnwrap(item["ids"] as? [String])
            XCTAssertEqual(JourneyLiveRefresh.focusedFirst(ids, focusedID: item["focused"] as? String) { $0 }, item["expected"] as? [String],
                           item["name"] as? String ?? "?")
        }
    }

    // MARK: - «Più presto» / «Più tardi»

    func testThePagingReadsTheSharedTable() throws {
        let table = try sharedData("results-paging-v1.json")
        XCTAssertEqual(ResultsPaging.earlierSpansMinutes, table["earlierSpansMinutes"] as? [Int])
        XCTAssertEqual(ResultsPaging.earlierPage, table["earlierPage"] as? Int)
        XCTAssertEqual(ResultsPaging.maximumFillLoads, table["maximumFillLoads"] as? Int)
        XCTAssertEqual(ResultsPaging.minimumEarlierSpanMinutes, table["minimumEarlierSpanMinutes"] as? Int)
        XCTAssertEqual(ResultsPaging.earlierJourneyResults, table["earlierJourneyResults"] as? Int)
    }

    /// Minutes of the Italian clock from the start of `day` ("2026-11-24" unless a case says its day), as dates.
    private func minutes(_ value: Int, day: String = "2026-11-24") throws -> Date {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        let start = try XCTUnwrap(rome.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])))
        let shifted = try XCTUnwrap(rome.date(byAdding: .day, value: value / 1_440, to: start))
        return try XCTUnwrap(rome.date(bySettingHour: value % 1_440 / 60, minute: value % 60, second: 0, of: shifted))
    }

    private func source(_ value: [String: Any], day: String = "2026-11-24") throws -> (Date) async -> [Date]? {
        let departures = try XCTUnwrap(value["departures"] as? [Int]).sorted().map { try minutes($0, day: day) }
        let pageSize = try XCTUnwrap(value["pageSize"] as? Int)
        let fails = value["fails"] as? Bool ?? false
        let failsBefore = try (value["failsBefore"] as? Int).map { try minutes($0, day: day) }
        return { from in
            if fails { return nil }
            if let failsBefore, from < failsBefore { return nil }
            return Array(departures.filter { $0 >= from }.prefix(pageSize))
        }
    }

    private func expected(_ item: [String: Any]) throws -> [Date]? {
        let day = item["day"] as? String ?? "2026-11-24"
        return try (item["expected"] as? [Int]).map { try $0.map { try minutes($0, day: day) } }
    }

    func testLater() async throws {
        let cases = try XCTUnwrap(try sharedRules("results-paging-v1.json")["later"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 2)
        for item in cases {
            let load = try source(try XCTUnwrap(item["source"] as? [String: Any]))
            let last = try minutes(try XCTUnwrap(item["last"] as? Int))
            let wanted = try expected(item)
            let found = await ResultsPaging.later(last, departureOf: { $0 }, load: load)
            XCTAssertEqual(found, wanted, item["name"] as? String ?? "?")
        }
    }

    func testEarlier() async throws {
        let cases = try XCTUnwrap(try sharedRules("results-paging-v1.json")["earlier"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 8)
        for item in cases {
            let day = item["day"] as? String ?? "2026-11-24"
            let load = try source(try XCTUnwrap(item["source"] as? [String: Any]), day: day)
            let first = try minutes(try XCTUnwrap(item["first"] as? Int), day: day)
            let notBefore = try minutes(try XCTUnwrap(item["notBefore"] as? Int), day: day)
            let count = try XCTUnwrap(item["wanted"] as? Int)
            let wanted = try expected(item)
            let found = await ResultsPaging.earlier(first, departureOf: { $0 }, notBefore: notBefore, wanted: count, load: load)
            XCTAssertEqual(found, wanted, item["name"] as? String ?? "?")
        }
    }

    // MARK: - The contract files

    private func sharedRules(_ name: String) throws -> [String: Any] {
        try file(repositoryRoot.appendingPathComponent("contracts/shared-rules/v1").appendingPathComponent(name))
    }

    private func sharedData(_ name: String) throws -> [String: Any] {
        try file(repositoryRoot.appendingPathComponent("contracts/shared-data/v1").appendingPathComponent(name))
    }

    private func file(_ url: URL) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any], url.lastPathComponent)
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
