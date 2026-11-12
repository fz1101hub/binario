package it.binario.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import it.binario.app.data.TrainService
import it.binario.app.gpsalarm.GpsArrivalAlarmController
import it.binario.app.notifications.LiveNotifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** What made a stop alarm ring: its scheduled time, or the GPS arrival alarm reaching the radius. */
enum class StopAlarmTrigger { TIME, LOCATION }

object StopAlarmScheduler {
    const val CHANNEL_ID = "binario_stop_alarm"
    private const val ACTION_STOP_ALARM = "it.binario.app.STOP_ALARM"
    private const val EXTRA_TRAIN = "train"
    private const val EXTRA_DESTINATION = "destination"
    private const val EXTRA_STATION = "station"
    private const val EXTRA_EXPECTED = "expected"

    /**
     * Sets the time-based alarm of [plan] and keeps it among the active stop alarms. There is one alarm per train and
     * stop: setting it again (a new expected time, another choice) replaces the previous one instead of adding one.
     */
    fun schedule(context: Context, plan: StopAlarmPlan): DeviceAlarmScheduleResult {
        StopAlarmStore(context).save(plan)
        val result = arm(context, plan, plan.triggerAtEpochMillis)
        StopAlarmRegistry.changed()
        return result
    }

    /** Cancels the time-based alarm of [plan]'s train and stop, if it is still pending. */
    fun cancel(context: Context, plan: StopAlarmPlan) = cancel(context, plan.trainNumber, plan.station)

    fun cancel(context: Context, trainNumber: String, station: String) {
        StopAlarmStore(context).remove(trainNumber, station)
        PendingIntent.getBroadcast(
            context,
            requestCode(trainNumber, station),
            alarmIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { pendingIntent ->
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        StopAlarmRegistry.changed()
    }

    /** The stop alarms still to ring, soonest first. */
    fun active(context: Context, nowEpochMillis: Long = System.currentTimeMillis()): List<StopAlarmPlan> =
        StopAlarmStore(context).plans(nowEpochMillis)

    /**
     * Sets again every stored stop alarm after a boot, an update, an app start after a force stop or a change of the
     * exact-alarm access: Android clears the alarms in the first cases and keeps them inexact in the last one.
     */
    fun restoreAll(context: Context, nowEpochMillis: Long = System.currentTimeMillis()) {
        val store = StopAlarmStore(context)
        store.plans(nowEpochMillis).forEach { plan ->
            when (val trigger = StopAlarmPlanner.restoreTrigger(plan, nowEpochMillis)) {
                null -> store.remove(plan.trainNumber, plan.station)
                else -> arm(context, plan, trigger)
            }
        }
        StopAlarmRegistry.changed()
    }

    /**
     * Follows the train: a followed-trip refresh read [services] for the trip's trains. A stored stop alarm on one of
     * them is planned again from the expected time just read, so a late train does not ring early.
     */
    internal fun follow(context: Context, services: List<TrainService?>, nowEpochMillis: Long = System.currentTimeMillis()) {
        val stored = StopAlarmStore(context).plans(nowEpochMillis)
        if (stored.isEmpty()) return
        StopAlarmPlanner.replanned(stored, services.filterNotNull(), nowEpochMillis).forEach { schedule(context, it) }
    }

    /** The alarm of [plan] rang: it is no longer active, unless it was set again for a later time meanwhile. */
    internal fun onFired(context: Context, trainNumber: String, station: String, nowEpochMillis: Long = System.currentTimeMillis()) {
        val store = StopAlarmStore(context)
        val plan = store.plan(trainNumber, station) ?: return
        if (plan.triggerAtEpochMillis <= nowEpochMillis + StopAlarmPlanner.REPLANNED_MARGIN_MILLIS) {
            store.remove(trainNumber, station)
            StopAlarmRegistry.changed()
        }
    }

    private fun arm(context: Context, plan: StopAlarmPlan, triggerAtEpochMillis: Long): DeviceAlarmScheduleResult {
        val intent = alarmIntent(context).apply {
            putExtra(EXTRA_TRAIN, plan.trainNumber)
            putExtra(EXTRA_DESTINATION, plan.destination)
            putExtra(EXTRA_STATION, plan.station)
            putExtra(EXTRA_EXPECTED, plan.expectedTime)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(plan.trainNumber, plan.station),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return scheduleWakeupAlarm(context, alarmManager, triggerAtEpochMillis, pendingIntent)
    }

    /** Whether a stop alarm can appear: notifications allowed and its channel not blocked by the user. */
    fun canShow(context: Context): Boolean = LiveNotifications.channelAllowed(context, CHANNEL_ID)

    /** Posts the stop alarm notification; false when Android does not let the app notify. */
    fun notify(
        context: Context,
        train: String,
        destination: String,
        station: String,
        expected: String,
        trigger: StopAlarmTrigger = StopAlarmTrigger.TIME
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.stop_alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.stop_alarm_channel_description) }
        )
        // A channel the user blocked shows nothing: not delivered, so the GPS alarm keeps the time-based one.
        if (!LiveNotifications.channelShows(manager.getNotificationChannel(CHANNEL_ID)?.importance)) return false
        val byLocation = trigger == StopAlarmTrigger.LOCATION
        val title = if (byLocation) R.string.gps_alarm_triggered_title else R.string.stop_alarm_notification_title
        val text = if (byLocation) R.string.gps_alarm_triggered_text else R.string.stop_alarm_notification_text
        val bigText = if (byLocation) R.string.gps_alarm_triggered_big_text else R.string.stop_alarm_notification_big_text
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_binario)
            .setContentTitle(context.getString(title, station))
            .setContentText(context.getString(text, train, destination, expected))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(bigText, train, destination, station, expected)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, train, station))
            .build()
        // Same id for both triggers and for every expected time: if the other alarm still rings later, or the alarm
        // was planned again, it replaces this notification instead of adding a second one.
        NotificationManagerCompat.from(context).notify(StopAlarmPlanner.key(train, station).hashCode(), notification)
        return true
    }

    private fun alarmIntent(context: Context): Intent =
        Intent(context, StopAlarmReceiver::class.java).setAction(ACTION_STOP_ALARM)

    /** One request code per train and stop, without the trigger time: a new plan replaces the pending alarm. */
    internal fun requestCode(train: String, station: String): Int = "stop-alarm|${StopAlarmPlanner.key(train, station)}".hashCode()

    internal fun trainExtra(intent: Intent) = intent.getStringExtra(EXTRA_TRAIN).orEmpty()
    internal fun destinationExtra(intent: Intent) = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
    internal fun stationExtra(intent: Intent) = intent.getStringExtra(EXTRA_STATION).orEmpty()
    internal fun expectedExtra(intent: Intent) = intent.getStringExtra(EXTRA_EXPECTED).orEmpty()

    private fun contentIntent(context: Context, train: String, station: String): PendingIntent = PendingIntent.getActivity(
        context,
        StopAlarmPlanner.key(train, station).hashCode(),
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/** Tells the UI that the active stop alarms changed (set, cancelled, rung); in memory only. */
internal object StopAlarmRegistry {
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    fun changed() {
        mutableRevision.value += 1
    }
}

/**
 * The time-based stop alarms still to ring, keyed by train and stop, in local preferences (never in the backup):
 * train number, destination, stop, expected time, trigger and expected arrival. A plan is removed when it rings, is
 * cancelled, or two hours after its expected arrival.
 */
internal class StopAlarmStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_stop_alarms_v1", Context.MODE_PRIVATE)

    fun plans(nowEpochMillis: Long): List<StopAlarmPlan> =
        StopAlarmCodec.decode(preferences.getString(KEY_PLANS, null)).filterNot { StopAlarmPlanner.isOver(it, nowEpochMillis) }
            .sortedBy { it.triggerAtEpochMillis }

    fun plan(trainNumber: String, station: String): StopAlarmPlan? = all().firstOrNull {
        StopAlarmPlanner.key(it.trainNumber, it.station) == StopAlarmPlanner.key(trainNumber, station)
    }

    fun save(plan: StopAlarmPlan) = write(StopAlarmPlanner.upsert(all(), plan, System.currentTimeMillis()))

    fun remove(trainNumber: String, station: String) = write(all().filterNot {
        StopAlarmPlanner.key(it.trainNumber, it.station) == StopAlarmPlanner.key(trainNumber, station)
    })

    private fun all(): List<StopAlarmPlan> = StopAlarmCodec.decode(preferences.getString(KEY_PLANS, null))

    private fun write(plans: List<StopAlarmPlan>) = preferences.edit {
        if (plans.isEmpty()) remove(KEY_PLANS) else putString(KEY_PLANS, StopAlarmCodec.encode(plans))
    }

    private companion object {
        const val KEY_PLANS = "plans"
    }
}

