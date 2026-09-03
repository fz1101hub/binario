import type { LocalGuestRoute } from "./localGuestRoutes";
import type { GuestRouteImportDraft, PersonalRouteFingerprint } from "./localGuestRouteMigration";

export type RouteSyncAccountRoute = PersonalRouteFingerprint & { id: number; name?: string | null };
export type RouteSyncCandidateState = "new" | "already_in_account" | "conflict";
export type RouteSyncCandidate = {
  localId: string;
  route: GuestRouteImportDraft;
  state: RouteSyncCandidateState;
  conflictingAccountRouteId?: number;
};

function normalizedEndpoint(stationCode: string | null, name: string): string {
  return stationCode?.trim().toUpperCase() || name.trim().toLocaleLowerCase("it");
}

function routeIdentity(route: PersonalRouteFingerprint): string {
  return [normalizedEndpoint(route.originStationCode, route.origin), normalizedEndpoint(route.destinationStationCode, route.destination), route.travelDate ?? "", route.travelTime ?? ""].join("|");
}

function routeImportDraft(route: LocalGuestRoute): GuestRouteImportDraft {
  return {
    origin: route.origin,
    originStationCode: route.originStationCode,
    destination: route.destination,
    destinationStationCode: route.destinationStationCode,
    name: `${route.origin} → ${route.destination}`,
    travelDate: route.travelDate,
    travelTime: route.travelTime,
  };
}

/**
 * La preview non invia né modifica dati. Una corrispondenza con stessa tratta,
 * data e ora non viene duplicata. Stesso testo con codici canonici discordanti
 * è un conflitto: l’account resta invariato finché l’utente non lo risolve.
 */
export function prepareOptInRouteSyncPreview(localRoutes: readonly LocalGuestRoute[], accountRoutes: readonly RouteSyncAccountRoute[]): RouteSyncCandidate[] {
  const accountByIdentity = new Map(accountRoutes.map(route => [routeIdentity(route), route]));
  const accountByNames = new Map(accountRoutes.map(route => [routeIdentity({ ...route, originStationCode: null, destinationStationCode: null }), route]));
  const seenLocalIdentities = new Set<string>();
  return localRoutes.flatMap<RouteSyncCandidate>(localRoute => {
    const route = routeImportDraft(localRoute);
    const identity = routeIdentity(route);
    if (seenLocalIdentities.has(identity)) return [];
    seenLocalIdentities.add(identity);
    const exactAccountRoute = accountByIdentity.get(identity);
    if (exactAccountRoute) return [{ localId: localRoute.id, route, state: "already_in_account" as const, conflictingAccountRouteId: exactAccountRoute.id }];
    const namedAccountRoute = accountByNames.get(routeIdentity({ ...route, originStationCode: null, destinationStationCode: null }));
    if (namedAccountRoute) return [{ localId: localRoute.id, route, state: "conflict" as const, conflictingAccountRouteId: namedAccountRoute.id }];
    return [{ localId: localRoute.id, route, state: "new" as const }];
  });
}

export function selectedOptInRouteSyncDrafts(candidates: readonly RouteSyncCandidate[], selectedLocalIds: ReadonlySet<string>): GuestRouteImportDraft[] {
  return candidates.filter(candidate => candidate.state === "new" && selectedLocalIds.has(candidate.localId)).map(candidate => candidate.route);
}
