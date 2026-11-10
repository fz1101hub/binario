package it.binario.app.wear

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import it.binario.app.IntegrationSettingsStore

/**
 * Receives an explicit refresh request from a paired Wear node. Play services binds this
 * listener; requests are still constrained to the documented path and bounded per node so
 * repeated taps cannot create an unbounded stream of Data Layer writes. The refresh loads the
 * route from the network, so it runs as work: the listener is unbound, and its scope would be
 * cancelled, as soon as the message is delivered.
 */
class WearRefreshRequestListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (!requestGate.accepts(messageEvent.path, messageEvent.sourceNodeId)) return
        if (!IntegrationSettingsStore(applicationContext).read().wearSnapshotSharingEnabled) return
        WearRefreshWorker.enqueue(applicationContext)
    }

    private companion object {
        /** Process-wide: Play services binds a new listener for a delivery, which would reset a per-instance limit. */
        val requestGate = WearRefreshRequestGate()
    }
}

/** One refresh for the watch at a time; sharing is checked again because the work can start later. */
class WearRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (IntegrationSettingsStore(applicationContext).read().wearSnapshotSharingEnabled) {
            WearSnapshotPublisher.refreshForWatch(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "binario-wear-refresh"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WearRefreshWorker>().apply {
                // The user is waiting at the watch: a plain request waits for a Doze maintenance window.
                if (runsExpedited(Build.VERSION.SDK_INT)) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }.build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Expedited from Android 12, where it is an expedited job. Before it WorkManager would run it as a foreground
         * service with a notification from getForegroundInfo(), which this worker does not have.
         */
        internal fun runsExpedited(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S
    }
}

/** Pure and deterministic gate, isolated so request behavior can be tested on the JVM. */
internal class WearRefreshRequestGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val minimumIntervalMillis: Long = MINIMUM_INTERVAL_MILLIS
) {
    private val lastAcceptedAtByNode = mutableMapOf<String, Long>()

    @Synchronized
    fun accepts(path: String, sourceNodeId: String): Boolean {
        if (path != WearSnapshotPublisher.REFRESH_REQUEST_PATH || sourceNodeId.isBlank()) return false
        val now = clock()
        val previous = lastAcceptedAtByNode[sourceNodeId]
        if (previous != null && now - previous < minimumIntervalMillis) return false
        lastAcceptedAtByNode[sourceNodeId] = now
        return true
    }

    private companion object {
        const val MINIMUM_INTERVAL_MILLIS = 15_000L
    }
}
