package it.binario.app.data.gtfs

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RouteFailureReason
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    @Test fun `shipped schedules contain Milano Centrale to Mendrisio for the reported travel day`() = runBlocking {
        val source = EmbeddedGtfsRouteAdapter(openAsset = { java.io.File("src/main/assets", it).inputStream() },
            packs = EmbeddedGtfsPacks.all.filter { it.sourceId.value in setOf("lombardia-trenord-gtfs", "ch-otd-gtfs") })
        val route = SavedRoute("cross-border", "Milano–Mendrisio",
            Station("curated:S01700", "Milano Centrale", "Milano", railwayCode = "S01700"),
            Station("curated:NC000977", "Mendrisio", "Mendrisio"))
        val result = source.loadRoute(route, Direction.OUTBOUND, JourneyQuery(LocalDate.of(2026, 10, 18), LocalTime.of(8, 0)))
        assertTrue("Cross-border route missing from shipped official schedules: $result", result is DataResult.Success)
        val services = (result as DataResult.Success).snapshot.services
        assertTrue(services.isNotEmpty())
        assertTrue(services.all { it.origin == "Milano Centrale" && it.destination == "Mendrisio" })
        assertTrue(services.all { !it.operationalStatusVerified })
    }

    @Test fun `number matches from the first pack are published before reading a later pack`() = runBlocking {
        val packs = listOf(EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "a"), EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "b"))
        val updates = mutableListOf<List<it.binario.app.data.repository.TrainNumberMatch>>()
        var reads = 0
        val source = EmbeddedGtfsRouteAdapter(openAsset = {
            reads++
            if (it == "b") assertEquals("Earlier pack is already visible", 1, updates.size)
            ByteArrayInputStream(feed())
        }, packs = packs)
        val query = it.binario.app.data.repository.TrainNumberQuery("2581", LocalDate.of(2026, 9, 4))
        val matches = source.searchByNumber(query) { updates += it }
        assertEquals(listOf(1, 2), updates.map { it.size })
        assertEquals(2, matches.size)
        source.searchByNumber(query)
        assertEquals("Repeated lookup reuses both compact results", 2, reads)
    }

    @Test fun `a pack already read for a number is opened again only when it has the number asked`() = runBlocking {
        val packs = listOf(EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "a"), EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "b"))
        val feeds = mapOf("a" to feed(tripNumber = "RE_80 - 2581"), "b" to feed(tripNumber = "IC 9999"))
        val opened = mutableListOf<String>()
        val source = EmbeddedGtfsRouteAdapter(openAsset = { opened += it; ByteArrayInputStream(feeds.getValue(it)) }, packs = packs)
        val date = LocalDate.of(2026, 9, 4)
        suspend fun numbers(number: String) = source.searchByNumber(it.binario.app.data.repository.TrainNumberQuery(number, date)).map { it.service.number }
        // The first lookup reads both packs and learns their numbers.
        assertEquals(listOf("RE_80 - 2581"), numbers("2581"))
        assertEquals(listOf("a", "b"), opened)
        assertEquals(packs.map { it.sourceId }.toSet(), source.knownNumberPacksForTest())
        // A number no pack has opens nothing; a number of the second pack opens that one only.
        opened.clear()
        assertTrue(numbers("1234").isEmpty())
        assertTrue(opened.isEmpty())
        assertEquals(listOf("IC 9999"), numbers("9999"))
        assertEquals(listOf("b"), opened)
        // Another spelling the search accepts still finds the train, again in its pack only.
        opened.clear()
        assertEquals(listOf("IC 9999"), numbers("ic 9999"))
        assertEquals(listOf("RE_80 - 2581"), numbers("R 2581"))
        assertEquals(listOf("b", "a"), opened)
    }

    @Test fun `lookups started together read a pack without their number once, not once each`() = runBlocking {
        val packs = listOf(EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "a"), EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "b"))
        val feeds = mapOf("a" to feed(tripNumber = "2581"), "b" to feed(tripNumber = "9999"))
        val opened = java.util.Collections.synchronizedList(mutableListOf<String>())
        val source = EmbeddedGtfsRouteAdapter(openAsset = { opened += it; ByteArrayInputStream(feeds.getValue(it)) }, packs = packs)
        val date = LocalDate.of(2026, 9, 4)
        val found = coroutineScope {
            listOf("1111", "2222", "2581", "3333").map { number ->
                async(Dispatchers.Default) { source.searchByNumber(it.binario.app.data.repository.TrainNumberQuery(number, date)).size }
            }.awaitAll()
        }
        assertEquals(listOf(0, 0, 1, 0), found)
        // Each pack once to learn its numbers; the pack with 2581 at most once more, for the lookup that came second.
        assertEquals(1, opened.count { it == "b" })
        assertTrue("$opened", opened.count { it == "a" } in 1..2)
    }

    @Test fun `the remembered numbers of a pack accept whatever the number search matches`() {
        val date = LocalDate.of(2026, 9, 4)
        val published = listOf("2581", "RE_11 - 2153", "S5 - 811A", "R1 - 10101", "EC 85", "REG 02581", "NJ 40235", "VE545", "IT::trip:77", "Italo 9939", "  9941 ")
        val asked = published + listOf("2153", "811A", "10101", "85", "EC85", "ec 85", "02581", "40235", "545", "9939", "9941", "RE_11", "S5", "77", "258", "1", "FR 2581", "R 2153")
        val numbers = GtfsPackNumbers.Builder().apply { published.forEach(::add) }.build(date)
        for (number in asked) {
            val query = it.binario.app.data.repository.TrainNumberQuery(number, date)
            val matches = published.any(query::matches)
            assertEquals("number '$number'", matches, numbers.mayContain(query))
        }
    }

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
        // No national pack lists the two stations here, as on a line across the border: the foreign pack comes first.
        assertTrue(services.single().id.startsWith("ch-otd-gtfs:"))
    }

    @Test
    fun `fra i pack che servono le due stazioni la copia tenuta e quella del pack nazionale`() = runBlocking {
        // As Milano Centrale - Verona Porta Nuova: the Swiss, Trenord, Italo and Trenitalia packs all serve the route,
        // and the Swiss and the Trenitalia pack publish the same EuroCity. The national pack's copy is kept.
        val ids = listOf("ch-otd-gtfs", "lombardia-trenord-gtfs", "italo-gtfs", "trenitalia-gtfs")
        val feeds = mapOf(
            "ch-otd-gtfs" to feed(tripId = "CH37", tripNumber = "EC 37", departure = "08:05:00", agencyName = "Schweizerische Bundesbahnen SBB"),
            "lombardia-trenord-gtfs" to feed(tripId = "TN2611", tripNumber = "2611", departure = "08:10:00", agencyName = "Trenord"),
            "italo-gtfs" to feed(tripId = "IT8977", tripNumber = "8977", departure = "08:15:00", agencyName = "Italo"),
            "trenitalia-gtfs" to feed(tripId = "TI37", tripNumber = "EC 37", departure = "08:05:00", agencyName = "TRENITALIA")
        )
        val adapter = EmbeddedGtfsRouteAdapter(
            openAsset = { path -> ByteArrayInputStream(requireNotNull(feeds[path.removePrefix("gtfs/").removeSuffix(".zip")])) },
            packs = ids.map { EmbeddedGtfsPack(SourceId(it), "gtfs/$it.zip") },
            clock = { 1_000L }
        )

        val result = adapter.loadRoute(route, Direction.OUTBOUND, JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0)))

        val services = (result as DataResult.Success).snapshot.services
        assertEquals(listOf("trenitalia-gtfs", "lombardia-trenord-gtfs", "italo-gtfs"), services.map { it.gtfsIdentity?.sourceId })
        assertEquals("TI37", services.first().gtfsIdentity?.tripId)

        // As Milano - Lugano: the Trenitalia pack lists Milano but not the other station, so the Trenord and the Swiss
        // copies of a train both list are in a border zone, where the foreign pack comes first.
        val border = mapOf(
            "lombardia-trenord-gtfs" to feed(tripId = "TN25", tripNumber = "25", departure = "08:05:00", agencyName = "Trenord"),
            "ch-otd-gtfs" to feed(tripId = "CH25", tripNumber = "25", departure = "08:05:00", agencyName = "TILO"),
            "trenitalia-gtfs" to feed(tripId = "TI1", tripNumber = "1", agencyName = "TRENITALIA", stopNames = Triple("Milano Centrale", "Monza", "Lecco"))
        )
        val borderServices = (
            EmbeddedGtfsRouteAdapter(
                openAsset = { path -> ByteArrayInputStream(requireNotNull(border[path.removePrefix("gtfs/").removeSuffix(".zip")])) },
                packs = listOf("trenitalia-gtfs", "lombardia-trenord-gtfs", "ch-otd-gtfs").map { EmbeddedGtfsPack(SourceId(it), "gtfs/$it.zip") },
                clock = { 1_000L }
            ).loadRoute(route, Direction.OUTBOUND, JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))) as DataResult.Success
            ).snapshot.services
        assertEquals(listOf("ch-otd-gtfs"), borderServices.map { it.gtfsIdentity?.sourceId })
    }

    /**
     * Audit android-data-6: Venezia S. Lucia → Venezia Mestre has REG 16348 (Trenitalia) and Italo 8984 both at
     * 13:57 → 14:09 on 10 Nov 2026. Same minutes from two packs are one train only when the numbers match, the
     * operator is the same, or one pack republishes partner trains under its own numbers (cross-border national feeds,
     * South Tyrol's multi-operator feed).
     */
    @Test
    fun `due treni di operatori diversi agli stessi minuti restano entrambi`() = runBlocking {
        val trenitaliaPack = EmbeddedGtfsPack(SourceId("trenitalia-gtfs"), "gtfs/trenitalia.zip")
        val italoPack = EmbeddedGtfsPack(SourceId("italo-gtfs"), "gtfs/italo.zip")
        val query = JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        suspend fun load(vararg feeds: Pair<EmbeddedGtfsPack, ByteArray>): List<it.binario.app.data.TrainService> {
            val byPath = feeds.associate { (pack, bytes) -> pack.assetPath to bytes }
            val adapter = EmbeddedGtfsRouteAdapter(
                openAsset = { path -> ByteArrayInputStream(requireNotNull(byPath[path])) },
                packs = feeds.map { it.first },
                clock = { 1_000L }
            )
            return (adapter.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success).snapshot.services
        }

        val regAndItalo = load(
            trenitaliaPack to feed(tripId = "REG", tripNumber = "16348", agencyName = "TRENITALIA"),
            italoPack to feed(tripId = "ITA", tripNumber = "8984", agencyName = "Italo - Nuovo Trasporto Viaggiatori")
        )
        assertEquals(listOf("16348", "8984"), regAndItalo.map { it.number }.sorted())

        // The same train under the same number from two operators' feeds (Trentino trasporti and Trenitalia) is one
        // row, even when the packs spell the stations differently.
        val trentinoPack = EmbeddedGtfsPack(SourceId("trentino-gtfs"), "gtfs/trentino.zip")
        val aliased = route.copy(outwardOrigin = Station("milano", "Milano Centrale", "Milano", aliases = listOf("Milano C.le")))
        val sameNumber = EmbeddedGtfsRouteAdapter(
            openAsset = { path ->
                ByteArrayInputStream(
                    if (path == trentinoPack.assetPath) {
                        feed(tripId = "TT", tripNumber = "16691", agencyName = "Trentino trasporti S.p.A.",
                            stopNames = Triple("Milano C.le", "Monza", "Como S. Giovanni"))
                    } else {
                        feed(tripId = "REG", tripNumber = "R 16691", agencyName = "TRENITALIA")
                    }
                )
            },
            packs = listOf(trentinoPack, trenitaliaPack),
            clock = { 1_000L }
        ).loadRoute(aliased, Direction.OUTBOUND, query)
        assertEquals(1, (sameNumber as DataResult.Success).snapshot.services.size)

        // A cross-border national feed republishes the Trenord train under its own number and operator: still one row.
        val trenordPack = EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "gtfs/trenord.zip")
        val swissPack = EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "gtfs/swiss.zip")
        val crossBorder = load(
            trenordPack to feed(tripId = "T1", tripNumber = "S30 - 25318", agencyName = "Trenord"),
            swissPack to feed(tripId = "CH1", tripNumber = "25358", agencyName = "Schweizerische Bundesbahnen SBB")
        )
        assertEquals(1, crossBorder.size)

        // Two Italian operators' feeds with different numbers (Trenord RE 2618 from Verona and Trenitalia 95343 from
        // Asti, both Milano Lambrate 08:28 → Milano Centrale 08:35) are two trains.
        val trenordAndTrenitalia = load(
            trenordPack to feed(tripId = "T1", tripNumber = "RE_6 - 2618", agencyName = "Trenord"),
            trenitaliaPack to feed(tripId = "REG", tripNumber = "95343", agencyName = "TRENITALIA")
        )
        assertEquals(2, trenordAndTrenitalia.size)
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
        // Thursday 08:00: nothing else runs on Thursday, so Friday 00:02 is the next train (within 24 hours).
        val morning = adapter.loadRoute(route, Direction.OUTBOUND, JourneyQuery(LocalDate.of(2026, 9, 3), LocalTime.of(8, 0)))
        assertTrue(morning.toString(), morning is DataResult.Success)
        assertEquals(
            LocalDateTime.of(2026, 9, 4, 0, 2),
            (morning as DataResult.Success).snapshot.services.single().scheduledDepartureDateTime
        )
    }

    @Test
    fun `dopo l ultimo treno della giornata propone i primi treni del giorno successivo`() = runBlocking {
        val adapter = adapter(feed())
        // Thursday 14:00: the 08:05 has left; the answer is Friday's 08:05, not "no train after 14:00".
        val afternoon = adapter.loadRoute(route, Direction.OUTBOUND, JourneyQuery(LocalDate.of(2026, 9, 3), LocalTime.of(14, 0)))
        assertTrue(afternoon.toString(), afternoon is DataResult.Success)
        val service = (afternoon as DataResult.Success).snapshot.services.single()
        assertEquals(LocalDateTime.of(2026, 9, 4, 8, 5), service.scheduledDepartureDateTime)
        assertEquals("08:05", service.scheduledDeparture)
        assertEquals(LocalDate.of(2026, 9, 4), service.gtfsIdentity?.serviceDate)
        // Friday 14:00: the weekday-only service has no Saturday run, so the day really is over.
        val friday = adapter.loadRoute(route, Direction.OUTBOUND, JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(14, 0)))
        assertEquals(RouteFailureReason.NoServiceAfter(LocalDate.of(2026, 9, 4), LocalTime.of(14, 0)), (friday as DataResult.Failure).reason)
        // Thursday 08:05 exactly: today's train is offered once, Friday's 08:05 lies outside the window.
        val exact = adapter.loadRoute(route, Direction.OUTBOUND, JourneyQuery(LocalDate.of(2026, 9, 3), LocalTime.of(8, 5)))
        assertEquals(listOf(LocalDateTime.of(2026, 9, 3, 8, 5)), (exact as DataResult.Success).snapshot.services.map { it.scheduledDepartureDateTime })
    }

    @Test
    fun `una partenza malformata mostra l arrivo valido della fermata e mai uno 00 00 inventato`() = runBlocking {
        val query = JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        val malformedDeparture = adapter(
            feed(
                stopTimes = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                    "T1,08:05:00,08:05:00,MI,1\n" +
                    "T1,08:22:00,nope,MO,2\n" +
                    "T1,08:50:00,08:50:00,CO,3\n"
            )
        ).loadRoute(route, Direction.OUTBOUND, query)
        val noTimes = adapter(
            feed(
                stopTimes = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                    "T1,08:05:00,08:05:00,MI,1\n" +
                    "T1,,,MO,2\n" +
                    "T1,08:50:00,08:50:00,CO,3\n"
            )
        ).loadRoute(route, Direction.OUTBOUND, query)

        val monza = (malformedDeparture as DataResult.Success).snapshot.services.single().stops.single { it.station == "Monza" }
        assertEquals("08:22", monza.scheduled)
        assertEquals("08:22", monza.expected)
        val unknown = (noTimes as DataResult.Success).snapshot.services.single().stops.single { it.station == "Monza" }
        assertEquals("—", unknown.scheduled)
        assertEquals("—", unknown.expected)
    }

    /**
     * Audit android-data-1 (the Fortezza → Brunico shape: STA 1839 arrives 07:28 and leaves 07:45 for Lienz): where the
     * traveller gets off, the stop carries the train's arrival, not its departure after the dwell. Duration, the fastest
     * sort, widget, Wear, calendar and connection risk all read it from there.
     */
    @Test
    fun `alla fermata di discesa mostra l arrivo e non la partenza dopo la sosta`() = runBlocking {
        val dwellAtMonza = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
            "T1,08:05:00,08:05:00,MI,1\n" +
            "T1,08:22:00,08:39:00,MO,2\n" +
            "T1,08:50:00,08:50:00,CO,3\n"
        val query = JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        val toMonza = route.copy(outwardDestination = Station("monza", "Monza", "Monza"))

        val service = (adapter(feed(stopTimes = dwellAtMonza)).loadRoute(toMonza, Direction.OUTBOUND, query) as DataResult.Success)
            .snapshot.services.single()
        assertEquals(listOf("08:05", "08:22"), service.stops.map { it.scheduled })
        assertEquals("08:22", service.stops.last().expected)
        assertEquals("08:22", it.binario.app.ui.common.JourneyTimes.arrival(service))
        assertEquals(17, it.binario.app.ui.common.JourneyTimes.durationMinutes(service))

        // Riding on, Monza is an intermediate stop: the time there stays the departure, as before.
        val throughMonza = (adapter(feed(stopTimes = dwellAtMonza)).loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success)
            .snapshot.services.single()
        assertEquals(listOf("08:05", "08:39", "08:50"), throughMonza.stops.map { it.scheduled })

        // Two packs that publish the same train with different dwells at the alighting stop still pair on the arrival.
        val trenordPack = EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "gtfs/trenord.zip")
        val swissPack = EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "gtfs/swiss.zip")
        val shorterDwell = dwellAtMonza.replace("08:39:00", "08:30:00")
        val feeds = mapOf(trenordPack.assetPath to feed(stopTimes = dwellAtMonza), swissPack.assetPath to feed(stopTimes = shorterDwell))
        val twoPacks = EmbeddedGtfsRouteAdapter(
            openAsset = { path -> ByteArrayInputStream(requireNotNull(feeds[path])) },
            packs = listOf(trenordPack, swissPack),
            clock = { 1_000L }
        )
        assertEquals(1, (twoPacks.loadRoute(toMonza, Direction.OUTBOUND, query) as DataResult.Success).snapshot.services.size)
    }

    @Test
    fun `senza trip_short_name usa il headsign numerico o la linea e mai il trip_id`() = runBlocking {
        val query = JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
        val sncfTripId = "OCESN105241F1187_F:NAV:FR:Line::85d3579c-996b-4671-89af-839855ede78a::87571000:87571240:2:1959:20261030"
        val sncfLike = adapter(feed(tripId = sncfTripId, tripNumber = "", headsign = "105241", routeShortName = "A01"))
        val tftLike = adapter(feed(tripId = "6924_92204", tripNumber = "", headsign = "Arezzo Rfi", routeShortName = "445"))

        val sncf = (sncfLike.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success).snapshot.services.single()
        val tft = (tftLike.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success).snapshot.services.single()

        assertEquals("105241", sncf.number)
        assertEquals(sncfTripId, sncf.gtfsIdentity?.tripId)
        assertEquals("445", tft.number)
        // The number search reaches the SNCF train through the same public number.
        assertEquals(1, sncfLike.searchByNumber(it.binario.app.data.repository.TrainNumberQuery("105241", LocalDate.of(2026, 9, 4))).size)
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
    fun `cache stop presence retains every configured pack`() = runBlocking {
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

        // The twelve packs of the first release and the regional railways of gtfs-transit/ (and gtfs-personal/).
        assertTrue(packs.size > 12)
        assertEquals(packs.size, adapter.stopPresenceCacheSizeForTest())
        // Each pass reparses all packs through the bounded full-index LRU (three entries, every pack);
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
        departure: String = "08:05:00",
        headsign: String = "Como S. Giovanni",
        routeShortName: String = "RE",
        agencyName: String = "Test Rail",
        stopNames: Triple<String, String, String> = Triple("Milano Centrale", "Monza", "Como S. Giovanni")
    ): ByteArray {
        val effectiveStopTimes = stopTimes ?: (
            "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "$tripId,$departure,$departure,MI,1\n" +
                "$tripId,08:22:00,08:23:00,MO,2\n" +
                "$tripId,08:50:00,08:50:00,CO,3\n"
            )
        val content = linkedMapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\nT,$agencyName,https://example.test,Europe/Rome\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nMI,${stopNames.first},45.48,9.20\nMO,${stopNames.second},45.58,9.27\n" +
                "CO,${stopNames.third},45.80,9.08\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\nR,T,$routeShortName,Milano Como,2\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign,trip_short_name\nR,W,$tripId,$headsign,$tripNumber\n",
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
