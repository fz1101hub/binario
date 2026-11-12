package it.binario.app.widget

import android.content.Context
import androidx.core.content.edit
import it.binario.app.FavouriteTrainOrdering
import it.binario.app.R
import it.binario.app.ServiceMonitor
import it.binario.app.data.Freshness
import it.binario.app.data.PlatformChange
import it.binario.app.data.RailTime
import it.binario.app.data.ServiceStatus
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.ui.common.LocalizedDates
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The last known state of one favourite train, shared by every "I miei treni" widget. Language
 * neutral: labels are resolved at render time. When a lookup fails the previous values stay and
 * [stale] marks them, so a row never turns empty.
 */
data class FavouriteTrainStatus(
    val number: String,
    val retrievedAt: Long,
    val category: TrainCategory? = null,
    val operator: String? = null,
    val origin: String = "",
    val destination: String = "",
    val scheduledDeparture: String = "",
    val scheduledDepartureEpochMillis: Long? = null,
    /** Arrival at the last stop, expected when verified, else scheduled. */
    val arrivalEpochMillis: Long? = null,
    /** Minutes of delay (negative when early); null without a verified observation. */
    val delayMinutes: Int? = null,
    val cancelled: Boolean = false,
    /** Route change, skipped or added stops. */
    val variation: Boolean = false,
    val nextStation: String? = null,
    val nextStationTime: String? = null,
    val lastDetectionStation: String? = null,
    val lastDetectionTime: String? = null,
    val platform: String? = null,
    /** Observed by the source; null when it published no observation. */
    val departed: Boolean? = null,
    val arrived: Boolean? = null,
    val operationalVerified: Boolean = false,
    val providerName: String = "",
    val stale: Boolean = false,
    /** Timetable platform of the same stop when the source moved the train; null otherwise. */
    val scheduledPlatform: String? = null
)

object FavouriteTrainStatusMapper {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * The status of [monitor] from its run [service], kept under the favourite's own number: a favourite saved from a
     * timetable row is called "RE_11 - 2153" while the live run that takes its place is "2153", and the widget reads
     * (and keeps) only the favourites' numbers.
     */
    fun forFavourite(monitor: ServiceMonitor, service: TrainService, providerName: String, retrievedAt: Long, zone: ZoneId = RailTime.defaultZone): FavouriteTrainStatus =
        fromService(service, providerName, retrievedAt, zone).copy(number = monitor.number)

    fun fromService(service: TrainService, providerName: String, retrievedAt: Long, zone: ZoneId = RailTime.defaultZone): FavouriteTrainStatus {
        val verified = service.operationalStatusVerified
        val scheduledDepartureEpoch = service.scheduledDepartureDateTime?.atZone(zone)?.toInstant()?.toEpochMilli()
            ?: WidgetClock.resolve(service.scheduledDeparture, retrievedAt, zone)
        val serviceDate = scheduledDepartureEpoch?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val last = service.stops.lastOrNull()
        val lastVerified = last?.operationalStatusVerified ?: verified
        val arrivalLabel = last?.let { if (lastVerified) it.expected.ifBlank { it.scheduled } else it.scheduled }
        val arrivalEpoch = if (arrivalLabel != null && serviceDate != null) WidgetClock.onDate(arrivalLabel, serviceDate, scheduledDepartureEpoch, zone) else null
        val next = service.stops.firstOrNull { !it.completed }
        val nextVerified = next?.operationalStatusVerified ?: verified
        val detection = service.lastOperationalDetection
        val lastCompleted = service.stops.lastOrNull { it.completed }
        return FavouriteTrainStatus(
            number = service.number,
            retrievedAt = retrievedAt,
            category = service.trainCategory,
            operator = service.displayOperator,
            origin = service.origin,
            destination = service.destination,
            scheduledDeparture = service.scheduledDeparture,
            scheduledDepartureEpochMillis = scheduledDepartureEpoch,
            arrivalEpochMillis = arrivalEpoch,
            delayMinutes = if (verified && service.status != ServiceStatus.CANCELLED) service.officialDelayMinutes ?: service.delayMinutes else null,
            cancelled = verified && service.status == ServiceStatus.CANCELLED,
            variation = service.routeChange != null || service.stops.any { it.routeChange != StopRouteChange.NONE },
            nextStation = next?.station,
            nextStationTime = next?.let { if (nextVerified) it.expected.ifBlank { it.scheduled } else it.scheduled },
            lastDetectionStation = detection?.locationLabel ?: lastCompleted?.station?.takeIf { verified },
            lastDetectionTime = detection?.actualAt?.value?.withZoneSameInstant(zone)?.format(timeFormatter)
                ?: lastCompleted?.expected?.takeIf { verified },
            platform = (next ?: service.stops.firstOrNull())?.platform ?: service.platform,
            scheduledPlatform = ((next ?: service.stops.firstOrNull())?.takeIf { it.platform != null }?.scheduledPlatform)
                ?: service.scheduledPlatform.takeIf { (next ?: service.stops.firstOrNull())?.platform == null && service.platform != null },
            departed = if (verified) detection != null || service.stops.firstOrNull()?.completed == true else null,
            arrived = if (verified) last?.completed == true else null,
            operationalVerified = verified,
            providerName = providerName
        )
    }
}

