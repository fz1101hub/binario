package it.binario.app.data.live

import it.binario.app.data.*
import java.io.IOException
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ViaggiaTrenoRealtimeAdapterTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = Instant.parse("2026-10-16T08:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val query = JourneyQuery(now.atZone(zone).toLocalDate(), LocalTime.of(10, 0))
    private val route = SavedRoute("test", "Appiano–Ostiense",
        Station("curated:S08328", "Appiano Proba Petronia", "Roma", railwayCode = "S08328"),
        Station("curated:S08406", "Roma Ostiense", "Roma", railwayCode = "S08406"))
    private val runDate = query.serviceDate.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun `riconosce Ostiense intermedia per codice e mostra il ritardo pubblicato`() = runBlocking {
        val paths = mutableListOf<String>()
        val adapter = adapter(paths)
        val result = adapter.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success
        val train = result.snapshot.services.single()
        assertEquals(listOf("Appiano Proba Petronia", "Roma Trastevere", "Roma Ostiense"), train.stops.map { it.station })
        assertEquals("10:05", train.scheduledDeparture)
        assertEquals("10:11", train.expectedDeparture)
        assertEquals(6, train.officialDelayMinutes)
        assertEquals(ServiceStatus.DELAYED, train.status)
        assertTrue(train.operationalStatusVerified)
        assertEquals(setOf("viaggiatreno"), train.operationalSourceIds)
        assertEquals(now.minusSeconds(30).toEpochMilli(), train.operationalObservedAtEpochMillis)
        assertTrue(paths.first().startsWith("partenze/S08328/"))
        assertEquals("andamentoTreno/S08001/12345/$runDate", paths.last())
        assertFalse(paths.any { it.startsWith("http:") })
    }

    @Test fun `assenza o vecchia rilevazione non diventa zero minuti live`() = runBlocking {
        for (run in listOf(
            run().put("oraUltimoRilevamento", JSONObject.NULL).put("ritardo", 0),
            run().put("oraUltimoRilevamento", now.minusSeconds(600).toEpochMilli()),
            run().put("nonPartito", true),
            run().put("ritardo", JSONObject.NULL)
        )) {
            val service = (adapter(run = run).loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success).snapshot.services.single()
            assertFalse(service.operationalStatusVerified)
            assertNull(service.officialDelayMinutes)
            assertTrue(service.operationalSourceIds.isEmpty())
        }
    }

    @Test fun `corsa con stessi nomi ma codice direzione giorno o numero sbagliati viene rifiutata`() = runBlocking {
        val adapter = adapter()
        for (run in listOf(
            run().put("numeroTreno", 22222),
            run().put("idOrigine", "S09999"),
            run().put("dataPartenzaTreno", runDate - 86_400_000),
            run().apply { getJSONArray("fermate").getJSONObject(1).put("id", "S09999") },
            run().apply { getJSONArray("fermate").getJSONObject(3).put("actualFermataType", 3) }
        )) assertNull(adapter.parseRun(run, "12345", "S08001", runDate, route, Direction.OUTBOUND, query, now))
        assertNull(adapter.parseRun(run(), "12345", "S08001", runDate, route, Direction.RETURN, query, now))
    }

    @Test fun `cancellazione esplicita resta visibile anche con fermate soppresse`() = runBlocking {
        val run = run().put("tipoTreno", "ST").put("provvedimento", 1).put("oraUltimoRilevamento", JSONObject.NULL)
        run.getJSONArray("fermate").getJSONObject(3).put("actualFermataType", 3)
        val service = (adapter(run = run).loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success).snapshot.services.single()
        assertEquals(ServiceStatus.CANCELLED, service.status)
        assertTrue(service.operationalStatusVerified)
    }

    @Test fun `treno in ritardo ancora prendibile non scompare dopo l'ora teorica`() = runBlocking {
        val later = query.copy(departureTime = LocalTime.of(10, 8))
        val service = (adapter().loadRoute(route, Direction.OUTBOUND, later) as DataResult.Success).snapshot.services.single()
        assertEquals("10:05", service.scheduledDeparture)
        assertEquals("10:11", service.expectedDeparture)
        val departed = adapter(run = run().apply {
            getJSONArray("fermate").getJSONObject(1).put("partenzaReale", now.plusSeconds(360).toEpochMilli())
        }).loadRoute(route, Direction.OUTBOUND, later)
        assertTrue(departed is DataResult.Failure)
    }

    @Test fun `date future e codici mancanti non interrogano il live`() = runBlocking {
        val paths = mutableListOf<String>()
        val adapter = adapter(paths)
        assertTrue(adapter.loadRoute(route, Direction.OUTBOUND, query.copy(serviceDate = query.serviceDate.plusDays(1))) is DataResult.Failure)
        assertTrue(adapter.loadRoute(route.copy(outwardOrigin = route.outwardOrigin.copy(railwayCode = null)), Direction.OUTBOUND, query) is DataResult.Failure)
        assertTrue(paths.isEmpty())
    }

    @Test fun `richieste concorrenti riusano tabellone e dettagli entro trenta secondi`() = runBlocking {
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val adapter = adapter(paths)
        coroutineScope { List(6) { async { adapter.loadRoute(route, Direction.OUTBOUND, query) } }.awaitAll() }
        assertEquals(2, paths.size)
    }

    @Test fun `errore rete mantiene il motivo ed evita richieste continue durante cooldown`() = runBlocking {
        var requests = 0
        val adapter = ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher { requests++; throw IOException("offline") }, clock)
        repeat(2) {
            assertEquals(RouteFailureReason.NetworkUnavailable, (adapter.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Failure).reason)
        }
        assertEquals(1, requests)
    }

    private fun adapter(paths: MutableList<String> = mutableListOf(), run: JSONObject = run()) =
        ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher { path ->
            paths.add(path)
            if (path.startsWith("partenze/")) JSONArray().put(JSONObject()
                .put("numeroTreno", 12345).put("codOrigine", "S08001").put("dataPartenzaTreno", runDate)
                .put("orarioPartenza", now.plusSeconds(300).toEpochMilli()).put("ritardo", 6)).toString()
            else run.toString()
        }, clock)

    // Synthetic contract fixture, not evidence of an actual train or delay.
    private fun run() = JSONObject().put("numeroTreno", 12345).put("idOrigine", "S08001")
        .put("dataPartenzaTreno", runDate).put("categoria", "REG").put("destinazione", "Roma Tiburtina")
        .put("ritardo", 6).put("oraUltimoRilevamento", now.minusSeconds(30).toEpochMilli()).put("nonPartito", false)
        .put("fermate", JSONArray()
            .put(stop("S08001", "Viterbo", -7200))
            .put(stop("S08328", "APPIANO", 300))
            .put(stop("S08405", "Roma Trastevere", 1200))
            .put(stop("S08406", "ROMA OSTIENSE", 1800))
            .put(stop("S08217", "Roma Tiburtina", 2700)))
    private fun stop(id: String, name: String, seconds: Long) = JSONObject()
        .put("id", id).put("stazione", name).put("actualFermataType", 0)
        .put("partenza_teorica", now.plusSeconds(seconds).toEpochMilli())
        .put("arrivo_teorico", now.plusSeconds(seconds).toEpochMilli())
}
