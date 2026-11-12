package it.binario.app

import it.binario.app.data.RailTime
import it.binario.app.data.Station
import it.binario.app.data.searchableValues
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

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
    /**
     * A route whose stations were not found (a recognizer writes «ミラノ», «Милан», the timetable «Milano»): the
     * station fields were not changed, the day and the time said were put in theirs.
     */
    data object DateTimeOnly : PlannerVoiceFeedback
    data class Unresolved(val target: DictationTarget) : PlannerVoiceFeedback
}

internal fun PlannerVoiceReview.feedback(target: DictationTarget): PlannerVoiceFeedback = when (status) {
    DictationReviewStatus.CONFIRMED -> PlannerVoiceFeedback.Confirmed
    DictationReviewStatus.NEEDS_REVIEW -> {
        val alternatives = stationCandidates
            .filter { it.candidates.size > 1 }
            .map { group -> group.role to group.candidates.take(3).map { it.name } }
        when {
            alternatives.isNotEmpty() -> PlannerVoiceFeedback.Ambiguous(alternatives)
            target == DictationTarget.ROUTE && (input.date != null || input.time != null) -> PlannerVoiceFeedback.DateTimeOnly
            else -> PlannerVoiceFeedback.Incomplete
        }
    }
    DictationReviewStatus.UNRESOLVED -> PlannerVoiceFeedback.Unresolved(target)
}

/** Interpreta localmente il testo già trascritto dal riconoscitore scelto dall’utente. */
internal object PlannerVoiceParser {
    // Languages of the app written in another script: a recognizer writes the station names in that script too,
    // and the timetable has them in Latin letters.
    private val otherScriptLanguages = setOf("ar", "hi", "ja", "ko", "my", "ru", "th", "zh")
    private val trainPattern = Regex(
        "(?:^|\\s)(?:treno|numero|train|number|no|zug|nummer|nr|tren|frecciarossa|frecciargento|frecciabianca|" +
            "italo|intercity|eurocity|ic|ec|ice|tgv|railjet|rj)\\s+(\\d{1,6})(?=\\s|$)"
    )
    private val bareTrainNumberPattern = Regex("^(\\d{1,6})$")
    private val numberPattern = Regex("\\d+")
    private val clockTimePattern = Regex("(?<![\\d/])(\\d{1,2})\\s*:\\s*(\\d{2})(?!\\d)")
    private val numericDatePattern = Regex("(?<![\\d:])(\\d{1,2})\\s*/\\s*(\\d{1,2})(?:\\s*/\\s*(\\d{4}))?(?![\\d:])")

    /**
     * Whether the microphone of [target] is offered when the app speaks [language] (the language code of its
     * locale). A train number is digits everywhere. A station is matched by its name in Latin letters, which a
     * recognizer of another script does not write («ミラノ», «Милан»). A route also carries a day and a time: it
     * is offered in every language whose words for them are known ([VoiceGrammars]); where the stations are not
     * found, the day and the time are still offered ([PlannerVoiceFeedback.DateTimeOnly]).
     */
    fun offers(target: DictationTarget, language: String): Boolean = when (target) {
        DictationTarget.TRAIN -> true
        DictationTarget.STATION -> language.lowercase(Locale.ROOT) !in otherScriptLanguages
        DictationTarget.ROUTE -> knowsDateTimeWords(language)
    }

    private fun knowsDateTimeWords(language: String): Boolean = VoiceGrammars.forLanguage(language) != null

    /** Today in Italy, whatever the zone of the phone: the timetable and the planner count days in Italy. */
    fun italianToday(clock: Clock = Clock.systemUTC()): LocalDate = LocalDate.now(clock.withZone(RailTime.defaultZone))

    /**
     * Compatibilità per i chiamanti esistenti: restituisce soltanto stazioni
     * risolte in modo univoco. I nuovi flussi UI devono usare [review].
     */
    fun parse(spoken: String, stations: List<Station>, today: LocalDate = italianToday()): DictatedPlannerInput =
        analyze(spoken, stations, today, VoiceGrammars.default).input

