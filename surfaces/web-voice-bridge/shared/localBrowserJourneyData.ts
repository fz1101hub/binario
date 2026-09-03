import { parseLocalGuestRoutes, LOCAL_GUEST_ROUTES_STORAGE_KEY } from "./localGuestRoutes";
import { LOCAL_JOURNEY_ITINERARIES_STORAGE_KEY, parseLocalJourneyItineraries } from "./localJourneyItineraries";
import { JOURNEY_ACCESSIBILITY_PREFERENCES_STORAGE_KEY } from "./localJourneyToolkit";

export type BrowserJourneyDataSummary = {
  localRoutes: number;
  localItineraries: number;
  hasJourneyPreferences: boolean;
};

export type BrowserJourneyDataRemoval = BrowserJourneyDataSummary & {
  cleared: boolean;
};

export function inspectBrowserJourneyData(storage: Pick<Storage, "getItem">): BrowserJourneyDataSummary {
  return {
    localRoutes: parseLocalGuestRoutes(storage.getItem(LOCAL_GUEST_ROUTES_STORAGE_KEY)).length,
    localItineraries: parseLocalJourneyItineraries(storage.getItem(LOCAL_JOURNEY_ITINERARIES_STORAGE_KEY)).length,
    hasJourneyPreferences: storage.getItem(JOURNEY_ACCESSIBILITY_PREFERENCES_STORAGE_KEY) !== null,
  };
}

/** Rimuove solo i dati di pianificazione locale del browser corrente. */
export function clearBrowserJourneyData(storage: Pick<Storage, "getItem" | "removeItem">): BrowserJourneyDataRemoval {
  const summary = inspectBrowserJourneyData(storage);
  storage.removeItem(LOCAL_GUEST_ROUTES_STORAGE_KEY);
  storage.removeItem(LOCAL_JOURNEY_ITINERARIES_STORAGE_KEY);
  storage.removeItem(JOURNEY_ACCESSIBILITY_PREFERENCES_STORAGE_KEY);
  return { ...summary, cleared: summary.localRoutes > 0 || summary.localItineraries > 0 || summary.hasJourneyPreferences };
}
