import { formatMessage, messagesForLocale, SUPPORTED_LOCALES } from "@shared/i18n";
import { DrizzleQueryError } from "drizzle-orm";
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it, vi } from "vitest";

const SKILL_ID = "amzn1.ask.skill.test";
const LOCALES = ["it-IT", "en-US", "en-GB", "en-IN", "de-DE", "fr-FR", "es-ES", "es-US", "ar-SA", "hi-IN", "ja-JP", "pt-BR"] as const;

vi.hoisted(() => {
  Object.assign(process.env, {
    ALEXA_ENABLED: "true",
    ALEXA_SKILL_ID: "amzn1.ask.skill.test",
    ALEXA_OAUTH_CLIENT_ID: "alexa-client",
    ALEXA_OAUTH_CLIENT_SECRET: "alexa-secret",
    ALEXA_OAUTH_REDIRECT_URIS: "https://layla.amazon.com/api/skill/link/M1TEST",
    VOICE_TOKEN_PEPPER: "test-pepper-with-plenty-of-entropy-0123456789",
  });
});

vi.mock("../db", () => ({
  createAuthorizationCode: vi.fn(),
  exchangeAuthorizationCode: vi.fn(),
  getAlexaLinkStatus: vi.fn(),
  listPersonalRoutesForAlexa: vi.fn(),
  recordAlexaVerification: vi.fn(),
  rotateRefreshToken: vi.fn(),
}));

const railData = vi.hoisted(() => ({
  punctualityStats: vi.fn(),
  railStationIndex: vi.fn(),
}));
vi.mock("../punctuality/source", () => railData);

import { getAlexaLinkStatus, listPersonalRoutesForAlexa } from "../db";
import { parseStationCatalog } from "../punctuality/stations";
import { parsePunctualityStats } from "../punctuality/stats";
import { catalogDocument, departuresDocument, MONDAY_MORNING, networkDocument } from "../punctuality/testFixtures";
import { createAlexaSkill, HANDLED_INTENTS } from "./alexa";
import { issueVoiceAccessToken } from "./crypto";

type Envelope = Parameters<ReturnType<typeof createAlexaSkill>["invoke"]>[0];

function envelope(request: Record<string, unknown>, locale = "it-IT"): Envelope {
  const user = { userId: "amzn1.ask.account.TEST" };
  return {
    version: "1.0",
    session: { new: true, sessionId: "amzn1.echo-api.session.test", application: { applicationId: SKILL_ID }, user },
    context: { System: { application: { applicationId: SKILL_ID }, user, device: { deviceId: "device", supportedInterfaces: {} }, apiEndpoint: "https://api.eu.amazonalexa.com" } },
    request: { requestId: "amzn1.echo-api.request.test", timestamp: new Date().toISOString(), locale, ...request },
  } as unknown as Envelope;
}

const intent = (name: string, locale = "it-IT", slots?: Record<string, string>) =>
  envelope(
    {
      type: "IntentRequest",
      intent: {
        name,
        confirmationStatus: "NONE",
        slots: Object.fromEntries(
          Object.entries(slots ?? {}).map(([slotName, value]) => [
            slotName,
            { name: slotName, value, confirmationStatus: "NONE" },
          ])
        ),
      },
    },
    locale
  );

function withAccessToken(request: Envelope, accessToken: string): Envelope {
  request.context.System.user.accessToken = accessToken;
  if (request.session?.user) request.session.user.accessToken = accessToken;
  return request;
}

type ModelIntent = {
  name: string;
  samples: string[];
  slots?: Array<{ name: string; type: string }>;
};
type Model = {
  interactionModel: {
    languageModel: { invocationName: string; intents: ModelIntent[] };
  };
};

function model(locale: string): Model {
  return JSON.parse(
    readFileSync(
      path.resolve(
        import.meta.dirname,
        "..",
        "..",
        "skill-package",
        "interactionModels",
        "custom",
        `${locale}.json`
      ),
      "utf8"
    )
  ) as Model;
}

type SkillManifest = {
  manifest: {
    publishingInformation: { locales: Record<string, unknown> };
    privacyAndCompliance: { locales: Record<string, unknown> };
    apis: { custom: { endpoint: { uri: string } } };
  };
};

function manifest(): SkillManifest {
  return JSON.parse(
    readFileSync(
      path.resolve(import.meta.dirname, "..", "..", "skill-package", "skill.json"),
      "utf8"
    )
  ) as SkillManifest;
}

function contract(items: ModelIntent[]) {
  return items
    .map(item => ({
      name: item.name,
      slots: (item.slots ?? [])
        .map(slot => `${slot.name}:${slot.type}`)
        .sort(),
    }))
    .sort((left, right) => left.name.localeCompare(right.name));
}

function speechText(
  response: Awaited<ReturnType<ReturnType<typeof createAlexaSkill>["invoke"]>>
) {
  return JSON.stringify(response.response.outputSpeech);
}

describe("Alexa skill handlers", () => {
  it("ends the session for Stop, Cancel, and NavigateHome", async () => {
    for (const name of [
      "AMAZON.StopIntent",
      "AMAZON.CancelIntent",
      "AMAZON.NavigateHomeIntent",
    ]) {
      const response = await createAlexaSkill().invoke(intent(name));
      expect(response.response.shouldEndSession).toBe(true);
      expect(speechText(response)).toContain("A presto");
    }
  });

  it("keeps the Help session open with a reprompt", async () => {
    const response = await createAlexaSkill().invoke(intent("AMAZON.HelpIntent"));
    expect(response.response.shouldEndSession).toBe(false);
    expect(speechText(response)).toContain("prossimi treni");
    expect(response.response.reprompt).toBeDefined();
  });

  it("does not produce speech for SessionEndedRequest", async () => {
    const response = await createAlexaSkill().invoke(
      envelope({ type: "SessionEndedRequest", reason: "USER_INITIATED" })
    );
    expect(response.response.outputSpeech).toBeUndefined();
  });

  it("answers the train status without a linked account, as it says nothing personal", async () => {
    vi.mocked(listPersonalRoutesForAlexa).mockClear();
    const response = await createAlexaSkill().invoke(intent("TrainStatusIntent", "it-IT", { trainNumber: "9512" }));
    expect(response.response.card?.type).not.toBe("LinkAccount");
    expect(speechText(response)).toContain("Per il treno 9512: dati non disponibili in tempo reale");
    expect(listPersonalRoutesForAlexa).not.toHaveBeenCalled();
    // AMAZON.NUMBER gives "?" when it heard no number: no «Treno ?».
    const unheard = await createAlexaSkill().invoke(intent("TrainStatusIntent", "it-IT", { trainNumber: "?" }));
    expect(speechText(unheard)).not.toContain("?");
    expect(unheard.response.card?.type).not.toBe("LinkAccount");
  });

  it("returns an account-link card for an unlinked personal intent", async () => {
    const response = await createAlexaSkill().invoke(intent("MyRoutesIntent"));
    expect(response.response.card?.type).toBe("LinkAccount");
    expect(response.response.shouldEndSession).toBe(true);
  });

  it.each([
    ["it-IT", "Ti diamo il benvenuto in Binario"],
    ["en-US", "Welcome to Binario"],
    ["en-GB", "Welcome to Binario"],
    ["de-DE", "Willkommen bei Binario"],
    ["fr-FR", "Bienvenue dans Binario"],
    ["es-ES", "Te damos la bienvenida a Binario"],
    ["ar-SA", "مرحبًا بك في بيناريو"],
    ["hi-IN", "बिनारियो में आपका स्वागत है"],
    ["ja-JP", "ビナリオへようこそ"],
    ["pt-BR", "Boas-vindas ao Binario"],
  ] as const)("uses request.locale %s for launch speech", async (locale, expectedSpeech) => {
    const response = await createAlexaSkill().invoke(
      envelope({ type: "LaunchRequest" }, locale)
    );
    expect(speechText(response)).toContain(expectedSpeech);
    expect(response.response.card).toMatchObject({
      type: "Simple",
      title: "Binario",
      content: expect.stringContaining(expectedSpeech),
    });
    expect(response.response.shouldEndSession).toBe(false);
  });

  it.each([
    ["it-IT", "dati non disponibili in tempo reale"],
    ["en-US", "real-time data is not available"],
    ["en-GB", "real-time data is not available"],
    ["de-DE", "Echtzeitdaten sind nicht verfügbar"],
    ["fr-FR", "les données en temps réel ne sont pas disponibles"],
    ["es-ES", "los datos en tiempo real no están disponibles"],
    ["ar-SA", "البيانات في الوقت الفعلي غير متاحة"],
    ["hi-IN", "रीयल-टाइम डेटा उपलब्ध नहीं है"],
    ["ja-JP", "リアルタイムデータは利用できません"],
    ["pt-BR", "os dados em tempo real não estão disponíveis"],
  ] as const)(
    "localizes one linked personal request and its card for %s",
    async (locale, expectedSpeech) => {
      vi.mocked(getAlexaLinkStatus).mockResolvedValue({
        userId: 7,
        credentialVersion: 3,
        revokedAt: null,
      } as Awaited<ReturnType<typeof getAlexaLinkStatus>>);
      vi.mocked(listPersonalRoutesForAlexa).mockResolvedValue([
        {
          id: 1,
          name: "Casa",
          origin: "Roma",
          destination: "Tivoli",
          isPinned: true,
        },
      ] as Awaited<ReturnType<typeof listPersonalRoutesForAlexa>>);
      const accessToken = await issueVoiceAccessToken(7, 3);
      const response = await createAlexaSkill().invoke(
        withAccessToken(intent("NextTrainsIntent", locale), accessToken)
      );
      expect(speechText(response)).toContain(expectedSpeech);
      expect(response.response.card).toMatchObject({
        type: "Simple",
        title: "Binario",
        content: expect.stringContaining(expectedSpeech),
      });
      expect(response.response.shouldEndSession).toBe(true);
    }
  );

  it.each(["NextTrainsIntent", "MyRoutesIntent"] as const)(
    "escapes route names in SSML but keeps the %s card readable",
    async intentName => {
      vi.mocked(getAlexaLinkStatus).mockResolvedValue({
        userId: 7,
        credentialVersion: 3,
        revokedAt: null,
      } as Awaited<ReturnType<typeof getAlexaLinkStatus>>);
      vi.mocked(listPersonalRoutesForAlexa).mockResolvedValue([
        {
          id: 1,
          name: "Casa & lavoro <sera>\u0007",
          origin: "Roma",
          destination: "Tivoli",
          isPinned: true,
        },
      ] as Awaited<ReturnType<typeof listPersonalRoutesForAlexa>>);
      const accessToken = await issueVoiceAccessToken(7, 3);
      const response = await createAlexaSkill().invoke(
        withAccessToken(intent(intentName), accessToken)
      );
      const ssml = speechText(response);
      expect(ssml).toContain("Casa &amp; lavoro &lt;sera&gt;");
      expect(ssml).not.toContain("\u0007");
      expect(response.response.card).toMatchObject({
        type: "Simple",
        content: expect.stringContaining("Casa & lavoro <sera>"),
      });
      expect(JSON.stringify(response.response.card)).not.toContain("&amp;");
    }
  );

  it("falls back to Italian for an unsupported Alexa request locale", async () => {
    const response = await createAlexaSkill().invoke(
      envelope({ type: "LaunchRequest" }, "nl-NL")
    );
    expect(speechText(response)).toContain("Ti diamo il benvenuto in Binario");
  });

  it("rejects requests for another skill", async () => {
    const other = intent("AMAZON.HelpIntent");
    (other.context.System.application as { applicationId: string }).applicationId =
      "amzn1.ask.skill.other";
    await expect(createAlexaSkill().invoke(other)).rejects.toThrow();
  });
});

