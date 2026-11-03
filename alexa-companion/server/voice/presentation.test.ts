import { SUPPORTED_LOCALES } from "@shared/i18n";
import { describe, expect, it } from "vitest";
import {
  aplText,
  buildRenderDocumentDirective,
  buildScreenDatasource,
  parseScreenEvent,
  SCREEN_COLORS,
  SCREEN_SIZE_CLASSES,
  supportsApl,
  type BinarioScreen,
} from "./presentation";

type Envelope = Parameters<typeof supportsApl>[0];
type Request = Parameters<typeof parseScreenEvent>[0];

const CHECKED_AT = new Date("2026-10-31T13:05:00Z");
const departures: BinarioScreen = { kind: "departures", routeName: "Casa & <lavoro>" };

function envelopeWith(supportedInterfaces: Record<string, unknown> | undefined): Envelope {
  return {
    version: "1.0",
    context: { System: { application: { applicationId: "skill" }, user: { userId: "u" }, device: supportedInterfaces ? { deviceId: "d", supportedInterfaces } : undefined } },
    request: { type: "LaunchRequest", requestId: "r", timestamp: CHECKED_AT.toISOString(), locale: "it-IT" },
  } as unknown as Envelope;
}

function userEvent(argumentsList: unknown[], token = "binario.myRoutes"): Request {
  return { type: "Alexa.Presentation.APL.UserEvent", requestId: "r", timestamp: CHECKED_AT.toISOString(), locale: "it-IT", token, arguments: argumentsList } as unknown as Request;
}

/** Every object of the document tree, so invariants hold for components at any depth. */
function objects(value: unknown): Array<Record<string, unknown>> {
  if (Array.isArray(value)) return value.flatMap(objects);
  if (!value || typeof value !== "object") return [];
  const record = value as Record<string, unknown>;
  return [record, ...Object.values(record).flatMap(objects)];
}

