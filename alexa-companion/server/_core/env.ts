const list = (value: string | undefined) =>
  (value ?? "")
    .split(",")
    .map(item => item.trim())
    .filter(Boolean);

export const ENV = {
  appId: process.env.VITE_APP_ID ?? "",
  cookieSecret: process.env.JWT_SECRET ?? "",
  databaseUrl: process.env.DATABASE_URL ?? "",
  oAuthServerUrl: process.env.OAUTH_SERVER_URL ?? "",
  oAuthPortalUrl: process.env.VITE_OAUTH_PORTAL_URL ?? "",
  ownerOpenId: process.env.OWNER_OPEN_ID ?? "",
  isProduction: process.env.NODE_ENV === "production",
  forgeApiUrl: process.env.BUILT_IN_FORGE_API_URL ?? "",
  forgeApiKey: process.env.BUILT_IN_FORGE_API_KEY ?? "",
  // No implicit activation: deployment must choose true or false at boot.
  alexaEnabled: process.env.ALEXA_ENABLED ?? "",
  alexaSkillId: process.env.ALEXA_SKILL_ID ?? "",
  alexaOAuthClientId: process.env.ALEXA_OAUTH_CLIENT_ID ?? "",
  alexaOAuthClientSecret: process.env.ALEXA_OAUTH_CLIENT_SECRET ?? "",
  // Exact Amazon callback URIs allowed for the Alexa account-linking flow.
  alexaOAuthRedirectUris: list(process.env.ALEXA_OAUTH_REDIRECT_URIS),
  voiceTokenPepper: process.env.VOICE_TOKEN_PEPPER ?? "",
  trustProxy: process.env.TRUST_PROXY ?? "",
};
