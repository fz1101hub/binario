package it.binario.app.data.reliability

import it.binario.app.data.RailTime
import it.binario.app.data.Station
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Network reliability statistics: how every observed train ran over the last days, aggregated by the Binario
 * pipeline from ViaggiaTreno observations and embedded in the app (`assets/punctuality/stats_v1.json.gz`, format in
 * the shared specification). Unofficial statistics, unrelated to the device-only history of the favourite trains
 * ([it.binario.app.PunctualityHistory]); nothing here is fetched, recorded or sent.
 *
 * @property windowEnd the last day the statistics include ("aggiornate al …").
 * @property windowDays the days of the window the pipeline aggregates (30), observed or not.
 * @property buckets the delay limits, in minutes, of the cumulative counts of every stop.
 */
class NetworkReliabilityStats internal constructor(
    val generatedAt: Instant?,
    val windowStart: LocalDate?,
    val windowEnd: LocalDate,
    val windowDays: Int,
    private val bucketLimits: IntArray,
    val trains: List<NetworkReliabilityTrain>
) {
    val buckets: List<Int> get() = bucketLimits.toList()

    private val byNumber: Map<String, List<NetworkReliabilityTrain>> = trains.groupBy { NetworkReliability.numberKey(it.number) }

    /**
     * The days the statistics really cover, never more than [windowDays]: in the first weeks of the collection the
     * window is not full yet, and a daily train is observed on at most every day collected.
     */
    val coveredDays: Int = minOf(windowDays, trains.maxOfOrNull { it.daysObserved } ?: windowDays).coerceAtLeast(1)

    /** Every train published with [number] ("9558", "FR 9558"); trains with another origin are other trains. */
    fun trainsNumbered(number: String): List<NetworkReliabilityTrain> = byNumber[NetworkReliability.numberKey(number)].orEmpty()
}

/**
 * One train (number and origin) of [NetworkReliabilityStats]: its days observed and cancelled, and per stop, in run
 * order, the samples, the mean delay and the cumulative counts of the delays up to each bucket limit. The stops are
 * stored in flat arrays: about nine thousand trains stay a few megabytes.
 */
class NetworkReliabilityTrain internal constructor(
    val number: String,
    /** ViaggiaTreno code of the origin ("S08409"). */
    val originCode: String,
    /** Category of the timetable pack ("FR", "REG", "IC"), upper case. */
    val category: String,
    /** Days the run is known (arrived or cancelled), cancellations included. */
    val daysObserved: Int,
    /** Days fully cancelled. */
    val daysCancelled: Int,
    private val codes: Array<String>,
    private val samples: ShortArray,
    private val meanTenths: IntArray,
    private val cumulative: ShortArray,
    private val suppressed: ShortArray,
    private val buckets: IntArray,
    /** Largest delay per stop, [ABSENT] where an older file does not publish it. */
    private val maxDelays: ShortArray = ShortArray(codes.size) { ABSENT },
    /** Scheduled minute of the day per stop (may pass 1439 after midnight), [ABSENT] where not published. */
    private val scheduledMinutes: ShortArray = ShortArray(codes.size) { ABSENT }
) {
    val stopCount: Int get() = codes.size

    fun stationCode(index: Int): String = codes[index]

    /** Days with a delay at the stop: cancelled days and days the stop was suppressed are not samples. */
    fun samples(index: Int): Int = samples[index].toInt()

    /** Mean delay at the stop in minutes (departure delay at the origin, arrival delay elsewhere); may be negative. */
    fun meanDelayMinutes(index: Int): Double = meanTenths[index] / 10.0

    fun suppressedDays(index: Int): Int = suppressed[index].toInt()

    /** Samples at the stop with a delay of at most `buckets[bucket]` minutes. */
    fun cumulative(index: Int, bucket: Int): Int = cumulative[index * buckets.size + bucket].toInt()

    /** The largest delay observed at the stop in minutes (early counts as 0), or null when the file does not say. */
    fun maxDelayMinutes(index: Int): Int? = maxDelays[index].toInt().takeIf { it >= 0 }

    /**
     * The scheduled arrival (departure at the origin) at the stop in minutes since midnight, Europe/Rome, of the most
     * recent day observed; past 1439 for a stop reached after midnight. Null when the file does not say.
     */
    fun scheduledMinuteOfDay(index: Int): Int? = scheduledMinutes[index].toInt().takeIf { it >= 0 }

    /** Samples at the stop with a delay of at most [limit] minutes when [limit] is one of the bucket limits, else null. */
    fun cumulativeAtLimit(index: Int, limit: Int): Int? {
        val bucket = buckets.indexOf(limit)
        return if (bucket < 0) null else cumulative(index, bucket)
    }

    /** First stop at or after [from] with the code, or -1. */
    fun indexOf(code: String, from: Int = 0): Int {
        for (index in from.coerceAtLeast(0) until codes.size) if (codes[index] == code) return index
        return -1
    }

    /**
     * Samples at the stop with a delay of at most [minutes], interpolated linearly between the cumulative counts of
     * the two bucket limits around it: exact at a limit, the last count beyond the last limit (later delays are only
     * in the samples) and nothing below the first limit, where the counts say nothing.
     */
    fun countAtMost(index: Int, minutes: Double): Double {
        if (minutes.isNaN() || minutes < buckets[0]) return 0.0
        val base = index * buckets.size
        if (minutes >= buckets[buckets.lastIndex]) return cumulative[base + buckets.lastIndex].toDouble()
        var bucket = 0
        while (minutes >= buckets[bucket + 1]) bucket++
        val low = cumulative[base + bucket].toDouble()
        val high = cumulative[base + bucket + 1].toDouble()
        return low + (high - low) * (minutes - buckets[bucket]) / (buckets[bucket + 1] - buckets[bucket])
    }

    internal companion object {
        const val ABSENT: Short = -1
    }
}

