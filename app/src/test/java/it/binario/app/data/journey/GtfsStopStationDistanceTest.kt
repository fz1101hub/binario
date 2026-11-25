package it.binario.app.data.journey

import it.binario.app.TestAssets
import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.gtfs.ForeignPackPins
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderResult
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stop binds to a catalogue station by name only within [GtfsStopStationResolver.MAX_NAME_MATCH_KM] of it, when both
 * have coordinates: the Swiss pack's Guarda (Engadin) is not the Guarda near Ferrara, 266 km away, and Steinach SG is
 * not Steinach in Tirol, 159 km away, whatever the name rules say.
 */
class GtfsStopStationDistanceTest {
    private val guarda = Station("curated:S05998", "Guarda", "Guarda", railwayCode = "S05998", latitude = 44.611476, longitude = 11.623357)
    private val steinach = Station("curated:NC001488", "Steinach in Tirol", "Steinach in Tirol", latitude = 47.08896, longitude = 11.459997)
    private val altstetten = Station("curated:S03001", "Zurich Altstetten", "Zurich Altstetten", railwayCode = "S03001")
    private val resolver = GtfsStopStationResolver(listOf(guarda, steinach, altstetten))

    @Test fun `a name rule binds a stop only within the bound of the catalogue station`() {
        // The exact-name rule: the Swiss Guarda stop is 266 km from the catalogue's Guarda.
        assertNull(resolver.resolve("Guarda", 46.77152162, 10.15053152))
        assertEquals(guarda, resolver.resolve("Guarda", 44.6115, 11.6234))
        // 9 km away still binds (the largest legitimate distance today is 8.4 km); 11 km away does not.
        assertEquals(guarda, resolver.resolve("Guarda", 44.611476 + 9.0 / 111.2, 11.623357))
        assertNull(resolver.resolve("Guarda", 44.611476 + 11.0 / 111.2, 11.623357))
        // The South Tyrol bilingual rule: Steinach SG, by Lake Constance, is not Steinach in Tirol.
        assertNull(resolver.resolve("Steinach", 47.50081251, 9.44226382))
        assertEquals(steinach, resolver.resolve("Steinach", 47.0890, 11.4600))
    }

    @Test fun `a namesake beyond the bound does not make a name ambiguous`() {
        val sicily = Station("curated:S12034", "San Giorgio", "San Giorgio", railwayCode = "S12034", latitude = 38.173967, longitude = 14.944088)
        val sardinia = Station(
            "curated:NC001829", "San Giorgio (ARST)", "San Giorgio (ARST)", aliases = listOf("San Giorgio"),
            latitude = 40.69019, longitude = 8.470571
        )
        val ticino = Station("curated:NC001417", "Sant'Antonino", "Sant'Antonino", latitude = 46.15515, longitude = 8.980107)
        val genova = Station(
            "curated:NC001833", "Sant'Antonino (AMT)", "Sant'Antonino (AMT)", aliases = listOf("Sant'Antonino"),
            latitude = 44.433632, longitude = 8.939717
        )
        val namesakes = GtfsStopStationResolver(listOf(sicily, sardinia, ticino, genova))
        // The exact-name rule: each stop takes the one namesake within the bound.
        assertEquals(sardinia, namesakes.resolve("SAN GIORGIO", 40.6901903, 8.4705713))
        assertEquals(sicily, namesakes.resolve("S.GIORGIO", 38.1740, 14.9441))
        assertEquals(genova, namesakes.resolve("SANT'ANTONINO/", 44.433632, 8.939717))
        // The strict rule: the Swiss "S. Antonino" is still the Ticino station, 191 km from the Genova one.
        assertEquals(ticino, namesakes.resolve("S. Antonino", 46.16, 8.97))
        // Without a position both namesakes are candidates, and two within the bound stay ambiguous.
        assertNull(namesakes.resolve("San Giorgio"))
        val near = Station("curated:X", "San Giorgio", "San Giorgio", latitude = 40.70, longitude = 8.48)
        assertNull(GtfsStopStationResolver(listOf(sicily, sardinia, near)).resolve("SAN GIORGIO", 40.6901903, 8.4705713))
    }

    @Test fun `without coordinates on either side a name still binds as before`() {
        assertEquals(guarda, resolver.resolve("Guarda"))
        assertEquals(steinach, resolver.resolve("Steinach"))
        // A catalogue entry without coordinates cannot be checked: its unique name binds it, as before.
        assertEquals(altstetten, resolver.resolve("Zürich Altstetten", 47.39147, 8.48894))
    }

    private val openAsset = { path: String -> TestAssets.open(path) }
    private val stations: List<Station> by lazy { EmbeddedCuratedRailStationCatalog(openAsset).load() }
    private val swiss = SourceId("ch-otd-gtfs")

    @Test fun `the full Swiss pack binds neither Guarda nor Steinach SG to the Italian or Tyrolean namesake`() = runBlocking {
        val whole = EmbeddedGtfsPacks.all.single { it.sourceId == swiss }
        val provider = EmbeddedGtfsJourneyProvider.create({ path -> TestAssets.openFull(path) }, listOf(whole))
        val byStop = provider.catalogueStationByStopForTest(swiss)
        for (stop in listOf("ch:1:sloid:9265", "Parentch:1:sloid:9265", "ch:1:sloid:16271:0:1", "Parentch:1:sloid:16271")) {
            assertNull("$stop: ${byStop[stop]}", byStop[stop])
        }
        // Catalogue entries without coordinates keep their name binding: Zürich Altstetten, and Folsogno-Dissimo in Italy.
        assertEquals("curated:S03001", byStop["Parentch:1:sloid:3001"])
        assertEquals("curated:NC000541", byStop["Parentch:1:sloid:5589"])
        // Stops near their catalogue station keep it: Tirano.
        assertTrue("curated:S01440" in byStop.values)
    }

    @Test fun `a search to Guarda near Ferrara finds no Swiss train once the full Swiss pack is installed`() = runBlocking {
        val whole = EmbeddedGtfsPacks.all.single { it.sourceId == swiss }
        val provider = EmbeddedGtfsJourneyProvider.create({ path -> TestAssets.openFull(path) }, listOf(whole))
        val tirano = stations.single { it.id == "curated:S01440" }
        val ferraraGuarda = stations.single { it.id == guarda.id }
        val result = provider.searchJourneys(
            JourneySearchRequest(
                origin = StationId(tirano.id),
                destination = StationId(ferraraGuarda.id),
                departureAt = ZonedDateTime.of(LocalDate.of(2026, 11, 24), LocalTime.of(8, 0), ZoneId.of("Europe/Rome"))
            )
        )
        val found: List<JourneyOption> = (result as? ProviderResult.Success)?.value.orEmpty()
        assertTrue("Swiss trains to the Swiss Guarda shown for Guarda (FE): ${found.map { option -> option.legs.map { it.destination } }}",
            found.none { option -> option.legs.any { it.runId?.value?.startsWith("ch-otd-gtfs:") == true } })
    }

    @Test fun `the pins do not list Guarda near Ferrara or Steinach in Tirol among the Swiss stations`() {
        val pins = ForeignPackPins.parse(File("src/main/assets/${ForeignPackPins.ASSET_PATH}").readText())
        val entry = pins.entry(swiss) ?: throw AssertionError("no Swiss pin")
        assertFalse(entry.serves(guarda.id))
        assertFalse(entry.serves(steinach.id))
        assertTrue(entry.serves("curated:S01440"))
    }
}
