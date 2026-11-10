import Foundation
import XCTest
@testable import BinarioKit

/// A train status source answering canned runs, recording what it was asked.
private struct CannedStatusSource: LiveTrainStatusSource {
    let source: LiveSource
    let answer: LiveSourceAnswer<[LiveRunStatus]>
    let asked: LiveCounter
    var delay: TimeInterval = 0

    func runs(for request: LiveTrainStatusRequest) async -> LiveSourceAnswer<[LiveRunStatus]> {
        asked.mark(source.rawValue)
        if delay > 0 { try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) }
        return Task.isCancelled ? .unavailable(.cancelled) : answer
    }
}

/// A board source answering canned rows.
private struct CannedBoardSource: LiveDepartureBoardSource {
    let source: LiveSource
    let answer: LiveSourceAnswer<[LiveDeparture]>
    var delay: TimeInterval = 0

    func departures(for window: LiveBoardWindow) async -> LiveSourceAnswer<[LiveDeparture]> {
        if delay > 0 { try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) }
        return Task.isCancelled ? .unavailable(.cancelled) : answer
    }
}

final class LiveRailDataTests: XCTestCase {
    private let now = LiveTestDates.viaggiaTrenoNow
    private let station = Station(id: "S08409", name: "Roma Termini", railwayCode: "S08409")

    private func observation(_ source: LiveSource, observedAt: Date? = nil) -> LiveObservation {
        LiveObservation(source: source, fetchedAt: now, observedAt: observedAt ?? now, expiresAt: now.addingTimeInterval(30))
    }

    private func makeRun(
        _ id: String, number: String, source: LiveSource, origin: String, originCode: String?, departure: Date,
        verified: Bool = true, stops: [String] = []
    ) -> LiveRunStatus {
        let calls = stops.map { code in
            LiveStopStatus(
                stationCode: code, stationName: code, scheduled: departure, scheduledDeparture: nil, expected: departure, actual: nil,
                actualDeparture: nil, platform: nil, scheduledPlatform: nil, delayMinutes: 0, passed: false, skipped: false,
                operationalStatusVerified: verified
            )
        }
        return LiveRunStatus(
            id: id, number: number, category: "REG", operatorName: "Trenitalia", operatorID: "trenitalia", originCode: originCode,
            originName: origin, destinationName: "Destinazione", scheduledDeparture: departure, expectedDeparture: departure,
            platform: nil, scheduledPlatform: nil, state: .onTime, cancellation: .none, delayMinutes: verified ? 0 : nil,
            operationalStatusVerified: verified, lastObservation: nil, stops: calls, observation: observation(source),
            viaggiaTrenoRun: nil
        )
    }

    private func makeRow(
        _ source: LiveSource, number: String, scheduled: Date, expectedMinutes: Int?, platform: String?,
        status: LiveDepartureStatus?, operatorID: String?, destination: String = "Napoli Centrale"
    ) -> LiveDeparture {
        let seen = observation(source)
        return LiveDeparture(
            id: "\(source.rawValue):\(number):\(LiveRailTime.isoDay(scheduled))", number: number, operatorID: operatorID,
            category: "", destination: destination, scheduled: scheduled,
            expected: expectedMinutes.map { LiveSourced(scheduled.addingTimeInterval(TimeInterval($0) * 60), observation: seen) },
            platform: platform.map { LiveSourced($0, observation: seen) }, scheduledPlatform: platform == nil ? nil : "1",
            status: status.map { LiveSourced($0, observation: seen) }, sources: [source], viaggiaTrenoRun: nil, observation: seen
        )
    }

    // MARK: Train status

