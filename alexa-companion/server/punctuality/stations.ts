import { isStationCode } from "./stats";

/**
 * Station names of the curated catalogue the Android app embeds
 * (`app/src/main/assets/stations/curated_it_rail_stations.json`), by ViaggiaTreno code: the
 * statistics name stations only by code, the dashboard routes only by the names the user typed.
 *
 * A name matches when it equals, after normalisation, a catalogue name or alias of a station with
 * a ViaggiaTreno code ("S08409"); nothing is guessed from a partial name, so «Milano» matches no
 * station while «Milano Centrale» does. A name shared by several codes (an alias such as
 * «Bologne») yields all of them.
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

export class StationIndex {
  constructor(private readonly codesByName: ReadonlyMap<string, readonly string[]>) {}

  get size() {
    return this.codesByName.size;
  }

  /** ViaggiaTreno codes of the station with this name, or none. */
  codesFor(name: string): readonly string[] {
    return this.codesByName.get(normalizeStationName(name)) ?? [];
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
  for (const station of stations) {
    if (typeof station !== "object" || station === null) continue;
    const { name, aliases, railwayCode } = station as { name?: unknown; aliases?: unknown; railwayCode?: unknown };
    const code = typeof railwayCode === "string" ? railwayCode.trim() : undefined;
    if (!isStationCode(code)) continue;
    const names = [name, ...(Array.isArray(aliases) ? aliases : [])];
    for (const candidate of names) {
      if (typeof candidate !== "string" || candidate.length > MAX_NAME_LENGTH) continue;
      const key = normalizeStationName(candidate);
      if (!key) continue;
      const codes = codesByName.get(key);
      if (!codes) codesByName.set(key, [code]);
      else if (!codes.includes(code)) codes.push(code);
    }
  }
  return codesByName.size ? new StationIndex(codesByName) : null;
}
