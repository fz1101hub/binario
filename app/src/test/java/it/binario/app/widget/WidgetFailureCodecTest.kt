package it.binario.app.widget

import it.binario.app.data.RouteFailureReason
import it.binario.app.uiMessage
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetFailureCodecTest {
    @Test
    fun `persisted failures keep their key and typed arguments so they render in any language`() {
        listOf(
            RouteFailureReason.Unavailable.uiMessage(),
            RouteFailureReason.ScheduleExpired(LocalDate.of(2026, 12, 13)).uiMessage(),
            RouteFailureReason.NoServiceAfter(LocalDate.of(2026, 10, 6), LocalTime.of(22, 30)).uiMessage()
        ).forEach { failure ->
            assertEquals(failure, WidgetStateStore.decodeFailure(WidgetStateStore.encodeFailure(failure)))
        }
    }

    @Test
    fun `Italian diagnostics persisted by earlier versions are dropped instead of shown`() {
        assertNull(WidgetStateStore.decodeFailure("Nessuna fonte realtime o GTFS programmata ha restituito una corsa per questa tratta."))
        assertNull(WidgetStateStore.decodeFailure(""))
    }
}
