package it.binario.wear.sync

import it.binario.wear.model.WearConfidence
import it.binario.wear.model.WearDataNature
import it.binario.wear.model.WearDirection
import it.binario.wear.model.WearFreshness
import it.binario.wear.model.WearOperationalState
import it.binario.wear.model.WearProvenance
import it.binario.wear.model.WearProvenanceProducer
import it.binario.wear.model.WearSnapshot
import it.binario.wear.model.WearSnapshotCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearSnapshotRuntimeTest {
    @Test
    fun `scheduled expiry clears state flow persistent cache and requests surface revocation`() {
        val clock = MutableClock(1_000L)
        val scheduler = ControlledScheduler()
        val storage = InMemoryStorage()
        var expiryNotifications = 0
        val runtime = WearSnapshotRuntime(storage, clock, scheduler) { expiryNotifications++ }

        runtime.update(snapshot(expiresAt = 1_500L))
        assertEquals("Rossa", runtime.state().value?.routeLabel)
        assertEquals(1, storage.writes)

        clock.now = 1_500L
        scheduler.runDue(clock.now)

        assertNull(runtime.state().value)
        assertNull(runtime.current())
        assertNull(storage.value)
        assertEquals(1, storage.clears)
        assertEquals(1, expiryNotifications)
    }

    @Test
    fun `accessor fails closed past expiry even before a delayed scheduler callback runs`() {
        val clock = MutableClock(10L)
        val scheduler = ControlledScheduler()
        val storage = InMemoryStorage()
        val runtime = WearSnapshotRuntime(storage, clock, scheduler)

        runtime.update(snapshot(expiresAt = 20L))
        clock.now = 21L

        // Tile/complication call this accessor, so a delayed main-looper callback cannot leak data.
        assertNull(runtime.current())
        assertNull(runtime.state().value)
        assertNull(storage.value)
        assertEquals(1, storage.clears)
    }

    @Test
    fun `new snapshot cancels earlier expiry and remains available until its own deadline`() {
        val clock = MutableClock(100L)
        val scheduler = ControlledScheduler()
        val storage = InMemoryStorage()
        val runtime = WearSnapshotRuntime(storage, clock, scheduler)

        runtime.update(snapshot(routeLabel = "Vecchia", expiresAt = 200L))
        runtime.update(snapshot(routeLabel = "Nuova", expiresAt = 400L))
        clock.now = 200L
        scheduler.runDue(clock.now)

        assertEquals("Nuova", runtime.current()?.routeLabel)
        assertEquals(0, storage.clears)

        clock.now = 400L
        scheduler.runDue(clock.now)

        assertNull(runtime.current())
        assertEquals(1, storage.clears)
    }

    @Test
    fun `an expired snapshot is told apart from nothing received until a new one arrives or sharing is revoked`() {
        val clock = MutableClock(1_000L)
        val scheduler = ControlledScheduler()
        val runtime = WearSnapshotRuntime(InMemoryStorage(), clock, scheduler)
        assertFalse(runtime.expired().value)

        runtime.update(snapshot(expiresAt = 1_500L))
        clock.now = 1_500L
        scheduler.runDue(clock.now)
        assertTrue(runtime.expired().value)

        runtime.update(snapshot(expiresAt = 3_000L))
        assertFalse(runtime.expired().value)
        clock.now = 3_000L
        assertNull(runtime.current())
        assertTrue(runtime.expired().value)

        // Sharing revoked on the phone: the watch has nothing, not an expired route.
        runtime.clear()
        assertFalse(runtime.expired().value)

        val restarted = WearSnapshotRuntime(InMemoryStorage(snapshot(expiresAt = 2_000L)), MutableClock(2_500L), ControlledScheduler())
        assertTrue(restarted.expired().value)
    }

    @Test
    fun `expired persisted value is cleared during runtime initialization`() {
        val clock = MutableClock(500L)
        val storage = InMemoryStorage(snapshot(expiresAt = 500L))
        var expiryNotifications = 0

        val runtime = WearSnapshotRuntime(storage, clock, ControlledScheduler()) { expiryNotifications++ }

        assertNull(runtime.current())
        assertNull(storage.value)
        assertEquals(1, storage.clears)
        assertEquals(1, expiryNotifications)
    }

    @Test
    fun `a Data Layer bootstrap fills an empty watch but never rolls back a newer pushed value`() {
        val clock = MutableClock(1_000L)
        val storage = InMemoryStorage()
        val runtime = WearSnapshotRuntime(storage, clock, ControlledScheduler())

        assertTrue(runtime.offer(snapshot(routeLabel = "Dal Data Layer", expiresAt = 2_000L)))
        assertEquals("Dal Data Layer", runtime.current()?.routeLabel)
        assertEquals(1, storage.writes)

        runtime.update(snapshot(routeLabel = "Spinta", expiresAt = 3_000L))
        assertFalse(runtime.offer(snapshot(routeLabel = "Più vecchia", expiresAt = 2_500L)))
        assertFalse(runtime.offer(snapshot(routeLabel = "Stessa generazione", expiresAt = 3_000L)))
        assertEquals("Spinta", runtime.current()?.routeLabel)

        assertTrue(runtime.offer(snapshot(routeLabel = "Più recente", expiresAt = 4_000L)))
        assertEquals("Più recente", runtime.state().value?.routeLabel)
    }

    @Test
    fun `a bootstrap value already past its deadline is not offered`() {
        val clock = MutableClock(5_000L)
        val storage = InMemoryStorage()
        val runtime = WearSnapshotRuntime(storage, clock, ControlledScheduler())

        assertFalse(runtime.offer(snapshot(expiresAt = 5_000L)))
        assertNull(runtime.current())
        assertEquals(0, storage.writes)
    }

    @Test
    fun `the newest valid item wins when several phones hold one`() {
        val now = 1_893_484_800_000L
        val older = WearSnapshotCodec.encode(snapshot(routeLabel = "Telefono A", expiresAt = now + 30_000L))
        val newer = WearSnapshotCodec.encode(snapshot(routeLabel = "Telefono B", expiresAt = now + 50_000L))
        val malformed = newer + ("unexpected" to "value")

        assertEquals("Telefono B", newestSnapshot(listOf(older, malformed, newer), now)?.routeLabel)
        assertEquals("Telefono A", newestSnapshot(listOf(older, malformed), now)?.routeLabel)
        assertNull(newestSnapshot(listOf(malformed), now))
        assertNull(newestSnapshot(emptyList(), now))
        assertEquals("wear://*/binario/wear/snapshot", SNAPSHOT_URI)
    }

    private fun snapshot(routeLabel: String = "Rossa", expiresAt: Long): WearSnapshot = WearSnapshot(
        routeId = "route-id",
        routeLabel = routeLabel,
        direction = WearDirection.OUTBOUND,
        provenance = WearProvenance(
            producer = WearProvenanceProducer.ANDROID_APP,
            dataNature = WearDataNature.OPERATIONAL,
            sourceLabel = "Fonte",
            observedAtEpochMillis = expiresAt - 100L,
        ),
        operationalState = WearOperationalState.OPERATIONAL,
        freshness = WearFreshness.FRESH,
        confidence = WearConfidence.OPERATOR_PRIMARY,
        generatedAtEpochMillis = expiresAt - 100L,
        expiresAtEpochMillis = expiresAt,
        ttlMillis = 100L,
        notice = "",
        services = emptyList(),
    )

    private class MutableClock(var now: Long) : WearSnapshotClock {
        override fun nowEpochMillis(): Long = now
    }

    private class InMemoryStorage(initial: WearSnapshot? = null) : WearSnapshotStorage {
        var value = initial
        var writes = 0
        var clears = 0

        override fun read(): WearSnapshot? = value

        override fun write(snapshot: WearSnapshot) {
            value = snapshot
            writes++
        }

        override fun clear() {
            value = null
            clears++
        }
    }

    private class ControlledScheduler : WearSnapshotExpiryScheduler {
        private val callbacks = mutableListOf<ScheduledCallback>()

        override fun schedule(expiresAtEpochMillis: Long, action: () -> Unit): WearSnapshotExpiryCancellation {
            val callback = ScheduledCallback(expiresAtEpochMillis, action)
            callbacks += callback
            return WearSnapshotExpiryCancellation { callback.cancelled = true }
        }

        fun runDue(now: Long) {
            while (true) {
                val next = callbacks
                    .filterNot { it.cancelled || it.executed }
                    .filter { it.expiresAtEpochMillis <= now }
                    .minByOrNull { it.expiresAtEpochMillis }
                    ?: return
                next.executed = true
                next.action()
            }
        }

        private class ScheduledCallback(
            val expiresAtEpochMillis: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
            var executed: Boolean = false,
        )
    }
}
