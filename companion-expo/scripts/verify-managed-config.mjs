import { execFileSync } from "node:child_process";
import path from "node:path";

function fail(message) {
  console.error(`Managed config verification failed: ${message}`);
  process.exitCode = 1;
}

const expoBin = path.resolve(
  "node_modules",
  ".bin",
  process.platform === "win32" ? "expo.cmd" : "expo",
);

let config;
try {
  const stdout = execFileSync(expoBin, ["config", "--type", "public", "--json"], {
    cwd: process.cwd(),
    encoding: "utf8",
    stdio: ["ignore", "pipe", "inherit"],
  });
  config = JSON.parse(stdout);
} catch (error) {
  console.error("Unable to resolve Expo managed config.", error);
  process.exit(1);
}

const android = config.android ?? {};
const blocked = new Set(android.blockedPermissions ?? []);
const requiredBlocked = [
  "android.permission.READ_EXTERNAL_STORAGE",
  "android.permission.WRITE_EXTERNAL_STORAGE",
];

if (android.allowBackup !== false) {
  fail("android.allowBackup must be false.");
}
for (const permission of requiredBlocked) {
  if (!blocked.has(permission)) fail(`${permission} must be blocked.`);
}

const explicitPermissions = new Set(android.permissions ?? []);
if (!explicitPermissions.has("POST_NOTIFICATIONS")) {
  fail("POST_NOTIFICATIONS must remain an explicit Android permission.");
}

const plugins = config.plugins ?? [];
const hasLocation = plugins.some((plugin) =>
  Array.isArray(plugin) ? plugin[0] === "expo-location" : plugin === "expo-location",
);
if (!hasLocation) {
  fail("expo-location must remain explicitly configured with its user-facing rationale.");
}

if (!process.exitCode) {
  console.log("Managed Expo config is hardened and internally consistent.");
}
