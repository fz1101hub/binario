package it.binario.app.ui.departures

import org.junit.Assert.assertEquals
import org.junit.Test

class StationBoardListCapTest {
    @Test
    fun `a board up to the cap is shown whole and says nothing`() {
        for (size in listOf(0, 1, 59, 60)) {
            val entries = List(size) { it }
            assertEquals(entries, StationBoardListCap.shown(entries))
            assertEquals(0, StationBoardListCap.leftOut(entries))
        }
    }

    @Test
    fun `a longer board shows the first rows and counts every train it leaves out`() {
        val entries = List(150) { it }
        assertEquals((0 until 60).toList(), StationBoardListCap.shown(entries))
        assertEquals(90, StationBoardListCap.leftOut(entries))
        assertEquals(1, StationBoardListCap.leftOut(List(61) { it }))
    }
}
