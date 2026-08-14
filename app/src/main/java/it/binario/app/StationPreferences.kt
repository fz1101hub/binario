package it.binario.app

import android.content.Context
import androidx.core.content.edit
import it.binario.app.data.Station

class StationPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("binario_station_preferences_v1", Context.MODE_PRIVATE)

    fun favourites(stations: List<Station>): List<Station> {
        val ids = preferences.getStringSet(KEY_FAVOURITES, emptySet()).orEmpty()
        return stations.filter { it.id in ids }
    }

    fun isFavourite(station: Station): Boolean = station.id in preferences.getStringSet(KEY_FAVOURITES, emptySet()).orEmpty()

    fun favouriteIds(): Set<String> = preferences.getStringSet(KEY_FAVOURITES, emptySet()).orEmpty().toSet()

    fun toggle(station: Station) {
        val updated = preferences.getStringSet(KEY_FAVOURITES, emptySet()).orEmpty().toMutableSet()
        if (!updated.add(station.id)) updated.remove(station.id)
        preferences.edit { putStringSet(KEY_FAVOURITES, updated) }
    }

    fun replaceFavourites(ids: Set<String>) {
        preferences.edit { putStringSet(KEY_FAVOURITES, ids) }
    }

    private companion object {
        const val KEY_FAVOURITES = "station_favourites"
    }
}
