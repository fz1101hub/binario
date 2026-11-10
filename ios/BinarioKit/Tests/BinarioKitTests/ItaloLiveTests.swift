import Foundation
import XCTest
@testable import BinarioKit

final class ItaloLiveTests: XCTestCase {
    private let now = LiveTestDates.viaggiaTrenoNow
    /// 2026-10-16 10:00 UTC, 12:00 in Rome: the clock of Android's `ItaloPublicJsonTest`.
    private let noon = Date(timeIntervalSince1970: 1_792_144_800)

    private func makeSource(_ transport: FakeLiveTransport, now: Date) -> ItaloLiveSource {
        let clock = LiveTestClock(now)
        return ItaloLiveSource(
            client: LiveSourceClient(transport: transport, now: clock.closure),
            gate: LiveSourceGate(source: .italo, configuration: .init(minimumInterval: 30), pauses: LiveSourcePauses(), now: clock.closure),
            now: clock.closure
        )
    }

    private func run(_ text: String, number: String = "9920", day: Date? = nil) -> LiveRunStatus? {
        guard let root = LiveJSONObject(LiveJSON.parse(text)) else { return nil }
        return ItaloParser.run(root, query: LiveTrainNumberQuery(number), serviceDay: day ?? now, fetchedAt: now)
    }

    // MARK: Train number

    func testTheNumberLookupGivesTheFullRunAndTheFreshDelay() throws {
        let text = try LiveFixtures.text("italo-ricerca-treno-9920.json")
        let train = try XCTUnwrap(run(text))
        XCTAssertEqual(train.id, "italo:9920:\(LiveRailTime.millis(LiveTestDates.rome(2026, 10, 16, 8, 0)))")
        XCTAssertEqual(train.originName, "Torino Porta Nuova")
        XCTAssertEqual(train.destinationName, "Roma Termini")
        XCTAssertEqual(train.stops.count, 3)
        XCTAssertEqual(LiveRailTime.hhmm(train.scheduledDeparture), "08:00")
        XCTAssertEqual(LiveRailTime.hhmm(train.expectedDeparture), "08:06")
        XCTAssertEqual(train.delayMinutes, 6)
        XCTAssertTrue(train.operationalStatusVerified)
        XCTAssertEqual(train.state, .delayed)
        XCTAssertEqual(train.operatorID, "italo")
        XCTAssertEqual(train.stops[1].platform, "12")
        XCTAssertTrue(train.stops[1].passed)
        XCTAssertFalse(train.stops[2].passed, "a stop not reached yet has no actual time")
        XCTAssertEqual(LiveRailTime.hhmm(train.stops[2].expected), "12:06")
        XCTAssertEqual(train.observation.source, .italo)
        XCTAssertNil(run(text.replacingOccurrences(of: "\"TrainNumber\": 9920", with: "\"NumeroTreno\": 9920"), number: "9950"))
        XCTAssertNotNil(run(text.replacingOccurrences(of: "\"TrainNumber\": 9920", with: "\"NumeroTreno\": 9920")))
    }

    func testAStaleOrMissingObservationNeverTurnsAMissingDelayIntoZero() throws {
        let text = try LiveFixtures.text("italo-ricerca-treno-9920.json")
        for payload in [
            text.replacingOccurrences(of: "2026-10-16T08:00:00Z", with: "2026-10-16T07:50:00Z"),
            text.replacingOccurrences(of: "\"DelayAmount\": 6, ", with: ""),
            text.replacingOccurrences(of: "\"LastUpdate\": \"2026-10-16T08:00:00Z\",", with: ""),
        ] {
            let train = try XCTUnwrap(run(payload))
            XCTAssertFalse(train.operationalStatusVerified)
            XCTAssertNil(train.delayMinutes)
        }
    }

    func testPlaceholderMidnightActualsCannotVerifyADaytimeTrain() throws {
        let text = try LiveFixtures.text("italo-ricerca-treno-9920.json")
            .replacingOccurrences(of: "\"DelayAmount\": 6, \"RunningState\": \"IN CORSA\"", with: "\"DelayAmount\": 0, \"RunningState\": 0")
            .replacingOccurrences(of: "\"ActualDepartureTime\": \"08:06\"", with: "\"ActualDepartureTime\": \"00:00\"")
            .replacingOccurrences(of: "2026-10-16T09:06:00", with: "2026-10-16T00:00:00")
        let train = try XCTUnwrap(run(text))
        XCTAssertFalse(train.operationalStatusVerified)
        XCTAssertNil(train.delayMinutes)
        XCTAssertTrue(train.stops.allSatisfy { !$0.operationalStatusVerified })
    }

