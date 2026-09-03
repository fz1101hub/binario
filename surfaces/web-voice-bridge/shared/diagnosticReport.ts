import { getProviderGovernance } from "./providerGovernance";

export function createWebDiagnosticReport(now = new Date()) {
  const governance = getProviderGovernance();
  return {
    schemaVersion: 1,
    generatedAt: now.toISOString(),
    product: "Binario",
    surface: "web",
    providerRegistryVersion: governance.registryVersion,
    providerState: governance.state,
    realtimeDeliveryEnabled: governance.liveDeliveryEnabled,
    excludedFields: ["utente", "email", "tratte", "stazioni", "note", "token", "codici", "audio", "coordinate", "snapshot ferroviari", "user agent"],
  } as const;
}
