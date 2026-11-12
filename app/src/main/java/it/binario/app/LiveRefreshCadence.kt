package it.binario.app

/** What the screen on show refreshes while it is visible: the data of this view model, the journeys, or both. */
internal enum class PeriodicRefresh {
    /** The direct trains of the active route (Partenze, a train detail). */
    ROUTE,
    /** The route results: the direct trains and the live legs of the journeys with changes. */
    ROUTE_AND_JOURNEYS,
    /** The detail of a journey with changes: the live legs of that journey. */
    JOURNEY,
    /** The results of a train-number search. */
    TRAIN_NUMBER,
    /** The board of a station, with the run opened from it. */
    STATION_BOARD
}

/**
 * When the visible data is asked for again. A refresh is due [INTERVAL_MILLIS] after the previous request of the same
 * data *started*, whatever its outcome: so coming back to the app, or moving between two screens, refreshes at once
 * when the data is older than that and otherwise only waits for the rest of the interval. The clock is any monotonic
 * millisecond count; a count that went backwards (a restarted clock) makes the refresh due.
 */
internal object LiveRefreshCadence {
    const val INTERVAL_MILLIS = 30_000L

    fun isDue(lastStartedAtMillis: Long?, nowMillis: Long): Boolean =
        lastStartedAtMillis == null || nowMillis < lastStartedAtMillis || nowMillis - lastStartedAtMillis >= INTERVAL_MILLIS

    /** How long the periodic loop sleeps before it asks again: the rest of the interval, the whole of it when due. */
    fun waitMillis(lastStartedAtMillis: Long?, nowMillis: Long): Long =
        if (isDue(lastStartedAtMillis, nowMillis)) INTERVAL_MILLIS
        else (INTERVAL_MILLIS - (nowMillis - lastStartedAtMillis!!)).coerceIn(1L, INTERVAL_MILLIS)
}
