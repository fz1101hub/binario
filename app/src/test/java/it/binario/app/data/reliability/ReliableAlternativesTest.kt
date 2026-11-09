package it.binario.app.data.reliability

import it.binario.app.data.reliability.ReliabilityFixtures.stop
import it.binario.app.data.reliability.ReliabilityFixtures.train
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Alternativa più puntuale»: which train of a list is suggested instead of a train often late at the destination. */
class ReliableAlternativesTest {
    private val day = LocalDate.of(2026, 11, 21)

    private fun at(time: String): LocalDateTime = day.atTime(LocalTime.parse(time))

    /** A train at the destination: [onTime] of [samples] within [limit] minutes. */
    private fun arrival(onTime: Int, samples: Int, limit: Int = NetworkReliability.OTHER_ON_TIME_MINUTES) =
        StopReliability("S00219", samples, onTime, limit, 4.0, samples, 0)

    private fun candidate(key: String, time: String?, onTime: Int, samples: Int, stillToDepart: Boolean = true, limit: Int = 15) =
        AlternativeCandidate(key, time?.let(::at), arrival(onTime, samples, limit), stillToDepart)

    private fun alternativeOf(key: String, vararg candidates: AlternativeCandidate): String? = ReliableAlternatives.find(candidates.toList())[key]?.key

    @Test fun `a train on time less than 70 percent of the days gets a train at least 20 points better`() {
        val late = candidate("a", "08:00", 20, 30)
        val punctual = candidate("b", "08:12", 27, 30)
        val found = ReliableAlternatives.find(listOf(late, punctual))
        assertEquals(setOf("a"), found.keys)
        val alternative = found.getValue("a")
        assertEquals("b", alternative.key)
        assertEquals(at("08:12"), alternative.departure)
        assertEquals(27, alternative.stop.onTime)
        assertEquals(30, alternative.stop.samples)
        // Exactly 70 percent is not «often late»; 69 is.
        assertNull(alternativeOf("a", candidate("a", "08:00", 70, 100), candidate("b", "08:12", 100, 100)))
        assertEquals("b", alternativeOf("a", candidate("a", "08:00", 69, 100), candidate("b", "08:12", 100, 100)))
        assertEquals("b", alternativeOf("a", candidate("a", "08:00", 20, 29), candidate("b", "08:12", 29, 29)))
        assertNull(alternativeOf("a", candidate("a", "08:00", 21, 30), candidate("b", "08:12", 30, 30)))
    }

    @Test fun `the gap is at least 20 points, counted exactly`() {
        // 7/10 − 5/10 is 0.19999999999999996 in floating point: still 20 points.
        assertEquals("b", alternativeOf("a", candidate("a", "08:00", 5, 10), candidate("b", "08:10", 7, 10)))
        assertNull(alternativeOf("a", candidate("a", "08:00", 10, 20), candidate("b", "08:10", 13, 20)))
        // Different sample counts: 3/7 (42.9 %) against 9/14 (64.3 %) and 8/14 (57.1 %).
        assertEquals("b", alternativeOf("a", candidate("a", "08:00", 3, 7), candidate("b", "08:10", 9, 14)))
        assertNull(alternativeOf("a", candidate("a", "08:00", 3, 7), candidate("b", "08:10", 8, 14)))
        // A train just as late, or later, is never suggested.
        assertNull(alternativeOf("a", candidate("a", "08:00", 10, 30), candidate("b", "08:10", 10, 30)))
    }

