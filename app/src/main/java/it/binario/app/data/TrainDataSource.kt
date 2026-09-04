package it.binario.app.data

import android.content.Context
import androidx.core.content.edit
import it.binario.app.data.core.DelayConsistency
import it.binario.app.data.core.OperationalDetection
import it.binario.app.data.core.OperationalRouteChange
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Direction(val label: String) {
    OUTBOUND("Andata"),
    RETURN("Ritorno")
}

enum class RailOperator(val accessibleLabel: String, val shortLabel: String) {
    TRENITALIA_REGIONALE("Trenitalia Regionale", "Regionale"),
    TRENITALIA_LONG_DISTANCE("Trenitalia", "Trenitalia"),
    TRENITALIA_AV("Trenitalia Frecce", "Frecce"),
    ITALO("Italo", "Italo"),
    TRENORD("Trenord", "Trenord"),
    OTHER("Altro operatore", "Altro")
}

enum class TrainCategory(val accessibleLabel: String) {
    REGIONALE("Regionale"),
    REGIONALE_VELOCE("Regionale Veloce"),
    INTERCITY("Intercity"),
    FRECCE("Freccia"),
    AV_ITALO("Italo Alta Velocità"),
    TRENORD_REGIO("Treno Regionale Trenord"),
    METROPOLITANO("Treno metropolitano"),
    OTHER("Treno")
}

enum class ServiceStatus(val accessibleLabel: String) {
    ON_TIME("In orario"),
    DELAYED("In ritardo"),
    CANCELLED("Soppresso")
}

enum class Freshness(val accessibleLabel: String) {
    FRESH("Dati aggiornati"),
    AGING("Dati in aggiornamento"),
    STALE("Ultimo dato disponibile"),
    UNAVAILABLE("Dati live non disponibili")
}

enum class DataConfidence(val accessibleLabel: String) {
    OPERATOR_PRIMARY("Fonte primaria dell’operatore"),
    INFRASTRUCTURE_PRIMARY("Fonte primaria del gestore dell’infrastruttura"),
    OPEN_DATA("Open data ufficiale"),
    LICENSED_AGGREGATE("Fonte aggregata autorizzata"),
    STATIC_ONLY("Orario programmato"),
    CACHED("Ultimo snapshot in cache"),
    DEMONSTRATION("Dato dimostrativo locale")
}

data class Station(
    val id: String,
    val name: String,
    val city: String,
    val region: String? = null,
    /** Codice ferroviario curato; identificativo stabile della stazione, quando disponibile. */
    val railwayCode: String? = null,
    /** Identificativo Trenitalia usabile solo quando la mappatura del catalogo è univoca. */
    val trenitaliaLocationId: String? = null,
    val aliases: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null
)

/** Nomi e codici da usare per ricerca e riconciliazione, non per inferire dati operativi. */
fun Station.searchableValues(): List<String> = buildList {
    add(name)
    add(city)
    addAll(aliases)
    railwayCode?.let(::add)
    trenitaliaLocationId?.let(::add)
}

fun Station.matchesPublishedName(publishedName: String): Boolean {
    val actual = normalizeStationIdentity(publishedName)
    return searchableValues().any { expected ->
        val normalizedExpected = normalizeStationIdentity(expected)
        normalizedExpected.isNotBlank() &&
            (actual == normalizedExpected || actual.contains(normalizedExpected) || normalizedExpected.contains(actual))
    }
}

fun Station.liveIdentityKey(): String = trenitaliaLocationId?.let { "trenitalia:$it" }
    ?: railwayCode?.let { "railway:$it" }
    ?: id

private fun normalizeStationIdentity(value: String): String = value
    .uppercase(Locale.ROOT)
    .replace("S. ", "SAN ")
    .replace(Regex("[^A-Z0-9]"), "")

data class SavedRoute(
    val id: String,
    val name: String,
    val outwardOrigin: Station,
    val outwardDestination: Station,
    val isPinned: Boolean = false,
    /** Local-only rank inside the pinned or regular group. Null preserves legacy alphabetical order. */
    val manualOrder: Int? = null
) {
    fun origin(direction: Direction): Station = if (direction == Direction.OUTBOUND) outwardOrigin else outwardDestination
    fun destination(direction: Direction): Station = if (direction == Direction.OUTBOUND) outwardDestination else outwardOrigin
    fun displayName(direction: Direction): String = "${origin(direction).name} → ${destination(direction).name}"
}

enum class StopRouteChange {
    NONE,
    ADDED,
    SKIPPED
}

data class TrainStop(
    val station: String,
    val scheduled: String,
    val expected: String,
    val platform: String?,
    val delayMinutes: Int,
    val completed: Boolean,
    val routeChange: StopRouteChange = StopRouteChange.NONE
)

