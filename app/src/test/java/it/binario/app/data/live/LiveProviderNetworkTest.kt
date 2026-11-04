package it.binario.app.data.live

import it.binario.app.data.core.*
import it.binario.app.data.ServiceStatus
import it.binario.app.data.gtfs.EmbeddedGtfsRealtimeScheduleResolver
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.GtfsArtifactDownloader
import it.binario.app.data.gtfs.BoundedGtfsZipScanner
import it.binario.app.data.gtfs.GtfsCompactIndexLimits
import it.binario.app.data.sources.InternationalSourceCatalog
import it.binario.app.data.urban.UrbanRealtimeFetcher
import it.binario.app.data.urban.UrbanGtfsRealtimeAdapter
import it.binario.app.data.urban.UrbanStopBoardAdapter
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.bikesharing.BikeSharingResult
import it.binario.app.data.bikesharing.BikeSharingSystems
import it.binario.app.data.bikesharing.BikeSharingService
import it.binario.app.data.urban.UrbanGtfsRealtimeDecoder
import it.binario.app.data.urban.HttpsBytesFetcher
import it.binario.app.data.urban.UrbanPackRoutes
import it.binario.app.data.gtfs.TransitGtfsPacks
import it.binario.app.data.urban.UrbanRealtimeFeed
import it.binario.app.data.urban.UrbanRealtimeFeeds
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
        val static = EmbeddedGtfsRouteAdapter({ File(assets, it).inputStream() },
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
        // The regional checks reach Monza's board through the catalogue (LiveRouteChecks.regionalRoutes).
        assertNotNull("RFI official catalogue: Monza", catalog.placeId(listOf("Monza")))
        // Both Milano Centrale boards are always read; an observation is required only where a row publishes minutes.
        val passed = LiveRouteChecks.rfiBoards(fetcher, clock)
        println("LIVE_ACCEPTANCE rfi-station-board: board=${passed.board}, departures=${passed.entries}, operational=${passed.operational}")
    }

    /** Appiano → Roma Ostiense, then the other [LiveRouteChecks.regionalRoutes] while the boards publish nothing to verify. */
    @Test fun rfiRegionalJourney() = runBlocking {
        selected("rfi")
        val passed = LiveRouteChecks.rfiRegional(diagnosticRfiFetcher(), LiveRouteChecks.regionalRoutes, it.binario.app.data.JourneyQuery.now(clock), clock)
        val services = passed.services
        println("LIVE_ACCEPTANCE RFI regional journey: route=${passed.route.name}, matchedLiveDepartures=${services.count { it.operationalStatusVerified }}")
        println("LIVE_ACCEPTANCE RFI 23263 paired boards: " + (services.singleOrNull { it.number == "23263" }?.let {
            "departure=${it.scheduledDeparture}, delay=${it.officialDelayMinutes}, arrival=${it.stops.last().scheduled}, arrivalDelay=${it.stops.last().delayMinutes}, arrivalVerified=${it.stops.last().operationalStatusVerified}"
        } ?: "not matched on both current boards"))
    }

    /** Every delay in minutes Appiano's departures publish, or (when they publish none) Roma Termini's or Milano Centrale's. */
    @Test fun rfiPublishedDelayAccuracy() = runBlocking {
        selected("rfi")
        val fetcher = diagnosticRfiFetcher()
        val place = RfiStationCatalog(fetcher, clock).placeId(listOf("Appiano")) ?: throw AssertionError("RFI accuracy: Appiano not resolved")
        val passed = LiveRouteChecks.rfiAccuracy(fetcher, LiveRouteChecks.rfiAccuracyBoards(place))
        println("LIVE_ACCEPTANCE RFI exact published delay: board=${passed.board}, compared=${passed.compared}; ${passed.reportedTrain}; mismatches=[]; formats=${passed.formats}")
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

    /** Appiano → Roma Ostiense, then the other [LiveRouteChecks.regionalRoutes] while no run has a recent detection. */
    @Test fun viaggiatrenoRegionalDelayAccuracy() = runBlocking {
        selected("viaggiatreno")
        val passed = LiveRouteChecks.viaggiaTrenoRegional(diagnosticViaggiaTrenoFetcher(), LiveRouteChecks.regionalRoutes,
            it.binario.app.data.JourneyQuery.now(clock), Clock.system(zone))
        val services = passed.services
        println("LIVE_ACCEPTANCE ViaggiaTreno exact regional delay: route=${passed.route.name}, verified=${services.count { it.operationalStatusVerified && it.status != ServiceStatus.CANCELLED }}; " +
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
        // The embedded STA pack is rail only (route_type 2 and 100-199) while the feed also carries the STA buses.
        tripUpdates(StaRealtimeAdapter(), "alto-adige-sta-gtfs", packSubsetRoute = LiveAcceptanceEvidence::staRouteOfTrip)
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
        tripUpdates(EuropeanSleeperGtfsRealtimeAdapter(), "european-sleeper", resolver, bytes)
    }

    /**
     * City realtime through the app's own adapters: each feed of [UrbanRealtimeFeeds] read with
     * [HttpsBytesFetcher], filtered to its pack's routes and matched to the pack's trips; then
     * the board of a stop whose trips have updates. Prints counts, lines and a few matched rows only.
     */
    @Test fun urbanRealtime() = runBlocking {
        selected("urban")
        // Candidates still to be verified by day (SOURCE_POLICY.md, «Altre reti»): read like the configured feeds.
        val candidates = listOf(
            UrbanRealtimeFeed(SourceId("gtt-gtfs-rt"), SourceId("gtt-gtfs"), "GTT", "https://percorsieorari.gtt.to.it/das_gtfsrt/trip_update.aspx", "CC BY 4.0", personalOnly = true),
            UrbanRealtimeFeed(SourceId("venezia-actv-gtfs-rt"), SourceId("venezia-actv-gtfs"), "ACTV", "https://tpl.actv.it/aut/GTFSRT/tripUpdates", "non dichiarata", personalOnly = true),
            UrbanRealtimeFeed(SourceId("toscana-gtfs-rt"), SourceId("firenze-gest-gtfs"), "Regione Toscana", "https://regionetoscana.smartregion.toscana.it/mobility/artifacts/gtfs-rt/trip-updates", "CC BY 4.0", personalOnly = false)
        ).filter { candidate -> UrbanRealtimeFeeds.all.none { it.sourceId == candidate.sourceId } }
        (UrbanRealtimeFeeds.all + candidates).forEach { feed ->
            val pack = TransitGtfsPacks.all.first { it.sourceId == feed.staticSourceId }
            val asset = listOf(File("src/main/assets", pack.assetPath), File("src/personal/assets", pack.assetPath)).first(File::isFile)
            val routes = UrbanPackRoutes.read(asset.inputStream())
            val tripRoutes = HashMap<String, String>()
            BoundedGtfsZipScanner(GtfsCompactIndexLimits()).scan(asset.inputStream(), setOf("trips.txt")) { _, rows ->
                val trip = rows.header.indexOf("trip_id")
                val route = rows.header.indexOf("route_id")
                rows.forEachRow(500_000) { row -> tripRoutes[row[trip]] = row[route] }
            }
            val started = System.nanoTime()
            val bytes = try {
                HttpsBytesFetcher().fetch(feed.tripUpdatesUrl)
            } catch (error: java.io.IOException) {
                println("LIVE_ACCEPTANCE urban ${feed.sourceId.value}: ${error::class.java.simpleName} ${error.message}")
                return@forEach
            }
            val all = UrbanGtfsRealtimeDecoder.decode(bytes, feed, clock.instant())
            val kept = UrbanGtfsRealtimeDecoder.decode(bytes, feed, clock.instant()) { it in routes }
            val matched = kept.trips.values.flatten().filter { it.tripId in tripRoutes }
            println("LIVE_ACCEPTANCE urban ${feed.sourceId.value}: bytes=${bytes.size} millis=${(System.nanoTime() - started) / 1_000_000} " +
                "age=${Duration.between(all.observedAt, clock.instant()).seconds}s trips=${all.trips.size} packRouteTrips=${kept.trips.size} " +
                "matchedPackTrips=${matched.size} cancelled=${matched.count { it.cancelled }} " +
                "routes=${matched.groupingBy { tripRoutes.getValue(it.tripId) }.eachCount()} " +
                "unmatchedRoutes=${kept.trips.values.flatten().filter { it.tripId !in tripRoutes }.groupingBy { it.routeId }.eachCount()}")
            matched.take(3).forEach { trip ->
                println("LIVE_ACCEPTANCE urban ${feed.sourceId.value} trip=${trip.tripId} route=${tripRoutes[trip.tripId]} start=${trip.startDate} " +
                    "stops=${trip.stops.take(4).map { "${it.stopSequence}/${it.stopId}:${it.departure ?: it.arrival}:${it.departureDelaySeconds ?: it.arrivalDelaySeconds}" }}")
            }
            // The board of the stop with most updates, through the app's own adapters.
            val busiest = matched.flatMap { trip -> trip.stops.mapNotNull { it.stopId } }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            if (busiest != null) {
                val bytesOfPack = asset.readBytes()
                val routeAdapter = EmbeddedGtfsRouteAdapter(openAsset = { java.io.ByteArrayInputStream(bytesOfPack) }, packs = listOf(pack))
                val board = UrbanStopBoardAdapter(
                    pack, routeAdapter,
                    UrbanGtfsRealtimeAdapter(feed, UrbanRealtimeFetcher { bytes }, clock, packRoutes = { routes })
                )
                val now = clock.instant()
                val result = board.getStationBoard(StationBoardRequest(StationId("gtfs:${pack.sourceId.value}:$busiest"), from = now.minusSeconds(600), to = now.plusSeconds(6 * 3600), kind = BoardKind.DEPARTURES))
                val entries = (result as? ProviderResult.Success)?.value?.entries ?: (result as? ProviderResult.Partial)?.value?.entries.orEmpty()
                println("LIVE_ACCEPTANCE urban ${feed.sourceId.value} board stop=$busiest result=${result.javaClass.simpleName} entries=${entries.size} " +
                    "live=${entries.count { it.expectedTime != null }} cancelled=${entries.count { it.status?.value == RunStatus.CANCELLED }} " +
                    "first=${entries.take(5).map { "${it.lineLabel}>${it.destinationOrOriginLabel} ${it.scheduledTime.toLocalTime()}/${it.expectedTime?.value?.toLocalTime()}" }}")
            }
        }
    }

    /** Shared bikes through [BikeSharingService] near one railway station per system. */
    @Test fun bikeSharing() = runBlocking {
        selected("gbfs")
        val service = BikeSharingService(
            BikeSharingSystems.all,
            HttpPublicSourceFetcher(PublicSourceRequestPolicy(maxBytes = 4 * 1024 * 1024, readTimeoutMillis = 15_000, totalTimeoutMillis = 20_000))
        )
        listOf(
            "Milano Centrale" to GeoPoint(45.4859, 9.2036), "Milano Porta Genova" to GeoPoint(45.4531, 9.1700),
            "Verona Porta Nuova" to GeoPoint(45.4290, 10.9823), "Bergamo" to GeoPoint(45.6905, 9.6752),
            "Senigallia" to GeoPoint(43.7157, 13.2172), "Roma Termini" to GeoPoint(41.9009, 12.5018)
        ).forEach { (name, point) ->
            when (val result = service.nearby(point)) {
                is BikeSharingResult.Loaded -> result.systems.forEach { nearby ->
                    println("LIVE_ACCEPTANCE gbfs $name ${nearby.system.id.value}: docks=${nearby.docks.map { "${it.name}@${it.distanceMeters}m bikes=${it.bikesAvailable} e=${it.ebikesAvailable} free=${it.docksAvailable}" }} " +
                        "vehicles=${nearby.vehicles.map { "${it.kind}@${it.distanceMeters}m ${it.batteryPercent}%" }} age=${Duration.between(nearby.observedAt, clock.instant()).seconds}s stale=${nearby.stale}")
                }
                else -> println("LIVE_ACCEPTANCE gbfs $name: $result")
            }
        }
    }

    @Test fun swissOpenTransport() = runBlocking {
        selected("opendata-ch")
        // Lugano, picked from the embedded Swiss GTFS pack, and Bellinzona through its curated Trenitalia id.
        board(SwissOpenTransportStationBoardAdapter(clock), "gtfs:ch-otd-gtfs:8505300", "Lugano")
        val now = clock.instant()
        val result = SwissOpenTransportStationBoardAdapter(clock).getStationBoard(StationBoardRequest(StationId("curated:S00300"), "Bellinzona",
            railwayCode = "S00300", from = now.minusSeconds(600), to = now.plusSeconds(6 * 3600), kind = BoardKind.DEPARTURES,
            trenitaliaLocationId = "850005213"))
        if (result !is ProviderResult.Success) fail("transport.opendata.ch Bellinzona: $result")
        val entries = (result as ProviderResult.Success).value.entries
        assertTrue("transport.opendata.ch Bellinzona: no journey numbers", entries.all { it.publicNumber?.all(Char::isDigit) == true })
        println("LIVE_ACCEPTANCE ch-transport-opendata: bellinzona=${entries.size}, lines=${entries.mapNotNull { it.lineLabel }.distinct().take(6)}")
    }

    @Test fun viaggiatrenoNotices() = runBlocking {
        selected("notices")
        val now = clock.instant()
        val board = ViaggiaTrenoRealtimeAdapter(diagnosticViaggiaTrenoFetcher(), clock).getStationBoard(StationBoardRequest(StationId("S01700"),
            "Milano Centrale", railwayCode = "S01700", from = now.minusSeconds(1800), to = now.plusSeconds(3 * 3600), kind = BoardKind.DEPARTURES))
        val numbers = (board as? ProviderResult.Success)?.value?.entries.orEmpty().mapNotNull { it.publicNumber?.takeIf(String::isNotBlank) }.distinct().take(4)
        assertTrue("ViaggiaTreno board gave no train numbers: $board", numbers.isNotEmpty())
        val adapter = ViaggiaTrenoTrainNoticesAdapter(clock)
        val today = now.atZone(zone).toLocalDate()
        val counts = numbers.map { number ->
            val result = adapter.getTrainNotices(TrainNoticeRequest(number, today))
            if (result is ProviderResult.Failure) fail("ViaggiaTreno smartcaring $number: $result")
            number to (result as ProviderResult.Success).value.size
        }
        println("LIVE_ACCEPTANCE viaggiatreno-train-notices: $counts")
        val news = TrenitaliaInfomobilityNewsAdapter(clock).getAlerts(AlertRequest())
        if (news !is ProviderResult.Success) fail("Trenitalia infomobility: $news")
        val alerts = (news as ProviderResult.Success).value
        assertTrue("Trenitalia infomobility: markup left in descriptions",
            alerts.none { alert -> alert.description?.value?.let { "&lt;" in it || "<p>" in it } == true })
        println("LIVE_ACCEPTANCE trenitalia-infomobility-news: items=${alerts.size}, tagged=${alerts.count { it.affectedTrainNumbers.isNotEmpty() }}")
        val trenord = TrenordLineNoticesAdapter(clock).getLineNotices()
        if (trenord !is ProviderResult.Success) fail("Trenord direttrici: $trenord")
        val lines = (trenord as ProviderResult.Success).value
        assertTrue("Trenord direttrici: no lines", lines.lines.isNotEmpty())
        println("LIVE_ACCEPTANCE trenord-line-notices: lines=${lines.lines.size}, notices=${lines.alerts.size}")
    }

    @Test fun openMeteo() = runBlocking {
        selected("open-meteo")
        val today = clock.instant().atZone(zone).toLocalDate()
        // Lugano station, as the curated catalogue places it.
        val result = it.binario.app.data.weather.OpenMeteoForecastAdapter(clock).forecast(46.005499, 8.946841, today)
        if (result !is ProviderResult.Success) fail("Open-Meteo: $result")
        val forecast = (result as ProviderResult.Success).value
        assertTrue("Open-Meteo: no weather code", forecast.weatherCode != null)
        println("LIVE_ACCEPTANCE open-meteo: $forecast")
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
            // The HTTPS news endpoints answer 301 to cleartext; www.viaggiatreno.it is the one cleartext domain allowed.
            numbers.forEach { add("vt-http-infomobility-$it" to "http://www.viaggiatreno.it/infomobilitamobile/resteasy/news/infomobility?trainNumber=$it") }
            numbers.take(3).forEach { add("vt-http-smartcaring-$it" to "http://www.viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=$it") }
            add("vt-http-infomobility-general" to "http://www.viaggiatreno.it/infomobilitamobile/resteasy/news/infomobility")
            add("vt-http-ticker" to "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/infomobilitaTicker")
            add("opendata-ch-stationboard-name" to "https://transport.opendata.ch/v1/stationboard?station=Lugano&limit=4")
            add("opendata-ch-stationboard-id" to "https://transport.opendata.ch/v1/stationboard?id=8505300&limit=3&type=arrival")
            add("opendata-ch-locations" to "https://transport.opendata.ch/v1/locations?query=Chiasso&type=station")
            add("trenord-direttrici" to "https://www.trenord.it/mgmt/store-management-api/mia/direttrici/")
            add("mit-scioperi" to "https://scioperi.mit.gov.it/mit2/public/scioperi")
            add("mit-scioperi-rss" to MitStrikeRssAdapter.URL)
            add("open-meteo" to "https://api.open-meteo.com/v1/forecast?latitude=46.0037&longitude=8.9511&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=Europe%2FRome&forecast_days=2")
        }
        // ViaggiaTreno journey solutions (with changes), through the production fetcher and its
        // HTTPS-to-cleartext handling: the format must be known before a journey adapter exists.
        val tomorrow = now.atZone(zone).toLocalDate().plusDays(1)
        val base = "https://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno"
        val mobileBase = "https://www.viaggiatreno.it/infomobilitamobile/resteasy/viaggiatreno"
        listOf(
            "new-mobile" to "$mobileBase/soluzioniViaggioNew/8409/219/${tomorrow}T08:00:00",
            "new-mobile-nosec" to "$mobileBase/soluzioniViaggioNew/8409/219/${tomorrow}T08:00",
            "new-nosec" to "$base/soluzioniViaggioNew/8409/219/${tomorrow}T08:00",
            "old" to "$base/soluzioniViaggio/8409/219/${tomorrow}T08:00:00",
            "old-mobile" to "$mobileBase/soluzioniViaggio/8409/219/${tomorrow}T08:00:00",
            "new-merano-mobile" to "$mobileBase/soluzioniViaggioNew/1700/2216/${tomorrow}T08:00:00"
        ).forEach { (name, url) ->
            val path = url
            // The production fetcher only allows the board/run endpoints: follow its redirect rule by hand.
            val sample = runCatching {
                var target = path
                val hops = mutableListOf<String>()
                var result = ""
                for (hop in 0 until 4) {
                    val connection = java.net.URI(target).toURL().openConnection() as java.net.HttpURLConnection
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 25_000
                    connection.setRequestProperty("User-Agent", "Binario/0.20 (source format discovery)")
                    connection.setRequestProperty("Accept", "application/json")
                    val code = connection.responseCode
                    hops += "$code ${target.substringBefore("/infomobilita")}"
                    val location = connection.getHeaderField("Location")
                    if (code in 300..399 && location != null) { target = location; continue }
                    val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                    result = "hops=$hops type=${connection.contentType} length=${body.length} body=" + body.take(9_000).replace("\r", "").replace("\n", "\\n")
                    break
                }
                result.ifEmpty { "hops=$hops (too many redirects)" }
            }.getOrElse { "error=${it::class.java.simpleName}: ${it.message}" }
            println("LIVE_ACCEPTANCE SAMPLE vt-solutions-$name path=$path $sample")
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
        // One check of the MIT strike feed through the production adapter, so its format can be verified in CI: it
        // reports (never fails on) what the RSS 2.0 reader and the rail filter make of the live items.
        val strikes = runCatching { MitStrikeRssAdapter(clock).strikes() }
            .getOrElse { error -> ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, error.toString())) }
        val strikeSummary = when (strikes) {
            is ProviderResult.Success -> "rail=${strikes.value.size} dated=${strikes.value.count { it.startDate != null }} " +
                strikes.value.take(5).joinToString(" | ") { "${it.startDate}..${it.endDate} ${it.title.take(120)}" }
            else -> strikes.toString().take(500)
        }
        println("LIVE_ACCEPTANCE mit-strikes-rss: $strikeSummary")
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

    /**
     * Fresh trip updates with published timing whose trip and stop resolve in the static pack. [packSubsetRoute] is set
     * when the pack holds only part of the feed's network (STA: rail only, beside the feed's buses) and tells which
     * route a trip id names: identity is then checked on the updates of the pack's own routes or stops, and the check
     * is skipped while the feed has none of them. Without it every fresh update has to be the pack's.
     */
    private suspend fun tripUpdates(provider: TripUpdateProvider, staticSource: String, customResolver: EmbeddedGtfsRealtimeScheduleResolver? = null,
        customPack: ByteArray? = null, packSubsetRoute: ((String) -> String?)? = null) {
        val now = clock.instant()
        val id = provider.descriptor.id.value
        val result = provider.getTripUpdates()
        if (result !is ProviderResult.Success) fail("$id: $result")
        val updates = (result as ProviderResult.Success).value
        val fresh = updates.filter { !it.observedAt.isBefore(now.minusSeconds(300)) && !it.observedAt.isAfter(now.plusSeconds(60)) }
        assertTrue("$id: no fresh updates with published timing", fresh.any { it.officialDelay != null || it.predictedAt != null })
        val assets by lazy { listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory } }
        val pack = LiveAcceptanceEvidence.GtfsPackIdentity.read(customPack ?: File(assets, "gtfs/$staticSource.zip").readBytes())
        val resolver = customResolver ?: EmbeddedGtfsRealtimeScheduleResolver({ File(assets, it).inputStream() })
        val timed = fresh.filter { it.officialDelay != null || it.predictedAt != null }
        val checked = if (packSubsetRoute == null) timed
            else (LiveAcceptanceEvidence.onPackNetwork(timed, pack, packSubsetRoute) + timed.filter { it.sourceTripId in pack.tripIds }).distinct()
        val dates = listOf(now.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate().minusDays(1))
        val candidates = checked.filter { it.sourceTripId in pack.tripIds }
        val matched = candidates.distinctBy { it.sourceTripId }.take(5).any { update ->
            (update.serviceDate?.let(::listOf) ?: dates).any { date ->
                resolver.resolve(SourceId(staticSource), update, date, zone) != null
            }
        }
        val routes = packSubsetRoute?.let { routeOf -> timed.mapNotNull { routeOf(it.sourceTripId)?.substringBefore('-') }.groupingBy { it }.eachCount() }
        val summary = "fresh=${fresh.size}; checkedUpdates=${checked.size}; matchingTripUpdates=${candidates.size}; " +
            "liveSample=${fresh.take(2).map { it.sourceTripId + ":" + it.sourceStopId }}; checkedSample=${checked.take(2).map { it.sourceTripId + ":" + it.sourceStopId }}; " +
            "staticSample=${pack.tripIds.take(2)}" + (routes?.let { "; liveRoutePrefixes=$it; packRoutePrefixes=${pack.routeIds.map { route -> route.substringBefore('-') }.toSortedSet()}" } ?: "")
        if (checked.isEmpty()) println("LIVE_ACCEPTANCE $id: skipped, no fresh update on a route or stop of the static pack; $summary")
        assumeTrue("$id: the feed has no fresh update on a route or stop of the static pack at this moment; $summary", checked.isNotEmpty())
        assertTrue("$id: live trip/stop IDs do not match GTFS; $summary", matched)
        println("LIVE_ACCEPTANCE $id: freshUpdates=${fresh.size}, checkedUpdates=${checked.size}, staticIdentityMatch=true")
    }
}