enum class TrainsRowEmphasis { NORMAL, EARLY, WARNING, ERROR, MUTED }

data class TrainsWidgetRow(
    val number: String,
    val origin: String,
    val title: String,
    val detail: String,
    val status: String,
    val emphasis: TrainsRowEmphasis,
    val platform: String,
    val accessibility: String,
    /** The platform differs from the timetable one: drawn in the attention colour. */
    val platformChanged: Boolean = false
)

/** Resource-backed texts of the trains widget, resolved once per render. */
data class TrainsWidgetStrings(
    val locale: Locale,
    val trainNumber: String,
    val categoryNumber: String,
    val nextStation: String,
    val lastDetection: String,
    val noDetection: String,
    val departureFrom: String,
    val waiting: String,
    val staleData: String,
    val onTime: String,
    val late: String,
    val early: String,
    val cancelled: String,
    val variation: String,
    val scheduled: String,
    val platformShort: String,
    val platform: String,
    val platformUnavailable: String,
    val delayAccessibility: (Int) -> String,
    val earlyAccessibility: (Int) -> String,
    val onTimeAccessibility: String,
    val cancelledAccessibility: String,
    val variationAccessibility: String,
    val scheduledAccessibility: String,
    val categoryLabels: Map<TrainCategory, String>,
    /** "BIN %1$s\nera %2$s" and "Binario cambiato: %1$s, previsto %2$s"; blank falls back to the plain forms. */
    val platformChangedShort: String = "",
    val platformChangedSpoken: String = ""
) {
    fun format(template: String, vararg values: Any): String = template.format(locale, *values)

    companion object {
        fun from(context: Context): TrainsWidgetStrings = TrainsWidgetStrings(
            locale = context.resources.configuration.locales[0],
            trainNumber = context.getString(R.string.train_number_label),
            categoryNumber = context.getString(R.string.service_category_number),
            nextStation = context.getString(R.string.trains_widget_next_station),
            lastDetection = context.getString(R.string.trains_widget_last_detection),
            noDetection = context.getString(R.string.trains_widget_no_detection),
            departureFrom = context.getString(R.string.trains_widget_departure_from),
            waiting = context.getString(R.string.trains_widget_waiting),
            staleData = context.getString(R.string.trains_widget_stale_data),
            onTime = context.getString(R.string.widget_status_on_time),
            late = context.getString(R.string.widget_status_delayed),
            early = context.getString(R.string.trains_widget_status_early),
            cancelled = context.getString(R.string.widget_status_cancelled),
            variation = context.getString(R.string.station_widget_status_changed),
            scheduled = context.getString(R.string.widget_status_scheduled),
            platformShort = context.getString(R.string.widget_platform_short),
            platform = context.getString(R.string.platform_label),
            platformUnavailable = context.getString(R.string.platform_unavailable),
            delayAccessibility = { minutes -> context.resources.getQuantityString(R.plurals.trains_widget_delay_accessibility, minutes, minutes) },
            earlyAccessibility = { minutes -> context.resources.getQuantityString(R.plurals.trains_widget_early_accessibility, minutes, minutes) },
            onTimeAccessibility = context.getString(R.string.trains_widget_on_time_accessibility),
            cancelledAccessibility = context.getString(R.string.trains_widget_cancelled_accessibility),
            variationAccessibility = context.getString(R.string.trains_widget_variation_accessibility),
            scheduledAccessibility = context.getString(R.string.operational_status_unverified),
            categoryLabels = TrainCategory.entries.associateWith { context.getString(it.trainsWidgetLabelRes()) },
            platformChangedShort = context.getString(R.string.widget_platform_changed),
            platformChangedSpoken = context.getString(R.string.platform_changed_accessible)
        )

        private fun TrainCategory.trainsWidgetLabelRes(): Int = when (this) {
            TrainCategory.REGIONALE -> R.string.train_category_regionale
            TrainCategory.REGIONALE_VELOCE -> R.string.train_category_regionale_veloce
            TrainCategory.INTERCITY -> R.string.train_category_intercity
            TrainCategory.FRECCE -> R.string.train_category_frecce
            TrainCategory.AV_ITALO -> R.string.train_category_italo_high_speed
            TrainCategory.TRENORD_REGIO -> R.string.train_category_trenord_regional
            TrainCategory.METROPOLITANO -> R.string.train_category_metropolitan
            TrainCategory.OTHER -> R.string.train_category_other
        }
    }
}

