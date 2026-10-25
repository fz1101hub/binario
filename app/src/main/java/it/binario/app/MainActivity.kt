package it.binario.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.speech.RecognizerIntent
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.binario.app.data.RailTime
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import it.binario.app.data.DataConfidence
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.repository.StationBoardRunDetails
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.OperationalRouteChange
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.plannedjourney.PlannedJourneyAgendaScreen
import it.binario.app.plannedjourney.PlannedJourneyExportFormat
import it.binario.app.plannedjourney.PlannedJourneyIcsContract
import it.binario.app.plannedjourney.PlannedJourneyIcsText
import it.binario.app.plannedjourney.PlannedJourneyRepository
import it.binario.app.plannedjourney.PlannedJourneyStation
import it.binario.app.plannedjourney.PlannedJourneyTransferContract
import it.binario.app.plannedjourney.PlannedJourneyViewModel
import it.binario.app.plannedjourney.SharedPreferencesPlannedJourneyStorage
import it.binario.app.widget.WidgetConfigurationStore
import it.binario.app.widget.WidgetStateStore
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { PLANNER, OPERATIONS, AGENDA, SETTINGS, RESULTS, NUMBER_RESULTS, DETAIL }
internal enum class OperationsSection { TRAINS, STATIONS }
private enum class SearchMode { ROUTE, TRAIN, STATION }

@Composable
private fun OperationsSection.localizedLabel(): String = stringResource(
    if (this == OperationsSection.TRAINS) R.string.operations_section_trains
    else R.string.operations_section_stations
)

@Composable
private fun SearchMode.localizedLabel(): String = stringResource(
    when (this) {
        SearchMode.ROUTE -> R.string.search_mode_route
        SearchMode.TRAIN -> R.string.search_mode_train
        SearchMode.STATION -> R.string.search_mode_station
    }
)

private val stopAlarmPlanSaver = listSaver<StopAlarmPlan?, Any>(
    save = { plan ->
        if (plan == null) emptyList()
        else listOf(plan.trainNumber, plan.destination, plan.station, plan.expectedTime, plan.triggerAtEpochMillis)
    },
    restore = { values ->
        if (values.isEmpty()) null
        else StopAlarmPlan(
            trainNumber = values[0] as String,
            destination = values[1] as String,
            station = values[2] as String,
            expectedTime = values[3] as String,
            triggerAtEpochMillis = values[4] as Long
        )
    }
)

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

