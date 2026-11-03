package it.binario.app.ui.planner

import it.binario.app.data.Station
import it.binario.app.data.TrainService
import it.binario.app.data.reliability.NetworkReliability
import it.binario.app.data.reliability.NetworkReliabilityStations
import it.binario.app.data.reliability.NetworkReliabilityStats
import it.binario.app.data.reliability.StopReliability
import it.binario.app.journeys.JourneyNetworkReliability
import it.binario.app.journeys.JourneyNetworkReliabilityAssessor
import kotlin.math.roundToLong

/** The network statistics of a result row: its card line and its place in «Più affidabili». */
internal sealed interface EntryReliability {
    /** The chance of arriving on time on a day of the window ([StopReliability.score], [JourneyNetworkReliability.score]). */
    val score: Double
    /** Mean delay at the arrival, for ties. */
    val meanDelayMinutes: Double

    data class Train(val stop: StopReliability) : EntryReliability {
        override val score: Double get() = stop.score
        override val meanDelayMinutes: Double get() = stop.meanDelayMinutes
    }

    data class Journey(val journey: JourneyNetworkReliability) : EntryReliability {
        override val score: Double get() = journey.score
        override val meanDelayMinutes: Double get() = journey.meanDelayAtArrival
    }
}

/**
 * The network statistics of the route results: a direct train is measured at the user's destination stop, boarded
 * at the user's origin; a journey with changes on every train leg and change ([JourneyNetworkReliabilityAssessor]).
 * Rows without enough samples get none, and show nothing.
 */
internal object ResultsReliability {
    fun assess(
        entries: List<ResultEntry>,
        stats: NetworkReliabilityStats,
        origin: Station,
        destination: Station,
        stations: NetworkReliabilityStations
    ): Map<String, EntryReliability> {
        val originCode = NetworkReliability.stationCode(origin)
        val destinationCode = NetworkReliability.stationCode(destination)
        return buildMap {
            for (entry in entries) {
                val assessment = when (entry) {
                    is ResultEntry.Train -> train(entry.service, stats, originCode, destinationCode)?.let(EntryReliability::Train)
                    is ResultEntry.Journey -> JourneyNetworkReliabilityAssessor.assess(entry.row, stats, stations::code)?.let(EntryReliability::Journey)
                }
                assessment?.let { put(entry.key, it) }
            }
        }
    }

    /**
     * A direct train at [destinationCode], or, leaving from [originCode], cancelled on every day observed; none for a
     * coach or a result that already holds changes.
     */
    fun train(service: TrainService, stats: NetworkReliabilityStats, originCode: String?, destinationCode: String?): StopReliability? {
        if (service.scheduledBus || service.transferCount > 0) return null
        val match = NetworkReliability.match(stats, service.number, destinationCode, boardingCode = originCode)
            ?: return NetworkReliability.cancelledEveryDay(stats, service.number, originCode)
        return NetworkReliability.at(match.train, match.alightingIndex)
    }

    /**
     * «Più affidabili»: the rows with statistics first, the most likely to arrive on time first, then the lower mean
     * delay at the arrival; the rows without statistics after them. Scores equal to six decimals are a tie.
     */
    fun mostReliableFirst(reliability: Map<String, EntryReliability>): Comparator<ResultEntry> =
        compareBy<ResultEntry, Long?>(nullsLast()) { entry -> reliability[entry.key]?.let { -(it.score * 1_000_000).roundToLong() } }
            .thenBy(nullsLast()) { entry -> reliability[entry.key]?.meanDelayMinutes }
}