/**
 * Rows of the "I miei treni" widget: ordering, the "only running trains" filter and the texts of
 * each row. Live values count only while the status is fresh (under fifteen minutes) and was not
 * left over by a failed lookup; otherwise the row keeps them, marked with the time (and, when it is
 * not today's, the date) of the data. The run of an earlier day that is over shows today's timetable.
 */
object TrainsWidgetPresenter {
    private val arrivalGrace: Duration = Duration.ofMinutes(10)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(RailTime.defaultZone)

    /**
     * [status] as it may be shown at [nowEpochMillis]. The run of an earlier service day that is over (arrived, or past
     * its arrival) says nothing about today's train: it becomes today's timetable, with the same departure and arrival
     * times moved to today and without delay, cancellation, variation, detections or observations. Anything else is
     * returned unchanged, including a run of yesterday still travelling after midnight.
     */
    fun forToday(status: FavouriteTrainStatus, nowEpochMillis: Long, zone: ZoneId = RailTime.defaultZone): FavouriteTrainStatus {
        val departure = status.scheduledDepartureEpochMillis ?: return status
        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
        val serviceDay = Instant.ofEpochMilli(departure).atZone(zone).toLocalDate()
        if (!serviceDay.isBefore(today)) return status
        val end = status.arrivalEpochMillis ?: departure
        if (status.arrived != true && nowEpochMillis <= end + arrivalGrace.toMillis()) return status
        val days = ChronoUnit.DAYS.between(serviceDay, today)
        fun moved(epochMillis: Long) = Instant.ofEpochMilli(epochMillis).atZone(zone).plusDays(days).toInstant().toEpochMilli()
        return status.copy(
            scheduledDepartureEpochMillis = moved(departure),
            arrivalEpochMillis = status.arrivalEpochMillis?.let(::moved),
            delayMinutes = null,
            cancelled = false,
            variation = false,
            nextStation = null,
            nextStationTime = null,
            lastDetectionStation = null,
            lastDetectionTime = null,
            platform = status.scheduledPlatform ?: status.platform,
            scheduledPlatform = null,
            departed = null,
            arrived = null,
            operationalVerified = false,
            stale = false
        )
    }

    /** "18:30" for data of today, "7 nov 18:30" for data of another day. */
    private fun dataTime(retrievedAt: Long, nowEpochMillis: Long, locale: Locale, zone: ZoneId = RailTime.defaultZone): String {
        val at = Instant.ofEpochMilli(retrievedAt).atZone(zone)
        val time = timeFormatter.format(at)
        return if (at.toLocalDate() == Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()) time
        else "${LocalizedDates.dayMonth(locale).format(at)} $time"
    }

