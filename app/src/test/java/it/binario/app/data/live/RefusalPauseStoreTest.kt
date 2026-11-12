package it.binario.app.data.live

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A refusal pause outlives the process: stored, capped at an hour, restored by the gate of the next process. */
class RefusalPauseStoreTest {
    private val now = Instant.parse("2026-11-12T09:00:00Z")

    @After fun clear() = SourcePauses.clearForTest()

    /** The preferences file as a map, label → epoch millis, with the same rules as the Android store. */
    private class MemoryStore : RefusalPauseStore {
        val values = mutableMapOf<String, Long>()
        override fun read(label: String, now: Instant): Instant? {
            values.entries.removeIf { RefusalPauseRules.restore(it.value, now) == null }
            return RefusalPauseRules.restore(values[label], now)
        }
        override fun write(label: String, until: Instant, now: Instant) {
            values[label] = RefusalPauseRules.encode(until, now)
        }
    }

    @Test fun `a stored pause is epoch millis, capped at an hour, gone once expired`() {
        val until = now.plusSeconds(900)
        assertEquals(until.toEpochMilli(), RefusalPauseRules.encode(until, now))
        assertEquals(now.plus(Duration.ofHours(1)).toEpochMilli(), RefusalPauseRules.encode(now.plus(Duration.ofHours(5)), now))
        assertEquals(until, RefusalPauseRules.restore(until.toEpochMilli(), now))
        // A clock set back cannot stretch the pause past an hour.
        assertEquals(now.plus(Duration.ofHours(1)), RefusalPauseRules.restore(now.plus(Duration.ofHours(3)).toEpochMilli(), now))
        assertNull(RefusalPauseRules.restore(until.toEpochMilli(), until))
        assertNull(RefusalPauseRules.restore(null, now))
        assertNull(RefusalPauseRules.restore(0L, now))
        val store = MemoryStore().apply { values["RFI"] = now.minusSeconds(1).toEpochMilli(); values["Italo"] = until.toEpochMilli() }
        assertNull(store.read("RFI", now))
        assertEquals(setOf("Italo"), store.values.keys)
    }

    @Test fun `a refusal met by one process pauses the gate of the next one without a request`() = runBlocking {
        val store = MemoryStore()
        val clock = MutablePublicSourceClock(now)
        val first = PublicSourceRefusalGate(clock, "ViaggiaTreno") { store }
        val refused = runCatching { first.call<String> { throw PublicSourceHttpException(429, "HTTP 429", Duration.ofSeconds(600)) } }.exceptionOrNull()
        assertTrue(refused is PublicSourceHttpException)
        assertEquals(now.plusSeconds(600).toEpochMilli(), store.values["ViaggiaTreno"])

        // The process dies: a new gate, a new breaker, no pause in memory.
        SourcePauses.clearForTest()
        clock.advance(Duration.ofMinutes(4))
        var requests = 0
        val next = PublicSourceRefusalGate(clock, "ViaggiaTreno") { store }
        val paused = runCatching { next.call { requests++; "ok" } }.exceptionOrNull()
        assertTrue(paused.toString(), paused is PublicSourcePausedException)
        assertEquals(0, requests)
        assertEquals(listOf("ViaggiaTreno" to now.plusSeconds(600)), SourcePauses.active(clock.instant()))
        // After the pause the source is asked again.
        clock.advance(Duration.ofMinutes(7))
        assertEquals("ok", next.call { requests++; "ok" })
        assertEquals(1, requests)
    }

    @Test fun `a 503 with Retry-After is kept, other failures and other sources are not`() = runBlocking {
        val store = MemoryStore()
        val clock = MutablePublicSourceClock(now)
        val gate = PublicSourceRefusalGate(clock, "RFI") { store }
        runCatching { gate.call<String> { throw PublicSourceHttpException(500, "HTTP 500") } }
        runCatching { gate.call<String> { throw PublicSourceHttpException(503, "HTTP 503") } }
        assertTrue(store.values.isEmpty())
        runCatching { gate.call<String> { throw PublicSourceHttpException(503, "HTTP 503", Duration.ofSeconds(120)) } }
        assertEquals(now.plusSeconds(120).toEpochMilli(), store.values["RFI"])
        // Another source's gate restores nothing.
        assertEquals("ok", PublicSourceRefusalGate(clock, "Italo") { store }.call { "ok" })
        // Without a store (JVM tests, before the app start) the gate works in memory as before.
        assertEquals("ok", PublicSourceRefusalGate(clock, "Italo") { null }.call { "ok" })
    }
}