    func testAnotherNumberOrDayCannotStandInForTheAskedRun() throws {
        let text = try LiveFixtures.text("italo-ricerca-treno-9920.json")
        XCTAssertNil(run(text, number: "9950"))
        XCTAssertNil(run(text, day: LiveTestDates.rome(2026, 10, 17, 9, 0)))
        XCTAssertNil(run("{\"IsEmpty\": false}"))
        XCTAssertNil(run("[1, 2]"))
    }

    func testScheduledTimesCrossingMidnightKeepTheirDay() throws {
        let text = """
        {"IsEmpty": false, "LastUpdate": "2026-10-16T08:00:00Z", "TrainSchedule": {"TrainNumber": 9920, "DepartureDate": "2026-10-16",
         "Distruption": {"DelayAmount": 6, "RunningState": "IN CORSA"},
         "StazionePartenza": {"LocationCode": "A", "LocationDescription": "Origin", "StationNumber": 1, "EstimatedDepartureTime": "23:50", "EstimatedArrivalTime": "23:50"},
         "StazioniFerme": [],
         "StazioniNonFerme": [{"LocationCode": "B", "LocationDescription": "Destination", "StationNumber": 2, "EstimatedDepartureTime": "00:10", "EstimatedArrivalTime": "00:10"}]}}
        """
        let train = try XCTUnwrap(run(text))
        XCTAssertEqual(LiveRailTime.hhmm(train.scheduledDeparture), "23:50")
        XCTAssertEqual(LiveRailTime.isoDay(train.stops[1].scheduled), "2026-10-17")
        XCTAssertEqual(LiveRailTime.hhmm(train.stops[1].expected), "00:16")
    }

    func testTheSourceAsksOnlyTodaysThreeToFiveDigitNumbers() async throws {
        let text = try LiveFixtures.text("italo-ricerca-treno-9920.json")
        let transport = FakeLiveTransport { _ in FakeLiveTransport.ok(text) }
        let source = makeSource(transport, now: now)
        let answer = await source.runs(for: LiveTrainStatusRequest(number: "ITALO 9920", serviceDate: now))
        guard case let .answered(runs, _) = answer else { return XCTFail("expected a run, got \(answer)") }
        XCTAssertEqual(runs.map(\.number), ["9920"])
        XCTAssertEqual(transport.paths, ["https://italoinviaggio.italotreno.com/api/RicercaTrenoService?TrainNumber=9920"])
        XCTAssertEqual(transport.requests.first?.headers["Accept"], "application/json,text/html")
        _ = await source.runs(for: LiveTrainStatusRequest(number: "9920", serviceDate: now))
        XCTAssertEqual(transport.requests.count, 1, "the answer is reused for 30 seconds")
        let tomorrow = await source.runs(for: LiveTrainStatusRequest(number: "9922", serviceDate: now.addingTimeInterval(86_400)))
        XCTAssertEqual(tomorrow, .answered([], complete: true))
        let short = await source.runs(for: LiveTrainStatusRequest(number: "12", serviceDate: now))
        XCTAssertEqual(short, .answered([], complete: true))
        XCTAssertEqual(transport.requests.count, 1)

        let empty = await makeSource(FakeLiveTransport { _ in FakeLiveTransport.ok("{\"IsEmpty\":true}") }, now: now)
            .runs(for: LiveTrainStatusRequest(number: "9920", serviceDate: now))
        XCTAssertEqual(empty, .answered([], complete: true))
        let offline = await makeSource(FakeLiveTransport { _ in .failure(.network) }, now: now)
            .runs(for: LiveTrainStatusRequest(number: "9920", serviceDate: now))
        XCTAssertEqual(offline, .unavailable(.sourceUnavailable))
    }

    // MARK: Station board

