package it.binario.app.data.live

/** Reviewed passenger-facing Italo codes; never inferred from RFI/ViaggiaTreno IDs.
 * Used when the public page moves its station catalogue into an external script.
 * A changed/mismatched station response is still rejected by the board parser.
 */
internal object ItaloPublicStationFallbacks {
    private val aliases = mapOf(
        "RRO" to setOf("Milano Expo Rho", "Rho Fiera"), "NAC" to setOf("Napoli"),
        "RCE" to setOf("Reggio Calabria"), "LON" to setOf("Lamezia Terme C"),
        "SDC" to setOf("Scalea"), "VLH" to setOf("Vallo d.Lucania"), "TSC" to setOf("Trieste C.le"),
        "LTL" to setOf("Latisana-Lignano"), "PGR" to setOf("Portogruaro"), "SDP" to setOf("S.Dona-Jesolo"),
        "DSG" to setOf("Desenzano"), "PSY" to setOf("Peschiera"), "VSL" to setOf("Venezia S.Lucia"),
        "SMN" to setOf("Firenze S.M.N.", "Firenze S. Maria Novella"), "AAV" to setOf("Mediopadana R.Emilia")
    )
    val all = listOf(
        "MC_" to "Milano Centrale", "RRO" to "Milano Rho Fiera", "RMT" to "Roma Termini",
        "RTB" to "Roma Tiburtina", "SMN" to "Firenze Santa Maria Novella", "BO2" to "Bologna Centrale",
        "AAV" to "Reggio Emilia AV Mediopadana", "OUE" to "Torino Porta Susa", "TOP" to "Torino Porta Nuova",
        "RG_" to "Milano Rogoredo", "NAF" to "Napoli Afragola", "NAC" to "Napoli Centrale",
        "CEA" to "Caserta", "SAL" to "Salerno", "RCE" to "Reggio di Calabria Centrale",
        "VSG" to "Villa S. Giovanni", "RUT" to "Rosarno", "LON" to "Lamezia Terme Centrale",
        "PAR" to "Paola", "SDC" to "Scalea S. Domenica Talao", "MRT" to "Maratea", "SRI" to "Sapri",
        "VLH" to "Vallo della Lucania Castelnuovo", "AGR" to "Agropoli Castellabate",
        "TSC" to "Trieste Centrale", "MNF" to "Monfalcone", "RHA" to "Trieste Airport",
        "LTL" to "Latisana Lignano Bibione", "PGR" to "Portogruaro Caorle", "SDP" to "S. Dona di Piave Jesolo",
        "VEM" to "Venezia Mestre", "VSL" to "Venezia Santa Lucia", "PD_" to "Padova", "VPN" to "Verona Porta Nuova",
        "BSC" to "Brescia", "DSG" to "Desenzano del Garda Sirmione", "PSY" to "Peschiera del Garda",
        "VIC" to "Vicenza", "F__" to "Ferrara", "R__" to "Rovigo", "BAC" to "Bari Centrale",
        "ML_" to "Molfetta", "BIG" to "Bisceglie", "TR_" to "Trani", "BLT" to "Barletta",
        "FG_" to "Foggia", "BEN" to "Benevento", "BLZ" to "Bolzano", "TCN" to "Trento", "RVR" to "Rovereto"
    ).map { (code, name) ->
        ItaloPublicStation(code, name, java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-").trim('-'), aliases[code].orEmpty())
    }
}
