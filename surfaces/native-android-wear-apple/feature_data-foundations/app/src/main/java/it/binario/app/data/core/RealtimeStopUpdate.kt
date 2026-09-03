package it.binario.app.data.core

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/** Indica se il tempo realtime è una previsione o un evento effettivamente osservato dal provider. */
enum class RealtimeTimeKind {
    PREDICTED,
    ACTUAL,
    UNKNOWN
}

/**
 * Forma normalizzata di un aggiornamento GTFS-Realtime per una fermata.
 * `actualAt` resta nullo finché la fonte non pubblica un evento effettivo:
 * un `delay` o un tempo previsto non è automaticamente un passaggio confermato.
 */
data class RealtimeStopUpdate(
    val runId: TrainRunId,
    val sourceTripId: String,
    val sourceStopId: String? = null,
    val stopSequence: Int? = null,
    val scheduledAt: ZonedDateTime? = null,
    val predictedAt: SourcedValue<ZonedDateTime>? = null,
    val actualAt: SourcedValue<ZonedDateTime>? = null,
    val officialDelay: SourcedValue<Duration>? = null,
    val observedAt: Instant,
    val source: SourceRef
) {
    init {
        require(sourceTripId.isNotBlank()) { "L'identificativo della corsa sorgente è obbligatorio" }
        require(sourceStopId != null || stopSequence != null) { "Serve stop_id o stop_sequence" }
    }

    fun asOperationalDetection(locationLabel: String, stationId: StationId? = null): OperationalDetection? {
        val scheduled = scheduledAt ?: return null
        val actual = actualAt ?: return null
        return OperationalDetection(
            locationLabel = locationLabel,
            stationId = stationId,
            kind = OperationalEventKind.INFRASTRUCTURE_DETECTION,
            scheduledAt = scheduled,
            actualAt = actual,
            source = source
        )
    }
}
