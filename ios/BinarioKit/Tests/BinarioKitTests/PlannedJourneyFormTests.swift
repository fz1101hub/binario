import Foundation
import XCTest
@testable import BinarioKit

/// The Agenda editor: what it saves and what it refuses (Android's editor rules on the iPhone).
final class PlannedJourneyFormTests: XCTestCase {
    private let roma = StationIdentity(id: "S08409", name: "Roma Termini", railwayCode: "S08409")
    private let milano = StationIdentity(id: "S01700", name: "Milano Centrale", railwayCode: "S01700")
    private let now = Date(timeIntervalSince1970: 1_762_931_000.4)

    func testANewJourneyStartsAtTheNextWholeHourInItaly() throws {
        let original = NSTimeZone.default
        defer { NSTimeZone.default = original }
        for zone in ["UTC", "America/New_York", "Asia/Tokyo"] {
            NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: zone))
            let morning = PlannedJourneyForm.new(now: try utc("2026-11-12T07:20:00Z")) // 08:20 in Italy
            XCTAssertEqual(morning.date?.iso, "2026-11-12", zone)
            XCTAssertEqual(morning.time?.hhmm, "09:00", zone)
            let lateEvening = PlannedJourneyForm.new(now: try utc("2026-11-12T22:30:00Z")) // 23:30 in Italy
            XCTAssertEqual(lateEvening.date?.iso, "2026-11-13", zone)
            XCTAssertEqual(lateEvening.time?.hhmm, "00:00", zone)
        }
    }

    func testANewJourneyIsSavedInItalianTimeWithTheEditorsValues() throws {
        let form = try filled(label: "  Lavoro  ", checklist: "  Biglietto \n\n Ombrello\r\nAcqua")
        let journey = try form.journey(id: "new-id", now: now, editing: nil).get()
        XCTAssertEqual(journey.id, "new-id")
        XCTAssertEqual(journey.label, "Lavoro")
        XCTAssertEqual(journey.origin.canonicalId, "S08409")
        XCTAssertEqual(journey.destination.displayName, "Milano Centrale")
        XCTAssertEqual(journey.departure.timeZone, "Europe/Rome")
        XCTAssertEqual(journey.departure.time.hhmm, "08:15")
        XCTAssertEqual(journey.segment.id, "segment-1")
        XCTAssertEqual(journey.checklist.map(\.label), ["Biglietto", "Ombrello", "Acqua"])
        XCTAssertEqual(journey.checklist.map(\.id), ["check-1", "check-2", "check-3"])
        XCTAssertEqual(journey.status, .active)
        XCTAssertNil(journey.reminderMinutes)
        XCTAssertEqual(journey.createdAt, Date(timeIntervalSince1970: 1_762_931_000))

        var many = form
        many.checklistText = (1...30).map { "Voce \($0)" }.joined(separator: "\n")
        XCTAssertEqual(try many.journey(id: "x", now: now, editing: nil).get().checklist.count, 24)
    }

    func testTheEditorRefusesWhatCannotBeSavedAndSaysWhy() throws {
        var form = try filled()
        form.origin = nil
        XCTAssertEqual(form.journey(id: "x", now: now, editing: nil), .failure(.missingStations))
        form = try filled()
        form.destination = roma
        XCTAssertEqual(form.journey(id: "x", now: now, editing: nil), .failure(.sameStation))
        form = try filled()
        form.time = nil
        XCTAssertEqual(form.journey(id: "x", now: now, editing: nil), .failure(.invalidDeparture))
        form = try filled()
        form.date = PlannedLocalDate(iso: "2027-03-28")
        form.time = PlannedLocalTime(hhmm: "02:30")
        XCTAssertEqual(form.journey(id: "x", now: now, editing: nil), .failure(.invalidDeparture))
        XCTAssertEqual(try filled(label: String(repeating: "x", count: 97)).journey(id: "x", now: now, editing: nil), .failure(.labelTooLong))
        XCTAssertEqual(try filled(checklist: "Biglietto\nPIN 1234").journey(id: "x", now: now, editing: nil), .failure(.sensitiveChecklist))
        XCTAssertEqual(
            try filled(checklist: String(repeating: "x", count: 161)).journey(id: "x", now: now, editing: nil),
            .failure(.checklistLineTooLong)
        )
    }

    func testEditingKeepsTheJourneysIdentityStateAndTheTicksOfUnchangedLines() throws {
        let original = try filled(checklist: "Biglietto\nOmbrello").journey(id: "j-1", now: now, editing: nil).get()
        let ticked = try original.settingChecklistItem(id: "check-2", completed: true)
        var form = PlannedJourneyForm(editing: ticked)
        XCTAssertEqual(form.checklistText, "Biglietto\nOmbrello")
        XCTAssertEqual(form.date?.iso, "2026-12-01")
        form.time = PlannedLocalTime(hhmm: "09:40")
        form.checklistText = "Ombrello\nAcqua"
        let edited = try form.journey(id: "ignored", now: now.addingTimeInterval(3_600), editing: ticked).get()
        XCTAssertEqual(edited.id, "j-1")
        XCTAssertEqual(edited.createdAt, ticked.createdAt)
        XCTAssertEqual(edited.departure.time.hhmm, "09:40")
        XCTAssertEqual(edited.checklist.map(\.label), ["Ombrello", "Acqua"])
        XCTAssertEqual(edited.checklist.map(\.completed), [true, false])
    }

    func testReturnAndReplanKeepOnlyTheRouteAndAskForANewDateAndTime() throws {
        let source = try PlannedJourney(
            id: "j-1",
            label: "Lavoro",
            segment: PlannedJourneySegment(
                origin: PlannedJourneyStation(canonicalId: "S08409", displayName: "Roma Termini", railwayCode: "S08409"),
                destination: PlannedJourneyStation(canonicalId: "S01700", displayName: "Milano Centrale", railwayCode: "S01700"),
                departure: PlannedJourneyDateTime(
                    date: XCTUnwrap(PlannedLocalDate(iso: "2026-12-01")),
                    time: XCTUnwrap(PlannedLocalTime(hhmm: "08:15"))
                )
            ),
            reminderMinutes: 15,
            checklist: [
                PlannedJourneyChecklistItem(id: "check-1", label: "Biglietto", completed: true),
                PlannedJourneyChecklistItem(id: "check-2", label: "Ombrello"),
            ],
            createdAt: now
        ).completing(at: now)

        let back = PlannedJourneyForm.returnTrip(from: source)
        XCTAssertEqual(back.origin, StationIdentity(id: "S01700", name: "Milano Centrale", railwayCode: "S01700"))
        XCTAssertEqual(back.destination, StationIdentity(id: "S08409", name: "Roma Termini", railwayCode: "S08409"))
        XCTAssertNil(back.date)
        XCTAssertNil(back.time)
        XCTAssertEqual(back.label, "")
        XCTAssertEqual(back.checklistText, "Biglietto\nOmbrello")
        // Until a date and time are chosen there is nothing to save.
        XCTAssertEqual(back.journey(id: "j-2", now: now, editing: nil), .failure(.invalidDeparture))

        var chosen = back
        chosen.date = PlannedLocalDate(iso: "2026-12-01")
        chosen.time = PlannedLocalTime(hhmm: "18:40")
        let saved = try chosen.journey(id: "j-2", now: now, editing: nil).get()
        XCTAssertEqual(saved.id, "j-2")
        XCTAssertEqual(saved.status, .active)
        XCTAssertNil(saved.reminderMinutes)
        XCTAssertEqual(saved.checklist.map(\.completed), [false, false])
        XCTAssertEqual(saved.origin.canonicalId, "S01700")

        let again = PlannedJourneyForm.replan(from: source)
        XCTAssertEqual(again.origin?.id, "S08409")
        XCTAssertEqual(again.destination?.id, "S01700")
        XCTAssertNil(again.date)
        XCTAssertNil(again.time)
        XCTAssertEqual(again.label, "")
    }

    func testADepartureAlreadyPassedIsSavedOnlyAfterAWarning() throws {
        let now = try utc("2026-12-01T08:00:00Z") // 09:00 in Italy
        var form = try filled() // 2026-12-01 08:15 in Italy
        XCTAssertTrue(form.needsPastDepartureWarning(now: now, editing: nil))
        form.time = PlannedLocalTime(hhmm: "09:30")
        XCTAssertFalse(form.needsPastDepartureWarning(now: now, editing: nil))
        form.time = nil
        XCTAssertFalse(form.needsPastDepartureWarning(now: now, editing: nil))

        // Editing a journey that has already left, without moving it, does not warn again.
        let departed = try filled().journey(id: "j-1", now: self.now, editing: nil).get()
        var edit = PlannedJourneyForm(editing: departed)
        edit.label = "Nuovo nome"
        XCTAssertFalse(edit.needsPastDepartureWarning(now: now, editing: departed))
        edit.time = PlannedLocalTime(hhmm: "08:45")
        XCTAssertTrue(edit.needsPastDepartureWarning(now: now, editing: departed))
    }

    func testTheReminderIsTheOneChosenInTheEditorAmongAndroidsLeads() throws {
        XCTAssertEqual(PlannedJourneyForm.reminderChoices, [nil, 5, 10, 15, 20, 30, 45, 60])
        var form = try filled()
        form.reminderMinutes = 30
        let saved = try form.journey(id: "j-1", now: now, editing: nil).get()
        XCTAssertEqual(saved.reminderMinutes, 30)

        var editor = PlannedJourneyForm(editing: saved)
        XCTAssertEqual(editor.reminderMinutes, 30)
        editor.reminderMinutes = nil
        XCTAssertNil(try editor.journey(id: "ignored", now: now, editing: saved).get().reminderMinutes, "«Nessuno» removes it")
        editor.reminderMinutes = 7
        XCTAssertEqual(editor.journey(id: "ignored", now: now, editing: saved), .failure(.invalid))
        XCTAssertNil(PlannedJourneyForm.returnTrip(from: saved).reminderMinutes, "a return trip inherits no reminder")
        XCTAssertNil(PlannedJourneyForm.new(now: now).reminderMinutes)
    }

    // MARK: Several legs (Android's multi-leg editor, EXPO_PORT_PLAN F19)

    func testAddedLegsStartWhereTheLastArrivesAndFollowItsDestination() throws {
        let firenze = StationIdentity(id: "S06421", name: "Firenze Santa Maria Novella", railwayCode: "S06421")
        let bologna = StationIdentity(id: "S05043", name: "Bologna Centrale", railwayCode: "S05043")
        var form = try filled()
        form.destination = firenze
        form.addLeg()
        XCTAssertEqual(form.legs.count, 2)
        XCTAssertEqual(form.laterLegs[0].origin, firenze, "the new leg leaves from the last arrival")
        XCTAssertEqual(form.laterLegs[0].date?.iso, "2026-12-01", "on its date")
        XCTAssertNil(form.laterLegs[0].time, "its time is chosen")

        // The prefilled origin follows a changed destination; one the user changed does not.
        var first = form.legs[0]
        first.destination = bologna
        form.setLeg(first, at: 0)
        XCTAssertEqual(form.destination, bologna)
        XCTAssertEqual(form.laterLegs[0].origin, bologna)
        form.laterLegs[0].origin = roma
        first.destination = firenze
        form.setLeg(first, at: 0)
        XCTAssertEqual(form.laterLegs[0].origin, roma)

        // At most four legs; the only leg stays.
        form.addLeg()
        form.addLeg()
        XCTAssertEqual(form.legs.count, 4)
        XCTAssertFalse(form.canAddLeg)
        form.addLeg()
        XCTAssertEqual(form.legs.count, 4)
        form.removeLeg(at: 1)
        XCTAssertEqual(form.legs.count, 3)
        var single = try filled()
        single.removeLeg(at: 0)
        XCTAssertEqual(single.legs.count, 1)
    }

    func testLegsAreSavedInOrderOrRefusedWithAndroidsMessages() throws {
        let firenze = StationIdentity(id: "S06421", name: "Firenze Santa Maria Novella", railwayCode: "S06421")
        var form = try filled() // Roma → Milano 08:15
        form.destination = firenze
        form.laterLegs = [PlannedJourneyFormLeg(
            origin: firenze, destination: milano, date: PlannedLocalDate(iso: "2026-12-01"), time: PlannedLocalTime(hhmm: "10:05")
        )]
        let saved = try form.journey(id: "j-1", now: now, editing: nil).get()
        XCTAssertEqual(saved.segments.map(\.id), ["segment-1", "segment-2"])
        XCTAssertEqual(saved.origin, try PlannedJourneyStation(roma))
        XCTAssertEqual(saved.destination, try PlannedJourneyStation(milano))

        var disconnected = form
        disconnected.laterLegs[0].origin = roma
        disconnected.laterLegs[0].destination = milano
        XCTAssertEqual(disconnected.journey(id: "j-1", now: now, editing: nil), .failure(.disconnectedLegs))
        var early = form
        early.laterLegs[0].time = PlannedLocalTime(hhmm: "08:00")
        XCTAssertEqual(early.journey(id: "j-1", now: now, editing: nil), .failure(.nonChronologicalLegs))
        var incomplete = form
        incomplete.laterLegs[0].time = nil
        XCTAssertEqual(incomplete.journey(id: "j-1", now: now, editing: nil), .failure(.invalidDeparture))
        incomplete = form
        incomplete.laterLegs[0].destination = nil
        XCTAssertEqual(incomplete.journey(id: "j-1", now: now, editing: nil), .failure(.missingStations))
    }

    func testEditingKeepsEveryLegAndItsIdentifierAndANewLegTakesAFreeOne() throws {
        let firenze = StationIdentity(id: "S06421", name: "Firenze Santa Maria Novella", railwayCode: "S06421")
        let bologna = StationIdentity(id: "S05043", name: "Bologna Centrale", railwayCode: "S05043")
        let stored = try PlannedJourney(
            id: "android-multi",
            segments: [
                leg("segment-1", roma, firenze, "07:10"),
                leg("segment-4", firenze, bologna, "08:55"),
            ],
            createdAt: now
        )
        var form = PlannedJourneyForm(editing: stored)
        XCTAssertEqual(form.legs.map(\.segmentID), ["segment-1", "segment-4"])
        XCTAssertEqual(form.laterLegs[0].time?.hhmm, "08:55")
        form.addLeg()
        form.laterLegs[1].destination = milano
        form.laterLegs[1].time = PlannedLocalTime(hhmm: "10:05")
        let edited = try form.journey(id: "ignored", now: now, editing: stored).get()
        XCTAssertEqual(edited.id, "android-multi")
        XCTAssertEqual(edited.segments.map(\.id), ["segment-1", "segment-4", "segment-2"])
        XCTAssertEqual(edited.destination.canonicalId, "S01700")
    }

    func testReturnReversesTheLegsAndReplanKeepsThemBothWithNewDatesAndTimes() throws {
        let firenze = StationIdentity(id: "S06421", name: "Firenze Santa Maria Novella", railwayCode: "S06421")
        let source = try PlannedJourney(
            id: "j-1",
            segments: [leg("segment-1", roma, firenze, "07:10"), leg("segment-2", firenze, milano, "09:00")],
            reminderMinutes: 15,
            createdAt: now
        )
        let back = PlannedJourneyForm.returnTrip(from: source)
        XCTAssertEqual(back.legs.map { $0.origin?.id }, ["S01700", "S06421"])
        XCTAssertEqual(back.legs.map { $0.destination?.id }, ["S06421", "S08409"])
        XCTAssertTrue(back.legs.allSatisfy { $0.date == nil && $0.time == nil && $0.segmentID == nil })
        XCTAssertNil(back.reminderMinutes)
        let again = PlannedJourneyForm.replan(from: source)
        XCTAssertEqual(again.legs.map { $0.origin?.id }, ["S08409", "S06421"])
        XCTAssertEqual(again.legs.map { $0.destination?.id }, ["S06421", "S01700"])
        XCTAssertTrue(again.legs.allSatisfy { $0.date == nil && $0.time == nil })
        XCTAssertEqual(again.journey(id: "j-2", now: now, editing: nil), .failure(.invalidDeparture))
    }

    func testAnErrorIsSaidAtOnceAndInformationWaitsItsTurn() {
        XCTAssertFalse(PlannedJourneyFeedbackSeverity.error.queuesBehindCurrentSpeech)
        XCTAssertTrue(PlannedJourneyFeedbackSeverity.information.queuesBehindCurrentSpeech)
    }

    // MARK: Helpers

    private func filled(label: String = "", checklist: String = "") throws -> PlannedJourneyForm {
        PlannedJourneyForm(
            label: label,
            origin: roma,
            destination: milano,
            date: try XCTUnwrap(PlannedLocalDate(iso: "2026-12-01")),
            time: try XCTUnwrap(PlannedLocalTime(hhmm: "08:15")),
            checklistText: checklist
        )
    }

    private func utc(_ text: String) throws -> Date {
        try XCTUnwrap(PlannedJourneyInstant.parse(text), text)
    }

    /// A leg on 1 December 2026, Italian time.
    private func leg(_ id: String, _ origin: StationIdentity, _ destination: StationIdentity, _ time: String) throws -> PlannedJourneySegment {
        try PlannedJourneySegment(
            id: id,
            origin: PlannedJourneyStation(origin),
            destination: PlannedJourneyStation(destination),
            departure: PlannedJourneyDateTime(date: XCTUnwrap(PlannedLocalDate(iso: "2026-12-01")), time: XCTUnwrap(PlannedLocalTime(hhmm: time)))
        )
    }
}
