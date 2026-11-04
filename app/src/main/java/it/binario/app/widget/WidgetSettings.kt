package it.binario.app.widget

import it.binario.app.data.Direction
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Per-instance widget settings, modelled on the widget options of the "Orario Treni" app and kept
 * free of Android types so every rule below runs in JVM unit tests. They are persisted next to the
 * existing configuration of each instance (see [WidgetSettingsCodec]); configurations saved before
 * these settings existed decode to the `LEGACY` values, which reproduce the previous behaviour.
 */

/** OFF: only manual or in-app refreshes. WINDOWS: in the chosen days and time windows. ALWAYS: the pre-settings behaviour. */
enum class AutoRefreshMode { OFF, WINDOWS, ALWAYS }

enum class RefreshInterval(val minutes: Int) {
    MINUTES_15(15), MINUTES_30(30), MINUTES_60(60);

    val duration: Duration get() = Duration.ofMinutes(minutes.toLong())
}

/** Day presets of the configure screen; any other set of days is CUSTOM. */
enum class DayPreset(val days: Set<DayOfWeek>) {
    WEEKDAYS(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)),
    EVERY_DAY(DayOfWeek.entries.toSet()),
    CUSTOM(emptySet());

    companion object {
        fun of(days: Set<DayOfWeek>): DayPreset = entries.firstOrNull { it != CUSTOM && it.days == days } ?: CUSTOM
    }
}

/**
 * A daily refresh window [start, end). A window whose end is not after its start crosses midnight
 * (22:00–01:00) and belongs to the day it starts on; equal ends cover the whole day from [start].
 */
data class TimeWindow(val start: LocalTime, val end: LocalTime) {
    val crossesMidnight: Boolean get() = !end.isAfter(start)

    fun label(): String = "${start.format(HH_MM)}–${end.format(HH_MM)}"

    companion object {
        val DEFAULT_MORNING = TimeWindow(LocalTime.of(7, 0), LocalTime.of(9, 30))
        val DEFAULT_EVENING = TimeWindow(LocalTime.of(17, 0), LocalTime.of(19, 30))
    }
}

data class AutoRefreshSettings(
    val mode: AutoRefreshMode = AutoRefreshMode.OFF,
    val interval: RefreshInterval = RefreshInterval.MINUTES_30,
    val days: Set<DayOfWeek> = DayPreset.WEEKDAYS.days,
    /** One or two windows; more are ignored. */
    val windows: List<TimeWindow> = listOf(TimeWindow.DEFAULT_MORNING)
) {
    val effectiveWindows: List<TimeWindow> get() = windows.take(MAX_WINDOWS)

    /** True when an automatic refresh may run at [at]: always for ALWAYS, never for OFF. */
    fun isActiveAt(at: ZonedDateTime): Boolean = when (mode) {
        AutoRefreshMode.OFF -> false
        AutoRefreshMode.ALWAYS -> true
        AutoRefreshMode.WINDOWS -> effectiveWindows.any { window -> windowContains(window, at) }
    }

    /**
     * Earliest instant at or after [from] inside a window, or null when none starts within the next
     * eight days (no day selected, or OFF). Window starts are local times of [from]'s zone; a start
     * that falls in a daylight-saving gap moves forward like [ZonedDateTime.of] does.
     */
    fun nextActiveFrom(from: ZonedDateTime): ZonedDateTime? {
        if (mode == AutoRefreshMode.OFF) return null
        if (isActiveAt(from)) return from
        if (mode == AutoRefreshMode.ALWAYS) return from
        val date = from.toLocalDate()
        return (0L..LOOKAHEAD_DAYS).asSequence()
            .flatMap { offset ->
                val day = date.plusDays(offset)
                effectiveWindows.asSequence()
                    .filter { day.dayOfWeek in days }
                    .map { ZonedDateTime.of(day, it.start, from.zone) }
            }
            .filter { it.isAfter(from) }
            .minOrNull()
    }

    /**
     * When the next automatic refresh is due: [interval] after [lastRefreshEpochMillis] (any refresh,
     * manual ones included), moved to the next window when that instant falls outside every window.
     */
    fun nextDueAt(lastRefreshEpochMillis: Long, now: ZonedDateTime): ZonedDateTime? {
        val ready = if (lastRefreshEpochMillis <= 0L) now
        else ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(lastRefreshEpochMillis).plus(interval.duration), now.zone)
        return nextActiveFrom(maxOf(now, ready))
    }

    fun isDue(lastRefreshEpochMillis: Long, now: ZonedDateTime): Boolean = nextDueAt(lastRefreshEpochMillis, now)?.let { !it.isAfter(now) } == true

    private fun windowContains(window: TimeWindow, at: ZonedDateTime): Boolean {
        val time = at.toLocalTime()
        val day = at.toLocalDate()
        return if (!window.crossesMidnight) {
            day.dayOfWeek in days && !time.isBefore(window.start) && time.isBefore(window.end)
        } else {
            (day.dayOfWeek in days && !time.isBefore(window.start)) ||
                (day.minusDays(1).dayOfWeek in days && time.isBefore(window.end))
        }
    }

    companion object {
        const val MAX_WINDOWS = 2
        private const val LOOKAHEAD_DAYS = 8L

        /** The refresh of widgets configured before the settings existed: every fifteen minutes, all day. */
        val LEGACY = AutoRefreshSettings(AutoRefreshMode.ALWAYS, RefreshInterval.MINUTES_15, DayPreset.EVERY_DAY.days, emptyList())
    }
}

