package it.binario.app.data.journey

import it.binario.app.TestAssets
import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.gtfs.EmbeddedUrbanStopIndex
import it.binario.app.data.gtfs.GtfsStationCodeIndex
import it.binario.app.data.gtfs.UrbanStopBadge
import it.binario.app.data.provider.JourneyEndpoint
import it.binario.app.data.sources.RailSourceCatalog
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The regional railways and city networks of `gtfs-transit/` (every build) and `gtfs-personal/` (personal builds,
 * which the unit tests are) with the planner, on the packs and the catalogue shipped in the APK. Expected trains and
 * lines belong to the bundled timetables: a pack refresh may change them, never the structure checked here.
 */
class EmbeddedGtfsTransitPacksTest {
    private val openAsset: (String) -> InputStream = { path -> TestAssets.open(path) }
    private val stations: List<Station> by lazy { EmbeddedCuratedRailStationCatalog(openAsset).load() }
    private val zone = ZoneId.of("Europe/Rome")
    /** A Tuesday inside the validity of every bundled pack. */
    private val tuesday = LocalDate.of(2026, 11, 10)
    private val packs: List<EmbeddedGtfsPack> = EmbeddedGtfsPacks.forBuild(personalSources = true)
    private val provider by lazy {
        EmbeddedGtfsJourneyProvider.create(openAsset, packs.filterNot(EmbeddedGtfsPack::isUrban) + packs.filter(EmbeddedGtfsPack::isUrban))
    }
    private val urbanStops by lazy { EmbeddedUrbanStopIndex(openAsset, packs.filter(EmbeddedGtfsPack::isUrban)) }

    /** A place picked in the station search: its id and the name the app passes along with it. */
    private data class Place(val id: StationId, val name: String)

    private fun station(name: String): Place = Place(StationId(stations.single { it.name == name }.id), name)

    private fun urbanStop(network: String, name: String): Place =
        urbanStops.search(name, limit = 20).first { it.station.name == name && it.station.id.startsWith("gtfs:$network:") }
            .let { Place(StationId(it.station.id), it.station.name) }

    private fun request(from: Place, to: Place, time: String) = JourneySearchRequest(
        origin = from.id,
        destination = to.id,
        departureAt = ZonedDateTime.of(tuesday, LocalTime.parse(time), zone),
        originEndpoint = JourneyEndpoint(from.name),
        destinationEndpoint = JourneyEndpoint(to.name)
    )

    private fun options(result: ProviderResult<List<JourneyOption>>): List<JourneyOption> =
        (result as? ProviderResult.Success)?.value ?: throw AssertionError("Expected a complete result, got $result")

    private fun JourneyOption.summary(): String = legs.joinToString(" | ") { leg ->
        val name = if (leg.mode == LegMode.WALK) "WALK" else "${leg.mode} ${leg.lineLabel ?: ""} ${leg.publicNumber ?: ""}".replace(Regex(" +"), " ").trim()
        "$name ${leg.originLabel} ${leg.scheduledDeparture.toLocalTime()}-${leg.scheduledArrival.toLocalTime()} ${leg.destinationLabel}"
    }

    private fun search(from: Place, to: Place, time: String): List<JourneyOption> = runBlocking {
        options(provider.searchJourneys(request(from, to, time))).also { found ->
            println("JOURNEY_EXAMPLE ${from.name} -> ${to.name} @ $time: ${found.size} options")
            found.take(3).forEach { println("  " + it.summary()) }
        }
    }

    @Test fun `Bari to Matera on the FAL trains`() {
        // The FAL pack is built from the National Access Point, which a refresh may find unavailable.
        assumeTrue("FAL pack not in this build", packs.any { it.sourceId.value == "fal-basilicata-puglia" })
        val found = search(station("Bari"), station("Matera Centrale FAL"), "08:00")
        assertTrue(found.isNotEmpty())
        val first = found.first()
        val trains = first.legs.filter { it.mode == LegMode.TRAIN }
        assertTrue(trains.isNotEmpty())
        assertTrue(trains.all { it.runId!!.value.startsWith("fal-basilicata-puglia:") })
    }

