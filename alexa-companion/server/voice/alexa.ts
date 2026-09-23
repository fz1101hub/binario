import express, { type Express, type Request, type Response } from "express";
import { parse as parseCookieHeader } from "cookie";
import { ExpressAdapter } from "ask-sdk-express-adapter";
import {
  getAccountLinkingAccessToken,
  getIntentName,
  getRequestType,
  SkillBuilders,
  type HandlerInput,
  type RequestHandler,
} from "ask-sdk-core";
import {
  createAuthorizationCode,
  exchangeAuthorizationCode,
  getAlexaLinkStatus,
  listPersonalRoutesForAlexa,
  recordAlexaVerification,
  rotateRefreshToken,
} from "../db";
import { ENV } from "../_core/env";
import { isSecureRequest } from "../_core/cookies";
import {
  createOpaqueSecret,
  hashVoiceSecret,
  issueVoiceAccessToken,
  secureStringEquals,
  verifyVoiceAccessToken,
} from "./crypto";
import {
  AUTHORIZATION_REQUEST_COOKIE,
  AUTHORIZATION_REQUEST_COOKIE_PATH,
  AUTHORIZATION_REQUEST_TTL_MS,
  CONSENT_COOKIE,
  CONSENT_COOKIE_PATH,
  CONSENT_TTL_MS,
  consentToken,
  createConsentNonce,
  renderConsentPage,
  signAuthorizationRequest,
  verifyConsentToken,
} from "./consent";
import { isAlexaConfigured } from "./config";
import { needsAccountLink } from "./guards";
import { parseOAuthAuthorizationParameters } from "./oauthContract";
import { liveDataUnavailableSpeech } from "./speech";

const ACCOUNT_LINK_REQUIRED =
  "Per usare le informazioni personali, collega prima il tuo account Binario nell'app Alexa.";
const UNSUPPORTED_SPEECH =
  "Non ho capito la richiesta. Puoi chiedere i prossimi treni, lo stato di un treno oppure le tue tratte.";
const HELP_SPEECH =
  "Con Binario puoi chiedere i prossimi treni di una tratta salvata, lo stato di un treno oppure l'elenco delle tue tratte. Cosa vuoi sapere?";
const GOODBYE_SPEECH = "A presto.";

/** Intent names the skill handles; the interaction model in skill-package/ must declare all of them. */
export const HANDLED_INTENTS = [
  "NextTrainsIntent",
  "TrainStatusIntent",
  "MyRoutesIntent",
  "AMAZON.HelpIntent",
  "AMAZON.StopIntent",
  "AMAZON.CancelIntent",
  "AMAZON.NavigateHomeIntent",
  "AMAZON.FallbackIntent",
] as const;

function isIntent(input: HandlerInput, ...names: string[]) {
  return (
    getRequestType(input.requestEnvelope) === "IntentRequest" &&
    names.includes(getIntentName(input.requestEnvelope))
  );
}

function getAlexaUserIdentifier(input: HandlerInput) {
  return (
    input.requestEnvelope.session?.user?.userId ??
    input.requestEnvelope.context.System.user.userId
  );
}

/** Resolves the Binario user behind an Alexa request; null when the link is missing or revoked. */
export async function resolveLinkedUser(
  accessToken: string | undefined,
  alexaUserId: string | undefined
) {
  if (needsAccountLink(accessToken)) return null;
  const principal = await verifyVoiceAccessToken(accessToken as string);
  if (!principal) return null;
  // An access token outlives an unlink by up to an hour: the stored link is
  // the source of truth, and a routine verification must never lift a revocation.
  const link = await getAlexaLinkStatus(principal.userId);
  if (
    !link ||
    link.revokedAt ||
    link.credentialVersion !== principal.credentialVersion
  )
    return null;
  if (alexaUserId)
    await recordAlexaVerification(
      principal.userId,
      hashVoiceSecret(alexaUserId)
    );
  return principal;
}

async function getLinkedUser(input: HandlerInput) {
  return resolveLinkedUser(
    getAccountLinkingAccessToken(input.requestEnvelope),
    getAlexaUserIdentifier(input)
  );
}

function linkRequiredResponse(input: HandlerInput) {
  return input.responseBuilder
    .speak(ACCOUNT_LINK_REQUIRED)
    .withLinkAccountCard()
    .withShouldEndSession(true)
    .getResponse();
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
  handle: input =>
    input.responseBuilder
      .speak(
        "Benvenuta in Binario. Puoi chiedere i prossimi treni, lo stato di un treno o le tue tratte."
      )
      .reprompt(UNSUPPORTED_SPEECH)
      .withShouldEndSession(false)
      .getResponse(),
};

