package it.binario.app

import it.binario.app.data.Station
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalMemoryTest {
    @Test
    fun recentRouteKey_isStableAndDirectional() {
        val roma = Station("roma-termini", "Roma Termini", "Roma", "Lazio")
        val torino = Station("torino-pn", "Torino Porta Nuova", "Torino", "Piemonte")

        assertEquals("roma-termini|torino-pn", recentRouteKey(roma, torino))
        assertEquals("torino-pn|roma-termini", recentRouteKey(torino, roma))
    }
}
