package it.binario.app

import android.content.Context
import androidx.core.content.edit
import it.binario.app.data.TrainService
import java.text.Collator
import java.time.LocalTime
import java.util.Locale

/**
 * A train saved with the heart of the train detail ("I miei treni", formerly only "treni monitorati").
 * [origin] and [scheduledDeparture] identify the run among the trains of the day that share the
 * number; [customName] is the user's name ("Il mio treno delle 7:42"). Trains saved by earlier
 * versions have only number and label.
 */
data class ServiceMonitor(
    val number: String,
    val label: String,
    val customName: String = "",
    val origin: String = "",
    val scheduledDeparture: String = ""
) {
    /** The custom name, or null when the user gave none. */
    val displayName: String? get() = customName.trim().ifBlank { null }
}

/**
 * Local storage of the favourite trains, in the same preferences and key the monitored trains have
 * always used, so that the trains saved by earlier versions stay and the format only grows.
 */
class FavouriteTrainStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun all(): List<ServiceMonitor> = FavouriteTrainCodec.decode(preferences.getString(KEY, "").orEmpty())

    /**
     * Saves [service] (newest first) or, when it is already saved, removes it: the heart of the detail. A full list
     * refuses the train ([FavouriteToggle.Full]) and keeps every saved one.
     */
    fun toggle(service: TrainService): FavouriteToggle {
        val outcome = FavouriteTrains.toggled(all(), service)
        if (outcome !is FavouriteToggle.Full) save(outcome.monitors)
        return outcome
    }

    fun remove(number: String): List<ServiceMonitor> = save(all().filterNot { it.number.equals(number, ignoreCase = true) })

    fun rename(number: String, customName: String): List<ServiceMonitor> = save(FavouriteTrains.rename(all(), number, customName))

    fun replace(monitors: List<ServiceMonitor>): List<ServiceMonitor> = save(FavouriteTrains.normalized(monitors))

    private fun save(monitors: List<ServiceMonitor>): List<ServiceMonitor> {
        preferences.edit { putString(KEY, FavouriteTrainCodec.encode(monitors)) }
        return monitors
    }

    companion object {
        /** Shared with the route store of the view model, which wrote the monitored trains before. */
        internal const val PREFERENCES = "binario_routes_v3"
        internal const val KEY = "monitored_services"
    }
}

/** What the heart of the train detail did to «I miei treni»; [monitors] is the list afterwards. */
sealed interface FavouriteToggle {
    val monitors: List<ServiceMonitor>

    data class Added(override val monitors: List<ServiceMonitor>) : FavouriteToggle
    data class Removed(override val monitors: List<ServiceMonitor>) : FavouriteToggle
    /** [FavouriteTrains.MAX] trains are saved already: nothing changes, and the user is told to remove one first. */
    data class Full(override val monitors: List<ServiceMonitor>) : FavouriteToggle
}

/** Pure rules of the favourite trains, shared by the app, the backup and the widget. */
object FavouriteTrains {
    const val MAX = 12
    const val MAX_NAME_LENGTH = 40

    fun monitorOf(service: TrainService): ServiceMonitor = ServiceMonitor(
        number = service.number,
        label = "${service.displayOperator} · ${service.destination}",
        origin = service.origin,
        scheduledDeparture = service.scheduledDeparture.trim().takeIf { runCatching { LocalTime.parse(it) }.isSuccess }.orEmpty()
    )

    /**
     * The heart of the detail on [current]: removes [service] when it is saved, else saves it first. With [MAX] trains
     * saved the new one is refused rather than dropping the oldest, which would lose its name without a word.
     */
    fun toggled(current: List<ServiceMonitor>, service: TrainService): FavouriteToggle {
        val saved = current.any { it.number.equals(service.number, ignoreCase = true) }
        return when {
            saved -> FavouriteToggle.Removed(current.filterNot { it.number.equals(service.number, ignoreCase = true) })
            current.size >= MAX -> FavouriteToggle.Full(current)
            else -> FavouriteToggle.Added(listOf(monitorOf(service)) + current)
        }
    }