    @Test fun `Milano Centrale to Duomo walks to the metro`() {
        val found = search(station("Milano Centrale"), urbanStop("milano-atm-gtfs", "Duomo M3"), "08:00")
        val first = found.first()
        assertEquals(LegMode.WALK, first.legs.first().mode)
        val metro = first.legs.filter { it.mode == LegMode.METRO }
        assertEquals(1, metro.size)
        assertEquals("M3", metro.single().lineLabel)
        assertTrue(java.time.Duration.between(first.legs.first().scheduledDeparture, first.legs.last().scheduledArrival).toMinutes() <= 25)
    }

    @Test fun `Roma Termini to the Lido di Ostia takes the metro and the Roma-Lido railway`() {
        val lido = stopOf("cotral-ferro-gtfs", "Lido Centro")
        val found = search(station("Roma Termini"), lido, "08:00")
        val first = found.first()
        val modes = first.legs.map { it.mode }
        assertTrue("modes $modes", LegMode.METRO in modes && LegMode.TRAIN in modes)
        assertTrue(first.legs.filter { it.mode == LegMode.TRAIN }.all { it.runId!!.value.startsWith("cotral-ferro-gtfs:") })
        // The end is a stop of a railway pack in a city whose network takes part: the city lines are searched with the
        // railway packs from the start, so a Trenitalia train to Ostiense and a walk (Roma-Lido 08:45, Lido 09:15) does
        // not hide the metro B to Piramide and the 08:25 Roma-Lido.
        assertEquals(java.time.LocalTime.of(8, 55), first.legs.last().scheduledArrival.toLocalTime())
        assertEquals(java.time.LocalTime.of(8, 55), found.minOf { it.arrival.toLocalTime() })
    }

    @Test fun `Firenze Santa Maria Novella to the airport on the T2 tram`() {
        val found = search(station("Firenze S. M. Novella"), urbanStop("firenze-gest-gtfs", "Peretola Aeroporto"), "08:00")
        val first = found.first()
        val trams = first.legs.filter { it.mode == LegMode.TRAM }
        assertEquals(1, trams.size)
        assertEquals("T2", trams.single().lineLabel)
    }

    @Test fun `Lecce to Gallipoli, Udine to Cividale and Bologna to Sassuolo stay uncovered`() = runBlocking {
        // Ferrovie del Sud Est publishes an empty dataset on the access point, the Udine - Cividale railway only
        // expired buses, and the Emilia-Romagna asset none of the Tper lines to Sassuolo: no pack serves them.
        listOf("Lecce" to "Gallipoli", "Udine" to "Cividale del Friuli", "Bologna Centrale" to "Sassuolo").forEach { (from, to) ->
            val result = provider.searchJourneys(request(station(from), station(to), "08:00"))
            val trains = (result as? ProviderResult.Success)?.value.orEmpty()
            assertTrue("$from -> $to: $result", result is ProviderResult.Failure || trains.isEmpty())
            if (result is ProviderResult.Failure) {
                assertTrue(result.error.kind in setOf(ProviderErrorKind.NOT_SUPPORTED, ProviderErrorKind.NOT_FOUND))
            }
        }
    }

    @Test fun `metro and tram stops carry their badge in the stop search`() {
        val duomo = urbanStops.search("Duomo M3", limit = 10).first { it.station.id.startsWith("gtfs:milano-atm-gtfs:") }
        assertEquals(UrbanStopBadge.METRO, duomo.badge)
        val tram = urbanStops.search("Peretola", limit = 10).first { it.station.id.startsWith("gtfs:firenze-gest-gtfs:") }
        assertEquals(UrbanStopBadge.TRAM, tram.badge)
    }

