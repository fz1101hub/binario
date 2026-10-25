package it.binario.app.widget

import it.binario.app.data.DataConfidence
import it.binario.app.data.Freshness
import it.binario.app.data.ServiceStatus

object WidgetChangeHighlight {
    fun shouldHighlight(
        status: ServiceStatus,
        confidence: DataConfidence,
        freshness: Freshness,
        operationalStatusVerified: Boolean = confidence in setOf(
            DataConfidence.OPERATOR_PRIMARY,
            DataConfidence.INFRASTRUCTURE_PRIMARY,
            DataConfidence.OPEN_DATA,
            DataConfidence.LICENSED_AGGREGATE
        )
    ): Boolean =
        operationalStatusVerified &&
            freshness in setOf(Freshness.FRESH, Freshness.AGING) &&
            status in setOf(ServiceStatus.DELAYED, ServiceStatus.CANCELLED)

    fun displayStatus(service: WidgetService, confidence: DataConfidence, strings: WidgetStrings): String = when {
        confidence == DataConfidence.DEMONSTRATION && service.status != ServiceStatus.ON_TIME -> strings.statusDemo
        !service.operationalStatusVerified -> strings.statusScheduled
        else -> service.statusLabel(strings)
    }
}
