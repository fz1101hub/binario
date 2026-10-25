package it.binario.app.widget

import android.content.Context
import it.binario.app.R
import it.binario.app.data.DataConfidence
import it.binario.app.data.Freshness
import it.binario.app.data.RailTime
import it.binario.app.data.Station
import it.binario.app.data.core.RunStatus
import it.binario.app.data.repository.StationBoardAggregate
import it.binario.app.data.repository.withoutExpiredObservations
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * One departure persisted by the station board widget. [expectedEpochMillis] and [status] are
 * the operational observation of the refresh; whether they may still be shown as live is decided
 * at render time from the snapshot age, never stored.
 */
data class StationWidgetDeparture(
    val scheduledEpochMillis: Long,
    val expectedEpochMillis: Long?,
    val number: String?,
    val operator: String?,
    val destination: String,
    val platform: String?,
    val status: RunStatus?
) {
    /** True when the source published an operational observation rather than the timetable alone. */
    val hasObservation: Boolean
        get() = expectedEpochMillis != null || (status != null && status !in SCHEDULE_ONLY_STATUSES)

    private companion object {
        val SCHEDULE_ONLY_STATUSES = setOf(RunStatus.SCHEDULED, RunStatus.UNKNOWN)
    }
}

data class StationWidgetSnapshot(
    val stationId: String,
    val stationName: String,
    val departures: List<StationWidgetDeparture>,
    val providerNames: List<String>,
    val retrievedAt: Long,
    /** Set when the last refresh failed: the rows stay for context but lose every live claim. */
    val unavailable: Boolean = false
) {
    fun freshnessAt(nowEpochMillis: Long): Freshness =
        if (unavailable) Freshness.UNAVAILABLE else WidgetFreshness.fromAge(retrievedAt, nowEpochMillis)

    companion object {
        const val MAX_STORED = 12
        private const val DEPARTED_GRACE_MILLIS = 60_000L

        /** The next departures of a freshly loaded board, without observations its sources already expired. */
        fun fromBoard(board: StationBoardAggregate, now: Instant): StationWidgetSnapshot {
            val departures = board.entries.asSequence()
                .map { it.withoutExpiredObservations(now) }
                .map { entry ->
                    StationWidgetDeparture(
                        scheduledEpochMillis = entry.scheduledTime.toInstant().toEpochMilli(),
                        expectedEpochMillis = entry.expectedTime?.value?.toInstant()?.toEpochMilli(),
                        number = entry.publicNumber?.trim()?.takeIf(String::isNotEmpty),
                        operator = entry.operatorId?.value?.trim()?.takeIf(String::isNotEmpty),
                        destination = entry.destinationOrOriginLabel?.takeIf(String::isNotBlank) ?: entry.destinationOrOrigin.value,
                        platform = entry.platform?.value?.trim()?.takeIf(String::isNotEmpty),
                        status = entry.status?.value
                    )
                }
                .filter { (it.expectedEpochMillis ?: it.scheduledEpochMillis) >= now.toEpochMilli() - DEPARTED_GRACE_MILLIS }
                .sortedWith(compareBy<StationWidgetDeparture> { it.expectedEpochMillis ?: it.scheduledEpochMillis }.thenBy { it.scheduledEpochMillis })
                .take(MAX_STORED)
                .toList()
            return StationWidgetSnapshot(
                stationId = board.station.id,
                stationName = board.station.name,
                departures = departures,
                providerNames = board.providerNames,
                retrievedAt = board.fetchedAt.toEpochMilli()
            )
        }
    }
}

enum class StationWidgetEmphasis { NORMAL, WARNING, ERROR }

data class StationWidgetRow(
    val time: String,
    val train: String,
    val destination: String,
    val platform: String,
    val status: String,
    val emphasis: StationWidgetEmphasis,
    val accessibility: String
)

