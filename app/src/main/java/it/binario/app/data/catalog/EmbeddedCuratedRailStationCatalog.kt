package it.binario.app.data.catalog

import it.binario.app.data.Station
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.GZIPInputStream

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
    fun load(): List<Station> = runCatching {
        GZIPInputStream(openAsset(assetPath)).bufferedReader(Charsets.UTF_8).use { reader ->
            val root = JSONObject(reader.readText())
            require(root.optString("kind") == "curated-rail-station-catalog")
            require(!root.optBoolean("operationalData", true))
            stationsFrom(root.optJSONArray("stations") ?: JSONArray())
        }
    }.getOrDefault(emptyList())

    private fun stationsFrom(values: JSONArray): List<Station> {
        val unique = linkedMapOf<String, Station>()
        for (index in 0 until minOf(values.length(), MAX_STATIONS)) {
            val value = values.optJSONObject(index) ?: continue
            val id = value.optString("id").trim()
            val name = value.optString("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            val aliases = value.optJSONArray("aliases").stringValues()
            val station = Station(
                id = "curated:$id",
                name = name,
                city = name,
                region = SOURCE_LABEL,
                railwayCode = value.optString("railwayCode").trim().ifBlank { null },
                trenitaliaLocationId = value.optString("trenitaliaLocationId").trim().ifBlank { null },
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

    companion object {
        const val ASSET_PATH = "stations/curated_it_rail_stations.json.gz"
        const val SOURCE_LABEL = "Catalogo stazioni curato"
        private const val MAX_STATIONS = 10_000
    }
}
