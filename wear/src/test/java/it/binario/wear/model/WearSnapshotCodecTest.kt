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
    fun `un avviso oltre il proprio limite non sostituisce la cache`() {
        val oversized = fixture("android-to-wear-v1.json") + ("notice" to "x".repeat(WearSnapshotCodec.MAX_PAYLOAD_BYTES))
        assertNull(WearSnapshotCodec.decode(oversized, now))
    }

    @Test
    fun `payload oltre budget non sostituisce la cache anche quando ogni campo è valido`() {
        val valid = fixture("android-to-wear-v1.json")
        val size = WearSnapshotCodec.estimatedUtf8Size(valid)

        assertNotNull(WearSnapshotCodec.decode(valid, now, maxPayloadBytes = size))
        assertNull(WearSnapshotCodec.decode(valid, now, maxPayloadBytes = size - 1))
    }

    @Test
    fun `il payload valido più grande resta sotto il budget`() {
        // Every text at its limit in three-byte characters: the 8,192-byte budget is a safety net, not a field limit.
        val largest = fixture("android-to-wear-v1-reliability.json").mapValues { (key, value) ->
            val limit = TEXT_LIMITS.entries.firstOrNull { key.endsWith(it.key) }?.value
            if (value is String && limit != null) "€".repeat(limit) else value
        } + ("service1ReliabilityOnTime" to 1L) + ("service1ReliabilitySamples" to 5L)

        assertNotNull(WearSnapshotCodec.decode(largest, now))
        assertTrue(WearSnapshotCodec.estimatedUtf8Size(largest) < WearSnapshotCodec.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `round trip mantiene lo snapshot e le label accessibili`() {
        val decoded = requireNotNull(WearSnapshotCodec.decode(fixture("android-to-wear-v1.json"), now))
        val roundTrip = WearSnapshotCodec.decode(WearSnapshotCodec.encode(decoded), now)

        assertEquals(decoded, roundTrip)
    }

    private val TEXT_LIMITS = mapOf(
        "provenanceSourceLabel" to 96, "routeId" to 64, "routeLabel" to 96, "notice" to 180,
        "Number" to 24, "Operator" to 64, "Category" to 64, "Destination" to 96, "Status" to 64, "Platform" to 16,
    )

    private fun fixture(name: String): Map<String, Any?> {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        return json.keys().asSequence().associateWith { key -> json.get(key) }
    }
}