    fun rename(monitors: List<ServiceMonitor>, number: String, customName: String): List<ServiceMonitor> {
        val name = customName.trim().take(MAX_NAME_LENGTH)
        return monitors.map { if (it.number.equals(number, ignoreCase = true)) it.copy(customName = name) else it }
    }

    fun normalized(monitors: List<ServiceMonitor>): List<ServiceMonitor> =
        monitors.filter { it.number.isNotBlank() }.distinctBy { it.number.uppercase(Locale.ROOT) }.take(MAX)
}

/**
 * `number,label[,customName,origin,departure]` records separated by `;`, every field Base64 encoded.
 * Two-field records are those of earlier versions; unreadable records are skipped one by one.
 */
object FavouriteTrainCodec {
    fun encode(monitors: List<ServiceMonitor>): String = monitors.joinToString(";") { monitor ->
        listOf(monitor.number, monitor.label, monitor.customName, monitor.origin, monitor.scheduledDeparture)
            .joinToString(",") { PreferenceValueCodec.encode(it) }
    }

    fun decode(raw: String): List<ServiceMonitor> = raw.split(";").filter(String::isNotBlank).mapNotNull { entry ->
        val parts = entry.split(",").map { PreferenceValueCodec.decode(it) ?: return@mapNotNull null }
        when (parts.size) {
            2 -> ServiceMonitor(parts[0], parts[1])
            5 -> ServiceMonitor(parts[0], parts[1], parts[2], parts[3], parts[4])
            else -> null
        }
    }.filter { it.number.isNotBlank() }
}

/** Order of the favourite trains in "Per te" and in the "I miei treni" widget. */
object FavouriteTrainOrdering {
    enum class Sort { DEPARTURE, NAME }

    /** The custom name, else "Treno <number>" as the caller formats it. */
    fun title(monitor: ServiceMonitor, trainLabel: (String) -> String): String = monitor.displayName ?: trainLabel(monitor.number)

    /**
     * DEPARTURE: by scheduled departure (trains without one last), then by title. NAME: alphabetical by
     * title with the locale's collation (accents and case ignored), then by departure.
     */
    fun sorted(monitors: List<ServiceMonitor>, sort: Sort, locale: Locale, trainLabel: (String) -> String): List<ServiceMonitor> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        val byTitle = Comparator<ServiceMonitor> { left, right -> collator.compare(title(left, trainLabel), title(right, trainLabel)) }
        val byDeparture = compareBy<ServiceMonitor, LocalTime?>(nullsLast()) { departure(it) }
        val comparator = when (sort) {
            Sort.DEPARTURE -> byDeparture.then(byTitle).thenBy { it.number }
            Sort.NAME -> byTitle.then(byDeparture).thenBy { it.number }
        }
        return monitors.sortedWith(comparator)
    }

    private fun departure(monitor: ServiceMonitor): LocalTime? = runCatching { LocalTime.parse(monitor.scheduledDeparture.trim()) }.getOrNull()
}

/**
 * Picks today's run of a favourite train among the matches of a train-number search: the only
 * match, else the one from the saved origin (and departure), else none rather than a guess.
 */
object FavouriteTrainMatcher {
    fun pick(services: List<TrainService>, monitor: ServiceMonitor): TrainService? {
        val sameNumber = services.filter { it.number.equals(monitor.number, ignoreCase = true) }.ifEmpty { services }
        if (sameNumber.size == 1) return sameNumber.single()
        val origin = normalize(monitor.origin)
        if (origin.isEmpty()) return null
        val fromOrigin = sameNumber.filter { normalize(it.origin) == origin }
        return fromOrigin.singleOrNull()
            ?: fromOrigin.firstOrNull { monitor.scheduledDeparture.isNotBlank() && it.scheduledDeparture.trim() == monitor.scheduledDeparture.trim() }
    }

    /** True when [service] runs from the saved origin of [monitor] (false when no origin was saved). */
    fun isFromOrigin(service: TrainService, monitor: ServiceMonitor): Boolean {
        val origin = normalize(monitor.origin)
        return origin.isNotEmpty() && normalize(service.origin) == origin
    }

    private fun normalize(value: String): String = value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
}
