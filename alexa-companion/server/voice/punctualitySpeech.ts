import { formatMessage, formatPlural, messagesForLocale, type SupportedLocale } from "@shared/i18n";
import type { RoutePunctuality, TrainPunctuality } from "../punctuality/stats";
import type { PunctualityFigures } from "./presentation";

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

/** Joins sentences with a space, except after the full-width stops of Japanese and Chinese. */
export function joinSentences(sentences: readonly string[]) {
  return sentences.reduce((text, sentence) => (!text ? sentence : /[。！？]$/.test(text) ? text + sentence : `${text} ${sentence}`), "");
}

/** The spoken or written answer: the sentences, then the attribution when it reports statistics. */
export function answerText(locale: SupportedLocale, answer: PunctualityAnswer) {
  return joinSentences([...answer.sentences, ...(answer.attributed ? [messages(locale).source] : [])]);
}
