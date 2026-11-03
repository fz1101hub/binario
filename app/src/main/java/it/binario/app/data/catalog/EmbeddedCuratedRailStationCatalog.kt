package it.binario.app.data.catalog

import it.binario.app.data.Station
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/**
 * Catalogo canonico offline fornito e curato dall’utente.
 *
 * Contiene identità, codici e coordinate delle stazioni; non contiene e non
 * stabilisce orari, ritardi, binari, soppressioni, posizioni o stato servizio.
 */
class EmbeddedCuratedRailStationCatalog(
    private val openAsset: (String) -> InputStream,
    private val assetPath: String = ASSET_PATH
) {
    fun load(): List<Station> {
        return openAsset(assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
            val root = JSONObject(reader.readText())
            require(root.optString("kind") == "curated-rail-station-catalog")
            require(!root.optBoolean("operationalData", true))
            stationsFrom(root.optJSONArray("stations") ?: JSONArray())
        }
    }

    private fun stationsFrom(values: JSONArray): List<Station> {
        val unique = linkedMapOf<String, Station>()
        for (index in 0 until minOf(values.length(), MAX_STATIONS)) {
            val value = values.optJSONObject(index) ?: continue
            val id = value.optString("id").trim()
            val name = value.optString("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            val aliases = value.optJSONArray("aliases")?.stringValues().orEmpty()
            val station = Station(
                id = "curated:$id",
                name = name,
                city = name,
                region = SOURCE_LABEL,
                railwayCode = value.optTextOrNull("railwayCode"),
                trenitaliaLocationId = value.optTextOrNull("trenitaliaLocationId"),
                aliases = aliases,
                latitude = value.optDoubleOrNull("latitude"),
                longitude = value.optDoubleOrNull("longitude")
            )
            unique.putIfAbsent(station.id, station)
        }
        return unique.values.toList()
    }

    private fun JSONArray.stringValues(): List<String> = buildList {
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }.distinct()

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name).takeIf { it.isFinite() } else null

    /**
     * The catalogue writes `null` for a station without the code. Android's org.json renders a
     * JSON null as the text "null" from optString, unlike the org.json used by the unit tests, so
     * the null is checked first: a station must never carry "null" as its railway code.
     */
    private fun JSONObject.optTextOrNull(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).trim().ifBlank { null } else null

    companion object {
        const val ASSET_PATH = "stations/curated_it_rail_stations.json"
        const val SOURCE_LABEL = "Catalogo stazioni curato"
        private const val MAX_STATIONS = 10_000
    }
}
