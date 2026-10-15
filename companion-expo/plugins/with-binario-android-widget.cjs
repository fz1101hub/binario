const { withAndroidManifest, withAppBuildGradle, withDangerousMod } = require("@expo/config-plugins");
const fs = require("node:fs");
const path = require("node:path");

const releaseIdentity = require("../native/android/release-identity.json");
if (
  releaseIdentity.schemaVersion !== 1 ||
  releaseIdentity.applicationId !== "im.binario.companion" ||
  !/^\d+\.\d+\.\d+$/.test(releaseIdentity.versionName) ||
  !Number.isInteger(releaseIdentity.phoneVersionCode) ||
  !Number.isInteger(releaseIdentity.wearVersionCode) ||
  releaseIdentity.phoneVersionCode <= 0 ||
  releaseIdentity.wearVersionCode <= releaseIdentity.phoneVersionCode
) {
  throw new Error("Binario Companion release identity is invalid.");
}
const phoneApplicationId = releaseIdentity.applicationId;
const packageName = `${phoneApplicationId}.binario`;
const receiverName = `${packageName}.BinarioStatusWidgetReceiver`;

const sharedReleaseSigningGradle = `def binarioReleaseSigningProperties = [
  BINARIO_RELEASE_STORE_FILE: providers.gradleProperty("BINARIO_RELEASE_STORE_FILE").orElse(providers.environmentVariable("BINARIO_RELEASE_STORE_FILE")),
  BINARIO_RELEASE_STORE_PASSWORD: providers.gradleProperty("BINARIO_RELEASE_STORE_PASSWORD").orElse(providers.environmentVariable("BINARIO_RELEASE_STORE_PASSWORD")),
  BINARIO_RELEASE_KEY_ALIAS: providers.gradleProperty("BINARIO_RELEASE_KEY_ALIAS").orElse(providers.environmentVariable("BINARIO_RELEASE_KEY_ALIAS")),
  BINARIO_RELEASE_KEY_PASSWORD: providers.gradleProperty("BINARIO_RELEASE_KEY_PASSWORD").orElse(providers.environmentVariable("BINARIO_RELEASE_KEY_PASSWORD")),
]
def binarioReleaseSigningConfigured = binarioReleaseSigningProperties.values().every { it.isPresent() }
if (binarioReleaseSigningProperties.values().any { it.isPresent() } && !binarioReleaseSigningConfigured) {
  logger.warn("Release signing is enabled only when every BINARIO_RELEASE_* value is supplied; building unsigned.")
}`;

const sharedReleaseSigningBlock = `  signingConfigs {
    if (binarioReleaseSigningConfigured) {
      release {
        storeFile rootProject.file(binarioReleaseSigningProperties.BINARIO_RELEASE_STORE_FILE.get())
        storePassword binarioReleaseSigningProperties.BINARIO_RELEASE_STORE_PASSWORD.get()
        keyAlias binarioReleaseSigningProperties.BINARIO_RELEASE_KEY_ALIAS.get()
        keyPassword binarioReleaseSigningProperties.BINARIO_RELEASE_KEY_PASSWORD.get()
      }
    }
  }`;

function addSharedReleaseSigning(contents) {
  if (!contents.includes("def binarioReleaseSigningProperties")) {
    contents = `${sharedReleaseSigningGradle}\n\n${contents}`;
  }
  if (contents.includes("binarioReleaseSigningConfigured) signingConfig signingConfigs.release")) return contents;
  const androidStart = contents.indexOf("android {");
  const buildTypesStart = contents.indexOf("buildTypes {", androidStart);
  const releaseStart = contents.indexOf("release {", buildTypesStart);
  if (androidStart < 0 || buildTypesStart < 0 || releaseStart < 0) {
    throw new Error("Binario Companion Gradle no longer has the expected Android release block.");
  }
  if (!contents.slice(androidStart, buildTypesStart).includes("signingConfigs {")) {
    contents = contents.slice(0, androidStart + "android {".length) + `\n${sharedReleaseSigningBlock}` + contents.slice(androidStart + "android {".length);
  }
  const currentBuildTypesStart = contents.indexOf("buildTypes {", androidStart);
  const currentReleaseStart = contents.indexOf("release {", currentBuildTypesStart);
  const lineEnd = contents.indexOf("\n", currentReleaseStart);
  return contents.slice(0, lineEnd) + "\n            if (binarioReleaseSigningConfigured) signingConfig signingConfigs.release" + contents.slice(lineEnd);
}

function setGradleProperty(contents, name, value) {
  const matcher = new RegExp(`^${name.replaceAll(".", "\\.")}\\s*=.*$`, "gm");
  const matches = contents.match(matcher) ?? [];
  if (matches.length > 1) throw new Error(`Duplicate Gradle property: ${name}`);
  if (matches.length === 1) return contents.replace(matcher, `${name}=${value}`);
  return `${contents.trimEnd()}\n${name}=${value}\n`;
}

function removeDebugReleaseSigning(contents) {
  const marker = "release {";
  const start = contents.indexOf(marker);
  if (start < 0) return contents;
  const braceStart = contents.indexOf("{", start);
  let depth = 0;
  let end = -1;
  for (let index = braceStart; index < contents.length; index += 1) {
    if (contents[index] === "{") depth += 1;
    if (contents[index] === "}") {
      depth -= 1;
      if (depth === 0) {
        end = index;
        break;
      }
    }
  }
  if (end < 0) return contents;
  const releaseBlock = contents.slice(braceStart + 1, end)
    .replace(/^\s*signingConfig\s+signingConfigs\.debug\s*$/m, "")
    .replace(
      /^\s*\/\/\s*Caution! In production, you need to generate your own keystore file\.\s*\n\s*\/\/\s*see https:\/\/reactnative\.dev\/docs\/signed-apk-android\.\s*$/m,
      "            // Production signing is supplied only by the release/EAS environment."
    );
  return contents.slice(0, braceStart + 1) + releaseBlock + contents.slice(end);
}

const widgetStoreKt = `package ${packageName}

import android.content.Context
import im.binario.companion.R

data class BinarioWidgetState(
  val title: String,
  val subtitle: String,
  val status: String,
  val updatedAtUtcMs: Long? = null,
  val expiresAtUtcMs: Long? = null,
  val plannedJourneyTitle: String? = null,
  val plannedJourneyAtUtcMs: Long? = null,
  val plannedReminderMinutes: Int? = null,
)

object BinarioWidgetStore {
  private const val preferenceName = "binario_widget_payload_v1"
  private const val keyTitle = "title"
  private const val keySubtitle = "subtitle"
  private const val keyStatus = "status"
  private const val keyUpdated = "updated_at"
  private const val keyExpires = "expires_at"
  private const val keyPlannedTitle = "planned_title"
  private const val keyPlannedAt = "planned_at"
  private const val keyPlannedReminder = "planned_reminder"

  internal fun unavailable(title: String = "Binario") = BinarioWidgetState(
    title = title,
    subtitle = "Dati non disponibili in tempo reale.",
    status = "unavailable",
  )

  internal fun accepted(state: BinarioWidgetState, now: Long): BinarioWidgetState {
    val fresh = state.status == "available" && state.updatedAtUtcMs != null &&
      state.expiresAtUtcMs != null && state.updatedAtUtcMs <= now && state.expiresAtUtcMs > now
    val futureJourney = !state.plannedJourneyTitle.isNullOrBlank() &&
      state.plannedJourneyAtUtcMs != null && state.plannedJourneyAtUtcMs > now
    return if (fresh && futureJourney) state else unavailable(state.title)
  }

  private fun localizedUnavailable(context: Context, title: String = "Binario") = unavailable(title).copy(
    subtitle = context.getString(R.string.binario_unavailable),
  )
  fun write(context: Context, state: BinarioWidgetState, now: Long = System.currentTimeMillis()) {
    val audited = accepted(state, now)
    val accepted = if (audited.status == "available") audited else localizedUnavailable(context, state.title)
    context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().apply {
      putString(keyTitle, accepted.title)
      putString(keySubtitle, accepted.subtitle)
      putString(keyStatus, accepted.status)
      if (accepted.updatedAtUtcMs == null) remove(keyUpdated) else putLong(keyUpdated, accepted.updatedAtUtcMs)
      if (accepted.expiresAtUtcMs == null) remove(keyExpires) else putLong(keyExpires, accepted.expiresAtUtcMs)
      if (accepted.plannedJourneyTitle == null || accepted.plannedJourneyAtUtcMs == null || accepted.plannedJourneyAtUtcMs <= now) {
        remove(keyPlannedTitle); remove(keyPlannedAt); remove(keyPlannedReminder)
      } else {
        putString(keyPlannedTitle, accepted.plannedJourneyTitle)
        putLong(keyPlannedAt, accepted.plannedJourneyAtUtcMs)
        if (accepted.plannedReminderMinutes == null) remove(keyPlannedReminder) else putInt(keyPlannedReminder, accepted.plannedReminderMinutes)
      }
    }.apply()
  }

  fun read(context: Context, now: Long = System.currentTimeMillis()): BinarioWidgetState {
    val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    val title = prefs.getString(keyTitle, "Binario") ?: "Binario"
    val status = prefs.getString(keyStatus, "unavailable") ?: "unavailable"
    val updatedAt = if (prefs.contains(keyUpdated)) prefs.getLong(keyUpdated, 0L) else null
    val expiresAt = if (prefs.contains(keyExpires)) prefs.getLong(keyExpires, 0L) else null
    val plannedTitle = prefs.getString(keyPlannedTitle, null)
    val plannedAt = if (prefs.contains(keyPlannedAt)) prefs.getLong(keyPlannedAt, 0L) else null
    val reminder = if (prefs.contains(keyPlannedReminder)) prefs.getInt(keyPlannedReminder, 0) else null
    val audited = accepted(BinarioWidgetState(title, prefs.getString(keySubtitle, "") ?: "", status, updatedAt, expiresAt, plannedTitle, plannedAt, reminder), now)
    return if (audited.status == "available") audited else localizedUnavailable(context, title)
  }
}
`;