/** Resource-backed texts of the station widget; the route widget's [WidgetStrings] supply the shared labels. */
data class StationWidgetStrings(
    val widget: WidgetStrings,
    val statusLate: String,
    val statusChanged: String,
    val platformShort: String,
    val trainNumber: String,
    val departureTo: String,
    val scheduledTime: String,
    val livePrediction: String,
    val liveStatus: String,
    val plannedOnly: String,
    val runStatusLabels: Map<RunStatus, String>,
    val titleAccessibility: String
) {
    fun format(template: String, vararg values: Any): String = widget.format(template, *values)

    companion object {
        fun from(context: Context): StationWidgetStrings = StationWidgetStrings(
            widget = WidgetStrings.from(context),
            statusLate = context.getString(R.string.station_widget_status_late),
            statusChanged = context.getString(R.string.station_widget_status_changed),
            platformShort = context.getString(R.string.widget_platform_short),
            trainNumber = context.getString(R.string.station_board_train_number),
            departureTo = context.getString(R.string.station_board_departure_to),
            scheduledTime = context.getString(R.string.station_board_scheduled_time),
            livePrediction = context.getString(R.string.station_board_status_live_prediction),
            liveStatus = context.getString(R.string.station_board_status_live),
            plannedOnly = context.getString(R.string.station_board_status_planned),
            runStatusLabels = RunStatus.entries.associateWith { context.getString(it.widgetLabelRes()) },
            titleAccessibility = context.getString(R.string.station_widget_title_accessibility)
        )

        private fun RunStatus.widgetLabelRes(): Int = when (this) {
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
    }
}

/**
 * Turns the persisted departures into widget rows. Like the route widget, an observation counts
 * as live only while the snapshot is fresh or aging (under fifteen minutes old); afterwards, or
 * after a failed refresh, every row shows its timetable time marked as scheduled.
 */
object StationWidgetPresenter {
    const val MAX_ROWS = 4
    private const val DEPARTED_GRACE_MILLIS = 60_000L
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(RailTime.defaultZone)
    private val changedStatuses = setOf(RunStatus.PARTIALLY_CANCELLED, RunStatus.DIVERTED, RunStatus.REPLACED_BY_BUS)

    fun isLive(departure: StationWidgetDeparture, freshness: Freshness): Boolean =
        freshness in setOf(Freshness.FRESH, Freshness.AGING) && departure.hasObservation

    /** Departures that have not left yet by their live (or else scheduled) time, earliest first. */
    fun upcoming(
        departures: List<StationWidgetDeparture>,
        freshness: Freshness,
        nowEpochMillis: Long,
        limit: Int = MAX_ROWS
    ): List<StationWidgetDeparture> = departures
        .filter { departure -> effectiveDeparture(departure, freshness) >= nowEpochMillis - DEPARTED_GRACE_MILLIS }
        .sortedWith(compareBy<StationWidgetDeparture> { effectiveDeparture(it, freshness) }.thenBy { it.scheduledEpochMillis })
        .take(limit.coerceIn(0, MAX_ROWS))

    fun row(departure: StationWidgetDeparture, freshness: Freshness, strings: StationWidgetStrings): StationWidgetRow {
        val widget = strings.widget
        val live = isLive(departure, freshness)
        val scheduled = timeFormatter.format(Instant.ofEpochMilli(departure.scheduledEpochMillis))
        val expected = departure.expectedEpochMillis?.takeIf { live }
        val delayMinutes = expected?.let { ((it - departure.scheduledEpochMillis) / 60_000L).coerceAtLeast(0L) } ?: 0L
        val status = departure.status.takeIf { live }
        val (statusLabel, emphasis) = when {
            !live -> widget.statusScheduled to StationWidgetEmphasis.NORMAL
            status == RunStatus.CANCELLED -> widget.statusCancelled to StationWidgetEmphasis.ERROR
            status in changedStatuses -> strings.statusChanged to StationWidgetEmphasis.WARNING
            delayMinutes > 0 -> strings.format(widget.statusDelayed, delayMinutes) to StationWidgetEmphasis.WARNING
            status == RunStatus.DELAYED && expected == null -> strings.statusLate to StationWidgetEmphasis.WARNING
            else -> widget.statusOnTime to StationWidgetEmphasis.NORMAL
        }
        val operational = when {
            !live -> strings.plannedOnly
            status != null && (status == RunStatus.CANCELLED || status in changedStatuses) ->
                strings.format(strings.liveStatus, strings.runStatusLabels.getValue(status))
            expected != null -> listOfNotNull(
                strings.format(strings.livePrediction, timeFormatter.format(Instant.ofEpochMilli(expected))),
                delayMinutes.takeIf { it > 0 }?.let { strings.format(widget.delayMinutes, it) }
            ).joinToString(", ")
            status != null -> strings.format(strings.liveStatus, strings.runStatusLabels.getValue(status))
            else -> strings.plannedOnly
        }
        val accessibility = listOfNotNull(
            departure.number?.let { strings.format(strings.trainNumber, it) },
            departure.operator,
            strings.format(strings.departureTo, departure.destination),
            strings.format(strings.scheduledTime, scheduled),
            operational,
            departure.platform?.let { strings.format(widget.platform, it) } ?: widget.platformUnavailable
        ).joinToString(". ")
        return StationWidgetRow(
            time = scheduled,
            train = listOfNotNull(departure.operator?.uppercase(widget.locale), departure.number).joinToString(" · ").ifBlank { "—" },
            destination = departure.destination,
            platform = departure.platform?.let { strings.format(strings.platformShort, it) } ?: "—",
            status = statusLabel,
            emphasis = emphasis,
            accessibility = accessibility
        )
    }

    /** Source, scheduled-only marker, freshness and update time, like the route widget's meta line. */
    fun meta(snapshot: StationWidgetSnapshot, freshness: Freshness, updatedAt: String, strings: StationWidgetStrings): String = listOfNotNull(
        snapshot.providerNames.joinToString(", ").ifBlank { null },
        strings.widget.confidenceLabels.getValue(DataConfidence.STATIC_ONLY).takeIf { snapshot.departures.none { isLive(it, freshness) } },
        strings.widget.freshnessLabels.getValue(freshness),
        updatedAt
    ).joinToString(" · ")

    fun titleAccessibility(snapshot: StationWidgetSnapshot, freshness: Freshness, updatedAt: String, strings: StationWidgetStrings): String =
        strings.format(
            strings.titleAccessibility,
            snapshot.stationName,
            snapshot.providerNames.joinToString(", "),
            strings.widget.freshnessAccessibleLabels.getValue(freshness),
            updatedAt
        )

    private fun effectiveDeparture(departure: StationWidgetDeparture, freshness: Freshness): Long =
        departure.expectedEpochMillis?.takeIf { isLive(departure, freshness) } ?: departure.scheduledEpochMillis
}

/**
 * Language-neutral persistence of the station widget. Each field is Base64 encoded with
 * java.util.Base64, which also runs in JVM unit tests (the android.util stub throws there).
 */
object StationWidgetCodec {
    private const val FIELD = ","
    private const val RECORD = ";"
    private const val ALIAS_SEPARATOR = "\u001F"

    fun encodeStation(station: Station): String = listOf(
        station.id,
        station.name,
        station.city,
        station.region.orEmpty(),
        station.railwayCode.orEmpty(),
        station.trenitaliaLocationId.orEmpty(),
        station.aliases.joinToString(ALIAS_SEPARATOR),
        station.latitude?.toString().orEmpty(),
        station.longitude?.toString().orEmpty()
    ).joinToString(FIELD) { encode(it) }

    fun decodeStation(value: String): Station? = runCatching {
        val parts = value.split(FIELD).map(::decode)
        require(parts.size == 9)
        val id = parts[0]
        val name = parts[1]
        if (id.isBlank() || name.isBlank()) return null
        Station(
            id = id,
            name = name,
            city = parts[2],
            region = parts[3].ifBlank { null },
            railwayCode = parts[4].ifBlank { null },
            trenitaliaLocationId = parts[5].ifBlank { null },
            aliases = parts[6].split(ALIAS_SEPARATOR).filter(String::isNotBlank),
            latitude = parts[7].toDoubleOrNull(),
            longitude = parts[8].toDoubleOrNull()
        )
    }.getOrNull()

    fun encodeDepartures(departures: List<StationWidgetDeparture>): String = departures.joinToString(RECORD) { departure ->
        listOf(
            departure.scheduledEpochMillis.toString(),
            departure.expectedEpochMillis?.toString().orEmpty(),
            departure.number.orEmpty(),
            departure.operator.orEmpty(),
            departure.destination,
            departure.platform.orEmpty(),
            departure.status?.name.orEmpty()
        ).joinToString(FIELD) { encode(it) }
    }

    /** Unreadable records are dropped one by one, never the whole board. */
    fun decodeDepartures(value: String): List<StationWidgetDeparture> = value.split(RECORD)
        .filter(String::isNotBlank)
        .mapNotNull { record ->
            runCatching {
                val parts = record.split(FIELD).map(::decode)
                require(parts.size == 7)
                StationWidgetDeparture(
                    scheduledEpochMillis = parts[0].toLong(),
                    expectedEpochMillis = parts[1].ifBlank { null }?.toLong(),
                    number = parts[2].ifBlank { null },
                    operator = parts[3].ifBlank { null },
                    destination = parts[4],
                    platform = parts[5].ifBlank { null },
                    status = parts[6].ifBlank { null }?.let { name -> RunStatus.entries.firstOrNull { it.name == name } ?: RunStatus.UNKNOWN }
                )
            }.getOrNull()
        }

    fun encodeNames(names: List<String>): String = names.joinToString(FIELD) { encode(it) }

    fun decodeNames(value: String): List<String> = value.split(FIELD)
        .filter(String::isNotEmpty)
        .mapNotNull { runCatching { decode(it) }.getOrNull() }
        .filter(String::isNotBlank)

    private fun encode(value: String): String = java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String): String = String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8)
}
