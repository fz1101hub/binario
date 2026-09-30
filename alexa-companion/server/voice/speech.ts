import { escapeXmlCharacters } from "ask-sdk-core";
import { messagesForLocale, type SupportedLocale } from "@shared/i18n";
import { LIVE_DATA_UNAVAILABLE_SPEECH } from "./config";

const localeSet = new Set<SupportedLocale>([
  "it-IT",
  "en-US",
  "en-GB",
  "de-DE",
  "fr-FR",
  "es-ES",
]);

/** Escapes untrusted text before interpolation into Alexa SSML. */
export function escapeSsmlText(value: string) {
  return escapeXmlCharacters(value);
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
