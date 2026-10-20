package it.binario.app

import it.binario.app.data.Direction

/**
 * Envelope locale dell'app principale da cui derivano payload separati per ciascun companion.
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
