package it.binario.app.data.live

import it.binario.app.data.Station
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Public RFI place IDs, shared with iOS and Alexa; never derive an ID from a train code. */
internal object RfiStationIds {
    private val byName: Map<String, Int> by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("rfi_stations.v1.json")) {
            "Missing bundled RFI station catalog"
        }
        val document = stream.bufferedReader().use { JSONObject(it.readText()) }
        require(document.getInt("schemaVersion") == 1)
        val stations = document.getJSONArray("stations")
        buildMap {
            repeat(stations.length()) { index ->
                val station = stations.getJSONObject(index)
                val id = station.getInt("id")
                require(id > 0)
                put(rfiStationKey(station.getString("name")), id)
            }
        }
    }

    fun placeId(stationId: String, stationName: String? = null): Int? =
        stationName?.let { byName[rfiStationKey(it)] } ?: byName[rfiStationKey(stationId)]

    fun placeId(station: Station): Int? =
        (listOf(station.name) + station.aliases).mapNotNull { byName[rfiStationKey(it)] }
            .distinct().singleOrNull()
}

internal fun rfiStationKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .uppercase(Locale.ROOT)
    .replace(Regex("\\p{M}+"), "")
    .replace(Regex("\\bS\\.\\s*"), "SAN ")
    .replace(Regex("[^A-Z0-9]"), "")

internal fun Station.matchesRfiStop(label: String): Boolean =
    (listOf(name) + aliases).any { rfiStationKey(it) == rfiStationKey(label) }
