import express, { type Express, type Request, type Response } from "express";
import { ExpressAdapter } from "ask-sdk-express-adapter";
import { getAccountLinkingAccessToken, getIntentName, getRequestType, SkillBuilders, type HandlerInput, type RequestHandler } from "ask-sdk-core";
import { createAuthorizationCode, createRefreshToken, getActiveRefreshToken, listPersonalRoutes, recordAlexaVerification, revokeRefreshToken, consumeAuthorizationCode } from "../db";
import { ENV } from "../_core/env";
import { createOpaqueSecret, hashVoiceSecret, issueVoiceAccessToken, secureStringEquals, verifyVoiceAccessToken } from "./crypto";
import { isAlexaConfigured } from "./config";
import { needsAccountLink } from "./guards";
import { parseOAuthAuthorizationParameters } from "./oauthContract";
import { publicRouteQueryFallback, publicTrainQueryFallback } from "./publicRailQuery";
import { liveDataUnavailableSpeech } from "./speech";

const ACCOUNT_LINK_REQUIRED = "Per ascoltare le tue tratte personali, collega facoltativamente il tuo account Binario nell'app Alexa.";
const UNSUPPORTED_SPEECH = "Non ho capito la richiesta. Puoi chiedere i prossimi treni, lo stato di un treno oppure le tue tratte.";

function getAlexaUserIdentifier(input: HandlerInput) {
  return input.requestEnvelope.session?.user?.userId ?? input.requestEnvelope.context.System.user.userId;
}

async function getLinkedUser(input: HandlerInput) {
  const token = getAccountLinkingAccessToken(input.requestEnvelope);
  if (needsAccountLink(token)) return null;
  const userId = await verifyVoiceAccessToken(token);
  if (!userId) return null;
  const alexaUserId = getAlexaUserIdentifier(input);
  if (alexaUserId) await recordAlexaVerification(userId, hashVoiceSecret(alexaUserId));
  return userId;
}

function linkRequiredResponse(input: HandlerInput) {
  return input.responseBuilder.speak(ACCOUNT_LINK_REQUIRED).withLinkAccountCard().withShouldEndSession(true).getResponse();
}

function intentSlot(input: HandlerInput, ...names: string[]) {
  const request = input.requestEnvelope.request;
  if (request.type !== "IntentRequest") return undefined;
  for (const name of names) {
    const value = request.intent.slots?.[name]?.value?.trim();
    if (value) return value;
  }
  return undefined;
}

const launchHandler: RequestHandler = {
  canHandle: input => getRequestType(input.requestEnvelope) === "LaunchRequest",
  handle: input => input.responseBuilder
    .speak("Benvenuta in Binario. Puoi chiedere i prossimi treni, lo stato di un treno o le tue tratte.")
    .reprompt(UNSUPPORTED_SPEECH)
    .withShouldEndSession(false)
    .getResponse(),
};

const nextTrainsHandler: RequestHandler = {
  canHandle: input => getRequestType(input.requestEnvelope) === "IntentRequest" && getIntentName(input.requestEnvelope) === "NextTrainsIntent",
  handle: async input => {
    const userId = await getLinkedUser(input);
    const requestedRouteName = intentSlot(input, "routeName", "route", "tratta");
    const requestedName = requestedRouteName?.toLocaleLowerCase("it-IT");
    if (!userId) {
      return input.responseBuilder.speak(publicRouteQueryFallback(requestedRouteName)).withShouldEndSession(true).getResponse();
    }
    const routes = await listPersonalRoutes(userId);
    const route = requestedName ? routes.find(item => item.name.toLocaleLowerCase("it-IT") === requestedName) : routes.find(item => item.isPinned) ?? routes[0];
    if (!route) return input.responseBuilder.speak(requestedRouteName ? publicRouteQueryFallback(requestedRouteName) : "Non hai ancora salvato alcuna tratta in Binario.").withShouldEndSession(true).getResponse();
    return input.responseBuilder.speak(liveDataUnavailableSpeech(`Per la tratta ${route.name}`)).withShouldEndSession(true).getResponse();
  },
};

const trainStatusHandler: RequestHandler = {
  canHandle: input => getRequestType(input.requestEnvelope) === "IntentRequest" && getIntentName(input.requestEnvelope) === "TrainStatusIntent",
  handle: async input => {
    const trainNumber = intentSlot(input, "trainNumber", "train", "numeroTreno");
    return input.responseBuilder.speak(publicTrainQueryFallback(trainNumber)).withShouldEndSession(true).getResponse();
  },
};

const myRoutesHandler: RequestHandler = {
  canHandle: input => getRequestType(input.requestEnvelope) === "IntentRequest" && getIntentName(input.requestEnvelope) === "MyRoutesIntent",
  handle: async input => {
    const userId = await getLinkedUser(input);
    if (!userId) return linkRequiredResponse(input);
    const routes = await listPersonalRoutes(userId);
    if (!routes.length) return input.responseBuilder.speak("Non hai ancora salvato alcuna tratta in Binario.").withShouldEndSession(true).getResponse();
    const names = routes.slice(0, 5).map(route => route.name).join(", ");
    const suffix = routes.length > 5 ? ". Ci sono altre tratte nella dashboard Binario." : ".";
    return input.responseBuilder.speak(`Le tue tratte salvate sono: ${names}${suffix}`).withShouldEndSession(true).getResponse();
  },
};

