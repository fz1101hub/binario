import Foundation
import XCTest
@testable import BinarioKit

/// Journeys made on Android with several legs and a repetition (EXPO_PORT_PLAN F17, F19): the iPhone
/// reads them whole, keeps them, writes the document back byte for byte as an Android phone writes
/// it, and exports Android's calendar event for event. The documents below were written by Android's
/// own `PlannedJourneyTransfer` and `PlannedJourneyIcs` (branch port/expo-android-2).
final class PlannedJourneyAndroidInteropTests: XCTestCase {
    /// `PlannedJourneyTransfer.encode` on the JVM, whose org.json keeps no field order.
    private let androidJVM = [
            #"{"journeys":[{"createdAt":"2026-11-12T09:30:15.250Z","schemaVersion":1,"reminderMinutes":30,"itinera"#,
            #"ry":{"segments":[{"origin":{"canonicalId":"S08409","displayName":"Roma Termini","railwayCode":"S0840"#,
            #"9"},"destination":{"canonicalId":"S06421","displayName":"Firenze Santa Maria Novella","railwayCode":"#,
            #""S06421"},"id":"segment-1","departure":{"date":"2026-12-03","timeZone":"Europe/Rome","time":"07:10"}"#,
            #"},{"origin":{"canonicalId":"S06421","displayName":"Firenze Santa Maria Novella","railwayCode":"S0642"#,
            #"1"},"destination":{"canonicalId":"S05043","displayName":"Bologna Centrale"},"id":"segment-2","depart"#,
            #"ure":{"date":"2026-12-03","timeZone":"Europe/Rome","time":"08:55"}},{"origin":{"canonicalId":"S05043"#,
            #"","displayName":"Bologna Centrale"},"destination":{"canonicalId":"S01700","displayName":"Milano Cent"#,
            #"rale","railwayCode":"S01700"},"id":"segment-4","departure":{"date":"2026-12-03","timeZone":"Europe/R"#,
            #"ome","time":"10:05"}}]},"checklist":[{"id":"check-1","label":"Biglietti, coincidenza a Firenze","com"#,
            #"pleted":true}],"id":"android-multi","label":"Riunione a Milano","completedOccurrenceDates":[],"statu"#,
            #"s":"ACTIVE"},{"recurrence":{"endsOn":"2026-12-31","schemaVersion":1,"weekdays":["MONDAY","THURSDAY"]"#,
            #","occurrenceLimit":4,"startsOn":"2026-11-16"},"createdAt":"2026-11-12T09:31:00Z","schemaVersion":1,""#,
            #"reminderMinutes":10,"itinerary":{"segments":[{"origin":{"canonicalId":"S01700","displayName":"Milano"#,
            #" Centrale","railwayCode":"S01700"},"destination":{"canonicalId":"S05043","displayName":"Bologna Cent"#,
            #"rale"},"id":"segment-1","departure":{"date":"2026-11-16","timeZone":"Europe/Rome","time":"18:05"}},{"#,
            #""origin":{"canonicalId":"S05043","displayName":"Bologna Centrale"},"destination":{"canonicalId":"S06"#,
            #"421","displayName":"Firenze Santa Maria Novella","railwayCode":"S06421"},"id":"segment-2","departure"#,
            #"":{"date":"2026-11-16","timeZone":"Europe/Rome","time":"19:40"}}]},"checklist":[],"id":"android-week"#,
            #"ly","label":"","completedOccurrenceDates":["2026-11-16"],"status":"ACTIVE"},{"recurrence":{"schemaVe"#,
            #"rsion":1,"weekdays":["THURSDAY"],"occurrenceLimit":2,"startsOn":"2026-12-24"},"createdAt":"2026-11-1"#,
            #"2T09:32:00Z","archivedAt":"2026-11-13T10:00:00Z","schemaVersion":1,"itinerary":{"segments":[{"origin"#,
            #"":{"canonicalId":"S08409","displayName":"Roma Termini","railwayCode":"S08409"},"destination":{"canon"#,
            #"icalId":"S01700","displayName":"Milano Centrale","railwayCode":"S01700"},"id":"segment-1","departure"#,
            #"":{"date":"2026-12-24","timeZone":"Europe/Rome","time":"07:05"}}]},"checklist":[],"id":"android-once"#,
            #"","label":"","completedOccurrenceDates":[],"status":"ARCHIVED"}],"schemaVersion":1,"kind":"binario_p"#,
            #"lanned_journeys"}
"#
    ].joined()

