package it.binario.app.data.reliability

import java.time.Duration
import java.time.LocalDateTime

/**
 * A direct train of a results list as «Alternativa più puntuale» weighs it: its key in the list, its scheduled
 * departure where the user boards, whether it can still be taken, and its reliability where the user gets off.
 */
data class AlternativeCandidate(
    val key: String,
    /** Scheduled departure, railway-local; null when the source published no readable time (never compared). */
    val departure: LocalDateTime?,
    /** The train at the user's destination ([NetworkReliability.at]); null without statistics. */
    val stop: StopReliability?,
    /** Neither cancelled nor already gone from the boarding station. */
    val stillToDepart: Boolean
)

/** The train suggested instead of another one: its key in the list, departure and reliability at the destination. */
data class ReliableAlternative(val key: String, val departure: LocalDateTime, val stop: StopReliability)

/**
 * «Alternativa più puntuale»: for a direct train that is often late where the user gets off, another direct train of the
 * same list leaving around the same time that is clearly more often on time there. Only trains measured against the
 * same on-time limit are compared (5 minutes for the high-speed categories, 15 for the others,
 * [NetworkReliability.onTimeLimitMinutes]): a regional on time within 15 minutes is not «more punctual» than a
 * Frecciarossa on time within 5, and the two need different tickets anyway. Cancellations count too: the alternative
 * must also be clearly more likely to run and arrive on time on a day ([StopReliability.score]), so a train often
 * cancelled is never offered for being punctual on the days it runs.
 */
object ReliableAlternatives {
    /** A train is helped only when it is on time in fewer than this many percent of its samples … */
    const val MAX_ON_TIME_PERCENT = 70
    /** … by a train whose on-time share is at least this many points higher … */
    const val MIN_GAP_POINTS = 20
    /** … leaving at most this many minutes before or after it. */
    const val WINDOW_MINUTES = 30L

    /**
     * The alternative of every train of [candidates] that has one, by the train's key. A train needs at least
     * [NetworkReliability.MIN_SAMPLES] samples, an on-time share below [MAX_ON_TIME_PERCENT] percent and to be still to
     * depart; the alternative too needs the samples and to be still to depart, has the same on-time limit, leaves
     * within [WINDOW_MINUTES] of it (scheduled times), is on time at least [MIN_GAP_POINTS] points more often and its
     * [StopReliability.score] is at least [MIN_GAP_POINTS] points higher. Among several, the departure closest to the
     * train's wins, then the earlier one, then the more punctual one.
     */
    fun find(candidates: List<AlternativeCandidate>): Map<String, ReliableAlternative> {
        val eligible = candidates.filter(::measured)
        return buildMap {
            for (train in eligible) {
                if (!oftenLate(train.stop!!)) continue
                alternativeFor(train, eligible)?.let { put(train.key, it) }
            }
        }
    }

    private fun alternativeFor(train: AlternativeCandidate, eligible: List<AlternativeCandidate>): ReliableAlternative? {
        val departure = train.departure!!
        val stop = train.stop!!
        return eligible.asSequence()
            .filter { it.key != train.key && it.stop!!.onTimeLimitMinutes == stop.onTimeLimitMinutes }
            .filter { Duration.between(departure, it.departure!!).abs() <= Duration.ofMinutes(WINDOW_MINUTES) }
            .filter { clearlyBetter(it.stop!!, stop) && clearlyMoreReliable(it.stop!!, stop) }
            .minWithOrNull(
                compareBy<AlternativeCandidate> { Duration.between(departure, it.departure!!).abs() }
                    .thenBy { it.departure }
                    .thenByDescending { it.stop!!.onTimeShare }
                    .thenByDescending { it.stop!!.samples }
                    .thenBy { it.key }
            )
            ?.let { ReliableAlternative(it.key, it.departure!!, it.stop!!) }
    }

    /** Enough samples at the destination, a departure time and still to depart. */
    private fun measured(candidate: AlternativeCandidate): Boolean {
        val stop = candidate.stop ?: return false
        return candidate.stillToDepart && candidate.departure != null && !stop.cancelledEveryDay && stop.samples >= NetworkReliability.MIN_SAMPLES
    }

    /** On time in fewer than [MAX_ON_TIME_PERCENT] percent of the samples (exact, in integers). */
    internal fun oftenLate(stop: StopReliability): Boolean = stop.onTime.toLong() * 100 < MAX_ON_TIME_PERCENT.toLong() * stop.samples

    /** [better]'s on-time share is at least [MIN_GAP_POINTS] points above [worse]'s (exact, in integers). */
    internal fun clearlyBetter(better: StopReliability, worse: StopReliability): Boolean {
        val gap = (better.onTime.toLong() * worse.samples - worse.onTime.toLong() * better.samples) * 100
        return gap >= MIN_GAP_POINTS.toLong() * better.samples * worse.samples
    }

    /**
     * [better]'s [StopReliability.score] (runs and is on time, cancelled days included) is at least [MIN_GAP_POINTS]
     * points above [worse]'s, exact in integers: the score is (days − cancelled) × on time ÷ (days × samples), with no
     * cancellation counted when no day is known.
     */
    internal fun clearlyMoreReliable(better: StopReliability, worse: StopReliability): Boolean {
        val (betterRan, betterDays) = ranOf(better)
        val (worseRan, worseDays) = ranOf(worse)
        val betterDenominator = betterDays * better.samples
        val worseDenominator = worseDays * worse.samples
        val gap = (betterRan * better.onTime * worseDenominator - worseRan * worse.onTime * betterDenominator) * 100
        return gap >= MIN_GAP_POINTS.toLong() * betterDenominator * worseDenominator
    }

    /** The days the train ran and the days known, as longs; one day run of one known when no day is. */
    private fun ranOf(stop: StopReliability): Pair<Long, Long> {
        if (stop.daysObserved <= 0) return 1L to 1L
        val cancelled = stop.daysCancelled.coerceIn(0, stop.daysObserved)
        return (stop.daysObserved - cancelled).toLong() to stop.daysObserved.toLong()
    }
}
