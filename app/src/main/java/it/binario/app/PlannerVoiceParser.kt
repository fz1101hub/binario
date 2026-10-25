package it.binario.app

import it.binario.app.data.RailTime
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

/** What to tell the user about a dictation, independent of the display language. */
sealed interface PlannerVoiceFeedback {
    data object Confirmed : PlannerVoiceFeedback
    /** Station names the user must choose between, by role; the fields were not changed. */
    data class Ambiguous(val alternatives: List<Pair<DictatedStationRole, List<String>>>) : PlannerVoiceFeedback
    data object Incomplete : PlannerVoiceFeedback
    data class Unresolved(val target: DictationTarget) : PlannerVoiceFeedback
}

internal fun PlannerVoiceReview.feedback(target: DictationTarget): PlannerVoiceFeedback = when (status) {
    DictationReviewStatus.CONFIRMED -> PlannerVoiceFeedback.Confirmed
    DictationReviewStatus.NEEDS_REVIEW -> {
        val alternatives = stationCandidates
            .filter { it.candidates.size > 1 }
            .map { group -> group.role to group.candidates.take(3).map { it.name } }
        if (alternatives.isNotEmpty()) PlannerVoiceFeedback.Ambiguous(alternatives) else PlannerVoiceFeedback.Incomplete
    }
    DictationReviewStatus.UNRESOLVED -> PlannerVoiceFeedback.Unresolved(target)
}

/** Interpreta localmente il testo già trascritto dal riconoscitore scelto dall’utente. */
internal object PlannerVoiceParser {
    // The system recognizer transcribes in the display language, so every app language is
    // understood: Italian, English, German, French and Spanish.
    private val trainPattern = Regex(
        "(?:^|\\s)(?:treno|numero|train|number|no|zug|nummer|nr|tren|frecciarossa|frecciargento|frecciabianca|" +
            "italo|intercity|eurocity|ic|ec|ice|tgv|railjet|rj)\\s+(\\d{1,6})(?=\\s|$)"
    )
    private val bareTrainNumberPattern = Regex("^(\\d{1,6})$")
    private const val MINUTES = "(\\d{2}|mezza|mezzo|media|demie|un quarto|quarto|cuarto|quart)"
    private const val MERIDIEM = "(?:\\s*(am|a m|pm|p m))?"
    private val keywordTimePattern = Regex(
        "(?:^|\\s)(?:alle|ore|per le|at|around|um|gegen|vers|a las|a la|sobre las|hacia las|a)\\s+(\\d{1,2})" +
            "(?:\\s*(?:uhr|heures|heure|horas|hours|h|o clock))?(?:\\s*(?:e|y|and|et)?\\s*$MINUTES)?$MERIDIEM(?=\\s|$)"
    )
    private val unitTimePattern = Regex("(?:^|\\s)(\\d{1,2})\\s*(?:uhr|heures|heure|h)(?:\\s*$MINUTES)?$MERIDIEM(?=\\s|$)")
    private val clockTimePattern = Regex("(?<![\\d/])(\\d{1,2})\\s*:\\s*(\\d{2})(?!\\d)")
    private val numericDatePattern = Regex("(?<![\\d:])(\\d{1,2})\\s*/\\s*(\\d{1,2})(?:\\s*/\\s*(\\d{4}))?(?![\\d:])")
    private val months = mapOf(
        "gennaio" to 1, "febbraio" to 2, "marzo" to 3, "aprile" to 4, "maggio" to 5, "giugno" to 6,
        "luglio" to 7, "agosto" to 8, "settembre" to 9, "ottobre" to 10, "novembre" to 11, "dicembre" to 12,
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6,
        "july" to 7, "august" to 8, "september" to 9, "october" to 10, "november" to 11, "december" to 12,
        "januar" to 1, "februar" to 2, "marz" to 3, "mai" to 5, "juni" to 6, "juli" to 7,
        "oktober" to 10, "dezember" to 12,
        "janvier" to 1, "fevrier" to 2, "mars" to 3, "avril" to 4, "juin" to 6, "juillet" to 7,
        "aout" to 8, "septembre" to 9, "octobre" to 10, "novembre" to 11, "decembre" to 12,
        "enero" to 1, "febrero" to 2, "abril" to 4, "mayo" to 5, "junio" to 6, "julio" to 7,
        "septiembre" to 9, "setiembre" to 9, "octubre" to 10, "noviembre" to 11, "diciembre" to 12
    )
    private val monthAlternation = months.keys.sortedByDescending { it.length }.joinToString("|")
    private val dayMonthPattern = Regex(
        "(?:^|\\s)(\\d{1,2})(?:st|nd|rd|th|er|o)?\\s+(?:de\\s+|of\\s+)?($monthAlternation)(?:\\s+(?:de\\s+)?(\\d{4}))?(?=\\s|$)"
    )
    private val monthDayPattern = Regex(
        "(?:^|\\s)($monthAlternation)\\s+(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+(\\d{4}))?(?=\\s|$)"
    )
    private val dayAfterTomorrowWords = listOf("dopodomani", "day after tomorrow", "ubermorgen", "apres demain", "pasado manana")
    private val tomorrowWords = listOf("domani", "tomorrow", "morgen", "demain", "manana")
    private val todayWords = listOf("oggi", "stasera", "stamattina", "today", "tonight", "heute", "aujourd hui", "ce soir", "hoy")
    // Time-of-day phrases that contain a "tomorrow" word: "heute morgen" is this morning,
    // "por la mañana" in the morning.
    private val morningPhrases = Regex("\\b(?:heute morgen|por la manana|de la manana)\\b")
    // "alle 8 di sera", "stasera alle 9", "at 8 tonight": an hour said with an afternoon or
    // evening phrase and no am/pm is a 12-hour time of the second half of the day.
    private val afternoonPhrases = Regex(
        "(?:^|\\s)(?:di sera|della sera|la sera|stasera|di pomeriggio|del pomeriggio|nel pomeriggio|il pomeriggio|" +
            "di notte|stanotte|in the evening|this evening|tonight|in the afternoon|this afternoon|at night|" +
            "abends|am abend|heute abend|nachmittags|am nachmittag|heute nachmittag|nachts|" +
            "du soir|le soir|ce soir|de l apres midi|l apres midi|cet apres midi|" +
            "de la tarde|por la tarde|esta tarde|de la noche|por la noche|esta noche)(?=\\s|$)"
    )
    private val returnWords = listOf(
        "andata e ritorno", "andata ritorno", "return trip", "round trip", "return journey", "and back",
        "hin und zuruck", "hin und ruckfahrt", "hin und retour", "aller retour", "ida y vuelta"
    )
    private val oneWayWords = listOf(
        "sola andata", "solo andata", "one way", "single journey", "einfache fahrt", "nur hinfahrt",
        "aller simple", "solo ida"
    )

