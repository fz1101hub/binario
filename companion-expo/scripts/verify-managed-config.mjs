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
// Expo applies expo-location even when it is not listed, with English default purpose strings
// and the iOS "Always" keys. It is listed to replace them; fine location stays blocked above.
const location = plugins.find((plugin) => (Array.isArray(plugin) ? plugin[0] : plugin) === "expo-location");
const locationProps = Array.isArray(location) ? location[1] ?? {} : {};
if (!location) {
  fail("expo-location must be configured explicitly with a specific purpose string and without the Always keys.");
}
if (locationProps.locationAlwaysPermission !== false || locationProps.locationAlwaysAndWhenInUsePermission !== false) {
  fail("expo-location must delete the iOS Always purpose strings: the nearest-station lookup is foreground only.");
}
const whenInUse = locationProps.locationWhenInUsePermission;
if (typeof whenInUse !== "string" || whenInUse.length < 40 || whenInUse.includes("$(PRODUCT_NAME)")) {
  fail("expo-location needs a specific when-in-use purpose string instead of the Expo default.");
}
for (const flag of ["isIosBackgroundLocationEnabled", "isAndroidBackgroundLocationEnabled", "isAndroidForegroundServiceEnabled"]) {
  if (locationProps[flag]) fail(`expo-location must not enable ${flag}.`);
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
