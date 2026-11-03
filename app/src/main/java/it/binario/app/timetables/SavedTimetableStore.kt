package it.binario.app.timetables

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * "Orari salvati": the saved result lists, in the app's private preferences on this device. They are never sent
 * anywhere; they leave the device only in the local backup the user exports. One process-wide state, so that Per te,
 * the results and a backup import always show the same list.
 */
object SavedTimetableStore {
    private const val PREFERENCES = "binario_saved_timetables"
    private const val KEY = "timetables"

    private var loaded = false
    var timetables by mutableStateOf<List<SavedTimetable>>(emptyList())
        private set

    fun all(context: Context): List<SavedTimetable> {
        if (!loaded) {
            timetables = SavedTimetableCodec.decode(preferences(context).getString(KEY, null))
            loaded = true
        }
        return timetables
    }

    fun find(context: Context, id: String): SavedTimetable? = all(context).firstOrNull { it.id == id }

    fun save(context: Context, timetable: SavedTimetable) = write(context, SavedTimetableCodec.withSaved(all(context), timetable))

    fun rename(context: Context, id: String, name: String) = write(context, SavedTimetableCodec.renamed(all(context), id, name))

    fun delete(context: Context, id: String) = write(context, all(context).filterNot { it.id == id })

    /** A backup import replaces the list as a whole. */
    fun replace(context: Context, value: List<SavedTimetable>) = write(context, value.take(SavedTimetableCodec.MAX_TIMETABLES))

    private fun write(context: Context, value: List<SavedTimetable>) {
        preferences(context).edit { putString(KEY, SavedTimetableCodec.encode(value)) }
        timetables = value
        loaded = true
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
