package it.binario.app

import android.app.Application
import android.util.Log
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.RouteSnapshotCache
import it.binario.app.data.RailTime
import it.binario.app.data.SavedRoute
import it.binario.app.data.SavedRouteOrdering
import it.binario.app.data.Station
import it.binario.app.data.StationAutocompleteEngine
import it.binario.app.data.StationCanonicalizer
import it.binario.app.data.TrainDataSource
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.data.repository.StationBoardAggregate
import it.binario.app.data.repository.StationBoardAggregateResult
import it.binario.app.data.repository.StationBoardAggregateService
import it.binario.app.data.repository.TrainNumberSearchService
import it.binario.app.data.repository.TrainNumberQuery
import it.binario.app.data.repository.TrainNumberMatch
import it.binario.app.data.repository.StationBoardRunDetailService
import it.binario.app.data.repository.StationBoardRunDetails
import it.binario.app.data.repository.withoutExpiredObservations
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.TrainService
import it.binario.app.data.core.BoardKind
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.orderServicesForDisplay
import it.binario.app.widget.RouteWidgetProvider
import it.binario.app.widget.WidgetStateStore
import it.binario.app.wear.WearSnapshotPublisher
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.Locale
import java.time.LocalDate
import java.time.LocalTime

enum class RefreshState { IDLE, LOADING, SUCCESS, ERROR }

data class RecentRoute(val origin: Station, val destination: Station) {
    val label: String get() = "${origin.name} → ${destination.name}"
}

data class ServiceMonitor(val number: String, val label: String)

