package it.binario.app.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.orderServicesForDisplay
import kotlinx.coroutines.suspendCancellableCoroutine
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

    suspend fun publish(context: Context, snapshot: RouteSnapshot) {
        runWearSafely {
            val request = PutDataMapRequest.create(SNAPSHOT_PATH).apply {
                dataMap.putLong("version", 1L)
                dataMap.putString("routeLabel", snapshot.routeLabel)
                dataMap.putString("provider", snapshot.providerName)
                dataMap.putString("freshness", snapshot.freshness.name)
                dataMap.putString("confidence", snapshot.confidence.name)
                dataMap.putLong("retrievedAt", snapshot.retrievedAtEpochMillis)
                dataMap.putString("notice", snapshot.notice.orEmpty())
                val services = orderServicesForDisplay(snapshot.services).take(2)
                dataMap.putInt("serviceCount", services.size)
                services.forEachIndexed { index, service ->
                    dataMap.putString("service${index}Number", service.number)
                    dataMap.putString("service${index}Operator", service.operator.accessibleLabel)
                    dataMap.putString("service${index}Category", service.trainCategory.accessibleLabel)
                    dataMap.putString("service${index}Destination", service.destination)
                    dataMap.putString("service${index}Expected", service.expectedDeparture)
                    dataMap.putString("service${index}Status", if (snapshot.confidence == it.binario.app.data.DataConfidence.STATIC_ONLY) "Programmato" else service.status.accessibleLabel)
                    dataMap.putInt("service${index}Delay", service.delayMinutes)
                    dataMap.putString("service${index}Platform", service.platform.orEmpty())
                }
            }.asPutDataRequest()
            Wearable.getDataClient(context).putDataItem(request).awaitTask()
        }
    }

    suspend fun publishUnavailable(context: Context, routeLabel: String, provider: String) {
        runWearSafely {
            val request = PutDataMapRequest.create(SNAPSHOT_PATH).apply {
                dataMap.putLong("version", 1L)
                dataMap.putString("routeLabel", routeLabel)
                dataMap.putString("provider", provider)
                dataMap.putString("freshness", "UNAVAILABLE")
                dataMap.putString("confidence", "CACHED")
                dataMap.putLong("retrievedAt", System.currentTimeMillis())
                dataMap.putString("notice", "dati non disponibili in tempo reale")
                dataMap.putInt("serviceCount", 0)
            }.asPutDataRequest()
            Wearable.getDataClient(context).putDataItem(request).awaitTask()
        }
    }

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
