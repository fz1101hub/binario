package im.binario.companion.binario

import android.content.Intent
import android.net.Uri
import androidx.glance.appwidget.updateAll
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.uimanager.ViewManager
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BinarioWidgetBridgeModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  override fun getName() = "BinarioWidgetBridge"

  @ReactMethod
  fun update(payload: ReadableMap) {
    val widget = payload.getMap("widget") ?: return
    val watch = payload.getMap("watch") ?: return
    val title = widget.getString("title") ?: "Binario"
    val subtitle = widget.getString("subtitle") ?: "Dati non disponibili in tempo reale."
    val status = widget.getString("status") ?: "unavailable"
    val updatedAt = if (widget.hasKey("updatedAtUtcMs") && !widget.isNull("updatedAtUtcMs")) widget.getDouble("updatedAtUtcMs").toLong() else null
    val expiresAt = if (widget.hasKey("expiresAtUtcMs") && !widget.isNull("expiresAtUtcMs")) widget.getDouble("expiresAtUtcMs").toLong() else null
    val planned = if (widget.hasKey("plannedJourney") && !widget.isNull("plannedJourney")) widget.getMap("plannedJourney") else null
    val plannedTitle = planned?.getString("title")?.take(196)
    val plannedAt = if (planned != null && planned.hasKey("scheduledAtUtcMs") && !planned.isNull("scheduledAtUtcMs")) planned.getDouble("scheduledAtUtcMs").toLong() else null
    val plannedReminder = if (planned != null && planned.hasKey("reminderMinutes") && !planned.isNull("reminderMinutes")) planned.getDouble("reminderMinutes").toInt() else null
    BinarioWidgetStore.write(context, BinarioWidgetState(title, subtitle, status, updatedAt, expiresAt, plannedTitle, plannedAt, plannedReminder))
    syncWearPayload(watch)
    scope.launch { BinarioStatusWidget().updateAll(context) }
  }

  private fun rawDataMapValues(payload: ReadableMap): Map<String, Any?> {
    val result = linkedMapOf<String, Any?>()
    val iterator = payload.keySetIterator()
    while (iterator.hasNextKey()) {
      val key = iterator.nextKey()
      result[key] = when (payload.getType(key)) {
        ReadableType.String -> payload.getString(key)
        ReadableType.Number -> payload.getDouble(key)
        ReadableType.Boolean -> payload.getBoolean(key)
        ReadableType.Null -> null
        else -> Unit
      }
    }
    return result
  }

  /** The Companion only transports a canonical v1 snapshot; bad JS input becomes explicit UNAVAILABLE. */
  private fun syncWearPayload(payload: ReadableMap) {
    val now = System.currentTimeMillis()
    val canonical = CompanionWearPayloadV1.decode(rawDataMapValues(payload), now) ?: CompanionWearPayloadV1.unavailable(now)
    val request = PutDataMapRequest.create("/binario/companion/watch-payload/v1")
    CompanionWearPayloadV1.put(request.dataMap, canonical)
    Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent())
  }

  override fun invalidate() { scope.cancel() }
}

class BinarioWidgetPackage : ReactPackage {
  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> = listOf(BinarioWidgetBridgeModule(reactContext))
  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}

/** Non interpreta né persiste il transcript: senza fonte autorizzata risponde fail-closed. */
class BinarioWatchVoiceRequestService : WearableListenerService() {
  override fun onMessageReceived(event: MessageEvent) {
    if (event.path == "/binario/companion/open-checklist/v1") {
      packageManager.getLaunchIntentForPackage(packageName)?.apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse("binario-companion://alerts")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      }?.let(::startActivity)
      return
    }
    if (event.path != "/binario/companion/voice-request/v1") return
    val answer = "Dati non disponibili in tempo reale."
    Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, "/binario/companion/voice-response/v1", answer.toByteArray(Charsets.UTF_8))
  }
}