    func testBothNumberSourcesAreAskedAndTheirRunsKeptInAndroidsOrder() async {
        let asked = LiveCounter()
        let viaggiaTreno = CannedStatusSource(source: .viaggiaTreno, answer: .answered([
            makeRun("viaggiatreno:a", number: "9920", source: .viaggiaTreno, origin: "Torino", originCode: "S00219", departure: now.addingTimeInterval(3_600)),
        ], complete: true), asked: asked)
        let italo = CannedStatusSource(source: .italo, answer: .answered([
            makeRun("italo:b", number: "9920", source: .italo, origin: "Milano", originCode: nil, departure: now),
            makeRun("italo:c", number: "9921", source: .italo, origin: "Milano", originCode: nil, departure: now),
        ], complete: true), asked: asked)
        let data = LiveRailData(statusSources: [viaggiaTreno, italo], boardSources: [])
        let result = await data.trainStatus(LiveTrainStatusRequest(number: "9920", serviceDate: now))
        guard case let .available(lookup) = result else { return XCTFail("expected runs, got \(result)") }
        XCTAssertEqual(lookup.runs.map(\.id), ["italo:b", "viaggiatreno:a"], "by departure; another number is dropped")
        XCTAssertTrue(lookup.isComplete)
        XCTAssertTrue(asked.marked("viaggiatreno") && asked.marked("italo-status"))
        let standard = LiveRailData.standard(transport: FakeLiveTransport { _ in .failure(.network) }, pauses: LiveSourcePauses())
        XCTAssertEqual(standard.statusSources.map { $0.source }, [.viaggiaTreno, .italo], "LocalOnlyRailDataPolicy.trainNumberSearchProviders")
        XCTAssertEqual(standard.boardSources.map { $0.source }, [.rfiStationBoard, .italo, .viaggiaTreno], "LocalOnlyRailDataPolicy.stationBoardProviders")
    }

    func testTheSameRunKeepsItsVerifiedCopyAndASourceFailureIsReported() async {
        let asked = LiveCounter()
        let unverified = makeRun("viaggiatreno:a", number: "100", source: .viaggiaTreno, origin: "A", originCode: "S00001", departure: now, verified: false)
        let verified = makeRun("viaggiatreno:a", number: "100", source: .italo, origin: "A", originCode: "S00001", departure: now)
        let first = CannedStatusSource(source: .viaggiaTreno, answer: .answered([unverified], complete: true), asked: asked)
        let second = CannedStatusSource(source: .italo, answer: .answered([verified], complete: false), asked: asked)
        let failing = CannedStatusSource(source: .rfiStationBoard, answer: .unavailable(.refused), asked: asked)
        let result = await LiveRailData(statusSources: [first, second, failing], boardSources: []).trainStatus(
            LiveTrainStatusRequest(number: "100", serviceDate: now)
        )
        guard case let .available(lookup) = result else { return XCTFail("expected runs, got \(result)") }
        XCTAssertEqual(lookup.runs.count, 1)
        XCTAssertTrue(lookup.runs[0].operationalStatusVerified)
        XCTAssertFalse(lookup.isComplete)
        XCTAssertEqual(lookup.unavailableSources, [.rfiStationBoard: .refused])
    }

    func testWithoutRunsTheReasonIsTypedAndSlowSourcesAreCutOff() async {
        let asked = LiveCounter()
        let none = CannedStatusSource(source: .viaggiaTreno, answer: .answered([], complete: true), asked: asked)
        let paused = CannedStatusSource(source: .italo, answer: .unavailable(.paused(until: now)), asked: asked)
        let notFound = await LiveRailData(statusSources: [none], boardSources: []).trainStatus(LiveTrainStatusRequest(number: "1", serviceDate: now))
        XCTAssertEqual(notFound, .unavailable(.notFound))
        let pausedResult = await LiveRailData(statusSources: [none, paused], boardSources: []).trainStatus(LiveTrainStatusRequest(number: "1", serviceDate: now))
        XCTAssertEqual(pausedResult, .unavailable(.paused(until: now)))
        let invalid = await LiveRailData(statusSources: [none], boardSources: []).trainStatus(LiveTrainStatusRequest(number: "Freccia", serviceDate: now))
        XCTAssertEqual(invalid, .unavailable(.invalidRequest))

        let slow = CannedStatusSource(source: .italo, answer: .answered([], complete: true), asked: asked, delay: 30)
        let started = Date()
        let cutOff = await LiveRailData(statusSources: [slow], boardSources: [], statusTimeout: 0.2).trainStatus(
            LiveTrainStatusRequest(number: "1", serviceDate: now)
        )
        XCTAssertEqual(cutOff, .unavailable(.sourceUnavailable))
        XCTAssertLessThan(Date().timeIntervalSince(started), 10)

        let data = LiveRailData(statusSources: [slow], boardSources: [])
        let request = LiveTrainStatusRequest(number: "1", serviceDate: now)
        let task = Task { await data.trainStatus(request) }
        try? await Task.sleep(nanoseconds: 100_000_000)
        task.cancel()
        let cancelled = await task.value
        XCTAssertEqual(cancelled, .unavailable(.cancelled))
    }

