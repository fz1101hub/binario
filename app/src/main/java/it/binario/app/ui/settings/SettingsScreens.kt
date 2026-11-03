package it.binario.app.ui.settings

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import it.binario.app.ActiveStopAlarmsPanel
import it.binario.app.AlertRepeat
import it.binario.app.AppThemeMode
import it.binario.app.BackgroundStatusPanel
import it.binario.app.BuildConfig
import it.binario.app.CommuterAlert
import it.binario.app.CommuterAlertScheduleResult
import it.binario.app.CommuterAlertScheduler
import it.binario.app.IntegrationSettings
import it.binario.app.LinkifiedText
import it.binario.app.LocalDataBackupCodec
import it.binario.app.MobilityPreferences
import it.binario.app.ProjectLinks
import it.binario.app.R
import it.binario.app.SourcesSettingsPanel
import it.binario.app.TrainViewModel
import it.binario.app.VoiceProfileSyncReadiness
import it.binario.app.gpsalarm.GpsArrivalAlarmFlow
import it.binario.app.gpsalarm.GpsArrivalAlarmPanel
import it.binario.app.localizedLabel
import it.binario.app.openExternalLink
import it.binario.app.resolve
import it.binario.app.ui.common.BinarioCard
import it.binario.app.ui.common.HeaderOverlapBox
import it.binario.app.ui.common.NavigationRow
import it.binario.app.ui.common.RowDivider
import it.binario.app.ui.common.ScreenHeader
import it.binario.app.ui.common.ScreenMargin
import it.binario.app.ui.common.SegmentedControl
import it.binario.app.ui.common.co2Credits
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.common.localizedSummary
import java.time.LocalTime
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Impostazioni: the theme and the existing settings grouped in the rows of the approved render.

/** The groups of "Impostazioni"; "Suggerisci una stazione" is a link row of the screen, not a group. */
internal enum class SettingsSection { ALERTS, WIDGETS, DATA, LANGUAGE, TRAVEL, TICKETS, ABOUT }

/** A row of "Impostazioni": a group of settings, or the station suggestion that opens in the browser. */
internal sealed interface SettingsRow {
    data class Group(val section: SettingsSection) : SettingsRow
    data object SuggestStation : SettingsRow
}

/** The cards of "Impostazioni" below the theme, as in the render: the four groups, then the suggestion and "Informazioni". */
internal val SETTINGS_CARDS: List<List<SettingsRow>> = listOf(
    listOf(SettingsSection.ALERTS, SettingsSection.WIDGETS, SettingsSection.DATA, SettingsSection.LANGUAGE).map { SettingsRow.Group(it) },
    // "Preferenze di viaggio": walks, ranking, kinds of service and wheelchair access (see TravelPreferencesSettings.kt).
    listOf(SettingsRow.Group(SettingsSection.TRAVEL)),
    // "Biglietti e assistenza": official links of the operators (see TicketsAssistance.kt).
    listOf(SettingsRow.Group(SettingsSection.TICKETS)),
    listOf(SettingsRow.SuggestStation, SettingsRow.Group(SettingsSection.ABOUT))
)

@Composable
internal fun SettingsSection.title(): String = stringResource(
    when (this) {
        SettingsSection.ALERTS -> R.string.settings_section_alerts
        SettingsSection.WIDGETS -> R.string.settings_section_widgets
        SettingsSection.DATA -> R.string.settings_section_data
        SettingsSection.LANGUAGE -> R.string.settings_section_language
        SettingsSection.TRAVEL -> R.string.settings_section_travel
        SettingsSection.TICKETS -> R.string.settings_section_tickets
        SettingsSection.ABOUT -> R.string.settings_section_about
    }
)

@Composable
private fun SettingsSection.detail(): String = stringResource(
    when (this) {
        SettingsSection.ALERTS -> R.string.settings_section_alerts_detail
        SettingsSection.WIDGETS -> R.string.settings_section_widgets_detail
        SettingsSection.DATA -> R.string.settings_section_data_detail
        SettingsSection.LANGUAGE -> R.string.settings_section_language_detail
        SettingsSection.TRAVEL -> R.string.settings_section_travel_detail
        SettingsSection.TICKETS -> R.string.settings_section_tickets_detail
        SettingsSection.ABOUT -> R.string.settings_section_about_detail
    }
)

private val SettingsSection.icon: ImageVector
    get() = when (this) {
        SettingsSection.ALERTS -> Icons.Default.NotificationsActive
        SettingsSection.WIDGETS -> Icons.Default.Widgets
        SettingsSection.DATA -> Icons.Default.Sync
        SettingsSection.LANGUAGE -> Icons.Default.Language
        SettingsSection.TRAVEL -> Icons.Default.Tune
        SettingsSection.TICKETS -> Icons.Default.ConfirmationNumber
        SettingsSection.ABOUT -> Icons.Default.Info
    }

