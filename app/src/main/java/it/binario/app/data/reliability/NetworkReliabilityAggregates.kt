package it.binario.app.data.reliability

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * How the delays of one train at one stop spread: no delay at all, 1–5, 6–15, 16–30 and over 30 minutes,
 * from the cumulative counts of the statistics (whole minutes, so "at most 5" is "1–5" once the days without delay are
 * taken out). The five counts add up to [samples].
 */
data class DelayDistribution(
    val samples: Int,
    /** Delay of 0 or less (early arrivals included): not the train's on-time limit of 5 or 15 minutes. */
    val noDelay: Int,
    val upTo5: Int,
    val upTo15: Int,
    val upTo30: Int,
    val over30: Int
) {
    val counts: List<Int> get() = listOf(noDelay, upTo5, upTo15, upTo30, over30)

    /**
     * Whole percentages of [samples], one per band, that add up to 100: rounded down, then the points left over go to
     * the bands with the largest remainders (the earlier band on a tie). All zero without samples.
     */
    val percentages: List<Int>
        get() {
            if (samples <= 0) return counts.map { 0 }
            val exact = counts.map { it * 100.0 / samples }
            val shares = exact.map { floor(it + 1e-9).toInt() }.toMutableList()
            var left = 100 - shares.sum()
            val byRemainder = exact.indices.sortedWith(compareByDescending<Int> { exact[it] - shares[it] }.thenBy { it })
            for (index in byRemainder) {
                if (left <= 0) break
                if (counts[index] == 0) continue
                shares[index] += 1
                left -= 1
            }
            return shares
        }
}

/** The departure hour bands of the route statistics, by scheduled time at the boarding stop. */
enum class HourBand(val startHour: Int, val endHour: Int) {
    NIGHT(0, 6),
    EARLY_MORNING(6, 9),
    MORNING(9, 12),
    MIDDAY(12, 15),
    AFTERNOON(15, 18),
    EVENING(18, 21),
    LATE_EVENING(21, 24);

    companion object {
        /** The band of a scheduled minute; minutes past midnight of the service day (1450 = 00:10) wrap round. */
        fun of(minuteOfDay: Int): HourBand {
            val hour = Math.floorMod(minuteOfDay, 24 * 60) / 60
            return entries.first { hour < it.endHour }
        }
    }
}

/** The on-time share of the trains of a route leaving in one [band]. */
data class HourBandPunctuality(val band: HourBand, val samples: Int, val onTime: Int) {
    val onTimeShare: Double get() = if (samples > 0) onTime.toDouble() / samples else 0.0
}

/**
 * Every train of the statistics that serves the origin of a route before its destination, measured where the route
 * is left: [onTime] counted against each train's own limit (5 minutes for the high-speed and international
 * categories, 15 for the others), [meanDelayMinutes] weighted by the samples. [bestBand] and [worstBand] are set only
 * when the departure hours tell them apart ([NetworkReliabilityAggregates.bestAndWorst]).
 */
data class RoutePunctuality(
    val trains: Int,
    val samples: Int,
    val onTime: Int,
    val meanDelayMinutes: Double,
    /** The days the statistics cover ([NetworkReliabilityStats.coveredDays]). */
    val days: Int,
    val bands: List<HourBandPunctuality>,
    val bestBand: HourBand?,
    val worstBand: HourBand?,
    /**
     * The arrivals by ISO weekday, Monday first, of the trains whose file publishes them (each against its own limit);
     * empty when none does.
     */
    val weekdays: List<WeekdayPunctuality> = emptyList()
) {
    val onTimeShare: Double get() = if (samples > 0) onTime.toDouble() / samples else 0.0
    val onTimePercent: Int get() = (onTimeShare * 100).roundToInt()
    val shownMeanDelayMinutes: Int get() = meanDelayMinutes.roundToInt().coerceAtLeast(0)

    /**
     * «Il venerdì è in orario …, peggio della media» for [day] (the day searched), when it differs from the route by the
     * spec rule ([NetworkReliability.weekdayRemarks]); the overall share is that of the same weekday counts.
     */
    fun weekdayRemark(day: java.time.DayOfWeek): WeekdayRemark? {
        val total = weekdays.sumOf { it.samples }
        if (total <= 0) return null
        val overall = weekdays.sumOf { it.onTime }.toDouble() / total
        // The counts are already on time against each train's own limit: measure them as "within 15" of themselves.
        val days = weekdays.map { WeekdayDelays(it.weekday, it.samples, it.onTime, it.onTime) }
        return NetworkReliability.weekdayRemarks(days, NetworkReliability.OTHER_ON_TIME_MINUTES, overall).firstOrNull { it.weekday == day }
    }
}

