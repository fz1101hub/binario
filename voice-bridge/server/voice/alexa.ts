import express, { type Express, type Request, type Response } from "express";
import { parse as parseCookieHeader } from "cookie";
import { ExpressAdapter } from "ask-sdk-express-adapter";
import { getAccountLinkingAccessToken, getIntentName, getRequestType, SkillBuilders, type HandlerInput, type RequestHandler } from "ask-sdk-core";
import { createAuthorizationCode, createRefreshToken, getActiveRefreshToken, getAlexaLinkStatus, listPersonalRoutes, recordAlexaVerification, restoreAlexaLink, revokeRefreshToken, consumeAuthorizationCode } from "../db";
import { ENV } from "../_core/env";
import { isSecureRequest } from "../_core/cookies";
import { createOpaqueSecret, hashVoiceSecret, issueVoiceAccessToken, secureStringEquals, verifyVoiceAccessToken } from "./crypto";
import { CONSENT_COOKIE, CONSENT_COOKIE_PATH, CONSENT_TTL_MS, consentToken, createConsentNonce, renderConsentPage, verifyConsentToken } from "./consent";
import { isAlexaConfigured } from "./config";
import { needsAccountLink } from "./guards";
import { parseOAuthAuthorizationParameters } from "./oauthContract";
import { liveDataUnavailableSpeech } from "./speech";

const ACCOUNT_LINK_REQUIRED = "Per usare le informazioni personali, collega prima il tuo account Binario nell'app Alexa.";
const UNSUPPORTED_SPEECH = "Non ho capito la richiesta. Puoi chiedere i prossimi treni, lo stato di un treno oppure le tue tratte.";

function getAlexaUserIdentifier(input: HandlerInput) {
  return input.requestEnvelope.session?.user?.userId ?? input.requestEnvelope.context.System.user.userId;
}

/** Resolves the Binario user behind an Alexa request; null when the link is missing or revoked. */
export async function resolveLinkedUser(accessToken: string | undefined, alexaUserId: string | undefined) {
  if (needsAccountLink(accessToken)) return null;
  const userId = await verifyVoiceAccessToken(accessToken as string);
  if (!userId) return null;
  // An access token outlives an unlink by up to an hour: the stored link is
  // the source of truth, and a routine verification must never lift a revocation.
  const link = await getAlexaLinkStatus(userId);
  if (link?.revokedAt) return null;
  if (alexaUserId) await recordAlexaVerification(userId, hashVoiceSecret(alexaUserId));
  return userId;
}

async function getLinkedUser(input: HandlerInput) {
  return resolveLinkedUser(getAccountLinkingAccessToken(input.requestEnvelope), getAlexaUserIdentifier(input));
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
    if (!userId) return linkRequiredResponse(input);
    const routes = await listPersonalRoutes(userId);
    const requestedName = intentSlot(input, "routeName", "route", "tratta")?.toLocaleLowerCase("it-IT");
    const route = requestedName ? routes.find(item => item.name.toLocaleLowerCase("it-IT") === requestedName) : routes.find(item => item.isPinned) ?? routes[0];
    if (!route) return input.responseBuilder.speak("Non hai ancora salvato alcuna tratta in Binario.").withShouldEndSession(true).getResponse();
    return input.responseBuilder.speak(liveDataUnavailableSpeech(`Per la tratta ${route.name}`)).withShouldEndSession(true).getResponse();
  },
};

const trainStatusHandler: RequestHandler = {
  canHandle: input => getRequestType(input.requestEnvelope) === "IntentRequest" && getIntentName(input.requestEnvelope) === "TrainStatusIntent",
  handle: async input => {
    const userId = await getLinkedUser(input);
    if (!userId) return linkRequiredResponse(input);
    const trainNumber = intentSlot(input, "trainNumber", "train", "numeroTreno");
    const subject = trainNumber ? `Per il treno ${trainNumber}` : undefined;
    return input.responseBuilder.speak(liveDataUnavailableSpeech(subject)).withShouldEndSession(true).getResponse();
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

function parseAuthorizationRequest(source: Record<string, unknown>) {
  const parsed = parseOAuthAuthorizationParameters(source, ENV.alexaOAuthClientId, ENV.alexaOAuthRedirectUris);
  return parsed ? { clientId: parsed.clientId, redirectUri: parsed.redirectUri, state: parsed.state, scope: parsed.scope } : null;
}

function redirectWithParameters(res: Response, redirectUri: string, parameters: Record<string, string>) {
  const url = new URL(redirectUri);
  for (const [name, value] of Object.entries(parameters)) url.searchParams.set(name, value);
  return res.redirect(302, url.toString());
}

function consentCookieOptions(req: Request) {
  return { httpOnly: true, path: CONSENT_COOKIE_PATH, sameSite: "lax" as const, secure: isSecureRequest(req) };
}

const LOGIN_REQUIRED = "Accedi prima alla dashboard Binario e ripeti il collegamento Alexa.";

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
  // GET only shows an authenticated consent page: the code is created by the
  // CSRF-protected POST below, so a cross-site navigation cannot link a
  // victim's Binario account to someone else's Alexa.
  app.get("/api/alexa/oauth/authorize", async (req, res) => {
    if (!isAlexaConfigured()) return oauthError(res, 503, "alexa_not_configured");
    const request = parseAuthorizationRequest(req.query as Record<string, unknown>);
    if (!request) return oauthError(res, 400, "invalid_request");
    const user = await requireBinarioUser(req);
    if (!user) return res.status(401).type("text/plain").send(LOGIN_REQUIRED);

    const nonce = createConsentNonce();
    res.cookie(CONSENT_COOKIE, nonce, { ...consentCookieOptions(req), maxAge: CONSENT_TTL_MS });
    res.setHeader("Cache-Control", "no-store");
    return res.status(200).type("html").send(renderConsentPage({ userName: user.name ?? "", ...request, token: consentToken(nonce, user.id) }));
  });

  app.post("/api/alexa/oauth/authorize", express.urlencoded({ extended: false }), async (req, res) => {
    if (!isAlexaConfigured()) return oauthError(res, 503, "alexa_not_configured");
    const body = (req.body ?? {}) as Record<string, unknown>;
    const request = parseAuthorizationRequest(body);
    if (!request) return oauthError(res, 400, "invalid_request");
    const user = await requireBinarioUser(req);
    if (!user) return res.status(401).type("text/plain").send(LOGIN_REQUIRED);

    const nonce = parseCookieHeader(req.headers.cookie ?? "")[CONSENT_COOKIE];
    res.clearCookie(CONSENT_COOKIE, consentCookieOptions(req));
    if (!verifyConsentToken(nonce, user.id, body.csrf_token)) return oauthError(res, 403, "invalid_csrf_token");
    if (body.decision !== "allow") return redirectWithParameters(res, request.redirectUri, { error: "access_denied", state: request.state });

    const rawCode = createOpaqueSecret();
    await createAuthorizationCode({
      userId: user.id,
      codeHash: hashVoiceSecret(rawCode),
      clientId: request.clientId,
      redirectUri: request.redirectUri,
      scope: request.scope,
      expiresAt: new Date(Date.now() + 5 * 60 * 1000),
    });
    return redirectWithParameters(res, request.redirectUri, { code: rawCode, state: request.state });
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
      await restoreAlexaLink(record.userId);
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
