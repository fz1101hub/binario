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
import android.text.format.DateUtils
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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

internal object BinarioWidgetPresentation {
  /** Journeys are Italian trains: the app saves and shows their times as Italian wall-clock time. */
  const val TRAVEL_TIME_ZONE = "Europe/Rome"

  /** True when both instants fall on the same calendar day in Italy, whatever the phone's time zone. */
  fun sameTravelDay(first: Long, second: Long): Boolean {
    val zone = java.util.TimeZone.getTimeZone(TRAVEL_TIME_ZONE)
    val firstDay = java.util.Calendar.getInstance(zone).apply { timeInMillis = first }
    val secondDay = java.util.Calendar.getInstance(zone).apply { timeInMillis = second }
    return firstDay.get(java.util.Calendar.YEAR) == secondDay.get(java.util.Calendar.YEAR) &&
      firstDay.get(java.util.Calendar.DAY_OF_YEAR) == secondDay.get(java.util.Calendar.DAY_OF_YEAR)
  }

  /**
   * DateUtils flags of the departure label: the time alone on the same Italian day, otherwise also the
   * abbreviated weekday and date.
   */
  fun departureFlags(scheduledAt: Long, now: Long): Int =
    if (sameTravelDay(scheduledAt, now)) DateUtils.FORMAT_SHOW_TIME
    else DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY

  /**
   * Delay until the widget's state next changes: the departure, after which the journey is no
   * longer shown, or the payload expiry, whichever comes first. Null when neither is ahead.
   */
  fun nextTransitionDelayMillis(plannedJourneyAtUtcMs: Long?, expiresAtUtcMs: Long?, now: Long = System.currentTimeMillis()): Long? =
    listOfNotNull(plannedJourneyAtUtcMs, expiresAtUtcMs).filter { it > now }.minOrNull()?.let { it - now }
}

