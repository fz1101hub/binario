import express, { type Express, type NextFunction, type Request, type Response } from "express";
import { parse as parseCookieHeader } from "cookie";
import { createAlexaWebhookHandler } from "./alexaWebhook";
import {
  getAccountLinkingAccessToken,
  getIntentName,
  getRequestType,
  SkillBuilders,
  type HandlerInput,
  type RequestHandler,
} from "ask-sdk-core";
import {
  formatMessage,
  formatPlural,
  messagesForLocale,
  resolveLocale,
  type SupportedLocale,
} from "@shared/i18n";
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
  renderConsentErrorPage,
  renderConsentPage,
  signAuthorizationRequest,
  verifyConsentToken,
} from "./consent";
import { isAlexaConfigured } from "./config";
import { needsAccountLink } from "./guards";
import { parseOAuthAuthorizationParameters } from "./oauthContract";
import { escapeSsmlText, liveDataUnavailableSpeech, liveDeparturesSpeech, plainSpeechText } from "./speech";
import { screenResponseInterceptor } from "./presentation";
import { getLiveDepartures } from "../rail/rfi";

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

/** Alexa supplies request.locale per invocation; unsupported locales safely fall back to Italian. */
export function getAlexaLocale(input: HandlerInput): SupportedLocale {
  return resolveLocale(input.requestEnvelope.request.locale);
}

function voiceMessages(input: HandlerInput) {
  return messagesForLocale(getAlexaLocale(input)).voice;
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
  const messages = voiceMessages(input);
  return input.responseBuilder
    .speak(messages.accountLinkRequired)
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
  handle: input => {
    const messages = voiceMessages(input);
    return input.responseBuilder
      .speak(messages.launch)
      .reprompt(messages.unsupported)
      .withSimpleCard(messages.cardTitle, messages.launch)
      .withShouldEndSession(false)
      .getResponse();
  },
};

const nextTrainsHandler: RequestHandler = {
  canHandle: input =>
    getRequestType(input.requestEnvelope) === "IntentRequest" &&
    getIntentName(input.requestEnvelope) === "NextTrainsIntent",
  handle: async input => {
    const locale = getAlexaLocale(input);
    const messages = messagesForLocale(locale).voice;
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
    )?.toLocaleLowerCase(locale);
    const namedRoutes = routes.filter(item => item.name.trim().length > 0);
    const route = requestedName
      ? namedRoutes.find(
          item => item.name.toLocaleLowerCase(locale) === requestedName
        )
      : (namedRoutes.find(item => item.isPinned) ?? namedRoutes[0]);
    if (!route)
      return input.responseBuilder
        .speak(messages.noRoutes)
        .withSimpleCard(messages.cardTitle, messages.noRoutes)
        .withShouldEndSession(true)
        .getResponse();
    // SSML needs escaped text; the companion-app card is plain text and must
    // show the route name as typed, not XML entities.
    const live = await getLiveDepartures(route.origin, route.destination);
    const speech = live ? liveDeparturesSpeech(locale, live) : liveDataUnavailableSpeech(
      locale,
      formatMessage(messages.routeSubject, { routeName: escapeSsmlText(route.name) })
    );
    const card = live ? liveDeparturesSpeech(locale, live, true) : liveDataUnavailableSpeech(
      locale,
      formatMessage(messages.routeSubject, { routeName: plainSpeechText(route.name) })
    );
    return input.responseBuilder
      .speak(speech)
      .withSimpleCard(messages.cardTitle, card)
      .withShouldEndSession(true)
      .getResponse();
  },
};

