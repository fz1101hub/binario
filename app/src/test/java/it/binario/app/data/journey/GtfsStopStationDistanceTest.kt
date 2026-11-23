package it.binario.app.data.journey

import it.binario.app.TestAssets
import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderResult
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        for (stop in listOf("8509265", "Parent8509265", "8516271:0:1", "Parent8516271")) {
            assertNull("$stop: ${byStop[stop]}", byStop[stop])
        }
        // Catalogue entries without coordinates keep their name binding: Zürich Altstetten, and Folsogno-Dissimo in Italy.
        assertEquals("curated:S03001", byStop["Parent8503001"])
        assertEquals("curated:NC000541", byStop["Parent8505589"])
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
}
