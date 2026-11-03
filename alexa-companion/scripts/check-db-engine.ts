// Run by `pnpm db:migrate` before drizzle-kit: refuses an engine the migrations cannot run on.
import "dotenv/config";
import { createConnection } from "mysql2/promise";
import { databaseEngineProblem } from "../server/_core/dbEngine";

const url = process.env.DATABASE_URL;
if (!url) {
  console.error("check-db-engine: DATABASE_URL non configurato");
  process.exit(1);
}
const connection = await createConnection(url);
try {
  const [rows] = await connection.query("SELECT VERSION() AS version");
  const version = String((rows as Array<{ version?: unknown }>)[0]?.version ?? "");
  const problem = databaseEngineProblem(version);
  if (problem) {
    // An operator who has verified an already migrated MySQL database may proceed deliberately.
    if (process.env.ALLOW_UNSUPPORTED_DATABASE_ENGINE === "1") console.warn(`check-db-engine: ${problem}`);
    else {
      console.error(`check-db-engine: ${problem}`);
      process.exitCode = 1;
    }
  } else console.log(`check-db-engine: ${version}`);
} finally {
  await connection.end();
}