    /**
     * A train is running unless it is cancelled, has clearly not left yet (no observed departure and
     * before its scheduled departure) or has arrived (observed, or ten minutes past its arrival).
     * Without times it counts as running: an unknown state never hides a train.
     */
    fun isRunning(lastKnown: FavouriteTrainStatus?, nowEpochMillis: Long): Boolean {
        val status = lastKnown?.let { forToday(it, nowEpochMillis) } ?: return true
        if (status.cancelled) return false
        val departure = status.scheduledDepartureEpochMillis
        val notLeftYet = status.departed != true && departure != null && nowEpochMillis < departure
        val arrived = status.arrived == true ||
            (status.arrivalEpochMillis != null && nowEpochMillis > status.arrivalEpochMillis + arrivalGrace.toMillis())
        return !notLeftYet && !arrived
    }

    fun isLive(status: FavouriteTrainStatus, nowEpochMillis: Long): Boolean =
        !status.stale && status.operationalVerified && WidgetFreshness.fromAge(status.retrievedAt, nowEpochMillis) in setOf(Freshness.FRESH, Freshness.AGING)

    /** Ordered (and, with [TrainsWidgetSettings.hideNotRunning], filtered) favourites with their status. */
    fun visible(
        monitors: List<ServiceMonitor>,
        statuses: Map<String, FavouriteTrainStatus>,
        settings: TrainsWidgetSettings,
        nowEpochMillis: Long,
        locale: Locale,
        trainLabel: (String) -> String
    ): List<ServiceMonitor> {
        val sort = if (settings.sort == TrainsWidgetSort.NAME) FavouriteTrainOrdering.Sort.NAME else FavouriteTrainOrdering.Sort.DEPARTURE
        // Saved trains of earlier versions have no departure: the last known one orders them.
        val withDeparture = monitors.map { monitor ->
            if (monitor.scheduledDeparture.isNotBlank()) monitor
            else monitor.copy(scheduledDeparture = statuses[monitor.number.uppercase(Locale.ROOT)]?.scheduledDeparture.orEmpty())
        }
        val ordered = FavouriteTrainOrdering.sorted(withDeparture, sort, locale, trainLabel)
        val originals = monitors.associateBy { it.number }
        return ordered.mapNotNull { originals[it.number] }
            .filter { !settings.hideNotRunning || isRunning(statuses[it.number.uppercase(Locale.ROOT)], nowEpochMillis) }
    }

