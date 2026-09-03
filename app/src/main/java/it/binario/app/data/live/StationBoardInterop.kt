package it.binario.app.data.live

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainService
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.Freshness
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

internal object LegacyStationBoardInterop {
    private val defaultZone: ZoneId = ZoneId.of("Europe/Rome")

    fun build(
        sourceId: SourceId,
        confidence: Confidence,
        stationId: StationId,
        kind: BoardKind,
        from: Instant,
        to: Instant,
        services: List<TrainService>,
        fetchedAt: Instant,
        ttl: Duration,
        zone: ZoneId = defaultZone
    ): StationBoard {
        val entries = services.mapNotNull { service ->
            val scheduled = resolveBoardDateTime(service.scheduledDeparture, from, to, zone) ?: return@mapNotNull null
            if (scheduled.toInstant().isBefore(from) || scheduled.toInstant().isAfter(to)) return@mapNotNull null

            val source = SourceRef(
                sourceId = sourceId,
                nativeId = service.id,
                observedAt = fetchedAt,
                fetchedAt = fetchedAt,
                expiresAt = fetchedAt.plus(ttl),
                confidence = confidence,
                traceId = "\${sourceId.value}:\${service.id}"
            )
            val expected = resolveExpectedDateTime(service.expectedDeparture, scheduled, zone)
            StationBoardEntry(
                runId = TrainRunId("\${sourceId.value}:\${service.number}:\${scheduled.toLocalDate()}"),
                scheduledTime = scheduled,
                expectedTime = expected?.let { SourcedValue(it, source) },
                destinationOrOrigin = StationId("published:\${normalizeStation(service.destination)}"),
                destinationOrOriginLabel = service.destination,
                publicNumber = service.number,
                operatorId = operatorId(service.operator),
                platform = service.platform?.let { SourcedValue(it, source) },
                status = SourcedValue(runStatus(service.status), source)
            )
        }

        val boardSource = SourceRef(
            sourceId = sourceId,
            observedAt = fetchedAt,
            fetchedAt = fetchedAt,
            expiresAt = fetchedAt.plus(ttl),
            confidence = confidence,
            traceId = "\${sourceId.value}:board:\${stationId.value}:\${fetchedAt.toEpochMilli()}"
        )
        return StationBoard(
            stationId = stationId,
            kind = kind,
            from = from,
            to = to,
            entries = entries,
            snapshot = SnapshotMeta(
                schemaVersion = 1,
                generatedAt = fetchedAt,
                validUntil = fetchedAt.plus(ttl),
                freshness = Freshness.FRESH,
                completeness = Completeness.PARTIAL,
                contributingSources = setOf(boardSource)
            )
        )
    }

    private fun resolveBoardDateTime(
        value: String,
        from: Instant,
        to: Instant,
        zone: ZoneId
    ): ZonedDateTime? {
        val time = parseTime(value) ?: return null
        val baseDate = from.atZone(zone).toLocalDate()
        val candidates = listOf(baseDate.minusDays(1), baseDate, baseDate.plusDays(1))
            .map { it.atTime(time).atZone(zone) }
        return candidates.firstOrNull { candidate ->
            !candidate.toInstant().isBefore(from) && !candidate.toInstant().isAfter(to)
        } ?: candidates.minByOrNull { candidate ->
            kotlin.math.abs(Duration.between(from, candidate.toInstant()).toMillis())
        }
    }

    private fun resolveExpectedDateTime(
        value: String,
        scheduled: ZonedDateTime,
        zone: ZoneId
    ): ZonedDateTime? {
        val time = parseTime(value) ?: return null
        var expected = scheduled.toLocalDate().atTime(time).atZone(zone)
        if (expected.isBefore(scheduled.minusHours(12))) expected = expected.plusDays(1)
        if (expected.isAfter(scheduled.plusHours(12))) expected = expected.minusDays(1)
        return expected
    }

    private fun parseTime(value: String): LocalTime? {
        val parts = value.split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private fun runStatus(status: ServiceStatus): RunStatus = when (status) {
        ServiceStatus.ON_TIME -> RunStatus.ON_TIME
        ServiceStatus.DELAYED -> RunStatus.DELAYED
        ServiceStatus.CANCELLED -> RunStatus.CANCELLED
    }

    private fun operatorId(operator: RailOperator): OperatorId = when (operator) {
        RailOperator.TRENITALIA_REGIONALE,
        RailOperator.TRENITALIA_LONG_DISTANCE,
        RailOperator.TRENITALIA_AV -> OperatorId("trenitalia")
        RailOperator.ITALO -> OperatorId("italo")
        RailOperator.TRENORD -> OperatorId("trenord")
        RailOperator.OTHER -> OperatorId("other")
    }

    private fun normalizeStation(value: String): String = value
        .uppercase(Locale.ROOT)
        .replace("S. ", "SAN ")
        .replace(Regex("[^A-Z0-9]"), "")
        .lowercase(Locale.ROOT)
}
