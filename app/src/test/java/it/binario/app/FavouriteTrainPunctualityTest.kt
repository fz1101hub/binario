package it.binario.app

import it.binario.app.data.reliability.ReliabilityFixtures
import it.binario.app.data.reliability.StopReliability
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Affidabilità dei miei treni»: the rows of the favourite trains, from the network statistics and the local history. */
class FavouriteTrainPunctualityTest {
    private val today = LocalDate.of(2026, 11, 9)
    private val stats = ReliabilityFixtures.stats(
        ReliabilityFixtures.FR_9558,
        ReliabilityFixtures.REG_2153_LOMBARDIA,
        ReliabilityFixtures.REG_2153_SICILIA,
        ReliabilityFixtures.REG_2155_CANCELLED
    )
    private val codes = mapOf("Roma Termini" to "S08409", "Milano Centrale" to "S01700", "Palermo Centrale" to "S12001")
    private val originCode: (ServiceMonitor) -> String? = { codes[it.origin] }

    private val frecciarossa = ServiceMonitor("9558", "Frecciarossa · Torino Porta Nuova", origin = "Roma Termini", scheduledDeparture = "07:10")
    private val regional = ServiceMonitor("2153", "Regionale · Pavia", customName = "Il mio treno delle 7:42", origin = "Milano Centrale")
    private val trenord = ServiceMonitor("RE_11 - 2153", "Trenord · Pavia", origin = "Milano Centrale")
    private val intercity = ServiceMonitor("701", "Intercity · Napoli Centrale")

    private fun run(daysAgo: Long, number: String, delay: Int, cancelled: Boolean = false) =
        PunctualityObservation(today.minusDays(daysAgo), PunctualityHistory.trainKey(number), "Trenitalia", delay, cancelled)

    private fun row(monitor: ServiceMonitor, history: List<PunctualityObservation> = emptyList()) =
        FavouriteTrainPunctuality.rows(listOf(monitor), history, today, stats, originCode).single()

    @Test fun `the network figures are those of the train's last stop with its own on-time limit`() {
        // FR 9558 at Torino Porta Nuova within 5 minutes: 20 of 30, mean 6.2, 1 day cancelled of 31.
        assertEquals(
            StopReliability("S00219", samples = 30, onTime = 20, onTimeLimitMinutes = 5, meanDelayMinutes = 6.2, daysObserved = 31, daysCancelled = 1),
            row(frecciarossa).network
        )
        // REG 2153 from Milano Centrale at Pavia within 15 minutes: 26 of 28; the Sicilian 2153 is another train.
        assertEquals(StopReliability("S01860", 28, 26, 15, 4.0, 28, 0), row(regional).network)
        assertEquals(StopReliability("S12002", 28, 21, 15, 9.0, 30, 2), row(regional.copy(origin = "Palermo Centrale")).network)
    }

    @Test fun `no network figures without a match, for an ambiguous number, below five samples or without statistics`() {
        // Saved without an origin: the only 9558 is enough, two 2153 are not.
        assertEquals(20, row(frecciarossa.copy(origin = "")).network?.onTime)
        assertNull(row(regional.copy(origin = "")).network)
        assertNull(row(intercity).network)
        // Cancelled on every day observed, or a stop with 4 samples: nothing to show.
        assertNull(row(ServiceMonitor("2155", "Regionale · Pavia", origin = "Milano Centrale")).network)
        val few = ReliabilityFixtures.stats(
            ReliabilityFixtures.train(
                "700", "S08409", "IC", 4, 0,
                ReliabilityFixtures.stop("S08409", 4, 0.0, listOf(4, 4, 4, 4, 4, 4, 4, 4)),
                ReliabilityFixtures.stop("S01700", 4, 2.0, listOf(1, 2, 3, 4, 4, 4, 4, 4))
            )
        )
        assertNull(FavouriteTrainPunctuality.rows(listOf(ServiceMonitor("700", "Intercity", origin = "Roma Termini")), emptyList(), today, few, originCode).single().network)
        // The build without the statistics (-Pbinario.punctualityStats=false) has none: the personal record still shows.
        val switchedOff = FavouriteTrainPunctuality.rows(listOf(frecciarossa), listOf(run(1, "9558", 3)), today, null, originCode).single()
        assertNull(switchedOff.network)
        assertEquals(LocalPunctuality(runs = 1, onTime = 1, cancelled = 0), switchedOff.personal)
        assertTrue(switchedOff.hasData)
    }

    @Test fun `the personal record counts the last 30 days on time within 5 minutes, cancellations apart`() {
        val history = listOf(
            run(0, "9558", 5), run(3, "9558", 6), run(10, "9558", 0, cancelled = true), run(29, "9558", 2),
            // Before the window, and another train.
            run(30, "9558", 0), run(2, "2153", 0)
        )
        assertEquals(LocalPunctuality(runs = 4, onTime = 2, cancelled = 1), row(frecciarossa, history).personal)
        assertNull(row(frecciarossa, listOf(run(30, "9558", 0))).personal)
    }

