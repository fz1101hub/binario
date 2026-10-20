import AsyncStorage from "@react-native-async-storage/async-storage";

export const LOCAL_FAVORITE_STATIONS_KEY = "binario-local-favorite-stations-v1";
export const MAX_LOCAL_FAVORITE_STATIONS = 12;

export type LocalFavoriteStation = { code: string; name: string; createdAtUtcMs: number };

function normalize(value: unknown): LocalFavoriteStation | null {
  if (!value || typeof value !== "object") return null;
  const item = value as Partial<LocalFavoriteStation>;
  const code = typeof item.code === "string" ? item.code.trim().slice(0, 64) : "";
  const name = typeof item.name === "string" ? item.name.trim().replace(/\s+/g, " ").slice(0, 96).trim() : "";
  return code && name && Number.isSafeInteger(item.createdAtUtcMs) && (item.createdAtUtcMs ?? 0) >= 0 ? { code, name, createdAtUtcMs: item.createdAtUtcMs! } : null;
}

export function parseLocalFavoriteStations(raw: string | null) {
  try { const parsed: unknown = JSON.parse(raw ?? "[]"); return Array.isArray(parsed) ? parsed.map(normalize).filter((item): item is LocalFavoriteStation => item !== null).filter((item, index, entries) => entries.findIndex(candidate => candidate.code === item.code) === index).slice(0, MAX_LOCAL_FAVORITE_STATIONS) : []; } catch { return []; }
}

export function toggleLocalFavoriteStation(entries: readonly LocalFavoriteStation[], station: Omit<LocalFavoriteStation, "createdAtUtcMs">, now = Date.now()) {
  const normalized = normalize({ ...station, createdAtUtcMs: now });
  if (!normalized) return [...entries];
  return entries.some(item => item.code === normalized.code) ? entries.filter(item => item.code !== normalized.code) : [normalized, ...entries].slice(0, MAX_LOCAL_FAVORITE_STATIONS);
}

export async function loadLocalFavoriteStations() { return parseLocalFavoriteStations(await AsyncStorage.getItem(LOCAL_FAVORITE_STATIONS_KEY)); }
export async function saveLocalFavoriteStations(entries: readonly LocalFavoriteStation[]) { const safe = entries.map(normalize).filter((item): item is LocalFavoriteStation => item !== null).slice(0, MAX_LOCAL_FAVORITE_STATIONS); await AsyncStorage.setItem(LOCAL_FAVORITE_STATIONS_KEY, JSON.stringify(safe)); return safe; }
