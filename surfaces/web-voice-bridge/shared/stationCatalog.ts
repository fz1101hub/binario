export type CanonicalStation = {
  stationId: string;
  name: string;
  code: string;
  latitude: number | null;
  longitude: number | null;
  aliases: string[];
  confidence: string;
  trenitaliaLocationId: string | null;
};

function parseCsv(csv: string) {
  const rows: string[][] = [];
  let fields: string[] = [];
  let value = "";
  let inQuotes = false;

  for (let index = 0; index < csv.length; index += 1) {
    const character = csv[index];
    if (character === '"') {
      if (inQuotes && csv[index + 1] === '"') {
        value += '"';
        index += 1;
      } else {
        inQuotes = !inQuotes;
      }
    } else if (character === "," && !inQuotes) {
      fields.push(value);
      value = "";
    } else if ((character === "\n" || character === "\r") && !inQuotes) {
      if (character === "\r" && csv[index + 1] === "\n") index += 1;
      fields.push(value);
      value = "";
      if (fields.some(field => field.length > 0)) rows.push(fields);
      fields = [];
    } else {
      value += character;
    }
  }

  fields.push(value);
  if (fields.some(field => field.length > 0)) rows.push(fields);
  return rows;
}

function normalizeForSearch(value: string) {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLocaleLowerCase("it")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function parseCoordinate(value: string | undefined) {
  const coordinate = Number(value?.trim());
  return Number.isFinite(coordinate) ? coordinate : null;
}

export function parseCanonicalStationCsv(csv: string): CanonicalStation[] {
  const [header = [], ...rows] = parseCsv(csv);
  const column = (name: string) => header.indexOf(name);
  const stationIdColumn = column("station_id");
  const stationNameColumn = column("station_name");
  const stationCodeColumn = column("station_code");
  const latitudeColumn = column("latitude");
  const longitudeColumn = column("longitude");
  const aliasesColumn = column("name_variants");
  const confidenceColumn = column("confidence");
  const trenitaliaIdColumn = column("trenitalia_location_id");

  if ([stationIdColumn, stationNameColumn, stationCodeColumn].some(index => index < 0)) {
    throw new Error("Il catalogo stazioni non contiene le colonne richieste.");
  }

  return rows.flatMap(values => {
    const name = values[stationNameColumn]?.trim();
    const code = values[stationCodeColumn]?.trim();
    if (!name || !code) return [];

    const aliases = (values[aliasesColumn] ?? "")
      .split("|")
      .map(alias => alias.trim())
      .filter(alias => Boolean(alias) && alias !== name);

    return [{
      stationId: values[stationIdColumn]?.trim() || code,
      name,
      code,
      latitude: parseCoordinate(values[latitudeColumn]),
      longitude: parseCoordinate(values[longitudeColumn]),
      aliases,
      confidence: values[confidenceColumn]?.trim() || "Non classificata",
      trenitaliaLocationId: values[trenitaliaIdColumn]?.trim() || null,
    }];
  });
}

function matchScore(station: CanonicalStation, query: string) {
  const normalizedName = normalizeForSearch(station.name);
  const normalizedCode = normalizeForSearch(station.code);
  const normalizedAliases = station.aliases.map(normalizeForSearch);

  if (normalizedName === query || normalizedCode === query) return 0;
  if (normalizedName.startsWith(query) || normalizedCode.startsWith(query)) return 1;
  if (normalizedAliases.some(alias => alias === query || alias.startsWith(query))) return 2;
  if (normalizedName.includes(query) || normalizedAliases.some(alias => alias.includes(query))) return 3;
  return Number.POSITIVE_INFINITY;
}

export function searchCanonicalStations(stations: CanonicalStation[], rawQuery: string, limit = 6) {
  const query = normalizeForSearch(rawQuery);
  if (query.length < 2) return [];

  return stations
    .map(station => ({ station, score: matchScore(station, query) }))
    .filter(result => Number.isFinite(result.score))
    .sort((left, right) => left.score - right.score || left.station.name.localeCompare(right.station.name, "it"))
    .slice(0, limit)
    .map(result => result.station);
}

export function getStationBoardPath(station: Pick<CanonicalStation, "name" | "code">) {
  return `/stazioni?name=${encodeURIComponent(station.name)}&code=${encodeURIComponent(station.code)}`;
}

export function getStationBoardContext(search: string) {
  const query = new URLSearchParams(search);
  return {
    name: query.get("name"),
    code: query.get("code"),
  };
}
