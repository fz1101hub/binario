package it.binario.app.data.sources

import it.binario.app.data.core.SourceId
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.shared.SharedData
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
    /** The scores of contracts/shared-data/v1/source-scores-v1.json, shared with the iPhone ([SharedData.SourceScores]). */
    private val preferences = SharedData.SourceScores.PREFERENCES.map { preference ->
        SourcePreference(SourceId(preference.source), RailInformationCategory.valueOf(preference.category), preference.score, preference.reason)
    }

    fun ranked(category: RailInformationCategory): List<SourcePreference> =
        preferences.filter { it.category == category }.sortedByDescending { it.score }

    fun score(category: RailInformationCategory, sourceId: SourceId, operatorHint: String? = null): Int {
        val base = preferences.firstOrNull { it.category == category && it.sourceId == sourceId }?.score
            ?: SharedData.SourceScores.DEFAULT_SCORE
        return base + operatorAffinity(category, sourceId, operatorHint)
    }

    fun isCoverageFallback(sourceId: SourceId): Boolean = sourceId.value in SharedData.SourceScores.COVERAGE_FALLBACKS

    /**
     * The bonus of the first rule of [SharedData.SourceScores.OPERATOR_AFFINITY] naming [sourceId] when [operatorHint]
     * contains one of its operator keywords: the source is the train's own operator.
     */
    private fun operatorAffinity(
        category: RailInformationCategory,
        sourceId: SourceId,
        operatorHint: String?
    ): Int {
        val op = normalize(operatorHint.orEmpty())
        if (op.isBlank()) return 0
        val rule = SharedData.SourceScores.OPERATOR_AFFINITY.firstOrNull { sourceId.value in it.sources } ?: return 0
        return if (rule.operatorKeywords.any(op::contains)) rule.pointsByCategory[category.name] ?: 0 else 0
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace("ö", "oe")
        .replace("ä", "ae")
        .replace("ü", "ue")
        .replace(Regex("[^a-z0-9]"), "")
}

/** Inventario complessivo per audit e UI diagnostica. */
object RailSourceInventory {
    val all: List<RailSourceDefinition>
        get() = (RailSourceCatalog.all + InternationalSourceCatalog.all).distinctBy { it.id }

    fun byId(id: SourceId): RailSourceDefinition? = all.firstOrNull { it.id == id }

    /** Fonti utilizzabili come tier primario: esclude esplicitamente copertura best-effort e sorgenti non abilitate. */
    fun primaryCandidates(capability: ProviderCapability): List<RailSourceDefinition> = all.filter {
        capability in it.capabilities && it.readiness in setOf(
            SourceReadiness.READY_FOR_GTFS_PACK,
            SourceReadiness.READY_FOR_PROTOCOL_TEST,
            SourceReadiness.IMPORT_VIA_GATEWAY
        )
    }

    /** Fonti deboli ma ammesse per colmare un buco reale, mai concorrenti nel tier primario. */
    fun fallbackCandidates(capability: ProviderCapability): List<RailSourceDefinition> = all.filter {
        capability in it.capabilities && it.readiness == SourceReadiness.BEST_EFFORT_FALLBACK
    }
}
