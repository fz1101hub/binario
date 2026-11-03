package it.binario.app.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyQueryTest {
    @Test
    fun `quick search default uses Europe Rome despite a different clock zone`() {
        val clock = Clock.fixed(Instant.parse("2026-01-15T23:30:45Z"), ZoneId.of("Pacific/Auckland"))

        val query = JourneyQuery.now(clock)

        assertEquals(ZoneOffset.ofHours(1), RailTime.defaultZone.rules.getOffset(clock.instant()))
        assertEquals(java.time.LocalDate.of(2026, 1, 16), query.serviceDate)
        assertEquals(java.time.LocalTime.of(0, 30), query.departureTime)
    }
}
