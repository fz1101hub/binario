package it.binario.app.wear

import it.binario.app.data.RouteSnapshot
import it.binario.app.data.orderServicesForDisplay

/**
 * App-local encoder for contracts/wear-snapshot/v1. It owns no transport: WearSnapshotPublisher
 * places this bounded map on the already-existing Data Layer path.
 */
internal object AndroidWearSnapshotContract {
    const val schemaVersion = 1L
    private const val maxTtlMillis = 15 * 60 * 1_000L

    fun operational(snapshot: RouteSnapshot, generatedAtEpochMillis: Long): Map<String, Any> {
        val expiresAt = minOf(snapshot.expiresAtEpochMillis, generatedAtEpochMillis + maxTtlMillis)
        require(expiresAt > generatedAtEpochMillis) { "Uno snapshot scaduto non può essere pubblicato" }
        val services = orderServicesForDisplay(snapshot.services).take(2)
        val operational = services.any { it.operationalStatusVerified }
        val dataNature = if (operational) "OPERATIONAL" else "PROGRAMMED"
        return buildMap {
            put("schemaVersion", schemaVersion)
            put("generatedAtEpochMillis", generatedAtEpochMillis)
            put("expiresAtEpochMillis", expiresAt)
            put("ttlMillis", expiresAt - generatedAtEpochMillis)
            put("provenanceProducer", "ANDROID_APP")
            put("provenanceDataNature", dataNature)
            put("provenanceSourceLabel", required(snapshot.confidence.accessibleLabel, 96))
            put("provenanceObservedAtEpochMillis", snapshot.sourceObservedAtEpochMillis.coerceAtLeast(0L))
            put("operationalState", dataNature)
            put("freshness", snapshot.freshness.name)
            put("confidence", snapshot.confidence.name.takeUnless { it == "DEMONSTRATION" } ?: "UNKNOWN")
            put("routeId", required(snapshot.route.id, 64))
            put("routeLabel", required(snapshot.routeLabel, 96))
            put("direction", snapshot.direction.name)
            put("notice", optional(snapshot.notice.orEmpty(), 180))
            put("serviceCount", services.size)
            services.forEachIndexed { index, service ->
                put("service${index}Number", required(service.number, 24))
                put("service${index}Operator", required(service.operator.accessibleLabel, 64))
                put("service${index}Category", required(service.trainCategory.accessibleLabel, 64))
                put("service${index}Destination", required(service.destination, 96))
                put("service${index}Scheduled", required(service.scheduledDeparture, 16))
                put("service${index}Expected", required(service.expectedDeparture, 16))
                put("service${index}Status", required(if (service.operationalStatusVerified) service.status.accessibleLabel else "Programmato", 64))
                put("service${index}Platform", optional(service.platform.orEmpty(), 16))
                put("service${index}DelayMinutes", service.delayMinutes.coerceIn(-720, 1_440))
            }
        }
    }

    fun unavailable(routeLabel: String, provider: String, generatedAtEpochMillis: Long): Map<String, Any> = mapOf(
        "schemaVersion" to schemaVersion,
        "generatedAtEpochMillis" to generatedAtEpochMillis,
        "expiresAtEpochMillis" to generatedAtEpochMillis + maxTtlMillis,
        "ttlMillis" to maxTtlMillis,
        "provenanceProducer" to "ANDROID_APP",
        "provenanceDataNature" to "UNAVAILABLE",
        "provenanceSourceLabel" to required(provider, 96),
        "provenanceObservedAtEpochMillis" to generatedAtEpochMillis,
        "operationalState" to "UNAVAILABLE",
        "freshness" to "UNAVAILABLE",
        "confidence" to "CACHED",
        "routeId" to "unavailable",
        "routeLabel" to required(routeLabel, 96),
        "direction" to "UNKNOWN",
        "notice" to "dati non disponibili in tempo reale",
        "serviceCount" to 0
    )

    private fun required(value: String, limit: Int): String = optional(value, limit).ifBlank { "Non disponibile" }
    private fun optional(value: String, limit: Int): String = value.replace(Regex("\\s+"), " ").trim().take(limit).trim()
}
