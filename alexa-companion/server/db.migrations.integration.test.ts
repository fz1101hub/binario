import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import { createConnection, type ConnectionOptions } from "mysql2/promise";
import { drizzle } from "drizzle-orm/mysql2";
import { migrate } from "drizzle-orm/mysql2/migrator";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  createAuthorizationCode,
  deleteUserAccount,
  exchangeAuthorizationCode,
  getDb,
  listPersonalRoutesForAlexa,
  revokeAllVoiceCredentialsForUser,
  rotateRefreshToken,
  touchExistingUserSession,
} from "./db";

const runDatabaseMigrationTests = process.env.RUN_DB_MIGRATION_TESTS === "1";
const drizzleDir = path.resolve(import.meta.dirname, "..", "drizzle");
const migrationTags = [
  "0000_flimsy_raza",
  "0001_shallow_shadow_king",
  "0002_sweet_rachel_grey",
  "0003_rich_jimmy_woo",
  "0004_clean_argent",
  "0005_abandoned_kate_bishop",
  "0006_unusual_husk",
  "0007_next_bloodstrike",
  "0008_white_sandman",
  "0009_fancy_thunderbolts",
  "0010_military_sentinels",
  "0011_volatile_risque",
  "0012_alexa_credential_revocation_adoption",
  "0013_complete_shiva",
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
  let shortLineageDatabase: string;
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
    shortLineageDatabase = `binario_migration_short_${randomUUID().replaceAll("-", "")}`;
    oauthDatabase = `binario_oauth_revocation_${randomUUID().replaceAll("-", "")}`;
    await admin.query(`CREATE DATABASE \`${emptyDatabase}\``);
    await admin.query(`CREATE DATABASE \`${upgradeDatabase}\``);
    await admin.query(`CREATE DATABASE \`${shortLineageDatabase}\``);
    await admin.query(`CREATE DATABASE \`${oauthDatabase}\``);
  });

  afterAll(async () => {
    if (!admin) return;
    await admin.query(`DROP DATABASE IF EXISTS \`${emptyDatabase}\``);
    await admin.query(`DROP DATABASE IF EXISTS \`${upgradeDatabase}\``);
    await admin.query(`DROP DATABASE IF EXISTS \`${shortLineageDatabase}\``);
    await admin.query(`DROP DATABASE IF EXISTS \`${oauthDatabase}\``);
    await admin.end();
    process.env.DATABASE_URL = originalDatabaseUrl;
  });

  it("applica 0000→0013 a un database vuoto", async () => {
    const connection = await createConnection({
      ...rootOptions,
      database: emptyDatabase,
    });
    try {
      await applyMigrations(connection, 0, 13);
      expect(await tableNames(connection)).toEqual([
        "alexa_account_links",
        "binario_profiles",
        "companion_devices",
        "companion_pairing_codes",
        "oauth_authorization_codes",
        "oauth_refresh_tokens",
        "railway_routes",
        "station_preferences",
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
      const [familyColumns] = await connection.query<Array<{ Field: string }>>(
        "SHOW COLUMNS FROM oauth_refresh_tokens WHERE Field = 'familyId'"
      );
      expect(familyColumns).toHaveLength(1);
    } finally {
      await connection.end();
    }
  });

  it("adotta un database storico 0000→0011 applicando 0012 e 0013", async () => {
    const connection = await createConnection({
      ...rootOptions,
      database: upgradeDatabase,
    });
    try {
      await applyMigrations(connection, 0, 11);
      const [historicalUser] = await connection.query(
        "INSERT INTO users (`openId`, `lastSignedIn`) VALUES ('historical-user', CURRENT_TIMESTAMP)"
      );
      const historicalUserId = Number(
        (historicalUser as { insertId: number }).insertId
      );
      await connection.query(
        "INSERT INTO station_preferences (`userId`, `stationCode`, `stationName`) VALUES (?, '8308409', 'Roma Termini')",
        [historicalUserId]
      );
      await connection.query(
        "INSERT INTO oauth_refresh_tokens (`userId`, `tokenHash`, `clientId`, `expiresAt`, `revokedAt`) VALUES (?, ?, 'legacy-client', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 DAY), CURRENT_TIMESTAMP), (?, ?, 'legacy-client', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 DAY), NULL)",
        [historicalUserId, "a".repeat(64), historicalUserId, "b".repeat(64)]
      );
      const [before] = await connection.query<Array<{ Field: string }>>(
        "SHOW COLUMNS FROM oauth_authorization_codes WHERE Field = 'revokedAt'"
      );
      expect(before).toHaveLength(0);
      const [linkBefore] = await connection.query<Array<{ Field: string }>>(
        "SHOW COLUMNS FROM alexa_account_links WHERE Field = 'credentialVersion'"
      );
      expect(linkBefore).toHaveLength(0);
      await connection.query(
        "CREATE TABLE `__drizzle_migrations` (`id` serial PRIMARY KEY, `hash` text NOT NULL, `created_at` bigint)"
      );
      await connection.query(
        "INSERT INTO `__drizzle_migrations` (`hash`, `created_at`) VALUES ('historical-webdev-0011', 1788377861523)"
      );
      await migrate(drizzle(connection), { migrationsFolder: drizzleDir });
      const [after] = await connection.query<Array<{ Field: string }>>(
        "SHOW COLUMNS FROM oauth_authorization_codes WHERE Field = 'revokedAt'"
      );
      expect(after).toHaveLength(1);
      const [linkAfter] = await connection.query<Array<{ Field: string }>>(
        "SHOW COLUMNS FROM alexa_account_links WHERE Field = 'credentialVersion'"
      );
      expect(linkAfter).toHaveLength(1);
      const [preserved] = await connection.query<Array<{ stationName: string }>>(
        "SELECT stationName FROM station_preferences WHERE userId = ?",
        [historicalUserId]
      );
      expect(preserved).toEqual([{ stationName: "Roma Termini" }]);
      const [families] = await connection.query<Array<{ familyId: string }>>(
        "SELECT familyId FROM oauth_refresh_tokens WHERE userId = ? ORDER BY id",
        [historicalUserId]
      );
      expect(families).toHaveLength(2);
      expect(families[0]?.familyId).toMatch(/^[0-9a-f]{64}$/);
      expect(families[1]?.familyId).toMatch(/^[0-9a-f]{64}$/);
      // Pre-family rows may belong to different historical grants. There is no
      // reliable lineage to reconstruct, so migration must not let replay of an
      // old revoked grant revoke an unrelated currently-active grant.
      expect(families[1]?.familyId).not.toBe(families[0]?.familyId);
      // A revoked pre-migration grant must not share lineage with a different
      // active pre-migration grant for the same user/client.
      const [legacyRows] = await connection.query<
        Array<{ tokenHash: string; familyId: string }>
      >(
        "SELECT tokenHash, familyId FROM oauth_refresh_tokens WHERE userId = ? ORDER BY id",
        [historicalUserId]
      );
      expect(legacyRows[0]?.familyId).not.toBe(legacyRows[1]?.familyId);

      // Replaying a revoked legacy grant must not invalidate a different
      // active legacy grant that happened to use the same user/client.
      const legacyReplay = await rotateRefreshToken({
        currentTokenHash: "a".repeat(64),
        clientId: "legacy-client",
        nextTokenHash: "c".repeat(64),
        nextTokenExpiresAt: new Date(Date.now() + 60_000),
      });
      expect(legacyReplay).toBeNull();
      const [stillActiveLegacy] = await connection.query<Array<{ revokedAt: Date | null }>>(
        "SELECT revokedAt FROM oauth_refresh_tokens WHERE tokenHash = ?",
        ["b".repeat(64)]
      );
      expect(stillActiveLegacy[0]?.revokedAt).toBeNull();

      const [applied] = await connection.query<Array<{ createdAt: string }>>(
        "SELECT CAST(created_at AS CHAR) AS createdAt FROM __drizzle_migrations ORDER BY created_at"
      );
      expect(applied.map(row => row.createdAt)).toEqual([
        "1788377861523",
        "1790192049966",
        "1790208352672",
      ]);
    } finally {
      await connection.end();
    }
  });

  it("adotta anche la breve catena Alexa 0000→0003 già distribuita", async () => {
    const connection = await createConnection({
      ...rootOptions,
      database: shortLineageDatabase,
    });
    try {
      await applyMigrations(connection, 0, 2);
      await connection.query(
        "ALTER TABLE `alexa_account_links` ADD `credentialVersion` int DEFAULT 1 NOT NULL"
      );
      await connection.query(
        "ALTER TABLE `oauth_authorization_codes` ADD `revokedAt` timestamp NULL"
      );
      const [insert] = await connection.query(
        "INSERT INTO users (`openId`, `lastSignedIn`) VALUES ('short-lineage-user', CURRENT_TIMESTAMP)"
      );
      const userId = Number((insert as { insertId: number }).insertId);
      await connection.query(
        "INSERT INTO railway_routes (`userId`, `origin`, `destination`, `name`) VALUES (?, 'Roma', 'Milano', 'Preservata')",
        [userId]
      );
      await connection.query(
        "CREATE TABLE `__drizzle_migrations` (`id` serial PRIMARY KEY, `hash` text NOT NULL, `created_at` bigint)"
      );
      await connection.query(
        "INSERT INTO `__drizzle_migrations` (`hash`, `created_at`) VALUES ('short-alexa-0003', 1790180520608)"
      );

      await migrate(drizzle(connection), { migrationsFolder: drizzleDir });

      expect(await tableNames(connection)).toEqual(
        expect.arrayContaining([
          "companion_devices",
          "companion_pairing_codes",
          "station_preferences",
        ])
      );
      const [route] = await connection.query<Array<{ name: string }>>(
        "SELECT name FROM railway_routes WHERE userId = ?",
        [userId]
      );
      expect(route).toEqual([{ name: "Preservata" }]);
      const [columns] = await connection.query<Array<{ Field: string }>>(
        "SHOW COLUMNS FROM railway_routes"
      );
      expect(columns.map(column => column.Field)).toEqual(
        expect.arrayContaining([
          "originStationCode",
          "archivedAt",
          "travelProfile",
          "preDepartureChecklistJson",
        ])
      );
    } finally {
      await connection.end();
    }
  });

  it("verifica revoca OAuth, replay family e cancellazione account", async () => {
    const connection = await createConnection({
      ...rootOptions,
      database: oauthDatabase,
    });
    try {
      await applyMigrations(connection, 0, 13);
      const [insert] = await connection.query(
        "INSERT INTO users (`openId`, `lastSignedIn`) VALUES (?, CURRENT_TIMESTAMP)",
        ["oauth-revocation-user"]
      );
      const userId = Number((insert as { insertId: number }).insertId);
      const databaseUrl = new URL(originalDatabaseUrl);
      databaseUrl.pathname = `/${oauthDatabase}`;
      process.env.DATABASE_URL = databaseUrl.toString();

      await expect(touchExistingUserSession(userId)).resolves.toBe(true);
      await expect(touchExistingUserSession(userId)).resolves.toBe(true);

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

      const rotated = await rotateRefreshToken({
        currentTokenHash: "8".repeat(64),
        clientId: "alexa-client",
        nextTokenHash: "4".repeat(64),
        nextTokenExpiresAt: new Date(Date.now() + 60_000),
      });
      expect(rotated).toMatchObject({
        userId,
        credentialVersion: relinked?.credentialVersion,
      });
      await expect(
        rotateRefreshToken({
          currentTokenHash: "8".repeat(64),
          clientId: "alexa-client",
          nextTokenHash: "3".repeat(64),
          nextTokenExpiresAt: new Date(Date.now() + 60_000),
        })
      ).resolves.toBeNull();
      await expect(
        rotateRefreshToken({
          currentTokenHash: "4".repeat(64),
          clientId: "alexa-client",
          nextTokenHash: "2".repeat(64),
          nextTokenExpiresAt: new Date(Date.now() + 60_000),
        })
      ).resolves.toBeNull();
      await expect(
        listPersonalRoutesForAlexa(userId, relinked?.credentialVersion ?? 0)
      ).resolves.toEqual([]);

      await createAuthorizationCode({
        userId,
        codeHash: "1".repeat(64),
        clientId: "alexa-client",
        redirectUri: "https://layla.amazon.com/api/skill/link/TEST",
        scope: "routes.read",
        expiresAt: new Date(Date.now() + 60_000),
      });
      const recovered = await exchangeAuthorizationCode({
        codeHash: "1".repeat(64),
        clientId: "alexa-client",
        redirectUri: "https://layla.amazon.com/api/skill/link/TEST",
        refreshTokenHash: "2".repeat(64),
        refreshTokenExpiresAt: new Date(Date.now() + 60_000),
      });
      expect(recovered?.credentialVersion).toBeGreaterThan(
        relinked?.credentialVersion ?? 0
      );
      await expect(
        listPersonalRoutesForAlexa(userId, recovered?.credentialVersion ?? 0)
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

      await connection.query(
        "INSERT INTO station_preferences (`userId`, `stationName`, `stationCode`, `isFavorite`) VALUES (?, 'Roma Termini', 'RM', TRUE)",
        [userId]
      );
      await connection.query(
        "INSERT INTO binario_profiles (`userId`, `activeRouteId`, `direction`, `monitoredTrainNumbersJson`, `lastMutationId`) VALUES (?, 'route-erasure', 'OUTBOUND', '[]', '00000000-0000-0000-0000-000000000001')",
        [userId]
      );
      await connection.query(
        "INSERT INTO companion_devices (`userId`, `deviceName`, `platform`, `tokenHash`, `tokenPrefix`, `expiresAt`) VALUES (?, 'Telefono test', 'android', ?, 'device-prefix', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 DAY))",
        [userId, "device".repeat(16)]
      );
      await connection.query(
        "INSERT INTO companion_pairing_codes (`userId`, `codeHash`, `deviceName`, `platform`, `expiresAt`) VALUES (?, ?, 'Telefono test', 'android', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 10 MINUTE))",
        [userId, "pairing".repeat(16)]
      );

      await expect(deleteUserAccount(userId)).resolves.toBe(true);
      for (const table of [
        "users",
        "railway_routes",
        "station_preferences",
        "binario_profiles",
        "alexa_account_links",
        "oauth_authorization_codes",
        "oauth_refresh_tokens",
        "companion_devices",
        "companion_pairing_codes",
      ]) {
        const [remaining] = await connection.query<Array<{ count: number }>>(
          `SELECT COUNT(*) AS count FROM \`${table}\` WHERE ${table === "users" ? "id" : "userId"} = ?`,
          [userId]
        );
        expect(Number(remaining[0]?.count)).toBe(0);
      }
    } finally {
      const db = await getDb();
      await db?.$client.end();
      await connection.end();
    }
  });
});
