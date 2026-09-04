package it.binario.app.data.sources

import it.binario.app.data.core.SourceId
import java.util.Locale

/** Categoria informativa: la fonte migliore non è necessariamente la stessa per ogni dato. */
enum class RailInformationCategory {
    PLANNED_ITINERARY,
    TRAIN_REALTIME,
    STATION_BOARD,
    SERVICE_ALERTS,
    ROUTE_VARIATIONS
}

data class SourcePreference(
    val sourceId: SourceId,
    val category: RailInformationCategory,
    val score: Int,
    val reason: String
)

/**
 * Gerarchia esplicita delle fonti. I punteggi esprimono affidabilità/precisione
 * per categoria, non una valutazione assoluta dell'organizzazione.
 *
 * Regole principali:
 * - orario: infrastruttura/operator GTFS ufficiale prima di aggregatori;
 * - realtime: feed strutturato dell'operatore/autorità prima di HTML;
 * - binario/stazione in Italia: RFI è fonte infrastrutturale primaria;
 * - deviazioni: feed strutturato o avviso ufficiale, mai inferenza dal GTFS;
 * - fonti MONITOR_ONLY non entrano automaticamente nel runtime.
 */
object RailSourceSelectionPolicy {
    private val preferences = listOf(
        pref("rfi-programmed", RailInformationCategory.PLANNED_ITINERARY, 140, "Quadro orario ufficiale dell'infrastruttura italiana, multi-operatore."),
        pref("ch-otd-gtfs", RailInformationCategory.PLANNED_ITINERARY, 135, "Dataset nazionale svizzero ufficiale, migliore baseline per SBB/TILO/BLS/RhB/FART e tratte transfrontaliere."),
        pref("oebb-gtfs", RailInformationCategory.PLANNED_ITINERARY, 135, "GTFS ufficiale ÖBB per Railjet/EuroCity/Nightjet."),
        pref("sncf-gtfs", RailInformationCategory.PLANNED_ITINERARY, 135, "GTFS ufficiale SNCF per TGV/TER/Intercités."),
        pref("lombardia-trenord-gtfs", RailInformationCategory.PLANNED_ITINERARY, 132, "GTFS ferroviario ufficiale Regione Lombardia/Trenord."),
        pref("alto-adige-sta-gtfs", RailInformationCategory.PLANNED_ITINERARY, 130, "Open Data Hub/STA ufficiale, utile anche per servizi transfrontalieri."),
        pref("delfi-gtfs", RailInformationCategory.PLANNED_ITINERARY, 118, "Dataset nazionale tedesco ufficiale; complemento DB, non duplicato preferito di un feed diretto dell'operatore."),
        pref("european-sleeper-gtfs", RailInformationCategory.PLANNED_ITINERARY, 30, "Feed community monitor-only finché provenienza/licenza e fermate italiane non sono verificate."),

        pref("sncf-gtfs-rt", RailInformationCategory.TRAIN_REALTIME, 140, "GTFS-RT ufficiale SNCF Trip Updates."),
        pref("ch-otd-gtfs-rt", RailInformationCategory.TRAIN_REALTIME, 140, "GTFS-RT ufficiale svizzero coordinato con GTFS statico; richiede API key."),
        pref("db-timetables", RailInformationCategory.TRAIN_REALTIME, 135, "API DB ufficiale con scostamenti dal Sollfahrplan; richiede account."),
        pref("italo-status", RailInformationCategory.TRAIN_REALTIME, 128, "Fonte operatore Italo per stato della corsa, best-effort personale."),
        pref("viaggiatreno", RailInformationCategory.TRAIN_REALTIME, 126, "Fonte Trenitalia per stato corsa; uso personale/best-effort e condizioni da rispettare."),
        pref("rfi-station-board", RailInformationCategory.TRAIN_REALTIME, 122, "Fonte infrastrutturale italiana multi-operatore, ottima per evento di stazione."),
        pref("european-sleeper-gtfs-rt", RailInformationCategory.TRAIN_REALTIME, 30, "Monitor-only finché il tratto Milano non è verificato nel feed."),

        pref("rfi-station-board", RailInformationCategory.STATION_BOARD, 145, "Fonte infrastrutturale primaria per arrivi/partenze e binari sulle stazioni RFI."),
        pref("italo-status", RailInformationCategory.STATION_BOARD, 125, "Fonte operatore complementare per Italo."),
        pref("viaggiatreno", RailInformationCategory.STATION_BOARD, 125, "Fonte operatore complementare per Trenitalia."),
        pref("db-timetables", RailInformationCategory.STATION_BOARD, 125, "API ufficiale DB per stazioni servite nel perimetro disponibile."),

        pref("sncf-gtfs-rt", RailInformationCategory.SERVICE_ALERTS, 140, "GTFS-RT Service Alerts ufficiale SNCF."),
        pref("ch-otd-gtfs-rt", RailInformationCategory.SERVICE_ALERTS, 140, "GTFS-RT Service Alerts ufficiale svizzero."),
        pref("rfi-infomobility-rss", RailInformationCategory.SERVICE_ALERTS, 132, "RSS ufficiale RFI per perturbazioni della rete italiana."),
        pref("italo-status", RailInformationCategory.SERVICE_ALERTS, 110, "Avvisi operatore Italo come complemento."),
        pref("viaggiatreno", RailInformationCategory.SERVICE_ALERTS, 105, "Informazioni Trenitalia come complemento personale/best-effort."),

        pref("ch-otd-gtfs-rt", RailInformationCategory.ROUTE_VARIATIONS, 145, "Trip Updates/Service Alerts strutturati: preferibili per fermate modificate e corse svizzere."),
        pref("sncf-gtfs-rt", RailInformationCategory.ROUTE_VARIATIONS, 145, "GTFS-RT strutturato per variazioni/cancellazioni SNCF."),
        pref("rfi-station-board", RailInformationCategory.ROUTE_VARIATIONS, 130, "RFI può fornire informazione operativa ufficiale sul tratto italiano; percorso strutturato solo quando esplicito."),
        pref("rfi-infomobility-rss", RailInformationCategory.ROUTE_VARIATIONS, 125, "Avvisi RFI ufficiali per deviazioni/interruzioni, tipicamente testuali."),
        pref("italo-status", RailInformationCategory.ROUTE_VARIATIONS, 115, "Fonte operatore complementare per variazioni Italo."),
        pref("viaggiatreno", RailInformationCategory.ROUTE_VARIATIONS, 115, "Fonte operatore complementare per variazioni Trenitalia.")
    )

