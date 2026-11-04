package it.binario.app.notifications

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkInfo
import it.binario.app.TestStringCatalogue
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundRunsTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val now = ZonedDateTime.of(2026, 11, 4, 17, 40, 0, 0, rome)
    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        ZonedDateTime.of(2026, 11, day, hour, minute, second, 0, rome).toInstant().toEpochMilli()

    private fun enqueued(millis: Long) = BackgroundWorkEntry(BackgroundWorkEntry.State.ENQUEUED, millis)

    @Test fun `the earliest waiting work of a chain is its next run, today tomorrow or a later day`() {
        assertEquals(BackgroundRunTime.Today(LocalTime.of(18, 15)), BackgroundRuns.nextRun(listOf(enqueued(at(4, 19, 0)), enqueued(at(4, 18, 15, 42))), now))
        assertEquals(BackgroundRunTime.Tomorrow(LocalTime.of(7, 0)), BackgroundRuns.nextRun(listOf(enqueued(at(5, 7, 0))), now))
        assertEquals(BackgroundRunTime.Later(LocalDate.of(2026, 11, 8), LocalTime.of(19, 0)), BackgroundRuns.nextRun(listOf(enqueued(at(8, 19, 0))), now))
    }

    @Test fun `a running work wins, a due one is soon, blocked finished or unscheduled ones have no time`() {
        val running = BackgroundWorkEntry(BackgroundWorkEntry.State.RUNNING)
        val blocked = BackgroundWorkEntry(BackgroundWorkEntry.State.BLOCKED)
        assertEquals(BackgroundRunTime.Running, BackgroundRuns.nextRun(listOf(blocked, running), now))
        // Waiting for the network or Doze after its time: it runs as soon as Android lets it.
        assertEquals(BackgroundRunTime.Soon, BackgroundRuns.nextRun(listOf(enqueued(at(4, 17, 10))), now))
        assertNull(BackgroundRuns.nextRun(listOf(blocked), now))
        assertNull(BackgroundRuns.nextRun(listOf(BackgroundWorkEntry(BackgroundWorkEntry.State.FINISHED, at(4, 18, 0))), now))
        assertNull(BackgroundRuns.nextRun(listOf(enqueued(Long.MAX_VALUE)), now))
        assertNull(BackgroundRuns.nextRun(emptyList(), now))
    }

    @Test fun `a run after midnight is tomorrow in the railway zone, also across the end of summer time`() {
        val lateEvening = ZonedDateTime.of(2026, 10, 24, 23, 50, 0, 0, rome)
        val afterChange = ZonedDateTime.of(2026, 10, 25, 2, 30, 0, 0, rome).plusHours(1).toInstant().toEpochMilli()
        assertEquals(BackgroundRunTime.Tomorrow(LocalTime.of(2, 30)), BackgroundRuns.nextRun(listOf(enqueued(afterChange)), lateEvening))
    }

    @Test fun `work infos map to the entries of the panel with their next schedule time`() {
        val infos = listOf(
            WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, setOf("binario-train-alerts"), nextScheduleTimeMillis = 42L,
                constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()),
            WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, emptySet()),
            WorkInfo(UUID.randomUUID(), WorkInfo.State.BLOCKED, emptySet()),
            WorkInfo(UUID.randomUUID(), WorkInfo.State.SUCCEEDED, emptySet()),
            WorkInfo(UUID.randomUUID(), WorkInfo.State.CANCELLED, emptySet())
        )
        assertEquals(
            listOf(
                BackgroundWorkEntry(BackgroundWorkEntry.State.ENQUEUED, 42L),
                BackgroundWorkEntry(BackgroundWorkEntry.State.RUNNING),
                BackgroundWorkEntry(BackgroundWorkEntry.State.BLOCKED),
                BackgroundWorkEntry(BackgroundWorkEntry.State.FINISHED),
                BackgroundWorkEntry(BackgroundWorkEntry.State.FINISHED)
            ),
            WorkManagerBackgroundWorkQuery.entriesOf(infos)
        )
    }

    @Test fun `planned lists only the checks with a run, in the panel order`() = runBlocking {
        val query = BackgroundWorkQuery { name ->
            when (name) {
                WidgetWork -> listOf(enqueued(at(4, 18, 0)))
                StrikeReminderScheduler.WORK -> listOf(enqueued(at(5, 18, 0)))
                TrainAlertScheduler.WORK -> listOf(BackgroundWorkEntry(BackgroundWorkEntry.State.RUNNING))
                else -> emptyList()
            }
        }
        assertEquals(
            listOf(
                BackgroundRun(BackgroundCheck.TRAIN_ALERTS, BackgroundRunTime.Running),
                BackgroundRun(BackgroundCheck.STRIKES, BackgroundRunTime.Tomorrow(LocalTime.of(18, 0))),
                BackgroundRun(BackgroundCheck.WIDGETS, BackgroundRunTime.Today(LocalTime.of(18, 0)))
            ),
            BackgroundRuns.planned(query, now)
        )
    }

    @Test fun `every check has its own unique work name`() {
        assertEquals(BackgroundCheck.entries.size, BackgroundCheck.entries.map { it.uniqueWork }.toSet().size)
    }

    @Test fun `the lines read as the resources say, in Italian and English`() {
        fun strings(locale: String?, javaLocale: Locale): BackgroundRunStrings {
            val catalogue = TestStringCatalogue.of(locale)
            return BackgroundRunStrings(
                locale = javaLocale,
                label = { check -> if (check == BackgroundCheck.STRIKES) catalogue.getValue("settings_strike_reminder_title") else check.name },
                running = catalogue.getValue("background_runs_running"),
                soon = catalogue.getValue("background_runs_soon"),
                today = catalogue.getValue("background_runs_today"),
                tomorrow = catalogue.getValue("background_runs_tomorrow"),
                later = catalogue.getValue("background_runs_later"),
                timeFormatter = DateTimeFormatter.ofPattern("HH:mm", javaLocale),
                dateFormatter = DateTimeFormatter.ofPattern("EEE d MMM", javaLocale)
            )
        }
        val italian = strings(null, Locale.ITALIAN)
        assertEquals("Scioperi del giorno dopo: oggi alle 18:00", BackgroundRuns.text(BackgroundRun(BackgroundCheck.STRIKES, BackgroundRunTime.Today(LocalTime.of(18, 0))), italian))
        assertEquals("Scioperi del giorno dopo: domani alle 07:05", BackgroundRuns.text(BackgroundRun(BackgroundCheck.STRIKES, BackgroundRunTime.Tomorrow(LocalTime.of(7, 5))), italian))
        assertEquals(
            "Scioperi del giorno dopo: dom 8 nov alle 19:00",
            BackgroundRuns.text(BackgroundRun(BackgroundCheck.STRIKES, BackgroundRunTime.Later(LocalDate.of(2026, 11, 8), LocalTime.of(19, 0))), italian)
        )
        assertEquals("Scioperi del giorno dopo: in corso", BackgroundRuns.text(BackgroundRun(BackgroundCheck.STRIKES, BackgroundRunTime.Running), italian))
        val english = strings("en", Locale.ENGLISH)
        assertEquals("WIDGETS: soon", BackgroundRuns.text(BackgroundRun(BackgroundCheck.WIDGETS, BackgroundRunTime.Soon), english))
        assertEquals("WIDGETS: tomorrow at 06:30", BackgroundRuns.text(BackgroundRun(BackgroundCheck.WIDGETS, BackgroundRunTime.Tomorrow(LocalTime.of(6, 30))), english))
    }

    private companion object {
        val WidgetWork = it.binario.app.widget.WidgetAutoRefreshScheduler.WORK
    }
}