    @Test fun `only trains leaving within 30 minutes before or after count`() {
        val late = candidate("a", "08:00", 10, 30)
        assertEquals("b", alternativeOf("a", late, candidate("b", "08:30", 30, 30)))
        assertEquals("b", alternativeOf("a", late, candidate("b", "07:30", 30, 30)))
        assertNull(alternativeOf("a", late, candidate("b", "08:31", 30, 30)))
        assertNull(alternativeOf("a", late, candidate("b", "07:29", 30, 30)))
        // Across midnight the departures are compared as date and time.
        val lateNight = AlternativeCandidate("n", LocalDateTime.of(day, LocalTime.of(23, 50)), arrival(10, 30), true)
        val afterMidnight = AlternativeCandidate("m", LocalDateTime.of(day.plusDays(1), LocalTime.of(0, 15)), arrival(30, 30), true)
        assertEquals("m", alternativeOf("n", lateNight, afterMidnight))
        // A row without a readable departure takes no part, either way.
        assertNull(alternativeOf("a", late, candidate("b", null, 30, 30)))
        assertNull(alternativeOf("a", candidate("a", null, 10, 30), candidate("b", "08:10", 30, 30)))
    }

    @Test fun `both trains need at least 5 samples at the destination`() {
        assertNull(alternativeOf("a", candidate("a", "08:00", 1, 4), candidate("b", "08:10", 30, 30)))
        assertEquals("b", alternativeOf("a", candidate("a", "08:00", 1, 5), candidate("b", "08:10", 30, 30)))
        assertNull(alternativeOf("a", candidate("a", "08:00", 10, 30), candidate("b", "08:10", 4, 4)))
        assertEquals("b", alternativeOf("a", candidate("a", "08:00", 10, 30), candidate("b", "08:10", 5, 5)))
        // No statistics, or a train cancelled on every day observed (no delays at all): neither side.
        assertNull(alternativeOf("a", AlternativeCandidate("a", at("08:00"), null, true), candidate("b", "08:10", 30, 30)))
        val alwaysCancelled = AlternativeCandidate("a", at("08:00"), StopReliability("S01700", 0, 0, 15, 0.0, 12, 12), true)
        assertNull(alternativeOf("a", alwaysCancelled, candidate("b", "08:10", 30, 30)))
        assertNull(alternativeOf("a", candidate("a", "08:00", 10, 30), AlternativeCandidate("b", at("08:10"), null, true)))
    }

    @Test fun `ties go to the closest departure, then the earlier one, then the more punctual one`() {
        val late = candidate("a", "08:00", 10, 30)
        // Closest first, however punctual the others are.
        assertEquals("near", alternativeOf("a", late, candidate("far", "08:20", 30, 30), candidate("near", "08:05", 25, 30), candidate("before", "07:50", 30, 30)))
        // As close before as after: the earlier one.
        assertEquals("before", alternativeOf("a", late, candidate("after", "08:10", 30, 30), candidate("before", "07:50", 25, 30)))
        // Same departure: the more punctual one, then the one with more samples, then the key.
        assertEquals("best", alternativeOf("a", late, candidate("good", "08:10", 25, 30), candidate("best", "08:10", 29, 30)))
        assertEquals("more", alternativeOf("a", late, candidate("fewer", "08:10", 9, 10), candidate("more", "08:10", 27, 30)))
        assertEquals("b1", alternativeOf("a", late, candidate("b2", "08:10", 27, 30), candidate("b1", "08:10", 27, 30)))
        // A train leaving at the same minute as the late one is the closest of all.
        assertEquals("same", alternativeOf("a", late, candidate("same", "08:00", 25, 30), candidate("after", "08:01", 30, 30)))
    }

    @Test fun `trains already gone or cancelled are never suggested, and get no suggestion`() {
        val late = candidate("a", "08:00", 10, 30)
        assertNull(alternativeOf("a", late, candidate("b", "08:10", 30, 30, stillToDepart = false)))
        assertEquals("c", alternativeOf("a", late, candidate("b", "08:05", 30, 30, stillToDepart = false), candidate("c", "08:20", 30, 30)))
        assertNull(alternativeOf("a", candidate("a", "08:00", 10, 30, stillToDepart = false), candidate("b", "08:10", 30, 30)))
    }

