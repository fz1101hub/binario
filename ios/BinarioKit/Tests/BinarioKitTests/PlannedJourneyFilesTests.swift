import Foundation
import XCTest
@testable import BinarioKit

/// The Agenda's files: the calendar in Android's format (the companion's local-calendar tests,
/// EXPO_PORT_PLAN §3.2) and the JSON transfer with Android's preview and conflict policy (F26).
final class PlannedJourneyFilesTests: XCTestCase {
    private let roma = ("S08409", "Roma Termini")
    private let milano = ("S01700", "Milano Centrale")
    private let firenze = ("S06421", "Firenze Santa Maria Novella")
    private let createdAt = Date(timeIntervalSince1970: 1_760_000_000)

    // MARK: Calendar export

    func testTheCalendarIsAndroidsFormatInUTCWhateverThePhonesZone() throws {
        let saved = NSTimeZone.default
        defer { NSTimeZone.default = saved }
        let label = "Milano Centrale; binario, da verificare"
        for zone in ["UTC", "America/New_York"] {
            NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: zone))
            let journey = try journey("j-1", "2026-07-12", "23:30", label: label, reminderMinutes: 15)
            let calendar = try PlannedJourneyIcs.export([journey], generatedAt: try utc("2026-11-10T12:00:05Z"))
            XCTAssertTrue(calendar.hasPrefix("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Binario//Planned Journeys 1.0//IT\r\n"), zone)
            XCTAssertTrue(calendar.hasSuffix("END:VCALENDAR\r\n"), zone)
            let lines = calendar.components(separatedBy: "\r\n").dropLast()
            XCTAssertTrue(lines.allSatisfy { $0.utf8.count <= 75 && !$0.contains("\n") && !$0.contains("\r") }, zone)
            XCTAssertTrue(lines.contains("DTSTART:20260712T213000Z"), zone)
            XCTAssertTrue(lines.contains("DTSTAMP:20261110T120005Z"), zone)
            XCTAssertTrue(lines.contains("SUMMARY:Milano Centrale\\; binario\\, da verificare"), zone)
            // Android's UID: SHA-256 of "j-1|2026-07-12", first 16 bytes in hex.
            XCTAssertTrue(lines.contains("UID:69fd39dc11b001f21e8150fe80b966e3@binario.local"), zone)
            XCTAssertTrue(lines.contains("X-BINARIO-JOURNEY-ID:j-1"), zone)
            XCTAssertTrue(lines.contains("X-BINARIO-OCCURRENCE-DATE:2026-07-12"), zone)
            XCTAssertTrue(lines.contains("TRIGGER:-PT15M"), zone)
            XCTAssertFalse(lines.contains { $0.hasPrefix("DTEND") || $0.hasPrefix("TZID") || $0.hasPrefix("BEGIN:VTIMEZONE") }, zone)

