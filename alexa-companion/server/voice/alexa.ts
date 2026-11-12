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
  formatList,
  formatMessage,
  formatPlural,
  messagesForLocale,
  resolveLocale,
  resolveWebLocale,
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
import { describeErrorForLog } from "../_core/logRedaction";
import { createWindowCounter, markRelayedForAnotherSkill } from "../_core/rateLimit";
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
import { escapeSsmlText, liveDataUnavailableSpeech, plainSpeechText } from "./speech";
import { formatRailDate, parseScreenEvent, requestTime, screenResponseInterceptor, setScreen, type RouteAction } from "./presentation";
import { alexaRequestLogContext } from "./requestLog";
import { matchRouteByName } from "./routeMatching";
import { punctualityStats, railStationIndex } from "../punctuality/source";
import { mostPunctualDepartures, railClock, routePunctuality, trainPunctuality } from "../punctuality/stats";
import {
  answerText,
  joinSentences,
  mostPunctualAnswer,
  routeAnswer,
  trainAnswer,
  unavailableAnswer,
  type PunctualityAnswer,
} from "./punctualitySpeech";

/** Intent names the skill handles; the interaction model in skill-package/ must declare all of them. */
export const HANDLED_INTENTS = [
  "NextTrainsIntent",
  "TrainStatusIntent",
  "MyRoutesIntent",
  "TrainPunctualityIntent",
  "RoutePunctualityIntent",
  "MostPunctualTrainIntent",
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

/**
 * Resolves the Binario user behind an Alexa request; null when the link is missing or revoked, or
 * bound to another Alexa account. A link is bound to the Alexa account (the digest of its user id)
 * of the first verified request after linking: an access token presented for another account,
 * should one ever leak, is refused like an unlinked one and the user is asked to link again.
 */
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
  // Every Alexa request names its user; without one the binding cannot be checked.
  if (!alexaUserId) return null;
  const alexaUserHash = hashVoiceSecret(alexaUserId);
  if (link.alexaUserHash && !secureStringEquals(link.alexaUserHash, alexaUserHash)) return null;
  if (!(await recordAlexaVerification(principal.userId, alexaUserHash))) return null;
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
    setScreen(input, { kind: "home", message: messages.launch });
    return input.responseBuilder
      .speak(messages.launch)
      // After a silent launch the user said nothing: ask again, never "I didn't understand".
      .reprompt(messages.launchReprompt)
      .withSimpleCard(messages.cardTitle, messages.launch)
      .withShouldEndSession(false)
      .getResponse();
  },
};

type PersonalRoute = Awaited<ReturnType<typeof listPersonalRoutesForAlexa>>[number];
type SkillResponse = ReturnType<HandlerInput["responseBuilder"]["getResponse"]>;

type RouteSelection =
  | PersonalRoute
  | { notFound: string }
  | { ambiguous: PersonalRoute[] }
  | undefined;

type RoutePurpose = RouteAction;
type RouteSelector = (namedRoutes: PersonalRoute[], locale: SupportedLocale) => RouteSelection;

/**
 * The saved route a request is about, or the response that asks which one or says there are
 * none. `select` picks the route among the named ones: undefined means none matched, `notFound` a
 * spoken name that matched nothing and `ambiguous` several candidates. Only called after the
 * account link has been verified.
 */
async function selectedRoute(
  input: HandlerInput,
  principal: { userId: number; credentialVersion: number },
  select: RouteSelector,
  purpose: RoutePurpose,
  onMissing?: () => Promise<SkillResponse>
): Promise<{ route: PersonalRoute } | { response: SkillResponse }> {
  const locale = getAlexaLocale(input);
  const routes = await listPersonalRoutesForAlexa(
    principal.userId,
    principal.credentialVersion
  );
  const namedRoutes = routes.filter(item => item.name.trim().length > 0);
  const selection = select(namedRoutes, locale);
  if (selection && "ambiguous" in selection)
    return { response: askWhichRoute(input, selection.ambiguous, undefined, purpose) };
  if (selection && "notFound" in selection)
    return {
      response: namedRoutes.length
        ? askWhichRoute(input, namedRoutes, selection.notFound, purpose)
        : noRoutesResponse(input),
    };
  if (selection) return { route: selection };
  return { response: onMissing && namedRoutes.length ? await onMissing() : noRoutesResponse(input) };
}

