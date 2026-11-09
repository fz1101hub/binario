package it.binario.app.ui

import it.binario.app.AppLocales
import it.binario.app.FavouriteTrainPunctuality
import it.binario.app.FavouriteTrainReliability
import it.binario.app.LocalPunctuality
import it.binario.app.PunctualityTrend
import it.binario.app.ServiceMonitor
import it.binario.app.data.reliability.StopReliability
import it.binario.app.ui.common.NetworkReliabilityStrings
import it.binario.app.ui.foryou.MyTrainsReliabilityLines
import it.binario.app.ui.foryou.MyTrainsReliabilityStrings
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * «Affidabilità dei miei treni» as users read and hear it, from the real `strings.xml` of each language with the CLDR
 * plural category of every count (the quantities themselves are checked by AndroidStringResourceParityTest).
 */
class MyTrainsReliabilityLinesTest {
    private val monitor = ServiceMonitor("9558", "Frecciarossa · Torino Porta Nuova", origin = "Roma Termini")
    private val network = StopReliability("S00219", samples = 30, onTime = 20, onTimeLimitMinutes = 5, meanDelayMinutes = 6.2, daysObserved = 31, daysCancelled = 1)
    private val worse = PunctualityTrend(recent = LocalPunctuality(4, 2, 0), earlier = LocalPunctuality(4, 4, 0))
    private val better = PunctualityTrend(recent = LocalPunctuality(4, 4, 0), earlier = LocalPunctuality(4, 2, 0))

    @Test fun `a row reads the network line as the results do, then the user's own records and the trend`() {
        val italian = strings("it")
        val text = MyTrainsReliabilityLines.text(FavouriteTrainReliability(monitor, network, LocalPunctuality(10, 8, 0), worse), italian)
        assertEquals("In orario 20 volte su 30 · ritardo medio 6 min · soppresso 1 volta", text.network?.text)
        assertEquals("Nei tuoi viaggi: in orario 8 volte su 10", text.personal?.text)
        assertEquals("In peggioramento", text.trend)
        assertNull(text.noData)
        assertEquals(
            "In orario 20 volte su 30, ritardo medio 6 min, soppresso 1 volta (statistiche non ufficiali). Nei tuoi viaggi: in orario 8 volte su 10. In peggioramento",
            text.spoken
        )
        // A regional train within 15 minutes, a cancelled run of the user's own, a better trend.
        val regional = StopReliability("S01860", 28, 26, 15, 4.0, 28, 0)
        val mixed = MyTrainsReliabilityLines.text(FavouriteTrainReliability(monitor.copy(number = "RE_11 - 2153"), regional, LocalPunctuality(4, 1, 1), better), italian)
        assertEquals("In orario 26 volte su 28 · ritardo medio 4 min", mixed.network?.text)
        assertEquals("Nei tuoi viaggi: in orario 1 volta su 4 · soppresso 1 volta", mixed.personal?.text)
        assertEquals("In miglioramento", mixed.trend)
        val english = MyTrainsReliabilityLines.text(FavouriteTrainReliability(monitor, network, LocalPunctuality(10, 8, 0), worse), strings("en"))
        assertEquals("On time 20 times out of 30 · average delay 6 min · cancelled 1 time", english.network?.text)
        assertEquals("On your trips: on time 8 times out of 10", english.personal?.text)
        assertEquals("Getting worse", english.trend)
    }

    @Test fun `only the figures there are, and a short note when there are none`() {
        val italian = strings("it")
        val networkOnly = MyTrainsReliabilityLines.text(FavouriteTrainReliability(monitor, network, null, null), italian)
        assertNull(networkOnly.personal)
        assertNull(networkOnly.trend)
        assertEquals("In orario 20 volte su 30, ritardo medio 6 min, soppresso 1 volta (statistiche non ufficiali)", networkOnly.spoken)
        // The build without statistics: the user's own records only, never said to be unofficial.
        val personalOnly = MyTrainsReliabilityLines.text(FavouriteTrainReliability(monitor, null, LocalPunctuality(1, 1, 0), null), italian)
        assertNull(personalOnly.network)
        assertEquals("Nei tuoi viaggi: in orario 1 volta su 1", personalOnly.spoken)
        val nothing = MyTrainsReliabilityLines.text(FavouriteTrainReliability(monitor, null, null, null), italian)
        assertNull(nothing.network)
        assertNull(nothing.personal)
        assertEquals("Ancora nessun dato", nothing.noData)
        assertEquals("Ancora nessun dato", nothing.spoken)
        assertEquals("No data yet", MyTrainsReliabilityLines.text(FavouriteTrainReliability(monitor, null, null, null), strings("en")).noData)
    }

