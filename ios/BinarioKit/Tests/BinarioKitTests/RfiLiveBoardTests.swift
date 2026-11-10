import Foundation
import XCTest
@testable import BinarioKit

final class RfiLiveBoardTests: XCTestCase {
    /// 2026-08-14 12:00 UTC, the clock of Android's `RfiStationBoardAdapterTest`.
    private let august = Date(timeIntervalSince1970: 1_786_708_800)

    private func makeSource(_ transport: FakeLiveTransport, now: Date) -> RfiLiveSource {
        let clock = LiveTestClock(now)
        return RfiLiveSource(
            client: LiveSourceClient(transport: transport, now: clock.closure),
            gate: LiveSourceGate(source: .rfiStationBoard, configuration: .init(minimumInterval: 20), pauses: LiveSourcePauses(), now: clock.closure)
        )
    }

    func testTheMonitorRowsAreReadAsAndroidReadsThem() throws {
        let rows = RfiMonitorParser.rows(try LiveFixtures.text("rfi-monitor-roma-termini.html"))
        XCTAssertEqual(rows.map(\.number), ["9401", "583"])
        let frecciarossa = rows[0]
        XCTAssertEqual(frecciarossa.destination, "Napoli Centrale")
        XCTAssertEqual(frecciarossa.scheduled, "10:00")
        XCTAssertEqual(frecciarossa.publishedDelay, 12)
        XCTAssertEqual(frecciarossa.platform, "7")
        XCTAssertEqual(frecciarossa.carrier, "TRENITALIA")
        XCTAssertEqual(frecciarossa.category, "Frecciarossa")
        XCTAssertEqual(frecciarossa.operatorID, "trenitalia")
        XCTAssertTrue(frecciarossa.verified)
        XCTAssertEqual(rows[1].publishedDelay, 0, "«Nessuno» is a published zero")
        XCTAssertEqual(rows[1].platform, "4")
    }

    func testTheVisibleDelayWinsAndPlaceholdersNeverProveZeroMinutes() throws {
        let html = try LiveFixtures.text("rfi-monitor-roma-termini.html")
        let visible = html.replacingOccurrences(of: "aria-label=\"12 minuti\">12 minuti", with: "aria-label=\"Nessuno\">6")
        XCTAssertEqual(RfiMonitorParser.rows(visible).first?.publishedDelay, 6)
        let generic = html.replacingOccurrences(of: "aria-label=\"12 minuti\"", with: "aria-label=\"Ritardo\"")
        XCTAssertEqual(RfiMonitorParser.rows(generic).first?.publishedDelay, 12)
        let quoted = html.replacingOccurrences(of: "aria-label=\"12 minuti\">12 minuti", with: "aria-label='\"Nessuno\"'>")
        let placeholder = try XCTUnwrap(RfiMonitorParser.rows(quoted).first)
        XCTAssertNil(placeholder.publishedDelay)
        XCTAssertFalse(placeholder.verified)
        XCTAssertEqual(RfiMonitorParser.rows(quoted.replacingOccurrences(of: "'\"Nessuno\"'", with: "'\"6\"'")).first?.publishedDelay, 6)
        let missing = html.replacingOccurrences(of: "<td id=\"RRitardo\" aria-label=\"12 minuti\">12 minuti</td>", with: "")
            .replacingOccurrences(of: "Regolare instradamento", with: "Il prossimo treno fra 10 minuti viaggia in orario")
        let unknown = try XCTUnwrap(RfiMonitorParser.rows(missing).first)
        XCTAssertNil(unknown.publishedDelay, "a number in a message is not a delay")
        XCTAssertFalse(unknown.verified)
        for (value, delay) in [("12 minuti", 12), ("+5", 5), ("RITARDO 7'", 7), ("Ritardo di 3 min.", 3), ("IN ORARIO", 0), ("-2", -2)] {
            XCTAssertEqual(RfiMonitorParser.parseDelay(value), delay, value)
        }
        for value in ["", "\"Nessuno\"", "Ritardo", "1234", "dieci"] {
            XCTAssertNil(RfiMonitorParser.parseDelay(value), value)
        }
    }

