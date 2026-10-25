package it.binario.app

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRiskTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val day = LocalDate.of(2026, 10, 12)
    private val now = ZonedDateTime.of(day.atTime(9, 0), zone).toInstant()

    @Test
    fun `buffer below zero is missed, below five minutes at risk, otherwise feasible`() {
        assertEquals(ConnectionRisk.MISSED, ConnectionRiskClassifier.classify(Duration.ofSeconds(-1)))
        assertEquals(ConnectionRisk.AT_RISK, ConnectionRiskClassifier.classify(Duration.ZERO))
        assertEquals(ConnectionRisk.AT_RISK, ConnectionRiskClassifier.classify(Duration.ofMinutes(5).minusSeconds(1)))
        assertEquals(ConnectionRisk.OK, ConnectionRiskClassifier.classify(Duration.ofMinutes(5)))
        assertEquals(ConnectionRisk.OK, ConnectionRiskClassifier.classify(Duration.ofMinutes(40)))
    }

    @Test
    fun `a scheduled-only arrival keeps the scheduled time even when the source repeats another one`() {
        val service = service(verified = false, stops = listOf(stop("Roma Termini", "08:00"), stop("Napoli Centrale", "09:10", expected = "09:25", verified = false)))

        val arrival = ConnectionRiskClassifier.arrival(service, service.stops.last(), day, zone)!!

        assertFalse(arrival.isLive)
        assertEquals(at(9, 10), arrival.scheduled)
        assertEquals(arrival.scheduled, arrival.expected)
    }

    @Test
    fun `a verified arrival uses the expected time and the board starts from the scheduled one`() {
        val service = service(verified = true, stops = listOf(stop("Roma Termini", "08:00"), stop("Napoli Centrale", "09:10", expected = "09:22")))

        val arrival = ConnectionRiskClassifier.arrival(service, service.stops.last(), day, zone)!!

        assertTrue(arrival.isLive)
        assertEquals(at(9, 22), arrival.expected)
        assertEquals(at(9, 10), arrival.boardFrom)
    }

    @Test
    fun `stop times after midnight roll to the next day and the expected time stays near the scheduled one`() {
        val departure = LocalDateTime.of(day, java.time.LocalTime.of(23, 30))
        val late = service(verified = true, departure = departure, stops = listOf(stop("Milano Centrale", "23:30"), stop("Torino Porta Nuova", "00:20", expected = "00:41")))
        val early = service(verified = true, departure = departure, stops = listOf(stop("Milano Centrale", "23:30"), stop("Torino Porta Nuova", "00:05", expected = "23:58")))

        val lateArrival = ConnectionRiskClassifier.arrival(late, late.stops.last(), day, zone)!!
        val earlyArrival = ConnectionRiskClassifier.arrival(early, early.stops.last(), day, zone)!!

        assertEquals(ZonedDateTime.of(day.plusDays(1).atTime(0, 20), zone).toInstant(), lateArrival.scheduled)
        assertEquals(ZonedDateTime.of(day.plusDays(1).atTime(0, 41), zone).toInstant(), lateArrival.expected)
        assertEquals(ZonedDateTime.of(day.atTime(23, 58), zone).toInstant(), earlyArrival.expected)
    }

    @Test
    fun `an unreadable stop time gives no arrival instead of a guess`() {
        val service = service(verified = true, stops = listOf(stop("Roma Termini", "08:00"), stop("Napoli Centrale", "--")))

        assertNull(ConnectionRiskClassifier.arrival(service, service.stops.last(), day, zone))
    }

    @Test
    fun `connections keep the window, drop this train and classify each buffer`() {
        val arrival = ConnectionArrival("Napoli Centrale", scheduled = at(9, 10), expected = at(9, 22), isLive = true)
        val entries = listOf(
            entry("2345", 9, 40, number = "REG 2345"), // this same train continuing
            entry("a", 9, 5), // left before the scheduled arrival: not a connection
            entry("b", 9, 15), // planned connection the delay made impossible
            entry("c", 9, 24), // two minutes
            entry("d", 9, 20, expected = 9 to 35), // delayed itself: 13 minutes
            entry("e", 10, 30, status = RunStatus.CANCELLED),
            entry("f", 10, 52), // exactly at the end of the window
            entry("g", 10, 53) // after the window
        )

        val connections = ConnectionRiskClassifier.connections(arrival, entries, "2345", now)

        assertEquals(listOf("b", "c", "d", "e", "f"), connections.map { it.entry.publicNumber })
        assertEquals(
            listOf(ConnectionRisk.MISSED, ConnectionRisk.AT_RISK, ConnectionRisk.OK, ConnectionRisk.CANCELLED, ConnectionRisk.OK),
            connections.map { it.risk }
        )
        assertEquals(Duration.ofMinutes(-7), connections.first().buffer)
        assertTrue(connections.single { it.entry.publicNumber == "d" }.isLive)
        assertFalse(connections.single { it.entry.publicNumber == "c" }.isLive)
    }

    @Test
    fun `an expired live prediction counts as the scheduled time and is not shown as live`() {
        val arrival = ConnectionArrival("Napoli Centrale", scheduled = at(9, 10), expected = at(9, 10), isLive = false)
        val expired = entry("h", 9, 12, expected = 9 to 30, expiresAt = now.minusSeconds(60))

        val connection = ConnectionRiskClassifier.connections(arrival, listOf(expired), "2345", now).single()

        assertFalse(connection.isLive)
        assertEquals(at(9, 12), connection.departure)
        assertEquals(ConnectionRisk.AT_RISK, connection.risk)
    }

    @Test
    fun `display keeps the last three missed connections and the reachable ones`() {
        val arrival = ConnectionArrival("Napoli Centrale", scheduled = at(9, 0), expected = at(10, 0), isLive = true)
        val entries = (0 until 6).map { entry("m$it", 9, 5 + it * 5) } + entry("ok", 10, 20)

        val shown = ConnectionRiskClassifier.forDisplay(ConnectionRiskClassifier.connections(arrival, entries, "9999", now))

        assertEquals(listOf("m3", "m4", "m5", "ok"), shown.map { it.entry.publicNumber })
    }

    @Test
    fun `the chosen stop wins while the train has not passed it, otherwise the last stop`() {
        val stops = listOf(stop("Roma Termini", "08:00"), stop("Latina", "08:40"), stop("Napoli Centrale", "09:10"))
        val passed = listOf(stop("Roma Termini", "08:00"), stop("Latina", "08:40", completed = true), stop("Napoli Centrale", "09:10"))

        assertEquals("Latina", ConnectionRiskClassifier.connectionStop(stops, "latina")?.station)
        assertEquals("Napoli Centrale", ConnectionRiskClassifier.connectionStop(passed, "Latina")?.station)
        assertEquals("Napoli Centrale", ConnectionRiskClassifier.connectionStop(stops, "Roma Termini")?.station)
        assertEquals("Napoli Centrale", ConnectionRiskClassifier.connectionStop(stops, null)?.station)
        assertNull(ConnectionRiskClassifier.connectionStop(stops.take(1), null))
    }

    @Test
    fun `train identity compares the public number, not its category prefix`() {
        assertTrue(ConnectionRiskClassifier.isSameTrain("REG 2345", "2345"))
        assertTrue(ConnectionRiskClassifier.isSameTrain("FR 09520", "9520"))
        assertFalse(ConnectionRiskClassifier.isSameTrain("2346", "2345"))
        assertFalse(ConnectionRiskClassifier.isSameTrain(null, "2345"))
        assertTrue(ConnectionRiskClassifier.isSameTrain("ec", "EC"))
    }

    private fun at(hour: Int, minute: Int): Instant = ZonedDateTime.of(day.atTime(hour, minute), zone).toInstant()

    private fun stop(station: String, scheduled: String, expected: String = scheduled, verified: Boolean? = null, completed: Boolean = false) =
        TrainStop(station, scheduled, expected, platform = null, delayMinutes = 0, completed = completed, operationalStatusVerified = verified)

    private fun service(
        verified: Boolean,
        stops: List<TrainStop>,
        departure: LocalDateTime = LocalDateTime.of(day, java.time.LocalTime.of(8, 0))
    ) = TrainService(
        id = "svc", number = "2345", operator = RailOperator.TRENITALIA_REGIONALE, trainCategory = TrainCategory.REGIONALE,
        origin = stops.first().station, destination = stops.last().station,
        scheduledDeparture = stops.first().scheduled, expectedDeparture = stops.first().scheduled,
        platform = null, delayMinutes = 0, status = ServiceStatus.ON_TIME, stops = stops,
        operationalStatusVerified = verified, scheduledDepartureDateTime = departure
    )

    private fun entry(
        id: String,
        hour: Int,
        minute: Int,
        number: String = id,
        expected: Pair<Int, Int>? = null,
        status: RunStatus? = null,
        expiresAt: Instant = now.plusSeconds(600)
    ): StationBoardEntry {
        val source = SourceRef(
            sourceId = SourceId("rfi-station-board"),
            fetchedAt = now,
            expiresAt = expiresAt,
            confidence = Confidence.INFRASTRUCTURE_PRIMARY,
            traceId = "trace-$id"
        )
        return StationBoardEntry(
            runId = TrainRunId("run-$id"),
            scheduledTime = ZonedDateTime.of(day.atTime(hour, minute), zone),
            expectedTime = expected?.let { (h, m) -> SourcedValue(ZonedDateTime.of(day.atTime(h, m), zone), source) },
            destinationOrOrigin = StationId("caserta"),
            destinationOrOriginLabel = "Caserta",
            publicNumber = number,
            status = status?.let { SourcedValue(it, source) }
        )
    }
}
