package it.binario.app.ui

import it.binario.app.AppLocales
import it.binario.app.data.reliability.DelayDistribution
import it.binario.app.data.reliability.HourBand
import it.binario.app.data.reliability.LateTrain
import it.binario.app.data.reliability.RoutePunctuality
import it.binario.app.data.reliability.StationPunctuality
import it.binario.app.ui.common.ReliabilityAggregateLines
import it.binario.app.ui.common.ReliabilityAggregateStrings
import it.binario.app.ui.detail.DelayDistributionStrings
import it.binario.app.ui.detail.DelayDistributionTexts
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The route, station and distribution texts of the network statistics as users read them, from the real `strings.xml`
 * of each language with the CLDR plural category of every count.
 */
class ReliabilityAggregateLinesTest {
    private val route = RoutePunctuality(
        trains = 12, samples = 109, onTime = 90, meanDelayMinutes = 2.4, days = 30, bands = emptyList(),
        bestBand = HourBand.EARLY_MORNING, worstBand = HourBand.EVENING
    )
    private val station = StationPunctuality(
        trains = 41, samples = 1040, onTime = 811, meanDelayMinutes = 4.2, days = 30,
        oftenLate = listOf(
            LateTrain("2153", "REG", "S01700", 28, 12, 9.0),
            LateTrain("2153", "REG", "S12001", 28, 10, 8.0),
            LateTrain("9558", "FR", "S08409", 30, 1, 3.0)
        )
    )

    @Test fun `a route reads its on-time share, mean delay, trains and runs, then the best and worst hours`() {
        val text = ReliabilityAggregateLines.route(route, aggregate("it"))
        assertEquals(
            listOf(
                "Questa tratta: 83% in orario · ritardo medio 2 min · 12 treni · 109 corse osservate in 30 giorni",
                "Partenze più puntuali: 6–9 · meno puntuali: 18–21"
            ),
            text.lines.map { it.text }
        )
        assertEquals(
            "Questa tratta: 83% in orario, ritardo medio 2 min, 12 treni, 109 corse osservate in 30 giorni. " +
                "Partenze più puntuali: 6–9 · meno puntuali: 18–21 (statistiche non ufficiali)",
            text.spoken
        )
        // No hour line when the bands do not tell the hours apart; singulars for one train, run and day.
        val small = route.copy(trains = 1, samples = 1, onTime = 1, days = 1, bestBand = null, worstBand = null)
        assertEquals(listOf("Questa tratta: 100% in orario · ritardo medio 2 min · 1 treno · 1 corsa osservata in 1 giorno"), ReliabilityAggregateLines.route(small, aggregate("it")).lines.map { it.text })
        assertEquals(
            "This route: 83% on time · average delay 2 min · 12 trains · 109 runs observed in 30 days",
            ReliabilityAggregateLines.route(route, aggregate("en")).lines.first().text
        )
    }

    @Test fun `a station reads its arrivals and the trains most often late, told apart by their origin`() {
        val italian = aggregate("it")
        assertEquals(
            "Arrivi in questa stazione: 78% in orario · ritardo medio 4 min · 41 treni · 1040 corse osservate in 30 giorni",
            ReliabilityAggregateLines.station(station, italian).text
        )
        val names = mapOf("S01700" to "Milano Centrale", "S12001" to "Palermo Centrale")
        assertEquals(
            listOf(
                "REG 2153 da Milano Centrale: in ritardo 12 volte su 28",
                "REG 2153 da Palermo Centrale: in ritardo 10 volte su 28",
                "FR 9558: in ritardo 1 volta su 30"
            ),
            ReliabilityAggregateLines.lateTrains(station.oftenLate, italian, names::get)
        )
        // Without a catalogue name for the origin, the number alone.
        assertEquals("REG 2153: in ritardo 12 volte su 28", ReliabilityAggregateLines.lateTrains(station.oftenLate, italian) { null }.first())
    }

    @Test fun `the distribution reads every band with its share and count, then the largest delay, in one sentence`() {
        val distribution = DelayDistribution(30, 12, 8, 6, 3, 1)
        val italian = distribution("it")
        val rows = DelayDistributionTexts.rows(distribution, italian)
        assertEquals(listOf("In orario", "1–5 min", "6–15 min", "16–30 min", "Oltre 30 min"), rows.map { it.label })
        assertEquals(listOf("40% · 12 volte", "27% · 8 volte", "20% · 6 volte", "10% · 3 volte", "3% · 1 volta"), rows.map { it.value })
        assertEquals(
            "Distribuzione dei ritardi: In orario 40% · 12 volte, 1–5 min 27% · 8 volte, 6–15 min 20% · 6 volte, " +
                "16–30 min 10% · 3 volte, Oltre 30 min 3% · 1 volta. Ritardo massimo: 47 min",
            DelayDistributionTexts.spoken(rows, 47, italian)
        )
        assertEquals("Delay distribution: On time 40% · 12 times, 1–5 min 27% · 8 times, 6–15 min 20% · 6 times, 16–30 min 10% · 3 times, Over 30 min 3% · 1 time", DelayDistributionTexts.spoken(DelayDistributionTexts.rows(distribution, distribution("en")), null, distribution("en")))
    }