    func testTheRunOfTheRequestIsPickedByItsStationOrOrigin() {
        let fromViterbo = makeRun("v:1", number: "12345", source: .viaggiaTreno, origin: "Viterbo", originCode: "S08001", departure: now, stops: ["S08001", "S08328"])
        let fromOrte = makeRun("v:2", number: "12345", source: .viaggiaTreno, origin: "Orte", originCode: "S01234", departure: now, stops: ["S01234", "S09000"])
        let lookup = LiveTrainStatusLookup(runs: [fromViterbo, fromOrte], unavailableSources: [:], isComplete: true)
        XCTAssertEqual(lookup.run(for: LiveTrainStatusRequest(number: "12345", serviceDate: now, stationCode: "S01234"))?.id, "v:2")
        XCTAssertEqual(lookup.run(for: LiveTrainStatusRequest(number: "12345", serviceDate: now, stationCode: "S08328"))?.id, "v:1", "the boarding station")
        XCTAssertEqual(lookup.run(for: LiveTrainStatusRequest(number: "12345", serviceDate: now, originName: "VITERBO"))?.id, "v:1")
        XCTAssertNil(lookup.run(for: LiveTrainStatusRequest(number: "12345", serviceDate: now)), "two runs: none is guessed")
        let single = LiveTrainStatusLookup(runs: [fromOrte], unavailableSources: [:], isComplete: true)
        XCTAssertEqual(single.run(for: LiveTrainStatusRequest(number: "RE_11 - 12345", serviceDate: now))?.id, "v:2")
    }

    // MARK: Departures

    func testEachValueComesFromItsBestSource() async {
        let scheduled = now.addingTimeInterval(2 * 3_600)
        let rfi = CannedBoardSource(source: .rfiStationBoard, answer: .answered([
            makeRow(.rfiStationBoard, number: "9401", scheduled: scheduled, expectedMinutes: 8, platform: "4", status: .delayed, operatorID: "trenitalia"),
        ], complete: true))
        let viaggiaTreno = CannedBoardSource(source: .viaggiaTreno, answer: .answered([
            makeRow(.viaggiaTreno, number: "9401", scheduled: scheduled, expectedMinutes: 6, platform: "5", status: .delayed, operatorID: "trenitalia",
                    destination: "NAPOLI CENTRALE"),
        ], complete: true))
        let result = await LiveRailData(statusSources: [], boardSources: [rfi, viaggiaTreno]).departures(LiveDeparturesRequest(station: station, from: now))
        guard case let .available(board) = result else { return XCTFail("expected a board, got \(result)") }
        let row = board.departures.first
        XCTAssertEqual(board.departures.count, 1, "one train, two sources")
        XCTAssertEqual(row?.platform?.value, "4", "RFI is the authority for platforms")
        XCTAssertEqual(row?.platform?.observation.source, .rfiStationBoard)
        XCTAssertEqual(row?.delayMinutes, 6, "ViaggiaTreno is the operator's realtime for a Trenitalia train")
        XCTAssertEqual(row?.expected?.observation.source, .viaggiaTreno)
        XCTAssertEqual(row?.sources, [.rfiStationBoard, .viaggiaTreno])
        XCTAssertEqual(row?.destination, "Napoli Centrale", "the label of the board's authority")
        XCTAssertTrue(board.warnings.contains(.sourceConflict), "the platforms differ")
        XCTAssertEqual(board.sources, [.rfiStationBoard, .viaggiaTreno])
        XCTAssertEqual(board.from, now.addingTimeInterval(-600))
        XCTAssertEqual(board.to, now.addingTimeInterval(6 * 3_600))
    }