const widgetKt = `package ${packageName}

import android.content.Context
import android.content.ComponentName
import im.binario.companion.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

internal object BinarioWidgetPresentation {
  fun countdownLabel(scheduledAt: Long, now: Long = System.currentTimeMillis()): String {
    if (scheduledAt <= now) return "Dati non disponibili in tempo reale."
    val minutes = kotlin.math.ceil((scheduledAt - now).toDouble() / 60_000).toLong()
    if (minutes < 60) return "Tra ${'$'}minutes min"
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0L) "Tra ${'$'}hours h" else "Tra ${'$'}hours h ${'$'}remainder min"
  }
}

class BinarioStatusWidget : GlanceAppWidget() {
  private fun countdownLabel(context: Context, scheduledAt: Long, now: Long = System.currentTimeMillis()): String {
    if (scheduledAt <= now) return context.getString(R.string.binario_unavailable)
    val minutes = kotlin.math.ceil((scheduledAt - now).toDouble() / 60_000).toInt()
    if (minutes < 60) return context.getString(R.string.binario_countdown_minutes, minutes)
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0) context.getString(R.string.binario_countdown_hours, hours) else context.getString(R.string.binario_countdown_hours_minutes, hours, remainder)
  }
  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val state = BinarioWidgetStore.read(context)
    val voiceActivity = ComponentName(context, im.binario.companion.MainActivity::class.java)
    provideContent {
      Column(
        modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFF071525))).padding(16.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
      ) {
        Text(if (state.plannedJourneyTitle != null) context.getString(R.string.binario_next_journey) else "BINARIO", style = TextStyle(color = ColorProvider(Color(0xFF9EDBFF))))
        Text(state.plannedJourneyTitle ?: state.title, style = TextStyle(color = ColorProvider(Color.White)))
        Text(if (state.plannedJourneyAtUtcMs != null) countdownLabel(context, state.plannedJourneyAtUtcMs) else state.subtitle, style = TextStyle(color = ColorProvider(Color(0xFFD5E4F1))))
        if (state.plannedJourneyTitle != null) Text("${'$'}{state.plannedReminderMinutes?.let { context.getString(R.string.binario_reminder, it) } ?: ""}${'$'}{context.getString(R.string.binario_scheduled_not_realtime)}", style = TextStyle(color = ColorProvider(Color(0xFFD5E4F1))))
        Text(context.getString(R.string.binario_open_binario), style = TextStyle(color = ColorProvider(Color(0xFF9EDBFF))), modifier = GlanceModifier.clickable(onClick = actionStartActivity(voiceActivity)))
      }
    }
  }
}

class BinarioWidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    BinarioStatusWidget().updateAll(applicationContext)
    return Result.success()
  }

  companion object {
    private const val uniqueWorkName = "binario-widget-refresh-v1"
    private const val refreshMinutes = 15L

    fun schedule(context: Context) {
      val request = PeriodicWorkRequestBuilder<BinarioWidgetRefreshWorker>(refreshMinutes, TimeUnit.MINUTES).build()
      WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
        uniqueWorkName,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
      )
    }

    fun cancel(context: Context) {
      WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueWorkName)
    }
  }
}

class BinarioStatusWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = BinarioStatusWidget()

  override fun onEnabled(context: Context) {
    super.onEnabled(context)
    BinarioWidgetRefreshWorker.schedule(context)
  }

  override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
    super.onUpdate(context, appWidgetManager, appWidgetIds)
    BinarioWidgetRefreshWorker.schedule(context)
  }

  override fun onDisabled(context: Context) {
    BinarioWidgetRefreshWorker.cancel(context)
    super.onDisabled(context)
  }
}
`;

const bridgeKt = `package im.binario.companion.binario

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
    val subtitle = widget.getString("subtitle") ?: context.getString(R.string.binario_unavailable)
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
    val canonical = CompanionWearPayloadV1.decode(rawDataMapValues(payload), now) ?: CompanionWearPayloadV1.unavailable(
      now,
      context.getString(R.string.binario_companion_source),
      context.getString(R.string.binario_no_active_route),
      context.getString(R.string.binario_companion_unavailable_notice),
    )
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
  private const val nodeSeparator = '\\n'
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

  private fun createChannel(context: Context, manager: NotificationManager) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(NotificationChannel(notificationChannelId, context.getString(R.string.binario_checklist_channel_name), NotificationManager.IMPORTANCE_DEFAULT))
    }
  }

  fun postNotification(context: Context, sourceNodeId: String, payload: ByteArray): Boolean {
    val nodeId = safeNodeId(sourceNodeId) ?: return false
    val id = requestId(payload) ?: return false
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    if (!notificationsAllowed(context, manager)) return false
    createChannel(context, manager)
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
      .setContentTitle(context.getString(R.string.binario_checklist_notification_title))
      .setContentText(context.getString(R.string.binario_checklist_notification_text))
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
    val answer = getString(R.string.binario_unavailable)
    Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, "/binario/companion/voice-response/v1", answer.toByteArray(Charsets.UTF_8))
  }
}
`;

const companionWearPayloadKt = `package im.binario.companion.binario

import com.google.android.gms.wearable.DataMap
import java.nio.charset.StandardCharsets

/** Producer-side validation for the closed Companion → Wear v1 DataMap contract. */
object CompanionWearPayloadV1 {
  const val schemaVersion = 1L
  const val maxSerializedBytes = 8_192
  const val maxTtlMillis = 365L * 24L * 60L * 60L * 1_000L
  const val futureSkewMillis = 60_000L

  private val producerValues = setOf("ANDROID_APP", "COMPANION", "IOS_APP", "UNKNOWN")
  private val dataNatureValues = setOf("PROGRAMMED", "OPERATIONAL", "UNAVAILABLE", "UNKNOWN")
  private val operationalStateValues = setOf("PROGRAMMED", "OPERATIONAL", "UNAVAILABLE", "UNKNOWN")
  private val freshnessValues = setOf("FRESH", "AGING", "STALE", "UNAVAILABLE", "UNKNOWN")
  private val confidenceValues = setOf("OPERATOR_PRIMARY", "INFRASTRUCTURE_PRIMARY", "OPEN_DATA", "LICENSED_AGGREGATE", "STATIC_ONLY", "CACHED", "LOCAL_PLANNED", "UNKNOWN")
  private val directionValues = setOf("OUTBOUND", "RETURN", "UNKNOWN")
  private val baseKeys = listOf(
    "schemaVersion", "generatedAtEpochMillis", "expiresAtEpochMillis", "ttlMillis",
    "provenanceProducer", "provenanceDataNature", "provenanceSourceLabel", "provenanceObservedAtEpochMillis",
    "operationalState", "freshness", "confidence", "routeId", "routeLabel", "direction", "notice", "serviceCount",
  )
  private val serviceFields = listOf("Number", "Operator", "Category", "Destination", "Scheduled", "Expected", "Status", "Platform", "DelayMinutes")

  private fun normalized(value: Any?, maximum: Int, allowEmpty: Boolean = false): String? {
    val string = value as? String ?: return null
    val normal = string.trim().replace(Regex("\\\\s+"), " ")
    return string.takeIf { it == normal && it.length <= maximum && (allowEmpty || it.isNotEmpty()) }
  }

  private fun integer(value: Any?): Long? {
    val number = value as? Number ?: return null
    val result = number.toLong()
    return result.takeIf {
      number.toDouble().isFinite() && number.toDouble() == result.toDouble() &&
        result in -9_007_199_254_740_991L..9_007_199_254_740_991L
    }
  }

  private fun expectedKeys(serviceCount: Int): Set<String> = buildSet {
    addAll(baseKeys)
    repeat(serviceCount) { index -> serviceFields.forEach { field -> add("service\${index}$field") } }
  }

  private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { character -> when (character) {
      '"' -> append("\\\\\\"")
      '\\\\' -> append("\\\\\\\\")
      '\\b' -> append("\\\\b")
      '\\u000C' -> append("\\\\f")
      '\\n' -> append("\\\\n")
      '\\r' -> append("\\\\r")
      '\\t' -> append("\\\\t")
      else -> if (character.code < 0x20) append("\\\\u%04x".format(character.code)) else append(character)
    } }
    append('"')
  }

  private fun serializedBytes(values: Map<String, Any>): Int = values.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
    "\${jsonString(key)}:\${if (value is String) jsonString(value) else value}"
  }.toByteArray(StandardCharsets.UTF_8).size

  /** Returns a canonical map only when every supplied key and value satisfies v1. */
  fun decode(raw: Map<String, Any?>, now: Long): Map<String, Any>? {
    val serviceCount = integer(raw["serviceCount"])?.takeIf { it in 0L..2L }?.toInt() ?: return null
    if (raw.keys != expectedKeys(serviceCount)) return null
    val schema = integer(raw["schemaVersion"]) ?: return null
    val generated = integer(raw["generatedAtEpochMillis"]) ?: return null
    val expires = integer(raw["expiresAtEpochMillis"]) ?: return null
    val ttl = integer(raw["ttlMillis"]) ?: return null
    val observed = integer(raw["provenanceObservedAtEpochMillis"]) ?: return null
    if (schema != schemaVersion || ttl !in 1..maxTtlMillis || expires - generated != ttl || expires <= now || generated > now + futureSkewMillis) return null
    val producer = normalized(raw["provenanceProducer"], 64)?.takeIf(producerValues::contains) ?: return null
    val dataNature = normalized(raw["provenanceDataNature"], 64)?.takeIf(dataNatureValues::contains) ?: return null
    val operational = normalized(raw["operationalState"], 64)?.takeIf(operationalStateValues::contains) ?: return null
    val freshness = normalized(raw["freshness"], 64)?.takeIf(freshnessValues::contains) ?: return null
    val confidence = normalized(raw["confidence"], 64)?.takeIf(confidenceValues::contains) ?: return null
    val direction = normalized(raw["direction"], 16)?.takeIf(directionValues::contains) ?: return null
    val sourceLabel = normalized(raw["provenanceSourceLabel"], 96) ?: return null
    val routeId = normalized(raw["routeId"], 64) ?: return null
    val routeLabel = normalized(raw["routeLabel"], 96) ?: return null
    val notice = normalized(raw["notice"], 180, allowEmpty = true) ?: return null
    if ((operational == "UNAVAILABLE" || dataNature == "UNAVAILABLE") && (serviceCount != 0 || dataNature != "UNAVAILABLE")) return null
    val canonical = linkedMapOf<String, Any>(
      "schemaVersion" to schema,
      "generatedAtEpochMillis" to generated,
      "expiresAtEpochMillis" to expires,
      "ttlMillis" to ttl,
      "provenanceProducer" to producer,
      "provenanceDataNature" to dataNature,
      "provenanceSourceLabel" to sourceLabel,
      "provenanceObservedAtEpochMillis" to observed,
      "operationalState" to operational,
      "freshness" to freshness,
      "confidence" to confidence,
      "routeId" to routeId,
      "routeLabel" to routeLabel,
      "direction" to direction,
      "notice" to notice,
      "serviceCount" to serviceCount,
    )
    repeat(serviceCount) { index ->
      val prefix = "service$index"
      listOf("Number" to 24, "Operator" to 64, "Category" to 64, "Destination" to 96, "Scheduled" to 16, "Expected" to 16, "Status" to 64).forEach { (field, maximum) ->
        canonical["$prefix$field"] = normalized(raw["$prefix$field"], maximum) ?: return null
      }
      canonical["\${prefix}Platform"] = normalized(raw["\${prefix}Platform"], 16, allowEmpty = true) ?: return null
      canonical["\${prefix}DelayMinutes"] = integer(raw["\${prefix}DelayMinutes"])?.takeIf { it in -720..1_440 } ?: return null
    }
    return canonical.takeIf { serializedBytes(it) <= maxSerializedBytes }
  }

  /** A bounded explicit fallback is safer than manufacturing operational information. */
  fun unavailable(now: Long, sourceLabel: String, routeLabel: String, notice: String): Map<String, Any> = linkedMapOf(
    "schemaVersion" to schemaVersion,
    "generatedAtEpochMillis" to now,
    "expiresAtEpochMillis" to now + 15L * 60L * 1_000L,
    "ttlMillis" to 15L * 60L * 1_000L,
    "provenanceProducer" to "COMPANION",
    "provenanceDataNature" to "UNAVAILABLE",
    "provenanceSourceLabel" to sourceLabel,
    "provenanceObservedAtEpochMillis" to now,
    "operationalState" to "UNAVAILABLE",
    "freshness" to "UNAVAILABLE",
    "confidence" to "LOCAL_PLANNED",
    "routeId" to "unavailable",
    "routeLabel" to routeLabel,
    "direction" to "UNKNOWN",
    "notice" to notice,
    "serviceCount" to 0,
  )

  fun put(dataMap: DataMap, payload: Map<String, Any>) {
    payload.forEach { (key, value) -> when (value) {
      is String -> dataMap.putString(key, value)
      is Int -> dataMap.putInt(key, value)
      is Long -> dataMap.putLong(key, value)
      else -> error("Unexpected canonical v1 DataMap type for $key")
    } }
  }
}
`;

