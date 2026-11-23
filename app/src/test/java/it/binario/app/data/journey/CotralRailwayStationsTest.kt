package it.binario.app.data.journey

import it.binario.app.TestAssets
import it.binario.app.data.Station
import it.binario.app.data.StationAutocompleteEngine
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.gtfs.GtfsStopCodeScheme
import it.binario.app.data.gtfs.GtfsStopDirectory
import it.binario.app.data.provider.JourneyPreferences
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.WalkingSpeed
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stations of the Roma-Lido and Roma Nord (Roma-Civita Castellana-Viterbo) railways in the Cotral pack of personal
 * builds, which no catalogue station stood for (Acqua Acetosa and Montebello were bound to namesakes 18.7 and 393 km
 * away until name matches were bounded to 10 km), are catalogue stations without codes, NC001861-NC001903, with the
 * pack's name and position. Each has a real train of the pack, and no stop of another pack changes station.
 * See CURATED_STATION_CATALOG.md.
 */
class CotralRailwayStationsTest {
    private val openAsset = { path: String -> TestAssets.open(path) }
    private val stations: List<Station> by lazy { EmbeddedCuratedRailStationCatalog(openAsset).load() }
    private val cotralIds = (1861..1903).map { "curated:NC00$it" }.toSet()
    private val zone = ZoneId.of("Europe/Rome")
    /** A Tuesday inside the validity of the Cotral pack, as in [RenonRailwayStationsTest]. */
    private val tuesday = LocalDate.of(2026, 11, 24)

    private fun station(name: String): Station = stations.single { it.name == name }

    /** Each added station, by line, and a station of the same line its trains reach. */
    private val lines: List<Pair<String, List<String>>> = listOf(
        "Cristoforo Colombo" to listOf(
            "Porta San Paolo", "San Paolo (Cotral)", "EUR Magliana", "Tor Di Valle", "Vitinia", "Casal Bernocchi Centro Giano",
            "Acilia", "Acilia Sud", "Ostia Antica", "Lido Nord", "Lido Centro", "Stella Polare", "Castelfusano"
        ),
        "Porta San Paolo" to listOf("Cristoforo Colombo"),
        "Montebello (Cotral)" to listOf(
            "Flaminio", "Euclide", "Acqua Acetosa (Cotral)", "Campi Sportivi", "Monte Antenne", "Tor Di Quinto", "Due Ponti",
            "Grotta Rossa", "Saxa Rubra", "Centro RAI", "Labaro", "La Celsa", "Prima Porta", "Giustiniana"
        ),
        "Flaminio" to listOf("Montebello (Cotral)"),
        "Catalano" to listOf("Morlupo", "Magliano Romano", "Rignano Flaminio", "Sant'Oreste", "Pian Paradiso"),
        "Viterbo" to listOf(
            "Catalano", "Faleri", "Fabrica", "Vignanello", "Vallerano", "Soriano", "Vitorchiano", "La Fornacchia", "Bagnaia"
        )
    )

    @Test fun `the Cotral stations are catalogue entries without codes`() {
        val added = stations.filter { it.id in cotralIds }
        assertEquals(lines.flatMap { it.second }.distinct().sorted(), added.map(Station::name).sorted())
        assertTrue(added.all { it.railwayCode == null && it.trenitaliaLocationId == null && it.latitude!! in 41.7..42.5 })
        assertEquals(listOf("Acqua Acetosa"), station("Acqua Acetosa (Cotral)").aliases)
        assertEquals(listOf("Montebello"), station("Montebello (Cotral)").aliases)
        assertEquals("curated:NC001875", StationAutocompleteEngine(stations).suggest("Acqua Acetosa (Cotral)").first().id)
    }

    @Test fun `every Cotral station has a train of the pack`() = runBlocking {
        val provider = EmbeddedGtfsJourneyProvider.create(openAsset)
        val failures = ArrayList<String>()
        for ((destination, names) in lines) for (name in names) {
            val result = provider.searchJourneys(
                JourneySearchRequest(
                    origin = StationId(station(name).id),
                    destination = StationId(station(destination).id),
                    departureAt = ZonedDateTime.of(tuesday, LocalTime.of(5, 0), zone),
                    // Neighbouring stops of these lines are 200-400 m apart: no walk to the previous stop of the same train.
                    preferences = JourneyPreferences(walkingSpeed = WalkingSpeed.SLOW, maxWalkMinutes = 5)
                )
            )
            val options = (result as? ProviderResult.Success)?.value.orEmpty()
            val direct = options.firstOrNull { option -> option.legs.size == 1 && option.legs.single().runId!!.value.startsWith("cotral-ferro-gtfs:") }
            if (direct == null || direct.legs.single().origin != StationId(station(name).id) ||
                direct.legs.single().destination != StationId(station(destination).id)
            ) failures += "$name -> $destination: ${options.map { o -> o.legs.map { "${it.runId?.value} ${it.origin.value}-${it.destination.value}" } }}"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun `the Cotral stations take the Cotral stops and no other stop of any pack`() = runBlocking {
        val adapter = EmbeddedGtfsRouteAdapter(openAsset = openAsset)
        val withAdded = GtfsStopStationResolver(stations)
        val withoutAdded = GtfsStopStationResolver(stations.filterNot { it.id in cotralIds })
        val changed = ArrayList<String>()
        for (pack in EmbeddedGtfsPacks.all) {
            val directory = adapter.stopDirectory(pack)
            for (index in 0 until directory.size) {
                val before = resolved(withoutAdded, pack, directory, index)?.id
                val after = resolved(withAdded, pack, directory, index)
                if (before != after?.id) changed += "${pack.sourceId.value} ${directory.names[index]} $before -> ${after?.name}"
            }
        }
        // Every Cotral stop but the three already bound (Civita Castellana, Corchiano, Viterbo), each to its namesake.
        val expected = lines.flatMap { it.second }.distinct().map { name -> "cotral-ferro-gtfs ${name.removeSuffix(" (Cotral)")} null -> $name" }
        assertEquals(expected.sorted(), changed.sorted())
    }

    /** The catalogue station of one stop, by the rules of the planner's stop identities (PackStops.build). */
    private fun resolved(resolver: GtfsStopStationResolver, pack: EmbeddedGtfsPack, directory: GtfsStopDirectory, index: Int): Station? {
        val name = directory.names[index]
        val latitude = directory.latitude(index)
        val longitude = directory.longitude(index)
        val byCode = if (pack.stopCodeScheme == GtfsStopCodeScheme.NONE) null
        else resolver.resolveCode(pack.stopCodeScheme, directory.codes[index], latitude, longitude, name)
        return byCode
            ?: pack.catalogueNameSuffixes.firstNotNullOfOrNull { suffix -> resolver.resolve("$name $suffix", latitude, longitude) }
            ?: resolver.resolve(name, latitude, longitude)
            ?: resolver.nearbyStation(name, latitude, longitude)
    }
}
