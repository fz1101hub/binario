package it.binario.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import it.binario.app.data.Direction
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.gpsalarm.GpsArrivalAlarmPanel
import it.binario.app.gpsalarm.StopAlarmOptionsDialog
import it.binario.app.gpsalarm.rememberGpsArrivalAlarmFlow
import it.binario.app.maps.rememberOpenMapStationIndex
import it.binario.app.plannedjourney.PlannedJourneyAgendaScreen
import it.binario.app.plannedjourney.PlannedJourneyExportFormat
import it.binario.app.plannedjourney.PlannedJourneyIcsContract
import it.binario.app.plannedjourney.PlannedJourneyIcsText
import it.binario.app.plannedjourney.PlannedJourneyStation
import it.binario.app.plannedjourney.PlannedJourneyTransferContract
import it.binario.app.plannedjourney.PlannedJourneyViewModel
import it.binario.app.ui.AppNavigation
import it.binario.app.ui.AppScreen
import it.binario.app.ui.TopDestination
import it.binario.app.ui.WidthClass
import it.binario.app.ui.common.BinarioNavigationBar
import it.binario.app.ui.common.BinarioNavigationRail
import it.binario.app.ui.common.BinarioTopBar
import it.binario.app.ui.common.DetailPlaceholder
import it.binario.app.ui.common.localizedQualityLabel
import it.binario.app.ui.departures.DeparturesScreen
import it.binario.app.ui.departures.OperationsSection
import it.binario.app.ui.departures.StationBoardRunDetailsPane
import it.binario.app.ui.detail.ServiceDetailScreen
import it.binario.app.ui.detail.trainShareStrings
import it.binario.app.ui.foryou.ForYouScreen
import it.binario.app.ui.planner.PlannerScreen
import it.binario.app.ui.planner.RouteResultsScreen
import it.binario.app.ui.planner.SearchMode
import it.binario.app.widget.WidgetConfigurationStore
import it.binario.app.widget.WidgetStateStore
import kotlinx.coroutines.delay

