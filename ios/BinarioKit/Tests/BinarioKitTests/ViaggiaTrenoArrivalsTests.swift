import Foundation
import XCTest
@testable import BinarioKit

/// ViaggiaTreno's arrivals board, the run of a board row and the per-train notices, as Android's
/// `ViaggiaTrenoRealtimeAdapter` (`arrivi`, `getStationBoardRun`) and `ViaggiaTrenoTrainNoticesAdapter`.
final class ViaggiaTrenoArrivalsTests: XCTestCase {
    private let base = "https://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/"
    private let now = LiveTestDates.viaggiaTrenoNow
    private let runDate = LiveTestDates.runDateMillis
    private let ostiense = Station(id: "S08406", name: "Roma Ostiense", railwayCode: "S08406")

    private func source(_ transport: FakeLiveTransport, clock: LiveTestClock? = nil) throws -> ViaggiaTrenoLiveSource {
        let current = clock ?? LiveTestClock(now)
        return ViaggiaTrenoLiveSource(
            client: LiveSourceClient(transport: transport, now: current.closure),
            gate: LiveSourceGate(source: .viaggiaTreno, configuration: .init(minimumInterval: 30), pauses: LiveSourcePauses(), now: current.closure),
            now: current.closure, notices: ViaggiaTrenoTrainNotices(rules: try XCTUnwrap(NoticeRules.standard))
        )
    }

    /// The 10:30 arrival of run 12345 (Viterbo → Roma Tiburtina) at Roma Ostiense, six minutes late.
    private func arrivalsRow() -> String {
        """
        [{"numeroTreno": 12345, "codOrigine": "S08001", "dataPartenzaTreno": \(runDate), "orarioArrivo": 1792139400000,
          "origine": "Viterbo", "destinazione": "Roma Tiburtina", "ritardo": 6, "ultimoRilev": 1792137570000,
          "binarioProgrammatoArrivoDescrizione": "5", "binarioEffettivoArrivoDescrizione": "7"}]
        """
    }

    func testTheArrivalsBoardAsksArriviAndNamesTheOrigin() async throws {
        let board = arrivalsRow()
        let transport = FakeLiveTransport { _ in FakeLiveTransport.ok(board) }
        let window = LiveBoardWindow(station: ostiense, from: now, to: now.addingTimeInterval(3_600), lateSince: nil, kind: .arrivals)
        let answer = try await source(transport).departures(for: window)
        guard case let .answered(rows, _) = answer else { return XCTFail("expected rows, got \(answer)") }
        XCTAssertEqual(transport.paths, [base + "arrivi/S08406/Fri%20Oct%2016%202026%2010%3A00%3A00%20GMT%2B0200"])
        let row = try XCTUnwrap(rows.first)
        XCTAssertEqual(row.destination, "Viterbo", "an arrivals row names the train's origin")
        XCTAssertEqual(LiveRailTime.hhmm(row.scheduled), "10:30")
        XCTAssertEqual(row.delayMinutes, 6)
        XCTAssertEqual(row.platform?.value, "7")
        XCTAssertEqual(row.scheduledPlatform, "5")
        XCTAssertEqual(row.viaggiaTrenoRun?.nativeID, "S08001:12345:\(runDate)")
    }

    func testABoardRowWithItsRunIsAskedDirectlyAndGivesItsStopAtTheStation() async throws {
        let detail = try LiveFixtures.text("viaggiatreno-andamentoTreno-12345.json")
        let transport = FakeLiveTransport { _ in FakeLiveTransport.ok(detail) }
        let reference = try XCTUnwrap(ViaggiaTrenoRunReference(originCode: "S08001", number: "12345", departureDateMillis: runDate))
        let request = LiveBoardRunRequest(
            station: ostiense, kind: .arrivals, number: "12345", scheduled: Date(timeIntervalSince1970: 1_792_139_400),
            viaggiaTrenoRun: reference
        )
        let answer = try await source(transport).boardRun(request)
        guard case let .answered(details, _) = answer else { return XCTFail("expected the run, got \(answer)") }
        XCTAssertEqual(transport.paths, [base + "andamentoTreno/S08001/12345/\(runDate)"], "no board, no number search")
        XCTAssertEqual(details.kind, .arrivals)
        XCTAssertEqual(details.run.originName, "Viterbo")
        XCTAssertEqual(details.run.destinationName, "Roma Tiburtina")
        XCTAssertEqual(details.run.stops.count, 5)
        XCTAssertEqual(details.stop.stationCode, "S08406")
        XCTAssertEqual(LiveRailTime.hhmm(details.stop.expected), "10:36")
        XCTAssertEqual(details.stop.delayMinutes, 6)
        XCTAssertTrue(details.stop.operationalStatusVerified)

        // Another time at the station is another run of the board: none.
        let other = LiveBoardRunRequest(
            station: ostiense, kind: .arrivals, number: "12345", scheduled: Date(timeIntervalSince1970: 1_792_139_460),
            viaggiaTrenoRun: reference
        )
        let missing = try await source(transport).boardRun(other)
        XCTAssertEqual(missing, .unavailable(.notFound))
    }

