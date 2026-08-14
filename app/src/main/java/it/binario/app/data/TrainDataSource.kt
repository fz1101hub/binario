package it.binario.app.data

import android.content.Context
import androidx.core.content.edit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    LICENSED_AGGREGATE("Fonte aggregata autorizzata"),
    STATIC_ONLY("Orario programmato"),
    CACHED("Ultimo snapshot in cache"),
    DEMONSTRATION("Dato dimostrativo locale")
}

data class Station(
    val id: String,
    val name: String,
    val city: String,
    val region: String? = null
)

data class SavedRoute(
    val id: String,
    val name: String,
    val outwardOrigin: Station,
    val outwardDestination: Station,
    val isPinned: Boolean = false
) {
    fun origin(direction: Direction): Station = if (direction == Direction.OUTBOUND) outwardOrigin else outwardDestination
    fun destination(direction: Direction): Station = if (direction == Direction.OUTBOUND) outwardDestination else outwardOrigin
    fun displayName(direction: Direction): String = "${origin(direction).name} → ${destination(direction).name}"
}

data class TrainStop(
    val station: String,
    val scheduled: String,
    val expected: String,
    val platform: String?,
    val delayMinutes: Int,
    val completed: Boolean
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
    val transferCount: Int = 0
) {
    val category: String get() = trainCategory.accessibleLabel
    val operatorAndCategory: String get() = "${operator.shortLabel} · ${trainCategory.accessibleLabel}"
    val accessibleSummary: String
        get() = "Operatore ${operator.accessibleLabel}. ${trainCategory.accessibleLabel} $number da $origin a $destination. Partenza prevista $expectedDeparture. ${if (transferCount == 0) "Viaggio diretto" else "$transferCount ${if (transferCount == 1) "cambio" else "cambi"}"}. ${status.accessibleLabel}${if (delayMinutes > 0) ", $delayMinutes minuti di ritardo" else ""}. ${platform?.let { "Binario $it" } ?: "Binario non disponibile"}."
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
fun orderServicesForDisplay(services: List<TrainService>): List<TrainService> =
    services.sortedWith(
        compareBy<TrainService> {
            runCatching { timeToMinutes(it.expectedDeparture) }.getOrDefault(Int.MAX_VALUE)
        }.thenBy { it.number }
    )

/**
 * Contract-only boundary. Production implementations must use a documented,
 * authorized provider or a personal backend. The Android client never embeds
 * credentials and never calls undocumented operator endpoints directly.
 */
interface TrainDataSource {
    suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult
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
    private val fallback: TrainDataSource = DemoTrainDataSource()
) : TrainDataSource {
    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
        authorisedSources.forEach { source ->
            when (val result = source.loadRoute(route, direction)) {
                is DataResult.Success -> return result
                is DataResult.Failure -> Unit
            }
        }
        return fallback.loadRoute(route, direction)
    }
}

/** Local-only national multi-operator fixture used until an authorised source is configured. */
class DemoTrainDataSource : TrainDataSource {
    private data class DemoSeed(
        val number: String,
        val operator: RailOperator,
        val category: TrainCategory,
        val delay: Int
    )

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
        val now = System.currentTimeMillis()
        val origin = route.origin(direction).name
        val destination = route.destination(direction).name
        val seeds = listOf(
            DemoSeed("4512", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, 0),
            DemoSeed("4132", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE_VELOCE, 5),
            DemoSeed("580", RailOperator.TRENITALIA_LONG_DISTANCE, TrainCategory.INTERCITY, 7),
            DemoSeed("9501", RailOperator.TRENITALIA_AV, TrainCategory.FRECCE, 0),
            DemoSeed("9927", RailOperator.ITALO, TrainCategory.AV_ITALO, 14),
            DemoSeed("4178", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE_VELOCE, 0),
            DemoSeed("704", RailOperator.TRENITALIA_LONG_DISTANCE, TrainCategory.INTERCITY, 0),
            DemoSeed("9623", RailOperator.TRENITALIA_AV, TrainCategory.FRECCE, 9),
            DemoSeed("9951", RailOperator.ITALO, TrainCategory.AV_ITALO, 0),
            DemoSeed("4310", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, 3),
            DemoSeed("611", RailOperator.TRENITALIA_LONG_DISTANCE, TrainCategory.INTERCITY, 0),
            DemoSeed("2462", RailOperator.TRENORD, TrainCategory.TRENORD_REGIO, 6)
        )
        val baseMinutes = 7 * 60 + if (direction == Direction.OUTBOUND) 8 else 21
        val services = seeds.mapIndexed { index, seed ->
            val scheduled = formatTime(baseMinutes + index * 32)
            val expected = formatTime(baseMinutes + index * 32 + seed.delay)
            val status = when {
                index == 10 -> ServiceStatus.CANCELLED
                seed.delay > 0 -> ServiceStatus.DELAYED
                else -> ServiceStatus.ON_TIME
            }
            TrainService(
                id = "${route.id}-${direction.name}-${seed.operator.name}-${seed.number}",
                number = seed.number,
                operator = seed.operator,
                trainCategory = seed.category,
                origin = origin,
                destination = destination,
                scheduledDeparture = scheduled,
                expectedDeparture = expected,
                platform = if (status == ServiceStatus.CANCELLED) null else ((index % 12) + 1).toString(),
                delayMinutes = seed.delay,
                status = status,
                stops = buildStops(origin, destination, scheduled, seed.delay, index, status),
                transferCount = when (index % 4) {
                    0, 1 -> 0
                    2 -> 1
                    else -> 2
                }
            )
        }
        return DataResult.Success(
            RouteSnapshot(
                route = route,
                direction = direction,
                services = services,
                providerName = "Catalogo dimostrativo multi-operatore (locale)",
                retrievedAtEpochMillis = now,
                expiresAtEpochMillis = now + 15 * 60 * 1000,
                freshness = Freshness.FRESH,
                sourceObservedAtEpochMillis = now,
                confidence = DataConfidence.DEMONSTRATION,
                notice = "Queste corse dimostrano la copertura nazionale e non sono dati di circolazione. Per ritardi, binari e soppressioni fai riferimento ai canali ufficiali finché un provider autorizzato non è configurato."
            )
        )
    }

    private fun buildStops(
        origin: String,
        destination: String,
        departure: String,
        delay: Int,
        index: Int,
        status: ServiceStatus
    ): List<TrainStop> {
        val departureMinutes = timeToMinutes(departure)
        val middle = listOf("Stazione intermedia", "Nodo di scambio").take(if (index % 2 == 0) 1 else 2)
        val names = listOf(origin) + middle + destination
        return names.mapIndexed { stopIndex, name ->
            val scheduled = formatTime(departureMinutes + stopIndex * 58)
            val expected = formatTime(departureMinutes + stopIndex * 58 + if (status == ServiceStatus.CANCELLED) 0 else delay)
            TrainStop(
                station = name,
                scheduled = scheduled,
                expected = expected,
                platform = if (status == ServiceStatus.CANCELLED) null else ((index + stopIndex) % 12 + 1).toString(),
                delayMinutes = if (status == ServiceStatus.CANCELLED) 0 else delay,
                completed = false
            )
        }
    }
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
