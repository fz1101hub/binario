package it.binario.app.data.core

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@JvmInline
value class StationId(val value: String)

@JvmInline
value class OperatorId(val value: String)

@JvmInline
value class TrainRunId(val value: String)

@JvmInline
value class JourneyId(val value: String)

@JvmInline
value class AlertId(val value: String)

@JvmInline
value class SourceId(val value: String)

enum class Confidence {
    OPERATOR_PRIMARY,
    INFRASTRUCTURE_PRIMARY,
    OPEN_DATA,
    LICENSED_AGGREGATE,
    DERIVED,
    CACHED,
    DEMONSTRATION
}

enum class Precision {
    EXACT,
    MINUTE,
    ESTIMATED,
    UNKNOWN
}

data class SourceRef(
    val sourceId: SourceId,
    val nativeId: String? = null,
    val observedAt: Instant? = null,
    val fetchedAt: Instant,
    val expiresAt: Instant? = null,
    val confidence: Confidence,
    val licenseId: String? = null,
    val traceId: String
)

data class SourcedValue<T>(
    val value: T,
    val source: SourceRef,
    val precision: Precision = Precision.EXACT
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
) {
    init {
        require(latitude in -90.0..90.0) { "Latitudine non valida" }
        require(longitude in -180.0..180.0) { "Longitudine non valida" }
    }
}

enum class AliasType {
    UIC,
    GTFS_STOP,
    RFI,
    VIAGGIATRENO,
    PROVIDER_NATIVE,
    LEGACY_LOCAL
}

data class StationAlias(
    val sourceId: SourceId,
    val nativeCode: String,
    val nativeName: String? = null,
    val type: AliasType = AliasType.PROVIDER_NATIVE
)

data class CanonicalStation(
    val id: StationId,
    val canonicalName: String,
    val city: String? = null,
    val region: String? = null,
    val countryCode: String = "IT",
    val timeZone: ZoneId = ZoneId.of("Europe/Rome"),
    val coordinates: GeoPoint? = null,
    val aliases: List<StationAlias> = emptyList(),
    val active: Boolean = true,
    val sources: Set<SourceRef> = emptySet()
)

data class RailOperatorProfile(
    val id: OperatorId,
    val publicBrand: String,
    val legalName: String? = null,
    val countryCode: String,
    val website: URI? = null,
    val active: Boolean = true
)

enum class ServiceKind {
    HIGH_SPEED,
    INTERCITY,
    NIGHT,
    REGIONAL,
    SUBURBAN,
    TOURIST,
    LUXURY,
    REPLACEMENT_BUS,
    SPECIAL,
    UNKNOWN
}

enum class RouteChangeKind {
    DIVERTED,
    SKIPPED_STOPS,
    ADDED_STOPS,
    SHORT_TURN,
    REPLACEMENT_SERVICE,
    OTHER
}

data class OperationalRouteChange(
    val kind: RouteChangeKind,
    val summary: String,
    val skippedStopLabels: List<String> = emptyList(),
    val addedStopLabels: List<String> = emptyList(),
    val viaStopLabels: List<String> = emptyList(),
    val replacementDestinationLabel: String? = null
) {
    val structured: Boolean
        get() = skippedStopLabels.isNotEmpty() || addedStopLabels.isNotEmpty() || replacementDestinationLabel != null

    init {
        require(summary.isNotBlank()) { "La descrizione della variazione di percorso è obbligatoria" }
    }
}

enum class RunStatus {
    SCHEDULED,
    ON_TIME,
    DELAYED,
    CANCELLED,
    PARTIALLY_CANCELLED,
    DIVERTED,
    ADDED,
    REPLACED_BY_BUS,
    COMPLETED,
    UNKNOWN
}

enum class CallStatus {
    PLANNED,
    ARRIVED,
    DEPARTED,
    SKIPPED,
    CANCELLED,
    REPLACED_BY_BUS,
    UNKNOWN
}

data class StopCall(
    val stationId: StationId,
    val sequence: Int,
    val scheduledArrival: ZonedDateTime? = null,
    val scheduledDeparture: ZonedDateTime? = null,
    val predictedArrival: SourcedValue<ZonedDateTime>? = null,
    val predictedDeparture: SourcedValue<ZonedDateTime>? = null,
    val actualArrival: SourcedValue<ZonedDateTime>? = null,
    val actualDeparture: SourcedValue<ZonedDateTime>? = null,
    val arrivalPlatform: SourcedValue<String>? = null,
    val departurePlatform: SourcedValue<String>? = null,
    val status: SourcedValue<CallStatus>? = null,
    val pickupAllowed: Boolean? = null,
    val dropOffAllowed: Boolean? = null
) {
    init {
        require(sequence >= 0) { "La sequenza della fermata non può essere negativa" }
    }
}

enum class Freshness {
    FRESH,
    AGING,
    STALE,
    UNAVAILABLE
}

enum class Completeness {
    COMPLETE,
    PARTIAL,
    STATIC_ONLY,
    UNKNOWN
}

enum class DataWarning {
    IDENTITY_AMBIGUOUS,
    REALTIME_UNAVAILABLE,
    STATIC_ONLY,
    STALE_CACHE,
    CONFLICTING_SOURCES,
    SCHEMA_CHANGED,
    REPLACEMENT_SERVICE,
    POSITION_ESTIMATED
}