    func testAnItaloTrainTakesItsTimesFromItaloAndItsPlatformFromRfi() async {
        let scheduled = now.addingTimeInterval(2 * 3_600)
        let rfi = CannedBoardSource(source: .rfiStationBoard, answer: .answered([
            makeRow(.rfiStationBoard, number: "9923", scheduled: scheduled, expectedMinutes: 8, platform: "4", status: .delayed, operatorID: "italo"),
        ], complete: true))
        let italo = CannedBoardSource(source: .italo, answer: .answered([
            makeRow(.italo, number: "9923", scheduled: scheduled, expectedMinutes: 5, platform: "7", status: .onTime, operatorID: "italo"),
        ], complete: true))
        let result = await LiveRailData(statusSources: [], boardSources: [italo, rfi]).departures(LiveDeparturesRequest(station: station, from: now))
        guard case let .available(board) = result, let row = board.departures.first else { return XCTFail("expected a row, got \(result)") }
        XCTAssertEqual(row.platform?.value, "4")
        XCTAssertEqual(row.delayMinutes, 5)
        XCTAssertEqual(row.status?.value, .onTime)
        XCTAssertEqual(row.status?.observation.source, .italo)
    }

    func testDifferentOperatorsOrRunsAreNeverMerged() async {
        let scheduled = now.addingTimeInterval(2 * 3_600)
        let rfi = CannedBoardSource(source: .rfiStationBoard, answer: .answered([
            makeRow(.rfiStationBoard, number: "9923", scheduled: scheduled, expectedMinutes: 0, platform: "4", status: .onTime, operatorID: "trenitalia"),
            makeRow(.rfiStationBoard, number: "9925", scheduled: scheduled.addingTimeInterval(720), expectedMinutes: 0, platform: "1", status: .onTime, operatorID: nil),
        ], complete: true))
        let italo = CannedBoardSource(source: .italo, answer: .answered([
            makeRow(.italo, number: "9923", scheduled: scheduled.addingTimeInterval(240), expectedMinutes: 4, platform: "7", status: .onTime, operatorID: "italo"),
            makeRow(.italo, number: "9925", scheduled: scheduled, expectedMinutes: nil, platform: nil, status: nil, operatorID: "italo"),
        ], complete: true))
        let result = await LiveRailData(statusSources: [], boardSources: [rfi, italo]).departures(LiveDeparturesRequest(station: station, from: now))
        guard case let .available(board) = result else { return XCTFail("expected a board, got \(result)") }
        XCTAssertEqual(board.departures.count, 4, "known operators that differ, and 12 minutes apart, are other trains")
        XCTAssertTrue(board.warnings.contains(.identityAmbiguous))
    }

