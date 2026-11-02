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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
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
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.journeys.JourneyResultsPresenter
import it.binario.app.journeys.JourneySearchViewModel
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
import it.binario.app.ui.common.HeaderOverlapBox
import it.binario.app.ui.common.LocalWideLayout
import it.binario.app.ui.common.ScreenHeader
import it.binario.app.ui.common.localizedQualityLabel
import it.binario.app.ui.departures.DeparturesScreen
import it.binario.app.ui.departures.OperationsSection
import it.binario.app.ui.departures.StationBoardRunDetailsPane
import it.binario.app.ui.detail.ServiceDetailScreen
import it.binario.app.ui.detail.trainShareStrings
import it.binario.app.ui.foryou.ForYouScreen
import it.binario.app.ui.journeys.JourneyDetailScreen
import it.binario.app.ui.planner.JourneyRequest
import it.binario.app.ui.planner.PlannerScreen
import it.binario.app.ui.planner.ResultsMapPane
import it.binario.app.ui.planner.RouteResultsScreen
import it.binario.app.ui.planner.SearchMode
import it.binario.app.ui.settings.SettingsScreen
import it.binario.app.ui.settings.SettingsSection
import it.binario.app.ui.settings.SettingsSectionScreen
import it.binario.app.ui.foryou.FavouriteTrainNameDialog
import it.binario.app.widget.TrainsWidgetProvider
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
        val widgetDeepLink = parseFavouriteTrainDeepLink(intent) ?: parseWidgetDeepLink(intent, applicationContext)
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

/**
 * The whole Binario UI: chrome (green header and bottom navigation on phones, green side navigation from 600 dp),
 * the screens and the list-detail panes from 840 dp. Internal so that the design screenshot test can render it
 * with a deterministic view model.
 */
