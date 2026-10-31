package it.binario.app.ui.foryou

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import it.binario.app.ActiveTrip
import it.binario.app.AlertRepeat
import it.binario.app.CommuterAlert
import it.binario.app.CommuterAlertScheduleResult
import it.binario.app.CommuterAlertScheduler
import it.binario.app.IntegrationSettings
import it.binario.app.LocalDataBackupCodec
import it.binario.app.MobilityPreferences
import it.binario.app.R
import it.binario.app.SourcesSettingsPanel
import it.binario.app.StationMonitor
import it.binario.app.TrainViewModel
import it.binario.app.VoiceProfileSyncReadiness
import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.resolve
import it.binario.app.ui.common.StationField
import it.binario.app.ui.common.localizedDisplayName
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.common.localizedName
import it.binario.app.ui.common.localizedSummary
import it.binario.app.ui.departures.StationPreferenceRow
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Per te: saved routes, alerts, personal travel, preferences, data and integrations.

private val commuterAlertSaver = listSaver<CommuterAlert?, Any>(
    save = { alert ->
        if (alert == null) emptyList()
        else listOf(
            alert.id,
            alert.routeLabel,
            alert.stationName,
            alert.time,
            alert.leadMinutes,
            alert.repeat.name,
            alert.quietHoursEnabled,
            alert.enabled
        )
    },
    restore = { values ->
        if (values.isEmpty()) null
        else CommuterAlert(
            id = values[0] as String,
            routeLabel = values[1] as String,
            stationName = values[2] as String,
            time = values[3] as String,
            leadMinutes = values[4] as Int,
            repeat = AlertRepeat.valueOf(values[5] as String),
            quietHoursEnabled = values[6] as Boolean,
            enabled = values[7] as Boolean
        )
    }
)

private fun commuterAlertScheduleFeedback(resources: android.content.res.Resources, result: CommuterAlertScheduleResult): String = when (result) {
    is CommuterAlertScheduleResult.Scheduled -> if (result.isExact) {
        resources.getString(R.string.commuter_alert_scheduled)
    } else {
        resources.getString(R.string.commuter_alert_scheduled_inexact)
    }
    CommuterAlertScheduleResult.QuietHours ->
        resources.getString(R.string.commuter_alert_quiet_hours_feedback)
    CommuterAlertScheduleResult.InvalidTime ->
        resources.getString(R.string.commuter_alert_invalid_time_feedback)
    CommuterAlertScheduleResult.Disabled ->
        resources.getString(R.string.commuter_alert_disabled_feedback)
    CommuterAlertScheduleResult.NoUpcomingTrigger ->
        resources.getString(R.string.commuter_alert_no_trigger_feedback)
}

/**
 * "Per te": what belongs to the user, all stored on this device. Saved routes come first, then the agenda
 * of planned journeys, favourite stations, trips and monitoring, alarms and alerts, preferences, data and
 * privacy (with the local export), integrations, sources and credits.
 */