    @Test fun `plural forms of the user's own records follow the on-time count in Russian and Arabic`() {
        val russian = strings("ru")
        assertEquals("В ваших поездках: вовремя 21 раз из 30", russian.personal(21, 30))
        assertEquals("В ваших поездках: вовремя 22 раза из 30", russian.personal(22, 30))
        assertEquals("В ваших поездках: вовремя 25 раз из 30", russian.personal(25, 30))
        val arabic = strings("ar")
        assertEquals("في رحلاتك: في الموعد 2 مرتين من أصل 30", arabic.personal(2, 30))
        assertEquals("في رحلاتك: في الموعد 7 مرات من أصل 30", arabic.personal(7, 30))
        assertEquals("في رحلاتك: في الموعد 27 مرة من أصل 30", arabic.personal(27, 30))
    }

    @Test fun `every language composes every line without a leftover placeholder or a repeated word`() {
        for (tag in AppLocales.tags) {
            val localized = strings(tag)
            val fixed = fixed(tag)
            for (text in fixed.values + listOf(localized.worse, localized.better, localized.noData)) {
                assertTrue("$tag: $text", text.isNotBlank())
                assertNoRepeatedWord(tag, text)
            }
            // The description names the 30 days of the figures and the 4 weeks of the trend.
            val description = fixed.getValue("my_trains_reliability_description")
            assertTrue("$tag: $description", description.contains(FavouriteTrainPunctuality.PERSONAL_DAYS.toString()))
            assertTrue("$tag: $description", description.contains((FavouriteTrainPunctuality.TREND_DAYS / 7).toString()))
            for (count in 0..31) {
                val row = FavouriteTrainReliability(monitor, network.copy(samples = 31, onTime = count), LocalPunctuality(31, count, count % 3), worse)
                val text = MyTrainsReliabilityLines.text(row, localized)
                val personal = requireNotNull(text.personal).text
                for (shown in listOf(requireNotNull(text.network).text, personal, text.spoken)) {
                    assertFalse("$tag: $shown", shown.contains('%'))
                    assertNoRepeatedWord(tag, shown)
                }
                assertTrue("$tag: $personal", personal.contains(count.toString()) && personal.contains("31"))
            }
        }
    }

    private fun assertNoRepeatedWord(tag: String, text: String) {
        val words = text.split(Regex("[\\s·,:：;；。.(（)）—]+"))
        assertTrue(
            "$tag repeats a word: $text",
            words.zipWithNext().none { (first, second) -> first.any(Char::isLetter) && first.equals(second, ignoreCase = true) }
        )
    }

    /** The plain strings of the section in [tag] that take no argument. */
    private fun fixed(tag: String): Map<String, String> = resources(tag).first.filterKeys {
        it in setOf("my_trains_reliability_title", "my_trains_reliability_description")
    }.also { assertEquals(tag, 2, it.size) }

    private fun resources(tag: String): Pair<Map<String, String>, Map<String, Map<String, String>>> {
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
        return strings to plurals
    }

    /** The strings of [tag] from its resource file, with the plural item CLDR picks for the count. */
    private fun strings(tag: String): MyTrainsReliabilityStrings {
        val (strings, plurals) = resources(tag)
        fun plural(name: String, count: Int, vararg args: Any): String {
            val items = plurals.getValue(name)
            return String.format(Locale.ROOT, items[quantity(tag, count)] ?: items.getValue("other"), *args)
        }
        fun string(name: String, vararg args: Any): String = String.format(Locale.ROOT, strings.getValue(name), *args)
        val network = NetworkReliabilityStrings(
            onTime = { count, total -> plural("reliability_on_time_count", count, count, total) },
            legOnTime = { label, count, total -> plural("reliability_leg_on_time_count", count, label, count, total) },
            meanDelay = { minutes -> string("reliability_mean_delay", plural("minutes_short", minutes, minutes)) },
            cancelled = { count -> plural("reliability_cancelled_count", count, count) },
            connection = { station, count, total -> plural("reliability_connection_count", count, station, count, total) },
            cancelledShare = { count, days -> plural("reliability_cancelled_share", count, count, days) },
            legCancelledShare = { label, count, days -> plural("reliability_leg_cancelled_share", count, label, count, days) },
            spoken = { text -> string("reliability_spoken", text) }
        )
        return MyTrainsReliabilityStrings(
            network = network,
            personal = { onTime, runs -> plural("my_trains_reliability_personal", onTime, onTime, runs) },
            worse = string("my_trains_reliability_worse"),
            better = string("my_trains_reliability_better"),
            noData = string("my_trains_reliability_no_data")
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
