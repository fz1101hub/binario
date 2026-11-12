package it.binario.app

import it.binario.app.data.Station
import it.binario.app.data.StationAutocompleteEngine
import it.binario.app.data.StationCanonicalizer

/**
 * The station list of the app and its two indexes (autocomplete, canonical identity), built together from the curated
 * catalogue and the stations the user remembered. Building them normalizes every name and alias of some 5,000
 * stations: [build] is meant for a background dispatcher, the view model only assigns the result on the main thread.
 */
internal class StationIndexes private constructor(
    val stations: List<Station>,
    val autocomplete: StationAutocompleteEngine,
    val canonicalizer: StationCanonicalizer,
    /** The remembered stations the indexes were built from, to notice one remembered meanwhile. */
    val remembered: List<Station>
) {
    companion object {
        /**
         * [catalogue] first, then the [remembered] stations it does not know (free text, search index, city stops);
         * a remembered station that the catalogue knows under another identifier becomes the catalogue's.
         */
        fun build(catalogue: List<Station>, remembered: List<Station>): StationIndexes {
            val catalogueCanonicalizer = StationCanonicalizer(catalogue)
            val catalogueIds = catalogue.mapTo(HashSet()) { it.id }
            val others = remembered.map(catalogueCanonicalizer::canonicalize).filterNot { it.id in catalogueIds }.distinctBy { it.id }
            val stations = catalogue + others
            return StationIndexes(
                stations = stations,
                autocomplete = StationAutocompleteEngine(stations),
                // Without other stations the list is the catalogue: its canonicalizer is not built a second time.
                canonicalizer = if (others.isEmpty()) catalogueCanonicalizer else StationCanonicalizer(stations),
                remembered = remembered
            )
        }
    }
}
