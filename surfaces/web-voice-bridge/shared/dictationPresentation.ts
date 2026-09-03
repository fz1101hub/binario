export type DictationStatus = "idle" | "recording" | "processing" | "unsupported" | "denied" | "error" | "too-large";

const messages: Record<DictationStatus, string> = {
  idle: "Tocca per dettare. Potrai modificare il testo prima di usarlo.",
  recording: "Registrazione in corso. Tocca di nuovo per terminare.",
  processing: "Trascrizione in corso…",
  unsupported: "La dettatura non è supportata da questo browser. Puoi continuare a digitare.",
  denied: "Microfono non autorizzato. Puoi abilitarlo nel browser o continuare a digitare.",
  error: "Trascrizione non riuscita. Riprova oppure inserisci il testo manualmente.",
  "too-large": "Registrazione troppo lunga. Riduci la durata e riprova.",
};

export function getDictationMessage(status: DictationStatus) {
  return messages[status];
}

export function normalizeDictationMimeType(mimeType: string) {
  const normalized = mimeType.split(";")[0]?.trim().toLowerCase();
  return normalized === "audio/webm" || normalized === "audio/ogg" || normalized === "audio/mp4" || normalized === "audio/mpeg" || normalized === "audio/wav"
    ? normalized
    : null;
}

export function normalizeDictationText(transcript: string) {
  return transcript.trim();
}
