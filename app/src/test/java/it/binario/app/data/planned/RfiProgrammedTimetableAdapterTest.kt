package it.binario.app.data.planned

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteFailureReason
import it.binario.app.data.RailDataRepository
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.repository.QualityTieredTrainDataSource
import it.binario.app.data.repository.StationBoardEnrichedTrainDataSource
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException

class RfiProgrammedTimetableAdapterTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-04T06:00:00Z"), ZoneOffset.UTC)
    private val roma = Station("roma-termini", "Roma Termini", "Roma")
    private val milano = Station("milano-centrale", "Milano Centrale", "Milano")
    private val firenze = Station("firenze-smn", "Firenze S.M.N.", "Firenze", aliases = listOf("Firenze Santa Maria Novella"))
    private val napoli = Station("napoli-centrale", "Napoli Centrale", "Napoli")

    @Test
    fun `lettura HTML RFI limita sia Content Length sia byte effettivi`() {
        assertEquals("1234", readBoundedRfiHtml(ByteArrayInputStream("1234".toByteArray()), 4, maxBytes = 4))
        assertThrows(IOException::class.java) {
            readBoundedRfiHtml(ByteArrayInputStream("12345".toByteArray()), 5, maxBytes = 4)
        }
        assertThrows(IOException::class.java) {
            readBoundedRfiHtml(ByteArrayInputStream("12345".toByteArray()), null, maxBytes = 4)
        }
    }

    @Test
    fun `Roma Milano include Frecciarossa e Italo con fermate programmate`() = runBlocking {
        val result = adapter().loadRoute(
            route(roma, milano),
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Success)
        val snapshot = (result as DataResult.Success).snapshot
        assertEquals(DataConfidence.INFRASTRUCTURE_PRIMARY, snapshot.confidence)
        assertEquals(listOf("9611", "9923"), snapshot.services.map { it.number })

        val freccia = snapshot.services.first { it.number == "9611" }
        assertEquals(RailOperator.TRENITALIA_AV, freccia.operator)
        assertEquals(TrainCategory.FRECCE, freccia.trainCategory)
        assertFalse(freccia.operationalStatusVerified)
        assertEquals(
            listOf("Roma Termini", "Roma Tiburtina", "Firenze S.M.N.", "Bologna Centrale", "Milano Centrale"),
            freccia.stops.map { it.station }
        )

        val italo = snapshot.services.first { it.number == "9923" }
        assertEquals(RailOperator.ITALO, italo.operator)
        assertEquals(TrainCategory.AV_ITALO, italo.trainCategory)
    }

    @Test
    fun `Roma Firenze usa la fermata intermedia e tronca il percorso alla destinazione richiesta`() = runBlocking {
        val result = adapter().loadRoute(
            route(roma, firenze),
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Success)
        val services = (result as DataResult.Success).snapshot.services
        assertEquals(listOf("9611", "9923"), services.map { it.number })
        services.forEach { service ->
            assertEquals("Firenze S.M.N.", service.stops.last().station)
            assertFalse(service.stops.any { it.station == "Milano Centrale" })
        }
    }

    @Test
    fun `ora richiesta esclude le partenze precedenti`() = runBlocking {
        val result = adapter().loadRoute(
            route(roma, milano),
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(9, 0))
        )

        assertTrue(result is DataResult.Success)
        // The 08:20 that already left is never today's train: it follows as the next day's departure.
        val services = (result as DataResult.Success).snapshot.services
        assertEquals(listOf("9923", "9611"), services.map { it.number })
        assertEquals(
            listOf(LocalDate.of(2026, 9, 4).atTime(9, 30), LocalDate.of(2026, 9, 5).atTime(8, 20)),
            services.map { it.scheduledDepartureDateTime }
        )
    }

    @Test
    fun `validita del quadro impedisce uso fuori periodo`() = runBlocking {
        val result = adapter().loadRoute(
            route(roma, milano),
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2027, 1, 10), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Failure)
        assertTrue((result as DataResult.Failure).message.contains("valido dal"))
    }

    @Test
    fun `stazione non mappata non effettua richieste di rete`() = runBlocking {
        var calls = 0
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { calls += 1; fixture() },
            clock = clock
        )
        val unknown = Station("stazione-ignota", "Stazione ignota", "Italia")

        val result = adapter.loadRoute(
            route(unknown, Station("altra-ignota", "Altra ignota", "Italia")),
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Failure)
        assertEquals(0, calls)
    }

    @Test
    fun `destinazione hub consulta arrivi senza inventare la partenza di una stazione assente`() = runBlocking {
        var calls = 0
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { calls += 1; fixture() },
            clock = clock
        )
        val unknown = Station("stazione-ignota", "Stazione ignota", "Italia")

        val result = adapter.loadRoute(
            route(unknown, milano),
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Failure)
        assertEquals(RouteFailureReason.RouteNotCovered, (result as DataResult.Failure).reason)
        assertEquals(1, calls)
    }

    private val appiano = Station(
        "curated:S08328", "Appiano Proba Petronia", "Roma", railwayCode = "S08328", aliases = listOf("APPIANO")
    )
    private val ostiense = Station("curated:S08406", "Roma Ostiense", "Roma", railwayCode = "S08406")

    @Test
    fun `Appiano Ostiense usa la partenza pubblicata nelle fermate precedenti degli arrivi`() = runBlocking {
        val urls = mutableListOf<String>()
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { url -> urls += url; arrivalsFixture() }, clock = clock
        )
        val query = JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        val result = adapter.loadRoute(route(appiano, ostiense), Direction.OUTBOUND, query) as DataResult.Success
        val service = result.snapshot.services.single()

        assertTrue(urls.single().contains("QO_Arrivi_SiPMR.aspx?Id=2211&"))
        assertEquals("08:12", service.scheduledDeparture)
        assertEquals(query.serviceDate.atTime(8, 12), service.scheduledDepartureDateTime)
        assertEquals(listOf(appiano.name, "Valle Aurelia", "Roma S.Pietro", "Roma Trastevere", ostiense.name), service.stops.map { it.station })
        assertEquals("08:30", service.stops.last().scheduled)
        assertEquals(ostiense.name, service.destination)
        assertFalse(service.operationalStatusVerified)
        assertEquals(null, service.platform)
        assertEquals(null, service.officialDelayMinutes)

        // After the day's only train the same row is offered as the next day's 08:12, from the cached board.
        val later = adapter.loadRoute(route(appiano, ostiense), Direction.OUTBOUND, query.copy(departureTime = LocalTime.of(8, 13)))
        assertEquals(query.serviceDate.plusDays(1).atTime(8, 12), (later as DataResult.Success).snapshot.services.single().scheduledDepartureDateTime)
        assertEquals(1, urls.size)
    }

    @Test
    fun `Appiano Ostiense rispetta direzione inversa e non scambia partenze per arrivi`() = runBlocking {
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { url ->
                assertTrue(url.contains("QO_Arrivi_SiPMR.aspx?Id=2211&"))
                arrivalsFixture()
            }, clock = clock
        )
        assertTrue(adapter.loadRoute(route(ostiense, appiano), Direction.RETURN,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))) is DataResult.Success)
        val wrongBoard = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { fixture() }, clock = clock
        )
        assertTrue(wrongBoard.loadRoute(route(appiano, ostiense), Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))) is DataResult.Failure)
    }

    @Test
    fun `arrivi rispettano periodicita e validita del quadro`() = runBlocking {
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { arrivalsFixture(periodicity = "feriale") }, clock = clock
        )
        val route = route(appiano, ostiense)
        assertTrue(adapter.loadRoute(route, Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 6), LocalTime.of(8, 0))) is DataResult.Failure)
        for (date in listOf(LocalDate.of(2026, 1, 10), LocalDate.of(2027, 1, 10))) {
            val outside = adapter.loadRoute(route, Direction.OUTBOUND,
                JourneyQuery(date, LocalTime.of(8, 0))) as DataResult.Failure
            assertEquals(RouteFailureReason.ScheduleOutsideValidity(LocalDate.of(2026, 6, 14), LocalDate.of(2026, 12, 12)), outside.reason)
        }
    }

    @Test
    fun `ricerca Appiano Ostiense attraversa il repository reale senza pack Lazio o fonti live`() = runBlocking {
        val fetcher = RfiProgrammedTimetableFetcher { arrivalsFixture() }
        val repository = RailDataRepository(
            authorisedSources = listOf(
                RfiProgrammedTimetableAdapter(fetcher = fetcher, clock = clock),
                RfiSupplementalHubTimetableAdapter(fetcher = fetcher, clock = clock)
            ),
            fallback = EmbeddedGtfsRouteAdapter(openAsset = { File("src/main/assets", it).inputStream() })
        )
        val source = StationBoardEnrichedTrainDataSource(
            plannedSource = QualityTieredTrainDataSource(repository, emptyList()),
            boardProviders = emptyList(), clock = clock
        )
        val query = JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        val result = source.loadRoute(route(appiano, ostiense), Direction.OUTBOUND, query) as DataResult.Success
        assertEquals("08:12", result.snapshot.services.single().scheduledDeparture)
        val lateQuery = query.copy(departureTime = LocalTime.of(9, 0))
        val late = source.loadRoute(route(appiano, ostiense), Direction.OUTBOUND, lateQuery) as DataResult.Success
        assertEquals(query.serviceDate.plusDays(1).atTime(8, 12), late.snapshot.services.single().scheduledDepartureDateTime)
    }

    @Test
    fun `arrivi senza ora pubblicata o con tempi di arrivo intermedi non inventano partenze`() = runBlocking {
        for (html in listOf(
            arrivalsFixture().replace("<td>08.30</td>", "<td>—</td>"),
            arrivalsFixture().replace("Appiano (08.12)", "Appiano (25.99)"),
            arrivalsFixture().replace("Fermate precedenti (orario di partenza)", "Fermate precedenti (orario di arrivo)")
        )) {
            val adapter = RfiProgrammedTimetableAdapter(
                fetcher = RfiProgrammedTimetableFetcher { html }, clock = clock
            )
            assertTrue(adapter.loadRoute(route(appiano, ostiense), Direction.OUTBOUND,
                JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))) is DataResult.Failure)
        }
    }

    @Test
    fun `timeout degli arrivi resta distinto da tratta non coperta e un nuovo tentativo puo riuscire`() = runBlocking {
        var calls = 0
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher {
                if (++calls == 1) throw java.net.SocketTimeoutException("timeout")
                arrivalsFixture()
            }, clock = clock
        )
        val query = JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        val first = adapter.loadRoute(route(appiano, ostiense), Direction.OUTBOUND, query) as DataResult.Failure
        assertEquals(RouteFailureReason.NetworkUnavailable, first.reason)
        assertTrue(adapter.loadRoute(route(appiano, ostiense), Direction.OUTBOUND, query) is DataResult.Success)
        assertEquals(2, calls)
    }

    @Test
    fun `cambiare origine mantenendo l'id della tratta non riusa la fermata di salita precedente`() = runBlocking {
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { arrivalsFixture() }, clock = clock
        )
        val query = JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        val original = route(appiano, ostiense)
        val first = adapter.loadRoute(original, Direction.OUTBOUND, query) as DataResult.Success
        assertEquals("08:12", first.snapshot.services.single().scheduledDeparture)
        val changed = original.copy(outwardOrigin = Station("valle-aurelia", "Valle Aurelia", "Roma"))
        val second = adapter.loadRoute(changed, Direction.OUTBOUND, query) as DataResult.Success
        assertEquals("08:15", second.snapshot.services.single().scheduledDeparture)
        assertEquals("Valle Aurelia", second.snapshot.services.single().stops.first().station)
        assertFalse(second.snapshot.services.single().stops.any { it.station == appiano.name })
    }

    @Test
    fun `arrivi riconoscono intestazioni in celle td e hub supplementari`() = runBlocking {
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { url ->
                assertTrue(url.contains("QO_Arrivi_SiPMR.aspx?Id=408&"))
                arrivalsFixture().replace("<th>", "<td>").replace("</th>", "</td>")
            }, clock = clock
        )
        // Only verifies parsing/orchestration with synthetic data, not a real Appiano–Ancona train.
        val ancona = Station("curated:S07113", "Ancona", "Ancona", railwayCode = "S07113")
        val result = adapter.loadRoute(route(appiano, ancona), Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))) as DataResult.Success
        assertEquals(ancona.name, result.snapshot.services.single().stops.last().station)
    }

    @Test
    fun `ricerca serale filtra gli arrivi con l'ora di partenza e il giorno effettivo`() = runBlocking {
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher {
                arrivalsFixture(periodicity = "festivo").replace("08.12", "23.52")
                    .replace("08.15", "23.55").replace("08.20", "00.00")
                    .replace("08.26", "00.06").replace("08.30", "00.10")
            }, clock = clock
        )
        val query = JourneyQuery(LocalDate.of(2026, 9, 5), LocalTime.of(23, 50))
        val result = adapter.loadRoute(route(appiano, ostiense), Direction.OUTBOUND, query) as DataResult.Success
        assertEquals(query.serviceDate.atTime(23, 52), result.snapshot.services.single().scheduledDepartureDateTime)
        val outsideValidity = adapter.loadRoute(route(appiano, ostiense), Direction.OUTBOUND,
            query.copy(serviceDate = LocalDate.of(2026, 12, 12)))
        assertTrue(outsideValidity is DataResult.Failure)
    }

    // Synthetic published-format fixture: these times are deliberately not evidence of a live service.
    private fun arrivalsFixture(periodicity: String = "Giornaliero") = """
        <html><body><div>ORARIO PROGRAMMATO 14 Giugno 2026 - 12 Dicembre 2026</div>
        <table><tr><th>Orario di arrivo</th><th>Tipo e n. di treno</th><th>Stazione di partenza</th>
        <th>Fermate precedenti (orario di partenza)</th><th>Periodicità e avvertenze</th></tr>
        <tr><td>08.30</td><td><img alt="Treno Regionale"/>12345</td><td>Viterbo Porta Fiorentina</td>
        <td>Roma Monte Mario (08.05) Appiano (08.12) Valle Aurelia (08.15) Roma S.Pietro (08.20) Roma Trastevere (08.26)</td>
        <td>$periodicity</td></tr></table></body></html>
    """.trimIndent()

    @Test
    fun `quadro per la stessa route viene riusato tra date diverse`() = runBlocking {
        var calls = 0
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { calls += 1; fixture() },
            clock = clock
        )
        val savedRoute = route(roma, milano)

        adapter.loadRoute(savedRoute, Direction.OUTBOUND, JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0)))
        adapter.loadRoute(savedRoute, Direction.OUTBOUND, JourneyQuery(LocalDate.of(2026, 9, 5), LocalTime.of(8, 0)))

        assertEquals(1, calls)
    }

    @Test
    fun `cache dei quadri RFI resta entro il limite configurato`() = runBlocking {
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { fixture() },
            clock = clock,
            cacheMaxEntries = 2
        )

        repeat(3) { index ->
            adapter.loadRoute(
                route(roma, milano).copy(id = "route-$index"),
                Direction.OUTBOUND,
                JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
            )
        }

        assertEquals(2, adapter.cachedTimetableCountForTest())
    }

    @Test
    fun `cache hit conserva timestamp e scadenza dell acquisizione originale`() = runBlocking {
        val mutableClock = MutableClock(Instant.parse("2026-09-04T06:00:00Z"), ZoneOffset.UTC)
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { fixture() },
            clock = mutableClock,
            cacheTtlMillis = 1_000L
        )
        val savedRoute = route(roma, milano)
        val query = JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))

        val first = adapter.loadRoute(savedRoute, Direction.OUTBOUND, query) as DataResult.Success
        mutableClock.advanceMillis(900L)
        val hit = adapter.loadRoute(savedRoute, Direction.OUTBOUND, query) as DataResult.Success

        assertEquals(first.snapshot.retrievedAtEpochMillis, hit.snapshot.retrievedAtEpochMillis)
        assertEquals(first.snapshot.sourceObservedAtEpochMillis, hit.snapshot.sourceObservedAtEpochMillis)
        assertEquals(first.snapshot.expiresAtEpochMillis, hit.snapshot.expiresAtEpochMillis)
    }

    @Test
    fun `cancellazione coroutine del fetch RFI viene propagata`() {
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { throw CancellationException("cancelled") },
            clock = clock
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                adapter.loadRoute(
                    route(roma, milano),
                    Direction.OUTBOUND,
                    JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
                )
            }
        }
    }

    @Test
    fun `periodicita feriale viene rispettata`() = runBlocking {
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { fixture(periodicity = "Lun-Ven") },
            clock = clock
        )

        val saturday = adapter.loadRoute(
            route(roma, milano),
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 5), LocalTime.of(8, 0))
        )

        assertTrue(saturday is DataResult.Failure)
    }

    @Test
    fun `feriale esclude una festivita nazionale che cade di sabato`() = runBlocking {
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { fixture(periodicity = "Feriale") },
            clock = clock
        )

        val ferragosto = adapter.loadRoute(
            route(roma, milano),
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 8, 15), LocalTime.of(8, 0))
        )

        assertTrue(ferragosto is DataResult.Failure)
    }

    @Test
    fun `festivo include una festivita nazionale che cade di sabato`() = runBlocking {
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { fixture(periodicity = "Festivo") },
            clock = clock
        )

        val ferragosto = adapter.loadRoute(
            route(roma, milano),
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 8, 15), LocalTime.of(8, 0))
        )

        assertTrue(ferragosto is DataResult.Success)
    }

    @Test
    fun `festivo is fail closed on Tuesday and includes Easter Monday`() {
        val adapter = adapter()

        assertFalse(adapter.runsOnDate("Festivo", LocalDate.of(2026, 4, 7)))
        assertTrue(adapter.runsOnDate("Festivo", LocalDate.of(2026, 4, 6)))
    }

    @Test
    fun `feriale includes an ordinary Tuesday and excludes Easter Monday`() {
        val adapter = adapter()

        assertTrue(adapter.runsOnDate("Feriale", LocalDate.of(2026, 4, 7)))
        assertFalse(adapter.runsOnDate("Feriale", LocalDate.of(2026, 4, 6)))
    }

    @Test
    fun `SAB E FEST includes ordinary Saturday Sunday and weekday holiday`() {
        val adapter = adapter()

        assertTrue(adapter.runsOnDate("SAB E FEST", LocalDate.of(2026, 9, 5)))
        assertTrue(adapter.runsOnDate("SAB E FEST", LocalDate.of(2026, 9, 6)))
        assertTrue(adapter.runsOnDate("SAB E FEST", LocalDate.of(2026, 6, 2)))
    }

    @Test
    fun `SABATO E FESTIVI includes ordinary Saturday Sunday and weekday holiday`() {
        val adapter = adapter()

        assertTrue(adapter.runsOnDate("SABATO E FESTIVI", LocalDate.of(2026, 9, 5)))
        assertTrue(adapter.runsOnDate("SABATO E FESTIVI", LocalDate.of(2026, 9, 6)))
        assertTrue(adapter.runsOnDate("SABATO E FESTIVI", LocalDate.of(2026, 6, 2)))
    }

    @Test
    fun `DOM and DOMENICA include Sunday only`() {
        val adapter = adapter()

        listOf("DOM", "DOMENICA").forEach { periodicity ->
            assertTrue(adapter.runsOnDate(periodicity, LocalDate.of(2026, 9, 6)))
            assertFalse(adapter.runsOnDate(periodicity, LocalDate.of(2026, 9, 5)))
        }
    }

    @Test
    fun `fetch usa I O e parsing usa dispatcher CPU configurato`() = runBlocking {
        val ioDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "rfi-test-io") }.asCoroutineDispatcher()
        val cpuDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "rfi-test-cpu") }.asCoroutineDispatcher()
        var fetchThread: String? = null
        var parsingThread: String? = null
        val adapter = RfiProgrammedTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher {
                fetchThread = Thread.currentThread().name
                fixture()
            },
            clock = clock,
            ioDispatcher = ioDispatcher,
            parsingDispatcher = cpuDispatcher,
            onParsing = { parsingThread = Thread.currentThread().name }
        )

        try {
            val result = adapter.loadRoute(
                route(roma, milano),
                Direction.OUTBOUND,
                JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
            )

            assertTrue(result is DataResult.Success)
            assertTrue(fetchThread.orEmpty().startsWith("rfi-test-io"))
            assertTrue(parsingThread.orEmpty().startsWith("rfi-test-cpu"))
        } finally {
            ioDispatcher.close()
            cpuDispatcher.close()
        }
    }

    private fun adapter() = RfiProgrammedTimetableAdapter(
        fetcher = RfiProgrammedTimetableFetcher { fixture() },
        clock = clock
    )

    private fun route(origin: Station, destination: Station) = SavedRoute(
        id = "${origin.id}-${destination.id}",
        name = "${origin.name}–${destination.name}",
        outwardOrigin = origin,
        outwardDestination = destination
    )

    private fun fixture(periodicity: String = "Giornaliero"): String = """
        <html><body>
          <div>ORARIO PROGRAMMATO 14 Giugno 2026 - 12 Dicembre 2026</div>
          <table>
            <tr>
              <th>Orario di partenza</th><th>Tipo e n. di treno</th><th>Stazione di arrivo</th>
              <th>Binario programmato</th><th>Classi e servizi a bordo</th><th>Impresa di trasporto</th>
              <th>Fermate precedenti (orario di partenza)</th>
              <th>Fermate successive (orario di arrivo)</th><th>Periodicità e avvertenze</th>
            </tr>
            <tr>
              <td>08.20</td>
              <td><img alt="Treno Frecciarossa Alta Velocità"/>9611</td>
              <td><img alt="Stazione accessibile"/>Milano Centrale (11.40)</td>
              <td>8</td><td></td><td>TI</td><td></td>
              <td>Roma Tiburtina (08.27) Firenze S.M.N. (09.46) Bologna Centrale (10.23) Milano Centrale (11.40)</td>
              <td>$periodicity</td>
            </tr>
            <tr>
              <td>09.30</td>
              <td><img alt="Treno Italo Alta Velocità"/>9923</td>
              <td>Torino Porta Nuova (14.50)</td>
              <td>10</td><td></td><td>NTV</td><td></td>
              <td>Firenze S.M.N. (10.55) Bologna Centrale (11.33) Milano Centrale (12.45) Torino Porta Susa (14.40) Torino Porta Nuova (14.50)</td>
              <td>$periodicity</td>
            </tr>
          </table>
        </body></html>
    """.trimIndent()

    private class MutableClock(
        private var current: Instant,
        private val currentZone: ZoneId
    ) : Clock() {
        fun advanceMillis(value: Long) {
            current = current.plusMillis(value)
        }

        override fun getZone(): ZoneId = currentZone

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        override fun instant(): Instant = current
    }
}
