import { SUPPORTED_LOCALES } from "@shared/i18n";
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it, vi } from "vitest";

const SKILL_ID = "amzn1.ask.skill.test";
const LOCALES = ["it-IT", "en-US", "en-GB", "de-DE", "fr-FR", "es-ES"] as const;

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

vi.mock("../rail/rfi", () => ({ getLiveDepartures: vi.fn(async () => undefined) }));

import { getAlexaLinkStatus, listPersonalRoutesForAlexa } from "../db";
import { getLiveDepartures } from "../rail/rfi";
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
  it("speaks the real provider delay and renders the same data on Echo Show", async () => {
    vi.mocked(getAlexaLinkStatus).mockResolvedValue({ userId: 7, credentialVersion: 3, revokedAt: null } as Awaited<ReturnType<typeof getAlexaLinkStatus>>);
    vi.mocked(listPersonalRoutesForAlexa).mockResolvedValue([
      { id: 1, name: "Lavoro", origin: "Milano Centrale", destination: "Lodi", isPinned: true },
    ] as Awaited<ReturnType<typeof listPersonalRoutesForAlexa>>);
    const now = Date.now();
    vi.mocked(getLiveDepartures).mockResolvedValueOnce({ observedAt: now, departures: [{
      number: "2451", destination: "Bologna Centrale", scheduledAt: now, expectedAt: now + 900_000,
      delayMinutes: 15, cancelled: false, platform: "7", stopNames: new Set(["LODI"]),
    }] });
    const request = withAccessToken(intent("NextTrainsIntent"), await issueVoiceAccessToken(7, 3));
    request.context.System.device!.supportedInterfaces["Alexa.Presentation.APL"] = { runtime: { maxVersion: "1.8" } };
    const response = await createAlexaSkill().invoke(request);
    expect(getLiveDepartures).toHaveBeenLastCalledWith("Milano Centrale", "Lodi");
    expect(speechText(response)).toContain("15 minuti di ritardo");
    expect(speechText(response)).toContain("Binario 7");
    expect(speechText(response)).not.toContain("dati non disponibili");
    expect(JSON.stringify(response.response.directives)).toContain("15 minuti di ritardo");
  });

  it("keeps voice responses identical on Echo speakers and adds an accessible summary on Echo Show", async () => {
    const speaker = envelope({ type: "LaunchRequest" });
    const screen = envelope({ type: "LaunchRequest" });
    screen.context.System.device!.supportedInterfaces["Alexa.Presentation.APL"] = { runtime: { maxVersion: "1.8" } };
    const voiceResponse = await createAlexaSkill().invoke(speaker);
    const screenResponse = await createAlexaSkill().invoke(screen);
    expect(screenResponse.response.outputSpeech).toEqual(voiceResponse.response.outputSpeech);
    expect(voiceResponse.response.directives ?? []).toHaveLength(0);
    expect(screenResponse.response.directives).toContainEqual(expect.objectContaining({
      type: "Alexa.Presentation.APL.RenderDocument",
      datasources: { summary: { title: "Binario", content: expect.stringContaining("Benvenuta in Binario") } },
    }));
    expect(JSON.stringify(screenResponse.response.directives)).toContain("accessibilityLabel");
  });

  it("does not render personal information while linking is required or after Stop", async () => {
    for (const name of ["NextTrainsIntent", "AMAZON.StopIntent"]) {
      const request = intent(name);
      request.context.System.device!.supportedInterfaces["Alexa.Presentation.APL"] = { runtime: { maxVersion: "1.8" } };
      const response = await createAlexaSkill().invoke(request);
      expect(response.response.directives ?? []).toHaveLength(0);
    }
  });

  it("falls back to voice and card on devices with an older APL runtime", async () => {
    const request = envelope({ type: "LaunchRequest" });
    request.context.System.device!.supportedInterfaces["Alexa.Presentation.APL"] = { runtime: { maxVersion: "1.0" } };
    const response = await createAlexaSkill().invoke(request);
    expect(response.response.directives ?? []).toHaveLength(0);
    expect(response.response.outputSpeech).toBeDefined();
    expect(response.response.card?.type).toBe("Simple");
  });

  it("escapes route markup in APL and retains the explicit realtime availability caveat", async () => {
    vi.mocked(getAlexaLinkStatus).mockResolvedValue({ userId: 7, credentialVersion: 3, revokedAt: null } as Awaited<ReturnType<typeof getAlexaLinkStatus>>);
    vi.mocked(listPersonalRoutesForAlexa).mockResolvedValue([
      { id: 1, name: "Casa & <lavoro>", origin: "Roma", destination: "Tivoli", isPinned: true },
    ] as Awaited<ReturnType<typeof listPersonalRoutesForAlexa>>);
    const request = withAccessToken(intent("NextTrainsIntent"), await issueVoiceAccessToken(7, 3));
    request.context.System.device!.supportedInterfaces["Alexa.Presentation.APL"] = { runtime: { maxVersion: "1.8" } };
    const response = await createAlexaSkill().invoke(request);
    const directive = response.response.directives?.find(item => item.type === "Alexa.Presentation.APL.RenderDocument");
    expect(directive).toBeDefined();
    expect(JSON.stringify(directive)).toContain("Casa &amp; &lt;lavoro&gt;");
    expect(JSON.stringify(directive)).toContain("dati non disponibili in tempo reale");
    expect(response.response.card).toMatchObject({ type: "Simple", content: expect.stringContaining("Casa & <lavoro>") });
  });

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
    ["it-IT", "Benvenuta in Binario"],
    ["en-US", "Welcome to Binario"],
    ["en-GB", "Welcome to Binario"],
    ["de-DE", "Willkommen bei Binario"],
    ["fr-FR", "Bienvenue dans Binario"],
    ["es-ES", "Te damos la bienvenida a Binario"],
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
      envelope({ type: "LaunchRequest" }, "pt-BR")
    );
    expect(speechText(response)).toContain("Benvenuta in Binario");
  });

  it("rejects requests for another skill", async () => {
    const other = intent("AMAZON.HelpIntent");
    (other.context.System.application as { applicationId: string }).applicationId =
      "amzn1.ask.skill.other";
    await expect(createAlexaSkill().invoke(other)).rejects.toThrow();
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