@Composable
internal fun ForYouScreen(
    modifier: Modifier,
    viewModel: TrainViewModel,
    onThemeClick: () -> Unit,
    openRoute: (SavedRoute) -> Unit,
    openAgenda: () -> Unit,
    openStationBoard: (Station) -> Unit,
    gpsAlarmPanel: @Composable () -> Unit
) {
    var showComposer by rememberSaveable { mutableStateOf(false) }
    var showAlertComposer by rememberSaveable { mutableStateOf(false) }
    var pendingAlert by rememberSaveable(stateSaver = commuterAlertSaver) {
        mutableStateOf<CommuterAlert?>(null)
    }
    var alertFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    var backupFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    val createBackupDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LocalDataBackupCodec.MIME_TYPE)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupFeedback = runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(viewModel.exportLocalBackup()) }
                ?: error(resources.getString(R.string.backup_open_output_error))
            resources.getString(R.string.backup_export_success)
        }.getOrElse { resources.getString(R.string.backup_export_failure, it.message ?: resources.getString(R.string.backup_file_error)) }
    }
    val openBackupDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            backupFeedback = runCatching {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.let(LocalDataBackupCodec::readBoundedUtf8)
                        ?: error(resources.getString(R.string.backup_read_input_error))
                }
                val stationCatalog = viewModel.stations
                val decoded = withContext(Dispatchers.Default) {
                    LocalDataBackupCodec.decode(raw, stationCatalog)
                }
                viewModel.importLocalBackup(decoded).resolve(resources)
            }.getOrElse { resources.getString(R.string.backup_import_failure, it.message ?: resources.getString(R.string.backup_file_error)) }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val alert = pendingAlert
        pendingAlert = null
        if (granted && alert != null) {
            alertFeedback = commuterAlertScheduleFeedback(resources, CommuterAlertScheduler.schedule(context, alert))
        } else {
            alertFeedback = resources.getString(R.string.notification_permission_required)
        }
    }
    if (showComposer) {
        RouteComposerSheet(viewModel = viewModel, onDismiss = { showComposer = false })
    }
    if (showAlertComposer) {
        CommuterAlertSheet(
            routeLabel = viewModel.currentRouteLabel(),
            defaultStation = viewModel.activeRoute.destination(viewModel.direction).name,
            onDismiss = { showAlertComposer = false },
            onSave = { station, time, lead, repeat, quietHours ->
                val alert = viewModel.createCommuterAlert(station, time, lead, repeat, quietHours)
                showAlertComposer = false
                if (alert == null) {
                    alertFeedback = resources.getString(R.string.commuter_alerts_route_required)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingAlert = alert
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    alertFeedback = commuterAlertScheduleFeedback(resources, CommuterAlertScheduler.schedule(context, alert))
                }
            }
        )
    }
    LazyColumn(modifier = modifier.fillMaxSize().testTag("for-you-screen"), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeading(stringResource(R.string.for_you_saved_routes))
                Text(stringResource(R.string.settings_personal_journeys_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { showComposer = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_add_route))
                }
            }
        }
        items(viewModel.savedRoutes, key = { it.id }) { route ->
            RouteListRow(
                route = route,
                isActive = route.id == viewModel.activeRoute.id,
                onOpen = { openRoute(route) },
                onPin = { viewModel.toggleRoutePin(route) },
                onMove = { delta -> viewModel.moveRoute(route, delta) },
                canMoveUp = viewModel.canMoveRoute(route, -1),
                canMoveDown = viewModel.canMoveRoute(route, 1),
                onRemove = { viewModel.removeRoute(route) },
                canRemove = viewModel.savedRoutes.size > 1
            )
        }
        item {
            SettingsActionRow(
                title = stringResource(R.string.screen_title_agenda),
                detail = stringResource(R.string.screen_subtitle_agenda),
                icon = Icons.Default.CalendarMonth,
                onClick = openAgenda,
                modifier = Modifier.testTag("for-you-agenda")
            )
        }
        if (viewModel.favouriteStations.isNotEmpty()) {
            item { SettingsSectionTitle(stringResource(R.string.station_board_favourites)) }
            items(viewModel.favouriteStations, key = { "fav-${it.id}" }) { station ->
                StationPreferenceRow(
                    station = station,
                    favourite = true,
                    onSelect = { openStationBoard(station) },
                    onToggleFavourite = { viewModel.toggleStationFavourite(station) }
                )
            }
        }
        item {
            PersonalTravelPanel(
                activeTrip = viewModel.activeTrip,
                stationMonitors = viewModel.stationMonitors,
                onAdvanceTrip = viewModel::advanceActiveTrip,
                onEndTrip = viewModel::endActiveTrip,
                onRemoveStationMonitor = viewModel::removeStationMonitor
            )
        }
        if (viewModel.monitoredServices.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_monitored_trains), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.settings_monitored_trains_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(viewModel.monitoredServices, key = { it.number }) { monitor ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Train, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(stringResource(R.string.train_number_label, monitor.number), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(monitor.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.removeServiceMonitoring(monitor.number) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.accessibility_remove_train_monitoring, monitor.number))
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionHeading(stringResource(R.string.for_you_alarms))
                Text(stringResource(R.string.for_you_stop_alarm_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            gpsAlarmPanel()
        }
        item {
            CommuterAlertsPanel(
                alerts = viewModel.commuterAlerts,
                feedback = alertFeedback,
                canAdd = viewModel.hasActiveRoute,
                onAdd = { showAlertComposer = true },
                onRemove = viewModel::removeCommuterAlert
            )
        }
        item {
            SettingsSectionTitle(stringResource(R.string.settings_appearance))
            SettingsActionRow(
                title = stringResource(R.string.settings_theme_title),
                detail = stringResource(R.string.settings_theme_description),
                icon = Icons.Default.Palette,
                onClick = onThemeClick
            )
        }
        item {
            MobilityPreferencesPanel(
                value = viewModel.mobilityPreferences,
                onChange = viewModel::updateMobilityPreferences
            )
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionHeading(stringResource(R.string.settings_privacy_title))
                Text(stringResource(R.string.settings_privacy_local_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_privacy_notifications_voice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_privacy_wear), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_privacy_maps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            LocalBackupPanel(
                feedback = backupFeedback,
                onExport = { createBackupDocument.launch("binario-backup.json") },
                onImport = { openBackupDocument.launch(arrayOf(LocalDataBackupCodec.MIME_TYPE, "text/plain")) }
            )
        }
        item { SettingsSectionTitle(stringResource(R.string.settings_integrations)) }
        item {
            IntegrationsPanel(
                settings = viewModel.integrationSettings,
                voiceReadiness = viewModel.voiceProfileSyncReadiness(),
                onWearSharingChanged = viewModel::setWearSnapshotSharing,
                onVoiceProfileChanged = viewModel::setVoiceProfileSyncRequested,
                onAppleWatchSetupChanged = viewModel::setAppleWatchSetupRequested
            )
        }
        item { SourcesSettingsPanel() }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionHeading(stringResource(R.string.for_you_credits))
                Text(stringResource(R.string.settings_privacy_osm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_credits_maps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).semantics { heading() }
    )
}

@Composable
private fun SettingsActionRow(title: String, detail: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accessibilityLabel = stringResource(R.string.accessibility_title_detail, title, detail)
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics { contentDescription = accessibilityLabel }
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun CommuterAlertsPanel(
    alerts: List<CommuterAlert>,
    feedback: String?,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onRemove: (CommuterAlert) -> Unit
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.commuter_alerts_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.commuter_alerts_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onAdd, enabled = canAdd, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.commuter_alerts_create)) }
        if (!canAdd) Text(stringResource(R.string.commuter_alerts_route_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        alerts.forEach { alert ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(alert.stationName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(alert.localizedSummary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onRemove(alert) }) { Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.accessibility_remove_commuter_alert, alert.stationName)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommuterAlertSheet(
    routeLabel: String,
    defaultStation: String,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, AlertRepeat, Boolean) -> Unit
) {
    var station by rememberSaveable { mutableStateOf(defaultStation) }
    var time by rememberSaveable { mutableStateOf("08:00") }
    var lead by rememberSaveable { mutableIntStateOf(15) }
    var repeat by rememberSaveable { mutableStateOf(AlertRepeat.WEEKDAYS.name) }
    var quietHours by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.commuter_alert_sheet_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(routeLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = station, onValueChange = { station = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.commuter_alert_station)) })
            OutlinedButton(onClick = {
                val parsed = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.of(8, 0))
                TimePickerDialog(context, { _, hour, minute -> time = "%02d:%02d".format(hour, minute) }, parsed.hour, parsed.minute, true).show()
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.commuter_alert_time, time)) }
            Text(stringResource(R.string.commuter_alert_lead), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(5, 10, 15, 30, 60)) { minutes ->
                    FilterChip(
                        selected = lead == minutes,
                        onClick = { lead = minutes },
                        label = { Text(pluralStringResource(R.plurals.minutes_short, minutes, minutes)) }
                    )
                }
            }
            Text(stringResource(R.string.commuter_alert_repeat), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AlertRepeat.entries) { option ->
                    FilterChip(selected = repeat == option.name, onClick = { repeat = option.name }, label = { Text(option.localizedLabel()) })
                }
            }
            FilterChip(selected = quietHours, onClick = { quietHours = !quietHours }, label = { Text(stringResource(R.string.commuter_alert_quiet_hours)) })
            Button(onClick = { onSave(station.trim(), time, lead, AlertRepeat.valueOf(repeat), quietHours) }, enabled = station.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.commuter_alert_save)) }
        }
    }
}

