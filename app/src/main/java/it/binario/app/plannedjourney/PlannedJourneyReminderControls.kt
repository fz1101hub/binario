package it.binario.app.plannedjourney

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import it.binario.app.R

@Composable
internal fun PlannedJourneyReminderControls(viewModel: PlannedJourneyViewModel) {
    val context = LocalContext.current
    var pendingEnable by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val enable = pendingEnable
        pendingEnable = false
        if (granted && enable) viewModel.setRemindersEnabled(true) else viewModel.refreshReminders()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshReminders() }
    val status = viewModel.reminderStatus
    val title = stringResource(R.string.agenda_reminder_enable)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = status.enabled,
                    modifier = Modifier.semantics { contentDescription = title },
                    onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            pendingEnable = true
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            pendingEnable = false
                            viewModel.setRemindersEnabled(enabled)
                        }
                    }
                )
            }
            Text(stringResource(R.string.agenda_reminder_explanation), style = MaterialTheme.typography.bodySmall)
            if (!status.notificationsAllowed) {
                Text(stringResource(R.string.agenda_reminder_permission_required), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text(stringResource(R.string.agenda_reminder_settings)) }
            }
            if (status.failed) Text(stringResource(R.string.agenda_reminder_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}
