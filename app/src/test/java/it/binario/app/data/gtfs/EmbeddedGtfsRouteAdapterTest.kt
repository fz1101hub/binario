package it.binario.app.data.gtfs

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RouteFailureReason
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.SourceId
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EmbeddedGtfsRouteAdapterTest {
    @Test fun `number lookup returns the complete run without a route or departure cutoff`() = runBlocking {
        val matches = adapter(feed()).searchByNumber(it.binario.app.data.repository.TrainNumberQuery("2581", LocalDate.of(2026, 9, 4)))
        val train = matches.single().service
        assertEquals("Milano Centrale", train.origin)
        assertEquals("Como S. Giovanni", train.destination)
        assertEquals(3, train.stops.size)
        assertEquals("08:05", train.scheduledDeparture)
        assertFalse(train.operationalStatusVerified)
        assertEquals("T1", train.gtfsIdentity?.tripId)
        assertTrue(adapter(feed()).searchByNumber(it.binario.app.data.repository.TrainNumberQuery("258", LocalDate.of(2026, 9, 4))).isEmpty())
    }

    @Test fun `number lookup honours calendar exceptions and overnight service dates`() = runBlocking {
        val date = LocalDate.of(2026, 9, 5)
        val removed = adapter(feed(calendarDates = "service_id,date,exception_type\nW,20260904,2\n"))
        assertTrue(removed.searchByNumber(it.binario.app.data.repository.TrainNumberQuery("2581", date.minusDays(1))).isEmpty())
        val night = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,25:10:00,25:10:00,MI,1\nT1,25:27:00,25:28:00,MO,2\nT1,25:50:00,25:50:00,CO,3\n"
        val service = adapter(feed(stopTimes = night)).searchByNumber(it.binario.app.data.repository.TrainNumberQuery("2581", date)).single().service
        assertEquals(date.minusDays(1), service.gtfsIdentity?.serviceDate)
        assertEquals(date.atTime(1, 10), service.scheduledDepartureDateTime)
    }

    @Test fun `same public number in different feeds preserves distinct native identities`() = runBlocking {
        val packs = listOf(EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "a"), EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "b"))
        val source = EmbeddedGtfsRouteAdapter(openAsset = { ByteArrayInputStream(feed()) }, packs = packs, clock = { 1_000L })
        val matches = source.searchByNumber(it.binario.app.data.repository.TrainNumberQuery("2581", LocalDate.of(2026, 9, 4)))
        assertEquals(2, matches.size)
        assertEquals(2, matches.map { it.id }.distinct().size)
    }

    private val pack = EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "gtfs/test.zip")
    private val route = SavedRoute(
        id = "test",
        name = "Milano – Como",
        outwardOrigin = Station("milano", "Milano Centrale", "Milano"),
        outwardDestination = Station("como", "Como S. Giovanni", "Como")
    )

    @Test
    fun `trasforma una corsa GTFS valida nel giorno richiesto in orario programmato`() = runBlocking {
        val adapter = adapter(feed())
        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Success)
        val snapshot = (result as DataResult.Success).snapshot
        val service = snapshot.services.single()
        assertEquals(DataConfidence.STATIC_ONLY, snapshot.confidence)
        assertEquals("08:05", service.scheduledDeparture)
        assertEquals("2581", service.number)
        assertEquals(3, service.stops.size)
        assertFalse(service.operationalStatusVerified)
        assertEquals(LocalDateTime.of(2026, 9, 4, 8, 5), service.scheduledDepartureDateTime)
        assertEquals("lombardia-trenord-gtfs", service.gtfsIdentity?.sourceId)
        assertEquals("T1", service.gtfsIdentity?.tripId)
        assertEquals(LocalDate.of(2026, 9, 4), service.gtfsIdentity?.serviceDate)
        assertEquals(listOf(1, 2, 3), service.gtfsIdentity?.stops?.map { it.sequence })
        assertEquals(listOf("MI", "MO", "CO"), service.gtfsIdentity?.stops?.map { it.stopId })
    }

    @Test
    fun `unisce pack diversi che pubblicano la stessa tratta invece di fermarsi al primo`() = runBlocking {
        val trenordPack = EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "gtfs/trenord.zip")
        val swissPack = EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "gtfs/swiss.zip")
        val feeds = mapOf(
            trenordPack.assetPath to feed(tripId = "T1", tripNumber = "2581", departure = "08:05:00"),
            swissPack.assetPath to feed(tripId = "T2", tripNumber = "EC151", departure = "08:35:00")
        )
        val adapter = EmbeddedGtfsRouteAdapter(
            openAsset = { path -> ByteArrayInputStream(requireNotNull(feeds[path])) },
            packs = listOf(trenordPack, swissPack),
            clock = { 1_000L }
        )

        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Success)
        val snapshot = (result as DataResult.Success).snapshot
        assertEquals(listOf("2581", "EC151"), snapshot.services.map { it.number })
        assertTrue(snapshot.providerName.contains("Regione Lombardia"))
        assertTrue(snapshot.providerName.contains("Switzerland"))
    }

    @Test
    fun `deduplica la stessa corsa pubblicata da due pack`() = runBlocking {
        val first = EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "gtfs/one.zip")
        val second = EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "gtfs/two.zip")
        val bytes = feed(tripId = "T1", tripNumber = "EC151", departure = "08:05:00")
        val adapter = EmbeddedGtfsRouteAdapter(
            openAsset = { ByteArrayInputStream(bytes) },
            packs = listOf(first, second),
            clock = { 1_000L }
        )

        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Success)
        assertEquals(1, (result as DataResult.Success).snapshot.services.size)
    }

    @Test
    fun `deduplica il treno transfrontaliero pubblicato con numeri diversi da Trenord e Svizzera`() = runBlocking {
        val trenordPack = EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "gtfs/trenord.zip")
        val swissPack = EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "gtfs/swiss.zip")
        val feeds = mapOf(
            trenordPack.assetPath to feed(tripId = "T1", tripNumber = "S10 - 25107", departure = "08:05:00"),
            swissPack.assetPath to feed(tripId = "CH1", tripNumber = "12878", departure = "08:05:00")
        )
        val adapter = EmbeddedGtfsRouteAdapter(
            openAsset = { path -> ByteArrayInputStream(requireNotNull(feeds[path])) },
            packs = listOf(trenordPack, swissPack),
            clock = { 1_000L }
        )

        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Success)
        val services = (result as DataResult.Success).snapshot.services
        assertEquals(1, services.size)
        assertTrue(services.single().id.startsWith("ch-otd-gtfs:"))
    }

    @Test
    fun `non restituisce partenze precedenti all ora richiesta`() = runBlocking {
        val result = adapter(feed()).loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(9, 0))
        )

        assertTrue(result is DataResult.Failure)
    }

    @Test
    fun `calendar dates puo aggiungere una corsa fuori dal calendario settimanale`() = runBlocking {
        val result = adapter(
            feed(calendarDates = "service_id,date,exception_type\nW,20260905,1\n")
        ).loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 5), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Success)
    }

    @Test
    fun `calendar dates puo rimuovere una corsa altrimenti attiva`() = runBlocking {
        val result = adapter(
            feed(calendarDates = "service_id,date,exception_type\nW,20260904,2\n")
        ).loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Failure)
    }

    @Test
    fun `mantiene il giorno di servizio per orari oltre le ventiquattro`() = runBlocking {
        val nightStopTimes =
            "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "T1,25:10:00,25:10:00,MI,1\n" +
                "T1,25:27:00,25:28:00,MO,2\n" +
                "T1,25:50:00,25:50:00,CO,3\n"
        val result = adapter(feed(stopTimes = nightStopTimes)).loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(23, 0))
        )

        assertTrue(result is DataResult.Success)
        val service = (result as DataResult.Success).snapshot.services.single()
        assertEquals("01:10", service.scheduledDeparture)
        assertEquals(LocalDateTime.of(2026, 9, 5, 1, 10), service.scheduledDepartureDateTime)
    }

    @Test
    fun `dopo mezzanotte include la corsa del giorno di servizio precedente`() = runBlocking {
        val nightStopTimes =
            "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "T1,25:10:00,25:10:00,MI,1\n" +
                "T1,25:27:00,25:28:00,MO,2\n" +
                "T1,25:50:00,25:50:00,CO,3\n"

        val result = adapter(feed(stopTimes = nightStopTimes)).loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 5), LocalTime.of(0, 30))
        )

        assertTrue(result is DataResult.Success)
        val service = (result as DataResult.Success).snapshot.services.single()
        assertEquals("01:10", service.scheduledDeparture)
        assertEquals(LocalDateTime.of(2026, 9, 5, 1, 10), service.scheduledDepartureDateTime)
    }

    @Test
    fun `due corse notturne dello stesso trip su service day distinti hanno id univoci`() = runBlocking {
        val nightStopTimes =
            "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "T1,25:10:00,25:10:00,MI,1\n" +
                "T1,25:27:00,25:28:00,MO,2\n" +
                "T1,25:50:00,25:50:00,CO,3\n"
        val result = adapter(feed(stopTimes = nightStopTimes)).loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(0, 30))
        )

        assertTrue(result is DataResult.Success)
        val services = (result as DataResult.Success).snapshot.services
        assertEquals(2, services.size)
        assertEquals(2, services.map { it.id }.toSet().size)
        assertEquals(
            listOf(LocalDateTime.of(2026, 9, 4, 1, 10), LocalDateTime.of(2026, 9, 5, 1, 10)),
            services.map { it.scheduledDepartureDateTime }
        )
    }

    @Test
    fun `trova la stazione nel pack tramite alias quando il nome del catalogo differisce`() = runBlocking {
        val adapter = adapter(feed())
        val aliased = SavedRoute(
            id = "aliased",
            name = "Milano Como",
            outwardOrigin = Station("curated:S01700", "Milano C.le (catalogo)", "Milano", aliases = listOf("Milano Centrale")),
            outwardDestination = Station("curated:S01122", "Como San Giovanni", "Como", aliases = listOf("Como S. Giovanni", "X__"))
        )
        // A translated alias that shares no word with the name must not bind a namesake stop.
        val exonym = SavedRoute(
            id = "exonym",
            name = "Chiomonte Como",
            outwardOrigin = Station("curated:S00001", "Chiomonte", "Chiomonte", aliases = listOf("Milano Centrale")),
            outwardDestination = Station("curated:S01122", "Como San Giovanni", "Como", aliases = listOf("Como S. Giovanni"))
        )
        val exonymResult = adapter.loadRoute(exonym, Direction.OUTBOUND, JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0)))
        assertEquals(RouteFailureReason.RouteNotCovered, (exonymResult as DataResult.Failure).reason)

        val result = adapter.loadRoute(aliased, Direction.OUTBOUND, JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0)))

        assertTrue(result.toString(), result is DataResult.Success)
        assertEquals("2581", (result as DataResult.Success).snapshot.services.single().number)
    }

    @Test
    fun `non propone fermate dove pickup o drop off sono vietati`() = runBlocking {
        val noPickupAtOrigin = adapter(
            feed(
                stopTimes = "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\n" +
                    "T1,08:05:00,08:05:00,MI,1,1,0\n" +
                    "T1,08:22:00,08:23:00,MO,2,0,0\n" +
                    "T1,08:50:00,08:50:00,CO,3,0,0\n"
            )
        )
        val noDropOffAtDestination = adapter(
            feed(
                stopTimes = "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\n" +
                    "T1,08:05:00,08:05:00,MI,1,0,0\n" +
                    "T1,08:22:00,08:23:00,MO,2,0,0\n" +
                    "T1,08:50:00,08:50:00,CO,3,0,1\n"
            )
        )
        val phoneAgency = adapter(
            feed(
                stopTimes = "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\n" +
                    "T1,08:05:00,08:05:00,MI,1,2,\n" +
                    "T1,08:22:00,08:23:00,MO,2,,\n" +
                    "T1,08:50:00,08:50:00,CO,3,,3\n"
            )
        )
        val query = JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))

        assertTrue(noPickupAtOrigin.loadRoute(route, Direction.OUTBOUND, query) is DataResult.Failure)
        assertTrue(noDropOffAtDestination.loadRoute(route, Direction.OUTBOUND, query) is DataResult.Failure)
        assertTrue(phoneAgency.loadRoute(route, Direction.OUTBOUND, query) is DataResult.Success)
    }

    @Test
    fun `una ricerca serale trova i primi treni dopo mezzanotte del giorno successivo`() = runBlocking {
        val adapter = adapter(
            feed(
                stopTimes = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                    "T1,00:02:00,00:02:00,MI,1\n" +
                    "T1,00:20:00,00:21:00,MO,2\n" +
                    "T1,00:45:00,00:45:00,CO,3\n"
            )
        )
        // Thursday 23:30: the Friday 00:02 departure is 32 minutes away.
        val late = adapter.loadRoute(route, Direction.OUTBOUND, JourneyQuery(LocalDate.of(2026, 9, 3), LocalTime.of(23, 30)))
        assertTrue(late.toString(), late is DataResult.Success)
        assertEquals(
            LocalDateTime.of(2026, 9, 4, 0, 2),
            (late as DataResult.Success).snapshot.services.single().scheduledDepartureDateTime
        )
        // Thursday 08:00: Friday 00:02 is beyond the look-ahead and is not offered as "next".
        val morning = adapter.loadRoute(route, Direction.OUTBOUND, JourneyQuery(LocalDate.of(2026, 9, 3), LocalTime.of(8, 0)))
        assertTrue(morning is DataResult.Failure)
    }

    @Test
    fun `non inventa corse quando la tratta non esiste`() = runBlocking {
        val missing = route.copy(outwardDestination = Station("bergamo", "Bergamo", "Bergamo"))

        assertTrue(
            adapter(feed()).loadRoute(
                missing,
                Direction.OUTBOUND,
                JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
            ) is DataResult.Failure
        )
    }

    @Test
    fun `segnala la validita del pack quando la data richiesta supera lastServiceDate`() = runBlocking {
        val expiredPack = EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "gtfs/test.zip", LocalDate.of(2026, 12, 31))
        val bytes = feed()
        val adapter = EmbeddedGtfsRouteAdapter(
            openAsset = { ByteArrayInputStream(bytes) },
            packs = listOf(expiredPack),
            clock = { 1_000L }
        )

        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2027, 1, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Failure)
        val message = (result as DataResult.Failure).message
        assertTrue(message, message.contains("2026-12-31"))
        assertTrue(message, message.contains("aggiorna l'app"))
        assertEquals(RouteFailureReason.ScheduleExpired(LocalDate.of(2026, 12, 31)), result.reason)
    }

    @Test
    fun `senza lastServiceDate mantiene il messaggio generico di assenza corse`() = runBlocking {
        val result = adapter(feed()).loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2027, 1, 4), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Failure)
        assertFalse((result as DataResult.Failure).message.contains("aggiorna l'app"))
        assertEquals(RouteFailureReason.NoServiceAfter(LocalDate.of(2027, 1, 4), LocalTime.of(8, 0)), result.reason)
    }

    @Test
    fun `cache stop presence retains all ten configured packs`() = runBlocking {
        val packs = EmbeddedGtfsPacks.all
        val bytes = feed()
        var assetOpenCount = 0
        val adapter = EmbeddedGtfsRouteAdapter(
            openAsset = { assetOpenCount += 1; ByteArrayInputStream(bytes) },
            packs = packs,
            clock = { 1_000L }
        )
        val query = JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))

        adapter.loadRoute(route, Direction.OUTBOUND, query)
        adapter.loadRoute(route, Direction.OUTBOUND, query)

        assertEquals(10, packs.size)
        assertEquals(packs.size, adapter.stopPresenceCacheSizeForTest())
        // Each pass reparses all packs through the intentionally two-entry full-index LRU;
        // the second pass must not additionally reopen every zip just to rediscover stops.
        assertEquals(packs.size * 3, assetOpenCount)
    }

    @Test
    fun `lettura asset usa I O e scansione GTFS usa dispatcher CPU configurato`() = runBlocking {
        val ioDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "gtfs-test-io") }.asCoroutineDispatcher()
        val cpuDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "gtfs-test-cpu") }.asCoroutineDispatcher()
        var openThread: String? = null
        var parsingThread: String? = null
        val bytes = feed()
        val adapter = EmbeddedGtfsRouteAdapter(
            openAsset = {
                openThread = Thread.currentThread().name
                ByteArrayInputStream(bytes)
            },
            packs = listOf(pack),
            clock = { 1_000L },
            ioDispatcher = ioDispatcher,
            parsingDispatcher = cpuDispatcher,
            onParsing = { parsingThread = Thread.currentThread().name }
        )

        try {
            val result = adapter.loadRoute(
                route,
                Direction.OUTBOUND,
                JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
            )

            assertTrue(result is DataResult.Success)
            assertTrue(openThread.orEmpty().startsWith("gtfs-test-io"))
            assertTrue(parsingThread.orEmpty().startsWith("gtfs-test-cpu"))
        } finally {
            ioDispatcher.close()
            cpuDispatcher.close()
        }
    }

    private fun adapter(bytes: ByteArray) = EmbeddedGtfsRouteAdapter(
        openAsset = { ByteArrayInputStream(bytes) },
        packs = listOf(pack),
        clock = { 1_000L }
    )

    private fun feed(
        calendarDates: String? = null,
        stopTimes: String? = null,
        tripId: String = "T1",
        tripNumber: String = "2581",
        departure: String = "08:05:00"
    ): ByteArray {
        val effectiveStopTimes = stopTimes ?: (
            "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "$tripId,$departure,$departure,MI,1\n" +
                "$tripId,08:22:00,08:23:00,MO,2\n" +
                "$tripId,08:50:00,08:50:00,CO,3\n"
            )
        val content = linkedMapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\nT,Test Rail,https://example.test,Europe/Rome\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nMI,Milano Centrale,45.48,9.20\nMO,Monza,45.58,9.27\nCO,Como S. Giovanni,45.80,9.08\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\nR,T,RE,Milano Como,2\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign,trip_short_name\nR,W,$tripId,Como S. Giovanni,$tripNumber\n",
            "stop_times.txt" to effectiveStopTimes,
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nW,1,1,1,1,1,0,0,20260101,20261231\n"
        )
        calendarDates?.let { content["calendar_dates.txt"] = it }

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            content.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
