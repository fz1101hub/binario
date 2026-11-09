import Foundation
import XCTest
@testable import BinarioKit

/// «Più puntuale»: which listed train is suggested instead of a train often late at the destination
/// (the cases of the Android ReliableAlternativesTest and ResultsAlternativesTest).
final class ReliableAlternativesTests: XCTestCase {
    // MARK: The rule

    func testATrainOnTimeLessThan70PercentGetsATrainAtLeast20PointsBetter() throws {
        let late = candidate("a", "08:00", 20, 30)
        let punctual = candidate("b", "08:12", 27, 30)
        let found = ReliableAlternatives.find([late, punctual])
        XCTAssertEqual(Set(found.keys), ["a"])
        let suggestion = try XCTUnwrap(found["a"])
        XCTAssertEqual(suggestion.key, "b")
        XCTAssertEqual(suggestion.departure, at("08:12"))
        XCTAssertEqual(suggestion.stop.onTime, 27)
        XCTAssertEqual(suggestion.stop.samples, 30)
        // Exactly 70 percent is not «often late»; 69 is.
        XCTAssertNil(suggested(for: "a", candidate("a", "08:00", 70, 100), candidate("b", "08:12", 100, 100)))
        XCTAssertEqual(suggested(for: "a", candidate("a", "08:00", 69, 100), candidate("b", "08:12", 100, 100)), "b")
        XCTAssertEqual(suggested(for: "a", candidate("a", "08:00", 20, 29), candidate("b", "08:12", 29, 29)), "b")
        XCTAssertNil(suggested(for: "a", candidate("a", "08:00", 21, 30), candidate("b", "08:12", 30, 30)))
    }

    func testTheGapIsAtLeast20PointsCountedExactly() {
        // 7/10 − 5/10 is 0.19999999999999996 in floating point: still 20 points.
        XCTAssertEqual(suggested(for: "a", candidate("a", "08:00", 5, 10), candidate("b", "08:10", 7, 10)), "b")
        XCTAssertNil(suggested(for: "a", candidate("a", "08:00", 10, 20), candidate("b", "08:10", 13, 20)))
        // Different sample counts: 3/7 (42.9 %) against 9/14 (64.3 %) and 8/14 (57.1 %).
        XCTAssertEqual(suggested(for: "a", candidate("a", "08:00", 3, 7), candidate("b", "08:10", 9, 14)), "b")
        XCTAssertNil(suggested(for: "a", candidate("a", "08:00", 3, 7), candidate("b", "08:10", 8, 14)))
        // A train just as late, or later, is never suggested.
        XCTAssertNil(suggested(for: "a", candidate("a", "08:00", 10, 30), candidate("b", "08:10", 10, 30)))
    }

    func testOnlyTrainsLeavingWithin30MinutesBeforeOrAfterCount() {
        let late = candidate("a", "08:00", 10, 30)
        XCTAssertEqual(suggested(for: "a", late, candidate("b", "08:30", 30, 30)), "b")
        XCTAssertEqual(suggested(for: "a", late, candidate("b", "07:30", 30, 30)), "b")
        XCTAssertNil(suggested(for: "a", late, candidate("b", "08:31", 30, 30)))
        XCTAssertNil(suggested(for: "a", late, candidate("b", "07:29", 30, 30)))
        // Across midnight the departures are compared as instants.
        let lateNight = AlternativeCandidate(key: "n", departure: at("23:50"), stop: arrival(10, 30), stillToDepart: true)
        let afterMidnight = AlternativeCandidate(key: "m", departure: at("00:15", nextDay: true), stop: arrival(30, 30), stillToDepart: true)
        XCTAssertEqual(suggested(for: "n", lateNight, afterMidnight), "m")
        // A row without a readable departure takes no part, either way.
        XCTAssertNil(suggested(for: "a", late, candidate("b", nil, 30, 30)))
        XCTAssertNil(suggested(for: "a", candidate("a", nil, 10, 30), candidate("b", "08:10", 30, 30)))
    }

