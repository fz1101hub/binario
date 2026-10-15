import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import {
  alertsRailwayStatusSemantics,
  companionHubActions,
  companionSurfaceSemantics,
  railwayStatusSemantics,
} from "@/lib/companion-ui-semantics";

describe("semantica dei controlli Companion", () => {
  it("descrive le due azioni realmente disponibili nell'hub", () => {
    expect(companionHubActions.planJourney).toEqual({
      accessibilityRole: "button",
      accessibilityLabel: "Pianifica una tratta",
      accessibilityHint:
        "Apre la schermata locale per aggiungere una partenza e un arrivo.",
    });
    expect(companionHubActions.manageReminders).toEqual({
      accessibilityRole: "button",
      accessibilityLabel: "Gestisci promemoria",
      accessibilityHint:
        "Rivedi o elimina promemoria locali, sempre non in tempo reale.",
    });
  });

  it("annuncia le superfici e gli stati ferroviari senza presentarli come realtime", () => {
    expect(
      companionSurfaceSemantics(
        "Widget",
        "Programmato localmente · non realtime",
      ),
    ).toEqual({
      accessibilityRole: "summary",
      accessibilityLabel: "Widget. Programmato localmente · non realtime",
    });
    expect(
      railwayStatusSemantics({ dataNature: "unavailable" }).accessibilityLabel,
    ).toContain("dati in tempo reale non disponibili");
    expect(
      railwayStatusSemantics({ dataNature: "scheduled" }).accessibilityLabel,
    ).toContain("orario programmato locale, non in tempo reale");
    expect(alertsRailwayStatusSemantics(true).accessibilityLabel).toBe(
      "Stato ferroviario: dati in tempo reale non disponibili.",
    );
    expect(alertsRailwayStatusSemantics(false).accessibilityLabel).toContain(
      "nessun avviso in tempo reale disponibile",
    );
  });

  it("presenta anche in foreground i reminder locali pianificati", () => {
    const layout = readFileSync(
      path.resolve(import.meta.dirname, "..", "app", "_layout.tsx"),
      "utf8",
    );
    expect(layout).toContain("Notifications.setNotificationHandler");
    expect(layout).toContain("shouldShowBanner: true");
    expect(layout).toContain("shouldShowList: true");
    expect(layout).toContain("shouldPlaySound: true");
  });
});
