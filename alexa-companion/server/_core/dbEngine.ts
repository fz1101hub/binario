/**
 * The migration chain (drizzle/0007, 0008, 0009, 0012) uses `ADD COLUMN IF NOT EXISTS` and
 * `CREATE INDEX IF NOT EXISTS`, which MariaDB and TiDB accept and MySQL 8 rejects. On MySQL a
 * fresh migration stops half-way through 0007 (DDL auto-commits, the journal row is never
 * written) and needs manual repair, so `pnpm db:migrate` refuses that engine up front.
 * Already shipped migrations are not rewritten: databases provisioned from them depend on
 * their exact history.
 */
export function databaseEngineProblem(version: string): string | null {
  if (/mariadb/i.test(version) || /tidb/i.test(version)) return null;
  return (
    `Motore database non supportato dalle migrazioni (VERSION() = "${version}"). ` +
    "Le migrazioni 0007, 0008, 0009 e 0012 usano ADD COLUMN IF NOT EXISTS e CREATE INDEX IF NOT EXISTS, " +
    "accettati da MariaDB e TiDB ma non da MySQL 8: usare MariaDB (verificata in CI) o TiDB. " +
    "Vedi docs/DATABASE_MIGRATIONS.md."
  );
}

/**
 * Nullable timestamps that shipped migrations declare as a bare `timestamp` (0001, 0004, 0006,
 * 0007). With `explicit_defaults_for_timestamp` OFF (the default up to MariaDB 10.9, still settable
 * later) MariaDB makes them `NOT NULL DEFAULT '0000-00-00 00:00:00'` and stores the current time
 * when NULL is assigned: every route looks archived, every authorization code consumed and a
 * re-link revoked at once. Shipped migrations are not rewritten (see above), so the service
 * refuses such a server, and a database already migrated on one, with a clear error.
 */
export const NULLABLE_TIMESTAMP_COLUMNS = [
  ["alexa_account_links", "lastVerifiedAt"],
  ["alexa_account_links", "revokedAt"],
  ["companion_devices", "lastUsedAt"],
  ["companion_devices", "revokedAt"],
  ["companion_pairing_codes", "consumedAt"],
  ["oauth_authorization_codes", "consumedAt"],
  ["oauth_refresh_tokens", "revokedAt"],
  ["railway_routes", "archivedAt"],
  ["station_preferences", "lastOpenedAt"],
] as const;

/**
 * The problem of `@@explicit_defaults_for_timestamp` (1, "1" or "ON" is right), or null. The variable
 * decides how a migration creates a bare `timestamp` column; a column already declared NULL keeps
 * NULL whatever its value, so it matters to `db:migrate`, not to a server on migrated tables.
 */
export function explicitDefaultsProblem(explicitDefaults: unknown): string | null {
  if (explicitDefaults === 1 || explicitDefaults === "1" || String(explicitDefaults).toUpperCase() === "ON") return null;
  return (
    `@@explicit_defaults_for_timestamp = ${String(explicitDefaults)}: le migrazioni creerebbero le date facoltative come NOT NULL ` +
    "e il server salverebbe l'ora corrente al posto di NULL. Impostare explicit_defaults_for_timestamp=ON nella configurazione " +
    "del server (predefinito da MariaDB 10.10) prima di migrare."
  );
}

/** The problem of the {@link NULLABLE_TIMESTAMP_COLUMNS} the database has as NOT NULL («table.column»), or null. */
export function notNullColumnsProblem(notNullColumns: readonly string[]): string | null {
  if (!notNullColumns.length) return null;
  return (
    `Colonne che devono ammettere NULL ma sono NOT NULL (database migrato con explicit_defaults_for_timestamp=OFF): ${notNullColumns.join(", ")}. ` +
    "Ripararle a mano con ALTER TABLE … MODIFY <colonna> timestamp NULL DEFAULT NULL e riportare a NULL i valori senza significato, " +
    "come descritto in docs/DATABASE_MIGRATIONS.md."
  );
}

/**
 * Null when timestamps behave as the migrations expect: `explicitDefaults` is the value of
 * `@@explicit_defaults_for_timestamp` ({@link explicitDefaultsProblem}) and `notNullColumns`
 * («table.column») lists the {@link NULLABLE_TIMESTAMP_COLUMNS} the database already has as NOT NULL.
 */
export function timestampSemanticsProblem(explicitDefaults: unknown, notNullColumns: readonly string[]): string | null {
  const problems = [explicitDefaultsProblem(explicitDefaults), notNullColumnsProblem(notNullColumns)].filter(problem => problem !== null);
  return problems.length ? problems.join(" ") : null;
}

type Queryable = { query: (sql: string) => Promise<unknown> };

/** The two halves of {@link timestampSemanticsProblem} read from the database: the variable and the columns. */
export async function readTimestampSemantics(connection: Queryable): Promise<{ variable: string | null; columns: string | null }> {
  const [variables] = (await connection.query("SELECT @@explicit_defaults_for_timestamp AS explicitDefaults")) as [Array<{ explicitDefaults?: unknown }>];
  const [columns] = (await connection.query(
    "SELECT TABLE_NAME AS tableName, COLUMN_NAME AS columnName FROM information_schema.COLUMNS " +
      "WHERE TABLE_SCHEMA = DATABASE() AND DATA_TYPE = 'timestamp' AND IS_NULLABLE = 'NO'"
  )) as [Array<{ tableName: string; columnName: string }>];
  const expected = new Set(NULLABLE_TIMESTAMP_COLUMNS.map(([table, column]) => `${table}.${column}`));
  const notNull = columns.map(row => `${row.tableName}.${row.columnName}`).filter(name => expected.has(name)).sort();
  return { variable: explicitDefaultsProblem(variables[0]?.explicitDefaults), columns: notNullColumnsProblem(notNull) };
}

/** Reads the variable and the columns of {@link NULLABLE_TIMESTAMP_COLUMNS}: both must be right before a migration. */
export async function checkTimestampSemantics(connection: Queryable): Promise<string | null> {
  const { variable, columns } = await readTimestampSemantics(connection);
  const problems = [variable, columns].filter(problem => problem !== null);
  return problems.length ? problems.join(" ") : null;
}

/**
 * What the server does at start with {@link readTimestampSemantics}: it refuses NOT NULL columns,
 * which break linking and routes now, and only warns about the variable, which breaks the next
 * migration (refused by `db:migrate`) but nothing on tables already migrated with nullable dates.
 */
export async function startupTimestampProblem(connection: Queryable, warn: (message: string) => void = console.warn): Promise<string | null> {
  const { variable, columns } = await readTimestampSemantics(connection);
  if (variable) warn(`[Database] ${variable}`);
  return columns;
}
