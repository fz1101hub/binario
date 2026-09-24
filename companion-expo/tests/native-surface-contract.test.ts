import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { createLocalScheduledSnapshot, createUnavailableSnapshot } from "@/lib/companion-snapshot";
import { createLocalPlannedRoute } from "@/lib/local-route-contract";
import { binarioSharedTextReceivedEventName, binarioSharedTextReceiverModuleName, binarioWidgetBridgeModuleName } from "@/lib/native-module-names";
import { buildNativeSurfacePayload } from "@/lib/native-surface-contract";
import { maxIncomingSharedTextCharacters } from "@/lib/shared-text-contract";

const source = (relativePath: string) => readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
const widgetPlugin = source("plugins/with-binario-android-widget.cjs");
const releaseIdentity = JSON.parse(source("native/android/release-identity.json")) as {
  schemaVersion: number;
  applicationId: string;
  versionName: string;
  phoneVersionCode: number;
  wearVersionCode: number;
};

/** Corpo della prima funzione Kotlin con quel nome nel template (le funzioni del template chiudono con `  }` a due spazi). */
function kotlinFunctionBody(name: string) {
  const start = widgetPlugin.indexOf(`fun ${name}(`);
  expect(start, `funzione Kotlin ${name} assente dal template`).toBeGreaterThanOrEqual(0);
  return widgetPlugin.slice(start, widgetPlugin.indexOf("\n  }\n", start));
}

/** Estrae ogni lettura `ricevitore.getX("chiave")` dal corpo Kotlin, così un rinomina lato Kotlin fa fallire il contratto. */
function kotlinReads(body: string) {
  return Array.from(body.matchAll(/\b(\w+)\??\.(getString|getDouble|getInt|getBoolean|getMap)\("(\w+)"\)/g), match => ({ receiver: match[1]!, accessor: match[2]!, key: match[3]! }));
}

function kotlinModuleNames() {
  return Array.from(widgetPlugin.matchAll(/override fun getName\(\) = "([^"]+)"/g), match => match[1]!);
}