    func testABoardRowWithoutItsRunIsFoundOnTheBoardFromAMinuteBefore() async throws {
        let detail = try LiveFixtures.text("viaggiatreno-andamentoTreno-12345.json")
        let board = arrivalsRow()
        let transport = FakeLiveTransport { request in
            request.url.path.contains("andamentoTreno") ? FakeLiveTransport.ok(detail) : FakeLiveTransport.ok(board)
        }
        let request = LiveBoardRunRequest(station: ostiense, kind: .arrivals, number: "12345", scheduled: Date(timeIntervalSince1970: 1_792_139_400))
        let answer = try await source(transport).boardRun(request)
        guard case let .answered(details, _) = answer else { return XCTFail("expected the run, got \(answer)") }
        XCTAssertEqual(transport.paths, [
            base + "arrivi/S08406/Fri%20Oct%2016%202026%2010%3A29%3A00%20GMT%2B0200",
            base + "andamentoTreno/S08001/12345/\(runDate)",
        ])
        XCTAssertEqual(details.stop.stationCode, "S08406")

        // The departure at an intermediate stop is its departure time there.
        let appiano = Station(id: "S08328", name: "Appiano", railwayCode: "S08328")
        let reference = try XCTUnwrap(ViaggiaTrenoRunReference(originCode: "S08001", number: "12345", departureDateMillis: runDate))
        let departure = try await source(transport).boardRun(LiveBoardRunRequest(
            station: appiano, kind: .departures, number: "12345", scheduled: Date(timeIntervalSince1970: 1_792_137_900),
            viaggiaTrenoRun: reference
        ))
        guard case let .answered(leaving, _) = departure else { return XCTFail("expected the run, got \(departure)") }
        XCTAssertEqual(leaving.stop.stationName, "APPIANO")
    }

