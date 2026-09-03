import type { LocalGuestRoute } from "./localGuestRoutes";

export type PersonalRouteFingerprint = {
  origin: string;
  originStationCode: string | null;
  destination: string;
  destinationStationCode: string | null;
  travelDate: string | null;
  travelTime: string | null;
};

export type GuestRouteImportDraft = {
  origin: string;
  originStationCode: string | null;
  destination: string;
  destinationStationCode: string | null;
  name: string;
  travelDate: string;
  travelTime: string;
};

function fingerprint(route: PersonalRouteFingerprint) {
  return [route.originStationCode ?? route.origin.trim().toLocaleLowerCase("it"), route.destinationStationCode ?? route.destination.trim().toLocaleLowerCase("it"), route.travelDate ?? "", route.travelTime ?? ""].join("|");
}

export function prepareGuestRouteImport(localRoutes: readonly LocalGuestRoute[], personalRoutes: readonly PersonalRouteFingerprint[]): GuestRouteImportDraft[] {
  const known = new Set(personalRoutes.map(fingerprint));
  return localRoutes.flatMap(route => {
    const key = fingerprint(route);
    if (known.has(key)) return [];
    known.add(key);
    return [{ origin: route.origin, originStationCode: route.originStationCode, destination: route.destination, destinationStationCode: route.destinationStationCode, name: `${route.origin} → ${route.destination}`, travelDate: route.travelDate, travelTime: route.travelTime }];
  });
}
