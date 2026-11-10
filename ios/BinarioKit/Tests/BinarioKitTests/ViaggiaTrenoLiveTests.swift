import Foundation
import XCTest
@testable import BinarioKit

final class ViaggiaTrenoLiveTests: XCTestCase {
    private let base = "https://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/"
    private let now = LiveTestDates.viaggiaTrenoNow
    private let runDate = LiveTestDates.runDateMillis

    private func source(
        _ transport: FakeLiveTransport, clock: LiveTestClock? = nil, detailsTimeout: TimeInterval = 8
    ) -> ViaggiaTrenoLiveSource {
        let current = clock ?? LiveTestClock(now)
        return ViaggiaTrenoLiveSource(
            client: LiveSourceClient(transport: transport, now: current.closure),
            gate: LiveSourceGate(source: .viaggiaTreno, configuration: .init(minimumInterval: 30), pauses: LiveSourcePauses(), now: current.closure),
            now: current.closure, detailsTimeout: detailsTimeout
        )
    }

    private func makeReference(_ origin: String = "S08001", number: String = "12345", date: Int64? = nil) throws -> ViaggiaTrenoRunReference {
        try XCTUnwrap(ViaggiaTrenoRunReference(originCode: origin, number: number, departureDateMillis: date ?? runDate))
    }

    // MARK: The recorded run

    func testTheRecordedFrecciarossaRunGivesItsStatusStopsAndSkippedStop() throws {
        let root = try LiveFixtures.json("viaggiatreno-andamentoTreno-9558-arrivato.json")
        let reference = try makeReference("S08409", number: "9558", date: 1_793_746_800_000)
        // 2026-11-04 10:19:20 in Rome, twenty seconds after the last observation at Milano Centrale.
        let fetched = Date(timeIntervalSince1970: 1_793_783_960)
        let run = try XCTUnwrap(ViaggiaTrenoParser.run(root, reference: reference, serviceDay: fetched, fetchedAt: fetched))
        XCTAssertEqual(run.id, "viaggiatreno:S08409:9558:1793746800000")
        XCTAssertEqual(run.number, "9558")
        XCTAssertEqual(run.category, "FR")
        XCTAssertEqual(run.operatorID, "trenitalia")
        XCTAssertEqual(run.originName, "ROMA TERMINI")
        XCTAssertEqual(run.destinationName, "MILANO CENTRALE")
        XCTAssertEqual(LiveRailTime.hhmm(run.scheduledDeparture), "07:00")
        XCTAssertEqual(LiveRailTime.hhmm(run.expectedDeparture), "07:01", "the actual departure")
        XCTAssertEqual(run.platform, "14")
        XCTAssertEqual(run.scheduledPlatform, "12")
        XCTAssertTrue(run.operationalStatusVerified)
        XCTAssertEqual(run.delayMinutes, 9)
        XCTAssertEqual(run.state, .delayed)
        XCTAssertEqual(run.cancellation, .partial, "Bologna Centrale is a cancelled stop")
        XCTAssertEqual(run.lastObservation?.stationName, "MILANO CENTRALE")
        XCTAssertEqual(run.lastObservation.map { LiveRailTime.hhmm($0.at) }, "10:19")
        XCTAssertEqual(run.lastObservation?.isRecent, true)
        XCTAssertEqual(run.observation.source, .viaggiaTreno)
        XCTAssertEqual(run.observation.observedAt, Date(timeIntervalSince1970: 1_793_783_940))
        XCTAssertEqual(run.observation.expiresAt, fetched.addingTimeInterval(30))
        XCTAssertEqual(run.viaggiaTrenoRun, reference)

        XCTAssertEqual(run.stops.map(\.stationCode), ["S08409", "S08217", "S06421", "S05043", "S01700"])
        XCTAssertEqual(run.stops.map(\.skipped), [false, false, false, true, false])
        XCTAssertTrue(run.stops.allSatisfy(\.passed))
        let firenze = run.stops[2]
        XCTAssertEqual(firenze.scheduled, Date(timeIntervalSince1970: 1_793_778_000))
        XCTAssertEqual(firenze.delayMinutes, -2, "two and a half minutes early: whole minutes towards zero")
        XCTAssertEqual(firenze.platform, "8")
        XCTAssertEqual(firenze.scheduledDeparture, Date(timeIntervalSince1970: 1_793_778_060))
        let milano = run.stops[4]
        XCTAssertEqual(milano.platform, "21")
        XCTAssertEqual(milano.actual, Date(timeIntervalSince1970: 1_793_783_945))
        XCTAssertEqual(milano.delayMinutes, 9)
        XCTAssertEqual(NetworkReliability.numberKey(run.number), run.numberKey)
    }

