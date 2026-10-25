package it.binario.app.data.live

import it.binario.app.data.repository.TrainNumberQuery
import it.binario.app.data.provider.ProviderResult
import java.time.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class ItaloTrainNumberAdapterTest {
    @Test fun `current NumeroTreno response field is recognized and checked against the requested number`() {
        val root = payload().apply { getJSONObject("TrainSchedule").apply { remove("TrainNumber"); put("NumeroTreno", 9920) } }
        val source = ItaloTrainNumberAdapter(clock = clock)
        assertEquals("9920", source.parse(root, query, now)?.number)
        assertNull(source.parse(root, query.copy(number = "9950"), now))
    }
    private val now = Instant.parse("2026-10-16T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("Europe/Rome"))
    private val query = TrainNumberQuery("9920", LocalDate.of(2026, 10, 16))
    private fun stop(code: String, name: String, sequence: Int, time: String) = JSONObject()
        .put("LocationCode", code).put("LocationDescription", name).put("StationNumber", sequence)
        .put("EstimatedDepartureTime", time).put("EstimatedArrivalTime", time)
    private fun payload() = JSONObject().put("IsEmpty", false).put("LastUpdate", now.toString()).put("TrainSchedule", JSONObject()
        .put("TrainNumber", 9920).put("DepartureDate", "2026-10-16")
        .put("Distruption", JSONObject().put("DelayAmount", 6).put("RunningState", "IN CORSA"))
        .put("StazionePartenza", stop("A", "Torino Porta Nuova", 1, "08:00").put("ActualDepartureTime", "08:06"))
        .put("StazioniFerme", JSONArray().put(stop("B", "Milano Centrale", 2, "09:00").put("ActualArrivalDateTime", "2026-10-16T09:06:00")))
        .put("StazioniNonFerme", JSONArray().put(stop("C", "Roma Termini", 3, "12:00"))))

    @Test fun `direct public number lookup returns full run and fresh delay with no route`() = runBlocking {
        val paths = mutableListOf<String>()
        val source = ItaloTrainNumberAdapter({ paths += it; payload().toString() }, clock)
        val train = (source.searchByNumber(query) as ProviderResult.Success).value.single().service
        assertEquals("Torino Porta Nuova", train.origin)
        assertEquals("Roma Termini", train.destination)
        assertEquals(3, train.stops.size)
        assertEquals("08:00", train.scheduledDeparture)
        assertEquals("08:06", train.expectedDeparture)
        assertEquals(6, train.officialDelayMinutes)
        assertTrue(train.operationalStatusVerified)
        source.searchByNumber(query)
        assertEquals(listOf("https://italoinviaggio.italotreno.com/api/RicercaTrenoService?TrainNumber=9920"), paths)
    }
    @Test fun `missing or stale observation does not turn missing delay into zero live`() {
        for (root in listOf(payload().put("LastUpdate", now.minusSeconds(600).toString()), payload().apply { getJSONObject("TrainSchedule").getJSONObject("Distruption").remove("DelayAmount") })) {
            val train = ItaloTrainNumberAdapter(clock = clock).parse(root, query, now)!!
            assertFalse(train.operationalStatusVerified)
            assertNull(train.officialDelayMinutes)
        }
    }
    @Test fun `placeholder midnight actuals cannot verify an unstarted daytime train`() {
        val root = payload().apply { getJSONObject("TrainSchedule").apply {
            getJSONObject("Distruption").put("RunningState", 0).put("DelayAmount", 0)
            getJSONObject("StazionePartenza").put("ActualDepartureTime", "00:00")
            getJSONArray("StazioniFerme").getJSONObject(0).put("ActualArrivalDateTime", "2026-10-16T00:00:00")
        } }
        val train = ItaloTrainNumberAdapter(clock = clock).parse(root, query, now)!!
        assertFalse(train.operationalStatusVerified)
        assertNull(train.officialDelayMinutes)
        assertTrue(train.stops.all { it.operationalStatusVerified == false })
    }

    @Test fun `wrong number and date cannot substitute a matching run`() {
        val source = ItaloTrainNumberAdapter(clock = clock)
        assertNull(source.parse(payload(), query.copy(number = "9950"), now))
        assertNull(source.parse(payload(), query.copy(date = query.date.plusDays(1)), now))
    }
    @Test fun `empty operator response is a successful no match and failures are not matches`() = runBlocking {
        assertTrue((ItaloTrainNumberAdapter({ "{\"IsEmpty\":true}" }, clock).searchByNumber(query) as ProviderResult.Success).value.isEmpty())
        assertTrue(ItaloTrainNumberAdapter({ throw java.io.IOException("offline") }, clock).searchByNumber(query) is ProviderResult.Failure)
    }
    @Test fun `scheduled times crossing midnight retain the correct full run date`() {
        val root = payload().apply { getJSONObject("TrainSchedule").apply {
            put("StazionePartenza", stop("A", "Origin", 1, "23:50"))
            put("StazioniFerme", JSONArray())
            put("StazioniNonFerme", JSONArray().put(stop("B", "Destination", 2, "00:10")))
        } }
        val train = ItaloTrainNumberAdapter(clock = clock).parse(root, query, now)!!
        assertEquals("23:50", train.scheduledDeparture)
        assertEquals("00:16", train.stops.last().expected)
    }
}
