package it.binario.app.data.journey

import it.binario.app.TestAssets
import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.gtfs.ForeignPackAdvisor
import it.binario.app.data.gtfs.ForeignPackEntry
import it.binario.app.data.gtfs.ForeignPackOffer
import it.binario.app.data.gtfs.ForeignPackPins
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The border subsets bundled in the APK (design §1.3, §6), read as the app reads them: through the pins file
 * (`gtfs/foreign_packs.json`) and [EmbeddedGtfsPacks.forStore] with nothing installed. Skipped until the tools have
 * written the subsets and the pins (WP1); the full ZIPs come from `data/gtfs-foreign/android/` ([TestAssets.fullPackFile]).
 */
class EmbeddedGtfsBorderZoneTest {
    private val pinsFile = File("src/main/assets/${ForeignPackPins.ASSET_PATH}")
    private val pins: ForeignPackPins by lazy { ForeignPackPins.parse(pinsFile.readText()) }
    private val openAsset = { path: String -> TestAssets.open(path) }
    private val stations: List<Station> by lazy { EmbeddedCuratedRailStationCatalog(openAsset).load() }
    private val zone = ZoneId.of("Europe/Rome")
    /** A Tuesday inside the validity of every bundled pack (as in EmbeddedGtfsJourneyRealPacksTest). */
    private val tuesday = LocalDate.of(2026, 11, 24)

    private fun station(name: String): Station = stations.single { it.name == name }

    private fun request(from: Station, to: Station, time: String = "08:00") = JourneySearchRequest(
        origin = StationId(from.id),
        destination = StationId(to.id),
        departureAt = ZonedDateTime.of(tuesday, LocalTime.parse(time), zone)
    )

