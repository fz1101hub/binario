package it.binario.app.data.live

import it.binario.app.data.core.*
import it.binario.app.data.gtfs.EmbeddedGtfsRealtimeScheduleResolver
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.GtfsArtifactDownloader
import it.binario.app.data.sources.InternationalSourceCatalog
import it.binario.app.data.provider.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.*

/** Opt-in functional checks against real feeds; excluded from deterministic unit-test tasks. */
class LiveProviderNetworkTest {
    private val clock = Clock.systemUTC()
    private val zone = ZoneId.of("Europe/Rome")

    @Test fun rfi() = runBlocking {
        selected("rfi")
        val catalog = RfiStationCatalog(HttpRfiMonitorFetcher(), clock)
        assertNotNull("RFI official catalogue: Appiano", catalog.placeId(listOf("Appiano")))
        assertNotNull("RFI official catalogue: Bressanone", catalog.placeId(listOf("Bressanone")))
        board(RfiStationBoardAdapter(), "milano-centrale", "Milano Centrale")
    }

    @Test fun rfiRegionalJourney() = runBlocking {
        selected("rfi")
        val route = it.binario.app.data.SavedRoute("live-regional", "Appiano → Roma Ostiense",
            it.binario.app.data.Station("curated:S08328", "Appiano Proba Petronia", "Roma", railwayCode = "S08328", aliases = listOf("APPIANO")),
            it.binario.app.data.Station("curated:S08406", "Roma Ostiense", "Roma", railwayCode = "S08406"))
        val query = it.binario.app.data.JourneyQuery.now(clock)
        val source = it.binario.app.data.repository.StationBoardEnrichedTrainDataSource(
            it.binario.app.data.planned.RfiProgrammedTimetableAdapter(), listOf(RfiStationBoardAdapter()))
        val result = source.loadRoute(route, it.binario.app.data.Direction.OUTBOUND, query)
        if (result !is it.binario.app.data.DataResult.Success) fail("RFI regional journey: $result")
        val services = (result as it.binario.app.data.DataResult.Success).snapshot.services
        assertTrue("RFI regional journey: no verified live departures", services.any {
            it.operationalStatusVerified && "rfi-station-board" in it.operationalSourceIds
        })
        println("LIVE_ACCEPTANCE RFI regional journey: matchedLiveDepartures=${services.count { it.operationalStatusVerified }}")
    }

    @Test fun italo() = runBlocking {
        selected("italo")
        board(ItaloPublicStationAdapter(), "roma-termini", "Roma Termini")
    }

    @Test fun viaggiatreno() = runBlocking {
        selected("viaggiatreno")
        board(ViaggiaTrenoRealtimeAdapter(), "S01700", "Milano Centrale", "S01700")
    }

    @Test fun sta() = runBlocking {
        selected("sta")
        tripUpdates(StaRealtimeAdapter(), "alto-adige-sta-gtfs")
    }

    @Test fun sncf() = runBlocking {
        selected("sncf")
        tripUpdates(SncfGtfsRealtimeAdapter(), "sncf-gtfs")
    }

    @Test fun europeanSleeper() = runBlocking {
        selected("european-sleeper")
        val source = requireNotNull(InternationalSourceCatalog.byId(SourceId("european-sleeper-gtfs")))
        val bytes = GtfsArtifactDownloader().open(source, allowBestEffortFallback = true).use { it.input.readBytes() }
        val resolver = EmbeddedGtfsRealtimeScheduleResolver({ java.io.ByteArrayInputStream(bytes) },
            listOf(EmbeddedGtfsPack(SourceId("european-sleeper"), "remote.zip")))
        tripUpdates(EuropeanSleeperGtfsRealtimeAdapter(), "european-sleeper", resolver)
    }

    private fun selected(source: String) {
        val requested = System.getProperty("binario.live.sources", "").orEmpty().split(',').toSet()
        assumeTrue("Source not selected: $source", source in requested)
    }

    private suspend fun board(provider: StationBoardProvider, id: String, name: String, code: String? = null) {
        val now = clock.instant()
        val result = provider.getStationBoard(StationBoardRequest(StationId(id), name, railwayCode = code,
            from = now.minusSeconds(3600), to = now.plusSeconds(20 * 3600), kind = BoardKind.DEPARTURES))
        if (result !is ProviderResult.Success) fail("${provider.descriptor.id.value}: $result")
        val entries = (result as ProviderResult.Success).value.entries
        assertTrue("${provider.descriptor.id.value}: no parseable departures", entries.isNotEmpty())
        assertTrue("${provider.descriptor.id.value}: departures contain no operational observation",
            entries.any { it.expectedTime != null || it.status != null })
        println("LIVE_ACCEPTANCE ${provider.descriptor.id.value}: departures=${entries.size}, operational=${entries.count { it.expectedTime != null || it.status != null }}")
    }

    private suspend fun tripUpdates(provider: TripUpdateProvider, staticSource: String, customResolver: EmbeddedGtfsRealtimeScheduleResolver? = null) {
        val now = clock.instant()
        val result = provider.getTripUpdates()
        if (result !is ProviderResult.Success) fail("${provider.descriptor.id.value}: $result")
        val updates = (result as ProviderResult.Success).value
        val fresh = updates.filter { !it.observedAt.isBefore(now.minusSeconds(300)) && !it.observedAt.isAfter(now.plusSeconds(60)) }
        assertTrue("${provider.descriptor.id.value}: no fresh updates with published timing", fresh.any { it.officialDelay != null || it.predictedAt != null })
        val resolver = customResolver ?: run {
            val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
            EmbeddedGtfsRealtimeScheduleResolver({ File(assets, it).inputStream() })
        }
        val dates = listOf(now.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate().minusDays(1))
        val matched = fresh.distinctBy { it.sourceTripId }.take(5).any { update ->
            (update.serviceDate?.let(::listOf) ?: dates).any { date ->
                resolver.resolve(SourceId(staticSource), update, date, zone) != null
            }
        }
        assertTrue("${provider.descriptor.id.value}: live trip/stop IDs do not match sampled embedded GTFS", matched)
        println("LIVE_ACCEPTANCE ${provider.descriptor.id.value}: freshUpdates=${fresh.size}, staticIdentityMatch=true")
    }
}
