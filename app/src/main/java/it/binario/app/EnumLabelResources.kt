package it.binario.app

import androidx.annotation.StringRes
import it.binario.app.data.DataConfidence
import it.binario.app.data.DataQualityBand
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory

/**
 * String resources for the data-layer enums shown to the user. Compose, the widget and the
 * Wear payload resolve them in the display language instead of reading the enums' Italian labels.
 */
@StringRes
internal fun Direction.labelRes(): Int = when (this) {
    Direction.OUTBOUND -> R.string.direction_outbound
    Direction.RETURN -> R.string.direction_return
}

@StringRes
internal fun Freshness.labelRes(): Int = when (this) {
    Freshness.FRESH -> R.string.freshness_fresh
    Freshness.AGING -> R.string.freshness_aging
    Freshness.STALE -> R.string.freshness_stale
    Freshness.UNAVAILABLE -> R.string.freshness_unavailable
}

@StringRes
internal fun DataConfidence.labelRes(): Int = when (this) {
    DataConfidence.OPERATOR_PRIMARY -> R.string.data_confidence_operator_primary
    DataConfidence.INFRASTRUCTURE_PRIMARY -> R.string.data_confidence_infrastructure_primary
    DataConfidence.OPEN_DATA -> R.string.data_confidence_open_data
    DataConfidence.LICENSED_AGGREGATE -> R.string.data_confidence_licensed_aggregate
    DataConfidence.STATIC_ONLY -> R.string.data_confidence_static_only
    DataConfidence.CACHED -> R.string.data_confidence_cached
    DataConfidence.DEMONSTRATION -> R.string.data_confidence_demonstration
}

@StringRes
internal fun DataQualityBand.labelRes(): Int = when (this) {
    DataQualityBand.VERIFIED -> R.string.quality_band_verified
    DataQualityBand.OFFICIAL_SCHEDULED -> R.string.quality_band_official_scheduled
    DataQualityBand.RELIABLE -> R.string.quality_band_reliable
    DataQualityBand.LIMITED -> R.string.quality_band_limited
    DataQualityBand.NOT_PUBLISHABLE -> R.string.quality_band_not_publishable
    DataQualityBand.UNKNOWN -> R.string.quality_band_unknown
}

@StringRes
internal fun TrainCategory.labelRes(): Int = when (this) {
    TrainCategory.REGIONALE -> R.string.train_category_regionale
    TrainCategory.REGIONALE_VELOCE -> R.string.train_category_regionale_veloce
    TrainCategory.INTERCITY -> R.string.train_category_intercity
    TrainCategory.FRECCE -> R.string.train_category_frecce
    TrainCategory.AV_ITALO -> R.string.train_category_italo_high_speed
    TrainCategory.TRENORD_REGIO -> R.string.train_category_trenord_regional
    TrainCategory.METROPOLITANO -> R.string.train_category_metropolitan
    TrainCategory.OTHER -> R.string.train_category_other
}

@StringRes
internal fun ServiceStatus.labelRes(): Int = when (this) {
    ServiceStatus.ON_TIME -> R.string.service_status_on_time
    ServiceStatus.DELAYED -> R.string.service_status_delayed
    ServiceStatus.CANCELLED -> R.string.service_status_cancelled
}
