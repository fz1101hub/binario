package it.binario.wear.complication

import it.binario.wear.ResourceNameStrings
import it.binario.wear.model.WearConfidence
import it.binario.wear.model.WearDataNature
import it.binario.wear.model.WearDirection
import it.binario.wear.model.WearFreshness
import it.binario.wear.model.WearOperationalState
import it.binario.wear.model.WearProvenance
import it.binario.wear.model.WearProvenanceProducer
import it.binario.wear.model.WearService
import it.binario.wear.model.WearSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class BinarioComplicationDataTest {
    /** 2030-01-01 07:00 UTC, 08:00 in Rome: the snapshot is generated a minute earlier. */
    private val now = 1_893_481_200_000L

    @Test
    fun `short text counts down to the departure and keeps the clock time as title`() {
        val texts = shortTextComplicationTexts(snapshot(services = listOf(service)), ResourceNameStrings, now)

        assertEquals("^1", texts.text)
        assertEquals("08:15", texts.title)
        assertEquals(Instant.parse("2030-01-01T07:15:00Z"), texts.countdownTo)
        assertEquals(
            "accessibility_scheduled_departure(08:05). accessibility_expected_departure(08:15). accessibility_train(RV 2345). " +
                "Firenze S. M. Novella. Trenitalia. Regionale Veloce. accessibility_platform(4). In orario",
            texts.contentDescription,
        )
    }

    @Test
    fun `short text without a parsable time splits departure and train number`() {
        val texts = shortTextComplicationTexts(snapshot(services = listOf(service.copy(expectedDeparture = "—"))), ResourceNameStrings, now)

        assertEquals("—", texts.text)
        assertEquals("RV 2345", texts.title)
        assertNull(texts.countdownTo)
    }

    @Test
    fun `short text without a service shows the app name and reads the data status`() {
        val texts = shortTextComplicationTexts(snapshot(services = emptyList()), ResourceNameStrings, now)

        assertEquals("app_name", texts.text)
        assertNull(texts.title)
        assertEquals("status_received_from_phone", texts.contentDescription)
    }

    @Test
    fun `long text keeps the combined label, appends the countdown and has the route as title`() {
        val withService = longTextComplicationTexts(snapshot(services = listOf(service)), ResourceNameStrings, now)
        val withoutService = longTextComplicationTexts(snapshot(services = emptyList()), ResourceNameStrings, now)

        assertEquals("service_time_number(08:15, RV 2345) · ^1", withService.text)
        assertEquals(Instant.parse("2030-01-01T07:15:00Z"), withService.countdownTo)
        assertEquals("Roma Termini → Firenze S. M. Novella", withService.title)
        assertEquals("status_received_from_phone", withoutService.text)
        assertNull(withoutService.countdownTo)
        assertEquals("Roma Termini → Firenze S. M. Novella", withoutService.title)
    }

    @Test
    fun `a departure is the first occurrence of its clock time from two hours before the snapshot generation`() {
        val lateEvening = Instant.parse("2030-01-01T22:50:00Z").toEpochMilli() // 23:50 in Rome
        val afterMidnight = Instant.parse("2030-01-01T23:05:00Z").toEpochMilli() // 00:05 in Rome

        assertEquals(Instant.parse("2030-01-01T23:10:00Z"), departureInstant("00:10", lateEvening))
        assertEquals(Instant.parse("2030-01-01T22:58:00Z"), departureInstant("23:58", afterMidnight))
        assertEquals(Instant.parse("2030-07-01T06:15:00Z"), departureInstant("08:15", Instant.parse("2030-07-01T06:00:00Z").toEpochMilli()))
        assertNull(departureInstant("", lateEvening))
        assertNull(departureInstant("25:00", lateEvening))
    }

    @Test
    fun `next-morning trains listed after the last train of the day are in the future`() {
        // 10 Nov 2026 17:30 in Rome: the phone lists the next morning's first departures after the day's last train.
        val evening = Instant.parse("2026-11-10T16:30:00Z").toEpochMilli()

        assertEquals(Instant.parse("2026-11-11T04:50:00Z"), departureInstant("05:50", evening))
        assertEquals(Instant.parse("2026-11-11T05:20:00Z"), departureInstant("06:20", evening))
        // Exactly twelve hours ahead is the next occurrence, not the one twelve hours ago.
        assertEquals(Instant.parse("2026-11-11T04:30:00Z"), departureInstant("05:30", evening))
        // A train that left within the last two hours stays in the past, so the glance skips it.
        assertEquals(Instant.parse("2026-11-10T15:45:00Z"), departureInstant("16:45", evening))

        val nextMorning = snapshot(services = listOf(service.copy(scheduledDeparture = "05:50", expectedDeparture = "05:50")))
            .copy(generatedAtEpochMillis = evening)
        val texts = shortTextComplicationTexts(nextMorning, ResourceNameStrings, evening + 60_000L)
        assertEquals(Instant.parse("2026-11-11T04:50:00Z"), texts.countdownTo)
    }

    @Test
    fun `the glance moves to the next service a minute after the first one leaves`() {
        val next = service.copy(number = "RV 2347", scheduledDeparture = "08:35", expectedDeparture = "08:35")
        val snapshot = snapshot(services = listOf(service, next))
        val departure = Instant.parse("2030-01-01T07:15:00Z").toEpochMilli()

        assertEquals("RV 2345", upcomingService(snapshot, departure + 59_000L)?.first?.number)
        assertEquals("RV 2347", upcomingService(snapshot, departure + 60_000L)?.first?.number)
        assertEquals("RV 2347", upcomingService(snapshot, departure + 3_600_000L)?.first?.number)
        assertNull(upcomingService(snapshot(services = emptyList()), now))
    }

    // binarioComplicationData() only hands these texts to the library builders (ShortText/LongText ComplicationData,
    // TimeDifferenceComplicationText, TimeRange), which need the Android runtime and cannot be built in a JVM unit
    // test; their wiring (validity, minute countdown, tap action) is checked on a watch (docs/AZIONI_TITOLARE.md §3).

    private val service = WearService(
        number = "RV 2345",
        operator = "Trenitalia",
        category = "Regionale Veloce",
        destination = "Firenze S. M. Novella",
        scheduledDeparture = "08:05",
        expectedDeparture = "08:15",
        status = "In orario",
        platform = "4",
        delayMinutes = 10,
    )

    private fun snapshot(expiresAt: Long = now + 900_000L, services: List<WearService>): WearSnapshot = WearSnapshot(
        routeId = "route-id",
        routeLabel = "Roma Termini → Firenze S. M. Novella",
        direction = WearDirection.OUTBOUND,
        provenance = WearProvenance(
            producer = WearProvenanceProducer.ANDROID_APP,
            dataNature = WearDataNature.OPERATIONAL,
            sourceLabel = "Fonte",
            observedAtEpochMillis = now - 60_000L,
        ),
        operationalState = WearOperationalState.OPERATIONAL,
        freshness = WearFreshness.FRESH,
        confidence = WearConfidence.OPERATOR_PRIMARY,
        generatedAtEpochMillis = now - 60_000L,
        expiresAtEpochMillis = expiresAt,
        ttlMillis = expiresAt - (now - 60_000L),
        notice = "",
        services = services,
    )
}
