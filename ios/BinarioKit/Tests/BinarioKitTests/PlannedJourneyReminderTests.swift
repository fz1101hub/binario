import Foundation
import XCTest
@testable import BinarioKit

/// The Agenda's local reminders (the companion's planned-reminder tests, EXPO_PORT_PLAN §3.8, on
/// Android's planner): `UNUserNotificationCenter` is replaced by a fake that keeps the pending
/// requests as the system would.
@MainActor
final class PlannedJourneyReminderTests: XCTestCase {
    private let roma = ("S08409", "Roma Termini")
    private let milano = ("S01700", "Milano Centrale")
    private let firenze = ("S06421", "Firenze Santa Maria Novella")
    private let createdAt = Date(timeIntervalSince1970: 1_760_000_000)

    func testOneDeterministicRequestPerJourneyThatAnotherSchedulingReplaces() async throws {
        let center = FakeNotificationCenter()
        center.pending = [PlannedJourneyNotificationRequest(identifier: "other", fireDate: nil, title: "", body: "")]
        let scheduler = makeScheduler(center)
        let now = try utc("2026-07-01T10:00:00Z")
        let first = try journey("j-1", "2026-07-12", "09:00", reminderMinutes: 30)

        _ = await scheduler.reconcile([first], now: now)
        _ = await scheduler.reconcile([first], now: now)
        XCTAssertEqual(center.agendaRequests.map(\.identifier), [PlannedJourneyReminderPlanner.identifier(journeyID: "j-1")])
        XCTAssertEqual(center.addCount, 1, "an unchanged request is not added again")
        XCTAssertEqual(PlannedJourneyReminderPlanner.identifier(journeyID: "j-1"), PlannedJourneyReminderPlanner.identifier(journeyID: "j-1"))
        XCTAssertNotEqual(PlannedJourneyReminderPlanner.identifier(journeyID: "j-1"), PlannedJourneyReminderPlanner.identifier(journeyID: "j-2"))

        // The edited journey keeps its identifier: its request is replaced, not doubled.
        let moved = try journey("j-1", "2026-07-12", "10:00", reminderMinutes: 15)
        let status = await scheduler.reconcile([moved], now: now)
        XCTAssertEqual(center.agendaRequests.count, 1)
        XCTAssertEqual(center.agendaRequests.first?.fireDate, try utc("2026-07-12T07:45:00Z"))
        XCTAssertEqual(status.scheduledJourneyIDs, ["j-1"])
        XCTAssertEqual(center.pending.filter { $0.identifier == "other" }.count, 1, "another request is left alone")
    }

