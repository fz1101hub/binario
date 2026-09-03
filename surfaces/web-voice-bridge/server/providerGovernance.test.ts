import { describe, expect, it } from "vitest";
import { getProviderGovernance, mayDeliverRealtime } from "../shared/providerGovernance";

describe("governance provider ferroviari", () => {
  it("resta fail-closed senza provider autorizzato", () => {
    const governance = getProviderGovernance();
    expect(governance).toMatchObject({ registryVersion: 1, state: "unconfigured", liveDeliveryEnabled: false, killSwitchActive: true, providerCount: 0 });
    expect(mayDeliverRealtime(governance)).toBe(false);
  });
  it("richiede stato attivo, provider e kill switch disattivato per dati realtime", () => {
    expect(mayDeliverRealtime({ ...getProviderGovernance(), state: "active", liveDeliveryEnabled: true, killSwitchActive: false, providerCount: 1 })).toBe(true);
    expect(mayDeliverRealtime({ ...getProviderGovernance(), state: "active", liveDeliveryEnabled: true, killSwitchActive: true, providerCount: 1 })).toBe(false);
  });
});
