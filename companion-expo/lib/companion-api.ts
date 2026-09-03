import type { CompanionPlannedRoute } from "@/lib/planned-route-reminder";
type Status = { syncEnabled: boolean };
const endpoint = process.env.EXPO_PUBLIC_BINARIO_SYNC_URL?.trim() ?? "";
export function isCompanionSyncConfigured() { return /^https:\/\//i.test(endpoint); }
export async function getCompanionSyncStatus(): Promise<Status | null> { return null; }
export async function redeemCompanionPairingCode(_code: string) { return false; }
export async function setCompanionSyncEnabled(_enabled: boolean) { return false; }
export async function revokeThisCompanionDevice() { return false; }
export async function fetchCompanionPlannedRoutes(): Promise<CompanionPlannedRoute[]> { return []; }
export async function pushCompanionPlannedRoutes(_routes: readonly CompanionPlannedRoute[]): Promise<number | null> { return null; }