const nextTrainsHandler: RequestHandler = {
  canHandle: input =>
    getRequestType(input.requestEnvelope) === "IntentRequest" &&
    getIntentName(input.requestEnvelope) === "NextTrainsIntent",
  handle: async input => {
    const principal = await getLinkedUser(input);
    if (!principal) return linkRequiredResponse(input);
    const routes = await listPersonalRoutesForAlexa(
      principal.userId,
      principal.credentialVersion
    );
    const requestedName = intentSlot(
      input,
      "routeName",
      "route",
      "tratta"
    )?.toLocaleLowerCase("it-IT");
    const route = requestedName
      ? routes.find(
          item => item.name.toLocaleLowerCase("it-IT") === requestedName
        )
      : (routes.find(item => item.isPinned) ?? routes[0]);
    if (!route)
      return input.responseBuilder
        .speak("Non hai ancora salvato alcuna tratta in Binario.")
        .withShouldEndSession(true)
        .getResponse();
    return input.responseBuilder
      .speak(liveDataUnavailableSpeech(`Per la tratta ${route.name}`))
      .withShouldEndSession(true)
      .getResponse();
  },
};

const trainStatusHandler: RequestHandler = {
  canHandle: input =>
    getRequestType(input.requestEnvelope) === "IntentRequest" &&
    getIntentName(input.requestEnvelope) === "TrainStatusIntent",
  handle: async input => {
    const principal = await getLinkedUser(input);
    if (!principal) return linkRequiredResponse(input);
    const trainNumber = intentSlot(
      input,
      "trainNumber",
      "train",
      "numeroTreno"
    );
    const subject = trainNumber ? `Per il treno ${trainNumber}` : undefined;
    return input.responseBuilder
      .speak(liveDataUnavailableSpeech(subject))
      .withShouldEndSession(true)
      .getResponse();
  },
};

const myRoutesHandler: RequestHandler = {
  canHandle: input =>
    getRequestType(input.requestEnvelope) === "IntentRequest" &&
    getIntentName(input.requestEnvelope) === "MyRoutesIntent",
  handle: async input => {
    const principal = await getLinkedUser(input);
    if (!principal) return linkRequiredResponse(input);
    const routes = await listPersonalRoutesForAlexa(
      principal.userId,
      principal.credentialVersion
    );
    if (!routes.length)
      return input.responseBuilder
        .speak("Non hai ancora salvato alcuna tratta in Binario.")
        .withShouldEndSession(true)
        .getResponse();
    const names = routes
      .slice(0, 5)
      .map(route => route.name)
      .join(", ");
    const suffix =
      routes.length > 5
        ? ". Ci sono altre tratte nella dashboard Binario."
        : ".";
    return input.responseBuilder
      .speak(`Le tue tratte salvate sono: ${names}${suffix}`)
      .withShouldEndSession(true)
      .getResponse();
  },
};

const helpHandler: RequestHandler = {
  canHandle: input => isIntent(input, "AMAZON.HelpIntent"),
  handle: input =>
    input.responseBuilder
      .speak(HELP_SPEECH)
      .reprompt(HELP_SPEECH)
      .withShouldEndSession(false)
      .getResponse(),
};

// Stop, Cancel and NavigateHome must close the session: certification rejects
// a skill that keeps listening after the user asked it to stop.
const stopHandler: RequestHandler = {
  canHandle: input =>
    isIntent(
      input,
      "AMAZON.StopIntent",
      "AMAZON.CancelIntent",
      "AMAZON.NavigateHomeIntent"
    ),
  handle: input =>
    input.responseBuilder
      .speak(GOODBYE_SPEECH)
      .withShouldEndSession(true)
      .getResponse(),
};

const sessionEndedHandler: RequestHandler = {
  canHandle: input =>
    getRequestType(input.requestEnvelope) === "SessionEndedRequest",
  // No speech may be returned for a SessionEndedRequest.
  handle: input => input.responseBuilder.getResponse(),
};

const fallbackHandler: RequestHandler = {
  canHandle: input => getRequestType(input.requestEnvelope) === "IntentRequest",
  handle: input =>
    input.responseBuilder
      .speak(UNSUPPORTED_SPEECH)
      .reprompt(UNSUPPORTED_SPEECH)
      .withShouldEndSession(false)
      .getResponse(),
};

