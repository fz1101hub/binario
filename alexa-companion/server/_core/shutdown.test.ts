import { EventEmitter } from "node:events";
import { afterEach, describe, expect, it, vi } from "vitest";
import { installGracefulShutdown, SHUTDOWN_GRACE_MS } from "./shutdown";

function fakeServer() {
  let finish: ((error?: Error) => void) | undefined;
  return {
    server: {
      close: vi.fn((callback?: (error?: Error) => void) => {
        finish = callback;
        return undefined as never;
      }),
      closeIdleConnections: vi.fn(),
    },
    /** The last in-flight request ends. */
    drained: (error?: Error) => finish?.(error),
  };
}

afterEach(() => {
  vi.useRealTimers();
});

describe("graceful shutdown", () => {
  it("on SIGTERM stops accepting, lets the requests finish, ends the database, exits 0", async () => {
    const { server, drained } = fakeServer();
    const signals = new EventEmitter();
    const exit = vi.fn();
    const closeDatabase = vi.fn(async () => undefined);
    installGracefulShutdown(server, { closeDatabase, exit, signals: signals as never, log: () => undefined });
    signals.emit("SIGTERM");
    expect(server.close).toHaveBeenCalledTimes(1);
    expect(server.closeIdleConnections).toHaveBeenCalledTimes(1);
    expect(closeDatabase).not.toHaveBeenCalled();
    expect(exit).not.toHaveBeenCalled();
    drained();
    await vi.waitFor(() => expect(exit).toHaveBeenCalledWith(0));
    expect(closeDatabase).toHaveBeenCalledTimes(1);
    // A second signal changes nothing.
    signals.emit("SIGINT");
    expect(server.close).toHaveBeenCalledTimes(1);
  });

  it("exits 1 when requests are still open after the grace period", () => {
    vi.useFakeTimers();
    const { server } = fakeServer();
    const signals = new EventEmitter();
    const exit = vi.fn();
    installGracefulShutdown(server, { closeDatabase: async () => undefined, exit, signals: signals as never, log: () => undefined });
    signals.emit("SIGINT");
    vi.advanceTimersByTime(SHUTDOWN_GRACE_MS - 1);
    expect(exit).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(exit).toHaveBeenCalledWith(1);
  });

  it("still exits when the database cannot be closed", async () => {
    const { server, drained } = fakeServer();
    const signals = new EventEmitter();
    const exit = vi.fn();
    installGracefulShutdown(server, { closeDatabase: async () => Promise.reject(new Error("pool gone")), exit, signals: signals as never, log: () => undefined });
    signals.emit("SIGTERM");
    drained();
    await vi.waitFor(() => expect(exit).toHaveBeenCalledWith(0));
  });
});
