package it.binario.app

import android.provider.CalendarContract
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TrainCalendarEventTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val day = LocalDate.of(2026, 10, 12)
    private val calendar = TrainCalendarStrings(
        operator = "Operatore: %1\$s",
        timesMayChange = "Orari programmati: possono cambiare. Controlla lo stato del treno prima di partire."
    )

    @Test
    fun `the event carries the scheduled departure and arrival, origin and operator`() {
        val event = TrainCalendarEventFactory.create(service(platform = "6"), shareStrings(), calendar, fallbackDate = day.minusDays(3), zone = zone)!!

        assertEquals("Regionale 4132 → Firenze S. M. Novella", event.title)
        assertEquals(millis(day, 9, 0), event.beginEpochMillis)
        assertEquals(millis(day, 10, 35), event.endEpochMillis)
        assertEquals("Roma Termini", event.location)
        assertEquals(
            "Operatore: Trenitalia Regionale\nBinario: 6\nOrari programmati: possono cambiare. Controlla lo stato del treno prima di partire.",
            event.description
        )
    }

    @Test
    fun `a delay never moves the event and an unknown platform is left out`() {
        val delayed = service(platform = null).copy(expectedDeparture = "09:25", delayMinutes = 25, status = ServiceStatus.DELAYED)

        val event = TrainCalendarEventFactory.create(delayed, shareStrings(), calendar, day, zone)!!

        assertEquals(millis(day, 9, 0), event.beginEpochMillis)
        assertFalse(event.description.contains("Binario"))
    }

    @Test
    fun `an arrival after midnight ends on the next day and a missing arrival leaves the end out`() {
        val night = service(departure = "23:40", arrival = "00:55")
        val noStops = service().copy(stops = emptyList())

        assertEquals(millis(day.plusDays(1), 0, 55), TrainCalendarEventFactory.create(night, shareStrings(), calendar, day, zone)!!.endEpochMillis)
        assertNull(TrainCalendarEventFactory.create(noStops, shareStrings(), calendar, day, zone)!!.endEpochMillis)
    }

    @Test
    fun `without a service date the fallback date is used and an unreadable time gives no event`() {
        val undated = service().copy(scheduledDepartureDateTime = null)

        assertEquals(millis(day, 9, 0), TrainCalendarEventFactory.create(undated, shareStrings(), calendar, day, zone)!!.beginEpochMillis)
        assertNull(TrainCalendarEventFactory.create(undated.copy(scheduledDeparture = "--"), shareStrings(), calendar, day, zone))
    }

    @Test
    fun `intent extras use the CalendarContract keys`() {
        val event = TrainCalendarEvent("Regionale 4132 → Firenze", 1_000L, 2_000L, "Roma Termini", "Operatore: Trenitalia")

        assertEquals(
            mapOf(
                CalendarContract.Events.TITLE to "Regionale 4132 → Firenze",
                CalendarContract.EXTRA_EVENT_BEGIN_TIME to 1_000L,
                CalendarContract.EXTRA_EVENT_END_TIME to 2_000L,
                CalendarContract.Events.EVENT_LOCATION to "Roma Termini",
                CalendarContract.Events.DESCRIPTION to "Operatore: Trenitalia"
            ),
            TrainCalendarEventFactory.intentExtras(event)
        )
        assertEquals("beginTime", CalendarContract.EXTRA_EVENT_BEGIN_TIME)
        assertFalse(TrainCalendarEventFactory.intentExtras(event.copy(endEpochMillis = null)).containsKey(CalendarContract.EXTRA_EVENT_END_TIME))
    }

    private fun millis(date: LocalDate, hour: Int, minute: Int): Long = ZonedDateTime.of(date.atTime(hour, minute), zone).toInstant().toEpochMilli()

    private fun service(platform: String? = null, departure: String = "09:00", arrival: String = "10:35"): TrainService {
        val time = java.time.LocalTime.parse(departure)
        return TrainService(
            id = "test", number = "4132", operator = RailOperator.TRENITALIA_REGIONALE,
            trainCategory = TrainCategory.REGIONALE, origin = "Roma Termini", destination = "Firenze S. M. Novella",
            scheduledDeparture = departure, expectedDeparture = departure, platform = platform, delayMinutes = 0,
            status = ServiceStatus.ON_TIME,
            stops = listOf(
                TrainStop("Roma Termini", departure, departure, platform, 0, false),
                TrainStop("Orte", "09:50", "09:50", null, 0, false),
                TrainStop("Firenze S. M. Novella", arrival, arrival, null, 0, false)
            ),
            operatorDisplayName = "Trenitalia Regionale",
            operationalStatusVerified = false,
            scheduledDepartureDateTime = LocalDateTime.of(day, time)
        )
    }

    private fun shareStrings() = TrainShareStrings(
        locale = Locale.ITALIAN,
        title = "Treno %1\$s · %2\$s → %3\$s",
        detailTitle = "Binario — dettaglio treno",
        operatorCategory = "%1\$s · %2\$s %3\$s",
        route = "%1\$s → %2\$s",
        expectedDeparture = "Partenza prevista: %1\$s",
        scheduledDeparture = "programmata %1\$s",
        platform = "Binario: %1\$s",
        platformToConfirm = "Binario da confermare",
        status = "Stato: %1\$s",
        stopsTitle = "Fermate",
        stop = "• %1\$s — previsto %2\$s%3\$s%4\$s",
        stopPlatform = ", binario %1\$s",
        stopDelay = ", +%1\$d min",
        data = "Dati: %1\$s",
        source = "Fonte: %1\$s · %2\$s",
        updated = "Aggiornamento: %1\$s",
        neverUpdated = "Aggiornamento: mai aggiornato",
        demonstrationData = "Catalogo dimostrativo locale, non live",
        scheduledData = "Orario programmato; stato live non disponibile",
        unavailableData = "Dati live non disponibili",
        liveData = "Dati live: %1\$s",
        statusLabels = ServiceStatus.entries.associateWith { it.name },
        categoryLabels = TrainCategory.entries.associateWith { if (it == TrainCategory.REGIONALE) "Regionale" else it.name },
        freshnessLabels = Freshness.entries.associateWith { it.name }
    )
}
