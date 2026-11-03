package it.binario.app.widget

import it.binario.app.data.Freshness
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetPresentationTest {
    @Test fun `small widget uses compact density`() = assertEquals(WidgetLayoutMode.COMPACT, WidgetLayoutMode.fromSize(220, 150))
    @Test fun `standard widget keeps five row tabellone`() = assertEquals(5, WidgetLayoutMode.fromSize(320, 250).visibleRows)
    @Test fun `large widget uses expanded density`() = assertEquals(WidgetLayoutMode.EXPANDED, WidgetLayoutMode.fromSize(400, 320))

    @Test fun `compact pagination moves by visible rows and clamps the final offset`() {
        assertEquals(2, WidgetRowPagination.move(0, 1, serviceCount = 7, visibleRows = 2))
        assertEquals(6, WidgetRowPagination.move(4, 1, serviceCount = 7, visibleRows = 2))
        assertEquals(4, WidgetRowPagination.pageCount(serviceCount = 7, visibleRows = 2))
    }

    @Test fun `freshness is derived from snapshot age at render time`() {
        val now = 1_000_000L
        assertEquals(Freshness.FRESH, WidgetFreshness.fromAge(now - 4 * 60_000L, now))
        assertEquals(Freshness.AGING, WidgetFreshness.fromAge(now - 5 * 60_000L, now))
        assertEquals(Freshness.STALE, WidgetFreshness.fromAge(now - 15 * 60_000L, now))
        assertEquals(Freshness.UNAVAILABLE, WidgetFreshness.fromAge(0L, now))
    }
}