data class TrainService(
    val id: String,
    val number: String,
    val operator: RailOperator,
    val trainCategory: TrainCategory,
    val origin: String,
    val destination: String,
    val scheduledDeparture: String,
    val expectedDeparture: String,
    val platform: String?,
    val delayMinutes: Int,
    val status: ServiceStatus,
    val stops: List<TrainStop>,
    val transferCount: Int = 0,
    /** Nome pubblicato da agency.txt o dalla fonte operativa. Mantiene l'identità reale anche per operatori non modellati nell'enum legacy. */
    val operatorDisplayName: String? = null,
    /** Ritardo pubblicato direttamente dal provider; non è sostituito dal calcolo sulla rilevazione. */
    val officialDelayMinutes: Int? = delayMinutes,
    /** Ultimo evento operativo confermato, se il provider espone località, orario teorico e reale. */
    val lastOperationalDetection: OperationalDetection? = null,
    /** Ritardo calcolato sull'ultimo evento operativo; nullo se l'evento non è confrontabile. */
    val detectionDelayMinutes: Int? = null,
    val delayConsistency: DelayConsistency = DelayConsistency.NOT_COMPARABLE,
    /** False for schedule-only data: ON_TIME must never be presented as an observed operational state. */
    val operationalStatusVerified: Boolean = true,
    /** Date/time of the scheduled departure when the source query provides a service date. */
    val scheduledDepartureDateTime: LocalDateTime? = null,
    /** Canonical source ids that contributed verified operational fields. */
    val operationalSourceIds: Set<String> = emptySet(),
    /** Most recent observation time among the operational sources used for this service. */
    val operationalObservedAtEpochMillis: Long? = null,
    /** Operational path change that may override the static GTFS path. */
    val routeChange: OperationalRouteChange? = null,
    val routeChangeSourceIds: Set<String> = emptySet()
) {
    val category: String get() = trainCategory.accessibleLabel
    val displayOperator: String get() = operatorDisplayName?.takeIf(String::isNotBlank) ?: operator.shortLabel
    val accessibleOperator: String get() = operatorDisplayName?.takeIf(String::isNotBlank) ?: operator.accessibleLabel
    val operatorAndCategory: String get() = "$displayOperator · ${trainCategory.accessibleLabel}"
    val accessibleSummary: String
        get() {
            val official = officialDelayMinutes?.takeIf { it != 0 }?.let { " Ritardo ufficiale $it minuti." }.orEmpty()
            val detection = detectionDelayMinutes?.let { value ->
                lastOperationalDetection?.let { " Ritardo all'ultima rilevazione di ${it.locationLabel}: $value minuti." }
            }.orEmpty()
            val operational = if (operationalStatusVerified) status.accessibleLabel else "Stato operativo non verificato"
            val routeVariation = routeChange?.let { " Variazione di percorso: ${it.summary}." }.orEmpty()
            return "Operatore $accessibleOperator. ${trainCategory.accessibleLabel} $number da $origin a $destination. Partenza prevista $expectedDeparture. ${if (transferCount == 0) "Viaggio diretto" else "$transferCount ${if (transferCount == 1) "cambio" else "cambi"}"}. $operational$routeVariation$official$detection ${platform?.let { "Binario $it" } ?: "Binario non disponibile"}."
        }
}

data class RouteSnapshot(
    val route: SavedRoute,
    val direction: Direction,
    val services: List<TrainService>,
    val providerName: String,
    val retrievedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val freshness: Freshness,
    val sourceObservedAtEpochMillis: Long = retrievedAtEpochMillis,
    val confidence: DataConfidence = DataConfidence.DEMONSTRATION,
    val qualityScore: Int? = null,
    val qualityBand: DataQualityBand = DataQualityBand.UNKNOWN,
    val qualityWarnings: Set<SnapshotWarning> = emptySet(),
    val notice: String? = null
) {
    val routeLabel: String get() = route.displayName(direction)
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtEpochMillis
}

sealed interface DataResult {
    data class Success(val snapshot: RouteSnapshot) : DataResult
    data class Failure(val message: String) : DataResult
}

/** Single neutral display-order rule shared by lists, widget and smartwatch. */
fun orderServicesForDisplay(services: List<TrainService>): List<TrainService> {
    if (services.isNotEmpty() && services.all { it.scheduledDepartureDateTime != null }) {
        return services.sortedWith(
            compareBy<TrainService> { it.scheduledDepartureDateTime }
                .thenBy { it.number }
        )
    }
    return services.sortedWith(
        compareBy<TrainService> {
            runCatching { timeToMinutes(it.expectedDeparture) }.getOrDefault(Int.MAX_VALUE)
        }.thenBy { it.number }
    )
}

/**
 * Contract-only boundary. Production implementations must use a documented,
 * authorized provider or a personal backend. The Android client never embeds
 * credentials and never calls undocumented operator endpoints directly.
 */
