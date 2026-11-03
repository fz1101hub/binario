package it.binario.app.data.live

import java.net.URL
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit proposal 3 and F3: per-host breakers that honour `Retry-After`, and a short memory of failures. */
class SourcePausesTest {
    private val clock = MutablePublicSourceClock(Instant.parse("2026-11-03T09:00:00Z"))

    @After fun clear() = SourcePauses.clearForTest()

    @Test fun `Retry-After is read as seconds or as an HTTP date`() {
        val fixed = Clock.fixed(Instant.parse("2026-11-03T09:00:00Z"), ZoneOffset.UTC)
        assertEquals(Duration.ofSeconds(120), retryAfterOf("120", fixed))
        assertEquals(Duration.ofMinutes(5), retryAfterOf("Tue, 03 Nov 2026 09:05:00 GMT", fixed))
        assertNull(retryAfterOf("Tue, 03 Nov 2026 08:55:00 GMT", fixed))
        assertNull(retryAfterOf("0", fixed))
        assertNull(retryAfterOf("soon", fixed))
        assertNull(retryAfterOf(null, fixed))
    }

    @Test fun `the fetcher passes the Retry-After of a 429 on`() = runBlocking {
        val fetcher = HttpPublicSourceFetcher { url ->
            object : java.net.HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 429
                override fun getHeaderField(name: String?): String? = if (name == "Retry-After") "600" else null
            }
        }
        try { fetcher.get("https://transport.opendata.ch/v1/stationboard?id=8505000"); error("429 accettato") }
        catch (error: PublicSourceHttpException) {
            assertEquals(429, error.status)
            assertEquals(Duration.ofSeconds(600), error.retryAfter)
        }
    }

    @Test fun `a Retry-After pauses the source at once until the time it asked for`() = runBlocking {
        val breaker = SourceCircuitBreaker(clock, pauseLabel = "transport.opendata.ch")
        val cache = ResilientSourceCache<String>(Duration.ofMinutes(1), Duration.ZERO, clock, breaker = breaker, retryDelayMillis = 0)
        val calls = AtomicInteger()
        cache.get("k") { calls.incrementAndGet(); throw PublicSourceHttpException(429, "HTTP 429", Duration.ofMinutes(10)) }
        val blocked = cache.get("k") { calls.incrementAndGet(); "never" } as SourceRead.Failed
        assertEquals(1, calls.get())
        assertEquals(SourceFailureKind.CIRCUIT_OPEN, blocked.failure.kind)
        assertEquals(Instant.parse("2026-11-03T09:10:00Z"), blocked.failure.pausedUntil)
        assertEquals(listOf("transport.opendata.ch" to Instant.parse("2026-11-03T09:10:00Z")), SourcePauses.active(clock.instant()))
        clock.advance(Duration.ofMinutes(11))
        assertTrue(cache.get("k") { calls.incrementAndGet(); "back" } is SourceRead.Fresh)
        assertTrue(SourcePauses.active(clock.instant()).isEmpty())
    }

    @Test fun `each host has its own breaker`() = runBlocking {
        val cache = ResilientSourceCache<String>(Duration.ofMinutes(1), Duration.ZERO, clock, retryDelayMillis = 0, breakerPerHost = true)
        repeat(3) { cache.get("https://mds.bird.co/gbfs/$it.json") { throw PublicSourceHttpException(503, "HTTP 503") } }
        val bird = cache.get("https://mds.bird.co/gbfs/other.json") { "never" } as SourceRead.Failed
        assertEquals(SourceFailureKind.CIRCUIT_OPEN, bird.failure.kind)
        assertTrue(cache.get("https://gbfs.urbansharing.com/bikemi.com/gbfs.json") { "bikemi" } is SourceRead.Fresh)
    }

    @Test fun `a failure is answered from memory for its cooldown`() = runBlocking {
        val cache = ResilientSourceCache<String>(Duration.ofMinutes(1), Duration.ZERO, clock, retryDelayMillis = 0, failureTtl = Duration.ofSeconds(30))
        val calls = AtomicInteger()
        cache.get("k") { calls.incrementAndGet(); throw PublicSourceHttpException(500, "HTTP 500") }
        assertTrue(cache.get("k") { calls.incrementAndGet(); "never" } is SourceRead.Failed)
        assertEquals(1, calls.get())
        clock.advance(Duration.ofSeconds(31))
        assertTrue(cache.get("k") { calls.incrementAndGet(); "ok" } is SourceRead.Fresh)
        assertEquals(2, calls.get())
    }

    @Test fun `a realtime feed guard remembers a failure briefly`() {
        val guard = RealtimeFeedGuard(clock, "SNCF Open Data", Duration.ofSeconds(30))
        assertNull(guard.blocked())
        guard.failed(it.binario.app.data.provider.ProviderError(it.binario.app.data.provider.ProviderErrorKind.NETWORK, "down"), java.io.IOException("x"))
        assertTrue(guard.blocked() != null)
        clock.advance(Duration.ofSeconds(31))
        assertNull(guard.blocked())
    }
}
