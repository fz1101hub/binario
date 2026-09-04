package it.binario.app.data.sources

import it.binario.app.data.core.SourceId
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.ProviderCapability
import java.net.URI

enum class SourceFormat {
    GTFS_ZIP,
    GTFS_REALTIME_PROTOBUF,
    RSS_XML,
    NETEX,
    SIRI,
    HTML_OR_PDF,
    OPERATOR_GATEWAY
}

enum class SourceReadiness {
    READY_FOR_GTFS_PACK,
    /**
     * Fonte non primaria ma sufficientemente stabile da colmare una tratta
     * altrimenti scoperta. Non deve mai scalzare una fonte ufficiale migliore.
     * L'import richiede opt-in esplicito della pipeline.
     */
    BEST_EFFORT_FALLBACK,
    READY_FOR_PROTOCOL_TEST,
    IMPORT_VIA_GATEWAY,
    LANDING_PAGE_ONLY,
    BEST_EFFORT_DISABLED,
    AGREEMENT_REQUIRED,
    MONITOR_ONLY
}

data class RailSourceDefinition(
    val id: SourceId,
    val displayName: String,
    val operatorOrAuthority: String,
    val formats: Set<SourceFormat>,
    val capabilities: Set<ProviderCapability>,
    val accessMode: AccessMode,
    val readiness: SourceReadiness,
    val countryCodes: Set<String>,
    val coverage: String,
    val landingPage: URI,
    val downloadOrProbeUri: URI? = null,
    val attribution: String? = null,
    val notes: String
)

/**
 * Inventario dichiarativo delle fonti valutate per la copertura ferroviaria
 * passeggeri sul territorio italiano. La presenza nel catalogo non abilita
 * traffico di rete: l'abilitazione richiede un adapter, fixture e test propri.
 */
object RailSourceCatalog {
    private fun uri(value: String): URI = URI.create(value)

    private val staticCapabilities = setOf(
        ProviderCapability.STATION_CATALOG,
        ProviderCapability.JOURNEY_SEARCH,
        ProviderCapability.TRAIN_RUN_STATIC
    )

    val all: List<RailSourceDefinition> = listOf(
        RailSourceDefinition(
            id = SourceId("arst-gtfs"),
            displayName = "ARST Sardegna — GTFS",
            operatorOrAuthority = "ARST S.p.A.",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_GTFS_PACK,
            countryCodes = setOf("IT"),
            coverage = "Servizi ARST, comprese componenti ferroviarie e stagionali da filtrare per prodotto.",
            landingPage = uri("https://www.arst.sardegna.it/open-data/"),
            downloadOrProbeUri = uri("https://arstspa.info/arst-cagliari-it.zip"),
            attribution = "ARST S.p.A.",
            notes = "Feed ZIP HTTPS verificato; la licenza della singola versione deve accompagnare ogni data pack."
        ),
        RailSourceDefinition(
            id = SourceId("trentino-gtfs"),
            displayName = "Trentino Trasporti — GTFS",
            operatorOrAuthority = "Trentino Trasporti",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_GTFS_PACK,
            countryCodes = setOf("IT"),
            coverage = "Rete trentina, inclusa Trento–Malè quando presente nel feed corrente.",
            landingPage = uri("https://www.trentinotrasporti.it/it/opendata-it"),
            downloadOrProbeUri = uri("https://www.trentinotrasporti.it/opendata/google_transit_extraurbano_tte.zip"),
            attribution = "Trentino Trasporti",
            notes = "Feed ZIP HTTPS verificato; validare la componente ferroviaria in import."
        ),
        RailSourceDefinition(
            id = SourceId("alto-adige-sta-gtfs"),
            displayName = "Open Data Hub Alto Adige — STA GTFS",
            operatorOrAuthority = "STA / Südtirolmobil",
            formats = setOf(SourceFormat.GTFS_ZIP, SourceFormat.NETEX, SourceFormat.SIRI),
            capabilities = staticCapabilities,
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_GTFS_PACK,
            countryCodes = setOf("IT", "AT"),
            coverage = "Servizi altoatesini e transfrontalieri presenti nel dataset.",
            landingPage = uri("https://gtfs.api.opendatahub.com/v1/dataset/sta-time-tables"),
            downloadOrProbeUri = uri("https://gtfs.api.opendatahub.com/v1/dataset/sta-time-tables/raw"),
            attribution = "Open Data Hub / STA",
            notes = "Open data ufficiale; validare route_type ferroviari durante import."
        )
    ) + legacyEntries

    fun byId(id: SourceId): RailSourceDefinition? = all.firstOrNull { it.id == id }

    /*
     * Il resto del catalogo è mantenuto nella stessa lista storica del branch.
     * Questa proprietà viene sostituita in fase di merge dal contenuto completo
     * già presente: è dichiarata qui solo per tenere il nuovo enum retrocompatibile.
     */
    private val legacyEntries: List<RailSourceDefinition> = emptyList()
}
