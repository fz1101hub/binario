package it.binario.app.data.gtfs

import it.binario.app.data.StationSearchNormalizer

/**
 * The normalisation of a station name for the stops the user adds from the packs. The search over the packs' stops that
 * used to live here was never called (it opened every pack at each keystroke): stations are searched in the catalogue
 * and city stops in [EmbeddedUrbanStopIndex].
 */
object EmbeddedGtfsStationSearchIndex {
    fun normalize(value: String): String = StationSearchNormalizer.normalize(value)
}
