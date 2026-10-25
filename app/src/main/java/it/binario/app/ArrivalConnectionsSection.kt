package it.binario.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import it.binario.app.data.RailTime
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainService
import it.binario.app.data.core.RunStatus
import it.binario.app.data.repository.StationBoardAggregate
import it.binario.app.data.repository.StationBoardAggregateResult
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

private enum class ConnectionLoadState { IDLE, LOADING, SUCCESS, ERROR, STATION_UNKNOWN }

private val CONNECTION_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)
private const val CONNECTIONS_REFRESH_MILLIS = 30_000L
private val ROUTINE_STATUSES = setOf(RunStatus.SCHEDULED, RunStatus.UNKNOWN, RunStatus.ON_TIME, RunStatus.DELAYED)

/**
 * Collapsible "connections at arrival" section of the train detail. Nothing is requested before
 * the user expands it; the board is collected in this composition while the app is started, so
 * leaving the detail, collapsing the section or leaving the app cancels the requests.
 */
@Composable
internal fun ArrivalConnectionsSection(
    service: TrainService,
    preferredStop: String?,
    hasBoardProviders: Boolean,
    loadBoard: (stopName: String, from: Instant) -> Flow<StationBoardAggregateResult>?
) {
    val stop = remember(service.stops, preferredStop) { ConnectionRiskClassifier.connectionStop(service.stops, preferredStop) }
    val stationName = stop?.station ?: service.destination
    val arrival = stop?.let { ConnectionRiskClassifier.arrival(service, it, LocalDate.now(RailTime.defaultZone)) }
    val trainCancelled = service.operationalStatusVerified && service.status == ServiceStatus.CANCELLED
    var expanded by rememberSaveable(service.id) { mutableStateOf(false) }
    var reloadRequest by remember(service.id) { mutableIntStateOf(0) }
    var board by remember(service.id, stationName) { mutableStateOf<StationBoardAggregate?>(null) }
    var loadState by remember(service.id, stationName) { mutableStateOf(ConnectionLoadState.IDLE) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val boardFrom by rememberUpdatedState(arrival?.boardFrom)

    LaunchedEffect(expanded, stationName, reloadRequest, arrival == null, trainCancelled, lifecycleOwner) {
        if (!expanded || !hasBoardProviders || arrival == null || trainCancelled) return@LaunchedEffect
        // Like the station board, refresh while visible and the app is started: board predictions
        // expire within a minute and must not linger as live.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val from = boardFrom ?: break
                val updates = loadBoard(stationName, from)
                if (updates == null) {
                    loadState = ConnectionLoadState.STATION_UNKNOWN
                    break
                }
                loadState = ConnectionLoadState.LOADING
                try {
                    updates.collect { result ->
                        when (result) {
                            is StationBoardAggregateResult.Success -> {
                                board = result.board
                                loadState = if (result.isComplete) ConnectionLoadState.SUCCESS else ConnectionLoadState.LOADING
                            }
                            is StationBoardAggregateResult.Unavailable -> {
                                // Rows already shown stay, without live claims whose validity has ended.
                                board = board?.withoutExpiredObservations(Instant.now())
                                loadState = ConnectionLoadState.ERROR
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    board = board?.withoutExpiredObservations(Instant.now())
                    loadState = ConnectionLoadState.ERROR
                }
                delay(CONNECTIONS_REFRESH_MILLIS)
            }
        }
    }

    val stateLabel = stringResource(if (expanded) R.string.connections_state_expanded else R.string.connections_state_collapsed)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = stateLabel },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.connections_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.connections_subtitle, stationName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
            if (expanded) {
                ConnectionsBody(
                    service = service,
                    stationName = stationName,
                    arrival = arrival,
                    trainCancelled = trainCancelled,
                    hasBoardProviders = hasBoardProviders,
                    board = board,
                    loadState = loadState,
                    onReload = { reloadRequest += 1 }
                )
            }
        }
    }
}