/**
 * Automatic direction of a route widget: outbound until [returnFrom], return from then until midnight.
 * A manual switch overrides the automatic direction until the next boundary (midnight or [returnFrom]).
 */
object WidgetDirectionSchedule {
    fun automaticDirection(returnFrom: LocalTime, now: ZonedDateTime): Direction =
        if (now.toLocalTime().isBefore(returnFrom)) Direction.OUTBOUND else Direction.RETURN

    /** Next instant at which the automatic direction changes. */
    fun nextBoundary(returnFrom: LocalTime, now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate()
        val swap = ZonedDateTime.of(today, returnFrom, now.zone)
        return if (now.isBefore(swap)) swap else startOfDay(today.plusDays(1), now)
    }

    /**
     * The direction to show. [manualDirection] applies only while [manualUntilEpochMillis] is in the
     * future; without an automatic swap the stored direction is kept as before.
     */
    fun effectiveDirection(
        returnFrom: LocalTime?,
        storedDirection: Direction,
        manualDirection: Direction?,
        manualUntilEpochMillis: Long,
        now: ZonedDateTime
    ): Direction {
        if (returnFrom == null) return storedDirection
        if (manualDirection != null && now.toInstant().toEpochMilli() < manualUntilEpochMillis) return manualDirection
        return automaticDirection(returnFrom, now)
    }

    private fun startOfDay(date: LocalDate, now: ZonedDateTime): ZonedDateTime = date.atStartOfDay(now.zone)
}

/** How far ahead the route widget lists trains. NEXT is the pre-settings list: what the source returns now. */
enum class RouteTimeRange(val hours: Long?) {
    NEXT(null), NEXT_3H(3), NEXT_6H(6), WHOLE_DAY(null)
}

/** Train type groups the route and station widgets can hide; a train of no known group is never hidden. */
enum class TrainTypeGroup { HIGH_SPEED, INTERCITY_NIGHT, REGIONAL_SUBURBAN, ITALO, REPLACEMENT_BUS, INTERNATIONAL }