describe("Echo Show screens (APL)", () => {
  type SkillResponse = Awaited<ReturnType<ReturnType<typeof createAlexaSkill>["invoke"]>>;
  const ROUTES = [
    { id: 1, name: "Casa & <lavoro>", origin: "Roma Termini", destination: "Tivoli", isPinned: true },
    { id: 2, name: "Mare", origin: "Roma Ostiense", destination: "Ostia", isPinned: false },
  ];

  function withApl(request: Envelope, maxVersion = "1.8"): Envelope {
    request.context.System.device!.supportedInterfaces["Alexa.Presentation.APL"] = { runtime: { maxVersion } };
    return request;
  }

  async function linkedRequest(request: Envelope) {
    vi.mocked(getAlexaLinkStatus).mockResolvedValue({ userId: 7, credentialVersion: 3, revokedAt: null } as Awaited<ReturnType<typeof getAlexaLinkStatus>>);
    vi.mocked(listPersonalRoutesForAlexa).mockResolvedValue(ROUTES as Awaited<ReturnType<typeof listPersonalRoutesForAlexa>>);
    const accessToken = await issueVoiceAccessToken(7, 3);
    return { request: withAccessToken(request, accessToken), accessToken };
  }

  function renderDocument(response: SkillResponse) {
    return response.response.directives?.find(item => item.type === "Alexa.Presentation.APL.RenderDocument") as
      | { token: string; datasources: { binario: Record<string, unknown> & { routes: Array<Record<string, unknown>> } } }
      | undefined;
  }

  function userEvent(args: unknown[], token = "binario.myRoutes", locale = "it-IT") {
    return envelope({ type: "Alexa.Presentation.APL.UserEvent", token, arguments: args }, locale);
  }

  it("keeps the voice response identical on speakers and adds the departures screen on Echo Show", async () => {
    const speaker = await linkedRequest(intent("NextTrainsIntent"));
    const voice = await createAlexaSkill().invoke(speaker.request);
    const screen = await linkedRequest(withApl(intent("NextTrainsIntent")));
    const shown = await createAlexaSkill().invoke(screen.request);

    expect(voice.response.directives ?? []).toHaveLength(0);
    expect(voice.response.shouldEndSession).toBe(true);
    expect(shown.response.outputSpeech).toEqual(voice.response.outputSpeech);
    expect(shown.response.card).toEqual(voice.response.card);
    // The session stays open with the microphone closed so the screen and its buttons remain usable.
    expect(shown.response.shouldEndSession).toBeUndefined();
    expect(shown.response.reprompt).toBeUndefined();
    const document = renderDocument(shown);
    expect(document?.token).toBe("binario.departures");
    expect(document?.datasources.binario).toMatchObject({
      screen: "departures",
      title: "Partenze",
      hero: "Casa &amp; &lt;lavoro&gt;",
      quality: { state: "unavailable", label: "Dati non disponibili in tempo reale" },
    });
  });

  it("never puts tokens, identifiers, origins or destinations on the screen", async () => {
    railData.punctualityStats.mockResolvedValue(parsePunctualityStats(networkDocument()));
    railData.railStationIndex.mockResolvedValue(parseStationCatalog(catalogDocument()));
    for (const name of ["NextTrainsIntent", "MyRoutesIntent", "TrainStatusIntent", "RoutePunctualityIntent", "MostPunctualTrainIntent"]) {
      const { request, accessToken } = await linkedRequest(withApl(intent(name, "it-IT", { trainNumber: "9512" })));
      const serialized = JSON.stringify((await createAlexaSkill().invoke(request)).response.directives);
      expect(serialized).toContain("RenderDocument");
      for (const secret of [accessToken, "amzn1.ask.account", "amzn1.echo-api", "device", "Roma Termini", "Tivoli", "Ostia", "userId", "credentialVersion"])
        expect(serialized, `${name}: ${secret}`).not.toContain(secret);
    }
  });

  it("projects the spoken route names and the train asked for", async () => {
    const routes = renderDocument(await createAlexaSkill().invoke((await linkedRequest(withApl(intent("MyRoutesIntent")))).request));
    expect(routes?.datasources.binario.routes.map(route => route.name)).toEqual(["Casa & <lavoro>", "Mare"]);
    expect(routes?.datasources.binario.routes[0].pinnedText).toBe("Fissata");
    const train = renderDocument(await createAlexaSkill().invoke((await linkedRequest(withApl(intent("TrainStatusIntent", "de-DE", { trainNumber: "9512" })))).request));
    expect(train?.datasources.binario).toMatchObject({ screen: "trainStatus", title: "Zugstatus", hero: "Zug 9512" });
  });

  it("shows a non-personal home screen for launch and help, keeping the microphone open", async () => {
    for (const request of [envelope({ type: "LaunchRequest" }), intent("AMAZON.HelpIntent")]) {
      const response = await createAlexaSkill().invoke(withApl(request));
      expect(renderDocument(response)?.datasources.binario).toMatchObject({ screen: "home", routes: [] });
      expect(response.response.shouldEndSession).toBe(false);
      expect(response.response.reprompt).toBeDefined();
    }
  });

  it("renders nothing before account linking, after Stop, or on an older APL runtime", async () => {
    for (const name of ["NextTrainsIntent", "MyRoutesIntent", "RoutePunctualityIntent", "MostPunctualTrainIntent", "AMAZON.StopIntent"]) {
      const response = await createAlexaSkill().invoke(withApl(intent(name)));
      expect(response.response.directives ?? [], name).toHaveLength(0);
      expect(response.response.shouldEndSession, name).toBe(true);
    }
    const old = await createAlexaSkill().invoke(withApl(envelope({ type: "LaunchRequest" }), "1.0"));
    expect(old.response.directives ?? []).toHaveLength(0);
    expect(old.response.card?.type).toBe("Simple");
  });

  it("does not render a screen when a personal request fails", async () => {
    const errors = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const { request } = await linkedRequest(withApl(intent("MyRoutesIntent")));
    vi.mocked(listPersonalRoutesForAlexa).mockRejectedValueOnce(new Error("database down"));
    const response = await createAlexaSkill().invoke(request);
    expect(speechText(response)).toContain("Si è verificato un problema");
    expect(response.response.directives ?? []).toHaveLength(0);
    errors.mockRestore();
  });

  it("logs a failed request by type, intent, locale and error class only", async () => {
    const errors = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const { request, accessToken } = await linkedRequest(intent("NextTrainsIntent", "de-DE", { routeName: "Casa di Mario" }));
    vi.mocked(listPersonalRoutesForAlexa).mockRejectedValueOnce(
      new DrizzleQueryError("select `name` from `railway_routes` where `userId` = ?", [7, "Casa di Mario"], new Error("boom"))
    );
    await createAlexaSkill().invoke(request);
    expect(errors).toHaveBeenCalledTimes(1);
    const [label, context] = errors.mock.calls[0] as [string, Record<string, unknown>];
    expect(label).toBe("[Alexa] request failed");
    expect(context).toMatchObject({
      requestType: "IntentRequest",
      intent: "NextTrainsIntent",
      locale: "de-DE",
      requestRef: expect.stringMatching(/^[0-9a-f]{16}$/),
      error: { name: "DrizzleQueryError" },
    });
    const serialized = JSON.stringify(errors.mock.calls);
    for (const secret of ["Casa di Mario", accessToken, "amzn1.ask.account", "amzn1.echo-api", "device"])
      expect(serialized).not.toContain(secret);
    errors.mockRestore();
  });

  it("answers a touched route with that route's screen after re-checking the link", async () => {
    const { request } = await linkedRequest(withApl(userEvent(["binario.nextTrains", "Mare"])));
    const response = await createAlexaSkill().invoke(request);
    expect(speechText(response)).toContain("Per la tratta Mare: dati non disponibili in tempo reale");
    expect(renderDocument(response)?.datasources.binario).toMatchObject({ screen: "departures", hero: "Mare" });

    const pinned = await linkedRequest(withApl(userEvent(["binario.nextTrains", ""])));
    expect(speechText(await createAlexaSkill().invoke(pinned.request))).toContain("Casa &amp; &lt;lavoro&gt;");

    const gone = await linkedRequest(withApl(userEvent(["binario.nextTrains", "Montagna"])));
    expect(renderDocument(await createAlexaSkill().invoke(gone.request))?.datasources.binario.screen).toBe("myRoutes");

    const list = await linkedRequest(withApl(userEvent(["binario.myRoutes"])));
    expect(renderDocument(await createAlexaSkill().invoke(list.request))?.datasources.binario.screen).toBe("myRoutes");
  });

  it("asks for linking again when a touch arrives without a valid link, and rejects unknown events", async () => {
    const unlinked = await createAlexaSkill().invoke(withApl(userEvent(["binario.myRoutes"])));
    expect(unlinked.response.card?.type).toBe("LinkAccount");
    expect(unlinked.response.directives ?? []).toHaveLength(0);

    const forged = await createAlexaSkill().invoke(withApl(userEvent(["binario.deleteAccount"])));
    expect(speechText(forged)).toContain("Non ho capito la richiesta");
    expect(renderDocument(forged)?.datasources.binario.screen).toBe("home");
  });
});