    /// The same journeys as an Android phone writes them: its org.json keeps the order of `put`
    /// (`journeyToJson`) and escapes "/".
    private let androidPhone = [
            #"{"kind":"binario_planned_journeys","schemaVersion":1,"journeys":[{"schemaVersion":1,"id":"android-mu"#,
            #"lti","label":"Riunione a Milano","itinerary":{"segments":[{"id":"segment-1","origin":{"canonicalId":"#,
            #""S08409","displayName":"Roma Termini","railwayCode":"S08409"},"destination":{"canonicalId":"S06421","#,
            #""displayName":"Firenze Santa Maria Novella","railwayCode":"S06421"},"departure":{"date":"2026-12-03""#,
            #","time":"07:10","timeZone":"Europe\/Rome"}},{"id":"segment-2","origin":{"canonicalId":"S06421","disp"#,
            #"layName":"Firenze Santa Maria Novella","railwayCode":"S06421"},"destination":{"canonicalId":"S05043""#,
            #","displayName":"Bologna Centrale"},"departure":{"date":"2026-12-03","time":"08:55","timeZone":"Europ"#,
            #"e\/Rome"}},{"id":"segment-4","origin":{"canonicalId":"S05043","displayName":"Bologna Centrale"},"des"#,
            #"tination":{"canonicalId":"S01700","displayName":"Milano Centrale","railwayCode":"S01700"},"departure"#,
            #"":{"date":"2026-12-03","time":"10:05","timeZone":"Europe\/Rome"}}]},"reminderMinutes":30,"checklist""#,
            #":[{"id":"check-1","label":"Biglietti, coincidenza a Firenze","completed":true}],"status":"ACTIVE","c"#,
            #"reatedAt":"2026-11-12T09:30:15.250Z","completedOccurrenceDates":[]},{"schemaVersion":1,"id":"android"#,
            #"-weekly","label":"","itinerary":{"segments":[{"id":"segment-1","origin":{"canonicalId":"S01700","dis"#,
            #"playName":"Milano Centrale","railwayCode":"S01700"},"destination":{"canonicalId":"S05043","displayNa"#,
            #"me":"Bologna Centrale"},"departure":{"date":"2026-11-16","time":"18:05","timeZone":"Europe\/Rome"}},"#,
            #"{"id":"segment-2","origin":{"canonicalId":"S05043","displayName":"Bologna Centrale"},"destination":{"#,
            #""canonicalId":"S06421","displayName":"Firenze Santa Maria Novella","railwayCode":"S06421"},"departur"#,
            #"e":{"date":"2026-11-16","time":"19:40","timeZone":"Europe\/Rome"}}]},"reminderMinutes":10,"recurrenc"#,
            #"e":{"schemaVersion":1,"weekdays":["MONDAY","THURSDAY"],"startsOn":"2026-11-16","endsOn":"2026-12-31""#,
            #","occurrenceLimit":4},"checklist":[],"status":"ACTIVE","createdAt":"2026-11-12T09:31:00Z","completed"#,
            #"OccurrenceDates":["2026-11-16"]},{"schemaVersion":1,"id":"android-once","label":"","itinerary":{"seg"#,
            #"ments":[{"id":"segment-1","origin":{"canonicalId":"S08409","displayName":"Roma Termini","railwayCode"#,
            #"":"S08409"},"destination":{"canonicalId":"S01700","displayName":"Milano Centrale","railwayCode":"S01"#,
            #"700"},"departure":{"date":"2026-12-24","time":"07:05","timeZone":"Europe\/Rome"}}]},"recurrence":{"s"#,
            #"chemaVersion":1,"weekdays":["THURSDAY"],"startsOn":"2026-12-24","occurrenceLimit":2},"checklist":[],"#,
            #""status":"ARCHIVED","createdAt":"2026-11-12T09:32:00Z","archivedAt":"2026-11-13T10:00:00Z","complete"#,
            #"dOccurrenceDates":[]}]}"#
    ].joined()