    @Test fun `every late train of the list gets its own alternative, never itself`() {
        val found = ReliableAlternatives.find(
            listOf(
                candidate("a", "08:00", 10, 30),
                candidate("b", "08:20", 15, 30),
                candidate("c", "08:40", 29, 30),
                candidate("d", "09:30", 5, 30)
            )
        )
        // a: c is 40 minutes away, b only 17 points better; b: c, 20 minutes later; d: nothing within 30 minutes.
        assertEquals(mapOf("b" to "c"), found.mapValues { it.value.key })
        assertTrue(ReliableAlternatives.find(listOf(candidate("a", "08:00", 10, 30))).isEmpty())
        assertTrue(ReliableAlternatives.find(emptyList()).isEmpty())
    }

    @Test fun `each train is measured against its own limit, 5 minutes for a Frecciarossa and 15 for a regional`() {
        val stats = ReliabilityFixtures.stats(
            // Roma → Torino: 18 of 30 within 5 minutes (60 %), 29 of 30 within 15.
            train("9560", "S08409", "FR", 30, 0,
                stop("S08409", 30, 0.5, listOf(25, 28, 30, 30, 30, 30, 30, 30)),
                stop("S00219", 30, 6.0, listOf(6, 12, 18, 25, 29, 30, 30, 30))),
            // A regional to Torino: 15 of 30 within 5 minutes, 27 of 30 within 15 (90 %).
            train("2041", "S08409", "REG", 30, 0,
                stop("S08409", 30, 0.5, listOf(25, 28, 30, 30, 30, 30, 30, 30)),
                stop("S00219", 30, 7.0, listOf(5, 10, 15, 22, 27, 29, 30, 30))),
            // Another regional: 18 of 30 within 15 minutes (60 %).
            train("2043", "S08409", "REG", 30, 0,
                stop("S08409", 30, 0.5, listOf(25, 28, 30, 30, 30, 30, 30, 30)),
                stop("S00219", 30, 12.0, listOf(2, 5, 8, 12, 18, 24, 28, 30))),
            // A Frecciarossa 27 of 30 within 5 minutes (90 %).
            train("9562", "S08409", "FR", 30, 0,
                stop("S08409", 30, 0.5, listOf(25, 28, 30, 30, 30, 30, 30, 30)),
                stop("S00219", 30, 2.0, listOf(20, 24, 27, 30, 30, 30, 30, 30)))
        )
        fun measured(number: String, time: String): AlternativeCandidate {
            val match = requireNotNull(NetworkReliability.match(stats, number, "S00219", boardingCode = "S08409"))
            return AlternativeCandidate(number, at(time), NetworkReliability.at(match.train, match.alightingIndex), true)
        }
        val fr9560 = measured("9560", "08:00")
        val reg2041 = measured("2041", "08:10")
        assertEquals(5, fr9560.stop?.onTimeLimitMinutes)
        assertEquals(18, fr9560.stop?.onTime)
        assertEquals(15, reg2041.stop?.onTimeLimitMinutes)
        assertEquals(27, reg2041.stop?.onTime)
        // The Frecciarossa at 5 minutes (60 %) gets the regional at 15 (90 %); at 5 minutes the regional would be 50 %.
        assertEquals("2041", alternativeOf("9560", fr9560, reg2041))
        assertTrue(ReliableAlternatives.oftenLate(fr9560.stop!!))
        assertFalse(ReliableAlternatives.clearlyBetter(reg2041.stop!!.copy(onTime = 15, onTimeLimitMinutes = 5), fr9560.stop!!))
        // The other way round: a regional at 15 minutes (60 %) gets a Frecciarossa at 5 (90 %).
        assertEquals("9562", alternativeOf("2043", measured("2043", "08:00"), measured("9562", "08:20")))
        // A regional at 90 % within 15 minutes is not «often late», though only half of its arrivals are within 5.
        assertNull(alternativeOf("2041", reg2041, measured("9562", "08:20")))
    }
}