/** The route the user named in the `routeName` slot, or the pinned (else first) route. */
function spokenRouteSelector(input: HandlerInput): RouteSelector {
  return (namedRoutes, locale) => {
    const requestedName = intentSlot(input, "routeName", "route", "tratta");
    if (!requestedName)
      return namedRoutes.find(item => item.isPinned) ?? namedRoutes[0];
    const match = matchRouteByName(namedRoutes, requestedName, locale);
    if (match.kind === "match") return match.route;
    if (match.kind === "ambiguous") return { ambiguous: match.routes };
    return { notFound: requestedName };
  };
}

/**
 * Speaks the live-data caveat for one saved route, then the trains of the statistics that usually
 * leave it in the next three hours, most punctual first, when there are any. Only reached after the
 * account link has been verified.
 */
async function respondNextTrains(
  input: HandlerInput,
  select: RouteSelector,
  onMissing?: () => Promise<SkillResponse>
) {
  const locale = getAlexaLocale(input);
  const messages = messagesForLocale(locale).voice;
  const principal = await getLinkedUser(input);
  if (!principal) return linkRequiredResponse(input);
  const selected = await selectedRoute(input, principal, select, "nextTrains", onMissing);
  if ("response" in selected) return selected.response;
  const { route } = selected;
  const known = await knownRoutePunctuality(input, route);
  // SSML needs escaped text; the companion-app card is plain text and must
  // show the route name as typed, not XML entities.
  const speech = withKnownPunctuality(
    input,
    liveDataUnavailableSpeech(locale, formatMessage(messages.routeSubject, { routeName: escapeSsmlText(route.name) })),
    known,
    escapeSsmlText
  );
  const card = withKnownPunctuality(
    input,
    liveDataUnavailableSpeech(locale, formatMessage(messages.routeSubject, { routeName: plainSpeechText(route.name) })),
    known,
    plainSpeechText
  );
  setScreen(input, { kind: "departures", routeName: route.name });
  return input.responseBuilder
    .speak(speech)
    .withSimpleCard(messages.cardTitle, card)
    .withShouldEndSession(true)
    .getResponse();
}

/** Spoken and card forms of a route list, joined the way the locale writes lists. */
function spokenRouteList(locale: SupportedLocale, routes: readonly PersonalRoute[]) {
  return {
    speech: formatList(locale, routes.map(route => escapeSsmlText(route.name))),
    card: formatList(locale, routes.map(route => plainSpeechText(route.name))),
  };
}

function noRoutesResponse(input: HandlerInput) {
  const messages = voiceMessages(input);
  setScreen(input, { kind: "myRoutes", routes: [], remainingCount: 0 });
  return input.responseBuilder
    .speak(messages.noRoutes)
    .withSimpleCard(messages.cardTitle, messages.noRoutes)
    .withShouldEndSession(true)
    .getResponse();
}

const SPOKEN_ROUTE_LIMIT = 5;

/**
 * The spoken name matched several routes, or (with `notFound`) none: say so, read the
 * candidates (at most five, like "my routes") and keep listening for the user's choice.
 */
