package im.binario.companion.binario

import org.junit.Assert.assertEquals
import org.junit.Test

class BinarioWidgetStateTest {
  private val now = 1_800_000_000_000L

  private fun available(scheduledAt: Long = now + 60_000L) = BinarioWidgetState(
    title = "Roma Termini → Napoli Centrale",
    subtitle = "Programmato localmente, non realtime",
    status = "available",
    updatedAtUtcMs = now - 1L,
    expiresAtUtcMs = now + 3_600_000L,
    plannedJourneyTitle = "Roma Termini → Napoli Centrale",
    plannedJourneyAtUtcMs = scheduledAt,
    plannedReminderMinutes = 15,
  )

  @Test
  fun countdownRoundsUpAndRecomputesAgainstCurrentTime() {
    val scheduledAt = now + 61_000L
    assertEquals("Tra 2 min", BinarioWidgetPresentation.countdownLabel(scheduledAt, now))
    assertEquals("Tra 1 min", BinarioWidgetPresentation.countdownLabel(scheduledAt, now + 1_000L))
  }

  @Test
  fun countdownAndStateBecomeUnavailableAtDeparture() {
    assertEquals("Dati non disponibili in tempo reale.", BinarioWidgetPresentation.countdownLabel(now, now))
    assertEquals(BinarioWidgetStore.unavailable(available(now).title), BinarioWidgetStore.accepted(available(now), now))
  }

  @Test
  fun futureJourneyRemainsAvailableWhileSnapshotIsFresh() {
    assertEquals(available(), BinarioWidgetStore.accepted(available(), now))
  }
}
