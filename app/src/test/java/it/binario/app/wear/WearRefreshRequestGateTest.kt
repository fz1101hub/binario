package it.binario.app.wear

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
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
    fun `the refresh asked from the watch runs expedited where that needs no foreground notification`() {
        assertFalse(WearRefreshWorker.runsExpedited(26))
        assertFalse(WearRefreshWorker.runsExpedited(30))
        assertTrue(WearRefreshWorker.runsExpedited(31))
        assertTrue(WearRefreshWorker.runsExpedited(36))
    }

    @Test
    fun `the per-node limit outlives a listener instance`() {
        // Play services binds a new WearRefreshRequestListenerService for a delivery: the gate must not be per instance.
        val gates = WearRefreshRequestListenerService::class.java.declaredFields.filter { it.type == WearRefreshRequestGate::class.java }

        assertEquals(1, gates.size)
        assertTrue(Modifier.isStatic(gates.single().modifiers))
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
