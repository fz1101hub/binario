import type { CanonicalStation } from "./stationCatalog";

export const LOCAL_FAVORITE_STATIONS_STORAGE_KEY = "binario-local-favorite-stations-v1";
export const MAX_LOCAL_FAVORITE_STATIONS = 12;

export type LocalFavoriteStation = Pick<CanonicalStation, "stationId" | "name" | "code">;

function isFavoriteStation(value: unknown): value is LocalFavoriteStation {
  if (!value || typeof value !== "object") return false;
  const station = value as Record<string, unknown>;
  return [station.stationId, station.name, station.code].every(item => typeof item === "string" && item.trim().length > 0);
}

export function parseLocalFavoriteStations(raw: string | null | undefined): LocalFavoriteStation[] {
  try {
    const parsed: unknown = JSON.parse(raw ?? "[]");
    if (!Array.isArray(parsed)) return [];
    const seen = new Set<string>();
    const favorites: LocalFavoriteStation[] = [];
    for (const candidate of parsed) {
      if (!isFavoriteStation(candidate)) continue;
      const code = candidate.code.trim();
      if (seen.has(code)) continue;
      seen.add(code);
      favorites.push({ stationId: candidate.stationId.trim(), name: candidate.name.trim(), code });
      if (favorites.length === MAX_LOCAL_FAVORITE_STATIONS) break;
    }
    return favorites;
  } catch { return []; }
}

export function toggleLocalFavoriteStation(current: LocalFavoriteStation[], station: CanonicalStation): LocalFavoriteStation[] {
  const code = station.code.trim();
  if (!code) return current;
  if (current.some(favorite => favorite.code === code)) return current.filter(favorite => favorite.code !== code);
  return [{ stationId: station.stationId, name: station.name, code }, ...current].slice(0, MAX_LOCAL_FAVORITE_STATIONS);
}

export function isLocalFavoriteStation(favorites: LocalFavoriteStation[], station: CanonicalStation | null): boolean {
  return Boolean(station && favorites.some(favorite => favorite.code === station.code));
}

export function favoriteStationToCanonical(station: LocalFavoriteStation): CanonicalStation {
  return { stationId: station.stationId, name: station.name, code: station.code, latitude: null, longitude: null, aliases: [], confidence: "Preferito locale", trenitaliaLocationId: null };
}
