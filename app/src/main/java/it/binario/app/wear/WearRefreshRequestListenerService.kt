package it.binario.app.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import it.binario.app.IntegrationSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Receives an explicit refresh request from a paired Wear node. Play services binds this
 * listener; requests are still constrained to the documented path and bounded per node so
 * repeated taps cannot create an unbounded stream of Data Layer writes.
 */
class WearRefreshRequestListenerService : WearableListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestGate = WearRefreshRequestGate()

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (!requestGate.accepts(messageEvent.path, messageEvent.sourceNodeId)) return
        if (!IntegrationSettingsStore(applicationContext).read().wearSnapshotSharingEnabled) return
        serviceScope.launch {
            WearSnapshotPublisher.republishLastSnapshot(applicationContext)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
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