    /**
     * The row of [monitor]. [reliability] (the network statistics of the train, «83% in orario») closes the detail line
     * and the spoken label when [TrainsWidgetSettings.showReliability] is on.
     */
    fun row(
        monitor: ServiceMonitor,
        lastKnown: FavouriteTrainStatus?,
        settings: TrainsWidgetSettings,
        nowEpochMillis: Long,
        strings: TrainsWidgetStrings,
        reliability: WidgetReliabilityText? = null
    ): TrainsWidgetRow {
        val status = lastKnown?.let { forToday(it, nowEpochMillis) }
        val trainLabel = status?.category?.let { strings.format(strings.categoryNumber, strings.categoryLabels.getValue(it), monitor.number) }
            ?: strings.format(strings.trainNumber, monitor.number)
        val title = monitor.displayName ?: trainLabel
        val shownReliability = reliability?.takeIf { settings.showReliability }
        if (status == null) {
            return TrainsWidgetRow(
                number = monitor.number,
                origin = monitor.origin,
                title = title,
                detail = listOfNotNull(trainLabel.takeIf { monitor.displayName != null }, strings.waiting, shownReliability?.shown).joinToString(" · "),
                status = "—",
                emphasis = TrainsRowEmphasis.MUTED,
                platform = "—",
                accessibility = listOfNotNull(title, trainLabel.takeIf { monitor.displayName != null }, strings.waiting, shownReliability?.spoken).joinToString(". ")
            )
        }
        val live = isLive(status, nowEpochMillis)
        val platformChange = PlatformChange.of(status.platform, status.scheduledPlatform)
        val info = when (settings.info) {
            TrainsWidgetInfo.NEXT_STATION -> status.nextStation?.let { strings.format(strings.nextStation, it, status.nextStationTime.orEmpty()).trim() }
            TrainsWidgetInfo.LAST_DETECTION -> status.lastDetectionStation?.let { strings.format(strings.lastDetection, it, status.lastDetectionTime.orEmpty()).trim() }
                ?: strings.noDetection.takeIf { status.operationalVerified }
        } ?: status.scheduledDeparture.takeIf { it.isNotBlank() }?.let { strings.format(strings.departureFrom, it, status.origin) }
        val staleMarker = if (live || !status.operationalVerified) null
        else strings.format(strings.staleData, dataTime(status.retrievedAt, nowEpochMillis, strings.locale))
        val (statusLabel, emphasis, statusAccessibility) = when {
            !status.operationalVerified -> Triple(strings.scheduled, TrainsRowEmphasis.NORMAL, strings.scheduledAccessibility)
            status.cancelled -> Triple(strings.cancelled, if (live) TrainsRowEmphasis.ERROR else TrainsRowEmphasis.MUTED, strings.cancelledAccessibility)
            status.variation -> Triple(strings.variation, if (live) TrainsRowEmphasis.WARNING else TrainsRowEmphasis.MUTED, strings.variationAccessibility)
            (status.delayMinutes ?: 0) > 0 -> Triple(
                strings.format(strings.late, status.delayMinutes ?: 0),
                if (live) TrainsRowEmphasis.WARNING else TrainsRowEmphasis.MUTED,
                strings.delayAccessibility(status.delayMinutes ?: 0)
            )
            (status.delayMinutes ?: 0) < 0 -> Triple(
                strings.format(strings.early, -(status.delayMinutes ?: 0)),
                if (live) TrainsRowEmphasis.EARLY else TrainsRowEmphasis.MUTED,
                strings.earlyAccessibility(-(status.delayMinutes ?: 0))
            )
            else -> Triple(strings.onTime, if (live) TrainsRowEmphasis.NORMAL else TrainsRowEmphasis.MUTED, strings.onTimeAccessibility)
        }
        val detail = listOfNotNull(
            trainLabel.takeIf { monitor.displayName != null },
            staleMarker,
            info,
            shownReliability?.shown
        ).joinToString(" · ")
        return TrainsWidgetRow(
            number = monitor.number,
            origin = monitor.origin.ifBlank { status.origin },
            title = title,
            detail = detail,
            status = statusLabel,
            emphasis = emphasis,
            platform = platformChange?.takeIf { strings.platformChangedShort.isNotBlank() }
                ?.let { strings.format(strings.platformChangedShort, it.actual, it.scheduled) }
                ?: status.platform?.let { strings.format(strings.platformShort, it) } ?: "—",
            platformChanged = platformChange != null,
            accessibility = listOfNotNull(
                title,
                trainLabel.takeIf { monitor.displayName != null },
                statusAccessibility,
                staleMarker,
                info,
                if (settings.showPlatform) {
                    platformChange?.takeIf { strings.platformChangedSpoken.isNotBlank() }
                        ?.let { strings.format(strings.platformChangedSpoken, it.actual, it.scheduled) }
                        ?: status.platform?.let { strings.format(strings.platform, it) } ?: strings.platformUnavailable
                } else null,
                shownReliability?.spoken
            ).joinToString(". ")
        )
    }
}

/**
 * Which favourites a refresh of «I miei treni» asks the sources for, and for which service day. An automatic refresh
 * asks only for the trains near their run: from [BEFORE_DEPARTURE] before the saved departure to [AFTER_ARRIVAL] after
 * the arrival of the last known run ([DEFAULT_DURATION] after the departure when none is known); the other rows keep
 * their last status. A train without a known departure is asked, since only a lookup can teach it. A refresh the user
 * asked for asks for every favourite. Italo is asked only for a train that may be one.
 */