data class JourneyQuery(
    val serviceDate: LocalDate,
    val departureTime: LocalTime
) {
    val requestedAt: LocalDateTime get() = LocalDateTime.of(serviceDate, departureTime)

    companion object {
        fun now(): JourneyQuery {
            val now = LocalDateTime.now()
            return JourneyQuery(now.toLocalDate(), now.toLocalTime().withSecond(0).withNano(0))
        }
    }
}

interface TrainDataSource {
    suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult

    /** Query-aware overload. Legacy/live adapters can ignore the schedule query until migrated. */
    suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult =
        loadRoute(route, direction)
}

/** A future authorised server-side gateway for Trenitalia operational data. */
interface AuthorizedRailGateway {
    suspend fun loadTrenitaliaRoute(route: SavedRoute, direction: Direction): DataResult
}

/**
 * Reserved adapter for an authorised Trenitalia / ViaggiaTreno integration.
 * With no gateway configured, it deliberately performs no network request.
 */
class TrenitaliaViaggiaTrenoAdapter(
    private val gateway: AuthorizedRailGateway? = null
) : TrainDataSource {
    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
        return gateway?.loadTrenitaliaRoute(route, direction)
            ?: DataResult.Failure("Il canale live Trenitalia non è configurato o autorizzato su questo dispositivo.")
    }
}

/**
 * Tries only expressly configured, authorised adapters and falls back to a
 * local fixture. This keeps app and widget usable without pretending that a
 * demo or cached service is live railway information.
 */
class RailDataRepository(
    private val authorisedSources: List<TrainDataSource> = emptyList(),
    private val fallback: TrainDataSource = UnavailableTrainDataSource(),
    private val clock: () -> Long = System::currentTimeMillis
) : TrainDataSource {
    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now())

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        val candidates = mutableListOf<RouteSnapshot>()
        authorisedSources.forEach { source ->
            when (val result = source.loadRoute(route, direction, query)) {
                is DataResult.Success -> {
                    val enriched = withQuality(result.snapshot)
                    candidates += enriched
                    if (enriched.qualityBand == DataQualityBand.VERIFIED) {
                        return DataResult.Success(enriched)
                    }
                }
                is DataResult.Failure -> Unit
            }
        }
        candidates.maxWithOrNull(
            compareBy<RouteSnapshot> { it.qualityScore ?: Int.MIN_VALUE }
                .thenBy { it.sourceObservedAtEpochMillis }
        )?.let { return DataResult.Success(it) }

        return when (val result = fallback.loadRoute(route, direction, query)) {
            is DataResult.Success -> DataResult.Success(withQuality(result.snapshot))
            is DataResult.Failure -> result
        }
    }

    private fun withQuality(snapshot: RouteSnapshot): RouteSnapshot {
        val quality = SnapshotQualityAssessor.assess(snapshot, clock())
        return snapshot.copy(
            qualityScore = quality.score,
            qualityBand = quality.band,
            qualityWarnings = quality.warnings
        )
    }
}

/**
 * Fallback di produzione: non crea corse artificiali quando nessuna fonte live
 * o GTFS programmata restituisce una tratta utilizzabile.
 */
class UnavailableTrainDataSource : TrainDataSource {
    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        DataResult.Failure(
            "Nessuna fonte realtime o GTFS programmata ha restituito una corsa per questa tratta. " +
                "Riprova più tardi o verifica i monitor ufficiali."
        )
}

class RouteSnapshotCache(context: Context) {
    private val preferences = context.getSharedPreferences("binario_snapshot_cache", Context.MODE_PRIVATE)

    fun save(snapshot: RouteSnapshot) {
        preferences.edit {
            putLong("last_refresh", snapshot.retrievedAtEpochMillis)
            putString("last_provider", snapshot.providerName)
            putString("last_confidence", snapshot.confidence.accessibleLabel)
        }
    }

    fun lastRefreshEpochMillis(): Long = preferences.getLong("last_refresh", 0L)
    fun lastProvider(): String = preferences.getString("last_provider", "Nessun provider") ?: "Nessun provider"
    fun lastConfidence(): String = preferences.getString("last_confidence", "Qualità non disponibile") ?: "Qualità non disponibile"
}

fun formatTime(totalMinutes: Int): String {
    val normalized = ((totalMinutes % (24 * 60)) + (24 * 60)) % (24 * 60)
    return "%02d:%02d".format(normalized / 60, normalized % 60)
}

fun timeToMinutes(value: String): Int {
    val pieces = value.split(":")
    return pieces[0].toInt() * 60 + pieces[1].toInt()
}

fun accessibleUpdateTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "mai aggiornato"
    val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    return "aggiornato alle ${formatter.format(Instant.ofEpochMilli(epochMillis))}"
}
