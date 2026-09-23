package it.binario.app.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import it.binario.app.data.RouteSnapshot
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Superficie derivata del profilo gestito dall’app principale: invia soltanto
 * tratta attiva e due corse, mai credenziali, biglietti, posizione o trascrizioni.
 */
object WearSnapshotPublisher {
    const val SNAPSHOT_PATH = "/binario/wear/snapshot"
    const val REFRESH_REQUEST_PATH = "/binario/wear/request-refresh"
    private const val TAG = "BinarioWearSync"
    private const val PREFERENCES = "binario_wear_publisher_v1"
    private const val KEY_LAST_PAYLOAD = "last_payload"

    suspend fun publish(context: Context, snapshot: RouteSnapshot) {
        val generatedAt = System.currentTimeMillis()
        val payload = runCatching { AndroidWearSnapshotContract.operational(snapshot, generatedAt) }
            .getOrElse { AndroidWearSnapshotContract.unavailable(snapshot.routeLabel, snapshot.providerName, generatedAt) }
        publishPayload(context, payload)
    }

    suspend fun publishUnavailable(context: Context, routeLabel: String, provider: String) {
        publishPayload(
            context,
            AndroidWearSnapshotContract.unavailable(routeLabel, provider, System.currentTimeMillis())
        )
    }

    /** Reissues the locally retained bounded payload only while its explicit TTL is still valid. */
    suspend fun republishLastSnapshot(context: Context): Boolean {
        val payload = readLastPayload(context) ?: return false
        val expiresAt = (payload["expiresAtEpochMillis"] as? Number)?.toLong() ?: return false
        if (expiresAt <= System.currentTimeMillis()) return false
        return runWearSafely { putPayload(context, payload) }
    }

    /** Deletes the Data Layer snapshot and the local payload when consent is withdrawn. */
    suspend fun clear(context: Context) {
        runWearSafely {
            Wearable.getDataClient(context)
                .deleteDataItems(Uri.Builder().scheme("wear").path(SNAPSHOT_PATH).build())
                .awaitTask()
        }
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            remove(KEY_LAST_PAYLOAD)
        }
    }

    private suspend fun publishPayload(context: Context, payload: Map<String, Any>) {
        runWearSafely { putPayload(context, payload) }
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(KEY_LAST_PAYLOAD, JSONObject(payload).toString())
        }
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
        }.asPutDataRequest()
        Wearable.getDataClient(context).putDataItem(request).awaitTask()
    }

    private fun readLastPayload(context: Context): Map<String, Any?>? = runCatching {
        val raw = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PAYLOAD, null) ?: return null
        val json = JSONObject(raw)
        json.keys().asSequence().associateWith { json.get(it) }
    }.getOrNull()

    private suspend fun runWearSafely(operation: suspend () -> Unit): Boolean = try {
        operation()
        true
    } catch (_: Exception) {
        Log.w(TAG, "Sincronizzazione smartwatch non disponibile")
        false
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
