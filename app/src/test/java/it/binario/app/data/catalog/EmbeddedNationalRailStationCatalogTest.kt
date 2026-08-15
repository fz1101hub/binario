package it.binario.app.data.catalog

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedNationalRailStationCatalogTest {
    @Test
    fun `catalog loads discovery entries without operational meaning`() {
        val catalog = EmbeddedNationalRailStationCatalog(
            openAsset = { ByteArrayInputStream(fixture()) },
            assetPath = "fixture.json.gz"
        )

        val stations = catalog.load()

        assertEquals(1, stations.size)
        assertEquals("Roma Termini", stations.single().name)
        assertEquals("OpenStreetMap · catalogo geografico", stations.single().region)
        assertTrue(stations.single().id.startsWith("catalog:osm-it:"))
    }

    @Test
    fun `embedded national catalog finds roma termini beyond regional GTFS packs`() {
        val assets = File("src/main/assets")
        val catalog = EmbeddedNationalRailStationCatalog(
            openAsset = { assetPath -> File(assets, assetPath).inputStream() }
        )

        val stations = catalog.load()

        assertTrue(stations.any { it.name.equals("Roma Termini", ignoreCase = true) })
        assertTrue(stations.any { it.name.equals("Viterbo Porta Fiorentina", ignoreCase = true) })
    }

    private fun fixture(): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { gzip ->
            gzip.write(
                """{"kind":"geographic-rail-station-discovery-catalog","operationalData":false,"stations":[{"id":"osm:node:1","name":"Roma Termini","city":"Roma"}]}"""
                    .toByteArray()
            )
        }
        output.toByteArray()
    }
}
