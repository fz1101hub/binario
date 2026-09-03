import { readFile, writeFile } from "node:fs/promises";

const [inputPath, outputPath] = process.argv.slice(2);

if (!inputPath || !outputPath) {
  throw new Error("Uso: node scripts/build-local-station-catalog.mjs <input-json> <output-json>");
}

const raw = JSON.parse(await readFile(inputPath, "utf8"));
if (!raw || raw.schemaVersion !== 1 || !Array.isArray(raw.stations)) {
  throw new Error("Catalogo stazioni sorgente non valido");
}

const stations = raw.stations.flatMap((station) => {
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

const output = {
  schemaVersion: 1,
  sourceVersion: "curated-it-rail-stations-v4",
  checksumSha256: "58b441886c5fe8b731f69000c010c860b28424158fcab4ef1124434874b5712a",
  stationCount: stations.length,
  stations,
};

await writeFile(outputPath, JSON.stringify(output));
