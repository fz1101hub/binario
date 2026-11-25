import { PackRoles } from "@shared/generated/sharedData";

/**
 * How the bundled timetable names a service and what it travels on, as the iPhone
 * (`TimetableNaming`, `TimetableLegMode`, `TimetableVehicle` in BinarioKit) and Android
 * (`EmbeddedGtfsRouteAdapter`, `JourneyLegVehicle`) name the same trips.
 * contracts/shared-rules/v1/journey-leg-vehicles-v1.json holds the cases the three apps test.
 */

/** A line of a city network: a metro (the Marconi Express people mover too), a tram, a funicular or lift. */
export type UrbanMode = "METRO" | "TRAM" | "FUNICULAR";

export type TimetableLegMode = "TRAIN" | "BUS" | "REPLACEMENT_BUS" | UrbanMode;

export type TimetableVehicle = "TRAIN" | "BUS_TRENITALIA" | "FRECCIALINK" | "ITALOBUS" | "BUS" | "REPLACEMENT_BUS" | UrbanMode;

/**
 * Rail (2, 100–199), metro (1, 12, 400–499), tram (0, 900–999), funicular and lift (5, 6, 7,
 * 1300–1499) or bus (3, 700–799); a bus is the operator's coach only in `scheduledCoachPacks`.
 * Any other type (a ferry, a flight) has no mode.
 */
export function legMode(routeType: number, sourceId: string): TimetableLegMode | null {
  if (routeType === 2 || (routeType >= 100 && routeType <= 199)) return "TRAIN";
  if (routeType === 1 || routeType === 12 || (routeType >= 400 && routeType <= 499)) return "METRO";
  if (routeType === 0 || (routeType >= 900 && routeType <= 999)) return "TRAM";
  if (routeType === 5 || routeType === 6 || routeType === 7 || (routeType >= 1300 && routeType <= 1499)) return "FUNICULAR";
  if (routeType === 3 || (routeType >= 700 && routeType <= 799))
    return PackRoles.SCHEDULED_COACH_PACKS.has(sourceId) ? "BUS" : "REPLACEMENT_BUS";
  return null;
}

/** A metro, tram or funicular line of a city network: said with its line, never a train number. */
export function isUrban(value: TimetableLegMode | TimetableVehicle): value is UrbanMode {
  return value === "METRO" || value === "TRAM" || value === "FUNICULAR";
}

/**
 * A city line is its mode; a coach is FrecciaLink by its line («FL», «FRECCIALINK»), ItaloBus by
 * its operator or line, a Trenitalia bus by its operator, else a bus.
 */
export function vehicleOf(mode: TimetableLegMode, operatorName: string | null, lineLabel: string | null): TimetableVehicle {
  if (mode === "TRAIN") return "TRAIN";
  if (mode === "REPLACEMENT_BUS") return "REPLACEMENT_BUS";
  if (isUrban(mode)) return mode;
  const operator = (operatorName ?? "").toLowerCase();
  const line = (lineLabel ?? "").toUpperCase().replaceAll(" ", "");
  if (line === "FL" || line.includes("FRECCIALINK")) return "FRECCIALINK";
  if (operator.includes("italo") || line.includes("ITALOBUS")) return "ITALOBUS";
  if (operator.includes("trenitalia")) return "BUS_TRENITALIA";
  return "BUS";
}

/**
 * The label of a bus with its published line («Bus sostitutivo S11», «Bus Trenitalia BUS»); for a
 * coach the line only when it adds something («ItaloBus», not «ItaloBus ItaloBus»). A line that is
 * a long name, a placeholder or the number itself is left out. `label` is the localized vehicle text.
 */
export function busLabel(label: string, vehicle: TimetableVehicle, line: string | null, number?: string): string {
  const trimmed = line ? trimSpaces(line) : "";
  if (
    !trimmed ||
    Array.from(trimmed).length > 10 ||
    trimmed.includes("(") ||
    trimmed.toUpperCase() === "INCONNU" ||
    (number !== undefined && trimmed.toUpperCase() === trimSpaces(number).toUpperCase())
  )
    return label;
  if (vehicle !== "REPLACEMENT_BUS" && trimmed.replaceAll(" ", "").toUpperCase() === label.replaceAll(" ", "").toUpperCase()) return label;
  return `${label} ${trimmed}`;
}

/**
 * The public name of a city line from its route short name (`TimetableNaming.urbanLine`, Android's
 * `urbanLineName`): Milan publishes its metro lines as «1»…«5» («M1»), Rome as «MEA», «MEB»,
 * «MEB1», «MEC» («A», «B1»); every other name is kept as published («M1», «14», the Marconi
 * Express «MEX»). Null without a name.
 */
export function urbanLine(shortName: string | null, mode: TimetableLegMode): string | null {
  const value = shortName === null ? "" : trimSpaces(shortName);
  if (!value) return null;
  if (mode !== "METRO") return value;
  const scalars = Array.from(value);
  if (scalars.length <= 2 && scalars.every(scalar => scalar >= "0" && scalar <= "9")) return `M${value}`;
  // ME followed by A, B or C and at most one digit: Rome's lines.
  if (
    (scalars.length === 3 || scalars.length === 4) &&
    scalars[0] === "M" &&
    scalars[1] === "E" &&
    scalars[2] >= "A" &&
    scalars[2] <= "C" &&
    (scalars.length === 3 || (scalars[3] >= "0" && scalars[3] <= "9"))
  )
    return scalars.slice(2).join("");
  return value;
}