    func testAnOldObservationKeepsTheDelayUnverifiedButKeepsTheActualTimes() throws {
        let root = try LiveFixtures.json("viaggiatreno-andamentoTreno-9558-arrivato.json")
        let reference = try makeReference("S08409", number: "9558", date: 1_793_746_800_000)
        let later = Date(timeIntervalSince1970: 1_793_783_940 + 600)
        let run = try XCTUnwrap(ViaggiaTrenoParser.run(root, reference: reference, serviceDay: later, fetchedAt: later))
        XCTAssertFalse(run.operationalStatusVerified)
        XCTAssertNil(run.delayMinutes, "a missing or old observation never becomes a live delay")
        XCTAssertEqual(run.state, .onTime)
        XCTAssertEqual(run.lastObservation?.isRecent, false)
        XCTAssertEqual(run.stops.last?.delayMinutes, 9, "an actual arrival stays the source's")
        XCTAssertTrue(run.stops.last?.operationalStatusVerified == true)
        XCTAssertTrue(run.stops[1].operationalStatusVerified, "Roma Tiburtina has its actual arrival")
    }

    // MARK: Train status by number

    func testANumberLookupAsksTheAutocompleteThenTheRunAsAndroidDoes() async throws {
        let autocomplete = try LiveFixtures.text("viaggiatreno-autocomplete-12345.txt")
        let detail = try LiveFixtures.text("viaggiatreno-andamentoTreno-12345.json")
        let transport = FakeLiveTransport { request in
            request.url.path.contains("cercaNumeroTrenoTrenoAutocomplete") ? FakeLiveTransport.ok(autocomplete) : FakeLiveTransport.ok(detail)
        }
        let answer = await source(transport).runs(for: LiveTrainStatusRequest(number: "R 12345", serviceDate: now))
        guard case let .answered(runs, complete) = answer else { return XCTFail("expected runs, got \(answer)") }
        XCTAssertTrue(complete)
        let run = try XCTUnwrap(runs.first)
        XCTAssertEqual(runs.count, 1, "123456 is another number; the run of 10 October is another day")
        XCTAssertEqual(run.originName, "Viterbo")
        XCTAssertEqual(run.destinationName, "Roma Tiburtina")
        XCTAssertEqual(run.stops.count, 5)
        XCTAssertEqual(LiveRailTime.hhmm(run.scheduledDeparture), "08:00")
        XCTAssertEqual(LiveRailTime.hhmm(run.expectedDeparture), "08:06")
        XCTAssertEqual(run.delayMinutes, 6)
        XCTAssertTrue(run.operationalStatusVerified)
        XCTAssertEqual(run.lastObservation?.stationName, "VITERBO PORTA FIORENTINA")
        XCTAssertEqual(transport.paths, [
            base + "cercaNumeroTrenoTrenoAutocomplete/12345",
            base + "andamentoTreno/S08001/12345/\(runDate)",
        ])
        XCTAssertEqual(transport.requests.map { $0.headers["Accept"] ?? "" }, ["text/plain", "application/json"])
        XCTAssertTrue(transport.requests.allSatisfy { $0.headers["User-Agent"] == LiveSourceUserAgent.viaggiaTrenoRealtime })
        XCTAssertTrue(transport.requests.allSatisfy { $0.url.scheme == "https" })

        // A run already identified (a board row) is asked directly.
        let direct = FakeLiveTransport { _ in FakeLiveTransport.ok(detail) }
        let known = try makeReference()
        _ = await source(direct).runs(for: LiveTrainStatusRequest(number: "12345", serviceDate: now, viaggiaTrenoRun: known))
        XCTAssertEqual(direct.paths, [base + "andamentoTreno/S08001/12345/\(runDate)"])
    }

