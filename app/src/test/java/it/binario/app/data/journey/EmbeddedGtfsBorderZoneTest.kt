package it.binario.app.data.journey

import it.binario.app.TestAssets
import it.binario.app.data.StationAutocompleteEngine
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
import it.binario.app.data.gtfs.parseGtfsCsvLine
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipInputStream
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

    /**
     * Design §6 ForeignCoverageParityTest, on the real full packs of every pinned country, stop by stop as the Android
     * matcher resolves them. A stop whose every train of the full pack is in the subset is *complete* (being in the
     * subset is not enough: a whole trip kept for its border stations also lists Zürich HB). (a) A station whose stops
     * are all complete is covered by the subset (a zone station, or one the pins do not list as served). (b) A stop with
     * trains missing from the subset resolves to a station the full pack serves outside the zone, which the subset does
     * not cover and the advisor offers: partial data is never shown as complete.
     */
    @Test
    fun `every stop Android resolves is covered by the subset exactly where the subset has all its trains`() = runBlocking {
        requireSubsets()
        var checked = 0
        for (entry in pins.borderOnly) {
            val id = entry.sourceId.value
            assertTrue("$id: zone within served", entry.servedStationIds.containsAll(entry.zoneStationIds))
            val subsetTrips = HashSet<String>()
            table(TestAssets.open("gtfs/$id.zip"), "trips.txt") { row, column -> subsetTrips += row[column("trip_id")] }
            val called = HashSet<String>()
            val incomplete = HashSet<String>()
            table(TestAssets.openFull("gtfs/$id.zip"), "stop_times.txt") { row, column ->
                val stop = row[column("stop_id")]
                called += stop
                if (row[column("trip_id")] !in subsetTrips) incomplete += stop
            }
            val whole = EmbeddedGtfsPacks.all.single { it.sourceId.value == id }
            val provider = EmbeddedGtfsJourneyProvider.create({ path -> TestAssets.openFull(path) }, listOf(whole))
            val bundled = asBundled(id)
            val partialShownComplete = ArrayList<String>()
            val stationsWithPartialStops = HashSet<String>()
            val resolvedStations = HashSet<String>()
            for ((stop, station) in provider.catalogueStationByStopForTest(entry.sourceId)) {
                if (stop !in called) continue
                resolvedStations += station
                if (stop in incomplete) {
                    stationsWithPartialStops += station
                    if (bundled.coversEnd(station) || !entry.serves(station)) partialShownComplete += "$stop -> $station"
                }
            }
            val completeLeftToFullPack = (resolvedStations - stationsWithPartialStops).filterNot(bundled::coversEnd).sorted()
            assertTrue("$id: resolves no stop", resolvedStations.isNotEmpty())
            assertTrue("$id: stops with trains missing from the subset at a covered station: $partialShownComplete",
                partialShownComplete.isEmpty())
            assertTrue("$id: stations with every train in the subset left to the full pack: $completeLeftToFullPack",
                completeLeftToFullPack.isEmpty())
            checked++
        }
        assertEquals(pins.borderOnly.size, checked)
    }

    /**
     * Maccagno has two catalogue entries 300 m apart (S13653 and S19005); the tools' matcher and the Android one place the
     * Swiss stop at different ones. Whichever the station search offers, the bundled Swiss subset still finds the TILO S30
     * trains of the Luino – Cadenazzo corridor: trip 25361, Cadenazzo 08:04 – Maccagno 08:34 on Tuesday 24 November 2026.
     */
    @Test
    fun `a search to either Maccagno entry finds the TILO trains of the Swiss subset`() = runBlocking {
        requireSubsets()
        assumeTrue("the Swiss pack is bundled whole (no pin, D2)", entry("ch-otd-gtfs")?.borderOnly == true)
        val search = StationAutocompleteEngine(stations)
        val maccagno = search.suggest("Maccagno").filter { it.name == "Maccagno" }
        assertTrue("two catalogue entries named Maccagno: ${maccagno.map(Station::id)}", maccagno.size >= 2)
        val cadenazzo = station("Cadenazzo")
        val provider = EmbeddedGtfsJourneyProvider.create(openAsset, listOf(asBundled("ch-otd-gtfs")))
        for (end in maccagno) {
            val found = options(provider.searchJourneys(request(cadenazzo, end)))
            val leg = found.flatMap { it.legs }.firstOrNull { it.publicNumber == "25361" }
            assertTrue("${end.id}: the S30 25361 of 08:04 is found: ${found.map { option -> option.legs.map { it.publicNumber } }}", leg != null)
        }
    }

    private fun table(stream: InputStream, name: String, consumer: (List<String>, (String) -> Int) -> Unit) {
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name != name) continue
                val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
                val header = parseGtfsCsvLine(reader.readLine().orEmpty().removePrefix("\uFEFF")).map(String::trim)
                val column = { key: String -> header.indexOf(key).also { require(it >= 0) { "$name: no $key" } } }
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) consumer(parseGtfsCsvLine(line), column)
                }
                return
            }
        }
        throw AssertionError("$name missing")
    }
}
