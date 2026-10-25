package it.binario.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.google.android.gms.wearable.Wearable
import it.binario.wear.model.WearSnapshot
import it.binario.wear.sync.WearSnapshotStore

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val snapshot by WearSnapshotStore.snapshot(this).collectAsState()
            MaterialTheme { WearHome(snapshot, ::requestRefreshFromPhone) }
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

@androidx.compose.runtime.Composable
private fun WearHome(snapshot: WearSnapshot?, onRefreshPhone: () -> Unit) {
    if (snapshot == null) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Binario", style = MaterialTheme.typography.titleMedium, color = Color(0xFF9BDD9E))
            Text("Apri Binario sul telefono per sincronizzare la tratta attiva.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRefreshPhone, modifier = Modifier.fillMaxWidth()) { Text("Aggiorna telefono") }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(snapshot.routeLabel, style = MaterialTheme.typography.titleMedium, color = Color(0xFF9BDD9E), modifier = Modifier.padding(top = 10.dp)) }
        item { Text(snapshot.dataStatusLabel, style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { contentDescription = "Stato dato: ${snapshot.dataStatusLabel}" }) }
        items(snapshot.services, key = { it.number }) { service ->
            Card(onClick = {}, modifier = Modifier.fillMaxWidth().semantics { contentDescription = service.accessibleLabel }) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${service.expectedDeparture} · ${service.number}", style = MaterialTheme.typography.titleSmall, color = Color(0xFF9BDD9E))
                    Text("${service.destination} · ${service.status}", style = MaterialTheme.typography.bodyMedium)
                    Text("${service.operator} · ${service.category}${service.platform?.let { " · Binario $it" } ?: ""}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item { Button(onClick = onRefreshPhone, modifier = Modifier.fillMaxWidth()) { Text("Aggiorna sul telefono") } }
        if (snapshot.notice.isNotBlank()) item { Text(snapshot.notice, style = MaterialTheme.typography.labelSmall) }
    }
}