internal object TrainsWidgetLookupPlanner {
    val BEFORE_DEPARTURE: Duration = Duration.ofHours(1)
    val AFTER_ARRIVAL: Duration = Duration.ofMinutes(30)
    val DEFAULT_DURATION: Duration = Duration.ofHours(3)
    private val MAX_DURATION: Duration = Duration.ofHours(36)

    /** The service day to ask for [monitor] at [now]; null when an automatic refresh leaves the train alone. */
    fun lookupDate(monitor: ServiceMonitor, status: FavouriteTrainStatus?, now: Instant, automatic: Boolean, zone: ZoneId = RailTime.defaultZone): LocalDate? {
        val today = now.atZone(zone).toLocalDate()
        val departure = departureOf(monitor, status) ?: return today
        val duration = durationOf(status) ?: DEFAULT_DURATION
        // Yesterday's run may still travel after midnight; tomorrow's may leave just after it.
        val inWindow = listOf(today.minusDays(1), today, today.plusDays(1)).firstOrNull { day ->
            val start = day.atTime(departure).atZone(zone).toInstant()
            !now.isBefore(start.minus(BEFORE_DEPARTURE)) && !now.isAfter(start.plus(duration).plus(AFTER_ARRIVAL))
        }
        return inWindow ?: today.takeIf { !automatic }
    }

    /** False when the last status names a category of another operator: Italo is then not asked for that number. */
    fun mayBeItalo(status: FavouriteTrainStatus?): Boolean =
        status?.category.let { it == null || it == TrainCategory.AV_ITALO || it == TrainCategory.OTHER }

    private fun departureOf(monitor: ServiceMonitor, status: FavouriteTrainStatus?): LocalTime? =
        listOf(monitor.scheduledDeparture, status?.scheduledDeparture.orEmpty()).firstNotNullOfOrNull { value ->
            value.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        }

    private fun durationOf(status: FavouriteTrainStatus?): Duration? {
        val departure = status?.scheduledDepartureEpochMillis ?: return null
        val arrival = status.arrivalEpochMillis ?: return null
        return Duration.ofMillis(arrival - departure).takeIf { !it.isNegative && !it.isZero && it <= MAX_DURATION }
    }
}

/** Codec of [FavouriteTrainStatus]: Base64 fields joined by `,`; unreadable records read as absent. */
object FavouriteTrainStatusCodec {
    private const val FIELDS = 23

    fun encode(status: FavouriteTrainStatus): String = listOf(
        status.number,
        status.retrievedAt.toString(),
        status.category?.name.orEmpty(),
        status.operator.orEmpty(),
        status.origin,
        status.destination,
        status.scheduledDeparture,
        status.scheduledDepartureEpochMillis?.toString().orEmpty(),
        status.arrivalEpochMillis?.toString().orEmpty(),
        status.delayMinutes?.toString().orEmpty(),
        flag(status.cancelled),
        flag(status.variation),
        status.nextStation.orEmpty(),
        status.nextStationTime.orEmpty(),
        status.lastDetectionStation.orEmpty(),
        status.lastDetectionTime.orEmpty(),
        status.platform.orEmpty(),
        status.departed?.let(::flag).orEmpty(),
        status.arrived?.let(::flag).orEmpty(),
        flag(status.operationalVerified),
        status.providerName,
        flag(status.stale),
        status.scheduledPlatform.orEmpty()
    ).joinToString(",") { java.util.Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }

    fun decode(value: String): FavouriteTrainStatus? = runCatching {
        val parts = value.split(",").map { String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8) }
        // Records written before the timetable platform have one field less.
        require(parts.size == FIELDS || parts.size == FIELDS - 1)
        FavouriteTrainStatus(
            number = parts[0],
            retrievedAt = parts[1].toLong(),
            category = TrainCategory.entries.firstOrNull { it.name == parts[2] },
            operator = parts[3].ifBlank { null },
            origin = parts[4],
            destination = parts[5],
            scheduledDeparture = parts[6],
            scheduledDepartureEpochMillis = parts[7].toLongOrNull(),
            arrivalEpochMillis = parts[8].toLongOrNull(),
            delayMinutes = parts[9].toIntOrNull(),
            cancelled = parts[10] == "1",
            variation = parts[11] == "1",
            nextStation = parts[12].ifBlank { null },
            nextStationTime = parts[13].ifBlank { null },
            lastDetectionStation = parts[14].ifBlank { null },
            lastDetectionTime = parts[15].ifBlank { null },
            platform = parts[16].ifBlank { null },
            departed = parts[17].ifBlank { null }?.let { it == "1" },
            arrived = parts[18].ifBlank { null }?.let { it == "1" },
            operationalVerified = parts[19] == "1",
            providerName = parts[20],
            stale = parts[21] == "1",
            scheduledPlatform = parts.getOrNull(22)?.ifBlank { null }
        )
    }.getOrNull()

    private fun flag(value: Boolean): String = if (value) "1" else "0"
}

