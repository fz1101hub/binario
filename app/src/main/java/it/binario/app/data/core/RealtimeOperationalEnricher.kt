package it.binario.app.data.core

import it.binario.app.data.gtfs.ResolvedScheduledStop

/** Risultato tracciabile del raccordo fra feed realtime e orario statico della stessa fermata. */
data class EnrichedRealtimeStop(
    val update: RealtimeStopUpdate,
    val scheduledStop: ResolvedScheduledStop,
    val officialDelay: OfficialDelayReport?,
    val operationalDetection: OperationalDetection?
)

/**
 * Unisce un Trip Update al suo stop GTFS. Le previsioni restano previsioni:
 * `operationalDetection` viene creato esclusivamente quando `actualAt` è pubblicato.
 */
object RealtimeOperationalEnricher {
    fun enrich(update: RealtimeStopUpdate, scheduled: ResolvedScheduledStop): EnrichedRealtimeStop? {
        if (update.sourceTripId != scheduled.sourceTripId) return null
        if (update.sourceStopId != null && update.sourceStopId != scheduled.sourceStopId) return null
        if (update.stopSequence != null && update.stopSequence != scheduled.stopSequence) return null
        val resolved = update.copy(scheduledAt = scheduled.scheduledAt)
        val official = resolved.officialDelay?.let(::OfficialDelayReport)
        return EnrichedRealtimeStop(
            update = resolved,
            scheduledStop = scheduled,
            officialDelay = official,
            operationalDetection = resolved.asOperationalDetection(scheduled.locationLabel)
        )
    }
}
