package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EmbeddedGtfsPackLoaderTest {
    @Test
    fun `carica un data pack embedded con la fonte catalogata`() {
        val pack = EmbeddedGtfsPack(SourceId("hzpp-gtfs"), "gtfs/hzpp-gtfs.zip")
        val loader = EmbeddedGtfsPackLoader(openAsset = { ByteArrayInputStream(validGtfs()) })

        val summary = loader.load(pack, Instant.parse("2026-08-14T00:00:00Z"))

        assertEquals("hzpp-gtfs", summary.sourceId.value)
        assertEquals(1, summary.stations.size)
        assertTrue(summary.agencyNames.isNotEmpty())
    }

    @Test
    fun `tutti i data pack embedded appartengono al catalogo`() {
        assertEquals(4, EmbeddedGtfsPacks.all.size)
        assertEquals(4, EmbeddedGtfsPacks.all.map { it.sourceId }.distinct().size)
    }

    private fun validGtfs(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            mapOf(
                "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\nA,Demo,https://example.test,Europe/Rome\n",
                "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nS,Stazione,41.9,12.5\n",
                "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\nR,A,R,Route,2\n",
                "trips.txt" to "route_id,service_id,trip_id\nR,W,T\n",
                "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT,10:00:00,10:00:00,S,1\n"
            ).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