const fallbackHandler: RequestHandler = {
  canHandle: input => getRequestType(input.requestEnvelope) === "IntentRequest",
  handle: input => input.responseBuilder.speak(UNSUPPORTED_SPEECH).reprompt(UNSUPPORTED_SPEECH).withShouldEndSession(false).getResponse(),
};

const errorHandler = {
  canHandle: () => true,
  handle: (input: HandlerInput) => input.responseBuilder.speak("Si è verificato un problema. Riprova più tardi.").withShouldEndSession(true).getResponse(),
};

function createAlexaSkill() {
  return SkillBuilders.custom()
    .withSkillId(ENV.alexaSkillId)
    .addRequestHandlers(launchHandler, nextTrainsHandler, trainStatusHandler, myRoutesHandler, fallbackHandler)
    .addErrorHandlers(errorHandler)
    .create();
}

function oauthError(res: Response, status: number, error: string) {
  return res.status(status).json({ error });
}

function parseAuthorizationRequest(req: Request) {
  const parsed = parseOAuthAuthorizationParameters(req.query, ENV.alexaOAuthClientId);
  return parsed ? { clientId: parsed.clientId, redirectUri: parsed.redirectUri, state: parsed.state, scope: parsed.scope } : null;
}

async function requireBinarioUser(req: Request) {
  const { sdk } = await import("../_core/sdk");
  try {
    return await sdk.authenticateRequest(req);
  } catch {
    return null;
  }
}

async function issueTokenPair(userId: number, clientId: string) {
  const accessToken = await issueVoiceAccessToken(userId);
  const refreshToken = createOpaqueSecret();
  await createRefreshToken({
    userId,
    clientId,
    tokenHash: hashVoiceSecret(refreshToken),
    expiresAt: new Date(Date.now() + 30 * 24 * 60 * 60 * 1000),
  });
  return { access_token: accessToken, token_type: "Bearer", expires_in: 3600, refresh_token: refreshToken };
}

export function registerAlexaRoutes(app: Express) {
  app.get("/api/alexa/oauth/authorize", async (req, res) => {
    if (!isAlexaConfigured()) return oauthError(res, 503, "alexa_not_configured");
    const request = parseAuthorizationRequest(req);
    if (!request) return oauthError(res, 400, "invalid_request");
    const user = await requireBinarioUser(req);
    if (!user) return res.status(401).type("text/plain").send("Accedi prima alla dashboard Binario e ripeti il collegamento Alexa.");

    const rawCode = createOpaqueSecret();
    await createAuthorizationCode({
      userId: user.id,
      codeHash: hashVoiceSecret(rawCode),
      clientId: request.clientId,
      redirectUri: request.redirectUri,
      scope: request.scope,
      expiresAt: new Date(Date.now() + 5 * 60 * 1000),
    });
    const redirect = new URL(request.redirectUri);
    redirect.searchParams.set("code", rawCode);
    redirect.searchParams.set("state", request.state);
    return res.redirect(302, redirect.toString());
  });

  app.post("/api/alexa/oauth/token", express.urlencoded({ extended: false }), express.json({ strict: true }), async (req, res) => {
    if (!isAlexaConfigured()) return oauthError(res, 503, "alexa_not_configured");
    const clientId = typeof req.body?.client_id === "string" ? req.body.client_id : "";
    const clientSecret = typeof req.body?.client_secret === "string" ? req.body.client_secret : "";
    if (!secureStringEquals(clientId, ENV.alexaOAuthClientId) || !secureStringEquals(clientSecret, ENV.alexaOAuthClientSecret)) return oauthError(res, 401, "invalid_client");

    if (req.body?.grant_type === "authorization_code") {
      const code = typeof req.body.code === "string" ? req.body.code : "";
      const redirectUri = typeof req.body.redirect_uri === "string" ? req.body.redirect_uri : "";
      const record = code && redirectUri ? await consumeAuthorizationCode(hashVoiceSecret(code), clientId, redirectUri) : null;
      if (!record) return oauthError(res, 400, "invalid_grant");
      return res.json(await issueTokenPair(record.userId, clientId));
    }

    if (req.body?.grant_type === "refresh_token") {
      const refreshToken = typeof req.body.refresh_token === "string" ? req.body.refresh_token : "";
      const tokenHash = refreshToken ? hashVoiceSecret(refreshToken) : "";
      const record = tokenHash ? await getActiveRefreshToken(tokenHash, clientId) : null;
      if (!record) return oauthError(res, 400, "invalid_grant");
      await revokeRefreshToken(tokenHash);
      return res.json(await issueTokenPair(record.userId, clientId));
    }

    return oauthError(res, 400, "unsupported_grant_type");
  });

  // The Alexa SDK installs its own raw-text parser and verifies Signature-256,
  // signing certificate, timestamp and skill id before a handler can run.
  app.post(
    "/api/alexa/webhook",
    (req, res, next) => {
      if (!isAlexaConfigured()) return oauthError(res, 503, "alexa_not_configured");
      return next();
    },
    ...new ExpressAdapter(createAlexaSkill(), true, true).getRequestHandlers()
  );
}