/** The arrivals of a route on one ISO weekday: [onTime] of [samples] within each train's own limit. */
data class WeekdayPunctuality(val weekday: java.time.DayOfWeek, val samples: Int, val onTime: Int)

/** A train often late at a station: [late] of [samples] arrivals beyond its own on-time limit. */
data class LateTrain(
    val number: String,
    val category: String,
    val originCode: String,
    val samples: Int,
    val late: Int,
    val meanDelayMinutes: Double
) {
    val lateShare: Double get() = if (samples > 0) late.toDouble() / samples else 0.0
}

/**
 * The arrivals of every train at one station (its origin is a departure, not an arrival): on-time share against each
 * train's own limit, mean delay weighted by the samples, and up to three trains most often late there.
 */
data class StationPunctuality(
    val trains: Int,
    val samples: Int,
    val onTime: Int,
    val meanDelayMinutes: Double,
    val days: Int,
    val oftenLate: List<LateTrain>
) {
    val onTimeShare: Double get() = if (samples > 0) onTime.toDouble() / samples else 0.0
    val onTimePercent: Int get() = (onTimeShare * 100).roundToInt()
    val shownMeanDelayMinutes: Int get() = meanDelayMinutes.roundToInt().coerceAtLeast(0)
}

/** Aggregates of the network statistics: one stop's delay distribution, a route, a station. */
object NetworkReliabilityAggregates {
    /** Below this many samples in all, a route or a station shows nothing. */
    const val MIN_AGGREGATE_SAMPLES = 20
    /** An hour band takes part in "più / meno puntuali" with at least this many samples. */
    const val MIN_BAND_SAMPLES = 20
    /** The best and the worst band are named only when their on-time shares differ by at least this many points. */
    const val MIN_BAND_GAP_POINTS = 5
    /** The trains named as often late at a station. */
    const val MAX_LATE_TRAINS = 3

    private val DISTRIBUTION_LIMITS = intArrayOf(0, 5, 15, 30)

    /**
     * The delay distribution of [train] at its stop [index], or null below [NetworkReliability.MIN_SAMPLES] samples or
     * when the buckets of the file do not include 0, 5, 15 and 30 minutes.
     */
    fun distribution(train: NetworkReliabilityTrain, index: Int): DelayDistribution? {
        if (index !in 0 until train.stopCount) return null
        val samples = train.samples(index)
        if (samples < NetworkReliability.MIN_SAMPLES) return null
        val (zero, five, fifteen, thirty) = DISTRIBUTION_LIMITS.map { train.cumulativeAtLimit(index, it) ?: return null }
        if (zero > five || five > fifteen || fifteen > thirty || thirty > samples) return null
        return DelayDistribution(samples, zero, five - zero, fifteen - five, thirty - fifteen, samples - thirty)
    }

    /**
     * The trains of the statistics leaving [originCode] (at its first stop there) and later stopping at
     * [destinationCode], measured at the destination; null without both codes, for the same code twice or below
     * [MIN_AGGREGATE_SAMPLES] samples at the destination in all. The hour bands use the scheduled minute at the
     * origin; a train whose file does not publish it is counted in the route, not in the bands.
     */
    fun route(stats: NetworkReliabilityStats, originCode: String?, destinationCode: String?): RoutePunctuality? {
        if (!NetworkReliability.isStationCode(originCode) || !NetworkReliability.isStationCode(destinationCode)) return null
        if (originCode == destinationCode) return null
        var trains = 0
        var samples = 0
        var onTime = 0
        var delaySum = 0.0
        val bandSamples = IntArray(HourBand.entries.size)
        val bandOnTime = IntArray(HourBand.entries.size)
        val weekdaySamples = IntArray(7)
        val weekdayOnTime = IntArray(7)
        var withWeekdays = false
        for (train in stats.trains) {
            val boarding = train.indexOf(originCode!!)
            if (boarding < 0) continue
            val alighting = train.indexOf(destinationCode!!, from = boarding + 1)
            if (alighting < 0) continue
            val count = train.samples(alighting)
            if (count <= 0) continue
            val inTime = onTimeAt(train, alighting)
            trains += 1
            samples += count
            onTime += inTime
            delaySum += train.meanDelayMinutes(alighting) * count
            train.scheduledMinuteOfDay(boarding)?.let { minute ->
                val band = HourBand.of(minute).ordinal
                bandSamples[band] += count
                bandOnTime[band] += inTime
            }
            train.weekdays(alighting)?.let { days ->
                val limit = NetworkReliability.onTimeLimitMinutes(train.category)
                withWeekdays = true
                days.forEach { day ->
                    weekdaySamples[day.weekday.ordinal] += day.samples
                    weekdayOnTime[day.weekday.ordinal] += day.onTime(limit).coerceIn(0, day.samples)
                }
            }
        }
        if (samples < MIN_AGGREGATE_SAMPLES) return null
        val bands = HourBand.entries.filter { bandSamples[it.ordinal] > 0 }
            .map { HourBandPunctuality(it, bandSamples[it.ordinal], bandOnTime[it.ordinal]) }
        val (best, worst) = bestAndWorst(bands)
        val weekdays = if (!withWeekdays) emptyList() else java.time.DayOfWeek.entries.map {
            WeekdayPunctuality(it, weekdaySamples[it.ordinal], weekdayOnTime[it.ordinal])
        }
        return RoutePunctuality(trains, samples, onTime, delaySum / samples, stats.coveredDays, bands, best, worst, weekdays)
    }

