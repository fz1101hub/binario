package it.binario.app

import it.binario.app.data.Direction

/**
 * Envelope di dominio per una futura sincronizzazione autenticata con Voice Bridge.
 * Non contiene token, coordinate, biglietti, audio o storico delle interrogazioni.
 */
data class UnifiedBinarioProfile(
    val schemaVersion: Int = 1,
    val activeRouteId: String,
    val direction: Direction,
    val monitoredTrainNumbers: List<String>,
    val wearSnapshotSharingEnabled: Boolean,
    val voiceProfileSyncRequested: Boolean,
    val appleWatchSetupRequested: Boolean
)
