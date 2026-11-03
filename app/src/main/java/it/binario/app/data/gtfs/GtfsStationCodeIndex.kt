package it.binario.app.data.gtfs

import it.binario.app.data.RailStopNameMatcher
import it.binario.app.data.Station

/**
 * The catalogue station a stop of a national operator pack stands for, from the code the pack publishes as
 * stop_code rather than from its name ("BOLOGNA C.LE", "Padua", "NA Afragola PES").
 *
 * Trenitalia ([GtfsStopCodeScheme.TRENITALIA_LOCATION_ID]) publishes its location id, the catalogue's
 * `trenitaliaLocationId` ("830008225" is Fidene). The ViaggiaTreno code is often the last five digits
 * ("830001700" ↔ S01700) but not always (Fidene is S08235, while S08225 is "Pc Orvieto"), so the code is tried in
 * this order and each candidate must be corroborated by the coordinates or, when one side has none, by a shared
 * word of the name:
 * 1. the station whose trenitaliaLocationId is the code, within [MAX_LOCATION_ID_KM];
 * 2. for an "8300xxxxx" code only, the station whose railwayCode is "S" + the last five digits, if it has no other
 *    trenitaliaLocationId, within [MAX_DERIVED_CODE_KM].
 *
 * Italo ([GtfsStopCodeScheme.ITALO_STATION_CODE]) uses three-character codes mapped by the reviewed
 * [ItaloGtfsStationCodes] table. A stop no rule binds returns null and is resolved by name, as in the other packs.
 */
internal class GtfsStationCodeIndex(stations: List<Station>) {
    private val byId: Map<String, Station> = stations.associateBy(Station::id)
    private val byLocationId: Map<String, Station> = uniqueBy(stations) { it.trenitaliaLocationId }
    private val byRailwayCode: Map<String, Station> = uniqueBy(stations) { it.railwayCode }

    fun resolve(scheme: GtfsStopCodeScheme, code: String, latitude: Double?, longitude: Double?, name: String): Station? {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return null
        return when (scheme) {
            GtfsStopCodeScheme.NONE -> null
            GtfsStopCodeScheme.ITALO_STATION_CODE -> ItaloGtfsStationCodes.catalogueId(trimmed)?.let(byId::get)
            GtfsStopCodeScheme.TRENITALIA_LOCATION_ID -> trenitalia(trimmed, latitude, longitude, name)
        }
    }

    private fun trenitalia(code: String, latitude: Double?, longitude: Double?, name: String): Station? {
        if (!TRENITALIA_LOCATION.matches(code)) return null
        byLocationId[code]?.takeIf { corroborated(it, latitude, longitude, name, MAX_LOCATION_ID_KM) }?.let { return it }
        if (!code.startsWith("8300")) return null
        val derived = byRailwayCode["S" + code.substring(4)] ?: return null
        if (derived.trenitaliaLocationId != null && derived.trenitaliaLocationId != code) return null
        return derived.takeIf { corroborated(it, latitude, longitude, name, MAX_DERIVED_CODE_KM) }
    }

    private fun corroborated(station: Station, latitude: Double?, longitude: Double?, name: String, maxKm: Double): Boolean {
        val stationLatitude = station.latitude
        val stationLongitude = station.longitude
        if (latitude != null && longitude != null && stationLatitude != null && stationLongitude != null) {
            return distanceKm(latitude, longitude, stationLatitude, stationLongitude) <= maxKm
        }
        val words = RailStopNameMatcher.tokens(name).filter { it.length >= 4 }.toSet()
        return (listOf(station.name) + station.aliases).any { label -> RailStopNameMatcher.tokens(label).any { it in words } }
    }