@Composable
private fun PersonalTravelPanel(
    activeTrip: ActiveTrip?,
    stationMonitors: List<StationMonitor>,
    onAdvanceTrip: () -> Unit,
    onEndTrip: () -> Unit,
    onRemoveStationMonitor: (String) -> Unit
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.personal_travel_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        activeTrip?.let { trip ->
            Text(stringResource(R.string.personal_travel_active, trip.trainNumber), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            val nextStopLabel = trip.nextStop?.let { stop ->
                stop.platform?.let { platform ->
                    stringResource(R.string.personal_travel_next_stop_platform, stop.station, stop.expected, platform)
                } ?: stringResource(R.string.personal_travel_next_stop, stop.station, stop.expected)
            } ?: stringResource(R.string.personal_travel_completed)
            Text(nextStopLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAdvanceTrip, enabled = !trip.isComplete) { Text(stringResource(R.string.action_stop_reached)) }
                TextButton(onClick = onEndTrip) { Text(stringResource(R.string.action_end)) }
            }
        } ?: Text(stringResource(R.string.personal_travel_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (stationMonitors.isNotEmpty()) {
            Text(stringResource(R.string.personal_travel_monitored_stations), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            stationMonitors.forEach { monitor ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(monitor.localizedSummary(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { onRemoveStationMonitor(monitor.stationId) }) { Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.accessibility_remove_station_monitoring, monitor.stationName)) }
                }
            }
        }
    }
}

