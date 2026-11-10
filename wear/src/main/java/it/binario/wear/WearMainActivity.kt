package it.binario.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import it.binario.wear.model.WearSnapshot
import it.binario.wear.sync.WearSnapshotStore
import kotlinx.coroutines.flow.MutableStateFlow

class WearMainActivity : ComponentActivity() {
    private val phoneApp = MutableStateFlow(WearPhoneAppPresence.UNKNOWN)
    private val refreshFeedback = MutableStateFlow<WearRefreshFeedback?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val snapshot by WearSnapshotStore.snapshot(this).collectAsState()
            val expired by WearSnapshotStore.expired(this).collectAsState()
            val phoneAppPresence by phoneApp.collectAsState()
            val feedback by refreshFeedback.collectAsState()
            // A newly received snapshot answers the request, so its note is no longer current.
            LaunchedEffect(snapshot?.generatedAtEpochMillis) { refreshFeedback.value = null }
            BinarioWearTheme { AppScaffold { WearHome(snapshot, expired, phoneAppPresence, feedback, ::requestRefreshFromPhone) } }
        }
    }

    override fun onResume() {
        super.onResume()
        // The expiry callback is posted on uptime, which stops while the watch sleeps, so a value
        // that expired meanwhile is still in the StateFlow: revalidate before the screen shows it.
        WearSnapshotStore.current(this)
        // A snapshot the phone published before this install, or before the watch data was cleared,
        // is never redelivered as DATA_CHANGED: read it back from the Data Layer.
        WearSnapshotStore.bootstrapFromDataLayer(this)
        // Without the phone app no setting can help: the empty state must say which one is missing.
        Wearable.getCapabilityClient(this).getCapability(PHONE_APP_CAPABILITY, CapabilityClient.FILTER_ALL)
            .addOnSuccessListener { info -> phoneApp.value = WearPhoneAppPresence.fromNodeCount(info.nodes.size) }
    }

    /** Asks every reachable phone with the Binario app for a new snapshot and reports whether one got the request. */
    private fun requestRefreshFromPhone() {
        refreshFeedback.value = null
        Wearable.getCapabilityClient(this).getCapability(PHONE_APP_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info -> sendRefreshRequest(info.nodes.map { node -> node.id }) }
            .addOnFailureListener { refreshFeedback.value = WearRefreshFeedback.UNREACHABLE }
    }

    private fun sendRefreshRequest(nodeIds: List<String>) {
        val delivery = WearRefreshDelivery(nodeIds.size) { feedback -> refreshFeedback.value = feedback }
        val messages = Wearable.getMessageClient(this)
        nodeIds.forEach { nodeId ->
            messages.sendMessage(nodeId, REFRESH_REQUEST_PATH, ByteArray(0))
                .addOnCompleteListener { task -> delivery.record(task.isSuccessful) }
        }
    }
}

/**
 * ScreenScaffold adds the time text and the scroll indicator; its content padding and the
 * ScalingLazyColumn keep the first and last items inside the visible disc of a round screen.
 */
@Composable
private fun WearHome(snapshot: WearSnapshot?, expired: Boolean, phoneApp: WearPhoneAppPresence, feedback: WearRefreshFeedback?, onRefreshPhone: () -> Unit) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            if (snapshot == null) {
                item { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center) }
                item { Text(stringResource(emptySnapshotMessageResId(phoneApp, expired)), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
                item { Button(onClick = onRefreshPhone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.refresh_phone)) } }
                refreshFeedbackItem(feedback)
            } else {
                item { Text(snapshot.routeLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center) }
                item {
                    val status = snapshot.dataStatus()
                    val dataStatus = stringResource(status.id, *status.args.toTypedArray())
                    val statusDescription = stringResource(R.string.accessibility_data_status, dataStatus)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(dataStatus, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { contentDescription = statusDescription })
                        // Where the phone took the data from («Fonte primaria dell’operatore», the timetable…), in the phone's language.
                        Text(snapshot.provider, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
                itemsIndexed(
                    items = snapshot.services,
                    key = { index, service -> "$index-${service.number}-${service.expectedDeparture}-${service.destination}" }
                ) { _, service ->
                    // «In orario 8 volte su 10»: the phone's network statistics, when it sent them for this train.
                    val reliability = service.reliability?.let { pluralStringResource(R.plurals.service_reliability_on_time, it.onTime, it.onTime, it.samples) }
                    // A plain container: a Card needs onClick, and an empty one made TalkBack offer
                    // "double-tap to activate" with nothing behind it. One focus stop reads the label.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
                            .clearAndSetSemantics { contentDescription = service.accessibleLabel(context, reliability) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(stringResource(R.string.service_time_number, service.expectedDeparture, service.number), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.service_destination_status, service.destination, service.status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            service.platform?.let { stringResource(R.string.service_operator_category_platform, service.operator, service.category, it) }
                                ?: stringResource(R.string.service_operator_category, service.operator, service.category),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        reliability?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                item { Button(onClick = onRefreshPhone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.refresh_on_phone)) } }
                refreshFeedbackItem(feedback)
                if (snapshot.notice.isNotBlank()) item { Text(snapshot.notice, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
            }
        }
    }
}

/** The outcome of the last refresh tap; a polite live region so TalkBack reads it without moving focus. */
private fun ScalingLazyListScope.refreshFeedbackItem(feedback: WearRefreshFeedback?) {
    if (feedback == null) return
    item {
        Text(
            stringResource(feedback.messageResId),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