const sharedTextReceiverKt = `package ${packageName}

import android.content.Context
import android.content.Intent
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.NativeModule
import com.facebook.react.ReactPackage
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.uimanager.ViewManager

/**
 * Riceve solo testo scelto nel menu Condividi; non accetta file, URL da risolvere o MIME generici.
 * Il testo resta nelle preferenze finché JS non chiama consume(): l'evento emesso ad app attiva è solo un segnale.
 */
object BinarioSharedTextStore {
  private const val preferences = "binario_pending_shared_text_v1"
  private const val keyText = "text"
  private const val maxCharacters = 4_000
  private const val eventName = "BinarioSharedTextReceived"
  private var reactContext: ReactApplicationContext? = null

  private fun normalize(raw: String?): String? {
    val text = raw?.replace("\\u0000", "")?.trim() ?: return null
    return text.takeIf { it.isNotEmpty() && it.length <= maxCharacters }
  }

  fun attach(context: ReactApplicationContext) { reactContext = context }

  fun capture(context: Context, intent: Intent?) {
    if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
    val text = normalize(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()) ?: return
    context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().putString(keyText, text).apply()
    val current = reactContext?.takeIf { it.hasActiveReactInstance() } ?: return
    current.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(eventName, text)
  }

  fun consume(context: Context): String? {
    val prefs = context.getSharedPreferences(preferences, Context.MODE_PRIVATE)
    val text = normalize(prefs.getString(keyText, null))
    prefs.edit().remove(keyText).apply()
    return text
  }
}

class BinarioSharedTextReceiverModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  init { BinarioSharedTextStore.attach(context) }
  override fun getName() = "BinarioSharedTextReceiver"
  @ReactMethod fun consume(promise: Promise) { promise.resolve(BinarioSharedTextStore.consume(context)) }
  @ReactMethod fun addListener(eventName: String) = Unit
  @ReactMethod fun removeListeners(count: Int) = Unit
}

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class BinarioSharedTextReceiverPackage : ReactPackage {
  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> = listOf(BinarioSharedTextReceiverModule(reactContext))
  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
`;

const widgetXml = `<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
  android:description="@string/binario_widget_description"
  android:minWidth="180dp"
  android:minHeight="100dp"
  android:resizeMode="horizontal|vertical"
  android:widgetCategory="home_screen" />
`;

const shortcutsXml = `<?xml version="1.0" encoding="utf-8"?>
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
  <shortcut android:shortcutId="plan" android:enabled="true" android:icon="@mipmap/ic_launcher" android:shortcutShortLabel="@string/binario_shortcut_plan_short" android:shortcutLongLabel="@string/binario_shortcut_plan_long">
    <intent android:action="android.intent.action.VIEW" android:targetPackage="im.binario.companion" android:targetClass="im.binario.companion.MainActivity" android:data="binario-companion://" />
  </shortcut>
  <shortcut android:shortcutId="alerts" android:enabled="true" android:icon="@mipmap/ic_launcher" android:shortcutShortLabel="@string/binario_shortcut_alerts_short" android:shortcutLongLabel="@string/binario_shortcut_alerts_long">
    <intent android:action="android.intent.action.VIEW" android:targetPackage="im.binario.companion" android:targetClass="im.binario.companion.MainActivity" android:data="binario-companion://alerts" />
  </shortcut>
</shortcuts>
`;

