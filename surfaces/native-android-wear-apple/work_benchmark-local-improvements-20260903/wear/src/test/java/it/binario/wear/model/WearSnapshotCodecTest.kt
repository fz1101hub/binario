package it.binario.wear.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearSnapshotCodecTest {
    @Test
    fun `valid scheduled payload preserves only the bounded snapshot fields`() {
        val payload = mapOf<String, Any?>(
            "version" to 1L,
            "routeLabel" to "Casa – lavoro",
            "provider" to "GTFS locale",
            "freshness" to "AGING",
            "confidence" to "STATIC_ONLY",
            "retrievedAt" to 100L,
            "serviceCount" to 3L,
            "service0Number" to "R 101",
            "service0Destination" to "Roma Termini",
            "service0Expected" to "08:15",
            "service0Status" to "In orario",
            "service1Number" to "R 102",
            "service1Destination" to "Roma Ostiense"
        )

        val snapshot = WearSnapshotCodec.decode(payload)
        assertEquals("Orario programmato", snapshot?.dataStatusLabel)
        assertEquals(2, snapshot?.services?.size)
        assertTrue(snapshot?.services?.first()?.accessibleLabel?.contains("Treno R 101") == true)
    }

    @Test
    fun `unknown version or missing identity is rejected without replacing cache`() {
        assertNull(WearSnapshotCodec.decode(mapOf("version" to 2L)))
        assertNull(WearSnapshotCodec.decode(mapOf("version" to 1L, "routeLabel" to "Casa", "provider" to "GTFS", "freshness" to "AGING", "confidence" to "STATIC_ONLY")))
    }
}