    /// `PlannedJourneyIcs.export(journeys, generatedAt = 2026-11-12T10:00:05Z)` on Android.
    private let androidCalendar = [
            #"BEGIN:VCALENDAR"#,
            #"VERSION:2.0"#,
            #"PRODID:-//Binario//Planned Journeys 1.0//IT"#,
            #"CALSCALE:GREGORIAN"#,
            #"METHOD:PUBLISH"#,
            #"BEGIN:VEVENT"#,
            #"UID:d04657b1932f8c4b93d769936e76c634@binario.local"#,
            #"DTSTAMP:20261112T100005Z"#,
            #"DTSTART:20261116T170500Z"#,
            #"SUMMARY:Milano Centrale → Firenze Santa Maria Novella"#,
            #"DESCRIPTION:Viaggio: Milano Centrale → Bologna Centrale\, partenza 2026-1"#,
            #" 1-16 alle 18:05 (Europe/Rome)\nViaggio: Bologna Centrale → Firenze Santa"#,
            #"  Maria Novella\, partenza 2026-11-16 alle 19:40 (Europe/Rome)"#,
            #"X-BINARIO-JOURNEY-ID:android-weekly"#,
            #"X-BINARIO-OCCURRENCE-DATE:2026-11-16"#,
            #"BEGIN:VALARM"#,
            #"ACTION:DISPLAY"#,
            #"TRIGGER:-PT10M"#,
            #"DESCRIPTION:Promemoria viaggio Binario"#,
            #"END:VALARM"#,
            #"END:VEVENT"#,
            #"BEGIN:VEVENT"#,
            #"UID:5ddf7fe78700c042e8226af5e349a1e6@binario.local"#,
            #"DTSTAMP:20261112T100005Z"#,
            #"DTSTART:20261119T170500Z"#,
            #"SUMMARY:Milano Centrale → Firenze Santa Maria Novella"#,
            #"DESCRIPTION:Viaggio: Milano Centrale → Bologna Centrale\, partenza 2026-1"#,
            #" 1-19 alle 18:05 (Europe/Rome)\nViaggio: Bologna Centrale → Firenze Santa"#,
            #"  Maria Novella\, partenza 2026-11-19 alle 19:40 (Europe/Rome)"#,
            #"X-BINARIO-JOURNEY-ID:android-weekly"#,
            #"X-BINARIO-OCCURRENCE-DATE:2026-11-19"#,
            #"BEGIN:VALARM"#,
            #"ACTION:DISPLAY"#,
            #"TRIGGER:-PT10M"#,
            #"DESCRIPTION:Promemoria viaggio Binario"#,
            #"END:VALARM"#,
            #"END:VEVENT"#,
            #"BEGIN:VEVENT"#,
            #"UID:9234620f81dda82ee801e194224f7565@binario.local"#,
            #"DTSTAMP:20261112T100005Z"#,
            #"DTSTART:20261123T170500Z"#,
            #"SUMMARY:Milano Centrale → Firenze Santa Maria Novella"#,
            #"DESCRIPTION:Viaggio: Milano Centrale → Bologna Centrale\, partenza 2026-1"#,
            #" 1-23 alle 18:05 (Europe/Rome)\nViaggio: Bologna Centrale → Firenze Santa"#,
            #"  Maria Novella\, partenza 2026-11-23 alle 19:40 (Europe/Rome)"#,
            #"X-BINARIO-JOURNEY-ID:android-weekly"#,
            #"X-BINARIO-OCCURRENCE-DATE:2026-11-23"#,
            #"BEGIN:VALARM"#,
            #"ACTION:DISPLAY"#,
            #"TRIGGER:-PT10M"#,
            #"DESCRIPTION:Promemoria viaggio Binario"#,
            #"END:VALARM"#,
            #"END:VEVENT"#,
            #"BEGIN:VEVENT"#,
            #"UID:bb7194a251f2943e370f243c6cd144de@binario.local"#,
            #"DTSTAMP:20261112T100005Z"#,
            #"DTSTART:20261126T170500Z"#,
            #"SUMMARY:Milano Centrale → Firenze Santa Maria Novella"#,
            #"DESCRIPTION:Viaggio: Milano Centrale → Bologna Centrale\, partenza 2026-1"#,
            #" 1-26 alle 18:05 (Europe/Rome)\nViaggio: Bologna Centrale → Firenze Santa"#,
            #"  Maria Novella\, partenza 2026-11-26 alle 19:40 (Europe/Rome)"#,
            #"X-BINARIO-JOURNEY-ID:android-weekly"#,
            #"X-BINARIO-OCCURRENCE-DATE:2026-11-26"#,
            #"BEGIN:VALARM"#,
            #"ACTION:DISPLAY"#,
            #"TRIGGER:-PT10M"#,
            #"DESCRIPTION:Promemoria viaggio Binario"#,
            #"END:VALARM"#,
            #"END:VEVENT"#,
            #"BEGIN:VEVENT"#,
            #"UID:396ca3cc4e6999a391cfab2493abc8c0@binario.local"#,
            #"DTSTAMP:20261112T100005Z"#,
            #"DTSTART:20261203T061000Z"#,
            #"SUMMARY:Riunione a Milano"#,
            #"DESCRIPTION:Viaggio: Roma Termini → Firenze Santa Maria Novella\, partenz"#,
            #" a 2026-12-03 alle 07:10 (Europe/Rome)\nViaggio: Firenze Santa Maria Novell"#,
            #" a → Bologna Centrale\, partenza 2026-12-03 alle 08:55 (Europe/Rome)\nVia"#,
            #" ggio: Bologna Centrale → Milano Centrale\, partenza 2026-12-03 alle 10:0"#,
            #" 5 (Europe/Rome)"#,
            #"X-BINARIO-JOURNEY-ID:android-multi"#,
            #"X-BINARIO-OCCURRENCE-DATE:2026-12-03"#,
            #"BEGIN:VALARM"#,
            #"ACTION:DISPLAY"#,
            #"TRIGGER:-PT30M"#,
            #"DESCRIPTION:Promemoria viaggio Binario"#,
            #"END:VALARM"#,
            #"END:VEVENT"#,
            #"BEGIN:VEVENT"#,
            #"UID:e7bbc4c4c8fe1d53e1552e1185cacb96@binario.local"#,
            #"DTSTAMP:20261112T100005Z"#,
            #"DTSTART:20261224T060500Z"#,
            #"SUMMARY:Roma Termini → Milano Centrale"#,
            #"DESCRIPTION:Viaggio: Roma Termini → Milano Centrale\, partenza 2026-12-24"#,
            #"  alle 07:05 (Europe/Rome)"#,
            #"X-BINARIO-JOURNEY-ID:android-once"#,
            #"X-BINARIO-OCCURRENCE-DATE:2026-12-24"#,
            #"END:VEVENT"#,
            #"BEGIN:VEVENT"#,
            #"UID:ed139b92ad8483ebce6d1b9686a2aa19@binario.local"#,
            #"DTSTAMP:20261112T100005Z"#,
            #"DTSTART:20261231T060500Z"#,
            #"SUMMARY:Roma Termini → Milano Centrale"#,
            #"DESCRIPTION:Viaggio: Roma Termini → Milano Centrale\, partenza 2026-12-31"#,
            #"  alle 07:05 (Europe/Rome)"#,
            #"X-BINARIO-JOURNEY-ID:android-once"#,
            #"X-BINARIO-OCCURRENCE-DATE:2026-12-31"#,
            #"END:VEVENT"#,
            #"END:VCALENDAR"#
    ].joined(separator: "\r\n") + "\r\n"