    func testARowOfAnotherOperatorOrWithoutACodeAsksNothing() async throws {
        let transport = FakeLiveTransport { _ in FakeLiveTransport.ok("[]") }
        let italo = LiveBoardRunRequest(
            station: ostiense, kind: .departures, number: "9920", scheduled: now, operatorID: "italo"
        )
        let refused = try await source(transport).boardRun(italo)
        XCTAssertEqual(refused, .unavailable(.notCovered))
        let uncoded = try await source(transport).boardRun(LiveBoardRunRequest(
            station: Station(id: "gtfs:x", name: "Somewhere"), kind: .departures, number: "12345", scheduled: now
        ))
        XCTAssertEqual(uncoded, .unavailable(.notCovered))
        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testARunWithoutCompImpresaTakesItsOperatorFromCodiceCliente() throws {
        let text = try LiveFixtures.text("viaggiatreno-andamentoTreno-9558-arrivato.json")
            .replacingOccurrences(of: "\"codiceCliente\": 1", with: "\"codiceCliente\": 63")
        let root = try XCTUnwrap(LiveJSONObject(LiveJSON.parse(text)))
        let reference = try XCTUnwrap(ViaggiaTrenoRunReference(originCode: "S08409", number: "9558", departureDateMillis: 1_793_746_800_000))
        let fetched = Date(timeIntervalSince1970: 1_793_783_960)
        let run = try XCTUnwrap(ViaggiaTrenoParser.run(root, reference: reference, serviceDay: fetched, fetchedAt: fetched))
        XCTAssertEqual(run.operatorID, "trenord")
        XCTAssertEqual(run.operatorName, "Trenord")
    }

    func testTheArrivalsBoardIsRfiAndViaggiaTrenoMergedAndItaloIsNotAsked() async throws {
        let board = arrivalsRow()
        let detail = try LiveFixtures.text("viaggiatreno-andamentoTreno-12345.json")
        let transport = FakeLiveTransport { request in
            let address = request.url.absoluteString
            if address.contains("iechub.rfi.it") { return FakeLiveTransport.ok("<html></html>") }
            if address.contains("arrivi/") { return FakeLiveTransport.ok(board) }
            if address.contains("andamentoTreno/") { return FakeLiveTransport.ok(detail) }
            return FakeLiveTransport.status(404)
        }
        let clock = LiveTestClock(now)
        let data = LiveRailData.standard(transport: transport, pauses: LiveSourcePauses(), now: clock.closure, noticeRules: nil)
        let result = await data.departures(LiveDeparturesRequest(station: ostiense, from: now, kind: .arrivals))
        guard case let .available(arrivals) = result else { return XCTFail("expected the arrivals, got \(result)") }
        XCTAssertEqual(arrivals.kind, .arrivals)
        XCTAssertEqual(arrivals.departures.map(\.number), ["12345"])
        XCTAssertEqual(arrivals.departures.first?.destination, "Viterbo")
        XCTAssertEqual(arrivals.sources, [.rfiStationBoard, .viaggiaTreno])
        XCTAssertEqual(arrivals.unavailableSources[.italo], .notCovered, "Italo lists departures only")
        XCTAssertTrue(transport.paths.contains { $0.hasSuffix("arrivals=True") })
        XCTAssertFalse(transport.paths.contains { $0.contains("italotreno") || $0.contains("partenze/") })

        // A row opens its run, ViaggiaTreno's only source of run details.
        let row = try XCTUnwrap(arrivals.departures.first)
        let run = await data.boardRun(LiveBoardRunRequest(row, station: ostiense, kind: .arrivals))
        guard case let .available(details) = run else { return XCTFail("expected the run, got \(run)") }
        XCTAssertEqual(details.stop.stationCode, "S08406")
        // Without the notice rules no notice source exists.
        let notices = await data.trainNotices(LiveTrainNoticesRequest(number: "12345", serviceDate: now))
        XCTAssertEqual(notices, .unavailable(.notCovered))
    }

    // MARK: Per-train notices

    func testTrainNoticesAreAskedOnceForTheirTimeToLiveAndKeptAsAnEarlierCopy() async throws {
        let fixture = try String(
            contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
                .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
                .appendingPathComponent("app/src/test/resources/sources/viaggiatreno-smartcaring-8829.json"),
            encoding: .utf8
        )
        let failing = LiveCounter()
        let transport = FakeLiveTransport { _ in
            failing.marked("fail") ? FakeLiveTransport.status(500) : FakeLiveTransport.ok(fixture)
        }
        let clock = LiveTestClock(LiveTestDates.rome(2026, 9, 13, 20, 0))
        let viaggiaTreno = try source(transport, clock: clock)
        let request = LiveTrainNoticesRequest(number: "08829", serviceDate: clock.now(), originCode: "s01700")
        let first = await viaggiaTreno.trainNotices(request)
        guard case let .answered(notices, _) = first else { return XCTFail("expected notices, got \(first)") }
        XCTAssertEqual(notices.notices.map(\.id), ["viaggiatreno-train-notices:789111"])
        XCTAssertFalse(notices.stale)
        let asked = try XCTUnwrap(transport.requests.first)
        let feed = try XCTUnwrap(NoticeTable.sources.first { $0.id == NoticeSourceID.trainNotices })
        XCTAssertEqual(asked.url.absoluteString, feed.url + "?commercialTrainNumber=8829")
        XCTAssertEqual(asked.headers["Accept"], feed.accept)
        XCTAssertEqual(asked.headers["User-Agent"], LiveSourceUserAgent.general)

        // Within its time to live the list is read again from memory.
        clock.advance(60)
        _ = await viaggiaTreno.trainNotices(request)
        XCTAssertEqual(transport.requests.count, 1)

        // After it, a failing refresh keeps the list as an earlier copy.
        failing.mark("fail")
        clock.advance(feed.timeToLiveSeconds)
        let stale = await viaggiaTreno.trainNotices(request)
        guard case let .answered(kept, _) = stale else { return XCTFail("expected the earlier copy, got \(stale)") }
        XCTAssertTrue(kept.stale)
        XCTAssertEqual(kept.notices.count, 1)
        XCTAssertEqual(transport.requests.count, 2)

        // A number without digits is never sent.
        let invalid = await viaggiaTreno.trainNotices(LiveTrainNoticesRequest(number: "FR 9588", serviceDate: clock.now()))
        XCTAssertEqual(invalid, .unavailable(.invalidRequest))
        XCTAssertEqual(transport.requests.count, 2)
    }

    func testWithoutTheNoticeRulesNoNoticeIsAsked() async throws {
        let transport = FakeLiveTransport { _ in FakeLiveTransport.ok("[]") }
        let clock = LiveTestClock(now)
        let plain = ViaggiaTrenoLiveSource(
            client: LiveSourceClient(transport: transport, now: clock.closure),
            gate: LiveSourceGate(source: .viaggiaTreno, configuration: .init(minimumInterval: 30), pauses: LiveSourcePauses(), now: clock.closure),
            now: clock.closure
        )
        let answer = await plain.trainNotices(LiveTrainNoticesRequest(number: "8829", serviceDate: now))
        XCTAssertEqual(answer, .unavailable(.notCovered))
        XCTAssertTrue(transport.requests.isEmpty)
    }
}
