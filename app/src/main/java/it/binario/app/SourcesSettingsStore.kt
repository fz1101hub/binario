package it.binario.app

import android.content.Context
import androidx.core.content.edit

/** Preferences of the optional public sources; kept on the device like every other Binario preference. */
class SourcesSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var destinationWeatherEnabled: Boolean
        get() = preferences.getBoolean(KEY_DESTINATION_WEATHER, true)
        set(value) = preferences.edit { putBoolean(KEY_DESTINATION_WEATHER, value) }

    private companion object {
        const val PREFERENCES = "binario_public_sources"
        const val KEY_DESTINATION_WEATHER = "destination_weather_enabled"
    }
}
