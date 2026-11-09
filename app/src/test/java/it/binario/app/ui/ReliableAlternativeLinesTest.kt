package it.binario.app.ui

import it.binario.app.AppLocales
import it.binario.app.data.reliability.StopReliability
import it.binario.app.ui.common.ReliableAlternativeLines
import it.binario.app.ui.common.ReliableAlternativeStrings
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * «Più puntuale: …» as users read and hear it, from the real `strings.xml` of each language with the CLDR plural
 * category of every count (the quantities themselves are checked by AndroidStringResourceParityTest).
 */
class ReliableAlternativeLinesTest {
    private val regional = StopReliability("S01860", samples = 10, onTime = 9, onTimeLimitMinutes = 15, meanDelayMinutes = 3.0, daysObserved = 10, daysCancelled = 0)

    @Test fun `the line names the train and its departure, then how often it was on time`() {
        val italian = strings("it")
        val text = ReliableAlternativeLines.text("Regionale 2157", "08:12", regional, italian)
        assertEquals("Più puntuale: Regionale 2157 delle 08:12 · in orario 9 volte su 10", text.line.text)
        assertEquals("Più puntuale: Regionale 2157 delle 08:12, in orario 9 volte su 10 (statistiche non ufficiali)", text.spoken)
        assertEquals("Mostra il treno delle 08:12", text.actionLabel)
        assertEquals(
            "Più puntuale: Treno Regionale Trenord RE_11 - 2153 delle 18:05 · in orario 1 volta su 5",
            ReliableAlternativeLines.text("Treno Regionale Trenord RE_11 - 2153", "18:05", regional.copy(samples = 5, onTime = 1), italian).line.text
        )
        val english = ReliableAlternativeLines.text("Regional 2157", "08:12", regional, strings("en"))
        assertEquals("More punctual: Regional 2157 at 08:12 · on time 9 times out of 10", english.line.text)
        assertEquals("More punctual: Regional 2157 at 08:12, on time 9 times out of 10 (unofficial statistics)", english.spoken)
        assertEquals("Show the 08:12 train", english.actionLabel)
        assertEquals("Pünktlicher: Regionalzug 2157 um 08:12 · 9 von 10 Mal pünktlich", ReliableAlternativeLines.text("Regionalzug 2157", "08:12", regional, strings("de")).line.text)
    }

    @Test fun `plural forms follow the on-time count in Russian and Arabic`() {
        val russian = strings("ru")
        assertEquals("вовремя 21 раз из 30", russian.onTime(21, 30))
        assertEquals("вовремя 22 раза из 30", russian.onTime(22, 30))
        assertEquals("вовремя 25 раз из 30", russian.onTime(25, 30))
        val arabic = strings("ar")
        assertEquals("في الموعد 2 مرتين من أصل 30", arabic.onTime(2, 30))
        assertEquals("في الموعد 7 مرات من أصل 30", arabic.onTime(7, 30))
        assertEquals("في الموعد 27 مرة من أصل 30", arabic.onTime(27, 30))
    }

    @Test fun `every language composes the line, the sentence and the click label without a leftover placeholder or a repeated word`() {
        for (tag in AppLocales.tags) {
            val localized = strings(tag)
            for (count in 0..31) {
                val text = ReliableAlternativeLines.text("REG 2157", "08:12", regional.copy(samples = 31, onTime = count), localized)
                for (shown in listOf(text.line.text, text.spoken, text.actionLabel)) {
                    assertFalse("$tag: $shown", shown.contains('%'))
                    assertTrue("$tag: $shown", shown.contains("08:12"))
                    val words = shown.split(Regex("[\\s·,:：(（)）]+"))
                    assertTrue(
                        "$tag repeats a word: $shown",
                        words.zipWithNext().none { (first, second) -> first.any(Char::isLetter) && first.equals(second, ignoreCase = true) }
                    )
                }
                assertTrue("$tag: ${text.line.text}", text.line.text.contains("REG 2157") && text.line.text.contains(count.toString()) && text.line.text.contains("31"))
                assertEquals(tag, 2, text.line.parts.size)
            }
        }
    }

    /** The strings of [tag] from its resource file, with the plural item CLDR picks for the count. */
    private fun strings(tag: String): ReliableAlternativeStrings {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(repositoryRoot, "app/src/main/res/${AppLocales.folder(tag)}/strings.xml")).documentElement
        val strings = HashMap<String, String>()
        val plurals = HashMap<String, Map<String, String>>()
        val nodes = root.childNodes
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            when (element.tagName) {
                "string" -> strings[element.getAttribute("name")] = element.textContent
                "plurals" -> plurals[element.getAttribute("name")] = buildMap {
                    val items = element.getElementsByTagName("item")
                    for (item in 0 until items.length) (items.item(item) as Element).let { put(it.getAttribute("quantity"), it.textContent) }
                }
            }
        }
        fun plural(name: String, count: Int, vararg args: Any): String {
            val items = plurals.getValue(name)
            return String.format(Locale.ROOT, items[quantity(tag, count)] ?: items.getValue("other"), *args)
        }
        fun string(name: String, vararg args: Any): String = String.format(Locale.ROOT, strings.getValue(name), *args)
        return ReliableAlternativeStrings(
            train = { label, time -> string("reliability_alternative_train", label, time) },
            onTime = { count, total -> plural("reliability_alternative_on_time", count, count, total) },
            open = { time -> string("reliability_alternative_open", time) },
            spoken = { text -> string("reliability_spoken", text) }
        )
    }

    /** CLDR plural category of an integer count, for the languages of the app. */
    private fun quantity(tag: String, n: Int): String = when (tag) {
        "it", "es", "en", "de", "tr" -> if (n == 1) "one" else "other"
        "fr", "pt" -> if (n == 0 || n == 1) "one" else "other"
        "hi" -> if (n == 0 || n == 1) "one" else "other"
        "ru" -> when {
            n % 10 == 1 && n % 100 != 11 -> "one"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "few"
            else -> "many"
        }
        "ar" -> when {
            n == 0 -> "zero"
            n == 1 -> "one"
            n == 2 -> "two"
            n % 100 in 3..10 -> "few"
            n % 100 in 11..99 -> "many"
            else -> "other"
        }
        else -> "other"
    }

    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
}