    fun ranked(category: RailInformationCategory): List<SourcePreference> =
        preferences.filter { it.category == category }.sortedByDescending { it.score }

    fun score(category: RailInformationCategory, sourceId: SourceId, operatorHint: String? = null): Int {
        val base = preferences.firstOrNull { it.category == category && it.sourceId == sourceId }?.score ?: 50
        return base + operatorAffinity(sourceId, operatorHint)
    }

    /**
     * Favorisce il feed dell'operatore quando due dataset descrivono la stessa
     * corsa: es. ÖBB batte DELFI per un treno ÖBB, Svizzera batte feed generici
     * per SBB/TILO/BLS/RhB/FART/SSIF, SNCF batte aggregati per SNCF.
     */
    private fun operatorAffinity(sourceId: SourceId, operatorHint: String?): Int {
        val op = normalize(operatorHint.orEmpty())
        if (op.isBlank()) return 0
        return when (sourceId.value) {
            "ch-otd-gtfs" -> if (op.containsAny("sbb", "tilo", "bls", "rhb", "rhatischebahn", "fart", "ssif")) 20 else 0
            "oebb-gtfs" -> if (op.containsAny("oebb", "obb", "osterreichischebundesbahnen")) 20 else 0
            "sncf-gtfs" -> if (op.contains("sncf")) 20 else 0
            "lombardia-trenord-gtfs" -> if (op.contains("trenord")) 20 else 0
            "delfi-gtfs" -> if (op.containsAny("db", "deutschebahn", "dbfernverkehr")) 20 else 0
            else -> 0
        }
    }

    private fun pref(id: String, category: RailInformationCategory, score: Int, reason: String) =
        SourcePreference(SourceId(id), category, score, reason)

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace("ö", "oe")
        .replace("ä", "ae")
        .replace("ü", "ue")
        .replace(Regex("[^a-z0-9]"), "")

    private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)
}

/** Inventario complessivo per audit e UI diagnostica. */
object RailSourceInventory {
    val all: List<RailSourceDefinition>
        get() = (RailSourceCatalog.all + InternationalSourceCatalog.all).distinctBy { it.id }

    fun byId(id: SourceId): RailSourceDefinition? = all.firstOrNull { it.id == id }
}
