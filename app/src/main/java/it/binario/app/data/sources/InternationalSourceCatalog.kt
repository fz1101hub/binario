package it.binario.app.data.sources

import it.binario.app.data.core.SourceId
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.ProviderCapability
import java.net.URI

/**
 * Fonti internazionali individuate nel censimento 2026. La readiness distingue
 * ciò che è attivabile, ciò che richiede credenziali e ciò che può essere usato
 * soltanto come fallback di copertura.
 */
object InternationalSourceCatalog {
    private fun uri(value: String): URI = URI.create(value)

    private val staticCapabilities = setOf(
        ProviderCapability.STATION_CATALOG,
        ProviderCapability.JOURNEY_SEARCH,
        ProviderCapability.TRAIN_RUN_STATIC
    )

    val all: List<RailSourceDefinition> = listOf(
        RailSourceDefinition(
            id = SourceId("delfi-gtfs"),
            displayName = "DELFI — Deutschlandweite Sollfahrplandaten GTFS",
            operatorOrAuthority = "DELFI / Deutschland",
            formats = setOf(SourceFormat.GTFS_ZIP, SourceFormat.NETEX),
            capabilities = staticCapabilities,
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("DE", "AT", "CH", "IT"),
            coverage = "Dataset nazionale tedesco con lunga percorrenza; complemento DB–ÖBB quando include le fermate della relazione richiesta.",
            landingPage = uri("https://www.opendata-oepnv.de/ht/de/organisation/delfi/startseite?tx_vrrkit_view%5Bdataset_name%5D=deutschlandweite-sollfahrplandaten-gtfs&tx_vrrkit_view%5Baction%5D=details&tx_vrrkit_view%5Bcontroller%5D=View"),
            attribution = "DELFI — CC BY 4.0",
            notes = "Fonte ufficiale aggiornata regolarmente; download ufficiale dietro registrazione. Preferire questa al mirror community."
        ),
        RailSourceDefinition(
            id = SourceId("delfi-gtfs-community"),
            displayName = "DELFI — GTFS community mirror",
            operatorOrAuthority = "DELFI / public-transport.earth mirror",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.PUBLIC_BEST_EFFORT,
            readiness = SourceReadiness.BEST_EFFORT_FALLBACK,
            countryCodes = setOf("DE", "AT", "CH", "IT"),
            coverage = "Mirror community del GTFS nazionale tedesco; usarlo solo quando la fonte ufficiale non è acquisibile e la tratta resterebbe scoperta.",
            landingPage = uri("https://github.com/public-transport/european-transport-feeds"),
            downloadOrProbeUri = uri("https://scraped.data.public-transport.earth/de/gtfs.zip"),
            attribution = "DELFI / opendata-oepnv.de — mirror public-transport.earth",
            notes = "Nessuno SLA ufficiale e copertura non garantita al 100%; fallback esplicito, mai preferito a ÖBB/SBB/SNCF/DELFI ufficiale per la stessa corsa."
        ),
        RailSourceDefinition(
            id = SourceId("db-timetables"),
            displayName = "Deutsche Bahn — Timetables API",
            operatorOrAuthority = "Deutsche Bahn AG",
            formats = setOf(SourceFormat.OPERATOR_GATEWAY),
            capabilities = setOf(ProviderCapability.TRAIN_RUN_STATIC, ProviderCapability.TRAIN_RUN_REALTIME, ProviderCapability.STATION_BOARD),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("DE", "AT", "CH", "IT"),
            coverage = "Sollfahrplan e variazioni per stazioni DB; utile per treni internazionali visibili sul tratto tedesco.",
            landingPage = uri("https://developers.deutschebahn.com/db-api-marketplace/apis/product/timetables"),
            attribution = "Deutsche Bahn AG — CC BY 4.0",
            notes = "Free plan 60 chiamate/minuto; richiede DB-Client-Id e DB-Api-Key. Adapter canonico già implementato, credenziali fuori APK."
        ),
        RailSourceDefinition(
            id = SourceId("db-ris-boards"),
            displayName = "Deutsche Bahn — RIS::Boards",
            operatorOrAuthority = "DB InfraGO / DB Reisendeninformation",
            formats = setOf(SourceFormat.OPERATOR_GATEWAY),
            capabilities = setOf(ProviderCapability.STATION_BOARD, ProviderCapability.TRAIN_RUN_REALTIME),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("DE"),
            coverage = "Tabelloni su rete DB InfraGO con tempi previsionali, binari, via, fermate saltate/aggiunte e sostitutivi.",
            landingPage = uri("https://developers.deutschebahn.com/db-api-marketplace/apis/product/ris-boards-netz"),
            attribution = "DB InfraGO / Deutsche Bahn",
            notes = "Fonte DB preferita per station-board operativo quando accessibile; richiede approvazione/piano RIS e credenziali DB."
        ),
        RailSourceDefinition(
            id = SourceId("db-ris-journeys"),
            displayName = "Deutsche Bahn — RIS::Journeys",
            operatorOrAuthority = "DB InfraGO / DB Reisendeninformation",
            formats = setOf(SourceFormat.OPERATOR_GATEWAY),
            capabilities = setOf(ProviderCapability.TRAIN_RUN_STATIC, ProviderCapability.TRAIN_RUN_REALTIME),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("DE", "AT", "CH", "IT"),
            coverage = "Percorso corsa e relativi eventi per i treni esposti dalla RIS-API; particolarmente utile per itinerari internazionali e variazioni di percorso.",
            landingPage = uri("https://developer-docs.deutschebahn.com/doku/apis/ris-journeys-10582266"),
            attribution = "DB Reisendeninformation / Deutsche Bahn",
            notes = "Preferibile a Timetables per ricostruire il percorso operativo completo quando l'accesso RIS è disponibile."
        ),
        RailSourceDefinition(
            id = SourceId("db-ris-disruptions"),
            displayName = "Deutsche Bahn — RIS::Disruptions",
            operatorOrAuthority = "DB Reisendeninformation",
            formats = setOf(SourceFormat.OPERATOR_GATEWAY),
            capabilities = setOf(ProviderCapability.SERVICE_ALERTS, ProviderCapability.TRAIN_RUN_REALTIME),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("DE", "AT", "CH", "IT"),
            coverage = "Perturbazioni con correlazione a corse, fermate e aree interessate.",
            landingPage = uri("https://developer-docs.deutschebahn.com/doku/apis/ris-disruptions-10686904"),
            attribution = "DB Reisendeninformation / Deutsche Bahn",
            notes = "Fonte preferita DB per disruption strutturate quando autorizzata."
        ),
        RailSourceDefinition(
            id = SourceId("european-sleeper-gtfs"),
            displayName = "European Sleeper — GTFS community feed",
            operatorOrAuthority = "European Sleeper / JBB feed mirror",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.PUBLIC_BEST_EFFORT,
            readiness = SourceReadiness.BEST_EFFORT_FALLBACK,
            countryCodes = setOf("BE", "NL", "DE", "CH", "IT"),
            coverage = "Bruxelles–Colonia–Zurigo–Lugano–Como–Milano dal 9 settembre 2026; solo se il feed contiene effettivamente le fermate richieste.",
            landingPage = uri("https://www.europeansleeper.eu/milan"),
            downloadOrProbeUri = uri("https://jbb.ghsq.de/gtfs/eu-es.gtfs.zip"),
            attribution = "European Sleeper / JBB community feed",
            notes = "Fallback esplicito: servizio Milano ufficiale, feed community. Non deve scalzare RFI o GTFS ufficiali."
        ),
        RailSourceDefinition(
            id = SourceId("european-sleeper-gtfs-rt"),
            displayName = "European Sleeper — GTFS-Realtime community feed",
            operatorOrAuthority = "European Sleeper / JBB feed mirror",
            formats = setOf(SourceFormat.GTFS_REALTIME_PROTOBUF),
            capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME),
            accessMode = AccessMode.PUBLIC_BEST_EFFORT,
            readiness = SourceReadiness.BEST_EFFORT_FALLBACK,
            countryCodes = setOf("BE", "NL", "DE", "CH", "IT"),
            coverage = "Trip Updates European Sleeper; fallback dal 9 settembre 2026 per corse non coperte da fonti operative migliori.",
            landingPage = uri("https://www.europeansleeper.eu/timetable"),
            downloadOrProbeUri = uri("https://jbb.ghsq.de/gtfs/european-sleeper/TripUpdates"),
            attribution = "European Sleeper / JBB community feed",
            notes = "Bassa priorità; matching contro GTFS statico e nessuna sostituzione di RFI/operator feed se presenti."
        ),
        monitoredOperator("westbahn-monitor", "WESTbahn", setOf("AT", "IT"), "https://westbahn.at/", "Monitorare eventuali servizi diretti verso l'Italia; nessun feed italiano verificato."),
        monitoredOperator("regiojet-monitor", "RegioJet", setOf("CZ", "AT", "IT"), "https://regiojet.com/", "Monitorare servizi internazionali 2026; non attivare senza feed con fermate italiane."),
        monitoredOperator("cd-monitor", "České dráhy (ČD)", setOf("CZ", "AT", "IT"), "https://www.cd.cz/en/", "Monitorare collegamenti internazionali; nessun feed italiano verificato."),
        monitoredOperator("mav-start-monitor", "MÁV-START", setOf("HU", "AT", "SI", "IT"), "https://www.mavcsoport.hu/en", "Monitorare servizi diretti/stagionali; RFI resta la fonte operativa sul tratto italiano quando disponibile."),
        monitoredOperator("snalltaget-monitor", "Snälltåget", setOf("SE", "DE", "AT", "IT"), "https://www.snalltaget.se/en", "Servizi stagionali variabili; nessuna copertura italiana strutturata va presunta."),
        monitoredOperator("urlaubs-express-monitor", "Urlaubs-Express", setOf("DE", "AT", "IT"), "https://urlaubs-express.de/?lang=en", "Servizi charter/stagionali soggetti a cancellazione o variazione; monitor-only.")
    )

    fun byId(id: SourceId): RailSourceDefinition? = all.firstOrNull { it.id == id }

    private fun monitoredOperator(id: String, name: String, countries: Set<String>, landing: String, note: String) = RailSourceDefinition(
        id = SourceId(id),
        displayName = "$name — monitor",
        operatorOrAuthority = name,
        formats = setOf(SourceFormat.HTML_OR_PDF),
        capabilities = setOf(ProviderCapability.JOURNEY_SEARCH, ProviderCapability.SERVICE_ALERTS),
        accessMode = AccessMode.PUBLIC_BEST_EFFORT,
        readiness = SourceReadiness.MONITOR_ONLY,
        countryCodes = countries,
        coverage = "Copertura italiana da dimostrare per data di servizio prima di qualsiasi attivazione.",
        landingPage = uri(landing),
        attribution = name,
        notes = note
    )
}
