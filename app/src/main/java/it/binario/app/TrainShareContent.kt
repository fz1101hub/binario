package it.binario.app

import it.binario.app.data.Freshness
import it.binario.app.data.TrainService
import it.binario.app.data.accessibleUpdateTime

data class TrainShareContent(val title: String, val text: String)

object TrainShareFormatter {
    fun create(
        service: TrainService,
        provider: String,
        confidence: String,
        freshness: Freshness?,
        refreshedAt: Long,
        isDemonstration: Boolean
    ): TrainShareContent {
        val dataLabel = if (isDemonstration) "Catalogo dimostrativo locale — non live"
        else freshness?.accessibleLabel ?: "dati non disponibili in tempo reale"
        val stops = service.stops.joinToString(separator = "\n") { stop ->
            "• ${stop.station} — previsto ${stop.expected}${stop.platform?.let { ", binario $it" } ?: ""}${if (stop.delayMinutes > 0) ", +${stop.delayMinutes} min" else ""}"
        }
        return TrainShareContent(
            title = "Treno ${service.number} · ${service.origin} → ${service.destination}",
            text = """
                Binario — dettaglio treno

                ${service.operator.accessibleLabel} · ${service.category} ${service.number}
                ${service.origin} → ${service.destination}
                Partenza prevista: ${service.expectedDeparture} (programmata ${service.scheduledDeparture})
                ${service.platform?.let { "Binario: $it" } ?: "Binario da confermare"}
                Stato: ${service.status.accessibleLabel}${if (service.delayMinutes > 0) " (+${service.delayMinutes} min)" else ""}

                Fermate:
                $stops

                Dati: $dataLabel
                Fonte: $provider · $confidence
                Aggiornamento: ${accessibleUpdateTime(refreshedAt)}
            """.trimIndent()
        )
    }
}
