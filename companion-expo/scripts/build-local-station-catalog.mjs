import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const localStationCatalogSourceVersion = "curated-it-rail-stations-v4";

export function normalizeLocalStations(raw) {
  if (!raw || raw.schemaVersion !== 1 || !Array.isArray(raw.stations)) {
    throw new Error("Catalogo stazioni sorgente non valido");
  }
  return raw.stations.flatMap((station) => {
    const id = typeof station?.id === "string" ? station.id.trim() : "";
    const name = typeof station?.name === "string" ? station.name.trim() : "";
    const code = typeof station?.railwayCode === "string" && station.railwayCode.trim() ? station.railwayCode.trim() : id;
    if (!id || !name || !code) return [];
    return [{
      id,
      name,
      code,
      aliases: Array.isArray(station.aliases) ? station.aliases.filter((alias) => typeof alias === "string").map((alias) => alias.trim()).filter(Boolean) : [],
      latitude: Number.isFinite(station.latitude) ? station.latitude : null,
      longitude: Number.isFinite(station.longitude) ? station.longitude : null,
    }];
  });
}

/** SHA-256 della serializzazione canonica delle stazioni normalizzate: cambia se e solo se cambiano i dati, mai scritto a mano. */
export function computeLocalStationCatalogChecksum(stations) {
  return createHash("sha256").update(JSON.stringify(stations)).digest("hex");
}

export function buildLocalStationCatalog(raw) {
  const stations = normalizeLocalStations(raw);
  return {
    schemaVersion: 1,
    sourceVersion: localStationCatalogSourceVersion,
    checksumSha256: computeLocalStationCatalogChecksum(stations),
    stationCount: stations.length,
    stations,
  };
}

const invokedDirectly = Boolean(process.argv[1]) && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (invokedDirectly) {
  const [inputPath, outputPath] = process.argv.slice(2);
  if (!inputPath || !outputPath) {
    throw new Error("Uso: node scripts/build-local-station-catalog.mjs <input-json> <output-json>");
  }
  const output = buildLocalStationCatalog(JSON.parse(await readFile(inputPath, "utf8")));
  await writeFile(outputPath, JSON.stringify(output));
}