function askWhichRoute(
  input: HandlerInput,
  candidates: readonly PersonalRoute[],
  notFound?: string,
  purpose: RoutePurpose = "nextTrains"
) {
  const locale = getAlexaLocale(input);
  const messages = messagesForLocale(locale).voice;
  // The example must be a sample of the intent the user is in, so the answer reaches it again.
  const p = messagesForLocale(locale).punctuality;
  const choiceTemplate = { nextTrains: messages.routeChoice, punctuality: p.routeChoice, mostPunctual: p.mostPunctualRouteChoice }[purpose];
  const spoken = candidates.slice(0, SPOKEN_ROUTE_LIMIT);
  const remainingCount = Math.max(candidates.length - SPOKEN_ROUTE_LIMIT, 0);
  const list = spokenRouteList(locale, spoken);
  const suffix = remainingCount > 0
    ? formatPlural(locale, remainingCount, messages.moreRoutes)
    : messages.listTerminator;
  const sentence = (routes: string, name: string, example: string) =>
    [
      notFound
        ? `${formatMessage(messages.routeNotFound, { routeName: name })} ${formatMessage(messages.routesSaved, { routes, suffix })}`
        : formatMessage(messages.routeAmbiguous, { routes }),
      formatMessage(choiceTemplate, { example }),
    ].join(" ");
  const choice = formatMessage(choiceTemplate, { example: escapeSsmlText(spoken[0].name) });
  setScreen(input, {
    kind: "myRoutes",
    routes: spoken.map(route => ({ name: route.name, isPinned: route.isPinned })),
    remainingCount,
    routeAction: purpose,
  });
  return input.responseBuilder
    .speak(sentence(list.speech, escapeSsmlText(notFound ?? ""), escapeSsmlText(spoken[0].name)))
    .reprompt(choice)
    .withSimpleCard(
      messages.cardTitle,
      sentence(list.card, plainSpeechText(notFound ?? ""), plainSpeechText(spoken[0].name))
    )
    .withShouldEndSession(false)
    .getResponse();
}

const nextTrainsHandler: RequestHandler = {
  canHandle: input =>
    getRequestType(input.requestEnvelope) === "IntentRequest" &&
    getIntentName(input.requestEnvelope) === "NextTrainsIntent",
  handle: input => respondNextTrains(input, spokenRouteSelector(input)),
};

/**
 * Says that live data is not available, then how punctual the train usually is when the statistics
 * have figures for it: nothing personal, so no linked account is needed.
 */
const trainStatusHandler: RequestHandler = {
  canHandle: input =>
    getRequestType(input.requestEnvelope) === "IntentRequest" &&
    getIntentName(input.requestEnvelope) === "TrainStatusIntent",
  handle: async input => {
    const locale = getAlexaLocale(input);
    const messages = messagesForLocale(locale).voice;
    const slot = intentSlot(input, "trainNumber", "train", "numeroTreno");
    // AMAZON.NUMBER gives "?" when it heard no number: no «Treno ?».
    const trainNumber = slot && /[0-9]/.test(slot) ? slot : undefined;
    const subject = trainNumber
      ? formatMessage(messages.trainSubject, { trainNumber: escapeSsmlText(trainNumber) })
      : undefined;
    const cardSubject = trainNumber
      ? formatMessage(messages.trainSubject, { trainNumber: plainSpeechText(trainNumber) })
      : undefined;
    const known = trainNumber ? await knownTrainPunctuality(locale, trainNumber) : undefined;
    const speech = withKnownPunctuality(input, liveDataUnavailableSpeech(locale, subject), known, escapeSsmlText);
    setScreen(input, { kind: "trainStatus", trainNumber });
    return input.responseBuilder
      .speak(speech)
      .withSimpleCard(messages.cardTitle, withKnownPunctuality(input, liveDataUnavailableSpeech(locale, cardSubject), known, plainSpeechText))
      .withShouldEndSession(true)
      .getResponse();
  },
};

