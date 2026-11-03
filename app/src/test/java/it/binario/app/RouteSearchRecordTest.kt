package it.binario.app

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteSearchRecordTest {
    @Test
    fun `a planned round trip and a search from now survive the saved state`() {
        val planned = RouteSearchRecord("roma-termini", "manual:bari", LocalDateTime.of(2026, 11, 7, 7, 30), LocalDateTime.of(2026, 11, 8, 18, 30))
        assertEquals(planned, RouteSearchRecord.decode(planned.encode()))
        val now = RouteSearchRecord("napoli-centrale", "bari-centrale")
        assertEquals(now, RouteSearchRecord.decode(now.encode()))
        assertNull(now.departure)
    }

    @Test
    fun `a damaged record is ignored`() {
        assertNull(RouteSearchRecord.decode(null))
        assertNull(RouteSearchRecord.decode(""))
        assertNull(RouteSearchRecord.decode("a\u001Fb"))
        assertNull(RouteSearchRecord.decode("a\u001Fb\u001Fyesterday\u001F"))
        assertNull(RouteSearchRecord.decode("\u001Fb\u001F\u001F"))
    }
}
