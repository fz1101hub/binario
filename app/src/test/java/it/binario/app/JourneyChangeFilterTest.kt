package it.binario.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyChangeFilterTest {
    @Test
    fun `direct filter only accepts journeys without changes`() {
        assertTrue(JourneyChangeFilter.DIRECT.accepts(0))
        assertFalse(JourneyChangeFilter.DIRECT.accepts(1))
    }

    @Test
    fun `maximum change filters respect their limits`() {
        assertTrue(JourneyChangeFilter.UP_TO_ONE.accepts(1))
        assertFalse(JourneyChangeFilter.UP_TO_ONE.accepts(2))
        assertTrue(JourneyChangeFilter.UP_TO_TWO.accepts(2))
    }
}
