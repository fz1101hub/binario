import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";

const require = createRequire(import.meta.url);
const withBinarioLocalNotificationsOnly = require("../plugins/with-binario-local-notifications-only.cjs") as (config: Record<string, unknown>) => { mods?: { ios?: { entitlements?: (config: Record<string, unknown>) => Promise<{ modResults: Record<string, unknown> }> } } };

describe("solo notifiche locali su iOS", () => {
  it("toglie l’entitlement push aggiunto da expo-notifications", async () => {
    const config = withBinarioLocalNotificationsOnly({ name: "Binario Companion", slug: "binario-companion-native" });
    const entitlements = config.mods?.ios?.entitlements;
    expect(entitlements).toBeTypeOf("function");
    const result = await entitlements!({ name: "Binario Companion", slug: "binario-companion-native", modResults: { "aps-environment": "development", "keep-me": true }, modRequest: { platform: "ios", modName: "entitlements", introspect: false } });
    expect(result.modResults).toEqual({ "keep-me": true });
  });

  it("è registrato prima di expo-notifications, così il suo mod gira dopo", () => {
    const config = readFileSync(path.resolve(import.meta.dirname, "..", "app.config.ts"), "utf8");
    const plugin = config.indexOf('"./plugins/with-binario-local-notifications-only.cjs"');
    expect(plugin).toBeGreaterThan(0);
    expect(plugin).toBeLessThan(config.indexOf('"expo-notifications"'));
  });
});
