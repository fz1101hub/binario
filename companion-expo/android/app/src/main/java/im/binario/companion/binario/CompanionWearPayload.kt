package im.binario.companion.binario

import com.google.android.gms.wearable.DataMap
import java.nio.charset.StandardCharsets

/** Producer-side validation for the closed Companion → Wear v1 DataMap contract. */
object CompanionWearPayloadV1 {
  const val schemaVersion = 1L
  const val maxSerializedBytes = 8_192
  const val maxTtlMillis = 365L * 24L * 60L * 60L * 1_000L
  const val futureSkewMillis = 60_000L

  private val producerValues = setOf("ANDROID_APP", "COMPANION", "IOS_APP", "UNKNOWN")
  private val dataNatureValues = setOf("PROGRAMMED", "OPERATIONAL", "UNAVAILABLE", "UNKNOWN")
  private val operationalStateValues = setOf("PROGRAMMED", "OPERATIONAL", "UNAVAILABLE", "UNKNOWN")
  private val freshnessValues = setOf("FRESH", "AGING", "STALE", "UNAVAILABLE", "UNKNOWN")
  private val confidenceValues = setOf("OPERATOR_PRIMARY", "INFRASTRUCTURE_PRIMARY", "OPEN_DATA", "LICENSED_AGGREGATE", "STATIC_ONLY", "CACHED", "LOCAL_PLANNED", "UNKNOWN")
  private val directionValues = setOf("OUTBOUND", "RETURN", "UNKNOWN")
  private val baseKeys = listOf(
    "schemaVersion", "generatedAtEpochMillis", "expiresAtEpochMillis", "ttlMillis",
    "provenanceProducer", "provenanceDataNature", "provenanceSourceLabel", "provenanceObservedAtEpochMillis",
    "operationalState", "freshness", "confidence", "routeId", "routeLabel", "direction", "notice", "serviceCount",
  )
  private val serviceFields = listOf("Number", "Operator", "Category", "Destination", "Scheduled", "Expected", "Status", "Platform", "DelayMinutes")

  private fun normalized(value: Any?, maximum: Int, allowEmpty: Boolean = false): String? {
    val string = value as? String ?: return null
    val normal = string.trim().replace(Regex("\\s+"), " ")
    return string.takeIf { it == normal && it.length <= maximum && (allowEmpty || it.isNotEmpty()) }
  }

  private fun integer(value: Any?): Long? {
    val number = value as? Number ?: return null
    val result = number.toLong()
    return result.takeIf {
      number.toDouble().isFinite() && number.toDouble() == result.toDouble() &&
        result in -9_007_199_254_740_991L..9_007_199_254_740_991L
    }
  }

  private fun expectedKeys(serviceCount: Int): Set<String> = buildSet {
    addAll(baseKeys)
    repeat(serviceCount) { index -> serviceFields.forEach { field -> add("service${index}$field") } }
  }

