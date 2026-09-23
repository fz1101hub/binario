import { describe, expect, it } from "vitest";
import { alertsRailwayStatusSemantics, companionHubActions, companionSurfaceSemantics, railwayStatusSemantics } from "@/lib/companion-ui-semantics";

describe("semantica dei controlli Companion", () => {
  it("descrive le due azioni realmente disponibili nell'hub", () => {
    expect(companionHubActions.planJourney).toEqual({
      accessibilityRole: "button",
      accessibilityLabel: "Pianifica una tratta",
      accessibilityHint: "Apre la schermata locale per aggiungere una partenza e un arrivo.",
    });
    expect(companionHubActions.manageReminders).toEqual({
      accessibilityRole: "button",
      accessibilityLabel: "Gestisci promemoria",
      accessibilityHint: "Rivedi o elimina promemoria locali, sempre non realtime.",
    });
  });

  it("annuncia le superfici e gli stati ferroviari senza presentarli come realtime", () => {
    expect(companionSurfaceSemantics("Widget", "Programmato localmente · non realtime")).toEqual({
      accessibilityRole: "summary",
      accessibilityLabel: "Widget. Programmato localmente · non realtime",
    });
    expect(railwayStatusSemantics({ dataNature: "unavailable" }).accessibilityLabel).toContain("dati realtime non disponibili");
    expect(railwayStatusSemantics({ dataNature: "scheduled" }).accessibilityLabel).toContain("orario programmato locale, non realtime");
    expect(alertsRailwayStatusSemantics(true).accessibilityLabel).toBe("Stato ferroviario: dati realtime non disponibili.");
    expect(alertsRailwayStatusSemantics(false).accessibilityLabel).toContain("nessun avviso realtime disponibile");
  });
});
