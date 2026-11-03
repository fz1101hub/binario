package it.binario.app.notifications

import java.time.Instant

/** Where the progress behind a stop countdown comes from, best first. */
enum class StopProgressSource {
    /** A stop whose passage a source confirmed, or the train's last detected station. */
    LIVE,

    /** The device position projected on the train's route (GPS alarm or "Stima dalla tua posizione"), on the device only. */
    POSITION,

    /** Expected times (live delay) or scheduled times compared with the clock. */
    SCHEDULED
}

/** One stop of the countdown timeline, in the train's order. */
data class CountdownStop(
    val name: String,
    /** "H:mm" as the app shows the stop: expected with live data, scheduled otherwise; null when unknown. */
    val timeLabel: String?,
    /** The same time as an instant, for the comparison with the clock; null when unknown. */
    val at: Instant?,
    /** A stop the train no longer serves today (route change): it is never counted. */
    val skipped: Boolean = false
)

/**
 * "Scendi tra N fermate": [stopsLeft] stops are still to be served up to and including [alightStop]; 1 means the
 * traveller gets off at the next stop ("Scendi alla prossima fermata").
 */
data class StopCountdown(
    val stopsLeft: Int,
    /** Index of the next stop in the timeline the countdown was built from. */
    val nextIndex: Int,
    val nextStop: String,
    val nextStopTime: String?,
    val nextStopAt: Instant?,
    val alightStop: String,
    val source: StopProgressSource
) {
    val alightAtNextStop: Boolean get() = stopsLeft <= 1
}

/**
 * Pure rules of the stop countdown shown in the followed-trip notification and by the GPS arrival alarm. The progress
 * is the last stop the train has reached: confirmed by the live source when it says so, else derived from the device
 * position on the route, else from the expected (or scheduled) times. Live data and the position can only move the
 * train forward of each other, never back; the timetable is used only when neither is known, because a late train
 * would otherwise be counted ahead of where it is.
 */
object StopCountdownCalculator {
    /**
     * @param stops the train's stops in order.
     * @param board index of the stop where the traveller boards: before the train leaves it there is no countdown.
     * @param alight index of the stop where the traveller gets off: once reached there is no countdown either.
     * @param livePassed last stop the live source confirmed (or detected), null when the source says nothing about progress.
     * @param positionPassed last stop reached according to a fresh device position on the route, null without one.
     */
    fun countdown(
        stops: List<CountdownStop>,
        board: Int,
        alight: Int,
        now: Instant,
        livePassed: Int? = null,
        positionPassed: Int? = null
    ): StopCountdown? {
        if (board < 0 || alight <= board || alight > stops.lastIndex) return null
        val (passed, source) = progress(stops, now, livePassed, positionPassed) ?: return null
        if (passed < board || passed >= alight) return null
        val next = (passed + 1..alight).firstOrNull { !stops[it].skipped } ?: return null
        val left = (next..alight).count { !stops[it].skipped }
        val stop = stops[next]
        return StopCountdown(left, next, stop.name, stop.timeLabel, stop.at, stops[alight].name, source)
    }

    private fun progress(stops: List<CountdownStop>, now: Instant, livePassed: Int?, positionPassed: Int?): Pair<Int, StopProgressSource>? {
        val live = livePassed?.takeIf { it in stops.indices }
        val position = positionPassed?.takeIf { it in stops.indices }
        return when {
            live != null && position != null -> if (position > live) position to StopProgressSource.POSITION else live to StopProgressSource.LIVE
            live != null -> live to StopProgressSource.LIVE
            position != null -> position to StopProgressSource.POSITION
            else -> stops.indices.lastOrNull { index -> stops[index].at?.let { !it.isAfter(now) } == true }
                ?.let { it to StopProgressSource.SCHEDULED }
        }
    }
}
