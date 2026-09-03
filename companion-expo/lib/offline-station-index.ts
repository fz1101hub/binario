import dataset from "@/assets/data/stazioni-canoniche-v2.json";

export type OfflineStation = { id: string; name: string; code: string; aliases: string[]; latitude?: number | null; longitude?: number | null };
type OfflineStationDataset = { schemaVersion: number; sourceVersion: string; checksumSha256: string; stationCount: number; stations: OfflineStation[] };

function normalize(value: string) {
  return value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLocaleLowerCase("it").replace(/[^a-z0-9]+/g, " ").trim();
}

function isValidDataset(value: unknown): value is OfflineStationDataset {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<OfflineStationDataset>;
  return candidate.schemaVersion === 1 && typeof candidate.sourceVersion === "string" && /^[a-f0-9]{64}$/.test(candidate.checksumSha256 ?? "") && Array.isArray(candidate.stations) && candidate.stationCount === candidate.stations.length && candidate.stations.every(station => Boolean(station?.id && station.name && station.code && Array.isArray(station.aliases)));
}

const catalog = isValidDataset(dataset) ? dataset : { schemaVersion: 1, sourceVersion: "invalid", checksumSha256: "", stationCount: 0, stations: [] };

export const offlineStationCatalog = Object.freeze({ version: catalog.sourceVersion, checksumSha256: catalog.checksumSha256, stationCount: catalog.stationCount, stations: Object.freeze(catalog.stations) });

function score(station: OfflineStation, query: string) {
  const name = normalize(station.name);
  const code = normalize(station.code);
  const aliases = station.aliases.map(normalize);
  if (name === query || code === query) return 0;
  if (name.startsWith(query) || code.startsWith(query)) return 1;
  if (aliases.some(alias => alias === query || alias.startsWith(query))) return 2;
  if (name.includes(query) || aliases.some(alias => alias.includes(query))) return 3;
  return Number.POSITIVE_INFINITY;
}

export function searchOfflineStations(stations: readonly OfflineStation[], rawQuery: string, limit = 6) {
  const query = normalize(rawQuery);
  if (query.length < 2 || limit < 1) return [];
  return stations.map(station => ({ station, score: score(station, query) })).filter(result => Number.isFinite(result.score)).sort((left, right) => left.score - right.score || left.station.name.localeCompare(right.station.name, "it")).slice(0, limit).map(result => result.station);
}

export function searchCanonicalOfflineStations(query: string, limit = 6) { return searchOfflineStations(offlineStationCatalog.stations, query, limit); }

/** Restituisce soltanto una stazione realmente presente nel catalogo offline canonico. */
export function findCanonicalOfflineStationByCode(rawCode: string) {
  const code = normalize(rawCode);
  return code ? offlineStationCatalog.stations.find(station => normalize(station.code) === code) ?? null : null;
}

export type NearbyOfflineStation = { station: OfflineStation; distanceKm: number };

function distanceKm(latitudeA: number, longitudeA: number, latitudeB: number, longitudeB: number) {
  const radians = (value: number) => value * Math.PI / 180;
  const deltaLatitude = radians(latitudeB - latitudeA);
  const deltaLongitude = radians(longitudeB - longitudeA);
  const a = Math.sin(deltaLatitude / 2) ** 2 + Math.cos(radians(latitudeA)) * Math.cos(radians(latitudeB)) * Math.sin(deltaLongitude / 2) ** 2;
  return 6_371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

export function findNearbyOfflineStations(latitude: number, longitude: number, limit = 3): NearbyOfflineStation[] {
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || limit < 1) return [];
  return offlineStationCatalog.stations.flatMap(station => {
    if (!Number.isFinite(station.latitude) || !Number.isFinite(station.longitude)) return [];
    return [{ station, distanceKm: distanceKm(latitude, longitude, station.latitude!, station.longitude!) }];
  }).sort((left, right) => left.distanceKm - right.distanceKm || left.station.name.localeCompare(right.station.name, "it")).slice(0, limit);
}
