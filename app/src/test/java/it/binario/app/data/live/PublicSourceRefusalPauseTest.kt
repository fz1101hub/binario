package it.binario.app.data.live

import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardRequest
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit android-data-5: ViaggiaTreno, the RFI monitor and Italo stop at the first 403 or 429 (and a 503 with
 * Retry-After), honour Retry-After, publish the pause, and every ViaggiaTreno instance of the app shares it.
 */
class PublicSourceRefusalPauseTest {
    private val clock = MutablePublicSourceClock(Instant.parse("2026-11-10T09:00:00Z"))

    @After fun clear() = SourcePauses.clearForTest()

    @Test fun `ViaggiaTreno sends the status and Retry-After of a refusal`() = runBlocking {
        val refused = object : HttpURLConnection(URL("https://www.viaggiatreno.it")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = 429
            override fun getHeaderField(name: String?): String? = if (name == "Retry-After") "120" else null
            override fun getInputStream(): InputStream = throw AssertionError("no body for a refusal")
        }
        val error = runCatching { HttpViaggiaTrenoRealtimeFetcher(openConnection = { refused }).fetch("partenze/S01700/now") }.exceptionOrNull()
        assertTrue(error.toString(), error is PublicSourceHttpException)
        assertEquals(429, (error as PublicSourceHttpException).status)
        assertEquals(Duration.ofSeconds(120), error.retryAfter)
    }

    @Test fun `a ViaggiaTreno refusal pauses every instance that shares the requests`() = runBlocking {
        val requests = ViaggiaTrenoRequests(clock)
        var calls = 0
        val refusing = ViaggiaTrenoRealtimeFetcher { calls++; throw PublicSourceHttpException(429, "ViaggiaTreno HTTP 429", Duration.ofSeconds(120)) }
        val board = ViaggiaTrenoRealtimeAdapter(refusing, clock, requests = requests)
        val route = ViaggiaTrenoRealtimeAdapter(refusing, clock, requests = requests)
        assertTrue(board.getStationBoard(request("S01700")) is ProviderResult.Failure)
        assertTrue(route.getStationBoard(request("S01645")) is ProviderResult.Failure)
        assertTrue(board.getStationBoard(request("S08409")) is ProviderResult.Failure)
        assertEquals(1, calls)
        assertEquals(listOf("ViaggiaTreno" to clock.instant().plusSeconds(120)), SourcePauses.active(clock.instant()))

        // After the pause one request goes out again.
        clock.advance(Duration.ofSeconds(121))
        board.getStationBoard(request("S05042"))
        assertEquals(2, calls)
    }

    @Test fun `a 403 without Retry-After pauses the RFI monitor and Italo`() = runBlocking {
        var rfiCalls = 0
        val rfi = RfiStationBoardAdapter(RfiMonitorFetcher { rfiCalls++; throw PublicSourceHttpException(403, "RFI HTTP 403") }, clock)
        repeat(3) { rfi.getStationBoard(request("S08409", "roma-termini", "Roma Termini")) }
        assertEquals(1, rfiCalls)

        var italoCalls = 0
        val italo = ItaloPublicStationAdapter(ItaloPublicStationFetcher { italoCalls++; throw PublicSourceHttpException(403, "Italo HTTP 403") }, clock)
        repeat(3) { italo.getStationBoard(request("S08409", "roma-termini", "Roma Termini")) }
        assertEquals(1, italoCalls)
        assertEquals(setOf("RFI", "Italo"), SourcePauses.active(clock.instant()).map { it.first }.toSet())

        // A plain server error is not a refusal: the source is asked again as before.
        var errors = 0
        val failing = ItaloPublicStationAdapter(ItaloPublicStationFetcher { errors++; throw PublicSourceHttpException(500, "Italo HTTP 500") }, clock,
            cacheTtlMillis = 1)
        repeat(2) {
            failing.getStationBoard(request("S08409", "roma-termini", "Roma Termini"))
            clock.advance(Duration.ofSeconds(2))
        }
        assertEquals(2, errors)
    }

    private fun request(code: String, id: String = "station:$code", name: String = code) = StationBoardRequest(
        stationId = StationId(id), stationName = name, railwayCode = code,
        from = clock.instant(), to = clock.instant().plusSeconds(3600), kind = BoardKind.DEPARTURES
    )
}
