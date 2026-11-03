package it.binario.app.data.bikesharing

import it.binario.app.data.core.GeoPoint
import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.GtfsStationCodeIndex
import it.binario.app.data.gtfs.TransitArea
import it.binario.app.data.live.PublicSourceFetcher
import it.binario.app.data.live.ResilientSourceCache
import it.binario.app.data.live.SourceRead
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.RatePolicy
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import it.binario.app.data.live.textOr

/**
 * One bike or scooter sharing system that publishes a GBFS auto-discovery file, with the evidence of its terms
 * (docs/data-sources/SOURCE_POLICY.md, «Bici in condivisione»). [personalOnly] systems (no licence in the feed, or a
 * licence agreement not reviewed) are used only in personal builds.
 */
data class BikeSharingSystem(
    val id: SourceId,
    /** Name shown with its vehicles ("BikeMi", "nextbike Bergamo"). */
    val name: String,
    val operator: String,
    val discoveryUrl: String,
    /** Where the system operates: asked only for a station inside one of them. */
    val areas: List<TransitArea>,
    /** The operator's own page, opened from the section ("Apri BikeMi"). */
    val websiteUrl: String,
    val licence: String,
    /** Shown with the data ("BikeMi, NLOD 2.0"): the attribution the licence asks for. */
    val attribution: String,
    val personalOnly: Boolean
) {
    init {
        require(discoveryUrl.startsWith("https://") && websiteUrl.startsWith("https://")) { "Il bike sharing deve usare HTTPS" }
    }

    fun serves(point: GeoPoint): Boolean = areas.any { it.contains(point.latitude, point.longitude) }
}

/**
 * The GBFS systems verified from CI (runs 41561372909 and 41564222536): reachable, current, with stations or vehicles
 * near railway stations, and their terms.
 */
object BikeSharingSystems {
    val NEXTBIKE_BERGAMO = BikeSharingSystem(
        id = SourceId("gbfs-nextbike-bergamo"),
        name = "nextbike Bergamo",
        operator = "nextbike GmbH",
        discoveryUrl = "https://gbfs.nextbike.net/maps/gbfs/v2/nextbike_ib/gbfs.json",
        areas = listOf(TransitArea("Bergamo", 45.6983, 9.6773, 8.0)),
        websiteUrl = "https://www.nextbike.it/bergamo/",
        licence = "CC0-1.0 (license_id in system_information)",
        attribution = "nextbike Bergamo, CC0",
        personalOnly = false
    )
    val FAMOSE_SENIGALLIA = BikeSharingSystem(
        id = SourceId("gbfs-famose-senigallia"),
        name = "Fa.Mo.Se",
        operator = "TIER Mobility SE / nextbike",
        discoveryUrl = "https://gbfs.nextbike.net/maps/gbfs/v2/nextbike_fa/gbfs.json",
        areas = listOf(TransitArea("Senigallia", 43.7147, 13.2180, 12.0)),
        websiteUrl = "https://www.comune.senigallia.an.it/aree-tematiche/mobilita/bike-sharing-fa-mo-se/",
        licence = "CC0-1.0 (license_id in system_information)",
        attribution = "Fa.Mo.Se, CC0",
        personalOnly = false
    )
    val BIKEMI = BikeSharingSystem(
        id = SourceId("gbfs-bikemi"),
        name = "BikeMi",
        operator = "IGPDecaux S.p.A.",
        discoveryUrl = "https://gbfs.urbansharing.com/bikemi.com/gbfs.json",
        areas = listOf(TransitArea("Milano", 45.4642, 9.1900, 10.0)),
        websiteUrl = "https://bikemi.com/",
        licence = "NLOD 2.0 (Norwegian Licence for Open Government Data), dichiarata su bikemi.com/it/open-data",
        attribution = "BikeMi, NLOD 2.0",
        personalOnly = false
    )
    val VERONA_BIKE = BikeSharingSystem(
        id = SourceId("gbfs-verona-bike"),
        name = "Verona Bike",
        operator = "IGPDecaux S.p.A.",
        discoveryUrl = "https://gbfs.urbansharing.com/bikeverona.it/gbfs.json",
        areas = listOf(TransitArea("Verona", 45.4384, 10.9916, 8.0)),
        websiteUrl = "https://bikeverona.it/",
        licence = "NLOD 2.0 (Norwegian Licence for Open Government Data), dichiarata su bikeverona.it/en/open-data",
        attribution = "Verona Bike, NLOD 2.0",
        personalOnly = false
    )
    val BIRD_ROMA = BikeSharingSystem(
        id = SourceId("gbfs-bird-roma"),
        name = "Bird",
        operator = "Bird Rides, Inc.",
        discoveryUrl = "https://mds.bird.co/gbfs/v2/public/rome/gbfs.json",
        areas = listOf(TransitArea("Roma", 41.8986, 12.4964, 15.0)),
        websiteUrl = "https://www.bird.co/",
        licence = "Bird GBFS Data License Agreement (license_url del feed), condizioni non riesaminate",
        attribution = "Bird",
        personalOnly = true
    )

