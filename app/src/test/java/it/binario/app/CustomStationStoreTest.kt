package it.binario.app

import it.binario.app.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomStationStoreTest {
    @Test
    fun `free text station is normalized without fabricating schedule data`() {
        val station = stationFromFreeText("  San  Benedetto  del Tronto ")!!

        assertEquals("San Benedetto del Tronto", station.name)
        assertEquals("Ricerca libera", station.region)
        assertTrue(station.id.startsWith("manual:"))
    }

    @Test
    fun `stations still in use are never evicted by the recent list cap`() {
        val stations = (1..6).map { Station("s$it", "S$it", "S$it") }

        val kept = boundedRecentStations(stations + stations.first(), retainedIds = setOf("s6"), limit = 3)

        assertEquals(listOf("s1", "s2", "s3", "s6"), kept.map { it.id })
    }
}
