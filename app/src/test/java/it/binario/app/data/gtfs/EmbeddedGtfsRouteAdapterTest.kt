package it.binario.app.data.gtfs

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.SourceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EmbeddedGtfsRouteAdapterTest {
    private val pack = EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "gtfs/test.zip")
    private val route = SavedRoute(
        id = "test",
        name = "Milano – Como",
        outwardOrigin = Station("milano", "Milano Centrale", "Milano"),
        outwardDestination = Station("como", "Como S. Giovanni", "Como")
    )

    @Test
    fun `trasforma una corsa GTFS diretta in orario programmato`() = runBlocking {
        val adapter = EmbeddedGtfsRouteAdapter(
            openAsset = { ByteArrayInputStream(feed()) },
            packs = listOf(pack),
            clock = { 1_000L }
        )

        val result = adapter.loadRoute(route, Direction.OUTBOUND)

        assertTrue(result is DataResult.Success)
        val snapshot = (result as DataResult.Success).snapshot
        assertEquals(DataConfidence.STATIC_ONLY, snapshot.confidence)
        assertEquals(1, snapshot.services.size)
        assertEquals("08:05", snapshot.services.single().scheduledDeparture)
        assertEquals("2581", snapshot.services.single().number)
        assertEquals(3, snapshot.services.single().stops.size)
    }

    @Test
    fun `non inventa corse quando la tratta non esiste`() = runBlocking {
        val adapter = EmbeddedGtfsRouteAdapter(
            openAsset = { ByteArrayInputStream(feed()) },
            packs = listOf(pack)
        )
        val missing = route.copy(outwardDestination = Station("bergamo", "Bergamo", "Bergamo"))

        assertTrue(adapter.loadRoute(missing, Direction.OUTBOUND) is DataResult.Failure)
    }

    private fun feed(): ByteArray {
        val content = mapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\nT,Trenord,https://example.test,Europe/Rome\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nMI,Milano Centrale,45.48,9.20\nMO,Monza,45.58,9.27\nCO,Como S. Giovanni,45.80,9.08\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\nR,T,RE,Milano Como,2\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign,trip_short_name\nR,W,T1,Como S. Giovanni,2581\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,08:05:00,08:05:00,MI,1\nT1,08:22:00,08:23:00,MO,2\nT1,08:50:00,08:50:00,CO,3\n"
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            content.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
