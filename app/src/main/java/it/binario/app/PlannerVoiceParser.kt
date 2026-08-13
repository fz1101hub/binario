package it.binario.app

import it.binario.app.data.Station
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

    fun parse(spoken: String, stations: List<Station>, today: LocalDate = LocalDate.now()): DictatedPlannerInput {
        val normalized = normalize(spoken)
        val matches = stations.mapNotNull { station ->
            val index = Regex("\\b${Regex.escape(normalize(station.name))}\\b").find(normalized)?.range?.first
            index?.let { it to station }
        }.sortedBy { it.first }
            .map { it.second }
            .distinctBy { it.id }

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
        return DictatedPlannerInput(
            origin = matches.getOrNull(0),
            destination = matches.getOrNull(1),
            station = matches.singleOrNull(),
            trainNumber = trainNumber,
            date = date,
            time = time,
            returnJourney = returnJourney
        )
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
}
