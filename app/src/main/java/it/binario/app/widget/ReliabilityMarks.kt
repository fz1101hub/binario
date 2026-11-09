package it.binario.app.widget

import android.content.Context
import it.binario.app.R
import it.binario.app.ServiceMonitor
import it.binario.app.data.StationAutocompleteEngine
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.reliability.NetworkReliability
import it.binario.app.data.reliability.NetworkReliabilityMatch
import it.binario.app.data.reliability.NetworkReliabilityRepository
import it.binario.app.data.reliability.NetworkReliabilityStats
import it.binario.app.data.reliability.StopReliability
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The compact reliability of one train from the embedded network statistics, for the surfaces without room for the
 * full card (widgets, the weekly digest, the watch): [onTime] of [samples] days within the train's own on-time limit
 * ([limitMinutes]: 5 minutes for the high-speed and international categories, 15 for the others) at the stop it is
 * measured at. Only built from [NetworkReliability.MIN_SAMPLES] samples; a train cancelled on every day has none.
 */
data class ReliabilityMark(
    val onTime: Int,
    val samples: Int,
    val limitMinutes: Int,
    /** Samples at most 5 minutes late, the limit of the local history, whatever the category; null when unknown. */
    val onTimeWithin5: Int?,
    /** The days the statistics cover ([NetworkReliabilityStats.coveredDays]). */
    val days: Int
) {
    val percent: Int get() = if (samples > 0) (onTime * 100.0 / samples).roundToInt() else 0
    val within5Percent: Int? get() = onTimeWithin5?.takeIf { samples > 0 }?.let { (it * 100.0 / samples).roundToInt() }
}

/** Pure matching of the compact marks: a train of a route, a favourite train. */
object ReliabilityMarks {
    /**
     * A train of a route boarded at [originCode] and left at [destinationCode] (the route widget, the watch), measured
     * at the destination; none for a coach, a result with changes or without codes to check.
     */
    fun route(stats: NetworkReliabilityStats, number: String?, originCode: String?, destinationCode: String?): ReliabilityMark? {
        val match = NetworkReliability.match(stats, number, destinationCode, boardingCode = originCode) ?: return null
        return mark(stats, match)
    }

    /** A row of the route widget: [route] unless the row is a replacement coach. */
    fun widgetRow(stats: NetworkReliabilityStats, service: WidgetService, originCode: String?, destinationCode: String?): ReliabilityMark? =
        if (service.typeGroup == TrainTypeGroup.REPLACEMENT_BUS) null else route(stats, service.number, originCode, destinationCode)

    /**
     * A favourite train ("I miei treni", the weekly digest), measured at its last stop: the train numbered [number]
     * that leaves (or calls at) [originCode]; without a code, the only train with that number that runs. Several
     * trains with the number and no code to tell them apart give none rather than a guess.
     */
    fun favourite(stats: NetworkReliabilityStats, number: String?, originCode: String?): ReliabilityMark? =
        favouriteMatch(stats, number, originCode)?.let { mark(stats, it) }

    /**
     * The same favourite train as [favourite], at its last stop, as the result and detail lines show it («In orario 27
     * volte su 30 · ritardo medio 3 min», «Affidabilità dei miei treni»); null below [NetworkReliability.MIN_SAMPLES].
     */
    fun favouriteStop(stats: NetworkReliabilityStats, number: String?, originCode: String?): StopReliability? =
        favouriteMatch(stats, number, originCode)?.let { NetworkReliability.at(it.train, it.alightingIndex) }

    private fun favouriteMatch(stats: NetworkReliabilityStats, number: String?, originCode: String?): NetworkReliabilityMatch? {
        if (number.isNullOrBlank()) return null
        NetworkReliability.matchTerminus(stats, number, originCode)?.let { return it }
        if (originCode != null && NetworkReliability.isStationCode(originCode)) return null
        val single = stats.trainsNumbered(number).filter { it.stopCount > 1 }.singleOrNull() ?: return null
        return NetworkReliabilityMatch(single, 0, single.stopCount - 1)
    }

    /**
     * A row of "I miei treni" while it is drawn: the favourite saved with [origin] (its train's origin), whose code the
     * catalogue gives once it is read; before that a train saved with an origin gets no mark rather than a guess.
     */
    fun favouriteRow(stats: NetworkReliabilityStats, number: String, origin: String, codeOf: (String) -> String?, catalogueLoaded: Boolean): ReliabilityMark? {
        if (origin.isBlank()) return favourite(stats, number, null)
        if (!catalogueLoaded) return null
        return favourite(stats, number, codeOf(origin))
    }

