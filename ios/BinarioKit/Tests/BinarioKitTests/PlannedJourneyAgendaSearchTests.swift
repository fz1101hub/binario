import Foundation
import XCTest
@testable import BinarioKit

/// The Agenda's search and month filter (EXPO_PORT_PLAN F13) and the «Pronto per partire» countdown
/// (F20, §3.5), on Android's rules.
final class PlannedJourneyAgendaSearchTests: XCTestCase {
    private let createdAt = Date(timeIntervalSince1970: 1_760_000_000)

    func testEveryWordMustAppearInTheLabelAStationTheDateOrTheTimeAccentsAndCaseIgnored() throws {
        let journey = try make("j-1", "S06421", "Firenze Santa Maria Novella", "S08409", "Roma Termini", "2026-12-24", "07:05", label: "Natale à casa")
        let now = try utc("2026-12-01T00:00:00Z")
        for query in ["", "  ", "firenze roma", "FIRENZE", "S06421", "natale a casa", "24/12/2026", "2026-12-24", "07:05", "santa  maria"] {
            XCTAssertTrue(PlannedJourneyAgendaSearch.matches(journey, now: now, query: query), query)
        }
        for query in ["milano", "firenze milano", "25/12/2026", "08:05"] {
            XCTAssertFalse(PlannedJourneyAgendaSearch.matches(journey, now: now, query: query), query)
        }
    }

    func testTheMonthsAreThoseOfTheShownDeparturesInTheListsOrder() throws {
        let december = try make("j-1", "S08409", "Roma Termini", "S01700", "Milano Centrale", "2026-12-24", "07:05")
        let november = try make("j-2", "S01700", "Milano Centrale", "S08409", "Roma Termini", "2026-11-20", "18:00")
        let alsoDecember = try make("j-3", "S08409", "Roma Termini", "S06421", "Firenze Santa Maria Novella", "2026-12-02", "09:00")
        let now = try utc("2026-11-01T00:00:00Z")
        let listed = PlannedJourneyAgenda.upcoming([december, november, alsoDecember], now: now)
        XCTAssertEqual(listed.map(\.id), ["j-2", "j-3", "j-1"])
        let months = PlannedJourneyAgendaSearch.months(listed, now: now)
        XCTAssertEqual(months.map { "\($0.year)-\($0.month)" }, ["2026-11", "2026-12"])
        XCTAssertEqual(PlannedJourneyAgendaSearch.filter(listed, now: now, query: "", month: months[1]).map(\.id), ["j-3", "j-1"])
        XCTAssertEqual(PlannedJourneyAgendaSearch.filter(listed, now: now, query: "firenze", month: months[1]).map(\.id), ["j-3"])
        XCTAssertEqual(PlannedJourneyAgendaSearch.filter(listed, now: now, query: "milano", month: nil).map(\.id), ["j-2", "j-1"])
    }

    func testTheCountdownRoundsUpToTheMinuteAndIsNeverNegative() throws {
        let departure = try utc("2026-10-02T07:30:00Z")
        XCTAssertEqual(PlannedJourneyCountdown.between(now: try utc("2026-10-02T06:20:00Z"), departure: departure), PlannedJourneyCountdown(hours: 1, minutes: 10))
        XCTAssertEqual(PlannedJourneyCountdown.between(now: try utc("2026-10-02T06:20:01Z"), departure: departure), PlannedJourneyCountdown(hours: 1, minutes: 10))
        XCTAssertEqual(PlannedJourneyCountdown.between(now: try utc("2026-10-02T05:30:00Z"), departure: departure), PlannedJourneyCountdown(hours: 2, minutes: 0))
        XCTAssertEqual(PlannedJourneyCountdown.between(now: try utc("2026-10-02T07:05:00Z"), departure: departure), PlannedJourneyCountdown(hours: 0, minutes: 25))
        XCTAssertEqual(PlannedJourneyCountdown.between(now: try utc("2026-10-02T08:00:00Z"), departure: departure), PlannedJourneyCountdown(hours: 0, minutes: 0))
    }

    // MARK: Helpers

    private func make(
        _ id: String, _ originID: String, _ originName: String, _ destinationID: String, _ destinationName: String,
        _ date: String, _ time: String, label: String = ""
    ) throws -> PlannedJourney {
        let segment = try PlannedJourneySegment(
            origin: PlannedJourneyStation(canonicalId: originID, displayName: originName, railwayCode: originID),
            destination: PlannedJourneyStation(canonicalId: destinationID, displayName: destinationName, railwayCode: destinationID),
            departure: PlannedJourneyDateTime(date: XCTUnwrap(PlannedLocalDate(iso: date)), time: XCTUnwrap(PlannedLocalTime(hhmm: time)))
        )
        return try PlannedJourney(id: id, label: label, segment: segment, createdAt: createdAt)
    }

    private func utc(_ text: String) throws -> Date {
        try XCTUnwrap(PlannedJourneyInstant.parse(text), text)
    }
}
