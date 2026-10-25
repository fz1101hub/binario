package it.binario.app.data.live

import java.io.IOException
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PublicSourceInfrastructureTest {
    private val clock = MutablePublicSourceClock(Instant.parse("2026-10-25T19:00:00Z"))

    @Test fun `fetcher sends the Binario user agent over HTTPS and closes the connection`() = runBlocking {
        val opened = mutableListOf<FakePublicSourceConnection>()
        val fetcher = HttpPublicSourceFetcher(PublicSourceRequestPolicy()) { url ->
            FakePublicSourceConnection(url, 200, body = "[]".toByteArray()).also(opened::add)
        }
        assertEquals("[]", fetcher.get("https://transport.opendata.ch/v1/stationboard?id=8505300"))
        assertEquals(BINARIO_PUBLIC_SOURCE_USER_AGENT, opened.single().getRequestProperty("User-Agent"))
        assertEquals("application/json", opened.single().getRequestProperty("Accept"))
        assertTrue(opened.single().closed)
        assertFalse(opened.single().instanceFollowRedirects)
    }

    @Test fun `fetcher refuses cleartext URLs and redirects outside the allowed ViaggiaTreno hop`() = runBlocking {
        val plain = HttpPublicSourceFetcher(PublicSourceRequestPolicy()) { url -> FakePublicSourceConnection(url, 200) }
        try { plain.get("http://www.trenord.it/x"); fail("HTTP accettato") } catch (_: IOException) { }

        val requested = "https://www.viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=8829"
        for (location in listOf(
            "http://www.viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=9999",
            "http://www.viaggiatreno.it/infomobilitamobile/resteasy/news/infomobility?commercialTrainNumber=8829",
            "http://viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=8829",
            "http://www.viaggiatreno.it:8080/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=8829",
            "http://user@www.viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=8829",
            "https://example.org/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=8829"
        )) {
            val urls = mutableListOf<URL>()
            val fetcher = HttpPublicSourceFetcher(PublicSourceRequestPolicy(allowViaggiaTrenoCleartextRedirect = true)) { url ->
                urls += url
                FakePublicSourceConnection(url, 301, location)
            }
            try { fetcher.get(requested); fail("Redirect non sicuro accettato: $location") } catch (_: IOException) { }
            assertEquals(1, urls.size)
        }
        // Without the explicit opt-in even the exact ViaggiaTreno cleartext hop is refused.
        val strict = HttpPublicSourceFetcher(PublicSourceRequestPolicy()) { url ->
            FakePublicSourceConnection(url, 301, requested.replace("https://", "http://"))
        }
        try { strict.get(requested); fail("Cleartext non richiesto accettato") } catch (_: IOException) { }
    }

    @Test fun `fetcher follows only the same ViaggiaTreno resource onto cleartext`() = runBlocking {
        val requested = "https://www.viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=8829"
        val urls = mutableListOf<URL>()
        val fetcher = HttpPublicSourceFetcher(PublicSourceRequestPolicy(allowViaggiaTrenoCleartextRedirect = true)) { url ->
            urls += url
            if (url.protocol == "https") FakePublicSourceConnection(url, 301, requested.replace("https://", "http://"))
            else FakePublicSourceConnection(url, 200, body = "[]".toByteArray())
        }
        assertEquals("[]", fetcher.get(requested))
        assertEquals(listOf("https", "http"), urls.map { it.protocol })
        assertTrue(urls.all { it.host == "www.viaggiatreno.it" && it.query == "commercialTrainNumber=8829" })
    }

    @Test fun `fetcher bounds declared and streamed bodies and types HTTP failures`() = runBlocking {
        val small = PublicSourceRequestPolicy(maxBytes = 8)
        val declared = HttpPublicSourceFetcher(small) { url -> FakePublicSourceConnection(url, 200, body = ByteArray(4), declaredLength = 1_000) }
        try { declared.get("https://www.trenord.it/x"); fail("Lunghezza dichiarata oltre limite accettata") } catch (_: IOException) { }
        val streamed = HttpPublicSourceFetcher(small) { url -> FakePublicSourceConnection(url, 200, body = ByteArray(20), declaredLength = -1) }
        try { streamed.get("https://www.trenord.it/x"); fail("Corpo oltre limite accettato") } catch (_: IOException) { }
        val unavailable = HttpPublicSourceFetcher(small) { url -> FakePublicSourceConnection(url, 503) }
        try { unavailable.get("https://www.trenord.it/x"); fail("HTTP 503 accettato") } catch (error: PublicSourceHttpException) {
            assertEquals(503, error.status)
        }
    }

    @Test fun `cache coalesces concurrent misses and serves the cached value within its TTL`() = runBlocking {
        val cache = ResilientSourceCache<String>(Duration.ofMinutes(1), Duration.ofMinutes(5), clock, retryDelayMillis = 0)
        val loads = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val reads = (1..4).map {
            async(start = CoroutineStart.UNDISPATCHED) { cache.get("k") { loads.incrementAndGet(); gate.await(); "value" } }
        }
        gate.complete(Unit)
        assertTrue(reads.awaitAll().all { it is SourceRead.Fresh && it.value == "value" })
        assertEquals(1, loads.get())
        val cached = cache.get("k") { error("non deve ricaricare") }
        assertTrue(cached is SourceRead.Fresh && cached.fromCache)
    }

    @Test fun `cache retries one transient network error but never HTTP or parse failures`() = runBlocking {
        val cache = ResilientSourceCache<String>(Duration.ofMinutes(1), Duration.ofMinutes(5), clock, retryDelayMillis = 0)
        val attempts = AtomicInteger()
        val recovered = cache.get("network") { if (attempts.incrementAndGet() == 1) throw IOException("reset") else "ok" }
        assertTrue(recovered is SourceRead.Fresh)
        assertEquals(2, attempts.get())

        val httpAttempts = AtomicInteger()
        val http = cache.get("http") { httpAttempts.incrementAndGet(); throw PublicSourceHttpException(429, "HTTP 429") }
        assertEquals(1, httpAttempts.get())
        assertEquals(SourceFailureKind.HTTP, (http as SourceRead.Failed).failure.kind)

        val parseAttempts = AtomicInteger()
        val parse = cache.get("parse") { parseAttempts.incrementAndGet(); org.json.JSONObject("<html>"); "never" }
        assertEquals(1, parseAttempts.get())
        assertEquals(SourceFailureKind.PARSE, (parse as SourceRead.Failed).failure.kind)
    }

    @Test fun `cache keeps the last good value as stale after a failed refresh and then drops it`() = runBlocking {
        val cache = ResilientSourceCache<String>(Duration.ofMinutes(1), Duration.ofMinutes(5), clock, retryDelayMillis = 0)
        cache.get("k") { "first" }
        clock.advance(Duration.ofMinutes(2))
        val stale = cache.get("k") { throw IOException("down") }
        assertTrue(stale is SourceRead.Stale && stale.value == "first")
        clock.advance(Duration.ofMinutes(10))
        assertTrue(cache.get("k") { throw IOException("down") } is SourceRead.Failed)
    }

    @Test fun `circuit breaker stops requests after repeated failures and lets one trial through later`() = runBlocking {
        val breaker = SourceCircuitBreaker(clock, failureThreshold = 3, baseOpenDuration = Duration.ofMinutes(1))
        val cache = ResilientSourceCache<String>(Duration.ofMinutes(1), Duration.ZERO, clock, breaker = breaker, retryDelayMillis = 0)
        val calls = AtomicInteger()
        repeat(3) { cache.get("k") { calls.incrementAndGet(); throw PublicSourceHttpException(503, "HTTP 503") } }
        assertEquals(3, calls.get())
        assertTrue(cache.circuitOpen())
        val blocked = cache.get("k") { calls.incrementAndGet(); "never" }
        assertEquals(SourceFailureKind.CIRCUIT_OPEN, (blocked as SourceRead.Failed).failure.kind)
        assertEquals(3, calls.get())
        clock.advance(Duration.ofMinutes(2))
        assertTrue(cache.get("k") { calls.incrementAndGet(); "back" } is SourceRead.Fresh)
        assertFalse(cache.circuitOpen())
    }
}