describe("spoken route names and route lists", () => {
  type SkillResponse = Awaited<ReturnType<ReturnType<typeof createAlexaSkill>["invoke"]>>;
  async function ask(locale: string, names: string[], slots?: Record<string, string>, intentName = "NextTrainsIntent", apl = false) {
    vi.mocked(getAlexaLinkStatus).mockResolvedValue({ userId: 7, credentialVersion: 3, revokedAt: null } as Awaited<ReturnType<typeof getAlexaLinkStatus>>);
    vi.mocked(listPersonalRoutesForAlexa).mockResolvedValue(
      names.map((name, index) => ({ id: index + 1, name, origin: "Roma", destination: "Tivoli", isPinned: index === 0 })) as Awaited<ReturnType<typeof listPersonalRoutesForAlexa>>
    );
    const token = await issueVoiceAccessToken(7, 3);
    const request = withAccessToken(intent(intentName, locale, slots), token);
    if (apl) request.context.System.device!.supportedInterfaces["Alexa.Presentation.APL"] = { runtime: { maxVersion: "1.8" } };
    return createAlexaSkill().invoke(request);
  }
  const card = (response: SkillResponse) => (response.response.card as { content?: string } | undefined)?.content ?? "";

  it("matches the ASR form of a saved name: no accents, punctuation or capitals", async () => {
    const response = await ask("it-IT", ["Casa-Lavoro", "Città Alta"], { routeName: "citta alta" });
    expect(card(response)).toBe("Per la tratta Città Alta: dati non disponibili in tempo reale");
    expect((await ask("it-IT", ["Casa-Lavoro", "Mare"], { routeName: "casa lavoro" })).response.card).toMatchObject({
      content: "Per la tratta Casa-Lavoro: dati non disponibili in tempo reale",
    });
  });

  it.each([
    ["it-IT", "milano", "Non ho trovato la tratta milano. Le tue tratte salvate sono: Casa-Lavoro e Mare. Quale tratta ti interessa? Puoi dire, per esempio: prossimi treni per Casa-Lavoro."],
    ["en-US", "beach", "I couldn't find the route beach. Your saved routes are: Casa-Lavoro and Mare. Which route would you like? You can say, for example: next trains for Casa-Lavoro."],
    ["fr-FR", "plage", "Je n’ai pas trouvé le trajet plage. Vos trajets enregistrés sont : Casa-Lavoro et Mare. Quel trajet voulez-vous ? Vous pouvez dire, par exemple : prochains trains pour Casa-Lavoro."],
  ] as const)("%s: an unmatched name lists the saved routes instead of claiming there are none", async (locale, spoken, expected) => {
    const response = await ask(locale, ["Casa-Lavoro", "Mare"], { routeName: spoken });
    expect(card(response)).toBe(expected);
    expect(speechText(response)).not.toMatch(/Non hai ancora|have not saved/);
    expect(response.response.shouldEndSession).toBe(false);
    expect(JSON.stringify(response.response.reprompt)).toContain("Casa-Lavoro");
  });

  it("asks which route when the spoken name fits several, and shows them on Echo Show", async () => {
    const response = await ask("de-DE", ["Casa-Lavoro", "Casa-Mare", "Palestra"], { routeName: "casa" }, "NextTrainsIntent", true);
    expect(card(response)).toBe(
      "Ich habe mehrere Strecken gefunden: Casa-Lavoro und Casa-Mare. Welche Strecke meinen Sie? Sagen Sie zum Beispiel: nächste Züge für Casa-Lavoro."
    );
    expect(response.response.shouldEndSession).toBe(false);
    const document = response.response.directives?.find(item => item.type === "Alexa.Presentation.APL.RenderDocument") as
      | { datasources: { binario: { screen: string; routes: Array<{ name: string }> } } }
      | undefined;
    expect(document?.datasources.binario.screen).toBe("myRoutes");
    expect(document?.datasources.binario.routes.map(item => item.name)).toEqual(["Casa-Lavoro", "Casa-Mare"]);
  });

  it("still says there are no routes when nothing is saved", async () => {
    const response = await ask("it-IT", [], { routeName: "casa" });
    expect(card(response)).toBe("Non hai ancora salvato alcuna tratta in Binario.");
    expect(response.response.shouldEndSession).toBe(true);
  });

  it.each([
    ["it-IT", "Le tue tratte salvate sono: Casa, Mare e Lago."],
    ["en-US", "Your saved routes are: Casa, Mare, and Lago."],
    ["de-DE", "Ihre gespeicherten Strecken sind: Casa, Mare und Lago."],
    ["ar-SA", "مساراتك المحفوظة هي: Casa وMare وLago."],
    ["hi-IN", "आपके सहेजे गए रूट हैं: Casa, Mare, और Lago।"],
    ["ja-JP", "保存したルートは、Casa、Mare、Lago です。"],
  ] as const)("%s: joins the route list and ends it the way the language writes it", async (locale, expected) => {
    expect(card(await ask(locale, ["Casa", "Mare", "Lago"], undefined, "MyRoutesIntent"))).toBe(expected);
  });

  it.each(LOCALES)("%s: the launch reprompt asks what the user wants, it never claims a misunderstanding", async locale => {
    const response = await createAlexaSkill().invoke(envelope({ type: "LaunchRequest" }, locale));
    const voice = messagesForLocale(locale).voice;
    const reprompt = JSON.stringify(response.response.reprompt);
    expect(reprompt).toContain(voice.launchReprompt);
    expect(voice.launchReprompt).not.toBe(voice.unsupported);
    expect(reprompt).not.toContain(voice.unsupported.split(/[.。।?？]/)[0]);
  });
});