    func testBothTrainsNeedAtLeast5SamplesAtTheDestination() {
        XCTAssertNil(suggested(for: "a", candidate("a", "08:00", 1, 4), candidate("b", "08:10", 30, 30)))
        XCTAssertEqual(suggested(for: "a", candidate("a", "08:00", 1, 5), candidate("b", "08:10", 30, 30)), "b")
        XCTAssertNil(suggested(for: "a", candidate("a", "08:00", 10, 30), candidate("b", "08:10", 4, 4)))
        XCTAssertEqual(suggested(for: "a", candidate("a", "08:00", 10, 30), candidate("b", "08:10", 5, 5)), "b")
        // No statistics, or a train cancelled on every day observed (no delays at all): neither side.
        let unmeasured = AlternativeCandidate(key: "a", departure: at("08:00"), stop: nil, stillToDepart: true)
        XCTAssertNil(suggested(for: "a", unmeasured, candidate("b", "08:10", 30, 30)))
        let alwaysCancelled = AlternativeCandidate(
            key: "a",
            departure: at("08:00"),
            stop: StopReliability(stationCode: "S01700", samples: 0, onTime: 0, onTimeLimitMinutes: 15, meanDelayMinutes: 0, daysObserved: 12, daysCancelled: 12),
            stillToDepart: true
        )
        XCTAssertNil(suggested(for: "a", alwaysCancelled, candidate("b", "08:10", 30, 30)))
        let unmeasuredAlternative = AlternativeCandidate(key: "b", departure: at("08:10"), stop: nil, stillToDepart: true)
        XCTAssertNil(suggested(for: "a", candidate("a", "08:00", 10, 30), unmeasuredAlternative))
    }

    func testTiesGoToTheClosestDepartureThenTheEarlierOneThenTheMorePunctualOne() {
        let late = candidate("a", "08:00", 10, 30)
        // Closest first, however punctual the others are.
        XCTAssertEqual(
            suggested(for: "a", late, candidate("far", "08:20", 30, 30), candidate("near", "08:05", 25, 30), candidate("before", "07:50", 30, 30)),
            "near"
        )
        // As close before as after: the earlier one.
        XCTAssertEqual(suggested(for: "a", late, candidate("after", "08:10", 30, 30), candidate("before", "07:50", 25, 30)), "before")
        // Same departure: the more punctual one, then the one with more samples, then the key.
        XCTAssertEqual(suggested(for: "a", late, candidate("good", "08:10", 25, 30), candidate("best", "08:10", 29, 30)), "best")
        XCTAssertEqual(suggested(for: "a", late, candidate("fewer", "08:10", 9, 10), candidate("more", "08:10", 27, 30)), "more")
        XCTAssertEqual(suggested(for: "a", late, candidate("b2", "08:10", 27, 30), candidate("b1", "08:10", 27, 30)), "b1")
        // A train leaving at the same minute as the late one is the closest of all.
        XCTAssertEqual(suggested(for: "a", late, candidate("same", "08:00", 25, 30), candidate("after", "08:01", 30, 30)), "same")
    }

    func testTrainsAlreadyGoneAreNeverSuggestedAndGetNoSuggestion() {
        let late = candidate("a", "08:00", 10, 30)
        XCTAssertNil(suggested(for: "a", late, candidate("b", "08:10", 30, 30, stillToDepart: false)))
        XCTAssertEqual(
            suggested(for: "a", late, candidate("b", "08:05", 30, 30, stillToDepart: false), candidate("c", "08:20", 30, 30)),
            "c"
        )
        XCTAssertNil(suggested(for: "a", candidate("a", "08:00", 10, 30, stillToDepart: false), candidate("b", "08:10", 30, 30)))
    }

    func testEveryLateTrainOfTheListGetsItsOwnAlternativeNeverItself() {
        let found = ReliableAlternatives.find([
            candidate("a", "08:00", 10, 30),
            candidate("b", "08:20", 15, 30),
            candidate("c", "08:40", 29, 30),
            candidate("d", "09:30", 5, 30),
        ])
        // a: c is 40 minutes away, b only 17 points better; b: c, 20 minutes later; d: nothing within 30 minutes.
        XCTAssertEqual(found.mapValues { $0.key }, ["b": "c"])
        XCTAssertTrue(ReliableAlternatives.find([candidate("a", "08:00", 10, 30)]).isEmpty)
        XCTAssertTrue(ReliableAlternatives.find([]).isEmpty)
    }

