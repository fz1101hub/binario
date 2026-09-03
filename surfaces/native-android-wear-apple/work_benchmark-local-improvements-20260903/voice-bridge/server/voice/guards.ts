/** Personal content must never be answered before an account-link token exists. */
export function needsAccountLink(accessToken: string | undefined | null) {
  return !accessToken || !accessToken.trim();
}