describe("dynamic Alexa speech values", () => {
  const unsafeText = `A & B < C > D "quotes" 'apostrophes' <break time="1s"/>`;
  const escapedUnsafeText =
    "A &amp; B &lt; C &gt; D &quot;quotes&quot; &apos;apostrophes&apos; " +
    "&lt;break time=&quot;1s&quot;/&gt;";
  async function linked(name: string, slots?: Record<string, string>) {
    vi.mocked(getAlexaLinkStatus).mockResolvedValue({ userId: 7, credentialVersion: 3, revokedAt: null } as Awaited<ReturnType<typeof getAlexaLinkStatus>>);
    const token = await issueVoiceAccessToken(7, 3);
    return withAccessToken(intent(name, "it-IT", slots), token);
  }
  it("escapes route names without allowing injected SSML", async () => {
    vi.mocked(listPersonalRoutesForAlexa).mockResolvedValue([{ id: 1, name: unsafeText, origin: "Roma", destination: "Milano", isPinned: true }] as Awaited<ReturnType<typeof listPersonalRoutesForAlexa>>);
    for (const name of ["NextTrainsIntent", "MyRoutesIntent"]) {
      const response = await createAlexaSkill().invoke(await linked(name));
      expect(speechText(response)).toContain(escapedUnsafeText);
      expect(speechText(response)).not.toContain(`<break time="1s"/>`);
    }
  });
  it("escapes a user-provided train number", async () => {
    const response = await createAlexaSkill().invoke(await linked("TrainStatusIntent", { trainNumber: `9512 ${unsafeText}` }));
    expect(speechText(response)).toContain(`9512 ${escapedUnsafeText}`);
    expect(speechText(response)).not.toContain(`<break time="1s"/>`);
  });
  it("treats blank route names as absent", async () => {
    vi.mocked(listPersonalRoutesForAlexa).mockResolvedValue([{ id: 1, name: "   ", origin: "Roma", destination: "Milano", isPinned: true }] as Awaited<ReturnType<typeof listPersonalRoutesForAlexa>>);
    const response = await createAlexaSkill().invoke(await linked("NextTrainsIntent"));
    expect(speechText(response)).toContain("Non hai ancora salvato alcuna tratta in Binario.");
  });
});

