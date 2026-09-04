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
 * - fallback best-effort ammesso se evita una tratta scoperta, ma non deve
 *   scalzare una fonte ufficiale che copre la stessa corsa.
 */
object RailSourceSelectionPolicy {
    private val preferences = listOf(
        pref("rfi-programmed", RailInformationCategory.PLANNED_ITINERARY, 145, "Quadro orario ufficiale dell'infrastruttura italiana, multi-operatore."),
        pref("ch-otd-gtfs", RailInformationCategory.PLANNED_ITINERARY, 140, "Dataset nazionale svizzero ufficiale, baseline per SBB/TILO/BLS/RhB/FART e tratte transfrontaliere."),
        pref("oebb-gtfs", RailInformationCategory.PLANNED_ITINERARY, 140, "GTFS ufficiale ÖBB per Railjet/EuroCity/Nightjet."),
        pref("sncf-gtfs", RailInformationCategory.PLANNED_ITINERARY, 140, "GTFS ufficiale SNCF per TGV/TER/Intercités."),
        pref("lombardia-trenord-gtfs", RailInformationCategory.PLANNED_ITINERARY, 137, "GTFS ferroviario ufficiale Regione Lombardia/Trenord."),
        pref("alto-adige-sta-gtfs", RailInformationCategory.PLANNED_ITINERARY, 135, "Open Data Hub/STA ufficiale, utile anche per servizi transfrontalieri."),
        pref("delfi-gtfs", RailInformationCategory.PLANNED_ITINERARY, 125, "Dataset nazionale tedesco ufficiale; complemento DB, non duplicato preferito di un feed diretto dell'operatore."),
        pref("delfi-gtfs-community", RailInformationCategory.PLANNED_ITINERARY, 65, "Mirror community del dataset DELFI: utile solo se il download ufficiale non è disponibile e la tratta resterebbe scoperta."),
        pref("european-sleeper-gtfs", RailInformationCategory.PLANNED_ITINERARY, 60, "Feed community best-effort; ammesso come fallback dopo l'avvio ufficiale del servizio Milano."),

        pref("db-ris-journeys", RailInformationCategory.TRAIN_REALTIME, 148, "RIS::Journeys ufficiale DB: percorso corsa completo e informazioni operative strutturate."),
        pref("sncf-gtfs-rt", RailInformationCategory.TRAIN_REALTIME, 145, "GTFS-RT ufficiale SNCF Trip Updates."),
        pref("ch-otd-gtfs-rt", RailInformationCategory.TRAIN_REALTIME, 145, "GTFS-RT ufficiale svizzero coordinato con GTFS statico; richiede API key."),
        pref("db-ris-boards", RailInformationCategory.TRAIN_REALTIME, 143, "RIS::Boards ufficiale DB: tempi previsionali, binari e fermate modificate."),
        pref("db-timetables", RailInformationCategory.TRAIN_REALTIME, 135, "Timetables API DB: Sollfahrplan e scostamenti, semplice fallback autorizzato."),
        pref("italo-status", RailInformationCategory.TRAIN_REALTIME, 130, "Fonte operatore Italo per stato della corsa, best-effort personale."),
        pref("viaggiatreno", RailInformationCategory.TRAIN_REALTIME, 128, "Fonte Trenitalia per stato corsa; uso personale/best-effort e condizioni da rispettare."),
        pref("rfi-station-board", RailInformationCategory.TRAIN_REALTIME, 126, "Fonte infrastrutturale italiana multi-operatore, ottima per evento di stazione."),
        pref("european-sleeper-gtfs-rt", RailInformationCategory.TRAIN_REALTIME, 60, "Fallback community a bassa priorità; non deve sostituire RFI o un feed ufficiale."),

        pref("rfi-station-board", RailInformationCategory.STATION_BOARD, 150, "Fonte infrastrutturale primaria per arrivi/partenze e binari sulle stazioni RFI."),
        pref("db-ris-boards", RailInformationCategory.STATION_BOARD, 148, "RIS::Boards ufficiale DB per tabelloni con previsione, binario, fermate saltate/aggiunte e sostitutivi."),
        pref("db-timetables", RailInformationCategory.STATION_BOARD, 135, "Timetables API DB per stazioni servite nel perimetro disponibile."),
        pref("italo-status", RailInformationCategory.STATION_BOARD, 128, "Fonte operatore complementare per Italo."),
        pref("viaggiatreno", RailInformationCategory.STATION_BOARD, 128, "Fonte operatore complementare per Trenitalia."),

        pref("db-ris-disruptions", RailInformationCategory.SERVICE_ALERTS, 148, "RIS::Disruptions ufficiale DB per perturbazioni e corse/fermate interessate."),
        pref("sncf-gtfs-rt", RailInformationCategory.SERVICE_ALERTS, 145, "GTFS-RT Service Alerts ufficiale SNCF."),
        pref("ch-otd-gtfs-rt", RailInformationCategory.SERVICE_ALERTS, 145, "GTFS-RT Service Alerts ufficiale svizzero."),
        pref("rfi-infomobility-rss", RailInformationCategory.SERVICE_ALERTS, 138, "RSS ufficiale RFI per perturbazioni della rete italiana."),
        pref("italo-status", RailInformationCategory.SERVICE_ALERTS, 112, "Avvisi operatore Italo come complemento."),
        pref("viaggiatreno", RailInformationCategory.SERVICE_ALERTS, 108, "Informazioni Trenitalia come complemento personale/best-effort."),

        pref("db-ris-journeys", RailInformationCategory.ROUTE_VARIATIONS, 150, "RIS::Journeys ufficiale DB: percorso corsa operativo completo."),
        pref("db-ris-boards", RailInformationCategory.ROUTE_VARIATIONS, 148, "RIS::Boards ufficiale DB: via, fermate saltate/aggiunte e servizi sostitutivi."),
        pref("ch-otd-gtfs-rt", RailInformationCategory.ROUTE_VARIATIONS, 145, "Trip Updates/Service Alerts strutturati per fermate modificate e corse svizzere."),
        pref("sncf-gtfs-rt", RailInformationCategory.ROUTE_VARIATIONS, 145, "GTFS-RT strutturato per variazioni/cancellazioni SNCF."),
        pref("db-ris-disruptions", RailInformationCategory.ROUTE_VARIATIONS, 142, "RIS::Disruptions correla perturbazioni a corse e fermate."),
        pref("rfi-station-board", RailInformationCategory.ROUTE_VARIATIONS, 135, "RFI può fornire informazione operativa ufficiale sul tratto italiano; percorso strutturato solo quando esplicito."),
        pref("rfi-infomobility-rss", RailInformationCategory.ROUTE_VARIATIONS, 128, "Avvisi RFI ufficiali per deviazioni/interruzioni, tipicamente testuali."),
        pref("italo-status", RailInformationCategory.ROUTE_VARIATIONS, 118, "Fonte operatore complementare per variazioni Italo."),
        pref("viaggiatreno", RailInformationCategory.ROUTE_VARIATIONS, 118, "Fonte operatore complementare per variazioni Trenitalia.")
    )

    fun ranked(category: RailInformationCategory): List<SourcePreference> =
        preferences.filter { it.category == category }.sortedByDescending { it.score }

    fun score(category: RailInformationCategory, sourceId: SourceId, operatorHint: String? = null): Int {
        val base = preferences.firstOrNull { it.category == category && it.sourceId == sourceId }?.score ?: 50
        return base + operatorAffinity(sourceId, operatorHint)
    }

    fun isCoverageFallback(sourceId: SourceId): Boolean = sourceId.value in setOf(
        "european-sleeper-gtfs",
        "european-sleeper-gtfs-rt",
        "delfi-gtfs-community"
    )

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
            "delfi-gtfs", "delfi-gtfs-community" -> if (op.containsAny("db", "deutschebahn", "dbfernverkehr")) 20 else 0
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
