package it.binario.app

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.RailDataRepository
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.RouteSnapshotCache
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.orderServicesForDisplay
import it.binario.app.widget.RouteWidgetProvider
import it.binario.app.widget.WidgetStateStore
import it.binario.app.wear.WearSnapshotPublisher
import kotlinx.coroutines.launch
import java.util.UUID

enum class RefreshState(val accessibleLabel: String) {
    IDLE("Pronta"),
    LOADING("Aggiornamento in corso"),
    SUCCESS("Dati aggiornati"),
    ERROR("Aggiornamento non riuscito")
}

data class RecentRoute(val origin: Station, val destination: Station) {
    val label: String get() = "${origin.name} → ${destination.name}"
}

data class ServiceMonitor(val number: String, val label: String)

class TrainViewModel(application: Application) : AndroidViewModel(application) {
    private val routeStore = RouteStore(application)
    private val cache = RouteSnapshotCache(application)
    private val integrationStore = IntegrationSettingsStore(application)
    private val stationPreferences = StationPreferences(application)
    private val commuterAlertStore = CommuterAlertStore(application)
    private val personalTravelStore = PersonalTravelStore(application)
    private val mobilityPreferencesStore = MobilityPreferencesStore(application)
    private val dataSource: TrainDataSource = RailDataRepository()

    val stations = listOf(
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
        Station("cassino", "Cassino", "Lazio", "Lazio"),
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
    )

    var favouriteStations by mutableStateOf(stationPreferences.favourites(stations))
        private set
    var nearbyStations by mutableStateOf<List<NearbyStation>>(emptyList())
        private set

    var savedRoutes by mutableStateOf(pinnedFirst(routeStore.routes(stations)))
        private set
    var recentRoutes by mutableStateOf(routeStore.recentRoutes(stations))
        private set
    var monitoredServices by mutableStateOf(routeStore.monitoredServices())
        private set
    var activeRoute by mutableStateOf(routeStore.activeRoute(stations, savedRoutes))
        private set
    var direction by mutableStateOf(WidgetStateStore.read(application)?.direction ?: routeStore.direction())
        private set
    var snapshot by mutableStateOf<RouteSnapshot?>(null)
        private set
    var refreshState by mutableStateOf(RefreshState.IDLE)
        private set
    var refreshMessage by mutableStateOf<String?>(null)
        private set
    var selectedService by mutableStateOf<TrainService?>(null)
        private set
    var operatorFilter by mutableStateOf<RailOperator?>(null)
        private set
    var journeyChangeFilter by mutableStateOf(JourneyChangeFilter.ANY)
        private set
    var trainNumberQuery by mutableStateOf("")
        private set
    var integrationSettings by mutableStateOf(integrationStore.read())
        private set
    var commuterAlerts by mutableStateOf(commuterAlertStore.alerts())
        private set
    var stationMonitors by mutableStateOf(personalTravelStore.stationMonitors(stations))
        private set
    var activeTrip by mutableStateOf(personalTravelStore.activeTrip())
        private set
    var widgetSurface by mutableStateOf(personalTravelStore.widgetSurface())
        private set
    var mobilityPreferences by mutableStateOf(mobilityPreferencesStore.read())
        private set

    init {
        refresh()
    }

    fun searchStations(query: String): List<Station> = if (query.isBlank()) emptyList() else {
        stations.filter { "${it.name} ${it.city} ${it.region.orEmpty()}".contains(query.trim(), ignoreCase = true) }.take(6)
    }

    fun stationFromName(name: String): Station? = stations.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun isStationFavourite(station: Station): Boolean = stationPreferences.isFavourite(station)

    fun toggleStationFavourite(station: Station) {
        stationPreferences.toggle(station)
        favouriteStations = stationPreferences.favourites(stations)
    }

    /** Riceve una posizione puntuale dalla UI; non viene mai persistita. */
    fun updateNearbyStations(latitude: Double, longitude: Double) {
        nearbyStations = StationCoordinates.nearby(stations, latitude, longitude).take(8)
    }

    fun isStationMonitored(station: Station): Boolean = stationMonitors.any { it.stationId == station.id }

    fun toggleStationMonitor(station: Station, mode: StationMonitorMode = StationMonitorMode.BOTH) {
        stationMonitors = personalTravelStore.toggleStationMonitor(station, mode, stations)
    }

    fun removeStationMonitor(stationId: String) {
        stationMonitors = personalTravelStore.removeStationMonitor(stationId, stations)
    }

