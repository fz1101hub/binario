package it.binario.app.wear

import android.content.res.Resources
import it.binario.app.R
import it.binario.app.UiMessageKey
import it.binario.app.advisoryMessage
import it.binario.app.data.DataConfidence
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.ServiceStatus
import it.binario.app.data.SnapshotWarning
import it.binario.app.data.TrainCategory
import it.binario.app.data.orderServicesForDisplay
import it.binario.app.labelRes
import it.binario.app.resourceId

/**
 * Phone-language texts carried by the v1 payload. The watch shows them verbatim, so they are
 * resolved from string resources before publishing instead of reading the enums' Italian labels.
 */
internal data class WearSnapshotStrings(
    val confidenceLabels: Map<DataConfidence, String>,
    val categoryLabels: Map<TrainCategory, String>,
    val statusLabels: Map<ServiceStatus, String>,
    val advisoryNotices: Map<UiMessageKey, String>,
    val warningLabels: Map<SnapshotWarning, String>,
    val scheduledStatus: String,
    val unavailableNotice: String,
    val missingValue: String
) {
    companion object {
        /** The advisories RouteSnapshot.advisoryMessage() can raise; the watch shows them as the notice. */
        internal val advisoryKeys = listOf(UiMessageKey.ROUTE_COVERAGE_FALLBACK, UiMessageKey.ROUTE_SOURCE_CONFLICT)

        fun from(resources: Resources): WearSnapshotStrings = WearSnapshotStrings(
            confidenceLabels = DataConfidence.entries.associateWith { resources.getString(it.labelRes()) },
            categoryLabels = TrainCategory.entries.associateWith { resources.getString(it.labelRes()) },
            statusLabels = ServiceStatus.entries.associateWith { resources.getString(it.labelRes()) },
            advisoryNotices = advisoryKeys.associateWith { resources.getString(it.resourceId()) },
            warningLabels = SnapshotWarning.entries.associateWith { resources.getString(it.labelRes()) },
            scheduledStatus = resources.getString(R.string.service_status_scheduled_badge),
            unavailableNotice = resources.getString(R.string.data_status_unavailable),
            missingValue = resources.getString(R.string.wear_payload_value_unavailable)
        )
    }
}

/**
 * App-local encoder for contracts/wear-snapshot/v1. It owns no transport: WearSnapshotPublisher
 * places this bounded map on the already-existing Data Layer path.
 */
internal object AndroidWearSnapshotContract {
    const val schemaVersion = 1L
    private const val maxTtlMillis = 15 * 60 * 1_000L

    fun operational(snapshot: RouteSnapshot, generatedAtEpochMillis: Long, strings: WearSnapshotStrings): Map<String, Any> {
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
            put("provenanceSourceLabel", required(strings.confidenceLabels.getValue(snapshot.confidence), 96, strings.missingValue))
            put("provenanceObservedAtEpochMillis", snapshot.sourceObservedAtEpochMillis.coerceAtLeast(0L))
            put("operationalState", dataNature)
            put("freshness", snapshot.freshness.name)
            put("confidence", snapshot.confidence.name.takeUnless { it == "DEMONSTRATION" } ?: "UNKNOWN")
            put("routeId", required(snapshot.route.id, 64, strings.missingValue))
            put("routeLabel", required(snapshot.routeLabel, 96, strings.missingValue))
            put("direction", snapshot.direction.name)
            put("notice", optional(notice(snapshot, strings), 180))
            put("serviceCount", services.size)
            services.forEachIndexed { index, service ->
                put("service${index}Number", required(service.number, 24, strings.missingValue))
                put("service${index}Operator", required(service.operator.accessibleLabel, 64, strings.missingValue))
                put("service${index}Category", required(strings.categoryLabels.getValue(service.trainCategory), 64, strings.missingValue))
                put("service${index}Destination", required(service.destination, 96, strings.missingValue))
                put("service${index}Scheduled", required(service.scheduledDeparture, 16, strings.missingValue))
                put("service${index}Expected", required(service.expectedDeparture, 16, strings.missingValue))
                put(
                    "service${index}Status",
                    required(if (service.operationalStatusVerified) strings.statusLabels.getValue(service.status) else strings.scheduledStatus, 64, strings.missingValue)
                )
                put("service${index}Platform", optional(service.platform.orEmpty(), 16))
                put("service${index}DelayMinutes", service.delayMinutes.coerceIn(-720, 1_440))
            }
        }
    }

    fun unavailable(routeLabel: String, provider: String, generatedAtEpochMillis: Long, strings: WearSnapshotStrings): Map<String, Any> = mapOf(
        "schemaVersion" to schemaVersion,
        "generatedAtEpochMillis" to generatedAtEpochMillis,
        "expiresAtEpochMillis" to generatedAtEpochMillis + maxTtlMillis,
        "ttlMillis" to maxTtlMillis,
        "provenanceProducer" to "ANDROID_APP",
        "provenanceDataNature" to "UNAVAILABLE",
        "provenanceSourceLabel" to required(provider, 96, strings.missingValue),
        "provenanceObservedAtEpochMillis" to generatedAtEpochMillis,
        "operationalState" to "UNAVAILABLE",
        "freshness" to "UNAVAILABLE",
        "confidence" to "CACHED",
        "routeId" to "unavailable",
        "routeLabel" to required(routeLabel, 96, strings.missingValue),
        "direction" to "UNKNOWN",
        "notice" to optional(strings.unavailableNotice, 180),
        "serviceCount" to 0
    )

    /** The phone's own advisory, else its first quality warning: the data layer's free text is Italian prose and never reaches the watch. */
    private fun notice(snapshot: RouteSnapshot, strings: WearSnapshotStrings): String =
        snapshot.advisoryMessage()?.let { strings.advisoryNotices.getValue(it.key) }
            ?: snapshot.qualityWarnings.firstOrNull()?.let { strings.warningLabels.getValue(it) }
            ?: ""

    private fun required(value: String, limit: Int, fallback: String): String = optional(value, limit).ifBlank { fallback }
    private fun optional(value: String, limit: Int): String = value.replace(Regex("\\s+"), " ").trim().take(limit).trim()
}
