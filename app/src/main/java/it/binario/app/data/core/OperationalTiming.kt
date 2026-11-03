package it.binario.app.data.core

import java.time.Duration
import java.time.ZonedDateTime

/** Evento reale o operativo che può essere confrontato con l'orario programmato dello stesso punto della corsa. */
enum class OperationalEventKind {
    STATION_ARRIVAL,
    STATION_DEPARTURE,
    INFRASTRUCTURE_DETECTION,
    UNKNOWN
}

/** L'ultima località confermata dal provider: non equivale a una coordinata GPS. */
data class OperationalDetection(
    val locationLabel: String,
    val stationId: StationId? = null,
    val kind: OperationalEventKind,
    val scheduledAt: ZonedDateTime,
    val actualAt: SourcedValue<ZonedDateTime>,
    val source: SourceRef
) {
    init {
        require(locationLabel.isNotBlank()) { "La località della rilevazione è obbligatoria" }
        require(!actualAt.value.isBefore(scheduledAt.minusHours(12))) { "L'orario effettivo è incompatibile con la rilevazione" }
    }
}

/** Ritardo dichiarato direttamente dal provider, mantenuto anche quando esiste una rilevazione calcolabile. */
data class OfficialDelayReport(
    val delay: SourcedValue<Duration>,
    val asOf: ZonedDateTime? = null
)

/** Ritardo ottenuto confrontando orario reale e programmato del medesimo evento operativo. */
data class DetectionDelayReport(
    val detection: OperationalDetection,
    val delay: SourcedValue<Duration>
)

enum class DelayConsistency {
    NOT_COMPARABLE,
    CONSISTENT,
    DIVERGENT
}

/** Espone entrambi i ritardi; nessuno dei due sostituisce l'altro. */
data class DualDelayAssessment(
    val official: OfficialDelayReport? = null,
    val atLastDetection: DetectionDelayReport? = null,
    val consistency: DelayConsistency = DelayConsistency.NOT_COMPARABLE,
    val difference: Duration? = null
)

object OperationalDelayCalculator {
    private val divergenceThreshold: Duration = Duration.ofMinutes(3)

    fun fromDetection(detection: OperationalDetection): DetectionDelayReport {
        val calculated = Duration.between(detection.scheduledAt.toInstant(), detection.actualAt.value.toInstant())
        val source = SourceRef(
            sourceId = SourceId("binario-operational-delay"),
            nativeId = detection.source.nativeId,
            observedAt = detection.actualAt.source.observedAt,
            fetchedAt = detection.source.fetchedAt,
            expiresAt = detection.source.expiresAt,
            confidence = Confidence.DERIVED,
            licenseId = detection.source.licenseId,
            traceId = "delay:${detection.source.traceId}"
        )
        return DetectionDelayReport(detection, SourcedValue(calculated, source, Precision.MINUTE))
    }

    fun assess(official: OfficialDelayReport?, detection: OperationalDetection?): DualDelayAssessment {
        val calculated = detection?.let(::fromDetection)
        if (official == null || calculated == null) return DualDelayAssessment(official, calculated)
        val difference = official.delay.value.minus(calculated.delay.value).abs()
        val consistency = if (difference <= divergenceThreshold) DelayConsistency.CONSISTENT else DelayConsistency.DIVERGENT
        return DualDelayAssessment(official, calculated, consistency, difference)
    }
}
