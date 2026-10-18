package it.binario.app.data.live

import it.binario.app.data.core.*
import it.binario.app.data.ServiceStatus
import it.binario.app.data.gtfs.EmbeddedGtfsRealtimeScheduleResolver
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.GtfsArtifactDownloader
import it.binario.app.data.gtfs.BoundedGtfsZipScanner
import it.binario.app.data.gtfs.GtfsCompactIndexLimits
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

    @Test fun viaggiatrenoGlobalNumber() = runBlocking {
        selected("viaggiatreno")
        val adapter = ViaggiaTrenoRealtimeAdapter(diagnosticViaggiaTrenoFetcher(), clock)
        val query = it.binario.app.data.repository.TrainNumberQuery("23263", clock.instant().atZone(zone).toLocalDate())
        val result = adapter.searchByNumber(query)
        val matches = when (result) { is ProviderResult.Success -> result.value; is ProviderResult.Partial -> result.value; else -> emptyList() }
        println("LIVE_ACCEPTANCE ViaggiaTreno global number 23263: result=${result.javaClass.simpleName}, matches=${matches.map { it.service.origin + ":" + it.service.destination + ":stops=" + it.service.stops.size }}")
        assertTrue("ViaggiaTreno direct number lookup returned no complete matching run: $result", matches.any { query.matches(it.service.number) && it.service.stops.size >= 2 })
    }

    @Test fun italoGlobalNumber() = runBlocking {
        selected("italo")
        val adapter = ItaloTrainNumberAdapter(fetch = { url ->
            val body = downloadItaloPublic(url)
            val root = org.json.JSONObject(body)
            println("LIVE_ACCEPTANCE Italo direct number schema: keys=${root.keySet()}, runKeys=${root.optJSONObject("TrainSchedule")?.keySet()}")
            body
        }, clock = clock)
        val query = it.binario.app.data.repository.TrainNumberQuery("9920", clock.instant().atZone(zone).toLocalDate())
        val result = adapter.searchByNumber(query)
        val matches = (result as? ProviderResult.Success)?.value.orEmpty()
        println("LIVE_ACCEPTANCE Italo global number 9920: result=${result.javaClass.simpleName}, matches=${matches.map { it.service.origin + ":" + it.service.destination + ":stops=" + it.service.stops.size }}")
        assertTrue("Italo direct number lookup returned no complete matching run: $result", matches.any { query.matches(it.service.number) && it.service.stops.size >= 2 })
    }

    @Test fun rfi() = runBlocking {
        selected("rfi")
        val fetcher = diagnosticRfiFetcher()
        val catalog = RfiStationCatalog(fetcher, clock)
        assertNotNull("RFI official catalogue: Appiano", catalog.placeId(listOf("Appiano")))
        assertNotNull("RFI official catalogue: Bressanone", catalog.placeId(listOf("Bressanone")))
        board(RfiStationBoardAdapter(fetcher = fetcher), "milano-centrale", "Milano Centrale")
        board(RfiStationBoardAdapter(fetcher = fetcher), "milano-centrale", "Milano Centrale", kind = BoardKind.ARRIVALS)
    }

    @Test fun rfiRegionalJourney() = runBlocking {
        selected("rfi")
        val route = it.binario.app.data.SavedRoute("live-regional", "Appiano → Roma Ostiense",
            it.binario.app.data.Station("curated:S08328", "Appiano Proba Petronia", "Roma", railwayCode = "S08328", aliases = listOf("APPIANO")),
            it.binario.app.data.Station("curated:S08406", "Roma Ostiense", "Roma", railwayCode = "S08406"))
        val query = it.binario.app.data.JourneyQuery.now(clock)
        val boardSummary = java.util.concurrent.ConcurrentHashMap<String, String>()
        val fetcher = diagnosticRfiFetcher()
        val boardProvider = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { url ->
            val html = fetcher.fetch(url)
            if (url.contains("/Monitor?")) {
                val doc = org.jsoup.Jsoup.parse(html)
                val rows = doc.select("tr[name=treno]")
                boardSummary[if (url.endsWith("True")) "arrivals" else "departures"] = "rows=${rows.size}, sample=" + rows.take(3).map { row ->
                    listOf("RTreno", "ROrario", "RRitardo").associateWith { id -> row.selectFirst("#$id")?.let { it.text() + "|" + it.attr("aria-label") } }
                }
            }
            html
        })
        val source = RfiLiveRouteDataSource(boardProvider)
        val result = source.loadRoute(route, it.binario.app.data.Direction.OUTBOUND, query)
        if (result !is it.binario.app.data.DataResult.Success) fail("RFI regional journey: $result; boards=$boardSummary")
        val services = (result as it.binario.app.data.DataResult.Success).snapshot.services
        assertTrue("RFI regional journey: no verified live departures; programmed=${services.take(3).map { it.number + ":" + it.scheduledDeparture }}; board=$boardSummary", services.any {
            it.operationalStatusVerified && "rfi-station-board" in it.operationalSourceIds
        })
        println("LIVE_ACCEPTANCE RFI regional journey: matchedLiveDepartures=${services.count { it.operationalStatusVerified }}")
        println("LIVE_ACCEPTANCE RFI 23263 paired boards: " + (services.singleOrNull { it.number == "23263" }?.let {
            "departure=${it.scheduledDeparture}, delay=${it.officialDelayMinutes}, arrival=${it.stops.last().scheduled}, arrivalDelay=${it.stops.last().delayMinutes}, arrivalVerified=${it.stops.last().operationalStatusVerified}"
        } ?: "not matched on both current boards"))
    }

    @Test fun rfiPublishedDelayAccuracy() = runBlocking {
        selected("rfi")
        val fetcher = diagnosticRfiFetcher()
        val place = RfiStationCatalog(fetcher, clock).placeId(listOf("Appiano"))
        assertNotNull("RFI accuracy: Appiano not resolved", place)
        val html = fetcher.fetch("https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=$place&arrivals=False")
        val route = it.binario.app.data.SavedRoute("accuracy", "Appiano", it.binario.app.data.Station("origin", "Appiano", "Roma"),
            it.binario.app.data.Station("destination", "Roma Ostiense", "Roma"))
        val services = RfiStationBoardAdapter().parseDepartures(html, "Appiano", "", route, it.binario.app.data.Direction.OUTBOUND)
        var compared = 0
        val mismatches = mutableListOf<String>()
        var reportedTrain = "23263 not present on the current board"
        org.jsoup.Jsoup.parse(html).select("tr[name=treno]").forEach { row ->
            val number = row.selectFirst("#RTreno")?.text()?.trim().orEmpty()
            val visible = row.selectFirst("#RRitardo")?.text()?.trim().orEmpty()
            val delay = Regex("^\\+?(\\d{1,3})\\s*(?:min(?:uti)?|['′])?$", RegexOption.IGNORE_CASE)
                .matchEntire(visible)?.groupValues?.get(1)?.toIntOrNull()
            val service = services.singleOrNull { it.number == number }
            if (number == "23263") reportedTrain = "23263 scheduled=${service?.scheduledDeparture}, visible=$visible, aria=${row.selectFirst("#RRitardo")?.attr("aria-label")}, parsed=${service?.officialDelayMinutes}, verified=${service?.operationalStatusVerified}"
            if (delay != null) {
                compared++
                if (service == null || service.officialDelayMinutes != delay || !service.operationalStatusVerified) {
                    mismatches += "$number: visible=$delay, parsed=${service?.officialDelayMinutes}"
                }
            }
        }
        println("LIVE_ACCEPTANCE RFI exact published delay: compared=$compared; $reportedTrain; mismatches=${mismatches.take(10)}")
        assertTrue("RFI accuracy: no numeric published delays to compare", compared > 0)
        assertTrue("RFI delay mismatch against visible station board: ${mismatches.take(10)}", mismatches.isEmpty())
    }

    @Test fun italo() = runBlocking {
        selected("italo")
        var shape = "not fetched"
        val upstream = HttpItaloPublicStationFetcher()
        val adapter = ItaloPublicStationAdapter(fetcher = ItaloPublicStationFetcher { slug ->
            val json = try { upstream.fetch(slug) } catch (error: java.io.IOException) {
                throw AssertionError("Italo live request: ${error.message}", error)
            }
            val root = org.json.JSONObject(json)
            shape = "keys=${root.keySet()}, LastUpdate=${root.optString("LastUpdate")}, rows=${root.optJSONArray("ListaTreniPartenza")?.length()}"
            json
        })
        // Overnight a single station can legitimately have no published departures.
        // Still require a real operational observation from one of the covered hubs.
        val failures = mutableListOf<String>()
        for ((slug, name) in listOf("roma-termini" to "Roma Termini", "milano-centrale" to "Milano Centrale", "napoli-centrale" to "Napoli Centrale")) {
            try {
                board(adapter, slug, name, details = { shape })
                return@runBlocking
            } catch (error: AssertionError) {
                failures += "$name: ${error.message}"
            }
        }
        fail("Italo: no verified operational board in sampled hubs; ${failures.joinToString("; ")}")
    }

    @Test fun viaggiatreno() = runBlocking {
        selected("viaggiatreno")
        board(ViaggiaTrenoRealtimeAdapter(diagnosticViaggiaTrenoFetcher()), "S01700", "Milano Centrale", "S01700")
    }

    @Test fun viaggiatrenoRegionalDelayAccuracy() = runBlocking {
        selected("viaggiatreno")
        val upstream = diagnosticViaggiaTrenoFetcher()
        val details = java.util.concurrent.ConcurrentHashMap<String, org.json.JSONObject>()
        val adapter = ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher { path ->
            upstream.fetch(path).also { if (path.startsWith("andamentoTreno/") && it.trim().startsWith("{")) details[path] = org.json.JSONObject(it) }
        })
        val route = it.binario.app.data.SavedRoute("vt-regional", "Appiano → Roma Ostiense",
            it.binario.app.data.Station("curated:S08328", "Appiano Proba Petronia", "Roma", railwayCode = "S08328"),
            it.binario.app.data.Station("curated:S08406", "Roma Ostiense", "Roma", railwayCode = "S08406"))
        val result = adapter.loadRoute(route, it.binario.app.data.Direction.OUTBOUND, it.binario.app.data.JourneyQuery.now(clock))
        if (result !is it.binario.app.data.DataResult.Success) fail("ViaggiaTreno regional: $result")
        val services = (result as it.binario.app.data.DataResult.Success).snapshot.services
        val verified = services.filter { it.operationalStatusVerified && it.status != ServiceStatus.CANCELLED }
        assertTrue("ViaggiaTreno regional: no verified operational train", verified.isNotEmpty())
        verified.forEach { service ->
            val id = service.id.split(':')
            val raw = requireNotNull(details["andamentoTreno/${id[1]}/${id[2]}/${id[3]}"])
            assertEquals("Published ViaggiaTreno delay differs for ${service.number}", raw.getInt("ritardo"), service.officialDelayMinutes)
        }
        println("LIVE_ACCEPTANCE ViaggiaTreno exact regional delay: verified=${verified.size}; " +
            (services.singleOrNull { it.number == "23263" }?.let { "23263 delay=${it.officialDelayMinutes}, verified=${it.operationalStatusVerified}, expected=${it.expectedDeparture}, observed=${it.operationalObservedAtEpochMillis}" }
                ?: "23263 not present in current source results"))
    }

    @Test fun viaggiatrenoMobileHtml() = runBlocking {
        selected("viaggiatreno-html")
        val upstream = HttpViaggiaTrenoStationFetcher()
        val adapter = ViaggiaTrenoStationAdapter(fetcher = ViaggiaTrenoStationFetcher { name ->
            try { upstream.fetch(name) } catch (error: java.io.IOException) {
                throw AssertionError("ViaggiaTreno mobile HTTPS: ${error.message}", error)
            }
        })
        board(adapter, "milano-centrale", "Milano Centrale")
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
        tripUpdates(EuropeanSleeperGtfsRealtimeAdapter(), "european-sleeper", resolver, tripIds(bytes))
    }

    private fun selected(source: String) {
        val requested = System.getProperty("binario.live.sources", "").orEmpty().split(',').toSet()
        assumeTrue("Source not selected: $source", source in requested)
    }

    private fun diagnosticRfiFetcher(): RfiMonitorFetcher = RfiMonitorFetcher { url ->
        try {
            HttpRfiMonitorFetcher().fetch(url)
        } catch (error: java.io.IOException) {
            val endpoint = java.net.URI(url)
            throw AssertionError("RFI HTTPS ${endpoint.host}${endpoint.path}: ${error::class.java.simpleName}: ${error.message}", error)
        }
    }

    private fun diagnosticViaggiaTrenoFetcher(): ViaggiaTrenoRealtimeFetcher = ViaggiaTrenoRealtimeFetcher { path ->
        try {
            HttpViaggiaTrenoRealtimeFetcher().fetch(path)
        } catch (error: java.io.IOException) {
            throw AssertionError("ViaggiaTreno public API: ${error::class.java.simpleName}: ${error.message}", error)
        }
    }

    private suspend fun board(provider: StationBoardProvider, id: String, name: String, code: String? = null, details: () -> String = { "" }, kind: BoardKind = BoardKind.DEPARTURES) {
        val now = clock.instant()
        val result = provider.getStationBoard(StationBoardRequest(StationId(id), name, railwayCode = code,
            from = now.minusSeconds(3600), to = now.plusSeconds(20 * 3600), kind = kind))
        if (result !is ProviderResult.Success) fail("${provider.descriptor.id.value}: $result")
        val entries = (result as ProviderResult.Success).value.entries
        assertTrue("${provider.descriptor.id.value}: no parseable departures; ${details()}", entries.isNotEmpty())
        assertTrue("${provider.descriptor.id.value}: departures contain no operational observation; ${details()}",
            entries.any { it.expectedTime != null || it.status != null })
        println("LIVE_ACCEPTANCE ${provider.descriptor.id.value}: departures=${entries.size}, operational=${entries.count { it.expectedTime != null || it.status != null }}")
    }

    private suspend fun tripUpdates(provider: TripUpdateProvider, staticSource: String, customResolver: EmbeddedGtfsRealtimeScheduleResolver? = null, customTripIds: Set<String>? = null) {
        val now = clock.instant()
        val result = provider.getTripUpdates()
        if (result !is ProviderResult.Success) fail("${provider.descriptor.id.value}: $result")
        val updates = (result as ProviderResult.Success).value
        val fresh = updates.filter { !it.observedAt.isBefore(now.minusSeconds(300)) && !it.observedAt.isAfter(now.plusSeconds(60)) }
        assertTrue("${provider.descriptor.id.value}: no fresh updates with published timing", fresh.any { it.officialDelay != null || it.predictedAt != null })
        var ids = customTripIds.orEmpty()
        val resolver = customResolver ?: run {
            val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
            ids = tripIds(File(assets, "gtfs/$staticSource.zip").readBytes())
            EmbeddedGtfsRealtimeScheduleResolver({ File(assets, it).inputStream() })
        }
        val dates = listOf(now.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate().minusDays(1))
        val candidates = fresh.filter { it.sourceTripId in ids && (it.officialDelay != null || it.predictedAt != null) }
        val matched = candidates.distinctBy { it.sourceTripId }.take(5).any { update ->
            (update.serviceDate?.let(::listOf) ?: dates).any { date ->
                resolver.resolve(SourceId(staticSource), update, date, zone) != null
            }
        }
        assertTrue("${provider.descriptor.id.value}: live trip/stop IDs do not match GTFS; fresh=${fresh.size}; matchingTripUpdates=${candidates.size}; liveSample=${fresh.take(2).map { it.sourceTripId + ":" + it.sourceStopId }}; staticSample=${ids.take(2)}", matched)
        println("LIVE_ACCEPTANCE ${provider.descriptor.id.value}: freshUpdates=${fresh.size}, staticIdentityMatch=true")
    }

    private fun tripIds(bytes: ByteArray): Set<String> = buildSet {
        val limits = GtfsCompactIndexLimits()
        BoundedGtfsZipScanner(limits).scan(java.io.ByteArrayInputStream(bytes), setOf("trips.txt")) { _, rows ->
            val index = rows.header.indexOf("trip_id")
            rows.forEachRow(limits.maxTrips) { row -> row.getOrNull(index)?.let(::add) }
        }
    }
}
