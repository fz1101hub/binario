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
