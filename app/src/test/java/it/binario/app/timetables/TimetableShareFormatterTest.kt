package it.binario.app.timetables

import it.binario.app.TestStringCatalogue
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TimetableShareFormatterTest {
    private val catalogue = TestStringCatalogue.of()
    private val strings = TimetableShareStrings(
        locale = Locale.ITALIAN,
        route = catalogue.getValue("share_route"),
        listHeader = catalogue.getValue("share_timetable_header"),
        nextDay = catalogue.getValue("journey_arrival_next_day"),
        platform = catalogue.getValue("platform_label"),
        platformChanged = catalogue.getValue("platform_changed_label"),
        source = catalogue.getValue("share_timetable_source"),
        scheduledOnly = catalogue.getValue("share_scheduled_data"),
        updated = catalogue.getValue("share_updated"),
        savedNotUpdated = catalogue.getValue("saved_timetable_saved_at"),
        returnTitle = catalogue.getValue("planner_return"),
        changes = { if (it <= 0) catalogue.getValue("journey_direct") else if (it == 1) "1 cambio" else "$it cambi" },
        stopLine = catalogue.getValue("share_stop_line"),
        stopArrival = catalogue.getValue("share_stop_arrival"),
        stopDeparture = catalogue.getValue("share_stop_departure"),
        stopExpected = catalogue.getValue("share_stop_expected"),
        stopPlatform = catalogue.getValue("share_stop_platform_short"),
        stopPlatformChanged = catalogue.getValue("share_stop_platform_changed")
    )
    private val fetched = Instant.parse("2026-11-02T16:32:00Z").toEpochMilli()
    private val direct = TimetableRow(
        departure = "17:40", arrival = "21:00", durationMinutes = 200, trains = "FRECCIAROSSA 9520", changes = 0,
        platform = "7", scheduledPlatform = "5", source = "ViaggiaTreno", fetchedAtEpochMillis = fetched, scheduledOnly = false
    )
    private val withChange = TimetableRow(
        departure = "23:10", arrival = "06:05", arrivalDayOffset = 1, durationMinutes = 415, trains = "REGIONALE 2153 › RE 24617",
        changes = 1, transfers = listOf("Cambio a Bologna Centrale · Attesa di 12 minuti"), source = "Orario programmato offline (open data GTFS)",
        fetchedAtEpochMillis = fetched - 60_000
    )

    @Test fun `one solution names stations, date, times, trains, changes, platform and source`() {
        val share = TimetableShareFormatter.solution(withChange, "Roma Termini", "Milano Centrale", "lun 2 nov", strings)
        assertEquals("Roma Termini → Milano Centrale", share.title)
        assertEquals(
            """
            Roma Termini → Milano Centrale
            lun 2 nov
            23:10 → 06:05 (il giorno dopo) · 6h 55min · 1 cambio
            REGIONALE 2153 › RE 24617
            Cambio a Bologna Centrale · Attesa di 12 minuti
            Fonte: Orario programmato offline (open data GTFS)
            Orario programmato; stato live non disponibile
            """.trimIndent(),
            share.text
        )
        val live = TimetableShareFormatter.solution(direct, "Roma Termini", "Milano Centrale", "lun 2 nov", strings).text
        assertEquals("17:40 → 21:00 · 3h 20min · Diretto", live.lines()[2])
        assertEquals("Binario 7 (era 5)", live.lines()[4])
    }

    @Test fun `the result list has a header, one line per solution, the return, sources and update time`() {
        val share = TimetableShareFormatter.list(
            "Roma Termini", "Milano Centrale", "lun 2 nov · dalle 17:00", listOf(direct, withChange), listOf(direct.copy(departure = "08:10", arrival = "11:30", platform = null)), strings
        )
        assertEquals("Orari Roma Termini → Milano Centrale · lun 2 nov · dalle 17:00", share.title)
        assertEquals(
            """
            Orari Roma Termini → Milano Centrale · lun 2 nov · dalle 17:00
            • 17:40 → 21:00 · 3h 20min · Diretto · FRECCIAROSSA 9520 · Binario 7 (era 5)
            • 23:10 → 06:05 (il giorno dopo) · 6h 55min · 1 cambio · REGIONALE 2153 › RE 24617
               Cambio a Bologna Centrale · Attesa di 12 minuti

            RITORNO
            • 08:10 → 11:30 · 3h 20min · Diretto · FRECCIAROSSA 9520

            Fonte: ViaggiaTreno, Orario programmato offline (open data GTFS)
            Orario programmato; stato live non disponibile
            Aggiornamento: aggiornato alle 17:32
            """.trimIndent(),
            share.text
        )
        val saved = TimetableShareFormatter.list("Roma Termini", "Milano Centrale", "lun 2 nov · dalle 17:00", listOf(direct), emptyList(), strings, savedAtLabel = "2 nov 2026, 17:35")
        assertEquals("Salvato il 2 nov 2026, 17:35 · non aggiornato", saved.text.lines().last())
    }

    @Test fun `a stop of a train reads as one line with the expected time and the platform change`() {
        assertEquals(
            "Treno 2153, fermata Milano Centrale: arr. 10:05 (previsto 10:12), bin. 3 (era 5)",
            TimetableShareFormatter.stop("2153", "Milano Centrale", "10:05", "10:12", departure = false, platform = "3", scheduledPlatform = "5", strings = strings).text
        )
        assertEquals(
            "Treno 2153, fermata Roma Termini: part. 08:00, bin. 3",
            TimetableShareFormatter.stop("2153", "Roma Termini", "08:00", "08:00", departure = true, platform = "3", scheduledPlatform = "3", strings = strings).text
        )
        assertEquals(
            "Treno 2153, fermata Orte: arr. 09:01",
            TimetableShareFormatter.stop("2153", "Orte", "09:01", null, departure = false, platform = null, scheduledPlatform = null, strings = strings).text
        )
    }
}
