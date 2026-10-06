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