    func testATrenordTrainListedByRfiAndViaggiaTrenoKeepsItsLiveValues() async throws {
        let scheduled = now.addingTimeInterval(2 * 3_600)
        let window = LiveBoardWindow(
            station: station, from: now.addingTimeInterval(-600), to: now.addingTimeInterval(6 * 3_600),
            lateSince: now.addingTimeInterval(-2 * 3_600)
        )
        // ViaggiaTreno's board lists Trenord's trains without naming the carrier.
        func viaggiaTrenoBoard(destination: String) throws -> [LiveDeparture] {
            let text = """
                [{"numeroTreno": 2153, "codOrigine": "S01700", "dataPartenzaTreno": \(LiveTestDates.runDateMillis),
                  "orarioPartenza": \(LiveRailTime.millis(scheduled)), "ritardo": 4,
                  "ultimoRilev": \(LiveRailTime.millis(now.addingTimeInterval(-30))), "destinazione": "\(destination)",
                  "categoria": "REG", "provvedimento": 0, "nonPartito": false,
                  "binarioProgrammatoPartenzaDescrizione": "4", "binarioEffettivoPartenzaDescrizione": "5"}]
                """
            return try XCTUnwrap(ViaggiaTrenoParser.departures(text, window: window, fetchedAt: now))
        }
        let rfiRow = makeRow(.rfiStationBoard, number: "2153", scheduled: scheduled, expectedMinutes: nil, platform: "5",
                             status: nil, operatorID: "trenord", destination: "Bergamo")
        let rfi = CannedBoardSource(source: .rfiStationBoard, answer: .answered([rfiRow], complete: true))

        let same = try viaggiaTrenoBoard(destination: "BERGAMO")
        XCTAssertNil(same.first?.operatorID, "a carrier the row does not name is unknown, not Trenitalia")
        let viaggiaTreno = CannedBoardSource(source: .viaggiaTreno, answer: .answered(same, complete: true))
        let result = await LiveRailData(statusSources: [], boardSources: [rfi, viaggiaTreno]).departures(LiveDeparturesRequest(station: station, from: now))
        guard case let .available(board) = result else { return XCTFail("expected a board, got \(result)") }
        XCTAssertEqual(board.departures.count, 1, "RFI's «TRENORD» row and ViaggiaTreno's row are one train")
        XCTAssertFalse(board.warnings.contains(.identityAmbiguous))
        let merged = try XCTUnwrap(LiveOverlay.departure(number: "RE_11 - 2153", scheduled: scheduled, in: board.departures))
        XCTAssertEqual(merged.sources, [.rfiStationBoard, .viaggiaTreno])
        XCTAssertEqual(merged.delayMinutes, 4)
        XCTAssertEqual(merged.expected?.observation.source, .viaggiaTreno)
        XCTAssertEqual(merged.platform?.observation.source, .rfiStationBoard)

        // A destination spelt differently keeps the rows apart: the board's best source answers
        // instead of nothing.
        let apartRows = try viaggiaTrenoBoard(destination: "BERGAMO FS")
        let apart = CannedBoardSource(source: .viaggiaTreno, answer: .answered(apartRows, complete: true))
        let split = await LiveRailData(statusSources: [], boardSources: [rfi, apart]).departures(LiveDeparturesRequest(station: station, from: now))
        guard case let .available(splitBoard) = split else { return XCTFail("expected a board, got \(split)") }
        XCTAssertEqual(splitBoard.departures.count, 2)
        let chosen = try XCTUnwrap(LiveOverlay.departure(number: "2153", scheduled: scheduled, in: splitBoard.departures))
        XCTAssertEqual(chosen.sources, [.rfiStationBoard], "RFI is the authority for the row")
        XCTAssertEqual(chosen.platform?.value, "5")

        // Two rows of one source at the same time are two trains: none is guessed.
        let twice = [
            makeRow(.rfiStationBoard, number: "2153", scheduled: scheduled, expectedMinutes: nil, platform: "1", status: nil, operatorID: "trenord"),
            makeRow(.rfiStationBoard, number: "2153", scheduled: scheduled, expectedMinutes: nil, platform: "2", status: nil, operatorID: "trenord",
                    destination: "Lecco"),
        ]
        XCTAssertNil(LiveOverlay.departure(number: "2153", scheduled: scheduled, in: twice))
    }

    func testAFailedOrSlowSourceLeavesTheOthersAndEveryoneNotCoveringIsFinal() async {
        let scheduled = now.addingTimeInterval(3_600)
        let rfi = CannedBoardSource(source: .rfiStationBoard, answer: .answered([
            makeRow(.rfiStationBoard, number: "2153", scheduled: scheduled, expectedMinutes: 2, platform: "3", status: .delayed, operatorID: "trenord"),
            makeRow(.rfiStationBoard, number: "2155", scheduled: now.addingTimeInterval(-3 * 3_600), expectedMinutes: 2, platform: "3", status: .delayed, operatorID: "trenord"),
        ], complete: true))
        let slow = CannedBoardSource(source: .italo, answer: .answered([], complete: true), delay: 30)
        let started = Date()
        let result = await LiveRailData(statusSources: [], boardSources: [rfi, slow], boardTimeout: 0.2)
            .departures(LiveDeparturesRequest(station: station, from: now))
        XCTAssertLessThan(Date().timeIntervalSince(started), 10)
        guard case let .available(board) = result else { return XCTFail("expected a board, got \(result)") }
        XCTAssertEqual(board.departures.map(\.number), ["2153"], "a train long gone is not listed")
        XCTAssertEqual(board.unavailableSources, [.italo: .sourceUnavailable])
        XCTAssertTrue(board.warnings.contains(.realtimeUnavailable))

        let notCovered = CannedBoardSource(source: .viaggiaTreno, answer: .unavailable(.notCovered))
        let final = await LiveRailData(statusSources: [], boardSources: [notCovered, notCovered]).departures(LiveDeparturesRequest(station: station, from: now))
        XCTAssertEqual(final, .unavailable(.notCovered))
        let failing = CannedBoardSource(source: .rfiStationBoard, answer: .unavailable(.refused))
        let refused = await LiveRailData(statusSources: [], boardSources: [notCovered, failing]).departures(LiveDeparturesRequest(station: station, from: now))
        XCTAssertEqual(refused, .unavailable(.refused))
        let empty = await LiveRailData(statusSources: [], boardSources: []).departures(LiveDeparturesRequest(station: station, from: now))
        XCTAssertEqual(empty, .unavailable(.notCovered))
    }

