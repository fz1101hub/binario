import type { CompanionSnapshot } from "@/lib/companion-snapshot";
import { formatLocalJourneySchedule } from "@/lib/local-date-time";
import { localRouteScheduledAtUtcMs, remainingLocalJourneyChecklistItems, type LocalPlannedRoute } from "@/lib/local-route-contract";

export const nativeSurfaceUnavailableText = "Dati non disponibili in tempo reale.";

/** Campi letti da BinarioWidgetBridgeModule.update() → BinarioWidgetStore (widget Glance). */
export type NativeWidgetPayload = {
  title: string;
  subtitle: string;
  status: "available" | "unavailable";
  updatedAtUtcMs: number | null;
  expiresAtUtcMs: number | null;
  plannedJourney: { title: string; scheduledAtUtcMs: number; reminderMinutes: number | null } | null;
};

/** Campi letti da BinarioWidgetBridgeModule.syncWearPayload() → DataMap per il Tile Wear OS. */
export type NativeWatchPayload = {
  title: string;
  status: string;
  canComplicate: boolean;
  plannedChecklistRemaining: number;
  updatedAtUtcMs: number | null;
  expiresAtUtcMs: number | null;
};

export type NativeSurfacePayload = { widget: NativeWidgetPayload; watch: NativeWatchPayload };

const unavailablePayload: NativeSurfacePayload = {
  widget: { title: "Binario", subtitle: nativeSurfaceUnavailableText, status: "unavailable", updatedAtUtcMs: null, expiresAtUtcMs: null, plannedJourney: null },
  watch: { title: "Binario", status: nativeSurfaceUnavailableText, canComplicate: false, plannedChecklistRemaining: 0, updatedAtUtcMs: null, expiresAtUtcMs: null },
};

/**
 * Costruisce esattamente il payload che il template Kotlin analizza. Lo store nativo accetta solo
 * status "available" con updatedAt ≤ adesso < expiresAt: senza tratta futura, o con snapshot non
 * disponibile o scaduto, widget e watch ricevono un payload "non disponibile" che sovrascrive il precedente.
 */
export function buildNativeSurfacePayload(snapshot: CompanionSnapshot, route: LocalPlannedRoute | null, now = Date.now()): NativeSurfacePayload {
  const expiresAtUtcMs = snapshot.provenance.expiresAtUtcMs;
  const scheduledAtUtcMs = route ? localRouteScheduledAtUtcMs(route) : null;
  if (!route || snapshot.provenance.dataNature !== "scheduled" || expiresAtUtcMs === null || expiresAtUtcMs <= now || scheduledAtUtcMs === null || scheduledAtUtcMs <= now) return unavailablePayload;
  const schedule = `${formatLocalJourneySchedule(route.travelDate, route.travelTime)} · Programmato localmente, non realtime`;
  const updatedAtUtcMs = snapshot.provenance.capturedAtUtcMs;
  return {
    widget: { title: route.name, subtitle: schedule, status: "available", updatedAtUtcMs, expiresAtUtcMs, plannedJourney: { title: route.name, scheduledAtUtcMs, reminderMinutes: route.plannedReminderMinutes } },
    watch: { title: route.name, status: schedule, canComplicate: true, plannedChecklistRemaining: remainingLocalJourneyChecklistItems(route), updatedAtUtcMs, expiresAtUtcMs },
  };
}
