package it.binario.app

import android.content.Context
import androidx.core.content.edit
import it.binario.app.data.gtfs.EmbeddedGtfsPacks

/** Preferences of the optional public sources; kept on the device like every other Binario preference. */
class SourcesSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var destinationWeatherEnabled: Boolean
        get() = preferences.getBoolean(KEY_DESTINATION_WEATHER, true)
        set(value) = preferences.edit { putBoolean(KEY_DESTINATION_WEATHER, value) }

    /**
     * "Includi fermate di metro e tram nella ricerca": the stops of the city packs among the station suggestions.
     * On by default in personal builds, off otherwise (the journeys still use the city lines either way).
     */
    var urbanStopsInSearch: Boolean
        get() = preferences.getBoolean(KEY_URBAN_STOPS, EmbeddedGtfsPacks.personalSourcesIncluded)
        set(value) = preferences.edit { putBoolean(KEY_URBAN_STOPS, value) }

    private companion object {
        const val PREFERENCES = "binario_public_sources"
        const val KEY_DESTINATION_WEATHER = "destination_weather_enabled"
        const val KEY_URBAN_STOPS = "urban_stops_in_search"
    }
}
