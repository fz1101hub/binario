package it.binario.app

import it.binario.app.data.RouteFailureReason
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteFailureMessageTest {
    @Test
    fun `actionable route failures keep their own message instead of the generic one`() {
        assertEquals(UiMessage(UiMessageKey.RAIL_DATA_UNAVAILABLE), RouteFailureReason.Unavailable.uiMessage())
        assertEquals(UiMessage(UiMessageKey.RAIL_NETWORK_UNAVAILABLE), RouteFailureReason.NetworkUnavailable.uiMessage())
        assertEquals(UiMessage(UiMessageKey.ROUTE_NOT_COVERED), RouteFailureReason.RouteNotCovered.uiMessage())
        assertEquals(
            UiMessage(UiMessageKey.ROUTE_SCHEDULE_EXPIRED, LocalDate.of(2026, 12, 13)),
            RouteFailureReason.ScheduleExpired(LocalDate.of(2026, 12, 13)).uiMessage()
        )
        assertEquals(
            UiMessage(UiMessageKey.ROUTE_NO_SERVICE_AFTER, LocalDate.of(2026, 10, 6), LocalTime.of(22, 30)),
            RouteFailureReason.NoServiceAfter(LocalDate.of(2026, 10, 6), LocalTime.of(22, 30)).uiMessage()
        )
    }
}