class MainActivity : ComponentActivity() {
    private val deepLinkDispatcher = DeepLinkRequestDispatcher()
    private var deepLinkRequest by mutableStateOf<DeepLinkRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // An Activity recreated from saved state must restore its UI, not replay the
        // launch Intent and navigate away from the screen the user was using.
        if (savedInstanceState == null) receiveDeepLinkIntent(intent)
        CommuterAlertScheduler.rescheduleAll(applicationContext)
        enableEdgeToEdge()
        setContent {
            val themeSettings = remember { ThemeSettings(applicationContext) }
            BinarioTheme(themeSettings.mode) {
                val viewModel: TrainViewModel = viewModel()
                BinarioApp(
                    viewModel = viewModel,
                    themeMode = themeSettings.mode,
                    onThemeModeChange = themeSettings::selectMode,
                    deepLinkRequest = deepLinkRequest,
                    onDeepLinkConsumed = ::consumeDeepLink
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveDeepLinkIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        it.binario.app.plannedjourney.AndroidPlannedJourneyReminders.requestRefresh(applicationContext)
    }

    private fun receiveDeepLinkIntent(intent: Intent?) {
        val widgetDeepLink = parseWidgetDeepLink(intent, applicationContext)
        deepLinkRequest = if (widgetDeepLink != null) {
            deepLinkDispatcher.receive(widgetDeepLink)
        } else {
            deepLinkDispatcher.receive(intent?.action, intent?.dataString)
        }
    }

    private fun consumeDeepLink(requestId: Long) {
        if (deepLinkDispatcher.consume(requestId)) deepLinkRequest = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BinarioApp(
    viewModel: TrainViewModel,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    deepLinkRequest: DeepLinkRequest?,
    onDeepLinkConsumed: (Long) -> Unit
) {
    var screen by rememberSaveable { mutableStateOf(Screen.PLANNER) }
    var detailReturnScreen by rememberSaveable { mutableStateOf(Screen.RESULTS) }
    var operationsSectionName by rememberSaveable { mutableStateOf(OperationsSection.TRAINS.name) }
    var plannerSearchModeName by rememberSaveable { mutableStateOf(SearchMode.ROUTE.name) }
    var plannerSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var showThemePicker by rememberSaveable { mutableStateOf(false) }
    var pendingStopAlarm by rememberSaveable(stateSaver = stopAlarmPlanSaver) {
        mutableStateOf<StopAlarmPlan?>(null)
    }
    var stopAlarmFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, screen, operationsSectionName) {
        if (screen in setOf(Screen.RESULTS, Screen.NUMBER_RESULTS, Screen.DETAIL, Screen.OPERATIONS)) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (screen == Screen.OPERATIONS && operationsSectionName == OperationsSection.STATIONS.name) {
                    viewModel.refreshStationBoardIfIdle()
                }
                while (true) {
                    delay(30_000)
                    if (screen == Screen.NUMBER_RESULTS) {
                        viewModel.searchTrainNumber(refresh = true)
                    } else if (screen == Screen.OPERATIONS && operationsSectionName == OperationsSection.STATIONS.name) {
                        viewModel.refreshStationBoardIfIdle()
                    } else viewModel.refreshLiveIfIdle()
                }
            }
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val trainShareStrings = trainShareStrings(locale)
    val plannedJourneyViewModel: PlannedJourneyViewModel = viewModel(
        factory = PlannedJourneyViewModel.factory(context.applicationContext)
    )
    val resources = LocalResources.current
    val plannedJourneyExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val format = plannedJourneyViewModel.consumePendingExport()
        if (uri != null && format != null) {
            runCatching {
                val content = plannedJourneyViewModel.export(
                    format,
                    PlannedJourneyIcsText(
                        eventDescription = resources.getString(R.string.planned_journey_ics_event_description),
                        reminderDescription = resources.getString(R.string.planned_journey_ics_reminder_description)
                    )
                )
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                    ?: error(resources.getString(R.string.backup_open_output_error))
            }.onSuccess {
                plannedJourneyViewModel.note(UiMessage(UiMessageKey.PLANNED_JOURNEY_EXPORT_SUCCESS))
            }.onFailure {
                plannedJourneyViewModel.note(UiMessage(UiMessageKey.PLANNED_JOURNEY_EXPORT_FAILURE))
            }
        }
    }
    val plannedJourneyImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    readBoundedPlannedJourneyDocument(
                        input = it,
                        tooLargeMessage = maxOf(
                            PlannedJourneyTransferContract.MAX_BYTES,
                            PlannedJourneyIcsContract.MAX_BYTES
                        ).let { limit ->
                            resources.getQuantityString(R.plurals.planned_journey_import_too_large, limit, limit)
                        }
                    )
                } ?: error(resources.getString(R.string.backup_read_input_error))
            }.onSuccess { content ->
                plannedJourneyViewModel.previewImport(
                    content = content,
                    isIcs = content.trimStart().startsWith("BEGIN:VCALENDAR")
                )
            }.onFailure {
                plannedJourneyViewModel.note(UiMessage(UiMessageKey.PLANNED_JOURNEY_IMPORT_FAILURE))
            }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val plan = pendingStopAlarm
        pendingStopAlarm = null
        if (granted && plan != null) {
            stopAlarmFeedback = stopAlarmScheduleFeedback(resources, StopAlarmScheduler.schedule(context, plan), plan)
        } else {
            stopAlarmFeedback = resources.getString(R.string.stop_alarm_permission_required)
        }
    }
    val scheduleStopAlarm: (TrainService, TrainStop) -> Unit = { service, stop ->
        val plan = StopAlarmPlanner.plan(service, stop)
        when {
            stop.completed -> stopAlarmFeedback = resources.getString(R.string.stop_alarm_completed, stop.station)
            plan == null -> stopAlarmFeedback = resources.getString(R.string.stop_alarm_unavailable)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                pendingStopAlarm = plan
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> {
                stopAlarmFeedback = stopAlarmScheduleFeedback(resources, StopAlarmScheduler.schedule(context, plan), plan)
            }
        }
    }
    LaunchedEffect(deepLinkRequest?.id) {
        val request = deepLinkRequest ?: return@LaunchedEffect
        when (val deepLink = request.deepLink) {
            BinarioDeepLink.Agenda -> {
                plannedJourneyViewModel.selectFilter(it.binario.app.plannedjourney.PlannedJourneyAgendaFilter.UPCOMING)
                screen = Screen.AGENDA
            }
            BinarioDeepLink.ActiveRoute, BinarioDeepLink.NextDepartures -> {
                operationsSectionName = OperationsSection.TRAINS.name
                screen = Screen.OPERATIONS
            }
            is BinarioDeepLink.TrainSearch -> {
                viewModel.updateTrainNumberQuery(deepLink.number.orEmpty())
                plannerSearchModeName = SearchMode.TRAIN.name
                plannerSearchRequestId += 1
                screen = Screen.PLANNER
            }
            is BinarioDeepLink.WidgetRoute -> {
                viewModel.activateRoute(deepLink.route, deepLink.direction)
                operationsSectionName = OperationsSection.TRAINS.name
                screen = Screen.OPERATIONS
            }
        }
        // Clear the event only after applying it. Subsequent recompositions and
        // lifecycle restores therefore cannot replay the same external navigation.
        onDeepLinkConsumed(request.id)
    }
    val plannerSearchMode = SearchMode.valueOf(plannerSearchModeName)
    val navigateBack = {
        when (screen) {
            Screen.DETAIL -> {
                viewModel.closeService()
                screen = detailReturnScreen
            }
            Screen.RESULTS, Screen.NUMBER_RESULTS -> screen = Screen.PLANNER
            else -> Unit
        }
    }
    BackHandler(enabled = screen == Screen.DETAIL || screen == Screen.RESULTS || screen == Screen.NUMBER_RESULTS, onBack = navigateBack)
    val title = stringResource(
        when (screen) {
            Screen.PLANNER -> R.string.screen_title_planner
            Screen.OPERATIONS -> R.string.screen_title_operations
            Screen.AGENDA -> R.string.screen_title_agenda
            Screen.SETTINGS -> R.string.screen_title_settings
            Screen.RESULTS -> R.string.screen_title_results
            Screen.NUMBER_RESULTS -> R.string.train_number_results_title
            Screen.DETAIL -> R.string.screen_title_detail
        }
    )
    val subtitle = stringResource(
        when (screen) {
            Screen.PLANNER -> R.string.screen_subtitle_planner
            Screen.OPERATIONS -> R.string.screen_subtitle_operations
            Screen.AGENDA -> R.string.screen_subtitle_agenda
            Screen.SETTINGS -> R.string.screen_subtitle_settings
            Screen.RESULTS -> R.string.screen_subtitle_results
            Screen.NUMBER_RESULTS -> R.string.train_number_results_subtitle
            Screen.DETAIL -> R.string.screen_subtitle_detail
        }
    )

    if (showThemePicker) {
        ThemePickerSheet(currentMode = themeMode, onModeSelected = {
            onThemeModeChange(it)
            showThemePicker = false
        }, onDismiss = { showThemePicker = false })
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (screen == Screen.PLANNER) stringResource(R.string.app_brand) else title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, letterSpacing = if (screen == Screen.PLANNER) 1.sp else 0.sp)
                        if (screen != Screen.DETAIL) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    if (screen == Screen.DETAIL || screen == Screen.RESULTS || screen == Screen.NUMBER_RESULTS) {
                        IconButton(onClick = navigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showThemePicker = true }) {
                        Icon(Icons.Default.Palette, contentDescription = stringResource(R.string.action_choose_theme, themeMode.localizedLabel()))
                    }
                    if (screen == Screen.OPERATIONS || screen == Screen.RESULTS || screen == Screen.NUMBER_RESULTS || screen == Screen.DETAIL) {
                        IconButton(onClick = {
                            if (screen == Screen.NUMBER_RESULTS) viewModel.searchTrainNumber(refresh = true)
                            else if (screen == Screen.OPERATIONS && operationsSectionName == OperationsSection.STATIONS.name) {
                                viewModel.refreshStationBoardIfIdle()
                            } else viewModel.refresh()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh_rail_information))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            if (screen != Screen.DETAIL && screen != Screen.RESULTS && screen != Screen.NUMBER_RESULTS) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = screen == Screen.PLANNER,
                        onClick = { screen = Screen.PLANNER },
                        icon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_search)) }
                    )
                    NavigationBarItem(
                        selected = screen == Screen.OPERATIONS,
                        onClick = { screen = Screen.OPERATIONS },
                        icon = { Icon(Icons.Default.Train, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_operations)) }
                    )
                    NavigationBarItem(
                        selected = screen == Screen.AGENDA,
                        onClick = { screen = Screen.AGENDA },
                        icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_agenda)) }
                    )
                    NavigationBarItem(
                        selected = screen == Screen.SETTINGS,
                        onClick = { screen = Screen.SETTINGS },
                        icon = { Icon(Icons.Default.Info, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_settings)) }
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when (screen) {
            Screen.PLANNER -> PlannerScreen(
                modifier = Modifier.padding(padding),
                viewModel = viewModel,
                initialMode = plannerSearchMode,
                modeRequestId = plannerSearchRequestId,
                showResults = { screen = Screen.RESULTS },
                showTrainResults = { screen = Screen.NUMBER_RESULTS },
                openMore = { screen = Screen.SETTINGS },
                openStations = { station ->
                    station?.let(viewModel::openStationBoard)
                    operationsSectionName = OperationsSection.STATIONS.name
                    screen = Screen.OPERATIONS
                }
            )
            Screen.OPERATIONS -> OperationsScreen(
                modifier = Modifier.padding(padding),
                viewModel = viewModel,
                selectedSection = OperationsSection.valueOf(operationsSectionName),
                onSectionSelected = { operationsSectionName = it.name },
                openDetails = { service -> detailReturnScreen = Screen.OPERATIONS; viewModel.openService(service); screen = Screen.DETAIL },
                openRoutes = { screen = Screen.SETTINGS },
                openSearch = { screen = Screen.PLANNER }
            )
            Screen.AGENDA -> PlannedJourneyAgendaScreen(
                modifier = Modifier.padding(padding),
                viewModel = plannedJourneyViewModel,
                stations = viewModel.stations.map { station ->
                    PlannedJourneyStation(station.id, station.name, station.railwayCode)
                },
                onExportJson = {
                    plannedJourneyViewModel.prepareExport(PlannedJourneyExportFormat.JSON)
                    plannedJourneyExportLauncher.launch(PlannedJourneyExportFormat.JSON.suggestedName)
                },
                onExportIcs = {
                    plannedJourneyViewModel.prepareExport(PlannedJourneyExportFormat.ICS)
                    plannedJourneyExportLauncher.launch(PlannedJourneyExportFormat.ICS.suggestedName)
                },
                onImportRequested = {
                    plannedJourneyImportLauncher.launch(arrayOf("application/json", "text/calendar", "text/plain"))
                }
            )
            Screen.SETTINGS -> SettingsScreen(
                modifier = Modifier.padding(padding),
                viewModel = viewModel,
                onThemeClick = { showThemePicker = true },
                openRoute = {
                    viewModel.activateRoute(it)
                    operationsSectionName = OperationsSection.TRAINS.name
                    screen = Screen.OPERATIONS
                }
            )
            Screen.RESULTS -> RouteResultsScreen(
                modifier = Modifier.padding(padding),
                viewModel = viewModel,
                openDetails = { service -> detailReturnScreen = Screen.RESULTS; viewModel.openService(service); screen = Screen.DETAIL }
            )
            Screen.NUMBER_RESULTS -> TrainNumberResultsScreen(
                modifier = Modifier.padding(padding), viewModel = viewModel,
                onOpen = { match -> detailReturnScreen = Screen.NUMBER_RESULTS; viewModel.openTrainNumberMatch(match); screen = Screen.DETAIL }
            )
            Screen.DETAIL -> {
                val service = viewModel.selectedService
                val detailProvider = viewModel.currentProviderLabel() ?: stringResource(R.string.provider_unavailable)
                val detailConfidence = viewModel.currentConfidence()?.localizedLabel() ?: stringResource(R.string.data_confidence_unavailable)
                if (service == null) {
                    // The selected service is intentionally in-memory. After process
                    // death, a restored DETAIL screen returns to its saved parent
                    // instead of showing an empty screen or recreating stale data.
                    LaunchedEffect(detailReturnScreen) { screen = detailReturnScreen }
                } else ServiceDetailScreen(
                    modifier = Modifier.padding(padding),
                    service = service,
                    provider = detailProvider,
                    confidence = detailConfidence,
                    freshness = viewModel.snapshot?.freshness,
                    refreshedAt = viewModel.lastRefreshEpochMillis(),
                    isDemonstration = viewModel.isDemonstrationData(),
                    onRefresh = viewModel::refresh,
                    isMonitored = viewModel.isMonitoring(service),
                    onToggleMonitoring = { viewModel.toggleServiceMonitoring(service) },
                    onScheduleStopAlarm = scheduleStopAlarm,
                    alarmFeedback = stopAlarmFeedback,
                    activeTrip = viewModel.activeTrip,
                    onStartActiveTrip = { viewModel.startActiveTrip(service) },
                    onAdvanceActiveTrip = viewModel::advanceActiveTrip,
                    onEndActiveTrip = viewModel::endActiveTrip,
                    onShare = {
                        val content = TrainShareFormatter.create(
                            service = service,
                            provider = detailProvider,
                            confidence = detailConfidence,
                            freshness = viewModel.snapshot?.freshness,
                            refreshedAt = viewModel.lastRefreshEpochMillis(),
                            isDemonstration = viewModel.isDemonstrationData(),
                            strings = trainShareStrings
                        )
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TITLE, content.title)
                                    .putExtra(Intent.EXTRA_TEXT, content.text),
                                resources.getString(R.string.share_train_chooser)
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun trainShareStrings(locale: Locale): TrainShareStrings = TrainShareStrings(
    locale = locale,
    title = stringResource(R.string.share_train_title),
    detailTitle = stringResource(R.string.share_detail_title),
    operatorCategory = stringResource(R.string.share_operator_category),
    route = stringResource(R.string.share_route),
    expectedDeparture = stringResource(R.string.share_expected_departure),
    scheduledDeparture = stringResource(R.string.share_scheduled_departure),
    platform = stringResource(R.string.share_platform),
    platformToConfirm = stringResource(R.string.platform_to_confirm),
    status = stringResource(R.string.share_status),
    stopsTitle = stringResource(R.string.service_stops_title),
    stop = stringResource(R.string.share_stop),
    stopPlatform = stringResource(R.string.share_stop_platform),
    stopDelay = stringResource(R.string.share_stop_delay),
    data = stringResource(R.string.share_data),
    source = stringResource(R.string.share_source),
    updated = stringResource(R.string.share_updated),
    neverUpdated = stringResource(R.string.share_never_updated),
    demonstrationData = stringResource(R.string.data_status_demo),
    scheduledData = stringResource(R.string.share_scheduled_data),
    unavailableData = stringResource(R.string.data_status_unavailable),
    liveData = stringResource(R.string.share_live_data),
    statusLabels = mapOf(
        ServiceStatus.ON_TIME to stringResource(R.string.share_status_on_time),
        ServiceStatus.DELAYED to stringResource(R.string.share_status_delayed),
        ServiceStatus.CANCELLED to stringResource(R.string.share_status_cancelled)
    ),
    categoryLabels = mapOf(
        TrainCategory.REGIONALE to stringResource(R.string.share_category_regionale),
        TrainCategory.REGIONALE_VELOCE to stringResource(R.string.share_category_regionale_veloce),
        TrainCategory.INTERCITY to stringResource(R.string.share_category_intercity),
        TrainCategory.FRECCE to stringResource(R.string.share_category_frecce),
        TrainCategory.AV_ITALO to stringResource(R.string.share_category_av_italo),
        TrainCategory.TRENORD_REGIO to stringResource(R.string.share_category_trenord_regio),
        TrainCategory.METROPOLITANO to stringResource(R.string.share_category_metropolitano),
        TrainCategory.OTHER to stringResource(R.string.share_category_other)
    ),
    freshnessLabels = mapOf(
        Freshness.FRESH to stringResource(R.string.share_freshness_fresh),
        Freshness.AGING to stringResource(R.string.share_freshness_aging),
        Freshness.STALE to stringResource(R.string.share_freshness_stale),
        Freshness.UNAVAILABLE to stringResource(R.string.share_freshness_unavailable)
    )
)


private fun readBoundedPlannedJourneyDocument(input: java.io.InputStream, tooLargeMessage: String): String {
    val limit = maxOf(PlannedJourneyTransferContract.MAX_BYTES, PlannedJourneyIcsContract.MAX_BYTES)
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        require(output.size() + read <= limit) { tooLargeMessage }
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun stopAlarmScheduleFeedback(resources: android.content.res.Resources, result: DeviceAlarmScheduleResult, plan: StopAlarmPlan): String =
    if (result.isExact) {
        resources.getString(R.string.stop_alarm_exact, plan.station, plan.expectedTime)
    } else {
        resources.getString(R.string.stop_alarm_inexact, plan.station)
    }

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

@Composable
private fun TodayScreen(
    modifier: Modifier,
    viewModel: TrainViewModel,
    openDetails: (TrainService) -> Unit,
    openRoutes: () -> Unit,
    openSearch: () -> Unit
) {
    LaunchedEffect(Unit) { viewModel.reconcileWidgetState() }
    val services = viewModel.homeServices()
    val firstService = services.firstOrNull()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item { BoardCommandBar(onOpenSearch = { openSearch() }) }
        item { BoardRouteHeader(viewModel.activeRoute, viewModel.direction, viewModel.refreshState, viewModel::switchDirection, viewModel::refresh, openRoutes) }
        item { BoardColumnHeader() }
        if (firstService != null) {
            items(services, key = { it.id }) { service ->
                DepartureListRow(service, isNext = service.id == firstService.id, onOpen = { openDetails(service) })
            }
        } else {
            item {
                TodayEmptyState(
                    state = viewModel.refreshState,
                    message = viewModel.refreshMessage,
                    onRefresh = viewModel::refresh,
                    onRoutes = openRoutes
                )
            }
        }
        if (viewModel.savedRoutes.size > 1) {
            item { MonitoredRoutesStrip(viewModel.savedRoutes, viewModel.activeRoute.id, viewModel::activateRoute, openRoutes) }
        }
        item {
            DataStatusLine(
                freshness = viewModel.snapshot?.freshness,
                provider = viewModel.currentProviderLabel() ?: stringResource(R.string.provider_unavailable),
                confidence = viewModel.currentConfidence()?.localizedLabel() ?: stringResource(R.string.data_confidence_unavailable),
                refreshedAt = viewModel.lastRefreshEpochMillis(),
                isDemonstration = viewModel.isDemonstrationData(),
                message = viewModel.refreshMessage
            )
        }
        item {
            Text(
                text = stringResource(R.string.official_information_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp)
            )
        }
    }
}

@Composable
private fun OperationsScreen(
    modifier: Modifier,
    viewModel: TrainViewModel,
    selectedSection: OperationsSection,
    onSectionSelected: (OperationsSection) -> Unit,
    openDetails: (TrainService) -> Unit,
    openRoutes: () -> Unit,
    openSearch: () -> Unit
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OperationsSectionCard(
                modifier = Modifier.weight(1f),
                section = OperationsSection.TRAINS,
                selected = selectedSection == OperationsSection.TRAINS,
                onClick = { onSectionSelected(OperationsSection.TRAINS) }
            )
            OperationsSectionCard(
                modifier = Modifier.weight(1f),
                section = OperationsSection.STATIONS,
                selected = selectedSection == OperationsSection.STATIONS,
                onClick = { onSectionSelected(OperationsSection.STATIONS) }
            )
        }
        when (selectedSection) {
            OperationsSection.TRAINS -> TodayScreen(
                modifier = Modifier.weight(1f),
                viewModel = viewModel,
                openDetails = openDetails,
                openRoutes = openRoutes,
                openSearch = openSearch
            )
            OperationsSection.STATIONS -> StationBoardScreen(Modifier.weight(1f), viewModel)
        }
    }
}

@Composable
internal fun OperationsSectionCard(
    modifier: Modifier,
    section: OperationsSection,
    selected: Boolean,
    onClick: () -> Unit
) {
    val label = section.localizedLabel()
    val subtitle = stringResource(
        if (section == OperationsSection.TRAINS) R.string.operations_section_trains_subtitle
        else R.string.operations_section_stations_subtitle
    )
    val selectionState = stringResource(
        if (selected) R.string.accessibility_section_selected else R.string.accessibility_section_not_selected
    )
    val icon = if (section == OperationsSection.TRAINS) Icons.Default.Train else Icons.Default.Route
    Surface(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = selectionState },
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun BoardCommandBar(onOpenSearch: () -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        item { AssistChip(onClick = onOpenSearch, label = { Text(stringResource(R.string.action_new_search)) }, leadingIcon = {
                Icon(Icons.Default.Train, contentDescription = null)
            }) }
        item { AssistChip(onClick = onOpenSearch, label = { Text(stringResource(R.string.action_plan_route)) }, leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            }) }
    }
}

