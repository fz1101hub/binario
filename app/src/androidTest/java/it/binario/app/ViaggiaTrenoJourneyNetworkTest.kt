package it.binario.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import it.binario.app.data.*
import it.binario.app.data.live.ViaggiaTrenoRealtimeAdapter
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit external acceptance test, excluded from deterministic UI checks. */
@RunWith(AndroidJUnit4::class)
class ViaggiaTrenoJourneyNetworkTest {
    @Test fun appianoToOstienseUsesPublishedTrainStops() = runBlocking {
        assertTrue(BuildConfig.PERSONAL_REALTIME_SOURCES)
        val now = ZonedDateTime.now(RailTime.defaultZone)
        val route = SavedRoute("live-appiano-ostiense", "Appiano → Roma Ostiense",
            Station("curated:S08328", "Appiano Proba Petronia", "Roma", railwayCode = "S08328"),
            Station("curated:S08406", "Roma Ostiense", "Roma", railwayCode = "S08406"))
        // At night inspect the first morning services; never invent a running train or delay.
        val time = if (now.toLocalTime().isBefore(LocalTime.of(5, 0))) LocalTime.of(5, 0) else now.toLocalTime()
        val result = ViaggiaTrenoRealtimeAdapter().loadRoute(route, Direction.OUTBOUND,
            JourneyQuery(now.toLocalDate(), time))
        assertTrue("ViaggiaTreno Appiano → Ostiense: $result", result is DataResult.Success)
        val snapshot = (result as DataResult.Success).snapshot
        assertTrue(snapshot.services.isNotEmpty())
        snapshot.services.forEach { service ->
            assertEquals(route.outwardOrigin.name, service.stops.first().station)
            assertEquals(route.outwardDestination.name, service.stops.last().station)
            assertNotNull(service.scheduledDepartureDateTime)
            if (service.operationalStatusVerified) {
                assertTrue(service.operationalSourceIds.contains("viaggiatreno"))
                assertNotNull(service.operationalObservedAtEpochMillis)
            } else {
                assertNull(service.officialDelayMinutes)
            }
        }
        println("ViaggiaTreno: ${snapshot.services.size} corse pubblicate, ${snapshot.services.count { it.operationalStatusVerified }} con stato operativo verificato.")
    }
}
