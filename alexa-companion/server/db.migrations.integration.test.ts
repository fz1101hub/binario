import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import { createConnection, type ConnectionOptions, type RowDataPacket } from "mysql2/promise";
import { drizzle } from "drizzle-orm/mysql2";
import { migrate } from "drizzle-orm/mysql2/migrator";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  createAuthorizationCode,
  createPersonalRoute,
  deleteUserAccount,
  exchangeAuthorizationCode,
  getAlexaLinkStatus,
  getDb,
  listPersonalRoutes,
  listPersonalRoutesForAlexa,
  purgeFinishedOAuthArtifacts,
  recordAlexaVerification,
  revokeAllVoiceCredentialsForUser,
  rotateRefreshToken,
  touchExistingUserSession,
} from "./db";
import { MAX_PERSONAL_ROUTES, RouteLimitError } from "./routes/contract";
import { checkTimestampSemantics, NULLABLE_TIMESTAMP_COLUMNS } from "./_core/dbEngine";

/** mysql2 types SELECT results as RowDataPacket rows. */
type Rows<T> = Array<T & RowDataPacket>;

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
  const [rows] = await connection.query<Rows<{ tableName: string }>>(
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
      expect(await checkTimestampSemantics(connection)).toBeNull();
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
      const [columns] = await connection.query<Rows<{ Field: string }>>(
        "SHOW COLUMNS FROM oauth_authorization_codes WHERE Field = 'revokedAt'"
      );
      expect(columns).toHaveLength(1);
      const [linkColumns] = await connection.query<Rows<{ Field: string }>>(
        "SHOW COLUMNS FROM alexa_account_links WHERE Field = 'credentialVersion'"
      );
      expect(linkColumns).toHaveLength(1);
      const [familyColumns] = await connection.query<Rows<{ Field: string }>>(
        "SHOW COLUMNS FROM oauth_refresh_tokens WHERE Field = 'familyId'"
      );
      expect(familyColumns).toHaveLength(1);
    } finally {
      await connection.end();
    }
  });

  it("rifiuta un server con explicit_defaults_for_timestamp=OFF e il database che vi è stato migrato", async () => {
    const database = `binario_migration_timestamps_${randomUUID().replaceAll("-", "")}`;
    await admin.query(`CREATE DATABASE \`${database}\``);
    const connection = await createConnection({ ...rootOptions, database });
    try {
      expect(await checkTimestampSemantics(connection)).toBeNull();
      await connection.query("SET SESSION explicit_defaults_for_timestamp = 0");
      await applyMigrations(connection, 0, 13);
      const problem = await checkTimestampSemantics(connection);
      expect(problem).toMatch(/explicit_defaults_for_timestamp = 0/);
      for (const [table, column] of NULLABLE_TIMESTAMP_COLUMNS) expect(problem).toContain(`${table}.${column}`);
      // What the check prevents: NULL becomes the current time.
      await connection.query("INSERT INTO users (`openId`) VALUES ('timestamps')");
      await connection.query("INSERT INTO railway_routes (`userId`, `origin`, `destination`, `name`, `archivedAt`) SELECT id, 'Roma', 'Milano', 'Casa', NULL FROM users");
      const [rows] = await connection.query<Rows<{ archivedAt: Date | null }>>("SELECT archivedAt FROM railway_routes");
      expect(rows[0].archivedAt).not.toBeNull();
      await connection.query("SET SESSION explicit_defaults_for_timestamp = 1");
      expect(await checkTimestampSemantics(connection)).toMatch(/NOT NULL.*railway_routes\.archivedAt/);
    } finally {
      await connection.end();
      await admin.query(`DROP DATABASE IF EXISTS \`${database}\``);
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
      const [before] = await connection.query<Rows<{ Field: string }>>(
        "SHOW COLUMNS FROM oauth_authorization_codes WHERE Field = 'revokedAt'"
      );
      expect(before).toHaveLength(0);
      const [linkBefore] = await connection.query<Rows<{ Field: string }>>(
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
      const [after] = await connection.query<Rows<{ Field: string }>>(
        "SHOW COLUMNS FROM oauth_authorization_codes WHERE Field = 'revokedAt'"
      );
      expect(after).toHaveLength(1);
      const [linkAfter] = await connection.query<Rows<{ Field: string }>>(
        "SHOW COLUMNS FROM alexa_account_links WHERE Field = 'credentialVersion'"
      );
      expect(linkAfter).toHaveLength(1);
      const [preserved] = await connection.query<Rows<{ stationName: string }>>(
        "SELECT stationName FROM station_preferences WHERE userId = ?",
        [historicalUserId]
      );
      expect(preserved).toEqual([{ stationName: "Roma Termini" }]);
      const [families] = await connection.query<
        Rows<{ tokenHash: string; familyId: string; isRevoked: number }>
      >(
        "SELECT tokenHash, familyId, revokedAt IS NOT NULL AS isRevoked FROM oauth_refresh_tokens WHERE userId = ? ORDER BY id",
        [historicalUserId]
      );
      expect(families).toHaveLength(2);
      expect(families[0]).toMatchObject({
        tokenHash: "a".repeat(64),
        isRevoked: 1,
      });
      expect(families[1]).toMatchObject({
        tokenHash: "b".repeat(64),
        isRevoked: 0,
      });
      expect(families[0]?.familyId).toMatch(/^[0-9a-f]{64}$/);
      expect(families[1]?.familyId).toMatch(/^[0-9a-f]{64}$/);
      // Pre-family rows may belong to different historical grants. There is no
      // reliable lineage to reconstruct, so migration must not let replay of an
      // old revoked grant revoke an unrelated currently-active grant.
      expect(families[1]?.familyId).not.toBe(families[0]?.familyId);
      const [applied] = await connection.query<Rows<{ createdAt: string }>>(
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
      const [route] = await connection.query<Rows<{ name: string }>>(
        "SELECT name FROM railway_routes WHERE userId = ?",
        [userId]
      );
      expect(route).toEqual([{ name: "Preservata" }]);
      const [columns] = await connection.query<Rows<{ Field: string }>>(
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
      await applyMigrations(connection, 0, 11);
      const [legacyInsert] = await connection.query(
        "INSERT INTO users (`openId`, `lastSignedIn`) VALUES (?, CURRENT_TIMESTAMP)",
        ["legacy-replay-user"]
      );
      const legacyUserId = Number(
        (legacyInsert as { insertId: number }).insertId
      );
      const revokedLegacyHash = "ab".repeat(32);
      const activeLegacyHash = "cd".repeat(32);
      await connection.query(
        "INSERT INTO oauth_refresh_tokens (`userId`, `tokenHash`, `clientId`, `expiresAt`, `revokedAt`) VALUES (?, ?, 'legacy-client', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 DAY), CURRENT_TIMESTAMP), (?, ?, 'legacy-client', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 DAY), NULL)",
        [legacyUserId, revokedLegacyHash, legacyUserId, activeLegacyHash]
      );
      await applyMigrations(connection, 12, 13);
      await connection.query(
        "INSERT INTO alexa_account_links (userId, linkedAt, lastVerifiedAt, credentialVersion, revokedAt) VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, NULL)",
        [legacyUserId]
      );
      const databaseUrl = new URL(originalDatabaseUrl);
      databaseUrl.pathname = `/${oauthDatabase}`;
      process.env.DATABASE_URL = databaseUrl.toString();

      await expect(
        rotateRefreshToken({
          currentTokenHash: revokedLegacyHash,
          clientId: "legacy-client",
          nextTokenHash: "ef".repeat(32),
          nextTokenExpiresAt: new Date(Date.now() + 60_000),
        })
      ).resolves.toBeNull();
      // Every connection of the service's pool runs in UTC, as drizzle writes and reads its dates.
      const service = await getDb();
      const [zone] = (await service!.$client.promise().query("SELECT @@session.time_zone AS zone")) as unknown as [Array<{ zone: string }>];
      expect(zone[0]?.zone).toBe("+00:00");
      const [legacyAfterReplay] = await connection.query<
        Rows<{ revokedAt: Date | null; credentialVersion: number }>
      >(
        "SELECT token.revokedAt, link.credentialVersion FROM oauth_refresh_tokens token JOIN alexa_account_links link ON link.userId = token.userId WHERE token.tokenHash = ?",
        [activeLegacyHash]
      );
      expect(legacyAfterReplay).toHaveLength(1);
      expect(legacyAfterReplay[0]?.revokedAt).toBeNull();
      expect(legacyAfterReplay[0]?.credentialVersion).toBe(1);

      const [insert] = await connection.query(
        "INSERT INTO users (`openId`, `lastSignedIn`) VALUES (?, CURRENT_TIMESTAMP)",
        ["oauth-revocation-user"]
      );
      const userId = Number((insert as { insertId: number }).insertId);

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
      // The link binds to the first Alexa account that uses it and refuses another one.
      await expect(recordAlexaVerification(userId, "1".repeat(64))).resolves.toBe(true);
      await expect(recordAlexaVerification(userId, "1".repeat(64))).resolves.toBe(true);
      await expect(recordAlexaVerification(userId, "2".repeat(64))).resolves.toBe(false);
      expect((await getAlexaLinkStatus(userId))?.alexaUserHash).toBe("1".repeat(64));
      // The historical WebDev columns may still hold personal free text in an
      // adopted database: the dashboard never receives them.
      await connection.query(
        "INSERT INTO railway_routes (`userId`, `origin`, `destination`, `name`, `travelDate`, `travelProfile`, `personalNote`, `accessibilityNote`, `personalTagsJson`) VALUES (?, 'Roma', 'Milano', 'Casa — lavoro', '2026-01-02', 'accessibility', 'nota privata', 'sedia a rotelle', '[\"lavoro\"]')",
        [userId]
      );
      // A route archived in the historical WebDev dashboard must stay hidden
      // from both the dashboard and Alexa after the lineage is adopted.
      await connection.query(
        "INSERT INTO railway_routes (`userId`, `origin`, `destination`, `name`, `isPinned`, `archivedAt`) VALUES (?, 'Roma', 'Napoli', 'Archiviata', 1, CURRENT_TIMESTAMP)",
        [userId]
      );
      await expect(
        listPersonalRoutesForAlexa(userId, initialLink?.credentialVersion ?? 0)
      ).resolves.toEqual([expect.objectContaining({ name: "Casa — lavoro" })]);
      const listed = await listPersonalRoutes(userId);
      expect(listed).toEqual([expect.objectContaining({ name: "Casa — lavoro", origin: "Roma", destination: "Milano" })]);
      expect(Object.keys(listed[0]).sort()).toEqual(["createdAt", "destination", "id", "isPinned", "name", "origin", "updatedAt"]);
      expect(JSON.stringify(listed)).not.toMatch(/nota privata|sedia a rotelle|2026-01-02|accessibility/);

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
      // A new link clears the binding: the next Alexa account (a re-enabled skill) is recorded.
      expect((await getAlexaLinkStatus(userId))?.alexaUserHash).toBeNull();
      await expect(recordAlexaVerification(userId, "2".repeat(64))).resolves.toBe(true);
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
      const rotate = (current: string, next: string) =>
        rotateRefreshToken({
          currentTokenHash: current,
          clientId: "alexa-client",
          nextTokenHash: next,
          nextTokenExpiresAt: new Date(Date.now() + 60_000),
        });
      const liveTokenCount = async () => {
        const [rows] = await connection.query<Rows<{ count: number }>>(
          "SELECT COUNT(*) AS count FROM oauth_refresh_tokens WHERE userId = ? AND revokedAt IS NULL AND expiresAt > UTC_TIMESTAMP()",
          [userId]
        );
        return Number(rows[0]?.count);
      };
      // Lost response: Amazon never received "4" and retries with "8". Inside the grace
      // window that is a retry: a fresh token of the same family, nothing revoked.
      await expect(rotate("8".repeat(64), "3".repeat(64))).resolves.toMatchObject({
        userId,
        credentialVersion: relinked?.credentialVersion,
      });
      await expect(
        listPersonalRoutesForAlexa(userId, relinked?.credentialVersion ?? 0)
      ).resolves.toHaveLength(1);
      expect(await liveTokenCount()).toBe(2);
      // Two concurrent refreshes with the same token both succeed: the second waits on the
      // link row lock, then finds the token just rotated by the first.
      const [first, second] = await Promise.all([
        rotate("3".repeat(64), "a1".repeat(32)),
        rotate("3".repeat(64), "b2".repeat(32)),
      ]);
      expect(first).toMatchObject({ credentialVersion: relinked?.credentialVersion });
      expect(second).toMatchObject({ credentialVersion: relinked?.credentialVersion });
      // The client keeps one answer; its next regular rotation expires the siblings it dropped,
      // which then fail as invalid_grant without revoking the family.
      const [kept, dropped] = ["a1".repeat(32), "b2".repeat(32)];
      await expect(rotate(kept, "c3".repeat(32))).resolves.not.toBeNull();
      expect(await liveTokenCount()).toBe(1);
      await expect(rotate(dropped, "d4".repeat(32))).resolves.toBeNull();
      await expect(rotate("4".repeat(64), "d4".repeat(32))).resolves.toBeNull();
      await expect(
        listPersonalRoutesForAlexa(userId, relinked?.credentialVersion ?? 0)
      ).resolves.toHaveLength(1);
      // Outside the grace window a replay is still theft: the family and the voice credential
      // generation are revoked. Move the rotation of `kept` two minutes into the past.
      await connection.query(
        "UPDATE oauth_refresh_tokens SET revokedAt = revokedAt - INTERVAL 2 MINUTE WHERE tokenHash = ?",
        [kept]
      );
      await connection.query(
        "UPDATE oauth_refresh_tokens SET createdAt = createdAt - INTERVAL 2 MINUTE WHERE tokenHash = ?",
        ["c3".repeat(32)]
      );
      await expect(rotate(kept, "e5".repeat(32))).resolves.toBeNull();
      expect(await liveTokenCount()).toBe(0);
      // The link is dead until the user links again: the dashboard must not say «linked».
      expect((await getAlexaLinkStatus(userId))?.revokedAt).toBeInstanceOf(Date);
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
      expect((await getAlexaLinkStatus(userId))?.revokedAt).toBeNull();
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
      const [activeRefresh] = await connection.query<Rows<{ count: number }>>(
        "SELECT COUNT(*) AS count FROM oauth_refresh_tokens WHERE userId = ? AND revokedAt IS NULL",
        [userId]
      );
      expect(Number(activeRefresh[0]?.count)).toBe(0);
      const [links] = await connection.query<Rows<{ revokedAt: Date | null }>>(
        "SELECT revokedAt FROM alexa_account_links WHERE userId = ?",
        [userId]
      );
      expect(links[0]?.revokedAt).not.toBeNull();

      // Finished OAuth artefacts are deleted 30 days after they expired or were revoked, not before.
      await connection.query(
        "INSERT INTO oauth_refresh_tokens (`userId`, `clientId`, `tokenHash`, `familyId`, `expiresAt`, `revokedAt`) VALUES (?, 'alexa-client', ?, 'old-family', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 300 DAY), DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 31 DAY))",
        [userId, "9".repeat(64)]
      );
      await connection.query(
        "INSERT INTO oauth_authorization_codes (`userId`, `codeHash`, `clientId`, `redirectUri`, `scope`, `expiresAt`) VALUES (?, ?, 'alexa-client', 'https://layla.amazon.com/api/skill/link/TEST', 'routes.read', DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 31 DAY))",
        [userId, "8".repeat(64)]
      );
      const [beforePurge] = await connection.query<Rows<{ count: number }>>(
        "SELECT COUNT(*) AS count FROM oauth_refresh_tokens WHERE userId = ?",
        [userId]
      );
      await purgeFinishedOAuthArtifacts();
      const [oldRows] = await connection.query<Rows<{ count: number }>>(
        "SELECT (SELECT COUNT(*) FROM oauth_refresh_tokens WHERE tokenHash = ?) + (SELECT COUNT(*) FROM oauth_authorization_codes WHERE codeHash = ?) AS count",
        ["9".repeat(64), "8".repeat(64)]
      );
      expect(Number(oldRows[0]?.count)).toBe(0);
      const [afterPurge] = await connection.query<Rows<{ count: number }>>(
        "SELECT COUNT(*) AS count FROM oauth_refresh_tokens WHERE userId = ?",
        [userId]
      );
      expect(Number(afterPurge[0]?.count)).toBe(Number(beforePurge[0]?.count) - 1);

      // At most MAX_PERSONAL_ROUTES routes per account.
      for (let saved = (await listPersonalRoutes(userId)).length; saved < MAX_PERSONAL_ROUTES; saved++)
        await createPersonalRoute(userId, { origin: "Roma Termini", destination: `Tivoli ${saved}`, name: `Tratta ${saved}` });
      await expect(createPersonalRoute(userId, { origin: "Roma Termini", destination: "Ostia", name: "Una di troppo" })).rejects.toBeInstanceOf(
        RouteLimitError
      );
      expect(await listPersonalRoutes(userId)).toHaveLength(MAX_PERSONAL_ROUTES);

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
        const [remaining] = await connection.query<Rows<{ count: number }>>(
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
