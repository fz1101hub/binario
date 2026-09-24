package it.binario.wear.model

/** Immutable, privacy-minimised cache received from the phone; the watch never fetches railway data itself. */
data class WearSnapshot(
    val routeId: String,
    val routeLabel: String,
    val direction: WearDirection,
    val provenance: WearProvenance,
    val operationalState: WearOperationalState,
    val freshness: WearFreshness,
    val confidence: WearConfidence,
    val generatedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val ttlMillis: Long,
    val notice: String,
    val services: List<WearService>
) {
    val provider: String get() = provenance.sourceLabel

    val dataStatusLabel: String
        get() = when (operationalState) {
            WearOperationalState.PROGRAMMED -> "Orario programmato"
            WearOperationalState.OPERATIONAL -> when (freshness) {
                WearFreshness.FRESH -> "Stato ricevuto dal telefono"
                WearFreshness.AGING -> "Dati in aggiornamento"
                WearFreshness.STALE -> "Ultimo snapshot locale"
                WearFreshness.UNAVAILABLE, WearFreshness.UNKNOWN -> "Dati live non disponibili"
            }
            WearOperationalState.UNAVAILABLE, WearOperationalState.UNKNOWN -> "Dati live non disponibili"
        }

    val complicationLabel: String
        get() = services.firstOrNull()?.let { service ->
            listOf(service.expectedDeparture, service.number).joinToString(" · ").take(32)
        } ?: dataStatusLabel.take(32)
}

data class WearProvenance(
    val producer: WearProvenanceProducer,
    val dataNature: WearDataNature,
    val sourceLabel: String,
    val observedAtEpochMillis: Long
)

enum class WearProvenanceProducer {
    ANDROID_APP, COMPANION, IOS_APP, UNKNOWN;

    companion object { fun fromWire(value: String) = entries.firstOrNull { it.name == value } ?: UNKNOWN }
}

enum class WearDataNature {
    PROGRAMMED, OPERATIONAL, UNAVAILABLE, UNKNOWN;

    companion object { fun fromWire(value: String) = entries.firstOrNull { it.name == value } ?: UNKNOWN }
}

enum class WearOperationalState {
    PROGRAMMED, OPERATIONAL, UNAVAILABLE, UNKNOWN;

    companion object { fun fromWire(value: String) = entries.firstOrNull { it.name == value } ?: UNKNOWN }
}

enum class WearFreshness {
    FRESH, AGING, STALE, UNAVAILABLE, UNKNOWN;

    companion object { fun fromWire(value: String) = entries.firstOrNull { it.name == value } ?: UNKNOWN }
}

enum class WearConfidence {
    OPERATOR_PRIMARY, INFRASTRUCTURE_PRIMARY, OPEN_DATA, LICENSED_AGGREGATE, STATIC_ONLY, CACHED, LOCAL_PLANNED, UNKNOWN;

    companion object { fun fromWire(value: String) = entries.firstOrNull { it.name == value } ?: UNKNOWN }
}

enum class WearDirection {
    OUTBOUND, RETURN, UNKNOWN;

    companion object { fun fromWire(value: String) = entries.firstOrNull { it.name == value } ?: UNKNOWN }
}

data class WearService(
    val number: String,
    val operator: String,
    val category: String,
    val destination: String,
    val scheduledDeparture: String,
    val expectedDeparture: String,
    val status: String,
    val platform: String?,
    val delayMinutes: Int
) {
    val accessibleLabel: String
        get() = listOfNotNull(
            "Partenza programmata $scheduledDeparture",
            "Partenza prevista $expectedDeparture",
            "Treno $number",
            destination.takeIf { it.isNotBlank() },
            operator.takeIf { it.isNotBlank() },
            category.takeIf { it.isNotBlank() },
            platform?.let { "Binario $it" },
            status.takeIf { it.isNotBlank() }
        ).joinToString(". ")
}

/**
 * Independent v1 decoder for the phone Data Layer. It accepts only the closed key set defined in
 * contracts/wear-snapshot/v1, including an explicit expiry; malformed, expired and future-version
 * input returns null so the caller never replaces a valid cache.
 */
