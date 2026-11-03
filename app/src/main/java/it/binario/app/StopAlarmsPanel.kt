package it.binario.app

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Whether Android lets Binario set exact alarms; always true before Android 12. */
internal fun canScheduleExactAlarms(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() != false

/**
 * The stop alarms still to ring ("Sveglie fermata attive"), each with "Annulla": all of them, or only those of
 * [trainNumber] in a train detail, where the exact-alarm note follows them. Nothing is shown when there is none.
 */
@Composable
internal fun ActiveStopAlarmsPanel(trainNumber: String? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val revision by StopAlarmRegistry.revision.collectAsState()
    // An alarm that rang while the app was in the background leaves the list when the app comes back.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed += 1 }
    val alarms = remember(revision, resumed, trainNumber) {
        StopAlarmScheduler.active(context).filter { trainNumber == null || it.trainNumber == trainNumber }
    }
    if (alarms.isEmpty()) return
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("active-stop-alarms"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.stop_alarms_active_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        alarms.forEach { alarm ->
            val cancelDescription = stringResource(R.string.accessibility_cancel_stop_alarm, alarm.station)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.stop_alarm_active_row, alarm.station, alarm.trainNumber, alarm.expectedTime),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(
                    onClick = { StopAlarmScheduler.cancel(context, alarm) },
                    modifier = Modifier.semantics { contentDescription = cancelDescription }
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        }
        if (trainNumber != null) ExactAlarmAccessNote()
    }
}

/**
 * "Attività in background" (read-only): whether Binario may notify, set exact alarms (with "Consenti sveglie
 * esatte" when not) and run unrestricted by the battery optimisation, so that a missing alert can be explained.
 */
@Composable
internal fun BackgroundStatusPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed += 1 }
    val notifications = remember(resumed) { it.binario.app.notifications.LiveNotifications.allowed(context) }
    val exact = remember(resumed) { canScheduleExactAlarms(context) }
    val unrestricted = remember(resumed) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    Column(modifier.fillMaxWidth().testTag("background-status"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.background_status_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        StatusLine(stringResource(if (notifications) R.string.background_status_notifications_on else R.string.background_status_notifications_off))
        if (exact) StatusLine(stringResource(R.string.background_status_exact_on)) else ExactAlarmAccessNote()
        StatusLine(stringResource(if (unrestricted) R.string.background_status_battery_unrestricted else R.string.background_status_battery_optimized))
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Without exact-alarm access (not granted by default from Android 14) stop alarms and commuter reminders may come
 * late: the note says so and "Consenti sveglie esatte" opens the Android page of the access. Hidden when granted.
 */
@Composable
internal fun ExactAlarmAccessNote(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val context = LocalContext.current
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed += 1 }
    val exact = remember(resumed) { canScheduleExactAlarms(context) }
    if (exact) return
    Column(modifier.fillMaxWidth().testTag("exact-alarm-access"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.exact_alarm_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }) { Text(stringResource(R.string.exact_alarm_action)) }
    }
}
