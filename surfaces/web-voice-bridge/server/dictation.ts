import { TRPCError } from "@trpc/server";
import { storageGetSignedUrl, storagePut } from "./storage";
import { transcribeAudio } from "./_core/voiceTranscription";

export const DICTATION_MAX_BYTES = 16 * 1024 * 1024;
const DICTATION_MAX_BASE64_LENGTH = Math.ceil(DICTATION_MAX_BYTES / 3) * 4;
/** Base64 massimo più il piccolo envelope JSON tRPC; non è un limite per altri endpoint. */
export const DICTATION_REQUEST_MAX_BYTES = DICTATION_MAX_BASE64_LENGTH + 64 * 1024;
export const dictationMimeTypes = ["audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg", "audio/wav"] as const;
export type DictationMimeType = (typeof dictationMimeTypes)[number];

const extensionByMimeType: Record<DictationMimeType, string> = {
  "audio/webm": "webm",
  "audio/ogg": "ogg",
  "audio/mp4": "m4a",
  "audio/mpeg": "mp3",
  "audio/wav": "wav",
};

export function isDictationBase64TooLarge(encodedLength: number) {
  return encodedLength > DICTATION_MAX_BASE64_LENGTH;
}

export function isDictationRequestTooLarge(contentLength: string | string[] | undefined) {
  const value = Array.isArray(contentLength) ? contentLength[0] : contentLength;
  if (!value || !/^\d+$/.test(value)) return false;
  const bytes = Number(value);
  return Number.isSafeInteger(bytes) && bytes > DICTATION_REQUEST_MAX_BYTES;
}

export function decodeDictationAudio(audioBase64: string) {
  const encoded = audioBase64.trim();
  if (isDictationBase64TooLarge(encoded.length)) {
    throw new TRPCError({ code: "BAD_REQUEST", message: "La registrazione supera il limite di 16 MB." });
  }
  const isCanonicalBase64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(encoded);
  if (!encoded || encoded !== audioBase64 || !isCanonicalBase64) {
    throw new TRPCError({ code: "BAD_REQUEST", message: "La registrazione vocale non è in un formato valido." });
  }
  const audio = Buffer.from(encoded, "base64");
  if (audio.length === 0 || audio.toString("base64") !== encoded) {
    throw new TRPCError({ code: "BAD_REQUEST", message: "La registrazione vocale non è in un formato valido." });
  }
  if (audio.length > DICTATION_MAX_BYTES) {
    throw new TRPCError({ code: "BAD_REQUEST", message: "La registrazione supera il limite di 16 MB." });
  }
  return audio;
}

export async function transcribeItalianRailDictation({
  audioBase64,
  mimeType,
}: {
  audioBase64: string;
  mimeType: DictationMimeType;
}) {
  const audio = decodeDictationAudio(audioBase64);

  try {
    const { key } = await storagePut(
      `dictation/ephemeral/${crypto.randomUUID()}.${extensionByMimeType[mimeType]}`,
      audio,
      mimeType,
    );
    const audioUrl = await storageGetSignedUrl(key);
    const result = await transcribeAudio({
      audioUrl,
      language: "it",
      prompt: "Trascrivi in italiano una richiesta ferroviaria. Mantieni correttamente nomi di stazioni italiane, numeri di treno e orari pronunciati.",
    });

    if ("error" in result) {
      throw new TRPCError({ code: "BAD_REQUEST", message: "Non è stato possibile trascrivere la registrazione." });
    }

    return {
      text: result.text.trim(),
      language: result.language,
      duration: result.duration,
    };
  } finally {
    audio.fill(0);
  }
}
