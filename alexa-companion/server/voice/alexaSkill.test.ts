import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it, vi } from "vitest";

const SKILL_ID = "amzn1.ask.skill.test";

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

const dbMocks = vi.hoisted(() => ({
  createAuthorizationCode: vi.fn(),
  exchangeAuthorizationCode: vi.fn(),
  getAlexaLinkStatus: vi.fn(),
  listPersonalRoutesForAlexa: vi.fn(),
  recordAlexaVerification: vi.fn(),
  rotateRefreshToken: vi.fn(),
}));
vi.mock("../db", () => dbMocks);
import { createAlexaSkill, HANDLED_INTENTS } from "./alexa";
import { issueVoiceAccessToken } from "./crypto";

type Envelope = Parameters<ReturnType<typeof createAlexaSkill>["invoke"]>[0];

function envelope(request: Record<string, unknown>): Envelope {
  const user = { userId: "amzn1.ask.account.TEST" };
  return {
    version: "1.0",
    session: {
      new: true,
      sessionId: "amzn1.echo-api.session.test",
      application: { applicationId: SKILL_ID },
      user,
    },
    context: {
      System: {
        application: { applicationId: SKILL_ID },
        user,
        device: { deviceId: "device", supportedInterfaces: {} },
        apiEndpoint: "https://api.eu.amazonalexa.com",
      },
    },
    request: {
      requestId: "amzn1.echo-api.request.test",
      timestamp: new Date().toISOString(),
      locale: "it-IT",
      ...request,
    },
  } as unknown as Envelope;
}

const intent = (name: string) =>
  envelope({
    type: "IntentRequest",
    intent: { name, confirmationStatus: "NONE" },
  });

function outputSsml(response: unknown) {
  const outputSpeech = (
    response as { response: { outputSpeech?: { type: string; ssml?: string } } }
  ).response.outputSpeech;
  expect(outputSpeech?.type).toBe("SSML");
  return outputSpeech?.ssml ?? "";
}

async function linkedIntent(name: string, slots?: Record<string, string>) {
  const request = envelope({
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
  });
  request.context.System.user.accessToken = await issueVoiceAccessToken(7, 3);
  return request;
}

describe("handler built-in della skill", () => {
  it("Stop, Cancel e NavigateHome chiudono la sessione", async () => {
    for (const name of [
      "AMAZON.StopIntent",
      "AMAZON.CancelIntent",
      "AMAZON.NavigateHomeIntent",
    ]) {
      const response = await createAlexaSkill().invoke(intent(name));
      expect(response.response.shouldEndSession).toBe(true);
      expect(JSON.stringify(response.response.outputSpeech)).toContain(
        "A presto"
      );
    }
  });

  it("Help spiega cosa si può chiedere e mantiene la sessione aperta", async () => {
    const response = await createAlexaSkill().invoke(
      intent("AMAZON.HelpIntent")
    );
    expect(response.response.shouldEndSession).toBe(false);
    expect(JSON.stringify(response.response.outputSpeech)).toContain(
      "prossimi treni"
    );
    expect(response.response.reprompt).toBeDefined();
  });

  it("SessionEndedRequest non produce alcuna voce", async () => {
    const response = await createAlexaSkill().invoke(
      envelope({ type: "SessionEndedRequest", reason: "USER_INITIATED" })
    );
    expect(response.response.outputSpeech).toBeUndefined();
  });

  it("un intent personale senza collegamento restituisce la card di account linking", async () => {
    const response = await createAlexaSkill().invoke(intent("MyRoutesIntent"));
    expect(response.response.card?.type).toBe("LinkAccount");
    expect(response.response.shouldEndSession).toBe(true);
  });

  it("rifiuta richieste destinate a un'altra skill", async () => {
    const other = intent("AMAZON.HelpIntent");
    (
      other.context.System.application as { applicationId: string }
    ).applicationId = "amzn1.ask.skill.other";
    await expect(createAlexaSkill().invoke(other)).rejects.toThrow();
  });
});

describe("valori dinamici nelle risposte vocali", () => {
  const unsafeText = `A & B < C > D "virgolette" 'apostrofi' <break time="1s"/>`;
  const escapedUnsafeText =
    "A &amp; B &lt; C &gt; D &quot;virgolette&quot; &apos;apostrofi&apos; " +
    "&lt;break time=&quot;1s&quot;/&gt;";

  it("escapa i nomi delle tratte senza alterare il markup SSML statico", async () => {
    dbMocks.getAlexaLinkStatus.mockResolvedValue({
      userId: 7,
      credentialVersion: 3,
      revokedAt: null,
    });
    dbMocks.recordAlexaVerification.mockResolvedValue(undefined);
    dbMocks.listPersonalRoutesForAlexa.mockResolvedValue([
      { name: unsafeText, isPinned: true },
    ]);

    const nextTrains = await createAlexaSkill().invoke(
      await linkedIntent("NextTrainsIntent")
    );
    const myRoutes = await createAlexaSkill().invoke(
      await linkedIntent("MyRoutesIntent")
    );

    for (const response of [nextTrains, myRoutes]) {
      expect(outputSsml(response)).toContain("<speak>");
      expect(outputSsml(response)).toContain(escapedUnsafeText);
      expect(outputSsml(response)).not.toContain(`<break time="1s"/>`);
    }
  });

  it("escapa il numero del treno fornito dall'utente", async () => {
    dbMocks.getAlexaLinkStatus.mockResolvedValue({
      userId: 7,
      credentialVersion: 3,
      revokedAt: null,
    });
    dbMocks.recordAlexaVerification.mockResolvedValue(undefined);

    const response = await createAlexaSkill().invoke(
      await linkedIntent("TrainStatusIntent", { trainNumber: unsafeText })
    );

    expect(outputSsml(response)).toContain("<speak>");
    expect(outputSsml(response)).toContain(escapedUnsafeText);
    expect(outputSsml(response)).not.toContain(`<break time="1s"/>`);
  });
});

describe("modello di interazione versionato", () => {
  const model = JSON.parse(
    readFileSync(
      path.resolve(
        import.meta.dirname,
        "..",
        "..",
        "skill-package",
        "interactionModels",
        "custom",
        "it-IT.json"
      ),
      "utf8"
    )
  ) as {
    interactionModel: {
      languageModel: {
        invocationName: string;
        intents: Array<{ name: string; samples: string[] }>;
      };
    };
  };

  it("dichiara ogni intent gestito dal codice e nessun altro", () => {
    const declared = model.interactionModel.languageModel.intents
      .map(item => item.name)
      .sort();
    expect(declared).toEqual([...HANDLED_INTENTS].sort());
  });

  it("ha un nome di invocazione e utterance italiane per gli intent personalizzati", () => {
    expect(model.interactionModel.languageModel.invocationName).toBe("binario");
    for (const item of model.interactionModel.languageModel.intents) {
      if (!item.name.startsWith("AMAZON."))
        expect(item.samples.length).toBeGreaterThan(0);
    }
  });
});
