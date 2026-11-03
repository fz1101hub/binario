package it.binario.app.notifications

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.gpsalarm.GpsTrackStatus
import it.binario.app.gpsalarm.GpsTrainTrack
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopCountdownTest {
    private val zone = ZoneId.of("Europe/Rome")
    private fun at(time: String): Instant = LocalDateTime.parse("2026-11-02T$time").atZone(zone).toInstant()

    private val timeline = listOf("08:30", "08:36", "08:42", "08:48", "08:55").mapIndexed { index, time ->
        CountdownStop("Stop $index", time, at(time))
    }

    @Test fun `scheduled times against the clock give the stops left, then the last stop before getting off`() {
        val countdown = StopCountdownCalculator.countdown(timeline, board = 0, alight = 4, now = at("08:37"))!!
        assertEquals(StopProgressSource.SCHEDULED, countdown.source)
        assertEquals(3, countdown.stopsLeft)
        assertEquals("Stop 2", countdown.nextStop)
        assertEquals("08:42", countdown.nextStopTime)

        val last = StopCountdownCalculator.countdown(timeline, 0, 4, at("08:50"))!!
        assertTrue(last.alightAtNextStop)
        assertEquals("Stop 4", last.nextStop)
        assertEquals("08:55", last.nextStopTime)
        assertEquals(at("08:55"), last.nextStopAt)
    }

    @Test fun `no countdown before the train leaves the boarding stop or once the traveller is there`() {
        assertNull(StopCountdownCalculator.countdown(timeline, board = 1, alight = 4, now = at("08:33")))
        assertNull(StopCountdownCalculator.countdown(timeline, 0, 4, at("08:56")))
        assertNull(StopCountdownCalculator.countdown(timeline, 3, 3, at("08:50")))
        // Live data saying the train has not reached the boarding stop wins over the clock.
        assertNull(StopCountdownCalculator.countdown(timeline, board = 2, alight = 4, now = at("08:50"), livePassed = 1))
    }

    @Test fun `live progress wins over the clock and the position only moves it forward`() {
        // 08:50 by the clock would be the last stop, but the late train was last confirmed at stop 1.
        val live = StopCountdownCalculator.countdown(timeline, 0, 4, at("08:50"), livePassed = 1)!!
        assertEquals(StopProgressSource.LIVE, live.source)
        assertEquals(3, live.stopsLeft)

        val ahead = StopCountdownCalculator.countdown(timeline, 0, 4, at("08:50"), livePassed = 1, positionPassed = 2)!!
        assertEquals(StopProgressSource.POSITION, ahead.source)
        assertEquals(2, ahead.stopsLeft)

        val behind = StopCountdownCalculator.countdown(timeline, 0, 4, at("08:50"), livePassed = 2, positionPassed = 1)!!
        assertEquals(StopProgressSource.LIVE, behind.source)
        assertEquals(2, behind.stopsLeft)

        val gps = StopCountdownCalculator.countdown(timeline, 0, 4, at("08:50"), positionPassed = 3)!!
        assertEquals(StopProgressSource.POSITION, gps.source)
        assertTrue(gps.alightAtNextStop)
    }

    @Test fun `skipped stops are never counted nor announced`() {
        val skipped = timeline.mapIndexed { index, stop -> if (index == 2) stop.copy(skipped = true) else stop }
        val countdown = StopCountdownCalculator.countdown(skipped, 0, 4, at("08:37"))!!
        assertEquals(2, countdown.stopsLeft)
        assertEquals("Stop 3", countdown.nextStop)
    }

    @Test fun `the followed train counts down from its confirmed stops, its detection or its expected times`() {
        val live = service(
            listOf(
                stop("Milano Centrale", "08:30", "08:34", completed = true),
                stop("Sesto San Giovanni", "08:36", "08:40", completed = true),
                stop("Monza", "08:42", "08:46"),
                stop("Lissone", "08:48", "08:52"),
                stop("Seregno", "08:55", "08:59")
            )
        )
        val fromCompleted = FollowedTripStatus.countdown(live, 0, 4, at("08:30"), at("08:41"), zone)!!
        assertEquals(StopProgressSource.LIVE, fromCompleted.source)
        assertEquals(3, fromCompleted.stopsLeft)
        assertEquals("08:46", fromCompleted.nextStopTime)

        val noFlags = live.copy(stops = live.stops.map { it.copy(completed = false) })
        val byExpected = FollowedTripStatus.countdown(noFlags, 0, 4, at("08:30"), at("08:53"), zone)!!
        assertEquals(StopProgressSource.SCHEDULED, byExpected.source)
        assertTrue(byExpected.alightAtNextStop)
        assertEquals("08:59", byExpected.nextStopTime)

        val scheduledOnly = noFlags.copy(operationalStatusVerified = false, stops = noFlags.stops.map { it.copy(operationalStatusVerified = false) })
        val bySchedule = FollowedTripStatus.countdown(scheduledOnly, 0, 4, at("08:30"), at("08:49"), zone)!!
        assertTrue(bySchedule.alightAtNextStop)
        assertEquals("08:55", bySchedule.nextStopTime)
    }

    @Test fun `a fresh device position on the followed train moves the countdown ahead of stale live data`() {
        val stale = service(
            listOf(
                stop("Milano Centrale", "08:30", "08:34", completed = true),
                stop("Sesto San Giovanni", "08:36", "08:40"),
                stop("Monza", "08:42", "08:46"),
                stop("Lissone", "08:48", "08:52")
            )
        )
        val now = at("08:47")
        val track = GpsTrainTrack("2153", GpsTrackStatus.ON_ROUTE, 2, "MONZA", null, now.toEpochMilli() - 30_000)
        val countdown = FollowedTripStatus.countdown(stale, 0, 3, at("08:30"), now, zone, track)!!
        assertEquals(StopProgressSource.POSITION, countdown.source)
        assertTrue(countdown.alightAtNextStop)
        // A position older than three minutes no longer decides.
        val old = track.copy(lastFixAtEpochMillis = now.toEpochMilli() - 4 * 60_000)
        assertEquals(StopProgressSource.LIVE, FollowedTripStatus.countdown(stale, 0, 3, at("08:30"), now, zone, old)!!.source)
    }

    @Test fun `the view announces the countdown and its next stop, and a cancelled train has none`() {
        val trip = FollowedTrip("trip", "Milano Centrale → Seregno", listOf(FollowedTripLeg("2153", "REG 2153", "Milano Centrale", "Seregno", at("08:30"), at("08:55"))))
        val running = service(
            listOf(
                stop("Milano Centrale", "08:30", "08:30", completed = true),
                stop("Monza", "08:42", "08:42", completed = true),
                stop("Seregno", "08:55", "08:57", platform = "2")
            )
        )
        val view = FollowedTripStatus.view(trip, listOf(running), at("08:45"), zone)
        assertTrue(view.countdown!!.alightAtNextStop)
        assertEquals("Seregno", view.nextStop)
        assertEquals("08:57", view.nextStopTime)
        assertEquals("2", view.platform)

        val cancelled = FollowedTripStatus.view(trip, listOf(running.copy(status = ServiceStatus.CANCELLED)), at("08:45"), zone)
        assertNull(cancelled.countdown)
    }

    @Test fun `a detected station counts as reached and a skipped stop is left out`() {
        val skipped = service(
            listOf(
                stop("Milano Centrale", "08:30", "08:30", completed = true),
                stop("Monza", "08:42", "08:42").copy(routeChange = StopRouteChange.SKIPPED),
                stop("Lissone", "08:48", "08:48"),
                stop("Seregno", "08:55", "08:55")
            )
        )
        val countdown = FollowedTripStatus.countdown(skipped, 0, 3, at("08:30"), at("08:35"), zone)!!
        assertEquals(2, countdown.stopsLeft)
        assertEquals("Lissone", countdown.nextStop)
    }

    @Test fun `near the stop to get off the refresh follows the next stop, never sooner than two minutes`() {
        val regular = at("09:00")
        val near = StopCountdown(2, 3, "Lissone", "08:48", at("08:48"), "Seregno", StopProgressSource.LIVE)
        assertEquals(at("08:48").plusSeconds(30), FollowedTripPlanner.nextRefreshAt(regular, at("08:45"), near))
        assertEquals(at("08:49"), FollowedTripPlanner.nextRefreshAt(regular, at("08:47"), near))
        // Far from the stop, or without a countdown, the regular 15-minute refresh stays.
        assertEquals(regular, FollowedTripPlanner.nextRefreshAt(regular, at("08:45"), near.copy(stopsLeft = 5)))
        assertEquals(regular, FollowedTripPlanner.nextRefreshAt(regular, at("08:45"), null))
        assertEquals(regular, FollowedTripPlanner.nextRefreshAt(regular, at("08:45"), near.copy(nextStopAt = at("09:20"))))
    }

    private fun stop(station: String, scheduled: String, expected: String, completed: Boolean = false, platform: String? = null) =
        TrainStop(station, scheduled, expected, platform, 0, completed, operationalStatusVerified = true)

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
        delayMinutes = 4,
        status = ServiceStatus.DELAYED,
        stops = stops
    )
}