/** Reads the saved route names; only reached after the account link has been verified. */
async function respondMyRoutes(input: HandlerInput) {
  const locale = getAlexaLocale(input);
  const messages = messagesForLocale(locale).voice;
  const principal = await getLinkedUser(input);
  if (!principal) return linkRequiredResponse(input);
  const routes = await listPersonalRoutesForAlexa(
    principal.userId,
    principal.credentialVersion
  );
  if (!routes.length) return noRoutesResponse(input);
  const spoken = routes.slice(0, SPOKEN_ROUTE_LIMIT);
  const remainingRouteCount = Math.max(routes.length - SPOKEN_ROUTE_LIMIT, 0);
  const suffix =
    remainingRouteCount > 0
      ? formatPlural(locale, remainingRouteCount, messages.moreRoutes)
      : messages.listTerminator;
  const list = spokenRouteList(locale, spoken);
  const speech = formatMessage(messages.routesSaved, { routes: list.speech, suffix });
  const card = formatMessage(messages.routesSaved, { routes: list.card, suffix });
  // The screen shows the same five names the voice reads, and nothing else about them.
  setScreen(input, {
    kind: "myRoutes",
    routes: spoken.map(route => ({ name: route.name, isPinned: route.isPinned })),
    remainingCount: remainingRouteCount,
  });
  return input.responseBuilder
    .speak(speech)
    .withSimpleCard(messages.cardTitle, card)
    .withShouldEndSession(true)
    .getResponse();
}

/**
 * A punctuality answer: speech (SSML-escaped user values) and card (plain), and the screen that
 * repeats it. `text` builds the answer for one of the two forms.
 */
/** Statistics whose window ended longer ago than this are still answered, saying until when. */
export const STALE_STATISTICS_DAYS = 14;
const SENTENCE_END: Partial<Record<SupportedLocale, string>> = { "ja-JP": "。", "hi-IN": "।" };

/**
 * «Dati fino al 9 novembre 2026.» after the figures when the file's window ended more than
 * {@link STALE_STATISTICS_DAYS} days before the request: the file is rebuilt and redeployed by
 * hand, and an old one must not sound current.
 */
function withDataAge(answer: PunctualityAnswer, locale: SupportedLocale, windowEnd: string | undefined, now: Date): PunctualityAnswer {
  if (!answer.attributed || !windowEnd) return answer;
  const ended = Date.parse(`${windowEnd}T23:59:59Z`);
  if (!Number.isFinite(ended) || now.getTime() - ended <= STALE_STATISTICS_DAYS * 86_400_000) return answer;
  const until = formatMessage(messagesForLocale(locale).punctuality.statisticsUntil, { date: formatRailDate(windowEnd, locale) });
  return { ...answer, sentences: [...answer.sentences, `${until}${SENTENCE_END[locale] ?? "."}`] };
}

/** What the statistics know about the subject of a live question: an answer with figures only. */
type KnownPunctuality = { build: (text: (value: string) => string) => PunctualityAnswer; windowEnd: string };

/**
 * The live-data caveat first, then what the statistics know about the same train or route, with
 * its attribution and, for an old file, until when it runs: the live data are missing, the usual
 * punctuality is not. Without figures the caveat stays alone.
 */
function withKnownPunctuality(
  input: HandlerInput,
  caveat: string,
  known: KnownPunctuality | undefined,
  text: (value: string) => string
): string {
  if (!known) return caveat;
  const locale = getAlexaLocale(input);
  const answer = withDataAge(known.build(text), locale, known.windowEnd, requestTime(input));
  return answerText(locale, { ...answer, sentences: [`${caveat}${SENTENCE_END[locale] ?? "."}`, ...answer.sentences] });
}

/**
 * The punctuality of the train asked for live, when the statistics have figures for it. A failure
 * here never costs the live answer: it is logged by error class only and the caveat stays alone.
 */