function contrast(first: string, second: string) {
  const luminance = (colour: string) => {
    const [r, g, b] = [1, 3, 5].map(index => {
      const channel = Number.parseInt(colour.slice(index, index + 2), 16) / 255;
      return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const [light, dark] = [luminance(first), luminance(second)].sort((a, b) => b - a);
  return (light + 0.05) / (dark + 0.05);
}

describe("APL gating", () => {
  it.each([
    ["no device", undefined, false],
    ["voice-only device", {}, false],
    ["APL without runtime", { "Alexa.Presentation.APL": {} }, false],
    ["APL 1.0", { "Alexa.Presentation.APL": { runtime: { maxVersion: "1.0" } } }, false],
    ["APL 1.3", { "Alexa.Presentation.APL": { runtime: { maxVersion: "1.3" } } }, false],
    ["APL 1.4", { "Alexa.Presentation.APL": { runtime: { maxVersion: "1.4" } } }, true],
    ["APL 1.8", { "Alexa.Presentation.APL": { runtime: { maxVersion: "1.8" } } }, true],
    ["APL 2024.2", { "Alexa.Presentation.APL": { runtime: { maxVersion: "2024.2" } } }, true],
    ["garbage version", { "Alexa.Presentation.APL": { runtime: { maxVersion: "latest" } } }, false],
  ] as const)("%s → %s", (_label, interfaces, expected) => {
    expect(supportsApl(envelopeWith(interfaces as Record<string, unknown> | undefined))).toBe(expected);
  });
});

describe("screen datasource", () => {
  it("states the unavailable real-time data, its source and the check time in Italian", () => {
    const data = buildScreenDatasource(departures, { locale: "it-IT", checkedAt: CHECKED_AT });
    expect(data.title).toBe("Partenze");
    expect(data.quality).toEqual({
      state: "unavailable",
      label: "Dati non disponibili in tempo reale",
      source: "Fonte: nessun provider ferroviario in tempo reale collegato",
      checked: "Verificato alle 14:05 CET",
      accessibilityLabel:
        "Dati non disponibili in tempo reale. Fonte: nessun provider ferroviario in tempo reale collegato. Verificato alle 14:05 CET.",
    });
    expect(data.message).toContain("non inventa partenze");
  });

  it.each([
    ["en-US", "Departures", "Real-time data is not available"],
    ["en-GB", "Departures", "Real-time data is not available"],
    ["de-DE", "Abfahrten", "Echtzeitdaten sind nicht verfügbar"],
    ["fr-FR", "Départs", "Les données en temps réel ne sont pas disponibles"],
    ["es-ES", "Salidas", "Los datos en tiempo real no están disponibles"],
    ["ar-SA", "المغادرة", "البيانات في الوقت الفعلي غير متاحة"],
    ["hi-IN", "प्रस्थान", "रीयल-टाइम डेटा उपलब्ध नहीं है"],
    ["ja-JP", "出発", "リアルタイムデータは利用できません"],
    ["pt-BR", "Partidas", "Os dados em tempo real não estão disponíveis"],
  ] as const)("localizes the departures screen for %s", (locale, title, label) => {
    const data = buildScreenDatasource(departures, { locale, checkedAt: CHECKED_AT });
    expect(data.title).toBe(title);
    expect(data.quality.label).toBe(label);
    // Arabic formats the time with Arabic-Indic digits.
    expect(data.quality.checked).toMatch(/[\d٠-٩]{2}:[0٠][5٥]/);
  });

  it("right-aligns the Arabic screen text and leaves the other locales left-aligned", () => {
    const alignments = (locale: "ar-SA" | "it-IT") =>
      (buildRenderDocumentDirective(departures, { locale, checkedAt: CHECKED_AT }).document.resources as Array<{
        strings?: Record<string, string>;
      }>).flatMap(resource => resource.strings?.binarioTextAlign ?? []);
    expect(alignments("ar-SA")).toEqual(["right", "center"]);
    expect(alignments("it-IT")).toEqual(["left", "center"]);
  });

  it("aligns every Text of the document, so Arabic route rows and hints are right-aligned too", () => {
    const texts: Array<Record<string, unknown>> = [];
    const walk = (node: unknown): void => {
      if (Array.isArray(node)) return node.forEach(walk);
      if (!node || typeof node !== "object") return;
      const record = node as Record<string, unknown>;
      if (record.type === "Text") texts.push(record);
      Object.values(record).forEach(walk);
    };
    walk(buildRenderDocumentDirective(departures, { locale: "ar-SA", checkedAt: CHECKED_AT }).document);
    expect(texts.length).toBeGreaterThan(10);
    for (const text of texts)
      expect(["@binarioTextAlign", "center"], String(text.text)).toContain(text.textAlign);
    for (const bound of ["${route.text}", "${payload.binario.hints.title}", "${data}"])
      expect(texts.find(text => text.text === bound)?.textAlign, bound).toBe("@binarioTextAlign");
  });

  it("labels a demo catalogue explicitly instead of presenting it as live", () => {
    const data = buildScreenDatasource(departures, { locale: "it-IT", checkedAt: CHECKED_AT, quality: "demo" });
    expect(data.quality.state).toBe("demo");
    expect(data.quality.label).toBe("Catalogo dimostrativo locale — non live");
    expect(data.quality.source).toBe("Fonte: dati dimostrativi, non operativi");
  });

  it("shows user text literally: APL markup is escaped, the plain value feeds accessibility and events", () => {
    const routes: BinarioScreen = {
      kind: "myRoutes",
      routes: [{ name: "Casa & <b>lavoro</b>\u0007", isPinned: true }, { name: "Mare", isPinned: false }],
      remainingCount: 0,
    };
    const data = buildScreenDatasource(routes, { locale: "it-IT", checkedAt: CHECKED_AT });
    expect(data.routes[0]).toEqual({
      text: "Casa &amp; &lt;b&gt;lavoro&lt;/b&gt;",
      name: "Casa & <b>lavoro</b>",
      pinnedText: "Fissata",
      accessibilityLabel: "Prossimi treni per Casa & <b>lavoro</b> (Fissata)",
    });
    expect(data.routes[1].pinnedText).toBe("");
    expect(buildScreenDatasource(departures, { locale: "it-IT", checkedAt: CHECKED_AT }).hero).toBe("Casa &amp; &lt;lavoro&gt;");
    expect(aplText("${payload.secret}")).toBe("${payload.secret}");
    // Long names are cut by code point: an emoji on the boundary is never split into a lone surrogate.
    const cut = aplText(`${"a".repeat(119)}🚆🚆`);
    expect(cut).toBe(`${"a".repeat(119)}🚆`);
    expect(cut).not.toMatch(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/);
  });

  it("projects the spoken route list: same names, the dashboard remainder, no other route fields", () => {
    const data = buildScreenDatasource(
      { kind: "myRoutes", routes: [{ name: "Casa", isPinned: false }], remainingCount: 3 },
      { locale: "it-IT", checkedAt: CHECKED_AT }
    );
    expect(data.more).toBe("Altre 3 tratte sono nella dashboard Binario.");
    expect(Object.keys(data.routes[0]).sort()).toEqual(["accessibilityLabel", "name", "pinnedText", "text"]);
    const empty = buildScreenDatasource({ kind: "myRoutes", routes: [], remainingCount: 0 }, { locale: "it-IT", checkedAt: CHECKED_AT });
    expect(empty.message).toBe("Non hai ancora salvato alcuna tratta in Binario.");
    expect(empty.actions).toEqual([]);
  });

  it("names the train asked for, or says none was given, without any status", () => {
    const named = buildScreenDatasource({ kind: "trainStatus", trainNumber: "9512" }, { locale: "it-IT", checkedAt: CHECKED_AT });
    expect(named.hero).toBe("Treno 9512");
    const missing = buildScreenDatasource({ kind: "trainStatus" }, { locale: "en-US", checkedAt: CHECKED_AT });
    expect(missing.hero).toBe("No train number given");
  });
});

describe("APL document", () => {
  const directive = buildRenderDocumentDirective(departures, { locale: "it-IT", checkedAt: CHECKED_AT });
  const nodes = objects(directive.document);

  it("is a self-contained APL 1.4 document bound to one datasource", () => {
    expect(directive.type).toBe("Alexa.Presentation.APL.RenderDocument");
    expect(directive.token).toBe("binario.departures");
    expect(directive.document).toMatchObject({ type: "APL", version: "1.4", theme: "dark" });
    expect(Object.keys(directive.datasources)).toEqual(["binario"]);
    const serialized = JSON.stringify(directive);
    // No remote packages, images, video, fonts or URLs; small enough for any skill response.
    expect(directive.document).not.toHaveProperty("import");
    expect(serialized).not.toMatch(/https?:\/\//);
    expect(nodes.map(node => node.type)).not.toEqual(expect.arrayContaining(["Image", "VectorGraphic", "Video"]));
    expect(serialized.length).toBeLessThan(24_000);
  });

  it("switches to the light tokens only through the document theme", () => {
    const light = buildRenderDocumentDirective(departures, { locale: "it-IT", checkedAt: CHECKED_AT, theme: "light" });
    expect(light.document.theme).toBe("light");
    expect(JSON.stringify(light.document.resources)).toContain("${viewport.theme == 'light'}");
  });

  it("keeps every text role at WCAG AA in both themes", () => {
    for (const colors of [SCREEN_COLORS.dark, SCREEN_COLORS.light]) {
      const pairs: Array<[string, string]> = [
        [colors.binarioOnHeader, colors.binarioHeader],
        [colors.binarioOnHeaderVariant, colors.binarioHeader],
        [colors.binarioOnBackground, colors.binarioBackground],
        [colors.binarioOnSurfaceVariant, colors.binarioBackground],
        [colors.binarioOnSurface, colors.binarioSurface],
        [colors.binarioOnSurfaceVariant, colors.binarioSurface],
        [colors.binarioOnSurface, colors.binarioSurfacePressed],
        [colors.binarioOnPrimary, colors.binarioPrimary],
        [colors.binarioOnPrimaryContainer, colors.binarioPrimaryContainer],
      ];
      for (const [foreground, background] of pairs)
        expect(contrast(foreground, background), `${foreground} on ${background}`).toBeGreaterThanOrEqual(4.5);
      // Borders and focus rings are non-text: 3:1.
      for (const background of [colors.binarioBackground, colors.binarioSurface]) {
        expect(contrast(colors.binarioOutline, background)).toBeGreaterThanOrEqual(3);
        expect(contrast(colors.binarioFocus, background)).toBeGreaterThanOrEqual(3);
      }
    }
    expect(SCREEN_COLORS.light.binarioPrimary).toBe("#2E7D32");
    expect(SCREEN_COLORS.dark.binarioPrimary).toBe("#9BDD9E");
  });

  it("has one responsive block per Echo size class, read from the viewport and not the product", () => {
    expect(SCREEN_SIZE_CLASSES.map(block => block.sizeClass)).toEqual(["small", "medium", "large", "xlarge", "round"]);
    for (const block of SCREEN_SIZE_CLASSES.slice(1)) expect(block.when).toMatch(/^\$\{viewport\.(width|shape)/);
    expect(SCREEN_SIZE_CLASSES.find(block => block.sizeClass === "large")?.strings.binarioPanelDirection).toBe("row");
    expect(SCREEN_SIZE_CLASSES.find(block => block.sizeClass === "xlarge")?.strings.binarioHintsDisplay).toBe("normal");
    expect(SCREEN_SIZE_CLASSES.find(block => block.sizeClass === "round")?.strings.binarioTextAlign).toBe("center");
  });

  it("gives every touch target at least 48 dp, a button role and a spoken label", () => {
    for (const block of SCREEN_SIZE_CLASSES) {
      const target = block.dimensions.binarioTouchTarget;
      if (target) expect(Number.parseInt(target, 10), block.sizeClass).toBeGreaterThanOrEqual(48);
    }
    const touchables = nodes.filter(node => node.type === "TouchWrapper");
    expect(touchables.length).toBeGreaterThan(0);
    for (const touchable of touchables) {
      expect(touchable).toMatchObject({
        minHeight: "@binarioTouchTarget",
        minWidth: "@binarioTouchTarget",
        role: "button",
        accessibilityLabel: expect.stringMatching(/^\$\{/),
        onPress: { type: "SendEvent" },
      });
    }
  });

  it("binds every value through the datasource, never by concatenation into the document", () => {
    const serializedDocument = JSON.stringify(directive.document);
    expect(serializedDocument).not.toContain("Casa");
    expect(serializedDocument).not.toContain("Partenze");
  });
});

describe("screen events", () => {
  it.each([
    [["binario.myRoutes"], { action: "myRoutes" }],
    [["binario.myRoutes", ""], { action: "myRoutes" }],
    [["binario.nextTrains", ""], { action: "nextTrains" }],
    [["binario.nextTrains", "Casa & lavoro"], { action: "nextTrains", routeName: "Casa & lavoro" }],
  ] as const)("accepts %j", (args, expected) => {
    expect(parseScreenEvent(userEvent([...args]))).toEqual(expected);
  });

  it.each([
    [["binario.deleteAccount"]],
    [["binario.myRoutes", "Casa"]],
    [["binario.nextTrains", 42]],
    [["binario.nextTrains", "x".repeat(201)]],
    [["binario.nextTrains", "Casa", "extra"]],
    [[]],
  ])("rejects %j", args => {
    expect(parseScreenEvent(userEvent(args))).toBeNull();
  });

  it("ignores events from documents that are not Binario's and non-event requests", () => {
    expect(parseScreenEvent(userEvent(["binario.myRoutes"], "another-skill"))).toBeNull();
    expect(parseScreenEvent({ type: "LaunchRequest", requestId: "r", timestamp: "", locale: "it-IT" } as unknown as Request)).toBeNull();
  });

  it("covers every Alexa publishing locale", () => {
    for (const locale of SUPPORTED_LOCALES)
      expect(buildScreenDatasource({ kind: "home", message: "x" }, { locale, checkedAt: CHECKED_AT }).hints.items).toHaveLength(3);
  });
});
