const { withAndroidManifest, withAppBuildGradle, withDangerousMod } = require("@expo/config-plugins");
const fs = require("node:fs");
const path = require("node:path");

const packageName = "im.binario.companion.binario";
const receiverName = `${packageName}.BinarioStatusWidgetReceiver`;

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

  private fun unavailable(title: String = "Binario") = BinarioWidgetState(
    title = title,
    subtitle = "Dati non disponibili in tempo reale.",
    status = "unavailable",
  )

  fun write(context: Context, state: BinarioWidgetState, now: Long = System.currentTimeMillis()) {
    val usable = state.status == "available" && state.updatedAtUtcMs != null &&
      state.expiresAtUtcMs != null && state.updatedAtUtcMs <= now && state.expiresAtUtcMs > now
    val accepted = if (usable) state else unavailable(state.title)
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
    val isFresh = status == "available" && updatedAt != null && expiresAt != null && updatedAt <= now && expiresAt > now
    if (!isFresh) return unavailable(title)
    val plannedTitle = prefs.getString(keyPlannedTitle, null)
    val plannedAt = if (prefs.contains(keyPlannedAt)) prefs.getLong(keyPlannedAt, 0L) else null
    val plannedUsable = !plannedTitle.isNullOrBlank() && plannedAt != null && plannedAt > now
    val reminder = if (prefs.contains(keyPlannedReminder)) prefs.getInt(keyPlannedReminder, 0) else null
    return BinarioWidgetState(title, prefs.getString(keySubtitle, "") ?: "", status, updatedAt, expiresAt, if (plannedUsable) plannedTitle else null, if (plannedUsable) plannedAt else null, if (plannedUsable) reminder else null)
  }
}
`;

const widgetKt = `package ${packageName}

import android.content.Context
import android.content.ComponentName
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

class BinarioStatusWidget : GlanceAppWidget() {
  private fun countdownLabel(scheduledAt: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = kotlin.math.ceil((scheduledAt - now).toDouble() / 60_000).toInt().coerceAtLeast(0)
    if (minutes < 60) return "Tra ${'$'}minutes min"
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0) "Tra ${'$'}hours h" else "Tra ${'$'}hours h ${'$'}remainder min"
  }
  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val state = BinarioWidgetStore.read(context)
    val voiceActivity = ComponentName(context, im.binario.companion.MainActivity::class.java)
    provideContent {
      Column(
        modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFF071525))).padding(16.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
      ) {
        Text(if (state.plannedJourneyTitle != null) "PROSSIMA TRATTA · PROGRAMMATA" else "BINARIO", style = TextStyle(color = ColorProvider(Color(0xFF9EDBFF))))
        Text(state.plannedJourneyTitle ?: state.title, style = TextStyle(color = ColorProvider(Color.White)))
        Text(if (state.plannedJourneyAtUtcMs != null) countdownLabel(state.plannedJourneyAtUtcMs) else state.subtitle, style = TextStyle(color = ColorProvider(Color(0xFFD5E4F1))))
        if (state.plannedJourneyTitle != null) Text("${'$'}{state.plannedReminderMinutes?.let { "Promemoria ${'$'}it min · " } ?: ""}Orario programmato, non realtime", style = TextStyle(color = ColorProvider(Color(0xFFD5E4F1))))
        Text("Apri Binario e detta", style = TextStyle(color = ColorProvider(Color(0xFF9EDBFF))), modifier = GlanceModifier.clickable(onClick = actionStartActivity(voiceActivity)))
      }
    }
  }
}

class BinarioStatusWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = BinarioStatusWidget()
}
`;

const bridgeKt = `package ${packageName}

