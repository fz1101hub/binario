package it.binario.app.gpsalarm

import it.binario.app.data.TrainStop
import it.binario.app.notifications.StopProgressSource
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsTrainTrackingTest {
    private val zone = ZoneId.of("Europe/Rome")
    private fun at(time: String): Long = LocalDateTime.parse("2026-11-02T$time").atZone(zone).toInstant().toEpochMilli()

    /** Four stops along the 45th parallel, about 7.9 km apart, six minutes apart in the timetable. */
    private val route = GpsTrainRoute(
        trainNumber = "2153",
        stops = listOf(
            GpsRouteStop("Alfa", 45.0, 9.0, at("10:00")),
            GpsRouteStop("Beta", 45.0, 9.1, at("10:06")),
            GpsRouteStop("Gamma", 45.0, 9.2, at("10:12")),
            GpsRouteStop("Delta", 45.0, 9.3, at("10:18"))
        ),
        boardIndex = 0,
        targetIndex = 3
    )

    private fun fix(longitude: Double, latitude: Double = 45.0, accuracy: Double? = 25.0, ageMillis: Long = 1_000L) =
        GpsFix(latitude, longitude, accuracy, ageMillis)

    @Test fun `a fix between two stops is projected on the line and compared with the interpolated timetable`() {
        val tracker = GpsTrainTracking.Tracker(route)
        // Halfway between Beta (10:06) and Gamma (10:12) at 10:15: six minutes behind 10:09.
        val track = tracker.onFix(fix(9.15, latitude = 45.002), at("10:15"))
        assertEquals(GpsTrackStatus.ON_ROUTE, track.status)
        assertEquals(1, track.passedIndex)
        assertEquals("Beta", track.passedStop)
        assertEquals(6, track.estimate!!.delayMinutes)
        assertEquals(at("10:24"), track.estimate!!.arrivalEpochMillis)
        assertEquals(at("10:15"), track.estimate!!.atEpochMillis)
    }

    @Test fun `standing at a stop counts as reaching it and the delay grows with the dwell`() {
        val tracker = GpsTrainTracking.Tracker(route)
        tracker.onFix(fix(9.15), at("10:09"))
        val dwell = tracker.onFix(fix(9.2005), at("10:13"))
        assertEquals(2, dwell.passedIndex)
        assertEquals(1, dwell.estimate!!.delayMinutes)
        val longer = tracker.onFix(fix(9.2003), at("10:16"))
        assertEquals(4, longer.estimate!!.delayMinutes)
        // Ahead of the timetable the estimate says on time, never early.
        val early = GpsTrainTracking.Tracker(route).onFix(fix(9.15), at("10:05"))
        assertEquals(0, early.estimate!!.delayMinutes)
    }

    @Test fun `at the boarding stop nothing is estimated yet`() {
        val track = GpsTrainTracking.Tracker(route).onFix(fix(9.0002), at("10:03"))
        assertEquals(GpsTrackStatus.ON_ROUTE, track.status)
        assertEquals(0, track.passedIndex)
        assertNull(track.estimate)
    }

    @Test fun `more than 1,5 km from the line gives no estimate and says so`() {
        val tracker = GpsTrainTracking.Tracker(route)
        tracker.onFix(fix(9.15), at("10:10"))
        val off = tracker.onFix(fix(9.16, latitude = 45.02), at("10:11"))
        assertEquals(GpsTrackStatus.OFF_ROUTE, off.status)
        assertNull(off.estimate)
        assertNull(off.passedIndex)
        // Back on the line the progress starts again.
        assertEquals(GpsTrackStatus.ON_ROUTE, tracker.onFix(fix(9.17), at("10:12")).status)
    }

    @Test fun `inaccurate, stale or unrated fixes are ignored and the last estimate stays with its time`() {
        val tracker = GpsTrainTracking.Tracker(route)
        val good = tracker.onFix(fix(9.15), at("10:12"))
        assertSame(good, tracker.onFix(fix(9.25, accuracy = 350.0), at("10:14")))
        assertSame(good, tracker.onFix(fix(9.25, accuracy = null), at("10:14")))
        assertSame(good, tracker.onFix(fix(9.25, ageMillis = 3 * 60_000L), at("10:14")))
        assertEquals(at("10:12"), tracker.track.estimate!!.atEpochMillis)
        assertFalse(GpsTrainTracking.accepted(fix(9.25, accuracy = 201.0)))
        assertTrue(GpsTrainTracking.accepted(fix(9.25, accuracy = 200.0)))
    }

    @Test fun `noise never moves the train back and the passed stop never decreases`() {
        val tracker = GpsTrainTracking.Tracker(route)
        // 800 m past Gamma, then a fix 600 m before it: the train is still past Gamma.
        tracker.onFix(fix(9.21), at("10:13"))
        assertEquals(2, tracker.onFix(fix(9.1924), at("10:14")).passedIndex)
        // A small step back is noise: the progress stays where it was.
        tracker.onFix(fix(9.25), at("10:15"))
        assertEquals(2, tracker.onFix(fix(9.247), at("10:15")).passedIndex)
    }

    @Test fun `reaching the stop to get off marks the arrival once`() {
        val tracker = GpsTrainTracking.Tracker(route)
        tracker.onFix(fix(9.25), at("10:15"))
        val arrived = tracker.onFix(fix(9.3001), at("10:20"))
        assertEquals(GpsTrackStatus.ARRIVED, arrived.status)
        assertEquals(at("10:20"), arrived.arrivedAtEpochMillis)
        assertEquals(at("10:20"), tracker.onFix(fix(9.3002), at("10:22")).arrivedAtEpochMillis)
    }

    @Test fun `a stop without coordinates is placed by its time and still counted`() {
        val withGap = route.copy(stops = route.stops.mapIndexed { index, stop -> if (index == 1) stop.copy(latitude = null, longitude = null) else stop })
        val geometry = GpsTrainTracking.Geometry(withGap)
        // Beta at 10:06 is placed halfway between Alfa (10:00) and Gamma (10:12), as its time is.
        assertEquals(geometry.positions[2] / 2, geometry.positions[1], 1.0)
        val track = GpsTrainTracking.Tracker(withGap).onFix(fix(9.12), at("10:08"))
        assertEquals(1, track.passedIndex)
        val countdown = GpsTrainRoutes.countdown(withGap, track, at("10:08"))!!
        assertEquals(StopProgressSource.POSITION, countdown.source)
        assertEquals(2, countdown.stopsLeft)
    }

    @Test fun `the countdown follows a fresh position, then the timetable shifted by the estimate`() {
        val tracker = GpsTrainTracking.Tracker(route)
        val track = tracker.onFix(fix(9.15), at("10:15"))
        val fresh = GpsTrainRoutes.countdown(route, track, at("10:16"))!!
        assertEquals(StopProgressSource.POSITION, fresh.source)
        assertEquals(2, fresh.stopsLeft)
        // In a tunnel for five minutes: the timetable plus the six minutes of delay decides.
        val tunnel = GpsTrainRoutes.countdown(route, track, at("10:20"))!!
        assertEquals(StopProgressSource.SCHEDULED, tunnel.source)
        assertTrue(tunnel.alightAtNextStop)
        assertEquals("10:24", tunnel.nextStopTime)
        // Without any fix the timetable alone.
        assertEquals(1, GpsTrainRoutes.countdown(route, null, at("10:13"))!!.stopsLeft)
    }

    @Test fun `the estimate session ends ten minutes after the arrival, delayed by the larger delay`() {
        val target = GpsArrivalAlarmTarget.estimate(route, "Delta", at("09:58"))!!
        assertTrue(target.isEstimate)
        assertEquals(0, target.radiusMeters)
        assertEquals(at("10:28"), GpsTrainTracking.estimateEndsAt(target, null, null))
        val late = GpsTrainTrack("2153", GpsTrackStatus.ON_ROUTE, 2, "Gamma", GpsDelayEstimate(6, at("10:24"), at("10:15")), at("10:15"))
        assertEquals(at("10:34"), GpsTrainTracking.estimateEndsAt(target, late, 4))
        assertEquals(at("10:40"), GpsTrainTracking.estimateEndsAt(target, late, 12))
        val arrived = late.copy(status = GpsTrackStatus.ARRIVED, arrivedAtEpochMillis = at("10:21"))
        assertEquals(at("10:31"), GpsTrainTracking.estimateEndsAt(target, arrived, 12))
        // Never after the hard expiry of the session.
        assertEquals(target.expiresAtEpochMillis, GpsTrainTracking.estimateEndsAt(target, null, 10 * 60))
    }

    @Test fun `the estimate can be started only while the trip is in progress`() {
        assertFalse(GpsTrainTracking.canStartEstimate(at("10:00"), at("10:18"), null, at("09:49")))
        assertTrue(GpsTrainTracking.canStartEstimate(at("10:00"), at("10:18"), null, at("09:51")))
        assertTrue(GpsTrainTracking.canStartEstimate(at("10:00"), at("10:18"), null, at("10:27")))
        assertFalse(GpsTrainTracking.canStartEstimate(at("10:00"), at("10:18"), null, at("10:29")))
        assertTrue(GpsTrainTracking.canStartEstimate(at("10:00"), at("10:18"), 20, at("10:40")))
    }

    @Test fun `a route is built from the train's stops across midnight and survives the codec`() {
        val stops = listOf(
            TrainStop("Alfa", "23:50", "23:50", null, 0, false),
            TrainStop("Beta", "23:58", "23:58", null, 0, false),
            TrainStop("Gamma", "00:06", "00:06", null, 0, false),
            TrainStop("Delta", "00:14", "00:14", null, 0, false)
        )
        val located = mapOf("Alfa" to 9.0, "Gamma" to 9.2, "Delta" to 9.3)
        val built = GpsTrainRoutes.build("2153", stops, 0, 3, 0, Instant.ofEpochMilli(at("23:50")), { name ->
            located[name]?.let { GpsAlarmStation(name, name, 45.0, it) }
        }, zone)!!
        assertEquals(at("23:50") + 24 * 60_000L, built.stops[3].scheduledEpochMillis)
        assertNull(built.stops[1].latitude)
        assertTrue(built.estimable)
        assertEquals(built, GpsTrainRoutes.decode(GpsTrainRoutes.encode(built)))
        // A route with a single located stop has no line.
        assertNull(GpsTrainRoutes.build("2153", stops, 0, 3, 0, Instant.ofEpochMilli(at("23:50")), { name -> if (name == "Alfa") GpsAlarmStation(name, name, 45.0, 9.0) else null }, zone))
        assertNull(GpsTrainRoutes.decode("{broken"))
    }

    @Test fun `the alarm target keeps its old form without a route and carries mode and route otherwise`() {
        val plain = GpsArrivalAlarmTarget("2153", "Delta", "Delta", "S1", 45.0, 9.3, 2_000, "10:18", at("10:18"), at("10:13"), at("12:18"))
        assertTrue(GpsArrivalAlarmTargetCodec.encode(plain).startsWith("gps1|"))
        val withRoute = plain.copy(route = route)
        val encoded = GpsArrivalAlarmTargetCodec.encode(withRoute)
        assertTrue(encoded.startsWith("gps2|"))
        assertEquals(withRoute, GpsArrivalAlarmTargetCodec.decode(encoded))
        val estimate = GpsArrivalAlarmTarget.estimate(route, "Delta", at("09:58"))!!
        assertEquals(estimate, GpsArrivalAlarmTargetCodec.decode(GpsArrivalAlarmTargetCodec.encode(estimate)))
        // An estimate without a usable route, or with a radius, is refused.
        assertNull(GpsArrivalAlarmTargetCodec.decode(GpsArrivalAlarmTargetCodec.encode(estimate.copy(radiusMeters = 2_000))))
        assertNull(GpsArrivalAlarmTargetCodec.decode(GpsArrivalAlarmTargetCodec.encode(estimate).replaceAfterLast("|", "")))
        // The route holds stop names, catalogue coordinates and times only.
        assertNotNull(GpsTrainRoutes.decode(GpsTrainRoutes.encode(route)))
    }

    @Test fun `the runtime publishes the progress only for the train of the running session`() {
        val target = GpsArrivalAlarmTarget.estimate(route, "Delta", at("09:58"))!!
        try {
            GpsArrivalAlarmRuntime.markActive(target, precise = true)
            GpsArrivalAlarmRuntime.updateTrack(GpsTrainTrack.waiting("9999"))
            assertNull(GpsArrivalAlarmRuntime.currentTrack())
            GpsArrivalAlarmRuntime.updateTrack(GpsTrainTrack.waiting("REG 2153"))
            assertEquals(GpsTrackStatus.WAITING, GpsArrivalAlarmRuntime.currentTrack()!!.status)
            GpsArrivalAlarmRuntime.markEnded(target, GpsArrivalAlarmEndReason.EXPIRED)
            assertNull(GpsArrivalAlarmRuntime.currentTrack())
        } finally {
            GpsArrivalAlarmRuntime.markEnded(null, GpsArrivalAlarmEndReason.CANCELLED)
            GpsArrivalAlarmRuntime.acknowledge()
        }
    }
}
