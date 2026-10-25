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
    OPERATOR_GATEWAY,
    JSON_API
}

enum class SourceReadiness {
    READY_FOR_GTFS_PACK,
    /** Fonte non primaria ma ammessa per colmare una tratta altrimenti scoperta. */
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
            attribution = "Open Data Hub Alto Adige / STA",
            notes = "ZIP HTTPS verificato ma voluminoso: solo import offline/data pack, mai download all'avvio."
        ),
        RailSourceDefinition(
            id = SourceId("amt-genova-casella-gtfs"),
            displayName = "AMT Genova–Casella — GTFS",
            operatorOrAuthority = "AMT Genova",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.GATEWAY_ONLY,
            readiness = SourceReadiness.IMPORT_VIA_GATEWAY,
            countryCodes = setOf("IT"),
            coverage = "Ferrovia Genova–Casella inclusa nel feed urbano/metropolitano.",
            landingPage = uri("https://www.ferroviagenovacasella.it/geca/"),
            downloadOrProbeUri = uri("http://opendatagoogletransit.comune.genova.it/downloads/google_transit.zip"),
            attribution = "Comune di Genova / AMT",
            notes = "Feed verificato soltanto in HTTP; il client Binario blocca cleartext, quindi import offline o gateway HTTPS."
        ),
        RailSourceDefinition(
            id = SourceId("cotral-ferro-gtfs"),
            displayName = "Cotral/ASTRAL — GTFS Ferro",
            operatorOrAuthority = "Cotral / ASTRAL",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.GATEWAY_ONLY,
            readiness = SourceReadiness.IMPORT_VIA_GATEWAY,
            countryCodes = setOf("IT"),
            coverage = "Roma–Viterbo e Metromare quando rappresentate nel feed ferroviario.",
            landingPage = uri("https://infomobilita.astralspa.it/"),
            downloadOrProbeUri = uri("http://travel.mob.cotralspa.it:7777/GTFS/GTFS_FERRO.zip"),
            attribution = "Cotral / ASTRAL",
            notes = "Feed verificato soltanto in HTTP; usare import offline o gateway HTTPS e verificare licenza/versione."
        ),
        RailSourceDefinition(
            id = SourceId("lombardia-trenord-gtfs"),
            displayName = "Regione Lombardia — GTFS ferroviario",
            operatorOrAuthority = "Regione Lombardia / Trenord",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_GTFS_PACK,
            countryCodes = setOf("IT"),
            coverage = "Trenord, Malpensa Express e reti Ferrovienord incluse nella distribuzione pubblicata.",
            landingPage = uri("https://www.dati.lombardia.it/Mobilit-e-trasporti/Orario-Ferroviario-Regionale-Gtfs/3z4k-mxz9"),
            downloadOrProbeUri = uri("https://www.dati.lombardia.it/download/3z4k-mxz9/application/zip"),
            attribution = "Regione Lombardia / Trenord",
            notes = "GTFS ferroviario regionale già validato e incluso tra i data pack Binario; mantenere manifest, hash e licenza allineati alla versione distribuita."
        ),
        RailSourceDefinition(
            id = SourceId("eav-gtfs"),
            displayName = "EAV Campania — Open Data",
            operatorOrAuthority = "EAV S.r.l.",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_GTFS_PACK,
            countryCodes = setOf("IT"),
            coverage = "Vesuviane, Flegree e linee ferroviarie EAV incluse nella distribuzione corrente.",
            landingPage = uri("https://www.eavsrl.it/open-data/"),
            downloadOrProbeUri = uri("https://www.wimob.it/cfile/download.php?file=google-transit.zip"),
            attribution = "EAV S.r.l. — Italia Open Data Licence",
            notes = "La pagina open data EAV indica il GTFS ferro/gomma aggiornato con cadenza mensile e licenza aperta; filtrare le sole componenti ferroviarie in import."
        ),
        RailSourceDefinition(
            id = SourceId("toscana-trenitalia-gtfs"),
            displayName = "Regione Toscana / Trenitalia — GTFS regionale",
            operatorOrAuthority = "Regione Toscana / Trenitalia S.p.A.",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_GTFS_PACK,
            countryCodes = setOf("IT"),
            coverage = "Servizi ferroviari regionali Trenitalia pubblicati dalla Regione Toscana.",
            landingPage = uri("https://dati.toscana.it/dataset/rt-oraritb"),
            downloadOrProbeUri = uri("https://dati.toscana.it/dataset/8bb8f8fe-fe7d-41d0-90dc-49f2456180d1/resource/4f85393b-357d-443d-8378-65de4198505f/download/trenitalia.gtfs"),
            attribution = "Regione Toscana / Trenitalia — Creative Commons Attribution",
            notes = "Fonte ufficiale regionale in GTFS. Importare come data pack versionato e applicare calendar/calendar_dates prima di pubblicare le corse."
        ),
        RailSourceDefinition(
            id = SourceId("toscana-tft-gtfs"),
            displayName = "Regione Toscana / TFT — GTFS",
            operatorOrAuthority = "Regione Toscana / Trasporto Ferroviario Toscano",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_GTFS_PACK,
            countryCodes = setOf("IT"),
            coverage = "Arezzo–Stia e Arezzo–Sinalunga.",
            landingPage = uri("https://dati.toscana.it/dataset/rt-oraritb"),
            downloadOrProbeUri = uri("https://dati.toscana.it/dataset/8bb8f8fe-fe7d-41d0-90dc-49f2456180d1/resource/59aeacbc-99ee-410b-ac1e-622b5574a666/download/tft.gtfs"),
            attribution = "Regione Toscana / Trasporto Ferroviario Toscano — Creative Commons Attribution",
            notes = "GTFS ufficiale regionale; il pack resta ferroviario anche quando route_type richiede normalizzazione specifica della fonte."
        ),
        RailSourceDefinition(
            id = SourceId("hzpp-gtfs"),
            displayName = "HŽ Putnički prijevoz — GTFS",
            operatorOrAuthority = "HŽPP",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_GTFS_PACK,
            countryCodes = setOf("HR"),
            coverage = "Rete croata; il data pack verificato non contiene fermate italiane. Eventuali future estensioni verso l'Italia vanno ri-verificate prima di dichiarare copertura IT.",
            landingPage = uri("https://www.hzpp.hr/en/timetable"),
            downloadOrProbeUri = uri("https://www.hzpp.hr/GTFS_files.zip"),
            attribution = "HŽ Putnički prijevoz",
            notes = "ZIP HTTPS e set minimo GTFS verificati; nessuna fermata italiana va presunta senza controllo del data pack."
        ),
        RailSourceDefinition(
            id = SourceId("sncf-gtfs"),
            displayName = "SNCF — GTFS statico",
            operatorOrAuthority = "SNCF Voyageurs",
            formats = setOf(SourceFormat.GTFS_ZIP, SourceFormat.NETEX),
            capabilities = staticCapabilities,
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_GTFS_PACK,
            countryCodes = setOf("FR", "IT"),
            coverage = "TGV INOUI e TER con tratte/fermate italiane presenti nel feed.",
            landingPage = uri("https://ressources.data.sncf.com/pages/accueil/"),
            downloadOrProbeUri = uri("https://eu.ftp.opendatasoft.com/sncf/plandata/Export_OpenData_SNCF_GTFS_NewTripId.zip"),
            attribution = "SNCF Open Data",
            notes = "ZIP HTTPS e set minimo GTFS verificati; filtrare i servizi rilevanti all'Italia nella pipeline."
        ),
        RailSourceDefinition(
            id = SourceId("sncf-gtfs-rt-trip-updates"),
            displayName = "SNCF — GTFS-RT Trip Updates",
            operatorOrAuthority = "SNCF Voyageurs / transport.data.gouv.fr",
            formats = setOf(SourceFormat.GTFS_REALTIME_PROTOBUF),
            capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME),
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_PROTOCOL_TEST,
            countryCodes = setOf("FR", "IT"),
            coverage = "Aggiornamenti delle corse SNCF, incluse quelle con tratta italiana quando presenti.",
            landingPage = uri("https://www.data.gouv.fr/datasets/horaires-sncf"),
            downloadOrProbeUri = uri("https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-trip-updates"),
            attribution = "SNCF Open Data / transport.data.gouv.fr",
            notes = "Payload protobuf HTTPS verificato; richiede modulo GTFS-RT e fixture binarie dedicate prima dell'abilitazione."
        ),
        RailSourceDefinition(
            id = SourceId("sncf-gtfs-rt-alerts"),
            displayName = "SNCF — GTFS-RT Service Alerts",
            operatorOrAuthority = "SNCF Voyageurs / transport.data.gouv.fr",
            formats = setOf(SourceFormat.GTFS_REALTIME_PROTOBUF),
            capabilities = setOf(ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_PROTOCOL_TEST,
            countryCodes = setOf("FR", "IT"),
            coverage = "Avvisi SNCF con oggetti interessati e finestre temporali.",
            landingPage = uri("https://www.data.gouv.fr/datasets/horaires-sncf"),
            downloadOrProbeUri = uri("https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-service-alerts"),
            attribution = "SNCF Open Data / transport.data.gouv.fr",
            notes = "Payload protobuf HTTPS verificato; non viene decodificato in questa tranche statica."
        ),
        RailSourceDefinition(
            id = SourceId("ch-otd-gtfs"),
            displayName = "Open Data Platform Mobility Switzerland — GTFS",
            operatorOrAuthority = "ODMCH / SBB Infrastructure",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_GTFS_PACK,
            countryCodes = setOf("CH", "IT"),
            coverage = "SBB, TILO, BLS, RhB e servizi transfrontalieri inclusi nella distribuzione nazionale.",
            landingPage = uri("https://data.opentransportdata.swiss/dataset/timetable-2026-gtfs2020"),
            downloadOrProbeUri = uri("https://data.opentransportdata.swiss/dataset/3d2c18f9-9ef1-463f-a249-5c67604efd74/resource/32c2dfb4-8953-45b5-b271-211a2fa3aa18/download/gtfs_fp2026_2025-08-14.zip"),
            attribution = "opentransportdata.swiss",
            notes = "Data pack ferroviario filtrato e verificato incluso nell’APK; aggiornare con manifest e attribuzione a ogni release."
        ),
        RailSourceDefinition(
            id = SourceId("ch-otd-gtfs-rt"),
            displayName = "Open Data Platform Mobility Switzerland — GTFS-RT",
            operatorOrAuthority = "ODMCH / SBB Infrastructure",
            formats = setOf(SourceFormat.GTFS_REALTIME_PROTOBUF),
            capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME, ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.GATEWAY_ONLY,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("CH", "IT"),
            coverage = "Realtime e avvisi della rete svizzera e servizi transfrontalieri coperti.",
            landingPage = uri("https://opentransportdata.swiss/en/cookbook/realtime-prediction-cookbook/gtfs-rt/"),
            attribution = "opentransportdata.swiss",
            notes = "I servizi richiedono registrazione/access code e limiti; non inserire il token nell'APK."
        ),
        RailSourceDefinition(
            id = SourceId("oebb-gtfs"),
            displayName = "ÖBB — GTFS Fahrplan",
            operatorOrAuthority = "ÖBB Personenverkehr AG",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_GTFS_PACK,
            countryCodes = setOf("AT", "IT"),
            coverage = "Railjet, EuroCity e Nightjet presenti nel feed e relative fermate italiane da filtrare.",
            landingPage = uri("https://data.oebb.at/de/datensaetze~soll-fahrplan-gtfs~"),
            downloadOrProbeUri = uri("https://static.web.oebb.at/open-data/soll-fahrplan-gtfs/GTFS_Fahrplan_2026.zip"),
            attribution = "ÖBB Personenverkehr AG — CC BY 4.0",
            notes = "Data pack ferroviario filtrato e verificato incluso nell’APK dopo accettazione autorizzata delle condizioni del portale; aggiornare annualmente."
        ),
        RailSourceDefinition(
            id = SourceId("viaggiatreno"),
            displayName = "ViaggiaTreno — stato corse",
            operatorOrAuthority = "Trenitalia",
            formats = setOf(SourceFormat.HTML_OR_PDF, SourceFormat.OPERATOR_GATEWAY),
            capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME, ProviderCapability.STATION_BOARD),
            accessMode = AccessMode.PUBLIC_BEST_EFFORT,
            readiness = SourceReadiness.BEST_EFFORT_DISABLED,
            countryCodes = setOf("IT"),
            coverage = "Corse Trenitalia sulle reti esposte dal servizio pubblico.",
            landingPage = uri("http://www.viaggiatreno.it/viaggiatrenomobile/pages/info/info.jsp?title=info"),
            attribution = "Trenitalia",
            notes = "Nessuna API open documentata; risposta 503 durante la verifica. Solo gateway prudente/feature flag futuro."
        ),
        RailSourceDefinition(
            id = SourceId("rfi-programmed"),
            displayName = "RFI — Quadri orario programmati",
            operatorOrAuthority = "Rete Ferroviaria Italiana",
            formats = setOf(SourceFormat.HTML_OR_PDF),
            capabilities = setOf(
                ProviderCapability.JOURNEY_SEARCH,
                ProviderCapability.TRAIN_RUN_STATIC,
                ProviderCapability.STATION_BOARD
            ),
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_PROTOCOL_TEST,
            countryCodes = setOf("IT"),
            coverage = "Orario programmato ufficiale stazione per stazione per gli hub con Id PRM verificato.",
            landingPage = uri("https://www.rfi.it/it/stazioni/pagine-stazioni/servizi-di-qualita/informazioni-al-pubblico/quadri-orario-on-line.html"),
            attribution = "Rete Ferroviaria Italiana",
            notes = "Fonte ufficiale di orario programmato, equivalente ai quadri murali. Non attribuisce ritardo, stato operativo o binario reale; gli Id stazione vengono aggiunti solo dopo verifica."
        ),
        RailSourceDefinition(
            id = SourceId("rfi-station-board"),
            displayName = "RFI — Monitor Arrivi/Partenze",
            operatorOrAuthority = "Rete Ferroviaria Italiana",
            formats = setOf(SourceFormat.HTML_OR_PDF, SourceFormat.OPERATOR_GATEWAY),
            capabilities = setOf(ProviderCapability.STATION_BOARD),
            accessMode = AccessMode.PUBLIC_BEST_EFFORT,
            readiness = SourceReadiness.BEST_EFFORT_DISABLED,
            countryCodes = setOf("IT"),
            coverage = "Tabelloni e informazioni pubbliche delle stazioni RFI.",
            landingPage = uri("https://www.rfi.it/it/stazioni/pagine-stazioni/servizi-di-qualita/informazioni-al-pubblico/monitor-arrivi-partenze-live.html"),
            attribution = "RFI",
            notes = "Pagina informativa HTTPS verificata; non trattare il monitor come API garantita."
        ),
        RailSourceDefinition(
            id = SourceId("rfi-infomobility-rss"),
            displayName = "RFI — Infomobilità RSS",
            operatorOrAuthority = "Rete Ferroviaria Italiana",
            formats = setOf(SourceFormat.RSS_XML),
            capabilities = setOf(ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_PROTOCOL_TEST,
            countryCodes = setOf("IT"),
            coverage = "Aggiornamenti nazionali di circolazione pubblicati dalla Sala Operativa Nazionale RFI.",
            landingPage = uri("https://www.rfi.it/it/news-e-media/feed---rss.html"),
            downloadOrProbeUri = uri("https://www.rfi.it/content/rfi/it/news-e-media/infomobilita.rss.updates.xml"),
            attribution = "Rete Ferroviaria Italiana",
            notes = "Feed RSS ufficiale e pubblico; usarlo per avvisi di rete con TTL e provenienza, non come stato puntuale del singolo treno."
        ),
        RailSourceDefinition(
            id = SourceId("rfi-infomobility"),
            displayName = "RFI — Infomobilità",
            operatorOrAuthority = "Rete Ferroviaria Italiana",
            formats = setOf(SourceFormat.HTML_OR_PDF),
            capabilities = setOf(ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.PUBLIC_BEST_EFFORT,
            readiness = SourceReadiness.BEST_EFFORT_DISABLED,
            countryCodes = setOf("IT"),
            coverage = "Avvisi della circolazione sulla rete nazionale.",
            landingPage = uri("https://www.rfi.it/it/news-e-media/infomobilita.html"),
            attribution = "RFI",
            notes = "Parser eventuale solo dietro gateway con attribuzione, cache e revisione manuale della stabilità."
        ),
        RailSourceDefinition(
            id = SourceId("italo-status"),
            displayName = "Italo in Viaggio",
            operatorOrAuthority = "NTV S.p.A.",
            formats = setOf(SourceFormat.HTML_OR_PDF, SourceFormat.OPERATOR_GATEWAY),
            capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME, ProviderCapability.STATION_BOARD, ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.PUBLIC_BEST_EFFORT,
            readiness = SourceReadiness.BEST_EFFORT_DISABLED,
            countryCodes = setOf("IT"),
            coverage = "Stato, tabelloni e avvisi dei servizi Italo.",
            landingPage = uri("https://italoinviaggio.italotreno.com/"),
            attribution = "Italo / NTV",
            notes = "Tracker HTTPS verificato; nessun token/app ufficiale o endpoint non documentato viene riusato."
        ),
        RailSourceDefinition(
            id = SourceId("ch-transport-opendata"),
            displayName = "transport.opendata.ch — tabelloni svizzeri",
            operatorOrAuthority = "Opendata.ch (API pubblica su dati orario svizzeri)",
            formats = setOf(SourceFormat.JSON_API),
            capabilities = setOf(ProviderCapability.STATION_BOARD),
            accessMode = AccessMode.PUBLIC_BEST_EFFORT,
            readiness = SourceReadiness.BEST_EFFORT_DISABLED,
            countryCodes = setOf("CH", "IT"),
            coverage = "Partenze e arrivi delle stazioni svizzere e transfrontaliere identificate da un numero UIC 85…",
            landingPage = uri("https://transport.opendata.ch/docs.html"),
            downloadOrProbeUri = uri("https://transport.opendata.ch/v1/stationboard?id=8505300&limit=3"),
            attribution = "transport.opendata.ch",
            notes = "API pubblica documentata senza token con limiti di uso corretto; attiva solo con le fonti personali, cache 60 s e circuit breaker. Le previsioni incoerenti con il lato del tabellone vengono scartate."
        ),
        RailSourceDefinition(
            id = SourceId("viaggiatreno-train-notices"),
            displayName = "ViaggiaTreno — avvisi per treno",
            operatorOrAuthority = "Trenitalia",
            formats = setOf(SourceFormat.JSON_API),
            capabilities = setOf(ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.PUBLIC_BEST_EFFORT,
            readiness = SourceReadiness.BEST_EFFORT_DISABLED,
            countryCodes = setOf("IT"),
            coverage = "Avvisi Trenitalia per numero treno commerciale (ritardi e cause pubblicati).",
            landingPage = uri("https://www.viaggiatreno.it/infomobilitamobile/pages/cercaTreno/cercaTreno.jsp"),
            downloadOrProbeUri = uri("https://www.viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=8829"),
            attribution = "Trenitalia",
            notes = "Stessa famiglia best effort di ViaggiaTreno: HTTPS reindirizzato alla stessa risorsa HTTP su www.viaggiatreno.it, unico dominio in chiaro consentito. La risposta contiene molti giorni passati: si tengono solo le note valide nel giorno della corsa, per numero e stazione di origine."
        ),
        RailSourceDefinition(
            id = SourceId("trenitalia-infomobility-news"),
            displayName = "Trenitalia — Infomobilità (ViaggiaTreno)",
            operatorOrAuthority = "Trenitalia",
            formats = setOf(SourceFormat.JSON_API),
            capabilities = setOf(ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.PUBLIC_BEST_EFFORT,
            readiness = SourceReadiness.BEST_EFFORT_DISABLED,
            countryCodes = setOf("IT"),
            coverage = "Notizie nazionali e regionali di circolazione Trenitalia, con i numeri dei treni interessati.",
            landingPage = uri("https://www.trenitalia.com/it/informazioni/Infomobilita/notizie-infomobilita.html"),
            downloadOrProbeUri = uri("https://www.viaggiatreno.it/infomobilitamobile/resteasy/news/infomobility"),
            attribution = "Trenitalia",
            notes = "Famiglia ViaggiaTreno best effort, stesso reindirizzamento HTTP limitato a www.viaggiatreno.it. Descrizioni HTML con entità codificate due volte: decodifica e riduzione a testo con jsoup, mai resa come HTML."
        ),
        RailSourceDefinition(
            id = SourceId("trenord-line-notices"),
            displayName = "Trenord — avvisi per direttrice",
            operatorOrAuthority = "Trenord S.r.l.",
            formats = setOf(SourceFormat.JSON_API),
            capabilities = setOf(ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.PUBLIC_BEST_EFFORT,
            readiness = SourceReadiness.BEST_EFFORT_DISABLED,
            countryCodes = setOf("IT", "CH"),
            coverage = "Avvisi testuali Trenord per ogni direttrice (lavori, variazioni, scioperi, bus sostitutivi).",
            landingPage = uri("https://www.trenord.it/linee-e-orari/circolazione/le-nostre-linee/"),
            downloadOrProbeUri = uri("https://www.trenord.it/mgmt/store-management-api/mia/direttrici/"),
            attribution = "Trenord",
            notes = "JSON pubblico di circa 90 KB usato dal sito Trenord; best effort con TTL 15 minuti. Solo avvisi: i ritardi strutturati restano legati all'adesione E015. Le notizie promozionali senza parole chiave operative vengono scartate."
        ),
        RailSourceDefinition(
            id = SourceId("open-meteo"),
            displayName = "Open-Meteo — previsioni giornaliere",
            operatorOrAuthority = "Open-Meteo.com",
            formats = setOf(SourceFormat.JSON_API),
            capabilities = setOf(ProviderCapability.WEATHER_FORECAST),
            accessMode = AccessMode.OPEN_DIRECT,
            readiness = SourceReadiness.READY_FOR_PROTOCOL_TEST,
            countryCodes = emptySet(),
            coverage = "Previsione giornaliera (tempo, temperature, probabilità di precipitazioni) per la stazione di arrivo, oggi e domani.",
            landingPage = uri("https://open-meteo.com/en/terms"),
            downloadOrProbeUri = uri("https://api.open-meteo.com/v1/forecast?latitude=46.00&longitude=8.95&daily=weather_code&forecast_days=2"),
            attribution = "Dati meteo: Open-Meteo.com (CC BY 4.0)",
            notes = "Licenza CC BY 4.0, uso non commerciale gratuito, nessuna chiave. Riceve solo le coordinate della stazione arrotondate a due decimali; disattivabile nelle impostazioni."
        ),
        RailSourceDefinition(
            id = SourceId("trenord-realtime"),
            displayName = "Trenord — tempo reale e avvisi",
            operatorOrAuthority = "Trenord S.r.l.",
            formats = setOf(SourceFormat.HTML_OR_PDF, SourceFormat.OPERATOR_GATEWAY),
            capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME, ProviderCapability.STATION_BOARD, ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.PUBLIC_BEST_EFFORT,
            readiness = SourceReadiness.BEST_EFFORT_DISABLED,
            countryCodes = setOf("IT"),
            coverage = "Trenord e Malpensa Express.",
            landingPage = uri("https://www.trenord.it/linee-e-orari/circolazione/tempo-reale/"),
            attribution = "Trenord",
            notes = "Pagina HTTPS verificata; realtime strutturato da valutare tramite canale autorizzato/E015."
        ),
        RailSourceDefinition(
            id = SourceId("gtt-gtfs"),
            displayName = "GTT Torino — GTFS",
            operatorOrAuthority = "Gruppo Torinese Trasporti S.p.A.",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("IT"),
            coverage = "Feed multimodale GTT che include anche l'agenzia ferroviaria; verificare i servizi ferroviari effettivamente attivi prima dell'import.",
            landingPage = uri("https://www.gtt.to.it/gtt_gtfs_license.html"),
            downloadOrProbeUri = uri("https://www.gtt.to.it/open_data/gtt_gtfs.zip"),
            attribution = "GTT S.p.A.",
            notes = "Il feed è pubblico ma la licenza ufficiale corrente limita l'uso non commerciale; non abilitarlo in produzione senza autorizzazione compatibile con la distribuzione di Binario."
        ),
        RailSourceDefinition(
            id = SourceId("sz-passenger"),
            displayName = "SŽ Potniški promet — Italia",
            operatorOrAuthority = "Slovenske železnice",
            formats = setOf(SourceFormat.HTML_OR_PDF),
            capabilities = setOf(ProviderCapability.JOURNEY_SEARCH, ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("SI", "IT"),
            coverage = "Collegamenti ufficiali verso Trieste, Trieste Airport, Palmanova e Udine.",
            landingPage = uri("https://potniski.sz.si/en/international-travel/italy-i/trieste-trieste-airport-palmanova-udine/"),
            attribution = "SŽ Potniški promet",
            notes = "Orari e avvisi ufficiali; nessun feed pubblico completo del tratto italiano verificato."
        ),
        RailSourceDefinition(
            id = SourceId("fuc-udine-cividale"),
            displayName = "Ferrovie Udine–Cividale",
            operatorOrAuthority = "FUC",
            formats = setOf(SourceFormat.HTML_OR_PDF),
            capabilities = setOf(ProviderCapability.JOURNEY_SEARCH, ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("IT"),
            coverage = "Udine–Cividale e servizi Mi.Co.Tra. con partner austriaci.",
            landingPage = uri("https://www.ferrovieudinecividale.it/orari-fuc-udine-cividale/"),
            attribution = "FUC",
            notes = "Orari pubblici ma feed strutturato/realtime da richiedere."
        ),
        RailSourceDefinition(
            id = SourceId("fse-puglia"),
            displayName = "Ferrovie del Sud Est",
            operatorOrAuthority = "FSE / Gruppo FS",
            formats = setOf(SourceFormat.HTML_OR_PDF),
            capabilities = setOf(ProviderCapability.JOURNEY_SEARCH, ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("IT"),
            coverage = "Linee ferroviarie FSE e autosostituzioni da modellare separatamente.",
            landingPage = uri("https://www.fseonline.it/"),
            attribution = "Ferrovie del Sud Est",
            notes = "Non usare dataset regionali storici o aggregatori come fonte primaria."
        ),
        RailSourceDefinition(
            id = SourceId("fal-basilicata-puglia"),
            displayName = "Ferrovie Appulo Lucane",
            operatorOrAuthority = "FAL",
            formats = setOf(SourceFormat.HTML_OR_PDF),
            capabilities = setOf(ProviderCapability.JOURNEY_SEARCH, ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("IT"),
            coverage = "Rete FAL fra Puglia e Basilicata.",
            landingPage = uri("https://ferrovieappulolucane.it/tratta/orari/"),
            attribution = "Ferrovie Appulo Lucane",
            notes = "Feed GTFS/NeTEx e realtime da richiedere all'operatore."
        ),
        RailSourceDefinition(
            id = SourceId("ferrotramviaria-bari-barletta"),
            displayName = "Ferrotramviaria",
            operatorOrAuthority = "Ferrotramviaria S.p.A.",
            formats = setOf(SourceFormat.HTML_OR_PDF),
            capabilities = setOf(ProviderCapability.JOURNEY_SEARCH, ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("IT"),
            coverage = "Bari–Barletta e collegamento aeroporto quando attivo.",
            landingPage = uri("https://www.ferrotramviaria.it/web/guest/avvisi"),
            attribution = "Ferrotramviaria",
            notes = "Realtime pubblico non documentato; richiedere feed diretto."
        ),
        RailSourceDefinition(
            id = SourceId("fce-circumetnea"),
            displayName = "Ferrovia Circumetnea",
            operatorOrAuthority = "FCE",
            formats = setOf(SourceFormat.HTML_OR_PDF),
            capabilities = setOf(ProviderCapability.JOURNEY_SEARCH, ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("IT"),
            coverage = "Rete ferroviaria Circumetnea; metropolitana fuori perimetro ferroviario iniziale.",
            landingPage = uri("https://www.circumetnea.it/category/news/"),
            attribution = "FCE",
            notes = "Non usare feed che rappresentano soltanto il bus; richiedere feed ferroviario dedicato."
        ),
        RailSourceDefinition(
            id = SourceId("fdc-calabria"),
            displayName = "Ferrovie della Calabria",
            operatorOrAuthority = "Ferrovie della Calabria",
            formats = setOf(SourceFormat.HTML_OR_PDF),
            capabilities = setOf(ProviderCapability.JOURNEY_SEARCH, ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("IT"),
            coverage = "Servizi ferroviari e turistici attivi, con stato linea da verificare.",
            landingPage = uri("https://web.ferroviedellacalabria.it/orari-dei-trasporti/"),
            attribution = "Ferrovie della Calabria",
            notes = "PDF/XLS pubblici; feed strutturato ferroviario da richiedere."
        ),
        RailSourceDefinition(
            id = SourceId("tua-abruzzo"),
            displayName = "TUA Abruzzo",
            operatorOrAuthority = "TUA",
            formats = setOf(SourceFormat.HTML_OR_PDF),
            capabilities = setOf(ProviderCapability.JOURNEY_SEARCH, ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("IT"),
            coverage = "Servizi ferroviari regionali TUA e collegamenti sulle linee interessate.",
            landingPage = uri("https://www.tuabruzzo.it/comunicazioni/25.html"),
            attribution = "TUA",
            notes = "Feed ferroviario GTFS/NeTEx non verificato come aperto."
        ),
        RailSourceDefinition(
            id = SourceId("arenaways"),
            displayName = "Arenaways",
            operatorOrAuthority = "Arenaways / Longitude Holding",
            formats = setOf(SourceFormat.HTML_OR_PDF),
            capabilities = setOf(ProviderCapability.JOURNEY_SEARCH, ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("IT"),
            coverage = "Servizi regionali open access attivi e future espansioni solo dopo conferma ufficiale.",
            landingPage = uri("https://arenaways.it/orari-e-tariffe/orari-treni"),
            attribution = "Arenaways",
            notes = "Orari HTML/PDF; feed strutturato da richiedere."
        ),
        RailSourceDefinition(
            id = SourceId("fs-tourist-trains"),
            displayName = "FS Treni Turistici Italiani / Fondazione FS",
            operatorOrAuthority = "Gruppo FS",
            formats = setOf(SourceFormat.HTML_OR_PDF),
            capabilities = setOf(ProviderCapability.JOURNEY_SEARCH, ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.MONITOR_ONLY,
            countryCodes = setOf("IT"),
            coverage = "Servizi turistici pubblici e stagionali, esclusi charter privati.",
            landingPage = uri("https://www.fstrenituristici.it/"),
            attribution = "FS Treni Turistici Italiani / Fondazione FS",
            notes = "Catalogare solo corse con calendario e vendita pubblica verificabili."
        ),
        RailSourceDefinition(
            id = SourceId("european-sleeper"),
            displayName = "European Sleeper verso Milano",
            operatorOrAuthority = "European Sleeper",
            formats = setOf(SourceFormat.HTML_OR_PDF),
            capabilities = setOf(ProviderCapability.JOURNEY_SEARCH, ProviderCapability.SERVICE_ALERTS),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.MONITOR_ONLY,
            countryCodes = setOf("BE", "NL", "DE", "CH", "IT"),
            coverage = "Servizio futuro da aggiungere solo alla data ufficiale di avvio e con dati del tratto italiano verificati.",
            landingPage = uri("https://www.europeansleeper.eu/"),
            attribution = "European Sleeper",
            notes = "Non trattare come attivo finché l'avvio e l'operatore del tratto italiano non sono ufficialmente confermati."
        )
    )

    fun byId(sourceId: SourceId): RailSourceDefinition? = all.firstOrNull { it.id == sourceId }

    fun testableNow(): List<RailSourceDefinition> = all.filter {
        it.readiness == SourceReadiness.READY_FOR_GTFS_PACK ||
            it.readiness == SourceReadiness.BEST_EFFORT_FALLBACK ||
            it.readiness == SourceReadiness.READY_FOR_PROTOCOL_TEST ||
            it.readiness == SourceReadiness.IMPORT_VIA_GATEWAY
    }

    fun activeCandidates(capability: ProviderCapability): List<RailSourceDefinition> = all.filter {
        capability in it.capabilities && it.readiness !in setOf(
            SourceReadiness.MONITOR_ONLY,
            SourceReadiness.AGREEMENT_REQUIRED,
            SourceReadiness.BEST_EFFORT_DISABLED
        )
    }
}
