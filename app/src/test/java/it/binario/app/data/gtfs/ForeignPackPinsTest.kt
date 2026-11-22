package it.binario.app.data.gtfs

import it.binario.app.data.gtfs.ForeignPackFixtures.oebb
import it.binario.app.data.gtfs.ForeignPackFixtures.packJson
import it.binario.app.data.gtfs.ForeignPackFixtures.pinsJson
import it.binario.app.data.gtfs.ForeignPackFixtures.sncf
import it.binario.app.data.gtfs.ForeignPackFixtures.swiss
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

class ForeignPackPinsTest {
    @Test
    fun `the sample pins list the three packs and pin only the licensed one`() {
        val pins = ForeignPackPins.parse(ForeignPackFixtures.sampleJson())

        assertEquals(listOf(swiss, oebb, sncf), pins.packs.map { it.sourceId })
        assertEquals(35.0, pins.radiusKm!!, 0.0)
        assertEquals(listOf(oebb), pins.downloadable.map { it.sourceId })
        assertEquals(listOf(oebb), pins.borderOnly.map { it.sourceId })
        val austria = pins.entry(oebb)!!
        assertEquals("AT", austria.country)
        assertEquals(LocalDate.of(2026, 12, 12), austria.lastServiceDate)
        assertEquals(1_409_875L, austria.android!!.sizeBytes)
        assertEquals(
            "https://github.com/fz1101hub/binario/releases/download/gtfs-full-oebb-gtfs-64764fcb68ec/oebb-gtfs.zip",
            austria.android!!.url
        )
        // D2: an unpinned pack is bundled whole and offers nothing.
        val switzerland = pins.entry(swiss)!!
        assertFalse(switzerland.borderOnly)
        assertNull(switzerland.android)
        assertFalse(switzerland.downloadable)
    }

    @Test
    fun `station ids compare with or without the app prefix`() {
        val austria = ForeignPackPins.parse(ForeignPackFixtures.sampleJson()).entry(oebb)!!

        assertTrue(austria.inZone("curated:NC000544"))
        assertTrue(austria.inZone("NC000544"))
        assertTrue(austria.serves("curated:NC001632"))
        assertFalse(austria.inZone("curated:NC001632"))
        assertFalse(austria.serves("curated:S01700"))
        assertFalse(austria.inZone(null))
    }

    @Test
    fun `a pin is expired after its last service day`() {
        val austria = ForeignPackPins.parse(ForeignPackFixtures.sampleJson()).entry(oebb)!!

        assertFalse(austria.expiredOn(LocalDate.of(2026, 12, 12)))
        assertTrue(austria.expiredOn(LocalDate.of(2026, 12, 13)))
    }

    @Test
    fun `the host constants are the ones the privacy documents name`() {
        assertEquals("https://github.com/fz1101hub/binario/releases/download/", ForeignPackPins.GITHUB_RELEASE_URL_PREFIX)
        assertEquals("github.com", ForeignPackPins.GITHUB_HOST)
        assertEquals("release-assets.githubusercontent.com", ForeignPackPins.GITHUB_RELEASE_DOWNLOAD_HOST)
        assertEquals("objects.githubusercontent.com", ForeignPackPins.GITHUB_LEGACY_RELEASE_DOWNLOAD_HOST)
        assertEquals("gtfs/foreign_packs.json", ForeignPackPins.ASSET_PATH)
    }

    @Test
    fun `no pins file means no foreign pack logic`() {
        val pins = ForeignPackPins.load { throw FileNotFoundException(it) }

        assertSame(ForeignPackPins.EMPTY, pins)
        assertTrue(pins.packs.isEmpty())
    }

    @Test
    fun `an invalid pins file is not used at all`() {
        val broken = ForeignPackFixtures.sampleJson().replace("gtfs-full-oebb-gtfs-64764fcb68ec", "gtfs-full-oebb-gtfs-000000000000")

        assertSame(ForeignPackPins.EMPTY, ForeignPackPins.load { ByteArrayInputStream(broken.toByteArray()) })
        assertSame(ForeignPackPins.EMPTY, ForeignPackPins.load { ByteArrayInputStream("{".toByteArray()) })
    }

