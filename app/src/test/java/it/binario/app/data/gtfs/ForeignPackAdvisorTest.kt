package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.ForeignPackFixtures.archive
import it.binario.app.data.gtfs.ForeignPackFixtures.oebb
import it.binario.app.data.gtfs.ForeignPackFixtures.packJson
import it.binario.app.data.gtfs.ForeignPackFixtures.pins
import it.binario.app.data.gtfs.ForeignPackFixtures.sncf
import it.binario.app.data.gtfs.ForeignPackFixtures.swiss
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design §3.3 with catalogue ids of the real stations; every pack pinned here (any mix is allowed by D2). */
class ForeignPackAdvisorTest {
    private val milano = "curated:S01700"
    private val bern = "curated:NC000041"
    private val brig = "curated:NC000063"
    private val lugano = "curated:NC000225"
    private val zurich = "curated:NC001381"
    private val geneve = "curated:NC000179"
    private val domodossola = "curated:S01003"
    private val como = "curated:S01307"
    private val brennero = "curated:S02001"
    private val innsbruck = "curated:NC000544"
    private val wien = "curated:NC001632"
    private val ventimiglia = "curated:S04501"
    private val nice = "curated:NC000556"
    private val paris = "curated:NC001074"

    private val swissBytes = archive("ch-otd-gtfs")
    private val allPinned = pins(
        packJson("ch-otd-gtfs", "CH", swissBytes,
            served = listOf("NC000041", "NC000063", "NC000225", "NC001381", "NC000179", "S01003", "S01307"),
            zone = listOf("NC000063", "NC000225", "S01003", "S01307")),
        packJson("oebb-gtfs", "AT", archive("oebb-gtfs"),
            served = listOf("S02001", "NC000544", "NC001632"), zone = listOf("S02001", "NC000544")),
        packJson("sncf-gtfs", "FR", archive("sncf-gtfs"),
            served = listOf("S04501", "NC000556", "NC001074", "NC000179"), zone = listOf("S04501", "NC000556"),
            lastServiceDate = LocalDate.of(2027, 4, 30))
    )
    private var today = LocalDate.of(2026, 11, 30)
    private val installed = mutableSetOf<SourceId>()
    private val declined = mutableSetOf<SourceId>()
    private fun advisor(pins: ForeignPackPins = allPinned) = ForeignPackAdvisor(pins, { it in installed }, { today }, { declined })

    @Test
    fun `Milano to Bern finds nothing and asks for Switzerland`() {
        val offer = advisor().afterSearch(milano, bern, found = false)!!

        assertEquals(ForeignPackOffer.Style.DIALOG, offer.style)
        assertEquals(listOf(swiss), offer.packs.map { it.sourceId })
        assertEquals(swissBytes.size.toLong(), offer.packs.single().sizeBytes)
        assertEquals("CH", offer.packs.single().country)
        assertTrue(offer.expired.isEmpty())
        // Either end.
        assertEquals(listOf(swiss), advisor().afterSearch(bern, milano, found = false)!!.packs.map { it.sourceId })
        // One end outside the zone with options found is no banner.
        assertNull(advisor().afterSearch(milano, bern, found = true))
    }

    @Test
    fun `the required border pairs never ask`() {
        for ((from, to) in listOf(domodossola to brig, como to lugano, brennero to innsbruck, ventimiglia to nice, milano to brig)) {
            for (found in listOf(true, false)) {
                assertNull("$from $to $found", advisor().afterSearch(from, to, found))
                assertNull("$to $from $found", advisor().afterSearch(to, from, found))
            }
        }
        assertTrue(advisor().forStation(brig).isEmpty())
        assertTrue(advisor().forStation(innsbruck).isEmpty())
    }

    @Test
    fun `both ends outside the same zone with results give a banner`() {
        val offer = advisor().afterSearch(bern, zurich, found = true)!!

        assertEquals(ForeignPackOffer.Style.BANNER, offer.style)
        assertEquals(listOf(swiss), offer.packs.map { it.sourceId })
        // Ends outside the zones of two different packs: no banner.
        assertNull(advisor().afterSearch(bern, wien, found = true))
        // The same pair without results is a dialog.
        assertEquals(ForeignPackOffer.Style.DIALOG, advisor().afterSearch(bern, zurich, found = false)!!.style)
    }

    @Test
    fun `a station served by two countries lists one row per country`() {
        val offer = advisor().afterSearch(geneve, milano, found = false)!!

        assertEquals(listOf(swiss, sncf), offer.packs.map { it.sourceId })
        assertEquals(listOf(swiss, sncf), advisor().forStation(geneve).map { it.sourceId })
        assertEquals(listOf(sncf), advisor().afterSearch(paris, milano, found = false)!!.packs.map { it.sourceId })
    }

    @Test
    fun `an expired pin is no offer and says the timetable has ended`() {
        today = LocalDate.of(2026, 12, 13)

        val offer = advisor().afterSearch(milano, bern, found = false)!!

        assertTrue(offer.packs.isEmpty())
        assertEquals(listOf(swiss), offer.expired.map { it.sourceId })
        assertEquals(LocalDate.of(2026, 12, 12), offer.expired.single().lastServiceDate)
        assertNull(advisor().afterSearch(bern, zurich, found = true))
        assertTrue(advisor().forStation(bern).isEmpty())
        // SNCF runs until April: still offered.
        assertEquals(listOf(sncf), advisor().afterSearch(paris, milano, found = false)!!.packs.map { it.sourceId })
    }

    @Test
    fun `an installed pack is never offered again`() {
        installed += swiss

        assertNull(advisor().afterSearch(milano, bern, found = false))
        assertNull(advisor().afterSearch(bern, zurich, found = true))
        assertEquals(listOf(sncf), advisor().afterSearch(geneve, milano, found = false)!!.packs.map { it.sourceId })
    }

    @Test
    fun `a declined pack is not asked again in this process`() {
        declined += swiss

        assertNull(advisor().afterSearch(milano, bern, found = false))
        assertEquals(listOf(sncf), advisor().afterSearch(geneve, milano, found = false)!!.packs.map { it.sourceId })
    }

    @Test
    fun `packs bundled whole are never offered`() {
        // D2: today only ÖBB has a confirmed licence; CH and SNCF are bundled whole and behave exactly as before.
        val sample = ForeignPackPins.parse(ForeignPackFixtures.sampleJson())

        assertNull(advisor(sample).afterSearch(milano, bern, found = false))
        assertNull(advisor(sample).afterSearch(milano, paris, found = false))
        assertTrue(advisor(sample).forStation(bern).isEmpty())
        assertEquals(listOf(oebb), advisor(sample).afterSearch(milano, wien, found = false)!!.packs.map { it.sourceId })
        assertNull(advisor(ForeignPackPins.EMPTY).afterSearch(milano, wien, found = false))
    }

    @Test
    fun `covers follows the border zone of the pack as the readers see it`() {
        val packs = EmbeddedGtfsPacks.forStore(EmbeddedGtfsPacks.all, allPinned, installed = setOf(oebb))
        val swissPack = packs.first { it.sourceId == swiss }
        val austrianPack = packs.first { it.sourceId == oebb }

        assertTrue(advisor().covers(swissPack, brig))
        assertTrue(!advisor().covers(swissPack, bern))
        assertTrue(advisor().covers(austrianPack, wien))
        assertTrue(advisor().covers(packs.first { it.sourceId.value == "trenitalia-gtfs" }, bern))
    }
}