    func testABareRitardoIsLateWithoutMinutesNeverOnTime() throws {
        // 2026-11-03 20:30:51 in Rome, Android's RfiLateWithoutMinutesTest.
        let now = Date(timeIntervalSince1970: 1_793_734_251)
        let rows = RfiMonitorParser.rows(try LiveFixtures.text("rfi-monitor-appiano-ritardi.html"))
        let byNumber = Dictionary(uniqueKeysWithValues: rows.map { ($0.number, $0) })
        XCTAssertEqual(byNumber["20729"]?.lateWithoutMinutes, true)
        XCTAssertEqual(byNumber["20729"]?.verified, false)
        XCTAssertEqual(byNumber["20539"]?.lateWithoutMinutes, false)
        XCTAssertEqual(byNumber["20383"]?.cancelled, true)
        XCTAssertEqual(byNumber["20383"]?.lateWithoutMinutes, false, "a cancellation stays a cancellation")
        XCTAssertEqual(byNumber["20385"]?.destination, "ROMA OSTIENSE")
        for value in ["RITARDO", " ritardo ", "\"RITARDO\""] { XCTAssertTrue(RfiMonitorParser.isBareLateLabel(value), value) }
        for value in ["RITARDO 12'", "In orario", ""] { XCTAssertFalse(RfiMonitorParser.isBareLateLabel(value), value) }

        let window = LiveBoardWindow(
            station: Station(id: "S08328", name: "Appiano"), from: now.addingTimeInterval(-3_600),
            to: now.addingTimeInterval(3 * 3_600), lateSince: nil
        )
        let departures = Dictionary(uniqueKeysWithValues: RfiMonitorParser.departures(rows, window: window, fetchedAt: now).map { ($0.number, $0) })
        XCTAssertEqual(departures["20729"]?.status?.value, .delayed)
        XCTAssertNil(departures["20729"]?.expected)
        XCTAssertEqual(departures["20729"]?.isLateWithoutMinutes, true)
        XCTAssertNil(departures["20539"]?.status)
        XCTAssertEqual(departures["20381"]?.status?.value, .delayed)
        XCTAssertEqual(departures["20381"]?.expected.map { LiveRailTime.hhmm($0.value) }, "20:37")
        XCTAssertEqual(departures["20383"]?.status?.value, .cancelled)
        XCTAssertNil(departures["20383"]?.expected, "a cancelled train has no expected time")
        XCTAssertEqual(departures["20381"]?.observation.expiresAt, now.addingTimeInterval(45))
    }

    func testClockTimesFallOnTheDayOfTheWindowAcrossMidnight() throws {
        // 23:50 in Rome on 3 November: «00:05» is tomorrow's, «20:13» left long ago.
        let now = LiveTestDates.rome(2026, 11, 3, 23, 50)
        let rows = RfiMonitorParser.rows(try LiveFixtures.text("rfi-monitor-appiano-ritardi.html"))
        let window = LiveBoardWindow(
            station: Station(id: "S08328", name: "Appiano"), from: now.addingTimeInterval(-600),
            to: now.addingTimeInterval(6 * 3_600), lateSince: now.addingTimeInterval(-2 * 3_600)
        )
        let departures = RfiMonitorParser.departures(rows, window: window, fetchedAt: now)
        XCTAssertEqual(departures.map(\.number), ["20385", "20387"])
        XCTAssertEqual(departures.map { LiveRailTime.isoDay($0.scheduled) }, ["2026-11-03", "2026-11-04"])
        XCTAssertEqual(departures.map { $0.delayMinutes }, [3, 5])
        XCTAssertEqual(departures[1].id, "rfi-station-board:20387:2026-11-04")
        XCTAssertEqual(LiveBoardTime.expected("00:02", scheduled: LiveTestDates.rome(2026, 11, 3, 23, 58)), LiveTestDates.rome(2026, 11, 4, 0, 2))
        XCTAssertEqual(LiveBoardTime.expected("23:55", scheduled: LiveTestDates.rome(2026, 11, 4, 0, 5)), LiveTestDates.rome(2026, 11, 3, 23, 55))
        XCTAssertNil(LiveBoardTime.resolve("25:00", from: now, to: now))
    }

    func testTheOfficialSelectorGivesOnlyUnambiguousPlaces() throws {
        let catalogue = RfiMonitorParser.catalogue(try LiveFixtures.text("rfi-elenco-localita.html"))
        XCTAssertEqual(catalogue["APPIANO"], [3428])
        XCTAssertEqual(catalogue["FORLI"], [102], "accents are folded")
        XCTAssertEqual(catalogue["CASTELLO"], [200, 201])
        XCTAssertNil(catalogue["SELEZIONA"], "value 0 is not a place")
    }

