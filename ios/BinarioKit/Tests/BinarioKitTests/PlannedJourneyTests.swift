import Foundation
import XCTest
@testable import BinarioKit

/// The iPhone Agenda: the planned journey, its Android JSON contract, the lists and the store
/// (the companion's local-route contract and archive tests, EXPO_PORT_PLAN §3.5 and §3.6, on
/// Android's model and rules).
final class PlannedJourneyTests: XCTestCase {
    private let roma = ("S08409", "Roma Termini")
    private let milano = ("S01700", "Milano Centrale")
    private let firenze = ("S06421", "Firenze Santa Maria Novella")
    private let createdAt = Date(timeIntervalSince1970: 1_760_000_000)

    // MARK: The journey

    func testAJourneyNeedsTwoDifferentCatalogueStationsAndAValidDeparture() throws {
        XCTAssertThrowsError(try journey("same", "2026-10-02", "08:15", from: roma, to: roma)) { error in
            XCTAssertEqual(error as? PlannedJourneyValidationError, .sameStation)
        }
        // The canonical identifier decides, as Android's normalizedId: case and spaces do not.
        let departure = try PlannedJourneyDateTime(
            date: XCTUnwrap(PlannedLocalDate(iso: "2026-10-02")),
            time: XCTUnwrap(PlannedLocalTime(hhmm: "08:15"))
        )
        XCTAssertThrowsError(try PlannedJourneySegment(
            origin: PlannedJourneyStation(canonicalId: "S08409", displayName: "Roma Termini"),
            destination: PlannedJourneyStation(canonicalId: " s08409 ", displayName: "Roma Termini bis"),
            departure: departure
        )) { error in
            XCTAssertEqual(error as? PlannedJourneyValidationError, .sameStation)
        }
        for (id, name) in [("S", "Roma Termini"), ("S08409", "R"), ("S08409", "Roma\nTermini")] {
            XCTAssertThrowsError(try PlannedJourneyStation(canonicalId: id, displayName: name)) { error in
                XCTAssertEqual(error as? PlannedJourneyValidationError, .invalidStation)
            }
        }
        XCTAssertThrowsError(try PlannedJourneyStation(canonicalId: "S08409", displayName: "Roma Termini", railwayCode: "S0 8409"))
        XCTAssertNoThrow(try PlannedJourneyStation(canonicalId: "S08409", displayName: "Roma Termini", railwayCode: "S08409"))
        XCTAssertNoThrow(try PlannedJourneyStation(StationIdentity(id: "S08409", name: "Roma Termini", railwayCode: "S08409")))

        XCTAssertNil(PlannedLocalDate(iso: "2026-14-02"))
        XCTAssertNil(PlannedLocalTime(hhmm: "25:15"))
    }

    func testLabelIdentifierReminderAndChecklistFollowAndroidsLimits() throws {
        XCTAssertNoThrow(try journey("a", "2026-10-02", "08:15", label: String(repeating: "x", count: 96)))
        XCTAssertThrowsError(try journey("a", "2026-10-02", "08:15", label: String(repeating: "x", count: 97)))
        XCTAssertThrowsError(try journey("a", "2026-10-02", "08:15", label: "Riga\u{0007}"))
        XCTAssertThrowsError(try journey(" ", "2026-10-02", "08:15"))
        XCTAssertThrowsError(try journey(String(repeating: "x", count: 121), "2026-10-02", "08:15"))
        XCTAssertThrowsError(try journey("a", "2026-10-02", "08:15", reminderMinutes: 7)) { error in
            XCTAssertEqual(error as? PlannedJourneyValidationError, .invalidReminder)
        }
        XCTAssertNoThrow(try journey("a", "2026-10-02", "08:15", reminderMinutes: 45))

        let items = try (1...25).map { try PlannedJourneyChecklistItem(id: "check-\($0)", label: "Voce \($0)") }
        XCTAssertNoThrow(try journey("a", "2026-10-02", "08:15", checklist: Array(items.prefix(24))))
        XCTAssertThrowsError(try journey("a", "2026-10-02", "08:15", checklist: items))
        XCTAssertThrowsError(try journey("a", "2026-10-02", "08:15", checklist: [items[0], items[0]]))
        XCTAssertThrowsError(try PlannedJourneyChecklistItem(id: "check-1", label: "  "))
        XCTAssertThrowsError(try PlannedJourneyChecklistItem(id: "check-1", label: String(repeating: "x", count: 161)))
    }

