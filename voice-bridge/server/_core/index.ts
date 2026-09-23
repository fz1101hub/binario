import "dotenv/config";
import { createServer } from "http";
import net from "net";
import { createApp } from "./app";
import { ENV } from "./env";
import { assertRuntimeEnvironment } from "./envCheck";

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

async function startServer() {
  assertRuntimeEnvironment();
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
