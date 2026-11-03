import { ENV } from "../_core/env";

export type AlexaEnvironment = Pick<
  typeof ENV,
  | "alexaEnabled"
  | "alexaSkillId"
  | "alexaOAuthClientId"
  | "alexaOAuthClientSecret"
  | "voiceTokenPepper"
  | "alexaOAuthRedirectUris"
>;

function hasText(value: string) {
  return Boolean(value.trim());
}

function hasAlexaSetting(env: AlexaEnvironment) {
  return Boolean(
    hasText(env.alexaSkillId) ||
      hasText(env.alexaOAuthClientId) ||
      hasText(env.alexaOAuthClientSecret) ||
      hasText(env.voiceTokenPepper) ||
      env.alexaOAuthRedirectUris.length
  );
}

function isValidAlexaRedirectUri(value: string) {
  try {
    const url = new URL(value);
    return (
      url.protocol === "https:" && !url.username && !url.password && !url.hash
    );
  } catch {
    return false;
  }
}

/**
 * Alexa stays disabled unless explicitly enabled with a complete configuration.
 * In particular, an empty skill identifier must never accept a request that is
 * otherwise valid for another skill.
 */
export function isAlexaConfigured(env: AlexaEnvironment = ENV): boolean {
  return Boolean(
    env.alexaEnabled === "true" &&
      hasText(env.alexaSkillId) &&
      hasText(env.alexaOAuthClientId) &&
      hasText(env.alexaOAuthClientSecret) &&
      hasText(env.voiceTokenPepper) &&
      env.alexaOAuthRedirectUris.length > 0 &&
      env.alexaOAuthRedirectUris.every(isValidAlexaRedirectUri)
  );
}

export function alexaConfigurationProblems(
  env: AlexaEnvironment = ENV
): string[] {
  if (env.alexaEnabled !== "true" && env.alexaEnabled !== "false") {
    return [
      "ALEXA_ENABLED deve essere impostata esplicitamente a true o false",
    ];
  }
  if (env.alexaEnabled === "false") {
    return hasAlexaSetting(env)
      ? ["ALEXA_ENABLED=false non ammette configurazione Alexa residua"]
      : [];
  }

  const problems: string[] = [];
  if (!hasText(env.alexaSkillId))
    problems.push("ALEXA_SKILL_ID non configurato");
  if (!hasText(env.alexaOAuthClientId))
    problems.push("ALEXA_OAUTH_CLIENT_ID non configurato");
  if (!hasText(env.alexaOAuthClientSecret))
    problems.push("ALEXA_OAUTH_CLIENT_SECRET non configurato");
  if (!hasText(env.voiceTokenPepper))
    problems.push("VOICE_TOKEN_PEPPER non configurato");
  if (!env.alexaOAuthRedirectUris.length)
    problems.push("ALEXA_OAUTH_REDIRECT_URIS non configurato");
  else if (!env.alexaOAuthRedirectUris.every(isValidAlexaRedirectUri))
    problems.push(
      "ALEXA_OAUTH_REDIRECT_URIS deve contenere soltanto callback HTTPS valide"
    );
  return problems;
}

export const LIVE_DATA_UNAVAILABLE_SPEECH =
  "dati non disponibili in tempo reale";
