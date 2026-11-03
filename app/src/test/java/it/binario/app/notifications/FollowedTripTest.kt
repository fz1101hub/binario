package it.binario.app.notifications

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.journeys.TransferRiskLevel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowedTripTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val today = LocalDate.of(2026, 11, 2)
    private fun at(time: String): Instant = LocalDateTime.parse("2026-11-02T$time").atZone(zone).toInstant()

    private val trip = FollowedTrip(
        id = "trip",
        title = "Milano Centrale → Lecco",
        legs = listOf(
            FollowedTripLeg("2153", "REG 2153", "Milano Centrale", "Monza", at("08:30"), at("08:50")),
            FollowedTripLeg("5521", "RE 5521", "Monza", "Lecco", at("08:58"), at("09:40"))
        ),
        transferMinutes = listOf(0)
    )

    @Test fun `nothing runs before 30 minutes ahead of the departure, then every 15 minutes until 30 minutes after arrival`() {
        assertEquals(FollowedTripPlanner.Step.WaitUntil(at("08:00")), FollowedTripPlanner.step(trip, at("06:15")))
        assertEquals(FollowedTripPlanner.Step.Refresh(at("08:15")), FollowedTripPlanner.step(trip, at("08:00")))
        assertEquals(FollowedTripPlanner.Step.Refresh(at("09:15")), FollowedTripPlanner.step(trip, at("09:00")))
        // The last refresh before the end is followed by one just after it, which removes the notification.
        assertEquals(FollowedTripPlanner.Step.Refresh(at("10:11")), FollowedTripPlanner.step(trip, at("10:00")))
        assertEquals(FollowedTripPlanner.Step.Finish, FollowedTripPlanner.step(trip, at("10:11")))
    }

    @Test fun `a known arrival delay stretches the end of the window, at most by six hours`() {
        assertEquals(at("10:10"), FollowedTripPlanner.windowEnd(trip, null))
        assertEquals(at("10:35"), FollowedTripPlanner.windowEnd(trip, 25))
        assertEquals(at("16:10"), FollowedTripPlanner.windowEnd(trip, 24 * 60))
        assertTrue(FollowedTripPlanner.step(trip, at("10:20"), 25) is FollowedTripPlanner.Step.Refresh)
        assertTrue(FollowedTripPlanner.isUpcomingOrRunning(trip, at("07:00")))
        assertFalse(FollowedTripPlanner.isUpcomingOrRunning(trip, at("11:00")))
    }

    @Test fun `the view shows the current train's delay, next stop, platform and the risk of the next change`() {
        val first = service("2153", listOf(
            stop("MILANO CENTRALE", "08:30", "08:36", 6, platform = "14", completed = true),
            stop("SESTO S. GIOVANNI", "08:40", "08:46", 6, platform = "2"),
            stop("MONZA", "08:50", "08:56", 6, platform = "3")
        ), delay = 6)

        val view = FollowedTripStatus.view(trip, listOf(first, null), at("08:41"), zone)

        assertEquals(0, view.legIndex)
        assertTrue(view.live)
        assertEquals(6, view.delayMinutes)
        assertEquals("SESTO S. GIOVANNI", view.nextStop)
        assertEquals("08:46", view.nextStopTime)
        assertEquals("2", view.platform)
        // Expected 08:56 + the 5 minute change time is after the 08:58 departure, which still follows the arrival: at risk.
        assertEquals(TransferRiskLevel.AT_RISK, view.transfer!!.level)
        assertEquals("Monza", view.transferStation)
        assertEquals("RE 5521", view.nextLegLabel)
    }

    @Test fun `after the first train arrives the second is the current one, and a cancellation is shown as such`() {
        val first = service("2153", listOf(stop("MILANO CENTRALE", "08:30", "08:30", 0, completed = true), stop("MONZA", "08:50", "08:50", 0, completed = true)), 0)
        val second = service("5521", listOf(stop("MONZA", "08:58", "08:58", 0), stop("LECCO", "09:40", "09:40", 0)), 0, status = ServiceStatus.CANCELLED)

        val view = FollowedTripStatus.view(trip, listOf(first, second), at("08:52"), zone)

        assertEquals(1, view.legIndex)
        assertTrue(view.cancelled)
        assertNull(view.delayMinutes)
        assertNull(view.transfer)
    }

    @Test fun `without any answer the view has no live data and the current train is the scheduled one`() {
        val view = FollowedTripStatus.view(trip, listOf(null, null), at("08:10"), zone)

        assertEquals(0, view.legIndex)
        assertFalse(view.live)
        assertNull(view.nextStop)
        assertEquals(TransferRiskLevel.UNKNOWN, view.transfer!!.level)
    }

    @Test fun `the run of a leg is the one calling at both stations in order`() {
        val other = service("2153", listOf(stop("LECCO", "06:00", "06:00", 0), stop("MONZA", "06:40", "06:40", 0)), 0)
        val right = service("2153", listOf(stop("MILANO CENTRALE", "08:30", "08:30", 0), stop("MONZA", "08:50", "08:50", 0)), 0)

        assertEquals(right, FollowedTripStatus.pick(listOf(other, right), trip.legs.first()))
        assertEquals(other, FollowedTripStatus.pick(listOf(other), trip.legs.first()))
    }

    @Test fun `a train detail becomes a one-leg trip from its first to its last shown stop`() {
        val detail = service("2153", listOf(
            stop("Milano Centrale", "23:40", "23:40", 0),
            stop("Monza", "00:05", "00:05", 0)
        ), 0).copy(scheduledDeparture = "23:40", scheduledDepartureDateTime = LocalDateTime.parse("2026-11-02T23:40"))

        val followed = FollowedTrip.fromService(detail, "REG 2153", today, zone)!!

        assertEquals(LocalDateTime.parse("2026-11-02T23:40").atZone(zone).toInstant(), followed.departure)
        assertEquals(LocalDateTime.parse("2026-11-03T00:05").atZone(zone).toInstant(), followed.arrival)
        assertEquals("Milano Centrale → Monza", followed.title)
        assertNull(FollowedTrip.fromService(detail.copy(stops = detail.stops.take(1)), "REG 2153", today, zone))
    }

    @Test fun `the codec keeps the trip and reads a damaged value as no trip`() {
        val decoded = FollowedTripCodec.decode(FollowedTripCodec.encode(trip))

        assertEquals(trip, decoded)
        assertNull(FollowedTripCodec.decode("{broken"))
        assertNull(FollowedTripCodec.decode("""{"id":"x","legs":[]}"""))
        assertNotNull(FollowedTripCodec.decode(FollowedTripCodec.encode(trip.copy(transferMinutes = emptyList()))))
        assertEquals(Duration.ZERO, trip.copy(transferMinutes = emptyList()).walkBefore(1))
    }

    @Test fun `a platform change before boarding, a cancellation and a connection at risk are notified once each`() {
        val board = { platform: String, scheduled: String? -> TrainStop("MILANO CENTRALE", "08:30", "08:30", platform, 0, false, operationalStatusVerified = true, scheduledPlatform = scheduled) }
        val onTime = service("2153", listOf(board("14", null), stop("MONZA", "08:50", "08:50", 0)), 0)
        val moved = service("2153", listOf(board("7", "14"), stop("MONZA", "08:50", "08:50", 0)), 0)

        val quiet = FollowedTripStatus.view(trip, listOf(onTime, null), at("08:10"), zone)
        val boarding = FollowedTripAlerts.boardingPlatform(trip, quiet, listOf(onTime, null))
        assertEquals("14", boarding!!.platform)
        assertTrue(FollowedTripAlerts.changes(trip, quiet, listOf(onTime, null), null, emptySet()).isEmpty())

        // Published by the source next to the timetable platform...
        val view = FollowedTripStatus.view(trip, listOf(moved, null), at("08:15"), zone)
        val change = FollowedTripAlerts.changes(trip, view, listOf(moved, null), boarding, emptySet()).single() as FollowedTripChange.PlatformChanged
        assertEquals("7" to "14", change.platform to change.previous)
        // ...and notified once: the next refresh with the same platform is quiet.
        assertTrue(FollowedTripAlerts.changes(trip, view, listOf(moved, null), boarding, setOf(change.key)).isEmpty())
        // A change the source does not mark is noticed against the platform the previous refresh read.
        val observed = FollowedTripAlerts.changes(trip, quiet, listOf(onTime, null), boarding.copy(platform = "12"), emptySet()).single()
        assertEquals("14" to "12", (observed as FollowedTripChange.PlatformChanged).platform to observed.previous)

        // Once the train has left the boarding stop its platform no longer matters.
        val departed = service("2153", listOf(stop("MILANO CENTRALE", "08:30", "08:36", 6, platform = "7", completed = true), stop("SESTO S. GIOVANNI", "08:40", "08:46", 6, platform = "2"), stop("MONZA", "08:50", "08:56", 6, platform = "3")), 6)
        val running = FollowedTripStatus.view(trip, listOf(departed, null), at("08:41"), zone)
        assertNull(FollowedTripAlerts.boardingPlatform(trip, running, listOf(departed, null)))
        val risk = FollowedTripAlerts.changes(trip, running, listOf(departed, null), boarding, emptySet()).single() as FollowedTripChange.ConnectionAtRisk
        assertEquals(TransferRiskLevel.AT_RISK, risk.level)

        val cancelled = service("2153", listOf(board("14", null), stop("MONZA", "08:50", "08:50", 0)), 0, status = ServiceStatus.CANCELLED)
        val cancelledView = FollowedTripStatus.view(trip, listOf(cancelled, null), at("08:15"), zone)
        assertTrue(FollowedTripAlerts.changes(trip, cancelledView, listOf(cancelled, null), null, emptySet()).single() is FollowedTripChange.Cancelled)
        assertTrue(FollowedTripAlerts.isQuiet(at("06:30"), zone))
        assertFalse(FollowedTripAlerts.isQuiet(at("08:15"), zone))
    }

    private fun stop(station: String, scheduled: String, expected: String, delay: Int, platform: String? = null, completed: Boolean = false) =
        TrainStop(station, scheduled, expected, platform, delay, completed, operationalStatusVerified = true)

    private fun service(number: String, stops: List<TrainStop>, delay: Int, status: ServiceStatus = ServiceStatus.DELAYED) = TrainService(
        id = "viaggiatreno:S01700:$number:1",
        number = number,
        operator = RailOperator.TRENITALIA_REGIONALE,
        trainCategory = TrainCategory.REGIONALE,
        origin = stops.first().station,
        destination = stops.last().station,
        scheduledDeparture = stops.first().scheduled,
        expectedDeparture = stops.first().expected,
        platform = stops.first().platform,
        delayMinutes = delay,
        status = status,
        stops = stops
    )
}
