package it.binario.app.data.provider

import it.binario.app.data.core.AlertId
import it.binario.app.data.core.CanonicalStation
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.ServiceAlert
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationAlias
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRun
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.VehiclePosition
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

enum class ProviderCapability {
    STATION_CATALOG,
    JOURNEY_SEARCH,
    TRAIN_RUN_STATIC,
    TRAIN_RUN_REALTIME,
    STATION_BOARD,
    SERVICE_ALERTS,
    VEHICLE_POSITIONS,
    /** Not railway data: forecast for a station's place, shown next to the journey. */
    WEATHER_FORECAST,
    /** Not railway data: shared bikes and scooters (GBFS) near a station, shown on request. */
    BIKE_SHARING
}

enum class AccessMode {
    OPEN_DIRECT,
    PUBLIC_BEST_EFFORT,
    GATEWAY_ONLY,
    AGREEMENT_REQUIRED,
    DISABLED
}

data class CoverageDescriptor(
    val countryCodes: Set<String> = emptySet(),
    val operatorIds: Set<OperatorId> = emptySet(),
    val stationIds: Set<StationId> = emptySet(),
    val description: String
)

/**
 * How a source may be asked. [minimumRequestInterval] is per resource (the same URL is not asked again sooner; the
 * adapter's response cache enforces it), [maxConcurrentRequests] per source (the adapter's request permits),
 * [supportsConditionalRequests] whether the adapter revalidates with `If-None-Match`/`If-Modified-Since`.
 */
data class RatePolicy(
    val minimumRequestInterval: Duration,
    val maxConcurrentRequests: Int = 1,
    val defaultTimeToLive: Duration,
    val supportsConditionalRequests: Boolean = false
) {
    init {
        require(!minimumRequestInterval.isNegative) { "L'intervallo minimo non può essere negativo" }
        require(maxConcurrentRequests > 0) { "La concorrenza deve essere positiva" }
        require(!defaultTimeToLive.isNegative && !defaultTimeToLive.isZero) { "Il TTL deve essere positivo" }
    }
}

data class ProviderDescriptor(
    val id: SourceId,
    val displayName: String,
    val capabilities: Set<ProviderCapability>,
    val coverage: CoverageDescriptor,
    val accessMode: AccessMode,
    val ratePolicy: RatePolicy,
    val schemaVersion: Int
)

data class ProviderResponseMeta(
    val providerId: SourceId,
    val fetchedAt: Instant,
    val observedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val fromCache: Boolean = false,
    val completeness: Completeness = Completeness.COMPLETE,
    val responseFingerprint: String? = null
)

enum class ProviderErrorKind {
    NOT_SUPPORTED,
    INVALID_REQUEST,
    NOT_FOUND,
    AUTH_REQUIRED,
    RATE_LIMITED,
    NETWORK,
    TIMEOUT,
    UPSTREAM_UNAVAILABLE,
    PARSE_ERROR,
    SCHEMA_CHANGED,
    INVALID_DATA,
    LEGAL_DISABLED,
    UNKNOWN
}

data class ProviderError(
    val kind: ProviderErrorKind,
    val safeMessage: String,
    val causeClass: String? = null
)

sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T, val meta: ProviderResponseMeta) : ProviderResult<T>
    data class Partial<T>(
        val value: T,
        val warnings: Set<ProviderWarning>,
        val meta: ProviderResponseMeta
    ) : ProviderResult<T>
    data class NotModified(val meta: ProviderResponseMeta) : ProviderResult<Nothing>
    data class Failure(val error: ProviderError, val retryAfter: Duration? = null) : ProviderResult<Nothing>
}

enum class ProviderWarning {
    REALTIME_UNAVAILABLE,
    /** A station board with only programmed rows: no live board answered with trains. */
    PROGRAMMED_ONLY,
    SOURCE_CONFLICT,
    STALE_SOURCE,
    IDENTITY_AMBIGUOUS,
    REPLACEMENT_SERVICE,
    INCOMPLETE_COVERAGE
}

data class StationSearchRequest(
    val query: String,
    val limit: Int = 10
) {
    init {
        require(query.isNotBlank()) { "La ricerca stazione è obbligatoria" }
        require(limit in 1..50) { "Il limite deve essere tra 1 e 50" }
    }
}

/**
 * Names and code of a journey endpoint as the app's station model knows them, so that a provider can match the
 * station the user picked even when its id is not in the catalogue (a stop picked from a GTFS pack).
 */
data class JourneyEndpoint(
    val name: String,
    val aliases: List<String> = emptyList(),
    val railwayCode: String? = null
) {
    init {
        require(name.isNotBlank()) { "Il nome della stazione è obbligatorio" }
    }
}

