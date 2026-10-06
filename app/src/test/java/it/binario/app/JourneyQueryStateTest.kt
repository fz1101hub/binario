package it.binario.app

import it.binario.app.data.JourneyQuery
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyQueryStateTest {
    private val current = JourneyQuery(
        serviceDate = LocalDate.of(2026, 9, 24),
        departureTime = LocalTime.of(8, 30)
    )

    @Test
    fun `route activation resets an old planned search to current railway time`() {
        val state = JourneyQueryState { current }
        state.selectPlanned(LocalDate.of(2026, 10, 10), LocalTime.of(12, 45))

        state.resetForRouteActivation()

        assertEquals(current, state.value)
    }

    @Test
    fun `planned query remains selected until an explicit route boundary`() {
        val state = JourneyQueryState { current }
        val plannedDate = LocalDate.of(2026, 10, 10)
        val plannedTime = LocalTime.of(12, 45)

        state.selectPlanned(plannedDate, plannedTime)

        assertEquals(JourneyQuery(plannedDate, plannedTime), state.value)
    }

    @Test
    fun `current query follows the clock instead of the time the state was created`() {
        var now = current
        val state = JourneyQueryState { now }

        now = JourneyQuery(LocalDate.of(2026, 9, 24), LocalTime.of(11, 10))

        assertEquals(now, state.value)
        assertTrue(state.followsCurrentTime)
    }

    @Test
    fun `planned query is not a current query until the route is reactivated`() {
        val state = JourneyQueryState { current }

        state.selectPlanned(LocalDate.of(2026, 10, 10), LocalTime.of(12, 45))
        assertFalse(state.followsCurrentTime)

        state.resetForRouteActivation()
        assertTrue(state.followsCurrentTime)
    }
}