    fun replaceStationMonitors(monitors: List<StationMonitor>) {
        stationMonitors = personalTravelStore.replaceStationMonitors(monitors, stations)
    }

    fun activateRoute(route: SavedRoute) {
        activeRoute = route
        routeStore.saveActiveRoute(route.id)
        selectedService = null
        refresh()
    }

    fun searchRoute(origin: Station, destination: Station): Boolean {
        if (origin == destination) {
            refreshMessage = "Scegli due stazioni diverse per la tratta."
            refreshState = RefreshState.ERROR
            return false
        }
        val storedRoute = savedRoutes.firstOrNull {
            it.outwardOrigin.id == origin.id && it.outwardDestination.id == destination.id
        }
        activeRoute = storedRoute ?: SavedRoute(
            id = "search-${origin.id}-${destination.id}",
            name = "${origin.city} – ${destination.city}",
            outwardOrigin = origin,
            outwardDestination = destination
        )
        direction = Direction.OUTBOUND
        routeStore.saveDirection(direction)
        recentRoutes = routeStore.recordRecent(origin, destination, stations)
        selectedService = null
        trainNumberQuery = ""
        refresh()
        return true
    }

    fun switchDirection() {
        direction = if (direction == Direction.OUTBOUND) Direction.RETURN else Direction.OUTBOUND
        routeStore.saveDirection(direction)
        selectedService = null
        refresh()
    }

    fun reconcileWidgetState() {
        val widgetState = WidgetStateStore.read(getApplication()) ?: return
        val matchingRoute = savedRoutes.firstOrNull { it.id == widgetState.route.id } ?: return
        val routeChanged = activeRoute.id != matchingRoute.id
        val directionChanged = direction != widgetState.direction
        if (routeChanged || directionChanged) {
            activeRoute = matchingRoute
            direction = widgetState.direction
            routeStore.saveActiveRoute(matchingRoute.id)
            routeStore.saveDirection(direction)
            selectedService = null
            refresh()
        }
    }

    fun chooseDirection(newDirection: Direction) {
        if (newDirection != direction) switchDirection()
    }

    fun selectOperatorFilter(operator: RailOperator?) {
        operatorFilter = operator
    }

    fun selectJourneyChangeFilter(filter: JourneyChangeFilter) {
        journeyChangeFilter = filter
    }

    fun updateTrainNumberQuery(query: String) {
        trainNumberQuery = query.filter { it.isLetterOrDigit() || it.isWhitespace() }.take(12)
    }

    fun clearSearchFilters() {
        operatorFilter = null
        journeyChangeFilter = JourneyChangeFilter.ANY
        trainNumberQuery = ""
    }

    fun createRoute(origin: Station, destination: Station, customName: String? = null): Boolean {
        if (origin == destination) {
            refreshMessage = "Scegli due stazioni diverse per la tratta."
            refreshState = RefreshState.ERROR
            return false
        }
        val defaultName = "${origin.city} – ${destination.city}"
        val route = SavedRoute(
            id = UUID.randomUUID().toString(),
            name = customName?.trim()?.takeIf { it.isNotBlank() } ?: defaultName,
            outwardOrigin = origin,
            outwardDestination = destination
        )
        savedRoutes = pinnedFirst(listOf(route) + savedRoutes.filterNot { it.outwardOrigin == origin && it.outwardDestination == destination })
        routeStore.saveRoutes(savedRoutes)
        activeRoute = route
        routeStore.saveActiveRoute(route.id)
        direction = Direction.OUTBOUND
        routeStore.saveDirection(direction)
        selectedService = null
        refresh()
        return true
    }

    fun toggleRoutePin(route: SavedRoute) {
        val updated = savedRoutes.map {
            if (it.id == route.id) it.copy(isPinned = !it.isPinned) else it
        }
        savedRoutes = pinnedFirst(updated)
        routeStore.saveRoutes(savedRoutes)
        activeRoute = savedRoutes.firstOrNull { it.id == activeRoute.id } ?: savedRoutes.first()
    }

    fun removeRoute(route: SavedRoute) {
        if (savedRoutes.size <= 1) {
            refreshMessage = "Conserva almeno una tratta oppure creane prima una nuova."
            refreshState = RefreshState.ERROR
            return
        }
        savedRoutes = savedRoutes.filterNot { it.id == route.id }
        routeStore.saveRoutes(savedRoutes)
        if (activeRoute.id == route.id) {
            activeRoute = savedRoutes.first()
            routeStore.saveActiveRoute(activeRoute.id)
            selectedService = null
            refresh()
        }
    }

