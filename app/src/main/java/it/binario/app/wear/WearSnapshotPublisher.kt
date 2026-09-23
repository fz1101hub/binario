package it.binario.app.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.orderServicesForDisplay
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
        publishPayload(context, WearSnapshotPayload.from(snapshot))
    }

    suspend fun publishUnavailable(context: Context, routeLabel: String, provider: String) {
        publishPayload(
            context,
            WearSnapshotPayload.unavailable(routeLabel, provider, System.currentTimeMillis())
        )
    }

    /** Reissues the locally retained bounded payload after an accepted Wear request. */
    suspend fun republishLastSnapshot(context: Context): Boolean {
        val payload = readLastPayload(context) ?: return false
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

    private suspend fun publishPayload(context: Context, payload: Map<String, Any?>) {
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
                    else -> error("Unsupported Wear payload value for $key")
                }
            }
            // Makes an explicit reissue visible even when business data is unchanged.
            dataMap.putLong("publishedAt", System.currentTimeMillis())
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

    internal object WearSnapshotPayload {
        private const val VERSION = 1L
        private const val MAX_SERVICES = 2

        fun from(snapshot: RouteSnapshot): Map<String, Any?> = buildMap {
            put("version", VERSION)
            put("routeLabel", snapshot.routeLabel)
            put("provider", snapshot.providerName)
            put("freshness", snapshot.freshness.name)
            put("confidence", snapshot.confidence.name)
            put("retrievedAt", snapshot.retrievedAtEpochMillis)
            put("notice", snapshot.notice.orEmpty())
            val services = orderServicesForDisplay(snapshot.services).take(MAX_SERVICES)
            put("serviceCount", services.size)
            services.forEachIndexed { index, service ->
                put("service${index}Number", service.number)
                put("service${index}Operator", service.operator.accessibleLabel)
                put("service${index}Category", service.trainCategory.accessibleLabel)
                put("service${index}Destination", service.destination)
                put("service${index}Expected", service.expectedDeparture)
                put("service${index}Status", if (service.operationalStatusVerified) service.status.accessibleLabel else "Programmato")
                put("service${index}Platform", service.platform.orEmpty())
            }
        }

        fun unavailable(routeLabel: String, provider: String, retrievedAtEpochMillis: Long): Map<String, Any?> = mapOf(
            "version" to VERSION,
            "routeLabel" to routeLabel,
            "provider" to provider,
            "freshness" to "UNAVAILABLE",
            "confidence" to "CACHED",
            "retrievedAt" to retrievedAtEpochMillis,
            "notice" to "dati non disponibili in tempo reale",
            "serviceCount" to 0
        )
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
