package it.binario.app.data.live

import it.binario.app.data.core.RouteChangeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalRouteChangeDetectorTest {
    @Test
    fun `riconosce una deviazione esplicita senza inventare il nuovo percorso`() {
        val change = OperationalRouteChangeDetector.detect(
            "Per guasto alla linea il treno è deviato via Caserta con variazione di percorso"
        )

        assertEquals(RouteChangeKind.DIVERTED, change?.kind)
        assertTrue(change?.summary.orEmpty().contains("Caserta"))
        assertEquals(listOf("Caserta"), change?.viaStopLabels)
        assertTrue(change?.structured == false)
    }

    @Test
    fun `riconosce una corsa limitata`() {
        val change = OperationalRouteChangeDetector.detect(
            "A causa di un inconveniente tecnico il treno è limitato a Firenze S.M.N."
        )

        assertEquals(RouteChangeKind.SHORT_TURN, change?.kind)
        assertEquals("Firenze S.M.N", change?.replacementDestinationLabel)
    }

    @Test
    fun `estrae una fermata saltata solo quando e nominata`() {
        val change = OperationalRouteChangeDetector.detect(
            "Per guasto il treno non effettua fermata a Formia-Gaeta"
        )

        assertEquals(RouteChangeKind.SKIPPED_STOPS, change?.kind)
        assertEquals(listOf("Formia-Gaeta"), change?.skippedStopLabels)
        assertTrue(change?.structured == true)
    }

    @Test
    fun `non trasforma una nota ordinaria in una variazione`() {
        assertNull(OperationalRouteChangeDetector.detect("Regolare instradamento"))
    }
}
