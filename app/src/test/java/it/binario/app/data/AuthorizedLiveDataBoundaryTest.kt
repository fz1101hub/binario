package it.binario.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedLiveDataBoundaryTest {
    @Test
    fun `default boundary is fail closed and holds no client credential`() {
        val status = AuthorizedLiveDataBoundary.trenitalia(gatewayConfigured = false)
        assertFalse(status.allowsNetworkRequest)
        assertFalse(status.hasClientCredentials)
    }

    @Test
    fun `reserved adapter returns failure instead of calling an undocumented endpoint`() = runBlocking {
        val roma = Station("roma", "Roma Termini", "Roma")
        val torino = Station("torino", "Torino Porta Nuova", "Torino")
        val result = TrenitaliaViaggiaTrenoAdapter().loadRoute(SavedRoute("r", "Roma – Torino", roma, torino), Direction.OUTBOUND)
        assertTrue(result is DataResult.Failure)
    }
}
