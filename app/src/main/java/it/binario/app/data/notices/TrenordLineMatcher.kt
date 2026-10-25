package it.binario.app.data.notices

import it.binario.app.data.core.ServiceAlert
import it.binario.app.data.live.TrenordLine
import it.binario.app.data.live.TrenordLineNoticesAdapter

/**
 * Relates Trenord line notices to stations by name. A station matches a station of a line's description when
 * the normalised names are equal or one is the other followed by more words ("VARESE NORD" matches "VARESE",
 * "MILANO CENTRALE" matches "MILANO").
 */
object TrenordLineMatcher {
    /** A station named by more lines than this is a hub, like Milano. */
    private const val HUB_LINE_COUNT = 8

    fun matchedTokens(line: TrenordLine, stationNames: Collection<String>): Set<String> {
        val stations = stationNames.map { TrenordLineNoticesAdapter.normalizeStationName(it) }.filter { it.length >= 2 }
        return line.stationTokens.filterTo(linkedSetOf()) { token -> stations.any { matches(it, token) } }
    }

    private fun matches(station: String, token: String): Boolean =
        station == token || station.startsWith("$token ") || token.startsWith("$station ")

    /** Lines whose description names at least [minimum] distinct stations among [stationNames]. */
    fun linesNaming(lines: List<TrenordLine>, stationNames: Collection<String>, minimum: Int): List<TrenordLine> =
        lines.filter { matchedTokens(it, stationNames).size >= minimum }

    /**
     * Lines for a train: a Trenord service is related to the lines naming two of its stops (or, failing that, one
     * stop that is not a hub); any other service only to lines naming both its boarding and alighting stations.
     */
    fun linesForTrain(lines: List<TrenordLine>, context: TrainNoticeContext): List<TrenordLine> {
        if (!context.trenordOperated) return linesNaming(lines, context.endpoints, minimum = 2)
        val strong = linesNaming(lines, context.stationNames, minimum = 2)
        if (strong.isNotEmpty()) return strong
        val hubs = hubTokens(lines)
        return lines.filter { line -> matchedTokens(line, context.stationNames).any { it !in hubs } }
    }

    /** Notices for a station board: its lines' notices, or only network-wide ones at a hub such as Milano. */
    fun alertsForStation(lines: List<TrenordLine>, alerts: List<ServiceAlert>, stationName: String): List<ServiceAlert> {
        val matched = linesNaming(lines, listOf(stationName), minimum = 1).mapTo(hashSetOf()) { it.code }
        if (matched.isEmpty()) return emptyList()
        if (matched.size > HUB_LINE_COUNT) return alerts.filter { isNetworkWide(it, lines) }
        return alerts.filter { alert -> alert.affectedLines.any(matched::contains) }
    }

    fun isNetworkWide(alert: ServiceAlert, lines: List<TrenordLine>): Boolean =
        lines.size > 1 && alert.affectedLines.size >= maxOf(3, (lines.size + 2) / 3)

    private fun hubTokens(lines: List<TrenordLine>): Set<String> = lines.flatMap { it.stationTokens }
        .groupingBy { it }.eachCount()
        .filterValues { it > maxOf(2, lines.size / 3) }.keys

    /** Rough Trenord service area (Lombardy and its border stations), used only to avoid useless requests. */
    fun inTrenordArea(latitude: Double, longitude: Double): Boolean = latitude in 44.85..46.65 && longitude in 8.35..10.90
}
