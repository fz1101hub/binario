package it.binario.app.maps

import it.binario.app.maps.MapViewCall.DESTROY
import it.binario.app.maps.MapViewCall.PAUSE
import it.binario.app.maps.MapViewCall.RESUME
import it.binario.app.maps.MapViewCall.START
import it.binario.app.maps.MapViewCall.STOP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapViewLifecycleTrackerTest {
    @Test
    fun `joining a resumed host starts then resumes the map`() {
        val tracker = MapViewLifecycleTracker()

        // LifecycleRegistry replays ON_CREATE, ON_START, ON_RESUME to a new observer.
        val calls = listOf(MapHostState.CREATED, MapHostState.STARTED, MapHostState.RESUMED).flatMap(tracker::moveTo)

        assertEquals(listOf(START, RESUME), calls)
        assertEquals(MapHostState.RESUMED, tracker.state)
    }

    @Test
    fun `background and foreground keep the calls balanced`() {
        val tracker = MapViewLifecycleTracker()
        tracker.moveTo(MapHostState.RESUMED)

        assertEquals(listOf(PAUSE), tracker.moveTo(MapHostState.STARTED))
        assertEquals(listOf(STOP), tracker.moveTo(MapHostState.CREATED))
        assertEquals(emptyList<MapViewCall>(), tracker.moveTo(MapHostState.CREATED))
        assertEquals(listOf(START, RESUME), tracker.moveTo(MapHostState.RESUMED))
        assertEquals(listOf(PAUSE, STOP), tracker.moveTo(MapHostState.CREATED))
    }

    @Test
    fun `leaving the composition while resumed pauses, stops and destroys once`() {
        val tracker = MapViewLifecycleTracker()
        tracker.moveTo(MapHostState.RESUMED)

        assertEquals(listOf(PAUSE, STOP, DESTROY), tracker.dispose())
        assertEquals(emptyList<MapViewCall>(), tracker.dispose())
        assertEquals(emptyList<MapViewCall>(), tracker.moveTo(MapHostState.RESUMED))
    }

    @Test
    fun `an activity destroyed before the composition is disposed destroys the map only once`() {
        val tracker = MapViewLifecycleTracker()
        tracker.moveTo(MapHostState.RESUMED)
        tracker.moveTo(MapHostState.STARTED)
        tracker.moveTo(MapHostState.CREATED)

        assertEquals(listOf(DESTROY), tracker.moveTo(MapHostState.DESTROYED))
        assertEquals(emptyList<MapViewCall>(), tracker.dispose())
    }

    @Test
    fun `a map disposed before it ever started is still destroyed`() {
        assertEquals(listOf(DESTROY), MapViewLifecycleTracker().dispose())
    }

    @Test
    fun `memory is released only when the process is in the background`() {
        assertFalse(MapViewLifecycleTracker.shouldReleaseMemory(20))
        assertTrue(MapViewLifecycleTracker.shouldReleaseMemory(40))
        assertTrue(MapViewLifecycleTracker.shouldReleaseMemory(80))
    }
}
