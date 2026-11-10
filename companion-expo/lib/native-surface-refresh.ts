import { createLocalScheduledSnapshot, createUnavailableSnapshot, type CompanionSnapshot } from "@/lib/companion-snapshot";
import { createTranslator, type CompanionLocale } from "@/lib/i18n";
import { selectNextLocalPlannedRoute, type LocalPlannedRoute } from "@/lib/local-route-contract";
import { loadLocalPlannedRoutes } from "@/lib/local-routes";
import { syncNativeCompanionSurfaces } from "@/lib/native-surface-bridge";
import { listScheduledRouteIds } from "@/lib/planned-reminder-service";

export type NativeSurfaceState = { snapshot: CompanionSnapshot; route: LocalPlannedRoute | null; reminderScheduled: boolean };

/** What the widget and the watch must show for the saved journeys: the next one, or explicitly nothing. */
export function nativeSurfaceStateFor(routes: LocalPlannedRoute[], scheduledRouteIds: readonly number[], now = Date.now(), locale?: CompanionLocale): NativeSurfaceState {
  const route = selectNextLocalPlannedRoute(routes, now);
  if (!route) return { snapshot: createUnavailableSnapshot({ kind: "route", id: "active-route", label: createTranslator(locale)("home.noActiveRoute") }, locale), route: null, reminderScheduled: false };
  return { snapshot: createLocalScheduledSnapshot(route, now, locale), route, reminderScheduled: scheduledRouteIds.includes(route.id) };
}

/**
 * Pushes the current journeys to the widget and the Wear tile. Called after every change made outside
 * Home (delete, complete, reminder, clear, import), so a deleted or completed journey does not stay on
 * the home screen or the watch after the user leaves the app.
 */
export async function refreshNativeCompanionSurfaces(locale?: CompanionLocale, now = Date.now()) {
  try {
    const routes = await loadLocalPlannedRoutes();
    const scheduled = await listScheduledRouteIds().catch(() => [] as number[]);
    const state = nativeSurfaceStateFor(routes, scheduled, now, locale);
    syncNativeCompanionSurfaces(state.snapshot, state.route, state.reminderScheduled);
  } catch {
    // The Companion stays usable without native surfaces.
  }
}
