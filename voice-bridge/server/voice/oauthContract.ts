export type OAuthAuthorizationParameters = {
  responseType: string;
  clientId: string;
  redirectUri: string;
  state: string;
  scope: string;
};

// RFC 6749 §3.1.2.2: redirect URIs are compared with exact string matching
// against the ones registered for the client.
export function isAllowedRedirectUri(redirectUri: string, allowedRedirectUris: readonly string[]) {
  try {
    if (new URL(redirectUri).protocol !== "https:") return false;
  } catch {
    return false;
  }
  return allowedRedirectUris.includes(redirectUri);
}

export function parseOAuthAuthorizationParameters(
  query: Record<string, unknown>,
  expectedClientId: string,
  allowedRedirectUris: readonly string[]
): OAuthAuthorizationParameters | null {
  const responseType = typeof query.response_type === "string" ? query.response_type : "";
  const clientId = typeof query.client_id === "string" ? query.client_id : "";
  const redirectUri = typeof query.redirect_uri === "string" ? query.redirect_uri : "";
  const state = typeof query.state === "string" ? query.state : "";
  const scope = typeof query.scope === "string" ? query.scope : "routes.read";
  if (responseType !== "code" || !expectedClientId || clientId !== expectedClientId || !state || state.length > 1024) return null;
  if (!isAllowedRedirectUri(redirectUri, allowedRedirectUris)) return null;
  return { responseType, clientId, redirectUri, state, scope: scope.slice(0, 512) };
}
