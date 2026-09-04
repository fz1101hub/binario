package it.binario.app.data.sources

import it.binario.app.data.core.SourceId
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.ProviderCapability
import java.net.URI

/**
 * Fonti internazionali individuate nel censimento 2026 che non sono ancora
 * necessariamente importabili come asset embedded. Tenerle separate dal
 * catalogo attivo evita di confondere "fonte interessante" con "copertura
 * disponibile nell'app".
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
            coverage = "Dataset nazionale tedesco routing-capable con trasporto pubblico e lunga percorrenza; utile come complemento per DB–ÖBB EuroCity/Nightjet quando le fermate italiane sono presenti.",
            landingPage = uri("https://www.opendata-oepnv.de/ht/de/organisation/delfi/startseite?tx_vrrkit_view%5Bdataset_name%5D=deutschlandweite-sollfahrplandaten-gtfs&tx_vrrkit_view%5Baction%5D=details&tx_vrrkit_view%5Bcontroller%5D=View"),
            attribution = "DELFI — Creative Commons Attribution",
            notes = "Fonte ufficiale e aggiornata normalmente ogni lunedì. Il download ufficiale richiede registrazione: non usare mirror community come asset di produzione."
        ),
        RailSourceDefinition(
            id = SourceId("db-timetables"),
            displayName = "Deutsche Bahn — Timetables API",
            operatorOrAuthority = "Deutsche Bahn AG",
            formats = setOf(SourceFormat.OPERATOR_GATEWAY),
            capabilities = setOf(
                ProviderCapability.TRAIN_RUN_STATIC,
                ProviderCapability.TRAIN_RUN_REALTIME,
                ProviderCapability.STATION_BOARD
            ),
            accessMode = AccessMode.AGREEMENT_REQUIRED,
            readiness = SourceReadiness.AGREEMENT_REQUIRED,
            countryCodes = setOf("DE", "AT", "CH", "IT"),
            coverage = "Sollfahrplan e scostamenti per stazioni/corse DB; complemento per servizi DB–ÖBB internazionali quando l'API espone la stazione/corsa italiana.",
            landingPage = uri("https://developers.deutschebahn.com/db-api-marketplace/apis/product/timetables"),
            attribution = "Deutsche Bahn AG — CC BY 4.0",
            notes = "Richiede account/API Marketplace. Limite free documentato 60 chiamate/minuto. Le credenziali devono vivere in gateway/secret storage, mai nell'APK."
        ),
        RailSourceDefinition(
            id = SourceId("european-sleeper-gtfs"),
            displayName = "European Sleeper — GTFS community feed",
            operatorOrAuthority = "European Sleeper / JBB feed mirror",
            formats = setOf(SourceFormat.GTFS_ZIP),
            capabilities = staticCapabilities,
            accessMode = AccessMode.PUBLIC_BEST_EFFORT,
            readiness = SourceReadiness.MONITOR_ONLY,
            countryCodes = setOf("BE", "NL", "DE", "CH", "IT"),
            coverage = "Bruxelles–Colonia–Zurigo–Como–Milano dal 9 settembre 2026, solo dopo verifica diretta delle fermate italiane nel feed.",
            landingPage = uri("https://www.europeansleeper.eu/milan"),
            downloadOrProbeUri = uri("https://jbb.ghsq.de/gtfs/eu-es.gtfs.zip"),
            attribution = "European Sleeper / feed community JBB",
            notes = "Il servizio Milano è ufficiale, ma il feed indicato non è pubblicato sul sito dell'operatore e la licenza non è stata verificata come fonte primaria. Monitorare; non distribuire come pack finché provenienza/licenza e fermate italiane non sono confermate."
        ),
        RailSourceDefinition(
            id = SourceId("european-sleeper-gtfs-rt"),
            displayName = "European Sleeper — GTFS-Realtime community feed",
            operatorOrAuthority = "European Sleeper / JBB feed mirror",
            formats = setOf(SourceFormat.GTFS_REALTIME_PROTOBUF),
            capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME),
            accessMode = AccessMode.PUBLIC_BEST_EFFORT,
            readiness = SourceReadiness.MONITOR_ONLY,
            countryCodes = setOf("BE", "NL", "DE", "CH", "IT"),
            coverage = "Trip Updates European Sleeper; abilitazione solo dopo verifica della nuova relazione Milano e della stabilità del feed.",
            landingPage = uri("https://www.europeansleeper.eu/timetable"),
            downloadOrProbeUri = uri("https://jbb.ghsq.de/gtfs/european-sleeper/TripUpdates"),
            attribution = "European Sleeper / feed community JBB",
            notes = "Monitor-only: il servizio Bruxelles–Milano parte il 9 settembre 2026; non assumere che il realtime contenga già il tratto italiano prima della verifica post-lancio."
        ),
        monitoredOperator(
            id = "westbahn-monitor",
            name = "WESTbahn",
            countries = setOf("AT", "IT"),
            landing = "https://westbahn.at/",
            note = "Tenere sotto osservazione eventuali servizi diretti verso l'Italia; nessun feed ufficiale italiano verificato in questa tranche."
        ),
        monitoredOperator(
            id = "regiojet-monitor",
            name = "RegioJet",
            countries = setOf("CZ", "AT", "IT"),
            landing = "https://regiojet.com/",
            note = "Monitorare servizi internazionali 2026; non attivare senza feed ufficiale/licenziato con fermate italiane."
        ),
        monitoredOperator(
            id = "cd-monitor",
            name = "České dráhy (ČD)",
            countries = setOf("CZ", "AT", "IT"),
            landing = "https://www.cd.cz/en/",
            note = "Monitorare collegamenti internazionali; nessun feed italiano verificato."
        ),
        monitoredOperator(
            id = "mav-start-monitor",
            name = "MÁV-START",
            countries = setOf("HU", "AT", "SI", "IT"),
            landing = "https://www.mavcsoport.hu/en",
            note = "Monitorare eventuali servizi diretti/stagionali con fermate italiane; usare RFI sul tratto italiano quando tracciati."
        ),
        monitoredOperator(
            id = "snalltaget-monitor",
            name = "Snälltåget",
            countries = setOf("SE", "DE", "AT", "IT"),
            landing = "https://www.snalltaget.se/en",
            note = "Servizi stagionali variabili: nessuna copertura italiana strutturata va presunta."
        ),
        monitoredOperator(
            id = "urlaubs-express-monitor",
            name = "Urlaubs-Express",
            countries = setOf("DE", "AT", "IT"),
            landing = "https://urlaubs-express.de/?lang=en",
            note = "Servizi charter/stagionali soggetti a cancellazione o variazione; monitor-only."
        )
    )

    fun byId(id: SourceId): RailSourceDefinition? = all.firstOrNull { it.id == id }

    private fun monitoredOperator(
        id: String,
        name: String,
        countries: Set<String>,
        landing: String,
        note: String
    ) = RailSourceDefinition(
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
