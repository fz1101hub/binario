import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

// Fails the build when development tooling leaks into the production bundle.
// The Manus preview runtime accepts cross-origin postMessage and can set the
// session cookie, so its presence in dist/ is a security regression.
const root = path.resolve(import.meta.dirname, "..", "dist", "public");
const forbidden = ["manus-runtime", "__MANUS_HOST_DEV__", "manus-cookie", "debug-collector", "%VITE_", "SpacePreviewerChannel", '"setCookie"'];

function walk(dir) {
  return readdirSync(dir).flatMap(entry => {
    const full = path.join(dir, entry);
    return statSync(full).isDirectory() ? walk(full) : [full];
  });
}

if (!existsSync(root)) {
  console.error(`verify-production-build: ${root} does not exist; run vite build first.`);
  process.exit(1);
}
if (existsSync(path.join(root, "__manus__"))) {
  console.error("verify-production-build: dist/public/__manus__ must not be published.");
  process.exit(1);
}
const problems = [];
for (const file of walk(root)) {
  if (!/\.(html|js|css)$/.test(file)) continue;
  const text = readFileSync(file, "utf8");
  for (const marker of forbidden) if (text.includes(marker)) problems.push(`${path.relative(root, file)}: contains ${marker}`);
}
if (problems.length) {
  console.error("verify-production-build: development tooling leaked into the production bundle:\n  " + problems.join("\n  "));
  process.exit(1);
}
console.log("verify-production-build: production bundle is clean.");