data class RouteWidgetSettings(
    val autoRefresh: AutoRefreshSettings = AutoRefreshSettings(),
    /** "Mostra il ritorno dalle HH:mm"; null keeps the manual direction only. */
    val returnFrom: LocalTime? = null,
    val excludedTypes: Set<TrainTypeGroup> = emptySet(),
    /** Keeps a run listed until its arrival at the destination, marked "In viaggio". */
    val untilArrival: Boolean = false,
    val showPlatform: Boolean = true,
    val showTrainName: Boolean = true,
    val timeRange: RouteTimeRange = RouteTimeRange.NEXT,
    /** «83% in orario» after the train, from the embedded network statistics (at least five samples). */
    val showReliability: Boolean = true
) {
    companion object {
        val LEGACY = RouteWidgetSettings(autoRefresh = AutoRefreshSettings.LEGACY)
    }
}

data class StationWidgetSettings(
    val displayName: String = "",
    val autoRefresh: AutoRefreshSettings = AutoRefreshSettings(),
    val excludedTypes: Set<TrainTypeGroup> = emptySet(),
    val showPlatform: Boolean = true,
    val showTrainName: Boolean = true,
    /** Shows the "Più vicina" button, which reads one location fix only when tapped. */
    val showNearestButton: Boolean = false
) {
    companion object {
        val LEGACY = StationWidgetSettings(autoRefresh = AutoRefreshSettings.LEGACY)
    }
}

enum class TrainsWidgetInfo { NEXT_STATION, LAST_DETECTION }

enum class TrainsWidgetSort { DEPARTURE, NAME }

data class TrainsWidgetSettings(
    val displayName: String = "",
    val info: TrainsWidgetInfo = TrainsWidgetInfo.NEXT_STATION,
    /** Hides trains that have not left yet or have already arrived. */
    val hideNotRunning: Boolean = false,
    val sort: TrainsWidgetSort = TrainsWidgetSort.DEPARTURE,
    val autoRefresh: AutoRefreshSettings = AutoRefreshSettings(),
    val showPlatform: Boolean = true,
    /** «83% in orario» in the detail line, from the embedded network statistics (at least five samples). */
    val showReliability: Boolean = true
)

/**
 * Compact, versioned `key=value;…` text for the settings. Unknown keys are ignored and missing or
 * unreadable values fall back to the defaults of a new widget, so a later version can add keys.
 */
object WidgetSettingsCodec {
    private const val VERSION = "1"

    fun encodeRoute(value: RouteWidgetSettings): String = encode(
        autoRefreshPairs(value.autoRefresh) + listOf(
            "sw" to value.returnFrom?.format(HH_MM).orEmpty(),
            "ex" to value.excludedTypes.sortedBy { it.ordinal }.joinToString(",") { it.name },
            "ua" to flag(value.untilArrival),
            "sp" to flag(value.showPlatform),
            "sn" to flag(value.showTrainName),
            "tr" to value.timeRange.name,
            "rl" to flag(value.showReliability)
        )
    )

    fun decodeRoute(value: String): RouteWidgetSettings {
        val map = decode(value)
        val defaults = RouteWidgetSettings()
        return RouteWidgetSettings(
            autoRefresh = autoRefresh(map),
            returnFrom = map["sw"]?.let(::parseTime),
            excludedTypes = types(map["ex"]),
            untilArrival = map.flag("ua", defaults.untilArrival),
            showPlatform = map.flag("sp", defaults.showPlatform),
            showTrainName = map.flag("sn", defaults.showTrainName),
            timeRange = enumOr(map["tr"], defaults.timeRange),
            showReliability = map.flag("rl", defaults.showReliability)
        )
    }

    fun encodeStation(value: StationWidgetSettings): String = encode(
        autoRefreshPairs(value.autoRefresh) + listOf(
            "ex" to value.excludedTypes.sortedBy { it.ordinal }.joinToString(",") { it.name },
            "sp" to flag(value.showPlatform),
            "sn" to flag(value.showTrainName),
            "nb" to flag(value.showNearestButton)
        )
    )

