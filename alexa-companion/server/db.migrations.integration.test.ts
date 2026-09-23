import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import { createConnection, type ConnectionOptions } from "mysql2/promise";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  createAuthorizationCode,
  exchangeAuthorizationCode,
  getDb,
  listPersonalRoutesForAlexa,
  revokeAllVoiceCredentialsForUser,
  rotateRefreshToken,
} from "./db";

const runDatabaseMigrationTests = process.env.RUN_DB_MIGRATION_TESTS === "1";
const drizzleDir = path.resolve(import.meta.dirname, "..", "drizzle");
const migrationTags = [
  "0000_flimsy_raza",
  "0001_shallow_shadow_king",
  "0002_sweet_rachel_grey",
  "0003_light_tattoo",
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
  let oauthDatabase: string;
  let originalDatabaseUrl: string;

  beforeAll(async () => {
    const databaseUrl = process.env.DATABASE_URL;
    if (!databaseUrl)
      throw new Error("RUN_DB_MIGRATION_TESTS=1 richiede DATABASE_URL");
    rootOptions = connectionOptionsFromDatabaseUrl(databaseUrl);
    originalDatabaseUrl = databaseUrl;
    admin = await createConnection(rootOptions);
    emptyDatabase = `binario_migration_empty_${randomUUID().replaceAll("-", "")}`;
    upgradeDatabase = `binario_migration_upgrade_${randomUUID().replaceAll("-", "")}`;
    oauthDatabase = `binario_oauth_revocation_${randomUUID().replaceAll("-", "")}`;
    await admin.query(`CREATE DATABASE \`${emptyDatabase}\``);
    await admin.query(`CREATE DATABASE \`${upgradeDatabase}\``);
    await admin.query(`CREATE DATABASE \`${oauthDatabase}\``);
  });

  afterAll(async () => {
    if (!admin) return;
    await admin.query(`DROP DATABASE IF EXISTS \`${emptyDatabase}\``);
    await admin.query(`DROP DATABASE IF EXISTS \`${upgradeDatabase}\``);
    await admin.query(`DROP DATABASE IF EXISTS \`${oauthDatabase}\``);
    await admin.end();
    process.env.DATABASE_URL = originalDatabaseUrl;
  });

  it("applica 0000→0003 a un database vuoto", async () => {
    const connection = await createConnection({
      ...rootOptions,
      database: emptyDatabase,
    });
    try {
      await applyMigrations(connection, 0, 3);
      expect(await tableNames(connection)).toEqual([
        "alexa_account_links",
        "binario_profiles",
        "oauth_authorization_codes",
        "oauth_refresh_tokens",
        "railway_routes",
        "users",
      ]);
      const [columns] = await connection.query<Array<{ Field: string }>>(
        "SHOW COLUMNS FROM oauth_authorization_codes WHERE Field = 'revokedAt'"
      );
      expect(columns).toHaveLength(1);
      const [linkColumns] = await connection.query<Array<{ Field: string }>>(
        "SHOW COLUMNS FROM alexa_account_links WHERE Field = 'credentialVersion'"
      );
      expect(linkColumns).toHaveLength(1);
    } finally {
      await connection.end();
    }
  });

  it("aggiorna un database 0002 a 0003 senza rieseguire la storia", async () => {
    const connection = await createConnection({
      ...rootOptions,
      database: upgradeDatabase,
    });
    try {
      await applyMigrations(connection, 0, 2);
      expect(await tableNames(connection)).toContain("binario_profiles");
      const [indexes] = await connection.query<Array<{ Key_name: string }>>(
        "SHOW INDEX FROM binario_profiles WHERE Key_name = 'binario_profiles_user_unique'"
      );
      expect(indexes).toHaveLength(1);
      const [before] = await connection.query<Array<{ Field: string }>>(
        "SHOW COLUMNS FROM oauth_authorization_codes WHERE Field = 'revokedAt'"
      );
      expect(before).toHaveLength(0);
      const [linkBefore] = await connection.query<Array<{ Field: string }>>(
        "SHOW COLUMNS FROM alexa_account_links WHERE Field = 'credentialVersion'"
      );
      expect(linkBefore).toHaveLength(0);
      await applyMigrations(connection, 3, 3);
      const [after] = await connection.query<Array<{ Field: string }>>(
        "SHOW COLUMNS FROM oauth_authorization_codes WHERE Field = 'revokedAt'"
      );
      expect(after).toHaveLength(1);
      const [linkAfter] = await connection.query<Array<{ Field: string }>>(
        "SHOW COLUMNS FROM alexa_account_links WHERE Field = 'credentialVersion'"
      );
      expect(linkAfter).toHaveLength(1);
    } finally {
      await connection.end();
    }
  });

  it("unlink revoca code pendenti e impedisce la rotazione di refresh token", async () => {
    const connection = await createConnection({
      ...rootOptions,
      database: oauthDatabase,
    });
    try {
      await applyMigrations(connection, 0, 3);
      const [insert] = await connection.query(
        "INSERT INTO users (`openId`, `lastSignedIn`) VALUES (?, CURRENT_TIMESTAMP)",
        ["oauth-revocation-user"]
      );
      const userId = Number((insert as { insertId: number }).insertId);
      const databaseUrl = new URL(originalDatabaseUrl);
      databaseUrl.pathname = `/${oauthDatabase}`;
      process.env.DATABASE_URL = databaseUrl.toString();

      await createAuthorizationCode({
        userId,
        codeHash: "a".repeat(64),
        clientId: "alexa-client",
        redirectUri: "https://layla.amazon.com/api/skill/link/TEST",
        scope: "routes.read",
        expiresAt: new Date(Date.now() + 60_000),
      });
      await revokeAllVoiceCredentialsForUser(userId);
      await expect(
        exchangeAuthorizationCode({
          codeHash: "a".repeat(64),
          clientId: "alexa-client",
          redirectUri: "https://layla.amazon.com/api/skill/link/TEST",
          refreshTokenHash: "b".repeat(64),
          refreshTokenExpiresAt: new Date(Date.now() + 60_000),
        })
      ).resolves.toBeNull();

      await createAuthorizationCode({
        userId,
        codeHash: "c".repeat(64),
        clientId: "alexa-client",
        redirectUri: "https://layla.amazon.com/api/skill/link/TEST",
        scope: "routes.read",
        expiresAt: new Date(Date.now() + 60_000),
      });
      const initialLink = await exchangeAuthorizationCode({
        codeHash: "c".repeat(64),
        clientId: "alexa-client",
        redirectUri: "https://layla.amazon.com/api/skill/link/TEST",
        refreshTokenHash: "d".repeat(64),
        refreshTokenExpiresAt: new Date(Date.now() + 60_000),
      });
      expect(initialLink).toMatchObject({ userId, credentialVersion: 1 });
      await connection.query(
        "INSERT INTO railway_routes (`userId`, `origin`, `destination`, `name`) VALUES (?, 'Roma', 'Milano', 'Casa — lavoro')",
        [userId]
      );
      await expect(
        listPersonalRoutesForAlexa(userId, initialLink?.credentialVersion ?? 0)
      ).resolves.toHaveLength(1);

      await createAuthorizationCode({
        userId,
        codeHash: "7".repeat(64),
        clientId: "alexa-client",
        redirectUri: "https://layla.amazon.com/api/skill/link/TEST",
        scope: "routes.read",
        expiresAt: new Date(Date.now() + 60_000),
      });
      const secondLink = await exchangeAuthorizationCode({
        codeHash: "7".repeat(64),
        clientId: "alexa-client",
        redirectUri: "https://layla.amazon.com/api/skill/link/TEST",
        refreshTokenHash: "6".repeat(64),
        refreshTokenExpiresAt: new Date(Date.now() + 60_000),
      });
      expect(secondLink?.credentialVersion).toBeGreaterThan(
        initialLink?.credentialVersion ?? 0
      );
      await expect(
        rotateRefreshToken({
          currentTokenHash: "d".repeat(64),
          clientId: "alexa-client",
          nextTokenHash: "5".repeat(64),
          nextTokenExpiresAt: new Date(Date.now() + 60_000),
        })
      ).resolves.toBeNull();

      await revokeAllVoiceCredentialsForUser(userId);
      await expect(
        listPersonalRoutesForAlexa(userId, initialLink?.credentialVersion ?? 0)
      ).resolves.toEqual([]);
      await expect(
        rotateRefreshToken({
          currentTokenHash: "6".repeat(64),
          clientId: "alexa-client",
          nextTokenHash: "e".repeat(64),
          nextTokenExpiresAt: new Date(Date.now() + 60_000),
        })
      ).resolves.toBeNull();

      await createAuthorizationCode({
        userId,
        codeHash: "9".repeat(64),
        clientId: "alexa-client",
        redirectUri: "https://layla.amazon.com/api/skill/link/TEST",
        scope: "routes.read",
        expiresAt: new Date(Date.now() + 60_000),
      });
      const relinked = await exchangeAuthorizationCode({
        codeHash: "9".repeat(64),
        clientId: "alexa-client",
        redirectUri: "https://layla.amazon.com/api/skill/link/TEST",
        refreshTokenHash: "8".repeat(64),
        refreshTokenExpiresAt: new Date(Date.now() + 60_000),
      });
      expect(relinked?.credentialVersion).toBeGreaterThan(
        initialLink?.credentialVersion ?? 0
      );
      await expect(
        listPersonalRoutesForAlexa(userId, initialLink?.credentialVersion ?? 0)
      ).resolves.toEqual([]);
      await expect(
        listPersonalRoutesForAlexa(userId, relinked?.credentialVersion ?? 0)
      ).resolves.toHaveLength(1);
      await revokeAllVoiceCredentialsForUser(userId);

      await createAuthorizationCode({
        userId,
        codeHash: "f".repeat(64),
        clientId: "alexa-client",
        redirectUri: "https://layla.amazon.com/api/skill/link/TEST",
        scope: "routes.read",
        expiresAt: new Date(Date.now() + 60_000),
      });
      await Promise.all([
        exchangeAuthorizationCode({
          codeHash: "f".repeat(64),
          clientId: "alexa-client",
          redirectUri: "https://layla.amazon.com/api/skill/link/TEST",
          refreshTokenHash: "0".repeat(64),
          refreshTokenExpiresAt: new Date(Date.now() + 60_000),
        }),
        revokeAllVoiceCredentialsForUser(userId),
      ]);
      const [activeRefresh] = await connection.query<Array<{ count: number }>>(
        "SELECT COUNT(*) AS count FROM oauth_refresh_tokens WHERE userId = ? AND revokedAt IS NULL",
        [userId]
      );
      expect(Number(activeRefresh[0]?.count)).toBe(0);
      const [links] = await connection.query<Array<{ revokedAt: Date | null }>>(
        "SELECT revokedAt FROM alexa_account_links WHERE userId = ?",
        [userId]
      );
      expect(links[0]?.revokedAt).not.toBeNull();
    } finally {
      const db = await getDb();
      await db?.$client.end();
      await connection.end();
    }
  });
});
