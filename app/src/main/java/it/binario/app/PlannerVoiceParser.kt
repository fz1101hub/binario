package it.binario.app

import it.binario.app.data.Station
import it.binario.app.data.searchableValues
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime

data class DictatedPlannerInput(
    val origin: Station? = null,
    val destination: Station? = null,
    val station: Station? = null,
    val trainNumber: String? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val returnJourney: Boolean? = null
)

enum class DictationTarget { ROUTE, TRAIN, STATION }

enum class DictationReviewStatus { CONFIRMED, NEEDS_REVIEW, UNRESOLVED }

enum class DictatedStationRole { ORIGIN, DESTINATION, STATION }

data class DictatedStationCandidates(
    val role: DictatedStationRole,
    val candidates: List<Station>
)

data class PlannerVoiceReview(
    val status: DictationReviewStatus,
    val input: DictatedPlannerInput,
    val stationCandidates: List<DictatedStationCandidates>,
    val canApply: Boolean
)

internal fun PlannerVoiceReview.feedbackMessage(target: DictationTarget): String = when (status) {
    DictationReviewStatus.CONFIRMED ->
        "Dati riconosciuti e applicati. Verifica i campi prima di cercare."
    DictationReviewStatus.NEEDS_REVIEW -> {
        val alternatives = stationCandidates
            .filter { it.candidates.size > 1 }
            .joinToString("; ") { group ->
                val role = when (group.role) {
                    DictatedStationRole.ORIGIN -> "partenza"
                    DictatedStationRole.DESTINATION -> "arrivo"
                    DictatedStationRole.STATION -> "stazione"
                }
                "$role: ${group.candidates.take(3).joinToString(" oppure ") { it.name }}"
            }
        if (alternatives.isNotBlank()) {
            "Dettatura ambigua ($alternatives). I campi non sono stati modificati: scegli manualmente."
        } else {
            "Dettatura incompleta. I campi non sono stati modificati: completa o correggi manualmente."
        }
    }
    DictationReviewStatus.UNRESOLVED -> when (target) {
        DictationTarget.ROUTE -> "Non ho riconosciuto una tratta. Prova: da Roma Termini a Torino Porta Nuova domani alle 18."
        DictationTarget.TRAIN -> "Non ho riconosciuto un numero treno. Prova: treno 9512."
        DictationTarget.STATION -> "Non ho riconosciuto una stazione. Prova: stazione Roma Termini."
    }
}

/** Interpreta localmente il testo già trascritto dal riconoscitore scelto dall’utente. */
internal object PlannerVoiceParser {
    private val trainPattern = Regex("(?:treno|numero)\\s*(\\d{1,6})")
    private val timePattern = Regex("(?:alle|ore)\\s*(\\d{1,2})(?:\\s*(?:e|:|\\.)\\s*(\\d{1,2}))?")
    private val numericDatePattern = Regex("(?:il\\s*)?(\\d{1,2})\\s*/\\s*(\\d{1,2})(?:\\s*/\\s*(\\d{4}))?")
    private val namedDatePattern = Regex("(?:il\\s*)?(\\d{1,2})\\s+(gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre)(?:\\s+(\\d{4}))?")
    private val months = mapOf(
        "gennaio" to 1, "febbraio" to 2, "marzo" to 3, "aprile" to 4, "maggio" to 5, "giugno" to 6,
        "luglio" to 7, "agosto" to 8, "settembre" to 9, "ottobre" to 10, "novembre" to 11, "dicembre" to 12
    )

    /**
     * Compatibilità per i chiamanti esistenti: restituisce soltanto stazioni
     * risolte in modo univoco. I nuovi flussi UI devono usare [review].
     */
    fun parse(spoken: String, stations: List<Station>, today: LocalDate = LocalDate.now()): DictatedPlannerInput =
        analyze(spoken, stations, today).input