    func testATrainOftenCancelledIsNotOfferedForBeingPunctualOnTheDaysItRuns() {
        let late = candidate("a", "08:00", 18, 30)
        // On time 10 times of 10, but cancelled on 10 more days: it runs and arrives on time on half the days.
        XCTAssertNil(suggested(for: "a", late, candidate("b", "08:10", 10, 10, cancelled: 10)))
        // One cancellation in 30 days still leaves it clearly more reliable.
        XCTAssertEqual(suggested(for: "a", late, candidate("b", "08:10", 28, 29, cancelled: 1)), "b")
        // On time 20 of 24 days and cancelled on 6 of 30: on time on 67 % of the days, only 7 points above «a» …
        XCTAssertNil(suggested(for: "a", late, candidate("b", "08:10", 20, 24, cancelled: 6)))
        // … but 27 points above a train cancelled on 10 of 30 days and on time 12 of the 20 it ran.
        XCTAssertEqual(suggested(for: "a", candidate("a", "08:00", 12, 20, cancelled: 10), candidate("b", "08:10", 20, 24, cancelled: 6)), "b")
        // Exactly 20 points of the days is enough, counted exactly; 23 of 30 is not.
        XCTAssertEqual(suggested(for: "a", late, candidate("b", "08:10", 24, 24, cancelled: 6)), "b")
        XCTAssertNil(suggested(for: "a", late, candidate("b", "08:10", 23, 23, cancelled: 7)))
        // No day known: no cancellation counted.
        let unknownDays = StopReliability(stationCode: "S00219", samples: 30, onTime: 27, onTimeLimitMinutes: 15, meanDelayMinutes: 2, daysObserved: 0, daysCancelled: 0)
        XCTAssertTrue(ReliableAlternatives.clearlyMoreReliable(unknownDays, than: arrival(18, 30)))
    }

    func testOnlyTrainsWithTheSameOnTimeLimitAreComparedFiveMinutesForAFrecciarossaFifteenForARegional() throws {
        let stats = try XCTUnwrap(NetworkReliabilityParser.parse(json: document([
            // Roma → Torino: 18 of 30 within 5 minutes (60 %), 29 of 30 within 15.
            """
            {"n": "9560", "o": "S08409", "c": "FR", "d": 30, "x": 0, "s": [
                ["S08409", 30, 0.5, [25, 28, 30, 30, 30, 30, 30, 30], 0],
                ["S00219", 30, 6.0, [6, 12, 18, 25, 29, 30, 30, 30], 0]
            ]}
            """,
            // A regional to Torino: 15 of 30 within 5 minutes, 27 of 30 within 15 (90 %).
            """
            {"n": "2041", "o": "S08409", "c": "REG", "d": 30, "x": 0, "s": [
                ["S08409", 30, 0.5, [25, 28, 30, 30, 30, 30, 30, 30], 0],
                ["S00219", 30, 7.0, [5, 10, 15, 22, 27, 29, 30, 30], 0]
            ]}
            """,
            // Another regional: 18 of 30 within 15 minutes (60 %).
            """
            {"n": "2043", "o": "S08409", "c": "REG", "d": 30, "x": 0, "s": [
                ["S08409", 30, 0.5, [25, 28, 30, 30, 30, 30, 30, 30], 0],
                ["S00219", 30, 12.0, [2, 5, 8, 12, 18, 24, 28, 30], 0]
            ]}
            """,
            // A Frecciarossa 27 of 30 within 5 minutes (90 %).
            """
            {"n": "9562", "o": "S08409", "c": "FR", "d": 30, "x": 0, "s": [
                ["S08409", 30, 0.5, [25, 28, 30, 30, 30, 30, 30, 30], 0],
                ["S00219", 30, 2.0, [20, 24, 27, 30, 30, 30, 30, 30], 0]
            ]}
            """,
        ])))
        func measured(_ number: String, _ time: String) throws -> AlternativeCandidate {
            let match = try XCTUnwrap(NetworkReliability.match(stats, number: number, alightingCode: "S00219", boardingCode: "S08409"))
            return AlternativeCandidate(
                key: number,
                departure: self.at(time),
                stop: NetworkReliability.at(match.train, index: match.alightingIndex),
                stillToDepart: true
            )
        }
        let fr9560 = try measured("9560", "08:00")
        let reg2041 = try measured("2041", "08:10")
        let frStop = try XCTUnwrap(fr9560.stop)
        let regStop = try XCTUnwrap(reg2041.stop)
        XCTAssertEqual(frStop.onTimeLimitMinutes, 5)
        XCTAssertEqual(frStop.onTime, 18)
        XCTAssertEqual(regStop.onTimeLimitMinutes, 15)
        XCTAssertEqual(regStop.onTime, 27)
        // The Frecciarossa at 5 minutes (60 %) does not get the regional at 15 (90 %): at 5 minutes it would be 50 %.
        XCTAssertTrue(ReliableAlternatives.oftenLate(frStop))
        XCTAssertNil(suggested(for: "9560", fr9560, reg2041))
        let regionalAtFive = StopReliability(stationCode: "S00219", samples: 30, onTime: 15, onTimeLimitMinutes: 5, meanDelayMinutes: 7, daysObserved: 30, daysCancelled: 0)
        XCTAssertFalse(ReliableAlternatives.clearlyBetter(regionalAtFive, than: frStop))
        // It gets the other Frecciarossa, at 5 minutes too (90 %).
        XCTAssertEqual(suggested(for: "9560", fr9560, reg2041, try measured("9562", "08:20")), "9562")
        // A regional at 15 minutes (60 %) gets the other regional (90 %), never the Frecciarossa at 5.
        XCTAssertNil(suggested(for: "2043", try measured("2043", "08:00"), try measured("9562", "08:20")))
        XCTAssertEqual(suggested(for: "2043", try measured("2043", "08:00"), reg2041, try measured("9562", "08:20")), "2041")
        // A regional at 90 % within 15 minutes is not «often late», though only half of its arrivals are within 5.
        XCTAssertNil(suggested(for: "2041", reg2041, try measured("9562", "08:20")))
    }

