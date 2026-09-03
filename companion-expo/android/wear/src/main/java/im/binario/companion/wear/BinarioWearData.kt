package im.binario.companion.wear

import android.content.Context
import android.content.Intent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

data class BinarioWearState(val title: String, val status: String, val canComplicate: Boolean, val updatedAtUtcMs: Long?, val expiresAtUtcMs: Long?, val plannedChecklistRemaining: Int)

object BinarioWearStore {
  private const val prefsName = "binario_wear_payload_v1"
  fun write(context: Context, state: BinarioWearState) {
    val fresh = state.canComplicate && state.updatedAtUtcMs != null && state.expiresAtUtcMs != null && state.updatedAtUtcMs <= System.currentTimeMillis() && state.expiresAtUtcMs > System.currentTimeMillis()
    context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().apply {
      putString("title", state.title)
      putString("status", if (fresh) state.status else "Dati non disponibili in tempo reale.")
      putBoolean("canComplicate", fresh)
      if (fresh && state.plannedChecklistRemaining > 0) putInt("plannedChecklistRemaining", state.plannedChecklistRemaining) else remove("plannedChecklistRemaining")
      if (fresh) { putLong("updatedAtUtcMs", state.updatedAtUtcMs!!); putLong("expiresAtUtcMs", state.expiresAtUtcMs!!) } else { remove("updatedAtUtcMs"); remove("expiresAtUtcMs") }
    }.apply()
  }
  fun read(context: Context): BinarioWearState {
    val p = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    val updated = if (p.contains("updatedAtUtcMs")) p.getLong("updatedAtUtcMs", 0L) else null
    val expires = if (p.contains("expiresAtUtcMs")) p.getLong("expiresAtUtcMs", 0L) else null
    val fresh = p.getBoolean("canComplicate", false) && updated != null && expires != null && updated <= System.currentTimeMillis() && expires > System.currentTimeMillis()
    return BinarioWearState(p.getString("title", "Binario") ?: "Binario", if (fresh) p.getString("status", "") ?: "" else "Dati non disponibili in tempo reale.", fresh, if (fresh) updated else null, if (fresh) expires else null, if (fresh) p.getInt("plannedChecklistRemaining", 0).coerceIn(0, 3) else 0)
  }
}

class BinarioWearDataService : WearableListenerService() {
  override fun onDataChanged(events: DataEventBuffer) {
    events.forEach { event ->
      if (event.type != com.google.android.gms.wearable.DataEvent.TYPE_CHANGED || event.dataItem.uri.path != "/binario/companion/watch-payload/v1") return@forEach
      val map = com.google.android.gms.wearable.DataMapItem.fromDataItem(event.dataItem).dataMap
      BinarioWearStore.write(this, BinarioWearState(map.getString("title") ?: "Binario", map.getString("status") ?: "Dati non disponibili in tempo reale.", map.getBoolean("canComplicate"), if (map.containsKey("updatedAtUtcMs")) map.getLong("updatedAtUtcMs") else null, if (map.containsKey("expiresAtUtcMs")) map.getLong("expiresAtUtcMs") else null, if (map.containsKey("plannedChecklistRemaining")) map.getInt("plannedChecklistRemaining") else 0))
    }
  }
  override fun onMessageReceived(event: MessageEvent) {
    if (event.path != "/binario/companion/voice-response/v1") return
    val reply = event.data.toString(Charsets.UTF_8).take(280).ifBlank { "Dati non disponibili in tempo reale." }
    sendBroadcast(Intent(BinarioVoiceRequestActivity.replyAction).setPackage(packageName).putExtra(BinarioVoiceRequestActivity.replyExtra, reply))
  }
}
