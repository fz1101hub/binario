package it.binario.app.ui.departures

import it.binario.app.AppLocales
import it.binario.app.R
import it.binario.app.TestStringCatalogue
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.OperationalRouteChange
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sentence TalkBack reads for a row of the station board, with the strings of the real catalogues. */
class StationBoardEntrySpeechTest {
    private val keys = mapOf(
        R.string.station_board_entry_accessibility to "station_board_entry_accessibility",
        R.string.accessibility_title_detail to "accessibility_title_detail",
        R.string.station_board_departure_to to "station_board_departure_to",
        R.string.station_board_arrival_from to "station_board_arrival_from",
        R.string.station_board_scheduled_time to "station_board_scheduled_time",
        R.string.station_board_status_live_prediction to "station_board_status_live_prediction",
        R.string.station_board_status_live to "station_board_status_live",
        R.string.station_board_status_unavailable to "station_board_status_unavailable",
        R.string.station_board_status_planned to "station_board_status_planned",
        R.string.platform_changed_accessible to "platform_changed_accessible",
        R.string.station_board_platform_accessible to "station_board_platform_accessible",
        R.string.station_board_platform_unavailable to "station_board_platform_unavailable",
        R.string.station_board_train_number to "station_board_train_number",
        R.string.station_board_route_change to "station_board_route_change",
        R.string.journey_last_of_day to "journey_last_of_day",
        R.string.run_status_scheduled to "run_status_scheduled",
        R.string.run_status_on_time to "run_status_on_time",
        R.string.run_status_delayed to "run_status_delayed",
        R.string.run_status_cancelled to "run_status_cancelled",
        R.string.run_status_partially_cancelled to "run_status_partially_cancelled",
        R.string.run_status_diverted to "run_status_diverted",
        R.string.run_status_added to "run_status_added",
        R.string.run_status_bus_replacement to "run_status_bus_replacement",
        R.string.run_status_completed to "run_status_completed",
        R.string.run_status_unknown to "run_status_unknown"
    )

    private fun catalogue(locale: String? = null) = StationBoardEntrySpeech.Catalogue { id, arguments ->
        TestStringCatalogue.of(locale).getValue(keys.getValue(id)).format(Locale.ROOT, *arguments)
    }

    private val rome = ZoneId.of("Europe/Rome")
    private val scheduled = ZonedDateTime.of(2026, 11, 11, 10, 40, 0, 0, rome)
    private val source = SourceRef(SourceId("viaggiatreno"), fetchedAt = Instant.parse("2026-11-11T09:30:00Z"), confidence = Confidence.OPERATOR_PRIMARY, traceId = "test")

    private fun entry(
        status: RunStatus?,
        expected: ZonedDateTime? = null,
        platform: String? = "5",
        scheduledPlatform: String? = null,
        number: String? = "2153",
        routeChange: OperationalRouteChange? = null
    ) = StationBoardEntry(
        runId = TrainRunId("viaggiatreno:2153:2026-11-11"),
        scheduledTime = scheduled,
        expectedTime = expected?.let { SourcedValue(it, source) },
        destinationOrOrigin = StationId("published:pavia"),
        destinationOrOriginLabel = "Pavia",
        publicNumber = number,
        platform = platform?.let { SourcedValue(it, source) },
        scheduledPlatform = scheduledPlatform?.let { SourcedValue(it, source) },
        status = status?.let { SourcedValue(it, source) },
        routeChange = routeChange?.let { SourcedValue(it, source) }
    )

    private fun spoken(entry: StationBoardEntry, kind: BoardKind = BoardKind.DEPARTURES, lastOfDay: Boolean = false, locale: String? = null) =
        StationBoardEntrySpeech.description(entry, kind, lastOfDay, catalogue(locale))

