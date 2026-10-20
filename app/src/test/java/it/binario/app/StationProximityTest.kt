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

    @Test
    fun `returns no stations for non finite user coordinates`() {
        val station = Station("curated:S08409", "Roma Termini", "Roma", latitude = 41.9008, longitude = 12.5016)

        assertTrue(StationCoordinates.nearby(listOf(station), Double.NaN, 12.5016).isEmpty())
        assertTrue(StationCoordinates.nearby(listOf(station), 41.9008, Double.POSITIVE_INFINITY).isEmpty())
    }

    @Test
    fun `ignores invalid station coordinates and uses an italian name tie break`() {
        val zeta = Station("curated:Z", "Zagarolo", "Roma", latitude = 41.9008, longitude = 12.5016)
        val abano = Station("curated:A", "Abano", "Padova", latitude = 41.9008, longitude = 12.5016)
        val invalid = Station("curated:X", "Coordinate invalide", "Roma", latitude = Double.NaN, longitude = 12.5016)

        val nearby = StationCoordinates.nearby(listOf(zeta, invalid, abano), 41.9008, 12.5016)

        assertEquals(listOf("curated:A", "curated:Z"), nearby.map { it.station.id })
    }
}
