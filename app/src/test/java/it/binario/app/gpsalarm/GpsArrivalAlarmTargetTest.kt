package it.binario.app.gpsalarm

import it.binario.app.StopAlarmPlan
import it.binario.app.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsArrivalAlarmTargetTest {
    private val target = GpsArrivalAlarmTarget(
        trainNumber = "FR 9512",
        trainDestination = "Napoli C.le | Piazza Garibaldi",
        stopName = "Firenze S. M. Novella",
        stationId = "curated:S06421",
        latitude = 43.77658,
        longitude = 11.247527,
        radiusMeters = 20_000,
        expectedTime = "10:45",
        arrivalEpochMillis = 1_780_000_000_000L,
        timeAlarmTriggerEpochMillis = 1_779_999_700_000L,
        expiresAtEpochMillis = 1_780_007_200_000L
    )

    @Test
    fun `codec round trips a target including the separator and accents`() {
        val withAccents = target.copy(stopName = "Città di Castello", trainDestination = "Bolzano/Bozen | Südtirol")
        assertEquals(target, GpsArrivalAlarmTargetCodec.decode(GpsArrivalAlarmTargetCodec.encode(target)))
        assertEquals(withAccents, GpsArrivalAlarmTargetCodec.decode(GpsArrivalAlarmTargetCodec.encode(withAccents)))
    }

    @Test
    fun `codec persists only the timetable and catalogue fields`() {
        val parts = GpsArrivalAlarmTargetCodec.encode(target).split("|")
        assertEquals(12, parts.size)
        assertEquals("gps1", parts[0])
        // The only coordinates are the catalogue station's.
        assertEquals("43.77658", parts[5])
        assertEquals("11.247527", parts[6])
    }

    @Test
    fun `malformed values decode to null`() {
        val encoded = GpsArrivalAlarmTargetCodec.encode(target)
        val parts = encoded.split("|")
        fun with(index: Int, value: String) = parts.toMutableList().also { it[index] = value }.joinToString("|")
        assertNull(GpsArrivalAlarmTargetCodec.decode(null))
        assertNull(GpsArrivalAlarmTargetCodec.decode(""))
        assertNull(GpsArrivalAlarmTargetCodec.decode(encoded.replaceFirst("gps1", "gps0")))
        assertNull(GpsArrivalAlarmTargetCodec.decode("$encoded|extra"))
        assertNull(GpsArrivalAlarmTargetCodec.decode(with(1, "")))
        assertNull(GpsArrivalAlarmTargetCodec.decode(with(1, "!!not base64!!")))
        assertNull(GpsArrivalAlarmTargetCodec.decode(with(5, "91.0")))
        assertNull(GpsArrivalAlarmTargetCodec.decode(with(6, "NaN")))
        assertNull(GpsArrivalAlarmTargetCodec.decode(with(7, "4000")))
        assertNull(GpsArrivalAlarmTargetCodec.decode(with(9, "soon")))
        assertNull(GpsArrivalAlarmTargetCodec.decode(with(11, "-1")))
    }

    @Test
    fun `target is built from the stop alarm plan and gives the same plan back`() {
        val plan = StopAlarmPlan("9512", "Napoli Centrale", "Roma Termini", "12:00", 1_000_000L, 1_300_000L)
        val station = GpsAlarmStation("curated:S08409", "Roma Termini", 41.900636, 12.502026)

        val built = GpsArrivalAlarmTarget.from(plan, station, 15_000)

        assertEquals("Roma Termini", built.stopName)
        assertEquals("curated:S08409", built.stationId)
        assertEquals(15_000, built.radiusMeters)
        assertEquals(1_300_000L, built.arrivalEpochMillis)
        assertEquals(1_300_000L + GpsArrivalAlarmPolicy.AFTER_ARRIVAL_MILLIS, built.expiresAtEpochMillis)
        assertEquals(plan, built.timeAlarmPlan())
        assertEquals(true, built.isFor("9512", "Roma Termini"))
        assertEquals(false, built.isFor("9512", "Napoli Centrale"))
    }

    @Test
    fun `stop names resolve to catalogue coordinates by name, alias or timetable abbreviation`() {
        val stations = listOf(
            station("curated:S01700", "Milano Centrale", 45.486347, 9.204528, aliases = listOf("Mailand")),
            station("curated:S06421", "Firenze S. M. Novella", 43.77658, 11.247527, aliases = listOf("Firenze Santa Maria Novella")),
            station("curated:S08409", "Roma Termini", 41.900636, 12.502026)
        )

        assertEquals("curated:S08409", GpsArrivalAlarmStations.resolve("ROMA TERMINI", stations)?.id)
        assertEquals("curated:S06421", GpsArrivalAlarmStations.resolve("Firenze Santa Maria Novella", stations)?.id)
        assertEquals("curated:S01700", GpsArrivalAlarmStations.resolve("MILANO C.LE", stations)?.id)
        assertEquals(41.900636, GpsArrivalAlarmStations.resolve("Roma Termini", stations)!!.latitude, 0.0)
        assertNull(GpsArrivalAlarmStations.resolve("Roma Tiburtina", stations))
        assertNull(GpsArrivalAlarmStations.resolve("", stations))
    }

    @Test
    fun `stops without coordinates or with distant namesakes have no gps option`() {
        val noCoordinates = listOf(station("custom:1", "Fermata Nuova", null, null))
        assertNull(GpsArrivalAlarmStations.resolve("Fermata Nuova", noCoordinates))

        val namesakes = listOf(
            station("curated:A", "San Giorgio", 45.0, 9.0),
            station("curated:B", "San Giorgio", 41.0, 15.0)
        )
        assertNull(GpsArrivalAlarmStations.resolve("San Giorgio", namesakes))

        val samePlace = listOf(
            station("curated:A", "Bivio Centro", 45.0, 9.0),
            station("curated:B", "Bivio Centro", 45.001, 9.001)
        )
        assertEquals("curated:A", GpsArrivalAlarmStations.resolve("Bivio Centro", samePlace)?.id)
    }

    @Test
    fun `a user station without coordinates does not hide the curated entry for the same stop`() {
        val stations = listOf(
            station("custom:1", "Milano C.le", null, null),
            station("curated:S01700", "Milano Centrale", 45.486347, 9.204528)
        )
        assertEquals("curated:S01700", GpsArrivalAlarmStations.resolve("Milano C.le", stations)?.id)
    }

    private fun station(id: String, name: String, latitude: Double?, longitude: Double?, aliases: List<String> = emptyList()) =
        Station(id = id, name = name, city = name, aliases = aliases, latitude = latitude, longitude = longitude)
}