    fun refresh() {
        refreshState = RefreshState.LOADING
        refreshMessage = null
        val routeAtRequest = activeRoute
        val directionAtRequest = direction
        viewModelScope.launch {
            when (val result = dataSource.loadRoute(routeAtRequest, directionAtRequest)) {
                is DataResult.Success -> {
                    snapshot = result.snapshot
                    cache.save(result.snapshot)
                    val previousWidget = WidgetStateStore.read(getApplication())
                    val page = previousWidget
                        ?.takeIf { it.route.id == result.snapshot.route.id && it.direction == result.snapshot.direction }
                        ?.page
                        ?: 0
                    WidgetStateStore.saveSnapshot(getApplication(), result.snapshot, page)
                    RouteWidgetProvider.refreshAll(getApplication())
                    if (integrationSettings.wearSnapshotSharingEnabled) {
                        WearSnapshotPublisher.publish(getApplication(), result.snapshot)
                    }
                    refreshState = RefreshState.SUCCESS
                    refreshMessage = result.snapshot.notice
                }
                is DataResult.Failure -> {
                    WidgetStateStore.markUnavailable(getApplication())
                    RouteWidgetProvider.refreshAll(getApplication())
                    if (integrationSettings.wearSnapshotSharingEnabled) {
                        WearSnapshotPublisher.publishUnavailable(getApplication(), routeAtRequest.displayName(directionAtRequest), currentProviderLabel())
                    }
                    refreshState = RefreshState.ERROR
                    refreshMessage = result.message
                }
            }
        }
    }

    fun openService(service: TrainService) {
        selectedService = service
    }

    fun closeService() {
        selectedService = null
    }

    fun currentServices(): List<TrainService> {
        val query = trainNumberQuery.trim()
        val filtered = snapshot?.services.orEmpty().filter { service ->
            (operatorFilter == null || service.operator == operatorFilter) &&
                (query.isBlank() || service.number.contains(query, ignoreCase = true)) &&
                journeyChangeFilter.accepts(service.transferCount)
        }
        return orderServicesForDisplay(filtered)
    }

    fun homeServices(): List<TrainService> =
        orderServicesForDisplay(snapshot?.services.orEmpty())

    fun quickServices(): List<TrainService> =
        orderServicesForDisplay(snapshot?.services.orEmpty()).take(2)

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

