package it.binario.app.ui

import it.binario.app.AppLocales
import it.binario.app.data.reliability.ConnectionOdds
import it.binario.app.data.reliability.StopReliability
import it.binario.app.journeys.ConnectionNetworkReliability
import it.binario.app.journeys.JourneyNetworkReliability
import it.binario.app.journeys.LegNetworkReliability
import it.binario.app.ui.common.NetworkReliabilityLines
import it.binario.app.ui.common.NetworkReliabilityStrings
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The reliability lines of the result cards as users read them, from the real `strings.xml` of each language with the
 * CLDR plural category of every count (the quantities themselves are checked by AndroidStringResourceParityTest).
 */
class NetworkReliabilityLinesTest {
    private val frecciarossa = StopReliability("S00219", samples = 30, onTime = 20, onTimeLimitMinutes = 5, meanDelayMinutes = 6.2, daysObserved = 31, daysCancelled = 1)
    private val regional = StopReliability("S00219", samples = 30, onTime = 29, onTimeLimitMinutes = 15, meanDelayMinutes = 3.0, daysObserved = 30, daysCancelled = 0)

    @Test fun `a direct train reads on time, mean delay and cancellations at the destination`() {
        val italian = strings("it")
        assertEquals("In orario 20 volte su 30 · ritardo medio 6 min · soppresso 1 volta", NetworkReliabilityLines.train(frecciarossa, italian).text)
        assertEquals("In orario 29 volte su 30 · ritardo medio 3 min", NetworkReliabilityLines.train(regional, italian).text)
        assertEquals(
            "In orario 1 volta su 5 · ritardo medio 0 min · soppresso 2 volte",
            NetworkReliabilityLines.train(regional.copy(samples = 5, onTime = 1, meanDelayMinutes = -0.4, daysCancelled = 2), italian).text
        )
        assertEquals("On time 20 times out of 30 · average delay 6 min · cancelled 1 time", NetworkReliabilityLines.train(frecciarossa, strings("en")).text)
        // Cancelled on every day observed: only the cancellations.
        val alwaysCancelled = StopReliability("S01700", 0, 0, 15, 0.0, daysObserved = 12, daysCancelled = 12)
        assertEquals("Soppresso 12 volte su 12", NetworkReliabilityLines.train(alwaysCancelled, italian).text)
        assertEquals("REG 2155 soppresso 12 volte su 12", NetworkReliabilityLines.train(alwaysCancelled, italian, label = "REG 2155").text)
        assertEquals("Cancelled 1 time out of 5", NetworkReliabilityLines.train(alwaysCancelled.copy(daysObserved = 5, daysCancelled = 1), strings("en")).text)
        val text = NetworkReliabilityLines.text(listOf(NetworkReliabilityLines.train(frecciarossa, italian)), italian)
        assertEquals("In orario 20 volte su 30, ritardo medio 6 min, soppresso 1 volta (statistiche non ufficiali)", text.spoken)
    }

    @Test fun `a journey reads its weakest train, named, and its weakest change`() {
        val change = ConnectionNetworkReliability(afterLegIndex = 0, stationLabel = "Milano Centrale", slackMinutes = 7, odds = ConnectionOdds(30, 23.6))
        val easyChange = change.copy(afterLegIndex = 2, stationLabel = "Novara", odds = ConnectionOdds(30, 29.5))
        val journey = JourneyNetworkReliability(
            legs = listOf(LegNetworkReliability(0, frecciarossa), LegNetworkReliability(2, regional)),
            connections = listOf(easyChange, change)
        )
        val italian = strings("it")
        val lines = NetworkReliabilityLines.journey(journey, legLabel = { index -> if (index == 0) "FR 9558" else "REG 2041" }, strings = italian)
        assertEquals(
            listOf("FR 9558 in orario 20 volte su 30 · ritardo medio 6 min · soppresso 1 volta", "Coincidenza a Milano Centrale riuscita 23 volte su 30"),
            lines.map { it.text }
        )
        assertEquals(
            "FR 9558 in orario 20 volte su 30, ritardo medio 6 min, soppresso 1 volta. Coincidenza a Milano Centrale riuscita 23 volte su 30 (statistiche non ufficiali)",
            NetworkReliabilityLines.text(lines, italian).spoken
        )
        // One train only: no name, as on a direct train.
        val single = JourneyNetworkReliability(listOf(LegNetworkReliability(0, regional)), emptyList())
        assertEquals(listOf("In orario 29 volte su 30 · ritardo medio 3 min"), NetworkReliabilityLines.journey(single, { "REG 2041" }, italian).map { it.text })
    }

