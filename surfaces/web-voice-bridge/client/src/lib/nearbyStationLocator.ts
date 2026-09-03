import { loadCanonicalStationCatalog } from "@/lib/stationCatalogClient";
import { findNearbyCanonicalStations, type NearbyStation } from "@shared/nearbyStations";

export type NearbyStationLookupFailure = "denied" | "unavailable";

export async function locateNearbyCanonicalStations(): Promise<NearbyStation[]> {
  if (typeof navigator === "undefined" || !navigator.geolocation) throw new Error("unavailable" satisfies NearbyStationLookupFailure);
  const position = await new Promise<GeolocationPosition>((resolve, reject) => navigator.geolocation.getCurrentPosition(resolve, reject, { enableHighAccuracy: false, maximumAge: 300_000, timeout: 10_000 }));
  const catalog = await loadCanonicalStationCatalog();
  return findNearbyCanonicalStations(catalog, position.coords.latitude, position.coords.longitude);
}

export function nearbyStationLookupFailure(error: unknown): NearbyStationLookupFailure {
  if (typeof GeolocationPositionError !== "undefined" && error instanceof GeolocationPositionError && error.code === error.PERMISSION_DENIED) return "denied";
  if (typeof error === "object" && error !== null && "code" in error && error.code === 1) return "denied";
  return "unavailable";
}
