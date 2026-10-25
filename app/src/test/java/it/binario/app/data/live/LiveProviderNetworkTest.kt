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

    @Test fun romaTerminiBoardAndCrossBorderIdentity() = runBlocking {
        selected("viaggiatreno")
        val upstream = diagnosticViaggiaTrenoFetcher()
        val adapter = ViaggiaTrenoRealtimeAdapter(upstream, clock)
        val started = System.nanoTime()
        board(adapter, "S08409", "Roma Termini", "S08409")
        println("LIVE_ACCEPTANCE Roma Termini board millis=${(System.nanoTime() - started) / 1_000_000}")
        val now = clock.instant()
        val result = adapter.getStationBoard(StationBoardRequest(StationId("S01700"), "Milano Centrale", railwayCode = "S01700",
            from = now.minusSeconds(600), to = now.plusSeconds(8 * 3600), kind = BoardKind.DEPARTURES))
        val entries = (result as? ProviderResult.Success)?.value?.entries.orEmpty()
        println("LIVE_ACCEPTANCE Milano destinations=${entries.map { it.publicNumber + ":" + it.destinationOrOriginLabel }}")
        val candidates = entries.filter { it.destinationOrOriginLabel?.contains(Regex("Locarno|Lugano|Bellinzona|Mendrisio", RegexOption.IGNORE_CASE)) == true }
        for (entry in candidates.take(3)) {
            val native = entry.nativeRunIds[SourceId("viaggiatreno")] ?: continue
            val (origin, number, date) = native.split(':')
            val run = org.json.JSONObject(upstream.fetch("andamentoTreno/$origin/$number/$date"))
            val calls = run.optJSONArray("fermate") ?: continue
            val stops = (0 until calls.length()).mapNotNull(calls::optJSONObject)
            println("LIVE_ACCEPTANCE Cross-border $number stops=${stops.map { it.optString("id") + ":" + it.optString("stazione") }}")
            assertEquals("Mendrisio native identity changed", "S13143", stops.singleOrNull { it.optString("stazione").equals("MENDRISIO", true) }?.optString("id"))
        }
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val static = it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter({ File(assets, it).inputStream() },
            packs = it.binario.app.data.gtfs.EmbeddedGtfsPacks.all.filter { it.sourceId.value == "lombardia-trenord-gtfs" })
        val route = it.binario.app.data.SavedRoute("cross-border-probe", "Milano–Mendrisio",
            it.binario.app.data.Station("S01700", "Milano Centrale", "Milano", railwayCode = "S01700"),
            it.binario.app.data.Station("NC000977", "Mendrisio", "Mendrisio"))
        val schedule = static.loadRoute(route, it.binario.app.data.Direction.OUTBOUND,
            it.binario.app.data.JourneyQuery(now.atZone(zone).toLocalDate(), LocalTime.NOON)) as it.binario.app.data.DataResult.Success
        val upcoming = schedule.snapshot.services.filter { it.scheduledDepartureDateTime?.isBefore(now.atZone(zone).toLocalDateTime()) == false }
        for (service in upcoming.ifEmpty { schedule.snapshot.services }.take(2)) {
            val number = service.number.substringAfterLast(" - ").trim()
            val refs = upstream.fetch("cercaNumeroTrenoTrenoAutocomplete/$number")
            println("LIVE_ACCEPTANCE Cross-border scheduled $number: nativeReferences=${refs.take(600)}")
            val direct = adapter.searchByNumber(it.binario.app.data.repository.TrainNumberQuery(number, now.atZone(zone).toLocalDate()))
            val matches = when (direct) { is ProviderResult.Success -> direct.value; is ProviderResult.Partial -> direct.value; else -> emptyList() }
            println("LIVE_ACCEPTANCE Cross-border number $number: runs=${matches.map { it.service.origin + ":" + it.service.destination + ":stops=" + it.service.stops.map { stop -> stop.station } }}")
        }
    }

    @Test fun viaggiatrenoGlobalNumber() = runBlocking {
        selected("viaggiatreno")
        val upstream = diagnosticViaggiaTrenoFetcher()
        var publishedReferences = false
        val adapter = ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher { path ->
            upstream.fetch(path).also { body ->
                if (path.startsWith("cercaNumero")) {
                    if (body.contains('|')) publishedReferences = true
                    println("LIVE_ACCEPTANCE ViaggiaTreno autocomplete $path: ${body.take(1200)}")
                }
            }
        }, clock)
        val date = clock.instant().atZone(zone).toLocalDate()
        val request = StationBoardRequest(StationId("global-number-fixture"), "Milano Centrale", railwayCode = "S01700",
            from = clock.instant(), to = clock.instant().plusSeconds(6 * 3600), kind = BoardKind.DEPARTURES)
        val board = adapter.getStationBoard(request)
        val entries = when (board) { is ProviderResult.Success -> board.value.entries; is ProviderResult.Partial -> board.value.entries; else -> emptyList() }
        val numbers = (entries.mapNotNull { it.publicNumber } + "23263").distinct()
        for (number in numbers.take(7)) {
            val query = it.binario.app.data.repository.TrainNumberQuery(number, date)
            val result = adapter.searchByNumber(query)
            val matches = when (result) { is ProviderResult.Success -> result.value; is ProviderResult.Partial -> result.value; else -> emptyList() }
            println("LIVE_ACCEPTANCE ViaggiaTreno global number ${query.number}: result=${result.javaClass.simpleName}, matches=${matches.map { it.service.origin + ":" + it.service.destination + ":stops=" + it.service.stops.size }}")
            if (matches.any { query.matches(it.service.number) && it.service.stops.size >= 2 }) return@runBlocking
        }
        assumeTrue("ViaggiaTreno explicitly published no current native train references", publishedReferences)
        fail("ViaggiaTreno direct lookup did not resolve any of the currently published numbers")
    }

    @Test fun italoCurrentStationCatalogue() = runBlocking {
        selected("italo")
        val html = downloadItaloPublic("${ItaloPublicStationAdapter.BASE_URL}/it/stazione")
        val document = org.jsoup.Jsoup.parse(html)
        val catalogue = parseItaloStationCatalog(html)
        assertFalse("Current Italo station-to-url JSON is missing", document.select("[data-ntv-name=station-to-url][data-ntv-type=Json]").isEmpty())
        assertTrue("Current catalogue is not parsed beyond the old fallback", catalogue.size > ItaloPublicStationFallbacks.all.size)
        println("LIVE_ACCEPTANCE Italo current catalogue: distinctCodes=${catalogue.size}, extraCodes=${catalogue.count { row -> ItaloPublicStationFallbacks.all.none { it.code == row.code } }}")
        val fetcher = HttpItaloPublicStationFetcher { url -> if (url.endsWith("/it/stazione")) html else downloadItaloPublic(url) }
        for (names in listOf(listOf("Ancona"), listOf("Genova Piazza Principe"), listOf("Genova Brignole"),
            listOf("Treviso Centrale"), listOf("Bologna Centrale"))) {
            val slug = fetcher.resolveStationSlug(names)
            assertNotNull("Italo station unresolved from current catalogue: $names", slug)
            val station = catalogue.single { it.slug == slug }
            val root = org.json.JSONObject(fetcher.fetch(slug!!))
            assertTrue("Unexpected Italo station board schema for $slug", root.has("IsEmpty"))
            println("LIVE_ACCEPTANCE Italo expanded catalogue board $names: code=${station.code}, slug=$slug, departures=${root.optJSONArray("ListaTreniPartenza")?.length()}, LastUpdate=${root.opt("LastUpdate")}")
        }
        val principe = catalogue.single { it.code == "G__" }
        val brignole = catalogue.single { it.code == "GB_" }
        assertNotEquals("Separate Genova stations must not share a board", principe.code, brignole.code)
        // The destination map also publishes e.g. Pisa, whose board returned HTTP400 in the audit.
        // Presence in that map alone is not evidence of an available or operational railway board.
    }

    @Test fun italoGlobalNumber() = runBlocking {
        selected("italo")
        val adapter = ItaloTrainNumberAdapter(fetch = { url ->
            val body = downloadItaloPublic(url)
            val root = org.json.JSONObject(body)
            val run = root.optJSONObject("TrainSchedule")
            println("LIVE_ACCEPTANCE Italo direct number schema: keys=${root.keySet()}, runKeys=${run?.keySet()}, date=${run?.opt("DepartureDate")}, origin=${run?.optJSONObject("StazionePartenza")}, firstReached=${run?.optJSONArray("StazioniFerme")?.optJSONObject(0)}, disruption=${run?.optJSONObject("Distruption")}")
            body
        }, clock = clock)
        val numbers = linkedSetOf<String>()
        val stationFetcher = HttpItaloPublicStationFetcher()
        for (slug in listOf("roma-termini", "milano-centrale", "napoli-centrale")) {
            val rows = org.json.JSONObject(stationFetcher.fetch(slug)).optJSONArray("ListaTreniPartenza") ?: continue
            for (i in 0 until rows.length()) rows.optJSONObject(i)?.optString("Numero")?.takeIf { it.matches(Regex("[0-9]{3,5}")) }?.let(numbers::add)
        }
        println("LIVE_ACCEPTANCE Italo currently published candidate numbers: $numbers")
        assertTrue("Italo published no candidate number for a direct lookup", numbers.isNotEmpty())
        var onlyEmptyResponses = true
        for (number in numbers.take(8)) {
            val query = it.binario.app.data.repository.TrainNumberQuery(number, clock.instant().atZone(zone).toLocalDate())
            val result = adapter.searchByNumber(query)
            val matches = (result as? ProviderResult.Success)?.value.orEmpty()
            if (result !is ProviderResult.Success || matches.isNotEmpty()) onlyEmptyResponses = false
            println("LIVE_ACCEPTANCE Italo global number $number: result=${result.javaClass.simpleName}, matches=${matches.map { it.service.origin + ":" + it.service.destination + ":stops=" + it.service.stops.size }}")
            if (matches.any { query.matches(it.service.number) && it.service.stops.size >= 2 }) return@runBlocking
        }
        assumeTrue("Italo explicitly returned IsEmpty for every currently published candidate", !onlyEmptyResponses)
        fail("Italo direct lookup did not resolve any currently published number")
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

    /**
     * Raw samples of candidate sources (ViaggiaTreno per-train notices, transport.opendata.ch, Trenord line
     * notices, MIT strike calendar, Open-Meteo) so their formats are known before an adapter is written.
     * Prints only public response bodies; no credential is sent.
     */
    @Test fun candidateSourceSamples() = runBlocking {
        selected("discovery")
        val upstream = diagnosticViaggiaTrenoFetcher()
        val adapter = ViaggiaTrenoRealtimeAdapter(upstream, clock)
        val now = clock.instant()
        val board = adapter.getStationBoard(StationBoardRequest(StationId("S01700"), "Milano Centrale", railwayCode = "S01700",
            from = now.minusSeconds(1800), to = now.plusSeconds(3 * 3600), kind = BoardKind.DEPARTURES))
        val numbers = (board as? ProviderResult.Success)?.value?.entries.orEmpty().mapNotNull { it.publicNumber?.takeIf(String::isNotBlank) }.distinct().take(6)
        println("LIVE_ACCEPTANCE SAMPLE numbers=$numbers")
        val urls = buildList {
            numbers.forEach { add("vt-infomobility-$it" to "https://www.viaggiatreno.it/infomobilitamobile/resteasy/news/infomobility?trainNumber=$it") }
            numbers.take(3).forEach { add("vt-smartcaring-$it" to "https://www.viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=$it") }
            add("vt-infomobility-general" to "https://www.viaggiatreno.it/infomobilitamobile/resteasy/news/infomobility")
            numbers.take(2).forEach { add("vt-infomobilita-news-$it" to "https://www.viaggiatreno.it/infomobilita/resteasy/news/infomobility?trainNumber=$it") }
            add("vt-infomobilita-ticker" to "https://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/infomobilitaTicker")
            add("mit-scioperi-root" to "https://scioperi.mit.gov.it/")
            add("opendata-ch-stationboard-name" to "https://transport.opendata.ch/v1/stationboard?station=Lugano&limit=4")
            add("opendata-ch-stationboard-id" to "https://transport.opendata.ch/v1/stationboard?id=8505300&limit=3&type=arrival")
            add("opendata-ch-locations" to "https://transport.opendata.ch/v1/locations?query=Chiasso&type=station")
            add("trenord-direttrici" to "https://www.trenord.it/mgmt/store-management-api/mia/direttrici/")
            add("mit-scioperi" to "https://scioperi.mit.gov.it/mit2/public/scioperi")
            add("open-meteo" to "https://api.open-meteo.com/v1/forecast?latitude=46.0037&longitude=8.9511&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=Europe%2FRome&forecast_days=2")
        }
        for ((name, url) in urls) {
            val sample = runCatching {
                val connection = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", "Binario/0.20 (source format discovery)")
                connection.setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.5")
                connection.instanceFollowRedirects = false
                val code = connection.responseCode
                connection.getHeaderField("Location")?.let { println("LIVE_ACCEPTANCE SAMPLE $name redirect=$it") }
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                "status=$code type=${connection.contentType} length=${body.length} links=${Regex("href=\"([^\"]*(rss|xml|csv|xls|json|ical|ics|export)[^\"]*)\"", RegexOption.IGNORE_CASE).findAll(body).map { it.groupValues[1] }.distinct().take(15).toList()} body=" +
                    body.take(if (name == "mit-scioperi") 12_000 else 7_000).replace("\r", "").replace("\n", "\\n")
            }.getOrElse { "error=${it::class.java.simpleName}: ${it.message}" }
            println("LIVE_ACCEPTANCE SAMPLE $name $sample")
        }
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
