package it.binario.app.data.catalog

import it.binario.app.data.Station
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Catalogo geografico offline per l’autocompletamento nazionale.
 *
 * La sorgente è esplicitamente separata dai provider ferroviari: nomi e coordinate
 * non attestano che una corsa sia programmata, attiva, in orario o monitorabile.
 */
class EmbeddedNationalRailStationCatalog(
    private val openAsset: (String) -> InputStream,
    private val assetPath: String = ASSET_PATH
) {
    fun load(): List<Station> = runCatching {
        GZIPInputStream(openAsset(assetPath)).bufferedReader(Charsets.UTF_8).use { reader ->
            val root = JSONObject(reader.readText())
            require(root.optString("kind") == "geographic-rail-station-discovery-catalog")
            require(!root.optBoolean("operationalData", true))
            stationsFrom(root.optJSONArray("stations") ?: JSONArray())
        }
    }.getOrDefault(emptyList())

    private fun stationsFrom(values: JSONArray): List<Station> {
        val unique = linkedMapOf<String, Station>()
        for (index in 0 until minOf(values.length(), MAX_STATIONS)) {
            val value = values.optJSONObject(index) ?: continue
            val sourceId = value.optString("id").trim()
            val name = value.optString("name").trim()
            if (sourceId.isBlank() || name.isBlank()) continue
            val city = value.optString("city").trim().ifBlank { "Italia" }
            val station = Station(
                id = "catalog:osm-it:$sourceId",
                name = name,
                city = city,
                region = SOURCE_LABEL
            )
            unique.putIfAbsent(normalize(name), station)
        }
        return unique.values.toList()
    }

    private fun normalize(value: String): String = value.lowercase().trim()

    companion object {
        const val ASSET_PATH = "stations/osm_it_rail_stations.json.gz"
        const val SOURCE_LABEL = "OpenStreetMap · catalogo geografico"
        private const val MAX_STATIONS = 10_000
    }
}
