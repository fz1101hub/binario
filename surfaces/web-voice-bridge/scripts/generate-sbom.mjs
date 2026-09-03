import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const projectRoot = resolve(import.meta.dirname, "..");
const packageJson = JSON.parse(readFileSync(resolve(projectRoot, "package.json"), "utf8"));
const dependencyTree = JSON.parse(execFileSync("pnpm", ["list", "--prod", "--json", "--depth", "Infinity"], {
  cwd: projectRoot,
  encoding: "utf8",
  maxBuffer: 10 * 1024 * 1024,
}));

const components = new Map();
function visit(node, dependencyName) {
  if (!node || typeof node !== "object") return;
  const name = node.name ?? node.from ?? dependencyName;
  if (name && node.version) {
    const key = `${name}@${node.version}`;
    components.set(key, {
      type: "library",
      name,
      version: node.version,
      purl: `pkg:npm/${encodeURIComponent(name).replace("%40", "@")}@${node.version}`,
    });
  }
  for (const [childName, dependency] of Object.entries(node.dependencies ?? {})) visit(dependency, childName);
}

for (const root of dependencyTree) visit(root);

const bom = {
  bomFormat: "CycloneDX",
  specVersion: "1.6",
  serialNumber: `urn:uuid:${crypto.randomUUID()}`,
  version: 1,
  metadata: {
    timestamp: new Date().toISOString(),
    tools: [{ vendor: "Binario", name: "generate-sbom.mjs" }],
    component: {
      type: "application",
      name: packageJson.name,
      version: packageJson.version,
      purl: `pkg:npm/${packageJson.name}@${packageJson.version}`,
    },
  },
  components: [...components.values()].sort((left, right) => left.purl.localeCompare(right.purl)),
};

writeFileSync(resolve(projectRoot, "docs", "sbom.cdx.json"), `${JSON.stringify(bom, null, 2)}\n`);
console.log(`SBOM creato: ${bom.components.length} componenti di produzione.`);