    func testAChecklistNeverHoldsCredentialsPaymentIdentityOrContactData() throws {
        for label in ["PIN della carta", "Password del Wi-Fi", "Codice fiscale", "IBAN IT60X0542811101000000123456",
                      "Scrivi a mario.rossi@example.com", "Carta 4111 1111 1111 1111", "CVV 123"] {
            XCTAssertThrowsError(try PlannedJourneyChecklistItem(id: "check-1", label: label), label) { error in
                XCTAssertEqual(error as? PlannedJourneyValidationError, .sensitiveChecklistContent, label)
            }
        }
        // A word that only contains a listed one is not sensitive, nor is a short number.
        for label in ["Spinaci per il pranzo", "Prepara titolo di viaggio e documenti", "Binario 12", "Treno 9558"] {
            XCTAssertNoThrow(try PlannedJourneyChecklistItem(id: "check-1", label: label), label)
        }
    }

    func testCompletingMovesAnActiveJourneyToTheArchiveAndChecksTheTimestamps() throws {
        let planned = try journey("a", "2026-10-02", "08:15")
        let completed = try planned.completing(at: createdAt.addingTimeInterval(60))
        XCTAssertEqual(completed.status, .completed)
        XCTAssertEqual(completed.completedAt, createdAt.addingTimeInterval(60))
        XCTAssertThrowsError(try completed.completing(at: createdAt.addingTimeInterval(120)))
        XCTAssertThrowsError(try planned.completing(at: createdAt.addingTimeInterval(-60)))
        XCTAssertThrowsError(try PlannedJourney(id: "b", segment: planned.segment, status: .completed, createdAt: createdAt))
        XCTAssertThrowsError(try PlannedJourney(
            id: "b", segment: planned.segment, status: .active, createdAt: createdAt, completedAt: createdAt
        ))
        XCTAssertNoThrow(try PlannedJourney(
            id: "b", segment: planned.segment, status: .archived, createdAt: createdAt,
            completedAt: createdAt, archivedAt: createdAt.addingTimeInterval(1)
        ))
    }

    func testAChecklistLineIsTickedOnItsOwn() throws {
        let items = try [
            PlannedJourneyChecklistItem(id: "check-1", label: "Biglietto"),
            PlannedJourneyChecklistItem(id: "check-2", label: "Ombrello"),
        ]
        let planned = try journey("a", "2026-10-02", "08:15", checklist: items)
        let ticked = try planned.settingChecklistItem(id: "check-2", completed: true)
        XCTAssertEqual(ticked.checklist.map(\.completed), [false, true])
        XCTAssertEqual(ticked.checklist.map(\.label), ["Biglietto", "Ombrello"])
        XCTAssertThrowsError(try planned.settingChecklistItem(id: "check-9", completed: true))
    }

    // MARK: The lists

    func testUpcomingAndArchiveFollowTheItalianClock() throws {
        let now = try utc("2026-10-02T06:00:00Z") // 08:00 in Italy
        let yesterday = try journey("a", "2026-10-01", "08:00")
        let today = try journey("b", "2026-10-02", "08:15")
        let nextWeek = try journey("c", "2026-10-09", "08:15")
        let doneEarly = try journey("d", "2026-10-20", "08:15").completing(at: createdAt)
        let all = [nextWeek, doneEarly, yesterday, today]

        XCTAssertEqual(PlannedJourneyAgenda.upcoming(all, now: now).map(\.id), ["b", "c"])
        // Completed or departed, the latest first.
        XCTAssertEqual(PlannedJourneyAgenda.archive(all, now: now).map(\.id), ["d", "a"])
        XCTAssertEqual(PlannedJourneyAgenda.next(all, now: now)?.id, "b")
    }

    func testTheNextJourneyIsTheEarliestNotDepartedAndTheCountdownRoundsUp() throws {
        let early = try journey("early", "2026-10-02", "08:15")
        let later = try journey("later", "2026-10-02", "09:30")
        let now = try utc("2026-10-02T06:20:00Z") // 08:20 in Italy
        XCTAssertEqual(PlannedJourneyAgenda.next([early, later], now: now)?.id, "later")
        XCTAssertEqual(PlannedJourneyAgenda.minutesUntilDeparture(of: later, now: now), 70)
        XCTAssertEqual(PlannedJourneyAgenda.minutesUntilDeparture(of: later, now: now.addingTimeInterval(1)), 70)
        XCTAssertNil(PlannedJourneyAgenda.next([early], now: now))
        XCTAssertNil(PlannedJourneyAgenda.minutesUntilDeparture(of: early, now: now))
    }

