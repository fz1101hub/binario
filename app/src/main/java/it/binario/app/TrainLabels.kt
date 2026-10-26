package it.binario.app

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.data.DataConfidence
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService

@Composable
internal fun ServiceStatusBadge(service: TrainService, isCurrent: Boolean = true) {
    val (container, foreground, text) = if (!isCurrent) {
        Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.commuter_data_stale))
    } else if (!service.operationalStatusVerified) {
        Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.service_status_scheduled_badge))
    } else when (service.status) {
        ServiceStatus.ON_TIME -> Triple(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary, stringResource(R.string.operational_on_time))
        ServiceStatus.DELAYED -> Triple(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary, stringResource(R.string.delay_minutes, service.delayMinutes))
        ServiceStatus.CANCELLED -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError, stringResource(R.string.service_status_cancelled))
    }
    Surface(color = container, contentColor = foreground, shape = RoundedCornerShape(999.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun compactStatusLabel(service: TrainService): String {
    if (!service.operationalStatusVerified) return stringResource(R.string.service_status_scheduled_compact)
    return when (service.status) {
        ServiceStatus.ON_TIME -> stringResource(R.string.operational_on_time)
        ServiceStatus.DELAYED -> stringResource(R.string.delay_minutes, service.delayMinutes)
        ServiceStatus.CANCELLED -> stringResource(R.string.service_status_cancelled)
    }
}

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
internal fun Direction.localizedLabel(): String = when (this) {
    Direction.OUTBOUND -> stringResource(R.string.direction_outbound)
    Direction.RETURN -> stringResource(R.string.direction_return)
}

@Composable
internal fun RefreshState.localizedLabel(): String = when (this) {
    RefreshState.IDLE -> stringResource(R.string.refresh_idle)
    RefreshState.LOADING -> stringResource(R.string.refresh_loading)
    RefreshState.SUCCESS -> stringResource(R.string.refresh_success)
    RefreshState.ERROR -> stringResource(R.string.refresh_error)
}

@Composable
internal fun Freshness.localizedLabel(): String = when (this) {
    Freshness.FRESH -> stringResource(R.string.freshness_fresh)
    Freshness.AGING -> stringResource(R.string.freshness_aging)
    Freshness.STALE -> stringResource(R.string.freshness_stale)
    Freshness.UNAVAILABLE -> stringResource(R.string.freshness_unavailable)
}

@Composable
internal fun DataConfidence.localizedLabel(): String = when (this) {
    DataConfidence.OPERATOR_PRIMARY -> stringResource(R.string.data_confidence_operator_primary)
    DataConfidence.INFRASTRUCTURE_PRIMARY -> stringResource(R.string.data_confidence_infrastructure_primary)
    DataConfidence.OPEN_DATA -> stringResource(R.string.data_confidence_open_data)
    DataConfidence.LICENSED_AGGREGATE -> stringResource(R.string.data_confidence_licensed_aggregate)
    DataConfidence.STATIC_ONLY -> stringResource(R.string.data_confidence_static_only)
    DataConfidence.CACHED -> stringResource(R.string.data_confidence_cached)
    DataConfidence.DEMONSTRATION -> stringResource(R.string.data_confidence_demonstration)
}

@Composable
internal fun TrainCategory.localizedLabel(): String = when (this) {
    TrainCategory.REGIONALE -> stringResource(R.string.train_category_regionale)
    TrainCategory.REGIONALE_VELOCE -> stringResource(R.string.train_category_regionale_veloce)
    TrainCategory.INTERCITY -> stringResource(R.string.train_category_intercity)
    TrainCategory.FRECCE -> stringResource(R.string.train_category_frecce)
    TrainCategory.AV_ITALO -> stringResource(R.string.train_category_italo_high_speed)
    TrainCategory.TRENORD_REGIO -> stringResource(R.string.train_category_trenord_regional)
    TrainCategory.METROPOLITANO -> stringResource(R.string.train_category_metropolitan)
    TrainCategory.OTHER -> stringResource(R.string.train_category_other)
}