/**
 * Reliability of one train at one stop: the delays of the [samples] days it reached the stop, measured against the
 * on-time limit of its category ([onTimeLimitMinutes]), and the days it was observed and cancelled.
 */
data class StopReliability(
    val stationCode: String,
    val samples: Int,
    /** Samples with a delay of at most [onTimeLimitMinutes]. */
    val onTime: Int,
    val onTimeLimitMinutes: Int,
    val meanDelayMinutes: Double,
    val daysObserved: Int,
    val daysCancelled: Int
) {
    val onTimeShare: Double get() = if (samples > 0) onTime.toDouble() / samples else 0.0
    val cancelledShare: Double get() = if (daysObserved > 0) daysCancelled.toDouble() / daysObserved else 0.0

    /** The chance of arriving on time on a day of the window: the train runs and is on time at the stop. */
    val score: Double get() = (1.0 - cancelledShare) * onTimeShare

    /** The mean delay the cards show, in whole minutes; an early average shows as no delay. */
    val shownMeanDelayMinutes: Int get() = meanDelayMinutes.roundToInt().coerceAtLeast(0)

    /** A train cancelled on every day observed: no delays at all, only its cancellations to show. */
    val cancelledEveryDay: Boolean get() = samples == 0
}

/** A train of the statistics matched to a ride: the stop where it is boarded (-1 when unknown) and left. */
class NetworkReliabilityMatch(val train: NetworkReliabilityTrain, val boardingIndex: Int, val alightingIndex: Int)

/** How often a change would have worked: [successes] of [samples] days (interpolated, rounded down). */
data class ConnectionOdds(val samples: Int, val expectedSuccesses: Double) {
    val probability: Double get() = if (samples > 0) (expectedSuccesses / samples).coerceIn(0.0, 1.0) else 0.0
    val successes: Int get() = floor(expectedSuccesses + 1e-9).toInt().coerceIn(0, samples)
}

/** The stop where the mean delay grows most from the stop before it. */
data class WorstStop(val stationCode: String, val increaseMinutes: Int, val samples: Int)

/** Pure rules of the network statistics (thresholds, minimum samples, matching, interpolation). */
object NetworkReliability {
    /** Below this many samples at the stop considered nothing is shown. */
    const val MIN_SAMPLES = 5
    const val HIGH_SPEED_ON_TIME_MINUTES = 5
    const val OTHER_ON_TIME_MINUTES = 15

    /** Categories measured at 5 minutes, as the operators and Treniamo do; every other category at 15. */
    private val HIGH_SPEED_CATEGORIES = setOf("FR", "FA", "FB", "EC", "ES", "ESC", "EN", "ITALO")
    private val STATION_CODE = Regex("S[0-9]{5}")

