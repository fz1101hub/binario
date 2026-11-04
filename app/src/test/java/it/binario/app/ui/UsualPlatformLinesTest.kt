package it.binario.app.ui

import it.binario.app.AppLocales
import it.binario.app.data.reliability.UsualPlatform
import it.binario.app.data.reliability.WeekdayRemark
import it.binario.app.ui.common.UsualPlatformLines
import it.binario.app.ui.common.UsualPlatformStrings
import java.io.File
import java.time.DayOfWeek
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The usual platform and the weekday remarks as users read them, from the real `strings.xml` of each language. */
class UsualPlatformLinesTest {
    @Test fun `the usual platform and the weekday remarks read as the specification shows them`() {
        val italian = strings("it")
        assertEquals("Di solito al binario 12 (8 volte su 10)", UsualPlatformLines.usualPlatform(UsualPlatform("12", 8, 10), italian).text)
        assertEquals("Di solito al binario 3 Ovest (24 volte su 30)", UsualPlatformLines.usualPlatform(UsualPlatform("3 Ovest", 24, 30), italian).text)
        assertEquals(
            "Il venerdì è in orario 4 volte su 10, peggio della media",
            UsualPlatformLines.weekday(WeekdayRemark(DayOfWeek.FRIDAY, 10, 4, better = false, gapPoints = -30.0), italian).text
        )
        assertEquals(
            "La domenica è in orario 1 volta su 4, meglio della media",
            UsualPlatformLines.weekday(WeekdayRemark(DayOfWeek.SUNDAY, 4, 1, better = true, gapPoints = 20.0), italian).text
        )
        val english = strings("en")
        assertEquals("Usually platform 12 (8 times out of 10)", UsualPlatformLines.usualPlatform(UsualPlatform("12", 8, 10), english).text)
        assertEquals(
            "On Fridays it is on time 4 times out of 10, worse than average",
            UsualPlatformLines.weekday(WeekdayRemark(DayOfWeek.FRIDAY, 10, 4, better = false, gapPoints = -30.0), english).text
        )
        assertEquals("Обычно путь 12 (22 раза из 30)", UsualPlatformLines.usualPlatform(UsualPlatform("12", 22, 30), strings("ru")).text)
        assertEquals("Обычно путь 12 (25 раз из 30)", UsualPlatformLines.usualPlatform(UsualPlatform("12", 25, 30), strings("ru")).text)
        assertEquals("عادةً الرصيف 12 (7 مرات من أصل 10)", UsualPlatformLines.usualPlatform(UsualPlatform("12", 7, 10), strings("ar")).text)
    }

    @Test fun `every language names every weekday and composes every line without a leftover placeholder`() {
        for (tag in AppLocales.tags) {
            val localized = strings(tag)
            val days = DayOfWeek.entries.map(localized.weekday)
            assertEquals(tag, 7, days.toSet().size)
            for (count in 0..31) {
                val texts = listOf(localized.usualPlatform("3 Ovest", count, 31)) +
                    days.flatMap { listOf(localized.weekdayWorse(it, count, 31), localized.weekdayBetter(it, count, 31)) }
                texts.forEach { text ->
                    assertFalse("$tag: $text", text.contains('%'))
                    assertTrue("$tag: $text", text.contains(count.toString()) && text.contains("31"))
                }
                assertTrue("$tag: ${texts[0]}", texts[0].contains("3 Ovest"))
                assertTrue("$tag: ${texts[1]}", texts[1].contains(days[0]))
            }
        }
    }

    /** The strings of [tag] from its resource file, with the plural item CLDR picks for the count. */
    private fun strings(tag: String): UsualPlatformStrings {
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
        return UsualPlatformStrings(
            usualPlatform = { name, count, total -> plural("reliability_usual_platform", count, name, count, total) },
            weekday = { day -> strings.getValue("reliability_weekday_" + day.name.lowercase(Locale.ROOT)) },
            weekdayWorse = { day, count, total -> plural("reliability_weekday_worse", count, day, count, total) },
            weekdayBetter = { day, count, total -> plural("reliability_weekday_better", count, day, count, total) }
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