    func testTheStationCodeOfTheRequestNarrowsTheRunsAsked() async throws {
        let autocomplete = "12345 - VITERBO|12345-S08001-\(runDate)\n12345 - ORTE|12345-S01234-\(runDate)"
        let detail = try LiveFixtures.text("viaggiatreno-andamentoTreno-12345.json")
        let transport = FakeLiveTransport { request in
            request.url.path.contains("cercaNumero") ? FakeLiveTransport.ok(autocomplete) : FakeLiveTransport.ok(detail)
        }
        _ = await source(transport).runs(for: LiveTrainStatusRequest(number: "12345", serviceDate: now, stationCode: "S08001"))
        XCTAssertEqual(transport.paths.count, 2, "only the run leaving from S08001 is asked")
        XCTAssertTrue(transport.paths.last?.hasSuffix("andamentoTreno/S08001/12345/\(runDate)") == true)
    }

    func testARunWithAnotherIdentityIsRefusedAndAMissingObservationStaysUnknown() throws {
        let text = try LiveFixtures.text("viaggiatreno-andamentoTreno-12345.json")
        let reference = try makeReference()
        for (label, payload) in [
            ("number", text.replacingOccurrences(of: "\"numeroTreno\": 12345", with: "\"numeroTreno\": 99999")),
            ("origin", text.replacingOccurrences(of: "\"idOrigine\": \"S08001\"", with: "\"idOrigine\": \"S09999\"")),
            ("date", text.replacingOccurrences(of: "\"dataPartenzaTreno\": \(runDate)", with: "\"dataPartenzaTreno\": \(runDate - 86_400_000)")),
        ] {
            let root = try XCTUnwrap(LiveJSONObject(LiveJSON.parse(payload)), label)
            XCTAssertNil(ViaggiaTrenoParser.run(root, reference: reference, serviceDay: now, fetchedAt: now), label)
        }
        for (label, payload) in [
            ("no observation", text.replacingOccurrences(of: "\"oraUltimoRilevamento\": 1792137570000", with: "\"oraUltimoRilevamento\": null")),
            ("old observation", text.replacingOccurrences(of: "\"oraUltimoRilevamento\": 1792137570000", with: "\"oraUltimoRilevamento\": 1792137000000")),
            ("not departed", text.replacingOccurrences(of: "\"nonPartito\": false", with: "\"nonPartito\": true")),
            ("no delay", text.replacingOccurrences(of: "\"ritardo\": 6", with: "\"ritardo\": null")),
            ("implausible delay", text.replacingOccurrences(of: "\"ritardo\": 6", with: "\"ritardo\": 99999")),
        ] {
            let root = try XCTUnwrap(LiveJSONObject(LiveJSON.parse(payload)), label)
            let run = try XCTUnwrap(ViaggiaTrenoParser.run(root, reference: reference, serviceDay: now, fetchedAt: now), label)
            XCTAssertFalse(run.operationalStatusVerified, label)
            XCTAssertNil(run.delayMinutes, label)
            XCTAssertEqual(run.state, .onTime, label)
            XCTAssertFalse(run.stops[1].operationalStatusVerified, label)
            XCTAssertEqual(run.stops[1].expected, run.stops[1].scheduled, label)
        }
    }

