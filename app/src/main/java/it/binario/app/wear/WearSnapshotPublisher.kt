package it.binario.app.wear

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import it.binario.app.data.RouteSnapshot
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
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
    private const val KEY_PUBLISHING_ALLOWED = "publishing_allowed"
    private val operationGate = WearPublicationGate()
    private val preferenceIntentLock = Any()

    suspend fun publish(context: Context, snapshot: RouteSnapshot) {
        val token = operationGate.publicationToken()
        val generatedAt = System.currentTimeMillis()
        val payload = runCatching { AndroidWearSnapshotContract.operational(snapshot, generatedAt) }
            .getOrElse { AndroidWearSnapshotContract.unavailable(snapshot.routeLabel, snapshot.providerName, generatedAt) }
        publishPayload(context, payload, token)
    }

    suspend fun publishUnavailable(context: Context, routeLabel: String, provider: String) {
        val token = operationGate.publicationToken()
        publishPayload(
            context,
            AndroidWearSnapshotContract.unavailable(routeLabel, provider, System.currentTimeMillis()),
            token
        )
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
                    if (wasPending) remove(KEY_LAST_PAYLOAD)
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
            val payload = readLastPayload(context) ?: return@runPublication false
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
            remove(KEY_REVOCATION_PENDING)
        }
    }

    private suspend fun publishPayload(context: Context, payload: Map<String, Any>, token: Long) {
        operationGate.runPublication(token) {
            val preferences = preferences(context)
            if (!preferences.getBoolean(KEY_PUBLISHING_ALLOWED, false)) return@runPublication
            if (preferences.getBoolean(KEY_REVOCATION_PENDING, false)) return@runPublication
            runWearSafely { putPayload(context, payload) }
            preferences.edit(commit = true) {
                putString(KEY_LAST_PAYLOAD, JSONObject(payload).toString())
            }
        }
    }

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
