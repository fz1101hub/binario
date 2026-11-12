package it.binario.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRefreshCadenceTest {
    @Test fun `data never asked for or older than thirty seconds is refreshed at once`() {
        assertTrue(LiveRefreshCadence.isDue(null, 5_000))
        // Back in the foreground 25 minutes later.
        assertTrue(LiveRefreshCadence.isDue(1_000, 1_000 + 25 * 60_000))
        assertTrue(LiveRefreshCadence.isDue(1_000, 31_000))
        assertEquals(LiveRefreshCadence.INTERVAL_MILLIS, LiveRefreshCadence.waitMillis(1_000, 31_000))
        assertEquals(LiveRefreshCadence.INTERVAL_MILLIS, LiveRefreshCadence.waitMillis(null, 0))
    }

    @Test fun `a screen change within thirty seconds only waits for the rest of the interval`() {
        assertFalse(LiveRefreshCadence.isDue(1_000, 30_999))
        // Results to detail 12 seconds after the last request: the next refresh is 18 seconds away, not 30.
        assertFalse(LiveRefreshCadence.isDue(1_000, 13_000))
        assertEquals(18_000, LiveRefreshCadence.waitMillis(1_000, 13_000))
        assertEquals(1, LiveRefreshCadence.waitMillis(1_000, 30_999))
    }

    @Test fun `a clock that restarted makes the refresh due instead of waiting for ever`() {
        assertTrue(LiveRefreshCadence.isDue(900_000, 2_000))
        assertEquals(LiveRefreshCadence.INTERVAL_MILLIS, LiveRefreshCadence.waitMillis(900_000, 2_000))
    }
}
