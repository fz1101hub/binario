import { formatList, formatMessage, formatPlural, messagesForLocale, type SupportedLocale } from "@shared/i18n";
import type { PunctualDeparture, RoutePunctuality, TrainPunctuality } from "../punctuality/stats";
import type { PunctualityFigures, PunctualityTrainRow } from "./presentation";

/**
 * Sentences of a punctuality answer. User values (train number, route name) go through `text`:
 * SSML-escaped for the speech, plain for the card and screen. They are interpolated last, so
 * nothing they contain is read as a placeholder. Every answer with numbers ends with the
 * attribution («Statistiche non ufficiali di Binario su dati ViaggiaTreno»).
 */
export type PunctualityAnswer = {
  /** The sentences before the attribution. */
  sentences: string[];
  /** True when the answer reports statistics and must carry the attribution. */
  attributed: boolean;
  figures?: PunctualityFigures;
  /** The trains of a most-punctual answer, for the screen's list. */
  trains?: PunctualityTrainRow[];
};

type Text = (value: string) => string;

function messages(locale: SupportedLocale) {
  return messagesForLocale(locale).punctuality;
}

export function unavailableAnswer(locale: SupportedLocale): PunctualityAnswer {
  return { sentences: [messages(locale).unavailable], attributed: false };
}

export function trainAnswer(
  locale: SupportedLocale,
  trainNumber: string,
  result: TrainPunctuality,
  text: Text
): PunctualityAnswer {
  const p = messages(locale);
  const trainNumberText = text(trainNumber);
  switch (result.kind) {
    case "unknown":
      return { sentences: [formatMessage(p.trainUnknown, { trainNumber: trainNumberText })], attributed: false };
    case "fewData":
      return { sentences: [formatMessage(p.trainFewData, { trainNumber: trainNumberText })], attributed: false };
    case "cancelledEveryDay":
      return {
        sentences: [
          formatMessage(p.cancelledEveryDay, {
            days: formatPlural(locale, result.days, p.observedDays),
            trainNumber: trainNumberText,
          }),
        ],
        attributed: true,
      };
    case "stats": {
      const main = formatMessage(formatPlural(locale, result.percent, p.train), {
        limit: formatPlural(locale, result.limitMinutes, p.minutes),
        days: formatPlural(locale, result.days, p.observedDays),
        trainNumber: trainNumberText,
      });
      return {
        sentences: [
          main,
          result.meanDelayMinutes > 0 ? formatPlural(locale, result.meanDelayMinutes, p.meanDelay) : p.noMeanDelay,
          ...(result.cancelledDays > 0 ? [formatPlural(locale, result.cancelledDays, p.cancelled)] : []),
        ],
        attributed: true,
        figures: { percent: result.percent, meanDelayMinutes: result.meanDelayMinutes, days: result.days },
      };
    }
  }
}

export type RouteAnswerInput =
  | { kind: "stationsUnknown" }
  | { kind: "fewData" }
  | { kind: "stats"; result: RoutePunctuality };

export function routeAnswer(
  locale: SupportedLocale,
  routeName: string,
  outcome: RouteAnswerInput,
  text: Text
): PunctualityAnswer {
  const p = messages(locale);
  const routeNameText = text(routeName);
  if (outcome.kind === "stationsUnknown")
    return { sentences: [formatMessage(p.routeStationsUnknown, { routeName: routeNameText })], attributed: false };
  if (outcome.kind === "fewData")
    return { sentences: [formatMessage(p.routeFewData, { routeName: routeNameText })], attributed: false };
  const { result } = outcome;
  const limit = result.limits.length === 1 ? formatPlural(locale, result.limits[0], p.minutes) : p.mixedLimit;
  const main = formatMessage(formatPlural(locale, result.percent, p.route), {
    limit,
    days: formatPlural(locale, result.days, p.observedDays),
    trains: formatPlural(locale, result.trains, p.trainCount),
    routeName: routeNameText,
  });
  return {
    sentences: [main, result.meanDelayMinutes > 0 ? formatPlural(locale, result.meanDelayMinutes, p.meanDelay) : p.noMeanDelay],
    attributed: true,
    figures: { percent: result.percent, meanDelayMinutes: result.meanDelayMinutes, days: result.days, trains: result.trains },
  };
}

