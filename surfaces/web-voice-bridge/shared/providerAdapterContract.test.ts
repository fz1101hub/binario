import { describe, expect, it } from "vitest";
import { getProviderGovernance } from "./providerGovernance";
import { validateProviderAdapterCandidate } from "./providerAdapterContract";

describe("contratto adapter provider", () => {
  it("resta fail-closed senza governance attiva anche con candidato formalmente valido", () => {
    const now = 1_800_000_000_000;
    expect(validateProviderAdapterCandidate({ providerId: "provider-open", licenceReference: "licenza-open-001", provenanceUrl: "https://example.test/source", observedAtUtcMs: now - 1_000, expiresAtUtcMs: now + 1_000, confidence: "verified" }, getProviderGovernance(), now)).toBe(false);
  });
});
