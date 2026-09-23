package it.binario.app.data.gtfs

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.SourceId
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EmbeddedGtfsRouteAdapterTest {
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
