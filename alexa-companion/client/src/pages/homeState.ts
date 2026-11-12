/**
 * What a dashboard card shows for a query: its data once it has some, otherwise an error line when
 * the request failed, otherwise a loading line. Never an empty or default state the server did not
 * send (a linked account shown as «not linked» while its status loads or after a failure).
 */
export type QueryView<T> = { state: "loading" } | { state: "error" } | { state: "ready"; data: T };

export function queryView<T>(query: { data: T | undefined; isError: boolean }): QueryView<T> {
  if (query.data !== undefined) return { state: "ready", data: query.data };
  return query.isError ? { state: "error" } : { state: "loading" };
}
