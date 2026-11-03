import { spawn, spawnSync } from "node:child_process";
import { cpSync, mkdtempSync, rmSync } from "node:fs";
import { createServer } from "node:net";
import os from "node:os";
import path from "node:path";

const projectRoot = path.resolve(import.meta.dirname, "..");
const sandbox = mkdtempSync(
  path.join(os.tmpdir(), "alexa-production-install-")
);
let child;

function copy(relativePath) {
  cpSync(
    path.join(projectRoot, relativePath),
    path.join(sandbox, relativePath),
    {
      recursive: true,
    }
  );
}

async function availablePort() {
  const probe = createServer();
  await new Promise((resolve, reject) => {
    probe.once("error", reject);
    probe.listen(0, "127.0.0.1", resolve);
  });
  const address = probe.address();
  await new Promise((resolve, reject) =>
    probe.close(error => (error ? reject(error) : resolve()))
  );
  if (!address || typeof address === "string") throw new Error("No test port");
  return address.port;
}

async function waitForHealth(port, output) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(
        `Production server exited before startup:\n${output.text}`
      );
    }
    try {
      const response = await fetch(`http://127.0.0.1:${port}/healthz`);
      if (response.ok) return;
    } catch {
      // Module linking and listener startup may still be in progress.
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error(`Production server did not become healthy:\n${output.text}`);
}

try {
  for (const file of [
    "package.json",
    "pnpm-lock.yaml",
    "pnpm-workspace.yaml",
    "patches",
    "dist",
  ])
    copy(file);

  const install = spawnSync(
    "pnpm",
    ["install", "--prod", "--frozen-lockfile", "--ignore-scripts"],
    { cwd: sandbox, encoding: "utf8" }
  );
  if (install.status !== 0) {
    throw new Error(
      `Production-only install failed:\n${install.stdout}${install.stderr}`
    );
  }

  const port = await availablePort();
  const output = { text: "" };
  child = spawn(process.execPath, ["dist/index.js"], {
    cwd: sandbox,
    env: {
      ...process.env,
      NODE_ENV: "production",
      PORT: String(port),
      JWT_SECRET: "production-smoke-secret-32-characters",
      VITE_APP_ID: "production-smoke",
      OAUTH_SERVER_URL: "https://oauth.example.test",
      VITE_OAUTH_PORTAL_URL: "https://portal.example.test",
      DATABASE_URL: "mysql://unused:unused@127.0.0.1:3306/unused",
      ALEXA_ENABLED: "false",
      ALEXA_SKILL_ID: "",
      ALEXA_OAUTH_CLIENT_ID: "",
      ALEXA_OAUTH_CLIENT_SECRET: "",
      ALEXA_OAUTH_REDIRECT_URIS: "",
      VOICE_TOKEN_PEPPER: "",
      TRUST_PROXY: "false",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout.on("data", chunk => (output.text += chunk));
  child.stderr.on("data", chunk => (output.text += chunk));

  await waitForHealth(port, output);
  console.log(
    "test-production-install: production-only server reached /healthz."
  );
} finally {
  if (child && child.exitCode === null) {
    child.kill("SIGTERM");
    await new Promise(resolve => child.once("exit", resolve));
  }
  rmSync(sandbox, { recursive: true, force: true });
}
