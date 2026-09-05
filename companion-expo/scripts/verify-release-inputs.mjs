import fs from "node:fs";
import path from "node:path";

function read(relativePath) {
  return fs.readFileSync(path.resolve(relativePath), "utf8");
}

function fail(message) {
  console.error(`Release input verification failed: ${message}`);
  process.exitCode = 1;
}

function block(source, marker) {
  const start = source.indexOf(marker);
  if (start < 0) return null;
  const braceStart = source.indexOf("{", start);
  if (braceStart < 0) return null;
  let depth = 0;
  for (let index = braceStart; index < source.length; index += 1) {
    if (source[index] === "{") depth += 1;
    if (source[index] === "}") {
      depth -= 1;
      if (depth === 0) return source.slice(braceStart + 1, index);
    }
  }
  return null;
}

const gradle = read("android/app/build.gradle");
const releaseBlock = block(gradle, "release {");
if (!releaseBlock) {
  fail("Android release buildType is missing.");
} else if (/signingConfig\s+signingConfigs\.debug/.test(releaseBlock)) {
  fail("release build must never use signingConfigs.debug.");
}

const mainManifest = read("android/app/src/main/AndroidManifest.xml");
for (const permission of [
  "android.permission.READ_EXTERNAL_STORAGE",
  "android.permission.WRITE_EXTERNAL_STORAGE",
  "android.permission.SYSTEM_ALERT_WINDOW",
]) {
  if (mainManifest.includes(permission)) {
    fail(`${permission} must not be requested by the production manifest.`);
  }
}
if (!/android:allowBackup="false"/.test(mainManifest)) {
  fail("production manifest must disable Android backup.");
}
if (!/android:usesCleartextTraffic="false"/.test(mainManifest)) {
  fail("production manifest must disable cleartext traffic.");
}

const debugManifest = read("android/app/src/debug/AndroidManifest.xml");
if (!/android:usesCleartextTraffic="true"/.test(debugManifest)) {
  fail("debug cleartext override is expected to remain isolated in the debug manifest.");
}

const bridge = read("android/app/src/main/java/im/binario/companion/binario/BinarioWidgetBridge.kt");
if (!/class\s+BinarioWatchVoiceRequestService\s*:\s*WearableListenerService/.test(bridge)) {
  fail("manifest-declared BinarioWatchVoiceRequestService implementation is missing.");
}
if (!mainManifest.includes("BinarioWatchVoiceRequestService")) {
  fail("Wear request service is implemented but missing from the production manifest.");
}

if (!process.exitCode) {
  console.log("Companion release inputs pass security invariants.");
}
