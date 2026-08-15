package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedGtfsStationSearchIndexTest {
    @Test
    fun `search normalizes accents and returns a real GTFS stop`() {
        val pack = EmbeddedGtfsPack(SourceId("alto-adige-sta-gtfs"), "fixture.zip")
        val bytes = fixtureZip()
        val index = EmbeddedGtfsStationSearchIndex(
            openAsset = { ByteArrayInputStream(bytes) },
            packs = listOf(pack),
            activeSourceIds = setOf(pack.sourceId)
        )

        val matches = index.search("Bolzano Bozen")

        assertEquals(1, matches.size)
        assertEquals("Bolzano/Bozen", matches.single().name)
        assertTrue(matches.single().id.startsWith("gtfs:alto-adige-sta-gtfs:"))
    }

    private fun fixtureZip(): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("stops.txt"))
            zip.write("stop_id,stop_name,location_type\nBZ,Bolzano/Bozen,1\nBUS,Deposito,3\n".toByteArray())
            zip.closeEntry()
        }
        output.toByteArray()
    }
}
