package it.binario.app

import android.content.Context
import androidx.core.content.edit
import it.binario.app.data.Station
import it.binario.app.data.gtfs.EmbeddedGtfsStationSearchIndex

/** Conserva soltanto le stazioni reali scelte dall’utente fuori dal catalogo iniziale. */
class CustomStationStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_custom_stations_v1", Context.MODE_PRIVATE)

    fun stations(): List<Station> = preferences.getString(KEY_STATIONS, "")
        .orEmpty()
        .split(";")
        .mapNotNull(::decode)

    fun remember(station: Station): List<Station> {
        val updated = (listOf(station) + stations())
            .distinctBy { it.id }
            .take(MAX_STATIONS)
        preferences.edit { putString(KEY_STATIONS, updated.joinToString(";") { encode(it) }) }
        return updated
    }

    private fun encode(station: Station): String = listOf(
        station.id,
        station.name,
        station.city,
        station.region.orEmpty(),
        station.railwayCode.orEmpty(),
        station.trenitaliaLocationId.orEmpty(),
        station.aliases.joinToString(ALIAS_SEPARATOR),
        station.latitude?.toString().orEmpty(),
        station.longitude?.toString().orEmpty()
    )
        .joinToString(",", transform = PreferenceValueCodec::encode)

    private fun decode(value: String): Station? = runCatching {
        val parts = value.split(",")
        when (parts.size) {
            4 -> Station(
                id = decodePart(parts[0]),
                name = decodePart(parts[1]),
                city = decodePart(parts[2]),
                region = decodePart(parts[3]).ifBlank { null }
            )
            9 -> Station(
                id = decodePart(parts[0]),
                name = decodePart(parts[1]),
                city = decodePart(parts[2]),
                region = decodePart(parts[3]).ifBlank { null },
                railwayCode = decodePart(parts[4]).ifBlank { null },
                trenitaliaLocationId = decodePart(parts[5]).ifBlank { null },
                aliases = decodePart(parts[6]).split(ALIAS_SEPARATOR).filter(String::isNotBlank),
                latitude = decodePart(parts[7]).toDoubleOrNull(),
                longitude = decodePart(parts[8]).toDoubleOrNull()
            )
            else -> null
        }
    }.getOrNull()

    private fun decodePart(value: String): String = PreferenceValueCodec.decode(value)
        ?: throw IllegalArgumentException("Valore preferenza Base64 non valido")

    private companion object {
        const val KEY_STATIONS = "stations"
        const val MAX_STATIONS = 96
        const val ALIAS_SEPARATOR = "\u001F"
    }
}

internal fun stationFromFreeText(value: String): Station? {
    val name = value.trim().replace(Regex("\\s+"), " ")
    val normalized = EmbeddedGtfsStationSearchIndex.normalize(name)
    if (normalized.length < 2) return null
    return Station(
        id = "manual:${normalized.replace(' ', '-')}",
        name = name,
        city = name,
        region = "Ricerca libera"
    )
}
