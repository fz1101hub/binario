import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const source = (relativePath: string) => readFileSync(path.resolve(process.cwd(), relativePath), "utf8");

describe("contratti delle superfici native Binario", () => {
  it("mantiene controlli tema e sincronizzazione espliciti nelle impostazioni", () => {
    const settings = source("app/(tabs)/settings.tsx");
    expect(settings).toContain('accessibilityRole="switch"');
    expect(settings).toContain("Autorizza sincronizzazione per questo dispositivo");
    expect(settings).toContain("Nessuna tratta è stata inviata");
    expect(settings).toContain("Revoca il collegamento di questo dispositivo alla sincronizzazione");
    expect(settings).toContain("Cancella tratte e promemoria locali da questo telefono");
  });

  it("espone stato companion e indisponibilità senza trasformarli in realtime", () => {
    const companion = source("app/(tabs)/companion.tsx");
    expect(companion).toContain('accessibilityRole="header"');
    expect(companion).toContain('accessibilityRole="button"');
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
