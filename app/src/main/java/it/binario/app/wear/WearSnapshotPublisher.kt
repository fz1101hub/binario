package it.binario.app.wear

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.RouteSnapshotCache
import it.binario.app.data.SavedRoute
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.reliability.NetworkReliabilityRepository
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.widget.ReliabilityMark
import it.binario.app.widget.StationWidgetCodec
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Superficie derivata del profilo gestito dall’app principale: invia soltanto
 * tratta attiva e due corse, mai credenziali, biglietti, posizione o trascrizioni.
 */
object WearSnapshotPublisher {
    const val SNAPSHOT_PATH = "/binario/wear/snapshot"
    const val REFRESH_REQUEST_PATH = "/binario/wear/request-refresh"
    internal const val SNAPSHOT_DELETE_URI = "wear://*$SNAPSHOT_PATH"
    internal const val KEY_REVOCATION_PENDING = "revocation_pending"
    private const val TAG = "BinarioWearSync"
    private const val PREFERENCES = "binario_wear_publisher_v1"
    private const val KEY_LAST_PAYLOAD = "last_payload"
    private const val KEY_LAST_ROUTE = "last_route"
    private const val KEY_PUBLISHING_ALLOWED = "publishing_allowed"
    private const val CAPABILITY_TIMEOUT_MILLIS = 3_000L
    private val operationGate = WearPublicationGate()
    private val preferenceIntentLock = Any()

    suspend fun publish(context: Context, snapshot: RouteSnapshot) {
        val token = operationGate.publicationToken()
        val generatedAt = System.currentTimeMillis()
        val strings = WearSnapshotStrings.from(context.resources)
        val reliability = reliabilityFor(context, snapshot)
        val payload = runCatching { AndroidWearSnapshotContract.operational(snapshot, generatedAt, strings, reliability) }
            .getOrElse { AndroidWearSnapshotContract.unavailable(snapshot.routeLabel, snapshot.providerName, generatedAt, strings) }
        publishPayload(context, payload, token, WearRefreshTarget(snapshot.route, snapshot.direction).encode())
    }

    /** A null provider means nothing was ever cached; the payload then carries the localized "not available". */
    suspend fun publishUnavailable(context: Context, routeLabel: String, provider: String?) {
        val token = operationGate.publicationToken()
        // The route stays refreshable from the watch only while the payload still names it.
        val refreshTarget = readRefreshTarget(context)?.takeIf { it.routeLabel == routeLabel }?.encode()
        publishPayload(
            context,
            AndroidWearSnapshotContract.unavailable(routeLabel, provider.orEmpty(), System.currentTimeMillis(), WearSnapshotStrings.from(context.resources)),
            token,
            refreshTarget
        )
    }

    /**
     * Answers a refresh request from the watch with a new load of the route the watch shows, so the
     * Data Item carries a new generatedAt: the Data Layer raises no DATA_CHANGED for an unchanged
     * item, which made reissuing the retained payload a no-op on the watch. A failed load publishes
     * "not available" for that route, as a failed refresh in the app does; without a stored route
     * (a payload from an earlier version) the retained payload is reissued as before.
     */
    suspend fun refreshForWatch(context: Context, source: TrainDataSource = BinarioDataGraph.routeDataSource(context)): Boolean {
        val preferences = preferences(context)
        val target = readRefreshTarget(context)
        if (
            target == null ||
            !preferences.getBoolean(KEY_PUBLISHING_ALLOWED, false) ||
            preferences.getBoolean(KEY_REVOCATION_PENDING, false)
        ) return republishLastSnapshot(context)
        when (val result = source.loadRoute(target.route, target.direction)) {
            is DataResult.Success -> publish(context, result.snapshot)
            is DataResult.Failure -> publishUnavailable(context, target.routeLabel, RouteSnapshotCache(context).lastProvider())
        }
        return true
    }

