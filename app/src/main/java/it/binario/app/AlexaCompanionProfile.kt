package it.binario.app

import it.binario.app.data.Direction
import java.util.UUID

/** Stato locale Alexa: nessuna rete parte finché non esiste un collegamento autenticato esplicito. */
enum class VoiceProfileSyncReadiness(val accessibleLabel: String) {
    DISABLED("Profilo remoto non richiesto"),
    ACCOUNT_LINK_REQUIRED("Collegamento account richiesto"),
    READY_FOR_AUTHENTICATED_SYNC("Pronto per la sincronizzazione autenticata")
}

/** Payload compatibile con Alexa Companion `profile.upsert`, privo di segreti e preferenze smartwatch. */
data class AlexaCompanionProfileRequest(
    val schemaVersion: Int,
    val activeRouteId: String,
    val direction: Direction,
    val monitoredTrainNumbers: List<String>,
    val expectedRevision: Int? = null,
    val clientMutationId: String = UUID.randomUUID().toString()
)

fun UnifiedBinarioProfile.toAlexaCompanionProfileRequest(
    expectedRevision: Int? = null,
    clientMutationId: String = UUID.randomUUID().toString()
): AlexaCompanionProfileRequest = AlexaCompanionProfileRequest(
    schemaVersion = schemaVersion,
    activeRouteId = activeRouteId,
    direction = direction,
    monitoredTrainNumbers = monitoredTrainNumbers.distinct().take(32),
    expectedRevision = expectedRevision,
    clientMutationId = clientMutationId
)