const errorHandler = {
  canHandle: () => true,
  handle: (input: HandlerInput) =>
    input.responseBuilder
      .speak("Si è verificato un problema. Riprova più tardi.")
      .withShouldEndSession(true)
      .getResponse(),
};

export function createAlexaSkill() {
  return SkillBuilders.custom()
    .withSkillId(ENV.alexaSkillId)
    .addRequestHandlers(
      launchHandler,
      helpHandler,
      stopHandler,
      sessionEndedHandler,
      nextTrainsHandler,
      trainStatusHandler,
      myRoutesHandler,
      fallbackHandler
    )
    .addErrorHandlers(errorHandler)
    .create();
}

function oauthError(res: Response, status: number, error: string) {
  return res.status(status).json({ error });
}

function parseAuthorizationRequest(source: Record<string, unknown>) {
  const parsed = parseOAuthAuthorizationParameters(
    source,
    ENV.alexaOAuthClientId,
    ENV.alexaOAuthRedirectUris
  );
  return parsed
    ? {
        clientId: parsed.clientId,
        redirectUri: parsed.redirectUri,
        state: parsed.state,
        scope: parsed.scope,
      }
    : null;
}

function redirectWithParameters(
  res: Response,
  redirectUri: string,
  parameters: Record<string, string>
) {
  const url = new URL(redirectUri);
  for (const [name, value] of Object.entries(parameters))
    url.searchParams.set(name, value);
  return res.redirect(302, url.toString());
}

function consentCookieOptions(req: Request) {
  return {
    httpOnly: true,
    path: CONSENT_COOKIE_PATH,
    sameSite: "lax" as const,
    secure: isSecureRequest(req),
  };
}

function authorizationRequestCookieOptions() {
  // The __Host- name requires Secure, Path=/ and no Domain. This cookie is
  // only useful across the HTTPS dashboard OAuth callback.
  return {
    httpOnly: true,
    path: AUTHORIZATION_REQUEST_COOKIE_PATH,
    sameSite: "lax" as const,
    secure: true,
  };
}

const LOGIN_REQUIRED =
  "Accedi prima alla dashboard Binario e ripeti il collegamento Alexa.";

async function requireBinarioUser(req: Request) {
  const { sdk } = await import("../_core/sdk");
  try {
    return await sdk.authenticateRequest(req);
  } catch {
    return null;
  }
}

async function tokenPairResponse(
  userId: number,
  credentialVersion: number,
  refreshToken: string
) {
  const accessToken = await issueVoiceAccessToken(userId, credentialVersion);
  return {
    access_token: accessToken,
    token_type: "Bearer",
    expires_in: 3600,
    refresh_token: refreshToken,
  };
}