    func testTwoJourneysFireAtTheItalianDepartureMinusTheLeadWhateverThePhonesZone() async throws {
        let saved = NSTimeZone.default
        defer { NSTimeZone.default = saved }
        for zone in ["Europe/London", "America/New_York", "UTC"] {
            NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: zone))
            let center = FakeNotificationCenter()
            let scheduler = makeScheduler(center)
            let journeys = [
                try journey("j-2", "2026-07-12", "12:00", from: milano, to: firenze, reminderMinutes: 60),
                try journey("j-1", "2026-07-12", "09:00", reminderMinutes: 30),
            ]
            let status = await scheduler.reconcile(journeys, now: try utc("2026-07-12T06:00:00Z"))
            XCTAssertEqual(center.agendaRequests.count, 2, zone)
            XCTAssertEqual(status.scheduledJourneyIDs, ["j-1", "j-2"], zone)
            let plans = PlannedJourneyReminderPlanner.plans(journeys, now: try utc("2026-07-12T06:00:00Z"))
            XCTAssertEqual(plans.map(\.journeyID), ["j-1", "j-2"], "the first to fire first")
            XCTAssertEqual(plans.map(\.fireDate), [try utc("2026-07-12T06:30:00Z"), try utc("2026-07-12T09:00:00Z")], zone)
        }
    }

    func testASpringGapTimeHasNoJourneyAndSoNoReminder() throws {
        let gap = try XCTUnwrap(PlannedLocalTime(hhmm: "02:30"))
        XCTAssertThrowsError(try PlannedJourneyDateTime(date: XCTUnwrap(PlannedLocalDate(iso: "2027-03-28")), time: gap)) { error in
            XCTAssertEqual(error as? PlannedJourneyValidationError, .nonexistentLocalTime)
        }
        let form = PlannedJourneyForm(
            origin: StationIdentity(id: roma.0, name: roma.1),
            destination: StationIdentity(id: milano.0, name: milano.1),
            date: PlannedLocalDate(iso: "2027-03-28"),
            time: gap,
            reminderMinutes: 15
        )
        XCTAssertEqual(form.journey(id: "gap", now: createdAt, editing: nil), .failure(.invalidDeparture))
    }

    func testATimeToFireAlreadyPastIsTooLateAndTheJourneyStaysSaved() async throws {
        let (store, defaults, suite) = try makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let center = FakeNotificationCenter()
        let scheduler = makeScheduler(center)
        let soon = try journey("j-1", "2026-07-12", "09:00", reminderMinutes: 30)
        let state = try store.add(soon).get()

        // 06:45Z: the train leaves at 07:00Z, the reminder was due at 06:30Z.
        let result = await scheduler.scheduleAfterSaving(soon, journeys: state.journeys, now: try utc("2026-07-12T06:45:00Z"))
        XCTAssertEqual(result.outcome, .tooLate)
        XCTAssertEqual(store.load().journeys, [soon])
        XCTAssertTrue(center.agendaRequests.isEmpty)
        XCTAssertEqual(center.permissionRequests, 0, "nothing to schedule, nothing asked")
        XCTAssertEqual(result.status.state(of: soon, now: try utc("2026-07-12T06:45:00Z")), .tooLate(minutes: 30))
    }

    func testPermissionIsAskedOnlyForAChosenReminderAndARefusalKeepsTheJourney() async throws {
        let (store, defaults, suite) = try makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let center = FakeNotificationCenter()
        center.currentPermission = .notDetermined
        center.answer = false
        let scheduler = makeScheduler(center)
        let now = try utc("2026-07-01T10:00:00Z")
        let withReminder = try journey("j-1", "2026-07-12", "09:00", reminderMinutes: 30)

        // Launch, foreground and imports reconcile without asking.
        _ = await scheduler.reconcile([withReminder], now: now)
        XCTAssertEqual(center.permissionRequests, 0)
        XCTAssertTrue(center.agendaRequests.isEmpty)
        // «Nessuno» asks nothing either.
        let plain = try journey("j-2", "2026-07-13", "09:00", from: milano, to: firenze)
        let none = await scheduler.scheduleAfterSaving(plain, journeys: [plain], now: now)
        XCTAssertEqual(none.outcome, .none)
        XCTAssertEqual(center.permissionRequests, 0)

        let state = try store.add(withReminder).get()
        let refused = await scheduler.scheduleAfterSaving(withReminder, journeys: state.journeys, now: now)
        XCTAssertEqual(center.permissionRequests, 1)
        XCTAssertEqual(refused.outcome, .notAllowed)
        XCTAssertEqual(refused.status.state(of: withReminder, now: now), .notAllowed(minutes: 30))
        XCTAssertEqual(store.load().journeys, [withReminder], "the journey is saved all the same")
        XCTAssertTrue(center.agendaRequests.isEmpty)

        // Denied once: the system does not ask again, and neither does Binario.
        _ = await scheduler.scheduleAfterSaving(withReminder, journeys: state.journeys, now: now)
        XCTAssertEqual(center.permissionRequests, 1)

        // Allowed later in the iPhone's settings: the next reconciliation schedules it.
        center.currentPermission = .allowed
        let allowed = await scheduler.reconcile(state.journeys, now: now)
        XCTAssertEqual(allowed.scheduledJourneyIDs, ["j-1"])
    }

    func testAnAnsweredQuestionSchedulesTheReminder() async throws {
        let center = FakeNotificationCenter()
        center.currentPermission = .notDetermined
        center.answer = true
        let scheduler = makeScheduler(center)
        let now = try utc("2026-07-01T10:00:00Z")
        let chosen = try journey("j-1", "2026-07-12", "09:00", reminderMinutes: 10)
        let result = await scheduler.scheduleAfterSaving(chosen, journeys: [chosen], now: now)
        XCTAssertEqual(result.outcome, .scheduled)
        XCTAssertEqual(result.status.state(of: chosen, now: now), .scheduled(minutes: 10))
        XCTAssertEqual(center.agendaRequests.first?.fireDate, try utc("2026-07-12T06:50:00Z"))
    }

    func testTheWordsComeFromTheValidatedStationNamesNotTheFreeLabel() async throws {
        let center = FakeNotificationCenter()
        let scheduler = PlannedJourneyReminderScheduler(center: center) { plan in
            PlannedJourneyReminderContent(
                title: "Agenda",
                body: "\(plan.departureTime.hhmm) · \(plan.origin) → \(plan.destination)"
            )
        }
        let labelled = try journey("j-1", "2026-07-12", "09:00", label: "Colloquio da Rossi", reminderMinutes: 30)
        _ = await scheduler.reconcile([labelled], now: try utc("2026-07-01T10:00:00Z"))
        XCTAssertEqual(center.agendaRequests.first?.body, "09:00 · Roma Termini → Milano Centrale")
        XCTAssertFalse(center.pending.contains { $0.title.contains("Rossi") || $0.body.contains("Rossi") })
        let plan = try XCTUnwrap(PlannedJourneyReminderPlanner.plan(for: labelled, now: try utc("2026-07-01T10:00:00Z")))
        XCTAssertEqual(plan.departureDate.iso, "2026-07-12")
        XCTAssertEqual(plan.minutesBefore, 30)
    }

    func testNoneSchedulesNothing() async throws {
        let center = FakeNotificationCenter()
        let scheduler = makeScheduler(center)
        let plain = try journey("j-1", "2026-07-12", "09:00")
        let status = await scheduler.reconcile([plain], now: try utc("2026-07-01T10:00:00Z"))
        XCTAssertTrue(center.agendaRequests.isEmpty)
        XCTAssertEqual(status.state(of: plain, now: try utc("2026-07-01T10:00:00Z")), PlannedJourneyReminderState.none)
    }

    func testCompletingOrDeletingCancelsTheReminder() async throws {
        let (store, defaults, suite) = try makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let center = FakeNotificationCenter()
        let scheduler = makeScheduler(center)
        let now = try utc("2026-07-01T10:00:00Z")
        let first = try journey("j-1", "2026-07-12", "09:00", reminderMinutes: 30)
        let second = try journey("j-2", "2026-07-13", "09:00", from: milano, to: firenze, reminderMinutes: 30)
        _ = try store.add(first).get()
        let both = try store.add(second).get()
        _ = await scheduler.reconcile(both.journeys, now: now)
        XCTAssertEqual(center.agendaRequests.count, 2)

        // Deleting: the request and any notification shown go before the journey.
        scheduler.cancel(journeyID: "j-1")
        XCTAssertEqual(center.agendaRequests.map(\.identifier), [PlannedJourneyReminderPlanner.identifier(journeyID: "j-2")])
        XCTAssertEqual(center.removedDelivered, [PlannedJourneyReminderPlanner.identifier(journeyID: "j-1")])
        let afterDelete = try store.remove(id: "j-1").get()

        // Completing: the journey is no longer active, so the reconciliation removes its request.
        let afterComplete = try store.complete(id: "j-2", at: now).get()
        let status = await scheduler.reconcile(afterComplete.journeys, now: now)
        XCTAssertTrue(center.agendaRequests.isEmpty)
        XCTAssertTrue(status.scheduledJourneyIDs.isEmpty)
        XCTAssertEqual(afterDelete.journeys.map(\.id), ["j-2"])
    }

    func testTheScheduledListIsReadBackFromTheSystem() async throws {
        let center = FakeNotificationCenter()
        center.refused = [PlannedJourneyReminderPlanner.identifier(journeyID: "j-2")]
        let scheduler = makeScheduler(center)
        let now = try utc("2026-07-01T10:00:00Z")
        let kept = try journey("j-1", "2026-07-12", "09:00", reminderMinutes: 30)
        let refused = try journey("j-2", "2026-07-13", "09:00", from: milano, to: firenze, reminderMinutes: 30)
        let status = await scheduler.reconcile([kept, refused], now: now)
        XCTAssertEqual(status.scheduledJourneyIDs, ["j-1"])
        XCTAssertTrue(status.failed)
        XCTAssertEqual(status.state(of: refused, now: now), .notScheduled(minutes: 30))

        // A request the system dropped is no longer reported as scheduled.
        center.pending.removeAll()
        center.refused = []
        center.addsSilentlyDropped = true
        let dropped = await scheduler.reconcile([kept], now: now)
        XCTAssertTrue(dropped.scheduledJourneyIDs.isEmpty)
    }

    func testOnlyTheNearestRequestsAreScheduledAndTheWindowIsRefilled() async throws {
        let center = FakeNotificationCenter()
        let scheduler = PlannedJourneyReminderScheduler(center: center, maximumPending: 2) { _ in
            PlannedJourneyReminderContent(title: "t", body: "b")
        }
        let journeys = [
            try journey("j-3", "2026-07-14", "09:00", from: firenze, to: roma, reminderMinutes: 30),
            try journey("j-1", "2026-07-12", "09:00", reminderMinutes: 30),
            try journey("j-2", "2026-07-13", "09:00", from: milano, to: firenze, reminderMinutes: 30),
        ]
        let first = await scheduler.reconcile(journeys, now: try utc("2026-07-01T10:00:00Z"))
        XCTAssertEqual(first.scheduledJourneyIDs, ["j-1", "j-2"])
        XCTAssertEqual(center.agendaRequests.count, 2)

        // The first one fired: the next reconciliation (launch or foreground) adds the third.
        let later = await scheduler.reconcile(journeys, now: try utc("2026-07-12T08:00:00Z"))
        XCTAssertEqual(later.scheduledJourneyIDs, ["j-2", "j-3"])
        XCTAssertEqual(center.agendaRequests.count, 2)
        XCTAssertEqual(PlannedJourneyReminderScheduler.defaultMaximumPending, 64)
    }

    // MARK: Helpers

    private func makeScheduler(_ center: FakeNotificationCenter) -> PlannedJourneyReminderScheduler {
        PlannedJourneyReminderScheduler(center: center) { plan in
            PlannedJourneyReminderContent(title: "Agenda", body: "\(plan.origin) → \(plan.destination) \(plan.departureTime.hhmm)")
        }
    }

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

    private func utc(_ text: String) throws -> Date {
        try XCTUnwrap(PlannedJourneyInstant.parse(text), text)
    }

    private func makeStore() throws -> (PlannedJourneyStore, UserDefaults, String) {
        let suite = "it.binario.ios.tests.reminders.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        return (PlannedJourneyStore(defaults: defaults), defaults, suite)
    }
}