@Composable
private fun MobilityPreferencesPanel(value: MobilityPreferences, onChange: (MobilityPreferences) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mobility_preferences_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.mobility_preferences_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.mobility_changes_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = value.maxChanges == null, onClick = { onChange(value.copy(maxChanges = null)) }, label = { Text(stringResource(R.string.mobility_changes_any)) }) }
            items(listOf(0, 1, 2)) { changes -> FilterChip(selected = value.maxChanges == changes, onClick = { onChange(value.copy(maxChanges = changes)) }, label = { Text(if (changes == 0) stringResource(R.string.mobility_changes_direct) else stringResource(R.string.mobility_changes_max, changes)) }) }
        }
        Text(stringResource(R.string.mobility_transfer_margin), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(5, 10, 15, 20)) { minutes -> FilterChip(selected = value.transferMarginMinutes == minutes, onClick = { onChange(value.copy(transferMarginMinutes = minutes)) }, label = { Text(pluralStringResource(R.plurals.minutes_short, minutes, minutes)) }) }
        }
        FilterChip(selected = value.stepFreePreferred, onClick = { onChange(value.copy(stepFreePreferred = !value.stepFreePreferred)) }, label = { Text(stringResource(R.string.mobility_step_free)) })
        FilterChip(selected = value.compactTimetablePreferred, onClick = { onChange(value.copy(compactTimetablePreferred = !value.compactTimetablePreferred)) }, label = { Text(stringResource(R.string.mobility_compact_timetable)) })
        Text(value.localizedSummary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LocalBackupPanel(feedback: String?, onExport: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.local_backup_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.local_backup_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_export)) }
            Button(onClick = onImport, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_import)) }
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun IntegrationsPanel(
    settings: IntegrationSettings,
    voiceReadiness: VoiceProfileSyncReadiness,
    onWearSharingChanged: (Boolean) -> Unit,
    onVoiceProfileChanged: (Boolean) -> Unit,
    onAppleWatchSetupChanged: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.integrations_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.integrations_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IntegrationChoice(
                title = stringResource(R.string.integration_wear_title),
                detail = if (settings.wearSnapshotSharingEnabled) stringResource(R.string.integration_wear_enabled) else stringResource(R.string.integration_wear_disabled),
                selected = settings.wearSnapshotSharingEnabled,
                onClick = { onWearSharingChanged(!settings.wearSnapshotSharingEnabled) }
            )
            IntegrationChoice(
                title = stringResource(R.string.integration_voice_title),
                detail = when (voiceReadiness) {
                    VoiceProfileSyncReadiness.DISABLED -> stringResource(R.string.integration_voice_disabled)
                    VoiceProfileSyncReadiness.ACCOUNT_LINK_REQUIRED -> stringResource(R.string.integration_voice_link_required)
                    VoiceProfileSyncReadiness.READY_FOR_AUTHENTICATED_SYNC -> stringResource(R.string.integration_voice_ready)
                },
                selected = settings.voiceProfileSyncRequested,
                onClick = { onVoiceProfileChanged(!settings.voiceProfileSyncRequested) }
            )
            IntegrationChoice(
                title = stringResource(R.string.integration_apple_watch_title),
                detail = if (settings.appleWatchSetupRequested) stringResource(R.string.integration_apple_watch_enabled) else stringResource(R.string.integration_apple_watch_disabled),
                selected = settings.appleWatchSetupRequested,
                onClick = { onAppleWatchSetupChanged(!settings.appleWatchSetupRequested) }
            )
            Text(stringResource(R.string.integration_alexa_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IntegrationChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val accessibilityLabel = stringResource(R.string.accessibility_title_detail, title, detail)
    Row(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = accessibilityLabel },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilterChip(selected = selected, onClick = onClick, label = { Text(if (selected) stringResource(R.string.state_active) else stringResource(R.string.state_inactive)) })
    }
}

@Composable
private fun RouteListRow(
    route: SavedRoute,
    isActive: Boolean,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onMove: (Int) -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRemove: () -> Unit,
    canRemove: Boolean
) {
    Surface(
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(route.localizedName(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (route.isPinned) Text(stringResource(R.string.route_pinned), style = MaterialTheme.typography.labelMedium, color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary)
                Text(route.localizedDisplayName(Direction.OUTBOUND), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.accessibility_move_route_up, route.localizedName()))
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.accessibility_move_route_down, route.localizedName()))
                }
                IconButton(onClick = onPin) {
                    Icon(if (route.isPinned) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = stringResource(if (route.isPinned) R.string.accessibility_unpin_route else R.string.accessibility_pin_route))
                }
                IconButton(onClick = onRemove, enabled = canRemove) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.accessibility_delete_route, route.localizedName()))
                }
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteComposerSheet(viewModel: TrainViewModel, onDismiss: () -> Unit) {
    var originText by rememberSaveable { mutableStateOf("") }
    var destinationText by rememberSaveable { mutableStateOf("") }
    var routeName by rememberSaveable { mutableStateOf("") }
    val origin = viewModel.stationFromName(originText)
    val destination = viewModel.stationFromName(destinationText)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.route_composer_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.route_composer_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            StationField(
                            label = stringResource(R.string.planner_origin),
                            value = originText,
                            onValueChange = { originText = it; viewModel.updateStationSuggestions("route-composer-origin", it) },
                            matches = viewModel.stationSuggestions("route-composer-origin"),
                            onSelect = { originText = it.name; viewModel.clearStationSuggestions("route-composer-origin") }
                        )
            TextButton(onClick = { val current = originText; originText = destinationText; destinationText = current }) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.route_swap_stations))
            }
            StationField(
                            label = stringResource(R.string.planner_destination),
                            value = destinationText,
                            onValueChange = { destinationText = it; viewModel.updateStationSuggestions("route-composer-destination", it) },
                            matches = viewModel.stationSuggestions("route-composer-destination"),
                            onSelect = { destinationText = it.name; viewModel.clearStationSuggestions("route-composer-destination") }
                        )
            OutlinedTextField(value = routeName, onValueChange = { routeName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.route_name_optional)) }, placeholder = { Text(stringResource(R.string.route_name_example)) })
            Button(
                onClick = { if (viewModel.createRoute(origin!!, destination!!, routeName)) onDismiss() },
                enabled = origin != null && destination != null && origin != destination,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.route_save_and_activate)) }
        }
    }
}