/** Kept in the config-plugin template so Android prebuilds are deterministic and idempotent. */
const nativeLocales = ["it", "en", "de", "fr", "es"];
const localizedNativeStrings = {
  "it": {
    "app_name": "Binario Companion",
    "widget_label": "Stato Binario",
    "widget_description": "Stato ferroviario verificato di Binario",
    "shortcut_plan_short": "Pianifica",
    "shortcut_plan_long": "Pianifica una tratta locale",
    "shortcut_alerts_short": "Promemoria",
    "shortcut_alerts_long": "Apri promemoria locali",
    "share_text_label": "Importa testo in Binario",
    "tile_name": "Stato Binario",
    "voice_request_label": "Parla a Binario",
    "checklist_request_label": "Apri checklist sul telefono",
    "unavailable": "Dati non disponibili in tempo reale.",
    "next_journey": "PROSSIMA TRATTA · PROGRAMMATA",
    "open_binario": "Apri Binario e detta",
    "reminder": "Promemoria %1$d min · ",
    "scheduled_not_realtime": "Orario programmato, non realtime",
    "countdown_minutes": "Tra %1$d min",
    "countdown_hours": "Tra %1$d h",
    "countdown_hours_minutes": "Tra %1$d h %2$d min",
    "microphone": "Microfono · Parla a Binario",
    "voice_prompt": "Chiedi a Binario",
    "dictation_unavailable": "Dettatura di sistema non disponibile.",
    "checking": "Verifica in corso…",
    "cancel": "Annulla",
    "no_voice": "Nessuna richiesta vocale acquisita.",
    "show_next": "Mostra prossima tratta",
    "retry": "Riprova",
    "close": "Chiudi",
    "listen": "Ascolta risposta",
    "enable_auto_read": "Attiva lettura automatica",
    "disable_auto_read": "Disattiva lettura automatica",
    "opening_checklist": "Apro la checklist sul telefono…",
    "phone_unreachable": "Telefono non raggiungibile.",
    "checklist_opened": "Checklist aperta sul telefono.",
    "checklist_channel_name": "Richieste dallo smartwatch",
    "checklist_notification_title": "Apri checklist Binario",
    "checklist_notification_text": "Tocca per aprire la checklist sul telefono.",
    "cannot_open_phone": "Non è possibile aprire il telefono ora.",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "Apre la richiesta vocale sullo smartwatch",
    "checklist": "Checklist · %1$d voci sul telefono",
    "checklist_state": "%1$d voci da preparare",
    "checking_state": "Verifica in corso. È disponibile il pulsante Annulla.",
    "cancel_checking": "Annulla verifica",
    "no_voice_state": "Nessuna richiesta vocale acquisita. Puoi mostrare la prossima tratta o riprovare.",
    "show_next_state": "Mostra la prossima tratta programmata",
    "retry_state": "Riprova richiesta vocale",
    "close_state": "Chiudi richiesta Binario",
    "answer_heading": "Risposta Binario. %1$s",
    "next_query": "prossima tratta",
    "saved_journey_keywords": "prossima|prossimo|mio viaggio|mia tratta|a che ora parte",
    "realtime_keywords": "ritard|binari|cancell|avvis|realtime",
    "companion_source": "Pianificazione locale del Companion",
    "no_active_route": "Nessuna tratta attiva",
    "companion_unavailable_notice": "Dati non disponibili nel Companion: apri l’app principale per aggiornare la pianificazione locale."
  },
  "en": {
    "app_name": "Binario Companion",
    "widget_label": "Binario status",
    "widget_description": "Verified Binario railway status",
    "shortcut_plan_short": "Plan",
    "shortcut_plan_long": "Plan a local journey",
    "shortcut_alerts_short": "Reminders",
    "shortcut_alerts_long": "Open local reminders",
    "share_text_label": "Import text in Binario",
    "tile_name": "Binario status",
    "voice_request_label": "Talk to Binario",
    "checklist_request_label": "Open checklist on phone",
    "unavailable": "Real-time data unavailable.",
    "next_journey": "NEXT JOURNEY · SCHEDULED",
    "open_binario": "Open Binario and dictate",
    "reminder": "Reminder %1$d min · ",
    "scheduled_not_realtime": "Scheduled time, not real-time",
    "countdown_minutes": "In %1$d min",
    "countdown_hours": "In %1$d h",
    "countdown_hours_minutes": "In %1$d h %2$d min",
    "microphone": "Microphone · Talk to Binario",
    "voice_prompt": "Ask Binario",
    "dictation_unavailable": "System dictation unavailable.",
    "checking": "Checking…",
    "cancel": "Cancel",
    "no_voice": "No voice request captured.",
    "show_next": "Show next journey",
    "retry": "Try again",
    "close": "Close",
    "listen": "Listen to answer",
    "enable_auto_read": "Enable automatic reading",
    "disable_auto_read": "Disable automatic reading",
    "opening_checklist": "Opening the checklist on the phone…",
    "phone_unreachable": "Phone unreachable.",
    "checklist_opened": "Checklist opened on phone.",
    "checklist_channel_name": "Smartwatch requests",
    "checklist_notification_title": "Open Binario checklist",
    "checklist_notification_text": "Tap to open the checklist on your phone.",
    "cannot_open_phone": "The phone cannot be opened now.",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "Opens the voice request on the smartwatch",
    "checklist": "Checklist · %1$d items on phone",
    "checklist_state": "%1$d items to prepare",
    "checking_state": "Checking. The Cancel button is available.",
    "cancel_checking": "Cancel check",
    "no_voice_state": "No voice request was captured. You can show the next journey or try again.",
    "show_next_state": "Show the next planned journey",
    "retry_state": "Try voice request again",
    "close_state": "Close Binario request",
    "answer_heading": "Binario answer. %1$s",
    "next_query": "next journey",
    "saved_journey_keywords": "next|my journey|my trip|what time does it leave",
    "realtime_keywords": "delay|platform|cancel|alert|real-time|realtime",
    "companion_source": "Companion local schedule",
    "no_active_route": "No active journey",
    "companion_unavailable_notice": "Data unavailable in the Companion: open the main app to update the local schedule."
  },
  "de": {
    "app_name": "Binario Companion",
    "widget_label": "Binario-Status",
    "widget_description": "Verifizierter Bahnstatus von Binario",
    "shortcut_plan_short": "Planen",
    "shortcut_plan_long": "Lokale Fahrt planen",
    "shortcut_alerts_short": "Erinnerungen",
    "shortcut_alerts_long": "Lokale Erinnerungen öffnen",
    "share_text_label": "Text in Binario importieren",
    "tile_name": "Binario-Status",
    "voice_request_label": "Mit Binario sprechen",
    "checklist_request_label": "Checkliste auf dem Telefon öffnen",
    "unavailable": "Echtzeitdaten nicht verfügbar.",
    "next_journey": "NÄCHSTE FAHRT · GEPLANT",
    "open_binario": "Binario öffnen und sprechen",
    "reminder": "Erinnerung %1$d Min. · ",
    "scheduled_not_realtime": "Geplante Zeit, nicht in Echtzeit",
    "countdown_minutes": "In %1$d Min.",
    "countdown_hours": "In %1$d Std.",
    "countdown_hours_minutes": "In %1$d Std. %2$d Min.",
    "microphone": "Mikrofon · Mit Binario sprechen",
    "voice_prompt": "Binario fragen",
    "dictation_unavailable": "Systemdiktat nicht verfügbar.",
    "checking": "Wird geprüft…",
    "cancel": "Abbrechen",
    "no_voice": "Keine Sprachanfrage erfasst.",
    "show_next": "Nächste Fahrt anzeigen",
    "retry": "Erneut versuchen",
    "close": "Schließen",
    "listen": "Antwort anhören",
    "enable_auto_read": "Automatisches Vorlesen aktivieren",
    "disable_auto_read": "Automatisches Vorlesen deaktivieren",
    "opening_checklist": "Checkliste wird auf dem Telefon geöffnet…",
    "phone_unreachable": "Telefon nicht erreichbar.",
    "checklist_opened": "Checkliste auf dem Telefon geöffnet.",
    "checklist_channel_name": "Smartwatch-Anfragen",
    "checklist_notification_title": "Binario-Checkliste öffnen",
    "checklist_notification_text": "Tippen, um die Checkliste auf dem Telefon zu öffnen.",
    "cannot_open_phone": "Das Telefon kann jetzt nicht geöffnet werden.",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "Öffnet die Sprachanfrage auf der Smartwatch",
    "checklist": "Checkliste · %1$d Einträge auf dem Telefon",
    "checklist_state": "%1$d Einträge vorzubereiten",
    "checking_state": "Prüfung läuft. Die Schaltfläche Abbrechen ist verfügbar.",
    "cancel_checking": "Prüfung abbrechen",
    "no_voice_state": "Keine Sprachanfrage erfasst. Sie können die nächste Fahrt anzeigen oder es erneut versuchen.",
    "show_next_state": "Nächste geplante Fahrt anzeigen",
    "retry_state": "Sprachanfrage erneut versuchen",
    "close_state": "Binario-Anfrage schließen",
    "answer_heading": "Binario-Antwort. %1$s",
    "next_query": "nächste Fahrt",
    "saved_journey_keywords": "nächste|nächster|meine Fahrt|meine Reise|wann fährt",
    "realtime_keywords": "verspät|gleis|ausfall|warn|echtzeit",
    "companion_source": "Lokale Companion-Planung",
    "no_active_route": "Keine aktive Strecke",
    "companion_unavailable_notice": "Daten im Companion nicht verfügbar: Öffnen Sie die Haupt-App, um die lokale Planung zu aktualisieren."
  },
  "fr": {
    "app_name": "Binario Companion",
    "widget_label": "État Binario",
    "widget_description": "État ferroviaire vérifié de Binario",
    "shortcut_plan_short": "Planifier",
    "shortcut_plan_long": "Planifier un trajet local",
    "shortcut_alerts_short": "Rappels",
    "shortcut_alerts_long": "Ouvrir les rappels locaux",
    "share_text_label": "Importer du texte dans Binario",
    "tile_name": "État Binario",
    "voice_request_label": "Parler à Binario",
    "checklist_request_label": "Ouvrir la liste sur le téléphone",
    "unavailable": "Données en temps réel indisponibles.",
    "next_journey": "PROCHAIN TRAJET · PLANIFIÉ",
    "open_binario": "Ouvrir Binario et dicter",
    "reminder": "Rappel %1$d min · ",
    "scheduled_not_realtime": "Horaire planifié, pas en temps réel",
    "countdown_minutes": "Dans %1$d min",
    "countdown_hours": "Dans %1$d h",
    "countdown_hours_minutes": "Dans %1$d h %2$d min",
    "microphone": "Microphone · Parler à Binario",
    "voice_prompt": "Demander à Binario",
    "dictation_unavailable": "Dictée système indisponible.",
    "checking": "Vérification en cours…",
    "cancel": "Annuler",
    "no_voice": "Aucune demande vocale capturée.",
    "show_next": "Afficher le prochain trajet",
    "retry": "Réessayer",
    "close": "Fermer",
    "listen": "Écouter la réponse",
    "enable_auto_read": "Activer la lecture automatique",
    "disable_auto_read": "Désactiver la lecture automatique",
    "opening_checklist": "Ouverture de la liste sur le téléphone…",
    "phone_unreachable": "Téléphone inaccessible.",
    "checklist_opened": "Liste ouverte sur le téléphone.",
    "checklist_channel_name": "Demandes de la montre",
    "checklist_notification_title": "Ouvrir la liste Binario",
    "checklist_notification_text": "Touchez pour ouvrir la liste sur le téléphone.",
    "cannot_open_phone": "Le téléphone ne peut pas être ouvert maintenant.",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "Ouvre la demande vocale sur la montre",
    "checklist": "Liste · %1$d éléments sur le téléphone",
    "checklist_state": "%1$d éléments à préparer",
    "checking_state": "Vérification en cours. Le bouton Annuler est disponible.",
    "cancel_checking": "Annuler la vérification",
    "no_voice_state": "Aucune demande vocale n’a été capturée. Vous pouvez afficher le prochain trajet ou réessayer.",
    "show_next_state": "Afficher le prochain trajet planifié",
    "retry_state": "Réessayer la demande vocale",
    "close_state": "Fermer la demande Binario",
    "answer_heading": "Réponse Binario. %1$s",
    "next_query": "prochain trajet",
    "saved_journey_keywords": "prochain|prochaine|mon trajet|mon voyage|quelle heure",
    "realtime_keywords": "retard|voie|annul|alerte|temps réel",
    "companion_source": "Planification locale du Companion",
    "no_active_route": "Aucun trajet actif",
    "companion_unavailable_notice": "Données indisponibles dans le Companion : ouvrez l’application principale pour mettre à jour la planification locale."
  },
  "es": {
    "app_name": "Binario Companion",
    "widget_label": "Estado de Binario",
    "widget_description": "Estado ferroviario verificado de Binario",
    "shortcut_plan_short": "Planificar",
    "shortcut_plan_long": "Planificar un viaje local",
    "shortcut_alerts_short": "Recordatorios",
    "shortcut_alerts_long": "Abrir recordatorios locales",
    "share_text_label": "Importar texto en Binario",
    "tile_name": "Estado de Binario",
    "voice_request_label": "Hablar con Binario",
    "checklist_request_label": "Abrir lista en el teléfono",
    "unavailable": "Datos en tiempo real no disponibles.",
    "next_journey": "PRÓXIMO VIAJE · PLANIFICADO",
    "open_binario": "Abrir Binario y dictar",
    "reminder": "Recordatorio %1$d min · ",
    "scheduled_not_realtime": "Hora programada, no en tiempo real",
    "countdown_minutes": "En %1$d min",
    "countdown_hours": "En %1$d h",
    "countdown_hours_minutes": "En %1$d h %2$d min",
    "microphone": "Micrófono · Hablar con Binario",
    "voice_prompt": "Preguntar a Binario",
    "dictation_unavailable": "Dictado del sistema no disponible.",
    "checking": "Comprobando…",
    "cancel": "Cancelar",
    "no_voice": "No se capturó ninguna solicitud de voz.",
    "show_next": "Mostrar próximo viaje",
    "retry": "Reintentar",
    "close": "Cerrar",
    "listen": "Escuchar respuesta",
    "enable_auto_read": "Activar lectura automática",
    "disable_auto_read": "Desactivar lectura automática",
    "opening_checklist": "Abriendo la lista en el teléfono…",
    "phone_unreachable": "Teléfono inalcanzable.",
    "checklist_opened": "Lista abierta en el teléfono.",
    "checklist_channel_name": "Solicitudes del reloj",
    "checklist_notification_title": "Abrir lista de Binario",
    "checklist_notification_text": "Toca para abrir la lista en el teléfono.",
    "cannot_open_phone": "No se puede abrir el teléfono ahora.",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "Abre la solicitud de voz en el reloj",
    "checklist": "Lista · %1$d elementos en el teléfono",
    "checklist_state": "%1$d elementos por preparar",
    "checking_state": "Comprobando. El botón Cancelar está disponible.",
    "cancel_checking": "Cancelar comprobación",
    "no_voice_state": "No se capturó ninguna solicitud de voz. Puedes mostrar el próximo viaje o intentarlo de nuevo.",
    "show_next_state": "Mostrar el próximo viaje planificado",
    "retry_state": "Reintentar solicitud de voz",
    "close_state": "Cerrar solicitud de Binario",
    "answer_heading": "Respuesta de Binario. %1$s",
    "next_query": "próximo viaje",
    "saved_journey_keywords": "próximo|próxima|mi viaje|mi trayecto|a qué hora",
    "realtime_keywords": "retras|andén|cancel|aviso|tiempo real",
    "companion_source": "Planificación local de Companion",
    "no_active_route": "Ningún trayecto activo",
    "companion_unavailable_notice": "Datos no disponibles en Companion: abre la aplicación principal para actualizar la planificación local."
  }
};