class BinarioStatusWidget : GlanceAppWidget() {
  /**
   * The departure as an Italian clock time (with the date when it is not today in Italy), the time the
   * user typed in the app, whatever the phone's time zone. A widget is redrawn only by the app or by
   * WorkManager, so a relative "in 10 min" would stay frozen between redraws.
   */
  private fun departureLabel(context: Context, scheduledAt: Long, now: Long = System.currentTimeMillis()): String {
    if (scheduledAt <= now) return context.getString(R.string.binario_unavailable)
    val flags = BinarioWidgetPresentation.departureFlags(scheduledAt, now)
    val formatter = java.util.Formatter(StringBuilder(), java.util.Locale.getDefault())
    return DateUtils.formatDateRange(context, formatter, scheduledAt, scheduledAt, flags, BinarioWidgetPresentation.TRAVEL_TIME_ZONE).toString()
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
        Text(if (state.plannedJourneyAtUtcMs != null) departureLabel(context, state.plannedJourneyAtUtcMs) else state.subtitle, style = TextStyle(color = ColorProvider(Color(0xFFD5E4F1))))
        // One format string per language keeps the separator spacing: Android trims a trailing space of an unquoted resource.
        if (state.plannedJourneyTitle != null) Text(state.plannedReminderMinutes?.let { context.getString(R.string.binario_reminder_and_status, it, context.getString(R.string.binario_scheduled_not_realtime)) } ?: context.getString(R.string.binario_scheduled_not_realtime), style = TextStyle(color = ColorProvider(Color(0xFFD5E4F1))))
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
    private const val transitionWorkName = "binario-widget-transition-v1"
    private const val refreshMinutes = 15L

    /** One redraw at the next state change, so the departed journey does not wait for the periodic refresh. */
    fun scheduleTransition(context: Context, plannedJourneyAtUtcMs: Long?, expiresAtUtcMs: Long?) {
      val workManager = WorkManager.getInstance(context.applicationContext)
      val delay = BinarioWidgetPresentation.nextTransitionDelayMillis(plannedJourneyAtUtcMs, expiresAtUtcMs)
      if (delay == null) {
        workManager.cancelUniqueWork(transitionWorkName)
        return
      }
      val request = OneTimeWorkRequestBuilder<BinarioWidgetRefreshWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
      workManager.enqueueUniqueWork(transitionWorkName, ExistingWorkPolicy.REPLACE, request)
    }

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
      WorkManager.getInstance(context.applicationContext).cancelUniqueWork(transitionWorkName)
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
    BinarioWidgetRefreshWorker.scheduleTransition(context, plannedAt, expiresAt)
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

/**
 * Android 12+ ignores allowBackup="false" for device-to-device transfer: without these rules a phone
 * migration copies the AsyncStorage database (journeys, reminders, templates) and the widget and Wear
 * preferences. Same rules as the main Binario app, for the phone and the Wear module.
 */
const dataExtractionRulesXml = `<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup disableIfNoEncryptionCapabilities="true">
        <exclude domain="sharedpref" path="." />
        <exclude domain="database" path="." />
        <exclude domain="file" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="." />
        <exclude domain="database" path="." />
        <exclude domain="file" path="." />
    </device-transfer>
</data-extraction-rules>
`;
const dataExtractionRulesResource = "@xml/binario_data_extraction_rules";

/** Kept in the config-plugin template so Android prebuilds are deterministic and idempotent. */
// Resource qualifiers: legacy "in" is Android's Indonesian folder; b+zh+Hans serves Simplified
// Chinese only (zh-Hant devices fall back to Italian, as the JS UI does) and, having no region,
// survives the language-only "zh" entry that expo-localization puts in resourceConfigurations.
const nativeLocales = ["it", "en", "de", "fr", "es", "ar", "hi", "in", "ja", "ko", "my", "pt", "ru", "th", "tr", "vi", "b+zh+Hans"];
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
    "reminder_and_status": "Promemoria %1$d min · %2$s",
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
    "reminder_and_status": "Reminder %1$d min · %2$s",
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
    "reminder_and_status": "Erinnerung %1$d Min. · %2$s",
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
    "reminder_and_status": "Rappel %1$d min · %2$s",
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
    "reminder_and_status": "Recordatorio %1$d min · %2$s",
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
  },
  "ar": {
    "app_name": "Binario Companion",
    "widget_label": "حالة Binario",
    "widget_description": "حالة القطارات الموثّقة في Binario",
    "shortcut_plan_short": "خطِّط",
    "shortcut_plan_long": "خطِّط لرحلة محلية",
    "shortcut_alerts_short": "التذكيرات",
    "shortcut_alerts_long": "افتح التذكيرات المحلية",
    "share_text_label": "استيراد نص إلى Binario",
    "tile_name": "حالة Binario",
    "voice_request_label": "تحدّث إلى Binario",
    "checklist_request_label": "افتح قائمة التحقق على الهاتف",
    "unavailable": "البيانات في الوقت الفعلي غير متاحة.",
    "next_journey": "الرحلة التالية · مجدولة",
    "open_binario": "افتح Binario وأملِ",
    "reminder_and_status": "تذكير %1$d د · %2$s",
    "scheduled_not_realtime": "موعد مجدول، وليس في الوقت الفعلي",
    "countdown_minutes": "بعد %1$d د",
    "countdown_hours": "بعد %1$d س",
    "countdown_hours_minutes": "بعد %1$d س %2$d د",
    "microphone": "الميكروفون · تحدّث إلى Binario",
    "voice_prompt": "اسأل Binario",
    "dictation_unavailable": "إملاء النظام غير متاح.",
    "checking": "جارٍ التحقق…",
    "cancel": "إلغاء",
    "no_voice": "لم يُلتقط أي طلب صوتي.",
    "show_next": "اعرض الرحلة التالية",
    "retry": "أعد المحاولة",
    "close": "إغلاق",
    "listen": "استمع إلى الإجابة",
    "enable_auto_read": "فعّل القراءة التلقائية",
    "disable_auto_read": "أوقف القراءة التلقائية",
    "opening_checklist": "جارٍ فتح قائمة التحقق على الهاتف…",
    "phone_unreachable": "تعذّر الوصول إلى الهاتف.",
    "checklist_opened": "فُتحت قائمة التحقق على الهاتف.",
    "checklist_channel_name": "طلبات الساعة الذكية",
    "checklist_notification_title": "افتح قائمة تحقق Binario",
    "checklist_notification_text": "اضغط لفتح قائمة التحقق على الهاتف.",
    "cannot_open_phone": "تعذّر فتح الهاتف الآن.",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "يفتح الطلب الصوتي على الساعة الذكية",
    "checklist": "قائمة التحقق · %1$d عنصر على الهاتف",
    "checklist_state": "%1$d عنصر للتجهيز",
    "checking_state": "جارٍ التحقق. زر الإلغاء متاح.",
    "cancel_checking": "ألغِ التحقق",
    "no_voice_state": "لم يُلتقط أي طلب صوتي. يمكنك عرض الرحلة التالية أو إعادة المحاولة.",
    "show_next_state": "اعرض الرحلة المخطّطة التالية",
    "retry_state": "أعد محاولة الطلب الصوتي",
    "close_state": "أغلق طلب Binario",
    "answer_heading": "إجابة Binario. %1$s",
    "next_query": "الرحلة التالية",
    "saved_journey_keywords": "التالية|التالي|رحلتي|مساري|متى يغادر",
    "realtime_keywords": "تأخير|تأخر|رصيف|إلغاء|ملغى|تنبيه|الوقت الفعلي|مباشر",
    "companion_source": "الجدول المحلي في Companion",
    "no_active_route": "لا توجد رحلة نشطة",
    "companion_unavailable_notice": "البيانات غير متاحة في Companion: افتح التطبيق الرئيسي لتحديث الجدول المحلي."
  },
  "hi": {
    "app_name": "Binario Companion",
    "widget_label": "Binario स्थिति",
    "widget_description": "Binario की सत्यापित रेलवे स्थिति",
    "shortcut_plan_short": "योजना",
    "shortcut_plan_long": "स्थानीय यात्रा की योजना बनाएँ",
    "shortcut_alerts_short": "रिमाइंडर",
    "shortcut_alerts_long": "स्थानीय रिमाइंडर खोलें",
    "share_text_label": "Binario में टेक्स्ट आयात करें",
    "tile_name": "Binario स्थिति",
    "voice_request_label": "Binario से बात करें",
    "checklist_request_label": "फ़ोन पर चेकलिस्ट खोलें",
    "unavailable": "रीयल-टाइम डेटा उपलब्ध नहीं।",
    "next_journey": "अगली यात्रा · निर्धारित",
    "open_binario": "Binario खोलें और बोलें",
    "reminder_and_status": "रिमाइंडर %1$d मि · %2$s",
    "scheduled_not_realtime": "निर्धारित समय, रीयल-टाइम नहीं",
    "countdown_minutes": "%1$d मि में",
    "countdown_hours": "%1$d घं में",
    "countdown_hours_minutes": "%1$d घं %2$d मि में",
    "microphone": "माइक्रोफ़ोन · Binario से बात करें",
    "voice_prompt": "Binario से पूछें",
    "dictation_unavailable": "सिस्टम डिक्टेशन उपलब्ध नहीं।",
    "checking": "जाँच जारी है…",
    "cancel": "रद्द करें",
    "no_voice": "कोई वॉइस अनुरोध नहीं मिला।",
    "show_next": "अगली यात्रा दिखाएँ",
    "retry": "फिर से कोशिश करें",
    "close": "बंद करें",
    "listen": "जवाब सुनें",
    "enable_auto_read": "स्वचालित पढ़ना चालू करें",
    "disable_auto_read": "स्वचालित पढ़ना बंद करें",
    "opening_checklist": "फ़ोन पर चेकलिस्ट खुल रही है…",
    "phone_unreachable": "फ़ोन तक पहुँच नहीं हो पा रही।",
    "checklist_opened": "फ़ोन पर चेकलिस्ट खुल गई।",
    "checklist_channel_name": "स्मार्टवॉच अनुरोध",
    "checklist_notification_title": "Binario चेकलिस्ट खोलें",
    "checklist_notification_text": "फ़ोन पर चेकलिस्ट खोलने के लिए टैप करें।",
    "cannot_open_phone": "अभी फ़ोन नहीं खोला जा सकता।",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "स्मार्टवॉच पर वॉइस अनुरोध खोलता है",
    "checklist": "चेकलिस्ट · फ़ोन पर %1$d आइटम",
    "checklist_state": "तैयार करने के लिए %1$d आइटम",
    "checking_state": "जाँच जारी है। रद्द करें बटन उपलब्ध है।",
    "cancel_checking": "जाँच रद्द करें",
    "no_voice_state": "कोई वॉइस अनुरोध नहीं मिला। आप अगली यात्रा दिखा सकते हैं या फिर से कोशिश कर सकते हैं।",
    "show_next_state": "अगली योजनाबद्ध यात्रा दिखाएँ",
    "retry_state": "वॉइस अनुरोध फिर से आज़माएँ",
    "close_state": "Binario अनुरोध बंद करें",
    "answer_heading": "Binario का जवाब। %1$s",
    "next_query": "अगली यात्रा",
    "saved_journey_keywords": "अगली|अगला|मेरी यात्रा|मेरा रूट|कब निकलेगी|कब चलेगी",
    "realtime_keywords": "देरी|लेट|प्लेटफ़ॉर्म|प्लेटफार्म|रद्द|अलर्ट|रीयल-टाइम|लाइव",
    "companion_source": "Companion का स्थानीय शेड्यूल",
    "no_active_route": "कोई सक्रिय यात्रा नहीं",
    "companion_unavailable_notice": "Companion में डेटा उपलब्ध नहीं: स्थानीय शेड्यूल अपडेट करने के लिए मुख्य ऐप खोलें।"
  },
  "in": {
    "app_name": "Binario Companion",
    "widget_label": "Status Binario",
    "widget_description": "Status kereta Binario yang terverifikasi",
    "shortcut_plan_short": "Rencanakan",
    "shortcut_plan_long": "Rencanakan perjalanan lokal",
    "shortcut_alerts_short": "Pengingat",
    "shortcut_alerts_long": "Buka pengingat lokal",
    "share_text_label": "Impor teks ke Binario",
    "tile_name": "Status Binario",
    "voice_request_label": "Bicara dengan Binario",
    "checklist_request_label": "Buka daftar periksa di ponsel",
    "unavailable": "Data waktu nyata tidak tersedia.",
    "next_journey": "PERJALANAN BERIKUTNYA · TERJADWAL",
    "open_binario": "Buka Binario dan dikte",
    "reminder_and_status": "Pengingat %1$d mnt · %2$s",
    "scheduled_not_realtime": "Jadwal terencana, bukan waktu nyata",
    "countdown_minutes": "Dalam %1$d mnt",
    "countdown_hours": "Dalam %1$d jam",
    "countdown_hours_minutes": "Dalam %1$d jam %2$d mnt",
    "microphone": "Mikrofon · Bicara dengan Binario",
    "voice_prompt": "Tanya Binario",
    "dictation_unavailable": "Dikte sistem tidak tersedia.",
    "checking": "Memeriksa…",
    "cancel": "Batal",
    "no_voice": "Tidak ada permintaan suara yang tertangkap.",
    "show_next": "Tampilkan perjalanan berikutnya",
    "retry": "Coba lagi",
    "close": "Tutup",
    "listen": "Dengarkan jawaban",
    "enable_auto_read": "Aktifkan pembacaan otomatis",
    "disable_auto_read": "Nonaktifkan pembacaan otomatis",
    "opening_checklist": "Membuka daftar periksa di ponsel…",
    "phone_unreachable": "Ponsel tidak dapat dijangkau.",
    "checklist_opened": "Daftar periksa dibuka di ponsel.",
    "checklist_channel_name": "Permintaan dari jam tangan pintar",
    "checklist_notification_title": "Buka daftar periksa Binario",
    "checklist_notification_text": "Ketuk untuk membuka daftar periksa di ponsel Anda.",
    "cannot_open_phone": "Ponsel tidak dapat dibuka sekarang.",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "Membuka permintaan suara di jam tangan pintar",
    "checklist": "Daftar periksa · %1$d item di ponsel",
    "checklist_state": "%1$d item perlu disiapkan",
    "checking_state": "Sedang memeriksa. Tombol Batal tersedia.",
    "cancel_checking": "Batalkan pemeriksaan",
    "no_voice_state": "Tidak ada permintaan suara yang tertangkap. Anda dapat menampilkan perjalanan berikutnya atau mencoba lagi.",
    "show_next_state": "Tampilkan perjalanan terencana berikutnya",
    "retry_state": "Coba lagi permintaan suara",
    "close_state": "Tutup permintaan Binario",
    "answer_heading": "Jawaban Binario. %1$s",
    "next_query": "perjalanan berikutnya",
    "saved_journey_keywords": "berikutnya|selanjutnya|perjalanan saya|rute saya|jam berapa berangkat",
    "realtime_keywords": "terlambat|keterlambatan|peron|batal|pembatalan|peringatan|waktu nyata|realtime",
    "companion_source": "Jadwal lokal Companion",
    "no_active_route": "Tidak ada perjalanan aktif",
    "companion_unavailable_notice": "Data tidak tersedia di Companion: buka aplikasi utama untuk memperbarui jadwal lokal."
  },
  "ja": {
    "app_name": "Binario Companion",
    "widget_label": "Binario の状況",
    "widget_description": "Binario の確認済み鉄道状況",
    "shortcut_plan_short": "計画",
    "shortcut_plan_long": "端末内の移動を計画",
    "shortcut_alerts_short": "リマインダー",
    "shortcut_alerts_long": "端末内のリマインダーを開く",
    "share_text_label": "Binario にテキストを読み込む",
    "tile_name": "Binario の状況",
    "voice_request_label": "Binario に話しかける",
    "checklist_request_label": "スマートフォンでチェックリストを開く",
    "unavailable": "リアルタイムデータは利用できません。",
    "next_journey": "次の移動 · 計画",
    "open_binario": "Binario を開いて音声入力",
    "reminder_and_status": "リマインダー %1$d 分前 · %2$s",
    "scheduled_not_realtime": "計画時刻（リアルタイムではありません）",
    "countdown_minutes": "あと %1$d 分",
    "countdown_hours": "あと %1$d 時間",
    "countdown_hours_minutes": "あと %1$d 時間 %2$d 分",
    "microphone": "マイク · Binario に話しかける",
    "voice_prompt": "Binario に聞く",
    "dictation_unavailable": "システムの音声入力を利用できません。",
    "checking": "確認中…",
    "cancel": "キャンセル",
    "no_voice": "音声リクエストを取得できませんでした。",
    "show_next": "次の移動を表示",
    "retry": "もう一度",
    "close": "閉じる",
    "listen": "答えを聞く",
    "enable_auto_read": "自動読み上げをオン",
    "disable_auto_read": "自動読み上げをオフ",
    "opening_checklist": "スマートフォンでチェックリストを開いています…",
    "phone_unreachable": "スマートフォンに接続できません。",
    "checklist_opened": "スマートフォンでチェックリストを開きました。",
    "checklist_channel_name": "スマートウォッチからのリクエスト",
    "checklist_notification_title": "Binario のチェックリストを開く",
    "checklist_notification_text": "タップしてスマートフォンでチェックリストを開きます。",
    "cannot_open_phone": "現在スマートフォンを開けません。",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "スマートウォッチで音声リクエストを開きます",
    "checklist": "チェックリスト · スマートフォンに %1$d 項目",
    "checklist_state": "準備する項目 %1$d 件",
    "checking_state": "確認中です。キャンセルボタンを使えます。",
    "cancel_checking": "確認をキャンセル",
    "no_voice_state": "音声リクエストを取得できませんでした。次の移動を表示するか、もう一度お試しください。",
    "show_next_state": "次に計画した移動を表示",
    "retry_state": "音声リクエストをやり直す",
    "close_state": "Binario のリクエストを閉じる",
    "answer_heading": "Binario の回答。%1$s",
    "next_query": "次の移動",
    "saved_journey_keywords": "次|私の移動|マイルート|何時に出る|何時発",
    "realtime_keywords": "遅延|遅れ|番線|運休|キャンセル|お知らせ|リアルタイム",
    "companion_source": "Companion の端末内計画",
    "no_active_route": "有効な移動はありません",
    "companion_unavailable_notice": "Companion ではデータを利用できません。端末内の計画を更新するにはメインアプリを開いてください。"
  },
  "ko": {
    "app_name": "Binario Companion",
    "widget_label": "Binario 상태",
    "widget_description": "Binario의 확인된 철도 상태",
    "shortcut_plan_short": "계획",
    "shortcut_plan_long": "로컬 여정 계획",
    "shortcut_alerts_short": "리마인더",
    "shortcut_alerts_long": "로컬 리마인더 열기",
    "share_text_label": "Binario로 텍스트 가져오기",
    "tile_name": "Binario 상태",
    "voice_request_label": "Binario에게 말하기",
    "checklist_request_label": "휴대전화에서 체크리스트 열기",
    "unavailable": "실시간 데이터를 사용할 수 없습니다.",
    "next_journey": "다음 여정 · 계획됨",
    "open_binario": "Binario를 열고 받아쓰기",
    "reminder_and_status": "리마인더 %1$d분 전 · %2$s",
    "scheduled_not_realtime": "계획 시각, 실시간 아님",
    "countdown_minutes": "%1$d분 후",
    "countdown_hours": "%1$d시간 후",
    "countdown_hours_minutes": "%1$d시간 %2$d분 후",
    "microphone": "마이크 · Binario에게 말하기",
    "voice_prompt": "Binario에게 묻기",
    "dictation_unavailable": "시스템 받아쓰기를 사용할 수 없습니다.",
    "checking": "확인 중…",
    "cancel": "취소",
    "no_voice": "음성 요청을 받지 못했습니다.",
    "show_next": "다음 여정 보기",
    "retry": "다시 시도",
    "close": "닫기",
    "listen": "답변 듣기",
    "enable_auto_read": "자동 읽기 켜기",
    "disable_auto_read": "자동 읽기 끄기",
    "opening_checklist": "휴대전화에서 체크리스트를 여는 중…",
    "phone_unreachable": "휴대전화에 연결할 수 없습니다.",
    "checklist_opened": "휴대전화에서 체크리스트를 열었습니다.",
    "checklist_channel_name": "스마트워치 요청",
    "checklist_notification_title": "Binario 체크리스트 열기",
    "checklist_notification_text": "탭하여 휴대전화에서 체크리스트를 여세요.",
    "cannot_open_phone": "지금은 휴대전화를 열 수 없습니다.",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "스마트워치에서 음성 요청을 엽니다",
    "checklist": "체크리스트 · 휴대전화에 %1$d개 항목",
    "checklist_state": "준비할 항목 %1$d개",
    "checking_state": "확인 중입니다. 취소 버튼을 사용할 수 있습니다.",
    "cancel_checking": "확인 취소",
    "no_voice_state": "음성 요청을 받지 못했습니다. 다음 여정을 보거나 다시 시도할 수 있습니다.",
    "show_next_state": "다음 계획 여정 보기",
    "retry_state": "음성 요청 다시 시도",
    "close_state": "Binario 요청 닫기",
    "answer_heading": "Binario 답변. %1$s",
    "next_query": "다음 여정",
    "saved_journey_keywords": "다음|내 여정|내 경로|몇 시에 출발",
    "realtime_keywords": "지연|늦|승강장|취소|운휴|알림|실시간",
    "companion_source": "Companion 로컬 계획",
    "no_active_route": "활성 여정 없음",
    "companion_unavailable_notice": "Companion에서 데이터를 사용할 수 없습니다. 로컬 계획을 업데이트하려면 기본 앱을 여세요."
  },
  "my": {
    "app_name": "Binario Companion",
    "widget_label": "Binario အခြေအနေ",
    "widget_description": "Binario ၏ အတည်ပြုထားသော ရထား အခြေအနေ",
    "shortcut_plan_short": "စီစဉ်ရန်",
    "shortcut_plan_long": "စက်တွင်း ခရီးစဉ် စီစဉ်ရန်",
    "shortcut_alerts_short": "သတိပေးချက်များ",
    "shortcut_alerts_long": "စက်တွင်း သတိပေးချက်များ ဖွင့်ရန်",
    "share_text_label": "Binario သို့ စာသား တင်သွင်းရန်",
    "tile_name": "Binario အခြေအနေ",
    "voice_request_label": "Binario နှင့် စကားပြောရန်",
    "checklist_request_label": "ဖုန်းပေါ်တွင် စစ်ဆေးစာရင်း ဖွင့်ရန်",
    "unavailable": "အချိန်နှင့်တပြေးညီ ဒေတာ မရရှိနိုင်ပါ။",
    "next_journey": "နောက်ခရီးစဉ် · စီစဉ်ထားသည်",
    "open_binario": "Binario ကို ဖွင့်ပြီး အသံဖြင့်ရေးပါ",
    "reminder_and_status": "သတိပေးချက် %1$d မိနစ် · %2$s",
    "scheduled_not_realtime": "စီစဉ်ထားသော အချိန်၊ အချိန်နှင့်တပြေးညီ မဟုတ်ပါ",
    "countdown_minutes": "%1$d မိနစ်အတွင်း",
    "countdown_hours": "%1$d နာရီအတွင်း",
    "countdown_hours_minutes": "%1$d နာရီ %2$d မိနစ်အတွင်း",
    "microphone": "မိုက်ခရိုဖုန်း · Binario နှင့် စကားပြောရန်",
    "voice_prompt": "Binario ကို မေးပါ",
    "dictation_unavailable": "စနစ် အသံဖြင့်ရေးခြင်း မရရှိနိုင်ပါ။",
    "checking": "စစ်ဆေးနေသည်…",
    "cancel": "မလုပ်တော့ပါ",
    "no_voice": "အသံ တောင်းဆိုချက် မရရှိပါ။",
    "show_next": "နောက်ခရီးစဉ် ပြရန်",
    "retry": "ထပ်စမ်းရန်",
    "close": "ပိတ်ရန်",
    "listen": "အဖြေ နားထောင်ရန်",
    "enable_auto_read": "အလိုအလျောက် ဖတ်ခြင်း ဖွင့်ရန်",
    "disable_auto_read": "အလိုအလျောက် ဖတ်ခြင်း ပိတ်ရန်",
    "opening_checklist": "ဖုန်းပေါ်တွင် စစ်ဆေးစာရင်း ဖွင့်နေသည်…",
    "phone_unreachable": "ဖုန်းကို ဆက်သွယ်၍ မရပါ။",
    "checklist_opened": "ဖုန်းပေါ်တွင် စစ်ဆေးစာရင်း ဖွင့်ပြီး။",
    "checklist_channel_name": "စမတ်နာရီ တောင်းဆိုချက်များ",
    "checklist_notification_title": "Binario စစ်ဆေးစာရင်း ဖွင့်ရန်",
    "checklist_notification_text": "ဖုန်းပေါ်တွင် စစ်ဆေးစာရင်း ဖွင့်ရန် နှိပ်ပါ။",
    "cannot_open_phone": "ယခု ဖုန်းကို ဖွင့်၍ မရပါ။",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "စမတ်နာရီပေါ်တွင် အသံ တောင်းဆိုချက်ကို ဖွင့်သည်",
    "checklist": "စစ်ဆေးစာရင်း · ဖုန်းပေါ်တွင် %1$d ခု",
    "checklist_state": "ပြင်ဆင်ရန် %1$d ခု",
    "checking_state": "စစ်ဆေးနေသည်။ မလုပ်တော့ပါ ခလုတ် ရှိသည်။",
    "cancel_checking": "စစ်ဆေးမှုကို ပယ်ဖျက်ရန်",
    "no_voice_state": "အသံ တောင်းဆိုချက် မရရှိပါ။ နောက်ခရီးစဉ်ကို ပြနိုင် သို့မဟုတ် ထပ်စမ်းနိုင်သည်။",
    "show_next_state": "နောက်ထပ် စီစဉ်ထားသော ခရီးစဉ်ကို ပြရန်",
    "retry_state": "အသံ တောင်းဆိုချက်ကို ထပ်စမ်းရန်",
    "close_state": "Binario တောင်းဆိုချက်ကို ပိတ်ရန်",
    "answer_heading": "Binario အဖြေ။ %1$s",
    "next_query": "နောက်ခရီးစဉ်",
    "saved_journey_keywords": "နောက်|ကျွန်ုပ်၏ ခရီးစဉ်|ကျွန်တော့် ခရီးစဉ်|ဘယ်အချိန် ထွက်",
    "realtime_keywords": "နောက်ကျ|စင်္ကြံ|ဖျက်သိမ်း|သတိပေး|အချိန်နှင့်တပြေးညီ",
    "companion_source": "Companion ၏ စက်တွင်း အချိန်ဇယား",
    "no_active_route": "အသက်ဝင်နေသော ခရီးစဉ် မရှိပါ",
    "companion_unavailable_notice": "Companion တွင် ဒေတာ မရရှိနိုင်ပါ- စက်တွင်း အချိန်ဇယားကို အပ်ဒိတ်လုပ်ရန် အဓိကအက်ပ်ကို ဖွင့်ပါ။"
  },
  "pt": {
    "app_name": "Binario Companion",
    "widget_label": "Status do Binario",
    "widget_description": "Situação ferroviária verificada do Binario",
    "shortcut_plan_short": "Planejar",
    "shortcut_plan_long": "Planejar uma viagem local",
    "shortcut_alerts_short": "Lembretes",
    "shortcut_alerts_long": "Abrir lembretes locais",
    "share_text_label": "Importar texto no Binario",
    "tile_name": "Status do Binario",
    "voice_request_label": "Falar com o Binario",
    "checklist_request_label": "Abrir checklist no celular",
    "unavailable": "Dados em tempo real indisponíveis.",
    "next_journey": "PRÓXIMA VIAGEM · PROGRAMADA",
    "open_binario": "Abra o Binario e dite",
    "reminder_and_status": "Lembrete %1$d min · %2$s",
    "scheduled_not_realtime": "Horário programado, não em tempo real",
    "countdown_minutes": "Em %1$d min",
    "countdown_hours": "Em %1$d h",
    "countdown_hours_minutes": "Em %1$d h %2$d min",
    "microphone": "Microfone · Falar com o Binario",
    "voice_prompt": "Pergunte ao Binario",
    "dictation_unavailable": "Ditado do sistema indisponível.",
    "checking": "Verificando…",
    "cancel": "Cancelar",
    "no_voice": "Nenhum pedido de voz capturado.",
    "show_next": "Mostrar próxima viagem",
    "retry": "Tentar de novo",
    "close": "Fechar",
    "listen": "Ouvir resposta",
    "enable_auto_read": "Ativar leitura automática",
    "disable_auto_read": "Desativar leitura automática",
    "opening_checklist": "Abrindo a checklist no celular…",
    "phone_unreachable": "Celular inacessível.",
    "checklist_opened": "Checklist aberta no celular.",
    "checklist_channel_name": "Pedidos do smartwatch",
    "checklist_notification_title": "Abrir checklist do Binario",
    "checklist_notification_text": "Toque para abrir a checklist no celular.",
    "cannot_open_phone": "Não é possível abrir o celular agora.",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "Abre o pedido de voz no smartwatch",
    "checklist": "Checklist · %1$d itens no celular",
    "checklist_state": "%1$d itens a preparar",
    "checking_state": "Verificando. O botão Cancelar está disponível.",
    "cancel_checking": "Cancelar verificação",
    "no_voice_state": "Nenhum pedido de voz capturado. Você pode mostrar a próxima viagem ou tentar de novo.",
    "show_next_state": "Mostrar a próxima viagem planejada",
    "retry_state": "Tentar o pedido de voz de novo",
    "close_state": "Fechar pedido do Binario",
    "answer_heading": "Resposta do Binario. %1$s",
    "next_query": "próxima viagem",
    "saved_journey_keywords": "próxima|próximo|minha viagem|meu trajeto|que horas sai",
    "realtime_keywords": "atras|plataforma|cancel|aviso|tempo real|realtime",
    "companion_source": "Planejamento local do Companion",
    "no_active_route": "Nenhuma viagem ativa",
    "companion_unavailable_notice": "Dados indisponíveis no Companion: abra o app principal para atualizar o planejamento local."
  },
  "ru": {
    "app_name": "Binario Companion",
    "widget_label": "Статус Binario",
    "widget_description": "Проверенное состояние движения в Binario",
    "shortcut_plan_short": "Планировать",
    "shortcut_plan_long": "Запланировать локальную поездку",
    "shortcut_alerts_short": "Напоминания",
    "shortcut_alerts_long": "Открыть локальные напоминания",
    "share_text_label": "Импортировать текст в Binario",
    "tile_name": "Статус Binario",
    "voice_request_label": "Говорить с Binario",
    "checklist_request_label": "Открыть чек-лист на телефоне",
    "unavailable": "Данные в реальном времени недоступны.",
    "next_journey": "СЛЕДУЮЩАЯ ПОЕЗДКА · ПО ПЛАНУ",
    "open_binario": "Откройте Binario и продиктуйте",
    "reminder_and_status": "Напоминание за %1$d мин · %2$s",
    "scheduled_not_realtime": "Плановое время, не в реальном времени",
    "countdown_minutes": "Через %1$d мин",
    "countdown_hours": "Через %1$d ч",
    "countdown_hours_minutes": "Через %1$d ч %2$d мин",
    "microphone": "Микрофон · Говорить с Binario",
    "voice_prompt": "Спросите Binario",
    "dictation_unavailable": "Системная диктовка недоступна.",
    "checking": "Проверка…",
    "cancel": "Отмена",
    "no_voice": "Голосовой запрос не получен.",
    "show_next": "Показать следующую поездку",
    "retry": "Повторить",
    "close": "Закрыть",
    "listen": "Прослушать ответ",
    "enable_auto_read": "Включить автоматическое чтение",
    "disable_auto_read": "Выключить автоматическое чтение",
    "opening_checklist": "Открываю чек-лист на телефоне…",
    "phone_unreachable": "Телефон недоступен.",
    "checklist_opened": "Чек-лист открыт на телефоне.",
    "checklist_channel_name": "Запросы со смарт-часов",
    "checklist_notification_title": "Открыть чек-лист Binario",
    "checklist_notification_text": "Нажмите, чтобы открыть чек-лист на телефоне.",
    "cannot_open_phone": "Сейчас не удаётся открыть телефон.",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "Открывает голосовой запрос на смарт-часах",
    "checklist": "Чек-лист · пунктов на телефоне: %1$d",
    "checklist_state": "Осталось подготовить: %1$d",
    "checking_state": "Идёт проверка. Доступна кнопка «Отмена».",
    "cancel_checking": "Отменить проверку",
    "no_voice_state": "Голосовой запрос не получен. Можно показать следующую поездку или повторить попытку.",
    "show_next_state": "Показать следующую запланированную поездку",
    "retry_state": "Повторить голосовой запрос",
    "close_state": "Закрыть запрос Binario",
    "answer_heading": "Ответ Binario. %1$s",
    "next_query": "следующая поездка",
    "saved_journey_keywords": "следующ|моя поездка|мой маршрут|во сколько отправ",
    "realtime_keywords": "задерж|опазд|платформ|отмен|уведомл|реальном времени",
    "companion_source": "Локальный план Companion",
    "no_active_route": "Нет активной поездки",
    "companion_unavailable_notice": "Данные недоступны в Companion: откройте основное приложение, чтобы обновить локальный план."
  },
  "th": {
    "app_name": "Binario Companion",
    "widget_label": "สถานะ Binario",
    "widget_description": "สถานะรถไฟที่ตรวจสอบแล้วของ Binario",
    "shortcut_plan_short": "วางแผน",
    "shortcut_plan_long": "วางแผนการเดินทางในเครื่อง",
    "shortcut_alerts_short": "การเตือน",
    "shortcut_alerts_long": "เปิดการเตือนในเครื่อง",
    "share_text_label": "นำเข้าข้อความไปยัง Binario",
    "tile_name": "สถานะ Binario",
    "voice_request_label": "พูดกับ Binario",
    "checklist_request_label": "เปิดเช็กลิสต์บนโทรศัพท์",
    "unavailable": "ไม่มีข้อมูลเรียลไทม์",
    "next_journey": "การเดินทางครั้งถัดไป · ตามแผน",
    "open_binario": "เปิด Binario แล้วพูด",
    "reminder_and_status": "เตือนก่อน %1$d นาที · %2$s",
    "scheduled_not_realtime": "เวลาตามแผน ไม่ใช่เรียลไทม์",
    "countdown_minutes": "อีก %1$d นาที",
    "countdown_hours": "อีก %1$d ชม.",
    "countdown_hours_minutes": "อีก %1$d ชม. %2$d นาที",
    "microphone": "ไมโครโฟน · พูดกับ Binario",
    "voice_prompt": "ถาม Binario",
    "dictation_unavailable": "การพิมพ์ด้วยเสียงของระบบไม่พร้อมใช้งาน",
    "checking": "กำลังตรวจสอบ…",
    "cancel": "ยกเลิก",
    "no_voice": "ไม่ได้รับคำขอด้วยเสียง",
    "show_next": "แสดงการเดินทางครั้งถัดไป",
    "retry": "ลองอีกครั้ง",
    "close": "ปิด",
    "listen": "ฟังคำตอบ",
    "enable_auto_read": "เปิดการอ่านอัตโนมัติ",
    "disable_auto_read": "ปิดการอ่านอัตโนมัติ",
    "opening_checklist": "กำลังเปิดเช็กลิสต์บนโทรศัพท์…",
    "phone_unreachable": "ติดต่อโทรศัพท์ไม่ได้",
    "checklist_opened": "เปิดเช็กลิสต์บนโทรศัพท์แล้ว",
    "checklist_channel_name": "คำขอจากสมาร์ตวอทช์",
    "checklist_notification_title": "เปิดเช็กลิสต์ Binario",
    "checklist_notification_text": "แตะเพื่อเปิดเช็กลิสต์บนโทรศัพท์",
    "cannot_open_phone": "ไม่สามารถเปิดโทรศัพท์ได้ในขณะนี้",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "เปิดคำขอด้วยเสียงบนสมาร์ตวอทช์",
    "checklist": "เช็กลิสต์ · %1$d รายการบนโทรศัพท์",
    "checklist_state": "ต้องเตรียม %1$d รายการ",
    "checking_state": "กำลังตรวจสอบ มีปุ่มยกเลิกให้ใช้",
    "cancel_checking": "ยกเลิกการตรวจสอบ",
    "no_voice_state": "ไม่ได้รับคำขอด้วยเสียง คุณแสดงการเดินทางครั้งถัดไปหรือลองอีกครั้งได้",
    "show_next_state": "แสดงการเดินทางตามแผนครั้งถัดไป",
    "retry_state": "ลองคำขอด้วยเสียงอีกครั้ง",
    "close_state": "ปิดคำขอ Binario",
    "answer_heading": "คำตอบจาก Binario %1$s",
    "next_query": "การเดินทางครั้งถัดไป",
    "saved_journey_keywords": "ถัดไป|การเดินทางของฉัน|เส้นทางของฉัน|ออกกี่โมง",
    "realtime_keywords": "ล่าช้า|ช้า|ชานชาลา|ยกเลิก|แจ้งเตือน|เรียลไทม์",
    "companion_source": "แผนในเครื่องของ Companion",
    "no_active_route": "ไม่มีการเดินทางที่ใช้งานอยู่",
    "companion_unavailable_notice": "ไม่มีข้อมูลใน Companion: เปิดแอปหลักเพื่ออัปเดตแผนในเครื่อง"
  },
  "tr": {
    "app_name": "Binario Companion",
    "widget_label": "Binario durumu",
    "widget_description": "Binario'nun doğrulanmış demiryolu durumu",
    "shortcut_plan_short": "Planla",
    "shortcut_plan_long": "Yerel bir yolculuk planla",
    "shortcut_alerts_short": "Hatırlatıcılar",
    "shortcut_alerts_long": "Yerel hatırlatıcıları aç",
    "share_text_label": "Binario'ya metin aktar",
    "tile_name": "Binario durumu",
    "voice_request_label": "Binario ile konuş",
    "checklist_request_label": "Kontrol listesini telefonda aç",
    "unavailable": "Gerçek zamanlı veri yok.",
    "next_journey": "SONRAKİ YOLCULUK · PLANLI",
    "open_binario": "Binario'yu aç ve dikte et",
    "reminder_and_status": "Hatırlatıcı %1$d dk · %2$s",
    "scheduled_not_realtime": "Planlı saat, gerçek zamanlı değil",
    "countdown_minutes": "%1$d dk sonra",
    "countdown_hours": "%1$d sa sonra",
    "countdown_hours_minutes": "%1$d sa %2$d dk sonra",
    "microphone": "Mikrofon · Binario ile konuş",
    "voice_prompt": "Binario'ya sor",
    "dictation_unavailable": "Sistem diktesi kullanılamıyor.",
    "checking": "Kontrol ediliyor…",
    "cancel": "İptal",
    "no_voice": "Sesli istek alınmadı.",
    "show_next": "Sonraki yolculuğu göster",
    "retry": "Tekrar dene",
    "close": "Kapat",
    "listen": "Yanıtı dinle",
    "enable_auto_read": "Otomatik okumayı aç",
    "disable_auto_read": "Otomatik okumayı kapat",
    "opening_checklist": "Kontrol listesi telefonda açılıyor…",
    "phone_unreachable": "Telefona ulaşılamıyor.",
    "checklist_opened": "Kontrol listesi telefonda açıldı.",
    "checklist_channel_name": "Akıllı saat istekleri",
    "checklist_notification_title": "Binario kontrol listesini aç",
    "checklist_notification_text": "Kontrol listesini telefonda açmak için dokunun.",
    "cannot_open_phone": "Telefon şu anda açılamıyor.",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "Akıllı saatte sesli isteği açar",
    "checklist": "Kontrol listesi · telefonda %1$d öğe",
    "checklist_state": "Hazırlanacak %1$d öğe",
    "checking_state": "Kontrol ediliyor. İptal düğmesi kullanılabilir.",
    "cancel_checking": "Kontrolü iptal et",
    "no_voice_state": "Sesli istek alınmadı. Sonraki yolculuğu gösterebilir veya tekrar deneyebilirsiniz.",
    "show_next_state": "Sonraki planlı yolculuğu göster",
    "retry_state": "Sesli isteği tekrar dene",
    "close_state": "Binario isteğini kapat",
    "answer_heading": "Binario yanıtı. %1$s",
    "next_query": "sonraki yolculuk",
    "saved_journey_keywords": "sonraki|yolculuğum|güzergâhım|güzergahım|kaçta kalkıyor",
    "realtime_keywords": "gecik|peron|iptal|uyarı|gerçek zamanlı|canlı",
    "companion_source": "Companion yerel planı",
    "no_active_route": "Etkin yolculuk yok",
    "companion_unavailable_notice": "Companion'da veri yok: yerel planı güncellemek için ana uygulamayı açın."
  },
  "vi": {
    "app_name": "Binario Companion",
    "widget_label": "Trạng thái Binario",
    "widget_description": "Tình trạng đường sắt đã xác minh của Binario",
    "shortcut_plan_short": "Lên kế hoạch",
    "shortcut_plan_long": "Lên kế hoạch hành trình trên máy",
    "shortcut_alerts_short": "Lời nhắc",
    "shortcut_alerts_long": "Mở lời nhắc trên máy",
    "share_text_label": "Nhập văn bản vào Binario",
    "tile_name": "Trạng thái Binario",
    "voice_request_label": "Nói với Binario",
    "checklist_request_label": "Mở danh sách kiểm tra trên điện thoại",
    "unavailable": "Không có dữ liệu thời gian thực.",
    "next_journey": "HÀNH TRÌNH TIẾP THEO · THEO KẾ HOẠCH",
    "open_binario": "Mở Binario và đọc",
    "reminder_and_status": "Nhắc trước %1$d phút · %2$s",
    "scheduled_not_realtime": "Giờ theo kế hoạch, không theo thời gian thực",
    "countdown_minutes": "Còn %1$d phút",
    "countdown_hours": "Còn %1$d giờ",
    "countdown_hours_minutes": "Còn %1$d giờ %2$d phút",
    "microphone": "Micrô · Nói với Binario",
    "voice_prompt": "Hỏi Binario",
    "dictation_unavailable": "Tính năng đọc chính tả của hệ thống không khả dụng.",
    "checking": "Đang kiểm tra…",
    "cancel": "Hủy",
    "no_voice": "Không nhận được yêu cầu bằng giọng nói.",
    "show_next": "Hiện hành trình tiếp theo",
    "retry": "Thử lại",
    "close": "Đóng",
    "listen": "Nghe câu trả lời",
    "enable_auto_read": "Bật tự động đọc",
    "disable_auto_read": "Tắt tự động đọc",
    "opening_checklist": "Đang mở danh sách kiểm tra trên điện thoại…",
    "phone_unreachable": "Không kết nối được với điện thoại.",
    "checklist_opened": "Đã mở danh sách kiểm tra trên điện thoại.",
    "checklist_channel_name": "Yêu cầu từ đồng hồ thông minh",
    "checklist_notification_title": "Mở danh sách kiểm tra Binario",
    "checklist_notification_text": "Chạm để mở danh sách kiểm tra trên điện thoại.",
    "cannot_open_phone": "Hiện không thể mở điện thoại.",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "Mở yêu cầu bằng giọng nói trên đồng hồ thông minh",
    "checklist": "Danh sách kiểm tra · %1$d mục trên điện thoại",
    "checklist_state": "%1$d mục cần chuẩn bị",
    "checking_state": "Đang kiểm tra. Có nút Hủy.",
    "cancel_checking": "Hủy kiểm tra",
    "no_voice_state": "Không nhận được yêu cầu bằng giọng nói. Bạn có thể hiện hành trình tiếp theo hoặc thử lại.",
    "show_next_state": "Hiện hành trình đã lên kế hoạch tiếp theo",
    "retry_state": "Thử lại yêu cầu bằng giọng nói",
    "close_state": "Đóng yêu cầu Binario",
    "answer_heading": "Câu trả lời của Binario. %1$s",
    "next_query": "hành trình tiếp theo",
    "saved_journey_keywords": "tiếp theo|kế tiếp|hành trình của tôi|tuyến của tôi|mấy giờ chạy",
    "realtime_keywords": "trễ|chậm|sân ga|hủy|cảnh báo|thời gian thực",
    "companion_source": "Kế hoạch trên máy của Companion",
    "no_active_route": "Không có hành trình đang hoạt động",
    "companion_unavailable_notice": "Không có dữ liệu trong Companion: hãy mở ứng dụng chính để cập nhật kế hoạch trên máy."
  },
  "b+zh+Hans": {
    "app_name": "Binario Companion",
    "widget_label": "Binario 状态",
    "widget_description": "Binario 已核实的铁路状态",
    "shortcut_plan_short": "规划",
    "shortcut_plan_long": "规划本机行程",
    "shortcut_alerts_short": "提醒",
    "shortcut_alerts_long": "打开本机提醒",
    "share_text_label": "将文本导入 Binario",
    "tile_name": "Binario 状态",
    "voice_request_label": "与 Binario 对话",
    "checklist_request_label": "在手机上打开清单",
    "unavailable": "无实时数据。",
    "next_journey": "下一个行程 · 计划",
    "open_binario": "打开 Binario 并听写",
    "reminder_and_status": "提前 %1$d 分钟提醒 · %2$s",
    "scheduled_not_realtime": "计划时刻，非实时",
    "countdown_minutes": "%1$d 分钟后",
    "countdown_hours": "%1$d 小时后",
    "countdown_hours_minutes": "%1$d 小时 %2$d 分钟后",
    "microphone": "麦克风 · 与 Binario 对话",
    "voice_prompt": "问问 Binario",
    "dictation_unavailable": "系统听写不可用。",
    "checking": "正在检查…",
    "cancel": "取消",
    "no_voice": "未收到语音请求。",
    "show_next": "显示下一个行程",
    "retry": "重试",
    "close": "关闭",
    "listen": "收听回答",
    "enable_auto_read": "开启自动朗读",
    "disable_auto_read": "关闭自动朗读",
    "opening_checklist": "正在手机上打开清单…",
    "phone_unreachable": "无法连接手机。",
    "checklist_opened": "已在手机上打开清单。",
    "checklist_channel_name": "来自智能手表的请求",
    "checklist_notification_title": "打开 Binario 清单",
    "checklist_notification_text": "点按即可在手机上打开清单。",
    "cannot_open_phone": "现在无法打开手机。",
    "tile_heading": "Binario. %1$s. %2$s",
    "voice_state": "在智能手表上打开语音请求",
    "checklist": "清单 · 手机上有 %1$d 项",
    "checklist_state": "待准备 %1$d 项",
    "checking_state": "正在检查。可使用“取消”按钮。",
    "cancel_checking": "取消检查",
    "no_voice_state": "未收到语音请求。你可以显示下一个行程或重试。",
    "show_next_state": "显示下一个计划行程",
    "retry_state": "重试语音请求",
    "close_state": "关闭 Binario 请求",
    "answer_heading": "Binario 的回答。%1$s",
    "next_query": "下一个行程",
    "saved_journey_keywords": "下一|我的行程|我的路线|几点出发|几点开",
    "realtime_keywords": "晚点|延误|站台|停运|取消|提醒|实时",
    "companion_source": "Companion 本机计划",
    "no_active_route": "没有进行中的行程",
    "companion_unavailable_notice": "Companion 中无可用数据：请打开主 App 更新本机计划。"
  }
};

