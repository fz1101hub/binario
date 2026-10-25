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
    WEATHER_FORECAST
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

data class JourneySearchRequest(
    val origin: StationId,
    val destination: StationId,
    val departureAt: ZonedDateTime,
    val maxTransfers: Int = 2
) {
    init {
        require(origin != destination) { "Origine e destinazione devono essere diverse" }
        require(maxTransfers in 0..6) { "Il numero massimo di cambi non è supportato" }
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
    val coordinates: it.binario.app.data.core.GeoPoint? = null
) {
    init {
        require(to.isAfter(from)) { "La finestra del tabellone non è valida" }
        require(Duration.between(from, to) <= Duration.ofHours(24)) { "La finestra del tabellone è troppo ampia" }
    }
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