  private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { character -> when (character) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\b' -> append("\\b")
      '\u000C' -> append("\\f")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
    } }
    append('"')
  }

  private fun serializedBytes(values: Map<String, Any>): Int = values.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
    "${jsonString(key)}:${if (value is String) jsonString(value) else value}"
  }.toByteArray(StandardCharsets.UTF_8).size

  /** Returns a canonical map only when every supplied key and value satisfies v1. */
  fun decode(raw: Map<String, Any?>, now: Long): Map<String, Any>? {
    val serviceCount = integer(raw["serviceCount"])?.takeIf { it in 0L..2L }?.toInt() ?: return null
    if (raw.keys != expectedKeys(serviceCount)) return null
    val schema = integer(raw["schemaVersion"]) ?: return null
    val generated = integer(raw["generatedAtEpochMillis"]) ?: return null
    val expires = integer(raw["expiresAtEpochMillis"]) ?: return null
    val ttl = integer(raw["ttlMillis"]) ?: return null
    val observed = integer(raw["provenanceObservedAtEpochMillis"]) ?: return null
    if (schema != schemaVersion || ttl !in 1..maxTtlMillis || expires - generated != ttl || expires <= now || generated > now + futureSkewMillis) return null
    val producer = normalized(raw["provenanceProducer"], 64)?.takeIf(producerValues::contains) ?: return null
    val dataNature = normalized(raw["provenanceDataNature"], 64)?.takeIf(dataNatureValues::contains) ?: return null
    val operational = normalized(raw["operationalState"], 64)?.takeIf(operationalStateValues::contains) ?: return null
    val freshness = normalized(raw["freshness"], 64)?.takeIf(freshnessValues::contains) ?: return null
    val confidence = normalized(raw["confidence"], 64)?.takeIf(confidenceValues::contains) ?: return null
    val direction = normalized(raw["direction"], 16)?.takeIf(directionValues::contains) ?: return null
    val sourceLabel = normalized(raw["provenanceSourceLabel"], 96) ?: return null
    val routeId = normalized(raw["routeId"], 64) ?: return null
    val routeLabel = normalized(raw["routeLabel"], 96) ?: return null
    val notice = normalized(raw["notice"], 180, allowEmpty = true) ?: return null
    if ((operational == "UNAVAILABLE" || dataNature == "UNAVAILABLE") && (serviceCount != 0 || dataNature != "UNAVAILABLE")) return null
    val canonical = linkedMapOf<String, Any>(
      "schemaVersion" to schema,
      "generatedAtEpochMillis" to generated,
      "expiresAtEpochMillis" to expires,
      "ttlMillis" to ttl,
      "provenanceProducer" to producer,
      "provenanceDataNature" to dataNature,
      "provenanceSourceLabel" to sourceLabel,
      "provenanceObservedAtEpochMillis" to observed,
      "operationalState" to operational,
      "freshness" to freshness,
      "confidence" to confidence,
      "routeId" to routeId,
      "routeLabel" to routeLabel,
      "direction" to direction,
      "notice" to notice,
      "serviceCount" to serviceCount,
    )
    repeat(serviceCount) { index ->
      val prefix = "service$index"
      listOf("Number" to 24, "Operator" to 64, "Category" to 64, "Destination" to 96, "Scheduled" to 16, "Expected" to 16, "Status" to 64).forEach { (field, maximum) ->
        canonical["$prefix$field"] = normalized(raw["$prefix$field"], maximum) ?: return null
      }
      canonical["${prefix}Platform"] = normalized(raw["${prefix}Platform"], 16, allowEmpty = true) ?: return null
      canonical["${prefix}DelayMinutes"] = integer(raw["${prefix}DelayMinutes"])?.takeIf { it in -720..1_440 } ?: return null
    }
    return canonical.takeIf { serializedBytes(it) <= maxSerializedBytes }
  }

  /** A bounded explicit fallback is safer than manufacturing operational information. */
  fun unavailable(now: Long): Map<String, Any> = linkedMapOf(
    "schemaVersion" to schemaVersion,
    "generatedAtEpochMillis" to now,
    "expiresAtEpochMillis" to now + 15L * 60L * 1_000L,
    "ttlMillis" to 15L * 60L * 1_000L,
    "provenanceProducer" to "COMPANION",
    "provenanceDataNature" to "UNAVAILABLE",
    "provenanceSourceLabel" to "Companion locale",
    "provenanceObservedAtEpochMillis" to now,
    "operationalState" to "UNAVAILABLE",
    "freshness" to "UNAVAILABLE",
    "confidence" to "LOCAL_PLANNED",
    "routeId" to "unavailable",
    "routeLabel" to "Nessuna tratta attiva",
    "direction" to "UNKNOWN",
    "notice" to "Dati non disponibili nel Companion: apri l’app principale per aggiornare la pianificazione locale.",
    "serviceCount" to 0,
  )

  fun put(dataMap: DataMap, payload: Map<String, Any>) {
    payload.forEach { (key, value) -> when (value) {
      is String -> dataMap.putString(key, value)
      is Int -> dataMap.putInt(key, value)
      is Long -> dataMap.putLong(key, value)
      else -> error("Unexpected canonical v1 DataMap type for $key")
    } }
  }
}