export type MostPunctualAnswerInput =
  | { kind: "stationsUnknown" }
  | { kind: "none" }
  | { kind: "trains"; trains: readonly PunctualDeparture[] };

/** A minute of the day as hours and minutes, the way the locale writes a time ("8:12", "8:12 AM"). */
function timeFormat(locale: SupportedLocale, latinDigits: boolean) {
  return new Intl.DateTimeFormat(locale, {
    hour: "numeric",
    minute: "2-digit",
    timeZone: "UTC",
    ...(latinDigits ? { numberingSystem: "latn" } : {}),
  });
}

function timeParts(locale: SupportedLocale, minuteOfDay: number, latinDigits: boolean) {
  const instant = new Date(Date.UTC(2026, 0, 1, Math.floor(minuteOfDay / 60), minuteOfDay % 60));
  return timeFormat(locale, latinDigits).formatToParts(instant);
}

/**
 * A usual departure time: digits as the rest of the speech writes them, spaces plain (some
 * runtimes put a narrow no-break space before AM/PM).
 */
export function spokenTime(locale: SupportedLocale, minuteOfDay: number) {
  return timeParts(locale, minuteOfDay, true)
    .map(part => part.value)
    .join("")
    .replace(/[\u00a0\u202f]/g, " ");
}

/** The same time for the Echo Show list, in the locale's own digits. */
export function displayedTime(locale: SupportedLocale, minuteOfDay: number) {
  return timeParts(locale, minuteOfDay, false)
    .map(part => part.value)
    .join("");
}

/** The hour as the locale writes it (13:05 is 1 in 12-hour English and Spanish), for agreement. */
function writtenHour(locale: SupportedLocale, minuteOfDay: number) {
  const hour = timeParts(locale, minuteOfDay, true).find(part => part.type === "hour")?.value;
  return Number(hour ?? Math.floor(minuteOfDay / 60));
}

/**
 * «Qual è il treno più puntuale per casa lavoro?»: the best train that usually leaves in the next
 * three hours and up to two more, each with its usual departure and how many times out of ten it
 * arrived on time. Only the route is named, never its stations.
 */
export function mostPunctualAnswer(
  locale: SupportedLocale,
  routeName: string,
  outcome: MostPunctualAnswerInput,
  text: Text
): PunctualityAnswer {
  const p = messages(locale);
  const routeNameText = text(routeName);
  if (outcome.kind === "stationsUnknown")
    return { sentences: [formatMessage(p.routeStationsUnknown, { routeName: routeNameText })], attributed: false };
  if (outcome.kind === "none" || !outcome.trains.length)
    return { sentences: [formatMessage(p.mostPunctualNone, { routeName: routeNameText })], attributed: false };
  const spoken = outcome.trains.map(train =>
    formatMessage(formatPlural(locale, writtenHour(locale, train.minuteOfDay), p.mostPunctualTrain), {
      onTime: formatPlural(locale, train.tenths, p.outOfTen),
      time: spokenTime(locale, train.minuteOfDay),
      trainNumber: text(train.number),
    })
  );
  const [best, ...others] = spoken;
  return {
    sentences: [
      formatMessage(p.mostPunctualBest, { train: best, routeName: routeNameText }),
      ...(others.length ? [formatMessage(p.mostPunctualOthers, { trains: formatList(locale, others) })] : []),
      // The times are Rome's wall clock: say so to a listener who may be elsewhere.
      ...(locale === "it-IT" ? [] : [p.mostPunctualItalianTime]),
    ],
    attributed: true,
    trains: outcome.trains.map(train => ({ trainNumber: train.number, minuteOfDay: train.minuteOfDay, percent: train.percent })),
  };
}

/** Joins sentences with a space, except after the full-width stops of Japanese and Chinese. */
export function joinSentences(sentences: readonly string[]) {
  return sentences.reduce((text, sentence) => (!text ? sentence : /[。！？]$/.test(text) ? text + sentence : `${text} ${sentence}`), "");
}

/** The spoken or written answer: the sentences, then the attribution when it reports statistics. */
export function answerText(locale: SupportedLocale, answer: PunctualityAnswer) {
  return joinSentences([...answer.sentences, ...(answer.attributed ? [messages(locale).source] : [])]);
}