    @Test fun `Trenord numbers find their network train by number and their own runs by the full number`() {
        val history = listOf(run(1, "RE_11 - 2153", 3), run(2, "re_11 - 2153", 20), run(3, "RE_11  -  2153", 0))
        val trenordRow = row(trenord, history)
        // «RE_11 - 2153» is train 2153 of the statistics; the saved origin picks the Lombard one.
        assertEquals(StopReliability("S01860", 28, 26, 15, 4.0, 28, 0), trenordRow.network)
        assertEquals(LocalPunctuality(runs = 3, onTime = 2, cancelled = 0), trenordRow.personal)
        // Without the origin's code two trains have the number: no guess.
        assertNull(FavouriteTrainPunctuality.rows(listOf(trenord), history, today, stats) { null }.single().network)
    }

    @Test fun `a trend needs four runs in each of the two four-week spans and 15 points between their shares`() {
        // Last 4 weeks (days 0–27): 2 of 4 on time; the 4 before (days 28–55): 4 of 4. 50 points worse.
        val worse = (0L..3L).map { run(it * 7, "9558", if (it < 2) 0 else 25) } + (28L..55L step 7).map { run(it, "9558", 1) }
        val trend = requireNotNull(row(frecciarossa, worse).trend)
        assertEquals(LocalPunctuality(4, 2, 0), trend.recent)
        assertEquals(LocalPunctuality(4, 4, 0), trend.earlier)
        assertFalse(trend.better)
        // The other way round: better.
        val better = (0L..3L).map { run(it * 7, "9558", 0) } + (28L..55L step 7).map { run(it, "9558", if (it < 42) 30 else 0) }
        assertTrue(requireNotNull(row(frecciarossa, better).trend).better)
        // Three runs in the last four weeks are not enough, nor three in the weeks before.
        assertNull(row(frecciarossa, worse.filterNot { it.serviceDate == today }).trend)
        assertNull(row(frecciarossa, worse.filterNot { it.serviceDate == today.minusDays(28) }).trend)
        // A cancellation is a run not on time.
        val cancelled = (0L..3L).map { run(it, "9558", 0, cancelled = it < 2) } + (28L..31L).map { run(it, "9558", 0) }
        assertEquals(LocalPunctuality(4, 2, 2), requireNotNull(row(frecciarossa, cancelled).trend).recent)
        // Day 56 is outside both spans.
        assertEquals(4, requireNotNull(row(frecciarossa, worse + run(56, "9558", 0)).trend).earlier.runs)
    }

    @Test fun `the 15 points are exact, not between rounded percentages`() {
        // 3 of 4 (75 %) against 6 of 10 (60 %): exactly 15 points.
        val exact = (0L..9L).map { run(it, "9558", if (it < 6) 0 else 20) } + (28L..31L).map { run(it, "9558", if (it < 31) 0 else 20) }
        val trend = requireNotNull(row(frecciarossa, exact).trend)
        assertEquals(LocalPunctuality(10, 6, 0), trend.recent)
        assertEquals(LocalPunctuality(4, 3, 0), trend.earlier)
        assertFalse(trend.better)
        // 3 of 4 (75 %) against 5 of 8 (62.5 %): 12.5 points.
        val small = (0L..7L).map { run(it, "9558", if (it < 5) 0 else 20) } + (28L..31L).map { run(it, "9558", if (it < 31) 0 else 20) }
        assertNull(row(frecciarossa, small).trend)
        // 11 of 18 (61.1 %, rounded 61) against 6 of 13 (46.2 %, rounded 46): the rounded gap is 15, the real one 14.96.
        val rounded = (0L..12L).map { run(it, "9558", if (it < 6) 0 else 20) } + (28L..45L).map { run(it, "9558", if (it < 39) 0 else 20) }
        assertNull(row(frecciarossa, rounded).trend)
    }

    @Test fun `a train with neither network nor personal figures has no data, and at most 8 trains are listed in order`() {
        val nothing = row(intercity)
        assertNull(nothing.network)
        assertNull(nothing.personal)
        assertNull(nothing.trend)
        assertFalse(nothing.hasData)
        val many = (1..10).map { ServiceMonitor("${100 + it}", "Regionale") }
        val rows = FavouriteTrainPunctuality.rows(many, emptyList(), today, stats, originCode)
        assertEquals(FavouriteTrainPunctuality.MAX_ROWS, rows.size)
        assertEquals(many.take(8), rows.map { it.monitor })
        assertEquals(emptyList<FavouriteTrainReliability>(), FavouriteTrainPunctuality.rows(emptyList(), listOf(run(0, "9558", 0)), today, stats, originCode))
    }
}
