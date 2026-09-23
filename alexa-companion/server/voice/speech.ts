import { LIVE_DATA_UNAVAILABLE_SPEECH } from "./config";

/**
 * Do not replace the constant with a friendly approximation: this exact phrase
 * is the product guarantee used whenever an authorized real-time provider is absent.
 */
export function liveDataUnavailableSpeech(subject?: string) {
  return subject ? `${subject}: ${LIVE_DATA_UNAVAILABLE_SPEECH}` : LIVE_DATA_UNAVAILABLE_SPEECH;
}
