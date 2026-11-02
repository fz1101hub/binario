package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedUrbanStopIndexTest {
    private val pack = EmbeddedGtfsPack(
        SourceId("test-city-gtfs"), "gtfs-transit/test-city-gtfs.zip", LocalDate.of(2026, 12, 31),
        kind = EmbeddedGtfsPackKind.URBAN, areas = listOf(TransitArea("Città", 45.0, 9.0, 10.0))
    )

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private val feed = zip(
        "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon,parent_station,location_type\n" +
            "P1,Duomo,45.0,9.0,,1\nM1,Duomo,45.0,9.0,P1,0\nT1,Duomo,45.001,9.001,,0\n" +
            "T2,\"Piazza Duomo, lato nord\",45.002,9.0,,0\nB1,Solo autobus,45.003,9.0,,0\n",
        "binario_stop_modes.txt" to "stop_id,modes\nM1,M\nP1,M\nT1,T\nT2,T\n"
    )

    @Test
    fun `one entry per name, metro before tram, buses left out`() {
        var opened = 0
        val index = EmbeddedUrbanStopIndex(openAsset = { opened += 1; ByteArrayInputStream(feed) }, packs = listOf(pack))
        assertNull("nothing is read before the first search", index.badgeOf("gtfs:test-city-gtfs:P1"))
        assertEquals(0, opened)

        val duomo = index.search("duomo")
        // The exact name first: the station "Duomo" (metro, its platform and the tram stop share the name).
        assertEquals("Duomo", duomo.first().station.name)
        assertEquals(UrbanStopBadge.METRO, duomo.first().badge)
        assertEquals("Città", duomo.first().station.city)
        assertEquals(listOf("Duomo", "Piazza Duomo, lato nord"), duomo.map { it.station.name })
        assertEquals(UrbanStopBadge.TRAM, duomo.last().badge)
        assertTrue(index.search("autobus").isEmpty())
        assertEquals(UrbanStopBadge.TRAM, index.badgeOf("gtfs:test-city-gtfs:T2"))
        assertEquals(1, opened)
    }

    @Test
    fun `a broken pack gives no stops instead of an error`() {
        val index = EmbeddedUrbanStopIndex(openAsset = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, packs = listOf(pack))
        assertTrue(index.search("duomo").isEmpty())
    }

    @Test
    fun `names in capitals or lower case become readable`() {
        assertEquals("Duomo M1", StopNameCase.display("DUOMO M1"))
        assertEquals("Stazione Centrale P.za Duca D'Aosta M2 M3", StopNameCase.display("stazione centrale p.za duca d'aosta m2 m3"))
        assertEquals("Genova Piazza Manin", StopNameCase.display("GENOVA/PIAZZA MANIN"))
        assertEquals("Busalletta", StopNameCase.display("BUSALLETTA/"))
        assertEquals("Sant'Olcese Chiesa", StopNameCase.display("SANT'OLCESE/CHIESA"))
        assertEquals("Centrale FS", StopNameCase.display("CENTRALE FS"))
        // A name that already mixes cases is the operator's own spelling.
        assertEquals("Peretola Aeroporto", StopNameCase.display("Peretola Aeroporto"))
        assertEquals("Valfonda - Stazione Smn", StopNameCase.display("Valfonda - Stazione Smn"))
    }
}
