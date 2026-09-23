package it.binario.app.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearRefreshRequestGateTest {
    @Test
    fun `accepts only the documented path and a non-empty node`() {
        val gate = WearRefreshRequestGate(clock = { 1_000L }, minimumIntervalMillis = 15_000L)

        assertFalse(gate.accepts("/wrong", "watch-1"))
        assertFalse(gate.accepts(WearSnapshotPublisher.REFRESH_REQUEST_PATH, ""))
        assertTrue(gate.accepts(WearSnapshotPublisher.REFRESH_REQUEST_PATH, "watch-1"))
    }

    @Test
    fun `deduplicates each node within the rate limit`() {
        var now = 1_000L
        val gate = WearRefreshRequestGate(clock = { now }, minimumIntervalMillis = 15_000L)

        assertTrue(gate.accepts(WearSnapshotPublisher.REFRESH_REQUEST_PATH, "watch-1"))
        assertFalse(gate.accepts(WearSnapshotPublisher.REFRESH_REQUEST_PATH, "watch-1"))
        assertTrue(gate.accepts(WearSnapshotPublisher.REFRESH_REQUEST_PATH, "watch-2"))
        now += 15_000L
        assertTrue(gate.accepts(WearSnapshotPublisher.REFRESH_REQUEST_PATH, "watch-1"))
    }
}