private val stopAlarmPlanSaver = listSaver<StopAlarmPlan?, Any>(
    save = { plan ->
        if (plan == null) emptyList()
        else listOf(plan.trainNumber, plan.destination, plan.station, plan.expectedTime, plan.triggerAtEpochMillis, plan.expectedAtEpochMillis)
    },
    restore = { values ->
        if (values.isEmpty()) null
        else StopAlarmPlan(
            trainNumber = values[0] as String,
            destination = values[1] as String,
            station = values[2] as String,
            expectedTime = values[3] as String,
            triggerAtEpochMillis = values[4] as Long,
            expectedAtEpochMillis = values.getOrNull(5) as? Long ?: values[4] as Long
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
                    onDeepLinkConsumed = ::consumeDeepLink,
                    onSystemBarsAppearance = ::applySystemBarsAppearance
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

    /** Status and navigation bar icons follow the colour actually drawn behind them (green planner bar, theme). */
    private fun applySystemBarsAppearance(lightStatusBar: Boolean, lightNavigationBar: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightStatusBar
        controller.isAppearanceLightNavigationBars = lightNavigationBar
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BinarioApp(
    viewModel: TrainViewModel,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    deepLinkRequest: DeepLinkRequest?,
    onDeepLinkConsumed: (Long) -> Unit,
    onSystemBarsAppearance: (lightStatusBar: Boolean, lightNavigationBar: Boolean) -> Unit
) {
    var screen by rememberSaveable { mutableStateOf(AppScreen.PLANNER) }
    var detailReturnScreen by rememberSaveable { mutableStateOf(AppScreen.RESULTS) }
    var operationsSectionName by rememberSaveable { mutableStateOf(OperationsSection.TRAINS.name) }
    var plannerSearchModeName by rememberSaveable { mutableStateOf(SearchMode.ROUTE.name) }
    var plannerSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var showThemePicker by rememberSaveable { mutableStateOf(false) }
    var pendingStopAlarm by rememberSaveable(stateSaver = stopAlarmPlanSaver) {
        mutableStateOf<StopAlarmPlan?>(null)
    }
    var stopAlarmFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    val gpsArrivalAlarm = rememberGpsArrivalAlarmFlow()
    // The stop chosen with the last stop alarm: the detail of that train looks for connections there.
    var stopAlarmTrain by rememberSaveable { mutableStateOf<String?>(null) }
    var stopAlarmStation by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, screen, operationsSectionName) {
        if (AppNavigation.refreshesPeriodically(screen)) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (screen == AppScreen.DEPARTURES && operationsSectionName == OperationsSection.STATIONS.name) {
                    viewModel.refreshStationBoardIfIdle()
                }
                while (true) {
                    delay(30_000)
                    if (screen == AppScreen.NUMBER_RESULTS) {
                        viewModel.searchTrainNumber(refresh = true)
                    } else if (screen == AppScreen.DEPARTURES && operationsSectionName == OperationsSection.STATIONS.name) {
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
            gpsArrivalAlarm.onTimeAlarmScheduled(plan)
        } else {
            gpsArrivalAlarm.onTimeAlarmRefused()
            stopAlarmFeedback = resources.getString(R.string.stop_alarm_permission_required)
        }
    }
    val confirmStopAlarm: (StopAlarmPlan) -> Unit = { plan ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStopAlarm = plan
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            stopAlarmFeedback = stopAlarmScheduleFeedback(resources, StopAlarmScheduler.schedule(context, plan), plan)
            gpsArrivalAlarm.onTimeAlarmScheduled(plan)
        }
    }
    val scheduleStopAlarm: (TrainService, TrainStop) -> Unit = { service, stop ->
        val plan = StopAlarmPlanner.plan(service, stop)
        if (plan != null && !stop.completed) {
            stopAlarmTrain = service.number
            stopAlarmStation = stop.station
        }
        when {
            stop.completed -> stopAlarmFeedback = resources.getString(R.string.stop_alarm_completed, stop.station)
            plan == null -> stopAlarmFeedback = resources.getString(R.string.stop_alarm_unavailable)
            // The options dialog adds the optional GPS part; confirming it schedules the time-based alarm.
            else -> gpsArrivalAlarm.offer(service, stop, plan, viewModel.stations)
        }
    }
    StopAlarmOptionsDialog(gpsArrivalAlarm, onScheduleTimeAlarm = confirmStopAlarm)
    /** Leaves the detail (if open) so that its pinned snapshot no longer drives the other screens. */
    fun showScreen(target: AppScreen) {
        if (screen == AppScreen.DETAIL && target != AppScreen.DETAIL) viewModel.closeService()
        screen = target
    }
    fun openDetail(parent: AppScreen, open: () -> Unit) {
        detailReturnScreen = parent
        open()
        screen = AppScreen.DETAIL
    }
    fun showDepartures(section: OperationsSection) {
        operationsSectionName = section.name
        showScreen(AppScreen.DEPARTURES)
    }
    LaunchedEffect(deepLinkRequest?.id) {
        val request = deepLinkRequest ?: return@LaunchedEffect
        val deepLink = request.deepLink
        when (deepLink) {
            BinarioDeepLink.Agenda ->
                plannedJourneyViewModel.selectFilter(it.binario.app.plannedjourney.PlannedJourneyAgendaFilter.UPCOMING)
            is BinarioDeepLink.TrainSearch -> viewModel.updateTrainNumberQuery(deepLink.number.orEmpty())
            is BinarioDeepLink.WidgetRoute -> viewModel.activateRoute(deepLink.route, deepLink.direction)
            BinarioDeepLink.ActiveRoute, BinarioDeepLink.NextDepartures -> Unit
        }
        val target = AppNavigation.targetOf(deepLink)
        target.departuresSection?.let { operationsSectionName = it.name }
        target.plannerMode?.let { mode ->
            plannerSearchModeName = mode.name
            plannerSearchRequestId += 1
        }
        showScreen(target.screen)
        // Clear the event only after applying it. Subsequent recompositions and
        // lifecycle restores therefore cannot replay the same external navigation.
        onDeepLinkConsumed(request.id)
    }
    val plannerSearchMode = SearchMode.valueOf(plannerSearchModeName)
    val operationsSection = OperationsSection.valueOf(operationsSectionName)
    val backTarget = AppNavigation.back(screen, detailReturnScreen)
    val navigateBack: () -> Unit = { backTarget?.let(::showScreen) }
    BackHandler(enabled = backTarget != null, onBack = navigateBack)
    val selectedDestination = AppNavigation.destinationOf(screen, detailReturnScreen)
    val selectDestination: (TopDestination) -> Unit = { destination -> showScreen(AppNavigation.rootOf(destination)) }
    val title = stringResource(
        when (screen) {
            AppScreen.PLANNER -> R.string.app_brand
            AppScreen.DEPARTURES -> R.string.nav_departures
            AppScreen.FOR_YOU -> R.string.nav_for_you
            AppScreen.AGENDA -> R.string.screen_title_agenda
            AppScreen.RESULTS -> R.string.screen_title_results
            AppScreen.NUMBER_RESULTS -> R.string.train_number_results_title
            AppScreen.DETAIL -> R.string.screen_title_detail
        }
    )
    val subtitle = when (screen) {
        // The green planner header below the bar carries the headline and its subtitle.
        AppScreen.PLANNER, AppScreen.DETAIL -> null
        AppScreen.DEPARTURES -> stringResource(R.string.departures_subtitle)
        AppScreen.FOR_YOU -> stringResource(R.string.for_you_subtitle)
        AppScreen.AGENDA -> stringResource(R.string.screen_subtitle_agenda)
        AppScreen.RESULTS -> stringResource(R.string.screen_subtitle_results)
        AppScreen.NUMBER_RESULTS -> stringResource(R.string.train_number_results_subtitle)
    }
    val greenTopBar = screen == AppScreen.PLANNER
    val topBarColor = if (greenTopBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background
    val navigationBarColor = MaterialTheme.colorScheme.surface
    SideEffect {
        onSystemBarsAppearance(topBarColor.luminance() > 0.5f, navigationBarColor.luminance() > 0.5f)
    }

    if (showThemePicker) {
        ThemePickerSheet(currentMode = themeMode, onModeSelected = {
            onThemeModeChange(it)
            showThemePicker = false
        }, onDismiss = { showThemePicker = false })
    }

    // inSidePane: from 840 dp a list shares the window with the detail pane (station run details included).
    val screenContent: @Composable (AppScreen, Modifier, Boolean) -> Unit = { shown, modifier, inSidePane ->
        when (shown) {
            AppScreen.PLANNER -> PlannerScreen(
                modifier = modifier,
                viewModel = viewModel,
                initialMode = plannerSearchMode,
                modeRequestId = plannerSearchRequestId,
                showResults = { showScreen(AppScreen.RESULTS) },
                showTrainResults = { showScreen(AppScreen.NUMBER_RESULTS) },
                openMore = { showScreen(AppScreen.FOR_YOU) },
                openStations = { station ->
                    station?.let(viewModel::openStationBoard)
                    showDepartures(OperationsSection.STATIONS)
                }
            )
            AppScreen.DEPARTURES -> DeparturesScreen(
                modifier = modifier,
                viewModel = viewModel,
                selectedSection = operationsSection,
                onSectionSelected = { section ->
                    operationsSectionName = section.name
                    // A side-pane detail belongs to the route board it was opened from.
                    if (screen == AppScreen.DETAIL) showScreen(AppScreen.DEPARTURES)
                },
                openDetails = { service -> openDetail(AppScreen.DEPARTURES) { viewModel.openService(service) } },
                manageRoutes = { showScreen(AppScreen.FOR_YOU) },
                stationRunDetailsInSheet = !inSidePane
            )
            AppScreen.FOR_YOU -> ForYouScreen(
                modifier = modifier,
                viewModel = viewModel,
                onThemeClick = { showThemePicker = true },
                openRoute = {
                    viewModel.activateRoute(it)
                    showDepartures(OperationsSection.TRAINS)
                },
                openAgenda = { showScreen(AppScreen.AGENDA) },
                openStationBoard = { station ->
                    viewModel.openStationBoard(station)
                    showDepartures(OperationsSection.STATIONS)
                },
                gpsAlarmPanel = { GpsArrivalAlarmPanel(gpsArrivalAlarm) }
            )
            AppScreen.AGENDA -> PlannedJourneyAgendaScreen(
                modifier = modifier,
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
            AppScreen.RESULTS -> RouteResultsScreen(
                modifier = modifier,
                viewModel = viewModel,
                openDetails = { service -> openDetail(AppScreen.RESULTS) { viewModel.openService(service) } }
            )
            AppScreen.NUMBER_RESULTS -> TrainNumberResultsScreen(
                modifier = modifier, viewModel = viewModel,
                onOpen = { match -> openDetail(AppScreen.NUMBER_RESULTS) { viewModel.openTrainNumberMatch(match) } }
            )
            AppScreen.DETAIL -> {
                val service = viewModel.selectedService
                val detailProvider = viewModel.currentProviderLabel() ?: stringResource(R.string.provider_unavailable)
                val detailConfidence = viewModel.localizedQualityLabel()
                if (service == null) {
                    // The selected service is intentionally in-memory. After process
                    // death, a restored DETAIL screen returns to its saved parent
                    // instead of showing an empty screen or recreating stale data.
                    LaunchedEffect(detailReturnScreen) { screen = detailReturnScreen }
                } else ServiceDetailScreen(
                    modifier = modifier,
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
                    gpsAlarmPanel = { GpsArrivalAlarmPanel(gpsArrivalAlarm) },
                    activeTrip = viewModel.activeTrip,
                    onStartActiveTrip = { viewModel.startActiveTrip(service) },
                    onAdvanceActiveTrip = viewModel::advanceActiveTrip,
                    onEndActiveTrip = viewModel::endActiveTrip,
                    connectionStop = stopAlarmStation.takeIf { stopAlarmTrain == service.number },
                    hasBoardProviders = viewModel.hasStationBoardProviders,
                    loadConnectionBoard = viewModel::connectionBoard,
                    shareStrings = trainShareStrings,
                    punctuality = viewModel.punctualityStats(service),
                    onClearPunctuality = { viewModel.clearPunctualityHistory(service.number) },
                    mapStationIndex = rememberOpenMapStationIndex(viewModel.stations, viewModel::stationFromName),
                    sourceSections = { TrainDetailSourceSections(service, viewModel.stations) },
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

    // Width classes come from the window this activity really has (multi-window and foldables included).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthClass = WidthClass.fromWidthDp(maxWidth.value)
        val withRail = widthClass != WidthClass.COMPACT
        val listPane = AppNavigation.listPaneOf(screen, detailReturnScreen, widthClass)
        Scaffold(
            topBar = {
                BinarioTopBar(
                    title = title,
                    subtitle = subtitle,
                    containerColor = topBarColor,
                    contentColor = if (greenTopBar) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
                    brand = greenTopBar,
                    navigationIcon = {
                        if (backTarget != null) {
                            IconButton(onClick = navigateBack, modifier = Modifier.testTag("top-bar-back")) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showThemePicker = true }) {
                            Icon(Icons.Default.Palette, contentDescription = stringResource(R.string.action_choose_theme, themeMode.localizedLabel()))
                        }
                        if (screen == AppScreen.DEPARTURES || screen == AppScreen.RESULTS || screen == AppScreen.NUMBER_RESULTS || screen == AppScreen.DETAIL) {
                            IconButton(onClick = {
                                if (screen == AppScreen.NUMBER_RESULTS) viewModel.searchTrainNumber(refresh = true)
                                else if (screen == AppScreen.DEPARTURES && operationsSection == OperationsSection.STATIONS) {
                                    viewModel.refreshStationBoardIfIdle()
                                } else viewModel.refresh()
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh_rail_information))
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (!withRail && AppNavigation.showsNavigation(screen, widthClass)) {
                    BinarioNavigationBar(selected = selectedDestination, onSelect = selectDestination, containerColor = navigationBarColor)
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (withRail) {
                    // Below the full-width app bar, so the status bar area keeps a single colour.
                    BinarioNavigationRail(selected = selectedDestination, onSelect = selectDestination)
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                when {
                    listPane != null -> {
                        // List and detail side by side: the list keeps its place while trains are opened.
                        screenContent(listPane, Modifier.weight(1f).fillMaxHeight(), true)
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(Modifier.weight(1.2f).fillMaxHeight().testTag("detail-pane")) {
                            when {
                                screen == AppScreen.DETAIL -> screenContent(AppScreen.DETAIL, Modifier.fillMaxSize(), true)
                                listPane == AppScreen.DEPARTURES && operationsSection == OperationsSection.STATIONS ->
                                    StationBoardRunDetailsPane(Modifier.fillMaxSize(), viewModel) { DetailPlaceholder() }
                                else -> DetailPlaceholder()
                            }
                        }
                    }
                    withRail -> Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                        screenContent(screen, Modifier.widthIn(max = SINGLE_PANE_MAX_WIDTH).fillMaxSize(), false)
                    }
                    else -> screenContent(screen, Modifier.weight(1f).fillMaxHeight(), false)
                }
            }
        }
    }
}

/** A single column wider than this is hard to read; wider windows centre it beside the rail. */
private val SINGLE_PANE_MAX_WIDTH = 840.dp

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

private fun parseWidgetDeepLink(intent: Intent?, context: android.content.Context): BinarioDeepLink.WidgetRoute? {
    val widgetId = intent?.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID)
        ?: android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
    if (widgetId == android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) return null
    val route = WidgetConfigurationStore(context).read(widgetId)?.routeTarget() ?: return null
    val direction = WidgetStateStore.displayedDirection(context, widgetId) ?: Direction.OUTBOUND
    return BinarioDeepLink.WidgetRoute(route, direction)
}
