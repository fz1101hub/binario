import { NativeModules } from "react-native";
import type { CompanionSnapshot } from "@/lib/companion-snapshot";
import type { LocalPlannedRoute } from "@/lib/local-route-contract";
import { binarioWidgetBridgeModuleName } from "@/lib/native-module-names";
import { buildNativeSurfacePayload, type NativeSurfacePayload } from "@/lib/native-surface-contract";
export function syncNativeCompanionSurfaces(snapshot: CompanionSnapshot, route: LocalPlannedRoute | null) { const bridge = NativeModules[binarioWidgetBridgeModuleName] as { update?: (payload: NativeSurfacePayload) => Promise<void> | void } | undefined; try { void bridge?.update?.(buildNativeSurfacePayload(snapshot, route)); } catch { /* Il companion resta utilizzabile senza superfici native. */ } }
