package it.binario.app.data.live

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.provider.ProviderResult
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The STA and SNCF Trip Updates stop at the first 403 or 429 of their host, also without Retry-After: before, the
 * failure memory of RealtimeFeedGuard let two more requests through (one every 30 s) before its circuit opened. The
 * SNCF and Trenitalia France feeds share the pause of their host.
 */
class GtfsRealtimeRefusalPauseTest {
    private val clock = MutablePublicSourceClock(Instant.parse("2026-11-23T15:00:00Z"))

    @After fun clear() = SourcePauses.clearForTest()

    @Test fun `SNCF non richiede di nuovo dopo un 403 senza Retry-After`() = runBlocking {
        var calls = 0
        val adapter = SncfGtfsRealtimeAdapter(fetcher = { calls++; throw PublicSourceHttpException(403, "HTTP 403") }, clock = clock)
        repeat(5) {
            assertTrue(adapter.getTripUpdates() is ProviderResult.Failure)
            clock.advance(Duration.ofSeconds(31))
        }
        assertEquals(1, calls)
    }

    @Test fun `STA non richiede di nuovo dopo un 429 senza Retry-After`() = runBlocking {
        var calls = 0
        val adapter = StaRealtimeAdapter(fetcher = { calls++; throw PublicSourceHttpException(429, "HTTP 429") }, clock = clock)
        repeat(5) {
            assertTrue(adapter.getTripUpdates() is ProviderResult.Failure)
            clock.advance(Duration.ofSeconds(31))
        }
        assertEquals(1, calls)
        assertTrue(SourcePauses.active(clock.instant()).any { it.first == "Open Data Hub Alto Adige" })
    }

    @Test fun `un rifiuto del feed SNCF ferma anche Trenitalia France sullo stesso host`() = runBlocking {
        val requests = TransportDataGouvRequests(clock)
        var franceCalls = 0
        val sncf = SncfGtfsRealtimeAdapter(fetcher = { throw PublicSourceHttpException(429, "HTTP 429") }, clock = clock, requests = requests)
        val empty = GtfsRealtime.FeedMessage.newBuilder().setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0")).build().toByteArray()
        val france = TrenitaliaFranceGtfsRealtimeAdapter(fetcher = { franceCalls++; empty }, clock = clock, requests = requests)
        assertTrue(sncf.getTripUpdates() is ProviderResult.Failure)
        assertTrue(france.getTripUpdates() is ProviderResult.Failure)
        assertEquals(0, franceCalls)
        // Once the pause is over (15 min without Retry-After) the feed is asked again.
        clock.advance(Duration.ofMinutes(16))
        assertTrue(france.getTripUpdates() is ProviderResult.Success)
        assertEquals(1, franceCalls)
    }

    @Test fun `un errore che non e un rifiuto resta alla memoria del guasto come prima`() = runBlocking {
        var calls = 0
        val adapter = SncfGtfsRealtimeAdapter(fetcher = { calls++; throw PublicSourceHttpException(500, "HTTP 500") }, clock = clock)
        repeat(3) {
            adapter.getTripUpdates()
            clock.advance(Duration.ofSeconds(31))
        }
        assertEquals(3, calls)
    }
}
