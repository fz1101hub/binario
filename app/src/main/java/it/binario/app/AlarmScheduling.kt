package it.binario.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build

/** The actual delivery may be deferred when the user has not granted exact-alarm access. */
data class DeviceAlarmScheduleResult(
    val triggerAtEpochMillis: Long,
    val isExact: Boolean
)

/**
 * Schedules a one-shot wakeup alarm. Android 12+ restricts exact alarms, so a denied (or
 * concurrently revoked) permission deliberately degrades to an inexact allow-while-idle alarm
 * instead of losing a user-created reminder.
 */
internal fun scheduleWakeupAlarm(
    context: Context,
    alarmManager: AlarmManager,
    triggerAtEpochMillis: Long,
    pendingIntent: PendingIntent
): DeviceAlarmScheduleResult {
    val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    val exactScheduled = exactAllowed && runCatching {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMillis, pendingIntent)
    }.isSuccess
    if (!exactScheduled) {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMillis, pendingIntent)
    }
    return DeviceAlarmScheduleResult(triggerAtEpochMillis, exactScheduled)
}
