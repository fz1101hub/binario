package it.binario.app

import android.content.Context
import androidx.core.content.edit
import it.binario.app.shared.SharedData

/** «Mobilità e accessibilità»: default margin, bounds and the panel's choices in contracts/shared-data/v1/mobility-preferences-v1.json. */
data class MobilityPreferences(
    val maxChanges: Int? = null,
    val transferMarginMinutes: Int = SharedData.MobilityPreferences.DEFAULT_TRANSFER_MARGIN_MINUTES,
    val stepFreePreferred: Boolean = false,
    val compactTimetablePreferred: Boolean = false
)

class MobilityPreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("binario_mobility_preferences", Context.MODE_PRIVATE)

    fun read(): MobilityPreferences = runCatching {
        MobilityPreferences(
            maxChanges = prefs.getInt(KEY_MAX_CHANGES, -1).takeIf { it >= 0 },
            transferMarginMinutes = prefs.getInt(KEY_MARGIN, SharedData.MobilityPreferences.DEFAULT_TRANSFER_MARGIN_MINUTES).coerceIn(0, SharedData.MobilityPreferences.MAXIMUM_TRANSFER_MARGIN_MINUTES),
            stepFreePreferred = prefs.getBoolean(KEY_STEP_FREE, false),
            compactTimetablePreferred = prefs.getBoolean(KEY_COMPACT, false)
        )
    }.getOrDefault(MobilityPreferences())

    fun save(value: MobilityPreferences) {
        prefs.edit {
            putInt(KEY_MAX_CHANGES, value.maxChanges ?: -1)
            putInt(KEY_MARGIN, value.transferMarginMinutes.coerceIn(0, SharedData.MobilityPreferences.MAXIMUM_TRANSFER_MARGIN_MINUTES))
            putBoolean(KEY_STEP_FREE, value.stepFreePreferred)
            putBoolean(KEY_COMPACT, value.compactTimetablePreferred)
        }
    }

    private companion object {
        const val KEY_MAX_CHANGES = "max_changes"
        const val KEY_MARGIN = "transfer_margin"
        const val KEY_STEP_FREE = "step_free"
        const val KEY_COMPACT = "compact_timetable"
    }
}
