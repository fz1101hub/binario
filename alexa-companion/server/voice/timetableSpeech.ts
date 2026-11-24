import { formatList, formatMessage, formatPlural, messagesForLocale, type SupportedLocale } from "@shared/i18n";
import { busLabel, type TimetableVehicle } from "../timetable/naming";
import type { TimetableService, TrainRun } from "../timetable/repository";
import type { DepartureRow } from "./presentation";
import { spokenTime, writtenHour } from "./punctualitySpeech";

/**
 * Sentences of a programmed-timetable answer. Values from outside the code (route and station
 * names, train labels) go through `text`: SSML-escaped for the speech, plain for the card and the
 * screen. Every answer says the times are programmed, that the platform is not known and that the
 * live state is in the app: the skill never approximates live data.
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
};

/**
 * How a service is named, as the iPhone's result rows name it (`VehicleTexts.rowLabel`): a train
 * by category and number («REG 2157»; the number alone when it has letters), a bus by its label,
 * its line when it adds something, and its number («Bus sostitutivo S11 25003»). A «line - run»
 * number (Trenord's «RE_80 - 25508») is said by its run, as Android shows it: «RE80 25508».
 */
export function serviceLabel(locale: SupportedLocale, service: Pick<TimetableService, "number" | "category" | "vehicle">): string {
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

/** «Secondo l'orario programmato le prossime partenze sono il REG 2157 delle 8:12, …», the platform and the app. */
export function departureSentences(locale: SupportedLocale, services: readonly TimetableService[], text: Text): string[] {
  const t = messages(locale);
  const spoken = services.slice(0, SPOKEN_DEPARTURES).map(service =>
    formatMessage(formatPlural(locale, writtenHour(locale, minuteOfDay(service.time)), t.departure), {
      time: spokenTime(locale, minuteOfDay(service.time)),
      train: text(serviceLabel(locale, service)),
    })
  );
  return [
    formatMessage(formatPlural(locale, spoken.length, t.departures), { trains: formatList(locale, spoken) }),
    t.platformAndLive,
    ...italianTime(locale),
  ];
}

/** The rows of the Echo Show list: the departures the voice reads. */
export function departureRows(locale: SupportedLocale, services: readonly TimetableService[]): DepartureRow[] {
  return services.slice(0, SPOKEN_DEPARTURES).map(service => ({ minuteOfDay: minuteOfDay(service.time), train: serviceLabel(locale, service) }));
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