const nativeStringKeys = Object.keys(localizedNativeStrings.it);
for (const locale of nativeLocales) {
  const keys = Object.keys(localizedNativeStrings[locale]);
  if (keys.length !== nativeStringKeys.length || keys.some((key, index) => key !== nativeStringKeys[index])) {
    throw new Error(`Native localization key parity failed for ${locale}.`);
  }
}

const binarioGlyphPath = "M12,8c-2.2,0 -4,1.8 -4,4v20c0,2.2 1.8,4 4,4h4l-3,4v2h5l3,-4h6l3,4h5v-2l-3,-4h4c2.2,0 4,-1.8 4,-4v-20c0,-2.2 -1.8,-4 -4,-4zM16,13h7v7h-7zM25,13h7v7h-7zM14,25h20v4h-20zM16,32a3,3 0,1 1,0 -6a3,3 0,0 1,0 6zM32,32a3,3 0,1 1,0 -6a3,3 0,0 1,0 6z";

const wearIconXml = `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="48dp" android:height="48dp" android:viewportWidth="48" android:viewportHeight="48">
  <path android:fillColor="#005A52" android:pathData="M24,0a24,24 0,1 1,0 48a24,24 0,1 1,0 -48z" />
  <group android:scaleX="0.7" android:scaleY="0.7" android:translateX="7.2" android:translateY="6.5">
    <path android:fillColor="#FFFFFF" android:pathData="${binarioGlyphPath}" />
  </group>
</vector>
`;

