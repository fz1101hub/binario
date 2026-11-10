import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import { buildCompanionHub } from "@/lib/companion-hub";
import { supportedLocales, translate } from "@/lib/i18n";
import { hasAndroidSurfaces, platformTextKey, type PlatformDependentTextKey } from "@/lib/platform-surfaces";

const keys: PlatformDependentTextKey[] = ["alerts.reminderScheduled", "alerts.agendaSimple", "alerts.agendaFull", "alerts.reminderHint", "hub.intro", "hub.noJourney", "guide.expired.body", "guide.unavailable.body", "home.planExpired"];

describe("superfici e testi solo Android", () => {
  it("mostra widget e Wear OS solo nella build Android", () => {
    expect(buildCompanionHub([], "it", "android").surfaces.map(surface => surface.id)).toEqual(["widget", "watch", "alexa"]);
    expect(buildCompanionHub([], "it", "ios").surfaces.map(surface => surface.id)).toEqual(["alexa"]);
    expect(buildCompanionHub([], "it", "web").surfaces.map(surface => surface.id)).toEqual(["alexa"]);
    expect(hasAndroidSurfaces("ios")).toBe(false);
  });

  it("su iOS nessun testo nomina Android, Wear OS, widget o smartwatch, in nessuna lingua", () => {
    for (const locale of supportedLocales) for (const key of keys) {
      const text = translate(locale, platformTextKey(key, "ios"));
      expect(text, `${locale}:${key}`).not.toMatch(/Android|Wear|[Ww]idget|smartwatch|ウィジェット|위젯|小组件|विजेट|วิดเจ็ต|Виджет|виджет|الأداة|ဝစ်ဂျက်|Tiện ích|スマートウォッチ|스마트워치|智能手表|स्मार्टवॉच|สมาร์ตวอทช์|смарт-час|الساعة الذكية|akıllı saat|jam tangan pintar|đồng hồ thông minh/);
      expect(platformTextKey(key, "android"), key).toBe(key);
    }
  });

  it("le schermate usano la variante della piattaforma e nascondono la scheda Telefono e smartwatch fuori da Android", () => {
    const read = (file: string) => readFileSync(path.resolve(import.meta.dirname, "..", file), "utf8");
    expect(read("app/(tabs)/settings.tsx")).toContain('{hasAndroidSurfaces(Platform.OS) ? <View');
    expect(read("app/(tabs)/companion.tsx")).toContain("buildCompanionHub(routes, locale, Platform.OS)");
    const screens = ["app/(tabs)/alerts.tsx", "app/(tabs)/companion.tsx", "app/(tabs)/index.tsx", "app/guide.tsx"].map(read).join("\n");
    for (const key of keys) expect(screens, key).not.toContain(`t("${key}")`);
  });
});
