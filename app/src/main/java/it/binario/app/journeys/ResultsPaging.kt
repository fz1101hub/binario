package it.binario.app.journeys

import it.binario.app.data.TrainService
import it.binario.app.data.repository.SourcedJourneyOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** «Più presto» / «Più tardi» of one source of the results: a window being loaded, or the last one found nothing new. */
data class ResultsPageState(val loading: Boolean = false, val nothingNew: Boolean = false) {
    companion object {
        val LOADING = ResultsPageState(loading = true)
    }
}

/**
 * The windows of solutions loaded around a search: «Più tardi» continues from the last departure shown, «Più presto»
 * looks before the first one. Each source (direct trains, journeys with changes) pages from its own first and last
 * departure, so no source leaves a gap; what a page repeats is dropped by identity. Pure, source-independent rules:
 * [later] and [earlier] take the loader of the source.
 */
object ResultsPaging {
    /** «Più presto» looks this far back first, then further while it finds fewer than a page. */
    val EARLIER_SPANS_MINUTES = listOf(60L, 180L, 360L, 720L)
    /** «Più presto» shows at most this many earlier solutions per source, the ones closest to the first shown. */
    const val EARLIER_PAGE = 6
    /** A window before the first departure that fills up before reaching it is loaded this many times at most. */
    const val MAX_FILL_LOADS = 3
    /** A window still too full starts closer to the first departure, but never less than this before it. */
    const val MIN_EARLIER_SPAN_MINUTES = 15L
    /** Journeys with changes asked per window of «Più presto» (the provider's own bound). */
    const val EARLIER_JOURNEY_RESULTS = 20

    /**
     * «Più tardi»: the items of the window from [last] (inclusive: another train may leave at the same minute), or null
     * when the source could not answer. Repeats of the items shown are left to the caller ([newServices], [newOptions]).
     */
    suspend fun <T> later(last: LocalDateTime, departureOf: (T) -> LocalDateTime?, load: suspend (LocalDateTime) -> List<T>?): List<T>? =
        load(last)?.filter { item -> departureOf(item)?.let { !it.isBefore(last) } == true }

    /**
     * «Più presto»: up to [wanted] items leaving just before [first] and not before [notBefore] (the start of the day
     * searched), from a window an hour back, then three, six and twelve while it holds fewer. A source answers from a
     * time onwards with a bounded count, so a window that fills up before reaching [first] is continued from its last
     * item ([MAX_FILL_LOADS] loads); one still too full starts closer to [first] (half the span, down to
     * [MIN_EARLIER_SPAN_MINUTES]), so that what is shown reaches [first] without a gap. Null when the source could not
     * answer; empty when nothing earlier leaves.
     */
    suspend fun <T> earlier(
        first: LocalDateTime,
        departureOf: (T) -> LocalDateTime?,
        notBefore: LocalDateTime? = null,
        wanted: Int = EARLIER_PAGE,
        load: suspend (LocalDateTime) -> List<T>?
    ): List<T>? {
        var answered = false
        var found: List<T> = emptyList()
        for (span in EARLIER_SPANS_MINUTES) {
            val from = first.minusMinutes(span).let { if (notBefore != null && it.isBefore(notBefore)) notBefore else it }
            if (!from.isBefore(first)) break
            val window = window(first, from, departureOf, load) ?: return if (answered) latest(found, wanted, departureOf) else null
            answered = true
            if (window.overflow) return latest(closer(first, span, window.items, departureOf, load), wanted, departureOf)
            found = window.items
            if (found.size >= wanted || from == notBefore) break
        }
        return latest(found, wanted, departureOf)
    }

    private class Window<T>(val items: List<T>, val overflow: Boolean)

    /** The items from [from] to before [first], continued while full; null when the source could not answer at all. */
    private suspend fun <T> window(first: LocalDateTime, from: LocalDateTime, departureOf: (T) -> LocalDateTime?, load: suspend (LocalDateTime) -> List<T>?): Window<T>? {
        val collected = ArrayList<T>()
        var cursor = from
        repeat(MAX_FILL_LOADS) {
            val page = load(cursor) ?: return if (collected.isEmpty()) null else Window(collected, overflow = true)
            val before = page.filter { item -> departureOf(item)?.let { !it.isBefore(from) && it.isBefore(first) } == true }
            collected += before
            val reachedFirst = page.any { item -> departureOf(item)?.let { !it.isBefore(first) } == true }
            val lastBefore = before.mapNotNull(departureOf).maxOrNull()
            if (reachedFirst || lastBefore == null) return Window(collected, overflow = false)
            cursor = lastBefore.plusMinutes(1)
        }
        return Window(collected, overflow = true)
    }

    /** A window too full to reach [first]: windows closer to it until one does; the [crowded] items when none does. */
    private suspend fun <T> closer(first: LocalDateTime, span: Long, crowded: List<T>, departureOf: (T) -> LocalDateTime?, load: suspend (LocalDateTime) -> List<T>?): List<T> {
        var current = span
        while (current > MIN_EARLIER_SPAN_MINUTES) {
            current = maxOf(MIN_EARLIER_SPAN_MINUTES, current / 2)
            val window = window(first, first.minusMinutes(current), departureOf, load) ?: return crowded
            if (!window.overflow) return window.items.ifEmpty { crowded }
        }
        return crowded
    }

    private fun <T> latest(items: List<T>, wanted: Int, departureOf: (T) -> LocalDateTime?): List<T> =
        items.sortedWith(compareBy(nullsFirst()) { item: T -> departureOf(item) }).takeLast(wanted)

    /** The railway-local departure of a direct train: its scheduled date and time, else its time on [serviceDate]. */
    fun departureOf(service: TrainService, serviceDate: LocalDate): LocalDateTime? =
        service.scheduledDepartureDateTime ?: runCatching { LocalDateTime.of(serviceDate, LocalTime.parse(service.scheduledDeparture.trim())) }.getOrNull()

    /**
     * The trains of [page] not already in [shown]: another id, and not the same number leaving at the same minute (a
     * live and a timetable source name the same run differently). Later duplicates within [page] are dropped too.
     */
    fun newServices(shown: List<TrainService>, page: List<TrainService>, serviceDate: LocalDate): List<TrainService> {
        val ids = shown.mapTo(HashSet()) { it.id }
        val runs = shown.mapNotNullTo(HashSet()) { runKey(it, serviceDate) }
        return page.filter { service ->
            val run = runKey(service, serviceDate)
            val fresh = service.id !in ids && (run == null || run !in runs)
            ids += service.id
            run?.let(runs::add)
            fresh
        }
    }

    /** The options of [page] not already in [shown] (same option id, or the same vehicles at the same times). */
    fun newOptions(shown: List<SourcedJourneyOption>, page: List<SourcedJourneyOption>): List<SourcedJourneyOption> {
        val ids = shown.mapTo(HashSet()) { it.option.id.value }
        val trips = shown.mapTo(HashSet(), ::tripKey)
        return page.filter { option ->
            val fresh = option.option.id.value !in ids && tripKey(option) !in trips
            ids += option.option.id.value
            trips += tripKey(option)
            fresh
        }
    }

    private fun runKey(service: TrainService, serviceDate: LocalDate): String? {
        val digits = service.number.filter(Char::isDigit).trimStart('0')
        val departure = departureOf(service, serviceDate) ?: return null
        return (digits.ifEmpty { service.number.trim() }) + "@" + departure
    }

    private fun tripKey(option: SourcedJourneyOption): String =
        option.option.legs.joinToString("|") { "${it.publicNumber}@${it.scheduledDeparture.toInstant()}>${it.scheduledArrival.toInstant()}" }
}
