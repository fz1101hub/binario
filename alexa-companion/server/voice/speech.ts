import { escapeXmlCharacters } from "ask-sdk-core";
import { formatMessage, formatPlural, messagesForLocale, type SupportedLocale } from "@shared/i18n";
import type { LiveDepartures } from "../rail/rfi";
import { LIVE_DATA_UNAVAILABLE_SPEECH } from "./config";

const localeSet = new Set<SupportedLocale>([
  "it-IT",
  "en-US",
  "en-GB",
  "de-DE",
  "fr-FR",
  "es-ES",
]);

// Characters XML 1.0 forbids even when escaped: C0 controls other than tab,
// line feed and carriage return, the U+FFFE/U+FFFF non-characters, and
// unpaired UTF-16 surrogates. Any of them makes Alexa reject the whole SSML.
const XML_FORBIDDEN_CHARACTERS =
  /[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]|[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g;

/** Removes characters that no XML document (and no Alexa card) can carry. */
export function plainSpeechText(value: string) {
  return value.replace(XML_FORBIDDEN_CHARACTERS, "");
}

/** Escapes untrusted text before interpolation into Alexa SSML. */
export function escapeSsmlText(value: string) {
  return escapeXmlCharacters(plainSpeechText(value));
}

/**
 * The Italian phrase remains the product guarantee for it-IT. Other locales use
 * their equivalent explicit caveat; no response ever approximates live data.
 * The subject-only overload preserves the prior Italian helper contract.
 */
export function liveDataUnavailableSpeech(subject?: string): string;
export function liveDataUnavailableSpeech(
  locale: SupportedLocale,
  subject?: string
): string;
export function liveDataUnavailableSpeech(
  localeOrSubject: SupportedLocale | string = "it-IT",
  optionalSubject?: string
) {
  const [locale, subject] = localeSet.has(localeOrSubject as SupportedLocale)
    ? [localeOrSubject as SupportedLocale, optionalSubject]
    : (["it-IT", localeOrSubject] as const);
  const caveat = messagesForLocale(locale).voice.liveDataUnavailable;
  return subject ? `${subject}: ${caveat}` : caveat;
}

/** Legacy configuration value retained for operational documentation and it-IT compatibility. */
export const italianLiveDataUnavailableSpeech = LIVE_DATA_UNAVAILABLE_SPEECH;

/** The spoken answer and Echo Show card use exactly the same source-backed fields. */
export function liveDeparturesSpeech(locale: SupportedLocale, live: LiveDepartures, card = false) {
  const messages = messagesForLocale(locale).voice;
  const safe = card ? plainSpeechText : escapeSsmlText;
  const time = (instant: number) => new Intl.DateTimeFormat(locale, {
    timeZone: "Europe/Rome", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  }).format(instant);
  const source = formatMessage(messages.liveSource, { time: time(live.observedAt) });
  const trains = live.departures.slice(0, 2).map(train => formatMessage(messages.liveTrain, {
    number: safe(train.number), destination: safe(train.destination), time: time(train.expectedAt),
    status: train.cancelled ? messages.liveCancelled : train.delayMinutes > 0
      ? formatPlural(locale, train.delayMinutes, messages.liveDelayed) : messages.liveOnTime,
    platform: train.platform ? formatMessage(messages.livePlatform, { platform: safe(train.platform) }) : "",
  }));
  return [source, ...trains].join(" ");
}
