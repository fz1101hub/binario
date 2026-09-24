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
const wearGradle = read("android/wear/build.gradle");
const releaseIdentity = JSON.parse(read("native/android/release-identity.json"));
const releaseIdentityKeys = [
  "applicationId",
  "phoneVersionCode",
  "schemaVersion",
  "versionName",
  "wearVersionCode",
];
if (
  JSON.stringify(Object.keys(releaseIdentity).sort()) !== JSON.stringify(releaseIdentityKeys) ||
  releaseIdentity.schemaVersion !== 1 ||
  releaseIdentity.applicationId !== "im.binario.companion" ||
  !/^\d+\.\d+\.\d+$/.test(releaseIdentity.versionName) ||
  !Number.isInteger(releaseIdentity.phoneVersionCode) ||
  !Number.isInteger(releaseIdentity.wearVersionCode) ||
  releaseIdentity.phoneVersionCode <= 0 ||
  releaseIdentity.wearVersionCode <= releaseIdentity.phoneVersionCode
) {
  fail("canonical Android release identity is invalid.");
}
for (const [label, source, versionCode] of [
  ["phone", gradle, releaseIdentity.phoneVersionCode],
  ["Wear", wearGradle, releaseIdentity.wearVersionCode],
]) {
  const applicationId = source.match(/applicationId\s+["']([^"']+)["']/)?.[1];
  if (applicationId !== releaseIdentity.applicationId) {
    fail(`${label} applicationId does not match the canonical release identity.`);
  }
  if (!source.includes(`versionCode ${versionCode}`)) {
    fail(`${label} versionCode does not match the canonical release identity.`);
  }
  if (!source.includes(`versionName "${releaseIdentity.versionName}"`)) {
    fail(`${label} versionName does not match the canonical release identity.`);
  }
}
const releaseBlock = block(gradle, "release {");
if (!releaseBlock) {
  fail("Android release buildType is missing.");
} else if (/signingConfig\s+signingConfigs\.debug/.test(releaseBlock)) {
  fail("release build must never use signingConfigs.debug.");
}

const wearReleaseBlock = block(wearGradle, "release {");
if (!wearReleaseBlock) {
  fail("Wear release buildType is missing.");
} else if (/signingConfig\s+signingConfigs\.debug/.test(wearReleaseBlock)) {
  fail("Wear release build must never use signingConfigs.debug.");
}

const gradleVerificationSource = read("native/android/verification-metadata.xml");
const gradleVerificationGenerated = read("android/gradle/verification-metadata.xml");
if (gradleVerificationGenerated !== gradleVerificationSource) {
  fail("generated Gradle verification metadata must match the reviewed source lock.");
}
if (!gradleVerificationSource.includes("<verify-metadata>true</verify-metadata>")) {
  fail("Gradle dependency metadata verification must remain enabled.");
}
if (gradleVerificationSource.includes("<trusted-artifacts>")) {
  fail("Gradle dependency verification must not trust artifact patterns.");
}
const gradleProperties = read("android/gradle.properties");
if (!gradleProperties.includes("org.gradle.parallel=false")) {
  fail("Gradle parallel project execution must remain disabled for deterministic release packaging.");
}
if (!gradleProperties.includes("org.gradle.workers.max=1")) {
  fail("Gradle release packaging must remain single-worker for deterministic App Bundle order.");
}
const wrapperProperties = read("android/gradle/wrapper/gradle-wrapper.properties");
if (
  !wrapperProperties.includes(
    "distributionSha256Sum=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"
  )
) {
  fail("The Companion Gradle Wrapper distribution must remain pinned to its reviewed SHA-256.");
}

const mainManifest = read("android/app/src/main/AndroidManifest.xml");
for (const permission of [
  "android.permission.ACCESS_FINE_LOCATION",
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

const wearManifest = read("android/wear/src/main/AndroidManifest.xml");
if (!/android:allowBackup="false"/.test(wearManifest)) {
  fail("Wear production manifest must disable Android backup.");
}
if (!/android:usesCleartextTraffic="false"/.test(wearManifest)) {
  fail("Wear production manifest must disable cleartext traffic.");
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
