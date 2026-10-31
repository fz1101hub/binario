package it.binario.app.ui

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.maps.RouteMapPlanner
import it.binario.app.ui.common.presentedAsDemonstration
import it.binario.app.ui.detail.StopTimelinePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DeparturePresentationTest {
    private val stops = listOf(
        TrainStop("Roma Termini", "08:00", "08:06", "4", 6, completed = true),
        TrainStop("Latina", "08:40", "08:46", "2", 6, completed = true),
        TrainStop("Formia", "09:20", "09:26", "1", 6, completed = false),
        TrainStop("Napoli Centrale", "10:00", "10:06", "12", 6, completed = false)
    )
    private val service = TrainService(
        "demo-1", "2345", RailOperator.OTHER, TrainCategory.REGIONALE, "Roma Termini", "Napoli Centrale",
        "08:00", "08:06", "4", 6, ServiceStatus.DELAYED, stops, operationalStatusVerified = true
    )

    @Test
    fun `demonstration data keeps the timetable but never presents delays platforms or progress as real`() {
        val shown = service.presentedAsDemonstration()

        assertEquals("08:00", shown.scheduledDeparture)
        assertEquals("08:00", shown.expectedDeparture)
        assertNull(shown.platform)
        assertEquals(0, shown.delayMinutes)
        assertNull(shown.officialDelayMinutes)
        assertFalse(shown.operationalStatusVerified)
        assertEquals(service.stops.map { it.station }, shown.stops.map { it.station })
        shown.stops.forEach { stop ->
            assertEquals(stop.scheduled, stop.expected)
            assertNull(stop.platform)
            assertEquals(0, stop.delayMinutes)
            assertFalse(stop.completed)
            assertEquals(false, stop.operationalStatusVerified)
        }
        assertEquals(
            List(stops.size) { StopTimelinePosition.UPCOMING },
            shown.stops.indices.map { StopTimelinePosition.of(it, RouteMapPlanner.progress(shown.stops), shown.stops[it].completed) }
        )
    }

    @Test
    fun `timeline names passed, last confirmed and next stops from confirmed passages`() {
        val progress = RouteMapPlanner.progress(stops)

        assertEquals(
            listOf(StopTimelinePosition.PASSED, StopTimelinePosition.LAST_CONFIRMED, StopTimelinePosition.NEXT, StopTimelinePosition.UPCOMING),
            stops.indices.map { StopTimelinePosition.of(it, progress, stops[it].completed) }
        )
    }

    @Test
    fun `without a confirmed passage no stop is marked as reached or next`() {
        val scheduled = stops.map { it.copy(completed = false) }
        val progress = RouteMapPlanner.progress(scheduled)

        assertEquals(List(scheduled.size) { StopTimelinePosition.UPCOMING }, scheduled.indices.map { StopTimelinePosition.of(it, progress, false) })
    }

    @Test
    fun `a skipped stop is never the next stop`() {
        val withSkip = stops.mapIndexed { index, stop -> if (index == 2) stop.copy(routeChange = StopRouteChange.SKIPPED) else stop }
        val progress = RouteMapPlanner.progress(withSkip)

        assertEquals(StopTimelinePosition.UPCOMING, StopTimelinePosition.of(2, progress, false))
        assertEquals(StopTimelinePosition.NEXT, StopTimelinePosition.of(3, progress, false))
    }
}