    fun onTimeLimitMinutes(category: String): Int =
        if (category.trim().uppercase(Locale.ROOT) in HIGH_SPEED_CATEGORIES) HIGH_SPEED_ON_TIME_MINUTES else OTHER_ON_TIME_MINUTES

    /** "9558" for "9558", "FR 9558" and "09558"; a number without digits keeps its letters. */
    fun numberKey(number: String): String {
        val digits = number.filter { it in '0'..'9' }.trimStart('0')
        return digits.ifEmpty { number.trim().replace(" ", "").uppercase(Locale.ROOT) }
    }

    fun isStationCode(code: String?): Boolean = code != null && STATION_CODE.matches(code)

    /** The ViaggiaTreno code of a catalogue station: its railway code ("S01700"), the code the live adapters use. */
    fun stationCode(station: Station?): String? = station?.railwayCode?.trim()?.takeIf(::isStationCode)

    /** The stop at [index] of [train], or null below [MIN_SAMPLES] samples. */
    fun at(train: NetworkReliabilityTrain, index: Int): StopReliability? {
        if (index !in 0 until train.stopCount) return null
        val samples = train.samples(index)
        if (samples < MIN_SAMPLES) return null
        val limit = onTimeLimitMinutes(train.category)
        return StopReliability(
            stationCode = train.stationCode(index),
            samples = samples,
            onTime = floor(train.countAtMost(index, limit.toDouble()) + 1e-9).toInt().coerceIn(0, samples),
            onTimeLimitMinutes = limit,
            meanDelayMinutes = train.meanDelayMinutes(index),
            daysObserved = train.daysObserved,
            daysCancelled = train.daysCancelled
        )
    }

    /**
     * The train numbered [number] that is left at [alightingCode]. The same number with another origin is another
     * train, so the candidates must stop at [alightingCode]; one that also stops at [boardingCode] before it is
     * preferred, then the one leaving from [originCode], then the one with more samples at [alightingCode]. Null
     * without a number or a station code to check.
     */
    fun match(
        stats: NetworkReliabilityStats,
        number: String?,
        alightingCode: String?,
        boardingCode: String? = null,
        originCode: String? = null
    ): NetworkReliabilityMatch? {
        if (number.isNullOrBlank() || alightingCode == null || !isStationCode(alightingCode)) return null
        val boarding = boardingCode?.takeIf(::isStationCode)
        return stats.trainsNumbered(number).mapNotNull { train ->
            val boardingIndex = boarding?.let(train::indexOf) ?: -1
            val alightingIndex = train.indexOf(alightingCode, from = boardingIndex + 1)
            if (alightingIndex < 0) null else NetworkReliabilityMatch(train, boardingIndex, alightingIndex)
        }.minWithOrNull(
            compareByDescending<NetworkReliabilityMatch> { it.boardingIndex >= 0 }
                .thenByDescending { originCode != null && it.train.originCode == originCode }
                .thenByDescending { it.train.samples(it.alightingIndex) }
                .thenBy { it.train.originCode }
        )
    }

    /**
     * The train numbered [number] that serves [boardingCode] (its origin or a stop), measured at its last stop: for a
     * train detail whose destination has no code. Null when no candidate stops at [boardingCode].
     */
    fun matchTerminus(stats: NetworkReliabilityStats, number: String?, boardingCode: String?): NetworkReliabilityMatch? {
        if (number.isNullOrBlank() || boardingCode == null || !isStationCode(boardingCode)) return null
        return stats.trainsNumbered(number).mapNotNull { train ->
            val boardingIndex = train.indexOf(boardingCode)
            if (boardingIndex < 0 || boardingIndex == train.stopCount - 1) null
            else NetworkReliabilityMatch(train, boardingIndex, train.stopCount - 1)
        }.minWithOrNull(
            compareByDescending<NetworkReliabilityMatch> { it.train.originCode == boardingCode }
                .thenByDescending { it.train.samples(it.alightingIndex) }
                .thenBy { it.train.originCode }
        )
    }

    /**
     * A train numbered [number] leaving [originCode] that was cancelled on every day observed (the file lists it
     * without stops), as a stop with no samples; null below [MIN_SAMPLES] days or when no such train leaves there.
     */
    fun cancelledEveryDay(stats: NetworkReliabilityStats, number: String?, originCode: String?): StopReliability? {
        if (number.isNullOrBlank() || originCode == null || !isStationCode(originCode)) return null
        val train = stats.trainsNumbered(number).firstOrNull { it.stopCount == 0 && it.originCode == originCode && it.daysObserved >= MIN_SAMPLES }
            ?: return null
        return StopReliability(originCode, 0, 0, onTimeLimitMinutes(train.category), 0.0, train.daysObserved, train.daysCancelled)
    }

