package it.binario.app

import android.content.Context
import androidx.core.content.edit
import it.binario.app.data.RailTime
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainService
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** One operational observation of a train on one service day, kept only on this device. */
data class PunctualityObservation(
    val serviceDate: LocalDate,
    val trainNumber: String,
    val operator: String,
    /** Last known delay in whole minutes, never negative. */
    val delayMinutes: Int,
    val cancelled: Boolean
)

data class PunctualityStats(
    val count: Int,
    /** Share of observations neither cancelled nor later than [PunctualityHistory.ON_TIME_MINUTES]. */
    val onTimePercent: Int,
    /** Mean delay of the trains that ran; null when every observation was a cancellation. */
    val averageDelayMinutes: Int?,
    val cancelledCount: Int,
    /** Newest first. */
    val recent: List<PunctualityObservation>
)

/**
 * Pure rules of the local punctuality history: one observation per train number and service day
 * (the latest wins), at most 90 days, 30 observations per train and 500 in total.
 */
object PunctualityHistory {
    const val RETENTION_DAYS = 90L
    const val MAX_PER_TRAIN = 30
    const val MAX_TOTAL = 500
    const val ON_TIME_MINUTES = 5
    const val MIN_FOR_STATS = 2
    const val RECENT_SHOWN = 5
    private const val MAX_NUMBER_LENGTH = 32
    private const val MAX_OPERATOR_LENGTH = 80
    private const val MAX_DELAY_MINUTES = 24 * 60
    private const val MAX_DECODED = MAX_TOTAL * 4

    fun trainKey(number: String): String = number.trim().replace(Regex("\\s+"), " ").uppercase(Locale.ROOT)

    /**
     * The observation an operational service gives for its service day, or null for
     * scheduled-only data, a future service day or a train without a number. The delay is the
     * one of the last stop with a verified time (the destination, once known), else the train's.
     */
    fun observationOf(service: TrainService, today: LocalDate): PunctualityObservation? {
        if (!service.operationalStatusVerified) return null
        val number = trainKey(service.number).takeIf { it.isNotEmpty() && it.length <= MAX_NUMBER_LENGTH } ?: return null
        val serviceDate = service.scheduledDepartureDateTime?.toLocalDate() ?: today
        if (serviceDate.isAfter(today)) return null
        val lastVerifiedStop = service.stops.lastOrNull { it.operationalStatusVerified ?: service.operationalStatusVerified }
        val delay = lastVerifiedStop?.delayMinutes ?: service.officialDelayMinutes ?: service.delayMinutes
        return PunctualityObservation(
            serviceDate = serviceDate,
            trainNumber = number,
            operator = service.displayOperator.trim().take(MAX_OPERATOR_LENGTH),
            delayMinutes = delay.coerceIn(0, MAX_DELAY_MINUTES),
            cancelled = service.status == ServiceStatus.CANCELLED
        )
    }

    fun record(history: List<PunctualityObservation>, observations: List<PunctualityObservation>, today: LocalDate): List<PunctualityObservation> =
        bound(history + observations, today)

    /** Newest first; for a repeated train and day the observation listed last wins. */
    fun bound(observations: List<PunctualityObservation>, today: LocalDate): List<PunctualityObservation> {
        val oldestKept = today.minusDays(RETENTION_DAYS - 1)
        val perTrain = mutableMapOf<String, Int>()
        return observations.asReversed()
            .asSequence()
            .map { it.copy(trainNumber = trainKey(it.trainNumber)) }
            .filter { it.trainNumber.isNotEmpty() && !it.serviceDate.isBefore(oldestKept) && !it.serviceDate.isAfter(today) }
            .distinctBy { it.trainNumber to it.serviceDate }
            .sortedWith(compareByDescending<PunctualityObservation> { it.serviceDate }.thenBy { it.trainNumber })
            .filter { observation ->
                val kept = perTrain.getOrDefault(observation.trainNumber, 0)
                (kept < MAX_PER_TRAIN).also { if (it) perTrain[observation.trainNumber] = kept + 1 }
            }
            .take(MAX_TOTAL)
            .toList()
    }

