package it.binario.app.ui.departures

/**
 * The station board lists at most [MAX_ROWS] trains and says how many it leaves out, instead of dropping the rest
 * without a word (audit2 android-ui improvement).
 */
internal object StationBoardListCap {
    const val MAX_ROWS = 60

    fun <T> shown(entries: List<T>): List<T> = entries.take(MAX_ROWS)

    /** The trains past the cap: 0 when the whole list is shown. */
    fun leftOut(entries: List<*>): Int = (entries.size - MAX_ROWS).coerceAtLeast(0)
}
