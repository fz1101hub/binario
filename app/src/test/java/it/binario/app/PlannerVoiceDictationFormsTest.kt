package it.binario.app

import it.binario.app.data.Station
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The dictation sentences of the frozen companion app (its tests and the corrections of its audit), read by the
 * Android parser in the language the recognizer was asked for.
 */
class PlannerVoiceDictationFormsTest {
    private val ostiense = Station("roma-ostiense", "Roma Ostiense", "Roma")
    private val sanPietro = Station("roma-san-pietro", "Roma San Pietro", "Roma")
    private val termini = Station("roma-termini", "Roma Termini", "Roma")
    private val novella = Station("firenze-smn", "Firenze Santa Maria Novella", "Firenze")
    private val centrale = Station("milano-centrale", "Milano Centrale", "Milano")
    private val stations = listOf(ostiense, sanPietro, termini, novella, centrale)
    private val today = LocalDate.of(2030, 1, 2)

    private fun route(spoken: String, language: String, day: LocalDate = today) =
        PlannerVoiceParser.review(spoken, stations, DictationTarget.ROUTE, day, language)

    private fun time(spoken: String, language: String): LocalTime? = route(spoken, language).input.time

    private fun assertOstienseToSanPietro(spoken: String, language: String, at: LocalTime = LocalTime.of(9, 35)) {
        val review = route(spoken, language)
        assertEquals("$language: $spoken", DictationReviewStatus.CONFIRMED, review.status)
        assertEquals("$language: $spoken", ostiense, review.input.origin)
        assertEquals("$language: $spoken", sanPietro, review.input.destination)
        assertEquals("$language: $spoken", today.plusDays(1), review.input.date)
        assertEquals("$language: $spoken", at, review.input.time)
    }

    private val westernPhrases = mapOf(
        "it" to "Da Roma Ostiense a Roma San Pietro domani alle {h}:{m}",
        "en" to "From Roma Ostiense to Roma San Pietro tomorrow at {h}:{m}",
        "de" to "Von Roma Ostiense nach Roma San Pietro morgen um {h}:{m}",
        "fr" to "De Roma Ostiense à Roma San Pietro demain à {h}:{m}",
        "es" to "De Roma Ostiense a Roma San Pietro mañana a las {h}:{m}"
    )

    private val eveningTimes = listOf("20" to "15", "21" to "40", "22" to "05", "23" to "59", "20" to "00")

    private fun assertTemplates(templates: Map<String, String>) = templates.forEach { (language, template) ->
        assertOstienseToSanPietro(template.replace("{h}", "9").replace("{m}", "35"), language)
        eveningTimes.forEach { (hour, minute) ->
            val spoken = template.replace("{h}", hour).replace("{m}", minute)
            assertOstienseToSanPietro(spoken, language, LocalTime.of(hour.toInt(), minute.toInt()))
        }
    }

    @Test
    fun `the five first languages read the companion sentences and the evening hours`() {
        assertTemplates(westernPhrases)
    }

    @Test
    fun `edge times are a time or nothing, never a shortened hour`() {
        assertEquals(LocalTime.of(21, 0), time("Da Roma Termini a Milano Centrale domani alle 21", "it"))
        assertNull(time("Da Roma Termini a Milano Centrale domani alle 24:00", "it"))
        assertNull(time("Von Roma Termini nach Milano Centrale morgen um 25", "de"))
        assertEquals(LocalTime.of(20, 15), time("De Roma Termini à Milano Centrale demain à 20h15", "fr"))
    }

    @Test
    fun `English am and pm`() {
        fun at(said: String) = time("From Roma Termini to Milano Centrale tomorrow at $said", "en")
        assertEquals(LocalTime.of(20, 0), at("8pm"))
        assertEquals(LocalTime.of(20, 0), at("8 pm"))
        assertEquals(LocalTime.of(20, 30), at("8:30 p.m."))
        assertEquals(LocalTime.of(23, 59), at("11:59 PM"))
        assertEquals(LocalTime.of(12, 0), at("12pm"))
        assertEquals(LocalTime.of(0, 0), at("12am"))
        assertEquals(LocalTime.of(8, 0), at("8am"))
        assertEquals(LocalTime.of(8, 0), at("8"))
        assertNull(at("13pm"))
        val noDay = route("From Roma Termini to Milano Centrale at 8pm", "en")
        assertEquals(termini, noDay.input.origin)
        assertEquals(centrale, noDay.input.destination)
        assertNull(noDay.input.date)
        assertEquals(LocalTime.of(20, 0), noDay.input.time)
    }

