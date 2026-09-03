import { mayDeliverRealtime, type ProviderGovernance } from "./providerGovernance";

export type ProviderAdapterCandidate = {
  providerId: string;
  licenceReference: string;
  provenanceUrl: string;
  observedAtUtcMs: number;
  expiresAtUtcMs: number;
  confidence: "verified" | "unverified";
};

export function validateProviderAdapterCandidate(candidate: ProviderAdapterCandidate, governance: ProviderGovernance, now = Date.now()) {
  const providerIdValid = /^[a-z0-9][a-z0-9_-]{2,63}$/.test(candidate.providerId);
  const licenceValid = candidate.licenceReference.trim().length >= 8;
  const provenanceValid = /^https:\/\//.test(candidate.provenanceUrl);
  const timestampsValid = Number.isFinite(candidate.observedAtUtcMs) && candidate.observedAtUtcMs <= now && Number.isFinite(candidate.expiresAtUtcMs) && candidate.expiresAtUtcMs > now;
  return providerIdValid && licenceValid && provenanceValid && timestampsValid && candidate.confidence === "verified" && mayDeliverRealtime(governance);
}
