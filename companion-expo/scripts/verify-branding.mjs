import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

function fail(message) {
  console.error(`Companion branding verification failed: ${message}`);
  process.exitCode = 1;
}

function read(relativePath) {
  return fs.readFileSync(path.resolve(relativePath), "utf8");
}

function sha256(relativePath) {
  return crypto.createHash("sha256").update(fs.readFileSync(path.resolve(relativePath))).digest("hex");
}

function assertFile(relativePath) {
  if (!fs.existsSync(path.resolve(relativePath))) fail(`${relativePath} is missing.`);
}

const config = read("app.config.ts");
const manifest = read("android/app/src/main/AndroidManifest.xml");
const strings = read("android/app/src/main/res/values/strings.xml");
const colors = read("android/app/src/main/res/values/colors.xml");
const sourceMark = read("../app/src/main/res/drawable/ic_binario.xml");

const sourceMarkInvariants = [
  'android:fillColor="#005A52"',
  'android:fillColor="#FFFFFF"',
  'android:viewportWidth="48"',
  'android:viewportHeight="48"',
  'M12,8c-2.2,0 -4,1.8 -4,4v20',
];
for (const invariant of sourceMarkInvariants) {
  if (!sourceMark.includes(invariant)) fail(`primary Binario mark no longer contains ${invariant}.`);
}

for (const relativePath of [
  "assets/brand/binario-companion-icon.png",
  "assets/brand/binario-companion-adaptive-foreground.png",
  "assets/brand/binario-companion-splash.png",
  "scripts/generate-binario-brand-assets.py",
]) assertFile(relativePath);

for (const expected of [
  'name: "Binario Companion"',
  'icon: "./assets/brand/binario-companion-icon.png"',
  'adaptiveIcon: {',
  'foregroundImage: "./assets/brand/binario-companion-adaptive-foreground.png"',
  'backgroundColor: "#005A52"',
  'splash: {',
  'image: "./assets/brand/binario-companion-splash.png"',
  'android: {',
  'blockedPermissions: [',
  '"android.permission.ACCESS_FINE_LOCATION"',
]) {
  if (!config.includes(expected)) fail(`app.config.ts must contain ${expected}.`);
}
if (config.includes('name: "Binario",')) fail("the companion label must remain distinct from the primary Binario app.");
if (/\["expo-location"\s*,\s*\{[^}]*locationWhenInUsePermission/.test(config)) {
  fail("expo-location must not configure a fine-location rationale.");
}

if (!/<string name="app_name">Binario Companion<\/string>/.test(strings)) {
  fail("Android app label must be Binario Companion.");
}
if (!/<color name="splashscreen_background">#005A52<\/color>/.test(colors)) {
  fail("Android splash background must use Binario teal.");
}
if (!/android:label="@string\/app_name"/.test(manifest)) {
  fail("generated Android manifest must use the companion string label.");
}
if (manifest.includes("android.permission.ACCESS_FINE_LOCATION")) {
  fail("generated Android manifest must not request ACCESS_FINE_LOCATION.");
}
if (!manifest.includes("android.permission.ACCESS_COARSE_LOCATION")) {
  fail("generated Android manifest must retain ACCESS_COARSE_LOCATION for nearest-station lookup.");
}

const templateHashes = new Set([
  "f84b9b17a4a1c7d7dd840cd272c07a6c3fd66d5a9826b64bb53facb6f176bc24",
  "721c895579cb08ec4c9a0fe7dadafe9a97c33ef758d3578ad5e8b540de59f974",
  "e1c5250e339add5ed7849cb0859d052edb9781abb91db049e68ae3a4059247d7",
  "23d97ccb41a821ee345b2c5bb0c9bdab730a751a9227925acbf0af021dc38a9b",
  "be9fac5c4cda48c41dce592288c486524bba3e4ca7fa00c0a04a46928154b5bf",
  "eef20f25fb1477d8c9df15757e764811cc503fb0777f18d0f7fb2d19178b5bf6",
  "ded7aabf6a56b694e486e096efd89e2f0c9067d292b634663898e352c9491f10",
  "e77c5045bfdb6f4bbe955b3a793bfed3baa369ce4811ae2a9103d5480637cfff",
  "eb3a34b13632e0cb3b1c0f4273035866cbe81b1b17b7178ce29d19c78d394a5e",
  "9ff27328b6916b7f75b99ef36153f154bb4724946e5654635cba2e257352c69f",
  "520d05f978a15ba0ccf23006a1a5691a054a02478c70cafc5ebafae76e600f0d",
  "21304a0c9b00da6a72cfa31c7229c9528fc17b6e5eb4e68a969bce08c01a2fee",
  "2846e1a703e519791fe22f41bcb243b82f908d12e5ebcb43f020ddf9982780b0",
  "363a569beb72e8b007bf046454612148d4c9f782b9391352059fe83179f18e30",
  "5bacd97a1b41e4413c6092cdbd83f65112a9124e823cfaa3a219c65e2761647b",
]);

const generatedAssets = [
  "assets/brand/binario-companion-icon.png",
  "assets/brand/binario-companion-adaptive-foreground.png",
  "assets/brand/binario-companion-splash.png",
];
for (const relativePath of generatedAssets) {
  if (templateHashes.has(sha256(relativePath))) fail(`${relativePath} matches a retired Expo template asset.`);
}

const nativeAssets = [
  ...["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"].flatMap((density) => [
    `android/app/src/main/res/mipmap-${density}/ic_launcher.webp`,
    `android/app/src/main/res/mipmap-${density}/ic_launcher_round.webp`,
    `android/app/src/main/res/drawable-${density}/splashscreen_logo.png`,
  ]),
];
for (const relativePath of nativeAssets) {
  assertFile(relativePath);
  if (templateHashes.has(sha256(relativePath))) fail(`${relativePath} matches a retired Expo template asset.`);
}

if (!process.exitCode) {
  console.log("Companion branding, generated manifest and location-permission invariants pass.");
}
