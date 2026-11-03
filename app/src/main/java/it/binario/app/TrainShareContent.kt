package it.binario.app

import it.binario.app.data.Freshness
import it.binario.app.data.RailTime
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TrainShareContent(val title: String, val text: String)

/**
 * Resource-backed templates for [TrainShareFormatter]. Keeping Android resources
 * at the Compose boundary makes formatting deterministic and directly testable on
 * the JVM while leaving provider, operator and station names untouched.
 */
data class TrainShareStrings(
    val locale: Locale,
    val title: String,
    val detailTitle: String,
    val operatorCategory: String,
    val route: String,
    val expectedDeparture: String,
    val scheduledDeparture: String,
    val platform: String,
    val platformToConfirm: String,
    val status: String,
    val stopsTitle: String,
    val stop: String,
    val stopPlatform: String,
    val stopDelay: String,
    val data: String,
    val source: String,
    val updated: String,
    val neverUpdated: String,
    val demonstrationData: String,
    val scheduledData: String,
    val unavailableData: String,
    val liveData: String,
    val statusLabels: Map<ServiceStatus, String>,
    val categoryLabels: Map<TrainCategory, String>,
    val freshnessLabels: Map<Freshness, String>
) {
    fun format(template: String, vararg values: Any): String = template.format(locale, *values)
}

object TrainShareFormatter {
    fun create(
        service: TrainService,
        provider: String,
        confidence: String,
        freshness: Freshness?,
        refreshedAt: Long,
        isDemonstration: Boolean,
        strings: TrainShareStrings
    ): TrainShareContent {
        val dataLabel = when {
            isDemonstration -> strings.demonstrationData
            !service.operationalStatusVerified -> strings.scheduledData
            freshness == null || freshness == Freshness.UNAVAILABLE -> strings.unavailableData
            else -> strings.format(strings.liveData, strings.freshnessLabels.getValue(freshness))
        }
        val statusLabel = if (service.operationalStatusVerified) {
            strings.statusLabels.getValue(service.status)
        } else {
            strings.scheduledData
        }
        val stops = service.stops.joinToString(separator = "\n") { stop ->
            strings.format(
                strings.stop,
                stop.station,
                stop.expected,
                stop.platform?.let { strings.format(strings.stopPlatform, it) }.orEmpty(),
                if (stop.delayMinutes > 0) strings.format(strings.stopDelay, stop.delayMinutes) else ""
            )
        }
        val updated = if (refreshedAt > 0L) {
            val time = DateTimeFormatter.ofPattern("HH:mm", strings.locale)
                .withZone(RailTime.defaultZone)
                .format(Instant.ofEpochMilli(refreshedAt))
            strings.format(strings.updated, time)
        } else {
            strings.neverUpdated
        }
        return TrainShareContent(
            title = strings.format(strings.title, service.number, service.origin, service.destination),
            text = """
                ${strings.detailTitle}

                ${strings.format(strings.operatorCategory, service.displayOperator, strings.categoryLabels.getValue(service.trainCategory), service.number)}
                ${strings.format(strings.route, service.origin, service.destination)}
                ${strings.format(strings.expectedDeparture, service.expectedDeparture)} (${strings.format(strings.scheduledDeparture, service.scheduledDeparture)})
                ${service.platform?.let { strings.format(strings.platform, it) } ?: strings.platformToConfirm}
                ${strings.format(strings.status, statusLabel)}${if (service.delayMinutes > 0) " (${strings.format(strings.stopDelay, service.delayMinutes).trimStart(',', ' ')})" else ""}

                ${strings.stopsTitle}:
                $stops

                ${strings.format(strings.data, dataLabel)}
                ${strings.format(strings.source, provider, confidence)}
                $updated
            """.trimIndent()
        )
    }
}
