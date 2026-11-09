package it.binario.app

import it.binario.app.data.reliability.NetworkReliabilityStats
import it.binario.app.data.reliability.StopReliability
import it.binario.app.widget.ReliabilityMarks
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** The runs of one train recorded on this phone over some days: how many, how many on time, cancelled. */
data class LocalPunctuality(val runs: Int, val onTime: Int, val cancelled: Int) {
    val onTimePercent: Int get() = if (runs > 0) (onTime * 100.0 / runs).roundToInt() else 0
}

/**
 * «In peggioramento» / «In miglioramento»: the runs of a train recorded on this phone in the last
 * [FavouriteTrainPunctuality.TREND_DAYS] days ([recent]) against the same span before them ([earlier]).
 */
data class PunctualityTrend(val recent: LocalPunctuality, val earlier: LocalPunctuality) {
    /** Exact comparison of the two on-time shares (never equal: a trend needs a gap). */
    val better: Boolean get() = recent.onTime.toLong() * earlier.runs > earlier.onTime.toLong() * recent.runs
}

/** One favourite train of «Affidabilità dei miei treni» in Per te. */
data class FavouriteTrainReliability(
    val monitor: ServiceMonitor,
    /**
     * At its last stop in the embedded network statistics, with its own 5- or 15-minute limit and at least
     * [it.binario.app.data.reliability.NetworkReliability.MIN_SAMPLES] samples; null without statistics or a match.
     */
    val network: StopReliability?,
    /** The runs recorded on this phone in the last [FavouriteTrainPunctuality.PERSONAL_DAYS] days; null without any. */
    val personal: LocalPunctuality?,
    val trend: PunctualityTrend?
) {
    val hasData: Boolean get() = network != null || personal != null
}

/**
 * Pure rules of the punctuality of the favourite trains, shared by the weekly digest and «Affidabilità dei miei treni»:
 * the runs recorded on this phone between two days (on time within the history's own
 * [PunctualityHistory.ON_TIME_MINUTES]), the trend of the last weeks and the train's network statistics at its last stop
 * ([ReliabilityMarks.favouriteStop]).
 */
object FavouriteTrainPunctuality {
    /** At most this many favourite trains are listed, in the order of «I miei treni». */
    const val MAX_ROWS = 8
    /** The personal record covers today and the days before it, as the network window does. */
    const val PERSONAL_DAYS = 30L
    /** The trend compares the last four weeks (today included) with the four weeks before them … */
    const val TREND_DAYS = 28L
    /** … each with at least this many runs recorded … */
    const val MIN_TREND_RUNS = 4
    /** … and on-time shares at least this many percentage points apart. */
    const val MIN_TREND_POINTS = 15

    /** The runs of [trainNumber] recorded from [from] to [to] (both included), or null when there is none. */
    fun local(history: List<PunctualityObservation>, trainNumber: String, from: LocalDate, to: LocalDate): LocalPunctuality? {
        val key = PunctualityHistory.trainKey(trainNumber)
        val runs = history.filter { it.trainNumber == key && !it.serviceDate.isBefore(from) && !it.serviceDate.isAfter(to) }
        if (runs.isEmpty()) return null
        return LocalPunctuality(
            runs = runs.size,
            onTime = runs.count { !it.cancelled && it.delayMinutes <= PunctualityHistory.ON_TIME_MINUTES },
            cancelled = runs.count { it.cancelled }
        )
    }

    /**
     * The trend of [trainNumber] at [today]: the last [TREND_DAYS] days against the [TREND_DAYS] before them, each with
     * at least [MIN_TREND_RUNS] runs, when their on-time shares are at least [MIN_TREND_POINTS] points apart (exact
     * arithmetic: 7 of 10 against 5 of 10 is 20 points); else null.
     */
    fun trend(history: List<PunctualityObservation>, trainNumber: String, today: LocalDate): PunctualityTrend? {
        val recentStart = today.minusDays(TREND_DAYS - 1)
        val recent = local(history, trainNumber, recentStart, today)?.takeIf { it.runs >= MIN_TREND_RUNS } ?: return null
        val earlier = local(history, trainNumber, recentStart.minusDays(TREND_DAYS), recentStart.minusDays(1))
            ?.takeIf { it.runs >= MIN_TREND_RUNS } ?: return null
        // |recent.onTime / recent.runs − earlier.onTime / earlier.runs| × 100 ≥ MIN_TREND_POINTS, without rounding.
        val gap = abs(recent.onTime.toLong() * earlier.runs - earlier.onTime.toLong() * recent.runs) * 100
        return if (gap >= MIN_TREND_POINTS.toLong() * recent.runs * earlier.runs) PunctualityTrend(recent, earlier) else null
    }

    /**
     * The rows of «Affidabilità dei miei treni» at [today]: the first [MAX_ROWS] of [monitors], in their order, each with
     * its network figures when [stats] are available ([originCode] gives the ViaggiaTreno code of the saved origin, null
     * when unknown) and its own records in [history]. A row may have no data at all.
     */
    fun rows(
        monitors: List<ServiceMonitor>,
        history: List<PunctualityObservation>,
        today: LocalDate,
        stats: NetworkReliabilityStats?,
        originCode: (ServiceMonitor) -> String?
    ): List<FavouriteTrainReliability> = monitors.take(MAX_ROWS).map { monitor ->
        FavouriteTrainReliability(
            monitor = monitor,
            network = stats?.let { ReliabilityMarks.favouriteStop(it, monitor.number, originCode(monitor)) },
            personal = local(history, monitor.number, today.minusDays(PERSONAL_DAYS - 1), today),
            trend = trend(history, monitor.number, today)
        )
    }
}
