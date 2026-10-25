package it.binario.app.data.provider

import it.binario.app.BuildConfig

import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.sources.RailSourceCatalog
import java.time.Duration

object KnownProviderIds {
    val GTFS_ITALY_REGIONAL = SourceId("gtfs-italy-regional")
    val GTFS_SWISS = SourceId("ch-otd-gtfs")
    val GTFS_RT_SWISS = SourceId("ch-otd-gtfs-rt")
    val SNCF_STATIC = SourceId("sncf-gtfs")
    val SNCF_REALTIME = SourceId("sncf-gtfs-rt")
    val OEBB_STATIC = SourceId("oebb-gtfs")
    val VIAGGIATRENO = SourceId("viaggiatreno")
    val RFI_PROGRAMMED = SourceId("rfi-programmed")
    val RFI_STATION_BOARD = SourceId("rfi-station-board")
    val RFI_INFOMOBILITY_RSS = SourceId("rfi-infomobility-rss")
    val STA_REALTIME = SourceId("alto-adige-sta-realtime")
    val ITALO_STATUS = SourceId("italo-status")
    val SWISS_OPEN_TRANSPORT = SourceId("ch-transport-opendata")
    val VIAGGIATRENO_TRAIN_NOTICES = SourceId("viaggiatreno-train-notices")
    val TRENITALIA_INFOMOBILITY_NEWS = SourceId("trenitalia-infomobility-news")
    val DEMO_LOCAL = SourceId("demo-local")
}

object KnownProviders {
    private val staticRatePolicy = RatePolicy(
        minimumRequestInterval = Duration.ofMinutes(5),
        defaultTimeToLive = Duration.ofHours(6),
        supportsConditionalRequests = true
    )

    private val realtimeRatePolicy = RatePolicy(
        minimumRequestInterval = Duration.ofSeconds(30),
        defaultTimeToLive = Duration.ofMinutes(1),
        supportsConditionalRequests = true
    )

