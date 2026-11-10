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
import it.binario.app.data.TrainService
import it.binario.app.data.orderServicesForDisplay
import it.binario.app.data.reliability.NetworkReliability
import it.binario.app.data.reliability.NetworkReliabilityStats
import it.binario.app.labelRes
import it.binario.app.resourceId
import it.binario.app.widget.ReliabilityMark
import it.binario.app.widget.ReliabilityMarks
import java.util.Locale

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
    val missingValue: String,
    /** «Soppresso: %1$s delle %2$s»: train number and scheduled time of a cancellation left out of the services. */
    val cancelledNotice: String
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
            missingValue = resources.getString(R.string.wear_payload_value_unavailable),
            cancelledNotice = resources.getString(R.string.wear_notice_cancelled)
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
    /** Advertised by the watch apps that decode the optional reliability pair (wear/src/main/res/values/wear.xml). */
    const val RELIABILITY_CAPABILITY = "binario_wear_reliability_v1"
    private val reliabilityKey = Regex("service[0-9]+Reliability(OnTime|Samples)")

    /**
     * The v1 payload of [snapshot]. [reliabilityOf] gives the optional «In orario N volte su M» of a service (the
     * `service{n}ReliabilityOnTime` / `service{n}ReliabilitySamples` pair); it stays out when it gives none.
     *
     * The watch shows it for 15 minutes from [generatedAtEpochMillis] and never more than 15 minutes past the
     * snapshot's own validity. That validity is the source cache's (30-60 s for a live board): used as the deadline,
     * it expired the glance before the user looked at it. The watch names the observation time of live values.
     */
    fun operational(
        snapshot: RouteSnapshot,
        generatedAtEpochMillis: Long,
        strings: WearSnapshotStrings,
        reliabilityOf: (TrainService) -> ReliabilityMark? = { null }
    ): Map<String, Any> {
        val expiresAt = minOf(generatedAtEpochMillis, snapshot.expiresAtEpochMillis) + maxTtlMillis
        require(expiresAt > generatedAtEpochMillis) { "Uno snapshot scaduto non può essere pubblicato" }
        val ordered = orderServicesForDisplay(snapshot.services)
        // The Tile and the complication show the next of these as "the next train" without its status.
        val services = ordered.filterNot(::confirmedCancelled).take(2)
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
            put("notice", optional(notice(snapshot, skippedCancellation(ordered, services), strings), 180))
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
                reliabilityOf(service)?.takeIf { it.samples in NetworkReliability.MIN_SAMPLES..MAX_RELIABILITY_SAMPLES && it.onTime in 0..it.samples }?.let { mark ->
                    put("service${index}ReliabilityOnTime", mark.onTime)
                    put("service${index}ReliabilitySamples", mark.samples)
                }
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

    /**
     * The reliability of a service of [snapshot] in the embedded statistics: a direct train boarded at the route's
     * origin, measured at its destination; none for coaches, journeys with changes or demonstration data.
     */
    fun reliabilityOf(stats: NetworkReliabilityStats, snapshot: RouteSnapshot): (TrainService) -> ReliabilityMark? {
        if (snapshot.confidence == DataConfidence.DEMONSTRATION) return { null }
        val origin = NetworkReliability.stationCode(snapshot.route.origin(snapshot.direction))
        val destination = NetworkReliability.stationCode(snapshot.route.destination(snapshot.direction))
        return { service ->
            if (service.scheduledBus || service.transferCount > 0) null else ReliabilityMarks.route(stats, service.number, origin, destination)
        }
    }

    /** [payload] without the optional reliability pairs, for a watch that does not decode them. */
    fun withoutReliability(payload: Map<String, Any?>): Map<String, Any?> = payload.filterKeys { !reliabilityKey.matches(it) }

    fun hasReliability(payload: Map<String, Any?>): Boolean = payload.keys.any(reliabilityKey::matches)

    /**
     * Whether the pair may be sent: a v1 decoder of an earlier watch app rejects unknown keys, so only when every
     * connected watch advertises [RELIABILITY_CAPABILITY] (and at least one is connected).
     */
    fun watchesDecodeReliability(connectedNodes: Set<String>, capableNodes: Set<String>): Boolean =
        connectedNodes.isNotEmpty() && capableNodes.containsAll(connectedNodes)

    private const val MAX_RELIABILITY_SAMPLES = 10_000

    /** A cancellation the source confirmed; such a train is never sent as one of the two services. */
    private fun confirmedCancelled(service: TrainService): Boolean =
        service.operationalStatusVerified && service.status == ServiceStatus.CANCELLED

    /** The first confirmed cancellation listed before the last service sent (anywhere, when fewer than two are sent). */
    private fun skippedCancellation(ordered: List<TrainService>, sent: List<TrainService>): TrainService? {
        val window = if (sent.size == 2) ordered.subList(0, ordered.indexOf(sent[1])) else ordered
        return window.firstOrNull(::confirmedCancelled)
    }

    /**
     * The cancellation the services leave out, else the phone's own advisory, else its first quality warning: the
     * data layer's free text is Italian prose and never reaches the watch.
     */
    private fun notice(snapshot: RouteSnapshot, cancellation: TrainService?, strings: WearSnapshotStrings): String =
        cancellation?.let { String.format(Locale.ROOT, strings.cancelledNotice, it.number, it.scheduledDeparture) }
            ?: snapshot.advisoryMessage()?.let { strings.advisoryNotices.getValue(it.key) }
            ?: snapshot.qualityWarnings.firstOrNull()?.let { strings.warningLabels.getValue(it) }
            ?: ""

    private fun required(value: String, limit: Int, fallback: String): String = optional(value, limit).ifBlank { fallback }
    private fun optional(value: String, limit: Int): String = value.replace(Regex("\\s+"), " ").trim().take(limit).trim()
}