const trainStatusHandler: RequestHandler = {
  canHandle: input =>
    getRequestType(input.requestEnvelope) === "IntentRequest" &&
    getIntentName(input.requestEnvelope) === "TrainStatusIntent",
  handle: async input => {
    const locale = getAlexaLocale(input);
    const messages = messagesForLocale(locale).voice;
    const principal = await getLinkedUser(input);
    if (!principal) return linkRequiredResponse(input);
    const trainNumber = intentSlot(
      input,
      "trainNumber",
      "train",
      "numeroTreno"
    );
    const subject = trainNumber
      ? formatMessage(messages.trainSubject, { trainNumber: escapeSsmlText(trainNumber) })
      : undefined;
    const cardSubject = trainNumber
      ? formatMessage(messages.trainSubject, { trainNumber: plainSpeechText(trainNumber) })
      : undefined;
    // Scope a train-number request to the linked user's saved routes. Never
    // guess a station or fan out across the national network.
    const routes = await listPersonalRoutesForAlexa(principal.userId, principal.credentialVersion);
    const candidates = trainNumber && /^\d{1,8}$/.test(trainNumber)
      ? await Promise.all((routes ?? []).slice(0, 2).map(route => getLiveDepartures(route.origin, route.destination)))
      : [];
    const matched = candidates.flatMap(live => live?.departures.map(train => ({ train, observedAt: live.observedAt })) ?? [])
      .find(value => value.train.number === trainNumber);
    const live = matched ? { observedAt: matched.observedAt, departures: [matched.train] } : undefined;
    const speech = live ? liveDeparturesSpeech(locale, live) : liveDataUnavailableSpeech(locale, subject);
    return input.responseBuilder
      .speak(speech)
      .withSimpleCard(messages.cardTitle, live ? liveDeparturesSpeech(locale, live, true) : liveDataUnavailableSpeech(locale, cardSubject))
      .withShouldEndSession(true)
      .getResponse();
  },
};

const myRoutesHandler: RequestHandler = {
  canHandle: input =>
    getRequestType(input.requestEnvelope) === "IntentRequest" &&
    getIntentName(input.requestEnvelope) === "MyRoutesIntent",
  handle: async input => {
    const locale = getAlexaLocale(input);
    const messages = messagesForLocale(locale).voice;
    const principal = await getLinkedUser(input);
    if (!principal) return linkRequiredResponse(input);
    const routes = await listPersonalRoutesForAlexa(
      principal.userId,
      principal.credentialVersion
    );
    if (!routes.length)
      return input.responseBuilder
        .speak(messages.noRoutes)
        .withSimpleCard(messages.cardTitle, messages.noRoutes)
        .withShouldEndSession(true)
        .getResponse();
    const spoken = routes.slice(0, 5);
    const remainingRouteCount = Math.max(routes.length - 5, 0);
    const suffix =
      remainingRouteCount > 0
        ? formatPlural(locale, remainingRouteCount, messages.moreRoutes)
        : ".";
    const speech = formatMessage(messages.routesSaved, {
      routes: spoken.map(route => escapeSsmlText(route.name)).join(", "),
      suffix,
    });
    const card = formatMessage(messages.routesSaved, {
      routes: spoken.map(route => plainSpeechText(route.name)).join(", "),
      suffix,
    });
    return input.responseBuilder
      .speak(speech)
      .withSimpleCard(messages.cardTitle, card)
      .withShouldEndSession(true)
      .getResponse();
  },
};

const helpHandler: RequestHandler = {
  canHandle: input => isIntent(input, "AMAZON.HelpIntent"),
  handle: input => {
    const messages = voiceMessages(input);
    return input.responseBuilder
      .speak(messages.help)
      .reprompt(messages.help)
      .withSimpleCard(messages.cardTitle, messages.help)
      .withShouldEndSession(false)
      .getResponse();
  },
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
  handle: input => {
    const messages = voiceMessages(input);
    return input.responseBuilder
      .speak(messages.goodbye)
      .withSimpleCard(messages.cardTitle, messages.goodbye)
      .withShouldEndSession(true)
      .getResponse();
  },
};

const sessionEndedHandler: RequestHandler = {
  canHandle: input =>
    getRequestType(input.requestEnvelope) === "SessionEndedRequest",
  // No speech may be returned for a SessionEndedRequest.
  handle: input => input.responseBuilder.getResponse(),
};