/// The system's notification centre as the tests see it.
@MainActor
private final class FakeNotificationCenter: PlannedJourneyNotificationCenter {
    var currentPermission: PlannedJourneyNotificationPermission = .allowed
    /// The user's answer to the system's question.
    var answer = true
    var permissionRequests = 0
    var pending: [PlannedJourneyNotificationRequest] = []
    var removedDelivered: [String] = []
    var addCount = 0
    var refused: Set<String> = []
    var addsSilentlyDropped = false

    var agendaRequests: [PlannedJourneyNotificationRequest] {
        pending.filter { PlannedJourneyReminderPlanner.isAgendaReminder($0.identifier) }
    }

    func permission() async -> PlannedJourneyNotificationPermission {
        currentPermission
    }

    func requestPermission() async -> Bool {
        permissionRequests += 1
        currentPermission = answer ? .allowed : .denied
        return answer
    }

    func pendingRequests() async -> [PlannedJourneyNotificationRequest] {
        pending
    }

    func add(_ request: PlannedJourneyNotificationRequest) async throws {
        if refused.contains(request.identifier) { throw FakeNotificationError.refused }
        addCount += 1
        guard !addsSilentlyDropped else { return }
        pending.removeAll { $0.identifier == request.identifier }
        pending.append(request)
    }

    func removePendingRequests(identifiers: [String]) {
        pending.removeAll { identifiers.contains($0.identifier) }
    }

    func removeDeliveredNotifications(identifiers: [String]) {
        removedDelivered += identifiers
    }
}

private enum FakeNotificationError: Error {
    case refused
}
