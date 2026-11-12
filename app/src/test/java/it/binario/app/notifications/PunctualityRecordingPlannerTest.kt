package it.binario.app.notifications

import it.binario.app.FavouriteTrainPunctuality
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.repository.TrainNumberSearchUpdate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctualityRecordingPlannerTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val today = LocalDate.of(2026, 11, 2)
    private fun at(time: String, date: LocalDate = today): Instant = LocalDateTime.of(date, LocalTime.parse(time)).atZone(zone).toInstant()

    @Test fun `the run is recorded after its scheduled arrival once known, before that two hours after departure`() {
        val unknownArrival = RecordingCandidate("2153", LocalTime.of(8, 30))
        val learned = unknownArrival.copy(durationMinutes = 65)
        val noDeparture = RecordingCandidate("4", null)

        assertEquals(at("10:30"), PunctualityRecordingPlanner.dueAt(unknownArrival, today, zone))
        assertEquals(at("09:55"), PunctualityRecordingPlanner.dueAt(learned, today, zone))
        assertEquals(at("23:15"), PunctualityRecordingPlanner.dueAt(noDeparture, today, zone))
    }

    @Test fun `once a day, retried after 45 minutes and at most three times`() {
        val recorded = RecordingCandidate("2153", LocalTime.of(8, 30), 65, recordedOn = today)
        val retried = RecordingCandidate("2153", LocalTime.of(8, 30), 65, attemptDate = today, attempts = 1, lastAttempt = at("09:56"))
        val exhausted = retried.copy(attempts = 3)

        assertNull(PunctualityRecordingPlanner.dueAt(recorded, today, zone))
        assertEquals(at("09:55", today.plusDays(1)), PunctualityRecordingPlanner.dueAt(recorded, today.plusDays(1), zone))
        assertEquals(at("10:41"), PunctualityRecordingPlanner.dueAt(retried, today, zone))
        assertNull(PunctualityRecordingPlanner.dueAt(exhausted, today, zone))
        assertEquals(at("09:55", today.plusDays(1)), PunctualityRecordingPlanner.dueAt(exhausted, today.plusDays(1), zone))
    }

    @Test fun `due runs and the next run, a late arrival after midnight included`() {
        val morning = RecordingCandidate("2153", LocalTime.of(8, 30), 65)
        val night = RecordingCandidate("1999", LocalTime.of(23, 20), 70)

        val nightDone = night.copy(recordedOn = today.minusDays(1))
        assertEquals(listOf(morning to today), PunctualityRecordingPlanner.due(listOf(morning, nightDone), at("10:00"), zone))
        // Yesterday's night run, never recorded, is still caught up within 20 hours.
        assertEquals(listOf(night to today.minusDays(1)), PunctualityRecordingPlanner.due(listOf(night), at("10:00"), zone))
        assertEquals(at("09:55"), PunctualityRecordingPlanner.nextRun(listOf(morning), at("06:00"), zone))
        assertEquals(at("10:00"), PunctualityRecordingPlanner.nextRun(listOf(morning), at("10:00"), zone))
        assertEquals(at("09:55", today.plusDays(1)), PunctualityRecordingPlanner.nextRun(listOf(morning.copy(recordedOn = today)), at("10:00"), zone))
        // 23:20 + 70 minutes + 20 = 00:50 of the next day, for yesterday's service day.
        val afterMidnight = at("01:00", today.plusDays(1))
        assertEquals(listOf(night to today), PunctualityRecordingPlanner.due(listOf(night), afterMidnight, zone))
        assertNull(PunctualityRecordingPlanner.nextRun(emptyList(), at("10:00"), zone))
        // A train leaving at 23:00 and taking 160 minutes: its run of the 2nd is due at 02:00 of the 3rd. Planned at
        // 00:30 of the 3rd (the app opened), the next run is that one, not 02:00 of the 4th.
        val late = RecordingCandidate("1998", LocalTime.of(23, 0), 160)
        assertEquals(at("02:00", today.plusDays(1)), PunctualityRecordingPlanner.nextRun(listOf(late), at("00:30", today.plusDays(1)), zone))
        assertEquals(listOf(late to today), PunctualityRecordingPlanner.due(listOf(late), at("02:00", today.plusDays(1)), zone))
    }

    @Test fun `a complete answer without the train ends the day, a failed or partial one is retried`() {
        assertTrue(PunctualityRecordingPlanner.runsNot(TrainNumberSearchUpdate(emptyList(), complete = true, unavailableSources = emptySet())))
        assertFalse(PunctualityRecordingPlanner.runsNot(null))
        assertFalse(PunctualityRecordingPlanner.runsNot(TrainNumberSearchUpdate(emptyList(), complete = false, unavailableSources = emptySet())))
        assertFalse(PunctualityRecordingPlanner.runsNot(TrainNumberSearchUpdate(emptyList(), complete = true, unavailableSources = setOf("ViaggiaTreno"))))
        // A day marked done is not due again: one lookup on a day the train does not run, not three.
        val weekday = RecordingCandidate("2153", LocalTime.of(8, 30), 65, recordedOn = today, attemptDate = today, attempts = 1, lastAttempt = at("09:55"))
        assertNull(PunctualityRecordingPlanner.dueAt(weekday, today, zone))
    }

    @Test fun `a run counts as arrived at its last stop or twenty minutes after the scheduled arrival`() {
        val running = service(completed = false)

        assertFalse(PunctualityRecordingPlanner.arrived(running, at("09:35"), at("09:50")))
        assertTrue(PunctualityRecordingPlanner.arrived(running, at("09:35"), at("09:55")))
        assertTrue(PunctualityRecordingPlanner.arrived(service(completed = true), at("09:35"), at("09:36")))
    }

    @Test fun `a favourite saved with its timetable label is recorded under that label`() {
        val day = LocalDate.of(2026, 11, 10)
        val live = service(completed = true).copy(scheduledDepartureDateTime = day.atTime(8, 30))
        val recorded = PunctualityRecordingPlanner.observation(live, "RE_11 - 2153", day)!!

        assertEquals("RE_11 - 2153", recorded.trainNumber)
        assertEquals(day, recorded.serviceDate)
        assertEquals(1, FavouriteTrainPunctuality.local(listOf(recorded), "RE_11 - 2153", day, day)?.runs)
        assertEquals("2153", PunctualityRecordingPlanner.observation(live, "2153", day)!!.trainNumber)
        assertNull(PunctualityRecordingPlanner.observation(live.copy(operationalStatusVerified = false), "RE_11 - 2153", day))
    }

    private fun service(completed: Boolean) = TrainService(
        id = "svc", number = "2153", operator = RailOperator.TRENITALIA_REGIONALE, trainCategory = TrainCategory.REGIONALE,
        origin = "Milano Centrale", destination = "Pavia", scheduledDeparture = "08:30", expectedDeparture = "08:30",
        platform = null, delayMinutes = 0, status = ServiceStatus.ON_TIME,
        stops = listOf(
            TrainStop("Milano Centrale", "08:30", "08:30", null, 0, completed = true),
            TrainStop("Pavia", "09:35", "09:35", null, 0, completed = completed)
        )
    )
}