const nativeStringKeys = Object.keys(localizedNativeStrings.it);
for (const locale of nativeLocales) {
  const keys = Object.keys(localizedNativeStrings[locale]);
  if (keys.length !== nativeStringKeys.length || keys.some((key, index) => key !== nativeStringKeys[index])) {
    throw new Error(`Native localization key parity failed for ${locale}.`);
  }
}

function nativeStringsXml(strings, wear = false) {
  const runtimeEntries = Object.fromEntries(Object.entries(strings)
    .filter(([key]) => !["app_name", "widget_label", "widget_description", "shortcut_plan_short", "shortcut_plan_long", "shortcut_alerts_short", "shortcut_alerts_long", "share_text_label", "tile_name", "voice_request_label", "checklist_request_label"].includes(key))
    .map(([key, value]) => [`binario_${key}`, value]));
  const entries = wear
    ? { app_name: strings.app_name, tile_name: strings.tile_name, voice_request_label: strings.voice_request_label, checklist_request_label: strings.checklist_request_label, ...runtimeEntries }
    : { app_name: strings.app_name, binario_widget_label: strings.widget_label, binario_widget_description: strings.widget_description, binario_shortcut_plan_short: strings.shortcut_plan_short, binario_shortcut_plan_long: strings.shortcut_plan_long, binario_shortcut_alerts_short: strings.shortcut_alerts_short, binario_shortcut_alerts_long: strings.shortcut_alerts_long, binario_share_text_label: strings.share_text_label, ...runtimeEntries };
  return `<resources>\n${Object.entries(entries).map(([key, value]) => `  <string name="${key}">${String(value).replaceAll("&", "&amp;").replaceAll("'", "\\'")}</string>`).join("\n")}\n</resources>\n`;
}