async function knownTrainPunctuality(locale: SupportedLocale, trainNumber: string): Promise<KnownPunctuality | undefined> {
  try {
    const stats = await punctualityStats();
    if (!stats) return undefined;
    const result = trainPunctuality(stats, trainNumber);
    if (result.kind !== "stats" && result.kind !== "cancelledEveryDay") return undefined;
    return { build: text => trainAnswer(locale, trainNumber, result, text), windowEnd: stats.windowEnd };
  } catch (error) {
    console.warn("[Alexa] punctuality not added", { error: describeErrorForLog(error) });
    return undefined;
  }
}

/** The most punctual trains of the next three hours on the route asked for live, when there are any. */
async function knownRoutePunctuality(input: HandlerInput, route: PersonalRoute): Promise<KnownPunctuality | undefined> {
  const locale = getAlexaLocale(input);
  try {
    const [stats, stations] = await Promise.all([punctualityStats(), railStationIndex()]);
    if (!stats || !stations) return undefined;
    const origins = stations.codesFor(route.origin);
    const destinations = stations.codesFor(route.destination);
    if (!origins.length || !destinations.length) return undefined;
    const trains = mostPunctualDepartures(stats, origins, destinations, railClock(requestTime(input)));
    if (!trains?.length) return undefined;
    return { build: text => mostPunctualAnswer(locale, route.name, { kind: "trains", trains }, text), windowEnd: stats.windowEnd };
  } catch (error) {
    console.warn("[Alexa] punctuality not added", { error: describeErrorForLog(error) });
    return undefined;
  }
}

function punctualityResponse(
  input: HandlerInput,
  build: (text: (value: string) => string) => PunctualityAnswer,
  subject: { about: "train"; trainNumber: string } | { about: "route"; routeName?: string },
  windowEnd: string | undefined
) {
  const locale = getAlexaLocale(input);
  const now = requestTime(input);
  const spoken = withDataAge(build(escapeSsmlText), locale, windowEnd, now);
  const written = withDataAge(build(plainSpeechText), locale, windowEnd, now);
  setScreen(input, {
    kind: "punctuality",
    ...subject,
    message: joinSentences(written.sentences),
    figures: written.figures,
    trains: written.trains,
    windowEnd,
  });
  return input.responseBuilder
    .speak(answerText(locale, spoken))
    .withSimpleCard(messagesForLocale(locale).voice.cardTitle, answerText(locale, written))
    .withShouldEndSession(true)
    .getResponse();
}

/** A train's punctuality is not personal: it is answered with or without a linked account. */
const trainPunctualityHandler: RequestHandler = {
  canHandle: input => isIntent(input, "TrainPunctualityIntent"),
  handle: async input => {
    const locale = getAlexaLocale(input);
    const p = messagesForLocale(locale).punctuality;
    const trainNumber = intentSlot(input, "trainNumber", "train", "numeroTreno");
    // AMAZON.NUMBER gives "?" when it heard no number.
    if (!trainNumber || !/[0-9]/.test(trainNumber)) {
      setScreen(input, { kind: "punctuality", about: "train", message: p.trainMissing });
      return input.responseBuilder
        .speak(p.trainMissing)
        .reprompt(p.trainMissing)
        .withSimpleCard(messagesForLocale(locale).voice.cardTitle, p.trainMissing)
        .withShouldEndSession(false)
        .getResponse();
    }
    const stats = await punctualityStats();
    const result = stats ? trainPunctuality(stats, trainNumber) : null;
    return punctualityResponse(
      input,
      text => (result ? trainAnswer(locale, trainNumber, result, text) : unavailableAnswer(locale)),
      { about: "train", trainNumber },
      stats?.windowEnd
    );
  },
};

/**
 * Punctuality of one of the linked user's saved routes: every train of the statistics from its
 * origin to its destination, matched by station name. The names stay on the server: the answer
 * and the screen name only the route.
 */
