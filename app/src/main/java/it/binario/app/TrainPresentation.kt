package it.binario.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.TrainService
import it.binario.app.data.ServiceStatus
import it.binario.app.data.accessibleUpdateTime

@Composable
internal fun SnapshotStatus(snapshot: RouteSnapshot?, message: UiMessage?) {
    val availability = snapshot?.availabilityAt(System.currentTimeMillis()) ?: SnapshotAvailability.UNAVAILABLE
    val label = stringResource(when (availability) {
        SnapshotAvailability.LIVE -> R.string.commuter_data_live
        SnapshotAvailability.SCHEDULED -> R.string.commuter_data_scheduled
        SnapshotAvailability.STALE -> R.string.commuter_data_stale
        SnapshotAvailability.DEMONSTRATION -> R.string.data_status_demo
        SnapshotAvailability.UNAVAILABLE -> R.string.data_status_unavailable
    })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        snapshot?.let {
            Text(stringResource(R.string.provider_updated_at, it.providerName, accessibleUpdateTime(it.sourceObservedAtEpochMillis)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        message?.let { Text(it.localized(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Reflows at narrow widths and large text sizes instead of truncating the useful information. */
@Composable
internal fun DepartureListRow(service: TrainService, isNext: Boolean = false, isCurrent: Boolean = true, onOpen: () -> Unit) {
    val status = if (isCurrent) compactStatusLabel(service) else stringResource(R.string.commuter_data_stale)
    val cancelled = isCurrent && service.operationalStatusVerified && service.status == ServiceStatus.CANCELLED
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = when {
            cancelled -> MaterialTheme.colorScheme.errorContainer
            isNext -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        }
    ) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.3f
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (stacked) {
                    Text(service.expectedDeparture, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                    TrainDescription(service)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(service.expectedDeparture, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                        Column(Modifier.weight(1f)) { TrainDescription(service) }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(status, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(service.platform?.let { stringResource(R.string.platform_label, it) } ?: stringResource(R.string.platform_to_confirm),
                    style = MaterialTheme.typography.bodyMedium)
                if (service.expectedDeparture != service.scheduledDeparture) {
                    Text(stringResource(R.string.service_scheduled_departure, service.scheduledDeparture), style = MaterialTheme.typography.bodySmall)
                }
                service.routeChange?.let { Text(it.summary, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun TrainDescription(service: TrainService) {
    Text(service.destination, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.service_operator_category_number, service.displayOperator, service.trainCategory.localizedLabel(), service.number),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        if (service.transferCount == 0) stringResource(R.string.service_transfer_direct)
        else pluralStringResource(R.plurals.service_transfer_count, service.transferCount, service.transferCount),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
