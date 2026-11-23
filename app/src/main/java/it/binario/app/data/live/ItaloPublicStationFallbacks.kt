package it.binario.app.data.live

import it.binario.app.shared.SharedData

/** Reviewed passenger-facing Italo codes; never inferred from RFI/ViaggiaTreno IDs.
 * Used when the public page moves its station catalogue into an external script.
 * A changed/mismatched station response is still rejected by the board parser.
 * The list is the shared table contracts/shared-data/v1/italo-stations-v1.json ([SharedData.ItaloStations]).
 */
internal object ItaloPublicStationFallbacks {
    val all = SharedData.ItaloStations.STATIONS.map { station ->
        ItaloPublicStation(station.code, station.name, java.text.Normalizer.normalize(station.name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-").trim('-'), station.aliases.toSet())
    }
}
