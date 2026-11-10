package im.binario.companion.binario

import android.text.format.DateUtils
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
  fun stateBecomesUnavailableAtDeparture() {
    assertEquals(BinarioWidgetStore.unavailable(available(now).title), BinarioWidgetStore.accepted(available(now), now))
    assertEquals(BinarioWidgetStore.unavailable(available(now - 1L).title), BinarioWidgetStore.accepted(available(now - 1L), now))
  }

  @Test
  fun widgetIsRedrawnAtTheEarlierOfDepartureAndExpiry() {
    assertEquals(60_000L, BinarioWidgetPresentation.nextTransitionDelayMillis(now + 60_000L, now + 3_600_000L, now))
    assertEquals(30_000L, BinarioWidgetPresentation.nextTransitionDelayMillis(now + 60_000L, now + 30_000L, now))
    assertEquals(60_000L, BinarioWidgetPresentation.nextTransitionDelayMillis(now + 60_000L, now - 1L, now))
    assertEquals(null, BinarioWidgetPresentation.nextTransitionDelayMillis(now, null, now))
    assertEquals(null, BinarioWidgetPresentation.nextTransitionDelayMillis(null, null, now))
  }

  @Test
  fun futureJourneyRemainsAvailableWhileSnapshotIsFresh() {
    assertEquals(available(), BinarioWidgetStore.accepted(available(), now))
  }

  @Test
  fun departureDayIsTheItalianCalendarDay() {
    // 23:30 and 00:30 in Italy (summer time, UTC+2): different Italian days, the same day in UTC or London.
    val lateEvening = 1_783_891_800_000L // 2026-07-12T21:30:00Z
    val afterMidnight = lateEvening + 3_600_000L
    assertEquals(false, BinarioWidgetPresentation.sameTravelDay(lateEvening, afterMidnight))
    assertEquals(true, BinarioWidgetPresentation.sameTravelDay(afterMidnight, afterMidnight + 20 * 3_600_000L))
    assertEquals("Europe/Rome", BinarioWidgetPresentation.TRAVEL_TIME_ZONE)
  }

  @Test
  fun departureLabelAddsTheDateOnlyForAnotherItalianDay() {
    val lateEvening = 1_783_891_800_000L // 2026-07-12T21:30:00Z, 23:30 in Italy
    assertEquals(DateUtils.FORMAT_SHOW_TIME, BinarioWidgetPresentation.departureFlags(lateEvening + 10 * 60_000L, lateEvening))
    val tomorrow = BinarioWidgetPresentation.departureFlags(lateEvening + 3_600_000L, lateEvening)
    assertEquals(DateUtils.FORMAT_SHOW_TIME, tomorrow and DateUtils.FORMAT_SHOW_TIME)
    assertEquals(DateUtils.FORMAT_SHOW_DATE, tomorrow and DateUtils.FORMAT_SHOW_DATE)
    assertEquals(DateUtils.FORMAT_SHOW_WEEKDAY, tomorrow and DateUtils.FORMAT_SHOW_WEEKDAY)
  }
}