    @Test fun `every language composes every text without a leftover placeholder`() {
        for (tag in AppLocales.tags) {
            val strings = aggregate(tag)
            val distributionStrings = distribution(tag)
            for (count in listOf(0, 1, 2, 3, 5, 11, 21, 22, 25, 101, 1040)) {
                val texts = ReliabilityAggregateLines.route(route.copy(trains = count, samples = count.coerceAtLeast(1), onTime = count, days = count.coerceIn(1, 30)), strings).lines.map { it.text } +
                    ReliabilityAggregateLines.station(station.copy(trains = count), strings).text +
                    ReliabilityAggregateLines.lateTrains(station.oftenLate.map { it.copy(late = count) }, strings) { "Milano Centrale" } +
                    DelayDistributionTexts.spoken(DelayDistributionTexts.rows(DelayDistribution(count, count, 0, 0, 0, 0), distributionStrings), count, distributionStrings)
                texts.forEach { text ->
                    assertFalse("$tag: $text", Regex("%(\\d+\\$)?[sd]").containsMatchIn(text) || text.contains("%%"))
                    assertTrue("$tag: $text", text.isNotBlank())
                }
            }
            HourBand.entries.forEach { band ->
                val label = ReliabilityAggregateLines.band(band, strings)
                assertTrue("$tag: $label", label.contains(band.startHour.toString()) && label.contains(band.endHour.toString()))
            }
        }
    }

    private fun aggregate(tag: String): ReliabilityAggregateStrings {
        val r = Resources(tag)
        return ReliabilityAggregateStrings(
            routeOnTime = { percent -> r.string("reliability_route_on_time", percent) },
            stationOnTime = { percent -> r.string("reliability_station_on_time", percent) },
            meanDelay = { minutes -> r.string("reliability_mean_delay", r.plural("minutes_short", minutes, minutes)) },
            trains = { count -> r.plural("reliability_trains_count", count, count) },
            runsObserved = { runs, days -> r.plural("reliability_runs_observed", runs, runs, r.plural("reliability_days", days, days)) },
            hours = { best, worst -> r.string("reliability_route_hours", best, worst) },
            hourBand = { start, end -> r.string("reliability_hour_band", start, end) },
            lateTrain = { label, late, total -> r.plural("reliability_late_train", late, label, late, total) },
            trainFrom = { label, origin -> r.string("reliability_train_from", label, origin) },
            spoken = { text -> r.string("reliability_spoken", text) }
        )
    }

    private fun distribution(tag: String): DelayDistributionStrings {
        val r = Resources(tag)
        fun minutes(value: Int) = r.plural("minutes_short", value, value)
        return DelayDistributionStrings(
            title = r.string("reliability_distribution_title"),
            bands = listOf(
                r.string("reliability_band_on_time"),
                r.string("reliability_band_range", 1, minutes(5)),
                r.string("reliability_band_range", 6, minutes(15)),
                r.string("reliability_band_range", 16, minutes(30)),
                r.string("reliability_band_over", minutes(30))
            ),
            share = { percent, count -> r.plural("reliability_band_share", count, percent, count) },
            maxDelay = { value -> r.string("reliability_max_delay", minutes(value)) }
        )
    }

    /** The strings and plurals of [tag]'s resource file, with the plural item CLDR picks for a count. */
    private class Resources(private val tag: String) {
        private val strings = HashMap<String, String>()
        private val plurals = HashMap<String, Map<String, String>>()

        init {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(repositoryRoot, "app/src/main/res/${AppLocales.folder(tag)}/strings.xml")).documentElement
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
        }

        fun string(name: String, vararg args: Any): String = String.format(Locale.ROOT, strings.getValue(name), *args)

        fun plural(name: String, count: Int, vararg args: Any): String {
            val items = plurals.getValue(name)
            return String.format(Locale.ROOT, items[quantity(count)] ?: items.getValue("other"), *args)
        }

        private fun quantity(n: Int): String = when (tag) {
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
    }

    private companion object {
        val repositoryRoot: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
    }
}