describe("contratti delle superfici native Binario", () => {
  it("genera telefono e Wear con lo stesso applicationId e la stessa firma release", () => {
    const appGradle = source("android/app/build.gradle");
    const wearGradle = source("android/wear/build.gradle");
    expect(releaseIdentity.schemaVersion).toBe(1);
    expect(widgetPlugin).toContain('require("../native/android/release-identity.json")');
    expect(appGradle).toContain(`applicationId '${releaseIdentity.applicationId}'`);
    expect(wearGradle).toContain(`applicationId "${releaseIdentity.applicationId}"`);
    expect(appGradle).toContain(`versionCode ${releaseIdentity.phoneVersionCode}`);
    expect(wearGradle).toContain(`versionCode ${releaseIdentity.wearVersionCode}`);
    expect(appGradle).toContain(`versionName "${releaseIdentity.versionName}"`);
    expect(wearGradle).toContain(`versionName "${releaseIdentity.versionName}"`);
    expect(appGradle).toContain("if (binarioReleaseSigningConfigured) signingConfig signingConfigs.release");
    expect(wearGradle).toContain("if (binarioReleaseSigningConfigured) signingConfig signingConfigs.release");
    expect(wearGradle).not.toContain('applicationId "im.binario.companion.wear"');
  });

  it("non ricattura il testo condiviso durante la sola ricreazione Activity", () => {
    const mainActivity = source("android/app/src/main/java/im/binario/companion/MainActivity.kt");
    expect(mainActivity).toContain("if (savedInstanceState == null) BinarioSharedTextStore.capture(this, intent)");
    expect(mainActivity).toContain("override fun onNewIntent(intent: Intent)");
  });

  it("genera il lock SHA-256 Gradle in ogni prebuild Android", () => {
    const metadata = source("native/android/verification-metadata.xml");
    expect(widgetPlugin).toContain('"native", "android", "verification-metadata.xml"');
    expect(widgetPlugin).toContain('"gradle", "verification-metadata.xml"');
    expect(widgetPlugin).toContain("fs.copyFileSync(verificationMetadataSource, verificationMetadataTarget)");
    expect(metadata).toContain("<verify-metadata>true</verify-metadata>");
    expect(metadata).not.toContain("<trusted-artifacts>");
  });
  it("serializza il packaging Gradle per un ordine AAB deterministico", () => {
    const gradleProperties = source("android/gradle.properties");
    expect(gradleProperties).toContain("org.gradle.parallel=false");
    expect(gradleProperties).toContain("org.gradle.workers.max=1");
    expect(widgetPlugin).toContain('setGradleProperty(gradleProperties, "org.gradle.parallel", "false")');
    expect(widgetPlugin).toContain('setGradleProperty(gradleProperties, "org.gradle.workers.max", "1")');
  });
  it("fissa il checksum della distribuzione Gradle prima di eseguirla", () => {
    const wrapperProperties = source("android/gradle/wrapper/gradle-wrapper.properties");
    const expected =
      "distributionSha256Sum=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531";
    expect(wrapperProperties).toContain(expected);
    expect(widgetPlugin).toContain('"distributionSha256Sum"');
    expect(widgetPlugin).toContain('"bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"');
  });

  it("mantiene controlli tema e dichiara indisponibile la sincronizzazione remota", () => {
    const settings = source("app/(tabs)/settings.tsx");
    expect(settings).toContain('accessibilityRole="switch"');
    expect(settings).toContain("Sincronizzazione remota");
    expect(settings).toContain("Non disponibile in questa build: il servizio remoto non è ancora attivo.");
    expect(settings).toContain("Nessun account può essere collegato e nessuna tratta può essere inviata o ricevuta.");
    expect(settings).not.toContain("Autorizza sincronizzazione per questo dispositivo");
    expect(settings).not.toContain("Collega dispositivo");
    expect(settings).not.toContain("Controlla collegamento");
    expect(settings).not.toContain("Copia sul telefono");
    expect(settings).not.toContain("Copia nell’area personale");
    expect(settings).not.toContain("fetchCompanionPlannedRoutes");
    expect(settings).not.toContain("pushCompanionPlannedRoutes");
    expect(settings).toContain("Cancella tratte e promemoria locali da questo telefono");
  });

  it("non espone nell’hub una route operativa di sincronizzazione remota", () => {
    const companion = source("app/(tabs)/companion.tsx");
    expect(companion).not.toContain("Sincronizzazione facoltativa");
    expect(companion).not.toContain('icon: "sync"');
    expect(companion).toContain("Pianifica una tratta");
    expect(companion).toContain("Gestisci promemoria");
  });

  it("espone stato companion e indisponibilità senza trasformarli in realtime", () => {
    const companion = source("app/(tabs)/companion.tsx");
    expect(companion).toContain('accessibilityRole="header"');
    expect(companion).toContain("const semantics = companionHubActions[action.id];");
    expect(companion).toContain("<Pressable key={action.label} {...semantics}");
    expect(companion).toContain("Programmato localmente · non realtime");
    expect(companion).toContain("non mostrano dati ferroviari inventati");
  });

  it("conserva la provenienza e il riepilogo accessibile dello snapshot", () => {
    const snapshotCard = source("components/snapshot-card.tsx");
    expect(snapshotCard).toContain('accessibilityRole="summary"');
    expect(snapshotCard).toContain("Fonte:");
    expect(snapshotCard).toContain("decision.notice");
  });

  it("mantiene checklist sul telefono e un’azione Tile che non ne trasferisce le voci", () => {
    const alerts = source("app/(tabs)/alerts.tsx");
    const widgetPlugin = source("plugins/with-binario-android-widget.cjs");
    expect(alerts).toContain('accessibilityRole="checkbox"');
    expect(alerts).toContain("localJourneyChecklistLabels");
    expect(widgetPlugin).toContain("plannedChecklistRemaining");
    expect(widgetPlugin).toContain("/binario/companion/open-checklist/v1");
    expect(widgetPlugin).toContain("Checklist aperta sul telefono.");
  });

  it("annuncia gli stati Wear con live region senza API deprecate", () => {
    const checklist = source("android/wear/src/main/java/im/binario/companion/wear/BinarioChecklistRequestActivity.kt");
    const voice = source("android/wear/src/main/java/im/binario/companion/wear/BinarioVoiceRequestActivity.kt");
    for (const kotlin of [widgetPlugin, checklist, voice]) {
      expect(kotlin).not.toContain("announceForAccessibility");
    }
    expect(widgetPlugin).toContain("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE");
    expect(checklist).toContain("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE");
    expect(voice.match(/accessibilityLiveRegion = View\.ACCESSIBILITY_LIVE_REGION_POLITE/g)).toHaveLength(3);
  });

  it("limita il planner a preset ferroviari e di viaggio pertinenti", () => {
    const planner = source("components/local-plan-card.tsx");
    const routeContract = source("lib/local-route-contract.ts");
    expect(planner).toContain("Preparazione del viaggio");
    expect(planner).toContain("localJourneyChecklistPresets");
    expect(routeContract).toContain('label: "Bici sul treno"');
    expect(routeContract).toContain('label: "Assistenza in stazione"');
    expect(routeContract).not.toContain("note generiche");
  });

  it("mantiene il trasferimento archivio manuale, selettivo e separato dalla sincronizzazione", () => {
    const settings = source("app/(tabs)/settings.tsx");
    expect(settings).toContain("Trasferimento manuale");
    expect(settings).toContain("Esporta archivio");
    expect(settings).toContain("Anteprima archivio");
    expect(settings).toContain("Nessuna tratta è stata importata");
    expect(settings).toContain("nessun backup automatico");
  });

  it("mostra orari e date nel formato locale del dispositivo", () => {
    const planner = source("components/local-plan-card.tsx");
    const agenda = source("app/(tabs)/alerts.tsx");
    const hub = source("lib/companion-hub.ts");
    expect(planner).toContain("formatLocalJourneyDate");
    expect(planner).toContain("formatLocalJourneyTime");
    expect(agenda).toContain("formatLocalJourneySchedule");
    expect(hub).toContain("formatLocalJourneySchedule");
  });

  it("propone la stazione vicina solo dal campo Partenza e senza coordinate persistite", () => {
    const planner = source("components/local-plan-card.tsx");
    const autocomplete = source("components/station-autocomplete-input.tsx");
    const locator = source("lib/nearby-offline-stations.ts");
    expect(planner).toContain("requestNearestOfflineStation");
    expect(planner).toContain("onUseNearestStation");
    expect(autocomplete).toContain("Usa la stazione più vicina");
    expect(locator).toContain("requestForegroundPermissionsAsync");
    expect(locator).not.toContain("AsyncStorage");
  });

  it("limita il receiver Android a testo condiviso e lo invia alla revisione Agenda", () => {
    const plugin = source("plugins/with-binario-android-widget.cjs");
    const agenda = source("app/(tabs)/alerts.tsx");
    const importCard = source("components/guided-text-route-import-card.tsx");
    expect(plugin).toContain("android.intent.action.SEND");
    expect(plugin).toContain('android:mimeType": "text/plain"');
    expect(plugin).toContain("BinarioSharedTextStore.capture");
    expect(plugin).toContain("prefs.edit().remove(keyText).apply()");
    expect(plugin).not.toContain("ACTION_SEND_MULTIPLE");
    expect(agenda).toContain("useIncomingSharedText");
    expect(importCard).toContain("receivedText");
    expect(importCard).toContain("non importa automaticamente");
  });

  it("consegna il testo condiviso tramite il modulo BinarioSharedTextReceiver, anche ad app già aperta", () => {
    const provider = source("lib/incoming-shared-text.tsx");
    expect([...kotlinModuleNames()].sort()).toEqual([binarioSharedTextReceiverModuleName, binarioWidgetBridgeModuleName].sort());
    expect(widgetPlugin).toContain(`eventName = "${binarioSharedTextReceivedEventName}"`);
    expect(Number(widgetPlugin.match(/maxCharacters = ([\d_]+)/)?.[1]?.replace(/_/g, ""))).toBe(maxIncomingSharedTextCharacters);
    expect(kotlinFunctionBody("capture")).toContain("emit(eventName");
    expect(kotlinFunctionBody("capture")).not.toContain("remove(keyText)");
    expect(kotlinFunctionBody("consume")).toContain("remove(keyText)");
    expect(provider).toContain("NativeModules[binarioSharedTextReceiverModuleName]");
    expect(provider).toContain("DeviceEventEmitter.addListener(eventName, handler)");
    expect(provider).toContain("subscribeIncomingSharedText(");
    expect(provider).not.toContain("BinarioSharedTextStore");
  });

  it("espone ricorrenze e completamento Agenda come azioni locali esplicite", () => {
    const agenda = source("app/(tabs)/alerts.tsx");
    const routes = source("lib/local-routes.ts");
    const contract = source("lib/local-route-contract.ts");
    expect(agenda).toContain("Duplica nei giorni scelti");
    expect(agenda).toContain("Completata manualmente sul telefono");
    expect(routes).toContain("addExplicitRecurringLocalPlannedRoutes");
    expect(routes).toContain("setLocalPlannedRouteCompletion");
    expect(contract).toContain("maxExplicitLocalJourneyOccurrences");
    expect(contract).toContain("non l’esito o la circolazione effettiva del treno");
  });
});

