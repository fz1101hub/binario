import type { Server } from "node:http";

/** In-flight requests (an Alexa answer, a token exchange) get this long to finish after SIGTERM. */
export const SHUTDOWN_GRACE_MS = 10_000;

export type ShutdownHooks = {
  /** Ends the database pool once the server has stopped. */
  closeDatabase: () => Promise<void>;
  exit: (code: number) => void;
  /** Where the signals are listened for (the process). */
  signals: Pick<NodeJS.Process, "once">;
  graceMs?: number;
  log?: (message: string) => void;
};

/**
 * On SIGTERM or SIGINT (a deploy, a restart) the server stops accepting connections, closes the
 * idle keep-alive ones, lets the requests in flight finish, then ends the database pool and exits
 * 0; after {@link SHUTDOWN_GRACE_MS} it exits 1 whatever is still open. A second signal changes
 * nothing.
 */
export function installGracefulShutdown(
  server: Pick<Server, "close" | "closeIdleConnections">,
  { closeDatabase, exit, signals, graceMs = SHUTDOWN_GRACE_MS, log = message => console.log(message) }: ShutdownHooks
) {
  let closing = false;
  const shutdown = (signal: string) => {
    if (closing) return;
    closing = true;
    log(`[Server] ${signal}: closing`);
    const deadline = setTimeout(() => exit(1), graceMs);
    deadline.unref?.();
    server.close(error => {
      void closeDatabase()
        .catch(() => undefined)
        .then(() => {
          clearTimeout(deadline);
          exit(error ? 1 : 0);
        });
    });
    server.closeIdleConnections();
  };
  signals.once("SIGTERM", () => shutdown("SIGTERM"));
  signals.once("SIGINT", () => shutdown("SIGINT"));
  return shutdown;
}
