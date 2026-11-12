package it.binario.app

import it.binario.app.data.Station
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dictation sentences of the frozen companion app (its tests and the corrections of its audit) and the parser's
 * own cases, read by the Android parser in the language the recognizer was asked for. They live in
 * contracts/voice-input/v1/dictation-cases.json, which the iPhone's RouteDictationTests read too: both phones must
 * read every sentence alike.
 */
class PlannerVoiceDictationFormsTest {
    private val contract: JSONObject by lazy {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        JSONObject(File(root, "contracts/voice-input/v1/dictation-cases.json").readText())
    }

    /** The catalogue of a case: the contract's aliases are what the tests used to give as the city. */
    private val stations: Map<String, Station> by lazy {
        contract.getJSONArray("stations").objects().associate { station ->
            val id = station.getString("id")
            val name = station.getString("name")
            id to Station(id, name, city = name, aliases = station.getJSONArray("aliases").strings())
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    @Test
    fun `the contract covers every language of the app with the companion sentences`() {
        assertEquals(1, contract.getInt("schemaVersion"))
        assertEquals("binario-voice-input-cases", contract.getString("kind"))
        val cases = contract.getJSONArray("cases").objects()
        val confirmed = cases
            .filter { it.getString("name").startsWith("companion ") }
            .map { it.getString("language") }
            .toSet()
        // "in" is Android's code for Indonesian, "id" the iPhone's: both are read.
        val appLanguages = setOf("it", "en", "de", "fr", "es", "ar", "hi", "in", "id", "ja", "ko", "my", "pt", "ru", "th", "tr", "vi", "zh")
        assertEquals(appLanguages, confirmed)
        assertTrue(cases.size >= 250)
    }

    @Test
    fun `text is compared in every script with ASCII digits`() {
        val failures = contract.getJSONArray("normalization").objects().mapNotNull { case ->
            val text = case.getString("text")
            val actual = if (case.optBoolean("joinNativeWords", false)) {
                VoiceText.joinNativeWords(text)
            } else {
                VoiceText.normalize(text, foldLatinMarks = case.optBoolean("foldLatinMarks", true))
            }
            val expected = case.getString("expected")
            if (actual == expected) null else "«$text»: expected «$expected», got «$actual»"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `the microphones offered in each language`() {
        contract.getJSONArray("offers").objects().forEach { offer ->
            val language = offer.getString("language")
            assertEquals(language, offer.getBoolean("route"), PlannerVoiceParser.offers(DictationTarget.ROUTE, language))
            assertEquals(language, offer.getBoolean("station"), PlannerVoiceParser.offers(DictationTarget.STATION, language))
            assertEquals(language, offer.getBoolean("train"), PlannerVoiceParser.offers(DictationTarget.TRAIN, language))
        }
    }

    @Test
    fun `every shared sentence is read as the contract says`() {
        val sets = contract.getJSONObject("stationSets")
        val failures = contract.getJSONArray("cases").objects().flatMap { case ->
            val name = case.getString("name")
            val catalogue = sets.getJSONArray(case.getString("stations")).strings().map { stations.getValue(it) }
            val target = DictationTarget.valueOf(case.getString("target").uppercase(Locale.ROOT))
            val expect = case.getJSONObject("expect")
            val failures = mutableListOf<String>()
            val today = if (case.has("now")) {
                val clock = Clock.fixed(Instant.parse(case.getString("now")), ZoneId.of(case.getString("deviceZone")))
                PlannerVoiceParser.italianToday(clock).also { italian ->
                    if (expect.has("italianToday") && expect.getString("italianToday") != italian.toString()) {
                        failures += "$name: italianToday expected ${expect.getString("italianToday")}, got $italian"
                    }
                }
            } else {
                LocalDate.parse(case.getString("today"))
            }
            val review = PlannerVoiceParser.review(case.getString("spoken"), catalogue, target, today, case.getString("language"))
            val input = review.input
            fun check(key: String, actual: Any?) {
                if (!expect.has(key)) return
                val expected = if (expect.isNull(key)) null else expect.get(key)
                if (expected != actual) failures += "$name: $key expected $expected, got $actual"
            }
            check("status", review.status.contractName())
            check("origin", input.origin?.id)
            check("destination", input.destination?.id)
            check("station", input.station?.id)
            check("trainNumber", input.trainNumber)
            check("date", input.date?.toString())
            check("time", input.time?.toString())
            check("returnJourney", input.returnJourney)
            val feedback = review.feedback(target)
            check("feedback", feedback.contractName())
            if (expect.has("candidates")) {
                val expected = expect.getJSONArray("candidates").let { groups -> (0 until groups.length()).map { groups.getJSONArray(it).strings() } }
                val actual = review.stationCandidates.map { group -> group.candidates.map { it.id } }
                if (expected != actual) failures += "$name: candidates expected $expected, got $actual"
            }
            if (expect.has("alternatives")) {
                val expected = expect.getJSONArray("alternatives").objects().map { it.getString("role") to it.getJSONArray("names").strings() }
                val actual = (feedback as? PlannerVoiceFeedback.Ambiguous)?.alternatives
                    ?.map { (role, names) -> role.name.lowercase(Locale.ROOT) to names }
                if (expected != actual) failures += "$name: alternatives expected $expected, got $actual"
            }
            failures
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun DictationReviewStatus.contractName(): String = when (this) {
        DictationReviewStatus.CONFIRMED -> "confirmed"
        DictationReviewStatus.NEEDS_REVIEW -> "needsReview"
        DictationReviewStatus.UNRESOLVED -> "unresolved"
    }

    private fun PlannerVoiceFeedback.contractName(): String = when (this) {
        PlannerVoiceFeedback.Confirmed -> "confirmed"
        is PlannerVoiceFeedback.Ambiguous -> "ambiguous"
        PlannerVoiceFeedback.Incomplete -> "incomplete"
        PlannerVoiceFeedback.DateTimeOnly -> "dateTimeOnly"
        is PlannerVoiceFeedback.Unresolved -> "unresolved"
    }
}