    val all: List<BikeSharingSystem> = listOf(NEXTBIKE_BERGAMO, FAMOSE_SENIGALLIA, BIKEMI, VERONA_BIKE, BIRD_ROMA)

    fun forBuild(personalSources: Boolean): List<BikeSharingSystem> = all.filter { personalSources || !it.personalOnly }
}

enum class SharedVehicleKind { BIKE, EBIKE, SCOOTER, OTHER }

/** A docking station within reach: what is free there now. */
data class NearbyDock(
    val name: String,
    val distanceMeters: Int,
    val bikesAvailable: Int,
    /** Electric bikes among [bikesAvailable], when the system publishes vehicle types per station. */
    val ebikesAvailable: Int?,
    val docksAvailable: Int?
)

/** A free-floating vehicle within reach. */
data class NearbyVehicle(
    val kind: SharedVehicleKind,
    val distanceMeters: Int,
    /** Battery charge 0–100 when published (GBFS `current_fuel_percent`). */
    val batteryPercent: Int?
)

data class BikeSharingNearby(
    val system: BikeSharingSystem,
    val docks: List<NearbyDock>,
    val vehicles: List<NearbyVehicle>,
    /** When the system's status feed was last updated (GBFS `last_updated`), else when it was read. */
    val observedAt: Instant,
    /** The last good answer shown after a failed refresh. */
    val stale: Boolean
)

sealed interface BikeSharingResult {
    /** No system of this build serves the station's place. */
    data object NotCovered : BikeSharingResult
    data class Loaded(val systems: List<BikeSharingNearby>) : BikeSharingResult
    data object Unavailable : BikeSharingResult
}

/** The parts of a GBFS system this app reads, already reduced to coordinates and counts. */
internal data class GbfsSnapshot(
    val stations: List<GbfsStation>,
    val vehicles: List<GbfsVehicle>,
    val lastUpdated: Instant?
)

internal data class GbfsStation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val bikes: Int,
    val ebikes: Int?,
    val docks: Int?,
    val renting: Boolean
)

internal data class GbfsVehicle(val latitude: Double, val longitude: Double, val kind: SharedVehicleKind, val batteryPercent: Int?)

/**
 * Reads GBFS 2.x and 3.0 documents ([parse] functions are pure, for tests). Untrusted input: every field is optional,
 * coordinates out of range, disabled or reserved vehicles, docked free-bike entries (they are counted at their station)
 * and stations that do not rent are dropped.
 */