export function registerAlexaRoutes(app: Express) {
  // GET only shows an authenticated consent page: the code is created by the
  // CSRF-protected POST below, so a cross-site navigation cannot link a
  // victim's Binario account to someone else's Alexa.
  app.get("/api/alexa/oauth/authorize", async (req, res) => {
    if (!isAlexaConfigured())
      return oauthError(res, 503, "alexa_not_configured");
    const request = parseAuthorizationRequest(
      req.query as Record<string, unknown>
    );
    if (!request) return oauthError(res, 400, "invalid_request");
    const user = await requireBinarioUser(req);
    if (!user) {
      // Amazon can reach this endpoint before an interactive dashboard login.
      // Persist only the already allowlisted request in a signed, HttpOnly,
      // short-lived cookie. The dashboard callback validates it again.
      res.cookie(
        AUTHORIZATION_REQUEST_COOKIE,
        signAuthorizationRequest(request),
        {
          ...authorizationRequestCookieOptions(),
          maxAge: AUTHORIZATION_REQUEST_TTL_MS,
        }
      );
      return res.redirect(302, "/");
    }

    const nonce = createConsentNonce();
    res.cookie(CONSENT_COOKIE, nonce, {
      ...consentCookieOptions(req),
      maxAge: CONSENT_TTL_MS,
    });
    res.setHeader("Cache-Control", "no-store");
    return res
      .status(200)
      .type("html")
      .send(
        renderConsentPage({
          userName: user.name ?? "",
          ...request,
          token: consentToken(nonce, user.id),
        })
      );
  });

  app.post(
    "/api/alexa/oauth/authorize",
    express.urlencoded({ extended: false }),
    async (req, res) => {
      if (!isAlexaConfigured())
        return oauthError(res, 503, "alexa_not_configured");
      const body = (req.body ?? {}) as Record<string, unknown>;
      const request = parseAuthorizationRequest(body);
      if (!request) return oauthError(res, 400, "invalid_request");
      const user = await requireBinarioUser(req);
      if (!user) return res.status(401).type("text/plain").send(LOGIN_REQUIRED);

      const nonce = parseCookieHeader(req.headers.cookie ?? "")[CONSENT_COOKIE];
      res.clearCookie(CONSENT_COOKIE, consentCookieOptions(req));
      if (!verifyConsentToken(nonce, user.id, body.csrf_token))
        return oauthError(res, 403, "invalid_csrf_token");
      if (body.decision !== "allow")
        return redirectWithParameters(res, request.redirectUri, {
          error: "access_denied",
          state: request.state,
        });

      const rawCode = createOpaqueSecret();
      await createAuthorizationCode({
        userId: user.id,
        codeHash: hashVoiceSecret(rawCode),
        clientId: request.clientId,
        redirectUri: request.redirectUri,
        scope: request.scope,
        expiresAt: new Date(Date.now() + 5 * 60 * 1000),
      });
      return redirectWithParameters(res, request.redirectUri, {
        code: rawCode,
        state: request.state,
      });
    }
  );

  app.post(
    "/api/alexa/oauth/token",
    express.urlencoded({ extended: false }),
    express.json({ strict: true }),
    async (req, res) => {
      if (!isAlexaConfigured())
        return oauthError(res, 503, "alexa_not_configured");
      const clientId =
        typeof req.body?.client_id === "string" ? req.body.client_id : "";
      const clientSecret =
        typeof req.body?.client_secret === "string"
          ? req.body.client_secret
          : "";
      if (
        !secureStringEquals(clientId, ENV.alexaOAuthClientId) ||
        !secureStringEquals(clientSecret, ENV.alexaOAuthClientSecret)
      )
        return oauthError(res, 401, "invalid_client");

      if (req.body?.grant_type === "authorization_code") {
        const code = typeof req.body.code === "string" ? req.body.code : "";
        const redirectUri =
          typeof req.body.redirect_uri === "string"
            ? req.body.redirect_uri
            : "";
        const refreshToken = createOpaqueSecret();
        const record =
          code && redirectUri
            ? await exchangeAuthorizationCode({
                codeHash: hashVoiceSecret(code),
                clientId,
                redirectUri,
                refreshTokenHash: hashVoiceSecret(refreshToken),
                refreshTokenExpiresAt: new Date(
                  Date.now() + 30 * 24 * 60 * 60 * 1000
                ),
              })
            : null;
        if (!record) return oauthError(res, 400, "invalid_grant");
        return res.json(
          await tokenPairResponse(
            record.userId,
            record.credentialVersion,
            refreshToken
          )
        );
      }

      if (req.body?.grant_type === "refresh_token") {
        const refreshToken =
          typeof req.body.refresh_token === "string"
            ? req.body.refresh_token
            : "";
        const tokenHash = refreshToken ? hashVoiceSecret(refreshToken) : "";
        const nextRefreshToken = createOpaqueSecret();
        const record = tokenHash
          ? await rotateRefreshToken({
              currentTokenHash: tokenHash,
              clientId,
              nextTokenHash: hashVoiceSecret(nextRefreshToken),
              nextTokenExpiresAt: new Date(
                Date.now() + 30 * 24 * 60 * 60 * 1000
              ),
            })
          : null;
        if (!record) return oauthError(res, 400, "invalid_grant");
        return res.json(
          await tokenPairResponse(
            record.userId,
            record.credentialVersion,
            nextRefreshToken
          )
        );
      }

      return oauthError(res, 400, "unsupported_grant_type");
    }
  );

  // The Alexa SDK installs its own raw-text parser and verifies Signature-256,
  // signing certificate, timestamp and skill id before a handler can run.
  app.post(
    "/api/alexa/webhook",
    (req, res, next) => {
      if (!isAlexaConfigured())
        return oauthError(res, 503, "alexa_not_configured");
      return next();
    },
    ...new ExpressAdapter(createAlexaSkill(), true, true).getRequestHandlers()
  );
}