const fallbackHandler: RequestHandler = {
  canHandle: input => getRequestType(input.requestEnvelope) === "IntentRequest",
  handle: input => {
    const messages = voiceMessages(input);
    return input.responseBuilder
      .speak(messages.unsupported)
      .reprompt(messages.unsupported)
      .withSimpleCard(messages.cardTitle, messages.unsupported)
      .withShouldEndSession(false)
      .getResponse();
  },
};

const errorHandler = {
  canHandle: () => true,
  handle: (input: HandlerInput) => {
    const messages = voiceMessages(input);
    return input.responseBuilder
      .speak(messages.error)
      .withSimpleCard(messages.cardTitle, messages.error)
      .withShouldEndSession(true)
      .getResponse();
  },
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
    .addResponseInterceptors(screenResponseInterceptor)
    .create();
}

function oauthError(res: Response, status: number, error: string) {
  return res.status(status).json({ error });
}

// Express 4 does not forward rejected async route handlers to its error layer.
function safeOAuthRoute(handler: (req: Request, res: Response) => Promise<unknown>) {
  return (req: Request, res: Response, next: NextFunction) => {
    void Promise.resolve().then(() => handler(req, res)).catch(next);
  };
}

function oauthHtmlError(
  req: Request,
  res: Response,
  status: number,
  kind: "notConfigured" | "invalidRequest" | "invalidCsrf"
) {
  const locale = resolveLocale(req.get("accept-language"));
  res.setHeader("Cache-Control", "no-store");
  return res.status(status).type("html").send(renderConsentErrorPage(locale, kind));
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
  app.get("/api/alexa/oauth/authorize", safeOAuthRoute(async (req, res) => {
    if (!isAlexaConfigured())
      return oauthHtmlError(req, res, 503, "notConfigured");
    const request = parseAuthorizationRequest(
      req.query as Record<string, unknown>
    );
    if (!request) return oauthHtmlError(req, res, 400, "invalidRequest");
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
          locale: resolveLocale(req.get("accept-language")),
          userName: user.name ?? "",
          ...request,
          token: consentToken(nonce, user.id),
        })
      );
  }));

  app.post(
    "/api/alexa/oauth/authorize",
    express.urlencoded({ extended: false }),
    safeOAuthRoute(async (req, res) => {
      if (!isAlexaConfigured())
        return oauthError(res, 503, "alexa_not_configured");
      const body = (req.body ?? {}) as Record<string, unknown>;
      const request = parseAuthorizationRequest(body);
      if (!request) return oauthError(res, 400, "invalid_request");
      const user = await requireBinarioUser(req);
      if (!user)
        return res.status(401).type("html").send(
          renderConsentErrorPage(
            resolveLocale(req.get("accept-language")),
            "loginRequired"
          )
        );

      const nonce = parseCookieHeader(req.headers.cookie ?? "")[CONSENT_COOKIE];
      res.clearCookie(CONSENT_COOKIE, consentCookieOptions(req));
      if (!verifyConsentToken(nonce, user.id, body.csrf_token))
        return oauthHtmlError(req, res, 403, "invalidCsrf");
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
    })
  );

  app.post(
    "/api/alexa/oauth/token",
    express.urlencoded({ extended: false }),
    express.json({ strict: true }),
    safeOAuthRoute(async (req, res) => {
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
    })
  );

  // Verify the raw signed body before the global JSON parser or skill handlers.
  app.post(
    "/api/alexa/webhook",
    (req, res, next) => {
      if (!isAlexaConfigured())
        return oauthError(res, 503, "alexa_not_configured");
      return next();
    },
    createAlexaWebhookHandler(createAlexaSkill(), ENV.alexaSkillId)
  );

  app.use("/api/alexa/oauth", (error: unknown, _req: Request, res: Response, next: NextFunction) => {
    if (res.headersSent) return next(error);
    // Keep database errors and tokens out of the response and logs.
    const status = typeof error === "object" && error !== null && "status" in error &&
      typeof error.status === "number" && error.status >= 400 && error.status < 500
      ? error.status : 500;
    res.setHeader("Cache-Control", "no-store");
    return oauthError(res, status, status === 500 ? "server_error" : "invalid_request");
  });
}
