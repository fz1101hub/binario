package it.binario.app.widget

import it.binario.app.data.DataConfidence
import it.binario.app.data.Freshness
import it.binario.app.data.ServiceStatus

object WidgetChangeHighlight {
    fun shouldHighlight(status: ServiceStatus, confidence: DataConfidence, freshness: Freshness): Boolean =
        confidence != DataConfidence.DEMONSTRATION &&
            freshness != Freshness.UNAVAILABLE &&
            status in setOf(ServiceStatus.DELAYED, ServiceStatus.CANCELLED)

    fun displayStatus(service: WidgetService, confidence: DataConfidence): String =
        if (confidence == DataConfidence.DEMONSTRATION && service.status != ServiceStatus.ON_TIME) "DEMO" else service.statusLabel
}