    @Test
    fun `a cancelled train is said cancelled first and never with an expected time`() {
        // ViaggiaTreno fills the expected time of a cancelled train with its timetable.
        val cancelled = entry(RunStatus.CANCELLED, expected = scheduled)

        assertEquals("Soppresso. Per Pavia. Orario programmato 10:40. Binario 5. Treno 2153.", spoken(cancelled))
        assertNull(StationBoardEntrySpeech.expectedTime(cancelled))
        assertEquals(RunStatus.CANCELLED, StationBoardEntrySpeech.disruption(cancelled))
        // Every language: the cancellation opens the sentence and the expected time of the source is not read.
        AppLocales.tags.map(AppLocales::qualifier).forEach { locale ->
            val sentence = spoken(entry(RunStatus.CANCELLED, expected = scheduled.plusMinutes(12)), locale = locale)
            val cancelledLabel = TestStringCatalogue.of(locale).getValue("run_status_cancelled")
            assertTrue("$locale: $sentence", sentence.startsWith(cancelledLabel))
            assertFalse("$locale: $sentence", sentence.contains("10:52"))
        }
    }

    @Test
    fun `a train cut short diverted or replaced by a bus says so first, then its expected time and the route change`() {
        val cut = entry(
            RunStatus.PARTIALLY_CANCELLED, expected = scheduled.plusMinutes(12),
            routeChange = OperationalRouteChange(RouteChangeKind.SHORT_TURN, "termina a Lodi")
        )
        assertEquals(
            "Parzialmente soppresso. Per Pavia. Orario programmato 10:40. Aggiornamento live: orario previsto 10:52. " +
                "Binario 5. Treno 2153. Variazione: termina a Lodi.",
            spoken(cut)
        )
        assertEquals("Deviato. Per Pavia. Orario programmato 10:40. Binario 5. Treno 2153.", spoken(entry(RunStatus.DIVERTED)))
        assertEquals(
            "Sostituito da bus. Da Pavia. Orario programmato 10:40. Binario non disponibile. Treno 2153.",
            spoken(entry(RunStatus.REPLACED_BY_BUS, platform = null), kind = BoardKind.ARRIVALS)
        )
        assertEquals(scheduled.plusMinutes(12), StationBoardEntrySpeech.expectedTime(cut))
    }

    @Test
    fun `a route change is read on a train that is otherwise on time`() {
        val skipping = entry(
            RunStatus.ON_TIME, expected = scheduled,
            routeChange = OperationalRouteChange(RouteChangeKind.SKIPPED_STOPS, "non ferma a Certosa di Pavia")
        )
        assertEquals(
            "Per Pavia. Orario programmato 10:40. Aggiornamento live: orario previsto 10:40. Binario 5. Treno 2153. " +
                "Variazione: non ferma a Certosa di Pavia.",
            spoken(skipping)
        )
        assertNull(StationBoardEntrySpeech.disruption(skipping))
    }

    @Test
    fun `a train that runs keeps the sentence it had`() {
        assertEquals(
            "Per Pavia. Orario programmato 10:40. Aggiornamento live: orario previsto 10:52. Binario cambiato: 7, previsto 5. Treno 2153.",
            spoken(entry(RunStatus.DELAYED, expected = scheduled.plusMinutes(12), platform = "7", scheduledPlatform = "5"))
        )
        assertEquals("Per Pavia. Orario programmato 10:40. Stato live: In ritardo. Binario 5. Treno 2153.", spoken(entry(RunStatus.DELAYED)))
        assertEquals("Per Pavia. Orario programmato 10:40. Stato live non disponibile. Binario 5. Treno 2153.", spoken(entry(RunStatus.UNKNOWN)))
        assertEquals(
            "Per Pavia. Orario programmato 10:40. Orario programmato; stato live non disponibile. Binario 5. Treno 2153. Ultimo di oggi.",
            spoken(entry(null), lastOfDay = true)
        )
    }

    @Test
    fun `every language joins the pieces with its own separator`() {
        val cut = entry(RunStatus.CANCELLED, routeChange = OperationalRouteChange(RouteChangeKind.OTHER, "X"))
        assertEquals("停运。开往 Pavia。计划时刻 10:40。站台 5。车次 2153。变更：X。", spoken(cut, locale = "zh-rCN"))
        assertEquals("Cancelled. To Pavia. Scheduled time 10:40. Platform 5. Train 2153. Alteration: X.", spoken(cut, locale = "en"))
    }
}
