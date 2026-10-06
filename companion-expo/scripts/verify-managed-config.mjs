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
  "android.permission.ACCESS_FINE_LOCATION",
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
if (!explicitPermissions.has("ACCESS_COARSE_LOCATION")) {
  fail("ACCESS_COARSE_LOCATION must remain explicit for the on-demand nearest-station lookup.");
}
if (!explicitPermissions.has("POST_NOTIFICATIONS")) {
  fail("POST_NOTIFICATIONS must remain an explicit Android permission.");
}

const plugins = config.plugins ?? [];
const hasLocation = plugins.some((plugin) =>
  Array.isArray(plugin) ? plugin[0] === "expo-location" : plugin === "expo-location",
);
if (hasLocation) {
  fail("expo-location must not be explicitly configured: the companion only needs approximate location and blocks fine location.");
}

const pluginNames = plugins.map((plugin) => (Array.isArray(plugin) ? plugin[0] : plugin));
for (const required of ["./plugins/with-binario-wear-manifest.cjs", "./plugins/with-binario-android-widget.cjs"]) {
  if (!pluginNames.includes(required)) {
    fail(`${required} must stay registered: it generates the shared release signing config and the Wear hardening.`);
  }
}
if (pluginNames.indexOf("./plugins/with-binario-wear-manifest.cjs") > pluginNames.indexOf("./plugins/with-binario-android-widget.cjs")) {
  fail("with-binario-wear-manifest.cjs must run before with-binario-android-widget.cjs so the debug keystore block is removed before release signing is inserted.");
}

if (!process.exitCode) {
  console.log("Managed Expo config is hardened and internally consistent.");
}