/** JSON of the stored stop alarms; a damaged entry is skipped. */
internal object StopAlarmCodec {
    fun encode(plans: List<StopAlarmPlan>): String = JSONArray().apply {
        plans.forEach { plan ->
            put(JSONObject().apply {
                put("train", plan.trainNumber)
                put("destination", plan.destination)
                put("station", plan.station)
                put("expected", plan.expectedTime)
                put("trigger", plan.triggerAtEpochMillis)
                put("arrival", plan.expectedAtEpochMillis)
            })
        }
    }.toString()

    fun decode(raw: String?): List<StopAlarmPlan> {
        val array = raw?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until minOf(array.length(), StopAlarmPlanner.MAX_ALARMS)).mapNotNull { index ->
            val value = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                StopAlarmPlan(
                    trainNumber = value.getString("train").also { require(it.isNotBlank()) },
                    destination = value.optString("destination"),
                    station = value.getString("station").also { require(it.isNotBlank()) },
                    expectedTime = value.optString("expected"),
                    triggerAtEpochMillis = value.getLong("trigger"),
                    expectedAtEpochMillis = value.getLong("arrival")
                )
            }.getOrNull()
        }
    }
}

class StopAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val train = StopAlarmScheduler.trainExtra(intent)
        val station = StopAlarmScheduler.stationExtra(intent)
        StopAlarmScheduler.onFired(context, train, station)
        StopAlarmScheduler.notify(
            context = context,
            train = train,
            destination = StopAlarmScheduler.destinationExtra(intent),
            station = station,
            expected = StopAlarmScheduler.expectedExtra(intent)
        )
        // The GPS alarm for the same stop stops here, unless a recent fix shows the stop still outside its radius.
        GpsArrivalAlarmController.onTimeAlarmFired(context, train, station)
    }
}