    @Test fun `plural forms follow the count in Russian and Arabic`() {
        val russian = strings("ru")
        assertEquals("Вовремя 21 раз из 30", russian.onTime(21, 30))
        assertEquals("Вовремя 22 раза из 30", russian.onTime(22, 30))
        assertEquals("Вовремя 25 раз из 30", russian.onTime(25, 30))
        assertEquals("Вовремя 11 раз из 30", russian.onTime(11, 30))
        val arabic = strings("ar")
        assertEquals("في الموعد 2 مرتين من أصل 30", arabic.onTime(2, 30))
        assertEquals("في الموعد 7 مرات من أصل 30", arabic.onTime(7, 30))
        assertEquals("في الموعد 27 مرة من أصل 30", arabic.onTime(27, 30))
    }

    @Test fun `every language composes every line without a leftover placeholder`() {
        for (tag in AppLocales.tags) {
            val localized = strings(tag)
            for (count in 0..31) {
                val texts = listOf(
                    localized.onTime(count, 31), localized.legOnTime("REG 2153", count, 31), localized.meanDelay(count), localized.cancelled(count),
                    localized.connection("Milano Centrale", count, 31), localized.spoken("x"),
                    localized.cancelledShare(count, 31), localized.legCancelledShare("REG 2153", count, 31)
                )
                texts.forEach { text ->
                    assertFalse("$tag: $text", text.contains('%'))
                    assertTrue("$tag: $text", text.isNotBlank())
                }
                assertTrue("$tag: ${texts[0]}", texts[0].contains(count.toString()) && texts[0].contains("31"))
                assertTrue("$tag: ${texts[4]}", texts[4].contains("Milano Centrale"))
            }
        }
    }

    /** The strings of [tag] from its resource file, with the plural item CLDR picks for the count. */
    private fun strings(tag: String): NetworkReliabilityStrings {
        val folder = AppLocales.folder(tag)
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(repositoryRoot, "app/src/main/res/$folder/strings.xml")).documentElement
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
        return NetworkReliabilityStrings(
            onTime = { count, total -> plural("reliability_on_time_count", count, count, total) },
            legOnTime = { label, count, total -> plural("reliability_leg_on_time_count", count, label, count, total) },
            meanDelay = { minutes -> string("reliability_mean_delay", plural("minutes_short", minutes, minutes)) },
            cancelled = { count -> plural("reliability_cancelled_count", count, count) },
            connection = { station, count, total -> plural("reliability_connection_count", count, station, count, total) },
            cancelledShare = { count, days -> plural("reliability_cancelled_share", count, count, days) },
            legCancelledShare = { label, count, days -> plural("reliability_leg_cancelled_share", count, label, count, days) },
            spoken = { text -> string("reliability_spoken", text) }
        )
    }

    /** CLDR plural category of an integer count, for the languages of the app. */
    private fun quantity(tag: String, n: Int): String = when (tag) {
        "it", "es", "en", "de", "tr" -> if (n == 1) "one" else if (n != 0 && n % 1_000_000 == 0 && tag in setOf("it", "es")) "many" else "other"
        "fr", "pt" -> if (n == 0 || n == 1) "one" else if (n != 0 && n % 1_000_000 == 0) "many" else "other"
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
