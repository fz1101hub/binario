package it.binario.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.google.android.gms.wearable.Wearable
import it.binario.wear.model.WearSnapshot
import it.binario.wear.sync.WearSnapshotStore

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val snapshot by WearSnapshotStore.snapshot(this).collectAsState()
            MaterialTheme { AppScaffold { WearHome(snapshot, ::requestRefreshFromPhone) } }
        }
    }

    private fun requestRefreshFromPhone() {
        Wearable.getNodeClient(this@WearMainActivity).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(this@WearMainActivity)
                    .sendMessage(node.id, "/binario/wear/request-refresh", ByteArray(0))
            }
        }
    }
}

/**
 * ScreenScaffold adds the time text and the scroll indicator; its content padding and the
 * ScalingLazyColumn keep the first and last items inside the visible disc of a round screen.
 */
@Composable
private fun WearHome(snapshot: WearSnapshot?, onRefreshPhone: () -> Unit) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            if (snapshot == null) {
                item { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
                item { Text(stringResource(R.string.empty_snapshot_message), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
                item { Button(onClick = onRefreshPhone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.refresh_phone)) } }
            } else {
                item { Text(snapshot.routeLabel, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
                item {
                    val dataStatus = stringResource(snapshot.dataStatusResId())
                    val statusDescription = stringResource(R.string.accessibility_data_status, dataStatus)
                    Text(dataStatus, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { contentDescription = statusDescription })
                }
                itemsIndexed(
                    items = snapshot.services,
                    key = { index, service -> "$index-${service.number}-${service.expectedDeparture}-${service.destination}" }
                ) { _, service ->
                    Card(onClick = {}, modifier = Modifier.fillMaxWidth().semantics { contentDescription = service.accessibleLabel(context) }) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.service_time_number, service.expectedDeparture, service.number), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.service_destination_status, service.destination, service.status), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                service.platform?.let { stringResource(R.string.service_operator_category_platform, service.operator, service.category, it) }
                                    ?: stringResource(R.string.service_operator_category, service.operator, service.category),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                item { Button(onClick = onRefreshPhone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.refresh_on_phone)) } }
                if (snapshot.notice.isNotBlank()) item { Text(snapshot.notice, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
            }
        }
    }
}
