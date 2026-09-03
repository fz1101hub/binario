export type OAuthAuthorizationParameters = {
  responseType: string;
  clientId: string;
  redirectUri: string;
  state: string;
  scope: string;
};

export function parseOAuthAuthorizationParameters(query: Record<string, unknown>, expectedClientId: string): OAuthAuthorizationParameters | null {
  const responseType = typeof query.response_type === "string" ? query.response_type : "";
  const clientId = typeof query.client_id === "string" ? query.client_id : "";
  const redirectUri = typeof query.redirect_uri === "string" ? query.redirect_uri : "";
  const state = typeof query.state === "string" ? query.state : "";
  const scope = typeof query.scope === "string" ? query.scope : "routes.read";
  if (responseType !== "code" || clientId !== expectedClientId || !state || state.length > 1024) return null;
  try {
    if (new URL(redirectUri).protocol !== "https:") return null;
  } catch {
    return null;
  }
  return { responseType, clientId, redirectUri, state, scope: scope.slice(0, 512) };
}
