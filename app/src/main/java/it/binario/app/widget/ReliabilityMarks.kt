package it.binario.app.widget

import android.content.Context
import it.binario.app.data.StationAutocompleteEngine
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.reliability.NetworkReliability
import it.binario.app.data.reliability.NetworkReliabilityMatch
import it.binario.app.data.reliability.NetworkReliabilityRepository
import it.binario.app.data.reliability.NetworkReliabilityStats
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

    /**
     * A favourite train ("I miei treni", the weekly digest), measured at its last stop: the train numbered [number]
     * that leaves (or calls at) [originCode]; without a code, the only train with that number that runs. Several
     * trains with the number and no code to tell them apart give none rather than a guess.
     */
    fun favourite(stats: NetworkReliabilityStats, number: String?, originCode: String?): ReliabilityMark? {
        if (number.isNullOrBlank()) return null
        NetworkReliability.matchTerminus(stats, number, originCode)?.let { return mark(stats, it) }
        if (originCode != null && NetworkReliability.isStationCode(originCode)) return null
        val single = stats.trainsNumbered(number).filter { it.stopCount > 1 }.singleOrNull() ?: return null
        return mark(stats, NetworkReliabilityMatch(single, 0, single.stopCount - 1))
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
 * first read of the process runs in the background and draws the widgets again when it is done.
 */
internal object WidgetReliabilityStats {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loading = AtomicBoolean(false)

    fun cachedOrLoad(context: Context, redraw: (Context) -> Unit): NetworkReliabilityStats? {
        if (!NetworkReliabilityRepository.enabled) return null
        NetworkReliabilityRepository.cached()?.let { return it }
        if (loading.compareAndSet(false, true)) {
            val application = context.applicationContext
            scope.launch {
                if (NetworkReliabilityRepository.load(application) != null) runCatching { redraw(application) }
            }
        }
        return null
    }
}
