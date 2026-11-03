package it.binario.app

/** Station identities emitted by the v0.16.3 schema-1 backup encoder. */
internal object LegacyBackupStationIds {
    private val v0163ToCuratedId: Map<String, String> = mapOf(
        "roma-termini" to "curated:S08409",
        "roma-tiburtina" to "curated:S08217",
        "roma-ostiense" to "curated:S08406",
        "roma-trastevere" to "curated:S08405",
        "roma-san-pietro" to "curated:S08323",
        "roma-tuscolana" to "curated:S08408",
        "fiumicino-aeroporto" to "curated:S08411",
        "ciampino" to "curated:S08650",
        "civitavecchia" to "curated:S08010",
        "frascati" to "curated:S08751",
        "velletri" to "curated:S08734",
        "viterbo-pf" to "curated:S08304",
        "orte" to "curated:S08209",
        "latina" to "curated:S08608",
        "frosinone" to "curated:S08662",
        "cassino" to "curated:S08671",
        "napoli-centrale" to "curated:S09218",
        "firenze-smn" to "curated:S06421",
        "bologna-centrale" to "curated:S05043",
        "milano-centrale" to "curated:S01700",
        "venezia-sl" to "curated:NC001358",
        "genova-pp" to "curated:S04700",
        "torino-pn" to "curated:S00219",
        "bari-centrale" to "curated:S11119",
        "palermo-centrale" to "curated:S12002",
        "cagliari" to "curated:S12891",
    )

    fun currentId(schemaVersion: Int, stationId: String): String =
        if (schemaVersion == 1) v0163ToCuratedId[stationId] ?: stationId else stationId
}
