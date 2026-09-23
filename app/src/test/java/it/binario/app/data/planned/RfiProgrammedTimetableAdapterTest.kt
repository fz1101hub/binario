package it.binario.app.data.planned

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailOperator
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
        assertEquals(listOf("9923"), (result as DataResult.Success).snapshot.services.map { it.number })
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
            route(unknown, napoli),
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Failure)
        assertEquals(0, calls)
    }

    @Test
    fun `destinazione hub mappata spiega il limite del quadro partenze`() = runBlocking {
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
        assertTrue((result as DataResult.Failure).message.contains("partenze dall’hub Milano Centrale"))
        assertEquals(0, calls)
    }

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
