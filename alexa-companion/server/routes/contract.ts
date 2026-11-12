import { z } from "zod";

export type PersonalRouteDraft = {
  origin: string;
  destination: string;
  name: string;
  isPinned?: boolean;
};

/**
 * Routes one account may keep (the owner's default): the dashboard
 * refuses the next one, and the voice reads no more.
 */
export const MAX_PERSONAL_ROUTES = 32;

/** The account already has {@link MAX_PERSONAL_ROUTES} routes. */
export class RouteLimitError extends Error {
  override name = "RouteLimitError";
}

/** Invalid user input: the API answers BAD_REQUEST, never an internal error. */
export class RouteValidationError extends Error {
  override name = "RouteValidationError";
}

function collapseSpaces(value: string) {
  return value.trim().replace(/\s+/g, " ");
}

function sameStation(origin: string, destination: string) {
  return collapseSpaces(origin).localeCompare(collapseSpaces(destination), "it", { sensitivity: "base" }) === 0;
}

function normalizeLabel(value: string, field: string): string {
  const normalized = collapseSpaces(value);
  if (normalized.length < 2 || normalized.length > 120) throw new RouteValidationError(`${field} non valido`);
  return normalized;
}

// Trimming before the length check: "  a  " is one character, not five.
const routeLabel = z.string().trim().min(2).max(120);

/** tRPC input for routes.create: the same rules as normalizePersonalRouteDraft, as BAD_REQUEST. */
export const personalRouteInput = z
  .object({
    origin: routeLabel,
    destination: routeLabel,
    name: routeLabel,
    isPinned: z.boolean().optional(),
  })
  .refine(input => !sameStation(input.origin, input.destination), {
    message: "Origine e destinazione devono essere diverse",
    path: ["destination"],
  });

export function normalizePersonalRouteDraft(input: PersonalRouteDraft) {
  const origin = normalizeLabel(input.origin, "Origine");
  const destination = normalizeLabel(input.destination, "Destinazione");
  if (sameStation(origin, destination)) throw new RouteValidationError("Origine e destinazione devono essere diverse");
  return {
    origin,
    destination,
    name: normalizeLabel(input.name, "Nome tratta"),
    isPinned: input.isPinned ?? false,
  };
}