internal object GbfsParser {
    /** {feed name: url} of gbfs.json: v3 lists `data.feeds`, v1/v2 one list per language (Italian, then English). */
    fun feeds(json: String): Map<String, String> {
        val data = JSONObject(json).optJSONObject("data") ?: return emptyMap()
        val list = data.optJSONArray("feeds") ?: listOf("it", "en").firstNotNullOfOrNull { data.optJSONObject(it)?.optJSONArray("feeds") }
            ?: data.keys().asSequence().firstNotNullOfOrNull { data.optJSONObject(it)?.optJSONArray("feeds") }
            ?: return emptyMap()
        return objects(list).mapNotNull { feed ->
            val name = feed.textOr("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val url = feed.textOr("url").takeIf(String::isNotBlank) ?: return@mapNotNull null
            name to url
        }.toMap()
    }

    /** vehicle_type_id → kind, from `vehicle_types` (form_factor and propulsion_type). */
    fun vehicleTypes(json: String): Map<String, SharedVehicleKind> =
        objects(JSONObject(json).optJSONObject("data")?.optJSONArray("vehicle_types")).mapNotNull { type ->
            val id = type.textOr("vehicle_type_id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val form = type.textOr("form_factor").lowercase()
            val propulsion = type.textOr("propulsion_type").lowercase()
            id to when {
                form == "bicycle" && propulsion in setOf("electric_assist", "electric") -> SharedVehicleKind.EBIKE
                form == "bicycle" || form == "cargo_bicycle" -> SharedVehicleKind.BIKE
                form == "scooter" || form == "scooter_standing" -> SharedVehicleKind.SCOOTER
                else -> SharedVehicleKind.OTHER
            }
        }.toMap()

    fun stations(informationJson: String, statusJson: String, types: Map<String, SharedVehicleKind>): List<GbfsStation> {
        val status = objects(JSONObject(statusJson).optJSONObject("data")?.optJSONArray("stations"))
            .associateBy { it.textOr("station_id") }
        return objects(JSONObject(informationJson).optJSONObject("data")?.optJSONArray("stations")).mapNotNull { station ->
            val id = station.textOr("station_id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val point = coordinates(station) ?: return@mapNotNull null
            val state = status[id] ?: return@mapNotNull null
            if (!flag(state, "is_installed", true) || !flag(state, "is_renting", true)) return@mapNotNull null
            val bikes = count(state, "num_bikes_available") ?: count(state, "num_vehicles_available") ?: 0
            val perType = objects(state.optJSONArray("vehicle_types_available"))
            val ebikes = if (perType.isEmpty() || types.isEmpty()) null else perType.sumOf { item ->
                if (types[item.textOr("vehicle_type_id")] == SharedVehicleKind.EBIKE) count(item, "count") ?: 0 else 0
            }
            GbfsStation(id, name(station), point.first, point.second, bikes, ebikes, count(state, "num_docks_available"), renting = true)
        }
    }

    fun vehicles(json: String, types: Map<String, SharedVehicleKind>): List<GbfsVehicle> {
        val data = JSONObject(json).optJSONObject("data") ?: return emptyList()
        return objects(data.optJSONArray("bikes") ?: data.optJSONArray("vehicles")).mapNotNull { vehicle ->
            if (flag(vehicle, "is_disabled", false) || flag(vehicle, "is_reserved", false)) return@mapNotNull null
            if (vehicle.textOr("station_id").isNotBlank()) return@mapNotNull null
            val point = coordinates(vehicle) ?: return@mapNotNull null
            val fuel = vehicle.optDouble("current_fuel_percent", Double.NaN).takeIf { it.isFinite() && it in 0.0..1.0 }
            GbfsVehicle(
                point.first, point.second,
                types[vehicle.textOr("vehicle_type_id")] ?: SharedVehicleKind.OTHER,
                fuel?.let { Math.round(it * 100).toInt() }
            )
        }
    }

    /** `last_updated`: POSIX seconds in GBFS 2.x, an RFC 3339 timestamp in 3.0. */
    fun lastUpdated(json: String): Instant? {
        val value = JSONObject(json).opt("last_updated") ?: return null
        return when (value) {
            is Number -> value.toLong().takeIf { it > 0 }?.let(Instant::ofEpochSecond)
            is String -> runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()
                ?: value.toLongOrNull()?.takeIf { it > 0 }?.let(Instant::ofEpochSecond)
            else -> null
        }
    }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun coordinates(item: JSONObject): Pair<Double, Double>? {
        val latitude = item.optDouble("lat", Double.NaN)
        val longitude = item.optDouble("lon", Double.NaN)
        return if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) latitude to longitude else null
    }

    private fun count(item: JSONObject, key: String): Int? = item.opt(key)?.let { (it as? Number)?.toInt() }?.takeIf { it >= 0 }

    private fun flag(item: JSONObject, key: String, default: Boolean): Boolean = when (val value = item.opt(key)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> default
    }

    /** GBFS 3.0 names are localized arrays ([{"text": …, "language": …}]). */
    private fun name(item: JSONObject): String {
        item.optJSONArray("name")?.let { names ->
            val list = objects(names)
            return (list.firstOrNull { it.textOr("language").startsWith("it") } ?: list.firstOrNull())?.textOr("text").orEmpty()
        }
        return item.textOr("name")
    }
}

/**
 * Shared bikes and scooters within [radiusMeters] of a station's catalogue coordinates (never the device position),
 * from the GBFS feeds of the systems serving that place. Asked only when the user opens the section: the static
 * documents (auto-discovery, station list, vehicle types) are cached for six hours, the status for one minute, with
 * the public-source circuit breaker. Feed URLs must stay on the auto-discovery host over HTTPS.
 */
class BikeSharingService(
    private val systems: List<BikeSharingSystem>,
    private val fetcher: PublicSourceFetcher,
    private val clock: Clock = Clock.systemUTC(),
    private val radiusMeters: Int = DEFAULT_RADIUS_METERS,
    private val maxDocks: Int = 4,
    private val maxVehicles: Int = 4
) {
    // One circuit breaker per operator host: an outage of one system must not pause the others.
    private val staticCache = ResilientSourceCache<String>(ttl = Duration.ofHours(6), maxStale = Duration.ofHours(24), clock = clock, maxEntries = 64,
        breakerPerHost = true)
    private val statusCache = ResilientSourceCache<String>(ttl = Duration.ofSeconds(60), maxStale = Duration.ofMinutes(5), clock = clock, maxEntries = 32,
        breakerPerHost = true)

    val hasSystems: Boolean get() = systems.isNotEmpty()

    fun serves(point: GeoPoint): Boolean = systems.any { it.serves(point) }

    /** GBFS documents (up to about 1 MB for a citywide vehicle list) are parsed off the caller's thread, which is Main. */
    suspend fun nearby(point: GeoPoint): BikeSharingResult = withContext(Dispatchers.Default) { nearbyNow(point) }

    private suspend fun nearbyNow(point: GeoPoint): BikeSharingResult {
        val serving = systems.filter { it.serves(point) }
        if (serving.isEmpty()) return BikeSharingResult.NotCovered
        val answers = coroutineScope { serving.map { system -> async { nearbyIn(system, point) } }.awaitAll() }.filterNotNull()
        return if (answers.isEmpty()) BikeSharingResult.Unavailable else BikeSharingResult.Loaded(answers)
    }

    private suspend fun nearbyIn(system: BikeSharingSystem, point: GeoPoint): BikeSharingNearby? = try {
        val discovery = read(staticCache, system.discoveryUrl, system) ?: return null
        val feeds = GbfsParser.feeds(discovery.value)
        val types = feeds["vehicle_types"]?.let { url -> read(staticCache, url, system)?.value }?.let(GbfsParser::vehicleTypes).orEmpty()
        var stale = discovery.stale
        var observedAt: Instant? = null
        val docks = run {
            val information = feeds["station_information"]?.let { read(staticCache, it, system) } ?: return@run emptyList()
            val status = feeds["station_status"]?.let { read(statusCache, it, system) } ?: return@run emptyList()
            stale = stale || status.stale
            observedAt = GbfsParser.lastUpdated(status.value) ?: status.fetchedAt
            GbfsParser.stations(information.value, status.value, types)
                .map { it to distance(point, it.latitude, it.longitude) }
                .filter { (_, meters) -> meters <= radiusMeters }
                .sortedBy { it.second }
                .take(maxDocks)
                .map { (station, meters) -> NearbyDock(station.name, meters, station.bikes, station.ebikes, station.docks) }
        }
        val vehicles = run {
            val url = feeds["vehicle_status"] ?: feeds["free_bike_status"] ?: return@run emptyList()
            val status = read(statusCache, url, system) ?: return@run emptyList()
            stale = stale || status.stale
            val updated = GbfsParser.lastUpdated(status.value) ?: status.fetchedAt
            observedAt = observedAt?.let { maxOf(it, updated) } ?: updated
            GbfsParser.vehicles(status.value, types)
                .map { it to distance(point, it.latitude, it.longitude) }
                .filter { (_, meters) -> meters <= radiusMeters }
                .sortedBy { it.second }
                .take(maxVehicles)
                .map { (vehicle, meters) -> NearbyVehicle(vehicle.kind, meters, vehicle.batteryPercent) }
        }
        BikeSharingNearby(system, docks, vehicles, observedAt ?: clock.instant(), stale)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun read(cache: ResilientSourceCache<String>, url: String, system: BikeSharingSystem): Body? {
        if (!sameHost(url, system.discoveryUrl)) return null
        return when (val read = cache.get(url) { fetcher.get(url) }) {
            is SourceRead.Fresh -> Body(read.value, read.fetchedAt, stale = false)
            is SourceRead.Stale -> Body(read.value, read.fetchedAt, stale = true)
            is SourceRead.Failed -> null
        }
    }

    private class Body(val value: String, val fetchedAt: Instant, val stale: Boolean)

    companion object {
        const val DEFAULT_RADIUS_METERS = 300

        /** A sub-feed is read only over HTTPS on the auto-discovery host: a document cannot send the app elsewhere. */
        internal fun sameHost(url: String, discovery: String): Boolean = try {
            val target = URI(url)
            val origin = URI(discovery)
            target.scheme == "https" && target.userInfo == null && target.host != null &&
                target.host.equals(origin.host, ignoreCase = true) && target.port in setOf(-1, 443)
        } catch (_: Exception) {
            false
        }

        internal fun distance(point: GeoPoint, latitude: Double, longitude: Double): Int =
            Math.round(GtfsStationCodeIndex.distanceKm(point.latitude, point.longitude, latitude, longitude) * 1000.0).toInt()

        fun descriptor(system: BikeSharingSystem) = ProviderDescriptor(
            id = system.id,
            displayName = "${system.name} — GBFS",
            capabilities = setOf(ProviderCapability.BIKE_SHARING),
            coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Bici e monopattini in condivisione di ${system.areas.joinToString { it.name }}"),
            accessMode = AccessMode.OPEN_DIRECT,
            ratePolicy = RatePolicy(Duration.ofSeconds(60), defaultTimeToLive = Duration.ofSeconds(60)),
            schemaVersion = 1
        )
    }
}
