export type ProviderGovernanceState = "unconfigured" | "maintenance" | "active";

export type ProviderGovernance = {
  registryVersion: 1;
  state: ProviderGovernanceState;
  liveDeliveryEnabled: boolean;
  killSwitchActive: boolean;
  providerCount: number;
  notice: string;
};

export function getProviderGovernance(): ProviderGovernance {
  return {
    registryVersion: 1,
    state: "unconfigured",
    liveDeliveryEnabled: false,
    killSwitchActive: true,
    providerCount: 0,
    notice: "Nessuna fonte ferroviaria autorizzata è configurata. Binario sopprime i dati realtime e gli avvisi operativi.",
  };
}

export function mayDeliverRealtime(governance: ProviderGovernance) {
  return governance.state === "active" && governance.liveDeliveryEnabled && !governance.killSwitchActive && governance.providerCount > 0;
}
