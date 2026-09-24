import type { CompanionSnapshot } from "@/lib/companion-snapshot";
import { buildCompanionWearPayloadV1, type CompanionWearPayloadV1 } from "@/lib/companion-wear-contract";
import { formatLocalJourneySchedule } from "@/lib/local-date-time";
import { localRouteScheduledAtUtcMs, type LocalPlannedRoute } from "@/lib/local-route-contract";

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

/** L'oggetto piatto che il bridge inserisce, senza envelope o chiavi private, nella DataMap Wear. */
export type NativeWatchPayload = CompanionWearPayloadV1;

export type NativeSurfacePayload = { widget: NativeWidgetPayload; watch: NativeWatchPayload };

function unavailableWidgetPayload(): NativeWidgetPayload {
  return { title: "Binario", subtitle: nativeSurfaceUnavailableText, status: "unavailable", updatedAtUtcMs: null, expiresAtUtcMs: null, plannedJourney: null };
}

/**
 * Costruisce le superfici locali. Il watch riceve sempre il contratto v1 completo e bounded;
 * la sua disponibilità rimane subordinata alla pianificazione locale dell'app Companion.
 */
export function buildNativeSurfacePayload(snapshot: CompanionSnapshot, route: LocalPlannedRoute | null, now = Date.now()): NativeSurfacePayload {
  const watch = buildCompanionWearPayloadV1(snapshot, route, now);
  const expiresAtUtcMs = snapshot.provenance.expiresAtUtcMs;
  const scheduledAtUtcMs = route ? localRouteScheduledAtUtcMs(route) : null;
  if (!route || snapshot.provenance.dataNature !== "scheduled" || expiresAtUtcMs === null || expiresAtUtcMs <= now || scheduledAtUtcMs === null || scheduledAtUtcMs <= now) return { widget: unavailableWidgetPayload(), watch };
  const schedule = `${formatLocalJourneySchedule(route.travelDate, route.travelTime)} · Programmato localmente, non realtime`;
  return {
    widget: {
      title: route.name,
      subtitle: schedule,
      status: "available",
      updatedAtUtcMs: snapshot.provenance.capturedAtUtcMs,
      expiresAtUtcMs,
      plannedJourney: { title: route.name, scheduledAtUtcMs, reminderMinutes: route.plannedReminderMinutes },
    },
    watch,
  };
}
