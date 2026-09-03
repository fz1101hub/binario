package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.sources.RailSourceDefinition
import it.binario.app.data.sources.SourceFormat
import it.binario.app.data.sources.SourceReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GtfsArchiveInspectorTest {
    @Test
    fun `ispeziona un data pack GTFS valido e conserva le stazioni`() {
        val summary = GtfsArchiveInspector().inspect(
            source = fixtureSource(),
            stream = ByteArrayInputStream(validGtfs()),
            importedAt = Instant.parse("2026-08-14T12:00:00Z")
        )

        assertEquals(listOf("Operatore di prova"), summary.agencyNames)
        assertEquals("fixture-v1", summary.feedVersion)
        assertEquals(2, summary.stations.size)
        assertEquals("Roma, Termini", summary.stations.first().canonicalName)
        assertEquals("fixture-gtfs:ROMA", summary.stations.first().id.value)
        assertTrue("stops.txt" in summary.presentFiles)
        assertEquals(64, summary.sha256.length)
    }

    @Test(expected = GtfsValidationException::class)
    fun `rifiuta un feed privo di file obbligatori`() {
        val entries = baseEntries().toMutableMap().apply { remove("stop_times.txt") }
        GtfsArchiveInspector().inspect(fixtureSource(), ByteArrayInputStream(zip(entries)))
    }

    @Test(expected = GtfsValidationException::class)
    fun `rifiuta una entry oltre il limite di sicurezza`() {
        val limits = GtfsSafetyLimits(maxEntryBytes = 16, maxTotalUncompressedBytes = 64)
        GtfsArchiveInspector(limits).inspect(fixtureSource(), ByteArrayInputStream(validGtfs()))
    }

    private fun fixtureSource() = RailSourceDefinition(
        id = SourceId("fixture-gtfs"),
        displayName = "GTFS fixture",
        operatorOrAuthority = "Test",
        formats = setOf(SourceFormat.GTFS_ZIP),
        capabilities = setOf(ProviderCapability.STATION_CATALOG),
        accessMode = AccessMode.OPEN_DIRECT,
        readiness = SourceReadiness.READY_FOR_GTFS_PACK,
        countryCodes = setOf("IT"),
        coverage = "Fixture",
        landingPage = URI.create("https://example.test/gtfs"),
        notes = "Solo test"
    )

    private fun validGtfs(): ByteArray = zip(baseEntries())

    private fun baseEntries(): Map<String, String> = linkedMapOf(
        "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\nTEST,Operatore di prova,https://example.test,Europe/Rome\n",
        "feed_info.txt" to "feed_publisher_name,feed_version\nTest,fixture-v1\n",
        "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon,location_type\nROMA,\"Roma, Termini\",41.901,12.501,1\nTIB,Roma Tiburtina,41.910,12.530,0\n",
        "routes.txt" to "route_id,route_short_name\nR1,Test\n",
        "trips.txt" to "route_id,service_id,trip_id\nR1,WD,T1\n",
        "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,10:00:00,10:00:00,ROMA,1\n"
    )

    private fun zip(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