    // MARK: The listed trains

    func testATrenordRegionalOftenLateAtPaviaGetsTheNextOneByItsPublishedNumber() throws {
        // Trenord publishes its numbers after the line ("RE_11 - 2153"): the statistics know them as 2153.
        let late = service("RE_11 - 2153", "08:00")
        let punctual = service("RE_11 - 2157", "08:12")
        let result = try makeSnapshot([late, punctual])
        let stats = try trenordStats()
        let reliability = JourneyReliability.assess(result, stats: stats)
        let found = ReliableAlternatives.find(in: result, reliability: reliability.byServiceID, now: at("07:30"))
        XCTAssertEqual(Set(found.keys), ["trenord:RE_11 - 2153"])
        let suggestion = try XCTUnwrap(found["trenord:RE_11 - 2153"])
        XCTAssertEqual(suggestion.service, punctual)
        XCTAssertEqual(suggestion.stop.onTime, 28)
        XCTAssertEqual(suggestion.stop.samples, 30)
        XCTAssertEqual(suggestion.stop.onTimeLimitMinutes, 15)
        // Without the statistics of the rows (absent or withdrawn statistics): nothing.
        XCTAssertTrue(ReliableAlternatives.find(in: result, reliability: [:], now: at("07:30")).isEmpty)
    }

    func testTheClosestTrainStillToDepartIsSuggestedNeverOneAlreadyGoneOrNeverRunning() throws {
        let late = service("RE_11 - 2153", "08:00")
        let services = [service("2161", "07:45"), late, service("RE_11 - 2157", "08:12"), service("2165", "08:25")]
        XCTAssertEqual(try alternatives(services, now: "07:30"), ["trenord:RE_11 - 2153": "RE_11 - 2157"])
        // At 07:46 the 07:45 has left; the 08:12 is still the closest.
        XCTAssertEqual(try alternatives(services, now: "07:46"), ["trenord:RE_11 - 2153": "RE_11 - 2157"])
        // The 07:58 is the closest until a minute after it leaves, then the 08:12.
        let close = [service("2161", "07:58"), late, service("RE_11 - 2157", "08:12")]
        XCTAssertEqual(try alternatives(close, now: "07:58"), ["trenord:RE_11 - 2153": "2161"])
        XCTAssertEqual(try alternatives(close, now: "07:59"), ["trenord:RE_11 - 2153": "RE_11 - 2157"])
        // The 2159 was cancelled on every day observed: the 08:25 instead.
        XCTAssertEqual(try alternatives([late, service("2159", "08:05"), service("2165", "08:25")], now: "07:30"), ["trenord:RE_11 - 2153": "2165"])
        // The late train itself can be taken until a minute after its departure, then gets nothing.
        XCTAssertEqual(try alternatives(services, now: "08:00"), ["trenord:RE_11 - 2153": "RE_11 - 2157"])
        XCTAssertTrue(try alternatives(services, now: "08:01").isEmpty)
    }