    fun createCommuterAlert(
        stationName: String,
        time: String,
        leadMinutes: Int,
        repeat: AlertRepeat,
        quietHoursEnabled: Boolean
    ): CommuterAlert {
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

    fun selectWidgetSurface(surface: WidgetSurface) {
        widgetSurface = surface
        personalTravelStore.saveWidgetSurface(surface)
        RouteWidgetProvider.refreshAll(getApplication())
    }

    fun updateMobilityPreferences(value: MobilityPreferences) {
        mobilityPreferences = value
        mobilityPreferencesStore.save(value)
    }

    fun exportLocalBackup(): String = LocalDataBackupCodec.encode(
        LocalDataBackup(
            routes = savedRoutes,
            favouriteStationIds = stationPreferences.favouriteIds(),
            monitoredServices = monitoredServices,
            stationMonitors = stationMonitors,
            commuterAlerts = commuterAlerts,
            mobilityPreferences = mobilityPreferences,
            widgetSurface = widgetSurface
        )
    )

    fun importLocalBackup(raw: String): String = when (val result = LocalDataBackupCodec.decode(raw, stations)) {
        is LocalBackupResult.Failure -> result.message
        is LocalBackupResult.Success -> {
            val backup = result.backup
            savedRoutes = pinnedFirst(routeStore.replaceRoutes(backup.routes))
            activeRoute = savedRoutes.first()
            routeStore.saveActiveRoute(activeRoute.id)
            stationPreferences.replaceFavourites(backup.favouriteStationIds)
            favouriteStations = stationPreferences.favourites(stations)
            replaceServiceMonitors(backup.monitoredServices)
            replaceStationMonitors(backup.stationMonitors)
            commuterAlerts = commuterAlertStore.replace(backup.commuterAlerts)
            updateMobilityPreferences(backup.mobilityPreferences)
            selectWidgetSurface(backup.widgetSurface)
            refresh()
            "Backup importato localmente: ${savedRoutes.size} tratte, ${stationMonitors.size} stazioni monitorate e ${commuterAlerts.size} avvisi."
        }
    }

    fun setWearSnapshotSharing(enabled: Boolean) {
        integrationSettings = integrationSettings.copy(wearSnapshotSharingEnabled = enabled)
        integrationStore.save(integrationSettings)
        if (enabled) snapshot?.let { latest ->
            viewModelScope.launch { WearSnapshotPublisher.publish(getApplication(), latest) }
        }
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
    fun preparedVoiceProfileRequest(expectedRevision: Int? = null): VoiceBridgeProfileRequest? {
        if (voiceProfileSyncReadiness() != VoiceProfileSyncReadiness.READY_FOR_AUTHENTICATED_SYNC) return null
        return unifiedProfile().toVoiceBridgeProfileRequest(expectedRevision)
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

    fun currentProviderLabel(): String = snapshot?.providerName ?: cache.lastProvider()

    fun currentConfidenceLabel(): String = snapshot?.confidence?.accessibleLabel ?: cache.lastConfidence()

    fun isDemonstrationData(): Boolean = snapshot?.confidence == DataConfidence.DEMONSTRATION

    fun lastRefreshEpochMillis(): Long = snapshot?.retrievedAtEpochMillis ?: cache.lastRefreshEpochMillis()
}

private fun pinnedFirst(routes: List<SavedRoute>): List<SavedRoute> =
    routes.sortedWith(compareByDescending<SavedRoute> { it.isPinned }.thenBy { it.name.lowercase() })

private class RouteStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_routes_v3", Context.MODE_PRIVATE)

    fun routes(stations: List<Station>): List<SavedRoute> {
        val stored = preferences.getString(KEY_ROUTES, null)
            ?.split(";")
            ?.mapNotNull { decodeRoute(it, stations) }
            .orEmpty()
        return if (stored.isNotEmpty()) {
            stored.map { route ->
                if (route.id == "default-roma-napoli") defaultRoute(stations) else route
            }
        } else listOf(defaultRoute(stations))
    }

    fun activeRoute(stations: List<Station>, routes: List<SavedRoute>): SavedRoute {
        val activeId = preferences.getString(KEY_ACTIVE_ROUTE, null)
        return routes.firstOrNull { it.id == activeId } ?: routes.firstOrNull() ?: defaultRoute(stations)
    }

    fun direction(): Direction = runCatching {
        Direction.valueOf(preferences.getString(KEY_DIRECTION, Direction.OUTBOUND.name) ?: Direction.OUTBOUND.name)
    }.getOrDefault(Direction.OUTBOUND)

    fun saveRoutes(routes: List<SavedRoute>) {
        preferences.edit { putString(KEY_ROUTES, routes.joinToString(";") { encodeRoute(it) }) }
    }

    fun replaceRoutes(routes: List<SavedRoute>): List<SavedRoute> {
        val normalized = routes.distinctBy { it.id }.take(32)
        saveRoutes(normalized)
        return normalized
    }

    fun saveActiveRoute(id: String) {
        preferences.edit { putString(KEY_ACTIVE_ROUTE, id) }
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
        else (listOf(ServiceMonitor(service.number, "${service.operator.shortLabel} · ${service.destination}")) + current).take(12)
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

    private fun defaultRoute(stations: List<Station>): SavedRoute {
        val origin = stations.first { it.id == "roma-termini" }
        val destination = stations.first { it.id == "torino-pn" }
        return SavedRoute("default-roma-torino", "Roma – Torino", origin, destination)
    }

    private fun encodeRoute(route: SavedRoute): String = listOf(
        route.id,
        route.name,
        route.outwardOrigin.id,
        route.outwardDestination.id,
        if (route.isPinned) "1" else "0"
    ).joinToString(",") { encode(it) }

    private fun decodeRoute(value: String, stations: List<Station>): SavedRoute? {
        val parts = value.split(",")
        if (parts.size !in 4..5) return null
        val origin = stations.firstOrNull { it.id == decode(parts[2]) } ?: return null
        val destination = stations.firstOrNull { it.id == decode(parts[3]) } ?: return null
        val isPinned = parts.getOrNull(4)?.let { decode(it) == "1" } ?: false
        return SavedRoute(decode(parts[0]), decode(parts[1]), origin, destination, isPinned)
    }

    private fun encode(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun decode(value: String): String = String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)

    private companion object {
        const val KEY_ROUTES = "routes"
        const val KEY_ACTIVE_ROUTE = "active_route"
        const val KEY_DIRECTION = "direction"
        const val KEY_RECENT = "recent"
        const val KEY_MONITORED_SERVICES = "monitored_services"
    }
}

internal fun recentRouteKey(origin: Station, destination: Station): String = "${origin.id}|${destination.id}"
