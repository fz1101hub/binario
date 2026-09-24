package im.binario.companion.wear

import android.content.Context
import android.content.Intent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.charset.StandardCharsets

data class BinarioWearState(
  val title: String,
  val status: String,
  val canComplicate: Boolean,
  val updatedAtUtcMs: Long?,
  val expiresAtUtcMs: Long?,
  val plannedChecklistRemaining: Int,
)

data class BinarioWearSnapshot(
  val routeLabel: String,
  val notice: String,
  val generatedAtEpochMillis: Long,
  val expiresAtEpochMillis: Long,
  val operationalState: String,
  val freshness: String,
  val direction: String,
  val serviceCount: Int,
)

/**
 * Decoder for the canonical flat Companion → Wear v1 DataMap. Its schema is closed: a
 * malformed or expanded map cannot replace the last valid watch cache. Enum values added
 * by a future producer deliberately degrade to UNKNOWN rather than gaining operational meaning.
 */
object BinarioWearSnapshotV1 {
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

  private fun enumOrUnknown(value: Any?, allowed: Set<String>): String? {
    val enum = normalized(value, 64) ?: return null
    return enum.takeIf(allowed::contains) ?: "UNKNOWN"
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

  private fun serializedBytes(values: Map<String, Any?>): Int = values.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
    "${jsonString(key)}:${if (value is String) jsonString(value) else value}"
  }.toByteArray(StandardCharsets.UTF_8).size

  fun decode(raw: Map<String, Any?>, now: Long): BinarioWearSnapshot? {
    val serviceCount = integer(raw["serviceCount"])?.takeIf { it in 0L..2L }?.toInt() ?: return null
    if (raw.keys != expectedKeys(serviceCount) || serializedBytes(raw) > maxSerializedBytes) return null
    val schema = integer(raw["schemaVersion"]) ?: return null
    val generated = integer(raw["generatedAtEpochMillis"]) ?: return null
    val expires = integer(raw["expiresAtEpochMillis"]) ?: return null
    val ttl = integer(raw["ttlMillis"]) ?: return null
    if (schema != schemaVersion || ttl !in 1..maxTtlMillis || expires - generated != ttl || expires <= now || generated > now + futureSkewMillis || integer(raw["provenanceObservedAtEpochMillis"]) == null) return null
    val producer = enumOrUnknown(raw["provenanceProducer"], producerValues) ?: return null
    val dataNature = enumOrUnknown(raw["provenanceDataNature"], dataNatureValues) ?: return null
    val operational = enumOrUnknown(raw["operationalState"], operationalStateValues) ?: return null
    val freshness = enumOrUnknown(raw["freshness"], freshnessValues) ?: return null
    if (enumOrUnknown(raw["confidence"], confidenceValues) == null) return null
    val direction = enumOrUnknown(raw["direction"], directionValues) ?: return null
    val source = normalized(raw["provenanceSourceLabel"], 96) ?: return null
    val routeId = normalized(raw["routeId"], 64) ?: return null
    val routeLabel = normalized(raw["routeLabel"], 96) ?: return null
    val notice = normalized(raw["notice"], 180, allowEmpty = true) ?: return null
    // Keep these named checks: provenance remains compulsory even though the Tile only renders a safe summary.
    if (producer.isBlank() || source.isBlank() || routeId.length > 64 || (operational == "UNAVAILABLE" || dataNature == "UNAVAILABLE") && (serviceCount != 0 || dataNature != "UNAVAILABLE")) return null
    repeat(serviceCount) { index ->
      val prefix = "service$index"
      listOf("Number" to 24, "Operator" to 64, "Category" to 64, "Destination" to 96, "Scheduled" to 16, "Expected" to 16, "Status" to 64).forEach { (field, maximum) ->
        normalized(raw["$prefix$field"], maximum) ?: return null
      }
      if (normalized(raw["${prefix}Platform"], 16, allowEmpty = true) == null || integer(raw["${prefix}DelayMinutes"])?.let { it !in -720..1_440 } != false) return null
    }
    return BinarioWearSnapshot(routeLabel, notice, generated, expires, operational, freshness, direction, serviceCount)
  }
}

object BinarioWearStore {
  private const val prefsName = "binario_wear_payload_v1"
  private const val unavailable = "Dati non disponibili in tempo reale."

  fun write(context: Context, snapshot: BinarioWearSnapshot) {
    context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().apply {
      putString("title", snapshot.routeLabel.ifBlank { "Binario" })
      putString("status", snapshot.notice.ifBlank { unavailable })
      putLong("generatedAtUtcMs", snapshot.generatedAtEpochMillis)
      putLong("expiresAtUtcMs", snapshot.expiresAtEpochMillis)
    }.apply()
  }

  fun read(context: Context, now: Long = System.currentTimeMillis()): BinarioWearState {
    val preferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    val generated = if (preferences.contains("generatedAtUtcMs")) preferences.getLong("generatedAtUtcMs", 0L) else null
    val expires = if (preferences.contains("expiresAtUtcMs")) preferences.getLong("expiresAtUtcMs", 0L) else null
    val fresh = generated != null && expires != null && generated <= now + BinarioWearSnapshotV1.futureSkewMillis && expires > now
    return if (fresh) {
      BinarioWearState(
        preferences.getString("title", "Binario") ?: "Binario",
        preferences.getString("status", unavailable) ?: unavailable,
        true,
        generated,
        expires,
        0,
      )
    } else {
      BinarioWearState("Binario", unavailable, false, null, null, 0)
    }
  }
}

class BinarioWearDataService : WearableListenerService() {
  override fun onDataChanged(events: DataEventBuffer) {
    events.forEach { event ->
      if (event.type != com.google.android.gms.wearable.DataEvent.TYPE_CHANGED || event.dataItem.uri.path != "/binario/companion/watch-payload/v1") return@forEach
      val map = DataMapItem.fromDataItem(event.dataItem).dataMap
      val raw: Map<String, Any?> = map.keySet().associate { key: String -> key to map.get(key) }
      BinarioWearSnapshotV1.decode(raw, System.currentTimeMillis())?.let { BinarioWearStore.write(this, it) }
    }
  }

  override fun onMessageReceived(event: MessageEvent) {
    if (event.path != "/binario/companion/voice-response/v1") return
    val reply = event.data.toString(Charsets.UTF_8).take(280).ifBlank { "Dati non disponibili in tempo reale." }
    sendBroadcast(Intent(BinarioVoiceRequestActivity.replyAction).setPackage(packageName).putExtra(BinarioVoiceRequestActivity.replyExtra, reply))
  }
}
