import { isStationCode } from "./stats";

/**
 * Station names of the curated catalogue the Android app embeds
 * (`app/src/main/assets/stations/curated_it_rail_stations.json`), by ViaggiaTreno code: the
 * statistics name stations only by code, the dashboard routes only by the names the user typed.
 *
 * A name matches when it equals, after normalisation, a catalogue name or alias of a station with
 * a ViaggiaTreno code ("S08409"); nothing is guessed from a partial name, so «Milano» matches no
 * station while «Milano Centrale» does. A name shared by several codes (an alias such as
 * «Bologne») yields all of them. The timetable reads the same names (`stationsFor`), with every
 * station of the catalogue, coded or not.
 */

const MAX_STATIONS = 50_000;
const MAX_NAME_LENGTH = 200;

/** Lower case, without accents, punctuation as spaces: «Città S. Angelo» → «citta s angelo». */
export function normalizeStationName(value: string): string {
  return value
    .normalize("NFKD")
    .replace(/\p{M}/gu, "")
    .toLocaleLowerCase("it")
    .replace(/[\p{P}\p{S}]+/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/** A catalogue station as the timetable index binds its stops: its id, ViaggiaTreno code (if any) and name. */
export type CatalogStation = { id: string; railwayCode: string | null; name: string };

export class StationIndex {
  private readonly namesById = new Map<string, string>();

  constructor(
    private readonly codesByName: ReadonlyMap<string, readonly string[]>,
    private readonly stationsByName: ReadonlyMap<string, readonly CatalogStation[]> = new Map()
  ) {
    for (const stations of stationsByName.values()) for (const station of stations) this.namesById.set(station.id, station.name);
  }

  /** The catalogue name of a station id («S08409» is «Roma Termini»). */
  nameOf(id: string | null): string | undefined {
    return id === null ? undefined : this.namesById.get(id);
  }

  get size() {
    return this.codesByName.size;
  }

  /** ViaggiaTreno codes of the station with this name, or none. */
  codesFor(name: string): readonly string[] {
    return this.codesByName.get(normalizeStationName(name)) ?? [];
  }

  /**
   * The catalogue stations with this name or alias, with or without a ViaggiaTreno code (the
   * timetable index also binds stations without one), matched exactly as `codesFor`.
   */
  stationsFor(name: string): readonly CatalogStation[] {
    return this.stationsByName.get(normalizeStationName(name)) ?? [];
  }
}

/** Reads the catalogue JSON; null when it is not a catalogue or names no station with a code. */
export function parseStationCatalog(text: string): StationIndex | null {
  let document: unknown;
  try {
    document = JSON.parse(text);
  } catch {
    return null;
  }
  const stations = (document as { stations?: unknown } | null)?.stations;
  if (!Array.isArray(stations) || stations.length > MAX_STATIONS) return null;
  const codesByName = new Map<string, string[]>();
  const stationsByName = new Map<string, CatalogStation[]>();
  for (const station of stations) {
    if (typeof station !== "object" || station === null) continue;
    const { id, name, aliases, railwayCode } = station as { id?: unknown; name?: unknown; aliases?: unknown; railwayCode?: unknown };
    const trimmed = typeof railwayCode === "string" ? railwayCode.trim() : undefined;
    const code = isStationCode(trimmed) ? trimmed : undefined;
    const entry =
      typeof id === "string" && id.trim() && id.length <= MAX_NAME_LENGTH && typeof name === "string" && name.trim() && name.length <= MAX_NAME_LENGTH
        ? { id: id.trim(), railwayCode: code ?? null, name: name.trim() }
        : undefined;
    if (!code && !entry) continue;
    const names = [name, ...(Array.isArray(aliases) ? aliases : [])];
    for (const candidate of names) {
      if (typeof candidate !== "string" || candidate.length > MAX_NAME_LENGTH) continue;
      const key = normalizeStationName(candidate);
      if (!key) continue;
      if (code) {
        const codes = codesByName.get(key);
        if (!codes) codesByName.set(key, [code]);
        else if (!codes.includes(code)) codes.push(code);
      }
      if (entry) {
        const known = stationsByName.get(key);
        if (!known) stationsByName.set(key, [entry]);
        else if (!known.some(item => item.id === entry.id)) known.push(entry);
      }
    }
  }
  return codesByName.size ? new StationIndex(codesByName, stationsByName) : null;
}
