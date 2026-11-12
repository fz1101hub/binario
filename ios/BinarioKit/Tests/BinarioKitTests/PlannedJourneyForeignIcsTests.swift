import Foundation
import XCTest
@testable import BinarioKit

/// Calendars of other apps (EXPO_PORT_PLAN F25, §3.2 import cases) on Android's rules: an event
/// titled «A → B» becomes a proposed journey at its Italian time, through the text import's review
/// and free places; an unknown zone, an all-day event or a skipped local time is never guessed.
final class PlannedJourneyForeignIcsTests: XCTestCase {
    private let resolver = PlannedJourneyStationResolver(stations: [
        Station(id: "S08409", name: "Roma Termini", railwayCode: "S08409"),
        Station(id: "S01700", name: "Milano Centrale", railwayCode: "S01700"),
        Station(id: "S06421", name: "Firenze Santa Maria Novella", railwayCode: "S06421"),
    ])
    private let now = Date(timeIntervalSince1970: 1_760_000_000)

    func testStartTimesBecomeItalianDatesAndTimes() throws {
        let cases: [(String, String, String)] = [
            ("DTSTART:20261112T070000Z", "2026-11-12", "08:00"),
            ("DTSTART:20260712T153000Z", "2026-07-12", "17:30"),
            ("DTSTART:20261111T233000Z", "2026-11-12", "00:30"),
            ("DTSTART;TZID=Europe/Rome:20260712T090000", "2026-07-12", "09:00"),
            ("DTSTART;TZID=\"Europe/London\":20260712T090000", "2026-07-12", "10:00"),
            ("DTSTART:20260712T090000", "2026-07-12", "09:00"),
            ("DTSTART;VALUE=DATE-TIME:20261112T081500", "2026-11-12", "08:15"),
        ]
        for (start, date, time) in cases {
            let calendar = try XCTUnwrap(PlannedJourneyForeignIcs.read(foreign([event("Roma Termini → Milano Centrale", start)])))
            XCTAssertEqual(calendar.drafts.map(\.date.iso), [date], start)
            XCTAssertEqual(calendar.drafts.map(\.time.hhmm), [time], start)
            XCTAssertEqual(calendar.skippedEvents, 0, start)
        }
    }

    func testWhatCannotBeAJourneyIsSkippedNeverGuessed() throws {
        let skipped = [
            event("Roma Termini → Milano Centrale", "DTSTART;TZID=W. Europe Standard Time:20260712T090000"),
            event("Roma Termini → Milano Centrale", "DTSTART;TZID=\"(UTC+01:00) Amsterdam, Berlin, Bern, Rome\":20260712T090000"),
            event("Roma Termini → Milano Centrale", "DTSTART;VALUE=DATE:20260712"),
            event("Riunione", "DTSTART:20260712T090000"),
            event("Roma Termini → Milano Centrale", "DTSTART;TZID=Europe/Rome:20270328T023000"),
        ]
        let calendar = try XCTUnwrap(PlannedJourneyForeignIcs.read(foreign(skipped)))
        XCTAssertTrue(calendar.drafts.isEmpty)
        XCTAssertEqual(calendar.skippedEvents, 5)
        XCTAssertNil(PlannedJourneyForeignIcs.read("not a calendar"))
        XCTAssertNil(PlannedJourneyForeignIcs.read(String(repeating: "x", count: PlannedJourneyIcsContract.maximumBytes + 1)))
    }

    func testAFoldedTitleIsJoinedAndARepeatingEventGivesItsFirstStartOnly() throws {
        let folded = [
            "BEGIN:VEVENT",
            "SUMMARY:Firenze Santa Maria Novella → Roma Termini\\, binari regi",
            " onali",
            "DTSTART;TZID=Europe/Rome:20261205T181000",
            "RRULE:FREQ=WEEKLY;COUNT=10",
            "END:VEVENT",
        ].joined(separator: "\r\n")
        let calendar = try XCTUnwrap(PlannedJourneyForeignIcs.read(foreign([folded])))
        XCTAssertEqual(calendar.drafts.count, 1)
        XCTAssertEqual(calendar.drafts.first?.origin, "Firenze Santa Maria Novella")
        XCTAssertEqual(calendar.drafts.first?.date.iso, "2026-12-05")
        XCTAssertEqual(calendar.drafts.first?.time.hhmm, "18:10")
    }

    func testTheReviewMergesRepeatsLeavesOutTheAgendasAndKeepsToTheFreePlaces() throws {
        let events = [
            event("Roma Termini → Milano Centrale", "DTSTART:20261112T070000Z"),
            event("Roma Termini → Milano Centrale", "DTSTART:20261112T070000Z"),
            event("Roma Termini → Firenze Santa Maria Novella", "DTSTART:20261112T070000Z"),
            event("Milano Centrale → Roma Termini", "DTSTART:20261113T170000Z"),
        ]
        let calendar = try XCTUnwrap(PlannedJourneyForeignIcs.read(foreign(events)))
        let existing = try PlannedJourneyForm(
            origin: StationIdentity(id: "S01700", name: "Milano Centrale", railwayCode: "S01700"),
            destination: StationIdentity(id: "S08409", name: "Roma Termini", railwayCode: "S08409"),
            date: PlannedLocalDate(iso: "2026-11-13"), time: PlannedLocalTime(hhmm: "18:00")
        ).journey(id: "in-agenda", now: now, editing: nil).get()
        var counter = 0
        let preview = PlannedJourneyTextImport.preview(
            calendar.drafts, skipped: calendar.skippedEvents, resolver: resolver, existing: [existing], now: now,
            makeID: { counter += 1; return "c-\(counter)" }
        )
        // The repeat is merged, two trips at the same time are kept, the Agenda's journey is left out.
        XCTAssertEqual(preview.journeys.map(\.destination.canonicalId), ["S01700", "S06421"])
        XCTAssertEqual(preview.skippedLines, 2)
    }

    func testBinariosOwnCalendarIsNotAForeignOne() throws {
        let journey = try PlannedJourneyForm(
            origin: StationIdentity(id: "S08409", name: "Roma Termini", railwayCode: "S08409"),
            destination: StationIdentity(id: "S01700", name: "Milano Centrale", railwayCode: "S01700"),
            date: PlannedLocalDate(iso: "2026-11-13"), time: PlannedLocalTime(hhmm: "18:00")
        ).journey(id: "j-1", now: now, editing: nil).get()
        XCTAssertFalse(PlannedJourneyForeignIcs.isForeign(try PlannedJourneyIcs.export([journey], generatedAt: now)))
        XCTAssertTrue(PlannedJourneyForeignIcs.isForeign(foreign([])))
    }

    // MARK: Helpers

    private func foreign(_ events: [String]) -> String {
        (["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Other//Calendar//EN"] + events + ["END:VCALENDAR"])
            .joined(separator: "\r\n") + "\r\n"
    }

    private func event(_ summary: String, _ start: String) -> String {
        ["BEGIN:VEVENT", "UID:\(UUID().uuidString)", "SUMMARY:" + summary, start, "END:VEVENT"].joined(separator: "\r\n")
    }
}
