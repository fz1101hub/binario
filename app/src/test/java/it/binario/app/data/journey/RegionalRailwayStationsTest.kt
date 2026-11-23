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
 * Rail stops of the bundled EAV, ARST, Genova-Casella, Ferrovie del Gargano and Trentino packs that no catalogue station stood
 * for (some were bound to a namesake hundreds of kilometres away until name matches were bounded to 10 km) are
 * catalogue stations without codes, NC001820-NC001860, with the pack's name and position; the EAV terminus Montesanto
 * is an alias of Napoli Montesanto, 139 m away. The search offers them, each has a real train of its pack, and no other stop changes station.
 * See CURATED_STATION_CATALOG.md.
 */
class RegionalRailwayStationsTest {
    private val openAsset = { path: String -> TestAssets.open(path) }
    private val stations: List<Station> by lazy { EmbeddedCuratedRailStationCatalog(openAsset).load() }
    private val addedIds = (1820..1860).map { "curated:NC00$it" }.toSet()
    private val zone = ZoneId.of("Europe/Rome")
    /** A Tuesday inside the validity of every pack involved, as in [RenonRailwayStationsTest]. */
    private val tuesday = LocalDate.of(2026, 11, 24)

    private fun station(name: String): Station = stations.single { it.name == name }

    /** Each added station, by line, and a station of the same line its trains reach (pack, destination). */
    private val lines: List<Triple<String, String, List<String>>> = listOf(
        Triple("eav-gtfs", "Piedimonte Matese", listOf("S. Marco")),
        Triple("arst-gtfs", "Alghero", listOf("Punta Moro", "Mamuntanas", "Molafà", "San Giorgio (ARST)", "Arcone")),
        Triple("arst-gtfs", "Sorso", listOf("S. M. Di Pisa", "Rodda Quadda", "Crabulazzi", "Funtana Niedda")),
        Triple("arst-gtfs", "Nuoro", listOf("Lei")),
        Triple(
            "amt-genova-casella-gtfs", "Casella Paese",
            listOf(
                "Genova Piazza Manin", "San Pantaleo", "Sant'Antonino (AMT)", "Cappuccio", "Trensasco", "Campi", "Pino",
                "Torrazza", "Sardorella", "Vallombrosa", "Vicomorasso", "Sant'Olcese Chiesa", "Sant'Olcese Tullo",
                "Busalletta", "Molinetti", "Niusci", "Canova", "Casella Deposito", "Crocetta D'Orero"
            )
        ),
        Triple("amt-genova-casella-gtfs", "Genova Piazza Manin", listOf("Casella Paese")),
        Triple(
            "fdg-gargano", "Peschici Calenella",
            listOf("Bellariva", "Guardiola", "Mulino di Mare", "Murge Nere", "Rodi Porto", "Santa Barbara", "Sotto la Costa")
        ),
        Triple("fdg-gargano", "Foggia", listOf("Don Bosco", "Lucera 2")),
        Triple("trentino-gtfs", "Daolasa", listOf("Mezzana"))
    )

    @Test fun `the added stations are catalogue entries without codes`() {
        val added = stations.filter { it.id in addedIds }
        assertEquals(lines.flatMap { it.third }.sorted(), added.map(Station::name).sorted())
        assertTrue(added.all { it.railwayCode == null && it.trenitaliaLocationId == null && it.latitude != null && it.longitude != null })
        // Namesakes of existing stations carry the operator and keep the pack's name as an alias.
        assertEquals(listOf("San Giorgio"), station("San Giorgio (ARST)").aliases)
        assertEquals(listOf("Sant'Antonino"), station("Sant'Antonino (AMT)").aliases)
        // A Ferrovie del Gargano halt "Fermata X" is X, as the catalogue already writes Portone Perrone.
        assertEquals(listOf("Fermata Rodi Porto"), station("Rodi Porto").aliases)
        assertTrue("Montesanto" in stations.single { it.id == "curated:S09107" }.aliases)
    }

    @Test fun `the station search finds the added stations`() {
        val search = StationAutocompleteEngine(stations)
        for (name in listOf("Casella Paese", "Genova Piazza Manin", "Sant'Olcese Tullo", "Funtana Niedda", "Molafà", "S. Marco")) {
            assertEquals(name, search.suggest(name).first().name)
        }
        assertEquals("curated:NC001824", search.suggest("molafa").first().id)
        val santAntonino = search.suggest("Sant'Antonino").map(Station::id)
        assertTrue(santAntonino.toString(), "curated:NC001833" in santAntonino && "curated:NC001417" in santAntonino)
        // Napoli Montesanto, which carries the EAV name as an alias, is offered with the Montesanto near Ferrara.
        assertTrue("curated:S09107" in search.suggest("Montesanto").map(Station::id))
    }

    @Test fun `every added station has a train of its pack`() = runBlocking {
        val provider = EmbeddedGtfsJourneyProvider.create(openAsset)
        val failures = ArrayList<String>()
        for ((pack, destination, names) in lines) for (name in names) {
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
            val direct = options.firstOrNull { option -> option.legs.size == 1 && option.legs.single().runId!!.value.startsWith("$pack:") }
            if (direct == null || direct.legs.single().origin != StationId(station(name).id) ||
                direct.legs.single().destination != StationId(station(destination).id)
            ) failures += "$name -> $destination: ${options.map { o -> o.legs.map { "${it.runId?.value} ${it.origin.value}-${it.destination.value}" } }}"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun `the added stations take their own stops and no other stop of any pack`() = runBlocking {
        val adapter = EmbeddedGtfsRouteAdapter(openAsset = openAsset)
        val withAdded = GtfsStopStationResolver(stations)
        val withoutAdded = GtfsStopStationResolver(
            stations.filterNot { it.id in addedIds }
                .map { if (it.id == "curated:S09107") it.copy(aliases = it.aliases - "Montesanto") else it }
        )
        val changed = ArrayList<String>()
        for (pack in EmbeddedGtfsPacks.all) {
            val directory = adapter.stopDirectory(pack)
            for (index in 0 until directory.size) {
                val before = resolved(withoutAdded, pack, directory, index)?.id
                val after = resolved(withAdded, pack, directory, index)?.id
                if (before != after) changed += "${pack.sourceId.value} ${directory.ids[index]} $before -> ${after?.removePrefix("curated:")}"
            }
        }
        val expected = listOf("eav-gtfs 6706 null -> NC001820", "eav-gtfs 6801 null -> S09107") +
            listOf("F_22562", "F_22579", "F_22596", "F_22604", "F_22605", "F_22606", "F_22607", "F_22608", "F_22626", "F_22627")
                .mapIndexed { index, stop -> "arst-gtfs $stop null -> NC00${1821 + index}" } +
            (9780..9799).map { stop -> "amt-genova-casella-gtfs $stop null -> NC00${1831 + stop - 9780}" } +
            listOf("BELLARIVA", "DONBOSCO", "GUARDIOLA", "LUCERA2", "MULINO", "MURGENERE", "RODIPORTO", "SANTABARBARA", "SOTTOLACOSTA")
                .flatMapIndexed { index, stop ->
                    listOf("StopPlace", "ScheduledStopPoint").map { kind -> "fdg-gargano IT:ITF4:$kind:${stop}_Rete_FDG null -> NC00${1851 + index}" }
                } +
            "trentino-gtfs 5718 null -> NC001860"
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
