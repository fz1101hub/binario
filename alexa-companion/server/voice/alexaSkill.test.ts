import { formatMessage, messagesForLocale, SUPPORTED_LOCALES } from "@shared/i18n";
import { DrizzleQueryError } from "drizzle-orm";
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it, vi } from "vitest";

const SKILL_ID = "amzn1.ask.skill.test";
const LOCALES = ["it-IT", "en-US", "en-GB", "de-DE", "fr-FR", "es-ES", "ar-SA", "hi-IN", "ja-JP", "pt-BR"] as const;

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

import { getAlexaLinkStatus, listPersonalRoutesForAlexa } from "../db";
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
    for (const name of ["NextTrainsIntent", "MyRoutesIntent", "TrainStatusIntent"]) {
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
    for (const name of ["NextTrainsIntent", "MyRoutesIntent", "TrainStatusIntent", "AMAZON.StopIntent"]) {
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
    const response = await createAlexaSkill().invoke(await linked("TrainStatusIntent", { trainNumber: unsafeText }));
    expect(speechText(response)).toContain(escapedUnsafeText);
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
    "de-DE": { open: "^alexa, öffne INV$", ask: "^alexa, frage INV (?:nach |ob )?(.+)$" },
    "fr-FR": { open: "^alexa, ouvre INV$", ask: "^alexa, demande à INV (?:de |si )?(.+)$" },
    "es-ES": { open: "^alexa, abre INV$", ask: "^alexa, pregunta a INV (?:por |si )?(.+)$" },
    "ar-SA": { open: "^أليكسا، افتح INV$", ask: "^أليكسا، اسأل INV (?:عن )?(.+)$" },
    "hi-IN": { open: "^अलेक्सा, INV खोलो$", ask: "^अलेक्सा, INV से (.+) पूछो$" },
    "ja-JP": { open: "^アレクサ、INVを開いて$", ask: "^アレクサ、INVで(.+)$" },
    "pt-BR": { open: "^alexa, abra INV$", ask: "^alexa, pergunte ao INV (.+)$" },
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
