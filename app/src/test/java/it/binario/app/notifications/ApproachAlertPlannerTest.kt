package it.binario.app.notifications

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApproachAlertPlannerTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val today = LocalDate.of(2026, 11, 2)
    private fun at(time: String, date: LocalDate = today): Instant = LocalDateTime.of(date, java.time.LocalTime.parse(time)).atZone(zone).toInstant()

    private val alert = ApproachAlert("2153", "REG 2153", "Monza", "08:42", "08:30", 10, at("06:00"))

    private fun observation(expected: String, delay: Int?, platform: String? = "3", passed: Boolean = false, cancelled: Boolean = false) =
        ApproachObservation(at(expected), delay, platform, passed, cancelled)

    @Test fun `the checks start 30 minutes before the moment to notify and last up to three hours of delay`() {
        val window = ApproachAlertPlanner.window(alert, today, zone)!!
        assertEquals(at("08:02"), window.start)
        assertEquals(at("11:42"), window.endInclusive)
        // Before the window: wait for it; inside, every 15 minutes while the moment is far.
        assertEquals(ApproachDecision.WaitUntil(at("08:02")), ApproachAlertPlanner.decide(alert, today, null, at("07:30"), false, zone))
        assertEquals(ApproachDecision.WaitUntil(at("08:17")), ApproachAlertPlanner.decide(alert, today, observation("08:52", 10), at("08:02"), false, zone))
    }

    @Test fun `the live delay moves the moment to notify and a one-shot run is planned for it`() {
        // Expected 08:52 (ten minutes late), lead 10: the moment is 08:42, before the next check at 08:47+.
        assertEquals(ApproachDecision.WaitUntil(at("08:42")), ApproachAlertPlanner.decide(alert, today, observation("08:52", 10), at("08:32"), false, zone))
        // At the moment: "tra ~10 min".
        assertEquals(ApproachDecision.Notify(10), ApproachAlertPlanner.decide(alert, today, observation("08:52", 10), at("08:42"), false, zone))
        // A run a few seconds late rounds the minutes up.
        assertEquals(ApproachDecision.Notify(10), ApproachAlertPlanner.decide(alert, today, observation("08:52", 10), at("08:42").plusSeconds(20), false, zone))
        // A delay grown in the meantime pushes the moment again.
        assertEquals(ApproachDecision.WaitUntil(at("08:50")), ApproachAlertPlanner.decide(alert, today, observation("09:00", 18), at("08:42"), false, zone))
        // Without any answer the timetable decides: 08:32 for 08:42.
        assertEquals(ApproachDecision.Notify(10), ApproachAlertPlanner.decide(alert, today, null, at("08:32"), false, zone))
    }

    @Test fun `lead times of 5 and 15 minutes and the minute count`() {
        val five = alert.copy(leadMinutes = 5)
        val fifteen = alert.copy(leadMinutes = 15)
        assertEquals(at("08:47"), ApproachAlertPlanner.notifyAt(five, at("08:52")))
        assertEquals(at("08:37"), ApproachAlertPlanner.notifyAt(fifteen, at("08:52")))
        assertEquals(ApproachDecision.Notify(5), ApproachAlertPlanner.decide(five, today, observation("08:52", 10), at("08:47"), false, zone))
        assertEquals(4, ApproachAlertPlanner.minutesUntil(at("08:47").plusSeconds(40), at("08:51")))
        assertEquals(0, ApproachAlertPlanner.minutesUntil(at("08:52"), at("08:51")))
        assertEquals(listOf(5, 10, 15), ApproachAlertPlanner.LEAD_OPTIONS)
    }

    @Test fun `nothing is notified twice a day, after the train passed, when cancelled or too late`() {
        assertEquals(ApproachDecision.Done, ApproachAlertPlanner.decide(alert, today, observation("08:52", 10), at("08:42"), notified = true, zone = zone))
        assertEquals(ApproachDecision.Done, ApproachAlertPlanner.decide(alert, today, observation("08:52", 10, passed = true), at("08:42"), false, zone))
        assertEquals(ApproachDecision.Done, ApproachAlertPlanner.decide(alert, today, observation("08:52", 10, cancelled = true), at("08:42"), false, zone))
        assertEquals(ApproachDecision.Done, ApproachAlertPlanner.decide(alert, today, observation("08:52", 10), at("08:55"), false, zone))
        assertEquals(ApproachDecision.Done, ApproachAlertPlanner.decide(alert, today, observation("08:52", 10), at("11:43"), false, zone))
    }

    @Test fun `the quiet hours from 22 to 7 never notify`() {
        val early = alert.copy(stationTime = "07:05", departure = "06:50")
        assertEquals(ApproachDecision.Done, ApproachAlertPlanner.decide(early, today, null, at("06:55"), false, zone))
        assertEquals(at("07:00"), ApproachAlertPlanner.window(early, today, zone)!!.start)
        val night = alert.copy(stationTime = "23:30", departure = "22:40")
        assertNull(ApproachAlertPlanner.window(night, today, zone))
        assertEquals(ApproachDecision.Done, ApproachAlertPlanner.decide(night, today, null, at("23:20"), false, zone))
        // A late evening train whose delay brings the moment past 22:00: the checks end at 22:00.
        val evening = alert.copy(stationTime = "21:40", departure = "21:00")
        assertEquals(at("22:00"), ApproachAlertPlanner.window(evening, today, zone)!!.endInclusive)
        assertEquals(ApproachDecision.Done, ApproachAlertPlanner.decide(evening, today, observation("22:20", 40), at("22:10"), false, zone))
    }

    @Test fun `a station after midnight belongs to the next calendar day of the run`() {
        val late = alert.copy(stationTime = "00:20", departure = "23:40")
        assertEquals(at("00:20", today.plusDays(1)), ApproachAlertPlanner.scheduledAt(late, today, zone))
    }

    @Test fun `the next run is today's check or moment, else tomorrow's window`() {
        assertEquals(at("08:02"), ApproachAlertPlanner.nextRun(alert, null, null, null, at("07:00"), zone))
        assertEquals(at("08:42"), ApproachAlertPlanner.nextRun(alert, null, null, observation("08:52", 10), at("08:32"), zone))
        assertEquals(at("08:40"), ApproachAlertPlanner.nextRun(alert, null, null, null, at("08:40"), zone))
        val tomorrow = at("08:02", today.plusDays(1))
        assertEquals(tomorrow, ApproachAlertPlanner.nextRun(alert, null, notifiedOn = today, observation = null, now = at("08:45"), zone = zone))
        assertEquals(tomorrow, ApproachAlertPlanner.nextRun(alert, doneOn = today, notifiedOn = null, observation = null, now = at("08:45"), zone = zone))
        assertEquals(tomorrow, ApproachAlertPlanner.nextRun(alert, null, null, null, at("12:00"), zone))
    }

    @Test fun `the observation reads the verified delay and platform at the station, else the timetable`() {
        val live = service(listOf(stop("Milano Centrale", "08:30", "08:38", 8, completed = true), stop("MONZA", "08:42", "08:50", 8, platform = "3")))
        val observed = ApproachAlertPlanner.observe(live, alert, today, zone)!!
        assertEquals(at("08:50"), observed.expectedAt)
        assertEquals(8, observed.delayMinutes)
        assertEquals("3", observed.platform)
        assertTrue(observed.live)
        assertFalse(observed.passed)

        val scheduled = live.copy(operationalStatusVerified = false, stops = live.stops.map { it.copy(operationalStatusVerified = false) })
        val timetable = ApproachAlertPlanner.observe(scheduled, alert, today, zone)!!
        assertEquals(at("08:42"), timetable.expectedAt)
        assertNull(timetable.delayMinutes)
        assertFalse(timetable.live)

        val passed = live.copy(stops = live.stops.map { it.copy(completed = true) })
        assertTrue(ApproachAlertPlanner.observe(passed, alert, today, zone)!!.passed)
        val skipped = live.copy(stops = live.stops.map { if (it.station == "MONZA") it.copy(routeChange = StopRouteChange.SKIPPED) else it })
        assertTrue(ApproachAlertPlanner.observe(skipped, alert, today, zone)!!.cancelled)
        assertTrue(ApproachAlertPlanner.observe(live.copy(status = ServiceStatus.CANCELLED), alert, today, zone)!!.cancelled)
        assertNull(ApproachAlertPlanner.observe(live, alert.copy(station = "Lecco"), today, zone))
    }

    @Test fun `only favourite trains are checked and the codecs keep alerts and day state`() {
        assertEquals(listOf(alert), ApproachAlertPlanner.active(listOf(alert, alert.copy(number = "9999")), listOf("REG 2153")))
        assertEquals(listOf(alert), ApproachAlertCodec.decode(ApproachAlertCodec.encode(listOf(alert))))
        assertEquals(emptyList<ApproachAlert>(), ApproachAlertCodec.decode("[{\"number\":\"1\",\"station\":\"X\",\"time\":\"8:00\",\"lead\":7,\"createdAt\":1}]"))
        assertEquals(emptyList<ApproachAlert>(), ApproachAlertCodec.decode("{broken"))
        val state = ApproachAlertCodec.DayState(today, null, today, observation("08:52", 10))
        assertEquals(state, ApproachAlertCodec.decodeState(ApproachAlertCodec.encodeState(state)))
        val scheduledOnly = ApproachAlertCodec.DayState(today, today, null, observation("08:42", null, platform = null))
        assertEquals(scheduledOnly, ApproachAlertCodec.decodeState(ApproachAlertCodec.encodeState(scheduledOnly)))
        assertEquals(ApproachAlertCodec.DayState(null, null, null, null), ApproachAlertCodec.decodeState("{broken"))
    }

    private fun stop(station: String, scheduled: String, expected: String, delay: Int, platform: String? = null, completed: Boolean = false) =
        TrainStop(station, scheduled, expected, platform, delay, completed, operationalStatusVerified = true)

    private fun service(stops: List<TrainStop>) = TrainService(
        id = "viaggiatreno:S01700:2153:1",
        number = "2153",
        operator = RailOperator.TRENITALIA_REGIONALE,
        trainCategory = TrainCategory.REGIONALE,
        origin = stops.first().station,
        destination = stops.last().station,
        scheduledDeparture = stops.first().scheduled,
        expectedDeparture = stops.first().expected,
        platform = null,
        delayMinutes = 8,
        status = ServiceStatus.DELAYED,
        stops = stops
    )
}
