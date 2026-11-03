import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync } from "node:fs";
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

  it("non rende un contenitore con controlli un unico elemento accessibile", () => {
    // `accessible` turns a View into one element: on iOS VoiceOver can no longer reach the
    // fields and buttons inside it. Only summaries without controls may be grouped.
    const root = path.resolve(import.meta.dirname, "..");
    const sources = ["app", "components"].flatMap(folder => tsxFiles(path.join(root, folder)));
    const offending = sources.flatMap(file => {
      const source = readFileSync(file, "utf8");
      return [...source.matchAll(/<View accessible\b/g)].flatMap(match => {
        const body = viewElement(source, match.index ?? 0);
        return /<(Pressable|TextInput|Switch|DateTimePicker|StationAutocompleteInput)\b/.test(body)
          ? [`${path.relative(root, file)}:${source.slice(0, match.index).split("\n").length}`]
          : [];
      });
    });
    expect(sources.length).toBeGreaterThan(5);
    expect(offending).toEqual([]);
  });
});

function tsxFiles(folder: string): string[] {
  return readdirSync(folder, { withFileTypes: true }).flatMap(entry => {
    const full = path.join(folder, entry.name);
    if (entry.isDirectory()) return tsxFiles(full);
    return entry.name.endsWith(".tsx") ? [full] : [];
  });
}

/** The View element that starts at `start`, up to its matching closing tag. */
function viewElement(source: string, start: number): string {
  const tags = /<View\b[^>]*?(\/?)>|<\/View>/g;
  tags.lastIndex = start;
  let depth = 0;
  for (let tag = tags.exec(source); tag; tag = tags.exec(source)) {
    if (tag[0].startsWith("</")) depth -= 1;
    else if (tag[1] !== "/") depth += 1;
    if (depth === 0) return source.slice(start, tags.lastIndex);
  }
  return source.slice(start);
}
