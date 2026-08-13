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
    val time: LocalTime? = null
)

/** Interpreta localmente il testo già trascritto dal riconoscitore scelto dall’utente. */
internal object PlannerVoiceParser {
    private val trainPattern = Regex("(?:treno|numero)\\s*(\\d{1,6})")
    private val timePattern = Regex("(?:alle|ore)\\s*(\\d{1,2})(?:\\s*(?:e|:|\\.)\\s*(\\d{1,2}))?")

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
        val date = when {
            "domani" in normalized -> today.plusDays(1)
            "oggi" in normalized -> today
            else -> null
        }
        val trainNumber = trainPattern.find(normalized)?.groupValues?.getOrNull(1)
        return DictatedPlannerInput(
            origin = matches.getOrNull(0),
            destination = matches.getOrNull(1),
            station = matches.singleOrNull(),
            trainNumber = trainNumber,
            date = date,
            time = time
        )
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .replace("[^a-z0-9 ]".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
        .trim()
}