describe("contratto JS ↔ Kotlin di BinarioWidgetBridge", () => {
  const route = createLocalPlannedRoute({ origin: "Roma Termini", destination: "Napoli Centrale", travelDate: "2030-01-02", travelTime: "09:30", plannedReminderMinutes: 15 }, 4, 1)!;
  const now = new Date("2030-01-02T08:00:00").getTime();
  const usable = buildNativeSurfacePayload(createLocalScheduledSnapshot(route, now - 1), route, now);
  const unavailable = buildNativeSurfacePayload(createUnavailableSnapshot({ kind: "route", id: "none", label: "Nessuna tratta attiva" }), null, now);

  it("chiama il modulo con il nome dichiarato da getName() nel template Kotlin", () => {
    expect(kotlinModuleNames()).toContain(binarioWidgetBridgeModuleName);
    expect(source("lib/native-surface-bridge.ts")).toContain("NativeModules[binarioWidgetBridgeModuleName]");
    expect(source("lib/native-surface-bridge.ts")).not.toContain("BinarioCompanionSurfaces");
  });

  it("invia al bridge solo widget e snapshot Wear v1, validato prima della DataMap", () => {
    const maps: Record<string, unknown> = { payload: usable, widget: usable.widget, planned: usable.widget.plannedJourney };
    const reads = [
      ...kotlinReads(kotlinFunctionBody("update")).map(read => ({ ...read, map: read.receiver })),
    ];
    expect(reads.length).toBeGreaterThan(0);
    for (const read of reads) {
      const label = `${read.map}.${read.key} (${read.accessor})`;
      expect(maps, `ricevitore Kotlin ${read.receiver} non mappato nel payload`).toHaveProperty(read.map);
      const map = maps[read.map] as Record<string, unknown>;
      expect(map, label).toHaveProperty(read.key);
      const value = map[read.key];
      if (read.accessor === "getString") expect(typeof value, label).toBe("string");
      if (read.accessor === "getDouble" || read.accessor === "getInt") expect(Number.isFinite(value), label).toBe(true);
      if (read.accessor === "getBoolean") expect(typeof value, label).toBe("boolean");
      if (read.accessor === "getMap") expect(value !== null && typeof value === "object", label).toBe(true);
    }
    for (const [name, map] of Object.entries(maps)) {
      expect(Object.keys(map as object).sort(), `chiavi inviate in ${name}`).toEqual([...new Set(reads.filter(read => read.map === name).map(read => read.key))].sort());
    }
    const bridge = kotlinFunctionBody("syncWearPayload");
    expect(bridge).toContain("CompanionWearPayloadV1.decode");
    expect(bridge).toContain("CompanionWearPayloadV1.put");
    expect(bridge).toContain('PutDataMapRequest.create("/binario/companion/watch-payload/v1")');
    expect(Object.keys(usable.watch).sort()).toEqual([
      "confidence", "direction", "expiresAtEpochMillis", "freshness", "generatedAtEpochMillis", "notice",
      "operationalState", "provenanceDataNature", "provenanceObservedAtEpochMillis", "provenanceProducer",
      "provenanceSourceLabel", "routeId", "routeLabel", "schemaVersion", "serviceCount", "ttlMillis",
    ]);
  });

  it("resta fail-closed senza tratta futura, con snapshot non disponibile o scaduto", () => {
    expect(unavailable.widget).toEqual({ title: "Binario", subtitle: "Dati non disponibili in tempo reale.", status: "unavailable", updatedAtUtcMs: null, expiresAtUtcMs: null, plannedJourney: null });
    expect(unavailable.watch).toMatchObject({ schemaVersion: 1, provenanceProducer: "COMPANION", provenanceDataNature: "UNAVAILABLE", operationalState: "UNAVAILABLE", freshness: "UNAVAILABLE", serviceCount: 0, direction: "UNKNOWN" });
    const unavailableSnapshot = buildNativeSurfacePayload(createUnavailableSnapshot({ kind: "route", id: "4", label: route.name }), route, now);
    const expiredSnapshot = buildNativeSurfacePayload(createLocalScheduledSnapshot(route, now), route, new Date("2030-01-02T09:30:00").getTime());
    expect(unavailableSnapshot.widget).toEqual(unavailable.widget);
    expect(expiredSnapshot.widget).toEqual(unavailable.widget);
    for (const candidate of [unavailableSnapshot.watch, expiredSnapshot.watch]) {
      expect(candidate).toMatchObject({ schemaVersion: 1, provenanceDataNature: "UNAVAILABLE", operationalState: "UNAVAILABLE", serviceCount: 0 });
      expect(candidate.expiresAtEpochMillis - candidate.generatedAtEpochMillis).toBe(candidate.ttlMillis);
    }
    expect(usable.widget).toMatchObject({ title: route.name, status: "available", updatedAtUtcMs: now - 1, expiresAtUtcMs: new Date("2030-01-02T09:30:00").getTime(), plannedJourney: { title: route.name, scheduledAtUtcMs: new Date("2030-01-02T09:30:00").getTime(), reminderMinutes: 15 } });
    expect(usable.watch).toMatchObject({ schemaVersion: 1, routeLabel: route.name, provenanceProducer: "COMPANION", provenanceDataNature: "PROGRAMMED", operationalState: "PROGRAMMED", confidence: "LOCAL_PLANNED", serviceCount: 0, generatedAtEpochMillis: now - 1 });
    expect(usable.watch.notice).toContain("non è un aggiornamento realtime");
  });
});