/** The words that name a city mode in any language of the apps: a line that is one of them only repeats the mode. */
const URBAN_MODE_WORDS = new Set([
  "metro", "metropolitana", "métro", "u-bahn", "subway", "tram", "tramvia", "tranvia", "tranvía", "tramway",
  "straßenbahn", "funicolare", "funicular", "funiculaire", "standseilbahn",
]);

/** The line, without spaces and in lower case, is the label itself or a word that names a city mode. */
export function urbanLineRepeatsMode(line: string, label: string): boolean {
  const compact = line.replaceAll(" ", "").toLowerCase();
  return compact === label.replaceAll(" ", "").toLowerCase() || URBAN_MODE_WORDS.has(compact);
}

/**
 * The label of a city line (`TimetableVehicle.urbanLabel`, Android's `journeyLegLabel`): «Metro M1»,
 * «Tram 14», «Funicolare»; a city run has no public number. A line that is a long name, a
 * placeholder or only repeats the mode is left out. `label` is the localized mode.
 */
export function urbanLabel(label: string, line: string | null): string {
  const trimmed = line ? trimSpaces(line) : "";
  if (
    !trimmed ||
    Array.from(trimmed).length > 10 ||
    trimmed.includes("(") ||
    trimmed.toUpperCase() === "INCONNU" ||
    urbanLineRepeatsMode(trimmed, label)
  )
    return label;
  return `${label} ${trimmed}`;
}

/** Trenitalia's route short names carry the company code first: «0083_FR» is «FR»; any other is kept as published. */
export function lineLabel(shortName: string): string {
  const trimmed = trimSpaces(shortName);
  const prefix = "0083_";
  if (!trimmed.startsWith(prefix)) return trimmed;
  const code = trimmed.slice(prefix.length);
  return /^[A-Za-z]{1,4}$/.test(code) ? code : trimmed;
}

/** Without accents, lower case, letters and digits only. */
export function normalizeName(value: string): string {
  return value
    .normalize("NFKD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");
}

/** `(^|[^a-z])word([^a-z]|$)` on lower-case text. */
function containsWord(word: string, text: string): boolean {
  return new RegExp(`(^|[^a-z])${word}([^a-z]|$)`).test(text);
}

/**
 * The operator a timetable names: the short brand of the operators the apps know («TRENITALIA» is
 * «Trenitalia», «Italo - Nuovo Trasporto Viaggiatori» is «Italo»), any other agency name as published.
 */
export function operatorName(agencyName: string, sourceId: string): string {
  const raw = agencyName.trim();
  if (!raw) {
    if (sourceId === "lombardia-trenord-gtfs") return "Trenord";
    if (sourceId === "oebb-gtfs") return "ÖBB";
    if (sourceId === "sncf-gtfs") return "SNCF Voyageurs";
    return raw;
  }
  const normalized = normalizeName(raw);
  const lower = raw.toLowerCase();
  if (normalized.includes("tilo")) return "TILO";
  if (normalized.includes("schweizerischebundesbahnen") || containsWord("sbb", lower)) return "SBB";
  if (normalized.includes("bls")) return "BLS";
  if (normalized.includes("rhatischebahn") || normalized.includes("rhaetischebahn") || normalized === "rhb") return "RhB";
  if (normalized.includes("ferrovieautolineeregionaliticinesi") || normalized === "fart") return "FART";
  if (normalized.includes("societasubalpinadiimpreseferroviarie") || normalized === "ssif") return "SSIF";
  if (normalized.includes("osterreichischebundesbahnen") || normalized.includes("oebb") || normalized === "obb") return "ÖBB";
  if (normalized.includes("dbfernverkehr") || normalized.includes("deutschebahn")) return "DB";
  if (normalized.includes("sncf")) return "SNCF Voyageurs";
  if (normalized.includes("europeansleeper")) return "European Sleeper";
  if (normalized.includes("trenord")) return "Trenord";
  if (normalized.includes("trenitalia")) return "Trenitalia";
  if (normalized.includes("ntv") || normalized.includes("italotreno") || normalized.startsWith("italo")) return "Italo";
  return raw;
}

/** Spaces and tabs at both ends, as Swift's `.whitespaces`. */
function trimSpaces(value: string): string {
  return value.replace(/^[ \t ]+|[ \t ]+$/g, "");
}

/** Runs of white space as one space, none at the ends (`normalizedWhitespace`). */
export function normalizedWhitespace(value: string): string {
  return value.split(/\s+/u).filter(Boolean).join(" ");
}

/** `value` without extra white space, cut to `maximum` characters with an ellipsis. */
export function bounded(value: string, maximum: number): string {
  const characters = Array.from(normalizedWhitespace(value));
  return characters.length > maximum ? `${characters.slice(0, maximum - 1).join("")}…` : characters.join("");
}
