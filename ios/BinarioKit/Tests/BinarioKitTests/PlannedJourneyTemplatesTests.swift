import Foundation
import XCTest
@testable import BinarioKit

/// The Agenda's templates, default lead and «Svuota l'Agenda» (EXPO_PORT_PLAN F7, F8, F27, §3.7) on
/// Android's rules.
final class PlannedJourneyTemplatesTests: XCTestCase {
    private let roma = StationIdentity(id: "S08409", name: "Roma Termini", railwayCode: "S08409")
    private let milano = StationIdentity(id: "S01700", name: "Milano Centrale", railwayCode: "S01700")
    private let createdAt = Date(timeIntervalSince1970: 1_760_000_000)

    // MARK: Templates (F7)

    func testATemplateIsOnlyWhatWouldMakeAValidJourney() throws {
        let time = try XCTUnwrap(PlannedLocalTime(hhmm: "08:15"))
        let start = try PlannedJourneyStation(roma)
        let end = try PlannedJourneyStation(milano)
        let template = try XCTUnwrap(PlannedJourneyTemplate(
            id: "t-1", name: "  Casa-lavoro  ", origin: start, destination: end, time: time, reminderMinutes: 15, checklist: ["Biglietto"]
        ))
        XCTAssertEqual(template.name, "Casa-lavoro")
        XCTAssertNil(PlannedJourneyTemplate(id: "t-1", name: " ", origin: start, destination: end, time: time))
        XCTAssertNil(PlannedJourneyTemplate(id: "t-1", name: String(repeating: "x", count: 49), origin: start, destination: end, time: time))
        XCTAssertNotNil(PlannedJourneyTemplate(id: "t-1", name: String(repeating: "x", count: 48), origin: start, destination: end, time: time))
        XCTAssertNil(PlannedJourneyTemplate(id: "t-1", name: "Giro", origin: start, destination: start, time: time))
        XCTAssertNil(PlannedJourneyTemplate(id: "t-1", name: "Giro", origin: start, destination: end, time: time, reminderMinutes: 7))
        XCTAssertNil(PlannedJourneyTemplate(id: "t-1", name: "Giro", origin: start, destination: end, time: time, checklist: ["PIN della carta"]))
    }

    func testTwelveTemplatesNewestFirstAndTheSameNameReplaced() throws {
        var templates: [PlannedJourneyTemplate] = []
        for index in 1...13 {
            templates = PlannedJourneyTemplates.adding(try template("t-\(index)", "Modello \(index)"), to: templates)
        }
        XCTAssertEqual(templates.count, 12)
        XCTAssertEqual(templates.first?.id, "t-13")
        XCTAssertEqual(templates.last?.id, "t-2", "the oldest goes")
        templates = PlannedJourneyTemplates.adding(try template("t-new", "MODELLO 5", time: "09:40"), to: templates)
        XCTAssertEqual(templates.count, 12)
        XCTAssertEqual(templates.first?.id, "t-new")
        XCTAssertFalse(templates.contains { $0.id == "t-5" }, "the same name, whatever the case, is replaced")
    }

