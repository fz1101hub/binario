import { ENV } from "./env";
import { alexaConfigurationProblems } from "../voice/config";

export const MIN_COOKIE_SECRET_LENGTH = 32;

function isDatabaseUrl(value: string) {
  try {
    const url = new URL(value);
    return url.protocol === "mysql:" && Boolean(url.hostname);
  } catch {
    return false;
  }
}

function isHttpsUrl(value: string) {
  try {
    const url = new URL(value);
    return url.protocol === "https:" && Boolean(url.hostname);
  } catch {
    return false;
  }
}

type RuntimeEnvironment = Pick<
  typeof ENV,
  | "cookieSecret"
  | "appId"
  | "oAuthServerUrl"
  | "oAuthPortalUrl"
  | "databaseUrl"
  | "alexaEnabled"
  | "alexaSkillId"
  | "alexaOAuthClientId"
  | "alexaOAuthClientSecret"
  | "voiceTokenPepper"
  | "alexaOAuthRedirectUris"
>;

export function environmentProblems(env: RuntimeEnvironment = ENV): string[] {
  const problems: string[] = [];
  if (env.cookieSecret.trim().length < MIN_COOKIE_SECRET_LENGTH)
    problems.push(
      `JWT_SECRET deve avere almeno ${MIN_COOKIE_SECRET_LENGTH} caratteri`
    );
  if (!env.appId.trim()) problems.push("VITE_APP_ID non configurato");
  if (!env.oAuthServerUrl.trim())
    problems.push("OAUTH_SERVER_URL non configurato");
  else if (!isHttpsUrl(env.oAuthServerUrl))
    problems.push("OAUTH_SERVER_URL deve essere un URL HTTPS valido");
  if (!env.oAuthPortalUrl.trim())
    problems.push("VITE_OAUTH_PORTAL_URL non configurato");
  else if (!isHttpsUrl(env.oAuthPortalUrl))
    problems.push("VITE_OAUTH_PORTAL_URL deve essere un URL HTTPS valido");
  if (!env.databaseUrl.trim()) problems.push("DATABASE_URL non configurato");
  else if (!isDatabaseUrl(env.databaseUrl))
    problems.push("DATABASE_URL deve essere un URL mysql valido");
  problems.push(...alexaConfigurationProblems(env));
  return problems;
}

// A misconfigured secret must stop a production process at boot instead of
// failing every login at runtime.
export function assertRuntimeEnvironment(env = ENV) {
  const problems = environmentProblems(env);
  if (!problems.length) return;
  if (env.isProduction)
    throw new Error(`Configurazione non valida: ${problems.join("; ")}`);
  console.warn(`[Env] ${problems.join("; ")}`);
}
