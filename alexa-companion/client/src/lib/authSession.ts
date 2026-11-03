import { UNAUTHED_ERR_MSG } from "@shared/const";
import { hashKey, type QueryClient } from "@tanstack/react-query";
import { TRPCClientError } from "@trpc/client";
import { getQueryKey } from "@trpc/react-query";
import { trpc } from "./trpc";

/**
 * Session handling for the dashboard. A missing or expired session never starts a login by
 * itself: an automatic redirect silently undid a logout and, after an account deletion, sent
 * the browser back through the OAuth callback, which provisions a fresh account. Login starts
 * only from the sign-in button (DashboardLayout).
 */

export function isUnauthorizedError(error: unknown) {
  return error instanceof TRPCClientError && error.message === UNAUTHED_ERR_MSG;
}

const meKey = () => getQueryKey(trpc.auth.me, undefined, "query");

/**
 * Marks the dashboard signed out and drops every other cached answer (routes, Alexa status),
 * so nothing personal stays in memory after logout, deletion or an expired session. Protected
 * queries are disabled without a user, so nothing refetches.
 */
export function forgetPersonalData(queryClient: QueryClient) {
  const key = meKey();
  const meHash = hashKey(key);
  queryClient.setQueryData(key, null);
  queryClient.removeQueries({ predicate: query => query.queryHash !== meHash });
}

/** Global query/mutation error hook: an UNAUTHORIZED answer shows the sign-in screen, nothing more. */
export function handleApiError(queryClient: QueryClient, error: unknown) {
  if (isUnauthorizedError(error)) forgetPersonalData(queryClient);
}
