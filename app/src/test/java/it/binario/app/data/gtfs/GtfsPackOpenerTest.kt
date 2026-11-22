package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GtfsPackOpenerTest {
    private val assets = mutableListOf<String>()
    private val openAsset = { path: String -> assets += path; ByteArrayInputStream("apk:$path".toByteArray()) }

    @Test
    fun `without an installed pack every path is read from the APK`() {
        val opener = GtfsPackOpener(openAsset, ForeignPackSnapshot.NONE, EmbeddedGtfsPacks.all)

        for (pack in EmbeddedGtfsPacks.all) {
            assertEquals("apk:${pack.assetPath}", opener(pack.assetPath).reader().readText())
        }
        assertEquals("apk:stations/curated_it_rail_stations.json", opener("stations/curated_it_rail_stations.json").reader().readText())
        assertEquals(EmbeddedGtfsPacks.all.size + 1, assets.size)
        assertTrue(opener.installedPaths.isEmpty())
    }

    @Test
    fun `an installed full pack replaces its bundled asset and nothing else`() {
        val file = Files.createTempFile("oebb", ".zip").toFile().apply { writeText("full") }
        try {
            val verified = mutableListOf<Pair<SourceId, File>>()
            val snapshot = ForeignPackSnapshot(3L, ForeignPackPins.EMPTY, mapOf(SourceId("oebb-gtfs") to file))
            val opener = GtfsPackOpener(openAsset, snapshot, EmbeddedGtfsPacks.all) { id, checked -> verified += id to checked }

            assertEquals("full", opener("gtfs/oebb-gtfs.zip").reader().readText())
            assertEquals("apk:gtfs/ch-otd-gtfs.zip", opener("gtfs/ch-otd-gtfs.zip").reader().readText())
            assertEquals(listOf(SourceId("oebb-gtfs") to file), verified)
            assertEquals(setOf("gtfs/oebb-gtfs.zip"), opener.installedPaths)
            assertEquals(listOf("gtfs/ch-otd-gtfs.zip"), assets)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a failed check is an unreadable pack`() {
        val file = Files.createTempFile("oebb", ".zip").toFile()
        try {
            val snapshot = ForeignPackSnapshot(1L, ForeignPackPins.EMPTY, mapOf(SourceId("oebb-gtfs") to file))
            val opener = GtfsPackOpener(openAsset, snapshot, EmbeddedGtfsPacks.all) { _, _ -> throw IOException("changed") }

            try {
                opener("gtfs/oebb-gtfs.zip")
                fail("opened a pack that failed its check")
            } catch (_: IOException) {
                // expected
            }
            assertTrue(assets.isEmpty())
        } finally {
            file.delete()
        }
    }
}