    func testAndroidsTemplateDocumentIsReadAndAnUnreadableOneIsNoTemplate() throws {
        // As Android's PlannedJourneyTemplates.encode writes it on a phone.
        let android = #"{"kind":"binario_planned_journey_templates","schemaVersion":1,"templates":[{"id":"a1","name":"Casa-lavoro","#
            + #""origin":{"canonicalId":"S08409","displayName":"Roma Termini","railwayCode":"S08409"},"destination":{"canonicalId":"#
            + #""S01700","displayName":"Milano Centrale"},"time":"07:45","reminderMinutes":10,"checklist":["Biglietto"]},"#
            + #"{"id":"a2","name":"Rotto","origin":{"canonicalId":"S08409","displayName":"Roma Termini"},"destination":"#
            + #"{"canonicalId":"S08409","displayName":"Roma Termini"},"time":"07:45","checklist":[]},"#
            + #"{"id":"a1","name":"Copia","origin":{"canonicalId":"S01700","displayName":"Milano Centrale"},"destination":"#
            + #"{"canonicalId":"S08409","displayName":"Roma Termini"},"time":"18:10","checklist":[]}]}"#
        let templates = PlannedJourneyTemplates.decode(Data(android.utf8))
        XCTAssertEqual(templates.map(\.id), ["a1"], "an invalid record and a repeated identifier are left out")
        let first = try XCTUnwrap(templates.first)
        XCTAssertEqual(first.time.hhmm, "07:45")
        XCTAssertEqual(first.reminderMinutes, 10)
        XCTAssertNil(first.destination.railwayCode)
        XCTAssertEqual(PlannedJourneyTemplates.decode(PlannedJourneyTemplates.encode(templates)), templates)
        XCTAssertEqual(
            String(decoding: PlannedJourneyTemplates.encode(templates), as: UTF8.self),
            #"{"kind":"binario_planned_journey_templates","schemaVersion":1,"templates":[{"id":"a1","name":"Casa-lavoro","#
                + #""origin":{"canonicalId":"S08409","displayName":"Roma Termini","railwayCode":"S08409"},"destination":{"canonicalId":"#
                + #""S01700","displayName":"Milano Centrale"},"time":"07:45","reminderMinutes":10,"checklist":["Biglietto"]}]}"#
        )
        for unreadable in ["not json", #"{"kind":"other","schemaVersion":1,"templates":[]}"#, "[1]", ""] {
            XCTAssertEqual(PlannedJourneyTemplates.decode(Data(unreadable.utf8)), [], unreadable)
        }
        XCTAssertEqual(PlannedJourneyTemplates.decode(nil), [])
    }

    func testTheStoreKeepsTemplatesOnThisIPhoneAndAnUnreadableValueReadsAsNone() throws {
        let (defaults, suite) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = PlannedJourneyTemplateStore(defaults: defaults)
        XCTAssertEqual(PlannedJourneyTemplateStore.storageKey, "it.binario.ios.agenda-templates.v1")
        defaults.set(Data("not json".utf8), forKey: PlannedJourneyTemplateStore.storageKey)
        XCTAssertEqual(store.load(), [])
        XCTAssertEqual(store.save(try template("t-1", "Lavoro")).map(\.id), ["t-1"])
        XCTAssertEqual(store.save(try template("t-2", "Ritorno")).map(\.id), ["t-2", "t-1"])
        XCTAssertEqual(PlannedJourneyTemplateStore(defaults: defaults).load().map(\.id), ["t-2", "t-1"])
        XCTAssertEqual(store.delete(id: "t-2").map(\.id), ["t-1"])
        XCTAssertEqual(store.delete(id: "t-1"), [])
        XCTAssertNil(defaults.object(forKey: PlannedJourneyTemplateStore.storageKey), "none left removes the value")
    }

    func testAOneLegFormBecomesATemplateAndATemplateFillsTheForm() throws {
        var form = PlannedJourneyForm(
            origin: roma, destination: milano, date: PlannedLocalDate(iso: "2026-12-01"), time: PlannedLocalTime(hhmm: "07:45"),
            checklistText: "Biglietto\n\nOmbrello", reminderMinutes: 10
        )
        let template = try XCTUnwrap(form.template(id: "t-1", name: "Casa-lavoro"))
        XCTAssertEqual(template.time.hhmm, "07:45")
        XCTAssertEqual(template.checklist, ["Biglietto", "Ombrello"])
        XCTAssertEqual(template.reminderMinutes, 10)
        form.addLeg()
        XCTAssertNil(form.template(id: "t-2", name: "Due tratte"), "a template is one leg")

        // Applied at 08:00 in Italy: 07:45 has gone today, so tomorrow is proposed, to be checked.
        var editor = PlannedJourneyForm(repeatCount: 4, repeatWeekdays: [.friday])
        editor.laterLegs = [PlannedJourneyFormLeg(origin: milano)]
        editor.apply(template, now: try utc("2026-12-01T07:00:00Z"))
        XCTAssertEqual(editor.legs.count, 1)
        XCTAssertEqual(editor.origin, roma)
        XCTAssertEqual(editor.destination, milano)
        XCTAssertEqual(editor.date?.iso, "2026-12-02")
        XCTAssertEqual(editor.time?.hhmm, "07:45")
        XCTAssertEqual(editor.reminderMinutes, 10)
        XCTAssertEqual(editor.checklistText, "Biglietto\nOmbrello")
        XCTAssertEqual(editor.repeatCount, 1)
        editor.apply(template, now: try utc("2026-12-01T06:00:00Z"))
        XCTAssertEqual(editor.date?.iso, "2026-12-01", "still to come today")
    }