    private fun options(result: ProviderResult<List<JourneyOption>>): List<JourneyOption> = when (result) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Partial -> result.value
        else -> emptyList()
    }

    private fun notCovered(result: ProviderResult<List<JourneyOption>>) =
        result is ProviderResult.Failure && result.error.kind == ProviderErrorKind.NOT_SUPPORTED

    private fun entry(id: String): ForeignPackEntry? = pins.entry(SourceId(id))

    /** The pack as the app reads it with nothing installed: its border subset when it has a pin. */
    private fun asBundled(id: String): EmbeddedGtfsPack =
        EmbeddedGtfsPacks.forStore(EmbeddedGtfsPacks.all, pins, emptySet()).single { it.sourceId.value == id }

    private fun requireSubsets() = assumeTrue("the border subsets and pins are not bundled yet (WP1)", pinsFile.isFile)

    @Test
    fun `the four required border pairs find journeys on the bundled packs`() = runBlocking {
        requireSubsets()
        for ((pack, from, to) in listOf(
            Triple("sncf-gtfs", "Ventimiglia", "Nice Ville"),
            Triple("ch-otd-gtfs", "Domodossola", "Brig"),
            Triple("ch-otd-gtfs", "Como S. Giovanni", "Lugano"),
            Triple("oebb-gtfs", "Brennero Brenner", "Innsbruck Hbf.")
        )) {
            val provider = EmbeddedGtfsJourneyProvider.create(openAsset, listOf(asBundled(pack)))
            assertTrue("$from → $to on $pack", options(provider.searchJourneys(request(station(from), station(to)))).isNotEmpty())
            assertTrue("$to → $from on $pack", options(provider.searchJourneys(request(station(to), station(from)))).isNotEmpty())
            entry(pack)?.takeIf { it.borderOnly }?.let { pinned ->
                assertTrue("$from in the zone of $pack", pinned.inZone(station(from).id) || !pinned.serves(station(from).id))
                assertTrue("$to in the zone of $pack", pinned.inZone(station(to).id))
            }
        }
    }

    @Test
    fun `a subset covers no end beyond its zone and the full ZIP does`() = runBlocking {
        requireSubsets()
        val beyond = mapOf("ch-otd-gtfs" to "Bern", "oebb-gtfs" to "Wien Hbf", "sncf-gtfs" to "Paris Gare de Lyon")
        val inside = mapOf("ch-otd-gtfs" to "Brig", "oebb-gtfs" to "Innsbruck Hbf.", "sncf-gtfs" to "Nice Ville")
        var checked = 0
        for (pinned in pins.borderOnly) {
            val id = pinned.sourceId.value
            val far = beyond[id]?.let(::station)?.takeIf { pinned.serves(it.id) && !pinned.inZone(it.id) }
                ?: stations.first { pinned.serves(it.id) && !pinned.inZone(it.id) }
            val near = station(inside.getValue(id))
            val subset = EmbeddedGtfsJourneyProvider.create(openAsset, listOf(asBundled(id)))
            assertTrue("$id: ${far.name} is not a covered end", notCovered(subset.searchJourneys(request(near, far))))
            val fullFile = TestAssets.fullPackFile("gtfs/$id.zip")
            assertTrue("$id: the full ZIP is in data/gtfs-foreign/android", fullFile.path.contains("gtfs-foreign"))
            val whole = EmbeddedGtfsPacks.all.single { it.sourceId.value == id }
            val full = EmbeddedGtfsJourneyProvider.create({ path -> TestAssets.openFull(path) }, listOf(whole))
            assertFalse("$id: ${far.name} is covered by the full pack", notCovered(full.searchJourneys(request(near, far))))
            checked++
        }
        assertEquals(pins.borderOnly.size, checked)
    }

    @Test
    fun `Milano to Bern asks for Switzerland while only the border zone is installed`() = runBlocking {
        requireSubsets()
        val swiss = entry("ch-otd-gtfs")
        assumeTrue("the Swiss pack is bundled whole (no pin, D2)", swiss?.downloadable == true)
        val milano = station("Milano Centrale")
        val bern = station("Bern")
        val subset = EmbeddedGtfsJourneyProvider.create(openAsset, listOf(asBundled("ch-otd-gtfs")))
        assertTrue(notCovered(subset.searchJourneys(request(milano, bern))))
        val full = EmbeddedGtfsJourneyProvider.create({ path -> TestAssets.openFull(path) },
            listOf(EmbeddedGtfsPacks.all.single { it.sourceId.value == "ch-otd-gtfs" }))
        assertFalse(notCovered(full.searchJourneys(request(milano, bern))))
        val offer = ForeignPackAdvisor(pins, { false }, { tuesday }).afterSearch(milano.id, bern.id, found = false)
        assertEquals(ForeignPackOffer.Style.DIALOG, offer?.style)
        // Switzerland first; with every country pinned, also each other pack whose full timetable serves an end
        // outside its zone (the SNCF pack serves Bern with the TGV Lyria): the advisor's documented rule.
        val expected = pins.downloadable.filter { entry -> listOf(milano.id, bern.id).any { entry.serves(it) && !entry.inZone(it) } }
        assertEquals("ch-otd-gtfs", offer?.packs?.firstOrNull()?.sourceId?.value)
        assertEquals(expected.map { it.sourceId.value }, offer?.packs?.map { it.sourceId.value })
    }

    /** Design §6 ForeignCoverageParityTest: Android's own matcher resolves every station the pins say a full pack serves. */
    @Test
    fun `every served station of the pins is resolved by the Android matcher on the full pack`() = runBlocking {
        requireSubsets()
        for (entry in pins.packs) {
            val id = entry.sourceId.value
            val whole = EmbeddedGtfsPacks.all.single { it.sourceId.value == id }
            val provider = EmbeddedGtfsJourneyProvider.create({ path -> TestAssets.openFull(path) }, listOf(whole))
            val resolved = provider.catalogueStationsForTest(entry.sourceId).map { ForeignPackEntry.catalogueId(it) }.toSet()
            val missing = entry.servedStationIds - resolved
            // A served station the Android matcher does not resolve may only be a zone station: the app never asks to
            // download for it. Today: Pino-Tronzano (S01110) and Maccagno (S13653) of the Swiss pack, catalogue
            // homonyms that the two matchers settle differently.
            assertTrue("$id: served by the pins, outside the zone and not resolved on Android: ${missing - entry.zoneStationIds}",
                entry.zoneStationIds.containsAll(missing))
            assertTrue("$id: zone within served", entry.servedStationIds.containsAll(entry.zoneStationIds))
        }
    }
}