    /**
     * Produce una decisione fail-closed. Una trascrizione ambigua non viene mai
     * applicata automaticamente: espone i candidati e richiede revisione.
     * [language] è la lingua chiesta al riconoscitore e sceglie le parole per giorno, ora e ritorno: dove non
     * sono note non si legge né l’uno né l’altra e una tratta non è mai confermata.
     */
    fun review(
        spoken: String,
        stations: List<Station>,
        target: DictationTarget,
        today: LocalDate = italianToday(),
        /** The language code of the locale the recognizer was asked for; the screen always passes it. */
        language: String = "it"
    ): PlannerVoiceReview {
        val grammar = VoiceGrammars.forLanguage(language)
        val analysis = analyze(spoken, stations, today, grammar).let { read ->
            // The train field takes a number and nothing else: the only number said is the train, whatever the
            // language calls a train («trem 9512», «kereta 9512»).
            if (target == DictationTarget.TRAIN && read.input.trainNumber == null) {
                read.copy(input = read.input.copy(trainNumber = onlyNumber(spoken)))
            } else read
        }
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
            // Two stations and words this parser cannot read: the day and the hour said with them are unknown.
            target == DictationTarget.ROUTE && grammar == null -> DictationReviewStatus.NEEDS_REVIEW
            else -> DictationReviewStatus.CONFIRMED
        }
        return PlannerVoiceReview(
            status = status,
            input = analysis.input,
            stationCandidates = groups,
            canApply = status == DictationReviewStatus.CONFIRMED
        )
    }

    private fun analyze(spoken: String, stations: List<Station>, today: LocalDate, grammar: VoiceGrammar?): Analysis {
        // Station names are compared without accents in every language: the catalogue writes them in Latin letters.
        val stationText = VoiceText.normalize(spoken)
        val mentions = inOrderOfTravel(findStationMentions(stationText, stations, grammar), stationText, grammar)
        val uniqueStations = mentions.map { mention -> mention.candidates.singleOrNull() }
        // Without the words of the language a number is not a time and a day is not said: nothing is guessed.
        val words = grammar?.text(spoken).orEmpty()
        val raw = VoiceText.raw(spoken)
        val time = grammar?.let { parseTime(words, raw, it) }
        val date = grammar?.let { parseDate(words, raw, today, it) }
        val trainNumber = trainPattern.find(stationText)?.groupValues?.getOrNull(1)
            ?: bareTrainNumberPattern.find(stationText)?.groupValues?.getOrNull(1)
        val returnJourney = when {
            grammar == null -> null
            grammar.hasAny(words, grammar.returnTrip) -> true
            grammar.hasAny(words, grammar.oneWay) -> false
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

    private fun findStationMentions(normalized: String, stations: List<Station>, grammar: VoiceGrammar?): List<StationMention> {
        if (normalized.isBlank()) return emptyList()
        // Turkish writes the case onto the name («Romadan», «Roma'ya»): the name before the ending is the station.
        val ending = grammar?.stationEnding?.let { "(?:$it)?" }.orEmpty()
        val raw = buildList {
            stations.forEach { station ->
                station.searchableValues()
                    .asSequence()
                    .map { VoiceText.normalize(it) }
                    .filter { it.length >= MIN_STATION_TERM_LENGTH }
                    .distinct()
                    .forEach { term ->
                        Regex("(?:^|\\s)(${Regex.escape(term)})$ending(?=\\s|$)").findAll(normalized).forEach { match ->
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

    /**
     * The first station said is the origin, unless the words around the two names say the opposite («to Milano
     * from Roma», «Milano まで Roma から»). A name with no such word, or with contradicting ones, keeps its place.
     */
    private fun inOrderOfTravel(mentions: List<StationMention>, stationText: String, grammar: VoiceGrammar?): List<StationMention> {
        if (grammar == null || mentions.size != 2) return mentions
        val first = grammar.role(stationText, mentions[0].range)
        val second = grammar.role(stationText, mentions[1].range)
        val reversed = (first == StationRole.TO || second == StationRole.FROM) && first != StationRole.FROM && second != StationRole.TO
        return if (reversed) mentions.reversed() else mentions
    }

    private fun parseTime(words: String, raw: String, grammar: VoiceGrammar): LocalTime? {
        val afternoon = grammar.saysAfternoon(words)
        for (pattern in grammar.times) {
            val match = pattern.find(words) ?: continue
            val hour = match.groups[1] ?: continue
            val minutes = match.groupValues.getOrElse(2) { "" }
            // «alle 8 meno un quarto», «alle 8 e tre quarti»: an hour followed by words not understood is no time.
            if (grammar.continues(words.substring(match.range.last + 1), minutesSaid = minutes.isNotEmpty())) return null
            val shift = grammar.modifierBefore(words, hour.range.first)
            if (shift != null && minutes.isNotEmpty()) return null
            val time = clockTime(hour.value, minutes, match.groupValues.getOrElse(3) { "" }, afternoon, grammar.minuteWords)
            return time?.plusMinutes(shift?.toLong() ?: 0L)
        }
        return clockTimePattern.find(raw)?.let { match -> clockTime(match.groupValues[1], match.groupValues[2], "", afternoon, emptyMap()) }
    }

    private fun clockTime(
        hourText: String,
        minuteText: String,
        meridiem: String,
        afternoon: Boolean,
        minuteWords: Map<String, Int>
    ): LocalTime? {
        var hour = hourText.toIntOrNull() ?: return null
        val minute = if (minuteText.isEmpty()) 0 else minuteWords[minuteText] ?: minuteText.toIntOrNull() ?: return null
        when (meridiem.replace(" ", "")) {
            "pm" -> if (hour in 1..11) hour += 12 else if (hour != 12) return null
            "am" -> if (hour == 12) hour = 0 else if (hour !in 1..11) return null
            // "alle 8 di sera" is 20:00; "alle 18 di sera" is already a 24-hour time.
            else -> if (afternoon && hour in 1..11) hour += 12
        }
        return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
    }

    private fun parseDate(words: String, raw: String, today: LocalDate, grammar: VoiceGrammar): LocalDate? {
        val relative = grammar.withoutNotDays(words)
        return when {
            grammar.hasAny(relative, grammar.dayAfterTomorrow) -> today.plusDays(2)
            grammar.hasAny(relative, grammar.tomorrow) -> today.plusDays(1)
            grammar.hasAny(words, grammar.today) -> today
            else -> grammar.dateRules().firstNotNullOfOrNull { rule ->
                rule.regex.find(words)?.let { match ->
                    val (first, second) = match.groupValues[1] to match.groupValues[2]
                    val (dayText, monthText) = if (rule.monthFirst) second to first else first to second
                    parseCalendarDate(dayText, grammar.month(monthText), match.groupValues.getOrElse(3) { "" }, today, grammar)
                }
            } ?: numericDatePattern.find(raw)?.takeIf { grammar.readsSlashDates }?.let { match ->
                parseCalendarDate(match.groupValues[1], match.groupValues[2].toIntOrNull(), match.groupValues[3], today, grammar)
            }
        }
    }

    private fun parseCalendarDate(dayText: String, month: Int?, yearText: String, today: LocalDate, grammar: VoiceGrammar): LocalDate? {
        val day = dayText.toIntOrNull() ?: return null
        val chosenYear = grammar.year(yearText) ?: today.year
        val candidate = runCatching { LocalDate.of(chosenYear, month ?: return null, day) }.getOrNull() ?: return null
        return if (yearText.isBlank() && candidate.isBefore(today)) candidate.plusYears(1) else candidate
    }

    /** The number of a sentence that has exactly one, of at most six digits. */
    private fun onlyNumber(spoken: String): String? =
        numberPattern.findAll(VoiceText.asciiDigits(spoken)).map { it.value }.singleOrNull()?.takeIf { it.length <= 6 }

    private fun rangeLength(range: IntRange): Int = range.last - range.first + 1

    private data class RawStationMatch(val range: IntRange, val station: Station)
    private data class StationMention(val range: IntRange, val candidates: List<Station>)
    private data class Analysis(val input: DictatedPlannerInput, val mentions: List<StationMention>)

    private const val MIN_STATION_TERM_LENGTH = 3
}
