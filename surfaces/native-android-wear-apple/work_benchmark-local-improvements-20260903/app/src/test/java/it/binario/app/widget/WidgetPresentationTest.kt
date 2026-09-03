package it.binario.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetPresentationTest {
    @Test fun `small widget uses compact density`() = assertEquals(WidgetLayoutMode.COMPACT, WidgetLayoutMode.fromSize(220, 150))
    @Test fun `standard widget keeps five row tabellone`() = assertEquals(5, WidgetLayoutMode.fromSize(320, 250).visibleRows)
    @Test fun `large widget uses expanded density`() = assertEquals(WidgetLayoutMode.EXPANDED, WidgetLayoutMode.fromSize(400, 320))
}