import android.content.Intent
import android.net.Uri
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.uimanager.ViewManager
import androidx.glance.appwidget.updateAll
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

  private fun syncWearPayload(payload: ReadableMap) {
    val request = PutDataMapRequest.create("/binario/companion/watch-payload/v1")
    request.dataMap.putString("title", payload.getString("title") ?: "Binario")
    request.dataMap.putString("status", payload.getString("status") ?: "Dati non disponibili in tempo reale.")
    request.dataMap.putBoolean("canComplicate", payload.hasKey("canComplicate") && !payload.isNull("canComplicate") && payload.getBoolean("canComplicate"))
    if (payload.hasKey("plannedChecklistRemaining") && !payload.isNull("plannedChecklistRemaining")) request.dataMap.putInt("plannedChecklistRemaining", payload.getDouble("plannedChecklistRemaining").toInt())
    if (payload.hasKey("updatedAtUtcMs") && !payload.isNull("updatedAtUtcMs")) request.dataMap.putLong("updatedAtUtcMs", payload.getDouble("updatedAtUtcMs").toLong())
    if (payload.hasKey("expiresAtUtcMs") && !payload.isNull("expiresAtUtcMs")) request.dataMap.putLong("expiresAtUtcMs", payload.getDouble("expiresAtUtcMs").toLong())
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

@Suppress("DEPRECATION")
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
    <intent android:action="android.intent.action.VIEW" android:targetPackage="im.binario.companion" android:targetClass="im.binario.companion.MainActivity" android:data="binario-companion://plan" />
  </shortcut>
  <shortcut android:shortcutId="alerts" android:enabled="true" android:icon="@mipmap/ic_launcher" android:shortcutShortLabel="@string/binario_shortcut_alerts_short" android:shortcutLongLabel="@string/binario_shortcut_alerts_long">
    <intent android:action="android.intent.action.VIEW" android:targetPackage="im.binario.companion" android:targetClass="im.binario.companion.MainActivity" android:data="binario-companion://alerts" />
  </shortcut>
</shortcuts>
`;

function withBinarioAndroidWidget(config) {
  config = withAppBuildGradle(config, (next) => {
    const lines = [
      '    implementation("androidx.glance:glance-appwidget:1.2.0")',
      '    implementation("androidx.glance:glance:1.2.0")',
      '    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")',
      '    implementation("com.google.android.gms:play-services-wearable:20.0.1")',
    ];
    if (!next.modResults.contents.includes("androidx.glance:glance-appwidget")) {
      next.modResults.contents = next.modResults.contents.replace("dependencies {", `dependencies {\n${lines.join("\n")}`);
    }
    next.modResults.contents = removeDebugReleaseSigning(next.modResults.contents);
    return next;
  });

  config = withAndroidManifest(config, (next) => {
    const manifest = next.modResults.manifest;
    const application = manifest.application?.[0];
    if (!application) throw new Error("Android manifest senza application");
    const blockedProductionPermissions = new Set([
      "android.permission.READ_EXTERNAL_STORAGE",
      "android.permission.WRITE_EXTERNAL_STORAGE",
      "android.permission.SYSTEM_ALERT_WINDOW",
    ]);
    manifest["uses-permission"] = (manifest["uses-permission"] ?? []).filter(
      (permission) => !blockedProductionPermissions.has(permission.$?.["android:name"]),
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
    const kotlinRoot = path.join(appRoot, "src", "main", "java", ...packageName.split("."));
    const xmlRoot = path.join(appRoot, "src", "main", "res", "xml");
    fs.mkdirSync(kotlinRoot, { recursive: true });
    fs.mkdirSync(xmlRoot, { recursive: true });
    fs.writeFileSync(path.join(kotlinRoot, "BinarioWidgetStore.kt"), widgetStoreKt);
    fs.writeFileSync(path.join(kotlinRoot, "BinarioStatusWidget.kt"), widgetKt);
    fs.writeFileSync(path.join(kotlinRoot, "BinarioWidgetBridge.kt"), bridgeKt);
    fs.writeFileSync(path.join(kotlinRoot, "BinarioSharedTextReceiver.kt"), sharedTextReceiverKt);
    fs.writeFileSync(path.join(xmlRoot, "binario_status_widget_info.xml"), widgetXml);
    fs.writeFileSync(path.join(xmlRoot, "binario_shortcuts.xml"), shortcutsXml);

    const stringsPath = path.join(appRoot, "src", "main", "res", "values", "strings.xml");
    let strings = fs.readFileSync(stringsPath, "utf8").replace("<string name=\"app_name\">Binario Companion</string>", "<string name=\"app_name\">Binario</string>");
    if (!strings.includes("binario_widget_label")) strings = strings.replace("</resources>", "  <string name=\"binario_widget_label\">Stato Binario</string>\n  <string name=\"binario_widget_description\">Stato ferroviario verificato di Binario</string>\n</resources>");
    if (!strings.includes("binario_shortcut_plan_short")) strings = strings.replace("</resources>", "  <string name=\"binario_shortcut_plan_short\">Pianifica</string>\n  <string name=\"binario_shortcut_plan_long\">Pianifica una tratta locale</string>\n  <string name=\"binario_shortcut_alerts_short\">Promemoria</string>\n  <string name=\"binario_shortcut_alerts_long\">Apri promemoria locali</string>\n</resources>");
    if (!strings.includes("binario_share_text_label")) strings = strings.replace("</resources>", "  <string name=\"binario_share_text_label\">Importa testo in Binario</string>\n</resources>");
    fs.writeFileSync(stringsPath, strings);

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
      mainActivityKt = mainActivityKt.replace("    super.onCreate(null)", "    super.onCreate(null)\n    BinarioSharedTextStore.capture(this, intent)");
      mainActivityKt = mainActivityKt.replace("\n  /**\n   * Returns the name", "\n  override fun onNewIntent(intent: Intent) {\n    super.onNewIntent(intent)\n    setIntent(intent)\n    BinarioSharedTextStore.capture(this, intent)\n  }\n\n  /**\n   * Returns the name");
      fs.writeFileSync(mainActivityPath, mainActivityKt);
    }

    const wearRoot = path.join(next.modRequest.platformProjectRoot, "wear");
    const wearSource = path.join(wearRoot, "src", "main", "java", "im", "binario", "companion", "wear");
    fs.mkdirSync(wearSource, { recursive: true });
    fs.mkdirSync(path.join(wearRoot, "src", "main", "res", "values"), { recursive: true });
    fs.writeFileSync(path.join(wearRoot, "build.gradle"), `apply plugin: "com.android.application"
apply plugin: "org.jetbrains.kotlin.android"

android {
  namespace "im.binario.companion.wear"
  compileSdk rootProject.ext.compileSdkVersion
  defaultConfig {
    applicationId "im.binario.companion.wear"
    minSdkVersion 30
    targetSdkVersion rootProject.ext.targetSdkVersion
    versionCode 1
    versionName "0.1.0"
  }
  signingConfigs {
    debug {
      storeFile rootProject.file("app/debug.keystore")
      storePassword "android"
      keyAlias "androiddebugkey"
      keyPassword "android"
    }
  }
  buildTypes {
    debug {
      signingConfig signingConfigs.debug
    }
    release {
      // Production signing is supplied only by the release environment.
    }
  }
}

dependencies {
  implementation("androidx.wear.tiles:tiles:1.6.2")
  implementation("androidx.wear.protolayout:protolayout:1.4.2")
  implementation("androidx.activity:activity-ktx:1.10.1")
  implementation("com.google.android.gms:play-services-wearable:20.0.1")
  implementation("com.google.guava:guava:33.5.0-android")
}
`);
    fs.writeFileSync(path.join(wearRoot, "src", "main", "AndroidManifest.xml"), `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-feature android:name="android.hardware.type.watch" />
  <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
  <application android:label="@string/app_name" android:allowBackup="false" android:usesCleartextTraffic="false" android:theme="@android:style/Theme.DeviceDefault">
    <service android:name=".BinarioTileService" android:exported="true" android:label="@string/tile_name" android:permission="com.google.android.wearable.permission.BIND_TILE_PROVIDER">
      <intent-filter><action android:name="androidx.wear.tiles.action.BIND_TILE_PROVIDER" /></intent-filter>
    </service>
    <service android:name=".BinarioWearDataService" android:exported="true">
      <intent-filter>
        <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
        <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
        <data android:scheme="wear" android:host="*" android:pathPrefix="/binario/companion/watch-payload/v1" />
        <data android:scheme="wear" android:host="*" android:pathPrefix="/binario/companion/voice-response/v1" />
      </intent-filter>
    </service>
    <activity android:name=".BinarioVoiceRequestActivity" android:exported="true" android:label="@string/voice_request_label" />
    <activity android:name=".BinarioChecklistRequestActivity" android:exported="true" android:label="@string/checklist_request_label" />
  </application>
</manifest>
`);
    fs.writeFileSync(path.join(wearRoot, "src", "main", "res", "values", "strings.xml"), `<resources><string name="app_name">Binario</string><string name="tile_name">Stato Binario</string><string name="voice_request_label">Parla a Binario</string><string name="checklist_request_label">Apri checklist sul telefono</string></resources>`);
    fs.writeFileSync(path.join(wearSource, "BinarioWearData.kt"), `package im.binario.companion.wear

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
`);
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
      .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(textFor(BinarioWearStore.read(this))))
      .build()
  )
  override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) = Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())
  private fun textFor(state: BinarioWearState): LayoutElementBuilders.Column {
    val column = LayoutElementBuilders.Column.Builder()
      .setModifiers(ModifiersBuilders.Modifiers.Builder().setSemantics(
        ModifiersBuilders.Semantics.Builder().setContentDescription("Binario. ${'$'}{state.title}. ${'$'}{state.status}").setHeading(true).build()
      ).build())
      .addContent(LayoutElementBuilders.Text.Builder().setText("${'$'}{state.title}\\n${'$'}{state.status}").build())
      .addContent(LayoutElementBuilders.Text.Builder().setText("Microfono · Parla a Binario")
        .setModifiers(ModifiersBuilders.Modifiers.Builder().setSemantics(
          ModifiersBuilders.Semantics.Builder().setContentDescription("Parla a Binario").setStateDescription(TypeBuilders.StringProp.Builder("Apre la richiesta vocale sullo smartwatch").build()).build()
        ).setClickable(
          ModifiersBuilders.Clickable.Builder().setId("binario-voice-request").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
              ActionBuilders.AndroidActivity.Builder().setPackageName("im.binario.companion.wear").setClassName(BinarioVoiceRequestActivity::class.java.name).build()
            ).build()
          ).build()
        ).build())
        .build())
    if (state.plannedChecklistRemaining > 0) column.addContent(
      LayoutElementBuilders.Text.Builder().setText("Checklist · ${'$'}{state.plannedChecklistRemaining} voci sul telefono")
        .setModifiers(ModifiersBuilders.Modifiers.Builder().setSemantics(
          ModifiersBuilders.Semantics.Builder().setContentDescription("Apri checklist sul telefono").setStateDescription(TypeBuilders.StringProp.Builder("${'$'}{state.plannedChecklistRemaining} voci da preparare").build()).build()
        ).setClickable(
          ModifiersBuilders.Clickable.Builder().setId("binario-open-checklist").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
              ActionBuilders.AndroidActivity.Builder().setPackageName("im.binario.companion.wear").setClassName(BinarioChecklistRequestActivity::class.java.name).build()
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
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.wearable.Wearable

/** Il watch inoltra solo la richiesta di aprire l’Agenda; le voci checklist restano sul telefono. */
class BinarioChecklistRequestActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val message = TextView(this).apply { text = "Apro la checklist sul telefono…"; textSize = 17f; setPadding(24, 24, 24, 24) }
    setContentView(LinearLayout(this).apply { gravity = Gravity.CENTER; addView(message) })
    Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
      if (nodes.isEmpty()) { message.text = "Telefono non raggiungibile."; return@addOnSuccessListener }
      nodes.forEach { node -> Wearable.getMessageClient(this).sendMessage(node.id, "/binario/companion/open-checklist/v1", ByteArray(0)) }
      message.text = "Checklist aperta sul telefono."
      message.announceForAccessibility(message.text)
    }.addOnFailureListener { message.text = "Non è possibile aprire il telefono ora."; message.announceForAccessibility(message.text) }
  }
}
`);
    fs.writeFileSync(path.join(wearSource, "BinarioVoiceRequestActivity.kt"), `package im.binario.companion.wear

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.wearable.Wearable
import java.util.Locale

/** Input vocale fornito dal sistema Wear OS: Binario non registra né persiste l'audio o il transcript. */
class BinarioVoiceRequestActivity : Activity() {
  private var tts: TextToSpeech? = null
  private val handler = Handler(Looper.getMainLooper())
  private val voicePreferences by lazy { getSharedPreferences("binario_wear_voice_preferences_v1", Context.MODE_PRIVATE) }
  private var waitingForRealtime = false
  private fun touchTargetPx() = (48 * resources.displayMetrics.density).toInt()
  private fun announceForAccessibility(message: String) { window.decorView.post { window.decorView.announceForAccessibility(message) } }
  private val replyReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == replyAction && waitingForRealtime) {
        waitingForRealtime = false
        handler.removeCallbacksAndMessages(null)
        showAnswer(intent.getStringExtra(replyExtra) ?: "Dati non disponibili in tempo reale.")
      }
    }
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale.ITALIAN }
    val filter = IntentFilter(replyAction)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(replyReceiver, filter, Context.RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(replyReceiver, filter)
    launchSystemVoiceInput()
  }
  private fun launchSystemVoiceInput() {
    try {
      startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ITALIAN.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Chiedi a Binario")
      }, companionRequest)
    } catch (_: Exception) { showAnswer("Dettatura di sistema non disponibile.") }
  }
  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    val transcript = if (requestCode == companionRequest && resultCode == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty() else ""
    if (transcript.isEmpty()) showLocalActions() else if (asksRealtime(transcript)) requestFreshRealtimeAnswer(transcript) else showAnswer(answerFromFreshLocalState(transcript))
  }
  private fun answerFromFreshLocalState(transcript: String): String {
    val state = BinarioWearStore.read(this)
    val question = transcript.lowercase(Locale.ITALIAN).trim().split(" ").filter { it.isNotBlank() }.joinToString(" ")
    val asksSavedJourney = listOf("prossima", "prossimo", "mio viaggio", "mia tratta", "a che ora parte").any { question.contains(it) }
    val savedRoute = state.title.split("→").map { it.trim().lowercase(Locale.ITALIAN) }
    val asksCurrentSavedRoute = savedRoute.size == 2 && savedRoute.all { it.length >= 2 && question.contains(it) }
    return if ((asksSavedJourney || asksCurrentSavedRoute) && state.canComplicate) "${'$'}{state.title}. ${'$'}{state.status}" else "Dati non disponibili in tempo reale."
  }
  private fun asksRealtime(transcript: String) = listOf("ritard", "binari", "cancell", "avvis", "realtime").any { transcript.lowercase(Locale.ITALIAN).contains(it) }
  private fun requestFreshRealtimeAnswer(transcript: String) {
    val request = transcript.take(240).toByteArray(Charsets.UTF_8)
    if (request.isEmpty()) { showAnswer("Dati non disponibili in tempo reale."); return }
    waitingForRealtime = true
    showWaiting()
    Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
      val node = nodes.firstOrNull()
      if (node == null) { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); showAnswer("Dati non disponibili in tempo reale."); return@addOnSuccessListener }
      Wearable.getMessageClient(this).sendMessage(node.id, "/binario/companion/voice-request/v1", request).addOnFailureListener { if (waitingForRealtime) { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); showAnswer("Dati non disponibili in tempo reale.") } }
    }
    handler.postDelayed({ if (waitingForRealtime) { waitingForRealtime = false; showAnswer("Dati non disponibili in tempo reale.") } }, 12_000)
  }
  private fun showWaiting() {
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
      addView(TextView(this@BinarioVoiceRequestActivity).apply { text = "Verifica in corso…"; textSize = 18f; gravity = Gravity.CENTER; contentDescription = text })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = "Annulla"; contentDescription = "Annulla verifica"; setOnClickListener { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); finish() } })
    })
    announceForAccessibility("Verifica in corso. È disponibile il pulsante Annulla.")
  }
  private fun showLocalActions() {
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
      addView(TextView(this@BinarioVoiceRequestActivity).apply { text = "Nessuna richiesta vocale acquisita."; textSize = 17f; gravity = Gravity.CENTER; contentDescription = text })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = "Mostra prossima tratta"; contentDescription = "Mostra la prossima tratta programmata"; setOnClickListener { showAnswer(answerFromFreshLocalState("prossima tratta")) } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = "Riprova"; contentDescription = "Riprova richiesta vocale"; setOnClickListener { launchSystemVoiceInput() } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = "Chiudi"; contentDescription = "Chiudi richiesta Binario"; setOnClickListener { finish() } })
    })
    announceForAccessibility("Nessuna richiesta vocale acquisita. Puoi mostrare la prossima tratta o riprovare.")
  }
  private fun showAnswer(answer: String) {
    val speakAnswers = voicePreferences.getBoolean("speak_answers", false)
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
      addView(TextView(this@BinarioVoiceRequestActivity).apply { text = answer; textSize = 18f; gravity = Gravity.CENTER; contentDescription = answer })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = "Ascolta risposta"; contentDescription = "Ascolta risposta"; setOnClickListener { tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "binario-voice-answer") } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = if (speakAnswers) "Disattiva lettura automatica" else "Attiva lettura automatica"; contentDescription = text; setOnClickListener { voicePreferences.edit().putBoolean("speak_answers", !speakAnswers).apply(); showAnswer(answer) } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = "Riprova"; contentDescription = "Riprova richiesta vocale"; setOnClickListener { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); launchSystemVoiceInput() } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = "Chiudi"; contentDescription = "Chiudi richiesta Binario"; setOnClickListener { finish() } })
    })
    announceForAccessibility("Risposta Binario. ${'$'}answer")
    if (speakAnswers) tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "binario-voice-answer")
  }
  override fun onDestroy() { unregisterReceiver(replyReceiver); handler.removeCallbacksAndMessages(null); tts?.stop(); tts?.shutdown(); super.onDestroy() }
  companion object { const val companionRequest = 71; const val replyAction = "im.binario.companion.wear.VOICE_REPLY"; const val replyExtra = "binario_voice_reply" }
}
`);
    const settingsPath = path.join(next.modRequest.platformProjectRoot, "settings.gradle");
    let settings = fs.readFileSync(settingsPath, "utf8");
    if (!settings.includes("include ':wear'")) { settings += "\ninclude ':wear'\n"; fs.writeFileSync(settingsPath, settings); }
    return next;
  }]);
}

module.exports = withBinarioAndroidWidget;
