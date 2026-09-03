package it.binario.app

import it.binario.app.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationProximityTest {
    @Test
    fun `orders catalog stations by distance from current point`() {
        val termini = Station("curated:S08409", "Roma Termini", "Roma", latitude = 41.9008, longitude = 12.5016)
        val tiburtina = Station("curated:S08217", "Roma Tiburtina", "Roma", latitude = 41.9100, longitude = 12.5307)
        val torino = Station("curated:S00200", "Torino Porta Nuova", "Torino", latitude = 45.0620, longitude = 7.6785)

        val nearby = StationCoordinates.nearby(listOf(torino, tiburtina, termini), 41.9008, 12.5016)

        assertEquals("curated:S08409", nearby.first().station.id)
        assertTrue(nearby.first().distanceKm < 0.01)
        assertEquals("curated:S00200", nearby.last().station.id)
    }
}
