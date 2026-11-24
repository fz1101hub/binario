import { formatList, formatMessage, formatPlural, messagesForLocale, type SupportedLocale } from "@shared/i18n";
import { busLabel, isUrban, urbanLabel, type TimetableVehicle, type UrbanMode } from "../timetable/naming";
import type { TimetableService, TrainRun } from "../timetable/repository";
import type { DepartureRow } from "./presentation";
import { spokenTime, writtenHour } from "./punctualitySpeech";

/**
 * Sentences of a programmed-timetable answer. Values from outside the code (route and station
 * names, train labels) go through `text`: SSML-escaped for the speech, plain for the card and the
 * screen. Every answer says the times are programmed, that the platform is not known and that the
 * live state is in the app: the skill never approximates live data. A run of a city line (metro,
 * tram, funicular, the Marconi Express) is said with its line, the city stop it is boarded at with
 * the minutes of the walk from the station, and that only its timetable is known.
 */

/** Spoken and shown: the first departures, as the most-punctual answer names its best three. */
export const SPOKEN_DEPARTURES = 3;
/** Several runs of one number are read up to this many. */
export const SPOKEN_RUNS = 3;

type Text = (value: string) => string;

function messages(locale: SupportedLocale) {
  return messagesForLocale(locale).timetable;
}

/** «08:12» → 492. */
export function minuteOfDay(time: string): number {
  const [hours, minutes] = time.split(":").map(Number);
  return hours * 60 + minutes;
}

const VEHICLE_KEYS: Record<Exclude<TimetableVehicle, "TRAIN">, keyof ReturnType<typeof messages>["vehicles"]> = {
  BUS_TRENITALIA: "busTrenitalia",
  FRECCIALINK: "freccialink",
  ITALOBUS: "italobus",
  BUS: "bus",
  REPLACEMENT_BUS: "replacementBus",
  METRO: "metro",
  TRAM: "tram",
  FUNICULAR: "funicular",
};

const CITY_DEPARTURE_KEYS: Record<UrbanMode, keyof ReturnType<typeof messages>["cityDeparture"]> = {
  METRO: "metro",
  TRAM: "tram",
  FUNICULAR: "funicular",
};

/**
 * How a service is named, as the iPhone's result rows name it (`VehicleTexts.rowLabel`): a train
 * by category and number («REG 2157»; the number alone when it has letters), a bus by its label,
 * its line when it adds something, and its number («Bus sostitutivo S11 25003»), a city run by its
 * mode and line, never a number («Metro MEX», «Tram 14»). A «line - run» number (Trenord's
 * «RE_80 - 25508») is said by its run, as Android shows it: «RE80 25508».
 */
export function serviceLabel(locale: SupportedLocale, service: Pick<TimetableService, "number" | "category" | "vehicle">): string {
  // A city run has a line but no number: the line is its category (repository.ts, `serviceNaming`).
  if (isUrban(service.vehicle)) return urbanLabel(messages(locale).vehicles[VEHICLE_KEYS[service.vehicle]], service.category);
  const separator = service.number.lastIndexOf(" - ");
  const run = separator >= 0 ? service.number.slice(separator + 3).trim() : "";
  const number = run || service.number.trim();
  if (service.vehicle === "TRAIN") {
    const category = service.category.trim();
    return !category || /\p{L}/u.test(number) ? number : `${category} ${number}`;
  }
  const label = busLabel(messages(locale).vehicles[VEHICLE_KEYS[service.vehicle]], service.vehicle, service.category, service.number);
  return number ? `${label} ${number}` : label;
}

/** The times are Rome's wall clock (every agency of the index keeps Central European time): say so outside Italian. */
function italianTime(locale: SupportedLocale): string[] {
  return locale === "it-IT" ? [] : [messagesForLocale(locale).punctuality.mostPunctualItalianTime];
}

/** The city stops the spoken runs are boarded at a walk from the station, each once, in the order they are said. */
function walks(services: readonly TimetableService[]): Array<{ stop: string; minutes: number }> {
  const seen = new Set<string>();
  const result: Array<{ stop: string; minutes: number }> = [];
  for (const service of services) {
    if (service.walkMinutes <= 0 || service.boardingStop === null) continue;
    const key = `${service.boardingStop}|${service.walkMinutes}`;
    if (seen.has(key)) continue;
    seen.add(key);
    result.push({ stop: service.boardingStop, minutes: service.walkMinutes });
  }
  return result;
}