const wearTilePreviewXml = `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="200dp" android:height="200dp" android:viewportWidth="320" android:viewportHeight="320">
  <path android:fillColor="#000000" android:pathData="M0,0h320v320h-320z" />
  <path android:fillColor="#005A52" android:pathData="M24,24h272v272h-272z" />
  <group android:scaleX="2" android:scaleY="2" android:translateX="112" android:translateY="48">
    <path android:fillColor="#FFFFFF" android:pathData="${binarioGlyphPath}" />
  </group>
  <path android:fillColor="#FFFFFF" android:pathData="M64,170h192v18h-192z" />
  <path android:fillColor="#D5E4F1" android:pathData="M64,204h148v14h-148z" />
  <path android:fillColor="#D5E4F1" android:pathData="M64,234h176v14h-176z" />
</vector>
`;

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
    application.$["android:dataExtractionRules"] = dataExtractionRulesResource;
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
    fs.writeFileSync(path.join(xmlRoot, "binario_data_extraction_rules.xml"), dataExtractionRulesXml);

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
  <application android:label="@string/app_name" android:icon="@drawable/ic_binario_companion_wear" android:allowBackup="false" android:dataExtractionRules="${dataExtractionRulesResource}" android:usesCleartextTraffic="false" android:supportsRtl="true" android:theme="@android:style/Theme.DeviceDefault">
    <meta-data android:name="com.google.android.wearable.standalone" android:value="false" />
    <service android:name=".BinarioTileService" android:exported="true" android:icon="@drawable/ic_binario_companion_wear" android:label="@string/tile_name" android:permission="com.google.android.wearable.permission.BIND_TILE_PROVIDER">
      <intent-filter><action android:name="androidx.wear.tiles.action.BIND_TILE_PROVIDER" /></intent-filter>
      <meta-data android:name="androidx.wear.tiles.PREVIEW" android:resource="@drawable/binario_tile_preview" />
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
    // The app list and the tile picker show these instead of the generic placeholder: the Binario
    // mark on the Companion colour, and a preview of the tile's layout (title, route, status).
    const wearDrawableRoot = path.join(wearRoot, "src", "main", "res", "drawable");
    fs.mkdirSync(wearDrawableRoot, { recursive: true });
    fs.writeFileSync(path.join(wearDrawableRoot, "ic_binario_companion_wear.xml"), wearIconXml);
    fs.writeFileSync(path.join(wearDrawableRoot, "binario_tile_preview.xml"), wearTilePreviewXml);
    const wearXmlRoot = path.join(wearRoot, "src", "main", "res", "xml");
    fs.mkdirSync(wearXmlRoot, { recursive: true });
    fs.writeFileSync(path.join(wearXmlRoot, "binario_data_extraction_rules.xml"), dataExtractionRulesXml);
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
    val language = configured.language.lowercase(Locale.ROOT)
    val traditionalChinese = language == "zh" && configured.script != "Hans" && (configured.script == "Hant" || configured.country in setOf("TW", "HK", "MO"))
    return configured.takeIf { !traditionalChinese && language in setOf("it", "en", "de", "fr", "es", "ar", "hi", "in", "id", "ja", "ko", "my", "pt", "ru", "th", "tr", "vi", "zh") } ?: Locale.ITALIAN
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
module.exports.__test = { localizedNativeStrings, nativeLocales, nativeStringKeys, nativeStringsXml, wearIconXml, wearTilePreviewXml };
