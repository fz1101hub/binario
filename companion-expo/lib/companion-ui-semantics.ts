import type { CompanionSnapshot } from "@/lib/companion-snapshot";

/**
 * Etichette pronunciate dalle superfici effettivamente presenti nel Companion.
 * Il contratto non descrive pairing o revoca: non esistono controlli UI per tali
 * funzioni in questa applicazione locale-first.
 */
export const companionHubActions = {
  planJourney: {
    accessibilityRole: "button" as const,
    accessibilityLabel: "Pianifica una tratta",
    accessibilityHint: "Apre la schermata locale per aggiungere una partenza e un arrivo.",
  },
  manageReminders: {
    accessibilityRole: "button" as const,
    accessibilityLabel: "Gestisci promemoria",
    accessibilityHint: "Rivedi o elimina promemoria locali, sempre non realtime.",
  },
};

export function railwayStatusSemantics(provenance: Pick<CompanionSnapshot["provenance"], "dataNature">) {
  const unavailable = provenance.dataNature === "unavailable";
  return {
    accessibilityRole: "summary" as const,
    accessibilityLabel: unavailable
      ? "Stato ferroviario: dati realtime non disponibili. Binario non inventa ritardi, binari, cancellazioni o avvisi."
      : "Stato ferroviario: orario programmato locale, non realtime.",
  };
}

export function alertsRailwayStatusSemantics(simpleMode: boolean) {
  return {
    accessibilityRole: "summary" as const,
    accessibilityLabel: simpleMode
      ? "Stato ferroviario: dati realtime non disponibili."
      : "Stato ferroviario: nessun avviso realtime disponibile senza una fonte autorizzata.",
  };
}

export function companionSurfaceSemantics(title: string, status: string) {
  return {
    accessibilityRole: "summary" as const,
    accessibilityLabel: `${title}. ${status}`,
  };
}
