package it.binario.app

import it.binario.app.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationProximityTest {
    @Test
    fun `orders catalog stations by distance from current point`() {
        val termini = Station("roma-termini", "Roma Termini", "Roma")
        val tiburtina = Station("roma-tiburtina", "Roma Tiburtina", "Roma")
        val torino = Station("torino-pn", "Torino Porta Nuova", "Torino")

        val nearby = StationCoordinates.nearby(listOf(torino, tiburtina, termini), 41.9008, 12.5016)

        assertEquals("roma-termini", nearby.first().station.id)
        assertTrue(nearby.first().distanceKm < 0.01)
        assertEquals("torino-pn", nearby.last().station.id)
    }
}
