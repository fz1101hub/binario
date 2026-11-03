package it.binario.app.data.live

import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.bikesharing.GbfsParser
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Explicit JSON nulls. On a device `optString` turns them into the text "null"; in the unit tests' org.json into "".
 * The adapters read them through [textOrNull], so both behave as "absent".
 */
class AndroidSafeJsonTest {
    @Test fun `null, missing and blank values are absent`() {
        val json = JSONObject("""{"a":null,"b":"","c":"  ","d":"x","e":12}""")
        assertNull(json.textOrNull("a"))
        assertNull(json.textOrNull("b"))
        assertNull(json.textOrNull("c"))
        assertNull(json.textOrNull("missing"))
        assertEquals("x", json.textOrNull("d"))
        assertEquals("12", json.textOrNull("e"))
        assertEquals("fallback", json.textOr("a", "fallback"))
    }

    @Test fun `Italo board with null station and update time keeps its rows`() {
        val clock = Clock.fixed(Instant.parse("2026-10-16T08:00:00Z"), ZoneOffset.UTC)
        val route = SavedRoute("t", "t", Station("roma-termini", "Roma Termini", "Roma"), Station("napoli", "Napoli Centrale", "Napoli"))
        val json = """{"IsEmpty":false,"DescrizioneLocalita":null,"LastUpdate":null,"ListaTreniPartenza":[
            {"Numero":"9911","DescrizioneLocalita":"Napoli Centrale","OraPassaggio":"10:05","Ritardo":"5","Binario":null,"NuovoOrario":null}]}"""
        val row = parseItaloPublicBoard(json, "Roma Termini", route, Direction.OUTBOUND, clock).single()
        assertTrue(row.operationalStatusVerified)
        assertEquals("10:10", row.expectedDeparture)
        assertNull(row.platform)
    }

    @Test fun `free-floating vehicles with a null station_id are kept`() {
        val json = """{"data":{"vehicles":[{"vehicle_id":"v1","lat":41.9,"lon":12.5,"station_id":null,"vehicle_type_id":null}]}}"""
        assertEquals(1, GbfsParser.vehicles(json, emptyMap()).size)
    }

    @Test fun `STA trip updates with null start date, relationship and stop id are kept`() {
        val json = """{"header":{"timestamp":1789000000},"entity":[{"id":"e1","trip_update":{
            "trip":{"trip_id":"T1","start_date":null,"schedule_relationship":null},
            "stop_time_update":[{"stop_id":null,"stop_sequence":2,"schedule_relationship":null,"departure":{"delay":120}}]}}]}"""
        val update = StaRealtimeAdapter(fetcher = StaRealtimeFetcher { json }).parseTripUpdates(json, Instant.EPOCH).single()
        assertNull(update.serviceDate)
        assertNull(update.sourceStopId)
        assertEquals(2, update.stopSequence)
    }
}
