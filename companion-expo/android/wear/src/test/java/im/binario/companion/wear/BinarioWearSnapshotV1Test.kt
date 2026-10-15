package im.binario.companion.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class BinarioWearSnapshotV1Test {
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
    "notice" to "Orario programmato locale: non è un aggiornamento ferroviario in tempo reale.",
    "serviceCount" to 0,
  )

  @Test fun `decodes canonical companion golden payload`() {
    val decoded = BinarioWearSnapshotV1.decode(golden(), now)
    assertNotNull(decoded)
    assertEquals("Roma Termini → Napoli Centrale", decoded?.routeLabel)
    assertEquals("PROGRAMMED", decoded?.operationalState)
  }

  @Test fun `rejects expired ttl mismatch and extra key without replacing cache`() {
    assertNull(BinarioWearSnapshotV1.decode(golden(), now + 5_400_000L))
    assertNull(BinarioWearSnapshotV1.decode(golden() + ("ttlMillis" to 5_400_001L), now))
    assertNull(BinarioWearSnapshotV1.decode(golden() + ("secret" to "not-allowed"), now))
  }

  @Test fun `maps future enum to unknown while preserving an otherwise valid snapshot`() {
    val decoded = BinarioWearSnapshotV1.decode(golden() + ("operationalState" to "FUTURE_STATE"), now)
    assertNotNull(decoded)
    assertEquals("UNKNOWN", decoded?.operationalState)
  }

  @Test fun `rejects empty route identity`() {
    assertNull(BinarioWearSnapshotV1.decode(golden() + ("routeId" to ""), now))
    assertNull(BinarioWearSnapshotV1.decode(golden() + ("routeLabel" to ""), now))
  }
}
