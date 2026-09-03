import type { CompanionSnapshot } from "@/lib/companion-snapshot";

export function msUntilSnapshotExpiry(provenance: CompanionSnapshot["provenance"], now = Date.now()) {
  return provenance.expiresAtUtcMs === null ? 0 : Math.max(0, provenance.expiresAtUtcMs - now);
}
