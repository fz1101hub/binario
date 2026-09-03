import type { CanonicalStation } from "./stationCatalog";

export type CatalogQualityReport = {
  schemaVersion: 1;
  recordCount: number;
  uniqueCodes: number;
  duplicateCodes: string[];
  invalidCoordinates: string[];
  emptyAliases: string[];
  revision: string;
  valid: boolean;
};

function fingerprint(value: string) {
  let hash = 2166136261;
  for (let index = 0; index < value.length; index += 1) hash = Math.imul(hash ^ value.charCodeAt(index), 16777619);
  return (hash >>> 0).toString(16).padStart(8, "0");
}

export function validateCanonicalCatalog(stations: CanonicalStation[]): CatalogQualityReport {
  const seenCodes = new Set<string>();
  const duplicateCodes = new Set<string>();
  const invalidCoordinates: string[] = [];
  const emptyAliases: string[] = [];
  for (const station of stations) {
    const code = station.code.trim().toUpperCase();
    if (seenCodes.has(code)) duplicateCodes.add(code); else seenCodes.add(code);
    const latitudeValid = station.latitude === null || (station.latitude >= -90 && station.latitude <= 90);
    const longitudeValid = station.longitude === null || (station.longitude >= -180 && station.longitude <= 180);
    if (!latitudeValid || !longitudeValid) invalidCoordinates.push(code);
    if (station.aliases.some(alias => !alias.trim())) emptyAliases.push(code);
  }
  const canonicalFingerprint = stations.map(station => `${station.stationId}|${station.code}|${station.name}`).sort().join("\n");
  return { schemaVersion: 1, recordCount: stations.length, uniqueCodes: seenCodes.size, duplicateCodes: Array.from(duplicateCodes).sort(), invalidCoordinates: invalidCoordinates.sort(), emptyAliases: emptyAliases.sort(), revision: `fnv1a-${fingerprint(canonicalFingerprint)}`, valid: duplicateCodes.size === 0 && invalidCoordinates.length === 0 && emptyAliases.length === 0 };
}
