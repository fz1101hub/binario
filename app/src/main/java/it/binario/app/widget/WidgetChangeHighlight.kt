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

    /** Live values count only while the snapshot is fresh or aging; afterwards (or after a failed refresh) rows are the timetable. */
    fun showsTimetable(freshness: Freshness): Boolean = freshness !in setOf(Freshness.FRESH, Freshness.AGING)

    fun displayStatus(service: WidgetService, confidence: DataConfidence, strings: WidgetStrings, freshness: Freshness = Freshness.FRESH): String = when {
        confidence == DataConfidence.DEMONSTRATION && service.status != ServiceStatus.ON_TIME -> strings.statusDemo
        !service.operationalStatusVerified || showsTimetable(freshness) -> strings.statusScheduled
        else -> service.statusLabel(strings)
    }
}