    /**
     * The chance that a change after [train] at its stop [index] works with [slackMinutes] to spare (planned
     * departure of the next vehicle minus planned arrival minus the change time): the share of days the train was at
     * most that late, interpolated. Null below [MIN_SAMPLES] samples.
     */
    fun connection(train: NetworkReliabilityTrain, index: Int, slackMinutes: Double): ConnectionOdds? {
        if (index !in 0 until train.stopCount) return null
        val samples = train.samples(index)
        if (samples < MIN_SAMPLES) return null
        return ConnectionOdds(samples, train.countAtMost(index, slackMinutes).coerceIn(0.0, samples.toDouble()))
    }

    /**
     * Where the delay grows most: between consecutive stops with at least [MIN_SAMPLES] samples, the stop whose mean
     * delay exceeds the one before it by the most, when that is at least one minute.
     */
    fun worstStop(train: NetworkReliabilityTrain): WorstStop? {
        var best: WorstStop? = null
        var bestIncrease = 0.0
        var previous = -1
        for (index in 0 until train.stopCount) {
            if (train.samples(index) < MIN_SAMPLES) continue
            if (previous >= 0) {
                val increase = train.meanDelayMinutes(index) - train.meanDelayMinutes(previous)
                if (increase.roundToInt() >= 1 && (best == null || increase > bestIncrease)) {
                    best = WorstStop(train.stationCode(index), increase.roundToInt(), train.samples(index))
                    bestIncrease = increase
                }
            }
            previous = index
        }
        return best
    }
}

/** ViaggiaTreno codes of the catalogue stations, by station id, and the catalogue name of each code. */
class NetworkReliabilityStations(stations: List<Station>) {
    private val codeById = HashMap<String, String>(stations.size * 2)
    private val nameByCode = HashMap<String, String>(stations.size * 2)

    init {
        for (station in stations) {
            val code = NetworkReliability.stationCode(station) ?: continue
            codeById[station.id] = code
            nameByCode.putIfAbsent(code, station.name)
        }
    }

    /** The code of the station with the id ("curated:S01700"), as journey legs name their stations. */
    fun code(stationId: String): String? = codeById[stationId]

    fun name(code: String): String? = nameByCode[code]
}

/**
 * Reader of `stats_v1.json.gz`. Tolerant: an absent, corrupt, oversized or other-schema file gives null (the
 * feature is hidden), and a train whose fields do not add up is left out. Bounded: compressed and decompressed size,
 * trains, stops and buckets.
 */
object NetworkReliabilityParser {
    const val SCHEMA = "binario-punctuality-v1"

    data class Limits(
        val maxCompressedBytes: Long = 8L * 1024 * 1024,
        val maxJsonBytes: Long = 64L * 1024 * 1024,
        /** The pipeline's own bounds (tools/build_punctuality_stats.py): 50,000 trains, 400 stops per train. */
        val maxTrains: Int = 50_000,
        val maxStopsPerTrain: Int = 400,
        val maxBuckets: Int = 16,
        /** Samples and days per entry: far above any window, and within a Short. */
        val maxCount: Int = 10_000
    )