    /**
     * The best and the worst band among those with at least [MIN_BAND_SAMPLES] samples, or nulls when fewer than two
     * qualify or their on-time shares are less than [MIN_BAND_GAP_POINTS] points apart. Equal shares go to the earlier
     * band for the best and the later band for the worst.
     */
    fun bestAndWorst(bands: List<HourBandPunctuality>): Pair<HourBand?, HourBand?> {
        val eligible = bands.filter { it.samples >= MIN_BAND_SAMPLES }
        if (eligible.size < 2) return null to null
        val best = eligible.maxWith(compareBy<HourBandPunctuality> { it.onTimeShare }.thenByDescending { it.band.ordinal })
        val worst = eligible.minWith(compareBy<HourBandPunctuality> { it.onTimeShare }.thenByDescending { it.band.ordinal })
        if (best.band == worst.band) return null to null
        if ((best.onTimeShare - worst.onTimeShare) * 100 < MIN_BAND_GAP_POINTS - 1e-9) return null to null
        return best.band to worst.band
    }

    /**
     * Arrivals at [stationCode] of every train that stops there after its origin; null without a code or below
     * [MIN_AGGREGATE_SAMPLES] samples. The trains often late have at least [NetworkReliability.MIN_SAMPLES] samples
     * there and at least one late arrival: the larger late share first, then the higher mean delay, then more samples.
     */
    fun station(stats: NetworkReliabilityStats, stationCode: String?): StationPunctuality? {
        if (!NetworkReliability.isStationCode(stationCode)) return null
        var trains = 0
        var samples = 0
        var onTime = 0
        var delaySum = 0.0
        val candidates = ArrayList<LateTrain>()
        for (train in stats.trains) {
            val index = train.indexOf(stationCode!!, from = 1)
            if (index < 0) continue
            val count = train.samples(index)
            if (count <= 0) continue
            val inTime = onTimeAt(train, index)
            trains += 1
            samples += count
            onTime += inTime
            delaySum += train.meanDelayMinutes(index) * count
            if (count >= NetworkReliability.MIN_SAMPLES && inTime < count) {
                candidates += LateTrain(train.number, train.category, train.originCode, count, count - inTime, train.meanDelayMinutes(index))
            }
        }
        if (samples < MIN_AGGREGATE_SAMPLES) return null
        val oftenLate = candidates.sortedWith(
            compareByDescending<LateTrain> { it.lateShare }
                .thenByDescending { it.meanDelayMinutes }
                .thenByDescending { it.samples }
                .thenBy { it.number }
                .thenBy { it.originCode }
        ).take(MAX_LATE_TRAINS)
        return StationPunctuality(trains, samples, onTime, delaySum / samples, stats.coveredDays, oftenLate)
    }

    /** Samples of [train] at [index] within its own on-time limit (both limits are buckets, so the count is exact). */
    private fun onTimeAt(train: NetworkReliabilityTrain, index: Int): Int {
        val limit = NetworkReliability.onTimeLimitMinutes(train.category)
        return floor(train.countAtMost(index, limit.toDouble()) + 1e-9).toInt().coerceIn(0, train.samples(index))
    }
}
