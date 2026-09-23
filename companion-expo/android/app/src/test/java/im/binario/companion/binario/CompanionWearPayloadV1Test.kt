package im.binario.companion.binario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class CompanionWearPayloadV1Test {
  private val now = 1_893_484_800_000L

  private fun golden(): Map<String, Any?> = linkedMapOf(
    "schemaVersion" to 1L,
    "generatedAtEpochMillis" to now,
    "expiresAtEpochMillis" to now + 5_400_000L,
    "ttlMillis" to 5_400_000L,
    "provenanceProducer" to "COMPANION",
    "provenanceDataNature" to "PROGRAMMED",
    "provenanceSourceLabel" to "Pianificazione locale del dispositivo",
    "provenanceObservedAtEpochMillis" to now,
    "operationalState" to "PROGRAMMED",
    "freshness" to "FRESH",
    "confidence" to "LOCAL_PLANNED",
    "routeId" to "local-42",
    "routeLabel" to "Roma Termini → Napoli Centrale",
    "direction" to "OUTBOUND",
    "notice" to "Orario programmato locale: non è un aggiornamento realtime ferroviario.",
    "serviceCount" to 0,
  )

  @Test fun `accepts canonical companion golden payload`() {
    val decoded = CompanionWearPayloadV1.decode(golden(), now)
    assertNotNull(decoded)
    assertEquals(golden(), decoded)
  }

  @Test fun `rejects expired and tampered payloads`() {
    assertNull(CompanionWearPayloadV1.decode(golden(), now + 5_400_000L))
    assertNull(CompanionWearPayloadV1.decode(golden() + ("ttlMillis" to 5_400_001L), now))
    assertNull(CompanionWearPayloadV1.decode(golden() + ("secret" to "not-allowed"), now))
  }

  @Test fun `rejects out of range service count and unsafe integers`() {
    assertNull(CompanionWearPayloadV1.decode(golden() + ("serviceCount" to Long.MAX_VALUE), now))
    assertNull(CompanionWearPayloadV1.decode(golden() + ("provenanceObservedAtEpochMillis" to 9_007_199_254_740_992L), now))
  }
}
