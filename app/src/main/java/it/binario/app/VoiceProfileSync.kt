package it.binario.app

import it.binario.app.data.Direction
import java.util.UUID

/** Stato locale: nessuna rete parte finché non esiste un collegamento autenticato esplicito. */
enum class VoiceProfileSyncReadiness(val accessibleLabel: String) {
    DISABLED("Profilo remoto non richiesto"),
    ACCOUNT_LINK_REQUIRED("Collegamento account richiesto"),
    READY_FOR_AUTHENTICATED_SYNC("Pronto per la sincronizzazione autenticata")
}

/** Payload compatibile con Voice Bridge `profile.upsert`, privo di segreti e dati di circolazione. */
data class VoiceBridgeProfileRequest(
    val schemaVersion: Int,
    val activeRouteId: String,
    val direction: Direction,
    val monitoredTrainNumbers: List<String>,
    val wearSnapshotSharingEnabled: Boolean,
    val voiceProfileSyncRequested: Boolean,
    val appleWatchSetupRequested: Boolean,
    val expectedRevision: Int? = null,
    val clientMutationId: String = UUID.randomUUID().toString()
)

fun UnifiedBinarioProfile.toVoiceBridgeProfileRequest(
    expectedRevision: Int? = null,
    clientMutationId: String = UUID.randomUUID().toString()
): VoiceBridgeProfileRequest = VoiceBridgeProfileRequest(
    schemaVersion = schemaVersion,
    activeRouteId = activeRouteId,
    direction = direction,
    monitoredTrainNumbers = monitoredTrainNumbers.distinct().take(32),
    wearSnapshotSharingEnabled = wearSnapshotSharingEnabled,
    voiceProfileSyncRequested = voiceProfileSyncRequested,
    appleWatchSetupRequested = appleWatchSetupRequested,
    expectedRevision = expectedRevision,
    clientMutationId = clientMutationId
)