    func testTheSourcePolicyIsAndroidsForTheThreeSources() {
        XCTAssertEqual(LiveSourcePolicy.score(.stationBoard, .rfiStationBoard, operatorHint: nil), 150)
        XCTAssertEqual(LiveSourcePolicy.score(.trainRealtime, .rfiStationBoard, operatorHint: "trenitalia"), 126)
        XCTAssertEqual(LiveSourcePolicy.score(.trainRealtime, .viaggiaTreno, operatorHint: "trenitalia"), 148)
        XCTAssertEqual(LiveSourcePolicy.score(.trainRealtime, .viaggiaTreno, operatorHint: "trenord"), 128)
        XCTAssertEqual(LiveSourcePolicy.score(.stationBoard, .viaggiaTreno, operatorHint: "Trenitalia"), 138)
        XCTAssertEqual(LiveSourcePolicy.score(.trainRealtime, .italo, operatorHint: "italo"), 150)
        XCTAssertEqual(LiveSourcePolicy.score(.stationBoard, .italo, operatorHint: "italo"), 138)
    }

    // MARK: Overlay on the bundled timetable

    func testALiveRowOverlaysTheBundledTrainWithTheSameNumberAndTime() {
        let day = LiveTestDates.rome(2026, 10, 16, 9, 0)
        let live = makeRow(.rfiStationBoard, number: "2153", scheduled: LiveTestDates.rome(2026, 10, 16, 10, 6), expectedMinutes: 3,
                           platform: "2", status: .delayed, operatorID: "trenord")
        let late = makeRow(.rfiStationBoard, number: "2153", scheduled: LiveTestDates.rome(2026, 10, 17, 0, 12), expectedMinutes: 0,
                           platform: "2", status: .onTime, operatorID: "trenord")
        let board = LiveDepartureBoard(
            stationID: "S01700", from: day, to: day.addingTimeInterval(86_400), departures: [live, late], sources: [.rfiStationBoard],
            unavailableSources: [:], warnings: [], fetchedAt: now
        )
        func service(_ number: String, _ time: String) -> TrainService {
            TrainService(id: "gtfs:\(number):\(time)", number: number, operatorName: "Trenord", category: "RE", destination: "Bergamo",
                         scheduledDeparture: time, expectedDeparture: time, status: "PROGRAMMED", platform: nil, delayMinutes: 0)
        }
        XCTAssertEqual(LiveOverlay.departure(for: service("RE_11 - 2153", "10:05"), on: day, in: board), live)
        XCTAssertNil(LiveOverlay.departure(for: service("RE_11 - 2153", "09:59"), on: day, in: board), "more than five minutes apart")
        XCTAssertNil(LiveOverlay.departure(for: service("RE_11 - 2154", "10:05"), on: day, in: board), "another number")
        XCTAssertEqual(LiveOverlay.departure(for: service("2153", "24:10"), on: day, in: board), late, "past midnight is the next day")
        XCTAssertNil(LiveOverlay.departure(for: service("2153", "10:5"), on: day, in: board))
        let tie = [
            makeRow(.rfiStationBoard, number: "1", scheduled: day.addingTimeInterval(-60), expectedMinutes: nil, platform: nil, status: nil, operatorID: nil),
            makeRow(.italo, number: "1", scheduled: day.addingTimeInterval(60), expectedMinutes: nil, platform: nil, status: nil, operatorID: nil),
        ]
        XCTAssertNil(LiveOverlay.departure(number: "1", scheduled: day, in: tie), "two rows equally close: none is guessed")

        let run = makeRun("v:1", number: "2153", source: .viaggiaTreno, origin: "Milano", originCode: "S01700", departure: day, stops: ["S01700", "S01529"])
        XCTAssertEqual(LiveOverlay.stop(in: run, stationCode: "S01529", stationName: nil)?.stationCode, "S01529")
        XCTAssertEqual(LiveOverlay.stop(in: run, stationCode: nil, stationName: "s01700")?.stationCode, "S01700")
        XCTAssertNil(LiveOverlay.stop(in: run, stationCode: "S00000", stationName: nil))
    }