function withBinarioAndroidWidget(config) {
  config = withAppBuildGradle(config, (next) => {
    const lines = [
      '    implementation("androidx.glance:glance-appwidget:1.2.0")',
      '    implementation("androidx.glance:glance:1.2.0")',
      '    implementation("androidx.work:work-runtime-ktx:2.7.1")',
      '    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")',
      '    implementation("com.google.android.gms:play-services-wearable:20.0.1")',
      '    testImplementation("junit:junit:4.13.2")',
    ];
    const missing = lines.filter((line) => !next.modResults.contents.includes(line));
    if (missing.length > 0) next.modResults.contents = next.modResults.contents.replace("dependencies {", `dependencies {\n${missing.join("\n")}`);
    next.modResults.contents = addSharedReleaseSigning(removeDebugReleaseSigning(next.modResults.contents));
    return next;
  });

  config = withAndroidManifest(config, (next) => {
    const manifest = next.modResults.manifest;
    const application = manifest.application?.[0];
    if (!application) throw new Error("Android manifest senza application");
    const blockedProductionPermissions = new Set([
      "android.permission.ACCESS_FINE_LOCATION",
      "android.permission.READ_EXTERNAL_STORAGE",
      "android.permission.WRITE_EXTERNAL_STORAGE",
      "android.permission.SYSTEM_ALERT_WINDOW",
      "android.permission.SCHEDULE_EXACT_ALARM",
      "android.permission.USE_EXACT_ALARM",
    ]);
    manifest["uses-permission"] = (manifest["uses-permission"] ?? []).filter(
      (permission) =>
        permission.$?.["tools:node"] === "remove" ||
        !blockedProductionPermissions.has(permission.$?.["android:name"]),
    );
    application.$ = application.$ ?? {};
    application.$["android:allowBackup"] = "false";
    application.$["android:usesCleartextTraffic"] = "false";
    application.receiver = application.receiver ?? [];
    application.service = application.service ?? [];
    application["meta-data"] = application["meta-data"] ?? [];
    if (!application["meta-data"].some((item) => item.$?.["android:name"] === "android.app.shortcuts")) {
      application["meta-data"].push({ $: { "android:name": "android.app.shortcuts", "android:resource": "@xml/binario_shortcuts" } });
    }
    if (!application.receiver.some((receiver) => receiver.$?.["android:name"] === receiverName)) {
      application.receiver.push({
        $: { "android:name": receiverName, "android:exported": "true", "android:label": "@string/binario_widget_label" },
        "intent-filter": [{ action: [{ $: { "android:name": "android.appwidget.action.APPWIDGET_UPDATE" } }] }],
        "meta-data": [{ $: { "android:name": "android.appwidget.provider", "android:resource": "@xml/binario_status_widget_info" } }],
      });
    }
    if (!application.service.some((service) => service.$?.["android:name"] === `${packageName}.BinarioWatchVoiceRequestService`)) {
      application.service.push({
        $: { "android:name": `${packageName}.BinarioWatchVoiceRequestService`, "android:exported": "true" },
        "intent-filter": [{ action: [{ $: { "android:name": "com.google.android.gms.wearable.MESSAGE_RECEIVED" } }], data: [{ $: { "android:scheme": "wear", "android:host": "*", "android:pathPrefix": "/binario/companion/voice-request/v1" } }, { $: { "android:scheme": "wear", "android:host": "*", "android:pathPrefix": "/binario/companion/open-checklist/v1" } }] }],
      });
    }
    application.activity = application.activity ?? [];
    const mainActivity = application.activity.find((activity) => activity.$?.["android:name"] === ".MainActivity");
    if (mainActivity) {
      mainActivity["intent-filter"] = mainActivity["intent-filter"] ?? [];
      const acceptsText = mainActivity["intent-filter"].some((filter) => filter.action?.some((action) => action.$?.["android:name"] === "android.intent.action.SEND") && filter.data?.some((data) => data.$?.["android:mimeType"] === "text/plain"));
      if (!acceptsText) mainActivity["intent-filter"].push({
        $: { "android:label": "@string/binario_share_text_label" },
        action: [{ $: { "android:name": "android.intent.action.SEND" } }],
        category: [{ $: { "android:name": "android.intent.category.DEFAULT" } }],
        data: [{ $: { "android:mimeType": "text/plain" } }],
      });
    }
    return next;
  });

  return withDangerousMod(config, ["android", async (next) => {
    const appRoot = path.join(next.modRequest.platformProjectRoot, "app");
    const verificationMetadataSource = path.join(__dirname, "..", "native", "android", "verification-metadata.xml");
    const verificationMetadataTarget = path.join(next.modRequest.platformProjectRoot, "gradle", "verification-metadata.xml");
    if (!fs.existsSync(verificationMetadataSource)) throw new Error("Companion Gradle verification metadata is missing");
    fs.mkdirSync(path.dirname(verificationMetadataTarget), { recursive: true });
    fs.copyFileSync(verificationMetadataSource, verificationMetadataTarget);
    const kotlinRoot = path.join(appRoot, "src", "main", "java", ...packageName.split("."));
    const testKotlinRoot = path.join(appRoot, "src", "test", "java", ...packageName.split("."));
    const xmlRoot = path.join(appRoot, "src", "main", "res", "xml");
    fs.mkdirSync(kotlinRoot, { recursive: true });
    fs.mkdirSync(testKotlinRoot, { recursive: true });
    fs.mkdirSync(xmlRoot, { recursive: true });
    fs.writeFileSync(path.join(kotlinRoot, "BinarioWidgetStore.kt"), widgetStoreKt);
    fs.writeFileSync(path.join(kotlinRoot, "BinarioStatusWidget.kt"), widgetKt);
    fs.writeFileSync(path.join(kotlinRoot, "BinarioWidgetBridge.kt"), bridgeKt);
    fs.writeFileSync(path.join(kotlinRoot, "CompanionWearPayload.kt"), companionWearPayloadKt);
    fs.writeFileSync(
      path.join(testKotlinRoot, "CompanionWearPayloadV1Test.kt"),
      fs.readFileSync(path.join(__dirname, "templates", "CompanionWearPayloadV1Test.kt"), "utf8"),
    );
    fs.writeFileSync(
      path.join(testKotlinRoot, "BinarioWidgetStateTest.kt"),
      fs.readFileSync(path.join(__dirname, "templates", "BinarioWidgetStateTest.kt"), "utf8"),
    );
    fs.writeFileSync(path.join(kotlinRoot, "BinarioSharedTextReceiver.kt"), sharedTextReceiverKt);
    fs.writeFileSync(path.join(xmlRoot, "binario_status_widget_info.xml"), widgetXml);
    fs.writeFileSync(path.join(xmlRoot, "binario_shortcuts.xml"), shortcutsXml);

    const phoneResRoot = path.join(appRoot, "src", "main", "res");
    for (const [locale, strings] of Object.entries(localizedNativeStrings)) {
      const valuesDirectory = path.join(phoneResRoot, locale === "it" ? "values" : `values-${locale}`);
      fs.mkdirSync(valuesDirectory, { recursive: true });
      const stringsPath = path.join(valuesDirectory, "strings.xml");
      const preserved = fs.existsSync(stringsPath) ? fs.readFileSync(stringsPath, "utf8").match(/<string name="expo_[\s\S]*?<\/string>/g) ?? [] : [];
      fs.writeFileSync(stringsPath, nativeStringsXml(strings).replace("</resources>", `${preserved.map(value => `  ${value}`).join("\n")}\n</resources>`));
    }

    const applicationPath = path.join(appRoot, "src", "main", "java", "im", "binario", "companion", "MainApplication.kt");
    let applicationKt = fs.readFileSync(applicationPath, "utf8");
    if (!applicationKt.includes("BinarioWidgetPackage")) {
      applicationKt = applicationKt.replace("import expo.modules.ApplicationLifecycleDispatcher", `import ${packageName}.BinarioWidgetPackage\n\nimport expo.modules.ApplicationLifecycleDispatcher`);
      applicationKt = applicationKt.replace("PackageList(this).packages.apply {", "PackageList(this).packages.apply {\n              add(BinarioWidgetPackage())");
      fs.writeFileSync(applicationPath, applicationKt);
    }
    if (!applicationKt.includes("BinarioSharedTextReceiverPackage")) {
      applicationKt = applicationKt.replace("import expo.modules.ApplicationLifecycleDispatcher", `import ${packageName}.BinarioSharedTextReceiverPackage\nimport expo.modules.ApplicationLifecycleDispatcher`);
      applicationKt = applicationKt.replace("add(BinarioWidgetPackage())", "add(BinarioWidgetPackage())\n              add(BinarioSharedTextReceiverPackage())");
      fs.writeFileSync(applicationPath, applicationKt);
    }

    const mainActivityPath = path.join(appRoot, "src", "main", "java", "im", "binario", "companion", "MainActivity.kt");
    let mainActivityKt = fs.readFileSync(mainActivityPath, "utf8");
    if (!mainActivityKt.includes("BinarioSharedTextStore.capture")) {
      mainActivityKt = mainActivityKt.replace("import android.os.Build", `import android.content.Intent\nimport ${packageName}.BinarioSharedTextStore\n\nimport android.os.Build`);
      mainActivityKt = mainActivityKt.replace("    super.onCreate(null)", "    super.onCreate(null)\n    if (savedInstanceState == null) BinarioSharedTextStore.capture(this, intent)");
      mainActivityKt = mainActivityKt.replace("\n  /**\n   * Returns the name", "\n  override fun onNewIntent(intent: Intent) {\n    super.onNewIntent(intent)\n    setIntent(intent)\n    BinarioSharedTextStore.capture(this, intent)\n  }\n\n  /**\n   * Returns the name");
    }
    if (!mainActivityKt.includes("BinarioChecklistHandoff.acknowledgeOpened")) {
      mainActivityKt = mainActivityKt.replace("import android.content.Intent\n", `import android.content.Intent\nimport ${packageName}.BinarioChecklistHandoff\n`);
      mainActivityKt = mainActivityKt.replace("    if (savedInstanceState == null) BinarioSharedTextStore.capture(this, intent)", "    if (savedInstanceState == null) BinarioSharedTextStore.capture(this, intent)\n    BinarioChecklistHandoff.acknowledgeOpened(this, intent)");
      mainActivityKt = mainActivityKt.replace("    BinarioSharedTextStore.capture(this, intent)\n  }", "    BinarioSharedTextStore.capture(this, intent)\n    BinarioChecklistHandoff.acknowledgeOpened(this, intent)\n  }");
    }
    fs.writeFileSync(mainActivityPath, mainActivityKt);

    const wearRoot = path.join(next.modRequest.platformProjectRoot, "wear");
    const wearSource = path.join(wearRoot, "src", "main", "java", "im", "binario", "companion", "wear");
    const wearTestSource = path.join(wearRoot, "src", "test", "java", "im", "binario", "companion", "wear");
    fs.mkdirSync(wearSource, { recursive: true });
    fs.mkdirSync(wearTestSource, { recursive: true });
    fs.mkdirSync(path.join(wearRoot, "src", "main", "res", "values"), { recursive: true });
    fs.writeFileSync(path.join(wearRoot, "build.gradle"), `${sharedReleaseSigningGradle}

apply plugin: "com.android.application"
apply plugin: "org.jetbrains.kotlin.android"

android {
${sharedReleaseSigningBlock}
  namespace "im.binario.companion.wear"
  compileSdk rootProject.ext.compileSdkVersion
  defaultConfig {
    // Data Layer peers must share package id and release signing identity.
    applicationId "${phoneApplicationId}"
    minSdkVersion 30
    targetSdkVersion rootProject.ext.targetSdkVersion
    versionCode ${releaseIdentity.wearVersionCode}
    versionName "${releaseIdentity.versionName}"
  }
  buildTypes {
    debug {}
    release {
      // Production signing is supplied only by the release environment.
      if (binarioReleaseSigningConfigured) signingConfig signingConfigs.release
    }
  }
}

dependencies {
  implementation("androidx.wear.tiles:tiles:1.6.2")
  implementation("androidx.wear.protolayout:protolayout:1.4.2")
  implementation("androidx.activity:activity-ktx:1.10.1")
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("com.google.android.gms:play-services-wearable:20.0.1")
  implementation("com.google.guava:guava:33.5.0-android")
  testImplementation("junit:junit:4.13.2")
}
`);
    fs.writeFileSync(path.join(wearRoot, "src", "main", "AndroidManifest.xml"), `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-feature android:name="android.hardware.type.watch" />
  <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
  <application android:label="@string/app_name" android:allowBackup="false" android:usesCleartextTraffic="false" android:theme="@android:style/Theme.DeviceDefault">
    <meta-data android:name="com.google.android.wearable.standalone" android:value="false" />
    <service android:name=".BinarioTileService" android:exported="true" android:label="@string/tile_name" android:permission="com.google.android.wearable.permission.BIND_TILE_PROVIDER">
      <intent-filter><action android:name="androidx.wear.tiles.action.BIND_TILE_PROVIDER" /></intent-filter>
    </service>
    <service android:name=".BinarioWearDataService" android:exported="true">
      <intent-filter>
        <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
        <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
        <data android:scheme="wear" android:host="*" android:pathPrefix="/binario/companion/watch-payload/v1" />
        <data android:scheme="wear" android:host="*" android:pathPrefix="/binario/companion/voice-response/v1" />
        <data android:scheme="wear" android:host="*" android:pathPrefix="/binario/companion/open-checklist-ack/v1" />
      </intent-filter>
    </service>
    <activity android:name=".BinarioVoiceRequestActivity" android:exported="true" android:label="@string/voice_request_label" />
    <activity android:name=".BinarioChecklistRequestActivity" android:exported="true" android:label="@string/checklist_request_label" />
  </application>
</manifest>
`);
    for (const [locale, strings] of Object.entries(localizedNativeStrings)) {
      const valuesDirectory = path.join(wearRoot, "src", "main", "res", locale === "it" ? "values" : `values-${locale}`);
      fs.mkdirSync(valuesDirectory, { recursive: true });
      fs.writeFileSync(path.join(valuesDirectory, "strings.xml"), nativeStringsXml(strings, true));
    }
    fs.writeFileSync(path.join(wearSource, "BinarioWearData.kt"), `package im.binario.companion.wear

import android.content.Context
import android.content.Intent
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.charset.StandardCharsets
import java.util.UUID

data class BinarioWearState(
  val title: String,
  val status: String,
  val canComplicate: Boolean,
  val updatedAtUtcMs: Long?,
  val expiresAtUtcMs: Long?,
  val plannedChecklistRemaining: Int,
)

data class BinarioWearSnapshot(
  val routeLabel: String,
  val notice: String,
  val generatedAtEpochMillis: Long,
  val expiresAtEpochMillis: Long,
  val operationalState: String,
  val freshness: String,
  val direction: String,
  val serviceCount: Int,
)

/**
 * Decoder for the canonical flat Companion → Wear v1 DataMap. Its schema is closed: a
 * malformed or expanded map cannot replace the last valid watch cache. Enum values added
 * by a future producer deliberately degrade to UNKNOWN rather than gaining operational meaning.
 */
object BinarioWearSnapshotV1 {
  const val schemaVersion = 1L
  const val maxSerializedBytes = 8_192
  const val maxTtlMillis = 365L * 24L * 60L * 60L * 1_000L
  const val futureSkewMillis = 60_000L

  private val producerValues = setOf("ANDROID_APP", "COMPANION", "IOS_APP", "UNKNOWN")
  private val dataNatureValues = setOf("PROGRAMMED", "OPERATIONAL", "UNAVAILABLE", "UNKNOWN")
  private val operationalStateValues = setOf("PROGRAMMED", "OPERATIONAL", "UNAVAILABLE", "UNKNOWN")
  private val freshnessValues = setOf("FRESH", "AGING", "STALE", "UNAVAILABLE", "UNKNOWN")
  private val confidenceValues = setOf("OPERATOR_PRIMARY", "INFRASTRUCTURE_PRIMARY", "OPEN_DATA", "LICENSED_AGGREGATE", "STATIC_ONLY", "CACHED", "LOCAL_PLANNED", "UNKNOWN")
  private val directionValues = setOf("OUTBOUND", "RETURN", "UNKNOWN")
  private val baseKeys = listOf(
    "schemaVersion", "generatedAtEpochMillis", "expiresAtEpochMillis", "ttlMillis",
    "provenanceProducer", "provenanceDataNature", "provenanceSourceLabel", "provenanceObservedAtEpochMillis",
    "operationalState", "freshness", "confidence", "routeId", "routeLabel", "direction", "notice", "serviceCount",
  )
  private val serviceFields = listOf("Number", "Operator", "Category", "Destination", "Scheduled", "Expected", "Status", "Platform", "DelayMinutes")

  private fun normalized(value: Any?, maximum: Int, allowEmpty: Boolean = false): String? {
    val string = value as? String ?: return null
    val normal = string.trim().replace(Regex("\\\\s+"), " ")
    return string.takeIf { it == normal && it.length <= maximum && (allowEmpty || it.isNotEmpty()) }
  }

  private fun integer(value: Any?): Long? {
    val number = value as? Number ?: return null
    val result = number.toLong()
    return result.takeIf {
      number.toDouble().isFinite() && number.toDouble() == result.toDouble() &&
        result in -9_007_199_254_740_991L..9_007_199_254_740_991L
    }
  }

  private fun enumOrUnknown(value: Any?, allowed: Set<String>): String? {
    val enum = normalized(value, 64) ?: return null
    return enum.takeIf(allowed::contains) ?: "UNKNOWN"
  }

  private fun expectedKeys(serviceCount: Int): Set<String> = buildSet {
    addAll(baseKeys)
    repeat(serviceCount) { index -> serviceFields.forEach { field -> add("service\${index}$field") } }
  }

  private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { character -> when (character) {
      '"' -> append("\\\\\\"")
      '\\\\' -> append("\\\\\\\\")
      '\\b' -> append("\\\\b")
      '\\u000C' -> append("\\\\f")
      '\\n' -> append("\\\\n")
      '\\r' -> append("\\\\r")
      '\\t' -> append("\\\\t")
      else -> if (character.code < 0x20) append("\\\\u%04x".format(character.code)) else append(character)
    } }
    append('"')
  }

  private fun serializedBytes(values: Map<String, Any?>): Int = values.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
    "\${jsonString(key)}:\${if (value is String) jsonString(value) else value}"
  }.toByteArray(StandardCharsets.UTF_8).size

  fun decode(raw: Map<String, Any?>, now: Long): BinarioWearSnapshot? {
    val serviceCount = integer(raw["serviceCount"])?.takeIf { it in 0L..2L }?.toInt() ?: return null
    if (raw.keys != expectedKeys(serviceCount) || serializedBytes(raw) > maxSerializedBytes) return null
    val schema = integer(raw["schemaVersion"]) ?: return null
    val generated = integer(raw["generatedAtEpochMillis"]) ?: return null
    val expires = integer(raw["expiresAtEpochMillis"]) ?: return null
    val ttl = integer(raw["ttlMillis"]) ?: return null
    if (schema != schemaVersion || ttl !in 1..maxTtlMillis || expires - generated != ttl || expires <= now || generated > now + futureSkewMillis || integer(raw["provenanceObservedAtEpochMillis"]) == null) return null
    val producer = enumOrUnknown(raw["provenanceProducer"], producerValues) ?: return null
    val dataNature = enumOrUnknown(raw["provenanceDataNature"], dataNatureValues) ?: return null
    val operational = enumOrUnknown(raw["operationalState"], operationalStateValues) ?: return null
    val freshness = enumOrUnknown(raw["freshness"], freshnessValues) ?: return null
    if (enumOrUnknown(raw["confidence"], confidenceValues) == null) return null
    val direction = enumOrUnknown(raw["direction"], directionValues) ?: return null
    val source = normalized(raw["provenanceSourceLabel"], 96) ?: return null
    val routeId = normalized(raw["routeId"], 64) ?: return null
    val routeLabel = normalized(raw["routeLabel"], 96) ?: return null
    val notice = normalized(raw["notice"], 180, allowEmpty = true) ?: return null
    // Keep these named checks: provenance remains compulsory even though the Tile only renders a safe summary.
    if (producer.isBlank() || source.isBlank() || routeId.length > 64 || (operational == "UNAVAILABLE" || dataNature == "UNAVAILABLE") && (serviceCount != 0 || dataNature != "UNAVAILABLE")) return null
    repeat(serviceCount) { index ->
      val prefix = "service$index"
      listOf("Number" to 24, "Operator" to 64, "Category" to 64, "Destination" to 96, "Scheduled" to 16, "Expected" to 16, "Status" to 64).forEach { (field, maximum) ->
        normalized(raw["$prefix$field"], maximum) ?: return null
      }
      if (normalized(raw["\${prefix}Platform"], 16, allowEmpty = true) == null || integer(raw["\${prefix}DelayMinutes"])?.let { it !in -720..1_440 } != false) return null
    }
    return BinarioWearSnapshot(routeLabel, notice, generated, expires, operational, freshness, direction, serviceCount)
  }
}

object BinarioWearStore {
  private const val prefsName = "binario_wear_payload_v1"

  fun write(context: Context, snapshot: BinarioWearSnapshot) {
    context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().apply {
      putString("title", snapshot.routeLabel.ifBlank { "Binario" })
      putString("status", snapshot.notice.ifBlank { context.getString(R.string.binario_unavailable) })
      putLong("generatedAtUtcMs", snapshot.generatedAtEpochMillis)
      putLong("expiresAtUtcMs", snapshot.expiresAtEpochMillis)
    }.apply()
    // A new payload must reach the Tile now, not at the next system refresh.
    TileService.getUpdater(context).requestUpdate(BinarioTileService::class.java)
  }

  const val tileFreshnessMillis = 15L * 60L * 1000L

  fun unavailable(context: Context) =
    BinarioWearState("Binario", context.getString(R.string.binario_unavailable), false, null, null, 0)

  fun read(context: Context, now: Long = System.currentTimeMillis()): BinarioWearState {
    val preferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    val generated = if (preferences.contains("generatedAtUtcMs")) preferences.getLong("generatedAtUtcMs", 0L) else null
    val expires = if (preferences.contains("expiresAtUtcMs")) preferences.getLong("expiresAtUtcMs", 0L) else null
    val fresh = generated != null && expires != null && generated <= now + BinarioWearSnapshotV1.futureSkewMillis && expires > now
    return if (fresh) {
      BinarioWearState(
        preferences.getString("title", "Binario") ?: "Binario",
        preferences.getString("status", context.getString(R.string.binario_unavailable)) ?: context.getString(R.string.binario_unavailable),
        true,
        generated,
        expires,
        0,
      )
    } else {
      unavailable(context)
    }
  }
}

class BinarioWearDataService : WearableListenerService() {
  override fun onDataChanged(events: DataEventBuffer) {
    events.forEach { event ->
      if (event.type != com.google.android.gms.wearable.DataEvent.TYPE_CHANGED || event.dataItem.uri.path != "/binario/companion/watch-payload/v1") return@forEach
      val map = DataMapItem.fromDataItem(event.dataItem).dataMap
      val raw: Map<String, Any?> = map.keySet().associate { key: String -> key to map.get(key) }
      BinarioWearSnapshotV1.decode(raw, System.currentTimeMillis())?.let { BinarioWearStore.write(this, it) }
    }
  }

  override fun onMessageReceived(event: MessageEvent) {
    when (event.path) {
      "/binario/companion/voice-response/v1" -> {
        val reply = event.data.toString(Charsets.UTF_8).take(280).ifBlank { getString(R.string.binario_unavailable) }
        sendBroadcast(Intent(BinarioVoiceRequestActivity.replyAction).setPackage(packageName).putExtra(BinarioVoiceRequestActivity.replyExtra, reply))
      }
      BinarioChecklistRequestActivity.acknowledgementPath -> {
        val requestId = event.data.toString(Charsets.UTF_8)
        if (event.data.size <= 36 && runCatching { UUID.fromString(requestId).toString() }.getOrNull() == requestId) {
          sendBroadcast(Intent(BinarioChecklistRequestActivity.acknowledgementAction).setPackage(packageName).putExtra(BinarioChecklistRequestActivity.acknowledgementExtra, requestId))
        }
      }
    }
  }
}
`);
    fs.writeFileSync(
      path.join(wearTestSource, "BinarioWearSnapshotV1Test.kt"),
      fs.readFileSync(path.join(__dirname, "templates", "BinarioWearSnapshotV1Test.kt"), "utf8"),
    );
    fs.writeFileSync(path.join(wearSource, "BinarioTileService.kt"), `package im.binario.companion.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures

class BinarioTileService : TileService() {
  override fun onTileRequest(requestParams: RequestBuilders.TileRequest) = Futures.immediateFuture(
    TileBuilders.Tile.Builder()
      .setResourcesVersion("1")
      .setFreshnessIntervalMillis(BinarioWearStore.tileFreshnessMillis)
      .setTileTimeline(timelineFor(BinarioWearStore.read(this)))
      .build()
  )
  // The renderer switches to the unavailable layout at expiry by itself, even
  // when this process (and any in-process timer) is no longer running.
  private fun timelineFor(state: BinarioWearState): TimelineBuilders.Timeline {
    val timeline = TimelineBuilders.Timeline.Builder()
      .addTimelineEntry(TimelineBuilders.TimelineEntry.fromLayoutElement(textFor(BinarioWearStore.unavailable(this))))
    val expiresAt = state.expiresAtUtcMs
    if (state.canComplicate && expiresAt != null) {
      timeline.addTimelineEntry(
        TimelineBuilders.TimelineEntry.Builder()
          .setLayout(LayoutElementBuilders.Layout.fromLayoutElement(textFor(state)))
          .setValidity(TimelineBuilders.TimeInterval.Builder().setEndMillis(expiresAt).build())
          .build()
      )
    }
    return timeline.build()
  }
  override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) = Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())
  private fun textFor(state: BinarioWearState): LayoutElementBuilders.Column {
    val column = LayoutElementBuilders.Column.Builder()
      .setModifiers(ModifiersBuilders.Modifiers.Builder().setSemantics(
        ModifiersBuilders.Semantics.Builder().setContentDescription(getString(R.string.binario_tile_heading, state.title, state.status)).setHeading(true).build()
      ).build())
      .addContent(LayoutElementBuilders.Text.Builder().setText("${'$'}{state.title}\\n${'$'}{state.status}").build())
      .addContent(LayoutElementBuilders.Text.Builder().setText(getString(R.string.binario_microphone))
        .setModifiers(ModifiersBuilders.Modifiers.Builder().setSemantics(
          ModifiersBuilders.Semantics.Builder().setContentDescription(getString(R.string.voice_request_label)).setStateDescription(TypeBuilders.StringProp.Builder(getString(R.string.binario_voice_state)).build()).build()
        ).setClickable(
          ModifiersBuilders.Clickable.Builder().setId("binario-voice-request").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
              ActionBuilders.AndroidActivity.Builder().setPackageName("${phoneApplicationId}").setClassName(BinarioVoiceRequestActivity::class.java.name).build()
            ).build()
          ).build()
        ).build())
        .build())
    if (state.plannedChecklistRemaining > 0) column.addContent(
      LayoutElementBuilders.Text.Builder().setText(getString(R.string.binario_checklist, state.plannedChecklistRemaining))
        .setModifiers(ModifiersBuilders.Modifiers.Builder().setSemantics(
          ModifiersBuilders.Semantics.Builder().setContentDescription(getString(R.string.checklist_request_label)).setStateDescription(TypeBuilders.StringProp.Builder(getString(R.string.binario_checklist_state, state.plannedChecklistRemaining)).build()).build()
        ).setClickable(
          ModifiersBuilders.Clickable.Builder().setId("binario-open-checklist").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
              ActionBuilders.AndroidActivity.Builder().setPackageName("${phoneApplicationId}").setClassName(BinarioChecklistRequestActivity::class.java.name).build()
            ).build()
          ).build()
        ).build())
        .build()
    )
    return column.build()
  }
}
`);
    fs.writeFileSync(path.join(wearSource, "BinarioChecklistRequestActivity.kt"), `package im.binario.companion.wear

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import java.util.UUID

/** Il watch inoltra solo la richiesta di aprire l’Agenda; le voci checklist restano sul telefono. */
class BinarioChecklistRequestActivity : Activity() {
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var message: TextView
  private val requestId = UUID.randomUUID().toString()
  private var waitingForAcknowledgement = true
  private val acknowledgementReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == acknowledgementAction && waitingForAcknowledgement && intent.getStringExtra(acknowledgementExtra) == requestId) {
        waitingForAcknowledgement = false
        handler.removeCallbacksAndMessages(null)
        message.text = getString(R.string.binario_checklist_opened)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ContextCompat.registerReceiver(this, acknowledgementReceiver, IntentFilter(acknowledgementAction), ContextCompat.RECEIVER_NOT_EXPORTED)
    message = TextView(this).apply { text = getString(R.string.binario_opening_checklist); textSize = 17f; setPadding(24, 24, 24, 24); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    setContentView(LinearLayout(this).apply { gravity = Gravity.CENTER; addView(message) })
    Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
      val node = nodes.firstOrNull()
      if (node == null) { failClosed(getString(R.string.binario_phone_unreachable)); return@addOnSuccessListener }
      Wearable.getMessageClient(this).sendMessage(node.id, requestPath, requestId.toByteArray(Charsets.UTF_8))
        .addOnSuccessListener { if (waitingForAcknowledgement) message.text = getString(R.string.binario_opening_checklist) }
        .addOnFailureListener { failClosed(getString(R.string.binario_cannot_open_phone)) }
    }.addOnFailureListener { failClosed(getString(R.string.binario_phone_unreachable)) }
    handler.postDelayed({ failClosed(getString(R.string.binario_cannot_open_phone)) }, acknowledgementTimeoutMillis)
  }

  private fun failClosed(copy: String) {
    if (!waitingForAcknowledgement) return
    waitingForAcknowledgement = false
    handler.removeCallbacksAndMessages(null)
    message.text = copy
  }

  override fun onDestroy() {
    unregisterReceiver(acknowledgementReceiver)
    handler.removeCallbacksAndMessages(null)
    super.onDestroy()
  }

  companion object {
    const val requestPath = "/binario/companion/open-checklist/v1"
    const val acknowledgementPath = "/binario/companion/open-checklist-ack/v1"
    const val acknowledgementAction = "im.binario.companion.wear.CHECKLIST_OPENED"
    const val acknowledgementExtra = "binario_checklist_request_id"
    const val acknowledgementTimeoutMillis = 30_000L
  }
}
`);
    fs.writeFileSync(path.join(wearSource, "BinarioVoiceRequestActivity.kt"), `package im.binario.companion.wear

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import java.util.Locale

/** Input vocale fornito dal sistema Wear OS: Binario non registra né persiste l'audio o il transcript. */
class BinarioVoiceRequestActivity : Activity() {
  private var tts: TextToSpeech? = null
  private val handler = Handler(Looper.getMainLooper())
  private val voicePreferences by lazy { getSharedPreferences("binario_wear_voice_preferences_v1", Context.MODE_PRIVATE) }
  private var waitingForRealtime = false
  private fun touchTargetPx() = (48 * resources.displayMetrics.density).toInt()
  private fun resolvedLocale(): Locale {
    val configured = resources.configuration.locales[0]
    return configured.takeIf { it.language.lowercase(Locale.ROOT) in setOf("it", "en", "de", "fr", "es") } ?: Locale.ITALIAN
  }
  private val replyReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == replyAction && waitingForRealtime) {
        waitingForRealtime = false
        handler.removeCallbacksAndMessages(null)
        showAnswer(intent.getStringExtra(replyExtra) ?: getString(R.string.binario_unavailable))
      }
    }
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = resolvedLocale() }
    val filter = IntentFilter(replyAction)
    ContextCompat.registerReceiver(this, replyReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    launchSystemVoiceInput()
  }
  private fun launchSystemVoiceInput() {
    try {
      startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, resolvedLocale().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.binario_voice_prompt))
      }, companionRequest)
    } catch (_: Exception) { showAnswer(getString(R.string.binario_dictation_unavailable)) }
  }
  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    val transcript = if (requestCode == companionRequest && resultCode == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty() else ""
    if (transcript.isEmpty()) showLocalActions() else if (asksRealtime(transcript)) requestFreshRealtimeAnswer(transcript) else showAnswer(answerFromFreshLocalState(transcript))
  }
  private fun answerFromFreshLocalState(transcript: String): String {
    val state = BinarioWearStore.read(this)
    val locale = resolvedLocale()
    val question = transcript.lowercase(locale).trim().split(" ").filter { it.isNotBlank() }.joinToString(" ")
    val asksSavedJourney = getString(R.string.binario_saved_journey_keywords).split("|").any { question.contains(it) }
    val savedRoute = state.title.split("→").map { it.trim().lowercase(locale) }
    val asksCurrentSavedRoute = savedRoute.size == 2 && savedRoute.all { it.length >= 2 && question.contains(it) }
    return if ((asksSavedJourney || asksCurrentSavedRoute) && state.canComplicate) "${'$'}{state.title}. ${'$'}{state.status}" else getString(R.string.binario_unavailable)
  }
  private fun asksRealtime(transcript: String) = getString(R.string.binario_realtime_keywords).split("|").any { transcript.lowercase(resolvedLocale()).contains(it) }
  private fun requestFreshRealtimeAnswer(transcript: String) {
    val request = transcript.take(240).toByteArray(Charsets.UTF_8)
    if (request.isEmpty()) { showAnswer(getString(R.string.binario_unavailable)); return }
    waitingForRealtime = true
    showWaiting()
    Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
      val node = nodes.firstOrNull()
      if (node == null) { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); showAnswer(getString(R.string.binario_unavailable)); return@addOnSuccessListener }
      Wearable.getMessageClient(this).sendMessage(node.id, "/binario/companion/voice-request/v1", request).addOnFailureListener { if (waitingForRealtime) { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); showAnswer(getString(R.string.binario_unavailable)) } }
    }
    handler.postDelayed({ if (waitingForRealtime) { waitingForRealtime = false; showAnswer(getString(R.string.binario_unavailable)) } }, 12_000)
  }
  private fun showWaiting() {
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
      addView(TextView(this@BinarioVoiceRequestActivity).apply { text = getString(R.string.binario_checking); textSize = 18f; gravity = Gravity.CENTER; contentDescription = getString(R.string.binario_checking_state); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(R.string.binario_cancel); contentDescription = getString(R.string.binario_cancel_checking); setOnClickListener { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); finish() } })
    })
  }
  private fun showLocalActions() {
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
      addView(TextView(this@BinarioVoiceRequestActivity).apply { text = getString(R.string.binario_no_voice); textSize = 17f; gravity = Gravity.CENTER; contentDescription = getString(R.string.binario_no_voice_state); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(R.string.binario_show_next); contentDescription = getString(R.string.binario_show_next_state); setOnClickListener { showAnswer(answerFromFreshLocalState(getString(R.string.binario_next_query))) } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(R.string.binario_retry); contentDescription = getString(R.string.binario_retry_state); setOnClickListener { launchSystemVoiceInput() } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(R.string.binario_close); contentDescription = getString(R.string.binario_close_state); setOnClickListener { finish() } })
    })
  }
  private fun showAnswer(answer: String) {
    val speakAnswers = voicePreferences.getBoolean("speak_answers", false)
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
      addView(TextView(this@BinarioVoiceRequestActivity).apply { text = answer; textSize = 18f; gravity = Gravity.CENTER; contentDescription = getString(R.string.binario_answer_heading, answer); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(R.string.binario_listen); contentDescription = getString(R.string.binario_listen); setOnClickListener { tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "binario-voice-answer") } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(if (speakAnswers) R.string.binario_disable_auto_read else R.string.binario_enable_auto_read); contentDescription = text; setOnClickListener { voicePreferences.edit().putBoolean("speak_answers", !speakAnswers).apply(); showAnswer(answer) } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(R.string.binario_retry); contentDescription = getString(R.string.binario_retry_state); setOnClickListener { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); launchSystemVoiceInput() } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(R.string.binario_close); contentDescription = getString(R.string.binario_close_state); setOnClickListener { finish() } })
    })
    if (speakAnswers) tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "binario-voice-answer")
  }
  override fun onDestroy() { unregisterReceiver(replyReceiver); handler.removeCallbacksAndMessages(null); tts?.stop(); tts?.shutdown(); super.onDestroy() }
  companion object { const val companionRequest = 71; const val replyAction = "im.binario.companion.wear.VOICE_REPLY"; const val replyExtra = "binario_voice_reply" }
}
`);
    const settingsPath = path.join(next.modRequest.platformProjectRoot, "settings.gradle");
    let settings = fs.readFileSync(settingsPath, "utf8");
    if (!settings.includes("include ':wear'")) { settings += "\ninclude ':wear'\n"; fs.writeFileSync(settingsPath, settings); }
    const gradlePropertiesPath = path.join(next.modRequest.platformProjectRoot, "gradle.properties");
    let gradleProperties = fs.readFileSync(gradlePropertiesPath, "utf8");
    gradleProperties = setGradleProperty(gradleProperties, "org.gradle.parallel", "false");
    gradleProperties = setGradleProperty(gradleProperties, "org.gradle.workers.max", "1");
    fs.writeFileSync(gradlePropertiesPath, gradleProperties);
    const wrapperPropertiesPath = path.join(next.modRequest.platformProjectRoot, "gradle", "wrapper", "gradle-wrapper.properties");
    let wrapperProperties = fs.readFileSync(wrapperPropertiesPath, "utf8");
    wrapperProperties = setGradleProperty(
      wrapperProperties,
      "distributionSha256Sum",
      "bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"
    );
    fs.writeFileSync(wrapperPropertiesPath, wrapperProperties);
    return next;
  }]);
}

module.exports = withBinarioAndroidWidget;
module.exports.__test = { localizedNativeStrings, nativeLocales, nativeStringKeys, nativeStringsXml };
