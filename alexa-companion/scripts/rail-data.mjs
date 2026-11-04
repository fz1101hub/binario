import { createHash } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Rail data the skill answers from, copied into the server bundle at build time.
 *
 * The weekly punctuality statistics and the curated station catalogue live in the Android assets
 * (`app/src/main/assets/<file>`); `copy` puts them in `dist/data/<file>`, where the production
 * server reads them (server/punctuality/source.ts uses the same relative names), and removes a
 * copy whose source is gone. `verify` fails when a copy differs from its repository file, or
 * exists without one. The statistics may be absent: the skill then says they are not available.
 *
 * Usage: node scripts/rail-data.mjs copy|verify [--repo <checkout>] [--dist <dist>]
 */

export const RAIL_DATA_FILES = ["punctuality/stats_v1.json.gz", "stations/curated_it_rail_stations.json"];

const projectRoot = path.resolve(import.meta.dirname, "..");
const defaults = { repo: path.resolve(projectRoot, ".."), dist: path.join(projectRoot, "dist") };

function locations({ repo, dist }, file) {
  return {
    source: path.join(repo, "app", "src", "main", "assets", file),
    copy: path.join(dist, "data", file),
  };
}

function sha256(file) {
  return createHash("sha256").update(readFileSync(file)).digest("hex");
}

/** Copies every present rail data file into dist/data and removes stale copies. */
export function copyRailData(options = defaults) {
  const copied = [];
  for (const file of RAIL_DATA_FILES) {
    const { source, copy } = locations(options, file);
    if (existsSync(source)) {
      mkdirSync(path.dirname(copy), { recursive: true });
      copyFileSync(source, copy);
      copied.push(file);
    } else rmSync(copy, { force: true });
  }
  return copied;
}

/** Problems of dist/data against the repository files; empty when every copy matches. */
export function verifyRailData(options = defaults) {
  const problems = [];
  for (const file of RAIL_DATA_FILES) {
    const { source, copy } = locations(options, file);
    if (!existsSync(source)) {
      if (existsSync(copy)) problems.push(`dist/data/${file} exists but the repository has no ${file}`);
    } else if (!existsSync(copy)) problems.push(`dist/data/${file} is missing: run the build again`);
    else if (sha256(copy) !== sha256(source)) problems.push(`dist/data/${file} differs from the repository file`);
  }
  return problems;
}

function parseArguments(argv) {
  const [command, ...rest] = argv;
  const options = { ...defaults };
  for (let index = 0; index < rest.length; index += 2) {
    const name = rest[index];
    const value = rest[index + 1];
    if ((name !== "--repo" && name !== "--dist") || !value) throw new Error(`unknown argument ${name}`);
    options[name.slice(2)] = path.resolve(value);
  }
  return { command, options };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { command, options } = parseArguments(process.argv.slice(2));
  if (command === "copy") {
    const copied = copyRailData(options);
    const absent = RAIL_DATA_FILES.filter(file => !copied.includes(file));
    console.log(
      `rail-data: copied ${copied.length ? copied.join(", ") : "nothing"}` +
        (absent.length ? `; absent in the repository: ${absent.join(", ")}` : "")
    );
  } else if (command === "verify") {
    const problems = verifyRailData(options);
    if (problems.length) {
      console.error(`rail-data: the bundled rail data does not match the repository:\n  ${problems.join("\n  ")}`);
      process.exit(1);
    }
    console.log("rail-data: bundled rail data matches the repository.");
  } else {
    console.error("usage: node scripts/rail-data.mjs copy|verify [--repo <checkout>] [--dist <dist>]");
    process.exit(2);
  }
}
