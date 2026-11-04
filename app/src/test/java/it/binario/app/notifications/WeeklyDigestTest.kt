package it.binario.app.notifications

import it.binario.app.PunctualityObservation
import it.binario.app.ServiceMonitor
import it.binario.app.TestStringCatalogue
import it.binario.app.widget.ReliabilityMark
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyDigestTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val sunday = LocalDate.of(2026, 11, 8)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = ZonedDateTime.of(date.atTime(hour, minute), rome)

    @Test fun `the digest window opens on Sunday at 19 and closes on Monday at noon, quiet from 22 to 7`() {
        assertNull(WeeklyDigestPlanner.weekEndAt(at(sunday, 18, 59)))
        assertEquals(sunday, WeeklyDigestPlanner.weekEndAt(at(sunday, 19)))
        assertEquals(sunday, WeeklyDigestPlanner.weekEndAt(at(sunday.plusDays(1), 11, 59)))
        assertNull(WeeklyDigestPlanner.weekEndAt(at(sunday.plusDays(1), 12)))
        assertNull(WeeklyDigestPlanner.weekEndAt(at(sunday.minusDays(3), 20)))
        assertTrue(WeeklyDigestPlanner.mayNotify(at(sunday, 21, 59)))
        assertFalse(WeeklyDigestPlanner.mayNotify(at(sunday, 22)))
        assertFalse(WeeklyDigestPlanner.mayNotify(at(sunday.plusDays(1), 6, 59)))
        assertTrue(WeeklyDigestPlanner.mayNotify(at(sunday.plusDays(1), 7)))
        assertEquals(LocalDate.of(2026, 11, 2), WeeklyDigestPlanner.weekStart(sunday))
    }

    @Test fun `the next run is Sunday at 19, now in the window, 7 after the quiet hours, next week once sent`() {
        assertEquals(at(sunday, 19), WeeklyDigestPlanner.nextRunAt(at(sunday.minusDays(4), 10), null))
        assertEquals(at(sunday, 19), WeeklyDigestPlanner.nextRunAt(at(sunday, 9), sunday.minusDays(7)))
        assertEquals(at(sunday, 20, 30), WeeklyDigestPlanner.nextRunAt(at(sunday, 20, 30), sunday.minusDays(7)))
        // Delayed into the quiet hours: Monday at 7, still in the window.
        assertEquals(at(sunday.plusDays(1), 7), WeeklyDigestPlanner.nextRunAt(at(sunday, 23, 10), null))
        assertEquals(at(sunday.plusDays(1), 7), WeeklyDigestPlanner.nextRunAt(at(sunday.plusDays(1), 2), null))
        // Sent this week, or the window closed: the next Sunday.
        assertEquals(at(sunday.plusDays(7), 19), WeeklyDigestPlanner.nextRunAt(at(sunday, 20), sunday))
        assertEquals(at(sunday.plusDays(7), 19), WeeklyDigestPlanner.nextRunAt(at(sunday.plusDays(1), 13), null))
    }

    @Test fun `the Sunday after the end of summer time is still at 19 local time`() {
        val october = LocalDate.of(2026, 10, 25)
        assertEquals(at(october, 19), WeeklyDigestPlanner.nextRunAt(at(october.minusDays(2), 12), null))
    }

    private fun observation(daysBeforeSunday: Long, number: String, delay: Int, cancelled: Boolean = false) =
        PunctualityObservation(sunday.minusDays(daysBeforeSunday), number, "Trenitalia", delay, cancelled)

    private val regional = ServiceMonitor("2153", "Regionale · Pavia", origin = "Milano Centrale", scheduledDeparture = "07:42")
    private val frecciarossa = ServiceMonitor("9558", "Frecciarossa · Torino", customName = "Il Freccia del lunedì", origin = "Roma Termini")
    private val intercity = ServiceMonitor("701", "Intercity · Napoli")

    @Test fun `this week's runs and the earlier ones are counted on time within 5 minutes, cancellations apart`() {
        val history = listOf(
            observation(0, "2153", 3), observation(1, "2153", 12), observation(2, "2153", 0, cancelled = true),
            observation(6, "2153", 5), observation(7, "2153", 40),
            observation(1, "9558", 2)
        )
        assertEquals(LocalPunctuality(runs = 4, onTime = 2, cancelled = 1), WeeklyDigestBuilder.punctuality(history, "2153", sunday.minusDays(6), sunday))
        assertEquals(LocalPunctuality(runs = 1, onTime = 0, cancelled = 0), WeeklyDigestBuilder.punctuality(history, "2153", sunday.minusDays(34), sunday.minusDays(7)))
        assertNull(WeeklyDigestBuilder.punctuality(history, "701", sunday.minusDays(6), sunday))
        assertEquals(50, LocalPunctuality(4, 2, 1).onTimePercent)
    }

    @Test fun `the train whose share dropped most against its earlier weeks is named`() {
        val history = buildList {
            // 2153: 1 of 4 this week, 5 of 6 the weeks before (83%): a drop of 58 points.
            addAll(listOf(observation(0, "2153", 20), observation(1, "2153", 2), observation(2, "2153", 30), observation(3, "2153", 16)))
            addAll((8L..13L).map { observation(it, "2153", if (it == 9L) 25 else 1) })
            // 9558: 2 of 3 this week, 3 of 4 before: a drop of 8 points only.
            addAll(listOf(observation(0, "9558", 0), observation(1, "9558", 4), observation(2, "9558", 22)))
            addAll((8L..11L).map { observation(it, "9558", if (it == 8L) 12 else 0) })
        }
        val digest = requireNotNull(WeeklyDigestBuilder.build(listOf(regional, frecciarossa, intercity), history, sunday) { null })
        assertEquals(listOf("2153", "9558"), digest.trains.map { it.monitor.number })
        assertEquals(DigestWorse(digest.trains[0], weekPercent = 25, usualPercent = 83), digest.worse)
        assertEquals(7, digest.runs)
        assertEquals(3, digest.onTime)
    }

    @Test fun `without earlier weeks the network share within 5 minutes is the usual one, and small drops name nobody`() {
        val history = listOf(observation(0, "9558", 12), observation(1, "9558", 0), observation(2, "9558", 18))
        val network = ReliabilityMark(onTime = 27, samples = 30, limitMinutes = 5, onTimeWithin5 = 27, days = 30)
        val digest = requireNotNull(WeeklyDigestBuilder.build(listOf(frecciarossa), history, sunday) { network })
        assertEquals(DigestWorse(digest.trains.single(), weekPercent = 33, usualPercent = 90), digest.worse)
        // 2 of 3 against 73%: 6 points, not a change worth naming.
        val steady = listOf(observation(0, "9558", 0), observation(1, "9558", 0), observation(2, "9558", 18))
        assertNull(WeeklyDigestBuilder.build(listOf(frecciarossa), steady, sunday) { network.copy(onTimeWithin5 = 22) }?.worse)
        // A single run this week says nothing about a change.
        assertNull(WeeklyDigestBuilder.build(listOf(frecciarossa), history.take(1), sunday) { network }?.worse)
    }

    @Test fun `a train with network statistics only is listed, with nothing at all there is no digest`() {
        val network = ReliabilityMark(25, 30, 15, 17, 30)
        val digest = requireNotNull(WeeklyDigestBuilder.build(listOf(regional, intercity), emptyList(), sunday) { if (it.number == "2153") network else null })
        assertEquals(listOf(regional), digest.trains.map { it.monitor })
        assertNull(digest.worse)
        assertNull(WeeklyDigestBuilder.build(listOf(regional, intercity), emptyList(), sunday) { null })
        assertNull(WeeklyDigestBuilder.build(emptyList(), listOf(observation(0, "2153", 0)), sunday) { network })
    }

    private fun italian(): WeeklyDigestStrings {
        val catalogue = TestStringCatalogue.of(null)
        return WeeklyDigestStrings(
            locale = Locale.ITALIAN,
            trainNumber = catalogue.getValue("train_number_label"),
            trainLine = catalogue.getValue("weekly_digest_train_line"),
            onTimeCount = { onTime, runs -> "In orario $onTime ${if (onTime == 1) "volta" else "volte"} su $runs" },
            cancelled = { count -> "soppresso $count ${if (count == 1) "volta" else "volte"}" },
            noRuns = catalogue.getValue("weekly_digest_no_runs"),
            network = { days, percent -> "ultimi $days giorni sulla rete: $percent% in orario" },
            worse = catalogue.getValue("weekly_digest_worse"),
            attribution = catalogue.getValue("reliability_attribution")
        )
    }

    @Test fun `the notification names the worse train first, then every train, then the attribution`() {
        val strings = italian()
        val regionalTrain = DigestTrain(regional, LocalPunctuality(4, 1, 1), LocalPunctuality(6, 5, 0), ReliabilityMark(25, 30, 15, 17, 30))
        val freccia = DigestTrain(frecciarossa, null, null, ReliabilityMark(27, 30, 5, 27, 30))
        val digest = WeeklyDigest(sunday, listOf(regionalTrain, freccia), DigestWorse(regionalTrain, 25, 83))
        assertEquals(
            listOf(
                "Peggiorato: Treno 2153, 25% in orario questa settimana (di solito 83%)",
                "Treno 2153: In orario 1 volta su 4 · soppresso 1 volta · ultimi 30 giorni sulla rete: 83% in orario",
                "Il Freccia del lunedì: nessuna corsa registrata · ultimi 30 giorni sulla rete: 90% in orario",
                "Statistiche non ufficiali di Binario su dati ViaggiaTreno, aggiornate al 9 nov 2026"
            ),
            WeeklyDigestText.lines(digest, strings, LocalDate.of(2026, 11, 9))
        )
        assertEquals("Peggiorato: Treno 2153, 25% in orario questa settimana (di solito 83%)", WeeklyDigestText.summary(digest, strings))
        val calm = digest.copy(worse = null)
        assertEquals("In orario 1 volta su 4", WeeklyDigestText.summary(calm, strings))
        // Without network shares (or without the statistics) there is nothing to attribute.
        val local = WeeklyDigest(sunday, listOf(regionalTrain.copy(network = null)), null)
        assertEquals(listOf("Treno 2153: In orario 1 volta su 4 · soppresso 1 volta"), WeeklyDigestText.lines(local, strings, LocalDate.of(2026, 11, 9)))
        assertEquals(listOf("Il Freccia del lunedì: nessuna corsa registrata · ultimi 30 giorni sulla rete: 90% in orario"), WeeklyDigestText.lines(WeeklyDigest(sunday, listOf(freccia), null), strings, null))
        assertEquals("Il Freccia del lunedì: nessuna corsa registrata · ultimi 30 giorni sulla rete: 90% in orario", WeeklyDigestText.summary(WeeklyDigest(sunday, listOf(freccia), null), strings))
    }

    @Test fun `the digest work has its own name and the setting is off by default in the panel order`() {
        assertEquals("binario-weekly-digest", WeeklyDigestScheduler.WORK)
        assertTrue(BackgroundCheck.WEEKLY_DIGEST in BackgroundCheck.entries)
    }
}