    val policies: List<ProviderPolicy> = listOf(
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.GTFS_ITALY_REGIONAL,
                displayName = "GTFS regionali italiani approvati",
                capabilities = setOf(ProviderCapability.STATION_CATALOG, ProviderCapability.JOURNEY_SEARCH, ProviderCapability.TRAIN_RUN_STATIC),
                coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Feed statici regionali con licenza verificata"),
                accessMode = AccessMode.OPEN_DIRECT,
                ratePolicy = staticRatePolicy,
                schemaVersion = 1
            ),
            enabled = false,
            priorityByCapability = mapOf(
                ProviderCapability.STATION_CATALOG to 20,
                ProviderCapability.JOURNEY_SEARCH to 20,
                ProviderCapability.TRAIN_RUN_STATIC to 20
            )
        ),
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.GTFS_SWISS,
                displayName = "Open Data Platform Mobility Switzerland — GTFS",
                capabilities = setOf(ProviderCapability.STATION_CATALOG, ProviderCapability.JOURNEY_SEARCH, ProviderCapability.TRAIN_RUN_STATIC),
                coverage = CoverageDescriptor(countryCodes = setOf("CH", "IT"), description = "Servizi svizzeri e transfrontalieri coperti dal feed"),
                accessMode = AccessMode.OPEN_DIRECT,
                ratePolicy = staticRatePolicy,
                schemaVersion = 1
            ),
            enabled = true,
            priorityByCapability = mapOf(
                ProviderCapability.STATION_CATALOG to 10,
                ProviderCapability.JOURNEY_SEARCH to 10,
                ProviderCapability.TRAIN_RUN_STATIC to 10
            )
        ),
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.GTFS_RT_SWISS,
                displayName = "Open Data Platform Mobility Switzerland — GTFS-RT",
                capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME, ProviderCapability.SERVICE_ALERTS),
                coverage = CoverageDescriptor(countryCodes = setOf("CH", "IT"), description = "Aggiornamenti e avvisi dei servizi coperti"),
                accessMode = AccessMode.GATEWAY_ONLY,
                ratePolicy = realtimeRatePolicy,
                schemaVersion = 1
            ),
            enabled = false,
            priorityByCapability = mapOf(
                ProviderCapability.TRAIN_RUN_REALTIME to 10,
                ProviderCapability.SERVICE_ALERTS to 10
            )
        ),
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.SNCF_STATIC,
                displayName = "SNCF Open Data — statico",
                capabilities = setOf(ProviderCapability.STATION_CATALOG, ProviderCapability.JOURNEY_SEARCH, ProviderCapability.TRAIN_RUN_STATIC),
                coverage = CoverageDescriptor(countryCodes = setOf("FR", "IT"), description = "TGV e TER con tratto italiano presente nel feed"),
                accessMode = AccessMode.OPEN_DIRECT,
                ratePolicy = staticRatePolicy,
                schemaVersion = 1
            ),
            enabled = true,
            priorityByCapability = mapOf(ProviderCapability.TRAIN_RUN_STATIC to 10)
        ),
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.SNCF_REALTIME,
                displayName = "SNCF Open Data — realtime",
                capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME, ProviderCapability.SERVICE_ALERTS),
                coverage = CoverageDescriptor(countryCodes = setOf("FR", "IT"), description = "Trip updates e avvisi SNCF"),
                accessMode = AccessMode.OPEN_DIRECT,
                ratePolicy = realtimeRatePolicy,
                schemaVersion = 1
            ),
            enabled = true,
            priorityByCapability = mapOf(
                ProviderCapability.TRAIN_RUN_REALTIME to 10,
                ProviderCapability.SERVICE_ALERTS to 10
            )
        ),
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.OEBB_STATIC,
                displayName = "ÖBB Open Data — GTFS",
                capabilities = setOf(ProviderCapability.STATION_CATALOG, ProviderCapability.JOURNEY_SEARCH, ProviderCapability.TRAIN_RUN_STATIC),
                coverage = CoverageDescriptor(countryCodes = setOf("AT", "IT"), description = "Servizi ÖBB e partner con fermate italiane"),
                accessMode = AccessMode.OPEN_DIRECT,
                ratePolicy = staticRatePolicy,
                schemaVersion = 1
            ),
            enabled = true,
            priorityByCapability = mapOf(ProviderCapability.TRAIN_RUN_STATIC to 30)
        ),
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.VIAGGIATRENO,
                displayName = "ViaggiaTreno — best effort",
                capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME, ProviderCapability.STATION_BOARD),
                coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Dati Trenitalia disponibili pubblicamente ma non come open API documentata"),
                accessMode = AccessMode.PUBLIC_BEST_EFFORT,
                ratePolicy = realtimeRatePolicy,
                schemaVersion = 1
            ),
            enabled = BuildConfig.PERSONAL_REALTIME_SOURCES,
            priorityByCapability = mapOf(
                ProviderCapability.TRAIN_RUN_REALTIME to 30,
                ProviderCapability.STATION_BOARD to 30
            )
        ),
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.RFI_PROGRAMMED,
                displayName = "RFI — Quadri orario programmati",
                capabilities = setOf(
                    ProviderCapability.JOURNEY_SEARCH,
                    ProviderCapability.TRAIN_RUN_STATIC,
                    ProviderCapability.STATION_BOARD
                ),
                coverage = CoverageDescriptor(
                    countryCodes = setOf("IT"),
                    description = "Orario programmato ufficiale per gli hub RFI con Id PRM verificato"
                ),
                accessMode = AccessMode.OPEN_DIRECT,
                ratePolicy = staticRatePolicy,
                schemaVersion = 1
            ),
            enabled = true,
            priorityByCapability = mapOf(
                ProviderCapability.JOURNEY_SEARCH to 5,
                ProviderCapability.TRAIN_RUN_STATIC to 5,
                ProviderCapability.STATION_BOARD to 5
            )
        ),
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.RFI_STATION_BOARD,
                displayName = "RFI Monitor Arrivi/Partenze — best effort",
                capabilities = setOf(ProviderCapability.STATION_BOARD),
                coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Tabelloni delle stazioni RFI"),
                accessMode = AccessMode.PUBLIC_BEST_EFFORT,
                ratePolicy = realtimeRatePolicy,
                schemaVersion = 1
            ),
            enabled = BuildConfig.PERSONAL_REALTIME_SOURCES,
            priorityByCapability = mapOf(ProviderCapability.STATION_BOARD to 20)
        ),
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.RFI_INFOMOBILITY_RSS,
                displayName = "RFI — Infomobilità RSS",
                capabilities = setOf(ProviderCapability.SERVICE_ALERTS),
                coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Avvisi nazionali e regionali RFI"),
                accessMode = AccessMode.OPEN_DIRECT,
                ratePolicy = RatePolicy(Duration.ofMinutes(5), defaultTimeToLive = Duration.ofMinutes(10), supportsConditionalRequests = true),
                schemaVersion = 1
            ),
            enabled = true,
            priorityByCapability = mapOf(ProviderCapability.SERVICE_ALERTS to 5)
        ),
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.STA_REALTIME,
                displayName = "Open Data Hub Alto Adige — GTFS-Realtime",
                capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME, ProviderCapability.SERVICE_ALERTS, ProviderCapability.VEHICLE_POSITIONS),
                coverage = CoverageDescriptor(countryCodes = setOf("IT", "AT"), description = "Servizi STA altoatesini e transfrontalieri presenti nel feed"),
                accessMode = AccessMode.OPEN_DIRECT,
                ratePolicy = RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = Duration.ofSeconds(90)),
                schemaVersion = 1
            ),
            enabled = true,
            priorityByCapability = mapOf(
                ProviderCapability.TRAIN_RUN_REALTIME to 15,
                ProviderCapability.SERVICE_ALERTS to 15,
                ProviderCapability.VEHICLE_POSITIONS to 15
            )
        ),
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.ITALO_STATUS,
                displayName = "Italo in Viaggio — tabellone pubblico",
                capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME, ProviderCapability.STATION_BOARD),
                coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Stato e tabelloni dei servizi Italo"),
                accessMode = AccessMode.PUBLIC_BEST_EFFORT,
                ratePolicy = realtimeRatePolicy,
                schemaVersion = 1
            ),
            enabled = BuildConfig.PERSONAL_REALTIME_SOURCES,
            priorityByCapability = mapOf(
                ProviderCapability.TRAIN_RUN_REALTIME to 20,
                ProviderCapability.STATION_BOARD to 20
            )
        ),
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.SWISS_OPEN_TRANSPORT,
                displayName = "transport.opendata.ch — tabelloni svizzeri",
                capabilities = setOf(ProviderCapability.STATION_BOARD),
                coverage = CoverageDescriptor(countryCodes = setOf("CH", "IT"), description = "Stazioni svizzere e transfrontaliere con numero UIC svizzero"),
                accessMode = AccessMode.PUBLIC_BEST_EFFORT,
                ratePolicy = realtimeRatePolicy,
                schemaVersion = 1
            ),
            enabled = BuildConfig.PERSONAL_REALTIME_SOURCES,
            priorityByCapability = mapOf(ProviderCapability.STATION_BOARD to 25)
        ),
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.VIAGGIATRENO_TRAIN_NOTICES,
                displayName = "ViaggiaTreno — avvisi per treno",
                capabilities = setOf(ProviderCapability.SERVICE_ALERTS),
                coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Avvisi Trenitalia pubblicati per numero treno"),
                accessMode = AccessMode.PUBLIC_BEST_EFFORT,
                ratePolicy = RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = Duration.ofMinutes(5)),
                schemaVersion = 1
            ),
            enabled = BuildConfig.PERSONAL_REALTIME_SOURCES,
            priorityByCapability = mapOf(ProviderCapability.SERVICE_ALERTS to 30)
        ),
        ProviderPolicy(
            descriptor = ProviderDescriptor(
                id = KnownProviderIds.TRENITALIA_INFOMOBILITY_NEWS,
                displayName = "Trenitalia — Infomobilità (ViaggiaTreno)",
                capabilities = setOf(ProviderCapability.SERVICE_ALERTS),
                coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Notizie di circolazione Trenitalia con numeri treno interessati"),
                accessMode = AccessMode.PUBLIC_BEST_EFFORT,
                ratePolicy = RatePolicy(Duration.ofMinutes(5), defaultTimeToLive = Duration.ofMinutes(10)),
                schemaVersion = 1
            ),
            enabled = BuildConfig.PERSONAL_REALTIME_SOURCES,
            priorityByCapability = mapOf(ProviderCapability.SERVICE_ALERTS to 25)
        )
    )

    /**
     * Le definizioni aggiuntive provengono dal catalogo nazionale e non sono
     * abilitate automaticamente. Questo rende visibili e testabili tutte le
     * fonti censite senza trasformarle in traffico runtime non controllato.
     */
    private fun catalogPolicies(): List<ProviderPolicy> {
        val configuredIds = policies.map { it.descriptor.id }.toSet()
        return RailSourceCatalog.all
            .filter { it.id !in configuredIds }
            .map { source ->
                ProviderPolicy(
                    descriptor = ProviderDescriptor(
                        id = source.id,
                        displayName = source.displayName,
                        capabilities = source.capabilities,
                        coverage = CoverageDescriptor(
                            countryCodes = source.countryCodes,
                            description = source.coverage
                        ),
                        accessMode = source.accessMode,
                        ratePolicy = if (
                            ProviderCapability.TRAIN_RUN_REALTIME in source.capabilities ||
                            ProviderCapability.STATION_BOARD in source.capabilities ||
                            ProviderCapability.SERVICE_ALERTS in source.capabilities
                        ) realtimeRatePolicy else staticRatePolicy,
                        schemaVersion = 1
                    ),
                    enabled = source.id in EmbeddedGtfsPacks.sourceIds,
                    priorityByCapability = source.capabilities.associateWith { 100 }
                )
            }
    }

    fun defaultRegistry(): ProviderRegistry = ProviderRegistry(policies + catalogPolicies())
}
