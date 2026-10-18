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
    @Test fun `a slow detail cannot discard completed verified runs or hold the whole route search`() = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        val slowCancelled = CompletableDeferred<Unit>()
        val source = ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher { path ->
            when {
                path.startsWith("partenze/") -> JSONArray().apply {
                    for (number in listOf(12345, 12346)) put(JSONObject().put("numeroTreno", number).put("codOrigine", "S08001")
                        .put("dataPartenzaTreno", runDate).put("orarioPartenza", now.plusSeconds(300).toEpochMilli()).put("ritardo", 6))
                }.toString()
                path.contains("/12346/") -> { slowStarted.complete(Unit); try { awaitCancellation() } finally { slowCancelled.complete(Unit) } }
                else -> run().toString()
            }
        }, clock, routeDetailsTimeoutMillis = 500)
        val result = withTimeout(3_000) { source.loadRoute(route, Direction.OUTBOUND, query) } as DataResult.Success
        withTimeout(2_000) { slowStarted.await(); slowCancelled.await() }
        assertEquals(listOf("12345"), result.snapshot.services.map { it.number })
        assertTrue(result.snapshot.services.single().operationalStatusVerified)
        assertEquals(6, result.snapshot.services.single().officialDelayMinutes)
    }

    @Test fun `global number lookup finds the full already departed run without any selected route`() = runBlocking {
        val paths = mutableListOf<String>()
        val source = ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher { path ->
            paths += path
            if (path.startsWith("cercaNumero")) "12345 - Viterbo|12345-S08001-$runDate\n123456 - Other|123456-S09999-$runDate"
            else run().toString()
        }, clock)
        val result = source.searchByNumber(it.binario.app.data.repository.TrainNumberQuery("R 12345", query.serviceDate)) as it.binario.app.data.provider.ProviderResult.Success
        val service = result.value.single().service
        assertEquals("Viterbo", service.origin)
        assertEquals("Roma Tiburtina", service.destination)
        assertEquals(5, service.stops.size)
        assertEquals("08:00", service.scheduledDeparture)
        assertEquals(6, service.officialDelayMinutes)
        assertTrue(service.operationalStatusVerified)
        assertEquals(listOf("cercaNumeroTrenoTrenoAutocomplete/12345", "andamentoTreno/S08001/12345/$runDate"), paths)
    }

    @Test fun `global lookup rejects wrong native identity and keeps missing observations unknown`() = runBlocking {
        for (payload in listOf(run().put("numeroTreno", 99999), run().put("dataPartenzaTreno", runDate - 86_400_000))) {
            val source = ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher { path ->
                if (path.startsWith("cercaNumero")) "12345 - Viterbo|12345-S08001-$runDate" else payload.toString()
            }, clock)
            assertTrue((source.searchByNumber(it.binario.app.data.repository.TrainNumberQuery("12345", query.serviceDate)) as it.binario.app.data.provider.ProviderResult.Success).value.isEmpty())
        }
        val source = ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher { path ->
            if (path.startsWith("cercaNumero")) "12345 - Viterbo|12345-S08001-$runDate" else run().put("oraUltimoRilevamento", JSONObject.NULL).toString()
        }, clock)
        val service = (source.searchByNumber(it.binario.app.data.repository.TrainNumberQuery("12345", query.serviceDate)) as it.binario.app.data.provider.ProviderResult.Success).value.single().service
        assertFalse(service.operationalStatusVerified)
        assertNull(service.officialDelayMinutes)
    }

    private val zone = ZoneId.of("Europe/Rome")
    private val now = Instant.parse("2026-10-16T08:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val query = JourneyQuery(now.atZone(zone).toLocalDate(), LocalTime.of(10, 0))
    private val route = SavedRoute("test", "Appiano–Ostiense",
        Station("curated:S08328", "Appiano Proba Petronia", "Roma", railwayCode = "S08328"),
        Station("curated:S08406", "Roma Ostiense", "Roma", railwayCode = "S08406"))
    private val runDate = query.serviceDate.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun `station board retains the native identity and opens the full run including past stops`() = runBlocking {
        val adapter = adapter()
        val request = it.binario.app.data.provider.StationBoardRequest(
            it.binario.app.data.core.StationId(route.outwardOrigin.id), route.outwardOrigin.name,
            railwayCode = route.outwardOrigin.railwayCode, from = now, to = now.plusSeconds(3600),
            kind = it.binario.app.data.core.BoardKind.DEPARTURES)
        val board = (adapter.getStationBoard(request) as it.binario.app.data.provider.ProviderResult.Success).value
        val entry = board.entries.single()
        assertEquals("S08001:12345:$runDate", entry.nativeRunIds[it.binario.app.data.core.SourceId("viaggiatreno")])
        val details = (adapter.getStationBoardRun(route.outwardOrigin, request.kind, entry) as it.binario.app.data.provider.ProviderResult.Success).value
        assertEquals(listOf("Viterbo", "APPIANO", "Roma Trastevere", "ROMA OSTIENSE", "Roma Tiburtina"), details.stops.map { it.station })
        assertEquals(entry.scheduledTime.plusMinutes(6), details.entry.expectedTime?.value)
        assertEquals(now.minusSeconds(30), details.observedAt)
    }

    @Test fun `arrival board uses arrival time origin label and arrival detail`() = runBlocking {
        val adapter = ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher { path ->
            if (path.startsWith("arrivi/")) JSONArray().put(JSONObject().put("numeroTreno", 12345).put("codOrigine", "S08001")
                .put("dataPartenzaTreno", runDate).put("orarioArrivo", now.plusSeconds(1800).toEpochMilli())
                .put("origine", "Viterbo").put("destinazione", "Roma Tiburtina").put("ritardo", 6)
                .put("ultimoRilev", now.minusSeconds(30).toEpochMilli()).put("binarioEffettivoArrivoDescrizione", "2")).toString()
            else run().toString()
        }, clock)
        val request = it.binario.app.data.provider.StationBoardRequest(
            it.binario.app.data.core.StationId(route.outwardDestination.id), route.outwardDestination.name,
            railwayCode = route.outwardDestination.railwayCode, from = now, to = now.plusSeconds(3600),
            kind = it.binario.app.data.core.BoardKind.ARRIVALS)
        val entry = (adapter.getStationBoard(request) as it.binario.app.data.provider.ProviderResult.Success).value.entries.single()
        assertEquals("Viterbo", entry.destinationOrOriginLabel)
        assertEquals("2", entry.platform?.value)
        assertEquals("10:30", entry.scheduledTime.toLocalTime().toString())
        val details = (adapter.getStationBoardRun(route.outwardDestination, request.kind, entry) as it.binario.app.data.provider.ProviderResult.Success).value
        assertEquals(entry.scheduledTime.plusMinutes(6), details.entry.expectedTime?.value)
    }

    @Test fun `details cannot substitute a different service date station time or public number`() = runBlocking {
        val adapter = adapter()
        val request = it.binario.app.data.provider.StationBoardRequest(it.binario.app.data.core.StationId(route.outwardOrigin.id), route.outwardOrigin.name,
            railwayCode = route.outwardOrigin.railwayCode, from = now, to = now.plusSeconds(3600), kind = it.binario.app.data.core.BoardKind.DEPARTURES)
        val entry = (adapter.getStationBoard(request) as it.binario.app.data.provider.ProviderResult.Success).value.entries.single()
        for (changed in listOf(entry.copy(publicNumber = "99999"), entry.copy(scheduledTime = entry.scheduledTime.plusDays(1)),
            entry.copy(scheduledTime = entry.scheduledTime.plusMinutes(1)))) {
            assertTrue(adapter.getStationBoardRun(route.outwardOrigin, request.kind, changed) is it.binario.app.data.provider.ProviderResult.Failure)
        }
        assertTrue(adapter.getStationBoardRun(route.outwardDestination, request.kind, entry) is it.binario.app.data.provider.ProviderResult.Failure)
        assertTrue(adapter(run = run().put("dataPartenzaTreno", runDate - 86_400_000))
            .getStationBoardRun(route.outwardOrigin, request.kind, entry) is it.binario.app.data.provider.ProviderResult.Failure)
    }

    @Test fun `missing fresh run observations do not show the cached board prediction as live in details`() = runBlocking {
        val adapter = adapter(run = run().put("oraUltimoRilevamento", now.minusSeconds(600).toEpochMilli()))
        val request = it.binario.app.data.provider.StationBoardRequest(it.binario.app.data.core.StationId(route.outwardOrigin.id), route.outwardOrigin.name,
            railwayCode = route.outwardOrigin.railwayCode, from = now, to = now.plusSeconds(3600), kind = it.binario.app.data.core.BoardKind.DEPARTURES)
        val entry = (adapter.getStationBoard(request) as it.binario.app.data.provider.ProviderResult.Success).value.entries.single()
        val details = (adapter.getStationBoardRun(route.outwardOrigin, request.kind, entry) as it.binario.app.data.provider.ProviderResult.Success).value
        assertNull(details.entry.expectedTime)
        assertNull(details.entry.status)
        assertTrue(details.stops.all { it.operationalStatusVerified == false })
    }

    @Test fun `il refresh dopo trenta secondi vede il nuovo ritardo anche con latenza del dettaglio`() = runBlocking {
        val ticks = java.util.concurrent.atomic.AtomicLong(now.toEpochMilli())
        var published = 0
        val advancingClock = object : Clock() {
            override fun getZone(): ZoneId = zone
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(ticks.get())
        }
        val adapter = ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher { path ->
            if (path.startsWith("andamentoTreno/")) {
                ticks.addAndGet(3_000)
                run().put("ritardo", published).put("oraUltimoRilevamento", ticks.get()).toString()
            } else JSONArray().put(JSONObject().put("numeroTreno", 12345).put("codOrigine", "S08001")
                .put("dataPartenzaTreno", runDate).put("orarioPartenza", now.plusSeconds(300).toEpochMilli()).put("ritardo", published)).toString()
        }, advancingClock)
        assertEquals(0, (adapter.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success).snapshot.services.single().delayMinutes)
        ticks.set(now.plusSeconds(30).toEpochMilli())
        published = 6
        assertEquals(6, (adapter.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success).snapshot.services.single().delayMinutes)
    }

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
        assertTrue(java.net.URLDecoder.decode(paths.first(), "UTF-8").contains("10:00:00"))
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
        assertEquals(3, paths.size)
    }

    @Test fun `tabellone richiesto all'ora scelta e lookback facoltativo non cancella il risultato`() = runBlocking {
        val paths = mutableListOf<String>()
        val adapter = ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher { path ->
            paths.add(path)
            when {
                path.startsWith("andamentoTreno/") -> run().toString()
                java.net.URLDecoder.decode(path, "UTF-8").contains("10:00:00") -> JSONArray().put(JSONObject()
                    .put("numeroTreno", 12345).put("codOrigine", "S08001").put("dataPartenzaTreno", runDate)
                    .put("orarioPartenza", now.plusSeconds(300).toEpochMilli()).put("ritardo", 6)).toString()
                else -> throw IOException("lookback offline")
            }
        }, clock)
        assertTrue(adapter.loadRoute(route, Direction.OUTBOUND, query) is DataResult.Success)
        assertTrue(java.net.URLDecoder.decode(paths.first(), "UTF-8").contains("10:00:00"))
        val futurePaths = mutableListOf<String>()
        adapter(futurePaths).loadRoute(route, Direction.OUTBOUND, query.copy(departureTime = LocalTime.of(13, 0)))
        assertEquals(1, futurePaths.size)
        assertTrue(java.net.URLDecoder.decode(futurePaths.single(), "UTF-8").contains("13:00:00"))
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
