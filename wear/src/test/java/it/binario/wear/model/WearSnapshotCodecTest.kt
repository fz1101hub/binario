package it.binario.wear.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearSnapshotCodecTest {
    private val now = 1_893_484_800_000L

    @Test
    fun `le tre fixture golden v1 sono decodificabili`() {
        val android = WearSnapshotCodec.decode(fixture("android-to-wear-v1.json"), now)
        val companion = WearSnapshotCodec.decode(fixture("companion-to-wear-v1.json"), now)
        val ios = WearSnapshotCodec.decode(fixture("ios-to-apple-watch-v1.json"), now)

        assertEquals(2, android?.services?.size)
        assertEquals(WearOperationalState.OPERATIONAL, android?.operationalState)
        assertEquals(WearProvenanceProducer.COMPANION, companion?.provenance?.producer)
        assertEquals(WearOperationalState.PROGRAMMED, companion?.operationalState)
        assertEquals(WearProvenanceProducer.IOS_APP, ios?.provenance?.producer)
    }

    @Test
    fun `versione futura campo extra ttl incoerente e snapshot scaduto sono rifiutati`() {
        val valid = fixture("android-to-wear-v1.json")
        assertNull(WearSnapshotCodec.decode(valid + ("schemaVersion" to 2L), now))
        assertNull(WearSnapshotCodec.decode(valid + ("unexpected" to "value"), now))
        assertNull(WearSnapshotCodec.decode(valid + ("ttlMillis" to 1L), now))
        assertNull(WearSnapshotCodec.decode(valid, 1_893_485_700_001L))
    }

    @Test
    fun `enum futuro diventa unknown senza attribuire stato operativo`() {
        val valid = fixture("companion-to-wear-v1.json")
        val decoded = WearSnapshotCodec.decode(
            valid + mapOf(
                "provenanceProducer" to "FUTURE_PRODUCER",
                "provenanceDataNature" to "FUTURE_NATURE",
                "operationalState" to "FUTURE_STATE",
                "freshness" to "FUTURE_FRESHNESS",
                "confidence" to "FUTURE_CONFIDENCE",
                "direction" to "FUTURE_DIRECTION"
            ),
            now
        )

        assertNotNull(decoded)
        assertEquals(WearProvenanceProducer.UNKNOWN, decoded?.provenance?.producer)
        assertEquals(WearDataNature.UNKNOWN, decoded?.provenance?.dataNature)
        assertEquals(WearOperationalState.UNKNOWN, decoded?.operationalState)
        assertEquals(WearFreshness.UNKNOWN, decoded?.freshness)
        assertEquals(WearConfidence.UNKNOWN, decoded?.confidence)
        assertEquals(WearDirection.UNKNOWN, decoded?.direction)
    }

    @Test
    fun `decoder accepts a normalized notice at the truncation boundary`() {
        val expectedNotice = "x".repeat(179)
        val decoded = WearSnapshotCodec.decode(fixture("android-to-wear-v1.json") + ("notice" to expectedNotice), now)

        assertEquals(expectedNotice, decoded?.notice)
    }

    @Test
    fun `payload oltre budget non sostituisce la cache`() {
        val oversized = fixture("android-to-wear-v1.json") + ("notice" to "x".repeat(WearSnapshotCodec.MAX_PAYLOAD_BYTES))
        assertNull(WearSnapshotCodec.decode(oversized, now))
    }

    @Test
    fun `round trip mantiene lo snapshot e le label accessibili`() {
        val decoded = requireNotNull(WearSnapshotCodec.decode(fixture("android-to-wear-v1.json"), now))
        val roundTrip = WearSnapshotCodec.decode(WearSnapshotCodec.encode(decoded), now)

        assertEquals(decoded, roundTrip)
        assertTrue(roundTrip?.services?.first()?.accessibleLabel?.contains("Treno FR 9505") == true)
    }

    private fun fixture(name: String): Map<String, Any?> {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        return json.keys().asSequence().associateWith { key -> json.get(key) }
    }
}