    private let createdAt = Date(timeIntervalSince1970: 1_760_000_000)

    // MARK: Reading, keeping and writing back

    func testAnAndroidDocumentWithLegsAndRepetitionsIsReadWhole() throws {
        for document in [androidJVM, androidPhone] {
            let collection = try XCTUnwrap(PlannedJourneyTransfer.decode(Data(document.utf8)))
            XCTAssertEqual(collection.journeys.map(\.id), ["android-multi", "android-weekly", "android-once"])
        }
        let journeys = try androidJourneys()

        let multi = journeys[0]
        XCTAssertEqual(multi.segments.map(\.id), ["segment-1", "segment-2", "segment-4"])
        XCTAssertEqual(multi.origin.displayName, "Roma Termini")
        XCTAssertEqual(multi.destination.displayName, "Milano Centrale", "the last leg's arrival")
        XCTAssertNil(multi.segments[1].destination.railwayCode)
        XCTAssertEqual(multi.segments.map(\.departure.time.hhmm), ["07:10", "08:55", "10:05"])
        XCTAssertNil(multi.recurrence)
        XCTAssertEqual(multi.occurrences.count, 1)
        XCTAssertEqual(multi.reminderMinutes, 30)
        XCTAssertEqual(multi.checklist.first?.label, "Biglietti, coincidenza a Firenze")
        XCTAssertEqual(multi.createdAt, try utc("2026-11-12T09:30:15Z"))

        let weekly = journeys[1]
        let recurrence = try XCTUnwrap(weekly.recurrence)
        XCTAssertEqual(recurrence.weekdays, [.monday, .thursday])
        XCTAssertEqual(recurrence.endsOn?.iso, "2026-12-31")
        XCTAssertEqual(recurrence.occurrenceLimit, 4)
        XCTAssertEqual(weekly.occurrences.map(\.date.iso), ["2026-11-16", "2026-11-19", "2026-11-23", "2026-11-26"])
        XCTAssertEqual(
            weekly.occurrences.map(\.instant),
            try ["2026-11-16T17:05:00Z", "2026-11-19T17:05:00Z", "2026-11-23T17:05:00Z", "2026-11-26T17:05:00Z"].map(utc)
        )
        XCTAssertEqual(weekly.occurrences[2].segments.map(\.departure.date.iso), ["2026-11-23", "2026-11-23"])
        XCTAssertEqual(weekly.completedOccurrenceDates.map(\.iso), ["2026-11-16"])

        let once = journeys[2]
        XCTAssertEqual(once.status, .archived)
        XCTAssertNil(once.recurrence?.endsOn)
        XCTAssertEqual(once.occurrences.map(\.date.iso), ["2026-12-24", "2026-12-31"])

        // Android's `PlannedJourneyDeduplication.fingerprint` of each, as Android printed it.
        XCTAssertEqual(journeys.map(\.fingerprint), [
            "S08409~S06421~2026-12-03~07:10~Europe/Rome|S06421~S05043~2026-12-03~08:55~Europe/Rome"
                + "|S05043~S01700~2026-12-03~10:05~Europe/Rome#recurrence:once",
            "S01700~S05043~2026-11-16~18:05~Europe/Rome|S05043~S06421~2026-11-16~19:40~Europe/Rome"
                + "#recurrence:1~MONDAY,THURSDAY~2026-11-16~2026-12-31~4",
            "S08409~S01700~2026-12-24~07:05~Europe/Rome#recurrence:1~THURSDAY~2026-12-24~~2",
        ])
    }

