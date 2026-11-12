import "dotenv/config";
import { createServer } from "http";
import net from "net";
import { createApp } from "./app";
import { ENV } from "./env";
import { assertRuntimeEnvironment } from "./envCheck";
import { startupTimestampProblem } from "./dbEngine";
import { createConnection } from "mysql2/promise";

function isPortAvailable(port: number): Promise<boolean> {
  return new Promise(resolve => {
    const server = net.createServer();
    server.listen(port, () => {
      server.close(() => resolve(true));
    });
    server.on("error", () => resolve(false));
  });
}

async function findAvailablePort(startPort: number = 3000): Promise<number> {
  for (let port = startPort; port < startPort + 20; port++) {
    if (await isPortAvailable(port)) {
      return port;
    }
  }
  throw new Error(`No available port found starting from ${startPort}`);
}

/**
 * Refuses to start on a database whose nullable dates would not stay NULL (tables migrated with
 * explicit_defaults_for_timestamp OFF): linking and routes would fail silently. The variable itself
 * only matters to the next migration, which `db:migrate` refuses: here it is a warning. A database
 * that cannot be reached now is left to the usual lazy connection.
 */
async function assertDatabaseTimestamps() {
  if (!process.env.DATABASE_URL) return;
  let connection: Awaited<ReturnType<typeof createConnection>>;
  try {
    connection = await createConnection(process.env.DATABASE_URL);
  } catch {
    console.warn("[Database] Timestamp check skipped: database not reachable at startup");
    return;
  }
  try {
    const problem = await startupTimestampProblem(connection);
    if (problem) throw new Error(`[Database] ${problem}`);
  } finally {
    await connection.end();
  }
}

async function startServer() {
  assertRuntimeEnvironment();
  await assertDatabaseTimestamps();
  const preferredPort = parseInt(process.env.PORT || "3000");
  // In production the port is a contract with the reverse proxy: a process
  // that silently moves elsewhere is unreachable, not resilient.
  const port = ENV.isProduction ? preferredPort : await findAvailablePort(preferredPort);
  if (port !== preferredPort) {
    console.log(`Port ${preferredPort} is busy, using port ${port} instead`);
  }

  const server = createServer();
  const app = await createApp({ server });
  server.on("request", app);
  server.on("error", error => {
    console.error("[Server] Unable to start:", error);
    process.exit(1);
  });
  server.listen(port, () => {
    console.log(`Server running on http://localhost:${port}/`);
  });
}

startServer().catch(error => {
  console.error(error);
  process.exit(1);
});