    fun withoutTrain(history: List<PunctualityObservation>, trainNumber: String): List<PunctualityObservation> {
        val key = trainKey(trainNumber)
        return history.filterNot { it.trainNumber == key }
    }

    /** Null below [MIN_FOR_STATS] observations: a single day says nothing about a train. */
    fun stats(history: List<PunctualityObservation>, trainNumber: String): PunctualityStats? {
        val key = trainKey(trainNumber)
        val observations = history.filter { it.trainNumber == key }.sortedByDescending { it.serviceDate }
        if (observations.size < MIN_FOR_STATS) return null
        val ran = observations.filterNot { it.cancelled }
        val onTime = ran.count { it.delayMinutes <= ON_TIME_MINUTES }
        return PunctualityStats(
            count = observations.size,
            onTimePercent = (onTime * 100.0 / observations.size).roundToInt(),
            averageDelayMinutes = ran.takeIf { it.isNotEmpty() }?.map { it.delayMinutes }?.average()?.roundToInt(),
            cancelledCount = observations.size - ran.size,
            recent = observations.take(RECENT_SHOWN)
        )
    }

    fun toJson(history: List<PunctualityObservation>): JSONArray = JSONArray().apply {
        history.forEach { observation ->
            put(JSONObject().apply {
                put("date", observation.serviceDate.toString())
                put("train", observation.trainNumber)
                put("operator", observation.operator)
                put("delayMinutes", observation.delayMinutes)
                put("cancelled", observation.cancelled)
            })
        }
    }

    /** Invalid entries are skipped one by one; values are clamped to the stored limits. */
    fun fromJson(array: JSONArray): List<PunctualityObservation> = (0 until minOf(array.length(), MAX_DECODED)).mapNotNull { index ->
        val value = array.optJSONObject(index) ?: return@mapNotNull null
        runCatching {
            val number = trainKey(value.optString("train"))
            require(number.isNotEmpty() && number.length <= MAX_NUMBER_LENGTH)
            require(value.has("delayMinutes"))
            PunctualityObservation(
                serviceDate = LocalDate.parse(value.optString("date")),
                trainNumber = number,
                operator = value.optString("operator").trim().take(MAX_OPERATOR_LENGTH),
                delayMinutes = value.optInt("delayMinutes", 0).coerceIn(0, MAX_DELAY_MINUTES),
                cancelled = value.optBoolean("cancelled", false)
            )
        }.getOrNull()
    }

    fun decode(raw: String?): List<PunctualityObservation> =
        raw?.let { runCatching { fromJson(JSONArray(it)) }.getOrNull() }.orEmpty()
}

/** SharedPreferences boundary of [PunctualityHistory]; nothing here leaves the device. */
class PunctualityHistoryStore(
    context: Context,
    private val today: () -> LocalDate = { LocalDate.now(RailTime.defaultZone) }
) {
    private val preferences = context.getSharedPreferences("binario_punctuality_history_v1", Context.MODE_PRIVATE)

    fun read(): List<PunctualityObservation> =
        PunctualityHistory.bound(PunctualityHistory.decode(preferences.getString(KEY_HISTORY, null)), today())

    fun record(current: List<PunctualityObservation>, observations: List<PunctualityObservation>): List<PunctualityObservation> =
        save(PunctualityHistory.record(current, observations, today()))

    fun replace(observations: List<PunctualityObservation>): List<PunctualityObservation> =
        save(PunctualityHistory.bound(observations, today()))

    fun removeTrain(current: List<PunctualityObservation>, trainNumber: String): List<PunctualityObservation> =
        save(PunctualityHistory.withoutTrain(current, trainNumber))

    private fun save(history: List<PunctualityObservation>): List<PunctualityObservation> {
        preferences.edit {
            if (history.isEmpty()) remove(KEY_HISTORY) else putString(KEY_HISTORY, PunctualityHistory.toJson(history).toString())
        }
        return history
    }

    private companion object {
        const val KEY_HISTORY = "observations"
    }
}