    func testThePublishedBoardGivesDelayPlatformAndUpdatedTime() throws {
        let text = try LiveFixtures.text("italo-ricerca-stazione-roma-termini.json")
        let window = LiveBoardWindow(
            station: Station(id: "S08409", name: "Roma Termini"), from: noon.addingTimeInterval(-600),
            to: noon.addingTimeInterval(6 * 3_600), lateSince: noon.addingTimeInterval(-7_200)
        )
        let rows = try XCTUnwrap(ItaloParser.departures(text, stationNames: ["Roma Termini"], window: window, fetchedAt: noon))
        XCTAssertEqual(rows.map(\.number), ["9923", "9925"], "a two-digit number and an impossible time are dropped")
        let delayed = rows[0]
        XCTAssertEqual(delayed.expected.map { LiveRailTime.hhmm($0.value) }, "12:42")
        XCTAssertEqual(delayed.delayMinutes, 12)
        XCTAssertEqual(delayed.platform?.value, "5")
        XCTAssertEqual(delayed.status?.value, .delayed)
        XCTAssertEqual(delayed.operatorID, "italo")
        XCTAssertEqual(delayed.observation.expiresAt, noon.addingTimeInterval(60))
        XCTAssertEqual(rows[1].status?.value, .cancelled)
        XCTAssertNil(rows[1].expected)
        XCTAssertNil(rows[1].platform)

        let zero = text.replacingOccurrences(of: "\"Ritardo\":12", with: "\"Ritardo\":0")
        XCTAssertEqual(ItaloParser.departures(zero, stationNames: ["Roma Termini"], window: window, fetchedAt: noon)?.first?.status?.value, .onTime)
        let unpublished = text.replacingOccurrences(of: "\"Ritardo\":12", with: "\"Ritardo\":null")
        XCTAssertNil(ItaloParser.departures(unpublished, stationNames: ["Roma Termini"], window: window, fetchedAt: noon)?.first?.status)
        for stale in ["11:50:00", "not-a-timestamp"] {
            let old = text.replacingOccurrences(of: "\"LastUpdate\":\"12:00:00\"", with: "\"LastUpdate\":\"\(stale)\"")
            let row = ItaloParser.departures(old, stationNames: ["Roma Termini"], window: window, fetchedAt: noon)?.first
            XCTAssertNil(row?.status, stale)
            XCTAssertNil(row?.expected, stale)
        }
        for other in ["Milano Centrale", "Roma Tiburtina"] {
            let foreign = text.replacingOccurrences(of: "{\"DescrizioneLocalita\":\"Roma Termini\"", with: "{\"DescrizioneLocalita\":\"\(other)\"")
            XCTAssertEqual(ItaloParser.departures(foreign, stationNames: ["Roma Termini"], window: window, fetchedAt: noon), [], other)
        }
        XCTAssertNil(ItaloParser.departures("<html>non JSON</html>", stationNames: ["Roma Termini"], window: window, fetchedAt: noon))
        XCTAssertEqual(ItaloParser.departures("{\"IsEmpty\":true}", stationNames: ["Roma Termini"], window: window, fetchedAt: noon), [])
    }

    func testTheStationListIsReadFromThePassengerPage() throws {
        let stations = ItaloParser.catalogue(try LiveFixtures.text("italo-stazione-catalogo.html"))
        let byCode = Dictionary(uniqueKeysWithValues: stations.map { ($0.code, $0) })
        XCTAssertEqual(Set(byCode.keys), ["AGR", "BC_", "NEW", "RMT", "BO2", "MC_"], "malformed codes and slugs are left out")
        XCTAssertEqual(byCode["AGR"]?.name, "Agropoli Castellabate")
        XCTAssertTrue(byCode["AGR"]?.aliases.contains("agropoli") == true)
        XCTAssertEqual(byCode["BC_"]?.name, "Bologna Centrale")
        XCTAssertEqual(byCode["BC_"]?.slug, "bologna")
        XCTAssertEqual(byCode["NEW"]?.name, "Genova Piazza Principe")
        XCTAssertEqual(byCode["BO2"]?.slug, "bologna-centrale")
        XCTAssertEqual(byCode["MC_"]?.name, "Milano Centrale")
        XCTAssertEqual(ItaloParser.slug("Firenze S. Maria Novella"), "firenze-s-maria-novella")
        XCTAssertEqual(ItaloParser.slug("  Forlì--Cesena "), "forli-cesena")
    }