    @Test fun `every transit network of the build is credited with its licence`() {
        val credits = EmbeddedGtfsPacks.transitCredits()
        val sources = EmbeddedGtfsPacks.forBuild(EmbeddedGtfsPacks.personalSourcesIncluded)
            .filter { it in EmbeddedGtfsPacks.transit }.map { it.sourceId }.distinct()
        assertEquals(sources.size, credits.size)
        sources.forEach { id ->
            val source = requireNotNull(RailSourceCatalog.byId(id))
            assertTrue(credits.any { it.startsWith(source.displayName + ": ") && source.attribution!! in it })
        }
    }

    /** Stations of the regional railway packs the catalogue resolves (by code, then name), and those a walk reaches. */
    @Test fun `station match rate of the regional railway packs`() {
        val resolver = GtfsStopStationResolver(stations)
        val located = stations.filter { it.latitude != null && it.longitude != null }
        packs.filter { !it.isUrban && !it.assetPath.startsWith("gtfs/") }.forEach { pack ->
            val stops = stationsOf(pack)
            var resolved = 0
            var walkable = 0
            val unresolved = ArrayList<String>()
            stops.forEach { stop ->
                // As the planner does: the station code, then the operator's suffixed catalogue name, then the name.
                val station = resolver.resolveCode(pack.stopCodeScheme, stop.code, stop.latitude, stop.longitude, stop.name)
                    ?: pack.catalogueNameSuffixes.firstNotNullOfOrNull { resolver.resolve("${stop.name} $it") }
                    ?: resolver.resolve(stop.name)
                if (station != null) resolved += 1
                else {
                    unresolved += stop.name
                    if (stop.latitude != null && stop.longitude != null && located.any {
                            GtfsStationCodeIndex.distanceKm(it.latitude!!, it.longitude!!, stop.latitude, stop.longitude) <= 0.4
                        }) walkable += 1
                }
            }
            println(
                "STATION_MATCH ${pack.sourceId.value} stations=${stops.size} resolved=$resolved " +
                    "share=${"%.3f".format(resolved.toDouble() / maxOf(stops.size, 1))} unresolvedWithin400m=$walkable " +
                    "unresolvedSample=${unresolved.take(8)}"
            )
            assertTrue(stops.isNotEmpty())
        }
    }

    /** A station of a railway pack the catalogue does not know, as the station search offers it. */
    private fun stopOf(network: String, name: String): Place {
        val stop = stationsOf(packs.single { it.sourceId.value == network }).first { it.name == name }
        return Place(StationId("gtfs:$network:${stop.id}"), stop.name)
    }

    private class Stop(val id: String, val code: String, val name: String, val latitude: Double?, val longitude: Double?)

    /** The stations of a pack: parent stations, and stops without one. */
    private fun stationsOf(pack: EmbeddedGtfsPack): List<Stop> {
        val rows = ArrayList<List<String>>()
        ZipInputStream(openAsset(pack.assetPath)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.substringAfterLast('/') == "stops.txt") {
                    zip.bufferedReader().lineSequence().forEach { rows += csv(it.removePrefix("\uFEFF")) }
                    break
                }
                entry = zip.nextEntry
            }
        }
        val header = rows.first()
        fun List<String>.at(column: String): String = header.indexOf(column).takeIf { it >= 0 }?.let { getOrNull(it) }.orEmpty().trim()
        return rows.drop(1)
            .filter { it.at("location_type") == "1" || (it.at("parent_station").isEmpty() && it.at("location_type") in setOf("", "0")) }
            .map { Stop(it.at("stop_id"), it.at("stop_code"), it.at("stop_name"), it.at("stop_lat").toDoubleOrNull(), it.at("stop_lon").toDoubleOrNull()) }
    }

    private fun csv(line: String): List<String> {
        val values = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && line.getOrNull(index + 1) == '"' -> { current.append('"'); index += 1 }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { values += current.toString(); current.clear() }
                else -> current.append(char)
            }
            index += 1
        }
        values += current.toString()
        return values
    }
}
