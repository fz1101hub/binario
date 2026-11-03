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

/** The delay a run had at one of its stops, in whole minutes (never negative). */
data class StopDelay(val station: String, val delayMinutes: Int)

/** One operational observation of a train on one service day, kept only on this device. */
data class PunctualityObservation(
    val serviceDate: LocalDate,
    val trainNumber: String,
    val operator: String,
    /** Last known delay in whole minutes, never negative. */
    val delayMinutes: Int,
    val cancelled: Boolean,
    /** Delays at the stops with verified times, in order; kept only for the newest observations (see [PunctualityHistory.bound]). */
    val stopDelays: List<StopDelay> = emptyList()
)

/** Buckets of the distribution bars of the "Puntualità" card. */
enum class PunctualityBucket { ON_TIME, UP_TO_15, UP_TO_30, OVER_30, CANCELLED }

/** The stop where the delay grows most on average, from the observations with per-stop delays. */
data class PunctualityWorstStop(val station: String, val averageIncreaseMinutes: Int, val samples: Int)

data class PunctualityStats(
    val count: Int,
    /** Share of observations neither cancelled nor later than [PunctualityHistory.ON_TIME_MINUTES]. */
    val onTimePercent: Int,
    /** Mean delay of the trains that ran; null when every observation was a cancellation. */
    val averageDelayMinutes: Int?,
    val cancelledCount: Int,
    /** Newest first. */
    val recent: List<PunctualityObservation>,
    /** Share of observations that ran more than [PunctualityHistory.LATE_MINUTES] late. */
    val latePercent: Int = 0,
    /** Observations per bucket, every bucket present (zero included). */
    val distribution: Map<PunctualityBucket, Int> = emptyMap(),
    val worstStop: PunctualityWorstStop? = null,
    /** Every observation of the train, newest first: the per-date history. */
    val history: List<PunctualityObservation> = recent
)

/**
 * Pure rules of the local punctuality history: one observation per train number and service day
 * (the latest wins), at most 365 days, 365 observations per train and 1500 in total. Per-stop delays
 * are kept only for the newest [DETAILED_PER_TRAIN] observations of a train and [MAX_DETAILED_TOTAL]
 * overall, so the history (and the local backup that carries it) stays small.
 */
