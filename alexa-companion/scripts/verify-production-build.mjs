import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { verifyRailData } from "./rail-data.mjs";

// Fails the build when development tooling leaks into the production bundle.
// The Manus preview runtime accepts cross-origin postMessage and can set the
// session cookie, so its presence in dist/ is a security regression.
const dist = path.resolve(import.meta.dirname, "..", "dist");
const root = path.join(dist, "public");
const forbidden = [
  "manus-runtime",
  "__MANUS_HOST_DEV__",
  "manus-cookie",
  "debug-collector",
  "%VITE_",
  "SpacePreviewerChannel",
  '"setCookie"',
];

function walk(dir) {
  return readdirSync(dir).flatMap(entry => {
    const full = path.join(dir, entry);
    return statSync(full).isDirectory() ? walk(full) : [full];
  });
}

if (!existsSync(root)) {
  console.error(
    `verify-production-build: ${root} does not exist; run vite build first.`
  );
  process.exit(1);
}
if (existsSync(path.join(root, "__manus__"))) {
  console.error(
    "verify-production-build: dist/public/__manus__ must not be published."
  );
  process.exit(1);
}
const problems = [];
for (const file of walk(root)) {
  if (!/\.(html|js|css)$/.test(file)) continue;
  const text = readFileSync(file, "utf8");
  for (const marker of forbidden)
    if (text.includes(marker))
      problems.push(`${path.relative(root, file)}: contains ${marker}`);
}
if (problems.length) {
  console.error(
    "verify-production-build: development tooling leaked into the production bundle:\n  " +
      problems.join("\n  ")
  );
  process.exit(1);
}

// skill.json links these icons (smallIconUri, largeIconUri) under the public host.
for (const icon of ["alexa/icon-108.png", "alexa/icon-512.png"]) {
  if (!existsSync(path.join(root, icon))) {
    console.error(`verify-production-build: ${icon} is missing from dist/public.`);
    process.exit(1);
  }
}

const serverBundle = path.join(dist, "index.js");
if (!existsSync(serverBundle)) {
  console.error(
    `verify-production-build: ${serverBundle} does not exist; build the server first.`
  );
  process.exit(1);
}
const serverText = readFileSync(serverBundle, "utf8");
if (
  /from\s*["']vite["']|import\s*\(\s*["']vite["']\s*\)|vite\.config/.test(
    serverText
  )
) {
  console.error(
    "verify-production-build: Vite leaked into the production server bundle."
  );
  process.exit(1);
}

// The punctuality statistics and the station catalogue the skill reads from dist/data must be
// the repository's files (or absent with them): a stale copy would answer from old data.
const railDataProblems = verifyRailData();
if (railDataProblems.length) {
  console.error(
    "verify-production-build: the bundled rail data does not match the repository:\n  " +
      railDataProblems.join("\n  ")
  );
  process.exit(1);
}
console.log("verify-production-build: production bundle is clean.");