    private fun mark(stats: NetworkReliabilityStats, match: NetworkReliabilityMatch): ReliabilityMark? {
        val stop = NetworkReliability.at(match.train, match.alightingIndex) ?: return null
        if (stop.cancelledEveryDay) return null
        val within5 = match.train.cumulativeAtLimit(match.alightingIndex, NetworkReliability.HIGH_SPEED_ON_TIME_MINUTES)
            ?: floor(match.train.countAtMost(match.alightingIndex, NetworkReliability.HIGH_SPEED_ON_TIME_MINUTES.toDouble()) + 1e-9).toInt()
        return ReliabilityMark(stop.onTime, stop.samples, stop.onTimeLimitMinutes, within5.coerceIn(0, stop.samples), stats.coveredDays)
    }
}

/**
 * The ViaggiaTreno codes of the station names a favourite train was saved with ("Roma Termini"): the offline station
 * catalogue, read once per process off the main thread, matched as the app's station search matches an exact name.
 */
object CatalogueStationCodes {
    private val mutex = Mutex()
    @Volatile private var engine: StationAutocompleteEngine? = null

    suspend fun code(context: Context, name: String): String? = NetworkReliability.stationCode(engine(context)?.findExact(name))

    /** The code of the origin each favourite train was saved with, by train number; null without an origin or a match. */
    suspend fun originCodes(context: Context, monitors: List<ServiceMonitor>): Map<String, String?> =
        monitors.associate { it.number to it.origin.takeIf(String::isNotBlank)?.let { origin -> code(context, origin) } }

    /** Whether the catalogue is already read in this process. */
    val loaded: Boolean get() = engine != null

    /** The code of [name] when the catalogue is already read in this process, else null (a widget being drawn). */
    fun cachedCode(name: String): String? = name.takeIf(String::isNotBlank)?.let { NetworkReliability.stationCode(engine?.findExact(it)) }

    internal suspend fun preload(context: Context) {
        engine(context)
    }

    private suspend fun engine(context: Context): StationAutocompleteEngine? {
        engine?.let { return it }
        val application = context.applicationContext
        return mutex.withLock {
            engine ?: withContext(Dispatchers.IO) {
                runCatching { EmbeddedCuratedRailStationCatalog({ path -> application.assets.open(path) }).load() }.getOrNull()
            }?.let(::StationAutocompleteEngine)?.also { engine = it }
        }
    }
}

/**
 * The statistics for drawing a widget, which happens on the main thread: the copy already read, or none while the
 * first read of the process runs in the background (with the station catalogue when [withCatalogue]) and draws the
 * widgets again when it is done. Nothing is requested over the network.
 */
internal object WidgetReliabilityStats {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadingStats = AtomicBoolean(false)
    private val loadingCatalogue = AtomicBoolean(false)

    fun cachedOrLoad(context: Context, withCatalogue: Boolean = false, redraw: (Context) -> Unit): NetworkReliabilityStats? {
        if (!NetworkReliabilityRepository.enabled) return null
        val application = context.applicationContext
        val cached = NetworkReliabilityRepository.cached()
        val loadCatalogue = withCatalogue && loadingCatalogue.compareAndSet(false, true)
        val loadStats = cached == null && loadingStats.compareAndSet(false, true)
        if (loadStats || loadCatalogue) {
            scope.launch {
                if (loadCatalogue) CatalogueStationCodes.preload(application)
                val stats = if (loadStats) NetworkReliabilityRepository.load(application) else NetworkReliabilityRepository.cached()
                if (stats != null) runCatching { redraw(application) }
            }
        }
        return cached
    }
}

/** The texts of a widget's reliability mark, resolved once per render. */
class WidgetReliabilityStrings(
    val locale: Locale,
    /** «%1$d%% in orario». */
    val mark: String,
    /** «In orario %1$d volte su %2$d (statistiche non ufficiali)», what TalkBack reads. */
    val spoken: (onTime: Int, samples: Int) -> String
) {
    companion object {
        fun from(context: Context): WidgetReliabilityStrings {
            val resources = context.resources
            return WidgetReliabilityStrings(
                locale = resources.configuration.locales[0],
                mark = resources.getString(R.string.widget_reliability_mark),
                spoken = { onTime, samples ->
                    resources.getString(
                        R.string.reliability_spoken,
                        resources.getQuantityString(R.plurals.reliability_on_time_count, onTime, onTime, samples)
                    )
                }
            )
        }
    }
}

/** A mark as a widget row shows it («83% in orario») and as TalkBack reads it. */
data class WidgetReliabilityText(val shown: String, val spoken: String) {
    companion object {
        fun of(mark: ReliabilityMark?, strings: WidgetReliabilityStrings): WidgetReliabilityText? = mark?.let {
            WidgetReliabilityText(String.format(strings.locale, strings.mark, it.percent), strings.spoken(it.onTime, it.samples))
        }
    }
}