    fun parseGzip(input: InputStream, limits: Limits = Limits()): NetworkReliabilityStats? = try {
        GZIPInputStream(BoundedInputStream(input, limits.maxCompressedBytes), 64 * 1024).use { gzip ->
            parse(InputStreamReader(BoundedInputStream(gzip, limits.maxJsonBytes), Charsets.UTF_8), limits)
        }
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** The statistics of an uncompressed JSON document, or null; malformed JSON throws [IOException]. */
    fun parse(reader: Reader, limits: Limits = Limits()): NetworkReliabilityStats? {
        val json = JsonPullReader(reader)
        var schema: String? = null
        var generatedAt: Instant? = null
        var windowStart: LocalDate? = null
        var windowEnd: LocalDate? = null
        var windowDays: Int? = null
        var buckets: IntArray? = null
        // Trains read after the buckets (the pipeline's order) are made compact at once; earlier ones wait for them.
        val built = ArrayList<NetworkReliabilityTrain>()
        val pending = ArrayList<RawTrain>()
        var builtWith: IntArray? = null
        var entries = 0
        val codes = HashMap<String, String>()
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "schema" -> schema = json.optString()
                "generatedAt" -> generatedAt = json.optString()?.let { runCatching { Instant.parse(it) }.getOrNull() }
                "windowStart" -> windowStart = json.optString()?.let(::date)
                "windowEnd" -> windowEnd = json.optString()?.let(::date)
                "windowDays" -> windowDays = json.optInt()
                "buckets" -> buckets = intArray(json, limits.maxBuckets)
                "trains" -> {
                    json.beginArray()
                    while (json.hasNext()) {
                        if (entries++ == limits.maxTrains) throw JsonFormatException("more than ${limits.maxTrains} trains")
                        val raw = train(json, limits, codes) ?: continue
                        val known = buckets?.takeIf(::validBuckets)
                        if (known == null) pending += raw else raw.build(known)?.let(built::add)
                        if (known != null) builtWith = known
                    }
                    json.endArray()
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        if (json.peek() != JsonPullReader.Token.END_DOCUMENT) return null
        if (schema != SCHEMA) return null
        val limitsInMinutes = buckets?.takeIf(::validBuckets) ?: return null
        val end = windowEnd ?: generatedAt?.atZone(RailTime.defaultZone)?.toLocalDate() ?: return null
        val days = windowDays ?: windowStart?.let { ChronoUnit.DAYS.between(it, end).toInt() + 1 } ?: return null
        if (days !in 1..MAX_WINDOW_DAYS) return null
        // Buckets given twice, differently: the trains already read do not match them.
        if (builtWith != null && !builtWith.contentEquals(limitsInMinutes)) return null
        val kept = built + pending.mapNotNull { it.build(limitsInMinutes) }
        return NetworkReliabilityStats(generatedAt, windowStart, end, days, limitsInMinutes, kept)
    }

    private fun validBuckets(buckets: IntArray): Boolean =
        buckets.isNotEmpty() && buckets[0] >= 0 && (1 until buckets.size).all { buckets[it] > buckets[it - 1] }

    private fun date(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

    /** An array of integers, or null after skipping a value that is not one (or holds more than [max]). */
    private fun intArray(json: JsonPullReader, max: Int): IntArray? {
        if (json.peek() != JsonPullReader.Token.BEGIN_ARRAY) return null.also { json.skipValue() }
        val values = ArrayList<Int>()
        var valid = true
        json.beginArray()
        while (json.hasNext()) {
            val value = json.optInt()
            if (value == null || values.size == max) valid = false else values += value
        }
        json.endArray()
        return if (valid) values.toIntArray() else null
    }

    private fun train(json: JsonPullReader, limits: Limits, codes: HashMap<String, String>): RawTrain? {
        if (json.peek() != JsonPullReader.Token.BEGIN_OBJECT) return null.also { json.skipValue() }
        var number: String? = null
        var origin: String? = null
        var category = ""
        var days: Int? = null
        var cancelled = 0
        var stops: List<RawStop>? = null
        var valid = true
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "n" -> number = json.optString()?.trim()
                "o" -> origin = json.optString()?.trim()
                "c" -> category = json.optString()?.trim()?.uppercase(Locale.ROOT).orEmpty()
                "d" -> days = json.optInt()
                "x" -> cancelled = json.optInt() ?: -1
                "s" -> stops = stops(json, limits, codes).also { if (it == null) valid = false }
                else -> json.skipValue()
            }
        }
        json.endObject()
        val observed = days ?: return null
        if (!valid || number.isNullOrEmpty() || number.length > MAX_TEXT || origin.isNullOrEmpty() || origin.length > MAX_TEXT) return null
        if (category.length > MAX_TEXT || observed !in 0..limits.maxCount || cancelled !in 0..observed) return null
        // A train cancelled on every day observed has no stops; any other train needs at least one.
        val stopList = stops?.takeIf { it.isNotEmpty() || (observed > 0 && cancelled == observed) } ?: return null
        return RawTrain(number, codes.getOrPut(origin) { origin }, category, observed, cancelled, stopList, limits.maxCount)
    }

    /** The stops of a train, or null when one of them is malformed (the whole train is then left out). */
    private fun stops(json: JsonPullReader, limits: Limits, codes: HashMap<String, String>): List<RawStop>? {
        if (json.peek() != JsonPullReader.Token.BEGIN_ARRAY) return null.also { json.skipValue() }
        val stops = ArrayList<RawStop>()
        var valid = true
        json.beginArray()
        while (json.hasNext()) {
            val stop = stop(json, limits, codes)
            if (stop == null || stops.size == limits.maxStopsPerTrain) valid = false else stops += stop
        }
        json.endArray()
        return if (valid) stops else null
    }

    private fun stop(json: JsonPullReader, limits: Limits, codes: HashMap<String, String>): RawStop? {
        if (json.peek() != JsonPullReader.Token.BEGIN_ARRAY) return null.also { json.skipValue() }
        json.beginArray()
        val code = if (json.hasNext()) json.optString()?.trim() else null
        val samples = if (json.hasNext()) json.optInt() else null
        val mean = if (json.hasNext()) json.optDouble() else null
        val cumulative = if (json.hasNext()) intArray(json, limits.maxBuckets) else null
        val suppressed = if (json.hasNext()) json.optInt() else 0
        // Optional since the 4 Nov 2026 extension: a value that is not a plausible integer is left out, not the stop.
        val maxDelay = (if (json.hasNext()) json.optInt() else null)?.takeIf { it in 0..MAX_DELAY_MINUTES.toInt() }
        val scheduledMinute = (if (json.hasNext()) json.optInt() else null)?.takeIf { it in 0 until MAX_SCHEDULED_MINUTE }
        // Later fields of a newer pipeline are ignored.
        while (json.hasNext()) json.skipValue()
        json.endArray()
        if (code.isNullOrEmpty() || code.length > MAX_TEXT || samples == null || samples !in 0..limits.maxCount) return null
        if (mean == null || !mean.isFinite() || mean < -MAX_DELAY_MINUTES || mean > MAX_DELAY_MINUTES) return null
        if (cumulative == null || suppressed == null || suppressed !in 0..limits.maxCount) return null
        if (cumulative.any { it !in 0..samples } || (1 until cumulative.size).any { cumulative[it] < cumulative[it - 1] }) return null
        return RawStop(codes.getOrPut(code) { code }, samples, (mean * 10).roundToInt(), cumulative, suppressed, maxDelay, scheduledMinute)
    }

    private class RawStop(
        val code: String,
        val samples: Int,
        val meanTenths: Int,
        val cumulative: IntArray,
        val suppressed: Int,
        val maxDelay: Int?,
        val scheduledMinute: Int?
    )

    private class RawTrain(
        val number: String,
        val origin: String,
        val category: String,
        val days: Int,
        val cancelled: Int,
        val stops: List<RawStop>,
        val maxCount: Int
    ) {
        /** The compact train, or null when a stop does not have one count per bucket limit. */
        fun build(buckets: IntArray): NetworkReliabilityTrain? {
            if (stops.any { it.cumulative.size != buckets.size }) return null
            val cumulative = ShortArray(stops.size * buckets.size)
            stops.forEachIndexed { index, stop -> stop.cumulative.forEachIndexed { bucket, count -> cumulative[index * buckets.size + bucket] = count.toShort() } }
            return NetworkReliabilityTrain(
                number = number,
                originCode = origin,
                category = category,
                daysObserved = days.coerceAtMost(maxCount),
                daysCancelled = cancelled,
                codes = Array(stops.size) { stops[it].code },
                samples = ShortArray(stops.size) { stops[it].samples.toShort() },
                meanTenths = IntArray(stops.size) { stops[it].meanTenths },
                cumulative = cumulative,
                suppressed = ShortArray(stops.size) { stops[it].suppressed.toShort() },
                buckets = buckets,
                maxDelays = ShortArray(stops.size) { stops[it].maxDelay?.toShort() ?: NetworkReliabilityTrain.ABSENT },
                scheduledMinutes = ShortArray(stops.size) { stops[it].scheduledMinute?.toShort() ?: NetworkReliabilityTrain.ABSENT }
            )
        }
    }

    private const val MAX_TEXT = 16
    private const val MAX_DELAY_MINUTES = 24.0 * 60
    private const val MAX_WINDOW_DAYS = 366
    /** A run that reaches a stop up to two days after its service day's midnight still names its minute. */
    private const val MAX_SCHEDULED_MINUTE = 3 * 24 * 60
}
