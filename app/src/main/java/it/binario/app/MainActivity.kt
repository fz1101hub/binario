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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Screen { PLANNER, OPERATIONS, SETTINGS, RESULTS, DETAIL }
private enum class OperationsSection(val label: String) { TRAINS("Treni"), STATIONS("Stazioni") }
private enum class SearchMode(val label: String) { ROUTE("Tratta"), TRAIN("N. treno"), STATION("Stazione") }

class MainActivity : ComponentActivity() {
    private val deepLinkDispatcher = DeepLinkRequestDispatcher()
    private var deepLinkRequest by mutableStateOf<DeepLinkRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // An Activity recreated from saved state must restore its UI, not replay the
        // launch Intent and navigate away from the screen the user was using.
        if (savedInstanceState == null) receiveDeepLinkIntent(intent)
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
        deepLinkRequest = deepLinkDispatcher.receive(intent?.action, intent?.dataString)
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
    var plannerSearchRequestId by rememberSaveable { mutableStateOf(0) }
    var showThemePicker by rememberSaveable { mutableStateOf(false) }
    var pendingStopAlarm by remember { mutableStateOf<StopAlarmPlan?>(null) }
    var stopAlarmFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val plan = pendingStopAlarm
        pendingStopAlarm = null
        if (granted && plan != null) {
            StopAlarmScheduler.schedule(context, plan)
            stopAlarmFeedback = "Sveglia impostata per ${plan.station}, cinque minuti prima dell’orario previsto ${plan.expectedTime}."
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
                StopAlarmScheduler.schedule(context, plan)
                stopAlarmFeedback = "Sveglia impostata per ${stop.station}, cinque minuti prima dell’orario previsto ${plan.expectedTime}."
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
    val title = when (screen) {
        Screen.PLANNER -> "Cerca e consulta"
        Screen.OPERATIONS -> "Consulta"
        Screen.SETTINGS -> "Impostazioni"
        Screen.RESULTS -> "Soluzioni"
        Screen.DETAIL -> "Dettaglio corsa"
    }
    val subtitle = when (screen) {
        Screen.PLANNER -> "Pianifica il prossimo viaggio"
        Screen.OPERATIONS -> "Treni e stazioni"
        Screen.SETTINGS -> "Preferenze e dati personali"
        Screen.RESULTS -> "Corse disponibili"
        Screen.DETAIL -> "Stato e fermate"
    }

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
                        Text(if (screen == Screen.PLANNER) "BINARIO" else title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, letterSpacing = if (screen == Screen.PLANNER) 1.sp else 0.sp)
                        if (screen != Screen.DETAIL) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    if (screen == Screen.DETAIL || screen == Screen.RESULTS) {
                        IconButton(onClick = navigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Torna indietro")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showThemePicker = true }) {
                        Icon(Icons.Default.Palette, contentDescription = "Scegli tema. Tema attuale ${themeMode.label}")
                    }
                    if (screen == Screen.OPERATIONS || screen == Screen.RESULTS || screen == Screen.DETAIL) {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Aggiorna le informazioni ferroviarie")
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
                        label = { Text("Cerca") }
                    )
                    NavigationBarItem(
                        selected = screen == Screen.OPERATIONS,
                        onClick = { screen = Screen.OPERATIONS },
                        icon = { Icon(Icons.Default.Train, contentDescription = null) },
                        label = { Text("Consulta") }
                    )
                    NavigationBarItem(
                        selected = screen == Screen.SETTINGS,
                        onClick = { screen = Screen.SETTINGS },
                        icon = { Icon(Icons.Default.Info, contentDescription = null) },
                        label = { Text("Impostazioni") }
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
private fun OperationsSectionCard(
    modifier: Modifier,
    section: OperationsSection,
    selected: Boolean,
    onClick: () -> Unit
) {
    val subtitle = if (section == OperationsSection.TRAINS) "Tabellone tratta" else "Arrivi e partenze"
    val icon = if (section == OperationsSection.TRAINS) Icons.Default.Train else Icons.Default.Route
    Surface(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${section.label}. $subtitle${if (selected) ". Sezione selezionata" else ""}" },
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null)
            Text(section.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
        item { AssistChip(onClick = onOpenSearch, label = { Text("Nuova ricerca") }, leadingIcon = {
                Icon(Icons.Default.Train, contentDescription = null)
            }) }
        item { AssistChip(onClick = onOpenSearch, label = { Text("Pianifica tratta") }, leadingIcon = {
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
                Text("Pianifica viaggio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Cerca le prossime soluzioni per tratta, giorno e orario.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SearchMode.entries) { item -> FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item.label) }) }
                }
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (mode) {
                        SearchMode.ROUTE -> {
                        VoiceInputButton(onClick = { startVoiceInput(SearchMode.ROUTE) }, label = "Detta tratta")
                        speechMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("STAZIONE PARTENZA", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        StationField("Partenza", originText, { originText = it }, viewModel.searchStations(originText), { originText = it.name })
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("STAZIONE ARRIVO", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { val old = originText; originText = destinationText; destinationText = old }) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = "Inverti partenza e arrivo")
                            }
                        }
                        StationField("Arrivo", destinationText, { destinationText = it }, viewModel.searchStations(destinationText), { destinationText = it.name })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FilterChip(selected = !returnJourney, onClick = { returnJourney = false }, label = { Text("Sola andata") })
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
                                label = { Text("Andata e ritorno") }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { showDatePicker = true },
                                modifier = Modifier.weight(1f).semantics { contentDescription = "Seleziona data andata. Valore attuale ${PlannerDateTime.dateLabel(travelDate)}" }
                            ) {
                                Column(horizontalAlignment = Alignment.Start) { Text("ANDATA · DATA", style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.dateLabel(travelDate), fontWeight = FontWeight.Bold) }
                            }
                            OutlinedButton(
                                onClick = { showTimePicker = true },
                                modifier = Modifier.weight(1f).semantics { contentDescription = "Seleziona ora andata. Valore attuale ${PlannerDateTime.timeLabel(travelTime)}" }
                            ) {
                                Column(horizontalAlignment = Alignment.Start) { Text("ANDATA · ORA", style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.timeLabel(travelTime), fontWeight = FontWeight.Bold) }
                            }
                        }
                        if (returnJourney) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { showReturnDatePicker = true },
                                    modifier = Modifier.weight(1f).semantics { contentDescription = "Seleziona data ritorno. Valore attuale ${PlannerDateTime.dateLabel(returnDate)}" }
                                ) {
                                    Column(horizontalAlignment = Alignment.Start) { Text("RITORNO · DATA", style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.dateLabel(returnDate), fontWeight = FontWeight.Bold) }
                                }
                                OutlinedButton(
                                    onClick = { showReturnTimePicker = true },
                                    modifier = Modifier.weight(1f).semantics { contentDescription = "Seleziona ora ritorno. Valore attuale ${PlannerDateTime.timeLabel(returnTime)}" }
                                ) {
                                    Column(horizontalAlignment = Alignment.Start) { Text("RITORNO · ORA", style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.timeLabel(returnTime), fontWeight = FontWeight.Bold) }
                                }
                            }
                            if (!roundTripChronologyValid) {
                                Text(
                                    "Il ritorno deve essere successivo alla partenza dell’andata.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        FilterChip(
                            selected = showFilters,
                            onClick = { showFilters = !showFilters },
                            label = { Text(if (showFilters) "Filtri aperti" else "Filtri · Tutti i treni") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        if (showFilters) {
                            JourneyChangeFilterBar(viewModel.journeyChangeFilter, viewModel::selectJourneyChangeFilter)
                            OperatorFilterBar(viewModel.operatorFilter, viewModel.operatorFilterOptions(), viewModel::selectOperatorFilter)
                            Text("Il filtro viene applicato solo quando la fonte restituisce informazioni sui cambi; Binario non li stima.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text(if (returnJourney) "Cerca andata e ritorno" else "Cerca soluzioni")
                        }
                        }
                        SearchMode.TRAIN -> {
                            VoiceInputButton(onClick = { startVoiceInput(SearchMode.TRAIN) }, label = "Detta numero treno")
                            speechMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text("FILTRO NUMERO TRENO NELLA TRATTA", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Questa ricerca filtra i treni della tratta attiva. La ricerca nazionale per solo numero richiede un provider TrainRun dedicato e non viene simulata.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = viewModel.trainNumberQuery,
                                onValueChange = viewModel::updateTrainNumberQuery,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Numero treno") },
                                placeholder = { Text("Es. 9310") },
                                leadingIcon = { Icon(Icons.Default.Train, contentDescription = null) }
                            )
                            if (!viewModel.hasActiveRoute) {
                                Text("Scegli prima una tratta da consultare.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Button(
                                onClick = { viewModel.refresh(); showResults() },
                                enabled = viewModel.trainNumberQuery.isNotBlank() && viewModel.hasActiveRoute,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Filtra treno nella tratta")
                            }
                        }
                        SearchMode.STATION -> {
                            VoiceInputButton(onClick = { startVoiceInput(SearchMode.STATION) }, label = "Detta stazione")
                            speechMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text("TABELLONE STAZIONE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            StationField("Stazione", stationText, { stationText = it }, viewModel.searchStations(stationText), { stationText = it.name })
                            Button(
                                onClick = { openStations(viewModel.stationFromName(stationText)) },
                                enabled = viewModel.stationFromName(stationText) != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Route, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Apri tabellone")
                            }
                        }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recenti e preferite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = openMore) { Text("Gestisci") }
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
                    Text("Ricerca recente", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.Search, contentDescription = "Ripeti ricerca ${recent.label}")
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
                Icon(Icons.Default.Search, contentDescription = "Usa tratta ${route.name}")
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

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(viewModel.activeRoute.displayName(primaryDirection), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (roundTrip) "${services.size} andata · ${returnServices.size} ritorno"
                    else "${services.size} soluzioni disponibili",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (roundTrip) "ANDATA" else primaryDirection.label.uppercase(Locale.ITALIAN), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
                item { SearchInfoState("Caricamento andata", "Sto cercando le soluzioni programmate e le eventuali informazioni operative disponibili.") }
            viewModel.refreshState == RefreshState.ERROR && viewModel.snapshot == null ->
                item { SearchInfoState("Andata non disponibile", viewModel.refreshMessage ?: "La fonte non ha restituito questa tratta.") }
            services.isEmpty() ->
                item { SearchInfoState("Nessuna soluzione di andata", "Modifica la ricerca o scegli una tratta differente.") }
            else -> items(services, key = { "out-${it.id}" }) { service ->
                DepartureListRow(service, isNext = service == services.first(), onOpen = { openDetails(service) })
            }
        }

        if (roundTrip) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("RITORNO", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
                    item { SearchInfoState("Caricamento ritorno", "Sto cercando le soluzioni di ritorno con la data e l’ora selezionate.") }
                viewModel.returnRefreshState == RefreshState.ERROR && viewModel.returnSnapshot == null ->
                    item { SearchInfoState("Ritorno non disponibile", viewModel.returnRefreshMessage ?: "La fonte non ha restituito il ritorno richiesto.") }
                returnServices.isEmpty() ->
                    item { SearchInfoState("Nessuna soluzione di ritorno", "Modifica data o ora del ritorno e riprova.") }
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
                Text("Tabellone stazione", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                StationField(
                    "Stazione",
                    stationText,
                    { stationText = it },
                    viewModel.searchStations(stationText),
                    {
                        stationText = it.name
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

                    Text("TABELLONE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(BoardKind.entries) { kind ->
                            FilterChip(
                                selected = viewModel.stationBoardKind == kind,
                                onClick = { viewModel.loadStationBoard(station, kind) },
                                enabled = viewModel.hasStationBoardProviders,
                                label = { Text(if (kind == BoardKind.DEPARTURES) "Partenze" else "Arrivi") }
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
                        Text(if (board == null) "Carica tabellone" else "Aggiorna tabellone")
                    }

                    Text("Monitoraggio locale", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
                        Text(if (viewModel.isStationMonitored(station)) "Rimuovi monitoraggio stazione" else "Monitora questa stazione")
                    }
                }

                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locateNearbyStations()
                        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Trova le stazioni vicine. La posizione viene usata una sola volta e non viene salvata."
                    }
                ) {
                    Icon(Icons.Default.Route, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Trova stazioni vicine")
                }

                locationMessage?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (!viewModel.hasStationBoardProviders) {
            item {
                SearchInfoState(
                    "Tabellone live non attivo",
                    "Questa build non abilita automaticamente fonti tabellone best-effort. Attivale esplicitamente in una build personale oppure usa un provider autorizzato; Binario non genera partenze artificiali."
                )
            }
        } else {
            when {
                viewModel.stationBoardRefreshState == RefreshState.LOADING && board == null -> item {
                    SearchInfoState("Caricamento tabellone", "Sto interrogando le fonti abilitate e riconciliando le informazioni per singolo campo.")
                }
                viewModel.stationBoardRefreshState == RefreshState.ERROR && board == null -> item {
                    SearchInfoState(
                        "Tabellone non disponibile",
                        viewModel.stationBoardMessage ?: "Le fonti abilitate non hanno restituito dati utilizzabili."
                    )
                }
                board != null && board.entries.isEmpty() -> item {
                    SearchInfoState(
                        "Nessun movimento nella finestra",
                        viewModel.stationBoardMessage ?: "Le fonti abilitate non hanno restituito partenze o arrivi nella finestra richiesta."
                    )
                }
                board != null -> {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                if (board.kind == BoardKind.DEPARTURES) "PARTENZE" else "ARRIVI",
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
                    "Stazioni preferite",
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
                    "Stazioni più vicine",
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
private fun StationBoardEntryRow(entry: StationBoardEntry, kind: BoardKind) {
    val scheduled = entry.scheduledTime.withZoneSameInstant(BOARD_ZONE)
    val expected = entry.expectedTime?.value?.withZoneSameInstant(BOARD_ZONE)
    val effective = expected ?: scheduled
    val delayedMinutes = expected?.let {
        java.time.Duration.between(scheduled, it).toMinutes().coerceAtLeast(0)
    } ?: 0L
    val endpoint = entry.destinationOrOriginLabel?.takeIf(String::isNotBlank)
        ?: entry.destinationOrOrigin.value
    val platform = entry.platform?.value?.takeIf(String::isNotBlank)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(effective.format(BOARD_TIME_FORMATTER), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (expected != null && expected.toInstant() != scheduled.toInstant()) {
                Text(
                    "prog. ${scheduled.format(BOARD_TIME_FORMATTER)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (kind == BoardKind.DEPARTURES) "Per $endpoint" else "Da $endpoint",
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
                Text("Bin. $it", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontWeight = FontWeight.Bold)
            }
        }
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
        isDemonstration -> "Catalogo dimostrativo locale — non live"
        freshness == Freshness.UNAVAILABLE || freshness == null -> "dati non disponibili in tempo reale"
        else -> freshness.accessibleLabel
    }
    val color = when {
        isDemonstration -> MaterialTheme.colorScheme.tertiary
        freshness == Freshness.UNAVAILABLE || freshness == null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .semantics { contentDescription = "$label. Fonte $provider. Qualità $confidence. ${accessibleUpdateTime(refreshedAt)}. ${message.orEmpty()}" },
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
private fun NextDepartureHero(service: TrainService, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .fillMaxWidth()
            .semantics { contentDescription = "Apri prossima partenza. ${service.accessibleSummary}" }
            .clickable(role = Role.Button, onClick = onOpen),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PROSSIMA PARTENZA", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(service.expectedDeparture, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    if (service.expectedDeparture != service.scheduledDeparture) {
                        Text("programmata ${service.scheduledDeparture}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                ServiceStatusBadge(service)
            }
            Text(service.destination, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${service.displayOperator} · ${service.category} ${service.number}", style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.18f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(service.platform?.let { "Binario $it" } ?: "Binario da confermare", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Dettaglio", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isNext) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f) else MaterialTheme.colorScheme.background)
            .semantics { contentDescription = "Apri dettaglio. ${service.accessibleSummary}" }
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
private fun SearchScreen(
    modifier: Modifier,
    viewModel: TrainViewModel,
    openDetails: (TrainService) -> Unit,
    openRoutes: () -> Unit,
    initialMode: SearchMode
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var originText by rememberSaveable { mutableStateOf(viewModel.plannerOriginText()) }
    var destinationText by rememberSaveable { mutableStateOf(viewModel.plannerDestinationText()) }
    var stationText by rememberSaveable { mutableStateOf("") }
    val origin = viewModel.stationFromName(originText)
    val destination = viewModel.stationFromName(destinationText)
    val results = viewModel.currentServices()
    LaunchedEffect(initialMode) { mode = initialMode }
    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Trova il tuo treno", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Cerca una tratta, un numero treno o una stazione.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SearchMode.entries) { item ->
                        FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item.label) })
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (mode) {
                        SearchMode.ROUTE -> {
                            Text("Pianifica una tratta", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            StationField("Partenza", originText, { originText = it }, viewModel.searchStations(originText), { originText = it.name })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { val current = originText; originText = destinationText; destinationText = current },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                                ) { Icon(Icons.Default.SwapHoriz, contentDescription = "Scambia partenza e arrivo") }
                                Spacer(Modifier.width(10.dp))
                                Text("Prossime corse · oggi", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StationField("Arrivo", destinationText, { destinationText = it }, viewModel.searchStations(destinationText), { destinationText = it.name })
                            Button(
                                onClick = { if (viewModel.searchRoute(origin!!, destination!!)) viewModel.clearSearchFilters() },
                                enabled = origin != null && destination != null && origin != destination,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Cerca treni")
                            }
                        }
                        SearchMode.TRAIN -> {
                            Text("FILTRO NUMERO TRENO NELLA TRATTA", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Filtra i treni della tratta attiva. La ricerca nazionale per solo numero richiede un provider TrainRun dedicato e non viene simulata.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = viewModel.trainNumberQuery,
                                onValueChange = viewModel::updateTrainNumberQuery,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Numero treno") },
                                placeholder = { Text("Es. 12534") },
                                leadingIcon = { Icon(Icons.Default.Train, contentDescription = null) },
                                trailingIcon = if (viewModel.trainNumberQuery.isNotBlank()) ({ IconButton(onClick = viewModel::clearSearchFilters) { Icon(Icons.Default.DeleteOutline, contentDescription = "Cancella ricerca e filtri") } }) else null
                            )
                            if (!viewModel.hasActiveRoute) {
                                Text("Scegli prima una tratta da consultare.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Button(
                                onClick = viewModel::refresh,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = viewModel.trainNumberQuery.isNotBlank() && viewModel.hasActiveRoute
                            ) {
                                Text("Filtra treno nella tratta")
                            }
                        }
                        SearchMode.STATION -> {
                            StationField("Stazione", stationText, { stationText = it }, viewModel.searchStations(stationText), { stationText = it.name })
                            Text("Il tabellone stazione verrà mostrato qui quando sarà disponibile una fonte autorizzata. Per ora puoi cercare una tratta o un numero treno.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        }
        item {
            Column(Modifier.padding(top = 18.dp)) {
                Text("I tuoi percorsi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
                    items(viewModel.savedRoutes.take(6), key = { it.id }) { route ->
                        FilterChip(
                            selected = route.id == viewModel.activeRoute.id,
                            onClick = { viewModel.activateRoute(route); originText = route.outwardOrigin.name; destinationText = route.outwardDestination.name; mode = SearchMode.ROUTE },
                            label = { Text(route.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = if (route.isPinned) ({ Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null
                        )
                    }
                    item { AssistChip(onClick = openRoutes, label = { Text("Gestisci") }, leadingIcon = { Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(16.dp)) }) }
                }
            }
        }
        if (mode == SearchMode.TRAIN) item { OperatorFilterBar(viewModel.operatorFilter, viewModel.operatorFilterOptions(), viewModel::selectOperatorFilter) }
        item {
            ListHeading(
                title = when (mode) {
                    SearchMode.ROUTE -> "Treni in circolazione"
                    SearchMode.TRAIN -> if (viewModel.trainNumberQuery.isBlank()) "Cerca per numero" else "Risultati per ${viewModel.trainNumberQuery}"
                    SearchMode.STATION -> "Tabellone stazione"
                },
                subtitle = if (mode == SearchMode.STATION) "Non disponibile" else "${results.size} risultati"
            )
        }
        if (mode == SearchMode.STATION) {
            item { SearchInfoState("Seleziona una tratta o un treno", "Il tabellone sarà disponibile soltanto con un provider ferroviario autorizzato. Binario non genera partenze artificiali.") }
        } else if (results.isEmpty()) {
            item {
                SearchInfoState("Nessuna corsa corrispondente", "Prova a modificare la ricerca, scegliere un’altra tratta o controllare il filtro operatore.", openRoutes)
            }
        } else {
            items(results, key = { it.id }) { service -> DepartureListRow(service, onOpen = { openDetails(service) }) }
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
    var pendingAlert by remember { mutableStateOf<CommuterAlert?>(null) }
    var alertFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    var backupFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
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
        backupFeedback = runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Impossibile leggere il file scelto.")
            viewModel.importLocalBackup(raw)
        }.getOrElse { "Importazione non riuscita: ${it.message ?: "errore file"}" }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val alert = pendingAlert
        pendingAlert = null
        if (granted && alert != null) {
            CommuterAlertScheduler.schedule(context, alert)
            alertFeedback = "Avviso salvato e pianificato sul dispositivo."
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
                    CommuterAlertScheduler.schedule(context, alert)
                    alertFeedback = "Avviso salvato e pianificato sul dispositivo."
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
                widgetSurface = viewModel.widgetSurface,
                onAdvanceTrip = viewModel::advanceActiveTrip,
                onEndTrip = viewModel::endActiveTrip,
                onRemoveStationMonitor = viewModel::removeStationMonitor,
                onSelectWidgetSurface = viewModel::selectWidgetSurface
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
        Text("Avvisi pendolare", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Promemoria locali con anticipo, ricorrenza e fascia silenziosa. Non si aggiornano con i ritardi senza una fonte autorizzata.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Crea avviso pendolare") }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        alerts.forEach { alert ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(alert.stationName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(alert.accessibleSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onRemove(alert) }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Elimina avviso per ${alert.stationName}") }
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
    var lead by rememberSaveable { mutableStateOf(15) }
    var repeat by rememberSaveable { mutableStateOf(AlertRepeat.WEEKDAYS.name) }
    var quietHours by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Avviso pendolare", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(routeLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = station, onValueChange = { station = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Fermata") })
            OutlinedButton(onClick = {
                val parsed = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.of(8, 0))
                TimePickerDialog(context, { _, hour, minute -> time = "%02d:%02d".format(hour, minute) }, parsed.hour, parsed.minute, true).show()
            }, modifier = Modifier.fillMaxWidth()) { Text("Orario: $time") }
            Text("Anticipo", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(5, 10, 15, 30, 60)) { minutes ->
                    FilterChip(selected = lead == minutes, onClick = { lead = minutes }, label = { Text("$minutes min") })
                }
            }
            Text("Ripetizione", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AlertRepeat.entries) { option ->
                    FilterChip(selected = repeat == option.name, onClick = { repeat = option.name }, label = { Text(option.label) })
                }
            }
            FilterChip(selected = quietHours, onClick = { quietHours = !quietHours }, label = { Text("Fascia silenziosa 22:00–07:00") })
            Button(onClick = { onSave(station.trim(), time, lead, AlertRepeat.valueOf(repeat), quietHours) }, enabled = station.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Salva avviso") }
        }
    }
}

@Composable
private fun PersonalTravelPanel(
    activeTrip: ActiveTrip?,
    stationMonitors: List<StationMonitor>,
    widgetSurface: WidgetSurface,
    onAdvanceTrip: () -> Unit,
    onEndTrip: () -> Unit,
    onRemoveStationMonitor: (String) -> Unit,
    onSelectWidgetSurface: (WidgetSurface) -> Unit
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
        Text("Superficie widget", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(WidgetSurface.entries) { surface ->
                FilterChip(selected = widgetSurface == surface, onClick = { onSelectWidgetSurface(surface) }, label = { Text(surface.label) })
            }
        }
        Text("Le superfici Viaggio attivo e Stazione monitorata riportano lo stato personale; senza provider autorizzato non mostrano un tabellone live.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            StationField("Partenza", originText, { originText = it }, viewModel.searchStations(originText), { originText = it.name })
            TextButton(onClick = { val current = originText; originText = destinationText; destinationText = current }) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Scambia stazioni")
            }
            StationField("Arrivo", destinationText, { destinationText = it }, viewModel.searchStations(destinationText), { destinationText = it.name })
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
                        "Cerca “${station.name}” nelle fonti disponibili"
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