    func testLiveRowsBecomeThePartenzeLiveStatusWhileTheyAreLive() {
        let day = LiveTestDates.rome(2026, 10, 16, 9, 0)
        let scheduled = LiveTestDates.rome(2026, 10, 16, 10, 5)
        let live = makeRow(.viaggiaTreno, number: "2153", scheduled: scheduled.addingTimeInterval(60), expectedMinutes: 7,
                           platform: "3", status: .delayed, operatorID: "trenitalia")
        let cancelled = makeRow(.rfiStationBoard, number: "2155", scheduled: scheduled, expectedMinutes: 0,
                                platform: nil, status: .cancelled, operatorID: "trenord")
        let board = LiveDepartureBoard(
            stationID: "S01700", from: day, to: day.addingTimeInterval(6 * 3_600), departures: [live, cancelled],
            sources: [.rfiStationBoard, .viaggiaTreno], unavailableSources: [:], warnings: [], fetchedAt: now
        )
        func row(_ number: String) -> StationBoardDeparture {
            StationBoardDeparture(
                departure: scheduled,
                service: TrainService(id: "gtfs:\(number)", number: "RE_11 - \(number)", operatorName: "Trenord", category: "RE",
                                      destination: "Bergamo", scheduledDeparture: "10:05", expectedDeparture: "10:05",
                                      status: "PROGRAMMED", platform: nil, delayMinutes: 0),
                terminus: StationIdentity(id: "S01529", name: "Bergamo")
            )
        }
        let rows = LiveOverlay.stationBoard([row("2153"), row("2155"), row("2157")], with: board, at: now)
        let status = rows[0].live
        XCTAssertEqual(status?.expectedDeparture, scheduled.addingTimeInterval(8 * 60))
        XCTAssertEqual(status?.delayMinutes, 7)
        XCTAssertEqual(status?.platform, "3")
        XCTAssertEqual(status?.cancelled, false)
        XCTAssertEqual(status?.sourceLabel, "Trenitalia — ViaggiaTreno realtime")
        XCTAssertEqual(status?.observedAt, now)
        XCTAssertEqual(rows[1].live?.cancelled, true)
        XCTAssertNil(rows[1].live?.expectedDeparture, "a cancelled train has no expected time")
        XCTAssertTrue(rows[2].isScheduled, "a train no source lists stays scheduled")
        let expired = LiveOverlay.stationBoard([row("2153")], with: board, at: now.addingTimeInterval(31))
        XCTAssertTrue(expired[0].isScheduled, "expired observations are not shown as live")
        let empty = makeRow(.italo, number: "1", scheduled: scheduled, expectedMinutes: nil, platform: nil, status: nil, operatorID: "italo")
        XCTAssertNil(empty.stationBoardLiveStatus(at: now))
    }