/**
 * «Secondo l'orario programmato le prossime partenze sono il REG 2157 delle 8:12, …», the platform and the app.
 * City runs are «corse» when every departure said is one («le prossime corse sono la Metro MEX delle 9:05, …»),
 * then where they are boarded: with `walkFrom` (the station asked) the stop and the station are named («Si sale alla
 * fermata Bologna Centrale FS, a 4 minuti a piedi da Bologna Centrale»); for a saved route only the minutes, since the
 * answer never names a route's stations.
 */
export function departureSentences(locale: SupportedLocale, services: readonly TimetableService[], text: Text, walkFrom?: string): string[] {
  const t = messages(locale);
  const said = services.slice(0, SPOKEN_DEPARTURES);
  const spoken = said.map(service => {
    const vehicle = service.vehicle;
    const template = isUrban(vehicle) ? t.cityDeparture[CITY_DEPARTURE_KEYS[vehicle]] : t.departure;
    return formatMessage(formatPlural(locale, writtenHour(locale, minuteOfDay(service.time)), template), {
      time: spokenTime(locale, minuteOfDay(service.time)),
      train: text(serviceLabel(locale, service)),
    });
  });
  const allCity = said.length > 0 && said.every(service => isUrban(service.vehicle));
  const anyCity = said.some(service => isUrban(service.vehicle));
  const walked = walks(said).map(({ stop, minutes }) =>
    walkFrom === undefined
      ? formatPlural(locale, minutes, t.routeWalk)
      : formatMessage(formatPlural(locale, minutes, t.walk), { stop: text(stop), station: text(walkFrom) })
  );
  return [
    formatMessage(formatPlural(locale, spoken.length, allCity ? t.cityDepartures : t.departures), { trains: formatList(locale, spoken) }),
    ...new Set(walked),
    ...(allCity ? [] : [t.platformAndLive]),
    ...(anyCity ? [t.cityProgrammedOnly] : []),
    ...italianTime(locale),
  ];
}

/** The rows of the Echo Show list: the departures the voice reads, a city run with the minutes of its walk. */
export function departureRows(locale: SupportedLocale, services: readonly TimetableService[]): DepartureRow[] {
  return services.slice(0, SPOKEN_DEPARTURES).map(service => ({
    minuteOfDay: minuteOfDay(service.time),
    train: serviceLabel(locale, service),
    ...(service.walkMinutes > 0 ? { walkMinutes: service.walkMinutes } : {}),
  }));
}

function at(locale: SupportedLocale, time: string) {
  const minutes = minuteOfDay(time);
  return formatMessage(formatPlural(locale, writtenHour(locale, minutes), messages(locale).at), { time: spokenTime(locale, minutes) });
}

/**
 * The programmed runs of a train number today: one with its first and last stop and their times,
 * several listed, none said as such. A stop is named as the catalogue names its station
 * (`stationName`; the packs publish some in capitals), else as the timetable does: never personal.
 */
export function trainRunSentences(
  locale: SupportedLocale,
  trainNumber: string,
  runs: readonly TrainRun[],
  text: Text,
  stationName: (catalogStationId: string | null) => string | undefined = () => undefined
): string[] {
  const t = messages(locale);
  const number = text(trainNumber);
  if (!runs.length) return [formatMessage(t.trainNotScheduled, { trainNumber: number })];
  const values = (run: TrainRun) => ({
    origin: text(stationName(run.originStationId) ?? run.originName),
    destination: text(stationName(run.destinationStationId) ?? run.destinationName),
    departure: at(locale, run.departureTime),
    arrival: at(locale, run.arrivalTime),
  });
  const sentence =
    runs.length === 1
      ? formatMessage(t.trainRun, { trainNumber: number, ...values(runs[0]) })
      : formatMessage(t.trainRuns, {
          trainNumber: number,
          runs: formatList(locale, runs.slice(0, SPOKEN_RUNS).map(run => formatMessage(t.run, values(run)))),
        });
  return [sentence, ...italianTime(locale)];
}