    /** The display name is stored as its own field of the station configuration, not in this text. */
    fun decodeStation(value: String, displayName: String): StationWidgetSettings {
        val map = decode(value)
        val defaults = StationWidgetSettings()
        return StationWidgetSettings(
            displayName = displayName,
            autoRefresh = autoRefresh(map),
            excludedTypes = types(map["ex"]),
            showPlatform = map.flag("sp", defaults.showPlatform),
            showTrainName = map.flag("sn", defaults.showTrainName),
            showNearestButton = map.flag("nb", defaults.showNearestButton)
        )
    }

    fun encodeTrains(value: TrainsWidgetSettings): String = encode(
        autoRefreshPairs(value.autoRefresh) + listOf(
            "in" to value.info.name,
            "hn" to flag(value.hideNotRunning),
            "so" to value.sort.name,
            "sp" to flag(value.showPlatform),
            "rl" to flag(value.showReliability)
        )
    )

    fun decodeTrains(value: String, displayName: String): TrainsWidgetSettings {
        val map = decode(value)
        val defaults = TrainsWidgetSettings()
        return TrainsWidgetSettings(
            displayName = displayName,
            info = enumOr(map["in"], defaults.info),
            hideNotRunning = map.flag("hn", defaults.hideNotRunning),
            sort = enumOr(map["so"], defaults.sort),
            autoRefresh = autoRefresh(map),
            showPlatform = map.flag("sp", defaults.showPlatform),
            showReliability = map.flag("rl", defaults.showReliability)
        )
    }

    private fun autoRefreshPairs(value: AutoRefreshSettings): List<Pair<String, String>> = listOf(
        "ar" to value.mode.name,
        "ai" to value.interval.minutes.toString(),
        "ad" to value.days.sortedBy { it.value }.joinToString(",") { it.value.toString() },
        "aw" to value.effectiveWindows.joinToString(",") { "${it.start.format(HH_MM)}-${it.end.format(HH_MM)}" }
    )

    private fun autoRefresh(map: Map<String, String>): AutoRefreshSettings {
        val defaults = AutoRefreshSettings()
        val days = map["ad"]?.let { raw ->
            raw.split(",").mapNotNull { it.trim().toIntOrNull()?.takeIf { day -> day in 1..7 }?.let(DayOfWeek::of) }.toSet()
        } ?: defaults.days
        val windows = map["aw"]?.let { raw ->
            raw.split(",").mapNotNull { part ->
                val bounds = part.split("-")
                if (bounds.size != 2) null else {
                    val start = parseTime(bounds[0]) ?: return@mapNotNull null
                    val end = parseTime(bounds[1]) ?: return@mapNotNull null
                    TimeWindow(start, end)
                }
            }.take(AutoRefreshSettings.MAX_WINDOWS)
        } ?: defaults.windows
        return AutoRefreshSettings(
            mode = enumOr(map["ar"], defaults.mode),
            interval = RefreshInterval.entries.firstOrNull { it.minutes.toString() == map["ai"] } ?: defaults.interval,
            days = days,
            windows = windows
        )
    }

    private fun types(raw: String?): Set<TrainTypeGroup> = raw.orEmpty().split(",")
        .mapNotNull { name -> TrainTypeGroup.entries.firstOrNull { it.name == name.trim() } }
        .toSet()

    private fun encode(pairs: List<Pair<String, String>>): String =
        (listOf("v" to VERSION) + pairs).joinToString(";") { (key, value) -> "$key=$value" }

    private fun decode(value: String): Map<String, String> = value.split(";").mapNotNull { pair ->
        val index = pair.indexOf('=')
        if (index <= 0) null else pair.substring(0, index).trim() to pair.substring(index + 1).trim()
    }.toMap()

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    private fun Map<String, String>.flag(key: String, default: Boolean): Boolean = when (this[key]) {
        "1" -> true
        "0" -> false
        else -> default
    }

    private inline fun <reified E : Enum<E>> enumOr(raw: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == raw } ?: default

    private fun parseTime(raw: String): LocalTime? = runCatching { LocalTime.parse(raw.trim(), HH_MM) }.getOrNull()
}

internal val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
