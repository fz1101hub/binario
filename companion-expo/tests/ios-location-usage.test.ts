import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { describe, expect, it } from "vitest";

const root = path.resolve(import.meta.dirname, "..");
const read = (relativePath: string) => readFileSync(path.join(root, relativePath), "utf8");
const usage = JSON.parse(read("native/ios/location-usage.json")).NSLocationWhenInUseUsageDescription as Record<string, string>;
const plugin = createRequire(import.meta.url)("../plugins/with-binario-ios-local-surfaces.cjs").__test as {
  infoPlistLocales: () => Record<string, Record<string, string>>;
  locationKey: string;
};

describe("scopo della posizione iOS del Companion", () => {
  it("ha un testo specifico per ogni lingua iOS dichiarata", () => {
    const config = read("app.config.ts");
    const iosLocales = JSON.parse(/ios: (\[[^\]]+\])/.exec(config)![1]!) as string[];

    expect(Object.keys(usage).sort()).toEqual([...iosLocales].sort());
    for (const [language, text] of Object.entries(usage)) {
      expect(text.length, language).toBeGreaterThan(40);
      // InfoPlist.strings values are written unescaped by Expo.
      expect(text, language).not.toMatch(/["\\\n]/);
      expect(text, language).not.toContain("$(PRODUCT_NAME)");
      expect(text, language).toContain("Binario Companion");
    }
    expect(new Set(Object.values(usage)).size).toBe(Object.keys(usage).length);
  });

  it("passa l'italiano a expo-location, elimina le chiavi Always e localizza le altre lingue", () => {
    const config = read("app.config.ts");
    expect(config).toContain('"expo-location",');
    expect(config).toContain("locationWhenInUsePermission: locationUsage.NSLocationWhenInUseUsageDescription.it");
    expect(config).toContain("locationAlwaysPermission: false");
    expect(config).toContain("locationAlwaysAndWhenInUsePermission: false");

    const locales = plugin.infoPlistLocales();
    expect(Object.keys(locales)).toHaveLength(16);
    expect(locales.it).toBeUndefined();
    expect(locales["zh-Hans"]).toEqual({ NSLocationWhenInUseUsageDescription: usage["zh-Hans"] });
    expect(locales["pt-BR"]![plugin.locationKey]).toBe(usage["pt-BR"]);
    // A top-level `locales` would also make Expo write Android values-b+<lang> files.
    expect(config).not.toMatch(/^\s*locales:/m);
  });
});
