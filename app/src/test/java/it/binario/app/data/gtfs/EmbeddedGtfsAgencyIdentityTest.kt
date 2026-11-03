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

class EmbeddedGtfsAgencyIdentityTest {
    private val route = SavedRoute(
        id = "milano-como",
        name = "Milano – Como",
        outwardOrigin = Station("milano", "Milano Centrale", "Milano"),
        outwardDestination = Station("como", "Como S. Giovanni", "Como")
    )

    @Test
    fun `agency txt distingue SBB dentro del dataset nazionale svizzero`() = runBlocking {
        val pack = EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "gtfs/ch.zip")
        val adapter = EmbeddedGtfsRouteAdapter(
            openAsset = { ByteArrayInputStream(feed("SBB", "Schweizerische Bundesbahnen SBB")) },
            packs = listOf(pack),
            clock = { 1_000L }
        )

        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Success)
        assertEquals("SBB", (result as DataResult.Success).snapshot.services.single().operatorDisplayName)
    }

    @Test
    fun `agency txt distingue TILO senza creare un nuovo data pack`() = runBlocking {
        val pack = EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "gtfs/ch.zip")
        val adapter = EmbeddedGtfsRouteAdapter(
            openAsset = { ByteArrayInputStream(feed("TILO", "TILO SA")) },
            packs = listOf(pack),
            clock = { 1_000L }
        )

        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        ) as DataResult.Success

        assertEquals("TILO", result.snapshot.services.single().operatorDisplayName)
    }

    @Test
    fun `deduplica feed sovrapposti conservando la fonte affine all operatore`() = runBlocking {
        val swiss = EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "gtfs/ch.zip")
        val oebb = EmbeddedGtfsPack(SourceId("oebb-gtfs"), "gtfs/at.zip")
        val feeds = mapOf(
            swiss.assetPath to feed("SBB", "Schweizerische Bundesbahnen SBB"),
            oebb.assetPath to feed("X", "Generic international aggregate")
        )
        val adapter = EmbeddedGtfsRouteAdapter(
            openAsset = { path -> ByteArrayInputStream(feeds.getValue(path)) },
            packs = listOf(oebb, swiss),
            clock = { 1_000L }
        )

        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        ) as DataResult.Success

        assertEquals(1, result.snapshot.services.size)
        assertEquals("SBB", result.snapshot.services.single().operatorDisplayName)
        assertTrue(result.snapshot.providerName.contains("Switzerland", ignoreCase = true) || result.snapshot.providerName.contains("Swiss", ignoreCase = true))
    }

    private fun feed(agencyId: String, agencyName: String): ByteArray {
        val content = linkedMapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\n$agencyId,$agencyName,https://example.test,Europe/Rome\n",
            "stops.txt" to "stop_id,stop_name\nMI,Milano Centrale\nCO,Como S. Giovanni\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\nR,$agencyId,EC,Milano Como,2\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign,trip_short_name\nR,W,T1,Como S. Giovanni,451\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,08:05:00,08:05:00,MI,1\nT1,08:50:00,08:50:00,CO,2\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nW,1,1,1,1,1,1,1,20260101,20261231\n"
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