    func testAnExplicitCancellationIsAVerifiedStateEvenWithoutObservation() throws {
        let text = try LiveFixtures.text("viaggiatreno-andamentoTreno-12345.json")
            .replacingOccurrences(of: "\"tipoTreno\": \"PG\"", with: "\"tipoTreno\": \"ST\"")
            .replacingOccurrences(of: "\"oraUltimoRilevamento\": 1792137570000", with: "\"oraUltimoRilevamento\": null")
        let root = try XCTUnwrap(LiveJSONObject(LiveJSON.parse(text)))
        let run = try XCTUnwrap(ViaggiaTrenoParser.run(root, reference: try makeReference(), serviceDay: now, fetchedAt: now))
        XCTAssertEqual(run.state, .cancelled)
        XCTAssertEqual(run.cancellation, .full)
        XCTAssertTrue(run.operationalStatusVerified)
        XCTAssertFalse(run.stops[1].operationalStatusVerified, "a cancelled train has no expected times")
    }

    func testANightRunIsFoundOnBothDaysItRunsAndNotTheDayAfter() throws {
        let root = try LiveFixtures.json("viaggiatreno-andamentoTreno-1990-notte.json")
        let reference = try makeReference("S08409", number: "1990")
        // 00:24:20 on 17 October in Rome, twenty seconds after the observation at Firenze.
        let fetched = Date(timeIntervalSince1970: 1_792_189_460)
        let nextDay = LiveTestDates.rome(2026, 10, 17, 9, 0)
        let run = try XCTUnwrap(ViaggiaTrenoParser.run(root, reference: reference, serviceDay: nextDay, fetchedAt: fetched))
        XCTAssertEqual(LiveRailTime.isoDay(run.scheduledDeparture), "2026-10-16")
        XCTAssertEqual(LiveRailTime.hhmm(run.scheduledDeparture), "23:40")
        XCTAssertEqual(LiveRailTime.isoDay(run.stops[2].scheduled), "2026-10-17")
        XCTAssertEqual(LiveRailTime.hhmm(run.stops[2].expected), "01:13", "the verified delay of 3 minutes after midnight")
        XCTAssertEqual(run.stops[1].delayMinutes, 3)
        XCTAssertEqual(run.stops[1].scheduledDeparture.map(LiveRailTime.hhmm), "00:25")
        XCTAssertEqual(run.lastObservation?.stationName, "FIRENZE S. M. NOVELLA")
        XCTAssertEqual(run.operatorName, "TRENITALIA")
        XCTAssertNotNil(ViaggiaTrenoParser.run(root, reference: reference, serviceDay: LiveTestDates.rome(2026, 10, 16, 20, 0), fetchedAt: fetched))
        XCTAssertNil(ViaggiaTrenoParser.run(root, reference: reference, serviceDay: LiveTestDates.rome(2026, 10, 18, 9, 0), fetchedAt: fetched))
    }

    func testAutocompleteLinesAreReadStrictly() throws {
        let text = try LiveFixtures.text("viaggiatreno-autocomplete-12345.txt")
        let references = ViaggiaTrenoParser.references(text, query: LiveTrainNumberQuery("12345"))
        XCTAssertEqual(references.map(\.nativeID), ["S08001:12345:\(runDate)", "S08001:12345:1791583200000"])
        XCTAssertEqual(ViaggiaTrenoParser.references(text, query: LiveTrainNumberQuery("123456")).map(\.number), ["123456"])
        let many = (1...40).map { "1 - X|1-S0\(String(1_000 + $0))-\(runDate)" }.joined(separator: "\n")
        XCTAssertEqual(ViaggiaTrenoParser.references(many, query: LiveTrainNumberQuery("1")).count, 24)
        XCTAssertNil(ViaggiaTrenoRunReference(originCode: "S8001", number: "1", departureDateMillis: runDate))
        XCTAssertNil(ViaggiaTrenoRunReference(originCode: "S08001", number: "1234567", departureDateMillis: runDate))
        XCTAssertNil(ViaggiaTrenoRunReference(originCode: "S08001", number: "1", departureDateMillis: 12))
    }

