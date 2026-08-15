package it.binario.app.data

import it.binario.app.data.core.DelayConsistency
import kotlin.math.max

enum class DataQualityBand(val accessibleLabel: String) {
    VERIFIED("Dato verificato"),
    RELIABLE("Dato affidabile"),
    LIMITED("Dato con copertura limitata"),
    NOT_PUBLISHABLE("Dato non pubblicabile"),
    UNKNOWN("Qualità non valutata")
}

enum class SnapshotWarning(val accessibleLabel: String) {
    SOURCE_CONFLICT("Fonti in conflitto"),
    PARTIAL_ROUTE_MATCH("Nessuna corrispondenza diretta di destinazione"),
    STALE_SOURCE("Aggiornamento non recente"),
    STATIC_ONLY("Sono disponibili solo orari programmati"),
    DEMONSTRATION("Sono visualizzati dati dimostrativi"),
    INCOMPLETE_SERVICE("Corsa incompleta"),
    IDENTITY_AMBIGUOUS("Identità della corsa da verificare"),
    DELAY_DIVERGENCE("Il ritardo ufficiale differisce dall'ultima rilevazione")
}

data class SnapshotQuality(
    val score: Int,
    val band: DataQualityBand,
    val warnings: Set<SnapshotWarning>,
    val assessedAtEpochMillis: Long
) {
    init {
        require(score in 0..100) { "Il punteggio di qualità deve essere compreso tra 0 e 100" }
    }
}

/**
 * Valutazione locale, spiegabile e priva di euristiche di rete. Non modifica
 * campi ferroviari: decide solo quanto un risultato possa essere privilegiato
 * o mostrato come aggiornamento operativo.
 */
object SnapshotQualityAssessor {
    fun assess(snapshot: RouteSnapshot, nowEpochMillis: Long = System.currentTimeMillis()): SnapshotQuality {
        val warnings = linkedSetOf<SnapshotWarning>()
        var score = authorityScore(snapshot.confidence)
        score += freshnessScore(snapshot, nowEpochMillis, warnings)
        score += completenessScore(snapshot, warnings)
        score += consistencyScore(snapshot, warnings)
        score += integrityScore(snapshot, warnings)
        if (snapshot.notice?.contains("nessuna corrispondenza diretta", ignoreCase = true) == true) {
            warnings += SnapshotWarning.PARTIAL_ROUTE_MATCH
            score -= 12
        }
        val bounded = score.coerceIn(0, 100)
        val publishableScore = if (snapshot.confidence == DataConfidence.DEMONSTRATION) {
            bounded.coerceAtMost(39)
        } else {
            bounded
        }
        return SnapshotQuality(
            score = publishableScore,
            band = when {
                publishableScore >= 85 -> DataQualityBand.VERIFIED
                publishableScore >= 65 -> DataQualityBand.RELIABLE
                publishableScore >= 40 -> DataQualityBand.LIMITED
                else -> DataQualityBand.NOT_PUBLISHABLE
            },
            warnings = warnings,
            assessedAtEpochMillis = nowEpochMillis
        )
    }

    private fun authorityScore(confidence: DataConfidence): Int = when (confidence) {
        DataConfidence.OPERATOR_PRIMARY -> 30
        DataConfidence.INFRASTRUCTURE_PRIMARY -> 29
        DataConfidence.OPEN_DATA -> 25
        DataConfidence.LICENSED_AGGREGATE -> 20
        DataConfidence.STATIC_ONLY -> 16
        DataConfidence.CACHED -> 10
        DataConfidence.DEMONSTRATION -> 0
    }

    private fun freshnessScore(
        snapshot: RouteSnapshot,
        now: Long,
        warnings: MutableSet<SnapshotWarning>
    ): Int {
        if (snapshot.confidence == DataConfidence.DEMONSTRATION) {
            warnings += SnapshotWarning.DEMONSTRATION
            return 0
        }
        val age = max(0L, now - snapshot.sourceObservedAtEpochMillis)
        return when (snapshot.freshness) {
            Freshness.FRESH -> when {
                age <= 90_000L -> 25
                age <= 5 * 60_000L -> 18
                else -> {
                    warnings += SnapshotWarning.STALE_SOURCE
                    7
                }
            }
            Freshness.AGING -> {
                warnings += SnapshotWarning.STALE_SOURCE
                if (age <= 10 * 60_000L) 12 else 5
            }
            Freshness.STALE -> {
                warnings += SnapshotWarning.STALE_SOURCE
                3
            }
            Freshness.UNAVAILABLE -> 0
        }
    }

    private fun completenessScore(
        snapshot: RouteSnapshot,
        warnings: MutableSet<SnapshotWarning>
    ): Int {
        if (snapshot.services.isEmpty()) {
            warnings += SnapshotWarning.INCOMPLETE_SERVICE
            return 0
        }
        val complete = snapshot.services.count { service ->
            service.number.isNotBlank() &&
                service.origin.isNotBlank() &&
                service.destination.isNotBlank() &&
                service.scheduledDeparture.matches(Regex("\\d{2}:\\d{2}"))
        }
        if (complete != snapshot.services.size) warnings += SnapshotWarning.INCOMPLETE_SERVICE
        return (15.0 * complete / snapshot.services.size).toInt()
    }

    private fun consistencyScore(
        snapshot: RouteSnapshot,
        warnings: MutableSet<SnapshotWarning>
    ): Int {
        val notice = snapshot.notice.orEmpty()
        return when {
            snapshot.services.any { it.delayConsistency == DelayConsistency.DIVERGENT } -> {
                warnings += SnapshotWarning.DELAY_DIVERGENCE
                7
            }
            notice.contains("conflitto", ignoreCase = true) -> {
                warnings += SnapshotWarning.SOURCE_CONFLICT
                4
            }
            notice.contains("nessuna corrispondenza diretta", ignoreCase = true) -> 10
            else -> 20
        }
    }

    private fun integrityScore(
        snapshot: RouteSnapshot,
        warnings: MutableSet<SnapshotWarning>
    ): Int {
        if (snapshot.confidence == DataConfidence.STATIC_ONLY) warnings += SnapshotWarning.STATIC_ONLY
        if (snapshot.services.any { it.id.isBlank() || it.number.isBlank() }) warnings += SnapshotWarning.IDENTITY_AMBIGUOUS
        return when {
            SnapshotWarning.IDENTITY_AMBIGUOUS in warnings -> 3
            SnapshotWarning.INCOMPLETE_SERVICE in warnings -> 6
            else -> 10
        }
    }
}
