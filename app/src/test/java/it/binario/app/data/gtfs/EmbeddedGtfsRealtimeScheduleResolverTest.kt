package it.binario.app.data.gtfs

import it.binario.app.data.core.Confidence
import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.TrainRunId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `interpreta il gap DST dalla mezzanotte GTFS noon minus twelve hours`() {
        val resolved = resolveDstStop(LocalDate.of(2026, 3, 29))

        assertNotNull(resolved)
        assertEquals(Instant.parse("2026-03-29T00:30:00Z"), resolved?.scheduledAt?.toInstant())
        assertEquals("01:30", resolved?.scheduledAt?.toLocalTime()?.toString()?.take(5))
    }

    @Test
    fun `interpreta l'overlap DST dalla mezzanotte GTFS noon minus twelve hours`() {
        val resolved = resolveDstStop(LocalDate.of(2026, 10, 25))

        assertNotNull(resolved)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), resolved?.scheduledAt?.toInstant())
        assertEquals("+01:00", resolved?.scheduledAt?.offset?.toString())
    }

    @Test
    fun `restituisce null per orari GTFS vuoti o malformati`() {
        val resolver = EmbeddedGtfsRealtimeScheduleResolver(
            openAsset = { ByteArrayInputStream(feed()) },
            packs = listOf(pack)
        )

        listOf("EMPTY", "TEXT", "HOURS", "MINUTES", "SECONDS", "COMPONENTS", "NEGATIVE").forEach { tripId ->
            assertNull(
                tripId,
                resolver.resolve(sourceId, update(tripId), LocalDate.of(2026, 8, 15), ZoneId.of("Europe/Rome"))
            )
        }
    }

    /** Audit F4: one index per pack and service day, with only the trips that run that day. */
    @Test
    fun `indexes the stop times of the trips running that day once`() {
        var opened = 0
        val content = mapOf(
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nWEEKDAY,1,1,1,1,1,0,0,20260101,20261231\n",
            "calendar_dates.txt" to "service_id,date,exception_type\nWEEKDAY,20260817,2\nHOLIDAY,20260815,1\n",
            "trips.txt" to "route_id,service_id,trip_id\nR,WEEKDAY,T1\nR,HOLIDAY,H1\n",
            "stops.txt" to "stop_id,stop_name\nMI,Milano Centrale\nMO,Monza\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,08:05:00,08:05:00,MI,1\nT1,08:22:00,08:23:00,MO,2\nH1,09:00:00,09:00:00,MI,1\n"
        )
        val resolver = EmbeddedGtfsRealtimeScheduleResolver(openAsset = { opened++; ByteArrayInputStream(zip(content)) }, packs = listOf(pack))
        val zone = ZoneId.of("Europe/Rome")
        val friday = LocalDate.of(2026, 8, 14)
        val t1 = update("T1").copy(sourceStopId = "MO", stopSequence = 2)
        assertEquals("08:23", resolver.resolve(sourceId, t1, friday, zone)?.scheduledAt?.toLocalTime()?.toString())
        val afterFirst = opened
        assertNotNull(resolver.resolve(sourceId, update("T1"), friday, zone))
        assertEquals("a second trip of the same day reads nothing", afterFirst, opened)
        // Saturday 15 August: T1 does not run (weekdays only), H1 does (calendar_dates addition).
        val saturday = LocalDate.of(2026, 8, 15)
        assertNull(resolver.resolve(sourceId, t1, saturday, zone))
        assertNotNull(resolver.resolve(sourceId, update("H1"), saturday, zone))
        // Monday 17 August is removed for T1 by calendar_dates.
        assertNull(resolver.resolve(sourceId, t1, LocalDate.of(2026, 8, 17), zone))
    }

    private fun zip(content: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            content.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }
        return output.toByteArray()
    }

    private fun resolveDstStop(serviceDate: LocalDate): ResolvedScheduledStop? {
        val resolver = EmbeddedGtfsRealtimeScheduleResolver(
            openAsset = { ByteArrayInputStream(feed()) },
            packs = listOf(pack)
        )
        return resolver.resolve(sourceId, update("D1"), serviceDate, ZoneId.of("Europe/Rome"))
    }

    private fun update(tripId: String) = RealtimeStopUpdate(
            runId = TrainRunId("lombardia-trenord-gtfs:D1"),
            sourceTripId = tripId,
            sourceStopId = "MI",
            stopSequence = 1,
            observedAt = observedAt,
            source = source
        )

    private fun feed(): ByteArray {
        val content = mapOf(
            "stops.txt" to "stop_id,stop_name\nMI,Milano Centrale\nMO,Monza\nCO,Como S. Giovanni\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,08:05:00,08:05:00,MI,1\nT1,08:22:00,08:23:00,MO,2\nT1,08:50:00,08:50:00,CO,3\nN1,25:10:00,25:10:00,CO,1\nD1,02:30:00,02:30:00,MI,1\nEMPTY,,,MI,1\nTEXT,nope,nope,MI,1\nHOURS,100:00:00,100:00:00,MI,1\nMINUTES,08:60:00,08:60:00,MI,1\nSECONDS,08:00:60,08:00:60,MI,1\nCOMPONENTS,08:00:00:00,08:00:00:00,MI,1\nNEGATIVE,-1:00:00,-1:00:00,MI,1\n"
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
