import axios from "axios";
import { describeErrorForLog } from "./logRedaction";

const SENSITIVE_KEYS = new Set([
  "accesstoken",
  "authorization",
  "code",
  "cookie",
  "idtoken",
  "jwttoken",
  "refreshtoken",
  "set-cookie",
  "token",
]);

function redactValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(redactValue);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(
    Object.entries(value).map(([key, nested]) => [
      key,
      SENSITIVE_KEYS.has(key.toLocaleLowerCase("en-US"))
        ? "[REDACTED]"
        : redactValue(nested),
    ])
  );
}

function safeUrl(value: string | undefined) {
  if (!value) return undefined;
  return value.split("?", 1)[0];
}

/**
 * Return only bounded Axios diagnostics. Request bodies are never logged: OAuth
 * libraries often serialize credentials into an opaque string that cannot be
 * redacted reliably. Header names are matched case-insensitively and URL query
 * parameters are removed.
 */
export function safeErrorDetails(error: unknown) {
  // Anything else (a database failure while provisioning the user, for example)
  // is described without values: see logRedaction.ts.
  if (!axios.isAxiosError(error)) return describeErrorForLog(error);
  return {
    code: error.code,
    message: error.message,
    status: error.response?.status,
    config: {
      method: error.config?.method,
      url: safeUrl(error.config?.url),
      headers: redactValue(
        error.config?.headers?.toJSON?.() ?? error.config?.headers
      ),
    },
  };
}
