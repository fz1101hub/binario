package it.binario.app.data

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOnlyRailDataPolicyTest {
    @Test
    fun `local production policy exposes no direct network provider`() {
        assertTrue(LocalOnlyRailDataPolicy.routeSources().isEmpty())
        assertTrue(LocalOnlyRailDataPolicy.alertProviders().isEmpty())
        assertTrue(LocalOnlyRailDataPolicy.vehiclePositionProviders().isEmpty())
        assertTrue(LocalOnlyRailDataPolicy.tripUpdateProviders().isEmpty())
    }
}
