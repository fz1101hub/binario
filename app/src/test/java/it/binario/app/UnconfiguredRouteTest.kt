package it.binario.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UnconfiguredRouteTest {
    @Test
    fun `initial route is a neutral placeholder rather than a railway preset`() {
        val route = unconfiguredRoute()

        assertEquals(UNCONFIGURED_ROUTE_ID, route.id)
        assertEquals("Nessuna tratta selezionata", route.name)
        assertFalse(route.displayName(it.binario.app.data.Direction.OUTBOUND).contains("Roma", ignoreCase = true))
    }
}
