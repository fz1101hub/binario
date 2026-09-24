package it.binario.app.data.gtfs

import it.binario.app.data.core.Confidence
import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.TrainRunId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EmbeddedGtfsRealtimeScheduleResolverTest {
    private val sourceId = SourceId("lombardia-trenord-gtfs")
    private val pack = EmbeddedGtfsPack(sourceId, "gtfs/test.zip")
    private val observedAt = Instant.parse("2026-08-15T08:00:00Z")
    private val source = SourceRef(
        sourceId = sourceId,
        nativeId = "tu-1",
        observedAt = observedAt,
        fetchedAt = observedAt,
        expiresAt = observedAt.plusSeconds(90),
        confidence = Confidence.OPEN_DATA,
        licenseId = "CC-BY-4.0",
        traceId = "test:tu-1"
    )

    @Test
    fun `risolve l'orario programmato della stessa corsa e fermata`() {
        val resolver = EmbeddedGtfsRealtimeScheduleResolver(
            openAsset = { ByteArrayInputStream(feed()) },
            packs = listOf(pack)
        )
        val update = RealtimeStopUpdate(
            runId = TrainRunId("lombardia-trenord-gtfs:T1"),
            sourceTripId = "T1",
            sourceStopId = "MO",
            stopSequence = 2,
            observedAt = observedAt,
            source = source
        )

        val resolved = resolver.resolve(sourceId, update, LocalDate.of(2026, 8, 15), ZoneId.of("Europe/Rome"))

        assertNotNull(resolved)
        assertEquals("Monza", resolved?.locationLabel)
        assertEquals("08:23", resolved?.scheduledAt?.toLocalTime()?.toString()?.take(5))
    }

    @Test
    fun `gestisce correttamente orari GTFS oltre mezzanotte`() {
        val resolver = EmbeddedGtfsRealtimeScheduleResolver(
            openAsset = { ByteArrayInputStream(feed()) },
            packs = listOf(pack)
        )
        val update = RealtimeStopUpdate(
            runId = TrainRunId("lombardia-trenord-gtfs:N1"),
            sourceTripId = "N1",
            sourceStopId = "CO",
            stopSequence = 1,
            observedAt = observedAt,
            source = source
        )

        val resolved = resolver.resolve(sourceId, update, LocalDate.of(2026, 8, 15), ZoneId.of("Europe/Rome"))

        assertNotNull(resolved)
        assertEquals(LocalDate.of(2026, 8, 16), resolved?.scheduledAt?.toLocalDate())
        assertEquals("01:10", resolved?.scheduledAt?.toLocalTime()?.toString()?.take(5))
    }

    @Test
    fun `GTFS service day mantiene ore trascorse nel passaggio all ora legale`() {
        val resolver = EmbeddedGtfsRealtimeScheduleResolver(
            openAsset = { ByteArrayInputStream(feed()) },
            packs = listOf(pack)
        )
        val update = RealtimeStopUpdate(
            runId = TrainRunId("lombardia-trenord-gtfs:N1"),
            sourceTripId = "N1",
            sourceStopId = "CO",
            stopSequence = 1,
            observedAt = observedAt,
            source = source
        )

        val resolved = resolver.resolve(sourceId, update, LocalDate.of(2026, 3, 29), ZoneId.of("Europe/Rome"))

        assertNotNull(resolved)
        // 25:10 is 25h10m after the GTFS service-day origin. On the spring
        // transition day that elapsed instant displays as 02:10 the next day,
        // rather than the wall-clock 01:10 produced by LocalDateTime.atZone().
        assertEquals(LocalDate.of(2026, 3, 30), resolved?.scheduledAt?.toLocalDate())
        assertEquals("02:10", resolved?.scheduledAt?.toLocalTime()?.toString()?.take(5))
    }

    @Test
    fun `GTFS service day mantiene ore trascorse nel ritorno all ora solare`() {
        val resolver = EmbeddedGtfsRealtimeScheduleResolver(
            openAsset = { ByteArrayInputStream(feed()) },
            packs = listOf(pack)
        )
        val update = RealtimeStopUpdate(
            runId = TrainRunId("lombardia-trenord-gtfs:N1"),
            sourceTripId = "N1",
            sourceStopId = "CO",
            stopSequence = 1,
            observedAt = observedAt,
            source = source
        )

        val resolved = resolver.resolve(sourceId, update, LocalDate.of(2026, 10, 25), ZoneId.of("Europe/Rome"))

        assertNotNull(resolved)
        assertEquals(LocalDate.of(2026, 10, 26), resolved?.scheduledAt?.toLocalDate())
        assertEquals("00:10", resolved?.scheduledAt?.toLocalTime()?.toString()?.take(5))
    }

    private fun feed(): ByteArray {
        val content = mapOf(
            "stops.txt" to "stop_id,stop_name\nMI,Milano Centrale\nMO,Monza\nCO,Como S. Giovanni\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,08:05:00,08:05:00,MI,1\nT1,08:22:00,08:23:00,MO,2\nT1,08:50:00,08:50:00,CO,3\nN1,25:10:00,25:10:00,CO,1\n"
        )
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
