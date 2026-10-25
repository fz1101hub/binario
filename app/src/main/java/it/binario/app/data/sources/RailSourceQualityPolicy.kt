package it.binario.app.data.sources

import it.binario.app.data.RailOperator
import it.binario.app.data.core.SourceId
import it.binario.app.data.provider.ProviderCapability

/**
 * Stable, explicit source preference rules. Lower rank means a source is
 * preferred for that specific kind of railway fact; there is deliberately no
 * single global "best provider".
 */
object RailSourceQualityPolicy {
    private val programmedRanks = mapOf(
        "rfi-programmed-timetable" to 5,
        "toscana-trenitalia-gtfs" to 10,
        "lombardia-trenord-gtfs" to 10,
        "alto-adige-sta-gtfs" to 10,
        "ch-otd-gtfs" to 10,
        "sncf-gtfs" to 10,
        "oebb-gtfs" to 10,
        "delfi-gtfs" to 20,
        "european-sleeper-gtfs" to 40
    )

    private val realtimeRanks = mapOf(
        "sncf-gtfs-rt" to 5,
        "ch-otd-gtfs-rt" to 5,
        "alto-adige-sta-realtime" to 8,
        "rfi-station-board" to 10,
        "italo-status" to 15,
        "db-timetables" to 15,
        "viaggiatreno" to 20,
        "ch-transport-opendata" to 25,
        "european-sleeper-gtfs-rt" to 40
    )

    private val boardRanks = mapOf(
        "rfi-station-board" to 5,
        "italo-status" to 10,
        "viaggiatreno" to 15,
        "db-timetables" to 20,
        "ch-transport-opendata" to 25
    )

    private val alertRanks = mapOf(
        "rfi-infomobility-rss" to 5,
        "sncf-gtfs-rt-alerts" to 5,
        "ch-otd-gtfs-sa" to 5,
        "alto-adige-sta-realtime" to 10,
        "italo-status" to 20,
        "european-sleeper-alerts" to 30
    )

    fun rank(sourceId: SourceId, capability: ProviderCapability): Int = when (capability) {
        ProviderCapability.JOURNEY_SEARCH,
        ProviderCapability.TRAIN_RUN_STATIC,
        ProviderCapability.STATION_CATALOG -> programmedRanks[sourceId.value] ?: 100
        ProviderCapability.TRAIN_RUN_REALTIME,
        ProviderCapability.VEHICLE_POSITIONS -> realtimeRanks[sourceId.value] ?: 100
        ProviderCapability.STATION_BOARD -> boardRanks[sourceId.value] ?: 100
        ProviderCapability.SERVICE_ALERTS -> alertRanks[sourceId.value] ?: 100
        ProviderCapability.WEATHER_FORECAST -> 100
    }

    /**
     * Tie-break for duplicate static services. Operator-native national feeds
     * beat broad aggregations when both publish the same train.
     */
    fun staticDuplicateRank(sourceId: SourceId, operator: RailOperator): Int {
        val id = sourceId.value
        return when (operator) {
            RailOperator.TRENORD -> if (id == "lombardia-trenord-gtfs") 0 else rank(sourceId, ProviderCapability.TRAIN_RUN_STATIC)
            RailOperator.SBB,
            RailOperator.TILO,
            RailOperator.BLS,
            RailOperator.RHB,
            RailOperator.FART_SSIF -> if (id == "ch-otd-gtfs") 0 else rank(sourceId, ProviderCapability.TRAIN_RUN_STATIC)
            RailOperator.SNCF -> if (id == "sncf-gtfs") 0 else rank(sourceId, ProviderCapability.TRAIN_RUN_STATIC)
            RailOperator.OEBB -> if (id == "oebb-gtfs") 0 else rank(sourceId, ProviderCapability.TRAIN_RUN_STATIC)
            RailOperator.DB -> if (id == "delfi-gtfs") 5 else rank(sourceId, ProviderCapability.TRAIN_RUN_STATIC)
            RailOperator.EUROPEAN_SLEEPER -> if (id == "european-sleeper-gtfs") 5 else rank(sourceId, ProviderCapability.TRAIN_RUN_STATIC)
            else -> rank(sourceId, ProviderCapability.TRAIN_RUN_STATIC)
        }
    }
}
