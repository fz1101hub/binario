import {
  getRequestType,
  type HandlerInput,
  type ResponseInterceptor,
} from "ask-sdk-core";
import {
  formatMessage,
  formatPlural,
  messagesForLocale,
  resolveLocale,
  type SupportedLocale,
} from "@shared/i18n";
import { plainSpeechText } from "./speech";

/**
 * Echo Show projection of the voice answers (docs/ECHO_SHOW_APL_UI_SPEC.md).
 *
 * Handlers describe what they just spoke as a {@link BinarioScreen}; the response interceptor
 * renders it only when the device exposes Alexa.Presentation.APL, so speakers and devices without
 * the interface keep exactly the voice and card response. A screen carries only values the skill
 * already speaks or shows on its card: never tokens, Alexa or Binario identifiers, origins or
 * destinations, and nothing personal before the account link is verified (the handlers set a
 * personal screen only after that check). The document uses no remote packages, images or fonts.
 */

export const APL_INTERFACE = "Alexa.Presentation.APL";
export const APL_DOCUMENT_VERSION = "1.4";
const SCREEN_ATTRIBUTE = "binarioScreen";
const EVENT_NEXT_TRAINS = "binario.nextTrains";
const EVENT_MY_ROUTES = "binario.myRoutes";
const TOKEN_PREFIX = "binario.";
const MAX_DISPLAY_LENGTH = 120;
const MAX_EVENT_ROUTE_LENGTH = 200;
/** Binario covers Italian railways: times are shown in Italian local time, with the zone name. */
const RAIL_TIME_ZONE = "Europe/Rome";

export type BinarioScreen =
  | { kind: "home"; message: string }
  | { kind: "departures"; routeName: string }
  | { kind: "trainStatus"; trainNumber?: string }
  | {
      kind: "myRoutes";
      routes: ReadonlyArray<{ name: string; isPinned: boolean }>;
      remainingCount: number;
    };

/** "unavailable" is the only state the skill produces today; "demo" labels a demo catalogue. */
export type ScreenDataQuality = "unavailable" | "demo";

export type ScreenOptions = {
  locale: SupportedLocale;
  checkedAt: Date;
  quality?: ScreenDataQuality;
  theme?: "dark" | "light";
};

export type ScreenEvent =
  | { action: "nextTrains"; routeName?: string }
  | { action: "myRoutes" };

type ScreenAction = {
  text: string;
  accessibilityLabel: string;
  event: string;
  routeName: string;
};

export type ScreenDatasource = {
  type: "object";
  screen: BinarioScreen["kind"];
  brand: string;
  title: string;
  quality: {
    state: ScreenDataQuality;
    label: string;
    source: string;
    checked: string;
    accessibilityLabel: string;
  };
  hero: string;
  heroAccessibilityLabel: string;
  message: string;
  routes: Array<{
    text: string;
    name: string;
    pinnedText: string;
    accessibilityLabel: string;
  }>;
  more: string;
  actions: ScreenAction[];
  hints: { title: string; items: string[] };
};

export type RenderDocumentDirective = {
  type: "Alexa.Presentation.APL.RenderDocument";
  token: string;
  document: Record<string, unknown>;
  datasources: { binario: ScreenDatasource };
};

/** True only for devices whose APL runtime renders the document version used here (1.4+). */
export function supportsApl(requestEnvelope: HandlerInput["requestEnvelope"]): boolean {
  const apl = requestEnvelope.context?.System?.device?.supportedInterfaces?.[APL_INTERFACE];
  if (!apl) return false;
  const version = /^(\d+)\.(\d+)(?:\.\d+)?$/.exec(apl.runtime?.maxVersion ?? "");
  if (!version) return false;
  const [major, minor] = [Number(version[1]), Number(version[2])];
  return major > 1 || (major === 1 && minor >= 4);
}

/** Records the screen that projects the response being built; null clears it (error paths). */
export function setScreen(input: HandlerInput, screen: BinarioScreen | null) {
  const attributes = input.attributesManager.getRequestAttributes();
  attributes[SCREEN_ATTRIBUTE] = screen;
  input.attributesManager.setRequestAttributes(attributes);
}

function currentScreen(input: HandlerInput): BinarioScreen | null {
  return (input.attributesManager.getRequestAttributes()[SCREEN_ATTRIBUTE] as BinarioScreen | null) ?? null;
}

