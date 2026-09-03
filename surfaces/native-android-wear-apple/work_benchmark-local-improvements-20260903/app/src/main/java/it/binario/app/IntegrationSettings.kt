package it.binario.app

import android.content.Context
import androidx.core.content.edit

/**
 * Preferenze di superficie, non credenziali. I segreti Alexa restano nel backend.
 */
data class IntegrationSettings(
    val wearSnapshotSharingEnabled: Boolean = true,
    val voiceProfileSyncRequested: Boolean = false,
    val appleWatchSetupRequested: Boolean = false
)

class IntegrationSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_integrations_v1", Context.MODE_PRIVATE)

    fun read(): IntegrationSettings = IntegrationSettings(
        wearSnapshotSharingEnabled = preferences.getBoolean(KEY_WEAR_SHARING, true),
        voiceProfileSyncRequested = preferences.getBoolean(KEY_VOICE_PROFILE, false),
        appleWatchSetupRequested = preferences.getBoolean(KEY_APPLE_WATCH, false)
    )

    fun save(settings: IntegrationSettings) {
        preferences.edit {
            putBoolean(KEY_WEAR_SHARING, settings.wearSnapshotSharingEnabled)
            putBoolean(KEY_VOICE_PROFILE, settings.voiceProfileSyncRequested)
            putBoolean(KEY_APPLE_WATCH, settings.appleWatchSetupRequested)
        }
    }

    private companion object {
        const val KEY_WEAR_SHARING = "wear_snapshot_sharing"
        const val KEY_VOICE_PROFILE = "voice_profile_sync"
        const val KEY_APPLE_WATCH = "apple_watch_setup"
    }
}