    func testAPhoneAbroadCountsDownToTheItalianDeparture() throws {
        let original = NSTimeZone.default
        defer { NSTimeZone.default = original }
        let train = try journey("a", "2026-07-12", "09:00")
        for zone in ["Europe/London", "America/New_York"] {
            NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: zone))
            XCTAssertEqual(PlannedJourneyAgenda.minutesUntilDeparture(of: train, now: try utc("2026-07-12T06:30:00Z")), 30, zone)
            XCTAssertNil(PlannedJourneyAgenda.next([train], now: try utc("2026-07-12T07:30:00Z")), zone)
        }
    }

    // MARK: Android's JSON contract

    func testTheDocumentUsesAndroidsFieldNamesAndNothingElse() throws {
        let item = try PlannedJourneyChecklistItem(id: "check-1", label: "Biglietto", completed: true)
        let planned = try journey("j-1", "2026-11-12", "08:15", label: "Lavoro", checklist: [item])
        let data = try PlannedJourneyTransfer.encode(PlannedJourneyCollection(validating: [planned]))
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(Set(root.keys), ["kind", "schemaVersion", "journeys"])
        XCTAssertEqual(root["kind"] as? String, "binario_planned_journeys")
        XCTAssertEqual(root["schemaVersion"] as? Int, 1)
        let record = try XCTUnwrap((root["journeys"] as? [[String: Any]])?.first)
        XCTAssertEqual(Set(record.keys), [
            "schemaVersion", "id", "label", "itinerary", "checklist", "status", "createdAt", "completedOccurrenceDates",
        ])
        XCTAssertEqual(record["status"] as? String, "ACTIVE")
        XCTAssertEqual(record["createdAt"] as? String, "2025-10-09T08:53:20Z")
        XCTAssertEqual((record["completedOccurrenceDates"] as? [Any])?.count, 0)
        let segment = try XCTUnwrap(((record["itinerary"] as? [String: Any])?["segments"] as? [[String: Any]])?.first)
        XCTAssertEqual(Set(segment.keys), ["id", "origin", "destination", "departure"])
        XCTAssertEqual(segment["id"] as? String, "segment-1")
        let departure = try XCTUnwrap(segment["departure"] as? [String: Any])
        XCTAssertEqual(departure["date"] as? String, "2026-11-12")
        XCTAssertEqual(departure["time"] as? String, "08:15")
        XCTAssertEqual(departure["timeZone"] as? String, "Europe/Rome")
        let origin = try XCTUnwrap(segment["origin"] as? [String: Any])
        XCTAssertEqual(origin["canonicalId"] as? String, "S08409")
        XCTAssertEqual(origin["displayName"] as? String, "Roma Termini")
        let checklist = try XCTUnwrap((record["checklist"] as? [[String: Any]])?.first)
        XCTAssertEqual(checklist["completed"] as? Bool, true)

        XCTAssertEqual(PlannedJourneyTransfer.decode(data)?.journeys, [planned])
    }

    func testADocumentWrittenByAndroidIsReadBack() throws {
        // As Android's org.json writes it: insertion order, no spaces, a fraction in the instants.
        let android = """
        {"kind":"binario_planned_journeys","schemaVersion":1,"journeys":[{"schemaVersion":1,"id":"6f1c","label":"Lavoro",\
        "itinerary":{"segments":[{"id":"segment-1","origin":{"canonicalId":"S01700","displayName":"Milano Centrale",\
        "railwayCode":"S01700"},"destination":{"canonicalId":"S08409","displayName":"Roma Termini"},"departure":\
        {"date":"2026-12-24","time":"07:05","timeZone":"Europe\\/Rome"}}]},"reminderMinutes":15,"checklist":\
        [{"id":"check-1","label":"Biglietto","completed":false}],"status":"COMPLETED","createdAt":"2026-11-12T13:17:46.123Z",\
        "completedAt":"2026-12-24T08:00:00Z","completedOccurrenceDates":[]}]}
        """
        let collection = try XCTUnwrap(PlannedJourneyTransfer.decode(Data(android.utf8)))
        let read = try XCTUnwrap(collection.journeys.first)
        XCTAssertEqual(read.id, "6f1c")
        XCTAssertEqual(read.origin.railwayCode, "S01700")
        XCTAssertNil(read.destination.railwayCode)
        XCTAssertEqual(read.departure.date.iso, "2026-12-24")
        XCTAssertEqual(read.departure.time.hhmm, "07:05")
        XCTAssertEqual(read.departure.instant, try utc("2026-12-24T06:05:00Z"))
        XCTAssertEqual(read.reminderMinutes, 15)
        XCTAssertEqual(read.status, .completed)
        XCTAssertEqual(read.createdAt, try utc("2026-11-12T13:17:46Z"))
        // Written back as Android wrote it, milliseconds included.
        XCTAssertEqual(String(decoding: try PlannedJourneyTransfer.encode(collection), as: UTF8.self), android)
    }

    func testWhatTheIPhoneCannotReadOrAndroidWouldRefuseIsRefused() throws {
        let planned = try journey("j-1", "2026-11-12", "08:15")
        let data = try PlannedJourneyTransfer.encode(PlannedJourneyCollection(validating: [planned]))
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let record = try XCTUnwrap((root["journeys"] as? [[String: Any]])?.first)

        func refused(_ edit: (inout [String: Any]) -> Void, _ reason: String) throws {
            var changed = record
            edit(&changed)
            var document = root
            document["journeys"] = [changed]
            let encoded = try JSONSerialization.data(withJSONObject: document)
            XCTAssertNil(PlannedJourneyTransfer.decode(encoded), reason)
        }
        try refused({ $0["notes"] = "unknown field" }, "closed schema")
        try refused({ $0["schemaVersion"] = 2 }, "another version")
        try refused({ $0["status"] = "DONE" }, "unknown status")
        try refused({ $0["reminderMinutes"] = 0 }, "a lead Android does not know")
        try refused({ $0["completedOccurrenceDates"] = ["2026-11-12"] }, "occurrences without recurrence")
        try refused({ _ = $0.removeValue(forKey: "completedOccurrenceDates") }, "a required list")
        try refused({ $0["recurrence"] = ["weekdays": ["THURSDAY"]] }, "an incomplete recurrence")
        try refused({ $0["createdAt"] = "yesterday" }, "an instant")
        try refused({ record in
            var itinerary = record["itinerary"] as? [String: Any] ?? [:]
            let segments = itinerary["segments"] as? [Any] ?? []
            itinerary["segments"] = segments + segments
            record["itinerary"] = itinerary
        }, "two legs with one identifier")
        try refused({ record in
            var itinerary = record["itinerary"] as? [String: Any] ?? [:]
            var segment = (itinerary["segments"] as? [[String: Any]])?.first ?? [:]
            segment["departure"] = ["date": "2027-03-28", "time": "02:30", "timeZone": "Europe/Rome"]
            itinerary["segments"] = [segment]
            record["itinerary"] = itinerary
        }, "a departure in the spring gap")

        var other = root
        other["kind"] = "binario-tratte"
        XCTAssertNil(PlannedJourneyTransfer.decode(try JSONSerialization.data(withJSONObject: other)))
        XCTAssertNil(PlannedJourneyTransfer.decode(Data("not json".utf8)))
    }

    func testAListHoldsFortyJourneysEachOnce() throws {
        let forty = try (0..<40).map { try journey("j-\($0)", "2026-11-12", time(minutes: 360 + $0)) }
        XCTAssertNoThrow(try PlannedJourneyCollection(validating: forty))
        XCTAssertThrowsError(try PlannedJourneyCollection(validating: forty + [journey("j-40", "2026-11-13", "06:00")])) { error in
            XCTAssertEqual(error as? PlannedJourneyValidationError, .tooManyJourneys)
        }
        // The same stations, date and time are the same journey, whatever the label.
        let first = try journey("a", "2026-11-12", "08:15", label: "Andata")
        let again = try journey("b", "2026-11-12", "08:15", label: "Altro nome")
        XCTAssertEqual(first.fingerprint, "S08409~S01700~2026-11-12~08:15~Europe/Rome#recurrence:once")
        XCTAssertThrowsError(try PlannedJourneyCollection(validating: [first, again])) { error in
            XCTAssertEqual(error as? PlannedJourneyValidationError, .duplicateJourney)
        }
        XCTAssertThrowsError(try PlannedJourneyCollection(validating: [first, journey("a", "2026-11-12", "09:15")])) { error in
            XCTAssertEqual(error as? PlannedJourneyValidationError, .duplicateIdentifier)
        }
    }

    // MARK: The store

    func testTheStoreKeepsTheAgendaInItsOwnKeyAndReadsItBack() throws {
        let (store, defaults, suite) = try makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertEqual(PlannedJourneyStore.storageKey, "it.binario.ios.planned-journeys.v1")
        XCTAssertEqual(store.load(), .empty)

        let first = try journey("a", "2026-11-12", "08:15")
        let second = try journey("b", "2026-11-13", "08:15", from: milano, to: firenze)
        XCTAssertEqual(try store.add(first).get().journeys, [first])
        XCTAssertEqual(try store.add(second).get().journeys, [first, second])
        XCTAssertEqual(PlannedJourneyStore(defaults: defaults).load(), PlannedJourneyAgendaState(journeys: [first, second]))

        let edited = try journey("a", "2026-11-12", "09:45", label: "Spostato")
        XCTAssertEqual(try store.replace(edited).get().journeys, [edited, second])
        let completed = try store.complete(id: "b", at: createdAt.addingTimeInterval(10)).get()
        XCTAssertEqual(completed.journeys.last?.status, .completed)
        XCTAssertEqual(try store.remove(id: "a").get().journeys.map(\.id), ["b"])
        XCTAssertEqual(store.remove(id: "a"), .failure(.notFound))
        XCTAssertEqual(store.complete(id: "b", at: createdAt.addingTimeInterval(20)), .failure(.invalid(.invalidStatusTimestamps)))
    }

    func testTheFortyFirstJourneyIsRefusedWithoutTouchingTheStoredAgenda() throws {
        let (store, defaults, suite) = try makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        for index in 0..<40 {
            _ = try store.add(journey("j-\(index)", "2026-11-12", time(minutes: 360 + index))).get()
        }
        let stored = try XCTUnwrap(defaults.data(forKey: PlannedJourneyStore.storageKey))
        // Dated earlier or later than every other, the 41st is refused the same way.
        XCTAssertEqual(store.add(try journey("early", "2026-11-01", "06:00")), .failure(.full))
        XCTAssertEqual(store.add(try journey("late", "2026-12-01", "06:00")), .failure(.full))
        XCTAssertEqual(defaults.data(forKey: PlannedJourneyStore.storageKey), stored)
        XCTAssertEqual(store.load().journeys.count, 40)
        // A duplicate is a duplicate even when the Agenda is full.
        XCTAssertEqual(store.add(try journey("again", "2026-11-12", "06:00", label: "Altro")), .failure(.duplicate))
    }

    func testTheLastFreePlaceGoesToOneSaveOnly() throws {
        let (store, defaults, suite) = try makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        for index in 0..<39 {
            _ = try store.add(journey("j-\(index)", "2026-11-12", time(minutes: 360 + index))).get()
        }
        // Two screens built from the same 39 journeys save at once: the store reads the stored
        // Agenda again for each, so exactly one is accepted.
        let results = [
            store.add(try journey("first", "2026-11-20", "07:00")),
            store.add(try journey("second", "2026-11-21", "07:00")),
        ]
        XCTAssertEqual(results.filter { (try? $0.get()) != nil }.count, 1)
        XCTAssertEqual(results.last, .failure(.full))
        XCTAssertEqual(store.load().journeys.count, 40)
    }

    func testADuplicateIsRefusedOnAddAndOnEditWithoutWriting() throws {
        let (store, defaults, suite) = try makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let first = try journey("a", "2026-11-12", "08:15")
        let second = try journey("b", "2026-11-12", "09:15")
        _ = try store.add(first).get()
        _ = try store.add(second).get()
        let stored = try XCTUnwrap(defaults.data(forKey: PlannedJourneyStore.storageKey))

        XCTAssertEqual(store.add(try journey("c", "2026-11-12", "08:15", label: "Copia")), .failure(.duplicate))
        XCTAssertEqual(store.replace(try journey("b", "2026-11-12", "08:15")), .failure(.duplicate))
        XCTAssertEqual(store.replace(try journey("z", "2026-11-12", "10:15")), .failure(.notFound))
        XCTAssertEqual(defaults.data(forKey: PlannedJourneyStore.storageKey), stored)
        // Editing a journey without moving it is not a duplicate of itself.
        XCTAssertNoThrow(try store.replace(journey("a", "2026-11-12", "08:15", label: "Nuovo nome")).get())
    }

    func testAnUnreadableRecordMakesTheAgendaReadOnlyAndKeepsTheReadableOnes() throws {
        let (store, defaults, suite) = try makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let first = try journey("a", "2026-11-12", "08:15")
        let second = try journey("b", "2026-11-13", "08:15")
        let valid = try PlannedJourneyTransfer.encode(PlannedJourneyCollection(validating: [first, second]))
        var root = try XCTUnwrap(try JSONSerialization.jsonObject(with: valid) as? [String: Any])
        var records = try XCTUnwrap(root["journeys"] as? [Any])
        records.insert(["origin": 42], at: 1)
        root["journeys"] = records
        let corrupt = try JSONSerialization.data(withJSONObject: root)
        defaults.set(corrupt, forKey: PlannedJourneyStore.storageKey)

        let state = store.load()
        XCTAssertEqual(state.journeys, [first, second])
        XCTAssertTrue(state.isReadOnly)
        XCTAssertEqual(state.unreadableRecords, 1)
        // Nothing may be written over the record this version cannot read.
        XCTAssertEqual(store.add(try journey("c", "2026-11-14", "08:15")), .failure(.readOnly))
        XCTAssertEqual(store.remove(id: "a"), .failure(.readOnly))
        XCTAssertEqual(store.complete(id: "a", at: createdAt), .failure(.readOnly))
        XCTAssertEqual(store.replace(first), .failure(.readOnly))
        XCTAssertEqual(store.setChecklistItem("check-1", completed: true, journeyID: "a"), .failure(.readOnly))
        XCTAssertEqual(defaults.data(forKey: PlannedJourneyStore.storageKey), corrupt)
    }

    func testAValueThatIsNotTheAgendaIsKeptReadOnly() throws {
        let (store, defaults, suite) = try makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        for payload in [Data("not json".utf8), Data("{\"kind\":\"other\",\"schemaVersion\":1,\"journeys\":[]}".utf8),
                        Data("[1, 2]".utf8), Data(repeating: 0x20, count: PlannedJourneySchema.maximumEncodedBytes + 1)] {
            defaults.set(payload, forKey: PlannedJourneyStore.storageKey)
            XCTAssertEqual(store.load(), PlannedJourneyAgendaState(journeys: [], isReadOnly: true))
            XCTAssertEqual(store.add(try journey("a", "2026-11-12", "08:15")), .failure(.readOnly))
            XCTAssertEqual(defaults.data(forKey: PlannedJourneyStore.storageKey), payload)
        }
        defaults.set("a string", forKey: PlannedJourneyStore.storageKey)
        XCTAssertTrue(store.load().isReadOnly)
        defaults.set(Data(), forKey: PlannedJourneyStore.storageKey)
        XCTAssertEqual(store.load(), .empty)
    }

    // MARK: Helpers

    private func journey(
        _ id: String,
        _ date: String,
        _ time: String,
        from origin: (String, String)? = nil,
        to destination: (String, String)? = nil,
        label: String = "",
        reminderMinutes: Int? = nil,
        checklist: [PlannedJourneyChecklistItem] = []
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
        return try PlannedJourney(
            id: id, label: label, segment: segment, reminderMinutes: reminderMinutes, checklist: checklist, createdAt: createdAt
        )
    }

    private func time(minutes: Int) -> String {
        PlannedLocalTime(hour: minutes / 60, minute: minutes % 60)?.hhmm ?? "00:00"
    }

    private func utc(_ text: String) throws -> Date {
        try XCTUnwrap(PlannedJourneyInstant.parse(text), text)
    }

    private func makeStore() throws -> (PlannedJourneyStore, UserDefaults, String) {
        let suite = "it.binario.ios.tests.agenda.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        return (PlannedJourneyStore(defaults: defaults), defaults, suite)
    }
}