            let events = try PlannedJourneyIcs.parseOwnedCalendar(calendar).get()
            XCTAssertEqual(events.count, 1)
            XCTAssertEqual(events.first?.summary, label, "the escaping round trip gives the label back")
            XCTAssertEqual(events.first?.startsAt, try utc("2026-07-12T21:30:00Z"))
            XCTAssertEqual(events.first?.reminderMinutes, 15)
            XCTAssertEqual(events.first?.journeyID, "j-1")
        }
    }

    func testFoldingNeverSplitsACharacterAndUnfoldingGivesTheLineBack() throws {
        let line = "SUMMARY:" + String(repeating: "→", count: 40)
        let folded = PlannedJourneyIcs.fold(line)
        XCTAssertGreaterThan(folded.count, 1)
        XCTAssertTrue(folded.allSatisfy { $0.utf8.count <= 75 })
        XCTAssertTrue(folded.dropFirst().allSatisfy { $0.hasPrefix(" →") || $0 == " " })
        XCTAssertEqual(PlannedJourneyIcs.unfold(folded.joined(separator: "\r\n")), [line])

        let arrows = String(repeating: "→", count: 40)
        let calendar = try PlannedJourneyIcs.export(
            [try journey("j-1", "2026-07-12", "09:00", label: arrows)], generatedAt: createdAt
        )
        XCTAssertEqual(try PlannedJourneyIcs.parseOwnedCalendar(calendar).get().first?.summary, arrows)
    }

    func testTextIsEscapedAndReadBack() {
        let text = "a\\b; c, d\ne\r\nf"
        let escaped = PlannedJourneyIcs.escape(text)
        XCTAssertEqual(escaped, "a\\\\b\\; c\\, d\\ne\\nf")
        XCTAssertEqual(PlannedJourneyIcs.unescape(escaped), "a\\b; c, d\ne\nf")
    }

    func testStartTimesInUTCInAZoneOrFloatingAndWhatIsRefused() throws {
        func start(_ line: String) -> Result<Date, PlannedJourneyIcsError> { PlannedJourneyIcs.start(line) }
        XCTAssertEqual(start("DTSTART:20261112T070000Z"), .success(try utc("2026-11-12T07:00:00Z")))
        XCTAssertEqual(start("DTSTART;TZID=Europe/Rome:20260712T090000"), .success(try utc("2026-07-12T07:00:00Z")))
        XCTAssertEqual(start("DTSTART;TZID=\"Europe/London\":20260712T090000"), .success(try utc("2026-07-12T08:00:00Z")))
        XCTAssertEqual(start("DTSTART:20260712T090000"), .success(try utc("2026-07-12T07:00:00Z")), "floating is Italian time")
        XCTAssertEqual(start("DTSTART;VALUE=DATE-TIME:20261112T081500"), .success(try utc("2026-11-12T07:15:00Z")))
        // A quoted TZID may hold a colon: the value is read after it, and the zone is then refused
        // as unknown (not as a malformed line, and never read as another time).
        XCTAssertEqual(start("DTSTART;TZID=\"(UTC+01:00) Amsterdam, Berlin, Bern, Rome\":20260712T090000"), .failure(.unknownTimeZone))
        let quoted = try XCTUnwrap(PlannedJourneyIcs.property("DTSTART;TZID=\"(UTC+01:00) Amsterdam, Berlin, Bern, Rome\":20260712T090000"))
        XCTAssertEqual(quoted.parameters["TZID"], "(UTC+01:00) Amsterdam, Berlin, Bern, Rome")
        XCTAssertEqual(quoted.value, "20260712T090000")
        XCTAssertEqual(start("DTSTART;TZID=W. Europe Standard Time:20260712T090000"), .failure(.unknownTimeZone))
        XCTAssertEqual(start("DTSTART;VALUE=DATE:20261112"), .failure(.allDay))
        XCTAssertEqual(start("DTSTART:20261112"), .failure(.allDay))
        XCTAssertEqual(start("DTSTART:20270328T023000"), .failure(.nonexistentLocalTime))
        XCTAssertEqual(start("DTSTART;TZID=Europe/Rome:20270328T023000"), .failure(.nonexistentLocalTime))
        XCTAssertEqual(start("DTSTART:2026-11-12T07:00:00Z"), .failure(.malformed))
        XCTAssertEqual(start("DTSTART;TZID=Europe/Rome:20261112T070000Z"), .failure(.malformed))
    }

    func testACalendarRewrittenInAZoneIsStillReadAndAnUnknownZoneRefusesIt() throws {
        let original = try PlannedJourneyIcs.export([try journey("j-1", "2026-07-12", "09:00")], generatedAt: createdAt)
        let inRome = original.replacingOccurrences(of: "DTSTART:20260712T070000Z", with: "DTSTART;TZID=\"Europe/Rome\":20260712T090000")
        XCTAssertNotEqual(inRome, original)
        XCTAssertEqual(try PlannedJourneyIcs.parseOwnedCalendar(inRome).get().first?.startsAt, try utc("2026-07-12T07:00:00Z"))
        let windows = original.replacingOccurrences(
            of: "DTSTART:20260712T070000Z", with: "DTSTART;TZID=\"(UTC+01:00) Amsterdam, Berlin, Bern, Rome\":20260712T090000"
        )
        XCTAssertEqual(PlannedJourneyIcs.parseOwnedCalendar(windows), .failure(.unknownTimeZone))
    }

    func testCalendarsAreBoundedInBytesAndEvents() throws {
        let many = try (0..<130).map { index in
            try journey("j-\(index)", "2026-07-12", time(minutes: 6 * 60 + index))
        }
        let calendar = try PlannedJourneyIcs.export(many, generatedAt: createdAt)
        let events = try PlannedJourneyIcs.parseOwnedCalendar(calendar).get()
        XCTAssertEqual(events.count, 120, "the export keeps the first 120 to leave")
        XCTAssertEqual(events.first?.journeyID, "j-0")

        // One more event than the limit refuses the whole calendar.
        let block = try XCTUnwrap(calendar.range(of: "BEGIN:VEVENT")).lowerBound
        let end = try XCTUnwrap(calendar.range(of: "END:VEVENT\r\n")).upperBound
        let oneMore = calendar.replacingOccurrences(of: "END:VCALENDAR\r\n", with: String(calendar[block..<end]) + "END:VCALENDAR\r\n")
        XCTAssertEqual(PlannedJourneyIcs.parseOwnedCalendar(oneMore), .failure(.tooManyEvents))

        let padded = calendar.replacingOccurrences(
            of: "END:VCALENDAR\r\n",
            with: String(repeating: "X-PADDING:" + String(repeating: "x", count: 60) + "\r\n", count: 4_000) + "END:VCALENDAR\r\n"
        )
        XCTAssertGreaterThan(padded.utf8.count, PlannedJourneyIcsContract.maximumBytes)
        XCTAssertEqual(PlannedJourneyIcs.parseOwnedCalendar(padded), .failure(.tooLarge))
    }

    func testForeignTamperedOrOverlongCalendarsAreRefused() throws {
        let calendar = try PlannedJourneyIcs.export([try journey("j-1", "2026-07-12", "09:00")], generatedAt: createdAt)
        XCTAssertEqual(
            PlannedJourneyIcs.parseOwnedCalendar(calendar.replacingOccurrences(of: "PRODID:-//Binario//", with: "PRODID:-//Other//")),
            .failure(.notBinario)
        )
        XCTAssertEqual(
            PlannedJourneyIcs.parseOwnedCalendar(calendar.replacingOccurrences(of: "X-BINARIO-JOURNEY-ID:j-1", with: "X-BINARIO-JOURNEY-ID:j-2")),
            .failure(.notBinario)
        )
        XCTAssertEqual(
            PlannedJourneyIcs.parseOwnedCalendar(calendar.replacingOccurrences(of: "METHOD:PUBLISH", with: "METHOD:" + String(repeating: "P", count: 80))),
            .failure(.malformed)
        )
    }

    func testACalendarIsComparedWithTheAgendaNeverImportedAsJourneys() throws {
        let kept = try journey("j-1", "2026-07-12", "09:00", reminderMinutes: 15)
        let moved = try journey("j-2", "2026-07-13", "09:00", from: milano, to: firenze)
        let gone = try journey("j-3", "2026-07-14", "09:00", from: firenze, to: roma)
        let events = try PlannedJourneyIcs.parseOwnedCalendar(
            PlannedJourneyIcs.export([kept, moved, gone], generatedAt: createdAt)
        ).get()
        let movedNow = try journey("j-2", "2026-07-13", "10:00", from: milano, to: firenze)
        let preview = PlannedJourneyIcs.preview(events, against: [kept, movedNow])
        XCTAssertEqual(preview.matching.map(\.journeyID), ["j-1"])
        XCTAssertEqual(preview.conflicting.map(\.journeyID), ["j-2"])
        XCTAssertEqual(preview.unlinked.map(\.journeyID), ["j-3"])
        let otherDay = try journey("j-2", "2026-07-15", "09:00", from: milano, to: firenze)
        let reminderChanged = try journey("j-1", "2026-07-12", "09:00", reminderMinutes: 30)
        let changed = PlannedJourneyIcs.preview(events, against: [reminderChanged, otherDay])
        XCTAssertEqual(changed.conflicting.map(\.journeyID), ["j-1"], "another reminder is a conflict")
        XCTAssertEqual(changed.unlinked.map(\.journeyID), ["j-2", "j-3"], "another date is another occurrence")

        let data = Data(try PlannedJourneyIcs.export([kept], generatedAt: createdAt).utf8)
        XCTAssertEqual(PlannedJourneyFileImport.preview(data, existing: [kept]), .calendar(PlannedJourneyIcsPreview(
            matching: events.filter { $0.journeyID == "j-1" }, conflicting: [], unlinked: []
        )))
    }

    // MARK: JSON transfer

    func testAnExportReadsBackAsNewJourneysOnAnEmptyAgenda() throws {
        let journeys = [try journey("j-1", "2026-07-12", "09:00", reminderMinutes: 30),
                        try journey("j-2", "2026-07-13", "09:00", from: milano, to: firenze)]
        let data = try PlannedJourneyTransfer.encode(PlannedJourneyCollection(validating: journeys))
        guard case let .journeys(preview)? = PlannedJourneyFileImport.preview(data, existing: []) else {
            return XCTFail("an Agenda export is a JSON transfer")
        }
        XCTAssertEqual(preview.accepted, journeys)
        XCTAssertTrue(preview.duplicates.isEmpty)
        XCTAssertTrue(preview.conflicts.isEmpty)
        XCTAssertEqual(preview.unreadableRecords, 0)
        XCTAssertTrue(preview.hasChanges)
    }

    func testThePreviewAndAndroidsConflictPolicy() throws {
        let (store, defaults, suite) = try makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let mine = try journey("j-1", "2026-07-12", "09:00", label: "Mio")
        let other = try journey("j-2", "2026-07-13", "09:00", from: milano, to: firenze)
        _ = try store.add(mine).get()
        _ = try store.add(other).get()

        let duplicate = try journey("x-9", "2026-07-12", "09:00", label: "Stesso viaggio")
        let conflict = try journey("j-2", "2026-07-20", "18:00", from: milano, to: firenze, label: "Dal file")
        let new = try journey("j-3", "2026-07-14", "09:00", from: firenze, to: roma)
        let preview = PlannedJourneyFileImport.preview([duplicate, conflict, new], unreadableRecords: 0, existing: store.load().journeys)
        XCTAssertEqual(preview.duplicates, [duplicate])
        XCTAssertEqual(preview.conflicts, [PlannedJourneyImportConflict(existing: other, imported: conflict)])
        XCTAssertEqual(preview.accepted, [new])

        let kept = try store.applyImport(preview, policy: .keepExisting).get()
        XCTAssertEqual(kept.journeys.map(\.id), ["j-1", "j-2", "j-3"])
        XCTAssertEqual(kept.journeys[1], other)

        let replaced = try store.applyImport(
            PlannedJourneyFileImport.preview([conflict], unreadableRecords: 0, existing: store.load().journeys),
            policy: .replaceExisting
        ).get()
        XCTAssertEqual(replaced.journeys.map(\.id), ["j-1", "j-2", "j-3"], "replaced in its place")
        XCTAssertEqual(replaced.journeys[1].label, "Dal file")
    }

    func testAnImportThatDoesNotFitOrAReadOnlyAgendaWritesNothing() throws {
        let (store, defaults, suite) = try makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        for index in 0..<39 {
            _ = try store.add(try journey("j-\(index)", "2026-07-12", time(minutes: 6 * 60 + index))).get()
        }
        let before = defaults.data(forKey: PlannedJourneyStore.storageKey)
        let two = [try journey("n-1", "2026-07-13", "09:00"), try journey("n-2", "2026-07-13", "10:00")]
        let preview = PlannedJourneyFileImport.preview(two, unreadableRecords: 0, existing: store.load().journeys)
        XCTAssertEqual(store.applyImport(preview, policy: .keepExisting), .failure(.full))
        XCTAssertEqual(defaults.data(forKey: PlannedJourneyStore.storageKey), before)

        defaults.set(Data("not json".utf8), forKey: PlannedJourneyStore.storageKey)
        XCTAssertEqual(store.applyImport(preview, policy: .keepExisting), .failure(.readOnly))
        XCTAssertEqual(defaults.data(forKey: PlannedJourneyStore.storageKey), Data("not json".utf8))
    }

    func testRepeatingJourneysAreOfferedAndOnlyInvalidRecordsAreCounted() throws {
        let valid = try journey("j-1", "2026-07-12", "09:00")
        var document = try XCTUnwrap(try JSONSerialization.jsonObject(
            with: PlannedJourneyTransfer.encode(PlannedJourneyCollection(validating: [valid]))
        ) as? [String: Any])
        var records = try XCTUnwrap(document["journeys"] as? [[String: Any]])
        // 12 July 2026 is a Sunday: a repetition from that date is a journey the iPhone keeps.
        var recurring = records[0]
        recurring["id"] = "j-weekly"
        recurring["recurrence"] = ["schemaVersion": 1, "weekdays": ["SUNDAY", "WEDNESDAY"], "startsOn": "2026-07-12", "occurrenceLimit": 4]
        records.append(recurring)
        // One that does not start on the first departure's date is not valid (Android refuses it too).
        var mismatched = records[0]
        mismatched["id"] = "j-mismatched"
        mismatched["recurrence"] = ["schemaVersion": 1, "weekdays": ["MONDAY"], "startsOn": "2026-07-13", "occurrenceLimit": 4]
        records.append(mismatched)
        document["journeys"] = records
        let data = try JSONSerialization.data(withJSONObject: document)
        guard case let .journeys(preview)? = PlannedJourneyFileImport.preview(data, existing: []) else {
            return XCTFail("a document of the right kind is offered")
        }
        XCTAssertEqual(preview.accepted.map(\.id), ["j-1", "j-weekly"])
        XCTAssertEqual(preview.accepted.last?.occurrences.map(\.date.iso), ["2026-07-12", "2026-07-15", "2026-07-19", "2026-07-22"])
        XCTAssertEqual(preview.unreadableRecords, 1)
    }

    func testWhatIsNotAnAgendaFileIsRefusedUnread() throws {
        XCTAssertNil(PlannedJourneyFileImport.preview(Data(), existing: []))
        XCTAssertNil(PlannedJourneyFileImport.preview(Data("{\"kind\":\"other\",\"schemaVersion\":1,\"journeys\":[]}".utf8), existing: []))
        XCTAssertNil(PlannedJourneyFileImport.preview(Data("BEGIN:VCALENDAR\r\nPRODID:-//Other//EN\r\nEND:VCALENDAR\r\n".utf8), existing: []))
        XCTAssertNil(PlannedJourneyFileImport.preview(Data(repeating: 0x20, count: PlannedJourneySchema.maximumEncodedBytes + 1), existing: []))
        // Deeper than the contract nests: refused before the parser sees it (Android's guard).
        let deep = Data(("{\"kind\":\"binario_planned_journeys\",\"schemaVersion\":1,\"journeys\":"
            + String(repeating: "[", count: 20) + String(repeating: "]", count: 20) + "}").utf8)
        XCTAssertGreaterThan(PlannedJourneyTransfer.nestingDepth(deep), PlannedJourneySchema.maximumNestingDepth)
        XCTAssertNil(PlannedJourneyTransfer.decode(deep))
        XCTAssertNil(PlannedJourneyFileImport.preview(deep, existing: []))
        XCTAssertEqual(PlannedJourneyTransfer.nestingDepth(Data("{\"a\":\"[[[[\\\"[[\"}".utf8)), 1, "brackets inside strings do not count")
    }

    // MARK: Helpers

    private func journey(
        _ id: String,
        _ date: String,
        _ time: String,
        from origin: (String, String)? = nil,
        to destination: (String, String)? = nil,
        label: String = "",
        reminderMinutes: Int? = nil
    ) throws -> PlannedJourney {
        let start = origin ?? roma
        let end = destination ?? milano
        let departure = try PlannedJourneyDateTime(
            date: XCTUnwrap(PlannedLocalDate(iso: date)),
            time: XCTUnwrap(PlannedLocalTime(hhmm: time))
        )
        let segment = try PlannedJourneySegment(
            origin: PlannedJourneyStation(canonicalId: start.0, displayName: start.1, railwayCode: start.0),
            destination: PlannedJourneyStation(canonicalId: end.0, displayName: end.1, railwayCode: end.0),
            departure: departure
        )
        return try PlannedJourney(id: id, label: label, segment: segment, reminderMinutes: reminderMinutes, createdAt: createdAt)
    }

    private func time(minutes: Int) -> String {
        PlannedLocalTime(hour: minutes / 60, minute: minutes % 60)?.hhmm ?? "00:00"
    }

    private func utc(_ text: String) throws -> Date {
        try XCTUnwrap(PlannedJourneyInstant.parse(text), text)
    }

    private func makeStore() throws -> (PlannedJourneyStore, UserDefaults, String) {
        let suite = "it.binario.ios.tests.files.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        return (PlannedJourneyStore(defaults: defaults), defaults, suite)
    }
}
