package it.binario.app.data.live

import it.binario.app.data.RailTime
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.AlertRequest
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardRequest
import it.binario.app.data.provider.TrainNoticeRequest
import it.binario.app.data.repository.TrainNumberQuery
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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

    /** Audit fix-android-7: the pause is of the host, not of the adapter that happened to be refused. */
    @Test fun `a refusal of the Italo board pauses the Italo number search of the same host`() = runBlocking {
        val requests = ItaloRequests(clock)
        var boardCalls = 0
        var numberCalls = 0
        val board = ItaloPublicStationAdapter(ItaloPublicStationFetcher { boardCalls++; throw PublicSourceHttpException(403, "Italo HTTP 403") }, clock,
            requests = requests)
        val number = ItaloTrainNumberAdapter({ numberCalls++; """{"IsEmpty":true}""" }, clock, requests)
        val today = clock.instant().atZone(RailTime.defaultZone).toLocalDate()

        board.getStationBoard(request("S08409", "roma-termini", "Roma Termini"))
        assertTrue(number.searchByNumber(TrainNumberQuery("9921", today)) is ProviderResult.Failure)
        assertEquals(1, boardCalls)
        assertEquals(0, numberCalls)
        assertEquals(listOf("Italo"), SourcePauses.active(clock.instant()).map { it.first })

        // After the pause the host is asked again, and the answer ends the pause for both.
        clock.advance(Duration.ofMinutes(15).plusSeconds(1))
        assertTrue(number.searchByNumber(TrainNumberQuery("9922", today)) is ProviderResult.Success)
        assertEquals(1, numberCalls)
        assertTrue(SourcePauses.active(clock.instant()).isEmpty())
    }

    /** Audit fix-android-7: the two notice clients of www.viaggiatreno.it stop with its realtime API, and the reverse. */
    @Test fun `a refusal of ViaggiaTreno pauses the notice clients of the same host`() = runBlocking {
        val requests = ViaggiaTrenoRequests(clock)
        var realtimeCalls = 0
        val realtime = ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher {
            realtimeCalls++; throw PublicSourceHttpException(429, "ViaggiaTreno HTTP 429", Duration.ofSeconds(600))
        }, clock, requests = requests)
        val fetcher = RecordingPublicSourceFetcher { "[]" }
        val notices = ViaggiaTrenoTrainNoticesAdapter(fetcher, clock, 0, requests.refusals)
        val news = TrenitaliaInfomobilityNewsAdapter(fetcher, clock, 0, requests.refusals)
        val today = clock.instant().atZone(RailTime.defaultZone).toLocalDate()

        assertTrue(realtime.getStationBoard(request("S01700")) is ProviderResult.Failure)
        repeat(2) {
            assertTrue(notices.getTrainNotices(TrainNoticeRequest("2153", today)) is ProviderResult.Failure)
            assertTrue(news.getAlerts(AlertRequest()) is ProviderResult.Failure)
        }
        assertEquals(1, realtimeCalls)
        assertTrue(fetcher.urls.toString(), fetcher.urls.isEmpty())

        // After the Retry-After the notices are asked again; a refusal there pauses the realtime API in turn.
        clock.advance(Duration.ofSeconds(601))
        assertTrue(news.getAlerts(AlertRequest()) is ProviderResult.Success)
        assertEquals(1, fetcher.urls.size)
        val refused = ViaggiaTrenoTrainNoticesAdapter(PublicSourceFetcher { throw PublicSourceHttpException(403, "HTTP 403") }, clock, 0, requests.refusals)
        assertTrue(refused.getTrainNotices(TrainNoticeRequest("2154", today)) is ProviderResult.Failure)
        assertTrue(realtime.getStationBoard(request("S01645")) is ProviderResult.Failure)
        assertEquals(1, realtimeCalls)
    }

    /** Audit fix-android-4: a request sent before the refusal and answered after it does not cancel the pause. */
    @Test fun `a late success of an earlier request does not cancel the pause`() = runBlocking {
        val gate = PublicSourceRefusalGate(clock, "ViaggiaTreno")
        val slow = CompletableDeferred<String>()
        val earlier = async(start = CoroutineStart.UNDISPATCHED) { gate.call { slow.await() } }
        val refusal = runCatching { gate.call<String> { throw PublicSourceHttpException(429, "ViaggiaTreno HTTP 429") } }.exceptionOrNull()
        assertTrue(refusal.toString(), refusal is PublicSourceHttpException)
        slow.complete("in ritardo")
        assertEquals("in ritardo", earlier.await())

        var calls = 0
        repeat(3) { assertTrue(runCatching { gate.call { calls++ } }.exceptionOrNull() is PublicSourcePausedException) }
        assertEquals(0, calls)
        assertEquals(listOf("ViaggiaTreno" to clock.instant().plus(Duration.ofMinutes(15))), SourcePauses.active(clock.instant()))

        // Once the pause is over one request goes out, and its success closes the pause.
        clock.advance(Duration.ofMinutes(15).plusSeconds(1))
        gate.call { calls++ }
        gate.call { calls++ }
        assertEquals(2, calls)
        assertTrue(SourcePauses.active(clock.instant()).isEmpty())
    }

    @Test fun `a success while the circuit is open leaves it open`() {
        val breaker = SourceCircuitBreaker(clock, pauseLabel = "RFI")
        breaker.recordFailure(Duration.ofMinutes(5))
        breaker.recordSuccess()
        assertTrue(breaker.isOpen())
        assertEquals(clock.instant().plus(Duration.ofMinutes(5)), breaker.pausedUntil())
        assertEquals(1, SourcePauses.active(clock.instant()).size)
        clock.advance(Duration.ofMinutes(5).plusSeconds(1))
        assertTrue(breaker.allowRequest())
        breaker.recordSuccess()
        assertTrue(!breaker.isOpen())
    }

    private fun request(code: String, id: String = "station:$code", name: String = code) = StationBoardRequest(
        stationId = StationId(id), stationName = name, railwayCode = code,
        from = clock.instant(), to = clock.instant().plusSeconds(3600), kind = BoardKind.DEPARTURES
    )
}