data class SnapshotMeta(
    val schemaVersion: Int,
    val generatedAt: Instant,
    val validUntil: Instant? = null,
    val freshness: Freshness,
    val completeness: Completeness,
    val contributingSources: Set<SourceRef>,
    val warnings: Set<DataWarning> = emptySet()
)

data class TrainRun(
    val id: TrainRunId,
    val serviceDate: LocalDate,
    val publicNumber: String,
    val operatorId: OperatorId,
    val publicBrand: String? = null,
    val serviceKind: ServiceKind = ServiceKind.UNKNOWN,
    val origin: StationId,
    val destination: StationId,
    val calls: List<StopCall>,
    val status: SourcedValue<RunStatus>,
    val alertIds: Set<AlertId> = emptySet(),
    val nativeIds: Map<SourceId, String> = emptyMap(),
    val snapshot: SnapshotMeta
) {
    init {
        require(publicNumber.isNotBlank()) { "Il numero treno è obbligatorio" }
        require(calls.zipWithNext().all { (first, second) -> first.sequence < second.sequence }) {
            "Le fermate devono avere sequenze strettamente crescenti"
        }
    }
}

enum class LegMode {
    TRAIN,
    REPLACEMENT_BUS,
    WALK,
    UNKNOWN
}

data class JourneyLeg(
    val mode: LegMode,
    val runId: TrainRunId? = null,
    val origin: StationId,
    val destination: StationId,
    val scheduledDeparture: ZonedDateTime,
    val expectedDeparture: SourcedValue<ZonedDateTime>? = null,
    val scheduledArrival: ZonedDateTime,
    val expectedArrival: SourcedValue<ZonedDateTime>? = null,
    val operatorId: OperatorId? = null,
    val intermediateCalls: List<StopCall> = emptyList()
)

data class JourneyOption(
    val id: JourneyId,
    val origin: StationId,
    val destination: StationId,
    val departure: ZonedDateTime,
    val arrival: ZonedDateTime,
    val legs: List<JourneyLeg>,
    val snapshot: SnapshotMeta
) {
    val transfers: Int get() = (legs.count { it.mode == LegMode.TRAIN } - 1).coerceAtLeast(0)
}

enum class BoardKind {
    DEPARTURES,
    ARRIVALS
}

data class StationBoardEntry(
    val runId: TrainRunId,
    val scheduledTime: ZonedDateTime,
    val expectedTime: SourcedValue<ZonedDateTime>? = null,
    val destinationOrOrigin: StationId,
    val destinationOrOriginLabel: String? = null,
    val publicNumber: String? = null,
    val operatorId: OperatorId? = null,
    val platform: SourcedValue<String>? = null,
    val status: SourcedValue<RunStatus>? = null,
    val routeChange: SourcedValue<OperationalRouteChange>? = null,
    /** Exact source identity for opening this run without guessing from a train number. */
    val nativeRunIds: Map<SourceId, String> = emptyMap(),
    /**
     * Commercial line label published next to the run number (for example "RE 80" or "S 10"). Display only:
     * board identity keeps using [publicNumber], the run number shared with GTFS and the other boards.
     */
    val lineLabel: String? = null
)

data class StationBoard(
    val stationId: StationId,
    val kind: BoardKind,
    val from: Instant,
    val to: Instant,
    val entries: List<StationBoardEntry>,
    val snapshot: SnapshotMeta
)

enum class AlertSeverity {
    INFO,
    WARNING,
    SEVERE,
    UNKNOWN
}

data class LocalizedText(
    val value: String,
    val languageTag: String = "it"
)

data class ServiceAlert(
    val id: AlertId,
    val title: LocalizedText,
    val description: LocalizedText? = null,
    val severity: AlertSeverity = AlertSeverity.UNKNOWN,
    val validFrom: Instant? = null,
    val validUntil: Instant? = null,
    val affectedStations: Set<StationId> = emptySet(),
    val affectedRuns: Set<TrainRunId> = emptySet(),
    val affectedOperators: Set<OperatorId> = emptySet(),
    val source: SourceRef,
    /** Public train numbers the publisher tags the notice with (for example Trenitalia `trainTags`). */
    val affectedTrainNumbers: Set<String> = emptySet(),
    /** Publisher page for the notice, http(s) only. */
    val link: String? = null,
    /** Line identifiers the publisher files the notice under (for example Trenord `D034`). */
    val affectedLines: Set<String> = emptySet()
)

/**
 * Operator notice about one train on one service day (for example a ViaggiaTreno delay explanation). The text is
 * the publisher's own; [englishText] is its English variant when the publisher provides one.
 */
data class TrainNotice(
    val id: String,
    val text: String,
    val englishText: String? = null,
    val validFrom: LocalDate,
    val validUntil: LocalDate,
    val publishedAt: Instant? = null,
    val source: SourceRef
) {
    /** The publisher's English text for a non-Italian app language when it is present, else the Italian text. */
    fun textFor(languageCode: String): String =
        englishText?.takeIf { !languageCode.equals("it", ignoreCase = true) && it.isNotBlank() } ?: text
}

enum class PositionKind {
    GPS,
    OPERATIONAL_EVENT,
    INTERPOLATED,
    UNKNOWN
}

data class VehiclePosition(
    val runId: TrainRunId,
    val point: GeoPoint,
    val observedAt: Instant,
    val kind: PositionKind,
    val accuracyMeters: Double? = null,
    val source: SourceRef
)

