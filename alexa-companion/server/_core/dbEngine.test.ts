import { describe, expect, it } from "vitest";
import { databaseEngineProblem, startupTimestampProblem, timestampSemanticsProblem } from "./dbEngine";

describe("motore database per le migrazioni", () => {
  it.each(["11.4.2-MariaDB-ubu2404", "10.11.14-MariaDB-0ubuntu0.24.04.1", "8.0.11-TiDB-v7.5.1"])("accetta %s", version => {
    expect(databaseEngineProblem(version)).toBeNull();
  });

  it.each(["8.0.36", "8.4.0", "8.0.32-google"])("rifiuta MySQL %s prima di una migrazione che fallirebbe a metà", version => {
    expect(databaseEngineProblem(version)).toMatch(/MariaDB.*TiDB.*MySQL 8/);
  });

  it.each([1, "1", "ON", "on"])("accetta explicit_defaults_for_timestamp = %s con le date facoltative che ammettono NULL", value => {
    expect(timestampSemanticsProblem(value, [])).toBeNull();
  });

  it.each([0, "0", "OFF", null, undefined])("rifiuta explicit_defaults_for_timestamp = %s, predefinito fino a MariaDB 10.9", value => {
    expect(timestampSemanticsProblem(value, [])).toMatch(/explicit_defaults_for_timestamp.*NOT NULL.*ON/);
  });

  /** A connection answering the two queries of the check. */
  const database = (explicitDefaults: unknown, notNull: Array<[string, string]>) => ({
    query: async (sql: string) =>
      sql.includes("@@explicit_defaults_for_timestamp")
        ? [[{ explicitDefaults }]]
        : [notNull.map(([tableName, columnName]) => ({ tableName, columnName }))],
  });

  it("all'avvio del server avvisa soltanto per la variabile spenta se le date facoltative ammettono già NULL", async () => {
    const warnings: string[] = [];
    expect(await startupTimestampProblem(database("OFF", []), message => warnings.push(message))).toBeNull();
    expect(warnings).toEqual([expect.stringMatching(/^\[Database\] @@explicit_defaults_for_timestamp = OFF/)]);
    // Unrelated NOT NULL timestamps (createdAt) are not the nullable dates of the migrations.
    expect(await startupTimestampProblem(database(1, [["railway_routes", "createdAt"]]), message => warnings.push(message))).toBeNull();
    expect(warnings).toHaveLength(1);
  });

  it("all'avvio del server rifiuta le date facoltative NOT NULL, con la variabile accesa o spenta", async () => {
    const warnings: string[] = [];
    expect(await startupTimestampProblem(database(1, [["railway_routes", "archivedAt"]]), message => warnings.push(message))).toMatch(
      /railway_routes\.archivedAt.*MODIFY/
    );
    expect(warnings).toEqual([]);
    expect(await startupTimestampProblem(database(0, [["oauth_refresh_tokens", "revokedAt"]]), message => warnings.push(message))).toMatch(
      /oauth_refresh_tokens\.revokedAt/
    );
    expect(warnings).toHaveLength(1);
  });

  it("rifiuta un database già migrato con le date facoltative NOT NULL, anche se ora la variabile è ON", () => {
    expect(timestampSemanticsProblem(1, ["oauth_authorization_codes.consumedAt", "railway_routes.archivedAt"])).toMatch(
      /oauth_authorization_codes\.consumedAt, railway_routes\.archivedAt.*MODIFY/
    );
  });
});
