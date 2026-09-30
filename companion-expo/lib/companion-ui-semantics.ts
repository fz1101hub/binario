import type { CompanionSnapshot } from "@/lib/companion-snapshot";
import { createTranslator, type CompanionLocale } from "@/lib/i18n";

/**
 * Etichette pronunciate dalle superfici effettivamente presenti nel Companion.
 * Il contratto non descrive pairing o revoca: non esistono controlli UI per tali
 * funzioni in questa applicazione locale-first.
 */
export function getCompanionHubActions(locale?: CompanionLocale) {
  const t = createTranslator(locale);
  return {
  planJourney: {
    accessibilityRole: "button" as const,
    accessibilityLabel: t("hub.planJourney"),
    accessibilityHint: t("hub.planJourneyHint"),
  },
  manageReminders: {
    accessibilityRole: "button" as const,
    accessibilityLabel: t("hub.manageReminders"),
    accessibilityHint: t("hub.manageRemindersHint"),
  },
  };
}

/** Italian fallback retained for existing callers and deterministic contract checks. */
export const companionHubActions = getCompanionHubActions("it");

export function railwayStatusSemantics(provenance: Pick<CompanionSnapshot["provenance"], "dataNature">, locale?: CompanionLocale) {
  const t = createTranslator(locale);
  const unavailable = provenance.dataNature === "unavailable";
  return {
    accessibilityRole: "summary" as const,
    accessibilityLabel: unavailable
      ? t("semantics.railwayUnavailable")
      : t("semantics.railwayScheduled"),
  };
}

export function alertsRailwayStatusSemantics(simpleMode: boolean, locale?: CompanionLocale) {
  const t = createTranslator(locale);
  return {
    accessibilityRole: "summary" as const,
    accessibilityLabel: simpleMode
      ? t("semantics.alertsUnavailable")
      : t("semantics.alertsNoAlerts"),
  };
}

export function companionSurfaceSemantics(title: string, status: string) {
  return {
    accessibilityRole: "summary" as const,
    accessibilityLabel: `${title}. ${status}`,
  };
}