    func testAStationOutsideTheKnownListIsResolvedFromTheSelectorOnce() async throws {
        let selector = try LiveFixtures.text("rfi-elenco-localita.html")
        let monitor = try LiveFixtures.text("rfi-monitor-roma-termini.html")
        let transport = FakeLiveTransport { request in
            request.url.absoluteString == RfiLiveSource.catalogueURL ? FakeLiveTransport.ok(selector) : FakeLiveTransport.ok(monitor)
        }
        let source = makeSource(transport, now: august)
        let window = LiveBoardWindow(
            station: Station(id: "S99999", name: "Nuova Stazione"), from: Date(timeIntervalSince1970: 1_786_690_800),
            to: Date(timeIntervalSince1970: 1_786_712_400), lateSince: nil
        )
        let answer = await source.departures(for: window)
        guard case let .answered(rows, _) = answer else { return XCTFail("expected rows, got \(answer)") }
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(transport.paths, [
            "https://iechub.rfi.it/ArriviPartenze",
            "https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=9876&arrivals=False",
        ])
        let first = try XCTUnwrap(rows.first)
        XCTAssertEqual(LiveRailTime.hhmm(first.scheduled), "10:00")
        XCTAssertEqual(first.expected.map { LiveRailTime.hhmm($0.value) }, "10:12")
        XCTAssertEqual(first.platform?.value, "7")
        XCTAssertEqual(first.status?.value, .delayed)
        XCTAssertEqual(first.sources, [.rfiStationBoard])

        // The selector is not asked again within the day; an ambiguous name has no place.
        let ambiguous = LiveBoardWindow(station: Station(id: "S88888", name: "Castello"), from: window.from, to: window.to, lateSince: nil)
        let notCovered = await source.departures(for: ambiguous)
        XCTAssertEqual(notCovered, .unavailable(.notCovered))
        XCTAssertEqual(transport.requests.count, 2)
    }

    func testAKnownStationNeedsNoSelectorAndTheMonitorIsAskedOverHTTPSOnly() async throws {
        let monitor = try LiveFixtures.text("rfi-monitor-roma-termini.html")
        let transport = FakeLiveTransport { _ in FakeLiveTransport.ok(monitor) }
        let window = LiveBoardWindow(
            station: Station(id: "S08409", name: "Roma Termini", railwayCode: "S08409"),
            from: Date(timeIntervalSince1970: 1_786_690_800), to: Date(timeIntervalSince1970: 1_786_712_400), lateSince: nil
        )
        _ = await makeSource(transport, now: august).departures(for: window)
        XCTAssertEqual(transport.paths, ["https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=2416&arrivals=False"])
        XCTAssertEqual(transport.requests.first?.maximumBytes, 2 * 1_048_576)

        let failing = FakeLiveTransport { _ in FakeLiveTransport.status(500) }
        let unavailable = await makeSource(failing, now: august).departures(for: window)
        XCTAssertEqual(unavailable, .unavailable(.sourceUnavailable))
    }

    func testMalformedPagesGiveNoRows() {
        XCTAssertEqual(RfiMonitorParser.rows("<html><body>Servizio non disponibile</body></html>"), [])
        XCTAssertEqual(RfiMonitorParser.rows("<tr name=\"treno\"><td id=\"RTreno\">1</td><td id=\"RStazione\">X</td>"), [], "no time")
        XCTAssertEqual(RfiMonitorParser.rows("<tr name=treno><td id=RTreno>1<td id=RStazione>Roma<td id=ROrario>10:00").map(\.destination), ["Roma"])
        XCTAssertEqual(RfiMonitorParser.catalogue("<select id=\"ElencoLocalita\"><option value=\"-1\">X</option><option value=\"abc\">Y</option>"), [:])
        XCTAssertEqual(LiveHTML("<a href='x'>&lt;b&gt; &amp; &#233;&#xE8; &unknown;</a>").elements { $0.name == "a" }.count, 1)
        let page = LiveHTML("<a href='x'>&lt;b&gt; &amp; &#233;&#xE8; &unknown;</a>")
        XCTAssertEqual(page.first { $0.name == "a" }.map { page.text($0) }, "<b> & éè &unknown;")
    }
}