    func testADelayIsCountedFromTheTimeTheRowPrintsWhenTheSourceListsTheTrainAtAnotherTime() {
        let day = LiveTestDates.rome(2026, 10, 16, 9, 0)
        let bundled = LiveTestDates.rome(2026, 10, 16, 10, 14)
        // RFI lists the train at 10:10 (a timetable change the monthly bundle lacks), «Nessuno».
        let earlier = makeRow(.rfiStationBoard, number: "2153", scheduled: LiveTestDates.rome(2026, 10, 16, 10, 10), expectedMinutes: 0,
                              platform: "2", status: .onTime, operatorID: "trenord")
        let later = makeRow(.rfiStationBoard, number: "2155", scheduled: LiveTestDates.rome(2026, 10, 16, 10, 10), expectedMinutes: 6,
                            platform: "3", status: .delayed, operatorID: "trenord")
        let same = makeRow(.rfiStationBoard, number: "2157", scheduled: bundled, expectedMinutes: 0,
                           platform: "4", status: .onTime, operatorID: "trenord")
        let board = LiveDepartureBoard(
            stationID: "S01700", from: day, to: day.addingTimeInterval(6 * 3_600), departures: [earlier, later, same],
            sources: [.rfiStationBoard], unavailableSources: [:], warnings: [], fetchedAt: now
        )
        func row(_ number: String) -> StationBoardDeparture {
            StationBoardDeparture(
                departure: bundled,
                service: TrainService(id: "gtfs:\(number)", number: number, operatorName: "Trenord", category: "RE",
                                      destination: "Bergamo", scheduledDeparture: "10:14", expectedDeparture: "10:14",
                                      status: "PROGRAMMED", platform: nil, delayMinutes: 0),
                terminus: StationIdentity(id: "S01529", name: "Bergamo")
            )
        }
        let rows = LiveOverlay.stationBoard([row("2153"), row("2155"), row("2157")], with: board, at: now)
        XCTAssertEqual(rows[0].live?.expectedDeparture, LiveTestDates.rome(2026, 10, 16, 10, 10))
        XCTAssertEqual(rows[0].live?.delayMinutes, -4, "leaves four minutes before the printed 10:14: never «on time»")
        XCTAssertEqual(rows[1].live?.delayMinutes, 2, "10:16 is two minutes after the printed time")
        XCTAssertEqual(rows[2].live?.delayMinutes, 0)
        XCTAssertEqual(earlier.stationBoardLiveStatus(at: now)?.delayMinutes, 0, "against the source's own time without a timetable row")
    }

    func testThePartenzeLiveStatusNamesTheSourceOfEachValueAndABareLateLabel() {
        let scheduled = LiveTestDates.rome(2026, 10, 16, 10, 5)
        let viaggiaTreno = observation(.viaggiaTreno)
        let rfi = observation(.rfiStationBoard)
        // A merged row: ViaggiaTreno's expected time and state, RFI's platform.
        let merged = LiveDeparture(
            id: "rfi-station-board:2153:2026-10-16", number: "2153", operatorID: "trenitalia", category: "REG",
            destination: "Bergamo", scheduled: scheduled,
            expected: LiveSourced(scheduled.addingTimeInterval(5 * 60), observation: viaggiaTreno),
            platform: LiveSourced("4", observation: rfi), scheduledPlatform: "3",
            status: LiveSourced(.delayed, observation: viaggiaTreno), sources: [.rfiStationBoard, .viaggiaTreno],
            viaggiaTrenoRun: nil, observation: rfi
        )
        let status = merged.stationBoardLiveStatus(at: now)
        XCTAssertEqual(status?.source, .viaggiaTreno, "named after the source of the expected time")
        XCTAssertEqual(status?.platformSource, .rfiStationBoard)
        XCTAssertEqual(status?.platform, "4")
        XCTAssertEqual(status?.delayMinutes, 5)
        XCTAssertEqual(status?.lateWithoutMinutes, false)

        // «RITARDO» without minutes: late, no expected time, no delay, never on time.
        let late = makeRow(.rfiStationBoard, number: "2155", scheduled: scheduled, expectedMinutes: nil, platform: nil,
                           status: .delayed, operatorID: "trenord")
        let lateStatus = late.stationBoardLiveStatus(at: now)
        XCTAssertEqual(lateStatus?.lateWithoutMinutes, true)
        XCTAssertNil(lateStatus?.expectedDeparture)
        XCTAssertNil(lateStatus?.delayMinutes)
        XCTAssertEqual(lateStatus?.source, .rfiStationBoard)
        XCTAssertNil(lateStatus?.platformSource)

        // A cancellation is never «late without minutes».
        let cancelled = makeRow(.rfiStationBoard, number: "2157", scheduled: scheduled, expectedMinutes: nil, platform: nil,
                                status: .cancelled, operatorID: "trenord")
        XCTAssertEqual(cancelled.stationBoardLiveStatus(at: now)?.cancelled, true)
        XCTAssertEqual(cancelled.stationBoardLiveStatus(at: now)?.lateWithoutMinutes, false)
    }
}
