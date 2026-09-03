import { LIVE_DATA_UNAVAILABLE_SPEECH } from "./config";
import { getCompanionFallback } from "../../shared/companionSnapshot";

/**
 * Do not replace the constant with a friendly approximation: this exact phrase
 * is the product guarantee used whenever an authorized real-time provider is absent.
 */
export function liveDataUnavailableSpeech(subject?: string) {
  const fallback = getCompanionFallback("alexa").toLowerCase();
  const speech = fallback === LIVE_DATA_UNAVAILABLE_SPEECH ? fallback : LIVE_DATA_UNAVAILABLE_SPEECH;
  return subject ? `${subject}: ${speech}` : speech;
}
