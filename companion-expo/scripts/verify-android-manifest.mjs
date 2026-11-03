import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

function fail(message) {
  console.error(`Android manifest verification failed: ${message}`);
  process.exitCode = 1;
}

function prefixedPermission(permission) {
  return permission.includes(".") ? permission : `android.permission.${permission}`;
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

const blockedPermissions = (config.android?.blockedPermissions ?? []).map(prefixedPermission);
if (blockedPermissions.length === 0) {
  fail("android.blockedPermissions must declare at least one production permission.");
}

const manifestPath = path.resolve("android/app/src/main/AndroidManifest.xml");
if (!fs.existsSync(manifestPath)) {
  fail("generated AndroidManifest.xml is missing; run Expo prebuild before verification.");
} else {
  const manifest = fs.readFileSync(manifestPath, "utf8");
  const permissionTags = Array.from(manifest.matchAll(/<uses-permission\b[^>]*\/?\s*>/g), (match) => match[0]);

  if (!/xmlns:tools="http:\/\/schemas\.android\.com\/tools"/.test(manifest)) {
    fail("generated AndroidManifest.xml must declare the tools namespace for blocked permission markers.");
  }

  for (const permission of blockedPermissions) {
    const tags = permissionTags.filter((tag) => tag.includes(`android:name="${permission}"`));
    if (!tags.some((tag) => /tools:node="remove"/.test(tag))) {
      fail(`${permission} must be retained as a uses-permission tools:node=remove marker.`);
    }
    if (tags.some((tag) => !/tools:node="remove"/.test(tag))) {
      fail(`${permission} must not remain as an active uses-permission request.`);
    }
  }
}

if (!process.exitCode) {
  console.log("Generated Android manifest retains every Expo blocked-permission removal marker.");
}
