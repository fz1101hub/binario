package it.binario.app.data.planned

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RfiSupplementalHubTimetableAdapterTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-04T06:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `supplemental hub uses verified RFI id and shared parser`() = runBlocking {
        var requestedUrl: String? = null
        val adapter = RfiSupplementalHubTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { url -> requestedUrl = url; fixture() },
            clock = clock
        )
        val origin = Station("curated:reggio", "Reggio Calabria Centrale", "Reggio Calabria")
        val destination = Station("curated:roma", "Roma Termini", "Roma")
        val route = SavedRoute("rc-roma", "Reggio–Roma", origin, destination)

        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(7, 0))
        )

        assertTrue(result is DataResult.Success)
        assertTrue(requestedUrl.orEmpty().contains("Id=116"))
        assertEquals("8110", (result as DataResult.Success).snapshot.services.single().number)
        assertEquals("Roma Termini", result.snapshot.services.single().stops.last().station)
    }

    @Test
    fun `unmapped station makes no network request`() = runBlocking {
        var calls = 0
        val adapter = RfiSupplementalHubTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { calls += 1; fixture() },
            clock = clock
        )
        val origin = Station("x", "Stazione ignota", "Italia")
        val destination = Station("curated:roma", "Roma Termini", "Roma")

        val result = adapter.loadRoute(
            SavedRoute("x", "x", origin, destination),
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(7, 0))
        )

        assertTrue(result is DataResult.Failure)
        assertEquals(0, calls)
    }

    @Test
    fun `same route reuses the supplemental timetable inside its TTL`() = runBlocking {
        var calls = 0
        val adapter = RfiSupplementalHubTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { calls += 1; fixture() },
            clock = clock
        )
        val origin = Station("curated:reggio", "Reggio Calabria Centrale", "Reggio Calabria")
        val destination = Station("curated:roma", "Roma Termini", "Roma")
        val route = SavedRoute("rc-roma", "Reggio–Roma", origin, destination)

        repeat(2) {
            assertTrue(
                adapter.loadRoute(
                    route,
                    Direction.OUTBOUND,
                    JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(7, 0))
                ) is DataResult.Success
            )
        }

        assertEquals(1, calls)
    }

    @Test
    fun `reverse direction explains that only departures from the mapped hub are available`() = runBlocking {
        var calls = 0
        val adapter = RfiSupplementalHubTimetableAdapter(
            fetcher = RfiProgrammedTimetableFetcher { calls += 1; fixture() },
            clock = clock
        )
        val origin = Station("curated:roma", "Roma Termini", "Roma")
        val destination = Station("curated:reggio", "Reggio Calabria Centrale", "Reggio Calabria")

        val result = adapter.loadRoute(
            SavedRoute("roma-rc", "Roma–Reggio", origin, destination),
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(7, 0))
        )

        assertTrue(result is DataResult.Failure)
        assertTrue((result as DataResult.Failure).message.contains("partenze dall’hub Reggio Calabria Centrale"))
        assertEquals(0, calls)
    }

    private fun fixture(): String = """
        <html><body>
          <div>ORARIO PROGRAMMATO 14 Giugno 2026 - 12 Dicembre 2026</div>
          <table>
            <tr>
              <th>Orario di partenza</th><th>Tipo e n. di treno</th><th>Stazione di arrivo</th>
              <th>Impresa di trasporto</th><th>Fermate successive (orario di arrivo)</th><th>Periodicità e avvertenze</th>
            </tr>
            <tr>
              <td>07.30</td><td>Intercity 8110</td><td>Roma Termini (14.00)</td><td>Trenitalia</td>
              <td>Lamezia Terme Centrale (09.10) Napoli Centrale (12.00) Roma Termini (14.00)</td><td>Giornaliero</td>
            </tr>
          </table>
        </body></html>
    """.trimIndent()
}
