package it.binario.app

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
}