    /**
     * Compatibilità per i chiamanti esistenti: restituisce soltanto stazioni
     * risolte in modo univoco. I nuovi flussi UI devono usare [review].
     */
    fun parse(spoken: String, stations: List<Station>, today: LocalDate = LocalDate.now(RailTime.defaultZone)): DictatedPlannerInput =
        analyze(spoken, stations, today).input

    /**
     * Produce una decisione fail-closed. Una trascrizione ambigua non viene mai
     * applicata automaticamente: espone i candidati e richiede revisione.
     */
    fun review(
        spoken: String,
        stations: List<Station>,
        target: DictationTarget,
        today: LocalDate = LocalDate.now(RailTime.defaultZone)
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
        val time = parseTime(normalized, spoken.lowercase())
        val date = parseDate(normalized, spoken.lowercase(), today)
        val trainNumber = trainPattern.find(normalized)?.groupValues?.getOrNull(1)
            ?: bareTrainNumberPattern.find(normalized)?.groupValues?.getOrNull(1)
        val returnJourney = when {
            returnWords.any { containsWords(normalized, it) } -> true
            oneWayWords.any { containsWords(normalized, it) } -> false
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

    private fun parseTime(normalized: String, raw: String): LocalTime? {
        val afternoon = afternoonPhrases.containsMatchIn(normalized)
        (keywordTimePattern.find(normalized) ?: unitTimePattern.find(normalized))?.let { match ->
            return clockTime(match.groupValues[1], match.groupValues[2], match.groupValues[3], afternoon)
        }
        return clockTimePattern.find(raw)?.let { match -> clockTime(match.groupValues[1], match.groupValues[2], "", afternoon) }
    }

    private fun clockTime(hourText: String, minuteText: String, meridiem: String, afternoon: Boolean = false): LocalTime? {
        var hour = hourText.toIntOrNull() ?: return null
        val minute = when (minuteText) {
            "" -> 0
            "mezza", "mezzo", "media", "demie" -> 30
            "un quarto", "quarto", "cuarto", "quart" -> 15
            else -> minuteText.toIntOrNull() ?: return null
        }
        when (meridiem.replace(" ", "")) {
            "pm" -> if (hour in 1..11) hour += 12 else if (hour != 12) return null
            "am" -> if (hour == 12) hour = 0 else if (hour !in 1..11) return null
            // "alle 8 di sera" is 20:00; "alle 18 di sera" is already a 24-hour time.
            else -> if (afternoon && hour in 1..11) hour += 12
        }
        return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
    }

    private fun parseDate(normalized: String, raw: String, today: LocalDate): LocalDate? {
        val relative = normalized.replace(morningPhrases, " ")
        return when {
            dayAfterTomorrowWords.any { containsWords(relative, it) } -> today.plusDays(2)
            tomorrowWords.any { containsWords(relative, it) } -> today.plusDays(1)
            todayWords.any { containsWords(normalized, it) } -> today
            else -> dayMonthPattern.find(normalized)?.let { match ->
                parseCalendarDate(match.groupValues[1], months[match.groupValues[2]], match.groupValues[3], today)
            } ?: monthDayPattern.find(normalized)?.let { match ->
                parseCalendarDate(match.groupValues[2], months[match.groupValues[1]], match.groupValues[3], today)
            } ?: numericDatePattern.find(raw)?.let { match ->
                parseCalendarDate(match.groupValues[1], match.groupValues[2].toIntOrNull(), match.groupValues[3], today)
            }
        }
    }

    /** Whole-word match: "oggi" must not fire inside "Poggibonsi", nor "domani" inside "dopodomani". */
    private fun containsWords(normalized: String, words: String): Boolean =
        Regex("(?:^|\\s)${Regex.escape(words)}(?=\\s|$)").containsMatchIn(normalized)

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
