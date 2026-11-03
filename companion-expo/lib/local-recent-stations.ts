import AsyncStorage from "@react-native-async-storage/async-storage";

export type LocalRecentStation = { code: string; name: string; usedAtUtcMs: number };
const storageKey = "binario.local-recent-stations.v1";
export async function loadLocalRecentStations(): Promise<LocalRecentStation[]> { try { const raw: unknown = JSON.parse((await AsyncStorage.getItem(storageKey)) ?? "[]"); return Array.isArray(raw) ? raw.filter((item): item is LocalRecentStation => Boolean(item) && typeof item.code === "string" && typeof item.name === "string" && Number.isSafeInteger(item.usedAtUtcMs)).slice(0, 8) : []; } catch { return []; } }
export function recordLocalRecentStation(current: LocalRecentStation[], station: Pick<LocalRecentStation, "code" | "name">, now = Date.now()) { if (!station.code.trim() || !station.name.trim()) return current; return [{ code: station.code.trim(), name: station.name.trim(), usedAtUtcMs: now }, ...current.filter(item => item.code !== station.code)].slice(0, 8); }
export async function saveLocalRecentStations(stations: LocalRecentStation[]) { await AsyncStorage.setItem(storageKey, JSON.stringify(stations.slice(0, 8))); }
