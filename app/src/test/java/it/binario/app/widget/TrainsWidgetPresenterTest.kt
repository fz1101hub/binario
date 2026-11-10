package it.binario.app.widget

import it.binario.app.ServiceMonitor
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainsWidgetPresenterTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val day = LocalDate.of(2026, 11, 2)
    private fun millis(hour: Int, minute: Int) = ZonedDateTime.of(day, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()
    private fun millisOn(date: LocalDate, hour: Int, minute: Int) = ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()

    private val strings = TrainsWidgetStrings(
        locale = Locale.ITALIAN,
        trainNumber = "Treno %1\$s",
        categoryNumber = "%1\$s %2\$s",
        nextStation = "Prossima: %1\$s %2\$s",
        lastDetection = "Rilevato a %1\$s %2\$s",
        noDetection = "Nessun rilevamento ancora",
        departureFrom = "Partenza %1\$s da %2\$s",
        waiting = "In attesa del primo aggiornamento",
        staleData = "Ultimo dato %1\$s",
        onTime = "OK",
        late = "+%1\$d",
        early = "−%1\$d",
        cancelled = "SOPP.",
        variation = "VAR.",
        scheduled = "PROGRAM.",
        platformShort = "BIN %1\$s",
        platform = "Binario %1\$s",
        platformUnavailable = "Binario non disponibile",
        delayAccessibility = { "In ritardo di $it minuti" },
        earlyAccessibility = { "In anticipo di $it minuti" },
        onTimeAccessibility = "In orario",
        cancelledAccessibility = "Treno soppresso",
        variationAccessibility = "Variazione",
        scheduledAccessibility = "Stato operativo non verificato",
        categoryLabels = TrainCategory.entries.associateWith { if (it == TrainCategory.REGIONALE) "Regionale" else it.name }
    )

    private val status = FavouriteTrainStatus(
        number = "2153",
        retrievedAt = millis(8, 0),
        category = TrainCategory.REGIONALE,
        origin = "Roma Termini",
        destination = "Orte",
        scheduledDeparture = "07:42",
        scheduledDepartureEpochMillis = millis(7, 42),
        arrivalEpochMillis = millis(8, 40),
        delayMinutes = 4,
        nextStation = "Monterotondo",
        nextStationTime = "08:06",
        lastDetectionStation = "Fara Sabina",
        lastDetectionTime = "08:01",
        platform = "3",
        departed = true,
        arrived = false,
        operationalVerified = true
    )

    @Test
    fun `a live row shows next station, delay and platform under the custom name`() {
        val row = TrainsWidgetPresenter.row(ServiceMonitor("2153", "", "Il mio treno delle 7:42"), status, TrainsWidgetSettings(), millis(8, 2), strings)

        assertEquals("Il mio treno delle 7:42", row.title)
        assertEquals("Regionale 2153 · Prossima: Monterotondo 08:06", row.detail)
        assertEquals("+4", row.status)
        assertEquals(TrainsRowEmphasis.WARNING, row.emphasis)
        assertEquals("BIN 3", row.platform)
        assertTrue(row.accessibility.contains("In ritardo di 4 minuti"))
        assertTrue(row.accessibility.contains("Binario 3"))
    }

    @Test
    fun `early trains are green and the last detection can replace the next station`() {
        val early = TrainsWidgetPresenter.row(ServiceMonitor("2153", ""), status.copy(delayMinutes = -2), TrainsWidgetSettings(info = TrainsWidgetInfo.LAST_DETECTION), millis(8, 2), strings)

        assertEquals("Regionale 2153", early.title)
        assertEquals("Rilevato a Fara Sabina 08:01", early.detail)
        assertEquals("−2", early.status)
        assertEquals(TrainsRowEmphasis.EARLY, early.emphasis)
    }

    @Test
    fun `cancellations and variations win over the delay`() {
        val settings = TrainsWidgetSettings()
        assertEquals("SOPP.", TrainsWidgetPresenter.row(ServiceMonitor("2153", ""), status.copy(cancelled = true), settings, millis(8, 2), strings).status)
        val variation = TrainsWidgetPresenter.row(ServiceMonitor("2153", ""), status.copy(variation = true), settings, millis(8, 2), strings)
        assertEquals("VAR.", variation.status)
        assertEquals(TrainsRowEmphasis.WARNING, variation.emphasis)
    }

    @Test
    fun `a failed lookup keeps the last values marked stale instead of an empty row`() {
        val row = TrainsWidgetPresenter.row(ServiceMonitor("2153", ""), status.copy(stale = true), TrainsWidgetSettings(), millis(8, 3), strings)

        assertEquals("+4", row.status)
        assertEquals(TrainsRowEmphasis.MUTED, row.emphasis)
        assertTrue(row.detail.startsWith("Ultimo dato 08:00"))
        // Old data (over fifteen minutes) is marked the same way.
        val old = TrainsWidgetPresenter.row(ServiceMonitor("2153", ""), status, TrainsWidgetSettings(), millis(8, 30), strings)
        assertTrue(old.detail.startsWith("Ultimo dato 08:00"))
        assertEquals(TrainsRowEmphasis.MUTED, old.emphasis)
        // A train never looked up yet still has its row.
        val waiting = TrainsWidgetPresenter.row(ServiceMonitor("9541", "", "Ritorno"), null, TrainsWidgetSettings(), millis(8, 0), strings)
        assertEquals("Ritorno", waiting.title)
        assertEquals("Treno 9541 · In attesa del primo aggiornamento", waiting.detail)
    }

    @Test
    fun `the run of an earlier day never shows its live values the next day`() {
        // The last refresh saw the run of 2 November cancelled; on the 3rd, before any refresh, the row is that day's timetable.
        val cancelled = status.copy(cancelled = true, delayMinutes = null)
        val nextDay = day.plusDays(1)
        val row = TrainsWidgetPresenter.row(ServiceMonitor("2153", ""), cancelled, TrainsWidgetSettings(), millisOn(nextDay, 7, 0), strings)

        assertEquals("PROGRAM.", row.status)
        assertEquals(TrainsRowEmphasis.NORMAL, row.emphasis)
        assertEquals("Partenza 07:42 da Roma Termini", row.detail)
        assertFalse(row.accessibility.contains("soppresso"))
        // The "only running trains" filter judges that day's run by its timetable, not by the old cancellation.
        assertFalse(TrainsWidgetPresenter.isRunning(cancelled, millisOn(nextDay, 7, 0)))
        assertTrue(TrainsWidgetPresenter.isRunning(cancelled, millisOn(nextDay, 8, 0)))
        assertFalse(TrainsWidgetPresenter.isRunning(cancelled, millisOn(nextDay, 9, 0)))
    }

    @Test
    fun `old live data of a run still going carries its date`() {
        // A night train seen at 23:00 and still running after midnight: the marker names the day of the data.
        val night = status.copy(scheduledDeparture = "22:40", scheduledDepartureEpochMillis = millis(22, 40),
            arrivalEpochMillis = millisOn(day.plusDays(1), 7, 30), retrievedAt = millis(23, 0))
        val row = TrainsWidgetPresenter.row(ServiceMonitor("2153", ""), night, TrainsWidgetSettings(), millisOn(day.plusDays(1), 1, 0), strings)

        assertTrue(row.detail, row.detail.startsWith("Ultimo dato 2 nov 23:00"))
        assertEquals("+4", row.status)
        assertEquals(TrainsRowEmphasis.MUTED, row.emphasis)
    }

    @Test
    fun `timetable only data shows the departure and no live claim`() {
        val planned = status.copy(operationalVerified = false, delayMinutes = null, nextStation = null, departed = null, arrived = null)

        val row = TrainsWidgetPresenter.row(ServiceMonitor("2153", ""), planned, TrainsWidgetSettings(), millis(7, 0), strings)

        assertEquals("PROGRAM.", row.status)
        assertEquals("Partenza 07:42 da Roma Termini", row.detail)
    }

    @Test
    fun `running means departed and not yet arrived, and unknown never hides`() {
        assertTrue(TrainsWidgetPresenter.isRunning(status, millis(8, 2)))
        assertFalse("not left yet", TrainsWidgetPresenter.isRunning(status.copy(departed = false), millis(7, 30)))
        assertTrue("past the timetable departure the source may lag", TrainsWidgetPresenter.isRunning(status.copy(departed = false), millis(7, 50)))
        assertTrue("an observed departure counts even before the timetable", TrainsWidgetPresenter.isRunning(status, millis(7, 40)))
        assertFalse("arrived", TrainsWidgetPresenter.isRunning(status.copy(arrived = true), millis(8, 30)))
        assertFalse("long past the arrival", TrainsWidgetPresenter.isRunning(status.copy(arrived = null), millis(8, 51)))
        assertFalse(TrainsWidgetPresenter.isRunning(status.copy(cancelled = true), millis(8, 2)))
        assertTrue(TrainsWidgetPresenter.isRunning(null, millis(8, 2)))
        assertTrue(TrainsWidgetPresenter.isRunning(FavouriteTrainStatus("1", 0L), millis(8, 2)))
    }

    @Test
    fun `the list hides trains not running and sorts by departure or name`() {
        val morning = ServiceMonitor("2153", "", "Andata", scheduledDeparture = "07:42")
        val evening = ServiceMonitor("9541", "", "Zeta ritorno", scheduledDeparture = "18:10")
        val legacy = ServiceMonitor("777", "")
        val statuses = mapOf(
            "2153" to status,
            "9541" to status.copy(number = "9541", departed = false, scheduledDepartureEpochMillis = millis(18, 10), arrivalEpochMillis = millis(20, 0)),
            "777" to status.copy(number = "777", scheduledDeparture = "06:00")
        )
        val label: (String) -> String = { "Treno $it" }

        assertEquals(listOf("777", "2153", "9541"), TrainsWidgetPresenter.visible(listOf(evening, legacy, morning), statuses, TrainsWidgetSettings(), millis(8, 2), Locale.ITALIAN, label).map { it.number })
        assertEquals(listOf("2153", "777", "9541"), TrainsWidgetPresenter.visible(listOf(evening, legacy, morning), statuses, TrainsWidgetSettings(sort = TrainsWidgetSort.NAME), millis(8, 2), Locale.ITALIAN, label).map { it.number })
        assertEquals(listOf("777", "2153"), TrainsWidgetPresenter.visible(listOf(evening, legacy, morning), statuses, TrainsWidgetSettings(hideNotRunning = true), millis(8, 2), Locale.ITALIAN, label).map { it.number })
    }

    @Test
    fun `status codec round trips and rejects damaged records`() {
        val full = status.copy(operator = "Trenitalia", providerName = "ViaggiaTreno", stale = true, variation = true)
        val sparse = FavouriteTrainStatus("9541", 5L)

        assertEquals(full, FavouriteTrainStatusCodec.decode(FavouriteTrainStatusCodec.encode(full)))
        assertEquals(sparse, FavouriteTrainStatusCodec.decode(FavouriteTrainStatusCodec.encode(sparse)))
        assertNull(FavouriteTrainStatusCodec.decode("broken,record"))
    }

    @Test
    fun `the mapper reads next stop, detection, delay and arrival from today's run`() {
        val service = TrainService(
            id = "2153", number = "2153", operator = RailOperator.TRENITALIA_REGIONALE, trainCategory = TrainCategory.REGIONALE,
            origin = "Roma Termini", destination = "Orte", scheduledDeparture = "07:42", expectedDeparture = "07:46",
            platform = "3", delayMinutes = 4, status = ServiceStatus.DELAYED,
            stops = listOf(
                TrainStop("Roma Termini", "07:42", "07:46", "3", 4, true),
                TrainStop("Fara Sabina", "07:58", "08:01", "1", 3, true),
                TrainStop("Monterotondo", "08:02", "08:06", "2", 4, false, routeChange = StopRouteChange.SKIPPED),
                TrainStop("Orte", "08:36", "08:40", null, 4, false)
            ),
            officialDelayMinutes = 4,
            scheduledDepartureDateTime = LocalDateTime.of(day, LocalTime.of(7, 42))
        )

        val mapped = FavouriteTrainStatusMapper.fromService(service, "ViaggiaTreno", millis(8, 3))

        assertEquals(4, mapped.delayMinutes)
        assertEquals("Monterotondo", mapped.nextStation)
        assertEquals("08:06", mapped.nextStationTime)
        assertEquals("2", mapped.platform)
        assertEquals("Fara Sabina", mapped.lastDetectionStation)
        assertEquals("08:01", mapped.lastDetectionTime)
        assertEquals(millis(7, 42), mapped.scheduledDepartureEpochMillis)
        assertEquals(millis(8, 40), mapped.arrivalEpochMillis)
        assertEquals(true, mapped.departed)
        assertEquals(false, mapped.arrived)
        assertTrue(mapped.variation)
        assertFalse(mapped.cancelled)
        assertEquals("ViaggiaTreno", mapped.providerName)
    }
}