@Composable
private fun PlannerScreen(
    modifier: Modifier,
    viewModel: TrainViewModel,
    initialMode: SearchMode,
    modeRequestId: Int,
    showResults: () -> Unit,
    showTrainResults: () -> Unit,
    openMore: () -> Unit,
    openStations: (Station?) -> Unit
) {
    var originText by rememberSaveable { mutableStateOf(viewModel.plannerOriginText()) }
    var destinationText by rememberSaveable { mutableStateOf(viewModel.plannerDestinationText()) }
    var returnJourney by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var stationText by rememberSaveable { mutableStateOf("") }
    var travelDate by rememberSaveable { mutableStateOf(LocalDate.now(RailTime.defaultZone).toString()) }
    var travelTime by rememberSaveable { mutableStateOf(LocalTime.now(RailTime.defaultZone).withSecond(0).withNano(0).toString()) }
    var returnDate by rememberSaveable { mutableStateOf(LocalDate.now(RailTime.defaultZone).plusDays(1).toString()) }
    var returnTime by rememberSaveable { mutableStateOf(LocalTime.now(RailTime.defaultZone).withSecond(0).withNano(0).toString()) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showReturnDatePicker by rememberSaveable { mutableStateOf(false) }
    var showReturnTimePicker by rememberSaveable { mutableStateOf(false) }
    var speechMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var voiceTargetName by rememberSaveable { mutableStateOf(SearchMode.ROUTE.name) }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val resources = LocalResources.current
    val dateUnselected = stringResource(R.string.planner_date_unselected)
    val timeUnselected = stringResource(R.string.planner_time_unselected)
    LaunchedEffect(modeRequestId) { mode = initialMode }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val voiceTarget = SearchMode.valueOf(voiceTargetName)
        val spoken = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else null
        if (spoken.isNullOrBlank()) {
            speechMessage = resources.getString(R.string.voice_no_text)
        } else {
            val dictationTarget = when (voiceTarget) {
                SearchMode.ROUTE -> DictationTarget.ROUTE
                SearchMode.TRAIN -> DictationTarget.TRAIN
                SearchMode.STATION -> DictationTarget.STATION
            }
            val review = PlannerVoiceParser.review(spoken, viewModel.stations, dictationTarget)
            val dictated = review.input
            if (review.status == DictationReviewStatus.CONFIRMED) when (voiceTarget) {
                SearchMode.ROUTE -> {
                    dictated.origin?.let { originText = it.name }
                    dictated.destination?.let { destinationText = it.name }
                    dictated.date?.let { travelDate = it.toString() }
                    dictated.time?.let { travelTime = it.toString() }
                    dictated.returnJourney?.let { requestedReturn ->
                        returnJourney = requestedReturn
                        if (requestedReturn) {
                            val outboundDate = PlannerDateTime.dateOrToday(travelDate)
                            val outboundTime = PlannerDateTime.timeOrNow(travelTime)
                            val currentReturnDate = PlannerDateTime.dateOrToday(returnDate)
                            val currentReturnTime = PlannerDateTime.timeOrNow(returnTime)
                            if (!PlannerDateTime.isReturnAfterDeparture(outboundDate, outboundTime, currentReturnDate, currentReturnTime)) {
                                returnDate = outboundDate.plusDays(1).toString()
                                returnTime = outboundTime.toString()
                            }
                        }
                    }
                }
                SearchMode.TRAIN -> {
                    dictated.trainNumber?.let(viewModel::updateTrainNumberQuery)
                }
                SearchMode.STATION -> {
                    (dictated.station ?: dictated.origin)?.let { stationText = it.name }
                }
            }
            speechMessage = review.feedback(dictationTarget).resolve(resources)
        }
    }
    fun startVoiceInput(target: SearchMode) {
        voiceTargetName = target.name
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, when (target) {
                SearchMode.ROUTE -> resources.getString(R.string.voice_prompt_route)
                SearchMode.TRAIN -> resources.getString(R.string.voice_prompt_train)
                SearchMode.STATION -> resources.getString(R.string.voice_prompt_station)
            })
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            speechMessage = resources.getString(R.string.voice_unavailable)
        } else voiceLauncher.launch(intent)
    }
    if (showDatePicker) {
        val selected = PlannerDateTime.dateOrToday(travelDate)
        LaunchedEffect(selected) {
            DatePickerDialog(context, { _, year, month, day ->
                travelDate = LocalDate.of(year, month + 1, day).toString()
                showDatePicker = false
            }, selected.year, selected.monthValue - 1, selected.dayOfMonth).apply {
                setOnDismissListener { showDatePicker = false }
                show()
            }
        }
    }
    if (showTimePicker) {
        val selected = PlannerDateTime.timeOrNow(travelTime)
        LaunchedEffect(selected) {
            TimePickerDialog(context, { _, hour, minute ->
                travelTime = LocalTime.of(hour, minute).toString()
                showTimePicker = false
            }, selected.hour, selected.minute, true).apply {
                setOnDismissListener { showTimePicker = false }
                show()
            }
        }
    }
    if (showReturnDatePicker) {
        val selected = PlannerDateTime.dateOrToday(returnDate)
        LaunchedEffect(selected) {
            DatePickerDialog(context, { _, year, month, day ->
                returnDate = LocalDate.of(year, month + 1, day).toString()
                showReturnDatePicker = false
            }, selected.year, selected.monthValue - 1, selected.dayOfMonth).apply {
                setOnDismissListener { showReturnDatePicker = false }
                show()
            }
        }
    }
    if (showReturnTimePicker) {
        val selected = PlannerDateTime.timeOrNow(returnTime)
        LaunchedEffect(selected) {
            TimePickerDialog(context, { _, hour, minute ->
                returnTime = LocalTime.of(hour, minute).toString()
                showReturnTimePicker = false
            }, selected.hour, selected.minute, true).apply {
                setOnDismissListener { showReturnTimePicker = false }
                show()
            }
        }
    }
    val origin = viewModel.stationFromName(originText)
    val destination = viewModel.stationFromName(destinationText)
    val selectedDepartureDate = runCatching { LocalDate.parse(travelDate) }.getOrNull()
    val selectedDepartureTime = runCatching { LocalTime.parse(travelTime) }.getOrNull()
    val selectedReturnDate = runCatching { LocalDate.parse(returnDate) }.getOrNull()
    val selectedReturnTime = runCatching { LocalTime.parse(returnTime) }.getOrNull()
    val roundTripChronologyValid = !returnJourney || (
        selectedDepartureDate != null &&
            selectedDepartureTime != null &&
            selectedReturnDate != null &&
            selectedReturnTime != null &&
            PlannerDateTime.isReturnAfterDeparture(
                selectedDepartureDate,
                selectedDepartureTime,
                selectedReturnDate,
                selectedReturnTime
            )
        )
    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.planner_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.planner_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SearchMode.entries) { item -> FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item.localizedLabel()) }) }
                }
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (mode) {
                        SearchMode.ROUTE -> {
                        VoiceInputButton(onClick = { startVoiceInput(SearchMode.ROUTE) }, label = stringResource(R.string.planner_voice_route))
                        speechMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(stringResource(R.string.planner_departure_station), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        StationField(
                            label = stringResource(R.string.planner_origin),
                            value = originText,
                            onValueChange = { originText = it; viewModel.updateStationSuggestions("planner-origin", it) },
                            matches = viewModel.stationSuggestions("planner-origin"),
                            onSelect = { originText = it.name; viewModel.clearStationSuggestions("planner-origin") }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.planner_arrival_station), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { val old = originText; originText = destinationText; destinationText = old }) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = stringResource(R.string.action_swap_origin_destination))
                            }
                        }
                        StationField(
                            label = stringResource(R.string.planner_destination),
                            value = destinationText,
                            onValueChange = { destinationText = it; viewModel.updateStationSuggestions("planner-destination", it) },
                            matches = viewModel.stationSuggestions("planner-destination"),
                            onSelect = { destinationText = it.name; viewModel.clearStationSuggestions("planner-destination") }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FilterChip(selected = !returnJourney, onClick = { returnJourney = false }, label = { Text(stringResource(R.string.planner_one_way)) })
                            FilterChip(
                                selected = returnJourney,
                                onClick = {
                                    returnJourney = true
                                    val outboundDate = PlannerDateTime.dateOrToday(travelDate)
                                    val outboundTime = PlannerDateTime.timeOrNow(travelTime)
                                    val currentReturnDate = PlannerDateTime.dateOrToday(returnDate)
                                    val currentReturnTime = PlannerDateTime.timeOrNow(returnTime)
                                    if (!PlannerDateTime.isReturnAfterDeparture(outboundDate, outboundTime, currentReturnDate, currentReturnTime)) {
                                        returnDate = outboundDate.plusDays(1).toString()
                                        returnTime = outboundTime.toString()
                                    }
                                },
                                label = { Text(stringResource(R.string.planner_round_trip)) }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            val outboundDateDescription = stringResource(
                                R.string.accessibility_select_outbound_date,
                                PlannerDateTime.dateLabel(travelDate, locale, dateUnselected)
                            )
                            val outboundTimeDescription = stringResource(
                                R.string.accessibility_select_outbound_time,
                                PlannerDateTime.timeLabel(travelTime, locale, timeUnselected)
                            )
                            OutlinedButton(
                                onClick = { showDatePicker = true },
                                modifier = Modifier.weight(1f).semantics { contentDescription = outboundDateDescription }
                            ) {
                                Column(horizontalAlignment = Alignment.Start) { Text(stringResource(R.string.planner_outbound_date), style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.dateLabel(travelDate, locale, dateUnselected), fontWeight = FontWeight.Bold) }
                            }
                            OutlinedButton(
                                onClick = { showTimePicker = true },
                                modifier = Modifier.weight(1f).semantics { contentDescription = outboundTimeDescription }
                            ) {
                                Column(horizontalAlignment = Alignment.Start) { Text(stringResource(R.string.planner_outbound_time), style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.timeLabel(travelTime, locale, timeUnselected), fontWeight = FontWeight.Bold) }
                            }
                        }
                        if (returnJourney) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                val returnDateDescription = stringResource(
                                    R.string.accessibility_select_return_date,
                                    PlannerDateTime.dateLabel(returnDate, locale, dateUnselected)
                                )
                                val returnTimeDescription = stringResource(
                                    R.string.accessibility_select_return_time,
                                    PlannerDateTime.timeLabel(returnTime, locale, timeUnselected)
                                )
                                OutlinedButton(
                                    onClick = { showReturnDatePicker = true },
                                    modifier = Modifier.weight(1f).semantics { contentDescription = returnDateDescription }
                                ) {
                                    Column(horizontalAlignment = Alignment.Start) { Text(stringResource(R.string.planner_return_date), style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.dateLabel(returnDate, locale, dateUnselected), fontWeight = FontWeight.Bold) }
                                }
                                OutlinedButton(
                                    onClick = { showReturnTimePicker = true },
                                    modifier = Modifier.weight(1f).semantics { contentDescription = returnTimeDescription }
                                ) {
                                    Column(horizontalAlignment = Alignment.Start) { Text(stringResource(R.string.planner_return_time), style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.timeLabel(returnTime, locale, timeUnselected), fontWeight = FontWeight.Bold) }
                                }
                            }
                            if (!roundTripChronologyValid) {
                                Text(
                                    stringResource(R.string.planner_return_after_outbound),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        FilterChip(
                            selected = showFilters,
                            onClick = { showFilters = !showFilters },
                            label = { Text(stringResource(if (showFilters) R.string.planner_filters_open else R.string.planner_filters_all_trains)) },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        if (showFilters) {
                            JourneyChangeFilterBar(viewModel.journeyChangeFilter, viewModel::selectJourneyChangeFilter)
                            OperatorFilterBar(viewModel.operatorFilter, viewModel.operatorFilterOptions(), viewModel::selectOperatorFilter)
                            Text(stringResource(R.string.planner_filter_disclosure), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = {
                                val searched = if (
                                    origin != null &&
                                    destination != null &&
                                    origin != destination &&
                                    selectedDepartureDate != null &&
                                    selectedDepartureTime != null
                                ) {
                                    if (
                                        returnJourney &&
                                        selectedReturnDate != null &&
                                        selectedReturnTime != null &&
                                        roundTripChronologyValid
                                    ) {
                                        viewModel.searchRoundTrip(
                                            origin,
                                            destination,
                                            selectedDepartureDate,
                                            selectedDepartureTime,
                                            selectedReturnDate,
                                            selectedReturnTime
                                        )
                                    } else if (!returnJourney) {
                                        viewModel.searchRoute(origin, destination, selectedDepartureDate, selectedDepartureTime)
                                    } else false
                                } else false
                                if (searched) showResults()
                            },
                            enabled = origin != null &&
                                destination != null &&
                                origin != destination &&
                                selectedDepartureDate != null &&
                                selectedDepartureTime != null &&
                                roundTripChronologyValid,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (returnJourney) R.string.planner_search_round_trip else R.string.planner_search_solutions))
                        }
                        }
                        SearchMode.TRAIN -> {
                            VoiceInputButton(onClick = { startVoiceInput(SearchMode.TRAIN) }, label = stringResource(R.string.planner_voice_train))
                            speechMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            TrainNumberSearchForm(
                                query = viewModel.trainNumberQuery,
                                onQueryChange = viewModel::updateTrainNumberQuery,
                                onSearch = { viewModel.searchTrainNumber(); showTrainResults() }
                            )
                        }
                        SearchMode.STATION -> {
                            VoiceInputButton(onClick = { startVoiceInput(SearchMode.STATION) }, label = stringResource(R.string.planner_voice_station))
                            speechMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(stringResource(R.string.planner_station_board_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            StationField(
                                label = stringResource(R.string.station_label),
                                value = stationText,
                                onValueChange = { stationText = it; viewModel.updateStationSuggestions("planner-station", it) },
                                matches = viewModel.stationSuggestions("planner-station"),
                                onSelect = { stationText = it.name; viewModel.clearStationSuggestions("planner-station") }
                            )
                            Button(
                                onClick = { openStations(viewModel.stationFromName(stationText)) },
                                enabled = viewModel.stationFromName(stationText) != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Route, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.planner_open_station_board))
                            }
                        }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.planner_recent_and_favourite), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = openMore) { Text(stringResource(R.string.action_manage)) }
            }
        }
        items(viewModel.recentRoutes.take(4), key = { "recent-${it.origin.id}-${it.destination.id}" }) { recent ->
            Row(
                Modifier.fillMaxWidth().clickable { if (viewModel.useRecentRoute(recent)) { originText = recent.origin.name; destinationText = recent.destination.name } }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(recent.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.planner_recent_search), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.accessibility_repeat_search, recent.label))
            }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        items(viewModel.savedRoutes.filter { it.isPinned }.take(4), key = { it.id }) { route ->
            Row(
                Modifier.fillMaxWidth().clickable { viewModel.activateRoute(route); originText = route.outwardOrigin.name; destinationText = route.outwardDestination.name }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (route.isPinned) Icons.Default.Star else Icons.Default.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(route.localizedName(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(route.localizedDisplayName(Direction.OUTBOUND), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.accessibility_use_route, route.localizedName()))
            }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun RouteResultsScreen(modifier: Modifier, viewModel: TrainViewModel, openDetails: (TrainService) -> Unit) {
    val services = viewModel.currentServices()
    val returnServices = viewModel.currentReturnServices()
    val roundTrip = viewModel.hasReturnJourney
    val primaryDirection = viewModel.direction
    val locale = LocalConfiguration.current.locales[0]
    val resultSummary = if (roundTrip) {
        stringResource(
            R.string.planner_round_trip_results,
            pluralStringResource(R.plurals.planner_outbound_result_count, services.size, services.size),
            pluralStringResource(R.plurals.planner_return_result_count, returnServices.size, returnServices.size)
        )
    } else {
        pluralStringResource(R.plurals.search_results_count, services.size, services.size)
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(viewModel.activeRoute.localizedDisplayName(primaryDirection), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    resultSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (roundTrip) stringResource(R.string.planner_outbound) else primaryDirection.localizedLabel().uppercase(locale), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(viewModel.activeRoute.localizedDisplayName(primaryDirection), style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(viewModel.refreshState.localizedLabel(), viewModel.refreshMessage?.localized()).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BoardColumnHeader()
        }
        when {
            viewModel.refreshState == RefreshState.LOADING && viewModel.snapshot == null ->
                item { SearchInfoState(stringResource(R.string.search_outbound_loading_title), stringResource(R.string.search_outbound_loading_message)) }
            viewModel.refreshState == RefreshState.ERROR && viewModel.snapshot == null ->
                item { SearchInfoState(stringResource(R.string.search_outbound_unavailable_title), viewModel.refreshMessage?.localized() ?: stringResource(R.string.search_outbound_unavailable_message)) }
            services.isEmpty() ->
                item { SearchInfoState(stringResource(R.string.search_outbound_empty_title), stringResource(R.string.search_outbound_empty_message)) }
            else -> items(services, key = { "out-${it.id}" }) { service ->
                DepartureListRow(service, isNext = service == services.first(), onOpen = { openDetails(service) })
            }
        }

        if (roundTrip) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.planner_return), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(viewModel.activeRoute.localizedDisplayName(Direction.RETURN), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(viewModel.returnRefreshState.localizedLabel(), viewModel.returnRefreshMessage?.localized()).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BoardColumnHeader()
            }
            when {
                viewModel.returnRefreshState == RefreshState.LOADING && viewModel.returnSnapshot == null ->
                    item { SearchInfoState(stringResource(R.string.search_return_loading_title), stringResource(R.string.search_return_loading_message)) }
                viewModel.returnRefreshState == RefreshState.ERROR && viewModel.returnSnapshot == null ->
                    item { SearchInfoState(stringResource(R.string.search_return_unavailable_title), viewModel.returnRefreshMessage?.localized() ?: stringResource(R.string.search_return_unavailable_message)) }
                returnServices.isEmpty() ->
                    item { SearchInfoState(stringResource(R.string.search_return_empty_title), stringResource(R.string.search_return_empty_message)) }
                else -> items(returnServices, key = { "ret-${it.id}" }) { service ->
                    DepartureListRow(service, isNext = service == returnServices.first(), onOpen = { openDetails(service) })
                }
            }
        }
    }
}

@Composable
internal fun StationBoardScreen(modifier: Modifier, viewModel: TrainViewModel) {
    LaunchedEffect(Unit) { viewModel.refreshStationBoardIfIdle() }
    viewModel.selectedBoardEntry?.let { selected ->
        StationBoardRunDetailsSheet(
            station = viewModel.stationBoardStation?.name.orEmpty(),
            entry = viewModel.stationBoardRunDetails?.entry ?: selected,
            kind = viewModel.stationBoardKind,
            details = viewModel.stationBoardRunDetails,
            state = viewModel.stationBoardRunRefreshState,
            onRefresh = viewModel::refreshStationBoardRun,
            onDismiss = viewModel::closeStationBoardRun
        )
    }
    var stationText by rememberSaveable(viewModel.stationBoardStation?.id) {
        mutableStateOf(viewModel.stationBoardStation?.name.orEmpty())
    }
    var locationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var monitorMode by rememberSaveable { mutableStateOf(StationMonitorMode.BOTH.name) }
    val context = LocalContext.current
    val resources = LocalResources.current

    fun locateNearbyStations() {
        requestNearbyStationLocation(context) { location ->
            if (location == null) {
                locationMessage = resources.getString(R.string.location_unavailable)
            } else {
                viewModel.updateNearbyStations(location.latitude, location.longitude)
                locationMessage = resources.getString(R.string.location_sorted_not_saved)
            }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locateNearbyStations()
        else locationMessage = resources.getString(R.string.location_permission_denied)
    }

    val selectedStation = viewModel.stationBoardStation?.takeIf { it.name.equals(stationText.trim(), ignoreCase = true) }
        ?: viewModel.stationFromName(stationText)
    val board = viewModel.stationBoard?.takeIf { current ->
        selectedStation != null &&
            current.station.id == selectedStation.id &&
            current.kind == viewModel.stationBoardKind
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("station-board-list"),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.station_board_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                StationField(
                    label = stringResource(R.string.station_label),
                    value = stationText,
                    onValueChange = { stationText = it; viewModel.updateStationSuggestions("station-board", it) },
                    matches = viewModel.stationSuggestions("station-board"),
                    onSelect = {
                        stationText = it.name
                        viewModel.clearStationSuggestions("station-board")
                        viewModel.selectStationBoardStation(it)
                    }
                )

                selectedStation?.let { station ->
                    StationPreferenceRow(
                        station = station,
                        favourite = viewModel.isStationFavourite(station),
                        onSelect = {
                            stationText = station.name
                            viewModel.selectStationBoardStation(station)
                        },
                        onToggleFavourite = { viewModel.toggleStationFavourite(station) }
                    )

                    Text(stringResource(R.string.station_board_section), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(BoardKind.entries) { kind ->
                            FilterChip(
                                selected = viewModel.stationBoardKind == kind,
                                onClick = { viewModel.loadStationBoard(station, kind) },
                                enabled = viewModel.hasStationBoardProviders,
                                label = { Text(stringResource(if (kind == BoardKind.DEPARTURES) R.string.station_board_departures else R.string.station_board_arrivals)) }
                            )
                        }
                    }
                    Button(
                        onClick = { viewModel.loadStationBoard(station, viewModel.stationBoardKind) },
                        enabled = viewModel.hasStationBoardProviders &&
                            viewModel.stationBoardRefreshState != RefreshState.LOADING,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (board == null) R.string.station_board_load else R.string.station_board_refresh))
                    }

                    if (viewModel.stationBoardRefreshState == RefreshState.LOADING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(stringResource(R.string.station_board_local_monitoring), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(StationMonitorMode.entries) { mode ->
                            FilterChip(
                                selected = monitorMode == mode.name,
                                onClick = { monitorMode = mode.name },
                                label = { Text(mode.localizedLabel()) }
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.toggleStationMonitor(station, StationMonitorMode.valueOf(monitorMode)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(if (viewModel.isStationMonitored(station)) R.string.station_board_stop_monitoring else R.string.station_board_start_monitoring))
                    }
                }

                val findNearbyStationsDescription = stringResource(R.string.accessibility_find_nearby_stations)
                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locateNearbyStations()
                        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = findNearbyStationsDescription
                    }
                ) {
                    Icon(Icons.Default.Route, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.station_board_find_nearby))
                }

                locationMessage?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (!viewModel.hasStationBoardProviders) {
            item {
                SearchInfoState(
                    stringResource(R.string.station_board_live_disabled_title),
                    stringResource(R.string.station_board_live_disabled_message)
                )
            }
        } else {
            when {
                viewModel.stationBoardRefreshState == RefreshState.LOADING && board?.entries.isNullOrEmpty() -> item {
                    SearchInfoState(stringResource(R.string.station_board_loading_title), stringResource(R.string.station_board_loading_message))
                }
                viewModel.stationBoardRefreshState == RefreshState.ERROR && board == null -> item {
                    SearchInfoState(
                        stringResource(R.string.station_board_unavailable_title),
                        viewModel.stationBoardMessages.firstOrNull()?.localized() ?: stringResource(R.string.station_board_unavailable_message)
                    )
                }
                board != null && board.entries.isEmpty() -> item {
                    SearchInfoState(
                        stringResource(R.string.station_board_empty_title),
                        viewModel.stationBoardMessages.firstOrNull()?.localized() ?: stringResource(R.string.station_board_empty_message)
                    )
                }
                board != null -> {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                stringResource(if (board.kind == BoardKind.DEPARTURES) R.string.station_board_departures_uppercase else R.string.station_board_arrivals_uppercase),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                board.providerNames.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            viewModel.stationBoardMessages.forEach { message ->
                                Text(
                                    message.localized(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    items(
                        board.entries.take(60),
                        key = { entry ->
                            listOf(
                                entry.runId.value,
                                entry.scheduledTime.toInstant().toEpochMilli().toString(),
                                entry.destinationOrOrigin.value
                            ).joinToString("|")
                        }
                    ) { entry ->
                        StationBoardEntryRow(entry, board.kind, onOpen = { viewModel.openStationBoardRun(entry) })
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }

        if (viewModel.favouriteStations.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.station_board_favourites),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
            items(viewModel.favouriteStations, key = { "fav-${it.id}" }) { station ->
                StationPreferenceRow(
                    station = station,
                    favourite = true,
                    onSelect = {
                        stationText = station.name
                        viewModel.selectStationBoardStation(station)
                    },
                    onToggleFavourite = { viewModel.toggleStationFavourite(station) }
                )
            }
        }

        if (viewModel.nearbyStations.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.station_board_nearby),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
            items(viewModel.nearbyStations, key = { "near-${it.station.id}" }) { nearby ->
                StationPreferenceRow(
                    station = nearby.station,
                    favourite = viewModel.isStationFavourite(nearby.station),
                    distanceLabel = stringResource(R.string.distance_kilometers, nearby.distanceKm),
                    onSelect = {
                        stationText = nearby.station.name
                        viewModel.selectStationBoardStation(nearby.station)
                    },
                    onToggleFavourite = { viewModel.toggleStationFavourite(nearby.station) }
                )
            }
        }
    }
}

@Composable
internal fun StationBoardEntryRow(entry: StationBoardEntry, kind: BoardKind, onOpen: (() -> Unit)? = null) {
    val scheduled = entry.scheduledTime.withZoneSameInstant(BOARD_ZONE)
    val expected = entry.expectedTime?.value?.withZoneSameInstant(BOARD_ZONE)
    val delayedMinutes = expected?.let {
        java.time.Duration.between(scheduled, it).toMinutes().coerceAtLeast(0)
    } ?: 0L
    val endpoint = entry.destinationOrOriginLabel?.takeIf(String::isNotBlank)
        ?: entry.destinationOrOrigin.value
    val platform = entry.platform?.value?.takeIf(String::isNotBlank)
    val directionLabel = stringResource(
        if (kind == BoardKind.DEPARTURES) R.string.station_board_departure_to
        else R.string.station_board_arrival_from,
        endpoint
    )
    val scheduledLabel = stringResource(
        R.string.station_board_scheduled_time,
        scheduled.format(BOARD_TIME_FORMATTER)
    )
    val statusLabel = stationBoardStatusAnnouncement(entry)
    val platformLabel = platform?.let {
        stringResource(R.string.station_board_platform_accessible, it)
    } ?: stringResource(R.string.station_board_platform_unavailable)
    val trainLabel = entry.publicNumber?.takeIf(String::isNotBlank)
        ?.let { stringResource(R.string.station_board_train_number, it) }
        .orEmpty()
    val accessibilityDescription = stringResource(
        R.string.station_board_entry_accessibility,
        directionLabel,
        scheduledLabel,
        statusLabel,
        platformLabel,
        trainLabel
    )

    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .clearAndSetSemantics {
                contentDescription = accessibilityDescription
                if (onOpen != null) {
                    role = Role.Button
                    onClick { onOpen(); true }
                }
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(scheduled.format(BOARD_TIME_FORMATTER), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (expected != null && expected.toInstant() != scheduled.toInstant()) {
                Text(
                    stringResource(R.string.expected_time_short, expected.format(BOARD_TIME_FORMATTER)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                directionLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(
                    entry.publicNumber?.takeIf(String::isNotBlank)?.let { stringResource(R.string.station_board_train_number, it) },
                    entry.operatorId?.value?.takeIf(String::isNotBlank),
                    entry.status?.value?.stationBoardLabel()
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (delayedMinutes > 0) {
                Text(stringResource(R.string.delay_minutes, delayedMinutes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            entry.routeChange?.value?.let { change ->
                Text(
                    stringResource(R.string.station_board_route_change, change.summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        platform?.let {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(stringResource(R.string.station_board_platform, it), modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontWeight = FontWeight.Bold)
            }
        }
        if (onOpen != null) {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StationBoardRunDetailsSheet(
    station: String,
    entry: StationBoardEntry,
    kind: BoardKind,
    details: StationBoardRunDetails?,
    state: RefreshState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.9f).testTag("station-run-details")) {
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.station_board_run_details_title), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.planned_journey_close)) }
                    }
                    Text(station, style = MaterialTheme.typography.titleMedium)
                    if (details != null) {
                        Text("${details.origin} → ${details.destination}", style = MaterialTheme.typography.bodyLarge)
                        Text(details.observedAt?.let {
                            stringResource(R.string.provider_updated_at, details.providerName, updateTimeMessage(it.toEpochMilli()).localized())
                        } ?: details.providerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                StationBoardEntryRow(entry, kind)
                TextButton(onClick = onRefresh, enabled = state != RefreshState.LOADING, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.action_refresh))
                }
                if (state == RefreshState.LOADING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.station_board_run_details_loading), modifier = Modifier.padding(20.dp))
                } else if (details == null) {
                    Text(stringResource(R.string.station_board_run_details_unavailable), modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (details != null) {
                item { Text(stringResource(R.string.service_stops_title), modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(details.stops) { stop ->
                    StopTimelineRow(stop.station, stop.expected, stop.scheduled, stop.delayMinutes, stop.platform, stop.completed,
                        operationalStatusVerified = stop.operationalStatusVerified == true, routeChange = stop.routeChange, onSetAlarm = null)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun stationBoardStatusAnnouncement(entry: StationBoardEntry): String {
    val runStatus = entry.status?.value
    return when {
        entry.expectedTime != null -> stringResource(
            R.string.station_board_status_live_prediction,
            entry.expectedTime.value.withZoneSameInstant(BOARD_ZONE).format(BOARD_TIME_FORMATTER)
        )
        runStatus != null && runStatus != RunStatus.SCHEDULED && runStatus != RunStatus.UNKNOWN ->
            stringResource(R.string.station_board_status_live, runStatus.stationBoardLabel())
        runStatus == RunStatus.UNKNOWN -> stringResource(R.string.station_board_status_unavailable)
        else -> stringResource(R.string.station_board_status_planned)
    }
}

@Composable
private fun RunStatus.stationBoardLabel(): String = when (this) {
    RunStatus.SCHEDULED -> stringResource(R.string.run_status_scheduled)
    RunStatus.ON_TIME -> stringResource(R.string.run_status_on_time)
    RunStatus.DELAYED -> stringResource(R.string.run_status_delayed)
    RunStatus.CANCELLED -> stringResource(R.string.run_status_cancelled)
    RunStatus.PARTIALLY_CANCELLED -> stringResource(R.string.run_status_partially_cancelled)
    RunStatus.DIVERTED -> stringResource(R.string.run_status_diverted)
    RunStatus.ADDED -> stringResource(R.string.run_status_added)
    RunStatus.REPLACED_BY_BUS -> stringResource(R.string.run_status_bus_replacement)
    RunStatus.COMPLETED -> stringResource(R.string.run_status_completed)
    RunStatus.UNKNOWN -> stringResource(R.string.run_status_unknown)
}

private val BOARD_ZONE: ZoneId = ZoneId.of("Europe/Rome")
private val BOARD_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)

private fun requestNearbyStationLocation(context: android.content.Context, onResult: (Location?) -> Unit) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        onResult(null)
        return
    }
    val locationManager = context.getSystemService(LocationManager::class.java) ?: run {
        onResult(null)
        return
    }
    val fallback = {
        onResult(runCatching {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull())
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            locationManager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ContextCompat.getMainExecutor(context)) { location ->
                if (location != null) onResult(location) else fallback()
            }
        }.onFailure { fallback() }
    } else fallback()
}

@Composable
private fun StationPreferenceRow(
    station: Station,
    favourite: Boolean,
    onSelect: () -> Unit,
    onToggleFavourite: () -> Unit,
    distanceLabel: String? = null
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onSelect, modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(station.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(station.city, distanceLabel).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (favourite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = stringResource(
                    if (favourite) R.string.accessibility_remove_favourite_station else R.string.accessibility_add_favourite_station,
                    station.name
                )
            )
        }
    }
}

@Composable
private fun BoardRouteHeader(
    route: SavedRoute,
    direction: Direction,
    refreshState: RefreshState,
    onSwitchDirection: () -> Unit,
    onRefresh: () -> Unit,
    onRoutes: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val directionUppercase = stringResource(if (direction == Direction.OUTBOUND) R.string.planner_outbound else R.string.planner_return)
                    val directionLabel = stringResource(if (direction == Direction.OUTBOUND) R.string.direction_outbound else R.string.direction_return)
                    Text(stringResource(R.string.board_header_title, directionUppercase), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(route.localizedDisplayName(direction), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.board_route_direction, route.localizedName(), directionLabel), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.accessibility_refresh_departures, refreshState.localizedLabel()))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onSwitchDirection) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_direction))
                }
                TextButton(onClick = onRoutes) { Text(stringResource(R.string.action_change_route), color = MaterialTheme.colorScheme.onPrimary) }
            }
        }
    }
}

@Composable
private fun BoardColumnHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.board_column_time), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(68.dp))
        Text(stringResource(R.string.board_column_service), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.board_column_platform), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(42.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Text(stringResource(R.string.board_column_status), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(50.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun MonitoredRoutesStrip(
    routes: List<SavedRoute>,
    activeId: String,
    onActivate: (SavedRoute) -> Unit,
    onManage: () -> Unit
) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.monitored_routes_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onManage) { Text(stringResource(R.string.action_manage)) }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp)
        ) {
            items(routes.take(6), key = { it.id }) { route ->
                FilterChip(
                    selected = route.id == activeId,
                    onClick = { onActivate(route) },
                    label = { Text(route.localizedName(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = if (route.isPinned) ({ Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null
                )
            }
        }
    }
}

@Composable
private fun DataStatusLine(
    freshness: Freshness?,
    provider: String,
    confidence: String,
    refreshedAt: Long,
    isDemonstration: Boolean,
    message: UiMessage?
) {
    val messageText = message?.localized()
    val label = when {
        isDemonstration -> stringResource(R.string.data_status_demo)
        freshness == Freshness.UNAVAILABLE || freshness == null -> stringResource(R.string.data_status_unavailable)
        else -> stringResource(R.string.data_status_live, freshness.localizedLabel())
    }
    val color = when {
        isDemonstration -> MaterialTheme.colorScheme.tertiary
        freshness == Freshness.UNAVAILABLE || freshness == null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    val statusContentDescription = stringResource(
        R.string.accessibility_data_status,
        label,
        provider,
        confidence,
        updateTimeMessage(refreshedAt).localized(),
        messageText?.takeIf(String::isNotBlank)?.let { stringResource(R.string.accessibility_optional_message, it) }.orEmpty()
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .clearAndSetSemantics {
                contentDescription = statusContentDescription
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Info, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
            Text(if (refreshedAt > 0L) stringResource(R.string.provider_updated_at, provider, updateTimeMessage(refreshedAt).localized()) else provider, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            messageText?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ListHeading(title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun DepartureListRow(service: TrainService, isNext: Boolean = false, onOpen: () -> Unit) {
    val serviceState = if (service.operationalStatusVerified) {
        stringResource(R.string.service_state_live, service.status.localizedLabel())
    } else {
        stringResource(R.string.service_state_scheduled)
    }
    val serviceContentDescription = stringResource(
        R.string.accessibility_open_service,
        service.localizedAccessibleSummary(),
        serviceState
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isNext) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f) else MaterialTheme.colorScheme.background)
            .clickable(role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = serviceContentDescription
                role = Role.Button
                onClick { onOpen(); true }
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(76.dp)) {
            Text(service.scheduledDeparture, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            if (!service.operationalStatusVerified) Text(stringResource(R.string.service_scheduled_lowercase), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (service.delayMinutes > 0) Text(stringResource(R.string.delay_minutes, service.delayMinutes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            else Text(stringResource(R.string.service_on_time_lowercase), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            if (service.operationalStatusVerified && service.expectedDeparture != service.scheduledDeparture) {
                Text(stringResource(R.string.expected_time_short, service.expectedDeparture), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(service.destination, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.service_operator_category_number, service.displayOperator, service.trainCategory.localizedLabel(), service.number), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            service.routeChange?.let {
                Text(
                    routeChangeLabel(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = routeChangeAccent(it),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(50.dp)) {
            Text(service.platform?.let { stringResource(R.string.platform_short, it) } ?: "—", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(50.dp)) {
            Text(compactStatusLabel(service), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun TodayEmptyState(state: RefreshState, message: UiMessage?, onRefresh: () -> Unit, onRoutes: () -> Unit) {
    val messageText = message?.localized()
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
        Text(stringResource(if (state == RefreshState.LOADING) R.string.today_empty_loading else R.string.today_empty_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.today_empty_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        messageText?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh) { Text(stringResource(R.string.action_refresh)) }
            OutlinedButton(onClick = onRoutes) { Text(stringResource(R.string.action_change_route)) }
        }
    }
}

@Composable
private fun SearchInfoState(title: String, message: String, openRoutes: (() -> Unit)? = null) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        openRoutes?.let { OutlinedButton(onClick = it) { Text(stringResource(R.string.action_open_routes)) } }
    }
}

@Composable
private fun OperatorFilterBar(
    selected: String?,
    operators: List<String>,
    onSelected: (String?) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)
    ) {
        item { FilterChip(selected = selected == null, onClick = { onSelected(null) }, label = { Text(stringResource(R.string.filter_all)) }) }
        items(operators, key = { it.lowercase(Locale.ROOT) }) { operator ->
            FilterChip(
                selected = selected?.equals(operator, ignoreCase = true) == true,
                onClick = { onSelected(operator) },
                label = { Text(operator) }
            )
        }
    }
}

@Composable
private fun JourneyChangeFilterBar(selected: JourneyChangeFilter, onSelected: (JourneyChangeFilter) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.journey_changes_uppercase), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(JourneyChangeFilter.entries) { filter ->
                val accessibilityLabel = filter.localizedAccessibleLabel()
                FilterChip(
                    selected = selected == filter,
                    onClick = { onSelected(filter) },
                    label = { Text(filter.localizedLabel()) },
                    modifier = Modifier.semantics { contentDescription = accessibilityLabel }
                )
            }
        }
    }
}

@Composable
private fun VoiceInputButton(onClick: () -> Unit, label: String) {
    val accessibilityLabel = stringResource(R.string.accessibility_system_voice_input, label)
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = accessibilityLabel }
    ) {
        Icon(Icons.Default.Mic, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    viewModel: TrainViewModel,
    onThemeClick: () -> Unit,
    openRoute: (SavedRoute) -> Unit
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
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
    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.settings_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsSectionTitle(stringResource(R.string.settings_appearance))
            SettingsActionRow(
                title = stringResource(R.string.settings_theme_title),
                detail = stringResource(R.string.settings_theme_description),
                onClick = onThemeClick
            )
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.settings_personal_journeys), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.settings_personal_journeys_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { showComposer = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_add_route))
                }
            }
        }
        item {
            CommuterAlertsPanel(
                alerts = viewModel.commuterAlerts,
                feedback = alertFeedback,
                onAdd = { showAlertComposer = true },
                onRemove = viewModel::removeCommuterAlert
            )
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
        item {
            MobilityPreferencesPanel(
                value = viewModel.mobilityPreferences,
                onChange = viewModel::updateMobilityPreferences
            )
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
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.settings_privacy_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.settings_privacy_local_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_privacy_notifications_voice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_privacy_wear), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_privacy_osm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
    )
}

@Composable
private fun SettingsActionRow(title: String, detail: String, onClick: () -> Unit) {
    val accessibilityLabel = stringResource(R.string.accessibility_title_detail, title, detail)
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics { contentDescription = accessibilityLabel }
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.Palette, contentDescription = null)
    }
}

@Composable
private fun CommuterAlertsPanel(
    alerts: List<CommuterAlert>,
    feedback: String?,
    onAdd: () -> Unit,
    onRemove: (CommuterAlert) -> Unit
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.commuter_alerts_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.commuter_alerts_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.commuter_alerts_create)) }
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
        color = MaterialTheme.colorScheme.surfaceVariant,
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

private fun parseWidgetDeepLink(intent: Intent?, context: android.content.Context): BinarioDeepLink.WidgetRoute? {
    val widgetId = intent?.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID)
        ?: android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
    if (widgetId == android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) return null
    val route = WidgetConfigurationStore(context).read(widgetId)?.routeTarget() ?: return null
    val direction = WidgetStateStore.displayedDirection(context, widgetId) ?: Direction.OUTBOUND
    return BinarioDeepLink.WidgetRoute(route, direction)
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
                if (route.isPinned) Text(stringResource(R.string.route_pinned), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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

@Composable
private fun StationField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    matches: List<Station>,
    onSelect: (Station) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) }, leadingIcon = { Icon(Icons.Default.Train, contentDescription = null) })
        if (matches.isNotEmpty() && (matches.none { it.name.equals(value, ignoreCase = true) } || matches.any { it.id.startsWith("manual:") })) {
            matches.take(5).forEach { station ->
                TextButton(onClick = { onSelect(station) }, modifier = Modifier.fillMaxWidth()) {
                    val stationLabel = if (station.id.startsWith("manual:")) {
                        stringResource(R.string.station_suggestion_free_text, station.name)
                    } else {
                        buildString {
                            append(station.name)
                            station.railwayCode?.let { append(" · ").append(it) }
                        }
                    }
                    Icon(Icons.Default.Train, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stationLabel, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ServiceDetailScreen(
    modifier: Modifier,
    service: TrainService,
    provider: String,
    confidence: String,
    freshness: Freshness?,
    refreshedAt: Long,
    isDemonstration: Boolean,
    onRefresh: () -> Unit,
    isMonitored: Boolean,
    onToggleMonitoring: () -> Unit,
    onScheduleStopAlarm: (TrainService, TrainStop) -> Unit,
    alarmFeedback: String?,
    activeTrip: ActiveTrip?,
    onStartActiveTrip: () -> Unit,
    onAdvanceActiveTrip: () -> Unit,
    onEndActiveTrip: () -> Unit,
    onShare: () -> Unit
) {
    val dataLabel = if (isDemonstration) stringResource(R.string.data_status_demo) else freshness?.localizedLabel() ?: stringResource(R.string.data_status_unavailable)
    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.service_operator_category, service.displayOperator, service.trainCategory.localizedLabel()), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.service_number_route, service.number, service.origin, service.destination), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.service_scheduled_departure_label), style = MaterialTheme.typography.labelMedium)
                            Text(service.scheduledDeparture, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            if (service.operationalStatusVerified && service.expectedDeparture != service.scheduledDeparture) {
                                Text(stringResource(R.string.expected_time, service.expectedDeparture), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        ServiceStatusBadge(service)
                    }
                    Text(service.platform?.let { stringResource(R.string.platform_label, it) } ?: stringResource(R.string.platform_to_confirm), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        service.routeChange?.let { routeChange ->
            item {
                RouteChangeBanner(
                    routeChange = routeChange,
                    sourceIds = service.routeChangeSourceIds
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.service_stops_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.service_data_summary, dataLabel, provider, confidence), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(updateTimeMessage(refreshedAt).localized(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.accessibility_share_service))
                    }
                    IconButton(onClick = onToggleMonitoring) {
                        Icon(if (isMonitored) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = stringResource(if (isMonitored) R.string.accessibility_stop_monitoring_service else R.string.accessibility_monitor_service))
                    }
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.accessibility_refresh_service)) }
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val isThisTrip = activeTrip?.trainNumber == service.number
                    Text(stringResource(if (isThisTrip) R.string.active_trip_title else R.string.follow_trip_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    val tripDescription = if (isThisTrip) {
                        activeTrip?.nextStop?.let { stringResource(R.string.personal_travel_next_stop, it.station, it.expected) }
                            ?: stringResource(R.string.personal_travel_completed)
                    } else {
                        stringResource(R.string.follow_trip_description)
                    }
                    Text(tripDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isThisTrip) Button(onClick = onStartActiveTrip) { Text(stringResource(R.string.action_start_trip)) }
                        else {
                            OutlinedButton(onClick = onAdvanceActiveTrip, enabled = activeTrip?.isComplete == false) { Text(stringResource(R.string.action_stop_reached)) }
                            TextButton(onClick = onEndActiveTrip) { Text(stringResource(R.string.action_end)) }
                        }
                    }
                }
            }
        }
        alarmFeedback?.let { feedback ->
            item {
                Text(
                    feedback,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Text(
                stringResource(R.string.stop_alarm_notice),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(service.stops, key = { "${service.id}-${it.station}" }) { stop ->
            StopTimelineRow(
                station = stop.station,
                expected = stop.expected,
                scheduled = stop.scheduled,
                delay = stop.delayMinutes,
                platform = stop.platform,
                completed = stop.completed,
                operationalStatusVerified = stop.operationalStatusVerified ?: service.operationalStatusVerified,
                routeChange = stop.routeChange,
                onSetAlarm = { onScheduleStopAlarm(service, stop) }
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
internal fun StopTimelineRow(
    station: String,
    expected: String,
    scheduled: String,
    delay: Int,
    platform: String?,
    completed: Boolean,
    operationalStatusVerified: Boolean,
    routeChange: StopRouteChange,
    onSetAlarm: (() -> Unit)?
) {
    val changeLabel = when (routeChange) {
        StopRouteChange.NONE -> ""
        StopRouteChange.ADDED -> stringResource(R.string.stop_change_added_accessibility) + " "
        StopRouteChange.SKIPPED -> stringResource(R.string.stop_change_skipped_accessibility) + " "
    }
    val operationalLabel = when {
        !operationalStatusVerified -> stringResource(R.string.operational_status_unverified)
        delay > 0 -> pluralStringResource(R.plurals.operational_delay_minutes, delay, delay)
        else -> stringResource(R.string.operational_on_time)
    }
    val platformLabel = platform?.let { stringResource(R.string.platform_label, it) }
        ?: stringResource(R.string.platform_unavailable)
    val accessibilityLabel = stringResource(
        R.string.accessibility_stop_timeline,
        station,
        changeLabel,
        scheduled,
        if (operationalStatusVerified && expected != scheduled) stringResource(R.string.expected_time, expected) + ". " else "",
        operationalLabel,
        platformLabel
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .semantics { contentDescription = accessibilityLabel },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
            Surface(modifier = Modifier.size(14.dp), shape = RoundedCornerShape(99.dp), color = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) {}
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                station,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (routeChange == StopRouteChange.SKIPPED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            when (routeChange) {
                StopRouteChange.SKIPPED -> Text(stringResource(R.string.stop_skipped_uppercase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                StopRouteChange.ADDED -> Text(stringResource(R.string.stop_added_uppercase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                StopRouteChange.NONE -> Unit
            }
            Text(platform?.let { stringResource(R.string.platform_label, it) } ?: stringResource(R.string.platform_to_confirm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(scheduled, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            if (!operationalStatusVerified) {
                Text(stringResource(R.string.operational_status_unverified), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (delay > 0) {
                Text(stringResource(R.string.delay_minutes, delay), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            }
            if (operationalStatusVerified && expected != scheduled) {
                Text(stringResource(R.string.expected_time_short, expected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!completed && onSetAlarm != null) {
                TextButton(onClick = onSetAlarm, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(stringResource(R.string.action_stop_alarm))
                }
            }
        }
    }
}


@Composable
private fun RouteChangeBanner(
    routeChange: OperationalRouteChange,
    sourceIds: Set<String>
) {
    val severe = routeChange.kind in setOf(
        RouteChangeKind.SKIPPED_STOPS,
        RouteChangeKind.SHORT_TURN,
        RouteChangeKind.REPLACEMENT_SERVICE
    )
    val container = if (severe) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val foreground = if (severe) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    val sourceLabel = sourceIds.takeIf { it.isNotEmpty() }?.joinToString(", ")
    val routeChangeTitle = routeChangeLabel(routeChange)
    val skippedDescription = routeChange.skippedStopLabels.takeIf { it.isNotEmpty() }
        ?.let { stringResource(R.string.route_change_accessibility_skipped, it.joinToString(", ")) }
    val addedDescription = routeChange.addedStopLabels.takeIf { it.isNotEmpty() }
        ?.let { stringResource(R.string.route_change_accessibility_added, it.joinToString(", ")) }
    val viaDescription = routeChange.viaStopLabels.takeIf { it.isNotEmpty() }
        ?.let { stringResource(R.string.route_change_accessibility_via, it.joinToString(", ")) }
    val destinationDescription = routeChange.replacementDestinationLabel
        ?.let { stringResource(R.string.route_change_destination, it) }
    val accessibilityLabel = listOfNotNull(
        routeChangeTitle,
        routeChange.summary,
        skippedDescription,
        addedDescription,
        viaDescription,
        destinationDescription
    ).joinToString(". ")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .semantics { contentDescription = accessibilityLabel },
        color = container,
        contentColor = foreground,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(routeChangeTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(routeChange.summary, style = MaterialTheme.typography.bodyMedium)
                if (routeChange.skippedStopLabels.isNotEmpty()) {
                    Text(stringResource(R.string.route_change_skipped, routeChange.skippedStopLabels.joinToString(", ")), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                if (routeChange.addedStopLabels.isNotEmpty()) {
                    Text(stringResource(R.string.route_change_added, routeChange.addedStopLabels.joinToString(", ")), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                if (routeChange.viaStopLabels.isNotEmpty()) {
                    Text(stringResource(R.string.route_change_via, routeChange.viaStopLabels.joinToString(", ")), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                routeChange.replacementDestinationLabel?.let {
                    Text(stringResource(R.string.route_change_destination, it), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                sourceLabel?.let {
                    Text(stringResource(R.string.route_change_source, it), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun routeChangeLabel(routeChange: OperationalRouteChange): String = when (routeChange.kind) {
    RouteChangeKind.DIVERTED -> stringResource(R.string.route_change_label_diverted)
    RouteChangeKind.SKIPPED_STOPS -> stringResource(R.string.route_change_label_skipped)
    RouteChangeKind.ADDED_STOPS -> stringResource(R.string.route_change_label_added)
    RouteChangeKind.SHORT_TURN -> stringResource(R.string.route_change_label_short_turn)
    RouteChangeKind.REPLACEMENT_SERVICE -> stringResource(R.string.route_change_label_replacement)
    RouteChangeKind.OTHER -> stringResource(R.string.route_change_label_other)
}

@Composable
private fun routeChangeAccent(routeChange: OperationalRouteChange) =
    if (routeChange.kind in setOf(RouteChangeKind.SKIPPED_STOPS, RouteChangeKind.SHORT_TURN, RouteChangeKind.REPLACEMENT_SERVICE)) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }

@Composable
private fun ServiceStatusBadge(service: TrainService) {
    val (container, foreground, text) = if (!service.operationalStatusVerified) {
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
private fun compactStatusLabel(service: TrainService): String {
    if (!service.operationalStatusVerified) return stringResource(R.string.service_status_scheduled_compact)
    return when (service.status) {
        ServiceStatus.ON_TIME -> stringResource(R.string.operational_on_time)
        ServiceStatus.DELAYED -> stringResource(R.string.delay_minutes, service.delayMinutes)
        ServiceStatus.CANCELLED -> stringResource(R.string.service_status_cancelled)
    }
}

@Composable
private fun SavedRoute.localizedName(): String =
    if (id == UNCONFIGURED_ROUTE_ID) stringResource(R.string.unconfigured_route_name) else name

@Composable
private fun SavedRoute.localizedDisplayName(direction: Direction): String =
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
private fun Direction.localizedLabel(): String = when (this) {
    Direction.OUTBOUND -> stringResource(R.string.direction_outbound)
    Direction.RETURN -> stringResource(R.string.direction_return)
}

@Composable
private fun RefreshState.localizedLabel(): String = when (this) {
    RefreshState.IDLE -> stringResource(R.string.refresh_idle)
    RefreshState.LOADING -> stringResource(R.string.refresh_loading)
    RefreshState.SUCCESS -> stringResource(R.string.refresh_success)
    RefreshState.ERROR -> stringResource(R.string.refresh_error)
}

@Composable
private fun Freshness.localizedLabel(): String = when (this) {
    Freshness.FRESH -> stringResource(R.string.freshness_fresh)
    Freshness.AGING -> stringResource(R.string.freshness_aging)
    Freshness.STALE -> stringResource(R.string.freshness_stale)
    Freshness.UNAVAILABLE -> stringResource(R.string.freshness_unavailable)
}

@Composable
private fun DataConfidence.localizedLabel(): String = when (this) {
    DataConfidence.OPERATOR_PRIMARY -> stringResource(R.string.data_confidence_operator_primary)
    DataConfidence.INFRASTRUCTURE_PRIMARY -> stringResource(R.string.data_confidence_infrastructure_primary)
    DataConfidence.OPEN_DATA -> stringResource(R.string.data_confidence_open_data)
    DataConfidence.LICENSED_AGGREGATE -> stringResource(R.string.data_confidence_licensed_aggregate)
    DataConfidence.STATIC_ONLY -> stringResource(R.string.data_confidence_static_only)
    DataConfidence.CACHED -> stringResource(R.string.data_confidence_cached)
    DataConfidence.DEMONSTRATION -> stringResource(R.string.data_confidence_demonstration)
}

@Composable
private fun TrainCategory.localizedLabel(): String = when (this) {
    TrainCategory.REGIONALE -> stringResource(R.string.train_category_regionale)
    TrainCategory.REGIONALE_VELOCE -> stringResource(R.string.train_category_regionale_veloce)
    TrainCategory.INTERCITY -> stringResource(R.string.train_category_intercity)
    TrainCategory.FRECCE -> stringResource(R.string.train_category_frecce)
    TrainCategory.AV_ITALO -> stringResource(R.string.train_category_italo_high_speed)
    TrainCategory.TRENORD_REGIO -> stringResource(R.string.train_category_trenord_regional)
    TrainCategory.METROPOLITANO -> stringResource(R.string.train_category_metropolitan)
    TrainCategory.OTHER -> stringResource(R.string.train_category_other)
}

@Composable
private fun TrainService.localizedAccessibleSummary(): String {
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
    val platformLabel = platform?.let { stringResource(R.string.platform_label, it) }
        ?: stringResource(R.string.platform_unavailable)
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
private fun StationMonitor.localizedSummary(): String = stringResource(
    R.string.station_monitor_summary,
    stationName,
    mode.localizedLabel()
)

@Composable
private fun CommuterAlert.localizedSummary(): String = stringResource(
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
private fun MobilityPreferences.localizedSummary(): String = stringResource(
    R.string.mobility_summary,
    maxChanges?.let { pluralStringResource(R.plurals.mobility_summary_max_changes, it, it) }
        ?: stringResource(R.string.mobility_summary_any_changes),
    pluralStringResource(R.plurals.minutes_short, transferMarginMinutes, transferMarginMinutes),
    if (stepFreePreferred) stringResource(R.string.mobility_summary_step_free) else "",
    if (compactTimetablePreferred) stringResource(R.string.mobility_summary_compact) else ""
)

@Composable
private fun ServiceStatus.localizedLabel(): String = when (this) {
    ServiceStatus.ON_TIME -> stringResource(R.string.service_status_on_time)
    ServiceStatus.DELAYED -> stringResource(R.string.service_status_delayed)
    ServiceStatus.CANCELLED -> stringResource(R.string.service_status_cancelled)
}

@Composable
private fun StationMonitorMode.localizedLabel(): String = when (this) {
    StationMonitorMode.DEPARTURES -> stringResource(R.string.station_monitor_departures)
    StationMonitorMode.ARRIVALS -> stringResource(R.string.station_monitor_arrivals)
    StationMonitorMode.BOTH -> stringResource(R.string.station_monitor_both)
}

@Composable
private fun JourneyChangeFilter.localizedLabel(): String = when (this) {
    JourneyChangeFilter.ANY -> stringResource(R.string.journey_change_any)
    JourneyChangeFilter.DIRECT -> stringResource(R.string.journey_change_direct)
    JourneyChangeFilter.UP_TO_ONE -> stringResource(R.string.journey_change_one)
    JourneyChangeFilter.UP_TO_TWO -> stringResource(R.string.journey_change_two)
}

@Composable
private fun JourneyChangeFilter.localizedAccessibleLabel(): String = when (this) {
    JourneyChangeFilter.ANY -> stringResource(R.string.journey_change_any_accessible)
    JourneyChangeFilter.DIRECT -> stringResource(R.string.journey_change_direct_accessible)
    JourneyChangeFilter.UP_TO_ONE -> stringResource(R.string.journey_change_one_accessible)
    JourneyChangeFilter.UP_TO_TWO -> stringResource(R.string.journey_change_two_accessible)
}

@Composable
private fun AlertRepeat.localizedLabel(): String = when (this) {
    AlertRepeat.ONCE -> stringResource(R.string.alert_repeat_once)
    AlertRepeat.WEEKDAYS -> stringResource(R.string.alert_repeat_weekdays)
    AlertRepeat.DAILY -> stringResource(R.string.alert_repeat_daily)
}
