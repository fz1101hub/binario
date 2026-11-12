package it.binario.app.ui.departures

import it.binario.app.R
import it.binario.app.data.PlatformChange
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.StationBoardEntry
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a row of the station board tells about its run, to the eye and to TalkBack. Pure: the screen passes the
 * resources, the tests the real catalogues, so the sentence a blind traveller hears is checked on the JVM.
 */
internal object StationBoardEntrySpeech {
    /** A string of the catalogue with its arguments, as `Resources.getString` resolves it. */
    fun interface Catalogue {
        fun text(id: Int, vararg arguments: Any): String
    }

    /**
     * A run that does not go as the timetable says: cancelled, cut short, diverted or replaced by a bus. The row says
     * so before anything else and shows it as a label of its own, never as one more item of the grey line.
     */
    fun disruption(entry: StationBoardEntry): RunStatus? = entry.status?.value?.takeIf { it in DISRUPTIONS }

    /**
     * The expected time to show and to read. A cancelled train has none: a source may fill the field with the
     * timetable (ViaggiaTreno does), and «orario previsto 10:40» would tell the traveller that the train is coming.
     */
    fun expectedTime(entry: StationBoardEntry): ZonedDateTime? =
        entry.expectedTime?.value?.takeUnless { entry.status?.value == RunStatus.CANCELLED }

    fun label(status: RunStatus): Int = when (status) {
        RunStatus.SCHEDULED -> R.string.run_status_scheduled
        RunStatus.ON_TIME -> R.string.run_status_on_time
        RunStatus.DELAYED -> R.string.run_status_delayed
        RunStatus.CANCELLED -> R.string.run_status_cancelled
        RunStatus.PARTIALLY_CANCELLED -> R.string.run_status_partially_cancelled
        RunStatus.DIVERTED -> R.string.run_status_diverted
        RunStatus.ADDED -> R.string.run_status_added
        RunStatus.REPLACED_BY_BUS -> R.string.run_status_bus_replacement
        RunStatus.COMPLETED -> R.string.run_status_completed
        RunStatus.UNKNOWN -> R.string.run_status_unknown
    }

    /**
     * The whole row as one sentence: the disruption first, then direction, timetable, what the live source says,
     * platform, train, the route change the row shows («Variazione: …») and «Ultimo di oggi».
     */
    fun description(entry: StationBoardEntry, kind: BoardKind, lastOfDay: Boolean, catalogue: Catalogue): String {
        val disruption = disruption(entry)
        val expected = expectedTime(entry)
        val status = entry.status?.value
        val endpoint = entry.destinationOrOriginLabel?.takeIf(String::isNotBlank) ?: entry.destinationOrOrigin.value
        val platform = entry.platform?.value?.takeIf(String::isNotBlank)
        val platformChange = PlatformChange.of(platform, entry.scheduledPlatform?.value)
        val pieces = listOfNotNull(
            disruption?.let { catalogue.text(label(it)) },
            catalogue.text(if (kind == BoardKind.DEPARTURES) R.string.station_board_departure_to else R.string.station_board_arrival_from, endpoint),
            catalogue.text(R.string.station_board_scheduled_time, time(entry.scheduledTime)),
            when {
                expected != null -> catalogue.text(R.string.station_board_status_live_prediction, time(expected))
                // Said first: nothing to add about the state of a disrupted run without an expected time.
                disruption != null -> null
                status != null && status != RunStatus.SCHEDULED && status != RunStatus.UNKNOWN ->
                    catalogue.text(R.string.station_board_status_live, catalogue.text(label(status)))
                status == RunStatus.UNKNOWN -> catalogue.text(R.string.station_board_status_unavailable)
                else -> catalogue.text(R.string.station_board_status_planned)
            },
            when {
                platformChange != null -> catalogue.text(R.string.platform_changed_accessible, platformChange.actual, platformChange.scheduled)
                platform != null -> catalogue.text(R.string.station_board_platform_accessible, platform)
                else -> catalogue.text(R.string.station_board_platform_unavailable)
            },
            entry.publicNumber?.takeIf(String::isNotBlank)?.let { catalogue.text(R.string.station_board_train_number, it) },
            entry.routeChange?.value?.let { catalogue.text(R.string.station_board_route_change, it.summary) },
            if (lastOfDay) catalogue.text(R.string.journey_last_of_day) else null
        )
        // The sentence of the catalogue has five places: what does not fit follows in the last one, with the
        // separator of the language.
        val last = pieces.drop(SENTENCE_PLACES - 1)
            .reduceOrNull { said, next -> catalogue.text(R.string.accessibility_title_detail, said, next) }
            .orEmpty()
        return catalogue.text(R.string.station_board_entry_accessibility, *(pieces.take(SENTENCE_PLACES - 1) + last).toTypedArray())
    }

    private fun time(moment: ZonedDateTime): String = moment.withZoneSameInstant(ZONE).format(TIME)

    private val DISRUPTIONS = setOf(RunStatus.CANCELLED, RunStatus.PARTIALLY_CANCELLED, RunStatus.DIVERTED, RunStatus.REPLACED_BY_BUS)
    private const val SENTENCE_PLACES = 5
    private val ZONE: ZoneId = ZoneId.of("Europe/Rome")
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)
}