    func testATrainIsGoneAMinuteAfterItsScheduledDeparture() {
        let departure = at("08:12")
        XCTAssertTrue(ReliableAlternatives.stillToDepart(departure, now: at("08:12")))
        XCTAssertTrue(ReliableAlternatives.stillToDepart(departure, now: at("08:12").addingTimeInterval(59)))
        XCTAssertFalse(ReliableAlternatives.stillToDepart(departure, now: at("08:13")))
        XCTAssertFalse(ReliableAlternatives.stillToDepart(nil, now: at("07:00")))
    }

    func testListedTimesArePlacedFromTheQueryTimeOnAcrossMidnight() throws {
        let services = [service("1", "23:50"), service("2", "23:50"), service("3", "00:15"), service("4", "8:7x")]
        let evening = try makeSnapshot(services, from: "23:40")
        let departures = ReliableAlternatives.scheduledDepartures(in: evening)
        XCTAssertEqual(departures["trenord:1"], at("23:50"))
        XCTAssertEqual(departures["trenord:2"], at("23:50"))
        XCTAssertEqual(departures["trenord:3"], at("00:15", nextDay: true))
        XCTAssertNil(departures["trenord:4"])
        // A late evening train gets one leaving after midnight.
        let night = [service("RE_11 - 2153", "23:50"), service("RE_11 - 2157", "00:15")]
        XCTAssertEqual(try alternatives(night, now: "23:40", from: "23:40"), ["trenord:RE_11 - 2153": "RE_11 - 2157"])
    }

    func testTheWindowIs30MinutesAndATrainAsLateGivesNothing() throws {
        let late = service("RE_11 - 2153", "08:00")
        // The 08:31 is 31 minutes after the 08:00: too far.
        XCTAssertTrue(try alternatives([late, service("2165", "08:31")], now: "07:30").isEmpty)
        XCTAssertEqual(try alternatives([late, service("2165", "08:30")], now: "07:30"), ["trenord:RE_11 - 2153": "2165"])
        // Two trains as late as each other: no suggestion.
        XCTAssertTrue(try alternatives([late, service("2169", "08:10")], now: "07:30").isEmpty)
    }

    func testTheSuggestedTrainIsNamedAsItsRowNamesIt() {
        XCTAssertEqual(ReliableAlternatives.trainLabel(service("16000", "08:00", category: "REG")), "REG 16000")
        XCTAssertEqual(ReliableAlternatives.trainLabel(service("S9 - 24902", "08:00", category: "S9")), "S9 - 24902")
        XCTAssertEqual(ReliableAlternatives.trainLabel(service("REX 2204", "08:00", category: "REX1")), "REX 2204")
        XCTAssertEqual(ReliableAlternatives.trainLabel(service("C3", "08:00", category: "C3")), "C3")
    }

    // MARK: - Fixtures

    private var calendar: Calendar { NetworkReliabilityDay.romeCalendar }

    /// Saturday 21 November 2026 (or the day after) at `time` ("08:12") in Rome.
    private func at(_ time: String, nextDay: Bool = false) -> Date {
        let parts = time.split(separator: ":").compactMap { Int($0) }
        let components = DateComponents(year: 2026, month: 11, day: nextDay ? 22 : 21, hour: parts[0], minute: parts[1])
        return calendar.date(from: components)!
    }

    /// A train at the destination: `onTime` of `samples` within 15 minutes, and `cancelled` more
    /// days cancelled.
    private func arrival(_ onTime: Int, _ samples: Int, cancelled: Int = 0) -> StopReliability {
        StopReliability(
            stationCode: "S00219",
            samples: samples,
            onTime: onTime,
            onTimeLimitMinutes: NetworkReliability.otherOnTimeMinutes,
            meanDelayMinutes: 4,
            daysObserved: samples + cancelled,
            daysCancelled: cancelled
        )
    }