class TrainViewModel @JvmOverloads constructor(
    application: Application,
    /** Widget configuration needs local catalog/search only, never startup refresh or Wear publication. */
    private val enableStartupEffects: Boolean = true,
    private val stationBoardService: StationBoardAggregateService = StationBoardAggregateService(BinarioDataGraph.stationBoardDisplayProviders()),
    private val stationBoardDetailsService: StationBoardRunDetailService = BinarioDataGraph.stationBoardRunDetailService(),
    private val trainNumberSearchService: TrainNumberSearchService = BinarioDataGraph.trainNumberSearchService(application)
) : AndroidViewModel(application) {
    private companion object {
        const val STATION_SUGGESTION_DEBOUNCE_MILLIS = 180L
    }
    private val routeStore = RouteStore(application)
    private val cache = RouteSnapshotCache(application)
    private val integrationStore = IntegrationSettingsStore(application)
    private val stationPreferences = StationPreferences(application)
    private val commuterAlertStore = CommuterAlertStore(application)
    private val personalTravelStore = PersonalTravelStore(application)
    private val mobilityPreferencesStore = MobilityPreferencesStore(application)
    private val customStationStore = CustomStationStore(application)
    private val dataSource: TrainDataSource = BinarioDataGraph.routeDataSource(application)
    private val curatedStationCatalog = EmbeddedCuratedRailStationCatalog(
        openAsset = { assetPath -> application.assets.open(assetPath) }
    )

    var stations by mutableStateOf(customStationStore.stations())
        private set
    @Volatile
    private var stationAutocomplete = StationAutocompleteEngine(stations)
    @Volatile
    private var stationCanonicalizer = StationCanonicalizer(stations)

    var favouriteStations by mutableStateOf(stationPreferences.favourites(stations))
        private set
    var nearbyStations by mutableStateOf<List<NearbyStation>>(emptyList())
        private set

    var stationBoardStation by mutableStateOf<Station?>(null)
        private set
    var stationBoardKind by mutableStateOf(BoardKind.DEPARTURES)
        private set
    var stationBoard by mutableStateOf<StationBoardAggregate?>(null)
        private set
    var stationBoardRefreshState by mutableStateOf(RefreshState.IDLE)
        private set
    var stationBoardMessages by mutableStateOf<List<UiMessage>>(emptyList())
        private set

    var selectedBoardEntry by mutableStateOf<StationBoardEntry?>(null)
        private set
    var stationBoardRunDetails by mutableStateOf<StationBoardRunDetails?>(null)
        private set
    var stationBoardRunRefreshState by mutableStateOf(RefreshState.IDLE)
        private set
    private var stationBoardJob: Job? = null
    private var stationBoardRunJob: Job? = null
    private var stationBoardRunRequestId = 0L

    var savedRoutes by mutableStateOf(pinnedFirst(routeStore.routes(stations)))
        private set
    var recentRoutes by mutableStateOf(routeStore.recentRoutes(stations))
        private set
    var monitoredServices by mutableStateOf(routeStore.monitoredServices())
        private set
    var activeRoute by mutableStateOf(routeStore.activeRoute(savedRoutes))
        private set
    var direction by mutableStateOf(routeStore.direction())
        private set
    private var primarySnapshot by mutableStateOf<RouteSnapshot?>(null)
    /** Whether [primarySnapshot] answers a current query, as opposed to a planned date/time search. */
    private var primarySnapshotIsCurrent = false
    /**
     * Snapshot visible to the UI. While a service detail is open this is pinned to the
     * snapshot that produced that service, so freshness/provenance cannot drift to the
     * other leg of a round trip after an unrelated refresh.
     */
    val snapshot: RouteSnapshot?
        get() = selectedServiceSnapshot ?: primarySnapshot
    var returnSnapshot by mutableStateOf<RouteSnapshot?>(null)
        private set
    var refreshState by mutableStateOf(RefreshState.IDLE)
        private set
    var refreshMessage by mutableStateOf<UiMessage?>(null)
        private set
    var returnRefreshState by mutableStateOf(RefreshState.IDLE)
        private set
    var returnRefreshMessage by mutableStateOf<UiMessage?>(null)
        private set
    var selectedService by mutableStateOf<TrainService?>(null)
        private set
    private var selectedServiceDirection: Direction? = null
    private var selectedServiceSnapshot: RouteSnapshot? = null
    var operatorFilter by mutableStateOf<String?>(null)
        private set
    var journeyChangeFilter by mutableStateOf(JourneyChangeFilter.ANY)
        private set
    var trainNumberQuery by mutableStateOf("")
        private set
    var trainNumberMatches by mutableStateOf<List<TrainNumberMatch>>(emptyList())
        private set
    var trainNumberSearchState by mutableStateOf(RefreshState.IDLE)
        private set
    var trainNumberUnavailableSources by mutableStateOf<Set<String>>(emptySet())
        private set
    var trainNumberSearchDate by mutableStateOf(JourneyQuery.now().serviceDate)
        private set
    private var trainNumberSearchJob: Job? = null
    private var trainNumberSearchRequestId = 0L
    private var selectedGlobalTrainId: String? = null
    var integrationSettings by mutableStateOf(integrationStore.read())
        private set
    var commuterAlerts by mutableStateOf(commuterAlertStore.alerts())
        private set
    var stationMonitors by mutableStateOf(personalTravelStore.stationMonitors(stations))
        private set
    var activeTrip by mutableStateOf(personalTravelStore.activeTrip())
        private set
    var mobilityPreferences by mutableStateOf(mobilityPreferencesStore.read())
        private set

    private var primaryServicePresentation by mutableStateOf(ServicePresentation.empty())
    private var returnServicePresentation by mutableStateOf(ServicePresentation.empty())
    private var visiblePrimaryServices by mutableStateOf<List<TrainService>>(emptyList())
    private var visibleReturnServices by mutableStateOf<List<TrainService>>(emptyList())
    private var availableOperatorNames by mutableStateOf<List<String>>(emptyList())

    private val journeyQueryState = JourneyQueryState()
    private var returnJourneyQuery: JourneyQuery? = null
    /** Monotonic token: only the newest current-direction refresh may mutate UI/cache/surfaces. */
    private var refreshRequestId: Long = 0L
    /** Separate token: a stale return response can never overwrite a newer round-trip search. */
    private var returnRefreshRequestId: Long = 0L
    /** Independent station-board token; changing station/kind invalidates older responses. */
    private var stationBoardRequestId: Long = 0L

    init {
        if (enableStartupEffects) {
            if (!integrationSettings.wearSnapshotSharingEnabled) {
                WearSnapshotPublisher.persistRevocationIntent(getApplication())
            }
            viewModelScope.launch {
                if (integrationSettings.wearSnapshotSharingEnabled) {
                    if (WearSnapshotPublisher.enable(getApplication()) && !integrationSettings.wearSnapshotSharingEnabled) {
                        WearSnapshotPublisher.clear(getApplication())
                    }
                } else {
                    WearSnapshotPublisher.clear(getApplication())
                }
            }
        }
        // The curated catalog is local and authoritative for station identity.
        // Bootstrap refresh starts only after this load has had a chance to canonicalize
        // the saved route, avoiding an initial request against stale/legacy station data.
        loadCuratedStationCatalog()
    }

    private fun loadCuratedStationCatalog() {
        viewModelScope.launch(Dispatchers.Default) {
            val imported = runCatching { curatedStationCatalog.load() }
                .onFailure { error ->
                    Log.e("BinarioCatalog", "Impossibile caricare il catalogo stazioni curato", error)
                }
                .getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                if (imported.isNotEmpty()) {
                    val legacyCustom = customStationStore.stations()
                    val importedCanonicalizer = StationCanonicalizer(imported)
                    val migratedCustom = legacyCustom.map(importedCanonicalizer::canonicalize)
                    stations = imported + migratedCustom.filterNot { custom -> imported.any { it.id == custom.id } }
                    stationAutocomplete = StationAutocompleteEngine(stations)
                    stationCanonicalizer = StationCanonicalizer(stations)
                    savedRoutes = pinnedFirst(routeStore.routes(stations).map(::canonicalRoute))
                    recentRoutes = routeStore.recentRoutes(stations).map { recent ->
                        RecentRoute(canonicalStation(recent.origin), canonicalStation(recent.destination))
                    }
                    // Canonicalize the route that is current *now*. Re-reading RouteStore here
                    // could overwrite an ad-hoc route selected while the catalog was loading.
                    activeRoute = savedRoutes.firstOrNull { it.id == activeRoute.id }
                        ?: canonicalRoute(activeRoute)
                    // Before the catalogue loads only remembered stations can decode the saved
                    // routes, so the stored active route may not have resolved at construction.
                    // Resolve it again unless the user already activated a route meanwhile.
                    if (!hasActiveRoute && refreshRequestId == 0L) {
                        activeRoute = routeStore.activeRoute(savedRoutes)
                    }
                    favouriteStations = stationPreferences.favourites(stations)
                    stationMonitors = personalTravelStore.stationMonitors(stations)
                }

                // If no user-driven refresh started while the catalog was loading, perform
                // the one bootstrap refresh now (also when the catalog load failed/was empty).
                if (enableStartupEffects && refreshRequestId == 0L && hasActiveRoute) refresh()
            }
        }
    }

    private fun canonicalRoute(route: SavedRoute): SavedRoute = route.copy(
        outwardOrigin = canonicalStation(route.outwardOrigin),
        outwardDestination = canonicalStation(route.outwardDestination)
    )

    private fun canonicalStation(station: Station): Station = stationCanonicalizer.canonicalize(station)

    val hasActiveRoute: Boolean get() = activeRoute.id != UNCONFIGURED_ROUTE_ID
    val hasReturnJourney: Boolean get() = returnJourneyQuery != null
    val hasStationBoardProviders: Boolean get() = stationBoardService.hasProviders

    fun plannerOriginText(): String = activeRoute.takeIf { hasActiveRoute }?.outwardOrigin?.name.orEmpty()

    fun plannerDestinationText(): String = activeRoute.takeIf { hasActiveRoute }?.outwardDestination?.name.orEmpty()

    /**
     * Risultati dell'autocomplete, separati per campo. Compose osserva soltanto questa
     * mappa: l'indice locale non viene interrogato durante il render.
     */
    var stationSuggestionResults by mutableStateOf<Map<String, List<Station>>>(emptyMap())
        private set
    private val stationSuggestionJobs = mutableMapOf<String, Job>()
    private val stationSuggestionRequestIds = mutableMapOf<String, Long>()

    fun stationSuggestions(fieldKey: String): List<Station> = stationSuggestionResults[fieldKey].orEmpty()

    /** Debounce e ricerca locale fuori dal thread/render Compose; un input successivo annulla il precedente. */
    fun updateStationSuggestions(fieldKey: String, query: String) {
        stationSuggestionJobs.remove(fieldKey)?.cancel()
        val requestId = (stationSuggestionRequestIds[fieldKey] ?: 0L) + 1L
        stationSuggestionRequestIds[fieldKey] = requestId
        stationSuggestionResults = stationSuggestionResults - fieldKey
        if (query.trim().length < 2) return
        val autocomplete = stationAutocomplete

        stationSuggestionJobs[fieldKey] = viewModelScope.launch(Dispatchers.Default) {
            delay(STATION_SUGGESTION_DEBOUNCE_MILLIS)
            val suggestions = autocomplete.suggest(query)
                .ifEmpty { listOfNotNull(stationFromFreeText(query)) }
            withContext(Dispatchers.Main) {
                if (stationSuggestionRequestIds[fieldKey] == requestId) {
                    stationSuggestionResults = stationSuggestionResults + (fieldKey to suggestions)
                    stationSuggestionJobs.remove(fieldKey)
                }
            }
        }
    }

    fun clearStationSuggestions(fieldKey: String) {
        stationSuggestionJobs.remove(fieldKey)?.cancel()
        stationSuggestionRequestIds[fieldKey] = (stationSuggestionRequestIds[fieldKey] ?: 0L) + 1L
        stationSuggestionResults = stationSuggestionResults - fieldKey
    }

    fun stationFromName(name: String): Station? {
        return stationAutocomplete.findExact(name) ?: stationFromFreeText(name)
    }

    fun isStationFavourite(station: Station): Boolean = stationPreferences.isFavourite(station)

    fun toggleStationFavourite(station: Station) {
        val remembered = rememberStation(station)
        stationPreferences.toggle(remembered)
        favouriteStations = stationPreferences.favourites(stations)
    }

    /** Riceve una posizione puntuale dalla UI; non viene mai persistita. */
    fun updateNearbyStations(latitude: Double, longitude: Double) {
        nearbyStations = StationCoordinates.nearby(stations, latitude, longitude).take(8)
    }

    fun selectStationBoardStation(station: Station) {
        val canonical = canonicalStation(station)
        if (stationBoardStation?.id != canonical.id) loadStationBoard(canonical, stationBoardKind)
    }

    fun loadStationBoard(station: Station? = stationBoardStation, kind: BoardKind = stationBoardKind) {
        val canonical = canonicalStation(station ?: return)
        val sameContext = stationBoardStation?.id == canonical.id && stationBoardKind == kind
        if (sameContext && stationBoardJob?.isActive == true) return
        val requestId = ++stationBoardRequestId
        stationBoardJob?.cancel()
        if (!sameContext) {
            stationBoard = null
            closeStationBoardRun()
        } else {
            stationBoard = stationBoard?.withoutExpiredObservations(java.time.Instant.now())
        }
        stationBoardStation = canonical
        stationBoardKind = kind
        stationBoardRefreshState = RefreshState.LOADING
        stationBoardMessages = emptyList()
        stationBoardJob = viewModelScope.launch {
            stationBoardService.observe(canonical, kind).collect { result ->
                if (requestId != stationBoardRequestId) return@collect
                when (result) {
                    is StationBoardAggregateResult.Success -> {
                        stationBoard = result.board
                        stationBoardRefreshState = if (result.isComplete) RefreshState.SUCCESS else RefreshState.LOADING
                        stationBoardMessages = result.board.warnings.map { UiMessage(it.stationBoardWarningKey()) }.distinct()
                    }
                    is StationBoardAggregateResult.Unavailable -> {
                        // Keep the existing rows during a transient outage, never stale live claims.
                        stationBoard = stationBoard?.withoutExpiredObservations(java.time.Instant.now())
                        stationBoardRefreshState = RefreshState.ERROR
                        stationBoardMessages = listOf(UiMessage(UiMessageKey.STATION_BOARD_UNAVAILABLE))
                    }
                }
            }
        }
    }

    fun openStationBoard(station: Station, kind: BoardKind = BoardKind.DEPARTURES) = loadStationBoard(station, kind)

    fun refreshStationBoardIfIdle() {
        if (stationBoardJob?.isActive != true) loadStationBoard()
        if (selectedBoardEntry != null && stationBoardRunJob?.isActive != true) refreshStationBoardRun()
    }

    fun openStationBoardRun(entry: StationBoardEntry) {
        selectedBoardEntry = entry.withoutExpiredObservations(java.time.Instant.now())
        refreshStationBoardRun()
    }

    fun refreshStationBoardRun() {
        val station = stationBoardStation ?: return
        val entry = selectedBoardEntry ?: return
        val kind = stationBoardKind
        selectedBoardEntry = entry.withoutExpiredObservations(java.time.Instant.now())
        val requestId = ++stationBoardRunRequestId
        stationBoardRunJob?.cancel()
        stationBoardRunDetails = null
        stationBoardRunRefreshState = RefreshState.LOADING
        stationBoardRunJob = viewModelScope.launch {
            val details = stationBoardDetailsService.load(station, kind, entry)
            if (requestId != stationBoardRunRequestId) return@launch
            stationBoardRunDetails = details
            stationBoardRunRefreshState = if (details != null) RefreshState.SUCCESS else RefreshState.ERROR
        }
    }

    fun closeStationBoardRun() {
        ++stationBoardRunRequestId
        stationBoardRunJob?.cancel()
        selectedBoardEntry = null
        stationBoardRunDetails = null
        stationBoardRunRefreshState = RefreshState.IDLE
    }

    fun isStationMonitored(station: Station): Boolean = stationMonitors.any { it.stationId == station.id }

    fun toggleStationMonitor(station: Station, mode: StationMonitorMode = StationMonitorMode.BOTH) {
        val remembered = rememberStation(station)
        stationMonitors = personalTravelStore.toggleStationMonitor(remembered, mode, stations)
    }

    fun removeStationMonitor(stationId: String) {
        stationMonitors = personalTravelStore.removeStationMonitor(stationId, stations)
    }

    fun replaceStationMonitors(monitors: List<StationMonitor>) {
        stationMonitors = personalTravelStore.replaceStationMonitors(monitors, stations)
    }

    fun activateRoute(route: SavedRoute, selectedDirection: Direction = direction) {
        clearReturnJourneyState()
        journeyQueryState.resetForRouteActivation()
        activeRoute = route
        routeStore.saveActiveRoute(route.id)
        direction = selectedDirection
        routeStore.saveDirection(selectedDirection)
        clearSelectedService()
        primarySnapshot = null
        rebuildServicePresentations()
        operatorFilter = null
        refresh()
    }

    fun searchRoute(
        origin: Station,
        destination: Station,
        serviceDate: LocalDate = LocalDate.now(RailTime.defaultZone),
        departureTime: LocalTime = LocalTime.now(RailTime.defaultZone).withSecond(0).withNano(0)
    ): Boolean {
        val rememberedOrigin = rememberStation(canonicalStation(origin))
        val rememberedDestination = rememberStation(canonicalStation(destination))
        if (rememberedOrigin.id == rememberedDestination.id) {
            refreshMessage = UiMessage(UiMessageKey.ROUTE_STATIONS_MUST_DIFFER)
            refreshState = RefreshState.ERROR
            return false
        }
        clearReturnJourneyState()
        val storedRoute = savedRoutes.firstOrNull {
            it.outwardOrigin.id == rememberedOrigin.id && it.outwardDestination.id == rememberedDestination.id
        }
        activeRoute = storedRoute ?: SavedRoute(
            id = searchedRouteId(rememberedOrigin, rememberedDestination),
            name = "${rememberedOrigin.city} – ${rememberedDestination.city}",
            outwardOrigin = rememberedOrigin,
            outwardDestination = rememberedDestination
        )
        direction = Direction.OUTBOUND
        journeyQueryState.selectPlanned(serviceDate, departureTime)
        routeStore.saveDirection(direction)
        recentRoutes = routeStore.recordRecent(rememberedOrigin, rememberedDestination, stations)
        clearSelectedService()
        primarySnapshot = null
        rebuildServicePresentations()
        operatorFilter = null
        trainNumberQuery = ""
        refresh()
        return true
    }

    fun searchRoundTrip(
        origin: Station,
        destination: Station,
        departureDate: LocalDate,
        departureTime: LocalTime,
        returnDate: LocalDate,
        returnTime: LocalTime
    ): Boolean {
        if (!PlannerDateTime.isReturnAfterDeparture(departureDate, departureTime, returnDate, returnTime)) {
            refreshMessage = UiMessage(UiMessageKey.ROUND_TRIP_RETURN_MUST_FOLLOW_DEPARTURE)
            refreshState = RefreshState.ERROR
            return false
        }
        if (!searchRoute(origin, destination, departureDate, departureTime)) return false

        returnJourneyQuery = JourneyQuery(returnDate, returnTime)
        refreshReturn()
        return true
    }

    fun switchDirection() {
        clearReturnJourneyState()
        // È ancora la stessa consultazione: una ricerca pianificata deve poter
        // mostrare anche la direzione opposta alla data/ora scelta dall'utente.
        direction = if (direction == Direction.OUTBOUND) Direction.RETURN else Direction.OUTBOUND
        routeStore.saveDirection(direction)
        clearSelectedService()
        primarySnapshot = null
        rebuildServicePresentations()
        operatorFilter = null
        refresh()
    }

    fun reconcileWidgetState() {
        // Ogni widget conserva ora il proprio target e la propria direzione.
        // Non modifica più la tratta attiva dell’app.
    }

    fun chooseDirection(newDirection: Direction) {
        if (newDirection != direction) switchDirection()
    }

    fun selectOperatorFilter(operator: String?) {
        operatorFilter = operator
        refreshVisibleServices()
    }

    fun operatorFilterOptions(): List<String> = availableOperatorNames

    fun selectJourneyChangeFilter(filter: JourneyChangeFilter) {
        journeyChangeFilter = filter
        refreshVisibleServices()
    }

    fun updateTrainNumberQuery(query: String) {
        val value = query.filter { it.isLetterOrDigit() || it.isWhitespace() || it == '-' }.take(32)
        if (value == trainNumberQuery) return
        trainNumberQuery = value
        ++trainNumberSearchRequestId
        trainNumberSearchJob?.cancel()
        trainNumberMatches = emptyList()
        trainNumberSearchState = RefreshState.IDLE
        trainNumberUnavailableSources = emptySet()
        if (selectedGlobalTrainId != null) clearSelectedService()
    }

    fun searchTrainNumber(refresh: Boolean = false) {
        val query = TrainNumberQuery(trainNumberQuery, JourneyQuery.now().serviceDate)
        if (!query.valid) { trainNumberSearchState = RefreshState.ERROR; return }
        if (trainNumberSearchJob?.isActive == true && refresh) return
        val requestId = ++trainNumberSearchRequestId
        trainNumberSearchJob?.cancel()
        if (!refresh) {
            clearSelectedService()
            trainNumberMatches = emptyList()
        } else {
            trainNumberMatches = trainNumberMatches.map { match ->
                if (!match.snapshot.isExpired) match else {
                    val service = serviceWithoutCurrentObservation(match.service)
                    match.copy(service = service, snapshot = match.snapshot.copy(services = listOf(service), freshness = Freshness.STALE))
                }
            }
        }
        trainNumberSearchDate = query.date
        val retainedMatches = trainNumberMatches
        trainNumberSearchState = RefreshState.LOADING
        trainNumberUnavailableSources = emptySet()
        trainNumberSearchJob = viewModelScope.launch {
            trainNumberSearchService.search(query).collect { update ->
                if (requestId != trainNumberSearchRequestId) return@collect
                trainNumberMatches = if (refresh && !update.complete) {
                    (retainedMatches.associateBy { it.id } + update.matches.associateBy { it.id }).values.toList()
                } else update.matches
                trainNumberUnavailableSources = update.unavailableSources
                trainNumberSearchState = if (!update.complete) RefreshState.LOADING
                    else if (update.matches.isEmpty() && update.unavailableSources.isNotEmpty()) RefreshState.ERROR else RefreshState.SUCCESS
                selectedGlobalTrainId?.let { selectedId ->
                    update.matches.singleOrNull { it.id == selectedId }?.let { match ->
                        selectedService = match.service
                        selectedServiceSnapshot = match.snapshot
                    } ?: if (update.complete) {
                        val service = selectedService?.let(::serviceWithoutCurrentObservation)
                        selectedService = service
                        selectedServiceSnapshot = selectedServiceSnapshot?.copy(services = listOfNotNull(service), freshness = Freshness.STALE)
                    } else Unit
                }
            }
        }
    }

    fun openTrainNumberMatch(match: TrainNumberMatch) {
        selectedServiceDirection = null
        selectedGlobalTrainId = match.id
        selectedService = match.service
        selectedServiceSnapshot = match.snapshot
    }

    fun clearSearchFilters() {
        operatorFilter = null
        journeyChangeFilter = JourneyChangeFilter.ANY
        trainNumberQuery = ""
        refreshVisibleServices()
    }

    fun createRoute(origin: Station, destination: Station, customName: String? = null): Boolean {
        clearReturnJourneyState()
        val rememberedOrigin = rememberStation(origin)
        val rememberedDestination = rememberStation(destination)
        if (rememberedOrigin == rememberedDestination) {
            refreshMessage = UiMessage(UiMessageKey.ROUTE_STATIONS_MUST_DIFFER)
            refreshState = RefreshState.ERROR
            return false
        }
        val defaultName = "${rememberedOrigin.city} – ${rememberedDestination.city}"
        val route = SavedRoute(
            id = UUID.randomUUID().toString(),
            name = customName?.trim()?.takeIf { it.isNotBlank() } ?: defaultName,
            outwardOrigin = rememberedOrigin,
            outwardDestination = rememberedDestination
        )
        savedRoutes = pinnedFirst(listOf(route) + savedRoutes.filterNot { it.outwardOrigin == origin && it.outwardDestination == destination })
        routeStore.saveRoutes(savedRoutes)
        activeRoute = route
        routeStore.saveActiveRoute(route.id)
        direction = Direction.OUTBOUND
        routeStore.saveDirection(direction)
        journeyQueryState.resetForRouteActivation()
        clearSelectedService()
        primarySnapshot = null
        rebuildServicePresentations()
        operatorFilter = null
        refresh()
        return true
    }

    fun toggleRoutePin(route: SavedRoute) {
        val updated = savedRoutes.map {
            if (it.id == route.id) it.copy(isPinned = !it.isPinned) else it
        }
        savedRoutes = pinnedFirst(updated)
        routeStore.saveRoutes(savedRoutes)
        activeRoute = savedRoutes.firstOrNull { it.id == activeRoute.id } ?: unconfiguredRoute()
    }

    fun canMoveRoute(route: SavedRoute, delta: Int): Boolean =
        SavedRouteOrdering.canMove(savedRoutes, route.id, delta)

    fun moveRoute(route: SavedRoute, delta: Int) {
        val updated = SavedRouteOrdering.move(savedRoutes, route.id, delta)
        if (updated == savedRoutes) return
        savedRoutes = updated
        routeStore.saveRoutes(savedRoutes)
    }

    fun removeRoute(route: SavedRoute) {
        savedRoutes = savedRoutes.filterNot { it.id == route.id }
        routeStore.saveRoutes(savedRoutes)
        if (activeRoute.id == route.id) {
            clearReturnJourneyState()
            journeyQueryState.resetForRouteActivation()
            activeRoute = savedRoutes.firstOrNull() ?: unconfiguredRoute()
            if (hasActiveRoute) routeStore.saveActiveRoute(activeRoute.id) else routeStore.clearActiveRoute()
            clearSelectedService()
            primarySnapshot = null
            rebuildServicePresentations()
            operatorFilter = null
            if (hasActiveRoute) refresh() else {
                refreshRequestId += 1
                primarySnapshot = null
                rebuildServicePresentations()
                refreshState = RefreshState.IDLE
                refreshMessage = UiMessage(UiMessageKey.ROUTE_REQUIRED_TO_START)
                revokeWearSnapshot()
            }
        }
    }

    fun refresh() {
        if (selectedGlobalTrainId != null) { searchTrainNumber(refresh = true); return }
        // A detail opened from the independent return leg must not refresh the primary leg
        // or emit its cache/widget/Wear side effects. Results/operations have no selected
        // return service, so their normal refresh still updates both legs when applicable.
        if (selectedServiceDirection == Direction.RETURN && returnJourneyQuery != null) {
            refreshReturn()
            return
        }
        if (!hasActiveRoute) {
            refreshRequestId += 1
            primarySnapshot = null
            rebuildServicePresentations()
            refreshState = RefreshState.ERROR
            refreshMessage = UiMessage(UiMessageKey.ROUTE_ORIGIN_DESTINATION_REQUIRED)
            return
        }
        refreshState = RefreshState.LOADING
        refreshMessage = null
        val routeAtRequest = activeRoute
        val directionAtRequest = direction
        val queryAtRequest = journeyQueryState.value
        // Widgets and Wear present their snapshot as the next trains of the route; a planned
        // search for another date or time must stay inside the app.
        val publishesCurrentDepartures = journeyQueryState.followsCurrentTime
        val requestId = ++refreshRequestId
        viewModelScope.launch {
            val result = dataSource.loadRoute(routeAtRequest, directionAtRequest, queryAtRequest)
            if (requestId != refreshRequestId) return@launch
            when (result) {
                is DataResult.Success -> {
                    primarySnapshot = result.snapshot
                    primarySnapshotIsCurrent = publishesCurrentDepartures
                    rebuildServicePresentations()
                    reconcileSelectedService(result.snapshot, directionAtRequest)
                    retainAvailableOperatorFilter()
                    cache.save(result.snapshot)
                    if (publishesCurrentDepartures) {
                        RouteWidgetProvider.saveSnapshotForMatchingRoutes(getApplication(), result.snapshot)
                        RouteWidgetProvider.refreshAll(getApplication())
                    }
                    if (publishesCurrentDepartures && integrationSettings.wearSnapshotSharingEnabled) {
                        if (WearSnapshotPublisher.enable(getApplication())) {
                            if (
                                integrationSettings.wearSnapshotSharingEnabled &&
                                hasActiveRoute &&
                                requestId == refreshRequestId &&
                                activeRoute.id == routeAtRequest.id
                            ) {
                                WearSnapshotPublisher.publish(getApplication(), result.snapshot)
                            } else if (!integrationSettings.wearSnapshotSharingEnabled || !hasActiveRoute) {
                                WearSnapshotPublisher.clear(getApplication())
                            }
                        }
                    }
                    refreshState = RefreshState.SUCCESS
                    refreshMessage = result.snapshot.advisoryMessage()
                }
                is DataResult.Failure -> {
                    invalidateSelectedService(directionAtRequest, onlyIfExpired = true)
                    if (publishesCurrentDepartures) {
                        RouteWidgetProvider.markMatchingRouteDirectionUnavailable(getApplication(), routeAtRequest, directionAtRequest, result.reason.uiMessage())
                        RouteWidgetProvider.refreshAll(getApplication())
                    }
                    if (publishesCurrentDepartures && integrationSettings.wearSnapshotSharingEnabled) {
                        if (WearSnapshotPublisher.enable(getApplication())) {
                            if (
                                integrationSettings.wearSnapshotSharingEnabled &&
                                hasActiveRoute &&
                                requestId == refreshRequestId &&
                                activeRoute.id == routeAtRequest.id
                            ) {
                                WearSnapshotPublisher.publishUnavailable(
                                    getApplication(),
                                    routeAtRequest.displayName(directionAtRequest),
                                    primarySnapshot?.providerName ?: cache.lastProvider()
                                )
                            } else if (!integrationSettings.wearSnapshotSharingEnabled || !hasActiveRoute) {
                                WearSnapshotPublisher.clear(getApplication())
                            }
                        }
                    }
                    refreshState = RefreshState.ERROR
                    refreshMessage = result.reason.uiMessage()
                }
            }
        }
        if (returnJourneyQuery != null) refreshReturn()
    }

    /** Called only while a result/detail surface is visible and the activity is started. */
    fun refreshLiveIfIdle() {
        if (selectedGlobalTrainId != null) { searchTrainNumber(refresh = true); return }
        if (!hasActiveRoute) return
        val today = JourneyQuery.now().serviceDate
        if (selectedServiceDirection == Direction.RETURN && returnJourneyQuery != null) {
            if (returnRefreshState != RefreshState.LOADING && returnJourneyQuery?.serviceDate == today) refreshReturn()
        } else if (refreshState != RefreshState.LOADING && returnRefreshState != RefreshState.LOADING && journeyQueryState.value.serviceDate == today) {
            refresh()
        }
    }

    fun refreshSelectedService() {
        if (selectedServiceDirection == Direction.RETURN && returnJourneyQuery != null) {
            refreshReturn()
        } else {
            refresh()
        }
    }

    private fun refreshReturn() {
        val queryAtRequest = returnJourneyQuery ?: return
        if (!hasActiveRoute) {
            clearReturnJourneyState()
            return
        }
        returnRefreshState = RefreshState.LOADING
        returnRefreshMessage = null
        val routeAtRequest = activeRoute
        val requestId = ++returnRefreshRequestId
        viewModelScope.launch {
            val result = dataSource.loadRoute(routeAtRequest, Direction.RETURN, queryAtRequest)
            if (requestId != returnRefreshRequestId || returnJourneyQuery != queryAtRequest) return@launch
            when (result) {
                is DataResult.Success -> {
                    returnSnapshot = result.snapshot
                    rebuildServicePresentations()
                    reconcileSelectedService(result.snapshot, Direction.RETURN)
                    retainAvailableOperatorFilter()
                    returnRefreshState = RefreshState.SUCCESS
                    returnRefreshMessage = result.snapshot.advisoryMessage()
                }
                is DataResult.Failure -> {
                    invalidateSelectedService(Direction.RETURN, onlyIfExpired = true)
                    // Keep the last successful return snapshot, matching the primary-leg
                    // refresh policy. A transient failure must not erase previously
                    // valid data; UI state/message make the failed refresh explicit.
                    retainAvailableOperatorFilter()
                    returnRefreshState = RefreshState.ERROR
                    returnRefreshMessage = result.reason.uiMessage()
                }
            }
        }
    }

    private fun clearReturnJourneyState() {
        returnRefreshRequestId += 1
        if (selectedServiceDirection == Direction.RETURN) clearSelectedService()
        returnJourneyQuery = null
        returnSnapshot = null
        rebuildServicePresentations()
        returnRefreshState = RefreshState.IDLE
        returnRefreshMessage = null
    }

    private fun clearSelectedService() {
        selectedGlobalTrainId = null
        selectedService = null
        selectedServiceDirection = null
        selectedServiceSnapshot = null
    }

    private fun retainAvailableOperatorFilter() {
        operatorFilter = operatorFilter?.takeIf { selected ->
            availableOperatorNames.any { it.equals(selected, ignoreCase = true) }
        }
        refreshVisibleServices()
    }

    private fun reconcileSelectedService(updated: RouteSnapshot, updatedDirection: Direction) {
        val selected = selectedService ?: return
        if (selectedServiceDirection != updatedDirection) return
        findRefreshedService(selected, updated.services)?.let { refreshed ->
            selectedService = refreshed
            selectedServiceSnapshot = updated
        } ?: invalidateSelectedService(updatedDirection)
    }

    private fun invalidateSelectedService(updatedDirection: Direction, onlyIfExpired: Boolean = false) {
        if (selectedServiceDirection != updatedDirection) return
        val previous = selectedServiceSnapshot ?: return
        if (onlyIfExpired && !previous.isExpired) return
        val retained = selectedService?.let(::serviceWithoutCurrentObservation) ?: return
        selectedService = retained
        selectedServiceSnapshot = previous.copy(
            services = listOf(retained), freshness = Freshness.STALE,
            qualityScore = null, qualityBand = it.binario.app.data.DataQualityBand.UNKNOWN,
            qualityWarnings = previous.qualityWarnings + it.binario.app.data.SnapshotWarning.STALE_SOURCE
        )
    }

    fun openService(service: TrainService, serviceDirection: Direction? = null) {
        selectedGlobalTrainId = null
        val belongsToReturn = returnSnapshot?.services.orEmpty().any { candidate ->
            candidate === service || candidate.id == service.id
        }
        val belongsToPrimary = primarySnapshot?.services.orEmpty().any { candidate ->
            candidate === service || candidate.id == service.id
        }
        val resolvedDirection = serviceDirection ?: if (
            returnJourneyQuery != null && belongsToReturn && !belongsToPrimary
        ) {
            Direction.RETURN
        } else {
            direction
        }
        selectedServiceDirection = resolvedDirection
        selectedService = service
        selectedServiceSnapshot = if (resolvedDirection == Direction.RETURN && returnJourneyQuery != null) {
            returnSnapshot
        } else {
            primarySnapshot
        }
    }

    fun closeService() {
        clearSelectedService()
    }

    fun currentServices(): List<TrainService> = visiblePrimaryServices

    fun currentReturnServices(): List<TrainService> = visibleReturnServices

    fun homeServices(): List<TrainService> = primaryServicePresentation.ordered

    fun quickServices(): List<TrainService> = primaryServicePresentation.ordered.take(2)

    private fun rebuildServicePresentations() {
        primaryServicePresentation = ServicePresentation.fromServices(primarySnapshot?.services.orEmpty())
        returnServicePresentation = ServicePresentation.fromServices(returnSnapshot?.services.orEmpty())
        availableOperatorNames = publishedOperatorNames(
            primaryServicePresentation.ordered + returnServicePresentation.ordered
        )
        refreshVisibleServices()
    }

    private fun refreshVisibleServices() {
        visiblePrimaryServices = primaryServicePresentation.filtered(
            operator = operatorFilter,
            numberQuery = "",
            journeyChangeFilter = journeyChangeFilter
        )
        visibleReturnServices = returnServicePresentation.filtered(
            operator = operatorFilter,
            numberQuery = "",
            journeyChangeFilter = journeyChangeFilter
        )
    }

    fun currentRouteLabel(): String = activeRoute.displayName(direction)

    fun useRecentRoute(recent: RecentRoute): Boolean = searchRoute(recent.origin, recent.destination)

    fun isMonitoring(service: TrainService): Boolean = monitoredServices.any { it.number.equals(service.number, ignoreCase = true) }

    fun toggleServiceMonitoring(service: TrainService) {
        monitoredServices = routeStore.toggleServiceMonitor(service)
    }

    fun removeServiceMonitoring(number: String) {
        monitoredServices = routeStore.removeServiceMonitor(number)
    }

    fun replaceServiceMonitors(monitors: List<ServiceMonitor>) {
        monitoredServices = routeStore.replaceServiceMonitors(monitors)
    }

    /** Null without an active route: the placeholder route has no label an alert could carry. */
    fun createCommuterAlert(
        stationName: String,
        time: String,
        leadMinutes: Int,
        repeat: AlertRepeat,
        quietHoursEnabled: Boolean
    ): CommuterAlert? {
        if (!hasActiveRoute) return null
        val alert = commuterAlertStore.newAlert(currentRouteLabel(), stationName, time, leadMinutes, repeat, quietHoursEnabled)
        commuterAlerts = commuterAlertStore.save(alert)
        return alert
    }

    fun removeCommuterAlert(alert: CommuterAlert) {
        CommuterAlertScheduler.cancel(getApplication(), alert.id)
        commuterAlerts = commuterAlertStore.remove(alert.id)
    }

    fun startActiveTrip(service: TrainService) {
        activeTrip = personalTravelStore.startTrip(service)
    }

    fun advanceActiveTrip() {
        activeTrip = personalTravelStore.advanceTrip()
    }

    fun endActiveTrip() {
        personalTravelStore.endTrip()
        activeTrip = null
    }

    fun updateMobilityPreferences(value: MobilityPreferences) {
        mobilityPreferences = value
        mobilityPreferencesStore.save(value)
    }

    fun exportLocalBackup(): String {
        val favouriteIds = stationPreferences.favouriteIds()
        val monitoredStationIds = stationMonitors.mapTo(hashSetOf()) { it.stationId }
        return LocalDataBackupCodec.encode(
            LocalDataBackup(
                routes = savedRoutes,
                favouriteStationIds = favouriteIds,
                monitoredServices = monitoredServices,
                stationMonitors = stationMonitors,
                commuterAlerts = commuterAlerts,
                mobilityPreferences = mobilityPreferences,
                stations = (
                    savedRoutes.flatMap { listOf(it.outwardOrigin, it.outwardDestination) } +
                        stations.filter { it.id in favouriteIds || it.id in monitoredStationIds }
                    ).distinctBy { it.id }
            )
        )
    }

    fun decodeLocalBackup(raw: String): LocalBackupResult = LocalDataBackupCodec.decode(raw, stations)

    fun importLocalBackup(raw: String): UiMessage = importLocalBackup(decodeLocalBackup(raw))

    fun importLocalBackup(result: LocalBackupResult): UiMessage = when (result) {
        is LocalBackupResult.Failure -> UiMessage(UiMessageKey.BACKUP_IMPORT_INVALID)
        is LocalBackupResult.Success -> {
            clearReturnJourneyState()
            val backup = result.backup
            restoreBackupStations(backup)
            savedRoutes = pinnedFirst(routeStore.replaceRoutes(backup.routes))
            activeRoute = savedRoutes.firstOrNull() ?: unconfiguredRoute()
            clearSelectedService()
            primarySnapshot = null
            operatorFilter = null
            if (hasActiveRoute) routeStore.saveActiveRoute(activeRoute.id) else routeStore.clearActiveRoute()
            stationPreferences.replaceFavourites(backup.favouriteStationIds)
            favouriteStations = stationPreferences.favourites(stations)
            replaceServiceMonitors(backup.monitoredServices)
            replaceStationMonitors(backup.stationMonitors)
            commuterAlerts = commuterAlertStore.replace(backup.commuterAlerts)
            CommuterAlertScheduler.rescheduleAll(getApplication())
            updateMobilityPreferences(backup.mobilityPreferences)
            if (hasActiveRoute) refresh() else {
                refreshRequestId += 1
                primarySnapshot = null
                refreshState = RefreshState.IDLE
                refreshMessage = UiMessage(UiMessageKey.BACKUP_IMPORTED_WITHOUT_ROUTE)
                revokeWearSnapshot()
            }
            UiMessage(UiMessageKey.BACKUP_IMPORT_SUCCESS, savedRoutes.size, stationMonitors.size, commuterAlerts.size)
        }
    }

    fun setWearSnapshotSharing(enabled: Boolean) {
        if (!enabled) WearSnapshotPublisher.persistRevocationIntent(getApplication())
        integrationSettings = integrationSettings.copy(wearSnapshotSharingEnabled = enabled)
        integrationStore.save(integrationSettings)
        if (enabled) {
            viewModelScope.launch {
                if (WearSnapshotPublisher.enable(getApplication())) {
                    if (integrationSettings.wearSnapshotSharingEnabled) {
                        primarySnapshot?.takeIf { primarySnapshotIsCurrent }?.let { WearSnapshotPublisher.publish(getApplication(), it) }
                    } else {
                        WearSnapshotPublisher.clear(getApplication())
                    }
                }
            }
        } else {
            revokeWearSnapshot()
        }
    }

    private fun revokeWearSnapshot() {
        WearSnapshotPublisher.persistRevocationIntent(getApplication())
        viewModelScope.launch { WearSnapshotPublisher.clear(getApplication()) }
    }

    fun setVoiceProfileSyncRequested(enabled: Boolean) {
        integrationSettings = integrationSettings.copy(voiceProfileSyncRequested = enabled)
        integrationStore.save(integrationSettings)
    }

    fun voiceProfileSyncReadiness(): VoiceProfileSyncReadiness = when {
        !integrationSettings.voiceProfileSyncRequested -> VoiceProfileSyncReadiness.DISABLED
        else -> VoiceProfileSyncReadiness.ACCOUNT_LINK_REQUIRED
    }

    /** Prepara il body contrattuale, ma non esegue rete né conserva un token. */
    fun preparedVoiceProfileRequest(expectedRevision: Int? = null): AlexaCompanionProfileRequest? {
        if (voiceProfileSyncReadiness() != VoiceProfileSyncReadiness.READY_FOR_AUTHENTICATED_SYNC) return null
        return unifiedProfile().toAlexaCompanionProfileRequest(expectedRevision)
    }

    fun setAppleWatchSetupRequested(enabled: Boolean) {
        integrationSettings = integrationSettings.copy(appleWatchSetupRequested = enabled)
        integrationStore.save(integrationSettings)
    }

    fun unifiedProfile(): UnifiedBinarioProfile = UnifiedBinarioProfile(
        activeRouteId = activeRoute.id,
        direction = direction,
        monitoredTrainNumbers = monitoredServices.map { it.number },
        wearSnapshotSharingEnabled = integrationSettings.wearSnapshotSharingEnabled,
        voiceProfileSyncRequested = integrationSettings.voiceProfileSyncRequested,
        appleWatchSetupRequested = integrationSettings.appleWatchSetupRequested
    )

    private fun displaySnapshot(): RouteSnapshot? = selectedServiceSnapshot ?: primarySnapshot

    fun currentProviderLabel(): String? = displaySnapshot()?.providerName ?: cache.lastProvider()

    fun currentConfidence(): DataConfidence? = displaySnapshot()?.confidence

    fun currentQualityBand() = displaySnapshot()?.qualityBand

    fun currentQualityScore(): Int? = displaySnapshot()?.qualityScore

    fun currentFreshness(): Freshness? = displaySnapshot()?.freshness

    fun isDemonstrationData(): Boolean = displaySnapshot()?.confidence == DataConfidence.DEMONSTRATION

    fun lastRefreshEpochMillis(): Long = selectedService?.operationalObservedAtEpochMillis
        ?: displaySnapshot()?.sourceObservedAtEpochMillis ?: cache.lastRefreshEpochMillis()

    private fun rememberStation(station: Station): Station {
        val existing = stations.firstOrNull { it.id == station.id } ?: station
        val saved = customStationStore.remember(existing, retainedStationIds())
        addKnownStations(saved)
        return saved.firstOrNull { it.id == existing.id } ?: existing
    }

    /** Stations only the backup knows must be remembered, or its routes vanish at the next start. */
    private fun restoreBackupStations(backup: LocalDataBackup) {
        if (backup.stations.isEmpty()) return
        val retained = retainedStationIds() +
            backup.routes.flatMap { listOf(it.outwardOrigin.id, it.outwardDestination.id) } +
            backup.favouriteStationIds + backup.stationMonitors.map { it.stationId }
        addKnownStations(customStationStore.rememberAll(backup.stations, retained))
    }

    private fun addKnownStations(candidates: List<Station>) {
        val knownIds = stations.mapTo(hashSetOf()) { it.id }
        val additions = candidates.filter { knownIds.add(it.id) }
        if (additions.isNotEmpty()) {
            stations = stations + additions
            stationAutocomplete = StationAutocompleteEngine(stations)
        }
    }

    private fun retainedStationIds(): Set<String> = buildSet {
        savedRoutes.forEach { add(it.outwardOrigin.id); add(it.outwardDestination.id) }
        if (hasActiveRoute) { add(activeRoute.outwardOrigin.id); add(activeRoute.outwardDestination.id) }
        addAll(stationPreferences.favouriteIds())
        stationMonitors.forEach { add(it.stationId) }
    }
}

