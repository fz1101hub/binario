package it.binario.app

import it.binario.app.data.Station
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dictation in each of the 17 languages of the app, fed with the sentence the app itself suggests to say
 * («Prova: …» of voice_feedback_unresolved_*). Where the parser does not know the words for a day and a time
 * nothing may be guessed: no route is confirmed and the route microphone is not offered.
 */
class PlannerVoiceLanguagesTest {
    private val termini = Station("roma-termini", "Roma Termini", "Roma")
    private val torino = Station("torino-pn", "Torino Porta Nuova", "Torino", aliases = listOf("Torino PN"))
    private val stations = listOf(termini, torino)
    private val today = LocalDate.of(2026, 11, 11)

    private val routeLanguages = setOf("it", "en", "de", "fr", "es", "pt", "tr", "vi", "id", "in", "ru")
    private val otherScripts = setOf("ar", "hi", "ja", "ko", "my", "ru", "th", "zh")

    /** The language code the configuration reports for a tag of the app ("zh-CN" → "zh"). */
    private fun language(tag: String) = tag.substringBefore('-')

    /** The example of a hint: what follows «Prova:», «例：» or «ဥပမာ-». */
    private fun example(tag: String, key: String): String {
        val hint = TestStringCatalogue.of(AppLocales.qualifier(tag)).getValue(key)
        val mark = requireNotNull(Regex("[:：]\\s*|- ").find(hint)) { "$tag:$key has no example: $hint" }
        return hint.substring(mark.range.last + 1)
    }

    @Test
    fun `the route hint of a language is understood whole, or the route is not dictated in that language`() {
        assertEquals(17, AppLocales.tags.size)
        AppLocales.tags.forEach { tag ->
            val spoken = example(tag, "voice_feedback_unresolved_route")
            assertTrue("$tag: $spoken", spoken.contains("Roma Termini") && spoken.contains("Torino Porta Nuova"))
            val review = PlannerVoiceParser.review(spoken, stations, DictationTarget.ROUTE, today, language(tag))
            if (language(tag) in routeLanguages) {
                assertTrue(tag, PlannerVoiceParser.offers(DictationTarget.ROUTE, language(tag)))
                assertEquals("$tag: $spoken", DictationReviewStatus.CONFIRMED, review.status)
                assertEquals(tag, termini, review.input.origin)
                assertEquals(tag, torino, review.input.destination)
                assertEquals("$tag: $spoken", today.plusDays(1), review.input.date)
                assertEquals("$tag: $spoken", LocalTime.of(18, 0), review.input.time)
            } else {
                // «明日 18 時», «내일 18시»: the day and the hour are not known here.
                assertFalse(tag, PlannerVoiceParser.offers(DictationTarget.ROUTE, language(tag)))
                assertNotEquals("$tag: $spoken", DictationReviewStatus.CONFIRMED, review.status)
                assertFalse(tag, review.canApply)
                assertNull("$tag: $spoken", review.input.date)
                assertNull("$tag: $spoken", review.input.time)
                assertNull("$tag: $spoken", review.input.returnJourney)
            }
        }
    }

    @Test
    fun `the station hint is understood wherever the station microphone is offered`() {
        AppLocales.tags.forEach { tag ->
            val offered = PlannerVoiceParser.offers(DictationTarget.STATION, language(tag))
            // A recogniser writes «Roma Termini» in the script of its language; the timetable has Latin letters.
            assertEquals(tag, language(tag) !in otherScripts, offered)
            if (offered) {
                val spoken = example(tag, "voice_feedback_unresolved_station")
                val review = PlannerVoiceParser.review(spoken, stations, DictationTarget.STATION, today, language(tag))
                assertEquals("$tag: $spoken", DictationReviewStatus.CONFIRMED, review.status)
                assertEquals("$tag: $spoken", termini, review.input.station)
            }
        }
    }

    @Test
    fun `the train hint gives the number in every language`() {
        AppLocales.tags.forEach { tag ->
            assertTrue(tag, PlannerVoiceParser.offers(DictationTarget.TRAIN, language(tag)))
            val spoken = example(tag, "voice_feedback_unresolved_train")
            val review = PlannerVoiceParser.review(spoken, stations, DictationTarget.TRAIN, today, language(tag))
            assertEquals("$tag: $spoken", DictationReviewStatus.CONFIRMED, review.status)
            assertEquals("$tag: $spoken", "9512", review.input.trainNumber)
        }
    }

    @Test
    fun `a train number is read in the digits of any script, and only one number is a train number`() {
        fun number(spoken: String, language: String) =
            PlannerVoiceParser.review(spoken, stations, DictationTarget.TRAIN, today, language)
        assertEquals("9512", number("القطار ٩٥١٢", "ar").input.trainNumber)
        assertEquals("9512", number("ट्रेन ९५१२", "hi").input.trainNumber)
        assertEquals("9512", number("９５１２", "ja").input.trainNumber)
        // Two numbers: which one is the train is not known.
        assertEquals(DictationReviewStatus.UNRESOLVED, number("kereta 9512 pukul 18", "id").status)
        // A number in a route sentence stays a time or a date, never a train.
        assertNull(PlannerVoiceParser.review("Da Roma Termini a Torino Porta Nuova alle 18", stations, DictationTarget.ROUTE, today).input.trainNumber)
    }

    @Test
    fun `a route said in a language without date and time words changes nothing`() {
        // Portuguese at 20:00 once read the stations and «18h» but not «amanhã»: now its words are known.
        val portuguese = PlannerVoiceParser.review(
            "de Roma Termini para Torino Porta Nuova amanhã às 18h", stations, DictationTarget.ROUTE, today, "pt"
        )
        assertEquals(DictationReviewStatus.CONFIRMED, portuguese.status)
        assertEquals(today.plusDays(1), portuguese.input.date)
        assertEquals(LocalTime.of(18, 0), portuguese.input.time)
        // A language the app does not have: no day, no time, no confirmed route, no route microphone.
        listOf("nl", "").forEach { language ->
            val dutch = PlannerVoiceParser.review(
                "van Roma Termini naar Torino Porta Nuova morgen om 18 uur", stations, DictationTarget.ROUTE, today, language
            )
            assertEquals(language, DictationReviewStatus.NEEDS_REVIEW, dutch.status)
            assertNull(language, dutch.input.date)
            assertNull(language, dutch.input.time)
            assertFalse(language, PlannerVoiceParser.offers(DictationTarget.ROUTE, language))
            assertTrue(language, PlannerVoiceParser.offers(DictationTarget.STATION, language))
        }
        // Indonesian is "in" on Android and "id" elsewhere.
        listOf("in", "id").forEach { language -> assertTrue(language, PlannerVoiceParser.offers(DictationTarget.ROUTE, language)) }
        assertTrue(PlannerVoiceParser.offers(DictationTarget.ROUTE, "IT"))
    }
}
