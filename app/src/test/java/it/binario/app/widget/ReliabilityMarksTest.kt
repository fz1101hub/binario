package it.binario.app.widget

import it.binario.app.data.reliability.ReliabilityFixtures
import it.binario.app.data.reliability.ReliabilityFixtures.stop
import it.binario.app.data.reliability.ReliabilityFixtures.train
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReliabilityMarksTest {
    private val stats = ReliabilityFixtures.stats(
        ReliabilityFixtures.FR_9558,
        ReliabilityFixtures.REG_2153_LOMBARDIA,
        ReliabilityFixtures.REG_2153_SICILIA,
        ReliabilityFixtures.REG_2155_CANCELLED,
        ReliabilityFixtures.REG_2041
    )

    @Test fun `a route train is measured at the destination of the route with its own on-time limit`() {
        // FR 9558 Roma Termini → Milano Centrale: 22 of 30 within 5 minutes at Milano.
        assertEquals(ReliabilityMark(22, 30, 5, 22, 30), ReliabilityMarks.route(stats, "FR 9558", "S08409", "S01700"))
        assertEquals(73, ReliabilityMarks.route(stats, "9558", "S08409", "S01700")?.percent)
        // REG 2041 to Torino at 15 minutes: 29 of 30, 24 within 5.
        assertEquals(ReliabilityMark(29, 30, 15, 24, 30), ReliabilityMarks.route(stats, "2041", "S01700", "S00219"))
    }

    @Test fun `no route mark without codes, for another route or below five samples`() {
        assertNull(ReliabilityMarks.route(stats, "9558", "S08409", null))
        assertNull(ReliabilityMarks.route(stats, "9558", "S01700", "S08409"))
        assertNull(ReliabilityMarks.route(stats, "1234", "S08409", "S01700"))
        val few = ReliabilityFixtures.stats(train("700", "S08409", "IC", 4, 0, stop("S08409", 4, 0.0, listOf(4, 4, 4, 4, 4, 4, 4, 4)), stop("S01700", 4, 2.0, listOf(1, 2, 3, 4, 4, 4, 4, 4))))
        assertNull(ReliabilityMarks.route(few, "700", "S08409", "S01700"))
    }

    @Test fun `a favourite train is measured at its last stop, the same number told apart by its origin`() {
        assertEquals(ReliabilityMark(20, 30, 5, 20, 30), ReliabilityMarks.favourite(stats, "9558", "S08409"))
        // Without a code the only train with that number is enough.
        assertEquals(ReliabilityMark(20, 30, 5, 20, 30), ReliabilityMarks.favourite(stats, "9558", null))
        // REG 2153 runs in Lombardy and in Sicily: the origin decides, and without it there is no guess.
        assertEquals(ReliabilityMark(26, 28, 15, 20, 30), ReliabilityMarks.favourite(stats, "2153", "S01700"))
        assertEquals(ReliabilityMark(21, 28, 15, 12, 30), ReliabilityMarks.favourite(stats, "2153", "S12001"))
        assertNull(ReliabilityMarks.favourite(stats, "2153", null))
    }

    @Test fun `no favourite mark for an unknown origin, a train cancelled every day or no number`() {
        assertNull(ReliabilityMarks.favourite(stats, "9558", "S99999"))
        assertNull(ReliabilityMarks.favourite(stats, "2155", "S01700"))
        assertNull(ReliabilityMarks.favourite(stats, "2155", null))
        assertNull(ReliabilityMarks.favourite(stats, " ", null))
    }

    @Test fun `percentages round to the nearest point`() {
        assertEquals(67, ReliabilityMark(20, 30, 5, 20, 30).percent)
        assertEquals(83, ReliabilityMark(25, 30, 15, 17, 30).percent)
        assertEquals(57, ReliabilityMark(25, 30, 15, 17, 30).within5Percent)
        assertNull(ReliabilityMark(25, 30, 15, null, 30).within5Percent)
    }
}