    func testTheTrainNumberQueryIsAndroidsExactMatch() {
        XCTAssertEqual(LiveTrainNumberQuery("FR 09558").numeric, "9558")
        XCTAssertEqual(LiveTrainNumberQuery("REG-2153").numeric, "2153")
        XCTAssertNil(LiveTrainNumberQuery("VE545").numeric, "only the listed categories prefix a number")
        XCTAssertTrue(LiveTrainNumberQuery("9558").matches("FR 9558"))
        XCTAssertTrue(LiveTrainNumberQuery("2153").matches("RE_11 - 2153"))
        XCTAssertFalse(LiveTrainNumberQuery("215").matches("2153"))
        XCTAssertFalse(LiveTrainNumberQuery("").isValid)
        XCTAssertFalse(LiveTrainNumberQuery("FR").isValid)
        XCTAssertTrue(LiveTrainNumberQuery("FR 9558").isValid)
    }

    func testMalformedRefusedAndStalledAnswersBecomeTypedResults() async throws {
        let malformed = FakeLiveTransport { request in
            request.url.path.contains("cercaNumero") ? FakeLiveTransport.ok("12345 - X|12345-S08001-\(LiveTestDates.runDateMillis)") : FakeLiveTransport.ok("<html>")
        }
        let unreadable = await source(malformed).runs(for: LiveTrainStatusRequest(number: "12345", serviceDate: now))
        XCTAssertEqual(unreadable, .unavailable(.unreadable))

        let refusing = FakeLiveTransport { _ in FakeLiveTransport.status(429) }
        let refusedSource = source(refusing)
        let refused = await refusedSource.runs(for: LiveTrainStatusRequest(number: "12345", serviceDate: now))
        XCTAssertEqual(refused, .unavailable(.refused))
        let paused = await refusedSource.runs(for: LiveTrainStatusRequest(number: "54321", serviceDate: now))
        XCTAssertEqual(paused, .unavailable(.paused(until: now.addingTimeInterval(60))))
        XCTAssertEqual(refusing.requests.count, 1, "nothing is asked after the refusal")

        let detail = try LiveFixtures.text("viaggiatreno-andamentoTreno-12345.json")
        let autocomplete = "12345 - VITERBO|12345-S08001-\(runDate)\n12345 - ALTRA|12345-S09999-\(runDate)"
        let stalling = FakeLiveTransport { request in
            if request.url.path.contains("cercaNumero") { return FakeLiveTransport.ok(autocomplete) }
            if request.url.path.contains("S09999") { return await FakeLiveTransport.stall() }
            return FakeLiveTransport.ok(detail)
        }
        let started = Date()
        let partial = await source(stalling, detailsTimeout: 0.5).runs(for: LiveTrainStatusRequest(number: "12345", serviceDate: now))
        guard case let .answered(runs, complete) = partial else { return XCTFail("expected the completed run, got \(partial)") }
        XCTAssertEqual(runs.map(\.originName), ["Viterbo"], "a stalled detail does not discard the completed run")
        XCTAssertFalse(complete)
        XCTAssertLessThan(Date().timeIntervalSince(started), 10)

        let none = await source(FakeLiveTransport { _ in FakeLiveTransport.ok("") }).runs(for: LiveTrainStatusRequest(number: "12345", serviceDate: now))
        XCTAssertEqual(none, .answered([], complete: true))
        let letters = FakeLiveTransport { _ in FakeLiveTransport.ok("") }
        _ = await source(letters).runs(for: LiveTrainStatusRequest(number: "Treno", serviceDate: now))
        XCTAssertTrue(letters.requests.isEmpty, "a number without digits is never sent")
    }

    // MARK: Departures

