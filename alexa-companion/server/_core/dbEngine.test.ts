import { describe, expect, it } from "vitest";
import { databaseEngineProblem, timestampSemanticsProblem } from "./dbEngine";

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

  it("rifiuta un database già migrato con le date facoltative NOT NULL, anche se ora la variabile è ON", () => {
    expect(timestampSemanticsProblem(1, ["oauth_authorization_codes.consumedAt", "railway_routes.archivedAt"])).toMatch(
      /oauth_authorization_codes\.consumedAt, railway_routes\.archivedAt.*MODIFY/
    );
  });
});
