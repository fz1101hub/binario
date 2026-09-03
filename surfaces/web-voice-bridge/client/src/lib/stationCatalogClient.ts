import { parseCanonicalStationCsv, type CanonicalStation } from "@shared/stationCatalog";

const STATION_CATALOG_URL = "/manus-storage/lista_stazioni_consolidata_v2_trenitalia_d7e0398e.csv";
const MAX_CATALOG_ATTEMPTS = 2;

let stationCatalogPromise: Promise<CanonicalStation[]> | undefined;

async function fetchStationCatalog() {
  let lastError: unknown;
  for (let attempt = 0; attempt < MAX_CATALOG_ATTEMPTS; attempt += 1) {
    try {
      const response = await fetch(STATION_CATALOG_URL, { cache: "force-cache" });
      if (!response.ok) throw new Error(`Catalogo stazioni non disponibile (${response.status})`);
      const catalog = parseCanonicalStationCsv(await response.text());
      if (catalog.length === 0) throw new Error("Il catalogo stazioni non contiene record validi.");
      return catalog;
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError instanceof Error ? lastError : new Error("Catalogo stazioni non disponibile");
}

export function loadCanonicalStationCatalog() {
  if (stationCatalogPromise) return stationCatalogPromise;
  const pending = fetchStationCatalog();
  stationCatalogPromise = pending;
  void pending.catch(() => { if (stationCatalogPromise === pending) stationCatalogPromise = undefined; });
  return stationCatalogPromise;
}

/** Solo per test: il runtime non espone controlli della cache all’utente. */
export function resetCanonicalStationCatalogForTests() { stationCatalogPromise = undefined; }