/** Order of the render: Chiaro | Scuro | Sistema. */
private val THEME_OPTIONS = listOf(AppThemeMode.LIGHT, AppThemeMode.DARK, AppThemeMode.SYSTEM)

/** "Impostazioni": the theme as a segmented control, then the groups of settings. */
@Composable
internal fun SettingsScreen(
    modifier: Modifier,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    openSection: (SettingsSection) -> Unit
) {
    LazyColumn(modifier.fillMaxSize().testTag("settings-screen")) {
        item { ScreenHeader(title = stringResource(R.string.settings_title), bottomPadding = 8.dp) }
        item {
            HeaderOverlapBox {
                BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.settings_theme), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        SegmentedControl(
                            options = THEME_OPTIONS,
                            selected = themeMode,
                            onSelect = onThemeModeChange,
                            labels = THEME_OPTIONS.map { it.localizedLabel() },
                            tagOf = { "settings-theme-${it.name.lowercase(java.util.Locale.ROOT)}" }
                        )
                    }
                }
            }
        }
        SETTINGS_CARDS.forEachIndexed { cardIndex, rows ->
            item {
                val uriHandler = LocalUriHandler.current
                val issueTitle = stringResource(R.string.settings_suggest_station_issue_title)
                BinarioCard(Modifier.padding(horizontal = ScreenMargin).padding(top = if (cardIndex == 0) 12.dp else 0.dp, bottom = 12.dp)) {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) RowDivider()
                        when (row) {
                            is SettingsRow.Group -> NavigationRow(
                                title = row.section.title(),
                                detail = row.section.detail(),
                                icon = row.section.icon,
                                onClick = { openSection(row.section) },
                                modifier = Modifier.testTag("settings-${row.section.name.lowercase(java.util.Locale.ROOT)}")
                            )
                            // The project has no e-mail contact: the row opens its public issue form with a prefilled title.
                            SettingsRow.SuggestStation -> NavigationRow(
                                title = stringResource(R.string.settings_suggest_station),
                                detail = stringResource(R.string.settings_suggest_station_detail),
                                icon = Icons.Default.AddLocationAlt,
                                onClick = { openExternalLink(uriHandler, ProjectLinks.newIssue("$issueTitle ")) },
                                modifier = Modifier.testTag("settings-suggest-station"),
                                trailingIcon = Icons.AutoMirrored.Filled.OpenInNew
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** One group of settings, with the panels that existed before in "Per te". */
@Composable
internal fun SettingsSectionScreen(
    modifier: Modifier,
    section: SettingsSection,
    viewModel: TrainViewModel,
    gpsArrivalAlarm: GpsArrivalAlarmFlow,
    onThemeClick: () -> Unit
) {
    LazyColumn(modifier.fillMaxSize().testTag("settings-section-${section.name.lowercase(java.util.Locale.ROOT)}")) {
        item { ScreenHeader(title = section.title(), subtitle = section.detail(), bottomPadding = 8.dp) }
        item {
            HeaderOverlapBox {
                BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        when (section) {
                            SettingsSection.ALERTS -> AlertsSettings(viewModel, gpsArrivalAlarm)
                            SettingsSection.WIDGETS -> WidgetSettings(viewModel)
                            SettingsSection.DATA -> DataSettings(viewModel)
                            SettingsSection.LANGUAGE -> LanguageSettings(viewModel, onThemeClick)
                            SettingsSection.TRAVEL -> TravelPreferencesSettings()
                            SettingsSection.TICKETS -> TicketsAssistanceSettings()
                            SettingsSection.ABOUT -> AboutSettings()
                        }
                    }
                }
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
private fun AlertsSettings(viewModel: TrainViewModel, gpsArrivalAlarm: GpsArrivalAlarmFlow) {
    var showAlertComposer by rememberSaveable { mutableStateOf(false) }
    var pendingAlert by rememberSaveable(stateSaver = commuterAlertSaver) {
        mutableStateOf<CommuterAlert?>(null)
    }
    var alertFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val alert = pendingAlert
        pendingAlert = null
        if (granted && alert != null) {
            alertFeedback = commuterAlertScheduleFeedback(resources, CommuterAlertScheduler.schedule(context, alert))
        } else {
            alertFeedback = resources.getString(R.string.notification_permission_required)
        }
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
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeading(stringResource(R.string.for_you_alarms))
        Text(stringResource(R.string.for_you_stop_alarm_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BackgroundStatusPanel()
    }
    ActiveStopAlarmsPanel()
    GpsArrivalAlarmPanel(gpsArrivalAlarm)
    CommuterAlertsPanel(
        alerts = viewModel.commuterAlerts,
        feedback = alertFeedback,
        canAdd = viewModel.hasActiveRoute,
        onAdd = { showAlertComposer = true },
        onRemove = viewModel::removeCommuterAlert
    )
    it.binario.app.notifications.LiveUpdatesSettingsPanel()
}

@Composable
private fun WidgetSettings(viewModel: TrainViewModel) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(stringResource(R.string.settings_widgets_title))
        Text(stringResource(R.string.settings_widgets_route), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.settings_widgets_station), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.settings_widgets_trains), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.settings_widgets_refresh), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.settings_widgets_how), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    IntegrationsPanel(
        settings = viewModel.integrationSettings,
        voiceReadiness = viewModel.voiceProfileSyncReadiness(),
        onWearSharingChanged = viewModel::setWearSnapshotSharing,
        onVoiceProfileChanged = viewModel::setVoiceProfileSyncRequested,
        onAppleWatchSetupChanged = viewModel::setAppleWatchSetupRequested
    )
}

@Composable
private fun DataSettings(viewModel: TrainViewModel) {
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
    SourcesSettingsPanel()
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeading(stringResource(R.string.settings_privacy_title))
        Text(stringResource(R.string.settings_privacy_local_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.settings_privacy_notifications_voice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.settings_privacy_wear), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.settings_privacy_maps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LocalBackupPanel(
        feedback = backupFeedback,
        onExport = { createBackupDocument.launch("binario-backup.json") },
        onImport = { openBackupDocument.launch(arrayOf(LocalDataBackupCodec.MIME_TYPE, "text/plain")) }
    )
}

@Composable
private fun LanguageSettings(viewModel: TrainViewModel, onThemeClick: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(stringResource(R.string.settings_language_title))
        Text(stringResource(R.string.settings_language_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AppLanguagePicker(Modifier.testTag("settings-language-picker"))
            // The same list (locale_config.xml) in the system settings, where Android also keeps the choice.
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("settings-language-open")
            ) {
                Icon(Icons.Default.Language, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_language_open))
            }
        } else {
            Text(stringResource(R.string.settings_language_system), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    RowDivider()
    NavigationRow(
        title = stringResource(R.string.settings_theme_title),
        detail = stringResource(R.string.settings_theme_description),
        icon = Icons.Default.Palette,
        onClick = onThemeClick
    )
    RowDivider()
    MobilityPreferencesPanel(
        value = viewModel.mobilityPreferences,
        onChange = viewModel::updateMobilityPreferences
    )
}

@Composable
private fun AboutSettings() {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(stringResource(R.string.app_name))
        Text(stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
        SectionHeading(stringResource(R.string.for_you_credits))
        Text(stringResource(R.string.settings_privacy_osm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.settings_credits_maps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Offline Trenitalia and Italo timetables (CC BY 4.0 GTFS conversion of the NAP data): attribution with links.
        val creditStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinkifiedText(stringResource(R.string.settings_credits_trenitalia), style = creditStyle)
        LinkifiedText(stringResource(R.string.settings_credits_italo), style = creditStyle)
        // Every other timetable pack of this build (gtfs/, gtfs-transit/, and gtfs-personal/ in a personal build):
        // licensor, licence and source page, generated from the manifests (GtfsPackCredits.kt, audit A7-03).
        val transitCredits = remember { it.binario.app.data.gtfs.GtfsPackCredits.lines() }
        if (transitCredits.isNotEmpty()) {
            Text(stringResource(R.string.settings_credits_transit), style = creditStyle)
            transitCredits.forEach { credit -> LinkifiedText(credit, style = creditStyle) }
        }
        // Emission factors of the CO2 estimate, with their source (co2/Co2Estimate.kt).
        LinkifiedText(co2Credits(), style = creditStyle)
        Text(stringResource(R.string.settings_privacy_local_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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
        feedback?.let { AnnouncedText(it) }
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
        // Scrolls at 200 % font size or above the keyboard, so "Salva" is never cut off.
        Column(
            Modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.commuter_alert_sheet_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(routeLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = station, onValueChange = { station = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.commuter_alert_station)) })
            OutlinedButton(onClick = {
                val parsed = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.of(8, 0))
                TimePickerDialog(context, { _, hour, minute -> time = commuterAlertTime(hour, minute) }, parsed.hour, parsed.minute, true).show()
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

/** The stored "HH:mm" of a commuter alert, in ASCII digits whatever the app language (Arabic, Hindi, Burmese). */
internal fun commuterAlertTime(hour: Int, minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

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
        feedback?.let { AnnouncedText(it) }
    }
}

/** A confirmation or error after an action (alert saved, backup imported): TalkBack announces it. */
@Composable
private fun AnnouncedText(text: String) {
    Text(
        text,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun IntegrationsPanel(
    settings: IntegrationSettings,
    voiceReadiness: VoiceProfileSyncReadiness,
    onWearSharingChanged: (Boolean) -> Unit,
    onVoiceProfileChanged: (Boolean) -> Unit,
    onAppleWatchSetupChanged: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.integrations_title), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
    // Title and detail are read once, as the label; the chip stays its own control.
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = accessibilityLabel },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilterChip(selected = selected, onClick = onClick, label = { Text(if (selected) stringResource(R.string.state_active) else stringResource(R.string.state_inactive)) })
    }
}