    // MARK: Default lead (F8)

    func testTheDefaultLeadIsNoneUntilChosenAndOnlyOneOfAndroidsLeads() throws {
        let (defaults, suite) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = PlannedJourneyAgendaPreferences(defaults: defaults)
        XCTAssertNil(preferences.defaultReminderMinutes)
        preferences.defaultReminderMinutes = 15
        XCTAssertEqual(PlannedJourneyAgendaPreferences(defaults: defaults).defaultReminderMinutes, 15)
        preferences.defaultReminderMinutes = 7
        XCTAssertNil(preferences.defaultReminderMinutes)
        defaults.set(25, forKey: PlannedJourneyAgendaPreferences.defaultReminderKey)
        XCTAssertNil(preferences.defaultReminderMinutes)

        // A new journey, a return and a replanned one propose it; an edit keeps the journey's own.
        let now = try utc("2026-12-01T07:00:00Z")
        XCTAssertEqual(PlannedJourneyForm.new(now: now, reminderMinutes: 30).reminderMinutes, 30)
        let form = PlannedJourneyForm(
            origin: roma, destination: milano, date: PlannedLocalDate(iso: "2026-12-01"), time: PlannedLocalTime(hhmm: "09:00")
        )
        let journey = try form.journey(id: "j-1", now: now, editing: nil).get()
        XCTAssertEqual(PlannedJourneyForm.returnTrip(from: journey, reminderMinutes: 30).reminderMinutes, 30)
        XCTAssertEqual(PlannedJourneyForm.replan(from: journey, reminderMinutes: 30).reminderMinutes, 30)
        XCTAssertNil(PlannedJourneyForm(editing: journey).reminderMinutes)
    }

    // MARK: «Svuota l'Agenda» (F27, §3.7)

    func testClearingRemovesEveryJourneyAlsoFromAReadOnlyAgenda() throws {
        let (defaults, suite) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = PlannedJourneyStore(defaults: defaults)
        defaults.set(Data("not json".utf8), forKey: PlannedJourneyStore.storageKey)
        XCTAssertTrue(store.load().isReadOnly)
        XCTAssertEqual(store.clearAll(), .empty)
        XCTAssertNil(defaults.object(forKey: PlannedJourneyStore.storageKey))
        XCTAssertEqual(store.load(), .empty, "writable again")
        let form = PlannedJourneyForm(
            origin: roma, destination: milano, date: PlannedLocalDate(iso: "2026-12-01"), time: PlannedLocalTime(hhmm: "09:00")
        )
        XCTAssertNoThrow(try store.add(form.journey(id: "j-1", now: createdAt, editing: nil).get()).get())
    }

    // MARK: Helpers

    private func template(_ id: String, _ name: String, time: String = "08:15") throws -> PlannedJourneyTemplate {
        try XCTUnwrap(PlannedJourneyTemplate(
            id: id, name: name, origin: PlannedJourneyStation(roma), destination: PlannedJourneyStation(milano),
            time: XCTUnwrap(PlannedLocalTime(hhmm: time))
        ))
    }

    private func makeDefaults() throws -> (UserDefaults, String) {
        let suite = "it.binario.ios.tests.templates.\(UUID().uuidString)"
        return (try XCTUnwrap(UserDefaults(suiteName: suite)), suite)
    }

    private func utc(_ text: String) throws -> Date {
        try XCTUnwrap(PlannedJourneyInstant.parse(text), text)
    }
}
