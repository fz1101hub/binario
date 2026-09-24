package it.binario.app

import it.binario.app.data.JourneyQuery
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
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
}
