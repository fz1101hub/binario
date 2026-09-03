import { ENV } from "../_core/env";

/**
 * Alexa remains fail-closed until all configuration is present. In particular,
 * an empty skill identifier must never result in accepting an otherwise valid
 * request intended for another skill.
 */
export function isAlexaConfigured(): boolean {
  return Boolean(
    ENV.alexaSkillId &&
      ENV.alexaOAuthClientId &&
      ENV.alexaOAuthClientSecret &&
      ENV.voiceTokenPepper
  );
}

export const LIVE_DATA_UNAVAILABLE_SPEECH = "dati non disponibili in tempo reale";