object WearSnapshotCodec {
    const val SCHEMA_VERSION = 1L
    const val MAX_PAYLOAD_BYTES = 8_192
    const val MAX_SERVICES = 2
    const val MAX_TTL_MILLIS = 365L * 24 * 60 * 60 * 1_000
    private const val MAX_FUTURE_SKEW_MILLIS = 60_000L

    private val baseKeys = setOf(
        "schemaVersion", "generatedAtEpochMillis", "expiresAtEpochMillis", "ttlMillis",
        "provenanceProducer", "provenanceDataNature", "provenanceSourceLabel", "provenanceObservedAtEpochMillis",
        "operationalState", "freshness", "confidence", "routeId", "routeLabel", "direction", "notice", "serviceCount"
    )

    fun decode(payload: Map<String, Any?>, nowEpochMillis: Long = System.currentTimeMillis()): WearSnapshot? {
        if (estimatedUtf8Size(payload) > MAX_PAYLOAD_BYTES) return null
        if (payload.long("schemaVersion") != SCHEMA_VERSION) return null
        val serviceCount = payload.int("serviceCount", 0, MAX_SERVICES) ?: return null
        val expectedKeys = buildSet {
            addAll(baseKeys)
            repeat(serviceCount) { index ->
                add("service${index}Number")
                add("service${index}Operator")
                add("service${index}Category")
                add("service${index}Destination")
                add("service${index}Scheduled")
                add("service${index}Expected")
                add("service${index}Status")
                add("service${index}Platform")
                add("service${index}DelayMinutes")
            }
        }
        if (payload.keys != expectedKeys) return null

        val generatedAt = payload.long("generatedAtEpochMillis") ?: return null
        val expiresAt = payload.long("expiresAtEpochMillis") ?: return null
        val ttl = payload.long("ttlMillis") ?: return null
        val observedAt = payload.long("provenanceObservedAtEpochMillis") ?: return null
        if (
            generatedAt < 0L || observedAt < 0L || generatedAt > nowEpochMillis + MAX_FUTURE_SKEW_MILLIS ||
            expiresAt <= nowEpochMillis || ttl !in 1..MAX_TTL_MILLIS || expiresAt - generatedAt != ttl ||
            observedAt > generatedAt + MAX_FUTURE_SKEW_MILLIS
        ) return null

        val producer = payload.requiredText("provenanceProducer", 32) ?: return null
        val dataNature = payload.requiredText("provenanceDataNature", 32) ?: return null
        val sourceLabel = payload.requiredText("provenanceSourceLabel", 96) ?: return null
        val operationalState = payload.requiredText("operationalState", 32) ?: return null
        val freshness = payload.requiredText("freshness", 24) ?: return null
        val confidence = payload.requiredText("confidence", 32) ?: return null
        val routeId = payload.requiredText("routeId", 64) ?: return null
        val routeLabel = payload.requiredText("routeLabel", 96) ?: return null
        val direction = payload.requiredText("direction", 16) ?: return null
        val notice = payload.optionalText("notice", 180) ?: return null

        val decodedNature = WearDataNature.fromWire(dataNature)
        val decodedOperationalState = WearOperationalState.fromWire(operationalState)
        if (decodedNature == WearDataNature.UNAVAILABLE && (decodedOperationalState != WearOperationalState.UNAVAILABLE || serviceCount != 0)) return null
        if (decodedOperationalState == WearOperationalState.UNAVAILABLE && serviceCount != 0) return null

        val services = (0 until serviceCount).map { index ->
            val number = payload.requiredText("service${index}Number", 24) ?: return null
            val operator = payload.requiredText("service${index}Operator", 64) ?: return null
            val category = payload.requiredText("service${index}Category", 64) ?: return null
            val destination = payload.requiredText("service${index}Destination", 96) ?: return null
            val scheduled = payload.requiredText("service${index}Scheduled", 16) ?: return null
            val expected = payload.requiredText("service${index}Expected", 16) ?: return null
            val status = payload.requiredText("service${index}Status", 64) ?: return null
            val platform = payload.optionalText("service${index}Platform", 16) ?: return null
            val delay = payload.int("service${index}DelayMinutes", -720, 1_440) ?: return null
            WearService(number, operator, category, destination, scheduled, expected, status, platform.ifBlank { null }, delay)
        }
        return WearSnapshot(
            routeId = routeId,
            routeLabel = routeLabel,
            direction = WearDirection.fromWire(direction),
            provenance = WearProvenance(WearProvenanceProducer.fromWire(producer), decodedNature, sourceLabel, observedAt),
            operationalState = decodedOperationalState,
            freshness = WearFreshness.fromWire(freshness),
            confidence = WearConfidence.fromWire(confidence),
            generatedAtEpochMillis = generatedAt,
            expiresAtEpochMillis = expiresAt,
            ttlMillis = ttl,
            notice = notice,
            services = services
        )
    }

