package it.binario.app.widget

import android.content.Context
import androidx.annotation.StringRes
import it.binario.app.R
import it.binario.app.data.DataConfidence
import it.binario.app.data.DataQualityBand
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.ServiceStatus
import it.binario.app.data.SnapshotWarning
import it.binario.app.data.TrainCategory
import it.binario.app.labelRes
import java.util.Locale

/**
 * Resource-backed texts for the widget model, resolved once per render in the display
 * language. The persisted state stays language-neutral and the model stays Context-free
 * and testable on the JVM, like TrainShareStrings.
 */
data class WidgetStrings(
    val locale: Locale,
    val statusOnTime: String,
    val statusDelayed: String,
    val statusCancelled: String,
    val statusDemo: String,
    val statusScheduled: String,
    val qualityScore: String,
    val serviceAccessibility: String,
    val delayMinutes: String,
    val scheduledUnverified: String,
    val platform: String,
    val platformUnavailable: String,
    val dataAccessibility: String,
    val qualityScoreAccessibility: String,
    val warningsAccessibility: String,
    val confidenceLabels: Map<DataConfidence, String>,
    val freshnessLabels: Map<Freshness, String>,
    val warningLabels: Map<SnapshotWarning, String>,
    val statusLabels: Map<ServiceStatus, String>,
    val categoryLabels: Map<TrainCategory, String>,
    val directionLabels: Map<Direction, String>,
    val confidenceAccessibleLabels: Map<DataConfidence, String>,
    val freshnessAccessibleLabels: Map<Freshness, String>,
    val qualityBandLabels: Map<DataQualityBand, String>,
    val warningAccessibleLabels: Map<SnapshotWarning, String>
) {
    fun format(template: String, vararg values: Any): String = template.format(locale, *values)

    companion object {
        fun from(context: Context): WidgetStrings = WidgetStrings(
            locale = context.resources.configuration.locales[0],
            statusOnTime = context.getString(R.string.widget_status_on_time),
            statusDelayed = context.getString(R.string.widget_status_delayed),
            statusCancelled = context.getString(R.string.widget_status_cancelled),
            statusDemo = context.getString(R.string.widget_status_demo),
            statusScheduled = context.getString(R.string.widget_status_scheduled),
            qualityScore = context.getString(R.string.widget_quality_score),
            serviceAccessibility = context.getString(R.string.widget_service_accessibility),
            delayMinutes = context.getString(R.string.delay_minutes),
            scheduledUnverified = context.getString(R.string.operational_status_unverified),
            platform = context.getString(R.string.platform_label),
            platformUnavailable = context.getString(R.string.platform_unavailable),
            dataAccessibility = context.getString(R.string.widget_data_accessibility),
            qualityScoreAccessibility = context.getString(R.string.widget_quality_score_accessibility),
            warningsAccessibility = context.getString(R.string.widget_warnings_accessibility),
            confidenceLabels = DataConfidence.entries.associateWith { context.getString(it.widgetLabelRes()) },
            freshnessLabels = Freshness.entries.associateWith { context.getString(it.widgetLabelRes()) },
            warningLabels = SnapshotWarning.entries.associateWith { context.getString(it.widgetLabelRes()) },
            statusLabels = ServiceStatus.entries.associateWith { context.getString(it.labelRes()) },
            categoryLabels = TrainCategory.entries.associateWith { context.getString(it.labelRes()) },
            directionLabels = Direction.entries.associateWith { context.getString(it.labelRes()) },
            confidenceAccessibleLabels = DataConfidence.entries.associateWith { context.getString(it.labelRes()) },
            freshnessAccessibleLabels = Freshness.entries.associateWith { context.getString(it.labelRes()) },
            qualityBandLabels = DataQualityBand.entries.associateWith { context.getString(it.labelRes()) },
            warningAccessibleLabels = SnapshotWarning.entries.associateWith { context.getString(it.labelRes()) }
        )
    }
}

/* Compact upper-case labels for the single metadata line of the widget. */

@StringRes
private fun DataConfidence.widgetLabelRes(): Int = when (this) {
    DataConfidence.OPERATOR_PRIMARY -> R.string.widget_confidence_operator_primary
    DataConfidence.INFRASTRUCTURE_PRIMARY -> R.string.widget_confidence_infrastructure_primary
    DataConfidence.OPEN_DATA -> R.string.widget_confidence_open_data
    DataConfidence.LICENSED_AGGREGATE -> R.string.widget_confidence_licensed_aggregate
    DataConfidence.STATIC_ONLY -> R.string.widget_confidence_static_only
    DataConfidence.CACHED -> R.string.widget_confidence_cached
    DataConfidence.DEMONSTRATION -> R.string.widget_confidence_demonstration
}

@StringRes
private fun Freshness.widgetLabelRes(): Int = when (this) {
    Freshness.FRESH -> R.string.widget_freshness_fresh
    Freshness.AGING -> R.string.widget_freshness_aging
    Freshness.STALE -> R.string.widget_freshness_stale
    Freshness.UNAVAILABLE -> R.string.widget_freshness_unavailable
}

@StringRes
private fun SnapshotWarning.widgetLabelRes(): Int = when (this) {
    SnapshotWarning.SOURCE_CONFLICT -> R.string.widget_warning_source_conflict
    SnapshotWarning.PARTIAL_ROUTE_MATCH -> R.string.widget_warning_partial_route_match
    SnapshotWarning.STALE_SOURCE -> R.string.widget_warning_stale_source
    SnapshotWarning.STATIC_ONLY -> R.string.widget_warning_static_only
    SnapshotWarning.DEMONSTRATION -> R.string.widget_warning_demonstration
    SnapshotWarning.INCOMPLETE_SERVICE -> R.string.widget_warning_incomplete_service
    SnapshotWarning.IDENTITY_AMBIGUOUS -> R.string.widget_warning_identity_ambiguous
    SnapshotWarning.DELAY_DIVERGENCE -> R.string.widget_warning_delay_divergence
}
