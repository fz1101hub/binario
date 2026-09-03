import { mayDeliverRealtime, type ProviderGovernance } from "./providerGovernance";

export type OfficialSellerCandidate = {
  sellerId: string;
  sellerName: string;
  providerId: string;
  officialUrl: string;
  approvedHosts: readonly string[];
  licenseVerified: boolean;
  officialLinkVerified: boolean;
};

export type OfficialSellerHandoff = {
  sellerId: string;
  sellerName: string;
  officialUrl: string;
  label: string;
};

export function getConfiguredOfficialSellers(): readonly OfficialSellerCandidate[] {
  return [];
}

function hasSafeOfficialUrl(candidate: OfficialSellerCandidate): boolean {
  try {
    const url = new URL(candidate.officialUrl);
    return url.protocol === "https:" && !url.username && !url.password && !url.port && !url.search && !url.hash && candidate.approvedHosts.map(host => host.toLocaleLowerCase("en-US")).includes(url.hostname.toLocaleLowerCase("en-US"));
  } catch { return false; }
}

export function resolveOfficialSellerHandoff(governance: ProviderGovernance, candidate: OfficialSellerCandidate): OfficialSellerHandoff | null {
  if (!mayDeliverRealtime(governance) || !candidate.sellerId || !candidate.sellerName || !candidate.providerId || !candidate.licenseVerified || !candidate.officialLinkVerified || !hasSafeOfficialUrl(candidate)) return null;
  return { sellerId: candidate.sellerId, sellerName: candidate.sellerName, officialUrl: candidate.officialUrl, label: `Continua sul sito ufficiale di ${candidate.sellerName}` };
}
