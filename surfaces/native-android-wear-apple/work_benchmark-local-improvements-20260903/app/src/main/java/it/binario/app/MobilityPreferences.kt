package it.binario.app

import android.content.Context
import androidx.core.content.edit

data class MobilityPreferences(
    val maxChanges: Int? = null,
    val transferMarginMinutes: Int = 10,
    val stepFreePreferred: Boolean = false,
    val compactTimetablePreferred: Boolean = false
) {
    val accessibleSummary: String
        get() = "${maxChanges?.let { "Massimo $it cambi" } ?: "Numero cambi libero"}, margine di interscambio $transferMarginMinutes minuti${if (stepFreePreferred) ", priorità percorsi senza barriere" else ""}${if (compactTimetablePreferred) ", tabellone compatto" else ""}."
}

class MobilityPreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("binario_mobility_preferences", Context.MODE_PRIVATE)

    fun read(): MobilityPreferences = MobilityPreferences(
        maxChanges = prefs.getInt(KEY_MAX_CHANGES, -1).takeIf { it >= 0 },
        transferMarginMinutes = prefs.getInt(KEY_MARGIN, 10).coerceIn(0, 45),
        stepFreePreferred = prefs.getBoolean(KEY_STEP_FREE, false),
        compactTimetablePreferred = prefs.getBoolean(KEY_COMPACT, false)
    )

    fun save(value: MobilityPreferences) {
        prefs.edit {
            putInt(KEY_MAX_CHANGES, value.maxChanges ?: -1)
            putInt(KEY_MARGIN, value.transferMarginMinutes.coerceIn(0, 45))
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
