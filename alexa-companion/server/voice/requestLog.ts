import { createHash } from "node:crypto";
import type { HandlerInput } from "ask-sdk-core";

type RequestEnvelope = HandlerInput["requestEnvelope"];

/**
 * What an Alexa error log line may say about the request: its type, intent, locale and a
 * short digest of Amazon's request id for correlation. Never slot values (route names, train
 * numbers), user, person or device identifiers, tokens or the session.
 */
export function alexaRequestLogContext(envelope: RequestEnvelope | undefined) {
  const request = envelope?.request as
    | { type?: unknown; locale?: unknown; requestId?: unknown; intent?: { name?: unknown } }
    | undefined;
  const text = (value: unknown, max = 80) =>
    typeof value === "string" && value ? value.slice(0, max) : undefined;
  const requestId = text(request?.requestId, 200);
  return {
    requestType: text(request?.type) ?? "unknown",
    intent: text(request?.intent?.name),
    locale: text(request?.locale, 16),
    requestRef: requestId
      ? createHash("sha256").update(requestId).digest("hex").slice(0, 16)
      : undefined,
  };
}
