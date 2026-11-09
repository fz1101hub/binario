package it.binario.app.ui.planner

import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainService
import it.binario.app.data.reliability.AlternativeCandidate
import it.binario.app.data.reliability.ReliableAlternatives
import it.binario.app.data.reliability.StopReliability
import java.time.LocalDateTime

/** «Più puntuale: …» under a direct train: the train of the same list to suggest instead, and its reliability. */
internal data class ResultAlternative(val target: ResultEntry.Train, val stop: StopReliability)

/**
 * «Alternativa più puntuale» of the route results ([ReliableAlternatives]) over the direct trains of a list, measured
 * where the user gets off ([ResultsReliability.assess]); journeys with changes and coaches take no part. Nothing without
 * the statistics of the rows (absent or disabled statistics, demonstration data).
 */
internal object ResultsAlternatives {
    /** A train counts as gone this long after its departure from the boarding station, as the widgets count it. */
    private const val DEPARTED_GRACE_MINUTES = 1L

    /** The alternative of each direct train of [entries] that has one, by the key of the train it is suggested for. */
    fun find(entries: List<ResultEntry>, reliability: Map<String, EntryReliability>, now: LocalDateTime): Map<String, ResultAlternative> {
        if (reliability.isEmpty()) return emptyMap()
        val trains = entries.filterIsInstance<ResultEntry.Train>().filter { it.service.transferCount == 0 && !it.service.scheduledBus }
        val byKey = trains.associateBy { it.key }
        val candidates = trains.map { train ->
            AlternativeCandidate(
                key = train.key,
                departure = train.departure,
                stop = (reliability[train.key] as? EntryReliability.Train)?.stop,
                stillToDepart = stillToDepart(train.service, train.departure, now)
            )
        }
        return buildMap {
            for ((key, alternative) in ReliableAlternatives.find(candidates)) {
                val target = byKey[alternative.key] ?: continue
                put(key, ResultAlternative(target, alternative.stop))
            }
        }
    }

    /**
     * The train can still be taken where the user boards: not cancelled, not seen passing the boarding station (its
     * first stop in the route results), and [now] (railway-local) before its departure plus [DEPARTED_GRACE_MINUTES];
     * the departure counts the delay only when the status is verified, so a timetable row is gone at its scheduled time
     * and a train late by unpublished minutes counts as gone once it is due. False without a departure time.
     */
    fun stillToDepart(service: TrainService, departure: LocalDateTime?, now: LocalDateTime): Boolean {
        if (service.status == ServiceStatus.CANCELLED) return false
        if (service.operationalStatusVerified && service.stops.firstOrNull()?.completed == true) return false
        val scheduled = departure ?: return false
        val delay = if (service.operationalStatusVerified) service.delayMinutes.coerceAtLeast(0).toLong() else 0L
        return now.isBefore(scheduled.plusMinutes(delay + DEPARTED_GRACE_MINUTES))
    }
}