describe("versioned interaction models", () => {
  const italianContract = contract(
    model("it-IT").interactionModel.languageModel.intents
  );

  it("declares every code-handled intent and no others in each locale", () => {
    for (const locale of LOCALES) {
      const declared = model(locale)
        .interactionModel.languageModel.intents.map(item => item.name)
        .sort();
      expect(declared).toEqual([...HANDLED_INTENTS].sort());
    }
  });

  it("keeps the identical intent and slot contract across all locales", () => {
    for (const locale of LOCALES)
      expect(contract(model(locale).interactionModel.languageModel.intents)).toEqual(
        italianContract
      );
  });

  it("provides an invocation name and natural samples for every custom intent", () => {
    for (const locale of LOCALES) {
      const languageModel = model(locale).interactionModel.languageModel;
      expect(languageModel.invocationName).toMatch(/\S/);
      for (const item of languageModel.intents)
        if (!item.name.startsWith("AMAZON."))
          expect(item.samples.length).toBeGreaterThan(0);
    }
  });

  it("serves en-IN and es-US with the en-US and es-ES models and dictionaries, unchanged", () => {
    expect(model("en-IN")).toEqual(model("en-US"));
    expect(model("es-US")).toEqual(model("es-ES"));
    const published = manifest().manifest.publishingInformation.locales;
    expect(published["en-IN"]).toEqual(published["en-US"]);
    expect(published["es-US"]).toEqual(published["es-ES"]);
    expect(messagesForLocale("en-IN")).toBe(messagesForLocale("en-US"));
    expect(messagesForLocale("es-US")).toBe(messagesForLocale("es-ES"));
    // Both run in countries the skill is already distributed in.
    const countries = (manifest().manifest.publishingInformation as unknown as { distributionCountries: string[] }).distributionCountries;
    expect(countries).toEqual(expect.arrayContaining(["IN", "US"]));
  });

  it("keeps model, publishing, privacy, and runtime locale sets identical", () => {
    const skillManifest = manifest().manifest;
    expect([...LOCALES]).toEqual([...SUPPORTED_LOCALES]);
    expect(Object.keys(skillManifest.publishingInformation.locales).sort()).toEqual(
      [...LOCALES].sort()
    );
    expect(Object.keys(skillManifest.privacyAndCompliance.locales).sort()).toEqual(
      [...LOCALES].sort()
    );
    for (const locale of LOCALES) expect(() => model(locale)).not.toThrow();
  });

  it("names each locale's invocation in its example phrases and Echo Show hints", () => {
    const published = manifest().manifest.publishingInformation.locales as Record<string, { examplePhrases: string[] }>;
    for (const locale of LOCALES) {
      const invocation = model(locale).interactionModel.languageModel.invocationName.toLocaleLowerCase(locale);
      const screen = messagesForLocale(locale).screen;
      for (const phrase of [
        ...published[locale].examplePhrases,
        screen.hintNextTrains,
        screen.hintTrainStatus,
        screen.hintMyRoutes,
        messagesForLocale(locale).punctuality.hint,
        messagesForLocale(locale).punctuality.mostPunctualHint,
        PUNCTUALITY_PHRASES[locale].train,
        PUNCTUALITY_PHRASES[locale].route,
        PUNCTUALITY_PHRASES[locale].best,
      ])
        expect(phrase.toLocaleLowerCase(locale), `${locale}: ${phrase}`).toContain(invocation);
    }
  });

  /**
   * How each locale wraps an utterance in a one-shot request: "Alexa, ask {invocation} for X".
   * The part after the launch phrase and its connector must be a sample, or Alexa may not reach
   * the intent and certification rejects the example phrase.
   */
  const PHRASE_GRAMMAR: Record<(typeof LOCALES)[number], { open: string; ask: string }> = {
    "it-IT": { open: "^alexa, apri INV$", ask: "^alexa, chiedi a INV (?:di |se )?(.+)$" },
    "en-US": { open: "^alexa, open INV$", ask: "^alexa, ask INV (?:for |to |about |if |whether )?(.+)$" },
    "en-GB": { open: "^alexa, open INV$", ask: "^alexa, ask INV (?:for |to |about |if |whether )?(.+)$" },
    "en-IN": { open: "^alexa, open INV$", ask: "^alexa, ask INV (?:for |to |about |if |whether )?(.+)$" },
    "de-DE": { open: "^alexa, öffne INV$", ask: "^alexa, frage INV (?:nach |ob )?(.+)$" },
    "fr-FR": { open: "^alexa, ouvre INV$", ask: "^alexa, demande à INV (?:de |si )?(.+)$" },
    "es-ES": { open: "^alexa, abre INV$", ask: "^alexa, pregunta a INV (?:por |si )?(.+)$" },
    "es-US": { open: "^alexa, abre INV$", ask: "^alexa, pregunta a INV (?:por |si )?(.+)$" },
    "ar-SA": { open: "^أليكسا، افتح INV$", ask: "^أليكسا، اسأل INV (?:عن )?(.+)$" },
    "hi-IN": { open: "^अलेक्सा, INV खोलो$", ask: "^अलेक्सा, INV से (.+) पूछो$" },
    "ja-JP": { open: "^アレクサ、INVを開いて$", ask: "^アレクサ、INVで(.+)$" },
    "pt-BR": { open: "^alexa, abra INV$", ask: "^alexa, pergunte ao INV (.+)$" },
  };
  /**
   * The brief's phrases, «quanto è puntuale il treno 9558», «… la mia tratta casa lavoro» and «qual è
   * il treno più puntuale per casa lavoro», in every locale.
   */
  const PUNCTUALITY_PHRASES: Record<(typeof LOCALES)[number], { train: string; route: string; best: string }> = {
    "it-IT": { train: "Alexa, chiedi a binario treni quanto è puntuale il treno 9558", route: "Alexa, chiedi a binario treni quanto è puntuale la mia tratta casa lavoro", best: "Alexa, chiedi a binario treni qual è il treno più puntuale per casa lavoro" },
    "en-US": { train: "Alexa, ask binario trains how punctual train 9558 is", route: "Alexa, ask binario trains about the punctuality of my route casa lavoro", best: "Alexa, ask binario trains which is the most punctual train for casa lavoro" },
    "en-GB": { train: "Alexa, ask binario trains how punctual train 9558 is", route: "Alexa, ask binario trains about the punctuality of my route casa lavoro", best: "Alexa, ask binario trains which is the most punctual train for casa lavoro" },
    "en-IN": { train: "Alexa, ask binario trains how punctual train 9558 is", route: "Alexa, ask binario trains about the punctuality of my route casa lavoro", best: "Alexa, ask binario trains which is the most punctual train for casa lavoro" },
    "de-DE": { train: "Alexa, frage binario züge nach der Pünktlichkeit von Zug 9558", route: "Alexa, frage binario züge nach der Pünktlichkeit meiner Strecke casa lavoro", best: "Alexa, frage binario züge nach dem pünktlichsten Zug für casa lavoro" },
    "fr-FR": { train: "Alexa, demande à binario trains la ponctualité du train 9558", route: "Alexa, demande à binario trains la ponctualité de mon trajet casa lavoro", best: "Alexa, demande à binario trains quel est le train le plus ponctuel pour casa lavoro" },
    "es-ES": { train: "Alexa, pregunta a binario trenes por la puntualidad del tren 9558", route: "Alexa, pregunta a binario trenes por la puntualidad de mi ruta casa lavoro", best: "Alexa, pregunta a binario trenes cuál es el tren más puntual para casa lavoro" },
    "es-US": { train: "Alexa, pregunta a binario trenes por la puntualidad del tren 9558", route: "Alexa, pregunta a binario trenes por la puntualidad de mi ruta casa lavoro", best: "Alexa, pregunta a binario trenes cuál es el tren más puntual para casa lavoro" },
    "ar-SA": { train: "أليكسا، اسأل قطارات بيناريو عن دقة مواعيد القطار 9558", route: "أليكسا، اسأل قطارات بيناريو عن دقة مواعيد مساري casa lavoro", best: "أليكسا، اسأل قطارات بيناريو عن القطار الأكثر دقة في المواعيد على مساري casa lavoro" },
    "hi-IN": { train: "अलेक्सा, बिनारियो ट्रेन से ट्रेन 9558 की समयबद्धता पूछो", route: "अलेक्सा, बिनारियो ट्रेन से मेरे रूट casa lavoro की समयबद्धता पूछो", best: "अलेक्सा, बिनारियो ट्रेन से मेरे रूट casa lavoro की सबसे समयबद्ध ट्रेन पूछो" },
    "ja-JP": { train: "アレクサ、ビナリオトレインで9558号の定時運行率を教えて", route: "アレクサ、ビナリオトレインでマイルートcasa lavoroの定時運行率を教えて", best: "アレクサ、ビナリオトレインでマイルートcasa lavoroの一番時間に正確な電車を教えて" },
    "pt-BR": { train: "Alexa, pergunte ao binario trens qual é a pontualidade do trem 9558", route: "Alexa, pergunte ao binario trens qual é a pontualidade do meu trajeto casa lavoro", best: "Alexa, pergunte ao binario trens qual é o trem mais pontual para casa lavoro" },
  };
  const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const plain = (locale: string, value: string) => {
    const text = value.replace(/[«»“”„"「」]/g, "").trim().toLocaleLowerCase(locale).replace(/\s+/g, " ");
    return locale === "ja-JP" ? text.replace(/ /g, "") : text;
  };
  function utteranceOf(locale: (typeof LOCALES)[number], phrase: string) {
    const invocation = escapeRegExp(model(locale).interactionModel.languageModel.invocationName);
    const grammar = PHRASE_GRAMMAR[locale];
    const text = plain(locale, phrase);
    if (new RegExp(grammar.open.replace("INV", invocation)).test(text)) return null;
    const ask = new RegExp(grammar.ask.replace("INV", invocation)).exec(text);
    if (!ask) throw new Error(`${locale}: "${phrase}" does not follow the invocation grammar`);
    return plain(locale, ask[1]);
  }
  function sampleMatches(locale: string, sample: string, utterance: string) {
    const pattern = escapeRegExp(plain(locale, sample)).replace(/\\\{[A-Za-z]+\\\}/g, ".+");
    return new RegExp(`^${pattern}$`).test(utterance);
  }

  it("uses an invocation name of at least two lower-case words in every locale", () => {
    for (const locale of LOCALES) {
      const invocation = model(locale).interactionModel.languageModel.invocationName;
      expect(invocation, locale).toBe(invocation.toLocaleLowerCase(locale));
      expect(invocation, locale).not.toMatch(/[^\p{L}\p{M} ]/u);
      if (locale === "ja-JP") expect(invocation).toMatch(/^ビナリオ.+$/);
      else expect(invocation.split(" ").length, locale).toBeGreaterThanOrEqual(2);
    }
  });

  it("makes every example phrase and Echo Show hint reach a sample utterance of its intent", () => {
    const published = manifest().manifest.publishingInformation.locales as Record<string, { examplePhrases: string[] }>;
    const problems: string[] = [];
    for (const locale of LOCALES) {
      const intents = model(locale).interactionModel.languageModel.intents;
      const samplesOf = (name?: string) =>
        intents.filter(item => !item.name.startsWith("AMAZON.") && (!name || item.name === name)).flatMap(item => item.samples);
      const screen = messagesForLocale(locale).screen;
      const phrases: Array<[string, string | undefined]> = [
        ...published[locale].examplePhrases.map(phrase => [phrase, undefined] as [string, undefined]),
        [screen.hintNextTrains, "NextTrainsIntent"],
        [screen.hintTrainStatus, "TrainStatusIntent"],
        [screen.hintMyRoutes, "MyRoutesIntent"],
        [messagesForLocale(locale).punctuality.hint, "TrainPunctualityIntent"],
        [messagesForLocale(locale).punctuality.mostPunctualHint, "MostPunctualTrainIntent"],
        [PUNCTUALITY_PHRASES[locale].train, "TrainPunctualityIntent"],
        [PUNCTUALITY_PHRASES[locale].route, "RoutePunctualityIntent"],
        [PUNCTUALITY_PHRASES[locale].best, "MostPunctualTrainIntent"],
      ];
      for (const [phrase, intentName] of phrases) {
        const utterance = utteranceOf(locale, phrase);
        if (utterance === null) continue;
        if (!samplesOf(intentName).some(sample => sampleMatches(locale, sample, utterance)))
          problems.push(`${locale}: "${utterance}" (${phrase})`);
      }
      expect(utteranceOf(locale, published[locale].examplePhrases[0]), `${locale}: the first example phrase opens the skill`).toBeNull();
    }
    expect(problems).toEqual([]);
  });

  it("reaches the punctuality intents with the documented phrases and with no other intent", () => {
    const problems: string[] = [];
    for (const locale of LOCALES) {
      const intents = model(locale).interactionModel.languageModel.intents.filter(item => !item.name.startsWith("AMAZON."));
      for (const [kind, intentName] of [["train", "TrainPunctualityIntent"], ["route", "RoutePunctualityIntent"], ["best", "MostPunctualTrainIntent"]] as const) {
        const utterance = utteranceOf(locale, PUNCTUALITY_PHRASES[locale][kind]);
        const reached = intents.filter(item => item.samples.some(sample => sampleMatches(locale, sample, utterance ?? ""))).map(item => item.name);
        if (reached.join() !== intentName) problems.push(`${locale}: "${utterance}" reaches ${reached.join(", ") || "nothing"}`);
      }
    }
    expect(problems).toEqual([]);
  });

  it("never uses one sample for two intents", () => {
    for (const locale of LOCALES) {
      const samples = model(locale).interactionModel.languageModel.intents.flatMap(item => item.samples.map(sample => plain(locale, sample)));
      expect(samples.filter((sample, index) => samples.indexOf(sample) !== index), locale).toEqual([]);
    }
  });

  it("keeps every sample, with its slots filled, out of every other intent's samples", () => {
    const filled = (locale: string, sample: string) =>
      plain(locale, sample.replace("{routeName}", "casa lavoro").replace("{trainNumber}", "9558"));
    // A number slot takes digits only (AMAZON.NUMBER), a search slot any words. plain() lowers the slot names too.
    const fits = (locale: string, item: ModelIntent, sample: string, utterance: string) => {
      const pattern = escapeRegExp(plain(locale, sample)).replace(/\\\{([A-Za-z]+)\\\}/g, (_match, name: string) =>
        item.slots?.find(slot => slot.name.toLowerCase() === name)?.type === "AMAZON.NUMBER" ? "[0-9]+" : ".+"
      );
      return new RegExp(`^${pattern}$`).test(utterance);
    };
    const problems: string[] = [];
    for (const locale of LOCALES) {
      const intents = model(locale).interactionModel.languageModel.intents.filter(item => !item.name.startsWith("AMAZON."));
      for (const item of intents)
        for (const sample of item.samples)
          for (const other of intents)
            if (other !== item && other.samples.some(candidate => fits(locale, other, candidate, filled(locale, sample))))
              problems.push(`${locale}: "${sample}" (${item.name}) also fits ${other.name}`);
    }
    expect(problems).toEqual([]);
  });

  it("asks which train and which route with phrases the punctuality intents understand", () => {
    for (const locale of LOCALES) {
      const p = messagesForLocale(locale).punctuality;
      const samplesOf = (name: string) => model(locale).interactionModel.languageModel.intents.find(item => item.name === name)!.samples;
      const trainQuestion = plain(locale, p.trainMissing);
      expect(
        samplesOf("TrainPunctualityIntent").some(sample => trainQuestion.includes(plain(locale, sample.replace("{trainNumber}", "9558")))),
        `${locale}: ${trainQuestion}`
      ).toBe(true);
      const routeQuestion = plain(locale, formatMessage(p.routeChoice, { example: "mare" }));
      expect(
        samplesOf("RoutePunctualityIntent").some(sample => sample.includes("{routeName}") && routeQuestion.includes(plain(locale, sample.replace("{routeName}", "mare")))),
        `${locale}: ${routeQuestion}`
      ).toBe(true);
      const bestQuestion = plain(locale, formatMessage(p.mostPunctualRouteChoice, { example: "mare" }));
      expect(
        samplesOf("MostPunctualTrainIntent").some(sample => sample.includes("{routeName}") && bestQuestion.includes(plain(locale, sample.replace("{routeName}", "mare")))),
        `${locale}: ${bestQuestion}`
      ).toBe(true);
    }
  });

  it("asks which route with a phrase the locale's NextTrainsIntent understands", () => {
    for (const locale of LOCALES) {
      const choice = plain(locale, formatMessage(messagesForLocale(locale).voice.routeChoice, { example: "mare" }));
      const samples = model(locale).interactionModel.languageModel.intents.find(item => item.name === "NextTrainsIntent")!.samples;
      expect(
        samples.some(sample => sample.includes("{routeName}") && choice.includes(plain(locale, sample.replace("{routeName}", "mare")))),
        `${locale}: ${choice}`
      ).toBe(true);
    }
  });

  it("declares the APL interface for the Echo Show screens, rectangular and round hubs", () => {
    const custom = manifest().manifest.apis.custom as { interfaces?: Array<{ type: string; supportedViewports?: Array<{ mode: string; shape: string }> }> };
    const apl = custom.interfaces?.find(item => item.type === "ALEXA_PRESENTATION_APL");
    expect(apl?.supportedViewports?.map(viewport => `${viewport.mode}:${viewport.shape}`).sort()).toEqual(["HUB:RECTANGLE", "HUB:ROUND"]);
  });

  it("keeps all manifest URLs HTTPS and deliberately unconfigured", () => {
    const skillManifest = manifest();
    const serialized = JSON.stringify(skillManifest);
    expect(skillManifest.manifest.apis.custom.endpoint.uri).toMatch(
      /^https:\/\/REPLACE_WITH_HTTPS_HOST\//
    );
    expect(serialized).not.toMatch(/http:\/\//);
    expect(serialized.match(/REPLACE_WITH_HTTPS_HOST/g)?.length).toBeGreaterThan(0);
  });
});

describe("punctuality intents", () => {
  type SkillResponse = Awaited<ReturnType<ReturnType<typeof createAlexaSkill>["invoke"]>>;
  const ROUTES = [
    { id: 1, name: "Casa-Lavoro", origin: "Roma Termini", destination: "Tivoli", isPinned: true },
    { id: 2, name: "Mare", origin: "Roma Ostiense", destination: "Ostia", isPinned: false },
    { id: 3, name: "Milano", origin: "Tivoli", destination: "Milano Centrale", isPinned: false },
  ];
  const card = (response: SkillResponse) => (response.response.card as { content?: string } | undefined)?.content ?? "";
  const screenOf = (response: SkillResponse) =>
    (response.response.directives?.find(item => item.type === "Alexa.Presentation.APL.RenderDocument") as
      | { datasources: { binario: Record<string, unknown> & { stats: Array<{ value: string; label: string }>; routes: Array<{ event: string; name: string }> } } }
      | undefined)?.datasources.binario;

  function withApl(request: Envelope): Envelope {
    request.context.System.device!.supportedInterfaces["Alexa.Presentation.APL"] = { runtime: { maxVersion: "1.8" } };
    return request;
  }

  async function linked(request: Envelope) {
    vi.mocked(getAlexaLinkStatus).mockResolvedValue({ userId: 7, credentialVersion: 3, revokedAt: null } as Awaited<ReturnType<typeof getAlexaLinkStatus>>);
    vi.mocked(listPersonalRoutesForAlexa).mockResolvedValue(ROUTES as Awaited<ReturnType<typeof listPersonalRoutesForAlexa>>);
    return withAccessToken(request, await issueVoiceAccessToken(7, 3));
  }

  function withStatistics(available = true) {
    railData.punctualityStats.mockResolvedValue(available ? parsePunctualityStats(networkDocument()) : null);
    railData.railStationIndex.mockResolvedValue(parseStationCatalog(catalogDocument()));
  }

  it("answers a train's punctuality at its terminus, with the attribution, without an account link", async () => {
    withStatistics();
    vi.mocked(getAlexaLinkStatus).mockClear();
    const response = await createAlexaSkill().invoke(intent("TrainPunctualityIntent", "it-IT", { trainNumber: "9558" }));
    expect(card(response)).toBe(
      "Il treno 9558 è arrivato al capolinea in orario, cioè entro 5 minuti, 81 volte su 100 in 28 giorni osservati. " +
        "Il ritardo medio è stato di 6 minuti. È stato soppresso 1 volta. " +
        "Sono statistiche non ufficiali di Binario su dati ViaggiaTreno."
    );
    expect(speechText(response)).toContain("81 volte su 100");
    expect(response.response.shouldEndSession).toBe(true);
    expect(getAlexaLinkStatus).not.toHaveBeenCalled();
  });

  it.each([
    ["en-US", "Train 9558 reached its final stop on time, that is within 5 minutes, 81 times out of 100 over 28 observed days."],
    ["de-DE", "Zug 9558 kam an 28 beobachteten Tagen 81-mal von 100 pünktlich an der Endstation an, also mit einer Verspätung von höchstens 5 Minuten."],
    ["fr-FR", "Le train 9558 est arrivé à l’heure à son terminus, c’est-à-dire avec un retard d’au plus 5 minutes, 81 fois sur 100 en 28 jours observés."],
    ["es-ES", "El tren 9558 llegó puntual a su destino final, es decir, con un retraso máximo de 5 minutos, 81 veces de cada 100 en 28 días observados."],
    ["pt-BR", "Em 28 dias observados, o trem 9558 chegou no horário ao destino final 81% das vezes, ou seja, com atraso de no máximo 5 minutos."],
    ["ar-SA", "وصل القطار 9558 إلى محطته الأخيرة في الموعد، أي بتأخير لا يتجاوز 5 دقائق، 81 مرة من كل 100 خلال 28 يومًا مرصودًا."],
    ["hi-IN", "28 देखे गए दिनों में ट्रेन 9558 अपने आख़िरी स्टेशन पर 100 में से 81 बार समय पर पहुँची, यानी अधिकतम 5 मिनट की देरी के साथ।"],
    ["ja-JP", "9558 号が終着駅に定刻どおり、つまり遅れ 5 分以内で着いたのは、観測した 28 日間で 100 回中 81 回でした。平均の遅れは 6 分でした。"],
  ] as const)("%s: says the train's on-time share, mean delay, observed days and source", async (locale, expected) => {
    withStatistics();
    const response = await createAlexaSkill().invoke(intent("TrainPunctualityIntent", locale, { trainNumber: "9558" }));
    const p = messagesForLocale(locale).punctuality;
    expect(card(response).startsWith(expected), card(response)).toBe(true);
    expect(card(response).endsWith(p.source)).toBe(true);
  });

  it("says when the train is unknown, has too few observations or was cancelled every day", async () => {
    withStatistics();
    const answer = async (trainNumber: string) => card(await createAlexaSkill().invoke(intent("TrainPunctualityIntent", "it-IT", { trainNumber })));
    expect(await answer("4242")).toBe("Non ho statistiche di puntualità per il treno 4242.");
    expect(await answer("1234")).toBe("Per il treno 1234 non ci sono ancora abbastanza osservazioni.");
    expect(await answer("7777")).toBe(
      "Il treno 7777 è stato soppresso in ognuno dei 7 giorni osservati. Sono statistiche non ufficiali di Binario su dati ViaggiaTreno."
    );
  });

  it("asks for the number when none was understood, keeping the session open", async () => {
    withStatistics();
    for (const slots of [undefined, { trainNumber: "?" }]) {
      const response = await createAlexaSkill().invoke(intent("TrainPunctualityIntent", "it-IT", slots));
      expect(card(response)).toBe(messagesForLocale("it-IT").punctuality.trainMissing);
      expect(response.response.shouldEndSession).toBe(false);
      expect(response.response.reprompt).toBeDefined();
    }
  });

  it("says the statistics are not available yet when the file is absent", async () => {
    withStatistics(false);
    const train = await createAlexaSkill().invoke(intent("TrainPunctualityIntent", "it-IT", { trainNumber: "9558" }));
    expect(card(train)).toBe("Le statistiche di puntualità di Binario non sono ancora disponibili.");
    const route = await createAlexaSkill().invoke(await linked(intent("RoutePunctualityIntent", "en-GB")));
    expect(card(route)).toBe("Binario's punctuality statistics are not available yet.");
    expect(listPersonalRoutesForAlexa).not.toHaveBeenCalledWith(7, 4);
  });

  it("aggregates a saved route of the linked user, by spoken name or the pinned one", async () => {
    withStatistics();
    const expected =
      "Sulla tratta Casa-Lavoro, con 3 treni in 28 giorni osservati, gli arrivi a destinazione sono stati in orario, cioè entro 15 minuti, 93 volte su 100. " +
      "Il ritardo medio è stato di 5 minuti. Sono statistiche non ufficiali di Binario su dati ViaggiaTreno.";
    const spoken = await createAlexaSkill().invoke(await linked(intent("RoutePunctualityIntent", "it-IT", { routeName: "casa lavoro" })));
    expect(card(spoken)).toBe(expected);
    const pinned = await createAlexaSkill().invoke(await linked(intent("RoutePunctualityIntent", "it-IT")));
    expect(card(pinned)).toBe(expected);
    expect(JSON.stringify(spoken.response)).not.toMatch(/Roma Termini|Tivoli/);
  });

  it("asks for linking before reading a saved route", async () => {
    withStatistics();
    const response = await createAlexaSkill().invoke(intent("RoutePunctualityIntent", "it-IT", { routeName: "casa lavoro" }));
    expect(response.response.card?.type).toBe("LinkAccount");
  });

  it("says when the route's stations are not recognised or its trains have too few observations", async () => {
    withStatistics();
    const ask = async (routeName: string) =>
      card(await createAlexaSkill().invoke(await linked(intent("RoutePunctualityIntent", "it-IT", { routeName }))));
    expect(await ask("mare")).toBe(
      "Non riconosco le stazioni della tratta Mare: nella dashboard Binario indica origine e destinazione con il nome della stazione, per esempio Roma Termini."
    );
    expect(await ask("milano")).toBe("Per la tratta Milano non ci sono ancora abbastanza osservazioni.");
  });

  it("asks which route with a punctuality example, and its screen rows ask for punctuality", async () => {
    withStatistics();
    const response = await createAlexaSkill().invoke(withApl(await linked(intent("RoutePunctualityIntent", "it-IT", { routeName: "montagna" }))));
    expect(card(response)).toBe(
      "Non ho trovato la tratta montagna. Le tue tratte salvate sono: Casa-Lavoro, Mare e Milano. Quale tratta ti interessa? Puoi dire, per esempio: quanto è puntuale la tratta Casa-Lavoro."
    );
    expect(response.response.shouldEndSession).toBe(false);
    expect(screenOf(response)?.routes.map(route => route.event)).toEqual(["binario.punctuality", "binario.punctuality", "binario.punctuality"]);
  });

  it("projects the answer on Echo Show: figures, unofficial statistics label and the window end", async () => {
    withStatistics();
    const train = screenOf(await createAlexaSkill().invoke(withApl(intent("TrainPunctualityIntent", "it-IT", { trainNumber: "9558" }))));
    expect(train).toMatchObject({
      screen: "punctuality",
      title: "Puntualità",
      hero: "Treno 9558",
      quality: { state: "statistics", label: "Statistiche non ufficiali di Binario su dati ViaggiaTreno", checked: "Dati fino al 9 novembre 2026" },
    });
    expect(train?.stats.map(stat => `${stat.value} ${stat.label}`)).toEqual(["81% In orario", "6 min Ritardo medio", "28 Giorni osservati"]);
    const route = screenOf(await createAlexaSkill().invoke(withApl(await linked(intent("RoutePunctualityIntent", "de-DE")))));
    expect(route).toMatchObject({ hero: "Casa-Lavoro", title: "Pünktlichkeit" });
    // German writes a no-break space before the percent sign.
    expect(route?.stats.map(stat => stat.value)).toEqual(["93\u00a0%", "5 Min.", "28", "3"]);
  });

  it("answers a touched route's punctuality after re-checking the link", async () => {
    withStatistics();
    const event = envelope({ type: "Alexa.Presentation.APL.UserEvent", token: "binario.myRoutes", arguments: ["binario.punctuality", "Casa-Lavoro"] });
    const response = await createAlexaSkill().invoke(withApl(await linked(event)));
    expect(card(response)).toContain("Sulla tratta Casa-Lavoro, con 3 treni");
    const unlinked = await createAlexaSkill().invoke(withApl(envelope({ type: "Alexa.Presentation.APL.UserEvent", token: "binario.myRoutes", arguments: ["binario.punctuality", "Casa-Lavoro"] })));
    expect(unlinked.response.card?.type).toBe("LinkAccount");
  });

  it("logs a failed punctuality request without the route, its stations or the train number", async () => {
    const errors = vi.spyOn(console, "error").mockImplementation(() => undefined);
    railData.punctualityStats.mockResolvedValue(parsePunctualityStats(networkDocument()));
    railData.railStationIndex.mockRejectedValueOnce(new Error("catalogue unreadable"));
    await createAlexaSkill().invoke(await linked(intent("RoutePunctualityIntent", "it-IT", { routeName: "casa lavoro" })));
    expect(errors).toHaveBeenCalledTimes(1);
    expect(errors.mock.calls[0][1]).toMatchObject({ intent: "RoutePunctualityIntent", error: { name: "Error" } });
    const serialized = JSON.stringify(errors.mock.calls);
    for (const secret of ["casa lavoro", "Casa-Lavoro", "Roma Termini", "Tivoli", "amzn1.ask.account"]) expect(serialized).not.toContain(secret);
    errors.mockRestore();
  });
});

describe("most punctual train intent", () => {
  type SkillResponse = Awaited<ReturnType<ReturnType<typeof createAlexaSkill>["invoke"]>>;
  const ROUTES = [
    { id: 1, name: "Casa-Lavoro", origin: "Roma Termini", destination: "Tivoli", isPinned: true },
    { id: 2, name: "Mare", origin: "Roma Ostiense", destination: "Ostia", isPinned: false },
    { id: 3, name: "Casa-Mare", origin: "Roma Termini", destination: "Ostia", isPinned: false },
  ];
  const BEST =
    "Per la tratta Casa-Lavoro, fra i treni che di solito partono nelle prossime tre ore, il più puntuale è il 2157 delle 8:12, in orario 9 volte su 10. " +
    "Poi il 2161 delle 9:12, in orario 8 volte su 10 e il 2165 delle 10:12, in orario 7 volte su 10. " +
    "Sono statistiche non ufficiali di Binario su dati ViaggiaTreno.";
  const card = (response: SkillResponse) => (response.response.card as { content?: string } | undefined)?.content ?? "";
  const screenOf = (response: SkillResponse) =>
    (response.response.directives?.find(item => item.type === "Alexa.Presentation.APL.RenderDocument") as
      | { datasources: { binario: Record<string, unknown> & { trains: Array<Record<string, string>>; routes: Array<{ event: string; name: string }> } } }
      | undefined)?.datasources.binario;

  function at(request: Envelope, isoInstant: string): Envelope {
    (request.request as { timestamp: string }).timestamp = isoInstant;
    return request;
  }

  function withApl(request: Envelope): Envelope {
    request.context.System.device!.supportedInterfaces["Alexa.Presentation.APL"] = { runtime: { maxVersion: "1.8" } };
    return request;
  }

  async function linked(request: Envelope) {
    vi.mocked(getAlexaLinkStatus).mockResolvedValue({ userId: 7, credentialVersion: 3, revokedAt: null } as Awaited<ReturnType<typeof getAlexaLinkStatus>>);
    vi.mocked(listPersonalRoutesForAlexa).mockResolvedValue(ROUTES as Awaited<ReturnType<typeof listPersonalRoutesForAlexa>>);
    return withAccessToken(request, await issueVoiceAccessToken(7, 3));
  }

  function withDepartures(available = true) {
    railData.punctualityStats.mockResolvedValue(available ? parsePunctualityStats(departuresDocument()) : null);
    railData.railStationIndex.mockResolvedValue(parseStationCatalog(catalogDocument()));
  }

  const ask = async (slots?: Record<string, string>, instant = MONDAY_MORNING, locale = "it-IT") =>
    createAlexaSkill().invoke(at(await linked(intent("MostPunctualTrainIntent", locale, slots)), instant));

  it("asks for linking before reading any route, and shows nothing", async () => {
    withDepartures();
    vi.mocked(listPersonalRoutesForAlexa).mockClear();
    const response = await createAlexaSkill().invoke(withApl(at(intent("MostPunctualTrainIntent", "it-IT", { routeName: "casa lavoro" }), MONDAY_MORNING)));
    expect(response.response.card?.type).toBe("LinkAccount");
    expect(response.response.shouldEndSession).toBe(true);
    expect(response.response.directives ?? []).toHaveLength(0);
    expect(listPersonalRoutesForAlexa).not.toHaveBeenCalled();
  });

  it("names the most punctual train of the next three hours and two more, for the spoken or the pinned route", async () => {
    withDepartures();
    const spoken = await ask({ routeName: "casa lavoro" });
    expect(card(spoken)).toBe(BEST);
    expect(speechText(spoken)).toContain("il 2157 delle 8:12, in orario 9 volte su 10");
    expect(spoken.response.shouldEndSession).toBe(true);
    expect(card(await ask())).toBe(BEST);
    // Only the route's name: never its stations.
    expect(JSON.stringify(spoken.response)).not.toMatch(/Roma|Tivoli|Ostia/);
  });

  it("follows the weekday: on Saturday the weekend train leads", async () => {
    withDepartures();
    // Saturday 7 November 2026 at 07:30 in Rome.
    expect(card(await ask(undefined, "2026-11-07T06:30:00Z"))).toContain("il più puntuale è il 2167 delle 8:30, in orario 10 volte su 10. Poi il 2157 delle 8:12");
  });

  it("says clearly when no train with enough observations leaves in the next three hours", async () => {
    withDepartures();
    // Monday at 14:00: the last departure of the fixture is at 11:00.
    const response = await ask(undefined, "2026-11-09T13:00:00Z");
    expect(card(response)).toBe("Per la tratta Casa-Lavoro nelle prossime tre ore non parte nessun treno con abbastanza osservazioni.");
    expect(response.response.shouldEndSession).toBe(true);
  });

  it("says when the route's stations are not recognised, or the statistics are not available", async () => {
    withDepartures();
    expect(card(await ask({ routeName: "mare" }))).toBe(
      "Non riconosco le stazioni della tratta Mare: nella dashboard Binario indica origine e destinazione con il nome della stazione, per esempio Roma Termini."
    );
    withDepartures(false);
    vi.mocked(listPersonalRoutesForAlexa).mockClear();
    const unavailable = createAlexaSkill().invoke(at(withAccessToken(intent("MostPunctualTrainIntent", "en-GB"), await issueVoiceAccessToken(7, 3)), MONDAY_MORNING));
    expect(card(await unavailable)).toBe("Binario's punctuality statistics are not available yet.");
    expect(listPersonalRoutesForAlexa).not.toHaveBeenCalled();
  });

  it("asks which route with a most-punctual example, and its screen rows ask for the most punctual train", async () => {
    withDepartures();
    const ambiguous = await createAlexaSkill().invoke(withApl(at(await linked(intent("MostPunctualTrainIntent", "it-IT", { routeName: "casa" })), MONDAY_MORNING)));
    expect(card(ambiguous)).toBe(
      "Ho trovato più tratte: Casa-Lavoro e Casa-Mare. Quale tratta ti interessa? Puoi dire, per esempio: qual è il treno più puntuale per Casa-Lavoro."
    );
    expect(ambiguous.response.shouldEndSession).toBe(false);
    expect(JSON.stringify(ambiguous.response.reprompt)).toContain("qual è il treno più puntuale per Casa-Lavoro");
    expect(screenOf(ambiguous)?.routes.map(route => route.event)).toEqual(["binario.mostPunctual", "binario.mostPunctual"]);
    const missing = await ask({ routeName: "montagna" }, MONDAY_MORNING, "en-US");
    expect(card(missing)).toBe(
      "I couldn't find the route montagna. Your saved routes are: Casa-Lavoro, Mare, and Casa-Mare. Which route would you like? You can say, for example: which is the most punctual train for Casa-Lavoro."
    );
  });

  it("answers a touched route after re-checking the link", async () => {
    withDepartures();
    const event = () =>
      at(envelope({ type: "Alexa.Presentation.APL.UserEvent", token: "binario.myRoutes", arguments: ["binario.mostPunctual", "Casa-Lavoro"] }), MONDAY_MORNING);
    expect(card(await createAlexaSkill().invoke(withApl(await linked(event()))))).toBe(BEST);
    const unlinked = await createAlexaSkill().invoke(withApl(event()));
    expect(unlinked.response.card?.type).toBe("LinkAccount");
    expect(unlinked.response.directives ?? []).toHaveLength(0);
  });

  it("lists the trains on the Echo Show punctuality screen under the statistics banner", async () => {
    withDepartures();
    const screen = screenOf(await createAlexaSkill().invoke(withApl(at(await linked(intent("MostPunctualTrainIntent", "it-IT")), MONDAY_MORNING))));
    expect(screen).toMatchObject({
      screen: "punctuality",
      title: "Puntualità",
      hero: "Casa-Lavoro",
      stats: [],
      quality: { state: "statistics", label: "Statistiche non ufficiali di Binario su dati ViaggiaTreno", checked: "Dati fino al 9 novembre 2026" },
    });
    expect(screen?.trains.map(row => `${row.time} ${row.train} ${row.value}`)).toEqual(["8:12 Treno 2157 90%", "9:12 Treno 2161 80%", "10:12 Treno 2165 70%"]);
    expect(JSON.stringify(screen)).not.toMatch(/Roma|Tivoli/);
  });

  it.each(LOCALES)("%s: answers in the request's language, ending with the source", async locale => {
    withDepartures();
    const response = await ask(undefined, MONDAY_MORNING, locale);
    const p = messagesForLocale(locale).punctuality;
    expect(card(response).endsWith(p.source), card(response)).toBe(true);
    for (const number of ["2157", "2161", "2165"]) expect(card(response)).toContain(number);
    expect(speechText(response)).toContain("Casa-Lavoro");
    expect(card(response)).not.toMatch(/[{}]/);
  });

  it("logs nothing about the route, its stations or the trains, on success or failure", async () => {
    const calls: unknown[][] = [];
    const spies = (["log", "info", "warn", "error", "debug"] as const).map(level =>
      vi.spyOn(console, level).mockImplementation((...args: unknown[]) => void calls.push(args))
    );
    try {
      withDepartures();
      await ask({ routeName: "casa lavoro" });
      railData.railStationIndex.mockRejectedValueOnce(new Error("catalogue unreadable"));
      await ask({ routeName: "casa lavoro" });
      const errors = calls.filter(args => args[0] === "[Alexa] request failed");
      expect(errors).toHaveLength(1);
      expect(errors[0][1]).toMatchObject({ intent: "MostPunctualTrainIntent", error: { name: "Error" } });
      const serialized = JSON.stringify(calls);
      for (const secret of ["casa lavoro", "Casa-Lavoro", "Roma Termini", "Tivoli", "2157", "2161", "8:12", "amzn1.ask.account"])
        expect(serialized).not.toContain(secret);
    } finally {
      for (const spy of spies) spy.mockRestore();
    }
  });
});
