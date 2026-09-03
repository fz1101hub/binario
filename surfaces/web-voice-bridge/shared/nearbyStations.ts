import type { CanonicalStation } from "./stationCatalog";

export type NearbyStation = {
  station: CanonicalStation;
  distanceKm: number;
};

const EARTH_RADIUS_KM = 6371;

function toRadians(value: number) {
  return (value * Math.PI) / 180;
}

export function distanceBetweenCoordinatesKm(latitudeA: number, longitudeA: number, latitudeB: number, longitudeB: number) {
  const latitudeDelta = toRadians(latitudeB - latitudeA);
  const longitudeDelta = toRadians(longitudeB - longitudeA);
  const haversine = Math.sin(latitudeDelta / 2) ** 2 + Math.cos(toRadians(latitudeA)) * Math.cos(toRadians(latitudeB)) * Math.sin(longitudeDelta / 2) ** 2;
  return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
}

export function findNearbyCanonicalStations(stations: CanonicalStation[], latitude: number, longitude: number, limit = 4): NearbyStation[] {
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || limit < 1) return [];

  return stations
    .flatMap(station => station.latitude === null || station.longitude === null ? [] : [{
      station,
      distanceKm: distanceBetweenCoordinatesKm(latitude, longitude, station.latitude, station.longitude),
    }])
    .sort((left, right) => left.distanceKm - right.distanceKm || left.station.name.localeCompare(right.station.name, "it"))
    .slice(0, limit);
}