    func testTheDocumentIsWrittenBackByteForByteAsAnAndroidPhoneWritesIt() throws {
        let collection = try XCTUnwrap(PlannedJourneyTransfer.decode(Data(androidPhone.utf8)))
        let written = try PlannedJourneyTransfer.encode(collection)
        XCTAssertEqual(String(decoding: written, as: UTF8.self), androidPhone)
        XCTAssertEqual(PlannedJourneyTransfer.decode(written), collection)
        // The JVM's document holds the same values in another order: the same bytes come out.
        let fromJVM = try XCTUnwrap(PlannedJourneyTransfer.decode(Data(androidJVM.utf8)))
        XCTAssertEqual(fromJVM, collection)
        XCTAssertEqual(try PlannedJourneyTransfer.encode(fromJVM), written)

        // The store keeps them: a change to one journey leaves the others as Android wrote them.
        let suite = "it.binario.ios.tests.interop.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(written, forKey: PlannedJourneyStore.storageKey)
        let store = PlannedJourneyStore(defaults: defaults)
        XCTAssertEqual(store.load(), PlannedJourneyAgendaState(journeys: collection.journeys))
        let changed = try store.setChecklistItem("check-1", completed: false, journeyID: "android-multi").get()
        XCTAssertEqual(changed.journeys[1], collection.journeys[1])
        XCTAssertEqual(changed.journeys[2], collection.journeys[2])
        let stored = try XCTUnwrap(defaults.data(forKey: PlannedJourneyStore.storageKey))
        XCTAssertEqual(
            String(decoding: stored, as: UTF8.self),
            androidPhone.replacingOccurrences(of: #""completed":true"#, with: #""completed":false"#)
        )
    }

    func testTimestampsKeepTheFractionAndroidWrote() {
        XCTAssertEqual(PlannedJourneyInstant.nanoseconds("2026-11-12T09:30:15.250Z"), 250_000_000)
        XCTAssertEqual(PlannedJourneyInstant.nanoseconds("2026-11-12T09:30:15Z"), 0)
        XCTAssertEqual(PlannedJourneyInstant.nanoseconds("2026-11-12T09:30:15.0000001Z"), 100)
        XCTAssertNil(PlannedJourneyInstant.nanoseconds("2026-11-12T09:30:15.Z"))
        let second = Date(timeIntervalSince1970: 1_794_475_815)
        // Java's Instant.toString(): groups of three digits, as many as needed.
        XCTAssertEqual(PlannedJourneyInstant.format(second, nanoseconds: 0), "2026-11-12T09:30:15Z")
        XCTAssertEqual(PlannedJourneyInstant.format(second, nanoseconds: 250_000_000), "2026-11-12T09:30:15.250Z")
        XCTAssertEqual(PlannedJourneyInstant.format(second, nanoseconds: 250_100_000), "2026-11-12T09:30:15.250100Z")
        XCTAssertEqual(PlannedJourneyInstant.format(second, nanoseconds: 100), "2026-11-12T09:30:15.000000100Z")
    }

    func testTheCalendarIsAndroidsEventForEvent() throws {
        let journeys = try androidJourneys()
        let calendar = try PlannedJourneyIcs.export(journeys, generatedAt: try utc("2026-11-12T10:00:05Z"))
        XCTAssertEqual(calendar, androidCalendar)
        let events = try PlannedJourneyIcs.parseOwnedCalendar(androidCalendar).get()
        XCTAssertEqual(events.count, 7, "one event per departure")
        let preview = PlannedJourneyIcs.preview(events, against: journeys)
        XCTAssertEqual(preview.matching.count, 7)
        XCTAssertTrue(preview.conflicting.isEmpty)
        XCTAssertTrue(preview.unlinked.isEmpty)
    }

    // MARK: The Agenda's lists

    func testARepeatingJourneyShowsItsNextDepartureAndGoesToTheArchiveWhenNoneIsLeft() throws {
        let journeys = try androidJourneys()
        let weekly = journeys[1]
        let monday = try utc("2026-11-17T12:00:00Z")
        // The 16th is done and gone: the next departure is the 19th.
        XCTAssertEqual(weekly.nextOccurrence(now: monday)?.date.iso, "2026-11-19")
        XCTAssertEqual(PlannedJourneyAgenda.upcoming(journeys, now: monday).map(\.id), ["android-weekly", "android-multi"])
        XCTAssertEqual(PlannedJourneyAgenda.archive(journeys, now: monday).map(\.id), ["android-once"])
        XCTAssertEqual(PlannedJourneyAgenda.minutesUntilDeparture(of: weekly, now: monday), 2 * 24 * 60 + 5 * 60 + 5)

        // «Completa» marks the next departure done; the journey stays active with the following one.
        let done = try weekly.completingOccurrence(on: XCTUnwrap(PlannedLocalDate(iso: "2026-11-19")))
        XCTAssertEqual(done.status, .active)
        XCTAssertEqual(done.completedOccurrenceDates.map(\.iso), ["2026-11-16", "2026-11-19"])
        XCTAssertEqual(done.nextOccurrence(now: monday)?.date.iso, "2026-11-23")
        XCTAssertThrowsError(try weekly.completingOccurrence(on: XCTUnwrap(PlannedLocalDate(iso: "2026-11-20"))))
        XCTAssertThrowsError(try journeys[0].completingOccurrence(on: XCTUnwrap(PlannedLocalDate(iso: "2026-12-03"))))

        // Every departure gone: archived, the latest first by its last departure.
        let later = try utc("2026-11-27T00:00:00Z")
        XCTAssertEqual(PlannedJourneyAgenda.upcoming(journeys, now: later).map(\.id), ["android-multi"])
        XCTAssertEqual(PlannedJourneyAgenda.archive(journeys, now: later).map(\.id), ["android-once", "android-weekly"])
        XCTAssertEqual(weekly.shownOccurrence(now: later).date.iso, "2026-11-26")
    }

    // MARK: Android's rules for legs and repetitions

    func testLegsMustConnectFollowEachOtherAndBeAtMostFour() throws {
        let roma = try station("S08409", "Roma Termini")
        let firenze = try station("S06421", "Firenze Santa Maria Novella")
        let bologna = try station("S05043", "Bologna Centrale")
        let milano = try station("S01700", "Milano Centrale")
        let first = try leg("segment-1", roma, firenze, "2026-12-03", "07:10")
        let second = try leg("segment-2", firenze, bologna, "2026-12-03", "08:55")
        XCTAssertNoThrow(try PlannedJourney(id: "a", segments: [first, second], createdAt: createdAt))
        let cases: [([PlannedJourneySegment], PlannedJourneyValidationError)] = [
            ([], .invalidItinerary),
            ([first, try leg("segment-2", bologna, milano, "2026-12-03", "08:55")], .disconnectedItinerary),
            ([first, try leg("segment-2", firenze, bologna, "2026-12-03", "07:10")], .nonChronologicalItinerary),
            ([first, try leg("segment-1", firenze, bologna, "2026-12-03", "08:55")], .invalidSegment),
            ([
                first, second, try leg("segment-3", bologna, milano, "2026-12-03", "10:05"),
                try leg("segment-4", milano, bologna, "2026-12-03", "12:00"),
                try leg("segment-5", bologna, firenze, "2026-12-03", "14:00"),
            ], .invalidItinerary),
        ]
        for (segments, expected) in cases {
            XCTAssertThrowsError(try PlannedJourney(id: "a", segments: segments, createdAt: createdAt)) { error in
                XCTAssertEqual(error as? PlannedJourneyValidationError, expected)
            }
        }
    }

    func testARepetitionFollowsAndroidsRules() throws {
        let roma = try station("S08409", "Roma Termini")
        let milano = try station("S01700", "Milano Centrale")
        let monday = try XCTUnwrap(PlannedLocalDate(iso: "2026-11-16"))
        // The first date's weekday, at most twelve, an end not before the start.
        XCTAssertThrowsError(try PlannedJourneyRecurrence(weekdays: [.tuesday], startsOn: monday, occurrenceLimit: 4))
        XCTAssertThrowsError(try PlannedJourneyRecurrence(weekdays: [.monday, .monday], startsOn: monday, occurrenceLimit: 4))
        XCTAssertThrowsError(try PlannedJourneyRecurrence(weekdays: [.monday], startsOn: monday, occurrenceLimit: 13))
        XCTAssertThrowsError(try PlannedJourneyRecurrence(weekdays: [.monday], startsOn: monday, occurrenceLimit: 0))
        XCTAssertThrowsError(try PlannedJourneyRecurrence(
            weekdays: [.monday], startsOn: monday, endsOn: XCTUnwrap(PlannedLocalDate(iso: "2026-11-15")), occurrenceLimit: 2
        ))
        // The end stops it before the count.
        let ending = try PlannedJourneyRecurrence(
            weekdays: [.monday, .friday], startsOn: monday, endsOn: XCTUnwrap(PlannedLocalDate(iso: "2026-11-23")), occurrenceLimit: 12
        )
        XCTAssertEqual(ending.dates.map(\.iso), ["2026-11-16", "2026-11-20", "2026-11-23"])

        let weekly = try PlannedJourneyRecurrence(weekdays: [.monday], startsOn: monday, occurrenceLimit: 2)
        let start = try leg("segment-1", roma, milano, "2026-11-16", "08:00")
        XCTAssertNoThrow(try PlannedJourney(id: "a", segments: [start], recurrence: weekly, createdAt: createdAt))
        // It starts on the first leg's date.
        XCTAssertThrowsError(try PlannedJourney(
            id: "a", segments: [try leg("segment-1", roma, milano, "2026-11-17", "08:00")], recurrence: weekly, createdAt: createdAt
        )) { error in
            XCTAssertEqual(error as? PlannedJourneyValidationError, .invalidRecurrence)
        }
        // A departure the spring clock change skips refuses the repetition (Sunday 28 March 2027, 02:30).
        let sunday = try XCTUnwrap(PlannedLocalDate(iso: "2027-03-21"))
        XCTAssertThrowsError(try PlannedJourney(
            id: "a", segments: [try leg("segment-1", roma, milano, "2027-03-21", "02:30")],
            recurrence: PlannedJourneyRecurrence(weekdays: [.sunday], startsOn: sunday, occurrenceLimit: 2), createdAt: createdAt
        )) { error in
            XCTAssertEqual(error as? PlannedJourneyValidationError, .invalidRecurrence)
        }
        // Dates marked done must be departures of the repetition, each once; none without one.
        let tuesday = try XCTUnwrap(PlannedLocalDate(iso: "2026-11-17"))
        for done in [[tuesday], [monday, monday]] {
            XCTAssertThrowsError(try PlannedJourney(
                id: "a", segments: [start], recurrence: weekly, createdAt: createdAt, completedOccurrenceDates: done
            )) { error in
                XCTAssertEqual(error as? PlannedJourneyValidationError, .invalidCompletedOccurrences)
            }
        }
        XCTAssertThrowsError(try PlannedJourney(id: "a", segments: [start], createdAt: createdAt, completedOccurrenceDates: [monday]))
    }

    // MARK: Helpers

    private func androidJourneys() throws -> [PlannedJourney] {
        try XCTUnwrap(PlannedJourneyTransfer.decode(Data(androidPhone.utf8))).journeys
    }

    private func station(_ id: String, _ name: String) throws -> PlannedJourneyStation {
        try PlannedJourneyStation(canonicalId: id, displayName: name, railwayCode: id)
    }

    private func leg(
        _ id: String,
        _ origin: PlannedJourneyStation,
        _ destination: PlannedJourneyStation,
        _ date: String,
        _ time: String
    ) throws -> PlannedJourneySegment {
        try PlannedJourneySegment(
            id: id, origin: origin, destination: destination,
            departure: PlannedJourneyDateTime(date: XCTUnwrap(PlannedLocalDate(iso: date)), time: XCTUnwrap(PlannedLocalTime(hhmm: time)))
        )
    }

    private func utc(_ text: String) throws -> Date {
        try XCTUnwrap(PlannedJourneyInstant.parse(text), text)
    }
}
