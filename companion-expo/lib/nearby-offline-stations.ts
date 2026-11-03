import * as Location from "expo-location";
import { findNearbyOfflineStations } from "@/lib/offline-station-index";

export type NearbyOfflineStationResult = { state: "ready"; station: ReturnType<typeof findNearbyOfflineStations>[number] } | { state: "denied" | "empty" | "unavailable" };
export async function requestNearestOfflineStation(): Promise<NearbyOfflineStationResult> {
  const permission = await Location.requestForegroundPermissionsAsync();
  if (permission.status !== "granted") return { state: "denied" };
  try {
    const position = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
    const station = findNearbyOfflineStations(position.coords.latitude, position.coords.longitude, 1)[0];
    return station ? { state: "ready", station } : { state: "empty" };
  } catch { return { state: "unavailable" }; }
}
