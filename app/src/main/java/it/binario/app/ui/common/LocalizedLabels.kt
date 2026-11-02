package it.binario.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import it.binario.app.AlertRepeat
import it.binario.app.CommuterAlert
import it.binario.app.JourneyChangeFilter
import it.binario.app.MobilityPreferences
import it.binario.app.R
import it.binario.app.RefreshState
import it.binario.app.StationMonitor
import it.binario.app.StationMonitorMode
import it.binario.app.TrainViewModel
import it.binario.app.UNCONFIGURED_ROUTE_ID
import it.binario.app.data.DataConfidence
import it.binario.app.data.DataQualityBand
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.labelRes

// Localized labels and spoken summaries of data-layer values shown by several screens.

@Composable
internal fun SavedRoute.localizedName(): String =
    if (id == UNCONFIGURED_ROUTE_ID) stringResource(R.string.unconfigured_route_name) else name

@Composable
internal fun SavedRoute.localizedDisplayName(direction: Direction): String =
    if (id == UNCONFIGURED_ROUTE_ID) {
        stringResource(
            R.string.unconfigured_route_display,
            stringResource(R.string.unconfigured_route_origin),
            stringResource(R.string.unconfigured_route_destination)
        )
    } else {
        displayName(direction)
    }

@Composable
internal fun Direction.localizedLabel(): String = stringResource(labelRes())

@Composable
internal fun RefreshState.localizedLabel(): String = when (this) {
    RefreshState.IDLE -> stringResource(R.string.refresh_idle)
    RefreshState.LOADING -> stringResource(R.string.refresh_loading)
    RefreshState.SUCCESS -> stringResource(R.string.refresh_success)
    RefreshState.ERROR -> stringResource(R.string.refresh_error)
}

@Composable
internal fun Freshness.localizedLabel(): String = stringResource(labelRes())

@Composable
internal fun DataConfidence.localizedLabel(): String = stringResource(labelRes())

@Composable
internal fun DataQualityBand.localizedLabel(): String = stringResource(labelRes())

/** A copy kept from an earlier refresh (cache or expired source): the UI shows its snapshot time. */
internal fun TrainViewModel.isCachedCopy(): Boolean =
    currentConfidence() == DataConfidence.CACHED || currentFreshness() == Freshness.STALE

/** Quality band and score precede the confidence, as promised for every published snapshot. */
@Composable
internal fun TrainViewModel.localizedQualityLabel(): String {
    val confidence = currentConfidence()?.localizedLabel() ?: return stringResource(R.string.data_confidence_unavailable)
    val band = currentQualityBand()
    val quality = currentQualityScore()?.let { score -> band?.let { stringResource(R.string.quality_band_score, it.localizedLabel(), score) } }
    return listOfNotNull(quality, confidence).joinToString(" · ")
}

@Composable
internal fun TrainCategory.localizedLabel(): String = stringResource(labelRes())

@Composable
internal fun TrainService.localizedAccessibleSummary(): String {
    val transferLabel = if (transferCount == 0) {
        stringResource(R.string.service_transfer_direct)
    } else {
        pluralStringResource(R.plurals.service_transfer_count, transferCount, transferCount)
    }
    val operationalLabel = if (operationalStatusVerified) {
        listOfNotNull(
            status.localizedLabel(),
            if (delayMinutes > 0) pluralStringResource(R.plurals.operational_delay_minutes, delayMinutes, delayMinutes) else null
        ).joinToString(", ")
    } else {
        stringResource(R.string.operational_status_unverified)
    }
    val platformLabel = platformSpoken(platform, scheduledPlatform)
    return stringResource(
        R.string.accessibility_train_service_summary,
        displayOperator,
        trainCategory.localizedLabel(),
        number,
        origin,
        destination,
        listOfNotNull(
            stringResource(R.string.service_scheduled_departure, scheduledDeparture),
            if (operationalStatusVerified && expectedDeparture != scheduledDeparture) stringResource(R.string.expected_time, expectedDeparture) else null
        ).joinToString(". "),
        transferLabel,
        operationalLabel,
        platformLabel
    )
}

@Composable
internal fun StationMonitor.localizedSummary(): String = stringResource(
    R.string.station_monitor_summary,
    stationName,
    mode.localizedLabel()
)

@Composable
internal fun CommuterAlert.localizedSummary(): String = stringResource(
    R.string.commuter_alert_summary,
    routeLabel,
    stationName,
    time,
    pluralStringResource(R.plurals.minutes_short, leadMinutes, leadMinutes),
    repeat.localizedLabel(),
    if (quietHoursEnabled) stringResource(R.string.commuter_alert_summary_quiet_hours) else "",
    if (!enabled) stringResource(R.string.commuter_alert_summary_disabled) else ""
)

@Composable
internal fun MobilityPreferences.localizedSummary(): String = stringResource(
    R.string.mobility_summary,
    maxChanges?.let { pluralStringResource(R.plurals.mobility_summary_max_changes, it, it) }
        ?: stringResource(R.string.mobility_summary_any_changes),
    pluralStringResource(R.plurals.minutes_short, transferMarginMinutes, transferMarginMinutes),
    if (stepFreePreferred) stringResource(R.string.mobility_summary_step_free) else "",
    if (compactTimetablePreferred) stringResource(R.string.mobility_summary_compact) else ""
)

@Composable
internal fun ServiceStatus.localizedLabel(): String = stringResource(labelRes())

@Composable
internal fun StationMonitorMode.localizedLabel(): String = when (this) {
    StationMonitorMode.DEPARTURES -> stringResource(R.string.station_monitor_departures)
    StationMonitorMode.ARRIVALS -> stringResource(R.string.station_monitor_arrivals)
    StationMonitorMode.BOTH -> stringResource(R.string.station_monitor_both)
}

@Composable
internal fun JourneyChangeFilter.localizedLabel(): String = when (this) {
    JourneyChangeFilter.ANY -> stringResource(R.string.journey_change_any)
    JourneyChangeFilter.DIRECT -> stringResource(R.string.journey_change_direct)
    JourneyChangeFilter.UP_TO_ONE -> stringResource(R.string.journey_change_one)
    JourneyChangeFilter.UP_TO_TWO -> stringResource(R.string.journey_change_two)
}

@Composable
internal fun JourneyChangeFilter.localizedAccessibleLabel(): String = when (this) {
    JourneyChangeFilter.ANY -> stringResource(R.string.journey_change_any_accessible)
    JourneyChangeFilter.DIRECT -> stringResource(R.string.journey_change_direct_accessible)
    JourneyChangeFilter.UP_TO_ONE -> stringResource(R.string.journey_change_one_accessible)
    JourneyChangeFilter.UP_TO_TWO -> stringResource(R.string.journey_change_two_accessible)
}

@Composable
internal fun AlertRepeat.localizedLabel(): String = when (this) {
    AlertRepeat.ONCE -> stringResource(R.string.alert_repeat_once)
    AlertRepeat.WEEKDAYS -> stringResource(R.string.alert_repeat_weekdays)
    AlertRepeat.DAILY -> stringResource(R.string.alert_repeat_daily)
}

@Composable
internal fun ServiceGroup.localizedLabel(): String = stringResource(
    when (this) {
        ServiceGroup.REGIONAL -> R.string.service_group_regional
        ServiceGroup.INTERCITY -> R.string.train_category_intercity
        ServiceGroup.FRECCE -> R.string.service_group_frecce
        ServiceGroup.ITALO -> R.string.service_group_italo
        ServiceGroup.OTHER -> R.string.service_group_other
    }
)