    @Test
    fun `every rule of the pins file is enforced`() {
        val bytes = ForeignPackFixtures.archive("oebb-gtfs")
        val valid = packJson("oebb-gtfs", "AT", bytes, listOf("A", "B"), listOf("A"))
        ForeignPackPins.parse(pinsJson(valid))
        val sha = ForeignPackFixtures.sha256(bytes)
        val tag = "gtfs-full-oebb-gtfs-${sha.take(12)}"
        val broken = mapOf(
            "tag of another digest" to valid.replace(tag, "gtfs-full-oebb-gtfs-${"0".repeat(12)}"),
            "tag of another pack" to valid.replace("\"tag\":\"$tag\"", "\"tag\":\"gtfs-full-sncf-gtfs-${sha.take(12)}\""),
            "uppercase digest" to valid.replace(sha, sha.uppercase()),
            "short digest" to valid.replace("\"sha256\":\"$sha\"", "\"sha256\":\"${sha.take(63)}\""),
            "plain http" to valid.replace("https://github.com/", "http://github.com/"),
            "another host" to valid.replace("https://github.com/", "https://example.com/"),
            "another repository" to valid.replace("fz1101hub/binario/releases", "someone/binario/releases"),
            "asset with a path" to valid.replace("\"asset\":\"oebb-gtfs.zip\"", "\"asset\":\"../oebb-gtfs.zip\""),
            "zero size" to valid.replace("\"sizeBytes\":${bytes.size}", "\"sizeBytes\":0"),
            "fractional size" to valid.replace("\"sizeBytes\":${bytes.size}", "\"sizeBytes\":${bytes.size}.5"),
            "oversized" to valid.replace("\"sizeBytes\":${bytes.size}", "\"sizeBytes\":${ForeignPackPins.MAX_PACK_BYTES + 1}"),
            "zone outside served" to valid.replace("\"zoneStationIds\":[\"A\"]", "\"zoneStationIds\":[\"C\"]"),
            "prefixed ids" to valid.replace("\"zoneStationIds\":[\"A\"]", "\"zoneStationIds\":[\"curated:A\"]"),
            "no borderOnly" to valid.replace(",\"borderOnly\":true", ""),
            "borderOnly as text" to valid.replace("\"borderOnly\":true", "\"borderOnly\":\"true\""),
            "bad date" to valid.replace("\"lastServiceDate\":\"2026-12-12\"", "\"lastServiceDate\":\"12/12/2026\""),
            "lowercase country" to valid.replace("\"country\":\"AT\"", "\"country\":\"at\""),
            "bad source id" to valid.replace("\"sourceId\":\"oebb-gtfs\"", "\"sourceId\":\"OEBB GTFS\"")
        )
        for ((label, pack) in broken) {
            try {
                ForeignPackPins.parse(pinsJson(pack))
                fail("accepted: $label")
            } catch (_: ForeignPackPinsException) {
                // expected
            }
        }
        for ((label, json) in mapOf(
            "schema 2" to pinsJson(valid).replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            "another repository field" to pinsJson(valid).replace("\"repository\":\"fz1101hub/binario\"", "\"repository\":\"x/y\""),
            "repeated pack" to pinsJson(valid, valid),
            "no packs" to "{\"schemaVersion\":1}"
        )) {
            try {
                ForeignPackPins.parse(json)
                fail("accepted: $label")
            } catch (_: ForeignPackPinsException) {
                // expected
            }
        }
    }

    @Test
    fun `any mix of pinned and unpinned packs is read`() {
        val bytes = ForeignPackFixtures.archive("x")
        for (pinned in 0..3) {
            val packs = listOf("ch-otd-gtfs" to "CH", "oebb-gtfs" to "AT", "sncf-gtfs" to "FR").mapIndexed { index, (id, country) ->
                packJson(id, country, if (index < pinned) bytes else null, listOf("A"), listOf("A"))
            }
            val pins = ForeignPackPins.parse(pinsJson(*packs.toTypedArray()))
            assertEquals(3, pins.packs.size)
            assertEquals(pinned, pins.downloadable.size)
        }
    }

    /**
     * The pins file of this build, when there is one (written by the tools with the border subsets): it must pass the
     * app's own rules, or the app would read the subsets as whole packs and never offer a download.
     */
    @Test
    fun `the bundled pins file passes the app's rules`() {
        val asset = File("src/main/assets/${ForeignPackPins.ASSET_PATH}")
        assumeTrue("no pins file in this build", asset.isFile)
        val pins = ForeignPackPins.parse(asset.readText())
        val foreign = setOf(swiss, oebb, sncf)
        assertEquals(foreign, pins.packs.map { it.sourceId }.toSet())
        for (entry in pins.packs) {
            val pack = EmbeddedGtfsPacks.all.firstOrNull { it.sourceId == entry.sourceId }
            assertNotNull(entry.sourceId.value, pack)
            assertTrue(File("src/main/assets/${pack!!.assetPath}").isFile)
            if (entry.borderOnly) assertNotNull("${entry.sourceId.value}: border subset without a pin", entry.android)
            if (entry.android != null) assertEquals("${entry.sourceId.value}.zip", entry.android!!.asset)
        }
    }
}