    fun encode(snapshot: WearSnapshot): Map<String, Any?> = buildMap {
        put("schemaVersion", SCHEMA_VERSION)
        put("generatedAtEpochMillis", snapshot.generatedAtEpochMillis)
        put("expiresAtEpochMillis", snapshot.expiresAtEpochMillis)
        put("ttlMillis", snapshot.ttlMillis)
        put("provenanceProducer", snapshot.provenance.producer.name)
        put("provenanceDataNature", snapshot.provenance.dataNature.name)
        put("provenanceSourceLabel", snapshot.provenance.sourceLabel)
        put("provenanceObservedAtEpochMillis", snapshot.provenance.observedAtEpochMillis)
        put("operationalState", snapshot.operationalState.name)
        put("freshness", snapshot.freshness.name)
        put("confidence", snapshot.confidence.name)
        put("routeId", snapshot.routeId)
        put("routeLabel", snapshot.routeLabel)
        put("direction", snapshot.direction.name)
        put("notice", snapshot.notice)
        put("serviceCount", snapshot.services.size)
        snapshot.services.forEachIndexed { index, service ->
            put("service${index}Number", service.number)
            put("service${index}Operator", service.operator)
            put("service${index}Category", service.category)
            put("service${index}Destination", service.destination)
            put("service${index}Scheduled", service.scheduledDeparture)
            put("service${index}Expected", service.expectedDeparture)
            put("service${index}Status", service.status)
            put("service${index}Platform", service.platform.orEmpty())
            put("service${index}DelayMinutes", service.delayMinutes)
        }
    }

    private fun Map<String, Any?>.long(key: String): Long? {
        val number = this[key] as? Number ?: return null
        val asDouble = number.toDouble()
        if (!asDouble.isFinite() || asDouble != number.toLong().toDouble()) return null
        return number.toLong()
    }

    private fun Map<String, Any?>.int(key: String, minimum: Int, maximum: Int): Int? = long(key)
        ?.takeIf { it in minimum.toLong()..maximum.toLong() }
        ?.toInt()

    /** Returns null for a missing, non-string, over-limit or non-normalised mandatory value. */
    private fun Map<String, Any?>.requiredText(key: String, limit: Int): String? = (this[key] as? String)
        ?.takeIf { it.isNotBlank() && it.length <= limit && it == normalise(it) }

    /** Empty platform/notice values are valid but cannot carry whitespace-only or oversized data. */
    private fun Map<String, Any?>.optionalText(key: String, limit: Int): String? = (this[key] as? String)
        ?.takeIf { it.length <= limit && (it.isEmpty() || it == normalise(it)) }

    private fun normalise(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    /** Conservative upper bound independent of a JSON implementation; nested/binary values are rejected by the closed key set. */
    private fun estimatedUtf8Size(payload: Map<String, Any?>): Int = payload.entries.sumOf { (key, value) ->
        key.toByteArray(Charsets.UTF_8).size + 8 + when (value) {
            is String -> value.toByteArray(Charsets.UTF_8).size
            is Number, is Boolean -> 24
            null -> 4
            else -> MAX_PAYLOAD_BYTES + 1
        }
    }
}
