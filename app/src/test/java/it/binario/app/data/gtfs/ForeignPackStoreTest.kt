package it.binario.app.data.gtfs

import it.binario.app.data.gtfs.ForeignPackFixtures.archive
import it.binario.app.data.gtfs.ForeignPackFixtures.oebb
import it.binario.app.data.gtfs.ForeignPackFixtures.packJson
import it.binario.app.data.gtfs.ForeignPackFixtures.pins
import it.binario.app.data.gtfs.ForeignPackFixtures.swiss
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ForeignPackStoreTest {
    private val root: File = Files.createTempDirectory("foreign-store").toFile()
    private val directory = File(root, "gtfs-full")
    private val bytes = archive("oebb-gtfs")
    private val pins = pins(
        packJson("ch-otd-gtfs", "CH", null, listOf("NC000041", "NC000063"), listOf("NC000063")),
        packJson("oebb-gtfs", "AT", bytes, listOf("NC000544", "NC001632", "S02001"), listOf("NC000544", "S02001"),
            lastServiceDate = LocalDate.of(2027, 1, 9))
    )
    private val today = LocalDate.of(2026, 12, 1)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun store(pins: ForeignPackPins = this.pins) = ForeignPackStore(directory, pins)

    private fun download(store: ForeignPackStore, content: ByteArray = bytes): File =
        store.partFile(oebb).also { store.ensureDirectory(); it.writeBytes(content) }

    @Test
    fun `an install moves the part into place, writes the sidecar last and bumps the generation`() {
        val store = store()
        assertNull(store.installed(oebb))
        assertEquals(ForeignPackStatus.BORDER_ONLY, store.status(pins.entry(oebb)!!, today))

        store.install(oebb, download(store))

        val installed = store.installed(oebb)
        assertNotNull(installed)
        assertTrue(bytes.contentEquals(installed!!.readBytes()))
        assertFalse(store.partFile(oebb).exists())
        assertTrue(File(directory, "oebb-gtfs.pin.json").isFile)
        assertEquals(1L, store.generation.value)
        assertEquals(ForeignPackStatus.INSTALLED, store.status(pins.entry(oebb)!!, today))
        // A second process sees the same install.
        assertNotNull(store().installed(oebb))
    }

    @Test
    fun `an archive of another pack is refused and leaves nothing installed`() {
        val other = archive("sncf-gtfs")
        val otherPins = pins(packJson("oebb-gtfs", "AT", other, listOf("A"), listOf("A")))
        val store = store(otherPins)

        try {
            store.install(oebb, download(store, other))
            fail("installed an archive of another pack")
        } catch (_: IOException) {
            // expected
        }
        assertNull(store.installed(oebb))
        assertFalse(store.zipFile(oebb).exists())
        assertFalse(File(directory, "oebb-gtfs.pin.json").exists())
        assertEquals(0L, store.generation.value)
    }

    @Test
    fun `a pack without a pin cannot be installed`() {
        val store = store()
        store.ensureDirectory()
        val part = File(directory, ".ch-otd-gtfs.part").apply { writeBytes(archive("ch-otd-gtfs")) }

        try {
            store.install(swiss, part)
            fail("installed an unpinned pack")
        } catch (_: IOException) {
            // expected
        }
        assertNull(store.installed(swiss))
    }

    @Test
    fun `delete removes the files and bumps the generation`() {
        val store = store()
        store.install(oebb, download(store))

        store.delete(oebb)

        assertNull(store.installed(oebb))
        assertFalse(store.zipFile(oebb).exists())
        assertEquals(2L, store.generation.value)
        assertEquals(ForeignPackStatus.BORDER_ONLY, store.status(pins.entry(oebb)!!, today))
    }

    @Test
    fun `startup cleaning removes parts, unfinished installs and files of unpinned packs`() {
        directory.mkdirs()
        File(directory, ".oebb-gtfs.part").writeBytes(bytes)
        File(directory, ".oebb-gtfs.pin.json.tmp").writeText("{}")
        File(directory, "oebb-gtfs.zip").writeBytes(bytes)
        File(directory, "ch-otd-gtfs.zip").writeBytes(archive("ch-otd-gtfs"))
        File(directory, "ch-otd-gtfs.pin.json").writeText("{}")

        val store = store()
        assertTrue(store.cleanUp())

        assertEquals(emptyList<String>(), directory.list()!!.sorted())
        assertNull(store.installed(oebb))
    }

    @Test
    fun `a pin changed by an app update deletes the old pack and offers the update in Settings`() {
        val old = store()
        old.install(oebb, download(old))
        val newBytes = archive("oebb-gtfs", extra = " 2")
        val newPins = pins(packJson("oebb-gtfs", "AT", newBytes, listOf("NC000544"), listOf("NC000544")))

        val updated = store(newPins)
        assertTrue(updated.cleanUp())

        assertNull(updated.installed(oebb))
        assertFalse(updated.zipFile(oebb).exists())
        assertEquals(ForeignPackStatus.UPDATE_AVAILABLE, updated.status(newPins.entry(oebb)!!, today))
        // Nothing is downloaded again by itself; a later install clears the notice.
        updated.install(oebb, updated.partFile(oebb).apply { writeBytes(newBytes) })
        assertEquals(ForeignPackStatus.INSTALLED, updated.status(newPins.entry(oebb)!!, today))
        updated.delete(oebb)
        assertEquals(ForeignPackStatus.BORDER_ONLY, updated.status(newPins.entry(oebb)!!, today))
    }

    @Test
    fun `an unchanged pin survives an app update`() {
        val old = store()
        old.install(oebb, download(old))

        val updated = store()
        assertFalse(updated.cleanUp())

        assertNotNull(updated.installed(oebb))
    }

    @Test
    fun `a pack whose bytes changed on disk is deleted at its first open`() {
        val store = store()
        store.install(oebb, download(store))
        val fresh = store()
        val file = fresh.installed(oebb)!!
        file.writeBytes(bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })

        try {
            fresh.verifyOnce(oebb, file)
            fail("accepted changed bytes")
        } catch (_: IOException) {
            // expected
        }
        assertNull(fresh.installed(oebb))
        assertEquals(1L, fresh.generation.value)
    }

    @Test
    fun `the hash of an installed pack is checked once per process`() {
        var hashed = 0
        val writer = store()
        writer.install(oebb, download(writer))
        val store = ForeignPackStore(directory, pins) { file -> hashed++; ForeignPackStore.sha256Hex(file) }
        val file = store.installed(oebb)!!

        store.verifyOnce(oebb, file)
        store.verifyOnce(oebb, file)

        assertEquals(1, hashed)
    }

    @Test
    fun `an expired pin is not offered in Settings`() {
        val store = store()

        assertEquals(ForeignPackStatus.EXPIRED, store.status(pins.entry(oebb)!!, LocalDate.of(2027, 1, 10)))
    }

    @Test
    fun `the readers see the installed pack whole with the pin's dates and the others as their border zone`() {
        val store = store()
        val before = store.snapshot().packs(EmbeddedGtfsPacks.all)
        val austriaBefore = before.first { it.sourceId == oebb }
        assertEquals(setOf("NC000544", "S02001"), austriaBefore.borderZone!!.zoneStationIds)
        assertFalse(austriaBefore.coversEnd("curated:NC001632"))
        assertTrue(austriaBefore.coversEnd("curated:S02001"))
        // CH has no pin: bundled whole, read as today.
        assertSame(EmbeddedGtfsPacks.all.first { it.sourceId == swiss }, before.first { it.sourceId == swiss })

        store.install(oebb, download(store))
        val snapshot = store.snapshot()
        val austria = snapshot.packs(EmbeddedGtfsPacks.all).first { it.sourceId == oebb }

        assertEquals(1L, snapshot.generation)
        assertNull(austria.borderZone)
        assertTrue(austria.coversEnd("curated:NC001632"))
        assertEquals(LocalDate.of(2027, 1, 9), austria.lastServiceDate)
    }

    @Test
    fun `without pins the packs are exactly the bundled ones`() {
        val store = ForeignPackStore(directory, ForeignPackPins.EMPTY)

        assertFalse(store.cleanUp())
        assertEquals(EmbeddedGtfsPacks.all, store.snapshot().packs(EmbeddedGtfsPacks.all))
        assertTrue(store.snapshot().installedFiles.isEmpty())
        assertTrue(EmbeddedGtfsPacks.all.all { it.borderZone == null && it.coversEnd("curated:NC000041") })
    }
}
