package it.binario.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import it.binario.app.plannedjourney.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Back up only the two Agenda stores; tests restore them and never erase other app data. */
open class AgendaDeviceFixture {
    protected val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    protected val controller get() = AndroidPlannedJourneyReminders.controller(context)
    private var savedJourneys: String? = null
    private var savedState: String? = null
    private val journeyPrefs get() = context.getSharedPreferences("binario_planned_journey_store_v1", Context.MODE_PRIVATE)
    private val statePrefs get() = context.getSharedPreferences("binario_agenda_reminders_v1", Context.MODE_PRIVATE)

    @Before fun isolateAgendaStores() = synchronized(AndroidPlannedJourneyReminders.gate) {
        savedJourneys = journeyPrefs.getString(PlannedJourneyPersistenceSchema.STORAGE_KEY, null)
        savedState = statePrefs.getString("state", null)
        controller.setEnabled(false)
        controller.refresh()
        write(emptyList())
        check(statePrefs.edit().remove("state").commit())
    }

    @After fun restoreAgendaStores() = synchronized(AndroidPlannedJourneyReminders.gate) {
        controller.setEnabled(false)
        controller.refresh()
        check(journeyPrefs.edit().putString(PlannedJourneyPersistenceSchema.STORAGE_KEY, savedJourneys).commit())
        check(statePrefs.edit().putString("state", savedState).commit())
        AndroidPlannedJourneyReminders.requestRefresh(context)
    }

    protected fun journey(): PlannedJourney {
        val departure = Instant.now().plusSeconds(305).atZone(ZoneId.of("Europe/Rome"))
        return PlannedJourney(id = UUID.randomUUID().toString(), label = "Test Agenda",
            itinerary = PlannedJourneyItinerary(listOf(PlannedJourneySegment("first",
                PlannedJourneyStation("IT:ROMA", "Roma Termini"), PlannedJourneyStation("IT:MILANO", "Milano Centrale"),
                PlannedJourneyDateTime(departure.toLocalDate(), departure.toLocalTime(), departure.zone)))),
            reminder = PlannedJourneyReminder(PlannedJourneyReminderMinutes.FIVE), createdAt = Instant.now().minusSeconds(60))
    }
    protected fun write(journeys: List<PlannedJourney>) {
        SharedPreferencesPlannedJourneyStorage(context).write(PlannedJourneyTransfer.encode(PlannedJourneyPersistenceSnapshot(journeys = journeys)))
    }
    protected fun plan(journey: PlannedJourney) = PlannedJourneyReminderPlanner.plans(PlannedJourneyPersistenceSnapshot(journeys = listOf(journey))).single()
    protected fun intent(key: String) = Intent(context, PlannedJourneyReminderReceiver::class.java).apply {
        action = "it.binario.app.PLANNED_JOURNEY_REMINDER"
        data = Uri.parse("binario-agenda-alarm://reminder/$key")
    }
    protected fun pending(key: String): PendingIntent? = PendingIntent.getBroadcast(context, 0, intent(key),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    protected fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for Android notification delivery", condition())
    }
}

@RunWith(AndroidJUnit4::class)
@android.annotation.TargetApi(33)
class PlannedJourneyReminderAndroidTest : AgendaDeviceFixture() {
    @Before fun supportedSdk() { org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33) }
    @Test fun scheduledAlarmDeliversPrivateNotificationAndDisablingRemovesIt() {
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        val journey = journey()
        write(listOf(journey))
        controller.setEnabled(true)
        assertFalse(controller.refresh().failed)
        val plan = plan(journey)
        assertNotNull(pending(plan.key))
        val notifications = context.getSystemService(NotificationManager::class.java)
        waitUntil { notifications.activeNotifications.any { it.tag == plan.key } }
        val posted = notifications.activeNotifications.single { it.tag == plan.key }.notification
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertNotNull(posted.publicVersion)
        assertNotNull(posted.contentIntent)
        controller.setEnabled(false)
        controller.refresh()
        assertNull(pending(plan.key))
        waitUntil { notifications.activeNotifications.none { it.tag == plan.key } }
    }

    @Test fun editingAndDeletingJourneyRemoveOldAndroidAlarmTokens() {
        val journey = journey()
        write(listOf(journey))
        controller.setEnabled(true)
        controller.refresh()
        val original = plan(journey)
        assertNotNull(pending(original.key))
        val updated = journey.copy(label = "New reminder content")
        write(listOf(updated))
        controller.refresh()
        val replacement = plan(updated)
        assertNull(pending(original.key))
        assertNotNull(pending(replacement.key))
        write(emptyList())
        controller.refresh()
        assertNull(pending(replacement.key))
    }
}

/** CI runs this class separately after revoking POST_NOTIFICATIONS outside the app process. */
@RunWith(AndroidJUnit4::class)
@android.annotation.TargetApi(33)
class PlannedJourneyReminderPermissionTest : AgendaDeviceFixture() {
    @Before fun supportedSdk() { org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33) }
    @Test fun deniedPermissionPreservesConsentButCannotCreateAlarmOrNotification() {
        assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS))
        val journey = journey()
        write(listOf(journey))
        controller.setEnabled(true)
        val status = controller.refresh()
        assertTrue(status.enabled)
        assertFalse(status.notificationsAllowed)
        assertNull(pending(plan(journey).key))
    }
}
