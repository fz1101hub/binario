package it.binario.app.journeys

import it.binario.app.data.core.LegMode
import it.binario.app.data.journey.GtfsJourneyPlannerSettings
import it.binario.app.data.reliability.ConnectionOdds
import it.binario.app.data.reliability.NetworkReliability
import it.binario.app.data.reliability.NetworkReliabilityStats
import it.binario.app.data.reliability.StopReliability
import java.time.Duration

/** The network statistics of one train leg of a journey, at the stop where it is left. */
data class LegNetworkReliability(val legIndex: Int, val stop: StopReliability)

/**
 * A change measured on the statistics: how often the arriving train ([afterLegIndex]) was at most [slackMinutes]
 * late at the change station, the minutes the planned change leaves beyond the change time.
 */
data class ConnectionNetworkReliability(
    val afterLegIndex: Int,
    val stationLabel: String,
    val slackMinutes: Long,
    val odds: ConnectionOdds
)

/**
 * A journey on the network statistics: every train leg, and the changes after them. [score] is the chance of the
 * whole journey working on a day of the window: every train runs and is on time where it is left, every change is
 * made.
 */
data class JourneyNetworkReliability(val legs: List<LegNetworkReliability>, val connections: List<ConnectionNetworkReliability>) {
    init {
        require(legs.isNotEmpty()) { "At least one train leg" }
    }

    val score: Double
        get() = legs.fold(1.0) { product, leg -> product * leg.stop.score } * connections.fold(1.0) { product, change -> product * change.odds.probability }

    /** The leg least likely to be on time (then the one with the higher mean delay, then the first). */
    val weakestLeg: LegNetworkReliability
        get() = legs.minWith(compareBy<LegNetworkReliability> { it.stop.score }.thenByDescending { it.stop.meanDelayMinutes })

    /** The change least likely to work, or null without a measured change. */
    val weakestConnection: ConnectionNetworkReliability?
        get() = connections.minByOrNull { it.odds.probability }

    /** The mean delay of the last train where it is left: the arrival of the journey for the ordering. */
    val meanDelayAtArrival: Double get() = legs.last().stop.meanDelayMinutes
}

/** Pure assessment of a [JourneyOptionRow] on [NetworkReliabilityStats]. */
object JourneyNetworkReliabilityAssessor {
    private val plannerSettings = GtfsJourneyPlannerSettings()

    /**
     * Null unless every train leg has at least [NetworkReliability.MIN_SAMPLES] samples where it is left, or is a
     * train cancelled on every day observed from where it is boarded (which makes the journey's score 0); buses,
     * metro and tram legs have no statistics and are not measured. A change after a train is measured on that train's
     * delays at the change station against the slack: planned departure of the next vehicle minus planned arrival,
     * minus the walk between them or else the change time the offline planner uses at that station.
     *
     * @param stationCode the ViaggiaTreno code of a leg's station id ("curated:S01700" → "S01700"), or null.
     */
    fun assess(
        row: JourneyOptionRow,
        stats: NetworkReliabilityStats,
        stationCode: (String) -> String?,
        transferMinutes: (String?) -> Int = plannerSettings::railTransferMinutes
    ): JourneyNetworkReliability? {
        val vehicles = row.legs.indices.filter { row.legs[it].mode != LegMode.WALK }
        val trains = vehicles.filter { row.legs[it].mode == LegMode.TRAIN }
        if (trains.isEmpty()) return null
        val matches = trains.associateWith { index ->
            val leg = row.legs[index]
            NetworkReliability.match(stats, leg.number, stationCode(leg.toStationId), boardingCode = stationCode(leg.fromStationId))
        }
        val legs = trains.map { index ->
            val leg = row.legs[index]
            val stop = matches[index]?.let { NetworkReliability.at(it.train, it.alightingIndex) }
                ?: NetworkReliability.cancelledEveryDay(stats, leg.number, stationCode(leg.fromStationId)).takeIf { matches[index] == null }
                ?: return null
            LegNetworkReliability(index, stop)
        }
        val connections = vehicles.zipWithNext().mapNotNull { (arriving, leaving) ->
            val match = matches[arriving] ?: return@mapNotNull null
            val arrivingLeg = row.legs[arriving]
            val walks = row.legs.subList(arriving + 1, leaving).filter { it.mode == LegMode.WALK }
            val needed = if (walks.isNotEmpty()) walks.sumOf { it.durationMinutes } else transferMinutes(stationCode(arrivingLeg.toStationId)).toLong()
            val slack = Duration.between(arrivingLeg.arrival, row.legs[leaving].departure).toMinutes() - needed
            NetworkReliability.connection(match.train, match.alightingIndex, slack.toDouble())?.let { odds ->
                ConnectionNetworkReliability(arriving, arrivingLeg.toLabel, slack, odds)
            }
        }
        return JourneyNetworkReliability(legs, connections)
    }
}