/** Last known status of each favourite train, shared by every trains widget. */
object FavouriteTrainStatusStore {
    private const val PREFS = "binario_trains_widget_state"

    fun read(context: Context, number: String): FavouriteTrainStatus? =
        prefs(context).getString(key(number), null)?.let(FavouriteTrainStatusCodec::decode)

    fun all(context: Context, numbers: List<String>): Map<String, FavouriteTrainStatus> =
        numbers.mapNotNull { number -> read(context, number)?.let { number.uppercase(Locale.ROOT) to it } }.toMap()

    fun save(context: Context, status: FavouriteTrainStatus) {
        prefs(context).edit { putString(key(status.number), FavouriteTrainStatusCodec.encode(status)) }
    }

    /** A failed lookup keeps the last known values, marked stale; a train never seen stays absent. */
    fun markStale(context: Context, number: String) {
        val previous = read(context, number) ?: return
        save(context, previous.copy(stale = true))
    }

    /** Drops the statuses of trains that are no longer favourites. */
    fun retainOnly(context: Context, numbers: Collection<String>) {
        val keep = numbers.mapTo(hashSetOf()) { key(it) }
        val stale = prefs(context).all.keys.filter { it.startsWith(PREFIX) && it !in keep }
        if (stale.isNotEmpty()) prefs(context).edit { stale.forEach(::remove) }
    }

    private const val PREFIX = "train_"
    private fun key(number: String) = PREFIX + number.trim().uppercase(Locale.ROOT)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Settings and page of each "I miei treni" widget instance. */
class TrainsWidgetConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_trains_widget_configuration_v1", Context.MODE_PRIVATE)

    fun read(widgetId: Int): TrainsWidgetSettings? = preferences.getString(key(widgetId), null)?.let(::decode)

    fun all(): List<Pair<Int, TrainsWidgetSettings>> = preferences.all.mapNotNull { (key, value) ->
        val widgetId = key.removePrefix("widget_").toIntOrNull() ?: return@mapNotNull null
        val settings = (value as? String)?.let(::decode) ?: return@mapNotNull null
        widgetId to settings
    }

    fun save(widgetId: Int, settings: TrainsWidgetSettings) {
        preferences.edit { putString(key(widgetId), encode(settings)) }
    }

    fun remove(widgetId: Int) {
        preferences.edit {
            remove(key(widgetId))
            remove(offsetKey(widgetId))
        }
    }

    fun rowOffset(widgetId: Int): Int = preferences.getInt(offsetKey(widgetId), 0)

    fun setRowOffset(widgetId: Int, offset: Int) {
        preferences.edit { putInt(offsetKey(widgetId), offset.coerceAtLeast(0)) }
    }

    private fun key(widgetId: Int) = "widget_$widgetId"
    private fun offsetKey(widgetId: Int) = "offset_$widgetId"

    companion object {
        internal fun encode(settings: TrainsWidgetSettings): String =
            listOf(settings.displayName, WidgetSettingsCodec.encodeTrains(settings))
                .joinToString("|") { java.util.Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }

        internal fun decode(value: String): TrainsWidgetSettings? = runCatching {
            val parts = value.split("|").map { String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8) }
            require(parts.size == 2)
            WidgetSettingsCodec.decodeTrains(parts[1], parts[0])
        }.getOrNull()
    }
}
