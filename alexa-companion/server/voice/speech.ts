import { escapeXmlCharacters } from "ask-sdk-core";
import { LIVE_DATA_UNAVAILABLE_SPEECH } from "./config";

/**
 * Escapes untrusted text before it is interpolated into Alexa SSML. Static
 * response markup must stay outside this helper so valid SSML is not escaped.
 */
export function escapeSsmlText(value: string) {
  return escapeXmlCharacters(value);
}

/**
 * Do not replace the constant with a friendly approximation: this exact phrase
 * is the product guarantee used whenever an authorized real-time provider is absent.
 */
export function liveDataUnavailableSpeech(subject?: string) {
  return subject
    ? `${subject}: ${LIVE_DATA_UNAVAILABLE_SPEECH}`
    : LIVE_DATA_UNAVAILABLE_SPEECH;
}