    func testTheDepartureBoardIsAndroidsWithTheLateWindowAndTheNativeRun() async throws {
        let board = try LiveFixtures.text("viaggiatreno-partenze-S08328.json")
        let transport = FakeLiveTransport { _ in FakeLiveTransport.ok(board) }
        let station = Station(id: "S08328", name: "Appiano", railwayCode: "S08328")
        let window = LiveBoardWindow(
            station: station, from: now.addingTimeInterval(-600), to: now.addingTimeInterval(6 * 3_600),
            lateSince: now.addingTimeInterval(-2 * 3_600)
        )
        let answer = await source(transport).departures(for: window)
        guard case let .answered(rows, complete) = answer else { return XCTFail("expected rows, got \(answer)") }
        XCTAssertTrue(complete)
        XCTAssertEqual(rows.map(\.number), ["12345", "12347", "12349"], "invalid numbers and trains already left are dropped")
        XCTAssertEqual(transport.paths, [
            base + "partenze/S08328/Fri%20Oct%2016%202026%2009%3A50%3A00%20GMT%2B0200",
            base + "partenze/S08328/Fri%20Oct%2016%202026%2008%3A00%3A00%20GMT%2B0200",
        ])
        let delayed = rows[0]
        XCTAssertEqual(delayed.id, "viaggiatreno:12345:2026-10-16")
        XCTAssertEqual(delayed.delayMinutes, 6)
        XCTAssertEqual(delayed.status?.value, .delayed)
        XCTAssertEqual(delayed.platform?.value, "7")
        XCTAssertEqual(delayed.scheduledPlatform, "5", "the timetable platform travels with the actual one")
        XCTAssertNil(delayed.operatorID, "the board row names no carrier: Trenord's trains are listed too")
        let trenord = try XCTUnwrap(LiveJSONObject(LiveJSON.parse("{\"compImpresa\": \"TRENORD\"}")))
        XCTAssertEqual(ViaggiaTrenoParser.boardOperatorID(trenord), "trenord")
        let trenitalia = try XCTUnwrap(LiveJSONObject(LiveJSON.parse("{\"compImpresa\": \"Trenitalia\"}")))
        XCTAssertEqual(ViaggiaTrenoParser.boardOperatorID(trenitalia), "trenitalia")
        XCTAssertEqual(delayed.destination, "ROMA TIBURTINA")
        XCTAssertEqual(delayed.viaggiaTrenoRun?.nativeID, "S08001:12345:\(runDate)")
        XCTAssertEqual(delayed.expected?.observation.observedAt, now.addingTimeInterval(-30))
        XCTAssertEqual(delayed.observation.expiresAt, now.addingTimeInterval(30))
        let unobserved = rows[1]
        XCTAssertNil(unobserved.expected)
        XCTAssertNil(unobserved.status)
        XCTAssertNil(unobserved.platform)
        XCTAssertNil(unobserved.scheduledPlatform, "without an actual platform the timetable one is not shown")
        XCTAssertEqual(rows[2].status?.value, .cancelled)

        let uncoded = await source(transport).departures(for: LiveBoardWindow(
            station: Station(id: "gtfs:x", name: "Somewhere"), from: now, to: now.addingTimeInterval(3_600), lateSince: nil
        ))
        XCTAssertEqual(uncoded, .unavailable(.notCovered))
        let broken = await source(FakeLiveTransport { _ in FakeLiveTransport.ok("{\"not\": \"a list\"}") }).departures(for: window)
        XCTAssertEqual(broken, .unavailable(.unreadable))
        let empty = await source(FakeLiveTransport { _ in .response(status: 204, headers: [:], body: Data()) }).departures(for: window)
        XCTAssertEqual(empty, .answered([], complete: true))
    }

    func testTheBoardTimeIsAndroidsEncodedDate() {
        XCTAssertEqual(
            ViaggiaTrenoParser.boardTimePathComponent(LiveTestDates.rome(2026, 10, 16, 10, 0, 42)),
            "Fri%20Oct%2016%202026%2010%3A00%3A00%20GMT%2B0200"
        )
        XCTAssertEqual(
            ViaggiaTrenoParser.boardTimePathComponent(LiveTestDates.rome(2026, 11, 3, 7, 5)),
            "Tue%20Nov%2003%202026%2007%3A05%3A00%20GMT%2B0100"
        )
        XCTAssertEqual(ViaggiaTrenoParser.formEncoded("a b+c/é"), "a%20b%2Bc%2F%C3%A9")
    }
}
