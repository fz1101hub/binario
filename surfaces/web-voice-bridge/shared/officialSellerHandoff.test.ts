import { describe, expect, it } from "vitest";
import { getConfiguredOfficialSellers, resolveOfficialSellerHandoff, type OfficialSellerCandidate } from "./officialSellerHandoff";
import { getProviderGovernance } from "./providerGovernance";

const activeGovernance = { ...getProviderGovernance(), state: "active" as const, liveDeliveryEnabled: true, killSwitchActive: false, providerCount: 1 };
const approvedSeller: OfficialSellerCandidate = { sellerId: "seller-test", sellerName: "Venditore test", providerId: "provider-test", officialUrl: "https://tickets.example.test/acquista", approvedHosts: ["tickets.example.test"], licenseVerified: true, officialLinkVerified: true };

describe("rinvio al venditore ufficiale", () => {
  it("resta assente finché il registro non contiene un venditore autorizzato", () => {
    expect(getConfiguredOfficialSellers()).toEqual([]);
    expect(resolveOfficialSellerHandoff(getProviderGovernance(), approvedSeller)).toBeNull();
  });

  it("accetta solo un link HTTPS verificato verso l’host ufficiale esplicitamente approvato", () => {
    expect(resolveOfficialSellerHandoff(activeGovernance, approvedSeller)).toEqual({ sellerId: "seller-test", sellerName: "Venditore test", officialUrl: "https://tickets.example.test/acquista", label: "Continua sul sito ufficiale di Venditore test" });
    expect(resolveOfficialSellerHandoff(activeGovernance, { ...approvedSeller, officialUrl: "https://attacker.test/acquista" })).toBeNull();
    expect(resolveOfficialSellerHandoff(activeGovernance, { ...approvedSeller, officialUrl: "https://tickets.example.test/acquista?next=https://attacker.test" })).toBeNull();
    expect(resolveOfficialSellerHandoff(activeGovernance, { ...approvedSeller, officialUrl: "https://utente:segreto@tickets.example.test/acquista" })).toBeNull();
    expect(resolveOfficialSellerHandoff(activeGovernance, { ...approvedSeller, officialUrl: "http://tickets.example.test/acquista" })).toBeNull();
  });
});
