package it.binario.app

import android.app.Application
import it.binario.app.data.StationSearchNormalizer
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
import it.binario.app.journeys.ResultsPageState
import it.binario.app.journeys.ResultsPaging
import it.binario.app.timetables.SavedTimetableStore
import it.binario.app.widget.RouteWidgetProvider
import it.binario.app.widget.TrainsWidgetProvider
import it.binario.app.widget.WidgetStateStore
import it.binario.app.wear.WearSnapshotPublisher
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.Locale
import java.time.LocalDate
import java.time.LocalTime

enum class RefreshState { IDLE, LOADING, SUCCESS, ERROR }

data class RecentRoute(val origin: Station, val destination: Station) {
    val label: String get() = "${origin.name} → ${destination.name}"
}

class TrainViewModel @JvmOverloads constructor(
    application: Application,
    /** Widget configuration needs local catalog/search only, never startup refresh or Wear publication. */
    private val enableStartupEffects: Boolean = true,
    private val stationBoardService: StationBoardAggregateService = StationBoardAggregateService(
        BinarioDataGraph.stationBoardDisplayProviders(),
        // Metro and tram stops get their city pack's board (and realtime feed); railway stations keep the same sources.
        stopProviders = BinarioDataGraph.urbanStopBoardProviders(application)
    ),
    private val stationBoardDetailsService: StationBoardRunDetailService = BinarioDataGraph.stationBoardRunDetailService(),
    private val trainNumberSearchService: TrainNumberSearchService = BinarioDataGraph.trainNumberSearchService(application),
    /** Route source; null uses the application data graph. Instrumented tests pass a fixed local catalogue. */
    routeDataSource: TrainDataSource? = null
) : AndroidViewModel(application) {
    private companion object {
        const val STATION_SUGGESTION_DEBOUNCE_MILLIS = 180L
        /** Metro and tram stops among the station suggestions (the field shows five). */
        const val URBAN_SUGGESTIONS = 3
    }
    private val routeStore = RouteStore(application)
    private val cache = RouteSnapshotCache(application)
    /** Last good route results and boards on the device, shown as a saved copy when a refresh finds no network. */
    private val lastGood = it.binario.app.data.repository.LastGoodSnapshotStore(application)
    private val integrationStore = IntegrationSettingsStore(application)
    private val stationPreferences = StationPreferences(application)
    private val commuterAlertStore = CommuterAlertStore(application)
    private val personalTravelStore = PersonalTravelStore(application)
    private val mobilityPreferencesStore = MobilityPreferencesStore(application)
    private val customStationStore = CustomStationStore(application)
    /** Metro and tram stops of the city packs, offered next to the stations when the setting is on. */
    private val urbanStopIndex = BinarioDataGraph.urbanStopIndex(application)
    private val sourcesSettingsStore = SourcesSettingsStore(application)
    private val punctualityStore = PunctualityHistoryStore(application)
    private val dataSource: TrainDataSource = routeDataSource ?: BinarioDataGraph.routeDataSource(application)
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
    /** When [stationBoardRunDetails] was read (wall clock), for the age of its live values. */
    private var stationBoardRunLoadedAtMillis: Long? = null

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
    /** Local-only punctuality observations, newest first. */
    var punctualityHistory by mutableStateOf(punctualityStore.read())
        private set

    private var primaryServicePresentation by mutableStateOf(ServicePresentation.empty())
    private var returnServicePresentation by mutableStateOf(ServicePresentation.empty())
    private var visiblePrimaryServices by mutableStateOf<List<TrainService>>(emptyList())
    private var visibleReturnServices by mutableStateOf<List<TrainService>>(emptyList())
    private var availableOperatorNames by mutableStateOf<List<String>>(emptyList())
    /** «Più presto» / «Più tardi» of the route results: windows of direct trains loaded around the primary search. */
    private var servicePages by mutableStateOf(ServicePages.EMPTY)
    /** The request that reads again the open train of one of those windows. */
    private var pagedServiceJob: Job? = null

    private val journeyQueryState = JourneyQueryState()
    private var returnJourneyQuery: JourneyQuery? = null
    /** Monotonic token: only the newest current-direction refresh may mutate UI/cache/surfaces. */
    private var refreshRequestId: Long = 0L
    /** Separate token: a stale return response can never overwrite a newer round-trip search. */
    private var returnRefreshRequestId: Long = 0L
    /** Independent station-board token; changing station/kind invalidates older responses. */
    private var stationBoardRequestId: Long = 0L
    /**
     * When the last request of each kind of visible data started ([refreshClock]); null before the first. The periodic
     * refresh asks again 30 seconds after these, not 30 seconds after the screen appeared ([refreshIfDue]).
     */
    private var routeRequestedAtMillis: Long? = null
    private var returnRequestedAtMillis: Long? = null
    private var trainNumberRequestedAtMillis: Long? = null
    private var stationBoardRequestedAtMillis: Long? = null
    /** The catalogue has been applied and the bootstrap refresh decided: the periodic refresh may ask for the route. */
    private var bootstrapCompleted = false
    private fun refreshClock(): Long = android.os.SystemClock.elapsedRealtime()

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
            // The indexes of the whole catalogue are built here, off the main thread, and only assigned on it: built
            // there, they froze the first screen right after it appeared at every cold start.
            val built = if (imported.isNotEmpty()) StationIndexes.build(imported, customStationStore.stations()) else null
            withContext(Dispatchers.Main) {
                val routeAtConstruction = activeRoute
                if (built != null) {
                    // A station remembered while they were being built (a search made at once) is rare: only then
                    // are the indexes built again, here.
                    val remembered = customStationStore.stations()
                    val indexes = if (remembered == built.remembered) built else StationIndexes.build(imported, remembered)
                    stations = indexes.stations
                    stationAutocomplete = indexes.autocomplete
                    stationCanonicalizer = indexes.canonicalizer
                    savedRoutes = pinnedFirst(routeStore.routes(stations).map(::canonicalRoute))
                    // Route widgets of the first versions saved only the route id: they take its stations now.
                    RouteWidgetProvider.migrateLegacyConfigurations(getApplication(), savedRoutes)
                    recentRoutes = routeStore.recentRoutes(stations).map { recent ->
                        RecentRoute(canonicalStation(recent.origin), canonicalStation(recent.destination))
                    }
                    // Before the catalogue loads only remembered stations can decode the saved routes, so the
                    // route taken at construction may not be the stored one (a route imported from a backup next
                    // to one created here). The stored route is resolved again, unless the user chose a route
                    // while the catalogue was loading: that one stays, canonicalized.
                    activeRoute = RouteStateRules.activeRouteAfterCatalogue(
                        current = activeRoute, routes = savedRoutes, storedId = routeStore.activeRouteId(),
                        routeChosenInSession = routeChangedInSession, canonical = ::canonicalRoute
                    )
                    favouriteStations = stationPreferences.favourites(stations)
                    stationMonitors = personalTravelStore.stationMonitors(stations)
                }

                // A refresh already started for the route taken at construction (the refresh button, pressed
                // while the catalogue was loading) does not count when the stored route is another one: its
                // rows are not this route's.
                if (activeRoute.id != routeAtConstruction.id && !routeChangedInSession && refreshRequestId != 0L) {
                    clearSelectedService()
                    primarySnapshot = null
                    rebuildServicePresentations()
                    if (enableStartupEffects && hasActiveRoute) refresh()
                }
                // If no user-driven refresh started while the catalog was loading, perform
                // the one bootstrap refresh now (also when the catalog load failed/was empty).
                if (enableStartupEffects && refreshRequestId == 0L && hasActiveRoute) refresh()
                bootstrapCompleted = true
            }
        }
    }

    private fun canonicalRoute(route: SavedRoute): SavedRoute = route.copy(
        outwardOrigin = canonicalStation(route.outwardOrigin),
        outwardDestination = canonicalStation(route.outwardDestination)
    )

    private fun canonicalStation(station: Station): Station = stationCanonicalizer.canonicalize(station)

    val hasActiveRoute: Boolean get() = activeRoute.id != UNCONFIGURED_ROUTE_ID
    /** Date and time the route results answer: the planned search, or the current railway time. */
    val journeyQuery: JourneyQuery get() = journeyQueryState.value
    /** True when the results are the next departures from now rather than a planned date and time. */
    val journeyFollowsCurrentTime: Boolean get() = journeyQueryState.followsCurrentTime
    val hasReturnJourney: Boolean get() = returnJourneyQuery != null
    /**
     * True while the active route is a route searched in Pianifica that is not one of the saved routes: Partenze says
     * so, and the watch keeps the commuter's route instead of the search.
     */
    var activeRouteIsSearch by mutableStateOf(false)
        private set
    /** The planned return of a round trip, for the planner form opened with "Modifica". */
    val returnQuery: JourneyQuery? get() = returnJourneyQuery

    /**
     * The planner search the route results answer, encoded for the saved instance state ([RouteSearchRecord]); null
     * once the active route or its time changed elsewhere (Partenze, Per te, a backup).
     */
    var routeSearchRecord by mutableStateOf<String?>(null)
        private set
    /** False until the active route, its direction or its time is changed in this view model (not by the bootstrap). */
    var routeChangedInSession by mutableStateOf(false)
        private set

    /** Repeats a planner search after the process was recreated; false when its stations are no longer known. */
    fun repeatRouteSearch(encoded: String): Boolean {
        val record = RouteSearchRecord.decode(encoded) ?: return false
        val origin = stations.firstOrNull { it.id == record.originId } ?: return false
        val destination = stations.firstOrNull { it.id == record.destinationId } ?: return false
        val departure = record.departure
        val returnAt = record.returnAt
        return when {
            returnAt != null -> {
                val outbound = departure ?: java.time.LocalDateTime.of(JourneyQuery.now().serviceDate, JourneyQuery.now().departureTime)
                searchRoundTrip(
                    origin, destination, outbound.toLocalDate(), outbound.toLocalTime(), returnAt.toLocalDate(), returnAt.toLocalTime(),
                    outboundFollowsCurrentTime = departure == null
                )
            }
            departure != null -> searchRoute(origin, destination, departure.toLocalDate(), departure.toLocalTime())
            else -> searchRoute(origin, destination)
        }
    }
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
            val urban = if (sourcesSettingsStore.urbanStopsInSearch) urbanStopIndex.search(query, URBAN_SUGGESTIONS).map { it.station } else emptyList()
            val suggestions = withUrbanStops(autocomplete.suggest(query), urban, query)
                .ifEmpty { listOfNotNull(stationFromFreeText(query)) }
            withContext(Dispatchers.Main) {
                if (stationSuggestionRequestIds[fieldKey] == requestId) {
                    stationSuggestionResults = stationSuggestionResults + (fieldKey to suggestions)
                    stationSuggestionJobs.remove(fieldKey)
                }
            }
        }
    }

    /**
     * Stations whose name is the query first, then the metro and tram stops (marked M/T by the field), then the other
     * stations: "Duomo" offers the metro stop before "Duomo (stazione)"-like partial matches, "Milano Centrale" the station.
     */
    private fun withUrbanStops(stations: List<Station>, urban: List<Station>, query: String): List<Station> {
        if (urban.isEmpty()) return stations
        val normalized = StationSearchNormalizer.normalize(query)
        val (exact, others) = stations.partition { station -> StationSearchNormalizer.normalize(station.name) == normalized }
        return (exact + urban + others).distinctBy(Station::id)
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
        stationBoardRequestedAtMillis = refreshClock()
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
                        if (result.isComplete) lastGood.saveBoard(result.board)
                    }
                    is StationBoardAggregateResult.Unavailable -> {
                        // Keep the existing rows during a transient outage, never stale live claims.
                        stationBoard = stationBoard?.withoutExpiredObservations(java.time.Instant.now())
                        // Nothing on screen: today's last good board, as a saved copy of its timetable.
                        val saved = if (stationBoard == null) savedBoard(canonical, kind) else null
                        if (saved != null) stationBoard = saved
                        stationBoardRefreshState = RefreshState.ERROR
                        stationBoardMessages = listOfNotNull(
                            saved?.let { UiMessage(UiMessageKey.STATION_BOARD_SAVED_COPY, savedTime(it.fetchedAt.toEpochMilli())) },
                            UiMessage(UiMessageKey.STATION_BOARD_UNAVAILABLE)
                        )
                    }
                }
            }
        }
    }

    fun openStationBoard(station: Station, kind: BoardKind = BoardKind.DEPARTURES) = loadStationBoard(station, kind)

    private fun savedToday(epochMillis: Long): Boolean =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(RailTime.defaultZone).toLocalDate() == java.time.LocalDate.now(RailTime.defaultZone)

    private fun savedTime(epochMillis: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(RailTime.defaultZone).format(java.time.Instant.ofEpochMilli(epochMillis))

    /** The saved board of today without the rows that have already left. */
    private fun savedBoard(station: Station, kind: BoardKind): it.binario.app.data.repository.StationBoardAggregate? {
        val board = lastGood.board(station, kind)?.takeIf { savedToday(it.fetchedAt.toEpochMilli()) } ?: return null
        val from = java.time.Instant.now().minusSeconds(600)
        return board.copy(entries = board.entries.filter { !it.scheduledTime.toInstant().isBefore(from) }).takeIf { it.entries.isNotEmpty() }
    }

    fun refreshStationBoardIfIdle() {
        if (stationBoardJob?.isActive != true) loadStationBoard()
        if (selectedBoardEntry != null && stationBoardRunJob?.isActive != true) refreshStationBoardRun()
    }

    fun openStationBoardRun(entry: StationBoardEntry) {
        // Another run: nothing of the previous one stays under its name.
        if (selectedBoardEntry?.runId != entry.runId) {
            stationBoardRunDetails = null
            stationBoardRunLoadedAtMillis = null
        }
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
        // The stops on screen stay while they are read again, and if that fails: the list is not emptied (and its
        // scroll position lost) at every periodic refresh. Only their live claims go once they are too old.
        stationBoardRunDetails = retainedRunDetails(stationBoardRunDetails, stationBoardRunLoadedAtMillis, System.currentTimeMillis())
        stationBoardRunRefreshState = RefreshState.LOADING
        stationBoardRunJob = viewModelScope.launch {
            val details = stationBoardDetailsService.load(station, kind, entry)
            if (requestId != stationBoardRunRequestId) return@launch
            if (details != null) {
                stationBoardRunDetails = details
                stationBoardRunLoadedAtMillis = System.currentTimeMillis()
            } else {
                stationBoardRunDetails = retainedRunDetails(stationBoardRunDetails, stationBoardRunLoadedAtMillis, System.currentTimeMillis())
            }
            stationBoardRunRefreshState = if (details != null) RefreshState.SUCCESS else RefreshState.ERROR
        }
    }

    /**
     * Departures board of a stop of the open train, for its connections at arrival. Null when the
     * stop name resolves to no station; the requests start only when the flow is collected.
     */
    fun connectionBoard(stopName: String, from: java.time.Instant): Flow<StationBoardAggregateResult>? {
        if (!stationBoardService.hasProviders) return null
        val station = stationFromName(stopName) ?: return null
        return stationBoardService.observe(canonicalStation(station), BoardKind.DEPARTURES, from)
    }

    fun closeStationBoardRun() {
        ++stationBoardRunRequestId
        stationBoardRunJob?.cancel()
        selectedBoardEntry = null
        stationBoardRunDetails = null
        stationBoardRunLoadedAtMillis = null
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
        activeRouteIsSearch = false
        routeChangedInSession = true
        routeSearchRecord = null
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

    /**
     * Searches the direct trains of [origin] → [destination]. Without a date and a time ("Oggi · Adesso") the results
     * follow the current railway time, so Partenze keeps showing the next trains from now; a chosen date or time is a
     * planned search that stays at that moment until the user goes back to now ([followCurrentTime]).
     */
    fun searchRoute(
        origin: Station,
        destination: Station,
        serviceDate: LocalDate? = null,
        departureTime: LocalTime? = null
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
        activeRouteIsSearch = storedRoute == null
        activeRoute = storedRoute ?: SavedRoute(
            id = searchedRouteId(rememberedOrigin, rememberedDestination),
            name = "${rememberedOrigin.city} – ${rememberedDestination.city}",
            outwardOrigin = rememberedOrigin,
            outwardDestination = rememberedDestination
        )
        direction = Direction.OUTBOUND
        routeChangedInSession = true
        routeSearchRecord = RouteSearchRecord(
            rememberedOrigin.id,
            rememberedDestination.id,
            if (serviceDate == null && departureTime == null) null else journeyQueryValue(serviceDate, departureTime).requestedAt
        ).encode()
        if (serviceDate == null && departureTime == null) {
            journeyQueryState.resetForRouteActivation()
        } else {
            journeyQueryValue(serviceDate, departureTime).let { journeyQueryState.selectPlanned(it.serviceDate, it.departureTime) }
        }
        storeDirectionOfStoredRoute()
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
        returnTime: LocalTime,
        /** The outbound leg is "Adesso": it follows the current time ([departureDate]/[departureTime] are now). */
        outboundFollowsCurrentTime: Boolean = false
    ): Boolean {
        if (!PlannerDateTime.isReturnAfterDeparture(departureDate, departureTime, returnDate, returnTime)) {
            refreshMessage = UiMessage(UiMessageKey.ROUND_TRIP_RETURN_MUST_FOLLOW_DEPARTURE)
            refreshState = RefreshState.ERROR
            return false
        }
        val searched = if (outboundFollowsCurrentTime) searchRoute(origin, destination)
        else searchRoute(origin, destination, departureDate, departureTime)
        if (!searched) return false

        routeSearchRecord = RouteSearchRecord.decode(routeSearchRecord)?.copy(returnAt = java.time.LocalDateTime.of(returnDate, returnTime))?.encode()
        returnJourneyQuery = JourneyQuery(returnDate, returnTime)
        refreshReturn()
        return true
    }

    fun switchDirection() {
        clearReturnJourneyState()
        // È ancora la stessa consultazione: una ricerca pianificata deve poter
        // mostrare anche la direzione opposta alla data/ora scelta dall'utente.
        routeChangedInSession = true
        routeSearchRecord = null
        direction = if (direction == Direction.OUTBOUND) Direction.RETURN else Direction.OUTBOUND
        storeDirectionOfStoredRoute()
        clearSelectedService()
        primarySnapshot = null
        rebuildServicePresentations()
        operatorFilter = null
        refresh()
    }

    /** The direction is stored only for the route the next start opens, never for a search ([RouteStateRules.storesDirection]). */
    private fun storeDirectionOfStoredRoute() {
        if (RouteStateRules.storesDirection(activeRoute, savedRoutes, routeStore.activeRouteId())) routeStore.saveDirection(direction)
    }

    /**
     * "Torna ad adesso" of Partenze: the board (and the route results) show the next trains from the current railway
     * time again instead of a planned date and time. The planned return leg of a round trip ends with it.
     */
    fun followCurrentTime() {
        if (journeyQueryState.followsCurrentTime) return
        routeChangedInSession = true
        routeSearchRecord = RouteSearchRecord.decode(routeSearchRecord)?.copy(departure = null, returnAt = null)?.encode()
        clearReturnJourneyState()
        journeyQueryState.resetForRouteActivation()
        clearSelectedService()
        primarySnapshot = null
        rebuildServicePresentations()
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
        trainNumberRequestedAtMillis = refreshClock()
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
                        notePunctuality(listOf(match.service), match.snapshot)
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
        notePunctuality(listOf(match.service), match.snapshot)
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
        activeRouteIsSearch = false
        routeChangedInSession = true
        routeSearchRecord = null
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
        activeRoute = RouteStateRules.activeRouteAfterPin(activeRoute, savedRoutes)
    }

    fun canMoveRoute(route: SavedRoute, delta: Int): Boolean =
        SavedRouteOrdering.canMove(savedRoutes, route.id, delta)

    fun moveRoute(route: SavedRoute, delta: Int) {
        val updated = SavedRouteOrdering.move(savedRoutes, route.id, delta)
        if (updated == savedRoutes) return
        savedRoutes = updated
        routeStore.saveRoutes(savedRoutes)
    }

    /** "Annulla" after [removeRoute]: the route back at [index], active again when it was. */
    fun restoreRoute(route: SavedRoute, index: Int, reactivate: Boolean) {
        if (savedRoutes.any { it.id == route.id }) return
        savedRoutes = pinnedFirst(savedRoutes.toMutableList().apply { add(index.coerceIn(0, size), route) })
        routeStore.saveRoutes(savedRoutes)
        if (reactivate) activateRoute(route)
    }

    fun removeRoute(route: SavedRoute) {
        savedRoutes = savedRoutes.filterNot { it.id == route.id }
        routeStore.saveRoutes(savedRoutes)
        if (activeRoute.id == route.id) {
            clearReturnJourneyState()
            journeyQueryState.resetForRouteActivation()
            activeRouteIsSearch = false
            routeChangedInSession = true
            routeSearchRecord = null
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
        expireServicePages()
        val routeAtRequest = activeRoute
        val directionAtRequest = direction
        val queryAtRequest = journeyQueryState.value
        // Widgets and Wear present their snapshot as the next trains of the route; a planned
        // search for another date or time must stay inside the app, and the watch never shows a route
        // only searched in Pianifica as the commuter's route.
        val publishesCurrentDepartures = journeyQueryState.followsCurrentTime
        val publishesToWear = publishesCurrentDepartures && !activeRouteIsSearch
        val requestId = ++refreshRequestId
        routeRequestedAtMillis = refreshClock()
        viewModelScope.launch {
            val result = observeRouteShowingProgress(routeAtRequest, directionAtRequest, queryAtRequest, { requestId == refreshRequestId }) { early ->
                primarySnapshot = early
                primarySnapshotIsCurrent = publishesCurrentDepartures
                rebuildServicePresentations()
            }
            if (requestId != refreshRequestId) return@launch
            when (result) {
                is DataResult.Success -> {
                    primarySnapshot = result.snapshot
                    primarySnapshotIsCurrent = publishesToWear
                    rebuildServicePresentations()
                    reconcileSelectedService(result.snapshot, directionAtRequest)
                    notePunctuality(result.snapshot.services.filter(::isMonitoring), result.snapshot)
                    retainAvailableOperatorFilter()
                    cache.save(result.snapshot)
                    if (publishesCurrentDepartures) lastGood.saveRoute(result.snapshot)
                    if (publishesCurrentDepartures) {
                        RouteWidgetProvider.saveSnapshotForMatchingRoutes(getApplication(), result.snapshot)
                        RouteWidgetProvider.refreshAll(getApplication())
                    }
                    if (publishesToWear && integrationSettings.wearSnapshotSharingEnabled) {
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
                    // Nothing of this route on screen: today's last good result without the trains already gone,
                    // marked as a saved copy (timetable only).
                    if (publishesCurrentDepartures && primarySnapshot?.let { it.route.id == routeAtRequest.id && it.direction == directionAtRequest } != true) {
                        lastGood.route(routeAtRequest, directionAtRequest)?.let { RouteStateRules.savedRouteCopy(it, java.time.Instant.now()) }?.let { saved ->
                            primarySnapshot = saved
                            primarySnapshotIsCurrent = false
                            rebuildServicePresentations()
                        }
                    }
                    if (publishesCurrentDepartures) {
                        RouteWidgetProvider.markMatchingRouteDirectionUnavailable(getApplication(), routeAtRequest, directionAtRequest, result.reason.uiMessage())
                        RouteWidgetProvider.refreshAll(getApplication())
                    }
                    if (publishesToWear && integrationSettings.wearSnapshotSharingEnabled) {
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

    /**
     * The periodic refresh of the screen on show ([target]), run when the screen appears, when the app returns to the
     * foreground and then at every wait it returns (milliseconds). The data is asked for again only when its last
     * request started 30 seconds ago or more: a return after a long pause refreshes at once instead of showing old
     * delays and platforms for 30 seconds, while moving between screens never asks more often than every 30 seconds.
     */
    internal fun refreshIfDue(target: PeriodicRefresh, nowMillis: Long = refreshClock()): Long {
        val requestedAt = when (target) {
            PeriodicRefresh.JOURNEY -> return LiveRefreshCadence.INTERVAL_MILLIS
            PeriodicRefresh.STATION_BOARD -> stationBoardRequestedAtMillis
            // Nothing was searched in this process yet (a restored screen searches by itself).
            PeriodicRefresh.TRAIN_NUMBER -> trainNumberRequestedAtMillis ?: return LiveRefreshCadence.INTERVAL_MILLIS
            PeriodicRefresh.ROUTE, PeriodicRefresh.ROUTE_AND_JOURNEYS -> when {
                selectedGlobalTrainId != null -> trainNumberRequestedAtMillis
                // The first request belongs to the bootstrap, after the catalogue has canonicalized the saved route.
                !bootstrapCompleted -> return LiveRefreshCadence.INTERVAL_MILLIS
                selectedServiceDirection == Direction.RETURN && returnJourneyQuery != null -> returnRequestedAtMillis
                else -> routeRequestedAtMillis
            }
        }
        if (!LiveRefreshCadence.isDue(requestedAt, nowMillis)) return LiveRefreshCadence.waitMillis(requestedAt, nowMillis)
        when (target) {
            PeriodicRefresh.STATION_BOARD -> refreshStationBoardIfIdle()
            PeriodicRefresh.TRAIN_NUMBER -> searchTrainNumber(refresh = true)
            PeriodicRefresh.ROUTE, PeriodicRefresh.ROUTE_AND_JOURNEYS -> refreshLiveIfIdle()
            PeriodicRefresh.JOURNEY -> Unit
        }
        return LiveRefreshCadence.INTERVAL_MILLIS
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
        returnRequestedAtMillis = refreshClock()
        viewModelScope.launch {
            val result = observeRouteShowingProgress(routeAtRequest, Direction.RETURN, queryAtRequest,
                { requestId == returnRefreshRequestId && returnJourneyQuery == queryAtRequest }) { early ->
                returnSnapshot = early
                rebuildServicePresentations()
            }
            if (requestId != returnRefreshRequestId || returnJourneyQuery != queryAtRequest) return@launch
            when (result) {
                is DataResult.Success -> {
                    returnSnapshot = result.snapshot
                    rebuildServicePresentations()
                    reconcileSelectedService(result.snapshot, Direction.RETURN)
                    notePunctuality(result.snapshot.services.filter(::isMonitoring), result.snapshot)
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

    /**
     * The route result, as [TrainDataSource.observeRoute] improves it: every earlier emission (the offline rows before
     * their optional realtime) is shown at once through [showEarly] while [stillCurrent]; the last one is returned and
     * handled as before (caches, widgets, Wear, punctuality). Which sources answer, and in which order, is unchanged.
     */
    private suspend fun observeRouteShowingProgress(
        route: SavedRoute,
        direction: Direction,
        query: JourneyQuery,
        stillCurrent: () -> Boolean,
        showEarly: (RouteSnapshot) -> Unit
    ): DataResult {
        var last: DataResult? = null
        dataSource.observeRoute(route, direction, query).collect { result ->
            last = result
            if (result is DataResult.Success && stillCurrent()) showEarly(result.snapshot)
        }
        return last ?: DataResult.Failure("Nessun risultato dalla fonte.")
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
        pagedServiceJob?.cancel()
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
            notePunctuality(listOf(refreshed), updated)
        } ?: run {
            // A train of «Più presto» / «Più tardi»: the primary search never held it, so its own window is asked again.
            if (isPagedService(selected)) refreshSelectedPagedService(updatedDirection)
            else invalidateSelectedService(updatedDirection)
        }
    }

    private fun isPagedService(service: TrainService): Boolean =
        currentServicePages()?.snapshots.orEmpty().any { page -> page.services.any { it.id == service.id } }

    /**
     * The trains of «Più presto» / «Più tardi» are read once, when their window is loaded: at every refresh the windows
     * past their expiry keep their trains as timetable rows, with no delay or platform shown as current.
     */
    private fun expireServicePages(nowMillis: Long = System.currentTimeMillis()) {
        val pages = servicePages
        val updated = pages.snapshots.map { pageWithoutExpiredObservations(it, nowMillis) }
        if (updated.indices.any { updated[it] !== pages.snapshots[it] }) servicePages = pages.copy(snapshots = updated)
    }

    /**
     * Reads again the open train of a «Più presto» / «Più tardi» window, from the same route sources and from its own
     * departure: one request per refresh, only while that detail is open. The answer replaces the train in its window;
     * without an answer the detail keeps what it had, with no live claim once that has expired.
     */
    private fun refreshSelectedPagedService(directionAtRequest: Direction) {
        val selected = selectedService ?: return
        val key = servicePagingKey()
        val from = ResultsPaging.departureOf(selected, journeyQueryState.value.serviceDate) ?: return
        val routeAtRequest = activeRoute
        pagedServiceJob?.cancel()
        pagedServiceJob = viewModelScope.launch {
            val answer = routePage(routeAtRequest, directionAtRequest, from)
            val open = selectedService
            val pages = currentServicePages()?.takeIf { it.key == key }
            if (open == null || pages == null || open.id != selected.id || selectedServiceDirection != directionAtRequest) return@launch
            val refreshed = answer?.let { findRefreshedService(open, it.services) }
            if (answer == null || refreshed == null) {
                invalidateSelectedService(directionAtRequest, onlyIfExpired = true)
                return@launch
            }
            // The train keeps the day of its window, so one of tomorrow morning stays after this evening's.
            val train = if (refreshed.scheduledDepartureDateTime != null) refreshed else refreshed.copy(scheduledDepartureDateTime = from)
            val page = answer.copy(services = listOf(train))
            servicePages = pages.copy(snapshots = pagesWithRefreshedService(pages.snapshots, open.id, page))
            selectedService = train
            selectedServiceSnapshot = page
            notePunctuality(listOf(train), page)
        }
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
        val page = if (belongsToPrimary || belongsToReturn) null else currentServicePages()?.snapshots?.firstOrNull { snapshot ->
            snapshot.services.any { candidate -> candidate === service || candidate.id == service.id }
        }
        selectedServiceSnapshot = when {
            page != null -> page
            resolvedDirection == Direction.RETURN && returnJourneyQuery != null -> returnSnapshot
            else -> primarySnapshot
        }
        notePunctuality(listOf(service), selectedServiceSnapshot)
        // A window read a while ago: the detail asks for its train again instead of waiting for the next refresh.
        if (page != null && (page.freshness == Freshness.STALE || page.isExpired)) refreshSelectedPagedService(resolvedDirection)
    }

    /** Statistics of the open train from the local history; null below two observations. */
    fun punctualityStats(service: TrainService): PunctualityStats? = PunctualityHistory.stats(punctualityHistory, service.number)

    fun clearPunctualityHistory(trainNumber: String) {
        punctualityHistory = punctualityStore.removeTrain(punctualityHistory, trainNumber)
    }

    /**
     * Records operational observations in the local punctuality history: the train whose detail is
     * open, and the monitored trains of a refreshed snapshot. Scheduled-only, expired and
     * demonstration data never count; an unchanged observation is not written again.
     */
    private fun notePunctuality(services: List<TrainService>, snapshot: RouteSnapshot?) {
        if (services.isEmpty() || snapshot?.confidence == DataConfidence.DEMONSTRATION || snapshot?.isExpired == true) return
        val today = JourneyQuery.now().serviceDate
        val observations = services.mapNotNull { PunctualityHistory.observationOf(it, today) }
            .filterNot { it in punctualityHistory }
        if (observations.isEmpty()) return
        punctualityHistory = punctualityStore.record(punctualityHistory, observations)
    }

    fun closeService() {
        clearSelectedService()
    }

    fun currentServices(): List<TrainService> = visiblePrimaryServices

    fun currentReturnServices(): List<TrainService> = visibleReturnServices

    /**
     * The direct trains «Più presto» and «Più tardi» loaded for the search on screen, with the operator and changes
     * filters of the list and without the trains the primary search now holds itself.
     */
    fun pagedServices(): List<TrainService> {
        val pages = currentServicePages() ?: return emptyList()
        if (pages.snapshots.isEmpty()) return emptyList()
        val fresh = ResultsPaging.newServices(primarySnapshot?.services.orEmpty(), pages.services, journeyQueryState.value.serviceDate)
        return ServicePresentation.fromServices(fresh).filtered(operator = operatorFilter, numberQuery = "", journeyChangeFilter = journeyChangeFilter)
    }

    fun earlierServicesPage(): ResultsPageState = currentServicePages()?.earlier ?: ResultsPageState()

    fun laterServicesPage(): ResultsPageState = currentServicePages()?.later ?: ResultsPageState()

    /** «Più tardi»: the direct trains of the window from the last departure shown, from the same sources as the search. */
    fun loadLaterServices() = loadServicePage(later = true)

    /** «Più presto»: the direct trains leaving before the first departure shown ([ResultsPaging.earlier]). */
    fun loadEarlierServices() = loadServicePage(later = false)

    /** The search the pages belong to: route, direction and planned moment ("now" while it follows the clock). */
    private fun servicePagingKey(): String = listOf(
        activeRoute.id,
        direction.name,
        if (journeyQueryState.followsCurrentTime) "now" else journeyQueryState.value.requestedAt.toString()
    ).joinToString("|")

    private fun currentServicePages(): ServicePages? = servicePages.takeIf { it.key == servicePagingKey() }

    private fun loadServicePage(later: Boolean) {
        if (!hasActiveRoute) return
        val key = servicePagingKey()
        val pages = currentServicePages() ?: ServicePages(key)
        if (pages.earlier.loading || pages.later.loading) return
        val serviceDate = journeyQueryState.value.serviceDate
        val departures = (primarySnapshot?.services.orEmpty() + pages.services).mapNotNull { ResultsPaging.departureOf(it, serviceDate) }
        val anchor = (if (later) departures.maxOrNull() else departures.minOrNull()) ?: return
        val routeAtRequest = activeRoute
        val directionAtRequest = direction
        servicePages = if (later) pages.copy(later = ResultsPageState.LOADING) else pages.copy(earlier = ResultsPageState.LOADING)
        viewModelScope.launch {
            var answered: RouteSnapshot? = null
            val load: suspend (java.time.LocalDateTime) -> List<TrainService>? = { from ->
                routePage(routeAtRequest, directionAtRequest, from)?.also { answered = it }?.services?.map { service ->
                    // Each page keeps its own day, so a train of the next morning stays after the evening ones.
                    if (service.scheduledDepartureDateTime != null) service
                    else service.copy(scheduledDepartureDateTime = ResultsPaging.departureOf(service, from.toLocalDate()))
                }
            }
            val departureOf = { service: TrainService -> ResultsPaging.departureOf(service, serviceDate) }
            val loaded = if (later) ResultsPaging.later(anchor, departureOf, load)
            else ResultsPaging.earlier(anchor, departureOf, notBefore = serviceDate.atStartOfDay(), load = load)
            val current = currentServicePages()?.takeIf { it.key == key } ?: return@launch
            val fresh = ResultsPaging.newServices(primarySnapshot?.services.orEmpty() + current.services, loaded.orEmpty(), serviceDate)
            val page = answered?.takeIf { fresh.isNotEmpty() }?.copy(services = fresh)
            val state = ResultsPageState(nothingNew = page == null)
            servicePages = current.copy(
                snapshots = current.snapshots + listOfNotNull(page),
                earlier = if (later) current.earlier else state,
                later = if (later) state else current.later
            )
        }
    }

    /** The last answer of the route sources for [route] from [from], or null when they found nothing. */
    private suspend fun routePage(route: SavedRoute, direction: Direction, from: java.time.LocalDateTime): RouteSnapshot? {
        var last: DataResult? = null
        dataSource.observeRoute(route, direction, JourneyQuery(from.toLocalDate(), from.toLocalTime().withSecond(0).withNano(0))).collect { last = it }
        return (last as? DataResult.Success)?.snapshot
    }

    fun homeServices(): List<TrainService> = primaryServicePresentation.ordered

    fun quickServices(): List<TrainService> = primaryServicePresentation.ordered.take(2)

    private fun rebuildServicePresentations() {
        // A new search (no primary result yet) starts without the pages of the previous one.
        if (primarySnapshot == null) servicePages = ServicePages.EMPTY
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

    /** A recent route searched again from now (Per te › Recenti). */
    fun useRecentRoute(recent: RecentRoute): Boolean = searchRoute(recent.origin, recent.destination)

    fun isMonitoring(service: TrainService): Boolean = monitoredServices.any { it.number.equals(service.number, ignoreCase = true) }

    /** The heart of the detail; [FavouriteToggle.Full] leaves «I miei treni» as it is. */
    fun toggleServiceMonitoring(service: TrainService): FavouriteToggle {
        val outcome = routeStore.toggleServiceMonitor(service)
        monitoredServices = outcome.monitors
        if (outcome !is FavouriteToggle.Full) {
            TrainsWidgetProvider.onFavouritesChanged(getApplication(), lookUpNewTrains = outcome is FavouriteToggle.Added)
        }
        return outcome
    }

    fun removeServiceMonitoring(number: String) {
        monitoredServices = routeStore.removeServiceMonitor(number)
        TrainsWidgetProvider.onFavouritesChanged(getApplication(), lookUpNewTrains = false)
    }

    /** Custom name of a favourite train ("Il mio treno delle 7:42"); a blank name restores the train label. */
    fun renameServiceMonitor(number: String, customName: String) {
        monitoredServices = routeStore.renameServiceMonitor(number, customName)
        TrainsWidgetProvider.onFavouritesChanged(getApplication(), lookUpNewTrains = false)
    }

    fun replaceServiceMonitors(monitors: List<ServiceMonitor>) {
        monitoredServices = routeStore.replaceServiceMonitors(monitors)
        TrainsWidgetProvider.onFavouritesChanged(getApplication(), lookUpNewTrains = true)
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
        // The ongoing notification follows one trip: another train ends the previous one.
        it.binario.app.notifications.FollowedTripScheduler.syncWithActiveTrip(getApplication(), service.number, ending = false)
    }

    fun advanceActiveTrip() {
        activeTrip = personalTravelStore.advanceTrip()
    }

    fun endActiveTrip() {
        activeTrip?.let { trip -> it.binario.app.notifications.FollowedTripScheduler.syncWithActiveTrip(getApplication(), trip.trainNumber, ending = true) }
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
                punctualityHistory = punctualityHistory,
                savedTimetables = SavedTimetableStore.all(getApplication()),
                travelPreferences = TravelPreferencesRepository.current(getApplication()),
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
            activeRouteIsSearch = false
            routeChangedInSession = true
            routeSearchRecord = null
            activeRoute = savedRoutes.firstOrNull() ?: unconfiguredRoute()
            clearSelectedService()
            primarySnapshot = null
            operatorFilter = null
            if (hasActiveRoute) routeStore.saveActiveRoute(activeRoute.id) else routeStore.clearActiveRoute()
            stationPreferences.replaceFavourites(backup.favouriteStationIds)
            favouriteStations = stationPreferences.favourites(stations)
            replaceServiceMonitors(backup.monitoredServices)
            replaceStationMonitors(backup.stationMonitors)
            // The alarms of the reminders the file does not contain are cancelled with them.
            commuterAlerts = CommuterAlertScheduler.replaceAll(getApplication(), commuterAlertStore, backup.commuterAlerts)
            updateMobilityPreferences(backup.mobilityPreferences)
            backup.punctualityHistory?.let { punctualityHistory = punctualityStore.replace(it) }
            backup.savedTimetables?.let { SavedTimetableStore.replace(getApplication(), it) }
            backup.travelPreferences?.let { TravelPreferencesRepository.update(getApplication(), it) }
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

    /**
     * Stations only the backup knows must be remembered, or its routes vanish at the next start. The catalogue
     * stations of its routes are remembered too, like those of a route created here: the saved routes are decoded
     * before the catalogue has loaded, and the active one must be among them.
     */
    private fun restoreBackupStations(backup: LocalDataBackup) {
        val routeStations = backup.routes.flatMap { listOf(it.outwardOrigin, it.outwardDestination) }
        val remembered = (backup.stations + routeStations).distinctBy { it.id }
        if (remembered.isEmpty()) return
        val retained = retainedStationIds() + routeStations.map { it.id } +
            backup.favouriteStationIds + backup.stationMonitors.map { it.stationId }
        addKnownStations(customStationStore.rememberAll(remembered, retained))
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

internal fun ProviderWarning.stationBoardWarningKey(): UiMessageKey = when (this) {
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

/** The stops of a board run keep their live values this long after they were read, when they cannot be read again. */
internal const val STATION_RUN_DETAILS_MAX_AGE_MILLIS = 120_000L

/**
 * The details of a board run kept on screen while they are read again or after a reading failed: the same stops, so
 * the list neither empties nor jumps. Read more than [STATION_RUN_DETAILS_MAX_AGE_MILLIS] ago (or at an unknown time)
 * they lose their live claims: no stop is shown as verified and the entry drops its expired observations.
 */
internal fun retainedRunDetails(previous: StationBoardRunDetails?, loadedAtMillis: Long?, nowMillis: Long): StationBoardRunDetails? {
    if (previous == null) return null
    val entry = previous.entry.withoutExpiredObservations(java.time.Instant.ofEpochMilli(nowMillis))
    val current = loadedAtMillis != null && nowMillis >= loadedAtMillis && nowMillis - loadedAtMillis <= STATION_RUN_DETAILS_MAX_AGE_MILLIS
    return if (current) previous.copy(entry = entry)
    else previous.copy(entry = entry, stops = previous.stops.map { it.copy(operationalStatusVerified = false) })
}

/** Retain the last known times for context without claiming a current zero-minute delay. */
internal fun serviceWithoutCurrentObservation(service: TrainService): TrainService = service.copy(
    operationalStatusVerified = false,
    officialDelayMinutes = null,
    stops = service.stops.map { it.copy(operationalStatusVerified = false) }
)

/**
 * A «Più presto» / «Più tardi» window at [nowMillis]: unchanged while its data is current; past its expiry its trains
 * stay as timetable rows (no verified status, no official delay) and the window is marked stale.
 */
internal fun pageWithoutExpiredObservations(page: RouteSnapshot, nowMillis: Long): RouteSnapshot =
    if (nowMillis <= page.expiresAtEpochMillis || page.freshness == Freshness.STALE) page
    else page.copy(services = page.services.map(::serviceWithoutCurrentObservation), freshness = Freshness.STALE)

/**
 * The windows after the train [serviceId] was read again: it leaves the window that held it (a window left empty goes)
 * and [refreshed], the new answer with that train alone, joins them. The other trains keep their own window and age.
 */
internal fun pagesWithRefreshedService(pages: List<RouteSnapshot>, serviceId: String, refreshed: RouteSnapshot): List<RouteSnapshot> =
    pages.mapNotNull { page ->
        if (page.services.none { it.id == serviceId }) page
        else page.copy(services = page.services.filterNot { it.id == serviceId }).takeIf { it.services.isNotEmpty() }
    } + refreshed

/** The windows «Più presto» / «Più tardi» loaded for the search [key]: their snapshots (new trains only) and states. */
private data class ServicePages(
    val key: String,
    val snapshots: List<RouteSnapshot> = emptyList(),
    val earlier: ResultsPageState = ResultsPageState(),
    val later: ResultsPageState = ResultsPageState()
) {
    val services: List<TrainService> get() = snapshots.flatMap { it.services }

    companion object {
        val EMPTY = ServicePages("")
    }
}

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

/** The planned query of a search with a date and/or a time; a missing one is today or now. */
private fun journeyQueryValue(serviceDate: LocalDate?, departureTime: LocalTime?): JourneyQuery = JourneyQuery(
    serviceDate ?: LocalDate.now(RailTime.defaultZone),
    departureTime ?: LocalTime.now(RailTime.defaultZone).withSecond(0).withNano(0)
)

private class RouteStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_routes_v3", Context.MODE_PRIVATE)
    private val favourites = FavouriteTrainStore(context)

    fun routes(stations: List<Station>): List<SavedRoute> {
        val stored = preferences.getString(KEY_ROUTES, null)
            ?.split(";")
            ?.mapNotNull { decodeRoute(it, stations) }
            .orEmpty()
        return stored.filterNot { route -> route.id.startsWith("default-") }
    }

    fun activeRouteId(): String? = preferences.getString(KEY_ACTIVE_ROUTE, null)

    fun activeRoute(routes: List<SavedRoute>): SavedRoute = RouteStateRules.storedActiveRoute(routes, activeRouteId())

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

    // The favourite ("monitored") trains live in the same preferences; FavouriteTrainStore owns their format.
    fun monitoredServices(): List<ServiceMonitor> = favourites.all()

    fun toggleServiceMonitor(service: TrainService): FavouriteToggle = favourites.toggle(service)

    fun removeServiceMonitor(number: String): List<ServiceMonitor> = favourites.remove(number)

    fun renameServiceMonitor(number: String, customName: String): List<ServiceMonitor> = favourites.rename(number, customName)

    fun replaceServiceMonitors(monitors: List<ServiceMonitor>): List<ServiceMonitor> = favourites.replace(monitors)

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