    /** Enables publishing only after any persisted revocation has completed. */
    suspend fun enable(context: Context): Boolean {
        val intent = operationGate.revokePublications()
        return operationGate.runExclusive {
            if (!operationGate.isCurrent(intent)) return@runExclusive false
            val preferences = preferences(context)
            val wasPending = preferences.getBoolean(KEY_REVOCATION_PENDING, false)
            if (wasPending && !deleteSnapshot(context)) return@runExclusive false
            synchronized(preferenceIntentLock) {
                if (!operationGate.isCurrent(intent)) return@runExclusive false
                preferences.edit(commit = true) {
                    putBoolean(KEY_PUBLISHING_ALLOWED, true)
                    remove(KEY_REVOCATION_PENDING)
                    if (wasPending) {
                        remove(KEY_LAST_PAYLOAD)
                        remove(KEY_LAST_ROUTE)
                    }
                }
            }
            true
        }
    }

    /** Reissues the locally retained bounded payload only while sharing and TTL remain valid. */
    suspend fun republishLastSnapshot(context: Context): Boolean {
        val token = operationGate.publicationToken()
        return operationGate.runPublication(token) {
            val preferences = preferences(context)
            if (preferences.getBoolean(KEY_REVOCATION_PENDING, false)) {
                if (deleteSnapshot(context)) {
                    finishRevocation(context)
                }
                return@runPublication false
            }
            if (!preferences.getBoolean(KEY_PUBLISHING_ALLOWED, false)) return@runPublication false
            val stored = readLastPayload(context) ?: return@runPublication false
            // A watch app of an earlier version, connected since, would reject the optional reliability pair.
            val payload = if (AndroidWearSnapshotContract.hasReliability(stored) && !watchesDecodeReliability(context)) {
                AndroidWearSnapshotContract.withoutReliability(stored)
            } else stored
            val expiresAt = (payload["expiresAtEpochMillis"] as? Number)?.toLong() ?: return@runPublication false
            if (expiresAt <= System.currentTimeMillis()) return@runPublication false
            runWearSafely { putPayload(context, payload) }
        } ?: false
    }

    /** Marks sharing revoked before deleting; failures remain pending and are retried later. */
    fun persistRevocationIntent(context: Context) {
        synchronized(preferenceIntentLock) {
            operationGate.revokePublications()
            preferences(context).edit(commit = true) {
                putBoolean(KEY_PUBLISHING_ALLOWED, false)
                putBoolean(KEY_REVOCATION_PENDING, true)
            }
        }
    }

    suspend fun clear(context: Context) {
        persistRevocationIntent(context)
        operationGate.runExclusive {
            if (deleteSnapshot(context)) {
                finishRevocation(context)
            }
        }
    }

    private fun finishRevocation(context: Context) {
        preferences(context).edit(commit = true) {
            remove(KEY_LAST_PAYLOAD)
            remove(KEY_LAST_ROUTE)
            remove(KEY_REVOCATION_PENDING)
        }
    }

    private suspend fun publishPayload(context: Context, payload: Map<String, Any>, token: Long, refreshTarget: String?) {
        operationGate.runPublication(token) {
            val preferences = preferences(context)
            if (!preferences.getBoolean(KEY_PUBLISHING_ALLOWED, false)) return@runPublication
            if (preferences.getBoolean(KEY_REVOCATION_PENDING, false)) return@runPublication
            runWearSafely { putPayload(context, payload) }
            preferences.edit(commit = true) {
                putString(KEY_LAST_PAYLOAD, JSONObject(payload).toString())
                if (refreshTarget != null) putString(KEY_LAST_ROUTE, refreshTarget) else remove(KEY_LAST_ROUTE)
            }
        }
    }

    /**
     * «In orario N volte su M» for the services of [snapshot], from the embedded statistics (read on the phone, no
     * request), only when every connected watch decodes it and the build keeps the statistics.
     */
    private suspend fun reliabilityFor(context: Context, snapshot: RouteSnapshot): (TrainService) -> ReliabilityMark? {
        if (!NetworkReliabilityRepository.enabled || !watchesDecodeReliability(context)) return { null }
        val stats = runCatching { NetworkReliabilityRepository.load(context) }.getOrNull() ?: return { null }
        return AndroidWearSnapshotContract.reliabilityOf(stats, snapshot)
    }

