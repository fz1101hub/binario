export type PersonalRouteDraft = {
  origin: string;
  destination: string;
  name: string;
  isPinned?: boolean;
};

function normalizeLabel(value: string, field: string): string {
  const normalized = value.trim().replace(/\s+/g, " ");
  if (normalized.length < 2 || normalized.length > 120) throw new Error(`${field} non valido`);
  return normalized;
}

export function normalizePersonalRouteDraft(input: PersonalRouteDraft) {
  const origin = normalizeLabel(input.origin, "Origine");
  const destination = normalizeLabel(input.destination, "Destinazione");
  if (origin.localeCompare(destination, "it", { sensitivity: "base" }) === 0) throw new Error("Origine e destinazione devono essere diverse");
  return {
    origin,
    destination,
    name: normalizeLabel(input.name, "Nome tratta"),
    isPinned: input.isPinned ?? false,
  };
}