    companion object {
        /** Location ids are a nine-digit UIC-style code; the catalogue only holds Italian (83…) ones. */
        private val TRENITALIA_LOCATION = Regex("\\d{9}")
        /** A location id names the station: only coordinates this far apart reveal a catalogue error. */
        const val MAX_LOCATION_ID_KM = 10.0
        /** The derived ViaggiaTreno code is a guess that the coordinates must confirm closely. */
        const val MAX_DERIVED_CODE_KM = 3.0

        private fun uniqueBy(stations: List<Station>, key: (Station) -> String?): Map<String, Station> {
            val grouped = HashMap<String, MutableList<Station>>()
            for (station in stations) key(station)?.trim()?.takeIf(String::isNotEmpty)?.let { grouped.getOrPut(it) { ArrayList(1) } += station }
            return grouped.filterValues { it.size == 1 }.mapValues { it.value.single() }
        }

        fun distanceKm(latitude1: Double, longitude1: Double, latitude2: Double, longitude2: Double): Double {
            val phi1 = Math.toRadians(latitude1)
            val phi2 = Math.toRadians(latitude2)
            val deltaPhi = phi2 - phi1
            val deltaLambda = Math.toRadians(longitude2 - longitude1)
            val a = Math.sin(deltaPhi / 2).let { it * it } + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2).let { it * it }
            return 2 * 6371.0 * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}

/**
 * Italo's station codes (stop_code of the italo-gtfs pack) and the catalogue station each one is, reviewed by name
 * and position on 2 November 2026 against the 64 stations of the feed. The passenger-facing codes match
 * [it.binario.app.data.live.ItaloPublicStationFallbacks] where both exist (Villa San Giovanni is "SGM" here, "VSG"
 * on the public board). Not mapped, so they stay stops of the pack: "LUX", the seasonal Lucca Comics coach stop
 * about a kilometre from Lucca station. "TRX" (Treviso Centrale) is mapped by name: the feed places it 5 km east of
 * the station.
 */
internal object ItaloGtfsStationCodes {
    private val catalogueIds: Map<String, String> = mapOf(
        "AGR" to "S11705", // Agropoli-Castellabate
        "AVR" to "S09006", // Aversa
        "BAC" to "S11119", // Bari Centrale (catalogue: "Bari ( Tutte Le Stazioni )", alias Bari Centrale)
        "BLT" to "S11108", // Barletta
        "BEN" to "S09311", // Benevento
        "BIG" to "S11113", // Bisceglie
        "BC_" to "S05043", // Bologna Centrale
        "BLZ" to "S02026", // Bolzano Bozen
        "BSC" to "S01717", // Brescia
        "CEA" to "S09211", // Caserta
        "CON" to "S02706", // Conegliano
        "CDZ" to "S02999", // Cortina d'Ampezzo (coach stop)
        "DSG" to "S02084", // Desenzano del Garda-Sirmione
        "F__" to "S05712", // Ferrara
        "SMN" to "S06421", // Firenze S. M. Novella
        "FG_" to "S11100", // Foggia
        "GB_" to "S04702", // Genova Brignole
        "G__" to "S04700", // Genova Piazza Principe
        "LON" to "S11749", // Lamezia Terme Centrale
        "LTL" to "S03202", // Latisana-Lignano-Bibione
        "LGZ" to "S02556", // Longarone-Zoldo
        "MRT" to "S11723", // Maratea
        "MC_" to "S01700", // Milano Centrale
        "RRO" to "S01039", // Rho-Fiera Milano
        "RG_" to "S01820", // Milano Rogoredo
        "ML_" to "S11114", // Molfetta
        "MNF" to "S03310", // Monfalcone
        "NAF" to "S09988", // Napoli Afragola
        "NAC" to "S09218", // Napoli Centrale
        "PD_" to "S02581", // Padova ("Padua" in the feed)
        "PAR" to "S11739", // Paola
        "P3_" to "S01860", // Pavia
        "PSY" to "S02088", // Peschiera del Garda
        "PNE" to "S02701", // Pordenone
        "PGR" to "S03200", // Portogruaro-Caorle
        "RCE" to "S11781", // Reggio di Calabria Centrale
        "AAV" to "S05254", // Reggio Emilia AV Mediopadana
        "RMT" to "S08409", // Roma Termini
        "RTB" to "S08217", // Roma Tiburtina
        "RUT" to "S11765", // Rosarno
        "RVR" to "S02044", // Rovereto
        "R__" to "S05706", // Rovigo
        "SAL" to "S09818", // Salerno
        "SDP" to "S02666", // San Donà di Piave-Jesolo
        "SDX" to "NC001202", // San Vito di Cadore (coach stop)
        "SRI" to "S11721", // Sapri
        "SDC" to "S11727", // Scalea-S. Domenica Talao
        "TDX" to "NC001285", // Tai di Cadore (coach stop)
        "TOP" to "S00219", // Torino Porta Nuova
        "OUE" to "S00035", // Torino Porta Susa
        "TR_" to "S11112", // Trani
        "TCN" to "S02038", // Trento
        "TRX" to "S02712", // Treviso Centrale
        "RHA" to "S03213", // Trieste Airport
        "TSC" to "S03317", // Trieste Centrale
        "UDN" to "S03026", // Udine
        "VLH" to "S11709", // Vallo della Lucania-Castelnuovo
        "VEM" to "S02589", // Venezia Mestre
        "VSL" to "S02593", // Venezia S. Lucia
        "VPN" to "S02430", // Verona Porta Nuova
        "VIP" to "S11789", // Vibo Valentia-Pizzo
        "VIC" to "S02446", // Vicenza
        "SGM" to "S11774" // Villa S. Giovanni
    )

    /** Codes reviewed and deliberately left to the pack ("LUX"). */
    val unmapped: Set<String> = setOf("LUX")

    /** The catalogue station id ("curated:S01700") of an Italo code, or null. */
    fun catalogueId(code: String): String? = catalogueIds[code.trim()]?.let { "curated:$it" }

    val size: Int get() = catalogueIds.size
}
