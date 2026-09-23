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
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.accessibleUpdateTime
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.OperationalRouteChange
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.plannedjourney.PlannedJourneyAgendaScreen
import it.binario.app.plannedjourney.PlannedJourneyIcsContract
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { PLANNER, OPERATIONS, AGENDA, SETTINGS, RESULTS, DETAIL }
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
    val plannedJourneyViewModel = remember {
        PlannedJourneyViewModel(
            PlannedJourneyRepository(SharedPreferencesPlannedJourneyStorage(context.applicationContext))
        )
    }
    var plannedJourneyExport by remember { mutableStateOf<PlannedJourneyExport?>(null) }
    val plannedJourneyExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val export = plannedJourneyExport
        plannedJourneyExport = null
        if (uri != null && export != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(export.content) }
                    ?: error("Impossibile aprire il file scelto")
            }.onSuccess {
                plannedJourneyViewModel.note("Esportazione completata.")
            }.onFailure { error ->
                plannedJourneyViewModel.note("Esportazione non riuscita: ${error.message ?: "errore file"}")
            }
        }
    }
    val plannedJourneyImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use(::readBoundedPlannedJourneyDocument)
                    ?: error("Impossibile leggere il file scelto")
            }.onSuccess { content ->
                plannedJourneyViewModel.previewImport(
                    content = content,
                    isIcs = content.trimStart().startsWith("BEGIN:VCALENDAR")
                )
            }.onFailure { error ->
                plannedJourneyViewModel.note("Importazione non riuscita: ${error.message ?: "errore file"}")
            }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val plan = pendingStopAlarm
        pendingStopAlarm = null
        if (granted && plan != null) {
            stopAlarmFeedback = stopAlarmScheduleFeedback(StopAlarmScheduler.schedule(context, plan), plan)
        } else {
            stopAlarmFeedback = "Per ricevere la sveglia alla fermata devi consentire le notifiche di Binario."
        }
    }
    val scheduleStopAlarm: (TrainService, TrainStop) -> Unit = { service, stop ->
        val plan = StopAlarmPlanner.plan(service, stop)
        when {
            stop.completed -> stopAlarmFeedback = "La fermata ${stop.station} risulta già superata."
            plan == null -> stopAlarmFeedback = "Non posso pianificare la sveglia: l’orario previsto della fermata è già passato o non è disponibile."
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                pendingStopAlarm = plan
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> {
                stopAlarmFeedback = stopAlarmScheduleFeedback(StopAlarmScheduler.schedule(context, plan), plan)
            }
        }
    }
    LaunchedEffect(deepLinkRequest?.id) {
        val request = deepLinkRequest ?: return@LaunchedEffect
        when (val deepLink = request.deepLink) {
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
            Screen.RESULTS -> screen = Screen.PLANNER
            else -> Unit
        }
    }
    BackHandler(enabled = screen == Screen.DETAIL || screen == Screen.RESULTS, onBack = navigateBack)
    val title = stringResource(
        when (screen) {
            Screen.PLANNER -> R.string.screen_title_planner
            Screen.OPERATIONS -> R.string.screen_title_operations
            Screen.AGENDA -> R.string.screen_title_agenda
            Screen.SETTINGS -> R.string.screen_title_settings
            Screen.RESULTS -> R.string.screen_title_results
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
                    if (screen == Screen.DETAIL || screen == Screen.RESULTS) {
                        IconButton(onClick = navigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showThemePicker = true }) {
                        Icon(Icons.Default.Palette, contentDescription = stringResource(R.string.action_choose_theme, themeMode.label))
                    }
                    if (screen == Screen.OPERATIONS || screen == Screen.RESULTS || screen == Screen.DETAIL) {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh_rail_information))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            if (screen != Screen.DETAIL && screen != Screen.RESULTS) {
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
                onExportJson = { content ->
                    plannedJourneyExport = PlannedJourneyExport("binario-viaggi-pianificati.json", content)
                    plannedJourneyExportLauncher.launch("binario-viaggi-pianificati.json")
                },
                onExportIcs = { content ->
                    plannedJourneyExport = PlannedJourneyExport("binario-agenda.ics", content)
                    plannedJourneyExportLauncher.launch("binario-agenda.ics")
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
            Screen.DETAIL -> {
                val service = viewModel.selectedService
                if (service == null) {
                    // The selected service is intentionally in-memory. After process
                    // death, a restored DETAIL screen returns to its saved parent
                    // instead of showing an empty screen or recreating stale data.
                    LaunchedEffect(detailReturnScreen) { screen = detailReturnScreen }
                } else ServiceDetailScreen(
                    modifier = Modifier.padding(padding),
                    service = service,
                    provider = viewModel.currentProviderLabel(),
                    confidence = viewModel.currentConfidenceLabel(),
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
                            provider = viewModel.currentProviderLabel(),
                            confidence = viewModel.currentConfidenceLabel(),
                            freshness = viewModel.snapshot?.freshness,
                            refreshedAt = viewModel.lastRefreshEpochMillis(),
                            isDemonstration = viewModel.isDemonstrationData()
                        )
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TITLE, content.title)
                                    .putExtra(Intent.EXTRA_TEXT, content.text),
                                "Condividi dettagli treno"
                            )
                        )
                    }
                )
            }
        }
    }
}

