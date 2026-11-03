package it.binario.app.data.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class OperationalTimingTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val source = SourceRef(
        sourceId = SourceId("test-operator"),
        nativeId = "event-42",
        observedAt = Instant.parse("2026-08-15T08:07:00Z"),
        fetchedAt = Instant.parse("2026-08-15T08:08:00Z"),
        confidence = Confidence.OPERATOR_PRIMARY,
        traceId = "test-event-42"
    )

    @Test
    fun `conserva ritardo ufficiale e calcola ritardo alla rilevazione`() {
        val scheduled = ZonedDateTime.of(2026, 8, 15, 10, 0, 0, 0, zone)
        val actual = scheduled.plusMinutes(7)
        val detection = OperationalDetection(
            locationLabel = "Bologna Centrale",
            stationId = StationId("it:bol-c"),
            kind = OperationalEventKind.STATION_DEPARTURE,
            scheduledAt = scheduled,
            actualAt = SourcedValue(actual, source),
            source = source
        )
        val official = OfficialDelayReport(SourcedValue(Duration.ofMinutes(7), source), actual)

        val assessment = OperationalDelayCalculator.assess(official, detection)

        assertEquals(Duration.ofMinutes(7), assessment.official?.delay?.value)
        assertEquals(Duration.ofMinutes(7), assessment.atLastDetection?.delay?.value)
        assertEquals(DelayConsistency.CONSISTENT, assessment.consistency)
        assertNotNull(assessment.atLastDetection?.detection)
    }

    @Test
    fun `segnala divergenza senza eliminare il ritardo ufficiale`() {
        val scheduled = ZonedDateTime.of(2026, 8, 15, 10, 0, 0, 0, zone)
        val detection = OperationalDetection(
            locationLabel = "Bologna Centrale",
            kind = OperationalEventKind.INFRASTRUCTURE_DETECTION,
            scheduledAt = scheduled,
            actualAt = SourcedValue(scheduled.plusMinutes(12), source),
            source = source
        )
        val official = OfficialDelayReport(SourcedValue(Duration.ofMinutes(5), source))

        val assessment = OperationalDelayCalculator.assess(official, detection)

        assertEquals(Duration.ofMinutes(5), assessment.official?.delay?.value)
        assertEquals(Duration.ofMinutes(12), assessment.atLastDetection?.delay?.value)
        assertEquals(DelayConsistency.DIVERGENT, assessment.consistency)
        assertTrue(assessment.difference!! >= Duration.ofMinutes(7))
    }

    @Test
    fun `assenza della rilevazione non inventa ritardo calcolato`() {
        val official = OfficialDelayReport(SourcedValue(Duration.ofMinutes(4), source))

        val assessment = OperationalDelayCalculator.assess(official, null)

        assertEquals(Duration.ofMinutes(4), assessment.official?.delay?.value)
        assertEquals(null, assessment.atLastDetection)
        assertEquals(DelayConsistency.NOT_COMPARABLE, assessment.consistency)
    }
}
