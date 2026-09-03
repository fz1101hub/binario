export const maxIncomingSharedTextCharacters = 4_000;

/** Conserva le righe per l’anteprima, rifiuta payload non testuali, vuoti o oltre soglia. */
export function normalizeIncomingSharedText(value: unknown): string | null {
  if (typeof value !== "string" || value.length === 0 || value.length > maxIncomingSharedTextCharacters) return null;
  const normalized = value.replace(/\r\n?/g, "\n").replace(/\u0000/g, "").trim();
  return normalized.length > 0 && normalized.length <= maxIncomingSharedTextCharacters ? normalized : null;
}