data class JourneySearchRequest(
    val origin: StationId,
    val destination: StationId,
    val departureAt: ZonedDateTime,
    val maxTransfers: Int = 2,
    val originEndpoint: JourneyEndpoint? = null,
    val destinationEndpoint: JourneyEndpoint? = null,
    /** At most this many options, sorted by departure. */
    val maxResults: Int = 6,
    /** "Preferenze di viaggio"; null keeps the provider's own defaults (walks of at most 400 m at 4.5 km/h). */
    val preferences: JourneyPreferences? = null
) {
    init {
        require(origin != destination) { "Origine e destinazione devono essere diverse" }
        require(maxTransfers in 0..6) { "Il numero massimo di cambi non è supportato" }
        require(maxResults in 1..20) { "Il numero di soluzioni deve essere tra 1 e 20" }
    }
}

data class TrainRunRequest(
    val runId: TrainRunId? = null,
    val publicNumber: String? = null,
    val serviceDate: LocalDate,
    val operatorId: OperatorId? = null
) {
    init {
        require(runId != null || !publicNumber.isNullOrBlank()) { "Servono un identificativo o un numero treno" }
    }
}

data class StationBoardRequest(
    val stationId: StationId,
    val stationName: String? = null,
    /** Catalogue aliases and railway code, so providers can resolve the same station the user picked. */
    val stationAliases: List<String> = emptyList(),
    val railwayCode: String? = null,
    val from: Instant,
    val to: Instant,
    val kind: it.binario.app.data.core.BoardKind,
    /** Curated Trenitalia location id (country prefix + code); Swiss stations carry `85…` here. */
    val trenitaliaLocationId: String? = null,
    /** Catalogue coordinates, used only to verify a provider's own station identity, never sent as user location. */
    val coordinates: it.binario.app.data.core.GeoPoint? = null,
    /**
     * Earliest scheduled time of a late train that is still to leave: a row scheduled between this and [from] stays on
     * the board while its source's expected time is at or after [from]. Null keeps only rows scheduled from [from].
     */
    val lateSince: Instant? = null
) {
    init {
        require(to.isAfter(from)) { "La finestra del tabellone non è valida" }
        require(Duration.between(from, to) <= Duration.ofHours(24)) { "La finestra del tabellone è troppo ampia" }
        require(lateSince == null || (!lateSince.isAfter(from) && Duration.between(lateSince, from) <= Duration.ofHours(6))) {
            "La finestra dei treni in ritardo non è valida"
        }
    }

    /** Earliest scheduled time a provider has to look at: [lateSince] when set, otherwise [from]. */
    val earliestScheduled: Instant get() = lateSince ?: from

    /**
     * Whether a row scheduled at [scheduled] belongs to this board: inside [from]..[to], or a late train scheduled from
     * [lateSince] whose [expected] time (known only from a live source) has not passed [from] yet.
     */
    fun keeps(scheduled: Instant, expected: Instant?): Boolean = boardWindowKeeps(from, to, lateSince, scheduled, expected)
}

/** [StationBoardRequest.keeps] for a window given by its bounds. */
fun boardWindowKeeps(from: Instant, to: Instant, lateSince: Instant?, scheduled: Instant, expected: Instant?): Boolean {
    if (scheduled.isAfter(to)) return false
    if (!scheduled.isBefore(from)) return true
    val since = lateSince ?: return false
    return !scheduled.isBefore(since) && expected != null && !expected.isBefore(from)
}

data class AlertRequest(
    val stationIds: Set<StationId> = emptySet(),
    val operatorIds: Set<OperatorId> = emptySet(),
    val runIds: Set<TrainRunId> = emptySet(),
    val knownAlertIds: Set<AlertId> = emptySet()
)

/** Notices for one public train number on one service day; [originCode] narrows them when the app knows it. */
data class TrainNoticeRequest(
    val publicNumber: String,
    val serviceDate: LocalDate,
    val originCode: String? = null
) {
    init {
        require(publicNumber.isNotBlank()) { "Il numero treno è obbligatorio" }
    }
}

interface RailProvider {
    val descriptor: ProviderDescriptor
}

interface StationCatalogProvider : RailProvider {
    suspend fun searchStations(request: StationSearchRequest): ProviderResult<List<CanonicalStation>>
    suspend fun resolveStation(alias: StationAlias): ProviderResult<CanonicalStation>
}

interface JourneyProvider : RailProvider {
    suspend fun searchJourneys(request: JourneySearchRequest): ProviderResult<List<JourneyOption>>
}

interface TrainRunProvider : RailProvider {
    suspend fun getTrainRun(request: TrainRunRequest): ProviderResult<TrainRun>
}

interface StationBoardProvider : RailProvider {
    suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard>
}

interface AlertProvider : RailProvider {
    suspend fun getAlerts(request: AlertRequest): ProviderResult<List<ServiceAlert>>
}

interface TrainNoticeProvider : RailProvider {
    suspend fun getTrainNotices(request: TrainNoticeRequest): ProviderResult<List<it.binario.app.data.core.TrainNotice>>
}

interface VehiclePositionProvider : RailProvider {
    suspend fun getVehiclePositions(): ProviderResult<List<VehiclePosition>>
}

/** Aggiornamenti realtime per fermata, normalizzati senza confondere previsioni e passaggi effettivi. */
interface TripUpdateProvider : RailProvider {
    suspend fun getTripUpdates(): ProviderResult<List<RealtimeStopUpdate>>
}