async function respondRoutePunctuality(
  input: HandlerInput,
  select: RouteSelector,
  onMissing?: () => Promise<SkillResponse>
) {
  const locale = getAlexaLocale(input);
  const principal = await getLinkedUser(input);
  if (!principal) return linkRequiredResponse(input);
  const [stats, stations] = await Promise.all([punctualityStats(), railStationIndex()]);
  if (!stats || !stations)
    return punctualityResponse(input, () => unavailableAnswer(locale), { about: "route" }, stats?.windowEnd);
  const selected = await selectedRoute(input, principal, select, "punctuality", onMissing);
  if ("response" in selected) return selected.response;
  const { route } = selected;
  const origins = stations.codesFor(route.origin);
  const destinations = stations.codesFor(route.destination);
  const result = origins.length && destinations.length ? routePunctuality(stats, origins, destinations) : null;
  const outcome = !origins.length || !destinations.length
    ? ({ kind: "stationsUnknown" } as const)
    : result
      ? ({ kind: "stats", result } as const)
      : ({ kind: "fewData" } as const);
  return punctualityResponse(
    input,
    text => routeAnswer(locale, route.name, outcome, text),
    { about: "route", routeName: route.name },
    stats.windowEnd
  );
}

const routePunctualityHandler: RequestHandler = {
  canHandle: input => isIntent(input, "RoutePunctualityIntent"),
  handle: input => respondRoutePunctuality(input, spokenRouteSelector(input)),
};

/**
 * «Qual è il treno più puntuale per casa lavoro?»: among the trains of the statistics from the
 * saved route's origin to its destination (as the route's punctuality), those that usually leave
 * the origin in the next three hours, Italian time, best on-time share first. Needs the account
 * link like every route question; the stations stay on the server and nothing is logged.
 */
async function respondMostPunctual(
  input: HandlerInput,
  select: RouteSelector,
  onMissing?: () => Promise<SkillResponse>
) {
  const locale = getAlexaLocale(input);
  const principal = await getLinkedUser(input);
  if (!principal) return linkRequiredResponse(input);
  const [stats, stations] = await Promise.all([punctualityStats(), railStationIndex()]);
  if (!stats || !stations)
    return punctualityResponse(input, () => unavailableAnswer(locale), { about: "route" }, stats?.windowEnd);
  const selected = await selectedRoute(input, principal, select, "mostPunctual", onMissing);
  if ("response" in selected) return selected.response;
  const { route } = selected;
  const origins = stations.codesFor(route.origin);
  const destinations = stations.codesFor(route.destination);
  const trains = origins.length && destinations.length
    ? mostPunctualDepartures(stats, origins, destinations, railClock(requestTime(input)))
    : null;
  const outcome = !origins.length || !destinations.length
    ? ({ kind: "stationsUnknown" } as const)
    : trains?.length
      ? ({ kind: "trains", trains } as const)
      : ({ kind: "none" } as const);
  return punctualityResponse(
    input,
    text => mostPunctualAnswer(locale, route.name, outcome, text),
    { about: "route", routeName: route.name },
    stats.windowEnd
  );
}

const mostPunctualTrainHandler: RequestHandler = {
  canHandle: input => isIntent(input, "MostPunctualTrainIntent"),
  handle: input => respondMostPunctual(input, spokenRouteSelector(input)),
};

const myRoutesHandler: RequestHandler = {
  canHandle: input =>
    getRequestType(input.requestEnvelope) === "IntentRequest" &&
    getIntentName(input.requestEnvelope) === "MyRoutesIntent",
  handle: input => respondMyRoutes(input),
};

const ROUTE_RESPONDERS: Readonly<
  Record<RouteAction, (input: HandlerInput, select: RouteSelector, onMissing?: () => Promise<SkillResponse>) => Promise<SkillResponse>>
> = {
  nextTrains: respondNextTrains,
  punctuality: respondRoutePunctuality,
  mostPunctual: respondMostPunctual,
};

/**
 * Touches on the Echo Show document. Each event is re-authorised exactly like a voice request
 * (the access token travels with it), and only the four allowlisted actions are accepted.
 */