object PunctualityHistory {
    const val RETENTION_DAYS = 365L
    const val MAX_PER_TRAIN = 365
    const val MAX_TOTAL = 1500
    const val DETAILED_PER_TRAIN = 30
    const val MAX_DETAILED_TOTAL = 240
    const val MAX_STOPS = 40
    const val ON_TIME_MINUTES = 5
    const val LATE_MINUTES = 15
    const val SEVERE_MINUTES = 30
    const val MIN_FOR_STATS = 2
    const val RECENT_SHOWN = 5
    /** A stop must appear in this many detailed observations before it can be the worst one. */
    const val MIN_STOP_SAMPLES = 2
    private const val MAX_NUMBER_LENGTH = 32
    private const val MAX_OPERATOR_LENGTH = 80
    private const val MAX_STATION_LENGTH = 40
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
        val verifiedStops = service.stops.filter { it.operationalStatusVerified ?: service.operationalStatusVerified }
        val lastVerifiedStop = verifiedStops.lastOrNull()
        val delay = lastVerifiedStop?.delayMinutes ?: service.officialDelayMinutes ?: service.delayMinutes
        val cancelled = service.status == ServiceStatus.CANCELLED
        return PunctualityObservation(
            serviceDate = serviceDate,
            trainNumber = number,
            operator = service.displayOperator.trim().take(MAX_OPERATOR_LENGTH),
            delayMinutes = delay.coerceIn(0, MAX_DELAY_MINUTES),
            cancelled = cancelled,
            stopDelays = if (cancelled) emptyList() else stopDelays(verifiedStops.map { StopDelay(it.station, it.delayMinutes) })
        )
    }

    private fun stopDelays(stops: List<StopDelay>): List<StopDelay> = stops.asSequence()
        .map { StopDelay(it.station.trim().take(MAX_STATION_LENGTH), it.delayMinutes.coerceIn(0, MAX_DELAY_MINUTES)) }
        .filter { it.station.isNotEmpty() }
        .take(MAX_STOPS)
        .toList()

    fun record(history: List<PunctualityObservation>, observations: List<PunctualityObservation>, today: LocalDate): List<PunctualityObservation> =
        bound(history + observations, today)

    /** Newest first; for a repeated train and day the observation listed last wins. */
    fun bound(observations: List<PunctualityObservation>, today: LocalDate): List<PunctualityObservation> {
        val oldestKept = today.minusDays(RETENTION_DAYS - 1)
        val perTrain = mutableMapOf<String, Int>()
        val detailedPerTrain = mutableMapOf<String, Int>()
        var detailed = 0
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
            .map { observation ->
                if (observation.stopDelays.isEmpty()) return@map observation
                val keptDetails = detailedPerTrain.getOrDefault(observation.trainNumber, 0)
                if (keptDetails < DETAILED_PER_TRAIN && detailed < MAX_DETAILED_TOTAL) {
                    detailedPerTrain[observation.trainNumber] = keptDetails + 1
                    detailed += 1
                    observation.copy(stopDelays = stopDelays(observation.stopDelays))
                } else observation.copy(stopDelays = emptyList())
            }
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
        val late = ran.count { it.delayMinutes > LATE_MINUTES }
        return PunctualityStats(
            count = observations.size,
            onTimePercent = (onTime * 100.0 / observations.size).roundToInt(),
            averageDelayMinutes = ran.takeIf { it.isNotEmpty() }?.map { it.delayMinutes }?.average()?.roundToInt(),
            cancelledCount = observations.size - ran.size,
            recent = observations.take(RECENT_SHOWN),
            latePercent = (late * 100.0 / observations.size).roundToInt(),
            distribution = PunctualityBucket.entries.associateWith { bucket -> observations.count { bucketOf(it) == bucket } },
            worstStop = worstStop(ran),
            history = observations
        )
    }

    fun bucketOf(observation: PunctualityObservation): PunctualityBucket = when {
        observation.cancelled -> PunctualityBucket.CANCELLED
        observation.delayMinutes <= ON_TIME_MINUTES -> PunctualityBucket.ON_TIME
        observation.delayMinutes <= LATE_MINUTES -> PunctualityBucket.UP_TO_15
        observation.delayMinutes <= SEVERE_MINUTES -> PunctualityBucket.UP_TO_30
        else -> PunctualityBucket.OVER_30
    }

    /**
     * The stop where the delay grows most: for each detailed observation, the increase at a stop is its delay minus
     * the delay at the stop before; the stop with the highest average increase over at least [MIN_STOP_SAMPLES]
     * observations wins, when that average is at least one minute. The first stop of a run has no increase.
     */
    fun worstStop(observations: List<PunctualityObservation>): PunctualityWorstStop? {
        val increases = mutableMapOf<String, MutableList<Int>>()
        val names = mutableMapOf<String, String>()
        observations.filter { it.stopDelays.size > 1 }.forEach { observation ->
            observation.stopDelays.zipWithNext().forEach { (before, at) ->
                val key = at.station.uppercase(Locale.ROOT)
                names.putIfAbsent(key, at.station)
                increases.getOrPut(key) { mutableListOf() } += at.delayMinutes - before.delayMinutes
            }
        }
        return increases.asSequence()
            .filter { it.value.size >= MIN_STOP_SAMPLES }
            .map { (key, values) -> PunctualityWorstStop(names.getValue(key), values.average().roundToInt(), values.size) }
            .filter { it.averageIncreaseMinutes >= 1 }
            .sortedWith(compareByDescending<PunctualityWorstStop> { it.averageIncreaseMinutes }.thenByDescending { it.samples }.thenBy { it.station })
            .firstOrNull()
    }

    fun toJson(history: List<PunctualityObservation>): JSONArray = JSONArray().apply {
        history.forEach { observation ->
            put(JSONObject().apply {
                put("date", observation.serviceDate.toString())
                put("train", observation.trainNumber)
                put("operator", observation.operator)
                put("delayMinutes", observation.delayMinutes)
                put("cancelled", observation.cancelled)
                if (observation.stopDelays.isNotEmpty()) {
                    put("stops", JSONArray().apply {
                        observation.stopDelays.forEach { stop -> put(JSONArray().apply { put(stop.station); put(stop.delayMinutes) }) }
                    })
                }
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
                cancelled = value.optBoolean("cancelled", false),
                stopDelays = value.optJSONArray("stops")?.let { stops ->
                    stopDelays((0 until minOf(stops.length(), MAX_STOPS)).mapNotNull { stopIndex ->
                        val stop = stops.optJSONArray(stopIndex) ?: return@mapNotNull null
                        val station = stop.optString(0).takeIf { it.isNotBlank() && stop.length() >= 2 } ?: return@mapNotNull null
                        StopDelay(station, stop.optInt(1, 0))
                    })
                }.orEmpty()
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

    /** Observations past the retention are removed from storage too, not only from the result. */
    fun read(): List<PunctualityObservation> {
        val stored = PunctualityHistory.decode(preferences.getString(KEY_HISTORY, null))
        val bounded = PunctualityHistory.bound(stored, today())
        return if (bounded.size != stored.size) save(bounded) else bounded
    }

    /**
     * Adds [observations] to the history. The background recorder of the favourite trains writes here too, so what
     * is stored wins over an older in-memory [current] for the same train and day; [observations] win over both.
     */
    fun record(current: List<PunctualityObservation>, observations: List<PunctualityObservation>): List<PunctualityObservation> =
        save(PunctualityHistory.record(withStored(current), observations, today()))

    fun replace(observations: List<PunctualityObservation>): List<PunctualityObservation> =
        save(PunctualityHistory.bound(observations, today()))

    fun removeTrain(current: List<PunctualityObservation>, trainNumber: String): List<PunctualityObservation> =
        save(PunctualityHistory.withoutTrain(withStored(current), trainNumber))

    /** Newest first, as [PunctualityHistory.bound] orders them: the stored entries listed last win. */
    private fun withStored(current: List<PunctualityObservation>): List<PunctualityObservation> =
        PunctualityHistory.bound(current.asReversed() + PunctualityHistory.decode(preferences.getString(KEY_HISTORY, null)).asReversed(), today())

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