@Composable
private fun ConnectionsBody(
    service: TrainService,
    stationName: String,
    arrival: ConnectionArrival?,
    trainCancelled: Boolean,
    hasBoardProviders: Boolean,
    board: StationBoardAggregate?,
    loadState: ConnectionLoadState,
    onReload: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (!hasBoardProviders) {
        Text(stringResource(R.string.station_board_live_disabled_message), style = MaterialTheme.typography.bodySmall, color = muted)
        return
    }
    if (trainCancelled) {
        Text(stringResource(R.string.connections_train_cancelled), style = MaterialTheme.typography.bodySmall, color = muted)
        return
    }
    if (arrival == null) {
        Text(stringResource(R.string.connections_arrival_unavailable, stationName), style = MaterialTheme.typography.bodySmall, color = muted)
        return
    }
    val arrivalTime = arrival.expected.atZone(RailTime.defaultZone).format(CONNECTION_TIME_FORMATTER)
    Text(
        if (arrival.isLive) stringResource(R.string.connections_arrival_live, arrivalTime)
        else stringResource(R.string.connections_arrival_scheduled, arrivalTime),
        style = MaterialTheme.typography.bodySmall,
        color = muted
    )
    if (loadState == ConnectionLoadState.LOADING) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        if (board == null) Text(stringResource(R.string.connections_loading, stationName), style = MaterialTheme.typography.bodySmall, color = muted)
    }
    when (loadState) {
        ConnectionLoadState.STATION_UNKNOWN -> Text(stringResource(R.string.connections_station_unknown, stationName), style = MaterialTheme.typography.bodySmall, color = muted)
        ConnectionLoadState.ERROR -> Text(UiMessage(UiMessageKey.STATION_BOARD_UNAVAILABLE).localized(), style = MaterialTheme.typography.bodySmall, color = muted)
        else -> Unit
    }
    board?.let { loaded ->
        val connections = ConnectionRiskClassifier.forDisplay(
            ConnectionRiskClassifier.connections(arrival, loaded.entries, service.number, Instant.now())
        )
        Text(
            listOf(stringResource(R.string.connections_sources, loaded.providerNames.joinToString(" · ")), updateTimeMessage(loaded.fetchedAt.toEpochMilli()).localized())
                .joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = muted
        )
        loaded.warnings.map { UiMessage(it.stationBoardWarningKey()) }.distinct().forEach { warning ->
            Text(warning.localized(), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        if (connections.isEmpty() && loadState != ConnectionLoadState.LOADING) {
            Text(stringResource(R.string.connections_empty), style = MaterialTheme.typography.bodyMedium)
        }
        connections.forEachIndexed { index, connection ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ArrivalConnectionRow(connection)
        }
    }
    Text(stringResource(R.string.connections_note), style = MaterialTheme.typography.labelSmall, color = muted)
    TextButton(onClick = onReload, enabled = loadState != ConnectionLoadState.LOADING) {
        Text(stringResource(R.string.connections_reload))
    }
}

@Composable
private fun ArrivalConnectionRow(connection: ArrivalConnection) {
    val entry = connection.entry
    val scheduled = entry.scheduledTime.withZoneSameInstant(RailTime.defaultZone)
    val departure = connection.departure.atZone(RailTime.defaultZone)
    val delayMinutes = if (connection.isLive) Duration.between(scheduled.toInstant(), connection.departure).toMinutes().coerceAtLeast(0) else 0L
    val destination = entry.destinationOrOriginLabel?.takeIf(String::isNotBlank) ?: entry.destinationOrOrigin.value
    val platform = entry.platform?.value?.takeIf(String::isNotBlank)
    val train = listOfNotNull(
        entry.publicNumber?.takeIf(String::isNotBlank)?.let { stringResource(R.string.station_board_train_number, it) },
        entry.operatorId?.value?.takeIf(String::isNotBlank)
    ).joinToString(" · ")
    val timeLabel = if (connection.isLive) {
        stringResource(R.string.station_board_status_live_prediction, departure.format(CONNECTION_TIME_FORMATTER))
    } else {
        stringResource(R.string.station_board_scheduled_time, scheduled.format(CONNECTION_TIME_FORMATTER))
    }
    // Same convention as the station board: a status other than scheduled/unknown is a live observation.
    val runStatus = entry.status?.value
    val statusLabel = when {
        connection.risk == ConnectionRisk.CANCELLED -> stringResource(R.string.run_status_cancelled)
        runStatus != null && runStatus !in ROUTINE_STATUSES -> runStatus.stationBoardLabel()
        connection.isLive && delayMinutes > 0 -> stringResource(R.string.delay_minutes, delayMinutes)
        connection.isLive || runStatus == RunStatus.ON_TIME -> stringResource(R.string.operational_on_time)
        runStatus == RunStatus.DELAYED -> runStatus.stationBoardLabel()
        else -> stringResource(R.string.run_status_scheduled)
    }
    val riskLabel = connection.risk.label()
    // A missed connection reports the whole minutes it leaves early, a reachable one the whole minutes to spare.
    val bufferMinutes = if (connection.buffer.isNegative) ((-connection.buffer.seconds + 59) / 60).toInt() else connection.buffer.toMinutes().toInt()
    val bufferLabel = stringResource(
        if (connection.buffer.isNegative) R.string.connections_buffer_negative else R.string.connections_buffer,
        pluralStringResource(R.plurals.minutes_short, bufferMinutes, bufferMinutes)
    )
    val platformLabel = platform?.let { stringResource(R.string.platform_label, it) } ?: stringResource(R.string.platform_unavailable)
    val directionLabel = stringResource(R.string.station_board_departure_to, destination)
    val accessibilityLabel = listOf(riskLabel, timeLabel, train, directionLabel, statusLabel, platformLabel, bufferLabel)
        .filter(String::isNotBlank)
        .joinToString(". ")
    val riskColors = when (connection.risk) {
        ConnectionRisk.MISSED, ConnectionRisk.CANCELLED -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
        ConnectionRisk.AT_RISK -> MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.onTertiary
        ConnectionRisk.OK -> MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.onSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = accessibilityLabel }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column {
            Text(
                (if (connection.isLive) departure else scheduled).format(CONNECTION_TIME_FORMATTER),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            if (connection.isLive && departure.toInstant() != scheduled.toInstant()) {
                Text(stringResource(R.string.connections_time_scheduled_short, scheduled.format(CONNECTION_TIME_FORMATTER)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(directionLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(train, statusLabel, platform?.let { stringResource(R.string.station_board_platform, it) }).filter { !it.isNullOrBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(bufferLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = riskColors.first, contentColor = riskColors.second, shape = RoundedCornerShape(999.dp)) {
            Text(riskLabel, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ConnectionRisk.label(): String = stringResource(
    when (this) {
        ConnectionRisk.MISSED -> R.string.connections_risk_missed
        ConnectionRisk.AT_RISK -> R.string.connections_risk_at_risk
        ConnectionRisk.OK -> R.string.connections_risk_ok
        ConnectionRisk.CANCELLED -> R.string.run_status_cancelled
    }
)