    private suspend fun watchesDecodeReliability(context: Context): Boolean = try {
        withTimeoutOrNull(CAPABILITY_TIMEOUT_MILLIS) {
            val connected = Wearable.getNodeClient(context).connectedNodes.awaitTask().mapTo(hashSetOf()) { it.id }
            val capable = Wearable.getCapabilityClient(context)
                .getCapability(AndroidWearSnapshotContract.RELIABILITY_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .awaitTask().nodes.mapTo(hashSetOf()) { it.id }
            AndroidWearSnapshotContract.watchesDecodeReliability(connected, capable)
        } == true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private fun readRefreshTarget(context: Context): WearRefreshTarget? =
        preferences(context).getString(KEY_LAST_ROUTE, null)?.let(WearRefreshTarget::decode)

    private suspend fun deleteSnapshot(context: Context): Boolean = runWearSafely {
        Wearable.getDataClient(context)
            .deleteDataItems(SNAPSHOT_DELETE_URI.toUri())
            .awaitTask()
    }

    private suspend fun putPayload(context: Context, payload: Map<String, Any?>) {
        val request = PutDataMapRequest.create(SNAPSHOT_PATH).apply {
            payload.forEach { (key, value) ->
                when (value) {
                    is String -> dataMap.putString(key, value)
                    is Int -> dataMap.putInt(key, value)
                    is Long -> dataMap.putLong(key, value)
                    else -> error("Unsupported Wear contract value for $key")
                }
            }
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).awaitTask()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun readLastPayload(context: Context): Map<String, Any?>? = runCatching {
        val raw = preferences(context).getString(KEY_LAST_PAYLOAD, null) ?: return null
        val json = JSONObject(raw)
        json.keys().asSequence().associateWith { json.get(it) }
    }.getOrNull()

    private suspend fun runWearSafely(operation: suspend () -> Unit): Boolean = try {
        operation()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Log.w(TAG, "Sincronizzazione smartwatch non disponibile")
        false
    }
}

/**
 * The route and direction of the last shared snapshot, kept next to its payload (and removed with
 * it on revocation) so a refresh asked from the watch reloads what the watch shows even when the
 * app's screen is closed. Stations use the widget codec, which keeps the catalogue identifiers the
 * route sources need.
 */
internal data class WearRefreshTarget(val route: SavedRoute, val direction: Direction) {
    val routeLabel: String get() = route.displayName(direction)

    fun encode(): String = JSONObject()
        .put("id", route.id)
        .put("name", route.name)
        .put("origin", StationWidgetCodec.encodeStation(route.outwardOrigin))
        .put("destination", StationWidgetCodec.encodeStation(route.outwardDestination))
        .put("direction", direction.name)
        .toString()

    companion object {
        fun decode(value: String): WearRefreshTarget? = runCatching {
            val json = JSONObject(value)
            val origin = StationWidgetCodec.decodeStation(json.getString("origin")) ?: return null
            val destination = StationWidgetCodec.decodeStation(json.getString("destination")) ?: return null
            val id = json.getString("id").takeIf(String::isNotBlank) ?: return null
            WearRefreshTarget(SavedRoute(id, json.getString("name"), origin, destination), Direction.valueOf(json.getString("direction")))
        }.getOrNull()
    }
}

internal class WearPublicationGate {
    private val mutex = Mutex()
    private val generation = AtomicLong()

    fun publicationToken(): Long = generation.get()

    fun revokePublications(): Long = generation.incrementAndGet()

    fun isCurrent(token: Long): Boolean = token == generation.get()

    suspend fun <T> runPublication(token: Long, operation: suspend () -> T): T? {
        mutex.lock()
        return try {
            if (token != generation.get()) null else operation()
        } finally {
            mutex.unlock()
        }
    }

    suspend fun <T> runExclusive(operation: suspend () -> T): T {
        mutex.lock()
        return try {
            operation()
        } finally {
            mutex.unlock()
        }
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T = suspendCoroutine { continuation ->
    addOnSuccessListener(continuation::resume)
    addOnFailureListener(continuation::resumeWithException)
    addOnCanceledListener {
        continuation.resumeWithException(CancellationException("Operazione Wear Data Layer annullata"))
    }
}