private data class PlannedJourneyExport(val suggestedName: String, val content: String)

private fun readBoundedPlannedJourneyDocument(input: java.io.InputStream): String {
    val limit = maxOf(PlannedJourneyTransferContract.MAX_BYTES, PlannedJourneyIcsContract.MAX_BYTES)
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        require(output.size() + read <= limit) { "Il file supera il limite di importazione di $limit byte" }
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun stopAlarmScheduleFeedback(result: DeviceAlarmScheduleResult, plan: StopAlarmPlan): String =
    if (result.isExact) {
        "Sveglia impostata per ${plan.station}, cinque minuti prima dell’orario previsto ${plan.expectedTime}."
    } else {
        "Sveglia impostata per ${plan.station}, ma Android può consegnarla in ritardo perché gli allarmi esatti non sono consentiti."
    }

private fun commuterAlertScheduleFeedback(result: CommuterAlertScheduleResult): String = when (result) {
    is CommuterAlertScheduleResult.Scheduled -> if (result.isExact) {
        "Avviso salvato e pianificato sul dispositivo."
    } else {
        "Avviso salvato e pianificato in modalità approssimata: Android può consegnarlo in ritardo perché gli allarmi esatti non sono consentiti."
    }
    CommuterAlertScheduleResult.QuietHours ->
        "Avviso salvato ma non pianificato: l’anticipo cade nella fascia silenziosa 22:00–07:00."
    CommuterAlertScheduleResult.InvalidTime ->
        "Avviso salvato ma non pianificato: l’orario non è valido."
    CommuterAlertScheduleResult.Disabled ->
        "Avviso salvato ma disattivato."
    CommuterAlertScheduleResult.NoUpcomingTrigger ->
        "Avviso salvato ma non pianificato: non esiste una prossima occorrenza."
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
                provider = viewModel.currentProviderLabel(),
                confidence = viewModel.currentConfidenceLabel(),
                refreshedAt = viewModel.lastRefreshEpochMillis(),
                isDemonstration = viewModel.isDemonstrationData(),
                message = viewModel.refreshMessage
            )
        }
        item {
            Text(
                text = "Per variazioni operative in stazione, fai sempre riferimento a monitor e annunci ufficiali.",
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
    openMore: () -> Unit,
    openStations: (Station?) -> Unit
) {
    var originText by rememberSaveable { mutableStateOf(viewModel.plannerOriginText()) }
    var destinationText by rememberSaveable { mutableStateOf(viewModel.plannerDestinationText()) }
    var returnJourney by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var stationText by rememberSaveable { mutableStateOf("") }
    var travelDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var travelTime by rememberSaveable { mutableStateOf(LocalTime.now().withSecond(0).withNano(0).toString()) }
    var returnDate by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    var returnTime by rememberSaveable { mutableStateOf(LocalTime.now().withSecond(0).withNano(0).toString()) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showReturnDatePicker by rememberSaveable { mutableStateOf(false) }
    var showReturnTimePicker by rememberSaveable { mutableStateOf(false) }
    var speechMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var voiceTargetName by rememberSaveable { mutableStateOf(SearchMode.ROUTE.name) }
    val context = LocalContext.current
    LaunchedEffect(modeRequestId) { mode = initialMode }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val voiceTarget = SearchMode.valueOf(voiceTargetName)
        val spoken = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else null
        if (spoken.isNullOrBlank()) {
            speechMessage = "Nessun testo ricevuto dalla dettatura. Puoi riprovare o compilare i campi manualmente."
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
            speechMessage = review.feedbackMessage(dictationTarget)
        }
    }
    fun startVoiceInput(target: SearchMode) {
        voiceTargetName = target.name
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_PROMPT, when (target) {
                SearchMode.ROUTE -> "Dì partenza, arrivo, data, ora o andata e ritorno"
                SearchMode.TRAIN -> "Dì il numero del treno"
                SearchMode.STATION -> "Dì il nome della stazione"
            })
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            speechMessage = "Il riconoscitore vocale di sistema non è disponibile su questo dispositivo."
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
                                PlannerDateTime.dateLabel(travelDate)
                            )
                            val outboundTimeDescription = stringResource(
                                R.string.accessibility_select_outbound_time,
                                PlannerDateTime.timeLabel(travelTime)
                            )
                            OutlinedButton(
                                onClick = { showDatePicker = true },
                                modifier = Modifier.weight(1f).semantics { contentDescription = outboundDateDescription }
                            ) {
                                Column(horizontalAlignment = Alignment.Start) { Text(stringResource(R.string.planner_outbound_date), style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.dateLabel(travelDate), fontWeight = FontWeight.Bold) }
                            }
                            OutlinedButton(
                                onClick = { showTimePicker = true },
                                modifier = Modifier.weight(1f).semantics { contentDescription = outboundTimeDescription }
                            ) {
                                Column(horizontalAlignment = Alignment.Start) { Text(stringResource(R.string.planner_outbound_time), style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.timeLabel(travelTime), fontWeight = FontWeight.Bold) }
                            }
                        }
                        if (returnJourney) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                val returnDateDescription = stringResource(
                                    R.string.accessibility_select_return_date,
                                    PlannerDateTime.dateLabel(returnDate)
                                )
                                val returnTimeDescription = stringResource(
                                    R.string.accessibility_select_return_time,
                                    PlannerDateTime.timeLabel(returnTime)
                                )
                                OutlinedButton(
                                    onClick = { showReturnDatePicker = true },
                                    modifier = Modifier.weight(1f).semantics { contentDescription = returnDateDescription }
                                ) {
                                    Column(horizontalAlignment = Alignment.Start) { Text(stringResource(R.string.planner_return_date), style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.dateLabel(returnDate), fontWeight = FontWeight.Bold) }
                                }
                                OutlinedButton(
                                    onClick = { showReturnTimePicker = true },
                                    modifier = Modifier.weight(1f).semantics { contentDescription = returnTimeDescription }
                                ) {
                                    Column(horizontalAlignment = Alignment.Start) { Text(stringResource(R.string.planner_return_time), style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.timeLabel(returnTime), fontWeight = FontWeight.Bold) }
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
                            Text(stringResource(R.string.planner_train_filter_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.planner_train_filter_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = viewModel.trainNumberQuery,
                                onValueChange = viewModel::updateTrainNumberQuery,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.planner_train_number)) },
                                placeholder = { Text(stringResource(R.string.planner_train_number_example)) },
                                leadingIcon = { Icon(Icons.Default.Train, contentDescription = null) }
                            )
                            if (!viewModel.hasActiveRoute) {
                                Text(stringResource(R.string.planner_choose_route_first), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Button(
                                onClick = { viewModel.refresh(); showResults() },
                                enabled = viewModel.trainNumberQuery.isNotBlank() && viewModel.hasActiveRoute,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.planner_filter_train))
                            }
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
                    Text(route.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(route.displayName(Direction.OUTBOUND), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.accessibility_use_route, route.name))
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
                Text(viewModel.activeRoute.displayName(primaryDirection), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    resultSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (roundTrip) stringResource(R.string.planner_outbound) else primaryDirection.label.uppercase(Locale.ITALIAN), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(viewModel.activeRoute.displayName(primaryDirection), style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(viewModel.refreshState.accessibleLabel, viewModel.refreshMessage).joinToString(" · "),
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
                item { SearchInfoState(stringResource(R.string.search_outbound_unavailable_title), viewModel.refreshMessage ?: stringResource(R.string.search_outbound_unavailable_message)) }
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
                    Text(viewModel.activeRoute.displayName(Direction.RETURN), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(viewModel.returnRefreshState.accessibleLabel, viewModel.returnRefreshMessage).joinToString(" · "),
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
                    item { SearchInfoState(stringResource(R.string.search_return_unavailable_title), viewModel.returnRefreshMessage ?: stringResource(R.string.search_return_unavailable_message)) }
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
private fun StationBoardScreen(modifier: Modifier, viewModel: TrainViewModel) {
    var stationText by rememberSaveable(viewModel.stationBoardStation?.id) {
        mutableStateOf(viewModel.stationBoardStation?.name.orEmpty())
    }
    var locationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var monitorMode by rememberSaveable { mutableStateOf(StationMonitorMode.BOTH.name) }
    val context = LocalContext.current

    fun locateNearbyStations() {
        requestNearbyStationLocation(context) { location ->
            if (location == null) {
                locationMessage = "Posizione non disponibile. Attiva la localizzazione del dispositivo e riprova."
            } else {
                viewModel.updateNearbyStations(location.latitude, location.longitude)
                locationMessage = "Stazioni ordinate dalla posizione appena richiesta. La posizione non è stata salvata."
            }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locateNearbyStations()
        else locationMessage = "Permesso posizione non concesso. Puoi continuare a cercare manualmente le stazioni."
    }

    val selectedStation = viewModel.stationFromName(stationText)
    val board = viewModel.stationBoard?.takeIf { current ->
        selectedStation != null &&
            current.station.id == selectedStation.id &&
            current.kind == viewModel.stationBoardKind
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
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

                    Text(stringResource(R.string.station_board_local_monitoring), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(StationMonitorMode.entries) { mode ->
                            FilterChip(
                                selected = monitorMode == mode.name,
                                onClick = { monitorMode = mode.name },
                                label = { Text(mode.label) }
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
                viewModel.stationBoardRefreshState == RefreshState.LOADING && board == null -> item {
                    SearchInfoState(stringResource(R.string.station_board_loading_title), stringResource(R.string.station_board_loading_message))
                }
                viewModel.stationBoardRefreshState == RefreshState.ERROR && board == null -> item {
                    SearchInfoState(
                        stringResource(R.string.station_board_unavailable_title),
                        viewModel.stationBoardMessage ?: stringResource(R.string.station_board_unavailable_message)
                    )
                }
                board != null && board.entries.isEmpty() -> item {
                    SearchInfoState(
                        stringResource(R.string.station_board_empty_title),
                        viewModel.stationBoardMessage ?: stringResource(R.string.station_board_empty_message)
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
                            viewModel.stationBoardMessage?.let { message ->
                                Text(
                                    message,
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
                        StationBoardEntryRow(entry, board.kind)
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
                    distanceLabel = String.format(Locale.ITALIAN, "%.1f km", nearby.distanceKm),
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
internal fun StationBoardEntryRow(entry: StationBoardEntry, kind: BoardKind) {
    val scheduled = entry.scheduledTime.withZoneSameInstant(BOARD_ZONE)
    val expected = entry.expectedTime?.value?.withZoneSameInstant(BOARD_ZONE)
    val effective = expected ?: scheduled
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
    val trainLabel = entry.publicNumber?.takeIf(String::isNotBlank)?.let { "Treno $it" }.orEmpty()
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
            .clearAndSetSemantics { contentDescription = accessibilityDescription }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(effective.format(BOARD_TIME_FORMATTER), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (expected != null && expected.toInstant() != scheduled.toInstant()) {
                Text(
                    stringResource(R.string.station_board_scheduled_time_short, scheduled.format(BOARD_TIME_FORMATTER)),
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
                    entry.publicNumber?.takeIf(String::isNotBlank)?.let { "Treno $it" },
                    entry.operatorId?.value?.takeIf(String::isNotBlank),
                    entry.status?.value?.stationBoardLabel()
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (delayedMinutes > 0) {
                Text("+$delayedMinutes min", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            entry.routeChange?.value?.let { change ->
                Text(
                    "Variazione: ${change.summary}",
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

private fun RunStatus.stationBoardLabel(): String = when (this) {
    RunStatus.SCHEDULED -> "Programmato"
    RunStatus.ON_TIME -> "In orario"
    RunStatus.DELAYED -> "In ritardo"
    RunStatus.CANCELLED -> "Soppresso"
    RunStatus.PARTIALLY_CANCELLED -> "Parzialmente soppresso"
    RunStatus.DIVERTED -> "Deviato"
    RunStatus.ADDED -> "Corsa aggiunta"
    RunStatus.REPLACED_BY_BUS -> "Sostituito da bus"
    RunStatus.COMPLETED -> "Completato"
    RunStatus.UNKNOWN -> "Stato non disponibile"
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
                contentDescription = if (favourite) "Rimuovi ${station.name} dalle stazioni preferite" else "Aggiungi ${station.name} alle stazioni preferite"
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
                    Text("PARTENZE · ${if (direction == Direction.OUTBOUND) "ANDATA" else "RITORNO"}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(route.displayName(direction), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${route.name} · ${if (direction == Direction.OUTBOUND) "Andata" else "Ritorno"}", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Aggiorna partenze. Stato: ${refreshState.accessibleLabel}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onSwitchDirection) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Direzione")
                }
                TextButton(onClick = onRoutes) { Text("Cambia tratta", color = MaterialTheme.colorScheme.onPrimary) }
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
        Text("ORA", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(68.dp))
        Text("DESTINAZIONE / TRENO", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("BIN.", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(42.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Text("STATO", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(50.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
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
            Text("Tratte monitorate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onManage) { Text("Gestisci") }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp)
        ) {
            items(routes.take(6), key = { it.id }) { route ->
                FilterChip(
                    selected = route.id == activeId,
                    onClick = { onActivate(route) },
                    label = { Text(route.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
    message: String?
) {
    val label = when {
        isDemonstration -> stringResource(R.string.data_status_demo)
        freshness == Freshness.UNAVAILABLE || freshness == null -> stringResource(R.string.data_status_unavailable)
        else -> stringResource(R.string.data_status_live, freshness.accessibleLabel)
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
        accessibleUpdateTime(refreshedAt),
        message?.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
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
            Text(if (refreshedAt > 0L) "$provider · ${accessibleUpdateTime(refreshedAt)}" else provider, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            message?.takeIf { it.isNotBlank() }?.let {
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
private fun DepartureListRow(service: TrainService, isNext: Boolean = false, onOpen: () -> Unit) {
    val serviceState = if (service.operationalStatusVerified) {
        stringResource(R.string.service_state_live, service.status.accessibleLabel)
    } else {
        stringResource(R.string.service_state_scheduled)
    }
    val serviceContentDescription = stringResource(
        R.string.accessibility_open_service,
        service.accessibleSummary,
        serviceState
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isNext) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f) else MaterialTheme.colorScheme.background)
            .clearAndSetSemantics {
                contentDescription = serviceContentDescription
            }
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(68.dp)) {
            Text(service.expectedDeparture, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            if (!service.operationalStatusVerified) Text("programmato", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (service.delayMinutes > 0) Text("+${service.delayMinutes} min", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            else Text("in orario", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(service.destination, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${service.displayOperator} · ${service.category} ${service.number}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Text(service.platform?.let { "Bin. $it" } ?: "—", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(50.dp)) {
            Text(compactStatusLabel(service), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun TodayEmptyState(state: RefreshState, message: String?, onRefresh: () -> Unit, onRoutes: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
        Text(if (state == RefreshState.LOADING) "Cerco le partenze…" else "Nessuna partenza da mostrare", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Aggiorna la tratta attiva oppure scegli una tratta salvata diversa. I dati non disponibili non vengono sostituiti con orari stimati.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        message?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh) { Text("Aggiorna") }
            OutlinedButton(onClick = onRoutes) { Text("Cambia tratta") }
        }
    }
}

@Composable
private fun SearchInfoState(title: String, message: String, openRoutes: (() -> Unit)? = null) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        openRoutes?.let { OutlinedButton(onClick = it) { Text("Apri le mie tratte") } }
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
        item { FilterChip(selected = selected == null, onClick = { onSelected(null) }, label = { Text("Tutti") }) }
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
        Text("CAMBI", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(JourneyChangeFilter.entries) { filter ->
                FilterChip(
                    selected = selected == filter,
                    onClick = { onSelected(filter) },
                    label = { Text(filter.label) },
                    modifier = Modifier.semantics { contentDescription = filter.accessibleLabel }
                )
            }
        }
    }
}

@Composable
private fun VoiceInputButton(onClick: () -> Unit, label: String) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label tramite riconoscitore vocale di sistema" }
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
    val coroutineScope = rememberCoroutineScope()
    val createBackupDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LocalDataBackupCodec.MIME_TYPE)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupFeedback = runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(viewModel.exportLocalBackup()) }
                ?: error("Impossibile aprire il file scelto.")
            "Backup locale esportato. Conservalo in un luogo protetto."
        }.getOrElse { "Esportazione non riuscita: ${it.message ?: "errore file"}" }
    }
    val openBackupDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            backupFeedback = runCatching {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.let(LocalDataBackupCodec::readBoundedUtf8)
                        ?: error("Impossibile leggere il file scelto.")
                }
                val stationCatalog = viewModel.stations
                val decoded = withContext(Dispatchers.Default) {
                    LocalDataBackupCodec.decode(raw, stationCatalog)
                }
                viewModel.importLocalBackup(decoded)
            }.getOrElse { "Importazione non riuscita: ${it.message ?: "errore file"}" }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val alert = pendingAlert
        pendingAlert = null
        if (granted && alert != null) {
            alertFeedback = commuterAlertScheduleFeedback(CommuterAlertScheduler.schedule(context, alert))
        } else {
            alertFeedback = "Avviso salvato. Per riceverlo, consenti le notifiche di Binario nelle impostazioni Android."
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
                    alertFeedback = commuterAlertScheduleFeedback(CommuterAlertScheduler.schedule(context, alert))
                }
            }
        )
    }
    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Impostazioni", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Gestisci preferenze, viaggi personali e integrazioni di Binario.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsSectionTitle("Aspetto")
            SettingsActionRow(
                title = "Tema dell’app",
                detail = "Sistema, chiaro o scuro",
                onClick = onThemeClick
            )
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Viaggi personali", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Le tratte fissate restano disponibili anche nel widget. Tocca una tratta per renderla attiva.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { showComposer = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Aggiungi una tratta")
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
        item { SettingsSectionTitle("Integrazioni") }
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
                    Text("Treni monitorati", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Gli avvisi in tempo reale saranno disponibili solo con una fonte autorizzata.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(viewModel.monitoredServices, key = { it.number }) { monitor ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Train, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Treno ${monitor.number}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(monitor.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.removeServiceMonitoring(monitor.number) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Rimuovi monitoraggio treno ${monitor.number}")
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Privacy e dati", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Tratte, preferiti, monitoraggi, avvisi e widget restano nel dispositivo. Backup e trasferimento Android sono esclusi; il file JSON viene esportato solo se lo scegli. La posizione serve solo a ordinare le stazioni vicine e non viene conservata né inviata alle fonti ferroviarie.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Le notifiche sono promemoria locali. La dettatura usa il riconoscitore di sistema: Binario non registra audio e usa solo il testo restituito. Le ricerche RFI inviano alla fonte l’identificativo pubblico della stazione di partenza; le fonti personali opzionali possono ricevere un identificativo o il nome della stazione. Senza una fonte autorizzata: dati non disponibili in tempo reale.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Se attivi lo smartwatch, riceve una sintesi della tratta e fino a due corse; disattivare la condivisione ferma nuovi invii ma non cancella la cache già sul watch. L’informativa completa è PRIVACY.md nel repository. Prima di una distribuzione pubblica manca un URL verificato e un recapito privacy: non esiste ancora un link pubblico nell’app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Autocompletamento nazionale: © OpenStreetMap contributors, ODbL 1.0. Il catalogo geografico suggerisce nomi di stazioni ma non conferma servizio, orario, binario, ritardo o circolazione.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics { contentDescription = "$title. $detail" }
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
                    Text(alert.accessibleSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    FilterChip(selected = repeat == option.name, onClick = { repeat = option.name }, label = { Text(option.label) })
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
        Text("Viaggio e monitoraggi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        activeTrip?.let { trip ->
            Text("Viaggio attivo · Treno ${trip.trainNumber}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(trip.nextStop?.let { "Prossima fermata: ${it.station}, prevista ${it.expected}${it.platform?.let { platform -> ", binario $platform" }.orEmpty()}" } ?: "Viaggio completato", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAdvanceTrip, enabled = !trip.isComplete) { Text("Fermata raggiunta") }
                TextButton(onClick = onEndTrip) { Text("Termina") }
            }
        } ?: Text("Nessun viaggio attivo. Apri il dettaglio di un treno per iniziarne il monitoraggio personale.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (stationMonitors.isNotEmpty()) {
            Text("Stazioni monitorate", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            stationMonitors.forEach { monitor ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(monitor.accessibleSummary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { onRemoveStationMonitor(monitor.stationId) }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Rimuovi monitoraggio ${monitor.stationName}") }
                }
            }
        }
    }
}

@Composable
private fun MobilityPreferencesPanel(value: MobilityPreferences, onChange: (MobilityPreferences) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mobilità e accessibilità", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Queste preferenze restano sul dispositivo e guideranno i filtri quando sarà disponibile una fonte autorizzata.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Cambi preferiti", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = value.maxChanges == null, onClick = { onChange(value.copy(maxChanges = null)) }, label = { Text("Qualsiasi") }) }
            items(listOf(0, 1, 2)) { changes -> FilterChip(selected = value.maxChanges == changes, onClick = { onChange(value.copy(maxChanges = changes)) }, label = { Text(if (changes == 0) "Diretto" else "Max $changes") }) }
        }
        Text("Margine di interscambio", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(5, 10, 15, 20)) { minutes -> FilterChip(selected = value.transferMarginMinutes == minutes, onClick = { onChange(value.copy(transferMarginMinutes = minutes)) }, label = { Text("$minutes min") }) }
        }
        FilterChip(selected = value.stepFreePreferred, onClick = { onChange(value.copy(stepFreePreferred = !value.stepFreePreferred)) }, label = { Text("Priorità percorsi senza barriere") })
        FilterChip(selected = value.compactTimetablePreferred, onClick = { onChange(value.copy(compactTimetablePreferred = !value.compactTimetablePreferred)) }, label = { Text("Preferisco tabelloni compatti") })
        Text(value.accessibleSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LocalBackupPanel(feedback: String?, onExport: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Backup locale", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Esporta o importa tratte, preferiti, monitoraggi, avvisi e preferenze con un file JSON scelto da te. Non viene usato alcun cloud automatico.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("Esporta") }
            Button(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Importa") }
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
            Text("Integrazioni Binario", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Tratte, monitoraggi e qualità del dato sono controllati dall’app. Credenziali e token non vengono memorizzati qui.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IntegrationChoice(
                title = "Smartwatch Android",
                detail = if (settings.wearSnapshotSharingEnabled) "Snapshot della tratta attiva al companion Wear OS" else "Sincronizzazione snapshot disattivata",
                selected = settings.wearSnapshotSharingEnabled,
                onClick = { onWearSharingChanged(!settings.wearSnapshotSharingEnabled) }
            )
            IntegrationChoice(
                title = "Comandi vocali e Alexa",
                detail = when (voiceReadiness) {
                    VoiceProfileSyncReadiness.DISABLED -> "Profilo vocale non condiviso; nessun account collegato"
                    VoiceProfileSyncReadiness.ACCOUNT_LINK_REQUIRED -> "Collegamento account Alexa Companion richiesto; nessun dato inviato"
                    VoiceProfileSyncReadiness.READY_FOR_AUTHENTICATED_SYNC -> "Profilo pronto per sincronizzazione autenticata"
                },
                selected = settings.voiceProfileSyncRequested,
                onClick = { onVoiceProfileChanged(!settings.voiceProfileSyncRequested) }
            )
            IntegrationChoice(
                title = "Apple Watch",
                detail = if (settings.appleWatchSetupRequested) "Preferenza pronta per il companion watchOS" else "Companion iOS da completare; nessun dato condiviso",
                selected = settings.appleWatchSetupRequested,
                onClick = { onAppleWatchSetupChanged(!settings.appleWatchSetupRequested) }
            )
            Text("Le risposte personali richiedono un account Alexa effettivamente collegato. Senza feed autorizzato: dati non disponibili in tempo reale.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IntegrationChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$title. $detail" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilterChip(selected = selected, onClick = onClick, label = { Text(if (selected) "Attivo" else "Non attivo") })
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
                Text(route.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (route.isPinned) Text("Tratta fissata", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(route.displayName(Direction.OUTBOUND), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Sposta ${route.name} su")
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Sposta ${route.name} giù")
                }
                IconButton(onClick = onPin) {
                    Icon(if (route.isPinned) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = if (route.isPinned) "Rimuovi fissaggio" else "Fissa tratta")
                }
                IconButton(onClick = onRemove, enabled = canRemove) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Elimina tratta ${route.name}")
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
            Text("Nuova tratta", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Scegli origine e destinazione. La tratta rimane solo sul dispositivo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Scambia stazioni")
            }
            StationField(
                            label = stringResource(R.string.planner_destination),
                            value = destinationText,
                            onValueChange = { destinationText = it; viewModel.updateStationSuggestions("route-composer-destination", it) },
                            matches = viewModel.stationSuggestions("route-composer-destination"),
                            onSelect = { destinationText = it.name; viewModel.clearStationSuggestions("route-composer-destination") }
                        )
            OutlinedTextField(value = routeName, onValueChange = { routeName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nome personale facoltativo") }, placeholder = { Text("Es. Casa – lavoro") })
            Button(
                onClick = { if (viewModel.createRoute(origin!!, destination!!, routeName)) onDismiss() },
                enabled = origin != null && destination != null && origin != destination,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Salva e attiva tratta") }
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
    val dataLabel = if (isDemonstration) "Catalogo dimostrativo locale — non live" else freshness?.accessibleLabel ?: "dati non disponibili in tempo reale"
    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${service.displayOperator} · ${service.category}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text("${service.number} · ${service.origin} → ${service.destination}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Partenza prevista", style = MaterialTheme.typography.labelMedium)
                            Text(service.expectedDeparture, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text("Programmata ${service.scheduledDeparture}", style = MaterialTheme.typography.bodySmall)
                        }
                        ServiceStatusBadge(service)
                    }
                    Text(service.platform?.let { "Binario $it" } ?: "Binario da confermare", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                    Text("Fermate", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("$dataLabel · $provider · $confidence", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(accessibleUpdateTime(refreshedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = "Condividi dettagli del treno con le app di sistema")
                    }
                    IconButton(onClick = onToggleMonitoring) {
                        Icon(if (isMonitored) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = if (isMonitored) "Rimuovi monitoraggio treno" else "Monitora questo treno")
                    }
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Aggiorna dettaglio della corsa") }
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
                    Text(if (isThisTrip) "Viaggio attivo" else "Segui questo viaggio", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(if (isThisTrip) activeTrip?.nextStop?.let { "Prossima fermata: ${it.station} alle ${it.expected}" } ?: "Viaggio completato" else "Conserva localmente la prossima fermata e avanzala manualmente.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isThisTrip) Button(onClick = onStartActiveTrip) { Text("Inizia viaggio") }
                        else {
                            OutlinedButton(onClick = onAdvanceActiveTrip, enabled = activeTrip?.isComplete == false) { Text("Fermata raggiunta") }
                            TextButton(onClick = onEndActiveTrip) { Text("Termina") }
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
                "La sveglia è locale e si basa sull’orario mostrato. Per variazioni operative fai sempre riferimento a monitor e annunci ufficiali.",
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
                operationalStatusVerified = service.operationalStatusVerified,
                routeChange = stop.routeChange,
                onSetAlarm = { onScheduleStopAlarm(service, stop) }
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StopTimelineRow(
    station: String,
    expected: String,
    scheduled: String,
    delay: Int,
    platform: String?,
    completed: Boolean,
    operationalStatusVerified: Boolean,
    routeChange: StopRouteChange,
    onSetAlarm: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .semantics {
                val changeLabel = when (routeChange) {
                    StopRouteChange.NONE -> ""
                    StopRouteChange.ADDED -> " Fermata aggiunta al percorso operativo."
                    StopRouteChange.SKIPPED -> " Fermata saltata nel percorso operativo."
                }
                contentDescription = "$station.$changeLabel Previsto $expected. Programmato $scheduled. ${if (!operationalStatusVerified) "Stato operativo non verificato" else if (delay > 0) "$delay minuti di ritardo" else "In orario"}. ${platform?.let { "Binario $it" } ?: "Binario non disponibile"}."
            },
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
                StopRouteChange.SKIPPED -> Text("FERMATA SALTATA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                StopRouteChange.ADDED -> Text("FERMATA AGGIUNTA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                StopRouteChange.NONE -> Unit
            }
            Text(platform?.let { "Binario $it" } ?: "Binario da confermare", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(expected, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            if (expected != scheduled) Text("prev. $scheduled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (delay > 0) Text("+$delay min", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            if (!completed) {
                TextButton(onClick = onSetAlarm, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("Sveglia")
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .semantics {
                contentDescription = buildString {
                    append(routeChangeLabel(routeChange)).append(". ")
                    append(routeChange.summary)
                    if (routeChange.skippedStopLabels.isNotEmpty()) {
                        append(". Fermate saltate: ").append(routeChange.skippedStopLabels.joinToString(", "))
                    }
                    if (routeChange.addedStopLabels.isNotEmpty()) {
                        append(". Fermate aggiunte: ").append(routeChange.addedStopLabels.joinToString(", "))
                    }
                    if (routeChange.viaStopLabels.isNotEmpty()) {
                        append(". Nuovo instradamento via: ").append(routeChange.viaStopLabels.joinToString(", "))
                    }
                    routeChange.replacementDestinationLabel?.let {
                        append(". Nuovo capolinea: ").append(it)
                    }
                }
            },
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
                Text(routeChangeLabel(routeChange), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(routeChange.summary, style = MaterialTheme.typography.bodyMedium)
                if (routeChange.skippedStopLabels.isNotEmpty()) {
                    Text("Saltate: ${routeChange.skippedStopLabels.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                if (routeChange.addedStopLabels.isNotEmpty()) {
                    Text("Aggiunte: ${routeChange.addedStopLabels.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                if (routeChange.viaStopLabels.isNotEmpty()) {
                    Text("Via: ${routeChange.viaStopLabels.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                routeChange.replacementDestinationLabel?.let {
                    Text("Nuovo capolinea: $it", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                sourceLabel?.let {
                    Text("Fonte: $it", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun routeChangeLabel(routeChange: OperationalRouteChange): String = when (routeChange.kind) {
    RouteChangeKind.DIVERTED -> "PERCORSO MODIFICATO"
    RouteChangeKind.SKIPPED_STOPS -> "FERMATE MODIFICATE"
    RouteChangeKind.ADDED_STOPS -> "FERMATE AGGIUNTE"
    RouteChangeKind.SHORT_TURN -> "CORSA LIMITATA"
    RouteChangeKind.REPLACEMENT_SERVICE -> "SERVIZIO SOSTITUTIVO"
    RouteChangeKind.OTHER -> "VARIAZIONE DI PERCORSO"
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
        Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "Programmato")
    } else when (service.status) {
        ServiceStatus.ON_TIME -> Triple(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary, "In orario")
        ServiceStatus.DELAYED -> Triple(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary, "+${service.delayMinutes} min")
        ServiceStatus.CANCELLED -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError, "Soppresso")
    }
    Surface(color = container, contentColor = foreground, shape = RoundedCornerShape(999.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

private fun compactStatusLabel(service: TrainService): String {
    if (!service.operationalStatusVerified) return "Programm."
    return when (service.status) {
        ServiceStatus.ON_TIME -> "In orario"
        ServiceStatus.DELAYED -> "+${service.delayMinutes} min"
        ServiceStatus.CANCELLED -> "Soppresso"
    }
}
