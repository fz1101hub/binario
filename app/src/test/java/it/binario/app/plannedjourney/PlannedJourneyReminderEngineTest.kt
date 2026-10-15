package it.binario.app.plannedjourney

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PlannedJourneyReminderEngineTest {
    private val now = Instant.parse("2026-10-15T05:00:00Z")
    private val store = MemoryState()
    private val device = Device()
    private val engine = PlannedJourneyReminderEngine(store, device)
    private val empty = PlannedJourneyPersistenceSnapshot(journeys = emptyList())

    private fun journey(id: String = "one", date: String = "2026-10-15", time: String = "08:00", destination: String = "IT:MILANO-CENTRALE") = PlannedJourney(
        id = id, label = "Riunione",
        itinerary = PlannedJourneyItinerary(listOf(PlannedJourneySegment("segment",
            PlannedJourneyStation("IT:ROMA-TERMINI", "Roma Termini"),
            PlannedJourneyStation(destination, "Milano Centrale"),
            PlannedJourneyDateTime(LocalDate.parse(date), LocalTime.parse(time), ZoneId.of("Europe/Rome"))))),
        reminder = PlannedJourneyReminder(PlannedJourneyReminderMinutes.TEN),
        createdAt = Instant.parse("2026-01-01T00:00:00Z")
    )
    private fun snapshot(vararg journeys: PlannedJourney) = PlannedJourneyPersistenceSnapshot(journeys = journeys.toList())
    private fun enable() { store.state = store.state.copy(enabled = true) }
    private fun fire(plan: PlannedJourneyReminderPlan, snapshot: PlannedJourneyPersistenceSnapshot) {
        device.pending.remove(plan.key)
        engine.deliver(plan.key, snapshot, true, plan.trigger.plusSeconds(1))
    }

    @Test fun `stored reminder preference and import never imply device consent`() {
        val imported = (PlannedJourneyTransfer.decode(PlannedJourneyTransfer.encode(snapshot(journey()))) as PlannedJourneyTransferDecodeResult.Valid).snapshot
        assertEquals(false, engine.refresh(imported, true, now).enabled)
        assertTrue(device.pending.isEmpty())
        assertTrue(store.state.registered.isEmpty())
    }

    @Test fun `plans first departure using the journey zone and lead time`() {
        enable()
        assertFalse(engine.refresh(snapshot(journey()), true, now).failed)
        val plan = device.pending.values.single()
        assertEquals(Instant.parse("2026-10-15T05:50:00Z"), plan.trigger)
        assertEquals(Instant.parse("2026-10-15T06:00:00Z"), plan.departure)
        assertEquals("Roma Termini", plan.origin)
        assertTrue(plan.key.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun `journal is durable before scheduling an alarm or displaying a notification`() {
        enable()
        device.onSchedule = { assertTrue(it.key in store.state.registered) }
        device.onNotify = { assertTrue(it.key in store.state.delivered) }
        val snapshot = snapshot(journey())
        engine.refresh(snapshot, true, now)
        fire(device.pending.values.single(), snapshot)
        assertEquals(1, device.posts)
    }

    @Test fun `each occurrence is delivered at most once including after process restart`() {
        enable()
        val snapshot = snapshot(journey())
        engine.refresh(snapshot, true, now)
        val plan = device.pending.values.single()
        fire(plan, snapshot)
        PlannedJourneyReminderEngine(store, device).deliver(plan.key, snapshot, true, plan.trigger.plusSeconds(2))
        assertEquals(1, device.posts)
        assertTrue(device.pending.isEmpty())
    }

    @Test fun `disabled or denied notifications cancel alarms and displayed notifications`() {
        for (permissionDenied in listOf(false, true)) {
            enable()
            val snapshot = snapshot(journey(id = "permission-$permissionDenied"))
            engine.refresh(snapshot, true, now)
            val plan = device.pending.values.single()
            fire(plan, snapshot)
            assertTrue(plan.key in device.visible)
            if (!permissionDenied) store.state = store.state.copy(enabled = false)
            engine.refresh(snapshot, !permissionDenied, plan.trigger.plusSeconds(2))
            assertTrue(device.pending.isEmpty())
            assertTrue(device.visible.isEmpty())
            engine.deliver(plan.key, snapshot, !permissionDenied, plan.trigger.plusSeconds(3))
        }
        assertEquals(2, device.posts)
    }

    @Test fun `permissions restored before trigger restore alarms without changing consent`() {
        enable()
        val snapshot = snapshot(journey())
        engine.refresh(snapshot, false, now)
        assertTrue(device.pending.isEmpty())
        assertTrue(store.state.enabled)
        engine.refresh(snapshot, true, now)
        assertEquals(1, device.pending.size)
    }

    @Test fun `deleting archiving completing or clearing reminder cancels previous alarm`() {
        val original = journey()
        val changes = listOf(empty, snapshot(original.copy(status = PlannedJourneyStatus.ARCHIVED, archivedAt = now)),
            snapshot(original.copy(status = PlannedJourneyStatus.COMPLETED, completedAt = now)),
            snapshot(original.copy(reminder = null)))
        enable()
        changes.forEach { changed ->
            engine.refresh(snapshot(original), true, now)
            assertEquals(1, device.pending.size)
            val oldKey = device.pending.keys.single()
            engine.refresh(changed, true, now)
            assertTrue(device.pending.isEmpty())
            engine.deliver(oldKey, changed, true, Instant.parse("2026-10-15T05:51:00Z"))
        }
        assertEquals(0, device.posts)
    }

    @Test fun `edits cancel old identity and stale broadcast cannot deliver previous content`() {
        enable()
        val old = journey()
        engine.refresh(snapshot(old), true, now)
        val previous = device.pending.values.single()
        val updated = old.copy(label = "Nuovo nome", reminder = PlannedJourneyReminder(PlannedJourneyReminderMinutes.THIRTY))
        engine.refresh(snapshot(updated), true, now)
        val replacement = device.pending.values.single()
        assertNotEquals(previous.key, replacement.key)
        engine.deliver(previous.key, snapshot(updated), true, previous.trigger.plusSeconds(1))
        assertEquals(0, device.posts)
        // Delivery itself also re-reads the authoritative snapshot, even before reconciliation.
        engine.refresh(snapshot(old), true, now)
        engine.deliver(previous.key, snapshot(updated), true, previous.trigger.plusSeconds(1))
        assertEquals(0, device.posts)
    }

    @Test fun `checklist updates do not produce another reminder identity`() {
        val original = journey()
        val edited = original.copy(checklist = listOf(PlannedJourneyChecklistItem("ticket", "Biglietto", true)))
        assertEquals(PlannedJourneyReminderPlanner.plans(snapshot(original)).single().key,
            PlannedJourneyReminderPlanner.plans(snapshot(edited)).single().key)
    }

    @Test fun `already displayed reminder is withdrawn after journey deletion`() {
        enable()
        val snapshot = snapshot(journey())
        engine.refresh(snapshot, true, now)
        val plan = device.pending.values.single()
        fire(plan, snapshot)
        engine.refresh(empty, true, plan.trigger.plusSeconds(2))
        assertTrue(device.visible.isEmpty())
        assertTrue(store.state.delivered.isEmpty())
    }

    @Test fun `boot reconciliation skips missed reminders and never catches up after departure`() {
        enable()
        val snapshot = snapshot(journey())
        engine.refresh(snapshot, true, now)
        val plan = device.pending.values.single()
        device.pending.clear() // Reboot clears AlarmManager, but retains the durable journal.
        engine.refresh(snapshot, true, plan.trigger.plusSeconds(1))
        assertTrue(device.pending.isEmpty())
        engine.deliver(plan.key, snapshot, true, plan.departure.plusSeconds(1))
        assertEquals(0, device.posts)
    }

    @Test fun `late Android alarm is useful only before departure and early replay is ignored`() {
        enable()
        val snapshot = snapshot(journey())
        engine.refresh(snapshot, true, now)
        val plan = device.pending.values.single()
        engine.deliver(plan.key, snapshot, true, plan.trigger.minusSeconds(1))
        assertEquals(0, device.posts)
        engine.deliver(plan.key, snapshot, true, plan.departure.minusSeconds(1))
        assertEquals(1, device.posts)
    }

    @Test fun `recurrences preserve civil time across DST and omit completed dates`() {
        val recurring = journey(date = "2026-10-24").copy(recurrence = PlannedJourneyRecurrence(
            weekdays = DayOfWeek.entries, startsOn = LocalDate.parse("2026-10-24"), occurrenceLimit = 3))
        val plans = PlannedJourneyReminderPlanner.plans(snapshot(recurring))
        assertEquals(listOf("2026-10-24T05:50:00Z", "2026-10-25T06:50:00Z", "2026-10-26T06:50:00Z"), plans.map { it.trigger.toString() })
        val completed = recurring.copy(completedOccurrenceDates = listOf(LocalDate.parse("2026-10-25")))
        assertEquals(2, PlannedJourneyReminderPlanner.plans(snapshot(completed)).size)
    }

    @Test fun `spring gap rejects reminders and autumn overlap uses earlier offset`() {
        assertTrue(PlannedJourneyReminderPlanner.plans(snapshot(journey(date = "2026-03-29", time = "02:30"))).isEmpty())
        assertEquals(Instant.parse("2026-10-25T00:20:00Z"),
            PlannedJourneyReminderPlanner.plans(snapshot(journey(date = "2026-10-25", time = "02:30"))).single().trigger)
    }

    @Test fun `clock changing does not move fixed zoned journeys or replay delivered reminders`() {
        enable()
        val snapshot = snapshot(journey())
        engine.refresh(snapshot, true, now)
        val plan = device.pending.values.single()
        fire(plan, snapshot)
        engine.refresh(snapshot, true, plan.departure.plusSeconds(60))
        engine.refresh(snapshot, true, now.minusSeconds(3600))
        assertTrue(device.pending.isEmpty())
        assertEquals(1, device.posts)
    }

    @Test fun `bounded alarm window refills as recurring reminders fire`() {
        enable()
        val snapshot = PlannedJourneyPersistenceSnapshot(journeys = (1..40).map { index ->
            journey(id = "trip-$index", destination = "IT:DEST-$index").copy(recurrence = PlannedJourneyRecurrence(
                weekdays = DayOfWeek.entries, startsOn = LocalDate.parse("2026-10-15"), occurrenceLimit = 2))
        })
        engine.refresh(snapshot, true, now)
        assertEquals(64, device.pending.size)
        val nextDayKey = PlannedJourneyReminderPlanner.plans(snapshot)[64].key
        assertFalse(nextDayKey in device.pending)
        val first = device.pending.values.first()
        fire(first, snapshot)
        assertEquals(64, device.pending.size)
        assertTrue(nextDayKey in device.pending)
        device.pending.values.filter { it.trigger == first.trigger }.toList().forEach { fire(it, snapshot) }
        assertEquals(40, device.posts)
        assertEquals(40, device.pending.size)
    }

    @Test fun `failed schedules and cancellations retain journal entries for repair`() {
        enable()
        device.failSchedule = true
        assertTrue(engine.refresh(snapshot(journey()), true, now).failed)
        assertEquals(1, store.state.registered.size)
        device.failSchedule = false
        assertFalse(engine.refresh(snapshot(journey()), true, now).failed)
        device.failCancel = true
        assertTrue(engine.refresh(empty, true, now).failed)
        assertEquals(1, store.state.registered.size)
        device.failCancel = false
        assertFalse(engine.refresh(empty, true, now).failed)
        assertTrue(store.state.registered.isEmpty())
    }

    @Test fun `a failed journal commit cannot create an untracked alarm`() {
        enable()
        store.failWrite = true
        assertTrue(runCatching { engine.refresh(snapshot(journey()), true, now) }.isFailure)
        assertTrue(device.pending.isEmpty())
    }

    @Test fun `opaque identities avoid Java hash collisions and keep labels out of journal`() {
        val a = journey(id = "FB")
        val b = journey(id = "Ea", time = "08:01")
        assertEquals(a.id.hashCode(), b.id.hashCode())
        enable()
        engine.refresh(snapshot(a, b), true, now)
        assertEquals(2, device.pending.size)
        val encoded = PlannedJourneyReminderStateCodec.encode(store.state)
        assertFalse(encoded.contains("Riunione"))
        assertFalse(encoded.contains("Roma"))
        assertEquals(store.state, PlannedJourneyReminderStateCodec.decode(encoded))
    }

    @Test fun `unknown versions malformed consent and oversized journals fail closed`() {
        assertFalse(PlannedJourneyReminderStateCodec.decode(null)!!.enabled)
        listOf("", "{}", "x".repeat(131073),
            """{"version":2,"enabled":true,"registered":[],"delivered":[]}""",
            """{"version":1,"enabled":"true","registered":[],"delivered":[]}""",
            """{"version":1,"enabled":true,"registered":["invalid"],"delivered":[]}""")
            .forEach { assertNull(PlannedJourneyReminderStateCodec.decode(it)) }
    }

    private class MemoryState : PlannedJourneyReminderStateStore {
        var state = PlannedJourneyReminderState()
        var failWrite = false
        override fun read() = state
        override fun write(state: PlannedJourneyReminderState) { check(!failWrite); this.state = state }
    }
    private class Device : PlannedJourneyReminderDevice {
        val pending = linkedMapOf<String, PlannedJourneyReminderPlan>()
        val visible = mutableSetOf<String>()
        var posts = 0
        var failSchedule = false
        var failCancel = false
        var onSchedule: (PlannedJourneyReminderPlan) -> Unit = {}
        var onNotify: (PlannedJourneyReminderPlan) -> Unit = {}
        override fun schedule(plan: PlannedJourneyReminderPlan) { check(!failSchedule); onSchedule(plan); pending[plan.key] = plan }
        override fun cancel(key: String) { check(!failCancel); pending.remove(key); visible.remove(key) }
        override fun notify(plan: PlannedJourneyReminderPlan) { onNotify(plan); posts++; visible += plan.key }
    }
}