    @Test
    fun `the forms the companion app misread give the right time`() {
        val prefix = "Da Roma Termini a Milano Centrale domani"
        assertEquals(LocalTime.of(7, 45), time("$prefix alle 7.45", "it"))
        assertEquals(LocalTime.of(8, 30), time("$prefix alle 8 e mezza", "it"))
        assertEquals(LocalTime.of(20, 30), time("From Roma Termini to Milano Centrale at 8.30pm", "en"))
        assertEquals(LocalTime.of(8, 30), time("Von Roma Termini nach Milano Centrale um 8.30", "de"))
        assertEquals(LocalTime.of(20, 15), time("Von Roma Termini nach Milano Centrale um 20 Uhr 15", "de"))
        assertEquals(LocalTime.of(20, 15), time("De Roma Termini à Milano Centrale à 20 h 15", "fr"))
        assertEquals(LocalTime.of(9, 0), time("$prefix alle ore 9", "it"))
        val dated = route("Da Roma Termini a Milano Centrale il 12/11 alle 9:30", "it", LocalDate.of(2026, 11, 11))
        assertEquals(LocalDate.of(2026, 11, 12), dated.input.date)
        assertEquals(LocalTime.of(9, 30), dated.input.time)
    }

    @Test
    fun `an hour followed by words the grammar does not understand leaves the time empty`() {
        val prefix = "Da Roma Termini a Milano Centrale domani"
        assertNull(time("$prefix alle 8 meno un quarto", "it"))
        assertNull(time("$prefix alle 8 e tre quarti", "it"))
        assertNull(time("$prefix alle 8 e 5", "it"))
        assertNull(time("De Roma Termini a Milano Centrale mañana a las 8 menos cuarto", "es"))
        assertNull(time("De Roma Termini à Milano Centrale demain à 9 h moins le quart", "fr"))
        // The day is still read.
        assertEquals(today.plusDays(1), route("$prefix alle 8 meno un quarto", "it").input.date)
    }

    @Test
    fun `a sentence with no route, day or time applies nothing`() {
        val review = route("ritardo del treno", "it")
        assertEquals(DictationReviewStatus.UNRESOLVED, review.status)
        assertEquals(DictatedPlannerInput(), review.input)
    }

    @Test
    fun `today and tomorrow are days of Italy, whatever the zone of the phone`() {
        val newYork = Clock.fixed(Instant.parse("2026-11-11T23:30:00Z"), ZoneId.of("America/New_York"))
        val italian = PlannerVoiceParser.italianToday(newYork)
        assertEquals(LocalDate.of(2026, 11, 12), italian)
        assertEquals(LocalDate.of(2026, 11, 12), route("Da Roma Termini a Milano Centrale oggi alle 9", "it", italian).input.date)
        assertEquals(LocalDate.of(2026, 11, 13), route("Da Roma Termini a Milano Centrale domani alle 9", "it", italian).input.date)
    }

    @Test
    fun `the words around the stations say which is the origin`() {
        val english = route("to Milano Centrale from Roma Termini tomorrow at 9", "en")
        assertEquals(termini, english.input.origin)
        assertEquals(centrale, english.input.destination)
        assertEquals(termini, route("Per Milano Centrale da Roma Termini domani alle 9", "it").input.origin)
        // Without such words, or with contradicting ones, the first station said is the origin.
        assertEquals(termini, route("Roma Termini Milano Centrale domani alle 9", "it").input.origin)
        assertEquals(termini, route("Da Roma Termini a Milano Centrale domani alle 9", "it").input.origin)
    }

    @Test
    fun `text is compared in every script with ASCII digits`() {
        assertEquals("roma 9 35", VoiceText.normalize("Ｒｏｍａ ９：３５"))
        assertEquals("20 15 20 15 20 15 20 15", VoiceText.normalize("٢٠:١٥ २०:१५ ၂၀:၁၅ ๒๐:๑๕"))
        assertEquals("manana yarin tu den strasse", VoiceText.normalize("Mañana YARIN Từ đến Straße"))
        assertEquals("lúc đến", VoiceText.normalize("Lúc ĐẾN", foldLatinMarks = false))
        // The marks of other scripts are part of the letters; the optional Arabic vowel signs are not.
        assertEquals("कल 9 बजे", VoiceText.normalize("कल 9 बजे।"))
        assertEquals("завтрак й", VoiceText.normalize("Завтрак Й"))
        assertEquals("غدا الى", VoiceText.normalize("غدًا إلى"))
        // A space between two scripts.
        assertEquals("9 時 35 分", VoiceText.normalize("9時35分"))
        assertEquals("roma san pietro 까지 내일 9 시", VoiceText.normalize("Roma San Pietro까지 내일 9시"))
        assertEquals("พรุ่งนี้เวลา 9 roma", VoiceText.joinNativeWords("พรุ่งนี้ เวลา 9 roma"))
    }
}
