package it.binario.app.data.gtfs

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.SourceId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedGtfsSourceScopingTest {
    private val lombardia = EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "lombardia.zip")
    private val swiss = EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "swiss.zip")

    @Test
    fun `one GTFS endpoint plus one curated endpoint does not suppress other matching packs`() = runBlocking {
        val route = SavedRoute(
            "mixed",
            "Milano–Como",
            Station("gtfs:lombardia-trenord-gtfs:MI", "Milano Centrale", "Milano"),
            Station("curated:como", "Como S. Giovanni", "Como")
        )
        val adapter = adapter()

        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Success)
        assertEquals(listOf("TRENORD-1", "SBB-1"), (result as DataResult.Success).snapshot.services.map { it.number })
    }

    @Test
    fun `both endpoints from same GTFS source intentionally scope to that source`() = runBlocking {
        val route = SavedRoute(
            "scoped",
            "Milano–Como",
            Station("gtfs:lombardia-trenord-gtfs:MI", "Milano Centrale", "Milano"),
            Station("gtfs:lombardia-trenord-gtfs:CO", "Como S. Giovanni", "Como")
        )
        val adapter = adapter()

        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Success)
        assertEquals(listOf("TRENORD-1"), (result as DataResult.Success).snapshot.services.map { it.number })
    }

    private fun adapter(): EmbeddedGtfsRouteAdapter {
        val feeds = mapOf(
            lombardia.assetPath to feed("TN", "TRENORD-1", "08:05:00", "Trenord"),
            swiss.assetPath to feed("CH", "SBB-1", "08:35:00", "SBB")
        )
        return EmbeddedGtfsRouteAdapter(
            openAsset = { ByteArrayInputStream(requireNotNull(feeds[it])) },
            packs = listOf(lombardia, swiss),
            clock = { 1_000L }
        )
    }

    private fun feed(tripId: String, number: String, departure: String, agency: String): ByteArray {
        val content = linkedMapOf(
            "agency.txt" to "agency_id,agency_name\nA,$agency\n",
            "stops.txt" to "stop_id,stop_name\nMI,Milano Centrale\nCO,Como S. Giovanni\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_type\nR,A,R,2\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_short_name\nR,W,$tripId,$number\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n$tripId,$departure,$departure,MI,1\n$tripId,09:00:00,09:00:00,CO,2\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nW,1,1,1,1,1,1,1,20260101,20261231\n",
            "calendar_dates.txt" to "service_id,date,exception_type\n"
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
