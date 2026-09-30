import type { LocalPlannedRoute } from "@/lib/local-route-contract";
import { formatLocalJourneySchedule } from "@/lib/local-date-time";
import { localCountdownLabel, selectNextLocalPlannedRoute } from "@/lib/local-route-contract";
import { createTranslator, type CompanionLocale } from "@/lib/i18n";
export type CompanionHubSurface = { id: "widget" | "watch" | "alexa"; title: string; description: string; status: string };
export function buildCompanionHub(routes: LocalPlannedRoute[], locale?: CompanionLocale) { const t = createTranslator(locale); const next = selectNextLocalPlannedRoute(routes); return { surfaces: [{ id: "widget", title: "Widget", description: t("hub.widget.description"), status: t("hub.scheduledStatus") }, { id: "watch", title: "Wear OS", description: t("hub.watch.description"), status: t("hub.scheduledStatus") }, { id: "alexa", title: "Alexa", description: t("hub.alexa.description"), status: t("hub.alexaStatus") }] satisfies CompanionHubSurface[], nextJourney: next ? { name: next.name, schedule: formatLocalJourneySchedule(next.travelDate, next.travelTime, locale), countdown: localCountdownLabel(next, Date.now(), locale) } : null }; }