    private func candidate(_ key: String, _ time: String?, _ onTime: Int, _ samples: Int, stillToDepart: Bool = true, cancelled: Int = 0) -> AlternativeCandidate {
        AlternativeCandidate(key: key, departure: time.map { self.at($0) }, stop: arrival(onTime, samples, cancelled: cancelled), stillToDepart: stillToDepart)
    }

    /// The key of the train suggested for `key`, or nil.
    private func suggested(for key: String, _ candidates: AlternativeCandidate...) -> String? {
        ReliableAlternatives.find(candidates)[key]?.key
    }

    private func service(_ number: String, _ time: String, category: String = "RE11") -> TrainService {
        TrainService(
            id: "trenord:\(number)",
            number: number,
            operatorName: "Trenord",
            category: category,
            destination: "Pavia",
            scheduledDeparture: time,
            expectedDeparture: time,
            status: "PROGRAMMED",
            platform: nil,
            delayMinutes: 0
        )
    }

    /// The trains listed from Milano Centrale to Pavia from `start` on.
    private func makeSnapshot(_ services: [TrainService], from start: String = "07:30") throws -> JourneySnapshot {
        let origin = StationIdentity(id: "S01700", name: "Milano Centrale", railwayCode: "S01700")
        let destination = StationIdentity(id: "S01860", name: "Pavia", railwayCode: "S01860")
        let departure = at(start)
        let query = try JourneyQuery(origin: origin, destination: destination, departure: departure)
        return JourneySnapshot(
            query: query,
            generatedAt: departure,
            expiresAt: departure.addingTimeInterval(12 * 60 * 60),
            operationalState: .programmed,
            provenance: DataProvenance(
                producer: .iosApp,
                nature: .programmed,
                sourceLabel: "Trenord",
                observedAt: departure,
                freshness: .unknown,
                confidence: .staticOnly
            ),
            services: services
        )
    }

    /// The suggested train's number for each listed train that has one, at `now`.
    private func alternatives(_ services: [TrainService], now: String, from start: String = "07:30") throws -> [String: String] {
        let result = try makeSnapshot(services, from: start)
        let stats = try trenordStats()
        let reliability = JourneyReliability.assess(result, stats: stats)
        return ReliableAlternatives.find(in: result, reliability: reliability.byServiceID, now: at(now)).mapValues { $0.service.number }
    }

    /// Trenord regionals from Milano Centrale to Pavia, and one cancelled on every day observed.
    private func trenordStats() throws -> NetworkReliabilityStats {
        try XCTUnwrap(NetworkReliabilityParser.parse(json: document([
            regional("2153", 15), regional("2157", 28), regional("2161", 27), regional("2165", 29), regional("2169", 14),
            #"{"n": "2159", "o": "S01700", "c": "RE", "d": 12, "x": 12, "s": []}"#,
        ])))
    }

    /// A Trenord regional Milano Centrale → Pavia, on time (15 minutes) `onTime` of 30 times at Pavia.
    private func regional(_ number: String, _ onTime: Int) -> String {
        """
        {"n": "\(number)", "o": "S01700", "c": "RE", "d": 30, "x": 0, "s": [
            ["S01700", 30, 0.5, [25, 28, 30, 30, 30, 30, 30, 30], 0],
            ["S01860", 30, 9.0, [\(onTime / 3), \(onTime / 2), \(onTime - 2), \(onTime - 1), \(onTime), 30, 30, 30], 0]
        ]}
        """
    }

    private func document(_ trains: [String]) -> [UInt8] {
        let text = """
            {"schema": "binario-punctuality-v1", "generatedAt": "2026-11-10T04:00:00Z", "windowStart": "2026-10-11",
             "windowEnd": "2026-11-09", "windowDays": 30, "source": "ViaggiaTreno, elaborazione Binario (non ufficiale)",
             "buckets": [0, 2, 5, 10, 15, 20, 30, 60], "trains": [\(trains.joined(separator: ",\n"))]}
            """
        return Array(text.utf8)
    }
}
