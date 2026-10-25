package it.binario.app.widget

import it.binario.app.data.Freshness
import it.binario.app.data.Station
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.core.BoardKind
import it.binario.app.data.repository.StationBoardAggregate
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class StationWidgetPresenterTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val day = LocalDate.of(2026, 10, 12)
    private val now = millis(10, 0)
    private val delayed = StationWidgetDeparture(millis(10, 5), millis(10, 12), "2345", "trenitalia", "Napoli Centrale", "3", RunStatus.DELAYED)
    private val planned = StationWidgetDeparture(millis(10, 20), null, "4120", null, "Latina", null, RunStatus.SCHEDULED)

    @Test
    fun `a fresh observation shows the scheduled time with the live delay`() {
        val row = StationWidgetPresenter.row(delayed, Freshness.FRESH, italian())

        assertEquals("10:05", row.time)
        assertEquals("+7", row.status)
        assertEquals(StationWidgetEmphasis.WARNING, row.emphasis)
        assertEquals("TRENITALIA · 2345", row.train)
        assertEquals("BIN 3", row.platform)
        assertEquals(
            "Treno 2345. trenitalia. Per Napoli Centrale. Orario programmato 10:05. Aggiornamento live: orario previsto 10:12, +7 min. Binario 3",
            row.accessibility
        )
    }

    @Test
    fun `a stale or failed snapshot drops every live claim`() {
        listOf(Freshness.STALE, Freshness.UNAVAILABLE).forEach { freshness ->
            val row = StationWidgetPresenter.row(delayed, freshness, italian())

            assertFalse(StationWidgetPresenter.isLive(delayed, freshness))
            assertEquals("PROGRAM.", row.status)
            assertEquals(StationWidgetEmphasis.NORMAL, row.emphasis)
            assertTrue(row.accessibility.contains("Orario programmato; stato live non disponibile"))
            assertFalse(row.accessibility.contains("10:12"))
        }
        val failed = snapshot(listOf(delayed)).copy(retrievedAt = now, unavailable = true)
        assertEquals(Freshness.UNAVAILABLE, failed.freshnessAt(now))
        assertEquals(Freshness.STALE, snapshot(listOf(delayed)).copy(retrievedAt = now - 20 * 60_000L).freshnessAt(now))
    }

    @Test
    fun `timetable rows stay marked as scheduled even in a fresh snapshot`() {
        val row = StationWidgetPresenter.row(planned, Freshness.FRESH, italian())

        assertEquals("PROGRAM.", row.status)
        assertEquals("—", row.platform)
        assertTrue(row.accessibility.endsWith("Orario programmato; stato live non disponibile. Binario non disponibile"))
    }

    @Test
    fun `cancellations and route changes are announced, a delay without minutes as late`() {
        val strings = italian()

        val cancelled = StationWidgetPresenter.row(delayed.copy(status = RunStatus.CANCELLED), Freshness.AGING, strings)
        val diverted = StationWidgetPresenter.row(delayed.copy(status = RunStatus.DIVERTED), Freshness.AGING, strings)
        val late = StationWidgetPresenter.row(delayed.copy(expectedEpochMillis = null), Freshness.FRESH, strings)
        val onTime = StationWidgetPresenter.row(delayed.copy(expectedEpochMillis = delayed.scheduledEpochMillis, status = RunStatus.ON_TIME), Freshness.FRESH, strings)

        assertEquals("SOPP." to StationWidgetEmphasis.ERROR, cancelled.status to cancelled.emphasis)
        assertTrue(cancelled.accessibility.contains("Stato live: Soppresso"))
        assertEquals("VAR." to StationWidgetEmphasis.WARNING, diverted.status to diverted.emphasis)
        assertEquals("RIT." to StationWidgetEmphasis.WARNING, late.status to late.emphasis)
        assertEquals("OK" to StationWidgetEmphasis.NORMAL, onTime.status to onTime.emphasis)
    }

    @Test
    fun `upcoming rows skip departed trains, follow the live time and stop at four`() {
        val departed = planned.copy(scheduledEpochMillis = millis(9, 50), number = "gone")
        val leavingLate = delayed.copy(scheduledEpochMillis = millis(9, 55), expectedEpochMillis = millis(10, 3), number = "late")
        val later = (1..5).map { planned.copy(scheduledEpochMillis = millis(10, 30 + it), number = "p$it") }
        val departures = listOf(departed, leavingLate) + later

        assertEquals(listOf("late", "p1", "p2", "p3"), StationWidgetPresenter.upcoming(departures, Freshness.FRESH, now).map { it.number })
        // Once the observation is stale its delay no longer keeps the train on the board.
        assertEquals(listOf("p1", "p2", "p3", "p4"), StationWidgetPresenter.upcoming(departures, Freshness.STALE, now).map { it.number })
        assertEquals(2, StationWidgetPresenter.upcoming(departures, Freshness.FRESH, now, limit = 2).size)
    }

    @Test
    fun `the meta line names the source and marks a board without live data as scheduled`() {
        val strings = italian()

        assertEquals("RFI · AGGIORNATO · AGG. 10:00", StationWidgetPresenter.meta(snapshot(listOf(delayed, planned)), Freshness.FRESH, "AGG. 10:00", strings))
        assertEquals("RFI · PROGRAMMATO · AGGIORNATO · AGG. 10:00", StationWidgetPresenter.meta(snapshot(listOf(planned)), Freshness.FRESH, "AGG. 10:00", strings))
        assertEquals("RFI · PROGRAMMATO · STALE · AGG. 10:00", StationWidgetPresenter.meta(snapshot(listOf(delayed)), Freshness.STALE, "AGG. 10:00", strings))
    }

    @Test
    fun `a loaded board keeps the next twelve departures without expired observations`() {
        val fetched = Instant.ofEpochMilli(now)
        val live = SourceRef(SourceId("rfi-station-board"), fetchedAt = fetched, expiresAt = fetched.plusSeconds(60), confidence = Confidence.INFRASTRUCTURE_PRIMARY, traceId = "live")
        val expired = live.copy(expiresAt = fetched.minusSeconds(1), traceId = "expired")
        val entries = listOf(
            entry("gone", 9, 30),
            entry("live", 10, 2, expected = SourcedValue(at(10, 9), live), platform = SourcedValue("4", live)),
            entry("old", 10, 4, expected = SourcedValue(at(10, 20), expired))
        ) + (0 until 15).map { entry("n$it", 10, 10 + it) }
        val board = StationBoardAggregate(Station("roma", "Roma Termini", "Roma"), BoardKind.DEPARTURES, entries, listOf("RFI"), emptySet(), fetched)

        val snapshot = StationWidgetSnapshot.fromBoard(board, fetched)

        assertEquals(StationWidgetSnapshot.MAX_STORED, snapshot.departures.size)
        // The expired prediction falls back to 10:04, so that run now comes first.
        assertEquals(listOf("old", "live"), snapshot.departures.take(2).map { it.number })
        assertEquals(null, snapshot.departures[0].expectedEpochMillis)
        assertEquals(millis(10, 9), snapshot.departures[1].expectedEpochMillis)
        assertEquals("4", snapshot.departures[1].platform)
        assertEquals("roma" to "Roma Termini", snapshot.stationId to snapshot.stationName)
        assertEquals(now, snapshot.retrievedAt)
    }

    @Test
    fun `the shipped Italian and English strings render the row and title`() {
        val italian = resourceStrings(null, italianWidgetStrings())
        val english = resourceStrings("en", englishWidgetStrings())
        val board = snapshot(listOf(delayed))

        assertEquals(
            "Treno 2345. trenitalia. Per Napoli Centrale. Orario programmato 10:05. Aggiornamento live: orario previsto 10:12, +7 min. Binario 3",
            StationWidgetPresenter.row(delayed, Freshness.FRESH, italian).accessibility
        )
        assertEquals(
            "Departures from Roma Termini. Source: RFI. Data updating. Updated at 10:00",
            StationWidgetPresenter.titleAccessibility(board, Freshness.AGING, "Updated at 10:00", english)
        )
        assertEquals("CHANGE", StationWidgetPresenter.row(delayed.copy(status = RunStatus.REPLACED_BY_BUS), Freshness.FRESH, english).status)
    }

    private fun snapshot(departures: List<StationWidgetDeparture>) =
        StationWidgetSnapshot("roma", "Roma Termini", departures, listOf("RFI"), retrievedAt = now)

    private fun italian() = StationWidgetStrings(
        widget = italianWidgetStrings(),
        statusLate = "RIT.",
        statusChanged = "VAR.",
        platformShort = "BIN %1\$s",
        trainNumber = "Treno %1\$s",
        departureTo = "Per %1\$s",
        scheduledTime = "Orario programmato %1\$s",
        livePrediction = "Aggiornamento live: orario previsto %1\$s",
        liveStatus = "Stato live: %1\$s",
        plannedOnly = "Orario programmato; stato live non disponibile",
        runStatusLabels = RunStatus.entries.associateWith { if (it == RunStatus.CANCELLED) "Soppresso" else it.name },
        titleAccessibility = "Partenze da %1\$s. Fonte: %2\$s. %3\$s. %4\$s"
    )

    /** Builds the station-specific texts from the shipped catalogue, on top of the shared widget fixture. */
    private fun resourceStrings(locale: String?, widget: WidgetStrings): StationWidgetStrings {
        val catalogue = catalogue(locale)
        return StationWidgetStrings(
            widget = widget,
            statusLate = catalogue.getValue("station_widget_status_late"),
            statusChanged = catalogue.getValue("station_widget_status_changed"),
            platformShort = catalogue.getValue("widget_platform_short"),
            trainNumber = catalogue.getValue("station_board_train_number"),
            departureTo = catalogue.getValue("station_board_departure_to"),
            scheduledTime = catalogue.getValue("station_board_scheduled_time"),
            livePrediction = catalogue.getValue("station_board_status_live_prediction"),
            liveStatus = catalogue.getValue("station_board_status_live"),
            plannedOnly = catalogue.getValue("station_board_status_planned"),
            runStatusLabels = RunStatus.entries.associateWith { catalogue.getValue(runStatusKeys.getValue(it)) },
            titleAccessibility = catalogue.getValue("station_widget_title_accessibility")
        )
    }

    private val runStatusKeys = mapOf(
        RunStatus.SCHEDULED to "run_status_scheduled",
        RunStatus.ON_TIME to "run_status_on_time",
        RunStatus.DELAYED to "run_status_delayed",
        RunStatus.CANCELLED to "run_status_cancelled",
        RunStatus.PARTIALLY_CANCELLED to "run_status_partially_cancelled",
        RunStatus.DIVERTED to "run_status_diverted",
        RunStatus.ADDED to "run_status_added",
        RunStatus.REPLACED_BY_BUS to "run_status_bus_replacement",
        RunStatus.COMPLETED to "run_status_completed",
        RunStatus.UNKNOWN to "run_status_unknown"
    )

    private fun catalogue(locale: String?): Map<String, String> {
        val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val file = File(repositoryRoot, "app/src/main/res/values${locale?.let { "-$it" }.orEmpty()}/strings.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                if (element.tagName == "string") put(element.getAttribute("name"), element.textContent.replace("\\'", "'"))
            }
        }
    }

    private fun millis(hour: Int, minute: Int): Long = at(hour, minute).toInstant().toEpochMilli()

    private fun at(hour: Int, minute: Int): ZonedDateTime = ZonedDateTime.of(day.atTime(hour, minute), zone)

    private fun entry(
        id: String,
        hour: Int,
        minute: Int,
        expected: SourcedValue<ZonedDateTime>? = null,
        platform: SourcedValue<String>? = null
    ) = StationBoardEntry(
        runId = TrainRunId("run-$id"),
        scheduledTime = at(hour, minute),
        expectedTime = expected,
        destinationOrOrigin = StationId("napoli"),
        destinationOrOriginLabel = "Napoli Centrale",
        publicNumber = id,
        platform = platform
    )
}