const screenEventHandler: RequestHandler = {
  canHandle: input =>
    getRequestType(input.requestEnvelope) === "Alexa.Presentation.APL.UserEvent",
  handle: input => {
    const event = parseScreenEvent(input.requestEnvelope.request);
    if (!event) return unsupportedResponse(input);
    if (event.action === "myRoutes") return respondMyRoutes(input);
    const respond = ROUTE_RESPONDERS[event.action];
    const { routeName } = event;
    if (!routeName)
      return respond(
        input,
        namedRoutes => namedRoutes.find(item => item.isPinned) ?? namedRoutes[0]
      );
    // The touched route may have been renamed or deleted since the screen was drawn: show the
    // current list instead of another route's answer.
    return respond(
      input,
      namedRoutes => namedRoutes.find(item => plainSpeechText(item.name) === routeName),
      () => respondMyRoutes(input)
    );
  },
};

const helpHandler: RequestHandler = {
  canHandle: input => isIntent(input, "AMAZON.HelpIntent"),
  handle: input => {
    const messages = voiceMessages(input);
    setScreen(input, { kind: "home", message: messages.help });
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

function unsupportedResponse(input: HandlerInput) {
  const messages = voiceMessages(input);
  setScreen(input, { kind: "home", message: messages.unsupported });
  return input.responseBuilder
    .speak(messages.unsupported)
    .reprompt(messages.unsupported)
    .withSimpleCard(messages.cardTitle, messages.unsupported)
    .withShouldEndSession(false)
    .getResponse();
}

const fallbackHandler: RequestHandler = {
  canHandle: input => getRequestType(input.requestEnvelope) === "IntentRequest",
  handle: input => unsupportedResponse(input),
};

const errorHandler = {
  canHandle: () => true,
  handle: (input: HandlerInput, error: unknown) => {
    // Request type, intent, locale and error class only: no slot values, user or device ids.
    console.error("[Alexa] request failed", {
      ...alexaRequestLogContext(input.requestEnvelope),
      error: describeErrorForLog(error),
    });
    const messages = voiceMessages(input);
    // A handler may have described its screen before failing. The SDK skips response
    // interceptors on this path today; clearing keeps an error from ever rendering it.
    setScreen(input, null);
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
      trainPunctualityHandler,
      routePunctualityHandler,
      mostPunctualTrainHandler,
      screenEventHandler,
      fallbackHandler
    )
    .addErrorHandlers(errorHandler)
    .addResponseInterceptors(screenResponseInterceptor)
    .create();
}

function oauthError(res: Response, status: number, error: string) {
  return res.status(status).json({ error });
}

/**
 * Refresh tokens last a year and slide with every rotation, so a user who does not speak to
 * the skill for a few weeks stays linked; unlink, re-link, account deletion and replay
 * detection still revoke them server-side.
 */
export const REFRESH_TOKEN_TTL_MS = 365 * 24 * 60 * 60 * 1000;

/** Token requests per minute for the one authenticated OAuth client (Amazon), all users together. */
export const ALEXA_TOKEN_REQUESTS_PER_CLIENT_PER_MINUTE = 1_200;

type ClientCredentials = { clientId: string; clientSecret: string };

function formUrlDecode(value: string) {
  return decodeURIComponent(value.replace(/\+/g, " "));
}

/**
 * Client authentication for the token endpoint (RFC 6749 §2.3.1): HTTP Basic, which the Alexa
 * console recommends, with the id and secret form-URL-encoded before base64, or client_id and
 * client_secret in the body. Using both at once is refused; a body client_id next to Basic is
 * allowed only when it names the same client. Null means no acceptable credentials.
 */
export function readClientCredentials(
  authorization: string | undefined,
  body: Record<string, unknown>
): ClientCredentials | null {
  const bodyId = typeof body.client_id === "string" ? body.client_id : undefined;
  const bodySecret = typeof body.client_secret === "string" ? body.client_secret : undefined;
  if (authorization !== undefined && /^basic(?:\s|$)/i.test(authorization)) {
    const encoded = /^basic\s+([A-Za-z0-9+/]+={0,2})\s*$/i.exec(authorization)?.[1];
    if (!encoded || bodySecret !== undefined) return null;
    const decoded = Buffer.from(encoded, "base64").toString("utf8");
    const separator = decoded.indexOf(":");
    if (separator < 0) return null;
    try {
      const clientId = formUrlDecode(decoded.slice(0, separator));
      const clientSecret = formUrlDecode(decoded.slice(separator + 1));
      if (bodyId !== undefined && bodyId !== clientId) return null;
      return { clientId, clientSecret };
    } catch {
      return null;
    }
  }
  return bodyId !== undefined && bodySecret !== undefined
    ? { clientId: bodyId, clientSecret: bodySecret }
    : null;
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
  const locale = resolveWebLocale(req.get("accept-language"));
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

export function registerAlexaRoutes(
  app: Express,
  options: { tokenRequestsPerClientPerMinute?: number } = {}
) {
  const tokenRequestsPerClient = createWindowCounter({
    limit: options.tokenRequestsPerClientPerMinute ?? ALEXA_TOKEN_REQUESTS_PER_CLIENT_PER_MINUTE,
  });
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
          locale: resolveWebLocale(req.get("accept-language")),
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
            resolveWebLocale(req.get("accept-language")),
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
      // RFC 6749 §5.1: token responses, errors included, are never cached.
      res.set({ "Cache-Control": "no-store", Pragma: "no-cache" });
      if (!isAlexaConfigured())
        return oauthError(res, 503, "alexa_not_configured");
      const credentials = readClientCredentials(
        req.get("authorization"),
        (req.body ?? {}) as Record<string, unknown>
      );
      if (
        !credentials ||
        !secureStringEquals(credentials.clientId, ENV.alexaOAuthClientId) ||
        !secureStringEquals(credentials.clientSecret, ENV.alexaOAuthClientSecret)
      ) {
        // Credentials of another client (someone's own skill pointed at this URL, relayed by
        // Amazon from our users' addresses) do not use up the per-IP failure budget; a wrong
        // secret for our client, or no usable credentials, does.
        if (credentials && !secureStringEquals(credentials.clientId, ENV.alexaOAuthClientId)) markRelayedForAnotherSkill(res);
        res.set("WWW-Authenticate", 'Basic realm="binario-alexa", charset="UTF-8"');
        return oauthError(res, 401, "invalid_client");
      }
      const { clientId } = credentials;
      // Amazon refreshes every linked user from a few shared addresses: the budget belongs
      // to the authenticated client, not to an IP (failed authentication is limited per IP).
      if (!tokenRequestsPerClient.hit(clientId)) {
        res.set("Retry-After", String(tokenRequestsPerClient.retryAfterSeconds(clientId)));
        return oauthError(res, 429, "too_many_requests");
      }

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
                refreshTokenExpiresAt: new Date(Date.now() + REFRESH_TOKEN_TTL_MS),
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
              nextTokenExpiresAt: new Date(Date.now() + REFRESH_TOKEN_TTL_MS),
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

  app.use("/api/alexa/oauth", (error: unknown, req: Request, res: Response, next: NextFunction) => {
    if (res.headersSent) return next(error);
    // Keep database errors and tokens out of the response; the log gets only the
    // redacted description (no request body, no bound SQL values).
    const status = typeof error === "object" && error !== null && "status" in error &&
      typeof error.status === "number" && error.status >= 400 && error.status < 500
      ? error.status : 500;
    if (status === 500)
      console.error("[Alexa OAuth] request failed", { path: req.path, error: describeErrorForLog(error) });
    res.setHeader("Cache-Control", "no-store");
    return oauthError(res, status, status === 500 ? "server_error" : "invalid_request");
  });
}