    /**
     * Produce una decisione fail-closed. Una trascrizione ambigua non viene mai
     * applicata automaticamente: espone i candidati e richiede revisione.
     */
    fun review(
        spoken: String,
        stations: List<Station>,
        target: DictationTarget,
        today: LocalDate = LocalDate.now()
    ): PlannerVoiceReview {
        val analysis = analyze(spoken, stations, today)
        val groups = when (target) {
            DictationTarget.ROUTE -> analysis.mentions.take(2).mapIndexed { index, mention ->
                DictatedStationCandidates(
                    role = if (index == 0) DictatedStationRole.ORIGIN else DictatedStationRole.DESTINATION,
                    candidates = mention.candidates
                )
            }
            DictationTarget.STATION -> analysis.mentions.take(1).map { mention ->
                DictatedStationCandidates(DictatedStationRole.STATION, mention.candidates)
            }
            DictationTarget.TRAIN -> emptyList()
        }
        val hasRelevantValue = when (target) {
            DictationTarget.ROUTE -> analysis.input.origin != null ||
                analysis.input.destination != null ||
                analysis.input.date != null ||
                analysis.input.time != null ||
                analysis.input.returnJourney != null ||
                groups.isNotEmpty()
            DictationTarget.TRAIN -> analysis.input.trainNumber != null
            DictationTarget.STATION -> analysis.input.station != null || groups.isNotEmpty()
        }
        val hasAmbiguousStation = groups.any { it.candidates.size != 1 }
        val isComplete = when (target) {
            DictationTarget.ROUTE -> analysis.input.origin != null && analysis.input.destination != null
            DictationTarget.TRAIN -> analysis.input.trainNumber != null
            DictationTarget.STATION -> analysis.input.station != null
        }
        val status = when {
            !hasRelevantValue -> DictationReviewStatus.UNRESOLVED
            hasAmbiguousStation || !isComplete -> DictationReviewStatus.NEEDS_REVIEW
            else -> DictationReviewStatus.CONFIRMED
        }
        return PlannerVoiceReview(
            status = status,
            input = analysis.input,
            stationCandidates = groups,
            canApply = status == DictationReviewStatus.CONFIRMED
        )
    }

    private fun analyze(spoken: String, stations: List<Station>, today: LocalDate): Analysis {
        val normalized = normalize(spoken)
        val mentions = findStationMentions(normalized, stations)
        val uniqueStations = mentions.map { mention -> mention.candidates.singleOrNull() }
        val time = timePattern.find(normalized)?.let { result ->
            val hour = result.groupValues[1].toIntOrNull()
            val minute = result.groupValues[2].toIntOrNull() ?: 0
            if (hour != null && hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
        }
        val date = parseDate(normalized, today)
        val trainNumber = trainPattern.find(normalized)?.groupValues?.getOrNull(1)
        val returnJourney = when {
            "andata e ritorno" in normalized || "andata ritorno" in normalized -> true
            "sola andata" in normalized -> false
            else -> null
        }
        return Analysis(
            input = DictatedPlannerInput(
                origin = uniqueStations.getOrNull(0),
                destination = uniqueStations.getOrNull(1),
                station = if (mentions.size == 1) uniqueStations.singleOrNull() else null,
                trainNumber = trainNumber,
                date = date,
                time = time,
                returnJourney = returnJourney
            ),
            mentions = mentions
        )
    }

    private fun findStationMentions(normalized: String, stations: List<Station>): List<StationMention> {
        if (normalized.isBlank()) return emptyList()
        val raw = buildList {
            stations.forEach { station ->
                station.searchableValues()
                    .asSequence()
                    .map(::normalize)
                    .filter { it.length >= MIN_STATION_TERM_LENGTH }
                    .distinct()
                    .forEach { term ->
                        Regex("(?:^|\\s)(${Regex.escape(term)})(?=\\s|$)").findAll(normalized).forEach { match ->
                            val range = match.groups[1]?.range ?: return@forEach
                            add(RawStationMatch(range, station))
                        }
                    }
            }
        }
        val maximal = raw.filterNot { candidate ->
            raw.any { other ->
                other.range != candidate.range &&
                    other.range.first <= candidate.range.first &&
                    other.range.last >= candidate.range.last &&
                    rangeLength(other.range) > rangeLength(candidate.range)
            }
        }
        return maximal
            .groupBy { it.range }
            .map { (range, matches) ->
                StationMention(range, matches.map { it.station }.distinctBy { it.id }.sortedBy { it.name })
            }
            .sortedBy { it.range.first }
    }

    private fun parseDate(normalized: String, today: LocalDate): LocalDate? = when {
        "domani" in normalized -> today.plusDays(1)
        "oggi" in normalized -> today
        else -> namedDatePattern.find(normalized)?.let { match ->
            parseCalendarDate(match.groupValues[1], months[match.groupValues[2]], match.groupValues[3], today)
        } ?: numericDatePattern.find(normalized)?.let { match ->
            parseCalendarDate(match.groupValues[1], match.groupValues[2].toIntOrNull(), match.groupValues[3], today)
        }
    }

    private fun parseCalendarDate(dayText: String, month: Int?, yearText: String, today: LocalDate): LocalDate? {
        val day = dayText.toIntOrNull() ?: return null
        val chosenYear = yearText.toIntOrNull() ?: today.year
        val candidate = runCatching { LocalDate.of(chosenYear, month ?: return null, day) }.getOrNull() ?: return null
        return if (yearText.isBlank() && candidate.isBefore(today)) candidate.plusYears(1) else candidate
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .replace("[^a-z0-9 ]".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
        .trim()

    private fun rangeLength(range: IntRange): Int = range.last - range.first + 1

    private data class RawStationMatch(val range: IntRange, val station: Station)
    private data class StationMention(val range: IntRange, val candidates: List<Station>)
    private data class Analysis(val input: DictatedPlannerInput, val mentions: List<StationMention>)

    private const val MIN_STATION_TERM_LENGTH = 3
}
