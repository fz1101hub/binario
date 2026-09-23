import { ENV } from "./env";

export const MIN_COOKIE_SECRET_LENGTH = 32;

export function environmentProblems(env: Pick<typeof ENV, "cookieSecret" | "appId" | "oAuthServerUrl"> = ENV): string[] {
  const problems: string[] = [];
  if (env.cookieSecret.length < MIN_COOKIE_SECRET_LENGTH) problems.push(`JWT_SECRET deve avere almeno ${MIN_COOKIE_SECRET_LENGTH} caratteri`);
  if (!env.appId) problems.push("VITE_APP_ID non configurato");
  if (!env.oAuthServerUrl) problems.push("OAUTH_SERVER_URL non configurato");
  return problems;
}

// A misconfigured secret must stop a production process at boot instead of
// failing every login at runtime.
export function assertRuntimeEnvironment(env = ENV) {
  const problems = environmentProblems(env);
  if (!problems.length) return;
  if (env.isProduction) throw new Error(`Configurazione non valida: ${problems.join("; ")}`);
  console.warn(`[Env] ${problems.join("; ")}`);
}