    func testTheBoardSourceFindsTheOperatorCodeAndAsksItsPublicBoard() async throws {
        let page = "<span data-ntv-name=\"station-to-url\" data-ntv-type=\"Json\">[{\"code\":\"RMT\",\"urlCoding\":\"roma-termini\"},"
            + "{\"code\":\"AAA\",\"urlCoding\":\"castello\"},{\"code\":\"BBB\",\"urlCoding\":\"castello\"}]</span>"
        let board = try LiveFixtures.text("italo-ricerca-stazione-roma-termini.json")
        let transport = FakeLiveTransport { request in
            request.url.path == "/it/stazione" ? FakeLiveTransport.ok(page) : FakeLiveTransport.ok(board)
        }
        let source = makeSource(transport, now: noon)
        let window = LiveBoardWindow(
            station: Station(id: "S08409", name: "Roma Termini"), from: noon.addingTimeInterval(-600),
            to: noon.addingTimeInterval(6 * 3_600), lateSince: nil
        )
        let answer = await source.departures(for: window)
        guard case let .answered(rows, _) = answer else { return XCTFail("expected rows, got \(answer)") }
        XCTAssertEqual(rows.first?.number, "9923")
        XCTAssertEqual(transport.paths, [
            "https://italoinviaggio.italotreno.com/it/stazione",
            "https://italoinviaggio.italotreno.com/api/RicercaStazioneService?CodiceStazione=RMT",
        ])
        let ambiguous = await source.departures(for: LiveBoardWindow(
            station: Station(id: "S77777", name: "Castello"), from: window.from, to: window.to, lateSince: nil
        ))
        XCTAssertEqual(ambiguous, .unavailable(.notCovered), "two stations with the name: none is guessed")
        XCTAssertEqual(transport.requests.count, 2, "the station list is read once a day")
    }

    func testWithoutTheStationListTheReviewedCodesAreUsed() async throws {
        let board = try LiveFixtures.text("italo-ricerca-stazione-roma-termini.json")
        let transport = FakeLiveTransport { request in
            request.url.path == "/it/stazione" ? FakeLiveTransport.status(503) : FakeLiveTransport.ok(board)
        }
        let window = LiveBoardWindow(
            station: Station(id: "S08409", name: "Roma Termini"), from: noon.addingTimeInterval(-600),
            to: noon.addingTimeInterval(6 * 3_600), lateSince: nil
        )
        _ = await makeSource(transport, now: noon).departures(for: window)
        XCTAssertEqual(transport.paths.last, "https://italoinviaggio.italotreno.com/api/RicercaStazioneService?CodiceStazione=RMT")
    }

    func testItalianObservationTimesAreRead() {
        XCTAssertEqual(ItaloParser.observation("", now: noon), noon, "a current board without a timestamp")
        XCTAssertEqual(ItaloParser.observation("12:00:00", now: noon), noon)
        XCTAssertEqual(ItaloParser.observation("12:05", now: noon), LiveTestDates.rome(2026, 10, 15, 12, 5), "a time ahead is yesterday's")
        XCTAssertEqual(ItaloParser.observation("16/10/2026 11:59:30", now: noon), LiveTestDates.rome(2026, 10, 16, 11, 59, 30))
        XCTAssertEqual(ItaloParser.observation("/Date(1792144800000)/", now: noon), noon)
        XCTAssertEqual(ItaloParser.observation("2026-10-16T10:00:00Z", now: noon), noon)
        XCTAssertEqual(ItaloParser.observation("2026-10-16T12:00:00+02:00", now: noon), noon)
        XCTAssertEqual(ItaloParser.observation("2026-10-16T12:00:00", now: noon), noon)
        XCTAssertNil(ItaloParser.observation("2026-10-16", now: noon))
        XCTAssertNil(ItaloParser.observation("2026-02-30T12:00:00", now: noon))
        XCTAssertEqual(ItaloParser.dateTime("2026-10-16", day: noon), LiveTestDates.rome(2026, 10, 16, 0, 0))
        XCTAssertEqual(ItaloParser.dateTime("08:06", day: noon), LiveTestDates.rome(2026, 10, 16, 8, 6))
        XCTAssertNil(ItaloParser.dateTime("ieri", day: noon))
    }
}
