package im.binario.companion.binario

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import im.binario.companion.MainActivity
import im.binario.companion.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

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

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class BinarioWidgetPackage : ReactPackage {
  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> = listOf(BinarioWidgetBridgeModule(reactContext))
  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}

object BinarioChecklistHandoff {
  const val requestPath = "/binario/companion/open-checklist/v1"
  const val acknowledgementPath = "/binario/companion/open-checklist-ack/v1"
  private const val notificationChannelId = "binario_checklist_handoff"
  private const val pendingPreferenceName = "binario_checklist_handoff_v1"
  private const val pendingLifetimeMillis = 15 * 60 * 1_000L
  private const val nodeSeparator = '\n'
  private const val extraNodeId = "binario_checklist_node_id"
  private const val extraRequestId = "binario_checklist_request_id"

  private fun requestId(payload: ByteArray): String? {
    if (payload.isEmpty() || payload.size > 36) return null
    val value = payload.toString(Charsets.UTF_8)
    return runCatching { UUID.fromString(value).toString() }.getOrNull()?.takeIf { it == value }
  }

  private fun safeNodeId(value: String?) = value?.takeIf { node ->
    node.isNotBlank() && node.length <= 256 && node.none(Char::isISOControl)
  }

  private fun notificationsAllowed(context: Context, manager: NotificationManager): Boolean =
    manager.areNotificationsEnabled() &&
      (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

  private fun createChannel(manager: NotificationManager) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(NotificationChannel(notificationChannelId, "Richieste dallo smartwatch", NotificationManager.IMPORTANCE_DEFAULT))
    }
  }

  fun postNotification(context: Context, sourceNodeId: String, payload: ByteArray): Boolean {
    val nodeId = safeNodeId(sourceNodeId) ?: return false
    val id = requestId(payload) ?: return false
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    if (!notificationsAllowed(context, manager)) return false
    createChannel(manager)
    val now = System.currentTimeMillis()
    val preferences = context.getSharedPreferences(pendingPreferenceName, Context.MODE_PRIVATE)
    preferences.all.forEach { (key, raw) ->
      val createdAt = (raw as? String)?.substringBefore(nodeSeparator)?.toLongOrNull()
      if (createdAt == null || now - createdAt !in 0..pendingLifetimeMillis) preferences.edit().remove(key).apply()
    }
    if (!preferences.edit().putString(id, "$now$nodeSeparator$nodeId").commit()) return false
    val openChecklist = Intent(context, MainActivity::class.java).apply {
      action = Intent.ACTION_VIEW
      data = Uri.parse("binario-companion://alerts")
      putExtra(extraNodeId, nodeId)
      putExtra(extraRequestId, id)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    val pendingIntent = PendingIntent.getActivity(context, id.hashCode(), openChecklist, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, notificationChannelId) else Notification.Builder(context)
    val notification = builder
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Apri checklist Binario")
      .setContentText("Tocca per aprire la checklist sul telefono.")
      .setContentIntent(pendingIntent)
      .setAutoCancel(true)
      .setCategory(Notification.CATEGORY_REMINDER)
      .build()
    return try {
      manager.notify(id.hashCode(), notification)
      true
    } catch (_: RuntimeException) {
      preferences.edit().remove(id).apply()
      false
    }
  }

  @Synchronized
  fun acknowledgeOpened(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_VIEW || intent.data?.toString() != "binario-companion://alerts") return
    val nodeId = safeNodeId(intent.getStringExtra(extraNodeId)) ?: return
    val id = requestId(intent.getStringExtra(extraRequestId)?.toByteArray(Charsets.UTF_8) ?: return) ?: return
    val preferences = context.getSharedPreferences(pendingPreferenceName, Context.MODE_PRIVATE)
    val pending = preferences.getString(id, null) ?: return
    val createdAt = pending.substringBefore(nodeSeparator).toLongOrNull() ?: return
    if (pending.substringAfter(nodeSeparator, "") != nodeId || System.currentTimeMillis() - createdAt !in 0..pendingLifetimeMillis) {
      preferences.edit().remove(id).apply()
      return
    }
    if (!preferences.edit().remove(id).commit()) return
    intent.removeExtra(extraNodeId)
    intent.removeExtra(extraRequestId)
    Wearable.getMessageClient(context).sendMessage(nodeId, acknowledgementPath, id.toByteArray(Charsets.UTF_8))
  }
}

/** Non interpreta né persiste il transcript: senza fonte autorizzata risponde fail-closed. */
class BinarioWatchVoiceRequestService : WearableListenerService() {
  override fun onMessageReceived(event: MessageEvent) {
    if (event.path == BinarioChecklistHandoff.requestPath) {
      BinarioChecklistHandoff.postNotification(this, event.sourceNodeId, event.data)
      return
    }
    if (event.path != "/binario/companion/voice-request/v1") return
    val answer = "Dati non disponibili in tempo reale."
    Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, "/binario/companion/voice-response/v1", answer.toByteArray(Charsets.UTF_8))
  }
}
