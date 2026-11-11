package it.binario.app.ui

import it.binario.app.ui.common.ListRowLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListRowLayoutTest {
    @Test
    fun `the platform sits beside the destination at the usual text sizes and under it with large text`() {
        assertFalse(ListRowLayout.stacksSideColumn(1f))
        assertFalse(ListRowLayout.stacksSideColumn(1.15f))
        assertTrue(ListRowLayout.stacksSideColumn(1.3f))
        assertTrue(ListRowLayout.stacksSideColumn(2f))
    }

    @Test
    fun `a moved platform is long and goes under the destination at any text size`() {
        assertTrue(ListRowLayout.stacksSideColumn(1f, wideSide = true))
        assertTrue(ListRowLayout.stacksSideColumn(0.85f, wideSide = true))
    }
}
