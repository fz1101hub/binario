package it.binario.app.plannedjourney

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Agenda preferences of this device; never part of the journeys' JSON or ICS files. */
interface PlannedJourneyPreferences {
    /** The reminder lead proposed for a new journey; null proposes none (also when nothing was chosen yet). */
    fun defaultReminder(): PlannedJourneyReminderMinutes?
    fun setDefaultReminder(value: PlannedJourneyReminderMinutes?)
}

class SharedPreferencesPlannedJourneyPreferences(context: Context) : PlannedJourneyPreferences {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        "binario_planned_journey_store_v1",
        Context.MODE_PRIVATE
    )

    override fun defaultReminder(): PlannedJourneyReminderMinutes? =
        PlannedJourneyReminderMinutes.fromMinutes(preferences.getInt(DEFAULT_REMINDER_KEY, 0))

    override fun setDefaultReminder(value: PlannedJourneyReminderMinutes?) {
        preferences.edit {
            if (value == null) remove(DEFAULT_REMINDER_KEY) else putInt(DEFAULT_REMINDER_KEY, value.minutes)
        }
    }

    internal companion object {
        const val DEFAULT_REMINDER_KEY = "binario-planned-journeys-default-reminder-v1"
    }
}

/** The reminder leads the editor and the default-lead choice offer, «Nessuno» first. */
internal val plannedJourneyReminderChoices: List<PlannedJourneyReminderMinutes?> = listOf(
    null,
    PlannedJourneyReminderMinutes.TEN,
    PlannedJourneyReminderMinutes.FIFTEEN,
    PlannedJourneyReminderMinutes.THIRTY,
    PlannedJourneyReminderMinutes.SIXTY
)
