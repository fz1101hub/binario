package it.binario.app.data.reliability

import it.binario.app.data.Station
import it.binario.app.data.TrainService
import java.time.LocalDate

/**
 * The «Affidabilità ultimi N giorni» card of a train detail: the train at the last stop shown (the destination of a
 * route search, else its terminus), how its delays there spread and the largest one, its days observed and cancelled
 * and the stop where its delay grows most. A train cancelled on every day observed has [arrival] without samples
 * ([StopReliability.cancelledEveryDay]) and no distribution.
 */
data class NetworkReliabilityDetail(
    /** The days the statistics cover ([NetworkReliabilityStats.coveredDays]): fewer than the window at first. */
    val windowDays: Int,
    val windowEnd: LocalDate,
    /** The stop [arrival] is measured at, as the detail names it. */
    val arrivalName: String,
    val arrival: StopReliability,
    val daysObserved: Int,
    val daysCancelled: Int,
    val worstStop: WorstStop?,
    /** Catalogue name of [worstStop]; the stop is left out when the catalogue does not know its code. */
    val worstStopName: String?,
    /** No delay, 1–5, 6–15, 16–30 and over 30 minutes at [arrival]; null when the file cannot tell them apart. */
    val distribution: DelayDistribution? = null,
    /** The largest delay at [arrival], when the file publishes it. */
    val maxDelayMinutes: Int? = null
) {
    companion object {
        /**
         * The card of [service], or null for a coach, without a train of the same number serving its first or last
         * stop, below [NetworkReliability.MIN_SAMPLES] at the stop measured or with fewer days observed.
         *
         * @param stationLookup the catalogue station of a published stop name.
         */
        fun of(
            stats: NetworkReliabilityStats,
            service: TrainService,
            stationLookup: (String) -> Station?,
            stations: NetworkReliabilityStations
        ): NetworkReliabilityDetail? {
            if (service.scheduledBus) return null
            val firstName = service.stops.firstOrNull()?.station ?: service.origin
            val lastName = service.stops.lastOrNull()?.station ?: service.destination
            val boardingCode = NetworkReliability.stationCode(stationLookup(firstName))
            val destinationCode = NetworkReliability.stationCode(stationLookup(lastName))
            val match = NetworkReliability.match(stats, service.number, destinationCode, boardingCode, originCode = boardingCode)
                ?: NetworkReliability.matchTerminus(stats, service.number, boardingCode)
                ?: return NetworkReliability.cancelledEveryDay(stats, service.number, boardingCode)?.let { cancelled ->
                    NetworkReliabilityDetail(stats.coveredDays, stats.windowEnd, lastName, cancelled, cancelled.daysObserved, cancelled.daysCancelled, null, null)
                }
            val train = match.train
            if (train.daysObserved < NetworkReliability.MIN_SAMPLES) return null
            val arrival = NetworkReliability.at(train, match.alightingIndex) ?: return null
            val arrivalName = if (arrival.stationCode == destinationCode) lastName else stations.name(arrival.stationCode) ?: service.destination
            val worst = NetworkReliability.worstStop(train)
            val worstName = worst?.let { stations.name(it.stationCode) }
            return NetworkReliabilityDetail(
                windowDays = stats.coveredDays,
                windowEnd = stats.windowEnd,
                arrivalName = arrivalName,
                arrival = arrival,
                daysObserved = train.daysObserved,
                daysCancelled = train.daysCancelled,
                worstStop = worst.takeIf { worstName != null },
                worstStopName = worstName,
                distribution = NetworkReliabilityAggregates.distribution(train, match.alightingIndex),
                maxDelayMinutes = train.maxDelayMinutes(match.alightingIndex)
            )
        }
    }
}
