import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import { createConnection, type ConnectionOptions } from "mysql2/promise";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

const runDatabaseMigrationTests = process.env.RUN_DB_MIGRATION_TESTS === "1";
const drizzleDir = path.resolve(import.meta.dirname, "..", "drizzle");
const migrationTags = [
  "0000_flimsy_raza",
  "0001_shallow_shadow_king",
  "0002_sweet_rachel_grey",
] as const;

function connectionOptionsFromDatabaseUrl(value: string): ConnectionOptions {
  const url = new URL(value);
  if (url.protocol !== "mysql:")
    throw new Error("DATABASE_URL deve usare il protocollo mysql:");
  return {
    host: url.hostname,
    port: Number(url.port || 3306),
    user: decodeURIComponent(url.username),
    password: decodeURIComponent(url.password),
  };
}

function migrationStatements(tag: string) {
  return readFileSync(path.join(drizzleDir, `${tag}.sql`), "utf8")
    .split("--> statement-breakpoint")
    .map(statement => statement.trim())
    .filter(Boolean);
}

async function applyMigrations(
  connection: Awaited<ReturnType<typeof createConnection>>,
  from: number,
  through: number
) {
  for (const tag of migrationTags.slice(from, through + 1)) {
    for (const statement of migrationStatements(tag))
      await connection.query(statement);
  }
}

async function tableNames(
  connection: Awaited<ReturnType<typeof createConnection>>
) {
  const [rows] = await connection.query<Array<{ tableName: string }>>(
    "SELECT table_name AS tableName FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name"
  );
  return rows.map(row => row.tableName);
}

const describeDatabase = runDatabaseMigrationTests ? describe : describe.skip;

describeDatabase("migrazioni MariaDB reali", () => {
  let admin: Awaited<ReturnType<typeof createConnection>>;
  let rootOptions: ConnectionOptions;
  let emptyDatabase: string;
  let upgradeDatabase: string;

  beforeAll(async () => {
    const databaseUrl = process.env.DATABASE_URL;
    if (!databaseUrl)
      throw new Error("RUN_DB_MIGRATION_TESTS=1 richiede DATABASE_URL");
    rootOptions = connectionOptionsFromDatabaseUrl(databaseUrl);
    admin = await createConnection(rootOptions);
    emptyDatabase = `binario_migration_empty_${randomUUID().replaceAll("-", "")}`;
    upgradeDatabase = `binario_migration_upgrade_${randomUUID().replaceAll("-", "")}`;
    await admin.query(`CREATE DATABASE \`${emptyDatabase}\``);
    await admin.query(`CREATE DATABASE \`${upgradeDatabase}\``);
  });

  afterAll(async () => {
    if (!admin) return;
    await admin.query(`DROP DATABASE IF EXISTS \`${emptyDatabase}\``);
    await admin.query(`DROP DATABASE IF EXISTS \`${upgradeDatabase}\``);
    await admin.end();
  });

  it("applica 0000→0002 a un database vuoto", async () => {
    const connection = await createConnection({
      ...rootOptions,
      database: emptyDatabase,
    });
    try {
      await applyMigrations(connection, 0, 2);
      expect(await tableNames(connection)).toEqual([
        "alexa_account_links",
        "binario_profiles",
        "oauth_authorization_codes",
        "oauth_refresh_tokens",
        "railway_routes",
        "users",
      ]);
    } finally {
      await connection.end();
    }
  });

  it("aggiorna un database 0001 a 0002 senza rieseguire la storia", async () => {
    const connection = await createConnection({
      ...rootOptions,
      database: upgradeDatabase,
    });
    try {
      await applyMigrations(connection, 0, 1);
      expect(await tableNames(connection)).not.toContain("binario_profiles");
      await applyMigrations(connection, 2, 2);
      expect(await tableNames(connection)).toContain("binario_profiles");
      const [indexes] = await connection.query<Array<{ Key_name: string }>>(
        "SHOW INDEX FROM binario_profiles WHERE Key_name = 'binario_profiles_user_unique'"
      );
      expect(indexes).toHaveLength(1);
    } finally {
      await connection.end();
    }
  });
});
