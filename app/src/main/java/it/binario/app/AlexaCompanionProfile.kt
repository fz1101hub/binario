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
    monitoredTrainNumbers = monitoredTrainNumbers.mapNotNull(::alexaCompanionTrainNumber).distinct().take(32),
    expectedRevision = expectedRevision,
    clientMutationId = clientMutationId
)

/**
 * A monitored number in the only form the Alexa Companion accepts: letters, digits and dashes,
 * at most 32 of them, after whitespace removal (`profile.upsert` rejects the whole profile on a
 * single other entry). Operator labels such as Trenord's "RE_80 - 2551" keep the train number
 * that ends them; anything else that cannot be sent, such as a raw GTFS trip id, is dropped.
 */
internal fun alexaCompanionTrainNumber(number: String): String? {
    val compact = number.filterNot(Char::isWhitespace)
    if (ALEXA_TRAIN_NUMBER.matches(compact)) return compact
    return LABELLED_TRAIN_NUMBER.find(number)?.groupValues?.get(1)
}

private val ALEXA_TRAIN_NUMBER = Regex("[A-Za-z0-9-]{1,32}")
private val LABELLED_TRAIN_NUMBER = Regex("\\s[-/]?\\s*(\\d{1,6})\\s*$")
