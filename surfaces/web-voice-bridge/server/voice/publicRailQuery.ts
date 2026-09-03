import { liveDataUnavailableSpeech } from "./speech";

function cleanSubject(value: string | undefined, prefix: string): string | undefined {
  const text = value?.replace(/\s+/g, " ").trim();
  return text && text.length <= 160 ? `${prefix} ${text}` : undefined;
}

export function publicRouteQueryFallback(route: string | undefined) {
  return liveDataUnavailableSpeech(cleanSubject(route, "Per la tratta"));
}

export function publicTrainQueryFallback(trainNumber: string | undefined) {
  return liveDataUnavailableSpeech(cleanSubject(trainNumber, "Per il treno"));
}
