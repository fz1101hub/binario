package it.binario.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.repository.BinarioDataGraph
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit network acceptance test, excluded from the deterministic UI suite. */
@RunWith(AndroidJUnit4::class)
class RfiJourneyNetworkTest {
    @Test
    fun appianoToOstienseHasPublishedDeparturesOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = SavedRoute(
            id = "network-appiano-ostiense", name = "Appiano → Roma Ostiense",
            outwardOrigin = Station("curated:S08328", "Appiano Proba Petronia", "Roma",
                railwayCode = "S08328", aliases = listOf("APPIANO")),
            outwardDestination = Station("curated:S08406", "Roma Ostiense", "Roma", railwayCode = "S08406")
        )
        val query = JourneyQuery(LocalDate.now(ZoneId.of("Europe/Rome")), LocalTime.MIDNIGHT)
        val result = BinarioDataGraph.routeDataSource(context).loadRoute(route, Direction.OUTBOUND, query)
        assertTrue("Appiano → Ostiense: $result", result is DataResult.Success)
        val snapshot = (result as DataResult.Success).snapshot
        assertTrue(snapshot.services.isNotEmpty())
        snapshot.services.forEach { service ->
            assertEquals(route.outwardOrigin.name, service.stops.first().station)
            assertEquals(route.outwardDestination.name, service.stops.last().station)
            assertTrue(service.scheduledDepartureDateTime != null)
            assertTrue(service.stops.last().scheduled.matches(Regex("[0-2][0-9]:[0-5][0-9]")))
            assertFalse(service.operationalStatusVerified)
        }
    }
}