/** Validates a TouchWrapper SendEvent against the two actions the document can send. */
export function parseScreenEvent(request: HandlerInput["requestEnvelope"]["request"]): ScreenEvent | null {
  if (request.type !== "Alexa.Presentation.APL.UserEvent") return null;
  if (typeof request.token !== "string" || !request.token.startsWith(TOKEN_PREFIX)) return null;
  const [action, routeName, ...rest] = Array.isArray(request.arguments) ? request.arguments : [];
  if (rest.length) return null;
  if (action === EVENT_MY_ROUTES && (routeName === undefined || routeName === "")) return { action: "myRoutes" };
  if (action !== EVENT_NEXT_TRAINS) return null;
  if (routeName === undefined || routeName === "") return { action: "nextTrains" };
  if (typeof routeName !== "string" || routeName.length > MAX_EVENT_ROUTE_LENGTH) return null;
  return { action: "nextTrains", routeName };
}

/** Display text without XML-forbidden characters, cut by code point so no emoji is split. */
function plainLabel(value: string) {
  return Array.from(plainSpeechText(value)).slice(0, MAX_DISPLAY_LENGTH).join("");
}

/** APL Text understands a little markup: user text is shown literally, never interpreted. */
export function aplText(value: string) {
  return plainLabel(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function sentenceCase(value: string, locale: SupportedLocale) {
  return value.charAt(0).toLocaleUpperCase(locale) + value.slice(1);
}

export function formatCheckedTime(checkedAt: Date, locale: SupportedLocale) {
  return new Intl.DateTimeFormat(locale, {
    hour: "2-digit",
    minute: "2-digit",
    timeZone: RAIL_TIME_ZONE,
    timeZoneName: "short",
  }).format(checkedAt);
}

export function buildScreenDatasource(screen: BinarioScreen, options: ScreenOptions): ScreenDatasource {
  const { locale } = options;
  const messages = messagesForLocale(locale);
  const t = messages.screen;
  const state = options.quality ?? "unavailable";
  const qualityLabel = state === "demo" ? t.demoData : sentenceCase(messages.voice.liveDataUnavailable, locale);
  const source = state === "demo" ? t.sourceDemo : t.sourceNone;
  const checked = formatMessage(t.checkedAt, { time: formatCheckedTime(options.checkedAt, locale) });
  const nextTrains: ScreenAction = {
    text: aplText(t.nextTrainsAction),
    accessibilityLabel: t.nextTrainsAction,
    event: EVENT_NEXT_TRAINS,
    routeName: "",
  };
  const myRoutes: ScreenAction = {
    text: aplText(t.myRoutesAction),
    accessibilityLabel: t.myRoutesAction,
    event: EVENT_MY_ROUTES,
    routeName: "",
  };

  const base: ScreenDatasource = {
    type: "object",
    screen: screen.kind,
    brand: "Binario",
    title: "",
    quality: {
      state,
      label: aplText(qualityLabel),
      source: aplText(source),
      checked: aplText(checked),
      accessibilityLabel: `${qualityLabel}. ${source}. ${checked}.`,
    },
    hero: "",
    heroAccessibilityLabel: "",
    message: "",
    routes: [],
    more: "",
    actions: [],
    hints: {
      title: aplText(t.hintsTitle),
      items: [t.hintNextTrains, t.hintTrainStatus, t.hintMyRoutes].map(aplText),
    },
  };

  switch (screen.kind) {
    case "home":
      return {
        ...base,
        title: aplText(t.homeTitle),
        message: aplText(screen.message),
        actions: [nextTrains, myRoutes],
      };
    case "departures":
      return {
        ...base,
        title: aplText(t.departuresTitle),
        hero: aplText(screen.routeName),
        heroAccessibilityLabel: plainLabel(screen.routeName),
        message: aplText(t.noTimetable),
        actions: [myRoutes],
      };
    case "trainStatus": {
      const heading = screen.trainNumber
        ? formatMessage(t.trainNumber, { trainNumber: plainLabel(screen.trainNumber) })
        : t.trainNumberMissing;
      return {
        ...base,
        title: aplText(t.trainStatusTitle),
        hero: aplText(heading),
        heroAccessibilityLabel: heading,
        message: aplText(t.noTimetable),
        actions: [nextTrains, myRoutes],
      };
    }
    case "myRoutes":
      return {
        ...base,
        title: aplText(t.myRoutesTitle),
        message: screen.routes.length ? "" : aplText(messages.voice.noRoutes),
        routes: screen.routes.map(route => ({
          text: aplText(route.name),
          name: plainSpeechText(route.name),
          pinnedText: route.isPinned ? aplText(t.pinned) : "",
          accessibilityLabel: formatMessage(t.showRoute, { routeName: plainLabel(route.name) }) +
            (route.isPinned ? ` (${t.pinned})` : ""),
        })),
        more: screen.remainingCount > 0
          ? aplText(formatPlural(locale, screen.remainingCount, t.moreRoutes))
          : "",
        actions: screen.routes.length ? [nextTrains] : [],
      };
  }
}

export function buildRenderDocumentDirective(
  screen: BinarioScreen,
  options: ScreenOptions
): RenderDocumentDirective {
  return {
    type: "Alexa.Presentation.APL.RenderDocument",
    token: `${TOKEN_PREFIX}${screen.kind}`,
    document: { ...BINARIO_APL_DOCUMENT, theme: options.theme ?? "dark" },
    datasources: { binario: buildScreenDatasource(screen, options) },
  };
}

function requestTime(input: HandlerInput) {
  const timestamp = Date.parse(input.requestEnvelope.request.timestamp ?? "");
  return Number.isFinite(timestamp) ? new Date(timestamp) : new Date();
}

export const screenResponseInterceptor: ResponseInterceptor = {
  process(input, response) {
    const screen = currentScreen(input);
    if (!response || !screen || !supportsApl(input.requestEnvelope)) return;
    if (getRequestType(input.requestEnvelope) === "SessionEndedRequest") return;
    let directive: RenderDocumentDirective;
    try {
      directive = buildRenderDocumentDirective(screen, {
        locale: resolveLocale(input.requestEnvelope.request.locale),
        checkedAt: requestTime(input),
      });
    } catch {
      // The screen is an addition: if it cannot be built the voice answer still goes out as is.
      return;
    }
    response.directives = [...(response.directives ?? []), directive];
    // On a screen device an undefined shouldEndSession keeps the session open with the
    // microphone closed: the document stays readable and its buttons can still reach the skill.
    // Responses that keep listening (shouldEndSession false) are left as they are.
    if (response.shouldEndSession === true) delete response.shouldEndSession;
  },
};

/* ------------------------------------------------------------------------------------------ */
/* APL document                                                                                 */
/* ------------------------------------------------------------------------------------------ */

/** Binario 2.0 tokens (design/BINARIO_2_DESIGN_SYSTEM.md). Echo skills render dark by default. */
export const SCREEN_COLORS = {
  dark: {
    binarioBackground: "#0D1710",
    binarioSurface: "#142018",
    binarioSurfacePressed: "#1D2C22",
    binarioHeader: "#205B2A",
    binarioOnHeader: "#FFFFFF",
    binarioOnHeaderVariant: "#DDF0DE",
    binarioOnBackground: "#FFFFFF",
    binarioOnSurface: "#FFFFFF",
    binarioOnSurfaceVariant: "#C3D0C4",
    binarioPrimary: "#9BDD9E",
    binarioOnPrimary: "#0D1710",
    binarioPrimaryContainer: "#205B2A",
    binarioOnPrimaryContainer: "#DDF0DE",
    binarioOutline: "#8A9E8D",
    binarioFocus: "#FFFFFF",
  },
  light: {
    binarioBackground: "#F7F8F5",
    binarioSurface: "#FFFFFF",
    binarioSurfacePressed: "#DDF0DE",
    binarioHeader: "#205B2A",
    binarioOnHeader: "#FFFFFF",
    binarioOnHeaderVariant: "#DDF0DE",
    binarioOnBackground: "#17201A",
    binarioOnSurface: "#17201A",
    binarioOnSurfaceVariant: "#47564A",
    binarioPrimary: "#2E7D32",
    binarioOnPrimary: "#FFFFFF",
    binarioPrimaryContainer: "#DDF0DE",
    binarioOnPrimaryContainer: "#0D3912",
    binarioOutline: "#74857A",
    binarioFocus: "#17201A",
  },
} as const;

type SizeClass = "small" | "medium" | "large" | "xlarge" | "round";

/**
 * Size classes from the device viewport, never from a product name (spec: viewport matrix).
 * small: Echo Show 5 (960x480 dp) and anything below; medium: Echo Show 8, first-generation Show,
 * Echo Hub (about 960-1279 dp wide); large: Echo Show 10 (1280x800 dp); xlarge: Echo Show 15/21
 * (1920x1080 dp); round: the original Echo Spot. Later blocks override earlier ones.
 */
export const SCREEN_SIZE_CLASSES: ReadonlyArray<{
  sizeClass: SizeClass;
  when?: string;
  dimensions: Record<string, string>;
  strings: Record<string, string>;
}> = [
  {
    sizeClass: "small",
    dimensions: {
      binarioGutter: "32dp", binarioSpacing: "16dp", binarioGap: "16dp", binarioRadius: "16dp",
      binarioTouchTarget: "64dp", binarioHeaderPaddingTop: "16dp", binarioBrandSize: "18dp",
      binarioTitleSize: "30dp", binarioHeroSize: "40dp", binarioBodySize: "24dp", binarioLabelSize: "20dp",
      binarioStatusWidth: "100%", binarioMainWidth: "100%", binarioHintsWidth: "100%",
    },
    strings: {
      binarioSizeClass: "small", binarioPanelDirection: "column", binarioActionsDirection: "column",
      binarioHintsDisplay: "none", binarioTextAlign: "left",
    },
  },
  {
    sizeClass: "medium",
    when: "${viewport.width >= 960 && viewport.height >= 540}",
    dimensions: {
      binarioGutter: "40dp", binarioTitleSize: "34dp", binarioHeroSize: "48dp",
      binarioBodySize: "26dp", binarioLabelSize: "22dp",
    },
    strings: { binarioSizeClass: "medium", binarioActionsDirection: "row", binarioHintsDisplay: "normal" },
  },
  {
    sizeClass: "large",
    when: "${viewport.width >= 1280 && viewport.height >= 720}",
    dimensions: {
      binarioGutter: "48dp", binarioGap: "24dp", binarioTitleSize: "40dp", binarioHeroSize: "56dp",
      binarioBodySize: "28dp", binarioLabelSize: "24dp", binarioStatusWidth: "34%", binarioMainWidth: "auto",
    },
    strings: { binarioSizeClass: "large", binarioPanelDirection: "row", binarioHintsDisplay: "none" },
  },
  {
    sizeClass: "xlarge",
    when: "${viewport.width >= 1920 && viewport.height >= 1000}",
    dimensions: {
      binarioGutter: "64dp", binarioGap: "32dp", binarioTouchTarget: "80dp", binarioBrandSize: "22dp",
      binarioTitleSize: "52dp", binarioHeroSize: "72dp", binarioBodySize: "34dp", binarioLabelSize: "28dp",
      binarioStatusWidth: "26%", binarioHintsWidth: "24%",
    },
    strings: { binarioSizeClass: "xlarge", binarioHintsDisplay: "normal" },
  },
  {
    sizeClass: "round",
    when: "${viewport.shape == 'round'}",
    dimensions: {
      binarioGutter: "56dp", binarioHeaderPaddingTop: "40dp", binarioTouchTarget: "64dp",
      binarioTitleSize: "28dp", binarioHeroSize: "34dp", binarioBodySize: "22dp", binarioLabelSize: "20dp",
      binarioStatusWidth: "100%", binarioMainWidth: "100%", binarioHintsWidth: "100%",
    },
    strings: {
      binarioSizeClass: "round", binarioPanelDirection: "column", binarioActionsDirection: "column",
      binarioHintsDisplay: "none", binarioTextAlign: "center",
    },
  },
];

const padding = (horizontal: string, vertical: string) => ({
  paddingLeft: horizontal,
  paddingRight: horizontal,
  paddingTop: vertical,
  paddingBottom: vertical,
});

const BINARIO_APL_DOCUMENT: Record<string, unknown> = {
  type: "APL",
  version: APL_DOCUMENT_VERSION,
  theme: "dark",
  description: "Binario 2.0 Echo Show screen (alexa-companion/docs/ECHO_SHOW_APL_UI_SPEC.md)",
  resources: [
    { description: "Binario 2.0 dark tokens", colors: SCREEN_COLORS.dark },
    { description: "Binario 2.0 light tokens", when: "${viewport.theme == 'light'}", colors: SCREEN_COLORS.light },
    ...SCREEN_SIZE_CLASSES.map(({ sizeClass, when, dimensions, strings }) => ({
      description: `Size class ${sizeClass}`,
      ...(when ? { when } : {}),
      dimensions,
      strings,
    })),
  ],
  styles: {
    binarioRowFrame: {
      values: [
        { backgroundColor: "@binarioSurface", borderColor: "@binarioSurface" },
        { when: "${state.pressed}", backgroundColor: "@binarioSurfacePressed" },
        { when: "${state.focused}", borderColor: "@binarioFocus" },
      ],
    },
    binarioActionFrame: {
      values: [
        { backgroundColor: "@binarioPrimary", borderColor: "@binarioPrimary" },
        { when: "${state.pressed}", opacity: 0.85 },
        { when: "${state.focused}", borderColor: "@binarioFocus" },
      ],
    },
  },
  layouts: {
    BinarioQualityBanner: {
      description: "Data state, source and check time: always text, never colour alone.",
      parameters: ["quality"],
      items: [
        {
          type: "Frame",
          width: "100%",
          backgroundColor: "@binarioSurface",
          borderColor: "@binarioOutline",
          borderWidth: "2dp",
          borderRadius: "@binarioRadius",
          item: {
            type: "Container",
            width: "100%",
            accessibilityLabel: "${quality.accessibilityLabel}",
            ...padding("@binarioSpacing", "@binarioSpacing"),
            items: [
              { type: "Text", text: "${quality.label}", color: "@binarioOnSurface", fontSize: "@binarioBodySize", fontWeight: "700", textAlign: "@binarioTextAlign" },
              { type: "Text", text: "${quality.source}", color: "@binarioOnSurfaceVariant", fontSize: "@binarioLabelSize", textAlign: "@binarioTextAlign", spacing: "8dp" },
              { type: "Text", text: "${quality.checked}", color: "@binarioOnSurfaceVariant", fontSize: "@binarioLabelSize", textAlign: "@binarioTextAlign", spacing: "4dp" },
            ],
          },
        },
      ],
    },
    BinarioRouteRow: {
      description: "A saved route; touching it asks the skill for that route's departures.",
      parameters: ["route"],
      items: [
        {
          type: "TouchWrapper",
          width: "100%",
          minWidth: "@binarioTouchTarget",
          minHeight: "@binarioTouchTarget",
          role: "button",
          accessibilityLabel: "${route.accessibilityLabel}",
          onPress: { type: "SendEvent", arguments: [EVENT_NEXT_TRAINS, "${route.name}"] },
          item: {
            type: "Frame",
            style: "binarioRowFrame",
            inheritParentState: true,
            width: "100%",
            minHeight: "@binarioTouchTarget",
            borderWidth: "3dp",
            borderRadius: "@binarioRadius",
            item: {
              type: "Container",
              direction: "row",
              alignItems: "center",
              width: "100%",
              minHeight: "@binarioTouchTarget",
              ...padding("@binarioSpacing", "8dp"),
              items: [
                { type: "Text", text: "${route.text}", grow: 1, shrink: 1, maxLines: 2, color: "@binarioOnSurface", fontSize: "@binarioBodySize", fontWeight: "500" },
                {
                  type: "Frame",
                  when: "${route.pinnedText != ''}",
                  spacing: "@binarioSpacing",
                  backgroundColor: "@binarioPrimaryContainer",
                  borderRadius: "@binarioRadius",
                  item: { type: "Text", text: "${route.pinnedText}", color: "@binarioOnPrimaryContainer", fontSize: "@binarioLabelSize", ...padding("12dp", "4dp") },
                },
              ],
            },
          },
        },
      ],
    },
    BinarioAction: {
      description: "A primary button that sends one allowlisted event back to the skill.",
      parameters: ["action"],
      items: [
        {
          type: "TouchWrapper",
          minWidth: "@binarioTouchTarget",
          minHeight: "@binarioTouchTarget",
          role: "button",
          accessibilityLabel: "${action.accessibilityLabel}",
          onPress: { type: "SendEvent", arguments: ["${action.event}", "${action.routeName}"] },
          item: {
            type: "Frame",
            style: "binarioActionFrame",
            inheritParentState: true,
            minHeight: "@binarioTouchTarget",
            borderWidth: "3dp",
            borderRadius: "@binarioRadius",
            item: {
              type: "Container",
              justifyContent: "center",
              minHeight: "@binarioTouchTarget",
              ...padding("@binarioGutter", "8dp"),
              items: [
                { type: "Text", text: "${action.text}", color: "@binarioOnPrimary", fontSize: "@binarioBodySize", fontWeight: "700", textAlign: "center" },
              ],
            },
          },
        },
      ],
    },
  },
  mainTemplate: {
    parameters: ["payload"],
    items: [
      {
        type: "Frame",
        width: "100vw",
        height: "100vh",
        backgroundColor: "@binarioBackground",
        item: {
          type: "Container",
          width: "100%",
          height: "100%",
          items: [
            {
              type: "Frame",
              width: "100%",
              backgroundColor: "@binarioHeader",
              item: {
                type: "Container",
                width: "100%",
                paddingLeft: "@binarioGutter",
                paddingRight: "@binarioGutter",
                paddingTop: "@binarioHeaderPaddingTop",
                paddingBottom: "16dp",
                items: [
                  { type: "Text", text: "${payload.binario.brand}", color: "@binarioOnHeaderVariant", fontSize: "@binarioBrandSize", fontWeight: "700", letterSpacing: "2dp", textAlign: "@binarioTextAlign" },
                  { type: "Text", text: "${payload.binario.title}", color: "@binarioOnHeader", fontSize: "@binarioTitleSize", fontWeight: "700", maxLines: 2, textAlign: "@binarioTextAlign", role: "header" },
                ],
              },
            },
            {
              type: "ScrollView",
              width: "100%",
              height: "100%",
              shrink: 1,
              item: {
                type: "Container",
                width: "100%",
                direction: "@binarioPanelDirection",
                alignItems: "start",
                ...padding("@binarioGutter", "@binarioGap"),
                items: [
                  {
                    type: "Container",
                    width: "@binarioStatusWidth",
                    items: [{ type: "BinarioQualityBanner", quality: "${payload.binario.quality}" }],
                  },
                  {
                    type: "Container",
                    width: "@binarioMainWidth",
                    grow: 1,
                    shrink: 1,
                    spacing: "@binarioGap",
                    items: [
                      { type: "Text", when: "${payload.binario.hero != ''}", text: "${payload.binario.hero}", accessibilityLabel: "${payload.binario.heroAccessibilityLabel}", color: "@binarioOnBackground", fontSize: "@binarioHeroSize", fontWeight: "700", maxLines: 3, textAlign: "@binarioTextAlign" },
                      { type: "Text", when: "${payload.binario.message != ''}", text: "${payload.binario.message}", color: "@binarioOnSurfaceVariant", fontSize: "@binarioBodySize", textAlign: "@binarioTextAlign", spacing: "@binarioSpacing" },
                      {
                        type: "Container",
                        width: "100%",
                        spacing: "@binarioSpacing",
                        data: "${payload.binario.routes}",
                        items: [{ type: "BinarioRouteRow", route: "${data}", spacing: "@binarioSpacing" }],
                      },
                      { type: "Text", when: "${payload.binario.more != ''}", text: "${payload.binario.more}", color: "@binarioOnSurfaceVariant", fontSize: "@binarioLabelSize", textAlign: "@binarioTextAlign", spacing: "@binarioSpacing" },
                      {
                        type: "Container",
                        direction: "@binarioActionsDirection",
                        spacing: "@binarioGap",
                        data: "${payload.binario.actions}",
                        items: [{ type: "BinarioAction", action: "${data}", spacing: "@binarioSpacing" }],
                      },
                    ],
                  },
                  {
                    type: "Container",
                    width: "@binarioHintsWidth",
                    display: "@binarioHintsDisplay",
                    spacing: "@binarioGap",
                    items: [
                      { type: "Text", text: "${payload.binario.hints.title}", color: "@binarioOnBackground", fontSize: "@binarioLabelSize", fontWeight: "700" },
                      {
                        type: "Container",
                        data: "${payload.binario.hints.items}",
                        items: [{ type: "Text", text: "${data}", color: "@binarioOnSurfaceVariant", fontSize: "@binarioLabelSize", spacing: "8dp" }],
                      },
                    ],
                  },
                ],
              },
            },
          ],
        },
      },
    ],
  },
};