private fun ProviderWarning.stationBoardWarningKey(): UiMessageKey = when (this) {
    ProviderWarning.SOURCE_CONFLICT -> UiMessageKey.STATION_BOARD_SOURCE_CONFLICT
    ProviderWarning.REALTIME_UNAVAILABLE -> UiMessageKey.STATION_BOARD_REALTIME_UNAVAILABLE
    ProviderWarning.STALE_SOURCE -> UiMessageKey.STATION_BOARD_STALE_SOURCE
    ProviderWarning.IDENTITY_AMBIGUOUS -> UiMessageKey.STATION_BOARD_IDENTITY_AMBIGUOUS
    ProviderWarning.REPLACEMENT_SERVICE -> UiMessageKey.STATION_BOARD_REPLACEMENT_SERVICE
    ProviderWarning.INCOMPLETE_COVERAGE -> UiMessageKey.STATION_BOARD_INCOMPLETE_COVERAGE
}

internal fun publishedOperatorNames(services: List<TrainService>): List<String> =
    services.asSequence()
        .map { it.displayOperator.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
        .toList()

internal fun findRefreshedService(selected: TrainService, services: List<TrainService>): TrainService? =
    services.firstOrNull { it.id == selected.id }
        ?: services.firstOrNull {
            it.number.equals(selected.number, ignoreCase = true) &&
                it.scheduledDeparture == selected.scheduledDeparture &&
                it.origin == selected.origin &&
                it.destination == selected.destination
        }

/** Retain the last known times for context without claiming a current zero-minute delay. */
internal fun serviceWithoutCurrentObservation(service: TrainService): TrainService = service.copy(
    operationalStatusVerified = false,
    officialDelayMinutes = null,
    stops = service.stops.map { it.copy(operationalStatusVerified = false) }
)

/**
 * Snapshot immutabile già ordinato. Filtri e UI leggono questa struttura invece
 * di ordinare l'elenco di corse a ogni ricomposizione.
 */
private class ServicePresentation private constructor(
    val ordered: List<TrainService>
) {
    companion object {
        fun empty(): ServicePresentation = ServicePresentation(emptyList())

        fun fromServices(services: List<TrainService>): ServicePresentation =
            ServicePresentation(orderServicesForDisplay(services))
    }

    fun filtered(
        operator: String?,
        numberQuery: String,
        journeyChangeFilter: JourneyChangeFilter
    ): List<TrainService> {
        val query = numberQuery.trim()
        return ordered.filter { service ->
            (operator == null || service.displayOperator.equals(operator, ignoreCase = true)) &&
                (query.isBlank() || service.number.contains(query, ignoreCase = true)) &&
                journeyChangeFilter.accepts(service.transferCount)
        }
    }
}

private fun pinnedFirst(routes: List<SavedRoute>): List<SavedRoute> = SavedRouteOrdering.ordered(routes)

private class RouteStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_routes_v3", Context.MODE_PRIVATE)

    fun routes(stations: List<Station>): List<SavedRoute> {
        val stored = preferences.getString(KEY_ROUTES, null)
            ?.split(";")
            ?.mapNotNull { decodeRoute(it, stations) }
            .orEmpty()
        return stored.filterNot { route -> route.id.startsWith("default-") }
    }

    fun activeRoute(routes: List<SavedRoute>): SavedRoute {
        val activeId = preferences.getString(KEY_ACTIVE_ROUTE, null)
        return routes.firstOrNull { it.id == activeId } ?: routes.firstOrNull() ?: unconfiguredRoute()
    }

    fun direction(): Direction = runCatching {
        Direction.valueOf(preferences.getString(KEY_DIRECTION, Direction.OUTBOUND.name) ?: Direction.OUTBOUND.name)
    }.getOrDefault(Direction.OUTBOUND)

    fun saveRoutes(routes: List<SavedRoute>) {
        preferences.edit { putString(KEY_ROUTES, routes.joinToString(";") { encodeRoute(it) }) }
    }

    fun replaceRoutes(routes: List<SavedRoute>): List<SavedRoute> {
        val normalized = SavedRouteOrdering.ordered(routes)
        saveRoutes(normalized)
        return normalized
    }

    fun saveActiveRoute(id: String) {
        preferences.edit { putString(KEY_ACTIVE_ROUTE, id) }
    }

    fun clearActiveRoute() {
        preferences.edit { remove(KEY_ACTIVE_ROUTE) }
    }

    fun saveDirection(direction: Direction) {
        preferences.edit { putString(KEY_DIRECTION, direction.name) }
    }

    fun recentRoutes(stations: List<Station>): List<RecentRoute> = preferences.getString(KEY_RECENT, "")
        .orEmpty()
        .split(";")
        .mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size != 2) null else {
                val origin = stations.firstOrNull { it.id == parts[0] }
                val destination = stations.firstOrNull { it.id == parts[1] }
                if (origin != null && destination != null) RecentRoute(origin, destination) else null
            }
        }

    fun recordRecent(origin: Station, destination: Station, stations: List<Station>): List<RecentRoute> {
        val key = recentRouteKey(origin, destination)
        val updated = (listOf(key) + preferences.getString(KEY_RECENT, "").orEmpty().split(";").filter { it.isNotBlank() && it != key })
            .take(8)
        preferences.edit { putString(KEY_RECENT, updated.joinToString(";")) }
        return recentRoutes(stations)
    }

    fun monitoredServices(): List<ServiceMonitor> = preferences.getString(KEY_MONITORED_SERVICES, "")
        .orEmpty()
        .split(";")
        .mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size != 2) null else runCatching { ServiceMonitor(decode(parts[0]), decode(parts[1])) }.getOrNull()
        }

    fun toggleServiceMonitor(service: TrainService): List<ServiceMonitor> {
        val current = monitoredServices()
        val exists = current.any { it.number.equals(service.number, ignoreCase = true) }
        val updated = if (exists) current.filterNot { it.number.equals(service.number, ignoreCase = true) }
        else (listOf(ServiceMonitor(service.number, "${service.displayOperator} · ${service.destination}")) + current).take(12)
        preferences.edit { putString(KEY_MONITORED_SERVICES, updated.joinToString(";") { "${encode(it.number)},${encode(it.label)}" }) }
        return updated
    }

    fun removeServiceMonitor(number: String): List<ServiceMonitor> {
        val updated = monitoredServices().filterNot { it.number.equals(number, ignoreCase = true) }
        preferences.edit { putString(KEY_MONITORED_SERVICES, updated.joinToString(";") { "${encode(it.number)},${encode(it.label)}" }) }
        return updated
    }

    fun replaceServiceMonitors(monitors: List<ServiceMonitor>): List<ServiceMonitor> {
        val updated = monitors.distinctBy { it.number.uppercase() }.take(12)
        preferences.edit { putString(KEY_MONITORED_SERVICES, updated.joinToString(";") { "${encode(it.number)},${encode(it.label)}" }) }
        return updated
    }

    private fun encodeRoute(route: SavedRoute): String = listOf(
        route.id,
        route.name,
        route.outwardOrigin.id,
        route.outwardDestination.id,
        if (route.isPinned) "1" else "0",
        route.manualOrder?.takeIf { it in 0 until SavedRouteOrdering.MAX_ROUTES }?.toString().orEmpty()
    ).joinToString(",") { encode(it) }

    private fun decodeRoute(value: String, stations: List<Station>): SavedRoute? {
        return runCatching {
            val parts = value.split(",")
            require(parts.size in 4..6)
            val decoded = parts.map(::decode)
            val origin = stations.firstOrNull { it.id == decoded[2] } ?: legacyStation(decoded[2]) ?: return null
            val destination = stations.firstOrNull { it.id == decoded[3] } ?: legacyStation(decoded[3]) ?: return null
            val isPinned = decoded.getOrNull(4) == "1"
            val manualOrder = decoded.getOrNull(5)?.toIntOrNull()?.takeIf { it in 0 until SavedRouteOrdering.MAX_ROUTES }
            SavedRoute(decoded[0], decoded[1], origin, destination, isPinned, manualOrder)
        }.getOrNull()
    }

    /** Solo migrazione di preferenze personali: non alimenta il catalogo della UI. */
    private fun legacyStation(id: String): Station? = legacyStations[id]

    private fun encode(value: String): String = PreferenceValueCodec.encode(value)
    private fun decode(value: String): String = PreferenceValueCodec.decode(value)
        ?: throw IllegalArgumentException("Valore preferenza Base64 non valido")

    private companion object {
        const val KEY_ROUTES = "routes"
        const val KEY_ACTIVE_ROUTE = "active_route"
        const val KEY_DIRECTION = "direction"
        const val KEY_RECENT = "recent"
        const val KEY_MONITORED_SERVICES = "monitored_services"

        val legacyStations = listOf(
            Station("roma-termini", "Roma Termini", "Roma", "Lazio"),
            Station("roma-tiburtina", "Roma Tiburtina", "Roma", "Lazio"),
            Station("roma-ostiense", "Roma Ostiense", "Roma", "Lazio"),
            Station("roma-trastevere", "Roma Trastevere", "Roma", "Lazio"),
            Station("roma-san-pietro", "Roma San Pietro", "Roma", "Lazio"),
            Station("roma-tuscolana", "Roma Tuscolana", "Roma", "Lazio"),
            Station("fiumicino-aeroporto", "Fiumicino Aeroporto", "Fiumicino", "Lazio"),
            Station("ciampino", "Ciampino", "Ciampino", "Lazio"),
            Station("civitavecchia", "Civitavecchia", "Civitavecchia", "Lazio"),
            Station("frascati", "Frascati", "Frascati", "Lazio"),
            Station("velletri", "Velletri", "Velletri", "Lazio"),
            Station("viterbo-pf", "Viterbo Porta Fiorentina", "Viterbo", "Lazio"),
            Station("orte", "Orte", "Orte", "Lazio"),
            Station("latina", "Latina", "Latina", "Lazio"),
            Station("frosinone", "Frosinone", "Frosinone", "Lazio"),
            Station("cassino", "Cassino", "Cassino", "Lazio"),
            Station("napoli-centrale", "Napoli Centrale", "Napoli", "Campania"),
            Station("firenze-smn", "Firenze Santa Maria Novella", "Firenze", "Toscana"),
            Station("bologna-centrale", "Bologna Centrale", "Bologna", "Emilia-Romagna"),
            Station("milano-centrale", "Milano Centrale", "Milano", "Lombardia"),
            Station("venezia-sl", "Venezia Santa Lucia", "Venezia", "Veneto"),
            Station("genova-pp", "Genova Piazza Principe", "Genova", "Liguria"),
            Station("torino-pn", "Torino Porta Nuova", "Torino", "Piemonte"),
            Station("bari-centrale", "Bari Centrale", "Bari", "Puglia"),
            Station("palermo-centrale", "Palermo Centrale", "Palermo", "Sicilia"),
            Station("cagliari", "Cagliari", "Cagliari", "Sardegna")
        ).associateBy { it.id }
    }
}

internal fun recentRouteKey(origin: Station, destination: Station): String = "${origin.id}|${destination.id}"

internal fun searchedRouteId(origin: Station, destination: Station): String = "search-${origin.id}-${destination.id}"

internal const val UNCONFIGURED_ROUTE_ID = "unconfigured-route"

internal fun unconfiguredRoute(): SavedRoute = SavedRoute(
    id = UNCONFIGURED_ROUTE_ID,
    name = "",
    outwardOrigin = Station("unconfigured-origin", "", ""),
    outwardDestination = Station("unconfigured-destination", "", "")
)
