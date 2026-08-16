package it.binario.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import it.binario.app.data.Station

class UnconfiguredRouteTest {
    @Test
    fun `initial route is a neutral placeholder rather than a railway preset`() {
        val route = unconfiguredRoute()

        assertEquals(UNCONFIGURED_ROUTE_ID, route.id)
        assertEquals("Nessuna tratta selezionata", route.name)
        assertFalse(route.displayName(it.binario.app.data.Direction.OUTBOUND).contains("Roma", ignoreCase = true))
    }

    @Test
    fun `search route identity preserves the requested origin and destination order`() {
        val appiano = Station("curated:S08328", "Appiano Proba Petronia", "Appiano")
        val ostiense = Station("curated:S08406", "Roma Ostiense", "Roma")

        val forward = searchedRouteId(appiano, ostiense)
        val reverse = searchedRouteId(ostiense, appiano)

        assertEquals("search-curated:S08328-curated:S08406", forward)
        assertEquals("search-curated:S08406-curated:S08328", reverse)
        assertFalse(forward == reverse)
    }
}
