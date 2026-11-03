package it.binario.app.data.core

import it.binario.app.data.gtfs.ResolvedScheduledStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RealtimeOperationalEnricherTest {
    private val source = SourceRef(
        sourceId = SourceId("sncf-gtfs-rt"),
        nativeId = "tu-1",
        observedAt = Instant.parse("2026-08-15T08:00:00Z"),
        fetchedAt = Instant.parse("2026-08-15T08:00:00Z"),
        expiresAt = Instant.parse("2026-08-15T08:02:00Z"),
        confidence = Confidence.OPEN_DATA,
        licenseId = "Etalab",
        traceId = "test:tu-1"
    )
    private val zone = ZoneId.of("Europe/Paris")
    private val scheduled = ResolvedScheduledStop(
        sourceId = SourceId("sncf-gtfs"),
        sourceTripId = "trip-42",
        sourceStopId = "FRPMO",
        stopSequence = 3,
        locationLabel = "Paris Montparnasse",
        scheduledAt = LocalDate.of(2026, 8, 15).atTime(10, 0).atZone(zone)
    )

    @Test
    fun `una previsione conserva il ritardo ufficiale ma non crea rilevazione`() {
        val update = RealtimeStopUpdate(
            runId = TrainRunId("sncf-gtfs:trip-42"),
            sourceTripId = "trip-42",
            sourceStopId = "FRPMO",
            stopSequence = 3,
            predictedAt = SourcedValue(scheduled.scheduledAt.plusMinutes(5), source),
            officialDelay = SourcedValue(Duration.ofMinutes(5), source),
            observedAt = source.observedAt!!,
            source = source
        )

        val enriched = RealtimeOperationalEnricher.enrich(update, scheduled)

        assertNotNull(enriched)
        assertEquals(5L, enriched?.officialDelay?.delay?.value?.toMinutes())
        assertNull(enriched?.operationalDetection)
    }

    @Test
    fun `un evento effettivo crea rilevazione e ritardo calcolabile`() {
        val update = RealtimeStopUpdate(
            runId = TrainRunId("sncf-gtfs:trip-42"),
            sourceTripId = "trip-42",
            sourceStopId = "FRPMO",
            stopSequence = 3,
            actualAt = SourcedValue(scheduled.scheduledAt.plusMinutes(7), source),
            observedAt = source.observedAt!!,
            source = source
        )

        val enriched = RealtimeOperationalEnricher.enrich(update, scheduled)
        val assessment = OperationalDelayCalculator.assess(enriched?.officialDelay, enriched?.operationalDetection)

        assertEquals("Paris Montparnasse", enriched?.operationalDetection?.locationLabel)
        assertEquals(7L, assessment.atLastDetection?.delay?.value?.toMinutes())
    }
}