@Composable
internal fun BinarioApp(
    viewModel: TrainViewModel,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    deepLinkRequest: DeepLinkRequest?,
    onDeepLinkConsumed: (Long) -> Unit,
    onSystemBarsAppearance: (lightStatusBar: Boolean, lightNavigationBar: Boolean) -> Unit,
    /** Journeys with changes, a separate search next to the direct trains of [viewModel]. */
    journeyViewModel: JourneySearchViewModel = viewModel(factory = JourneySearchViewModel.factory(LocalContext.current.applicationContext))
) {
    var screen by rememberSaveable { mutableStateOf(AppScreen.PLANNER) }
    var selectedJourneyId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailReturnScreen by rememberSaveable { mutableStateOf(AppScreen.RESULTS) }
    var operationsSectionName by rememberSaveable { mutableStateOf(OperationsSection.TRAINS.name) }
    var plannerSearchModeName by rememberSaveable { mutableStateOf(SearchMode.ROUTE.name) }
    var plannerSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var showThemePicker by rememberSaveable { mutableStateOf(false) }
    var settingsSectionName by rememberSaveable { mutableStateOf(SettingsSection.ALERTS.name) }
    var pendingStopAlarm by rememberSaveable(stateSaver = stopAlarmPlanSaver) {
        mutableStateOf<StopAlarmPlan?>(null)
    }
    var stopAlarmFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    val gpsArrivalAlarm = rememberGpsArrivalAlarmFlow()
    // The stop chosen with the last stop alarm: the detail of that train looks for connections there.
    var stopAlarmTrain by rememberSaveable { mutableStateOf<String?>(null) }
    var stopAlarmStation by rememberSaveable { mutableStateOf<String?>(null) }
    // A favourite train to open as soon as the number search finds its run ("number<US>origin").
    var pendingFavouriteTrain by rememberSaveable { mutableStateOf<String?>(null) }
    // The train just saved with the heart, offered a custom name.
    var namingFavouriteTrain by rememberSaveable { mutableStateOf<String?>(null) }
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
    val journeyState by journeyViewModel.state.collectAsState()
    // The journeys with changes are searched next to the direct trains whenever the route results are on screen
    // (alone, beside a detail, or under a detail opened from them), from any path that leads there: planner, recent
    // routes, saved routes. The direct-train search and its sources are left exactly as they are.
    val showingRouteResults = screen == AppScreen.RESULTS || screen == AppScreen.JOURNEY_DETAIL ||
        (screen == AppScreen.DETAIL && detailReturnScreen == AppScreen.RESULTS)
    val journeyRequest = JourneyRequest.of(viewModel)
    LaunchedEffect(showingRouteResults, journeyRequest?.key) {
        val request = journeyRequest ?: return@LaunchedEffect
        if (showingRouteResults && request.needsSearch(journeyViewModel.state.value.query)) {
            journeyViewModel.searchJourneys(request.origin, request.destination, request.departure.toLocalDate(), request.departure.toLocalTime())
        }
    }
    fun openJourney(row: JourneyOptionRow) {
        selectedJourneyId = row.id
        showScreen(AppScreen.JOURNEY_DETAIL)
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
            is BinarioDeepLink.FavouriteTrain -> {
                viewModel.updateTrainNumberQuery(deepLink.number)
                viewModel.searchTrainNumber()
                pendingFavouriteTrain = deepLink.number + FAVOURITE_SEPARATOR + deepLink.origin
            }
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
    // Opens the favourite train's run once the search finds it: at once when it comes from the saved
    // origin, otherwise when the search is complete (the run must not be guessed among homonyms).
    LaunchedEffect(pendingFavouriteTrain, viewModel.trainNumberMatches, viewModel.trainNumberSearchState) {
        val pending = pendingFavouriteTrain ?: return@LaunchedEffect
        val number = pending.substringBefore(FAVOURITE_SEPARATOR)
        val origin = pending.substringAfter(FAVOURITE_SEPARATOR, "")
        val saved = viewModel.monitoredServices.firstOrNull { it.number.equals(number, ignoreCase = true) }
        val monitor = (saved ?: ServiceMonitor(number, "")).let { if (origin.isNotBlank()) it.copy(origin = origin) else it }
        val loading = viewModel.trainNumberSearchState == RefreshState.LOADING
        val matches = viewModel.trainNumberMatches
        val picked = FavouriteTrainMatcher.pick(matches.map { it.service }, monitor)
        // While sources are still answering, only a run from the saved origin is certain.
        if (loading && (picked == null || !FavouriteTrainMatcher.isFromOrigin(picked, monitor))) return@LaunchedEffect
        val match = picked?.let { service -> matches.firstOrNull { it.service === service } }
        if (match != null) {
            pendingFavouriteTrain = null
            openDetail(AppScreen.NUMBER_RESULTS) { viewModel.openTrainNumberMatch(match) }
        } else if (!loading) {
            // Several runs, or none: the results stay on screen to choose from.
            pendingFavouriteTrain = null
        }
    }
    fun openFavouriteTrain(monitor: ServiceMonitor) {
        viewModel.updateTrainNumberQuery(monitor.number)
        viewModel.searchTrainNumber()
        pendingFavouriteTrain = monitor.number + FAVOURITE_SEPARATOR + monitor.origin
        showScreen(AppScreen.NUMBER_RESULTS)
    }
    namingFavouriteTrain?.let { number ->
        val monitor = viewModel.monitoredServices.firstOrNull { it.number.equals(number, ignoreCase = true) }
        if (monitor == null) {
            namingFavouriteTrain = null
        } else {
            FavouriteTrainNameDialog(
                monitor = monitor,
                title = stringResource(R.string.favourite_train_saved_title),
                description = stringResource(R.string.favourite_train_saved_description),
                dismissLabel = stringResource(R.string.favourite_train_skip_name),
                onSave = { name ->
                    viewModel.renameServiceMonitor(monitor.number, name)
                    namingFavouriteTrain = null
                },
                onDismiss = { namingFavouriteTrain = null }
            )
        }
    }
    val plannerSearchMode = SearchMode.valueOf(plannerSearchModeName)
    val operationsSection = OperationsSection.valueOf(operationsSectionName)
    val backTarget = AppNavigation.back(screen, detailReturnScreen)
    val navigateBack: () -> Unit = { backTarget?.let(::showScreen) }
    BackHandler(enabled = backTarget != null, onBack = navigateBack)
    val selectedDestination = AppNavigation.destinationOf(screen, detailReturnScreen)
    val selectDestination: (TopDestination) -> Unit = { destination -> showScreen(AppNavigation.rootOf(destination)) }
    if (showThemePicker) {
        ThemePickerSheet(currentMode = themeMode, onModeSelected = {
            onThemeModeChange(it)
            showThemePicker = false
        }, onDismiss = { showThemePicker = false })
    }

    val detailProviderLabel = viewModel.currentProviderLabel() ?: stringResource(R.string.provider_unavailable)
    val detailConfidenceLabel = viewModel.localizedQualityLabel()
    val shareSelectedService: () -> Unit = {
        viewModel.selectedService?.let { service ->
            val content = TrainShareFormatter.create(
                service = service,
                provider = detailProviderLabel,
                confidence = detailConfidenceLabel,
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
                stationRunDetailsInSheet = !inSidePane,
                selectedServiceId = viewModel.selectedService?.id.takeIf { inSidePane && screen == AppScreen.DETAIL }
            )
            AppScreen.FOR_YOU -> ForYouScreen(
                modifier = modifier,
                viewModel = viewModel,
                openRoute = {
                    viewModel.activateRoute(it)
                    showDepartures(OperationsSection.TRAINS)
                },
                openRecent = { recent -> if (viewModel.useRecentRoute(recent)) showScreen(AppScreen.RESULTS) },
                openAgenda = { showScreen(AppScreen.AGENDA) },
                openStationBoard = { station ->
                    viewModel.openStationBoard(station)
                    showDepartures(OperationsSection.STATIONS)
                },
                openSettings = { showScreen(AppScreen.SETTINGS) },
                openFavouriteTrain = ::openFavouriteTrain
            )
            AppScreen.SETTINGS -> SettingsScreen(
                modifier = modifier,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                openSection = { section ->
                    settingsSectionName = section.name
                    showScreen(AppScreen.SETTINGS_SECTION)
                }
            )
            AppScreen.SETTINGS_SECTION -> SettingsSectionScreen(
                modifier = modifier,
                section = SettingsSection.valueOf(settingsSectionName),
                viewModel = viewModel,
                gpsArrivalAlarm = gpsArrivalAlarm,
                onThemeClick = { showThemePicker = true }
            )
            AppScreen.AGENDA -> HeaderedPane(
                modifier = modifier,
                title = stringResource(R.string.screen_title_agenda),
                subtitle = stringResource(R.string.screen_subtitle_agenda)
            ) { paneModifier -> PlannedJourneyAgendaScreen(
                modifier = paneModifier,
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
            ) }
            AppScreen.RESULTS -> RouteResultsScreen(
                modifier = modifier,
                viewModel = viewModel,
                journeys = journeyState,
                openDetails = { service -> openDetail(AppScreen.RESULTS) { viewModel.openService(service) } },
                openJourney = ::openJourney,
                retryJourneys = journeyViewModel::retry,
                editSearch = { showScreen(AppScreen.PLANNER) },
                selectedServiceId = if (inSidePane) {
                    viewModel.selectedService?.id.takeIf { screen == AppScreen.DETAIL }
                        ?: viewModel.currentServices().firstOrNull()?.id.takeIf { screen != AppScreen.JOURNEY_DETAIL }
                } else null,
                selectedJourneyId = selectedJourneyId.takeIf { inSidePane && screen == AppScreen.JOURNEY_DETAIL }
            )
            AppScreen.JOURNEY_DETAIL -> {
                val row = remember(journeyState.result, selectedJourneyId) {
                    journeyState.result?.let { result -> JourneyResultsPresenter.present(result).rows.firstOrNull { it.id == selectedJourneyId } }
                }
                if (row == null) {
                    // The journeys are kept in memory only: after process death the detail returns to the results.
                    LaunchedEffect(selectedJourneyId) { screen = AppScreen.RESULTS }
                } else JourneyDetailScreen(
                    modifier = modifier,
                    row = row,
                    origin = journeyState.query?.origin,
                    destination = journeyState.query?.destination
                )
            }
            AppScreen.NUMBER_RESULTS -> HeaderedPane(
                modifier = modifier,
                title = stringResource(R.string.train_number_results_title),
                subtitle = stringResource(R.string.train_number_results_subtitle)
            ) { paneModifier ->
                TrainNumberResultsScreen(
                    modifier = paneModifier, viewModel = viewModel,
                    onOpen = { match -> openDetail(AppScreen.NUMBER_RESULTS) { viewModel.openTrainNumberMatch(match) } }
                )
            }
            AppScreen.DETAIL -> {
                val service = viewModel.selectedService
                if (service == null) {
                    // The selected service is intentionally in-memory. After process
                    // death, a restored DETAIL screen returns to its saved parent
                    // instead of showing an empty screen or recreating stale data.
                    LaunchedEffect(detailReturnScreen) { screen = detailReturnScreen }
                } else ServiceDetailScreen(
                    modifier = modifier,
                    service = service,
                    provider = detailProviderLabel,
                    confidence = detailConfidenceLabel,
                    freshness = viewModel.snapshot?.freshness,
                    refreshedAt = viewModel.lastRefreshEpochMillis(),
                    isDemonstration = viewModel.isDemonstrationData(),
                    onRefresh = viewModel::refresh,
                    isMonitored = viewModel.isMonitoring(service),
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
                    stationLookup = viewModel::stationFromName,
                    sourceSections = { TrainDetailSourceSections(service, viewModel.stations) },
                    onShare = shareSelectedService,
                    showShareButton = inSidePane
                )
            }
        }
    }

    val navigationIcon: @Composable () -> Unit = {
        if (backTarget != null) {
            IconButton(onClick = navigateBack, modifier = Modifier.testTag("top-bar-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        }
    }
    val actions: @Composable RowScope.() -> Unit = {
        when (screen) {
            AppScreen.PLANNER -> IconButton(onClick = { showThemePicker = true }) {
                Icon(Icons.Default.Palette, contentDescription = stringResource(R.string.action_choose_theme, themeMode.localizedLabel()))
            }
            AppScreen.FOR_YOU -> IconButton(onClick = { showScreen(AppScreen.SETTINGS) }, modifier = Modifier.testTag("top-bar-settings")) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
            }
            AppScreen.DEPARTURES, AppScreen.RESULTS, AppScreen.NUMBER_RESULTS -> IconButton(onClick = {
                if (screen == AppScreen.NUMBER_RESULTS) viewModel.searchTrainNumber(refresh = true)
                else if (screen == AppScreen.DEPARTURES && operationsSection == OperationsSection.STATIONS) {
                    viewModel.refreshStationBoardIfIdle()
                } else viewModel.refresh()
            }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh_rail_information))
            }
            AppScreen.DETAIL -> viewModel.selectedService?.let { service ->
                val monitored = viewModel.isMonitoring(service)
                // The heart is the existing "Salva": it adds the train to "I miei treni" of Per te (and the
                // widget), then offers a custom name; tapped again it removes the train.
                IconButton(onClick = {
                    viewModel.toggleServiceMonitoring(service)
                    if (!monitored) namingFavouriteTrain = service.number
                }, modifier = Modifier.testTag("detail-save")) {
                    Icon(
                        if (monitored) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(if (monitored) R.string.accessibility_saved_monitored else R.string.accessibility_save_monitored)
                    )
                }
                IconButton(onClick = shareSelectedService, modifier = Modifier.testTag("detail-share")) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                }
            }
            AppScreen.AGENDA, AppScreen.SETTINGS, AppScreen.SETTINGS_SECTION, AppScreen.JOURNEY_DETAIL -> Unit
        }
    }

    // Width classes come from the window this activity really has (multi-window and foldables included).
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val widthClass = WidthClass.fromWidthDp(maxWidth.value)
        val wide = widthClass != WidthClass.COMPACT
        val listPane = AppNavigation.listPaneOf(screen, detailReturnScreen, widthClass)
        val statusBarColor = if (wide) MaterialTheme.colorScheme.background else BinarioTheme.colors.header
        val navigationBarColor = MaterialTheme.colorScheme.surface
        SideEffect {
            onSystemBarsAppearance(statusBarColor.luminance() > 0.5f, navigationBarColor.luminance() > 0.5f)
        }
        CompositionLocalProvider(LocalWideLayout provides wide) {
            if (!wide) {
                Scaffold(
                    topBar = { BinarioTopBar(brand = screen == AppScreen.PLANNER, navigationIcon = navigationIcon, actions = actions) },
                    bottomBar = {
                        if (AppNavigation.showsNavigation(screen, widthClass)) {
                            BinarioNavigationBar(selected = selectedDestination, onSelect = selectDestination, containerColor = navigationBarColor)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.background
                ) { padding ->
                    screenContent(screen, Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), false)
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    // The green side navigation reaches behind the status bar, as in the tablet render.
                    BinarioNavigationRail(
                        selected = selectedDestination,
                        onSelect = selectDestination,
                        expanded = widthClass == WidthClass.EXPANDED
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom))
                    ) {
                        BinarioTopBar(brand = false, navigationIcon = navigationIcon, actions = actions)
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                listPane != null -> {
                                    // List and detail side by side: the list keeps its place while trains are opened.
                                    screenContent(listPane, Modifier.weight(1f).fillMaxHeight(), true)
                                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Box(Modifier.weight(1.2f).fillMaxHeight().testTag("detail-pane")) {
                                        val previewService = viewModel.currentServices().firstOrNull()
                                        when {
                                            screen == AppScreen.DETAIL -> screenContent(AppScreen.DETAIL, Modifier.fillMaxSize(), true)
                                            screen == AppScreen.JOURNEY_DETAIL -> screenContent(AppScreen.JOURNEY_DETAIL, Modifier.fillMaxSize(), true)
                                            listPane == AppScreen.DEPARTURES && operationsSection == OperationsSection.STATIONS &&
                                                viewModel.selectedBoardEntry != null ->
                                                StationBoardRunDetailsPane(Modifier.fillMaxSize(), viewModel)
                                            listPane == AppScreen.RESULTS && previewService != null -> ResultsMapPane(
                                                service = previewService,
                                                isDemonstration = viewModel.isDemonstrationData(),
                                                stationIndex = rememberOpenMapStationIndex(viewModel.stations, viewModel::stationFromName),
                                                onOpenDetails = { openDetail(AppScreen.RESULTS) { viewModel.openService(previewService) } }
                                            )
                                            else -> DetailPlaceholder()
                                        }
                                    }
                                }
                                else -> Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                                    screenContent(screen, Modifier.widthIn(max = SINGLE_PANE_MAX_WIDTH).fillMaxSize(), false)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A screen without its own header (agenda, train number results): the header, then the content on a sheet. */
@Composable
private fun HeaderedPane(modifier: Modifier, title: String, subtitle: String?, content: @Composable (Modifier) -> Unit) {
    Column(modifier) {
        ScreenHeader(title = title, subtitle = subtitle, bottomPadding = 8.dp)
        HeaderOverlapBox(Modifier.weight(1f), overlap = 28.dp) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .padding(top = 8.dp)
            ) {
                content(Modifier.fillMaxSize())
            }
        }
    }
}

/** A single column wider than this is hard to read; wider windows centre it beside the side navigation. */
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

private const val FAVOURITE_SEPARATOR = "\u001F"

/** A tap on a row of the "I miei treni" widget. */
private fun parseFavouriteTrainDeepLink(intent: Intent?): BinarioDeepLink.FavouriteTrain? {
    if (intent?.action != TrainsWidgetProvider.ACTION_OPEN_TRAIN) return null
    return BinarioDeepLink.FavouriteTrain.of(
        intent.getStringExtra(TrainsWidgetProvider.EXTRA_TRAIN_NUMBER),
        intent.getStringExtra(TrainsWidgetProvider.EXTRA_TRAIN_ORIGIN)
    )
}

private fun parseWidgetDeepLink(intent: Intent?, context: android.content.Context): BinarioDeepLink.WidgetRoute? {
    val widgetId = intent?.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID)
        ?: android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
    if (widgetId == android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) return null
    val route = WidgetConfigurationStore(context).read(widgetId)?.routeTarget() ?: return null
    val direction = WidgetStateStore.displayedDirection(context, widgetId) ?: Direction.OUTBOUND
    return BinarioDeepLink.WidgetRoute(route, direction)
}
