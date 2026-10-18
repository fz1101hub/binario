package it.binario.app.data.planned

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedSingleFlightCacheTest {
    @Test fun `abandoned opt-in load cancels and cannot remove the replacement flight`() = runBlocking {
        val cache = BoundedSingleFlightCache<String>(2, 1_000L) { 100L }
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("run", cancelWhenUnused = true) {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); finishCleanup.await() }
                }
            }
        }
        entered.await()
        old.cancelAndJoin()
        withTimeout(2_000) { cleaning.await() }
        val newEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fresh = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("run", cancelWhenUnused = true) { newEntered.complete(Unit); release.await(); "fresh" }
        }
        newEntered.await()
        finishCleanup.complete(Unit)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("run", cancelWhenUnused = true) { error("replacement must remain shared") }
        }
        release.complete(Unit)
        assertEquals("fresh", withTimeout(2_000) { fresh.await().value })
        assertEquals("fresh", waiter.await().value)
    }

    @Test fun `opt-in shared request stays alive while another consumer still needs it`() = runBlocking {
        val cache = BoundedSingleFlightCache<String>(2, 1_000L) { 100L }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("run", cancelWhenUnused = true) { entered.complete(Unit); release.await(); "live" }
        }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("run", cancelWhenUnused = true) { error("shared load expected") }
        }
        first.cancelAndJoin()
        release.complete(Unit)
        assertEquals("live", withTimeout(2_000) { second.await().value })
    }

    @Test
    fun `concurrent misses for one key execute one loader`() = runBlocking {
        var now = 100L
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = BoundedSingleFlightCache<String>(4, 1_000L) { now }

        val results = List(10) {
            async(start = CoroutineStart.UNDISPATCHED) {
                cache.getOrLoad("roma") {
                    calls += 1
                    entered.complete(Unit)
                    release.await()
                    "orario"
                }
            }
        }
        entered.await()
        assertEquals(1, calls)
        release.complete(Unit)

        assertEquals(List(10) { "orario" }, results.awaitAll().map { it.value })
        assertEquals(1, calls)
    }

    @Test
    fun `cache hit preserves acquisition timestamps and expiry reloads`() = runBlocking {
        var now = 100L
        var calls = 0
        val cache = BoundedSingleFlightCache<String>(2, 50L) { now }

        val first = cache.getOrLoad("roma") {
            calls += 1
            now = 110L
            "prima"
        }
        now = 159L
        val hit = cache.getOrLoad("roma") { error("cache hit atteso") }
        now = 160L
        val refreshed = cache.getOrLoad("roma") {
            calls += 1
            now = 170L
            "seconda"
        }

        assertEquals(110L, first.fetchedAtEpochMillis)
        assertEquals(160L, first.expiresAtEpochMillis)
        assertEquals(first, hit)
        assertEquals(170L, refreshed.fetchedAtEpochMillis)
        assertEquals(220L, refreshed.expiresAtEpochMillis)
        assertEquals(2, calls)
    }

    @Test
    fun `cancelling first consumer does not cancel shared load`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = BoundedSingleFlightCache<String>(2, 1_000L) { 100L }
        var calls = 0

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("roma") {
                calls += 1
                entered.complete(Unit)
                release.await()
                "orario"
            }
        }
        entered.await()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("roma") { error("secondo loader non atteso") }
        }

        first.cancelAndJoin()
        release.complete(Unit)

        assertEquals("orario", waiter.await().value)
        assertEquals(1, calls)
    }

    @Test
    fun `cache evicts oldest values above its bound`() = runBlocking {
        var now = 1L
        val cache = BoundedSingleFlightCache<String>(2, 1_000L) { now }

        cache.getOrLoad("a") { "a" }
        now += 1
        cache.getOrLoad("b") { "b" }
        now += 1
        cache.getOrLoad("c") { "c" }

        assertEquals(2, cache.sizeForTest())
    }
}
